package com.egydead

import android.util.Log
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resilient network, domain-discovery, and 4-tier anti-Cloudflare transport layer for EgyDead.
 *
 * - Primary live entry: `https://tv10.egydead.live/h3/` (`DEFAULT_BASE_URL = https://tv10.egydead.live`).
 * - Instant zero-blocking startup (`getBaseUrl()` never stalls on speculative domain timeouts;
 *   redirect discovery happens organically on responses or on genuine TCP/DNS failure via beacons).
 * - Defeats JA3/JA4 TLS-bound Cloudflare Managed Challenges via `cfMutex`-serialized stealth
 *   `1x1` WebView solving (`CloudflareSolver.solveAndCaptureHtml`) + persistent blank-origin
 *   `window.fetch()` WebView transport (`preferWebViewTransport`) with zero phone UI lag.
 */
object EgyDeadNetwork {
    private const val TAG = "EgyDeadNet"

    const val DEFAULT_BASE_URL = "https://tv10.egydead.live"
    const val HOME_LANDING_PATH = "/h3/"

    private val REDIRECT_BEACONS = listOf(
        "https://egydead.live",
        "https://egydead.fyi"
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
    private var cachedHomeDoc: Document? = null

    @Volatile
    private var cachedHomeDocAtMs: Long = 0L

    private const val HOME_DOC_TTL_MS = 90_000L

    val currentUserAgent: String
        get() = EgyDeadPrefs.savedUserAgent ?: DEFAULT_ANDROID_CHROME_UA

    // =========================================================================
    // Session-Sticky Cookie Jar (In-Memory + SharedPreferences Persistence)
    // =========================================================================

    private object StickyCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val hostKey = baseHostKey(url.host)
            val bucket = store.getOrPut(hostKey) { ConcurrentHashMap() }
            for (c in cookies) {
                bucket[c.name] = c
                if (c.name == "cf_clearance" && c.value.isNotBlank()) {
                    EgyDeadPrefs.cfClearance = c.value
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostKey = baseHostKey(url.host)
            val bucket = store[hostKey]
            val now = System.currentTimeMillis()
            val out = LinkedHashMap<String, Cookie>()
            if (bucket != null) {
                for ((k, c) in bucket) {
                    if (c.expiresAt >= now) {
                        out[k] = c
                    } else {
                        bucket.remove(k)
                    }
                }
            }
            return out.values.toList()
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
                if (name == "cf_clearance" && value.isNotBlank()) {
                    EgyDeadPrefs.cfClearance = value
                }
            }
        }

        fun clear() {
            store.clear()
        }

        private fun baseHostKey(host: String): String {
            val lower = host.lowercase()
            return if (lower.contains("egydead.")) "egydead" else lower
        }
    }

    // =========================================================================
    // DNS-over-HTTPS (Cloudflare + Google fallback when system DNS is ISP-blocked)
    // =========================================================================

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
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
    // OkHttp Clients (Direct + Optional Custom Proxy + CloudflareKiller Fallback)
    // =========================================================================

    @Volatile
    private var cachedClientKey: String? = null

    @Volatile
    private var cachedClient: OkHttpClient? = null

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
            val type = if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress.createUnresolved(host, port))
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
                    StickyCookieJar.importCookieHeader(DEFAULT_BASE_URL, it)
                }
                val builder = OkHttpClient.Builder()
                    .cookieJar(StickyCookieJar)
                    .dns(SmartDohDns)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(18, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)

                if (useProxy) {
                    parseCustomProxy(proxySpec)?.let { builder.proxy(it) }
                }

                val built = builder.build()
                cachedClient = built
                cachedClientKey = key
                return built
            }
        }

    private val cfKillerClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(StickyCookieJar)
            .dns(SmartDohDns)
            .addInterceptor(CloudflareKiller())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // =========================================================================
    // URL Normalization, Validation & Domain Management
    // =========================================================================

    fun invalidateDomainCache() {
        activeBaseUrl = null
        preferWebViewTransport = false
        cachedHomeDoc = null
        cachedHomeDocAtMs = 0L
        StickyCookieJar.clear()
        CloudflareSolver.resetPersistentWebView()
    }

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

    /**
     * Returns the active EgyDead origin immediately with zero startup blocking.
     * Redirects (`tv10` -> `tv11`, etc.) automatically update [activeBaseUrl] & [EgyDeadPrefs.discoveredDomain].
     */
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
        // Only adopt real numbered/content hosts (e.g. tv10.egydead.live), not bare redirector domains
        if (host.contains("egydead.") && host != "egydead.live" && host != "www.egydead.live" &&
            host != "egydead.fyi" && host != "www.egydead.fyi"
        ) {
            if (activeBaseUrl != origin) {
                activeBaseUrl = origin
                EgyDeadPrefs.discoveredDomain = origin
            }
        }
    }

    /**
     * Fast beacon discovery invoked ONLY if the current domain suffers a genuine TCP/DNS failure.
     */
    private suspend fun discoverDomainFromBeacons(): String? = beaconMutex.withLock {
        for (beacon in REDIRECT_BEACONS) {
            val probe = executeRawGet(beacon, referer = "$beacon/") ?: continue
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
            body.contains("cf-turnstile", ignoreCase = true) && body.contains("__cf_chl_", ignoreCase = true)
    }

    fun isValidEgyDeadHtml(code: Int, body: String): Boolean {
        if (code !in 200..299 && code != 404) return false
        if (body.isBlank() || isCloudflareBlock(code, body)) return false
        return body.contains("movieItem") ||
            body.contains("BottomTitle") ||
            body.contains("ServersList") ||
            body.contains("singleTitle") ||
            body.contains("EpsList") ||
            body.contains("posts-list") ||
            body.contains("ايجي ديد") ||
            body.contains("egydead", ignoreCase = true)
    }

    // =========================================================================
    // HTTP Execution + 4-Tier Anti-Cloudflare Transport
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
            .header("Accept-Language", extraHeaders["Accept-Language"] ?: "ar,en-US;q=0.9,en;q=0.8")

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
            .header("Accept-Language", extraHeaders["Accept-Language"] ?: "ar,en-US;q=0.9,en;q=0.8")

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
        if (doc != null) {
            cachedHomeDoc = doc
            cachedHomeDocAtMs = System.currentTimeMillis()
        }
        doc
    }

    /**
     * Fetches an HTML page and parses it with Jsoup using the 4-tier anti-Cloudflare pipeline:
     * Tier 0: Persistent single-WebView `window.fetch()` (when JA4 TLS-bound mode is active)
     * Tier 1: Direct session-sticky OkHttp + DoH
     * Tier 2: `cfMutex`-serialized stealth `1x1` WebView solver + DOM capture + TLS auto-detection
     * Tier 3: CloudStream `CloudflareKiller` & redirect-beacon domain failover
     */
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
        val effectiveReferer = referer ?: "${getBaseUrl()}/"

        // Tier 0: TLS-bound persistent WebView fetch (zero DOM layout, ~200ms)
        if (preferWebViewTransport && targetUrl.contains("egydead.", ignoreCase = true)) {
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

        // Tier 1: Fast direct OkHttp request
        val direct = executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers)
        if (direct != null && isValidEgyDeadHtml(direct.code, direct.body)) {
            updateActiveDomainFromUrl(direct.finalUrl)
            return@withContext Jsoup.parse(direct.body, direct.finalUrl)
        }

        // Tier 4 pre-check: If direct TCP/DNS failed completely (`direct == null`), check redirect beacons
        if (direct == null && targetUrl.contains("egydead.", ignoreCase = true) && EgyDeadPrefs.customDomain == null) {
            val newOrigin = discoverDomainFromBeacons()
            if (newOrigin != null) {
                val rewritten = rewriteToActiveBase(targetUrl)
                val retryBeacon = executeRawGet(rewritten, referer = "$newOrigin/", extraHeaders = headers)
                if (retryBeacon != null && isValidEgyDeadHtml(retryBeacon.code, retryBeacon.body)) {
                    return@withContext Jsoup.parse(retryBeacon.body, retryBeacon.finalUrl)
                }
            }
        }

        // Tier 2: Single-flight Cloudflare recovery (`cfMutex` prevents multiple WebViews!)
        if (targetUrl.contains("egydead.", ignoreCase = true)) {
            val epochBefore = cfSolveEpoch.get()
            var delegateToParallelWebView = false

            val solvedInsideLock: Document? = cfMutex.withLock {
                // If another coroutine already solved Cloudflare while we waited on `cfMutex`:
                if (cfSolveEpoch.get() != epochBefore) {
                    if (preferWebViewTransport) {
                        delegateToParallelWebView = true
                        return@withLock null
                    }
                    val afterWait = executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers)
                    if (afterWait != null && isValidEgyDeadHtml(afterWait.code, afterWait.body)) {
                        updateActiveDomainFromUrl(afterWait.finalUrl)
                        return@withLock Jsoup.parse(afterWait.body, afterWait.finalUrl)
                    }
                }

                // 2a. Try persistent WebView fetch FIRST in case Chromium's SQLite cookie jar
                // already holds a valid TLS-bound `cf_clearance` from a prior app launch!
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

                // 2b. Solve Cloudflare JS/Turnstile in a single 1x1 stealth WebView & capture DOM
                CloudflareSolver.resetPersistentWebView()
                val solved = CloudflareSolver.solveAndCaptureHtml(targetUrl, effectiveReferer)
                if (solved != null) {
                    if (!solved.userAgent.isNullOrBlank()) {
                        EgyDeadPrefs.savedUserAgent = solved.userAgent
                    }
                    if (!solved.cookies.isNullOrBlank()) {
                        EgyDeadPrefs.savedCookieHeader = solved.cookies
                        StickyCookieJar.importCookieHeader(solved.finalUrl, solved.cookies)
                    }
                    updateActiveDomainFromUrl(solved.finalUrl)

                    // Probe once whether OkHttp can reuse `cf_clearance` or if Cloudflare is JA4 TLS-bound
                    val okProbe = executeRawGet(targetUrl, referer = effectiveReferer, extraHeaders = headers)
                    if (okProbe != null && isValidEgyDeadHtml(okProbe.code, okProbe.body)) {
                        preferWebViewTransport = false
                        cfSolveEpoch.incrementAndGet()
                        updateActiveDomainFromUrl(okProbe.finalUrl)
                        return@withLock Jsoup.parse(okProbe.body, okProbe.finalUrl)
                    }

                    // OkHttp got 403 -> Cloudflare is TLS-bound! Switch to persistent WebView transport
                    // and return the HTML already captured by `solveAndCaptureHtml`!
                    preferWebViewTransport = true
                    cfSolveEpoch.incrementAndGet()
                    return@withLock Jsoup.parse(solved.html, solved.finalUrl)
                }

                // 2c. CloudStream CloudflareKiller fallback (e.g. when Activity is unavailable)
                val killed = executeRawGet(
                    targetUrl,
                    referer = effectiveReferer,
                    extraHeaders = headers,
                    client = cfKillerClient
                )
                if (killed != null && isValidEgyDeadHtml(killed.code, killed.body)) {
                    cfSolveEpoch.incrementAndGet()
                    updateActiveDomainFromUrl(killed.finalUrl)
                    return@withLock Jsoup.parse(killed.body, killed.finalUrl)
                }

                null
            }

            if (solvedInsideLock != null) return@withContext solvedInsideLock

            if (delegateToParallelWebView) {
                val parallelWv = CloudflareSolver.fetchViaPersistentWebView(
                    targetUrl = targetUrl,
                    method = "GET",
                    referer = effectiveReferer
                )
                if (parallelWv != null && isValidEgyDeadHtml(parallelWv.code, parallelWv.body)) {
                    updateActiveDomainFromUrl(parallelWv.finalUrl)
                    return@withContext Jsoup.parse(parallelWv.body, parallelWv.finalUrl)
                }
            }
        }

        if (direct != null && direct.body.isNotBlank() && !isCloudflareBlock(direct.code, direct.body)) {
            return@withContext Jsoup.parse(direct.body, direct.finalUrl)
        }
        null
    }

    /**
     * Executes an HTML form POST (such as `POST View=1` fallback) across both direct OkHttp
     * and TLS-bound persistent WebView transport.
     */
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
        val effectiveReferer = referer ?: targetUrl

        if (preferWebViewTransport && targetUrl.contains("egydead.", ignoreCase = true)) {
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

        val resp = executeRawPostForm(targetUrl, formFields, referer = effectiveReferer, extraHeaders = headers)
        if (resp != null && isValidEgyDeadHtml(resp.code, resp.body)) {
            return@withContext Jsoup.parse(resp.body, resp.finalUrl)
        }

        if (targetUrl.contains("egydead.", ignoreCase = true)) {
            // Ensure clearance via fetchDocument, then POST via persistent WebView
            fetchDocument(effectiveReferer, referer = "${getBaseUrl()}/")
            val wvRetry = CloudflareSolver.fetchViaPersistentWebView(
                targetUrl = targetUrl,
                method = "POST",
                formFields = formFields,
                referer = effectiveReferer
            )
            if (wvRetry != null && isValidEgyDeadHtml(wvRetry.code, wvRetry.body)) {
                return@withContext Jsoup.parse(wvRetry.body, wvRetry.finalUrl)
            }
        }

        if (resp != null && resp.body.isNotBlank() && !isCloudflareBlock(resp.code, resp.body)) {
            return@withContext Jsoup.parse(resp.body, resp.finalUrl)
        }
        null
    }
}
