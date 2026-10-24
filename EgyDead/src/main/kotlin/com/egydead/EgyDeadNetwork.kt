package com.egydead

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Live-verified zero-timeout transport, DoH DNS, and domain-discovery engine for EgyDead v19.
 *
 * Transport Pipeline (budgeted so CloudStream's 120s timeout is NEVER reached):
 * - Tier 1A: Persistent WebView `window.fetch()` fast-path (`~375-670ms`, same BoringSSL TLS +
 *   `CookieManager` `cf_clearance`) once Turnstile has been solved in this session.
 * - Tier 1B: Fast direct `siteClient` (`4s` connect / `6s` read + Cloudflare/Google DoH) with
 *   persisted `cf_clearance` cookies & consistent native browser headers.
 * - Tier 2: Single-flight `CloudflareSolver` (`cfMutex` + `cfSolveEpoch` deduplication):
 *   - 2A: Silent `(0, 0)` software-rendered WebView with `dir`-aware Turnstile checkbox auto-tap (`22s` max, solves live in `~5-14s`).
 *   - 2B: Rate-limited 1-tap interactive Turnstile fallback dialog (`45s` max, `60s` cooldown).
 * - Tier 3 (Headless / no-Activity fallback only): Sticky proxy session pool (`4s` connect, single batch) +
 *   15s negative failure cooldown so queued shelves fail fast instead of hanging.
 */
object EgyDeadNetwork {
    private const val TAG = "EgyDeadNet"

    const val DEFAULT_BASE_URL = "https://tv10.egydead.live"
    const val HOME_LANDING_PATH = "/h3/"

    private val REDIRECT_BEACONS = listOf(
        "https://egydead.live",
        "https://egydead.fyi"
    )

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val HOME_DOC_TTL_MS = 90_000L
    private const val FAILURE_COOLDOWN_MS = 15_000L

    private val PROXY_LIST_SOURCES = listOf(
        "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "socks5" to "https://raw.githubusercontent.com/Roosterkid/openproxylist/main/SOCKS5_RAW.txt",
        "socks5" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt",
        "https" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt",
        "https" to "https://raw.githubusercontent.com/Roosterkid/openproxylist/main/HTTPS_RAW.txt"
    )

    @Volatile
    private var activeBaseUrl: String? = null

    @Volatile
    private var preferWebViewTransport: Boolean = false

    @Volatile
    private var lastAllTiersFailedAtMs: Long = 0L

    private val cfSolveEpoch = AtomicLong(0L)
    private val cfMutex = Mutex()
    private val beaconMutex = Mutex()
    private val homeDocMutex = Mutex()
    private val proxyHarvestMutex = Mutex()

    @Volatile
    private var cachedHomeDoc: Document? = null

    @Volatile
    private var cachedHomeDocAtMs: Long = 0L

    val currentUserAgent: String
        get() = EgyDeadPrefs.savedUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT

    data class RawResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val headers: Headers
    )

    // =========================================================================
    // Sticky Cookie Jar (Bridges OkHttp, SharedPreferences & Android WebView)
    // =========================================================================

    object StickyCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val key = baseHostKey(url.host)
            val hostMap = store.getOrPut(key) { ConcurrentHashMap() }
            for (c in cookies) {
                hostMap[c.name] = c.value
                if (c.name == "cf_clearance" && c.value.isNotBlank()) {
                    EgyDeadPrefs.cfClearance = c.value
                }
            }
            if (key == "egydead" && hostMap.isNotEmpty()) {
                EgyDeadPrefs.savedCookieHeader = hostMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val key = baseHostKey(url.host)
            val merged = LinkedHashMap<String, String>()
            if (key == "egydead") {
                EgyDeadPrefs.savedCookieHeader?.let { hdr ->
                    CloudflareSolver.parseCookieHeader(hdr).forEach { (k, v) -> merged[k] = v }
                }
                EgyDeadPrefs.cfClearance?.takeIf { it.isNotBlank() }?.let { merged["cf_clearance"] = it }
                CloudflareSolver.readCookiesFromWebView(url.toString()).forEach { (k, v) -> merged[k] = v }
            }
            store[key]?.forEach { (k, v) -> merged[k] = v }
            return merged.mapNotNull { (name, value) ->
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(url.host)
                        .path("/")
                        .build()
                }.getOrNull()
            }
        }

        fun importCookies(urlOrHost: String, cookies: Map<String, String>) {
            if (cookies.isEmpty()) return
            val host = runCatching {
                if (urlOrHost.contains("://")) URI(encodeUrl(urlOrHost)).host ?: urlOrHost else urlOrHost
            }.getOrDefault(urlOrHost)
            val key = baseHostKey(host)
            val hostMap = store.getOrPut(key) { ConcurrentHashMap() }
            for ((k, v) in cookies) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    hostMap[k] = v
                    if (k == "cf_clearance") EgyDeadPrefs.cfClearance = v
                }
            }
            if (key == "egydead" && hostMap.isNotEmpty()) {
                EgyDeadPrefs.savedCookieHeader = hostMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }

        fun clear() {
            store.clear()
        }

        private fun baseHostKey(host: String): String {
            val lower = host.lowercase()
            return if (lower.contains("egydead")) "egydead" else lower
        }
    }

    // =========================================================================
    // DNS-over-HTTPS (Cloudflare + Google fallback when system DNS is ISP-blocked)
    // =========================================================================

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val cloudflareDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
            .build()
    }

    private val googleDoh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://8.8.8.8/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4")
            )
            .build()
    }

    private object SmartDohDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
                ?.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            runCatching { cloudflareDoh.lookup(hostname) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            runCatching { googleDoh.lookup(hostname) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            throw UnknownHostException("Unable to resolve $hostname via System DNS or DoH")
        }
    }

    // =========================================================================
    // OkHttp Clients (Extractor Client + Fast Site Client + Sticky Proxy Pool)
    // =========================================================================

    private val permissiveTrustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val permissiveSslSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(permissiveTrustManager), SecureRandom())
    }.socketFactory

    @Volatile
    private var cachedClientKey: String? = null

    @Volatile
    private var cachedClient: OkHttpClient? = null

    @Volatile
    private var cachedSiteClient: OkHttpClient? = null

    private fun parseCustomProxy(spec: String?): Proxy? {
        if (spec.isNullOrBlank()) return null
        return runCatching {
            val trimmed = spec.trim()
            val isSocks = trimmed.startsWith("socks", ignoreCase = true)
            val withoutScheme = trimmed
                .replace(Regex("^[a-zA-Z0-9]+://"), "")
                .substringAfterLast('@')
            val host = withoutScheme.substringBeforeLast(':').trim()
            val port = withoutScheme.substringAfterLast(':').trim().toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            val addr = if (isSocks) {
                InetSocketAddress(InetAddress.getByName(host), port)
            } else {
                InetSocketAddress(host, port)
            }
            Proxy(if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP, addr)
        }.getOrNull()
    }

    val httpClient: OkHttpClient
        get() {
            val useProxy = EgyDeadPrefs.useProxy
            val proxySpec = if (useProxy) EgyDeadPrefs.customProxy.orEmpty() else ""
            val key = "$useProxy|$proxySpec"
            cachedClient?.let { if (cachedClientKey == key) return it }

            synchronized(this) {
                cachedClient?.let { if (cachedClientKey == key) return it }
                val builder = OkHttpClient.Builder()
                    .cookieJar(StickyCookieJar)
                    .dns(SmartDohDns)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(18, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .sslSocketFactory(permissiveSslSocketFactory, permissiveTrustManager)
                    .hostnameVerifier { _, _ -> true }

                if (useProxy) {
                    parseCustomProxy(proxySpec)?.let { builder.proxy(it) }
                }

                val built = builder.build()
                cachedClient = built
                cachedSiteClient = built.newBuilder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS)
                    .build()
                cachedClientKey = key
                return built
            }
        }

    private val siteClient: OkHttpClient
        get() {
            httpClient
            return cachedSiteClient ?: httpClient
        }

    private val sharedProxyPool = ConnectionPool(16, 2, TimeUnit.MINUTES)
    private val workingProxies = CopyOnWriteArrayList<String>()
    private val deadProxies = ConcurrentHashMap.newKeySet<String>()
    private val proxySessions = ConcurrentHashMap<String, ProxySession>()

    @Volatile
    private var savedProxiesSeeded = false

    private class ProxySession(val spec: String) {
        val client: OkHttpClient = OkHttpClient.Builder()
            .proxy(buildProxy(spec))
            .connectionPool(sharedProxyPool)
            .cookieJar(StickyCookieJar)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .sslSocketFactory(permissiveSslSocketFactory, permissiveTrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()

        private fun buildProxy(spec: String): Proxy {
            val scheme = spec.substringBefore("://").lowercase()
            val hostPort = spec.substringAfter("://")
            val host = hostPort.substringBeforeLast(':').trim()
            val port = hostPort.substringAfterLast(':').toIntOrNull()
                ?: error("Invalid port in $spec")
            require(port in 1..65535)
            val addr = if (scheme == "socks5") {
                InetSocketAddress(InetAddress.getByName(host), port)
            } else {
                InetSocketAddress(host, port)
            }
            return Proxy(if (scheme == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, addr)
        }
    }

    // =========================================================================
    // URL Normalization, Validation & Domain Management
    // =========================================================================

    fun invalidateDomainCache() {
        activeBaseUrl = null
        preferWebViewTransport = false
        lastAllTiersFailedAtMs = 0L
        cachedHomeDoc = null
        cachedHomeDocAtMs = 0L
        cachedClientKey = null
        cachedClient = null
        cachedSiteClient = null
        workingProxies.clear()
        deadProxies.clear()
        proxySessions.clear()
        savedProxiesSeeded = false
        StickyCookieJar.clear()
        CloudflareSolver.resetPersistentWebView()
    }

    fun activeTransportSummary(): String =
        "base=${getBaseUrl()}, preferWebView=$preferWebViewTransport, testBridge=${CloudflareSolver.jvmTestFetchBridge != null}, workingProxies=${workingProxies.take(3)}"

    fun getImageHeaders(): Map<String, String> {
        val base = getBaseUrl()
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = currentUserAgent
        map["Referer"] = "$base/"
        buildCookieHeader(base)?.let { map["Cookie"] = it }
        return map
    }

    /**
     * Safely percent-encodes raw UTF-8 characters (Arabic slugs, spaces) while preserving
     * already-encoded `%XX` sequences so URLs are never double-encoded.
     */
    fun encodeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        return try {
            val fixed = StringBuilder(trimmed.length + 16)
            var i = 0
            while (i < trimmed.length) {
                val ch = trimmed[i]
                when {
                    ch == '%' && i + 2 < trimmed.length &&
                        trimmed[i + 1].isHexDigit() && trimmed[i + 2].isHexDigit() -> {
                        fixed.append(trimmed, i, i + 3)
                        i += 3
                    }
                    ch == ' ' -> {
                        fixed.append("%20")
                        i++
                    }
                    ch.code > 127 || ch in "\"<>\\^`{|}" -> {
                        val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                        for (b in bytes) {
                            fixed.append(String.format("%%%02X", b.toInt() and 0xFF))
                        }
                        i++
                    }
                    else -> {
                        fixed.append(ch)
                        i++
                    }
                }
            }
            fixed.toString()
        } catch (_: Throwable) {
            trimmed
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun originOf(url: String): String? = runCatching {
        val parsed = URI(encodeUrl(url))
        val scheme = parsed.scheme ?: return null
        val host = parsed.host ?: return null
        val port = if (parsed.port != -1 && parsed.port != 80 && parsed.port != 443) ":${parsed.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    fun upgradeLegacyPath(url: String): String {
        return url
            .replace(Regex("(?i)(https?://[^/]+)/episode/"), "$1/episodes/")
            .replace(Regex("(?i)(https?://[^/]+)/season/"), "$1/seasons/")
            .replace(Regex("(?i)(https?://[^/]+)/serie/"), "$1/Series/")
            .replace(Regex("(?i)(https?://[^/]+)/assembly/"), "$1/film-series/")
    }

    fun rewriteToActiveBase(url: String): String {
        val base = getBaseUrl().trimEnd('/')
        val trimmed = url.trim()
        if (trimmed.startsWith("/")) {
            return encodeUrl(upgradeLegacyPath("$base$trimmed"))
        }
        val upgraded = upgradeLegacyPath(encodeUrl(trimmed))
        val parsed = runCatching { URI(upgraded) }.getOrNull() ?: return upgraded
        val host = parsed.host?.lowercase() ?: return upgraded
        return if (host.contains("egydead.")) {
            val rawPath = parsed.rawPath?.ifEmpty { "/" } ?: "/"
            val rawQuery = parsed.rawQuery?.let { "?$it" }.orEmpty()
            "$base$rawPath$rawQuery"
        } else {
            upgraded
        }
    }

    fun getBaseUrl(): String {
        EgyDeadPrefs.customDomain?.let { return it }
        activeBaseUrl?.let { return it }
        val initial = EgyDeadPrefs.discoveredDomain ?: DEFAULT_BASE_URL
        activeBaseUrl = initial
        return initial
    }

    private fun updateActiveDomainFromUrl(landedUrl: String) {
        if (!EgyDeadPrefs.customDomain.isNullOrBlank()) return
        val origin = originOf(landedUrl) ?: return
        val host = origin.substringAfter("://").lowercase()
        if (host.contains("egydead.") && host != "egydead.live" && host != "www.egydead.live" &&
            host != "egydead.fyi" && host != "www.egydead.fyi"
        ) {
            if (activeBaseUrl != origin) {
                activeBaseUrl = origin
                EgyDeadPrefs.discoveredDomain = origin
            }
        }
    }

    private suspend fun discoverDomainFromBeacons(): String? = beaconMutex.withLock {
        for (beacon in REDIRECT_BEACONS) {
            val probe = executeRawGet(beacon, referer = "$beacon/", client = siteClient) ?: continue
            val landedOrigin = originOf(probe.finalUrl) ?: continue
            val host = landedOrigin.substringAfter("://").lowercase()
            if (host.contains("egydead.") && host != "egydead.live" && host != "egydead.fyi") {
                activeBaseUrl = landedOrigin
                EgyDeadPrefs.discoveredDomain = landedOrigin
                return@withLock landedOrigin
            }
        }
        null
    }

    // =========================================================================
    // Cloudflare & EgyDead HTML Validation
    // =========================================================================

    fun isCloudflareBlock(code: Int, body: String): Boolean {
        val hasPositiveDom = body.contains("movieItem") ||
            body.contains("singleTitle") ||
            body.contains("serversList") ||
            body.contains("ServersList") ||
            body.contains("EpsList")
        if (code in 200..299 && hasPositiveDom) return false
        if (code == 403 || code == 503 || code == 429) {
            if (body.contains("Just a moment", ignoreCase = true) ||
                body.contains("لحظة") ||
                body.contains("Attention Required", ignoreCase = true) ||
                body.contains("cf-browser-verification", ignoreCase = true) ||
                body.contains("challenges.cloudflare.com", ignoreCase = true) ||
                body.contains("_cf_chl_", ignoreCase = true) ||
                body.contains("cloudflare", ignoreCase = true) ||
                body.length < 12_000
            ) {
                return true
            }
        }
        return !hasPositiveDom && (
            body.contains("<title>Just a moment", ignoreCase = true) ||
                body.contains("<title>لحظة") ||
                (body.contains("cf-turnstile", ignoreCase = true) && body.contains("_cf_chl_", ignoreCase = true))
            )
    }

    fun isValidEgyDeadHtml(code: Int, body: String): Boolean {
        if (code !in 200..299 && code != 404) return false
        if (body.isBlank() || isCloudflareBlock(code, body)) return false
        val hasBrand = body.contains("ايجي ديد") || body.contains("egydead", ignoreCase = true)
        val hasDom = body.contains("movieItem") ||
            body.contains("BottomTitle") ||
            body.contains("serversList") ||
            body.contains("ServersList") ||
            body.contains("singleTitle") ||
            body.contains("EpsList") ||
            body.contains("posts-list") ||
            body.contains("MasterSingleMeta")
        return hasDom || (hasBrand && body.length > 4_000)
    }

    // =========================================================================
    // HTTP Execution + Live-Verified Anti-Cloudflare Transport
    // =========================================================================

    private fun buildCookieHeader(url: String): String? {
        val savedFull = EgyDeadPrefs.savedCookieHeader?.trim()?.takeIf { it.isNotBlank() }
        if (savedFull != null) return savedFull
        val cf = EgyDeadPrefs.cfClearance?.trim()?.takeIf { it.isNotBlank() }
        if (cf != null && url.contains("egydead", ignoreCase = true)) {
            return "cf_clearance=$cf"
        }
        return null
    }

    fun executeRawGet(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        client: OkHttpClient = httpClient
    ): RawResponse? {
        val safeUrl = encodeUrl(url)
        val reqBuilder = Request.Builder()
            .url(safeUrl)
            .get()
            .header("User-Agent", extraHeaders["User-Agent"] ?: currentUserAgent)
            .header(
                "Accept",
                extraHeaders["Accept"]
                    ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            .header("Accept-Language", extraHeaders["Accept-Language"] ?: "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Upgrade-Insecure-Requests", "1")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", encodeUrl(referer))
        }

        if (!extraHeaders.containsKey("Cookie")) {
            buildCookieHeader(safeUrl)?.let { reqBuilder.header("Cookie", it) }
        }

        for ((k, v) in extraHeaders) {
            reqBuilder.header(k, v)
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                RawResponse(
                    code = resp.code,
                    body = text,
                    finalUrl = resp.request.url.toString(),
                    headers = resp.headers
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET failed for $safeUrl: ${t.message}")
            null
        }
    }

    fun executeRawPostForm(
        url: String,
        formFields: Map<String, String>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        client: OkHttpClient = httpClient
    ): RawResponse? {
        val safeUrl = encodeUrl(url)
        val formBuilder = FormBody.Builder()
        for ((k, v) in formFields) {
            formBuilder.add(k, v)
        }

        val reqBuilder = Request.Builder()
            .url(safeUrl)
            .post(formBuilder.build())
            .header("User-Agent", extraHeaders["User-Agent"] ?: currentUserAgent)
            .header(
                "Accept",
                extraHeaders["Accept"]
                    ?: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            .header("Accept-Language", extraHeaders["Accept-Language"] ?: "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")

        if (!referer.isNullOrBlank()) {
            val safeRef = encodeUrl(referer)
            reqBuilder.header("Referer", safeRef)
            originOf(safeRef)?.let { reqBuilder.header("Origin", it) }
        }

        if (!extraHeaders.containsKey("Cookie")) {
            buildCookieHeader(safeUrl)?.let { reqBuilder.header("Cookie", it) }
        }

        for ((k, v) in extraHeaders) {
            reqBuilder.header(k, v)
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                RawResponse(
                    code = resp.code,
                    body = text,
                    finalUrl = resp.request.url.toString(),
                    headers = resp.headers
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "POST failed for $safeUrl: ${t.message}")
            null
        }
    }

    /**
     * Shared 90-second cached fetch of `https://tv10.egydead.live/h3/` so multiple home shelves
     * (`المواضيع المميزة`, `المضاف حديثاً`) render from ONE single HTTP/WebView request.
     */
    suspend fun getHomeLandingDocument(forceRefresh: Boolean = false): Document? = homeDocMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedHomeDoc
        if (!forceRefresh && existing != null && now - cachedHomeDocAtMs < HOME_DOC_TTL_MS) {
            return@withLock existing
        }
        val base = getBaseUrl()
        val homeUrl = "${base.trimEnd('/')}$HOME_LANDING_PATH"
        val doc = fetchDocument(homeUrl, referer = "$base/")
        if (doc != null && doc.select("li.movieItem").isNotEmpty()) {
            cachedHomeDoc = doc
            cachedHomeDocAtMs = System.currentTimeMillis()
        }
        doc
    }

    suspend fun fetchDocument(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Document? = withContext(Dispatchers.IO) {
        val isSiteUrl = url.contains("egydead.", ignoreCase = true) || url.startsWith("/")
        val targetUrl = if (isSiteUrl) rewriteToActiveBase(url) else encodeUrl(url)
        val effectiveReferer = referer ?: "${getBaseUrl()}$HOME_LANDING_PATH"

        if (!isSiteUrl) {
            val ext = executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers)
            return@withContext if (ext != null && ext.body.isNotBlank() && !isCloudflareBlock(ext.code, ext.body)) {
                Jsoup.parse(ext.body, ext.finalUrl)
            } else {
                null
            }
        }

        // Test-only JVM Live E2E bridge (null in production Android APK)
        CloudflareSolver.jvmTestFetchBridge?.let { bridge ->
            val bridged = runCatching { bridge(targetUrl, effectiveReferer, "GET", null) }.getOrNull()
            if (bridged != null && isValidEgyDeadHtml(bridged.first, bridged.second)) {
                return@withContext Jsoup.parse(bridged.second, targetUrl)
            }
        }

        // Negative-cache guard: fail fast if all tiers recently failed so queued shelves never hit 120s timeout
        val sinceLastFail = System.currentTimeMillis() - lastAllTiersFailedAtMs
        if (lastAllTiersFailedAtMs > 0L && sinceLastFail in 0 until FAILURE_COOLDOWN_MS) {
            Log.d(TAG, "Fast-failing $targetUrl during ${FAILURE_COOLDOWN_MS}ms negative cooldown")
            return@withContext null
        }

        // Tier 1A: Persistent WebView `window.fetch` fast-path (~375-670ms once Turnstile solved)
        if (preferWebViewTransport || CloudflareSolver.hasValidClearance(targetUrl)) {
            val wvFast = CloudflareSolver.fetchViaPersistentWebView(
                url = targetUrl,
                referer = effectiveReferer,
                method = "GET"
            )
            if (wvFast != null && isValidEgyDeadHtml(wvFast.status, wvFast.body)) {
                preferWebViewTransport = true
                updateActiveDomainFromUrl(wvFast.finalUrl)
                return@withContext Jsoup.parse(wvFast.body, wvFast.finalUrl)
            }
        }

        // Tier 1B: Fast direct OkHttp request (`siteClient` 4s connect / 6s read)
        val direct = executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers, client = siteClient)
        if (direct != null && isValidEgyDeadHtml(direct.code, direct.body)) {
            updateActiveDomainFromUrl(direct.finalUrl)
            return@withContext Jsoup.parse(direct.body, direct.finalUrl)
        }

        // Genuine TCP/DNS failure -> check redirect beacons (`egydead.live` / `egydead.fyi`)
        if (direct == null && EgyDeadPrefs.customDomain.isNullOrBlank()) {
            val newDomain = discoverDomainFromBeacons()
            if (newDomain != null) {
                val relocated = rewriteToActiveBase(url)
                val retry = executeRawGet(relocated, referer = "$newDomain/", extraHeaders = headers, client = siteClient)
                if (retry != null && isValidEgyDeadHtml(retry.code, retry.body)) {
                    return@withContext Jsoup.parse(retry.body, retry.finalUrl)
                }
            }
        }

        // Tier 2: Single-flight Android WebView Turnstile solver (primary bypass when Activity present!)
        if (CloudflareSolver.currentActivity() != null) {
            val epochBefore = cfSolveEpoch.get()
            val solvedInMutex = cfMutex.withLock {
                if (cfSolveEpoch.get() != epochBefore) {
                    null
                } else {
                    val silent = CloudflareSolver.solveSilently(targetUrl)
                    val win = silent ?: CloudflareSolver.solveInteractiveDialog(targetUrl)
                    if (win != null) {
                        recordSolvedSession(win)
                        cfSolveEpoch.incrementAndGet()
                    }
                    win
                }
            }

            if (solvedInMutex != null && isValidEgyDeadHtml(200, solvedInMutex.html)) {
                updateActiveDomainFromUrl(solvedInMutex.finalUrl)
                return@withContext Jsoup.parse(solvedInMutex.html, solvedInMutex.finalUrl)
            }

            if (cfSolveEpoch.get() != epochBefore) {
                val wvAfterPeer = CloudflareSolver.fetchViaPersistentWebView(
                    url = targetUrl,
                    referer = effectiveReferer,
                    method = "GET"
                )
                if (wvAfterPeer != null && isValidEgyDeadHtml(wvAfterPeer.status, wvAfterPeer.body)) {
                    updateActiveDomainFromUrl(wvAfterPeer.finalUrl)
                    return@withContext Jsoup.parse(wvAfterPeer.body, wvAfterPeer.finalUrl)
                }
                val directAfterPeer = executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers, client = siteClient)
                if (directAfterPeer != null && isValidEgyDeadHtml(directAfterPeer.code, directAfterPeer.body)) {
                    updateActiveDomainFromUrl(directAfterPeer.finalUrl)
                    return@withContext Jsoup.parse(directAfterPeer.body, directAfterPeer.finalUrl)
                }
            }
        } else {
            // Tier 3 (Headless / no-Activity fallback ONLY): quick single-batch sticky proxy probe
            seedSavedProxiesOnce()
            tryWorkingStickyProxies(targetUrl, effectiveReferer, headers)?.let {
                return@withContext Jsoup.parse(it.body, it.finalUrl)
            }
            discoverStickyProxySingleBatch(targetUrl, effectiveReferer, headers)?.let {
                return@withContext Jsoup.parse(it.body, it.finalUrl)
            }
        }

        lastAllTiersFailedAtMs = System.currentTimeMillis()
        null
    }

    suspend fun postDocument(
        url: String,
        formParams: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Document? = withContext(Dispatchers.IO) {
        val isSiteUrl = url.contains("egydead.", ignoreCase = true) || url.startsWith("/")
        val targetUrl = if (isSiteUrl) rewriteToActiveBase(url) else encodeUrl(url)
        val effectiveReferer = referer ?: targetUrl

        // Test-only JVM Live E2E bridge (null in production Android APK)
        if (isSiteUrl) {
            CloudflareSolver.jvmTestFetchBridge?.let { bridge ->
                val bridged = runCatching { bridge(targetUrl, effectiveReferer, "POST", formParams) }.getOrNull()
                if (bridged != null && bridged.second.isNotBlank() && !isCloudflareBlock(bridged.first, bridged.second)) {
                    return@withContext Jsoup.parse(bridged.second, targetUrl)
                }
            }
        }

        // 1. Persistent WebView POST fast-path (`View=1` watch unlock)
        if (isSiteUrl && (preferWebViewTransport || CloudflareSolver.hasValidClearance(targetUrl))) {
            val wvPost = CloudflareSolver.fetchViaPersistentWebView(
                url = targetUrl,
                referer = effectiveReferer,
                method = "POST",
                formBody = formParams
            )
            if (wvPost != null && wvPost.body.isNotBlank() && !isCloudflareBlock(wvPost.status, wvPost.body)) {
                preferWebViewTransport = true
                updateActiveDomainFromUrl(wvPost.finalUrl)
                return@withContext Jsoup.parse(wvPost.body, wvPost.finalUrl)
            }
        }

        // 2. Direct OkHttp POST
        val direct = executeRawPostForm(targetUrl, formParams, referer = effectiveReferer, extraHeaders = headers, client = siteClient)
        if (direct != null && direct.body.isNotBlank() && !isCloudflareBlock(direct.code, direct.body)) {
            updateActiveDomainFromUrl(direct.finalUrl)
            return@withContext Jsoup.parse(direct.body, direct.finalUrl)
        }

        // 3. Single-flight WebView recovery + retry POST
        if (isSiteUrl && CloudflareSolver.currentActivity() != null) {
            val epochBefore = cfSolveEpoch.get()
            cfMutex.withLock {
                if (cfSolveEpoch.get() == epochBefore) {
                    val homeUrl = "${getBaseUrl().trimEnd('/')}$HOME_LANDING_PATH"
                    val solved = CloudflareSolver.solveSilently(homeUrl)
                        ?: CloudflareSolver.solveInteractiveDialog(homeUrl)
                    if (solved != null) {
                        recordSolvedSession(solved)
                        cfSolveEpoch.incrementAndGet()
                    }
                }
            }
            val wvRetry = CloudflareSolver.fetchViaPersistentWebView(
                url = targetUrl,
                referer = effectiveReferer,
                method = "POST",
                formBody = formParams
            )
            if (wvRetry != null && wvRetry.body.isNotBlank() && !isCloudflareBlock(wvRetry.status, wvRetry.body)) {
                updateActiveDomainFromUrl(wvRetry.finalUrl)
                return@withContext Jsoup.parse(wvRetry.body, wvRetry.finalUrl)
            }
            val directRetry = executeRawPostForm(targetUrl, formParams, referer = effectiveReferer, extraHeaders = headers, client = siteClient)
            if (directRetry != null && directRetry.body.isNotBlank() && !isCloudflareBlock(directRetry.code, directRetry.body)) {
                updateActiveDomainFromUrl(directRetry.finalUrl)
                return@withContext Jsoup.parse(directRetry.body, directRetry.finalUrl)
            }
        }

        null
    }

    private fun recordSolvedSession(solved: CloudflareSolver.SolveResult) {
        if (solved.userAgent.isNotBlank()) {
            EgyDeadPrefs.savedUserAgent = solved.userAgent
        }
        if (solved.cookies.isNotEmpty()) {
            StickyCookieJar.importCookies(solved.finalUrl, solved.cookies)
        }
        preferWebViewTransport = true
        lastAllTiersFailedAtMs = 0L
        updateActiveDomainFromUrl(solved.finalUrl)
        if (solved.finalUrl.contains("/h3") && solved.html.contains("movieItem")) {
            runCatching {
                val doc = Jsoup.parse(solved.html, solved.finalUrl)
                if (doc.select("li.movieItem").isNotEmpty()) {
                    cachedHomeDoc = doc
                    cachedHomeDocAtMs = System.currentTimeMillis()
                }
            }
        }
    }

    private fun seedSavedProxiesOnce() {
        if (savedProxiesSeeded) return
        savedProxiesSeeded = true
        for (spec in EgyDeadPrefs.savedWorkingProxies) {
            if (spec.isNotBlank() && !workingProxies.contains(spec)) {
                workingProxies.add(spec)
            }
        }
    }

    private fun sessionFor(spec: String): ProxySession? = runCatching {
        proxySessions.getOrPut(spec) { ProxySession(spec) }
    }.getOrElse {
        deadProxies.add(spec)
        workingProxies.remove(spec)
        null
    }

    private fun tryWorkingStickyProxies(
        url: String,
        referer: String,
        headers: Map<String, String>
    ): RawResponse? {
        for (spec in workingProxies.toList()) {
            val session = sessionFor(spec) ?: continue
            val res = executeRawGet(url, referer = referer, extraHeaders = headers, client = session.client)
            if (res != null && isValidEgyDeadHtml(res.code, res.body)) {
                updateActiveDomainFromUrl(res.finalUrl)
                return res
            }
        }
        return null
    }

    private suspend fun discoverStickyProxySingleBatch(
        url: String,
        referer: String,
        headers: Map<String, String>
    ): RawResponse? = proxyHarvestMutex.withLock {
        tryWorkingStickyProxies(url, referer, headers)?.let { return@withLock it }

        val feeds = coroutineScope {
            PROXY_LIST_SOURCES.map { (scheme, feedUrl) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(3_000L) {
                        runCatching {
                            val req = Request.Builder()
                                .url(feedUrl)
                                .header("User-Agent", DEFAULT_USER_AGENT)
                                .build()
                            siteClient.newCall(req).execute().use { r ->
                                if (!r.isSuccessful) emptyList()
                                else r.body?.string().orEmpty()
                                    .lineSequence()
                                    .map { it.trim() }
                                    .filter { line ->
                                        line.isNotEmpty() && !line.startsWith("#") &&
                                            line.count { ch -> ch == ':' } == 1
                                    }
                                    .map { "$scheme://$it" }
                                    .take(25)
                                    .toList()
                            }
                        }.getOrDefault(emptyList())
                    } ?: emptyList()
                }
            }.awaitAll()
        }

        val candidates = ArrayList<String>(40)
        val seen = HashSet<String>()
        val maxLen = feeds.maxOfOrNull { it.size } ?: 0
        for (idx in 0 until maxLen) {
            for (feed in feeds) {
                if (idx < feed.size) {
                    val spec = feed[idx]
                    if (spec !in deadProxies && seen.add(spec)) {
                        candidates.add(spec)
                        if (candidates.size >= 36) break
                    }
                }
            }
            if (candidates.size >= 36) break
        }
        if (candidates.isEmpty()) return@withLock null

        val winner = CompletableDeferred<Pair<String, RawResponse>?>()
        val raceJob = SupervisorJob()
        val raceScope = CoroutineScope(Dispatchers.IO + raceJob)
        val sem = Semaphore(24)

        val jobs = candidates.map { spec ->
            raceScope.launch {
                sem.withPermit {
                    if (winner.isCompleted) return@withPermit
                    val session = sessionFor(spec) ?: return@withPermit
                    val res = executeRawGet(url, referer = referer, extraHeaders = headers, client = session.client)
                    if (res != null && isValidEgyDeadHtml(res.code, res.body)) {
                        winner.complete(spec to res)
                    } else {
                        deadProxies.add(spec)
                        proxySessions.remove(spec)
                    }
                }
            }
        }
        raceScope.launch {
            jobs.forEach { it.join() }
            winner.complete(null)
        }
        val found = withTimeoutOrNull(4_500L) { winner.await() }
        raceJob.cancel()

        if (found != null) {
            val (spec, res) = found
            workingProxies.add(0, spec)
            EgyDeadPrefs.savedWorkingProxies = workingProxies.distinct().take(6)
            updateActiveDomainFromUrl(res.finalUrl)
            return@withLock res
        }
        null
    }
}
