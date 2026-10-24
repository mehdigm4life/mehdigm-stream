package com.egydead

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-layer anti-Cloudflare & domain-resilient transport engine for EgyDead (`https://tv10.egydead.live/h3/`).
 *
 * Transport Pipeline (strictly bounded — zero `CloudflareKiller` 60s hangs, zero 120s timeouts):
 * - Tier 0a: Persistent blank-origin Android WebView `window.fetch()` (when WebView clearance is active).
 * - Tier 0b: Verified session-sticky SOCKS5/HTTPS proxy pool (`workingProxies`, persisted across launches).
 * - Tier 1: Direct session-sticky OkHttp + DoH with full Chrome Mobile Client Hints.
 * - Tier 2 (`cfMutex` single-flight):
 *     2a) Parallel latency-sorted sticky proxy harvester (`monosans`, `Roosterkid`, `vakhov`, `hookzof`, `TheSpeedX`)
 *         verified against real `isValidEgyDeadHtml` — works headless on CI AND zero-WebView on Android!
 *     2b) Software-rendered off-screen full-viewport Android WebView Turnstile solver (`9s` cap) +
 *         auto-closing 1-tap interactive Turnstile dialog fallback (`60s` cooldown).
 */
object EgyDeadNetwork {
    private const val TAG = "EgyDeadNet"

    const val DEFAULT_BASE_URL = "https://tv10.egydead.live"
    const val HOME_LANDING_PATH = "/h3/"

    private val REDIRECT_BEACONS = listOf(
        "https://egydead.live",
        "https://egydead.fyi"
    )

    private val PROXY_FEEDS = listOf(
        "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "socks5" to "https://raw.githubusercontent.com/Roosterkid/openproxylist/main/SOCKS5_RAW.txt",
        "socks5" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt",
        "http" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt",
        "http" to "https://raw.githubusercontent.com/Roosterkid/openproxylist/main/HTTPS_RAW.txt",
        "socks5" to "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
        "socks5" to "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt"
    )

    private const val DEFAULT_ANDROID_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var activeBaseUrl: String? = null

    @Volatile
    var preferWebViewTransport: Boolean = false
        private set

    private val cfMutex = Mutex()
    private val cfSolveEpoch = AtomicInteger(0)
    private val beaconMutex = Mutex()
    private val homeDocMutex = Mutex()

    @Volatile
    private var lastAllTiersFailedAtMs: Long = 0L
    private const val FAILURE_COOLDOWN_MS = 15_000L

    @Volatile
    private var cachedHomeDoc: Document? = null

    @Volatile
    private var cachedHomeDocAtMs: Long = 0L
    private const val HOME_DOC_TTL_MS = 90_000L

    // Session-sticky verified proxy pool
    private val workingProxies = CopyOnWriteArrayList<String>()
    private val deadProxies = ConcurrentHashMap.newKeySet<String>()
    private val proxyFailCounts = ConcurrentHashMap<String, Int>()
    private val proxySessions = ConcurrentHashMap<String, ProxySession>()

    @Volatile
    private var savedProxiesSeeded = false

    val currentUserAgent: String
        get() = EgyDeadPrefs.savedUserAgent ?: DEFAULT_ANDROID_CHROME_UA

    // =========================================================================
    // Session-Sticky Cookie Jar (In-Memory + SharedPreferences Persistence)
    // =========================================================================

    class HostStickyCookieJar(private val persistClearance: Boolean = false) : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val hostKey = baseHostKey(url.host)
            val bucket = store.getOrPut(hostKey) { ConcurrentHashMap() }
            for (c in cookies) {
                bucket[c.name] = c
                if (persistClearance && c.name == "cf_clearance" && c.value.isNotBlank()) {
                    EgyDeadPrefs.cfClearance = c.value
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostKey = baseHostKey(url.host)
            val bucket = store[hostKey] ?: return emptyList()
            val now = System.currentTimeMillis()
            val out = ArrayList<Cookie>(bucket.size)
            for ((k, c) in bucket) {
                if (c.expiresAt >= now) out.add(c) else bucket.remove(k)
            }
            return out
        }

        fun importCookieHeader(urlStr: String, rawCookieHeader: String?) {
            if (rawCookieHeader.isNullOrBlank()) return
            val httpUrl = urlStr.toHttpUrlOrNull() ?: DEFAULT_BASE_URL.toHttpUrl()
            val hostKey = baseHostKey(httpUrl.host)
            val bucket = store.getOrPut(hostKey) { ConcurrentHashMap() }
            for (segment in rawCookieHeader.split(';')) {
                val part = segment.trim()
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isBlank()) continue
                val cookie = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(httpUrl.host)
                    .path("/")
                    .build()
                bucket[name] = cookie
                if (persistClearance && name == "cf_clearance" && value.isNotBlank()) {
                    EgyDeadPrefs.cfClearance = value
                }
            }
        }

        fun clear() = store.clear()

        private fun baseHostKey(host: String): String {
            val lower = host.lowercase()
            return if (lower.contains("egydead.")) "egydead" else lower
        }
    }

    private val directCookieJar = HostStickyCookieJar(persistClearance = true)

    // =========================================================================
    // DNS-over-HTTPS (Cloudflare + Google fallback when system DNS is ISP-blocked)
    // =========================================================================

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
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
    // OkHttp Clients (Extractor Client + Fast Site Client + Sticky Proxy Sessions)
    // =========================================================================

    @Volatile
    private var cachedClientKey: String? = null

    @Volatile
    private var cachedClient: OkHttpClient? = null

    @Volatile
    private var cachedSiteClient: OkHttpClient? = null

    private fun parseProxySpec(spec: String?): Proxy? {
        if (spec.isNullOrBlank()) return null
        return runCatching {
            val trimmed = spec.trim()
            val scheme = if (trimmed.contains("://")) trimmed.substringBefore("://").lowercase() else "http"
            if (scheme == "socks4") return null
            val withoutScheme = trimmed
                .replace(Regex("^[a-zA-Z0-9]+://"), "")
                .substringAfterLast('@')
            val colonIdx = withoutScheme.lastIndexOf(':')
            if (colonIdx <= 0) return null
            val host = withoutScheme.substring(0, colonIdx).trim()
            val port = withoutScheme.substring(colonIdx + 1).trim().toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            val resolvedAddr = InetSocketAddress(InetAddress.getByName(host), port)
            Proxy(type, resolvedAddr)
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
                EgyDeadPrefs.savedCookieHeader?.let {
                    directCookieJar.importCookieHeader(DEFAULT_BASE_URL, it)
                }
                val builder = OkHttpClient.Builder()
                    .cookieJar(directCookieJar)
                    .dns(SmartDohDns)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(18, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)

                if (useProxy) {
                    parseProxySpec(proxySpec)?.let { builder.proxy(it) }
                }

                val built = builder.build()
                cachedClient = built
                cachedSiteClient = built.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
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

    private class ProxySession(val spec: String, proxy: Proxy) {
        val cookieJar = HostStickyCookieJar(persistClearance = false)
        val client: OkHttpClient = OkHttpClient.Builder()
            .proxy(proxy)
            .cookieJar(cookieJar)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun getOrCreateProxySession(spec: String): ProxySession? {
        proxySessions[spec]?.let { return it }
        val proxy = parseProxySpec(spec) ?: return null
        val created = ProxySession(spec, proxy)
        proxySessions[spec] = created
        return created
    }

    private fun ensureSavedProxiesSeeded() {
        if (savedProxiesSeeded) return
        savedProxiesSeeded = true
        for (spec in EgyDeadPrefs.savedWorkingProxies) {
            if (!workingProxies.contains(spec) && !deadProxies.contains(spec)) {
                workingProxies.add(spec)
            }
        }
    }

    private fun markProxySuccess(spec: String) {
        proxyFailCounts.remove(spec)
        if (workingProxies.firstOrNull() != spec) {
            workingProxies.remove(spec)
            workingProxies.add(0, spec)
            EgyDeadPrefs.savedWorkingProxies = workingProxies.take(6)
        }
    }

    private fun markProxyFailure(spec: String) {
        val strikes = (proxyFailCounts[spec] ?: 0) + 1
        proxyFailCounts[spec] = strikes
        if (strikes >= 2) {
            workingProxies.remove(spec)
            deadProxies.add(spec)
            proxySessions.remove(spec)
            EgyDeadPrefs.savedWorkingProxies = workingProxies.take(6)
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
        directCookieJar.clear()
        workingProxies.clear()
        deadProxies.clear()
        proxyFailCounts.clear()
        proxySessions.clear()
        savedProxiesSeeded = false
        CloudflareSolver.resetPersistentWebView()
    }

    fun activeTransportSummary(): String =
        "base=${getBaseUrl()}, preferWebView=$preferWebViewTransport, workingProxies=${workingProxies.take(4)}, deadProxies=${deadProxies.size}"

    fun getImageHeaders(): Map<String, String> {
        val base = getBaseUrl()
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = currentUserAgent
        map["Referer"] = "$base/"
        buildCookieHeader(base)?.let { map["Cookie"] = it }
        return map
    }

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
        if (code == 403 || code == 503 || code == 429) {
            if (body.contains("Just a moment", ignoreCase = true) ||
                body.contains("Attention Required", ignoreCase = true) ||
                body.contains("cf-browser-verification", ignoreCase = true) ||
                body.contains("challenges.cloudflare.com", ignoreCase = true) ||
                body.contains("__cf_chl_", ignoreCase = true) ||
                body.contains("cloudflare", ignoreCase = true) ||
                body.length < 12_000
            ) {
                return true
            }
        }
        return body.contains("<title>Just a moment...</title>", ignoreCase = true) ||
            (body.contains("cf-turnstile", ignoreCase = true) && body.contains("__cf_chl_", ignoreCase = true))
    }

    fun isValidEgyDeadHtml(code: Int, body: String): Boolean {
        if (code !in 200..299 && code != 404) return false
        if (body.isBlank() || isCloudflareBlock(code, body)) return false
        // Require genuine EgyDead WordPress DOM markers (prevents captive-portal/proxy garbage false positives)
        return body.contains("movieItem") ||
            body.contains("BottomTitle") ||
            body.contains("ServersList") ||
            body.contains("serversList") ||
            body.contains("singleTitle") ||
            body.contains("EpsList") ||
            body.contains("posts-list") ||
            (body.contains("ايجي ديد") && body.contains("wp-content"))
    }

    // =========================================================================
    // HTTP Execution + Multi-Layer Anti-Cloudflare Pipeline
    // =========================================================================

    data class RawResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val headers: Headers
    )

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
        client: OkHttpClient = httpClient,
        includeSavedCookies: Boolean = true
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
            .header("Accept-Language", extraHeaders["Accept-Language"] ?: "ar,en-US;q=0.9,en;q=0.8")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"")
            .header("Sec-CH-UA-Mobile", "?1")
            .header("Sec-CH-UA-Platform", "\"Android\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", if (referer != null) "same-origin" else "none")
            .header("Sec-Fetch-User", "?1")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", encodeUrl(referer))
        }

        if (includeSavedCookies && !extraHeaders.containsKey("Cookie")) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.d(TAG, "GET failed for $safeUrl: ${t.message}")
            null
        }
    }

    fun executeRawPostForm(
        url: String,
        formFields: Map<String, String>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        client: OkHttpClient = httpClient,
        includeSavedCookies: Boolean = true
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
            .header("Accept-Language", extraHeaders["Accept-Language"] ?: "ar,en-US;q=0.9,en;q=0.8")

        if (!referer.isNullOrBlank()) {
            val safeRef = encodeUrl(referer)
            reqBuilder.header("Referer", safeRef)
            originOf(safeRef)?.let { reqBuilder.header("Origin", it) }
        }

        if (includeSavedCookies && !extraHeaders.containsKey("Cookie")) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.d(TAG, "POST failed for $safeUrl: ${t.message}")
            null
        }
    }

    // =========================================================================
    // Sticky Proxy Pool Execution & Parallel Latency-Sorted Harvester
    // =========================================================================

    private fun tryWorkingProxiesGet(
        targetUrl: String,
        referer: String?,
        headers: Map<String, String>
    ): RawResponse? {
        ensureSavedProxiesSeeded()
        for (spec in workingProxies.toList()) {
            if (deadProxies.contains(spec)) continue
            val ps = getOrCreateProxySession(spec) ?: continue
            val res = executeRawGet(
                url = targetUrl,
                referer = referer,
                extraHeaders = headers,
                client = ps.client,
                includeSavedCookies = false
            )
            if (res != null && isValidEgyDeadHtml(res.code, res.body)) {
                markProxySuccess(spec)
                return res
            }
            markProxyFailure(spec)
        }
        return null
    }

    private fun tryWorkingProxiesPost(
        targetUrl: String,
        formFields: Map<String, String>,
        referer: String?,
        headers: Map<String, String>
    ): RawResponse? {
        ensureSavedProxiesSeeded()
        for (spec in workingProxies.toList()) {
            if (deadProxies.contains(spec)) continue
            val ps = getOrCreateProxySession(spec) ?: continue
            val res = executeRawPostForm(
                url = targetUrl,
                formFields = formFields,
                referer = referer,
                extraHeaders = headers,
                client = ps.client,
                includeSavedCookies = false
            )
            if (res != null && isValidEgyDeadHtml(res.code, res.body)) {
                markProxySuccess(spec)
                return res
            }
            markProxyFailure(spec)
        }
        return null
    }

    /**
     * Harvests latency-sorted SOCKS5 & HTTPS proxies in parallel and races them against [targetUrl],
     * returning the first verified real EgyDead HTML response and caching winning sticky proxies.
     */
    private suspend fun discoverStickyProxyAndFetch(
        targetUrl: String,
        referer: String?,
        headers: Map<String, String>
    ): RawResponse? = withTimeoutOrNull(14_000L) {
        coroutineScope {
            if (deadProxies.size > 600) deadProxies.clear()

            val feedLists = withTimeoutOrNull(3_200L) {
                coroutineScope {
                    PROXY_FEEDS.map { (proto, feedUrl) ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val req = Request.Builder()
                                    .url(feedUrl)
                                    .header("User-Agent", DEFAULT_ANDROID_CHROME_UA)
                                    .build()
                                bootstrapClient.newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful) return@use emptyList()
                                    val picked = ArrayList<String>(90)
                                    resp.body?.byteStream()?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                                        while (picked.size < 90) {
                                            val rawLine = reader.readLine() ?: break
                                            val clean = rawLine.trim()
                                            if (clean.isEmpty() || clean.startsWith('#')) continue
                                            val spec = if (clean.contains("://")) clean else "$proto://$clean"
                                            if (!deadProxies.contains(spec)) {
                                                picked.add(spec)
                                            }
                                        }
                                    }
                                    picked
                                }
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
            }.orEmpty()

            // Interleave feeds in latency order so fastest proxies from every feed race first!
            val interleaved = ArrayList<String>(360)
            val maxLen = feedLists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxLen) {
                for (list in feedLists) {
                    if (i < list.size) interleaved.add(list[i])
                }
            }
            val candidates = interleaved.distinct().take(240)
            if (candidates.isEmpty()) return@coroutineScope null

            val sem = Semaphore(36)
            for (batch in candidates.chunked(48)) {
                val winner = CompletableDeferred<RawResponse?>()
                val jobs = batch.map { spec ->
                    async(Dispatchers.IO) {
                        if (winner.isCompleted) return@async
                        sem.withPermit {
                            if (winner.isCompleted) return@withPermit
                            val ps = getOrCreateProxySession(spec) ?: return@withPermit
                            val res = executeRawGet(
                                url = targetUrl,
                                referer = referer,
                                extraHeaders = headers,
                                client = ps.client,
                                includeSavedCookies = false
                            )
                            if (res != null && isValidEgyDeadHtml(res.code, res.body)) {
                                markProxySuccess(spec)
                                winner.complete(res)
                            } else {
                                deadProxies.add(spec)
                                proxySessions.remove(spec)
                            }
                        }
                    }
                }

                val batchResult = withTimeoutOrNull(6_000L) {
                    // Complete early as soon as first valid proxy wins, or null if all 48 fail
                    val monitor = async {
                        jobs.awaitAll()
                        if (!winner.isCompleted) winner.complete(null)
                    }
                    val won = winner.await()
                    monitor.cancel()
                    won
                }
                jobs.forEach { it.cancel() }
                if (batchResult != null) {
                    return@coroutineScope batchResult
                }
            }
            null
        }
    }

    // =========================================================================
    // Public High-Level Page & Home Landing Fetchers
    // =========================================================================

    suspend fun getHomeLandingDocument(forceRefresh: Boolean = false): Document? = homeDocMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedHomeDoc
        if (!forceRefresh && existing != null && now - cachedHomeDocAtMs < HOME_DOC_TTL_MS) {
            return@withLock existing
        }
        val base = getBaseUrl()
        val homeUrl = "${base.trimEnd('/')}$HOME_LANDING_PATH"
        val doc = fetchDocument(homeUrl, referer = "$base/")
        if (doc != null) {
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
        val targetUrl = if (url.contains("egydead.", ignoreCase = true) || url.startsWith("/")) {
            rewriteToActiveBase(url)
        } else {
            encodeUrl(url)
        }
        val isEgyDeadHost = targetUrl.contains("egydead.", ignoreCase = true)
        val effectiveReferer = referer ?: "${getBaseUrl()}/"

        // Tier 0a: Persistent WebView `window.fetch` when WebView TLS clearance is active
        if (isEgyDeadHost && preferWebViewTransport) {
            val wvRes = CloudflareSolver.fetchViaPersistentWebView(
                targetUrl = targetUrl,
                method = "GET",
                referer = effectiveReferer
            )
            if (wvRes != null && isValidEgyDeadHtml(wvRes.code, wvRes.body)) {
                updateActiveDomainFromUrl(wvRes.finalUrl)
                return@withContext Jsoup.parse(wvRes.body, wvRes.finalUrl)
            }
        }

        // Tier 0b: Verified session-sticky proxy (fast parallel execution across shelves!)
        if (isEgyDeadHost && !EgyDeadPrefs.useProxy) {
            tryWorkingProxiesGet(targetUrl, effectiveReferer, headers)?.let { proxyRes ->
                updateActiveDomainFromUrl(proxyRes.finalUrl)
                return@withContext Jsoup.parse(proxyRes.body, proxyRes.finalUrl)
            }
        }

        // Tier 1: Fast direct OkHttp request
        val direct = executeRawGet(
            targetUrl,
            referer = effectiveReferer,
            extraHeaders = headers,
            client = if (isEgyDeadHost) siteClient else httpClient
        )
        if (direct != null && isValidEgyDeadHtml(direct.code, direct.body)) {
            updateActiveDomainFromUrl(direct.finalUrl)
            return@withContext Jsoup.parse(direct.body, direct.finalUrl)
        }

        // Connection-failure beacon failover (if tv10 ever suffers DNS/TCP death)
        if (direct == null && isEgyDeadHost && EgyDeadPrefs.customDomain == null) {
            discoverDomainFromBeacons()?.let { newOrigin ->
                val rewritten = rewriteToActiveBase(targetUrl)
                val retryBeacon = executeRawGet(rewritten, referer = "$newOrigin/", extraHeaders = headers, client = siteClient)
                if (retryBeacon != null && isValidEgyDeadHtml(retryBeacon.code, retryBeacon.body)) {
                    return@withContext Jsoup.parse(retryBeacon.body, retryBeacon.finalUrl)
                }
            }
        }

        // Tier 2: Single-flight Cloudflare recovery (`cfMutex` prevents concurrent storms & timeouts!)
        if (isEgyDeadHost) {
            val epochBefore = cfSolveEpoch.get()
            var retryOutsideLock = false

            val recovered: Document? = cfMutex.withLock {
                // Another coroutine already recovered while we waited on `cfMutex`!
                if (cfSolveEpoch.get() != epochBefore) {
                    retryOutsideLock = true
                    return@withLock null
                }

                // Fail-fast negative cooldown so 18 shelves never queue for 120s when offline
                if (System.currentTimeMillis() - lastAllTiersFailedAtMs < FAILURE_COOLDOWN_MS) {
                    return@withLock null
                }

                // 2a. Harvest & verify a session-sticky proxy against `targetUrl` (zero WebView/GPU lag!)
                if (!EgyDeadPrefs.useProxy) {
                    val harvested = discoverStickyProxyAndFetch(targetUrl, effectiveReferer, headers)
                    if (harvested != null && isValidEgyDeadHtml(harvested.code, harvested.body)) {
                        cfSolveEpoch.incrementAndGet()
                        updateActiveDomainFromUrl(harvested.finalUrl)
                        return@withLock Jsoup.parse(harvested.body, harvested.finalUrl)
                    }
                }

                // 2b. Check if Android WebView's Chromium cookie DB already holds valid `cf_clearance`
                val quickWv = CloudflareSolver.fetchViaPersistentWebView(
                    targetUrl = targetUrl,
                    method = "GET",
                    referer = effectiveReferer
                )
                if (quickWv != null && isValidEgyDeadHtml(quickWv.code, quickWv.body)) {
                    preferWebViewTransport = true
                    cfSolveEpoch.incrementAndGet()
                    updateActiveDomainFromUrl(quickWv.finalUrl)
                    return@withLock Jsoup.parse(quickWv.body, quickWv.finalUrl)
                }

                // 2c. Silent software-rendered full-viewport WebView Turnstile solver (9s cap)
                CloudflareSolver.resetPersistentWebView()
                val solved = CloudflareSolver.solveAndCaptureHtml(targetUrl, effectiveReferer)
                    ?: CloudflareSolver.solveInteractiveDialog(targetUrl, effectiveReferer)

                if (solved != null) {
                    if (!solved.userAgent.isNullOrBlank()) {
                        EgyDeadPrefs.savedUserAgent = solved.userAgent
                    }
                    if (!solved.cookies.isNullOrBlank()) {
                        EgyDeadPrefs.savedCookieHeader = solved.cookies
                        directCookieJar.importCookieHeader(solved.finalUrl, solved.cookies)
                    }
                    updateActiveDomainFromUrl(solved.finalUrl)

                    val okProbe = executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers, client = siteClient)
                    if (okProbe != null && isValidEgyDeadHtml(okProbe.code, okProbe.body)) {
                        preferWebViewTransport = false
                        cfSolveEpoch.incrementAndGet()
                        updateActiveDomainFromUrl(okProbe.finalUrl)
                        return@withLock Jsoup.parse(okProbe.body, okProbe.finalUrl)
                    }

                    preferWebViewTransport = true
                    cfSolveEpoch.incrementAndGet()
                    return@withLock Jsoup.parse(solved.html, solved.finalUrl)
                }

                lastAllTiersFailedAtMs = System.currentTimeMillis()
                null
            }

            if (recovered != null) return@withContext recovered

            if (retryOutsideLock) {
                if (preferWebViewTransport) {
                    CloudflareSolver.fetchViaPersistentWebView(targetUrl, "GET", referer = effectiveReferer)?.let { wv ->
                        if (isValidEgyDeadHtml(wv.code, wv.body)) {
                            updateActiveDomainFromUrl(wv.finalUrl)
                            return@withContext Jsoup.parse(wv.body, wv.finalUrl)
                        }
                    }
                }
                if (!EgyDeadPrefs.useProxy) {
                    tryWorkingProxiesGet(targetUrl, effectiveReferer, headers)?.let { p ->
                        updateActiveDomainFromUrl(p.finalUrl)
                        return@withContext Jsoup.parse(p.body, p.finalUrl)
                    }
                }
                executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers, client = siteClient)?.let { d ->
                    if (isValidEgyDeadHtml(d.code, d.body)) {
                        updateActiveDomainFromUrl(d.finalUrl)
                        return@withContext Jsoup.parse(d.body, d.finalUrl)
                    }
                }
            }
        }

        if (direct != null && direct.body.isNotBlank() && !isCloudflareBlock(direct.code, direct.body)) {
            return@withContext Jsoup.parse(direct.body, direct.finalUrl)
        }
        null
    }

    suspend fun postDocument(
        url: String,
        formFields: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Document? = withContext(Dispatchers.IO) {
        val targetUrl = if (url.contains("egydead.", ignoreCase = true) || url.startsWith("/")) {
            rewriteToActiveBase(url)
        } else {
            encodeUrl(url)
        }
        val isEgyDeadHost = targetUrl.contains("egydead.", ignoreCase = true)
        val effectiveReferer = referer ?: targetUrl

        if (isEgyDeadHost && preferWebViewTransport) {
            val wvPost = CloudflareSolver.fetchViaPersistentWebView(
                targetUrl = targetUrl,
                method = "POST",
                formFields = formFields,
                referer = effectiveReferer
            )
            if (wvPost != null && isValidEgyDeadHtml(wvPost.code, wvPost.body)) {
                return@withContext Jsoup.parse(wvPost.body, wvPost.finalUrl)
            }
        }

        if (isEgyDeadHost && !EgyDeadPrefs.useProxy) {
            tryWorkingProxiesPost(targetUrl, formFields, effectiveReferer, headers)?.let { p ->
                return@withContext Jsoup.parse(p.body, p.finalUrl)
            }
        }

        val resp = executeRawPostForm(
            targetUrl,
            formFields,
            referer = effectiveReferer,
            extraHeaders = headers,
            client = if (isEgyDeadHost) siteClient else httpClient
        )
        if (resp != null && isValidEgyDeadHtml(resp.code, resp.body)) {
            return@withContext Jsoup.parse(resp.body, resp.finalUrl)
        }

        if (isEgyDeadHost) {
            fetchDocument(effectiveReferer, referer = "${getBaseUrl()}/")
            if (preferWebViewTransport) {
                CloudflareSolver.fetchViaPersistentWebView(targetUrl, "POST", formFields, effectiveReferer)?.let { wv ->
                    if (isValidEgyDeadHtml(wv.code, wv.body)) return@withContext Jsoup.parse(wv.body, wv.finalUrl)
                }
            }
            if (!EgyDeadPrefs.useProxy) {
                tryWorkingProxiesPost(targetUrl, formFields, effectiveReferer, headers)?.let { p ->
                    return@withContext Jsoup.parse(p.body, p.finalUrl)
                }
            }
        }

        if (resp != null && resp.body.isNotBlank() && !isCloudflareBlock(resp.code, resp.body)) {
            return@withContext Jsoup.parse(resp.body, resp.finalUrl)
        }
        null
    }
}
