package com.egydead

import android.app.Activity
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Network & Cloudflare transport layer for EgyDead (v20), restored to use the proven
 * pre-rewrite [CloudflareSolver] + `app.get` / `app.post` (`cfGet` / `cfPost`) cookie-hunting
 * flow with 7-day `cf_clearance` persistence via [EgyDeadPrefsEncoder].
 */
object EgyDeadNetwork {
    private const val TAG = "EgyDeadNet"

    const val DEFAULT_BASE_URL = "https://tv10.egydead.live"
    const val HOME_LANDING_PATH = "/h3/"

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private const val HOME_DOC_TTL_MS = 90_000L
    private const val FAILED_SOLVE_COOLDOWN_MS = 30_000L

    @Volatile
    private var activeBaseUrl: String? = null

    @Volatile
    private var savedCookies: String? = null

    @Volatile
    private var lastFailedSolveAtMs: Long = 0L

    private val cfSolveEpoch = AtomicLong(0L)
    private val cfMutex = Mutex()
    private val homeDocMutex = Mutex()

    @Volatile
    private var cachedHomeDoc: Document? = null

    @Volatile
    private var cachedHomeDocAtMs: Long = 0L

    val currentUserAgent: String
        get() = DEFAULT_USER_AGENT

    data class RawResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val headers: Headers
    )

    // =========================================================================
    // Sticky Cookie Jar (for OkHttp extractor client)
    // =========================================================================

    object StickyCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostMap = store.getOrPut(url.host.lowercase()) { ConcurrentHashMap() }
            for (c in cookies) {
                hostMap[c.name] = c.value
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostMap = store[url.host.lowercase()] ?: return emptyList()
            return hostMap.mapNotNull { (name, value) ->
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

        fun clear() {
            store.clear()
        }
    }

    // =========================================================================
    // DNS-over-HTTPS + OkHttp Client (used by external video host extractors)
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
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
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
                cachedClientKey = key
                return built
            }
        }

    // =========================================================================
    // URL Normalization & Domain Helpers
    // =========================================================================

    fun invalidateDomainCache() {
        activeBaseUrl = null
        savedCookies = null
        lastFailedSolveAtMs = 0L
        cachedHomeDoc = null
        cachedHomeDocAtMs = 0L
        cachedClientKey = null
        cachedClient = null
        StickyCookieJar.clear()
    }

    fun getImageHeaders(): Map<String, String> {
        val base = getBaseUrl()
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = DEFAULT_USER_AGENT
        map["Referer"] = "$base/"
        resolveStoredCookieHeader()?.let { map["Cookie"] = it }
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
        try {
            val nh = URL(landedUrl).host
            if (nh.isNotBlank() && nh.contains("egydead.", ignoreCase = true) &&
                !nh.equals("egydead.live", ignoreCase = true) &&
                !nh.equals("www.egydead.live", ignoreCase = true)
            ) {
                val newOrigin = "https://$nh"
                activeBaseUrl = newOrigin
                EgyDeadPrefs.discoveredDomain = newOrigin
            }
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // Cloudflare Detection & HTML Validation
    // =========================================================================

    fun isCloudflareBlock(code: Int, body: String): Boolean {
        val hasPositiveDom = body.contains("movieItem") ||
            body.contains("singleTitle") ||
            body.contains("serversList") ||
            body.contains("ServersList") ||
            body.contains("EpsList")
        if (code in 200..299 && hasPositiveDom) return false
        if (code in listOf(403, 503, 429)) return true
        return !hasPositiveDom && (
            body.contains("Just a moment", ignoreCase = true) ||
                body.contains("Attention Required", ignoreCase = true) ||
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
    // Raw OkHttp Helpers (used by external video extractors)
    // =========================================================================

    private fun resolveStoredCookieHeader(): String? {
        savedCookies?.takeIf { it.isNotBlank() }?.let { return it }
        EgyDeadPrefsEncoder.loadCookies()?.takeIf { it.isNotBlank() }?.let {
            savedCookies = it
            return it
        }
        EgyDeadPrefs.savedCookieHeader?.takeIf { it.isNotBlank() }?.let { return it }
        EgyDeadPrefs.cfClearance?.takeIf { it.isNotBlank() }?.let { return "cf_clearance=$it" }
        return null
    }

    private fun buildSiteHeaders(
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): MutableMap<String, String> {
        val base = getBaseUrl()
        val headers = mutableMapOf(
            "User-Agent" to DEFAULT_USER_AGENT,
            "Referer" to encodeUrl(referer ?: base),
            "Accept-Language" to "ar,en-US;q=0.9"
        )
        resolveStoredCookieHeader()?.let { headers["Cookie"] = it }
        headers.putAll(extraHeaders)
        return headers
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
            .header("User-Agent", extraHeaders["User-Agent"] ?: DEFAULT_USER_AGENT)
            .header(
                "Accept",
                extraHeaders["Accept"]
                    ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            .header("Accept-Language", extraHeaders["Accept-Language"] ?: "ar,en-US;q=0.9,en;q=0.8")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", encodeUrl(referer))
        }
        if (!extraHeaders.containsKey("Cookie") && safeUrl.contains("egydead", ignoreCase = true)) {
            resolveStoredCookieHeader()?.let { reqBuilder.header("Cookie", it) }
        }
        for ((k, v) in extraHeaders) {
            reqBuilder.header(k, v)
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                RawResponse(
                    code = resp.code,
                    body = resp.body?.string().orEmpty(),
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
            .header("User-Agent", extraHeaders["User-Agent"] ?: DEFAULT_USER_AGENT)
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
        if (!extraHeaders.containsKey("Cookie") && safeUrl.contains("egydead", ignoreCase = true)) {
            resolveStoredCookieHeader()?.let { reqBuilder.header("Cookie", it) }
        }
        for ((k, v) in extraHeaders) {
            reqBuilder.header(k, v)
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                RawResponse(
                    code = resp.code,
                    body = resp.body?.string().orEmpty(),
                    finalUrl = resp.request.url.toString(),
                    headers = resp.headers
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "POST failed for $safeUrl: ${t.message}")
            null
        }
    }

    // =========================================================================
    // Pre-Rewrite cfGet / cfPost Flow (app.get / app.post + CloudflareSolver)
    // =========================================================================

    private suspend fun runSingleFlightSolverIfNeeded(initialUrl: String, epochBefore: Long): String {
        var updatedUrl = initialUrl
        cfMutex.withLock {
            if (cfSolveEpoch.get() != epochBefore) {
                updatedUrl = rewriteToActiveBase(updatedUrl)
                return@withLock
            }
            val cmCookie = runCatching { CookieManager.getInstance()?.getCookie(updatedUrl) }.getOrNull()
            if (cmCookie != null && cmCookie != savedCookies && cmCookie.contains("cf_clearance")) {
                savedCookies = cmCookie
                EgyDeadPrefsEncoder.saveCookies(cmCookie)
                cfSolveEpoch.incrementAndGet()
                return@withLock
            }
            val now = System.currentTimeMillis()
            if (lastFailedSolveAtMs > 0L && now - lastFailedSolveAtMs < FAILED_SOLVE_COOLDOWN_MS) {
                return@withLock
            }
            val activity = CommonActivity.activity ?: (EgyDeadPrefs.appContext as? Activity)
            if (activity != null && !activity.isFinishing) {
                val solver = CloudflareSolver.solve(activity, updatedUrl, DEFAULT_USER_AGENT)
                if (solver != null) {
                    if (!solver.cookies.isNullOrEmpty() && solver.cookies.contains("cf_clearance")) {
                        savedCookies = solver.cookies
                        EgyDeadPrefsEncoder.saveCookies(solver.cookies)
                        lastFailedSolveAtMs = 0L
                        cfSolveEpoch.incrementAndGet()
                    } else {
                        lastFailedSolveAtMs = System.currentTimeMillis()
                    }
                    if (solver.finalUrl.isNotBlank() && solver.finalUrl != updatedUrl) {
                        updateActiveDomainFromUrl(solver.finalUrl)
                        updatedUrl = solver.finalUrl
                    }
                } else {
                    lastFailedSolveAtMs = System.currentTimeMillis()
                }
            }
        }
        return updatedUrl
    }

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
        var currentUrl = if (isSiteUrl) rewriteToActiveBase(url) else encodeUrl(url)

        if (!isSiteUrl) {
            val ext = executeRawGet(currentUrl, referer = referer, extraHeaders = headers)
            return@withContext if (ext != null && ext.body.isNotBlank() && !isCloudflareBlock(ext.code, ext.body)) {
                Jsoup.parse(ext.body, ext.finalUrl)
            } else {
                null
            }
        }

        try {
            var reqHeaders = buildSiteHeaders(referer, headers)
            var res = app.get(currentUrl, headers = reqHeaders, timeout = 30)
            if (isCloudflareBlock(res.code, res.text)) {
                val epochBefore = cfSolveEpoch.get()
                currentUrl = runSingleFlightSolverIfNeeded(currentUrl, epochBefore)
                reqHeaders = buildSiteHeaders(referer, headers)
                res = app.get(currentUrl, headers = reqHeaders, timeout = 30)
            }
            if (res.text.isNotBlank() && !isCloudflareBlock(res.code, res.text)) {
                updateActiveDomainFromUrl(res.url)
                res.document
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cfGet failed for $currentUrl: ${t.message}")
            null
        }
    }

    suspend fun postDocument(
        url: String,
        formParams: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Document? = withContext(Dispatchers.IO) {
        val isSiteUrl = url.contains("egydead.", ignoreCase = true) || url.startsWith("/")
        var currentUrl = if (isSiteUrl) rewriteToActiveBase(url) else encodeUrl(url)

        if (!isSiteUrl) {
            val ext = executeRawPostForm(currentUrl, formParams, referer = referer, extraHeaders = headers)
            return@withContext if (ext != null && ext.body.isNotBlank() && !isCloudflareBlock(ext.code, ext.body)) {
                Jsoup.parse(ext.body, ext.finalUrl)
            } else {
                null
            }
        }

        try {
            val postExtra = LinkedHashMap<String, String>().apply {
                put("X-Requested-With", "XMLHttpRequest")
                putAll(headers)
            }
            var reqHeaders = buildSiteHeaders(referer ?: currentUrl, postExtra)
            var res = app.post(currentUrl, data = formParams, headers = reqHeaders, timeout = 30)
            if (isCloudflareBlock(res.code, res.text)) {
                val epochBefore = cfSolveEpoch.get()
                currentUrl = runSingleFlightSolverIfNeeded(currentUrl, epochBefore)
                reqHeaders = buildSiteHeaders(referer ?: currentUrl, postExtra)
                res = app.post(currentUrl, data = formParams, headers = reqHeaders, timeout = 30)
            }
            if (res.text.isNotBlank() && !isCloudflareBlock(res.code, res.text)) {
                updateActiveDomainFromUrl(res.url)
                res.document
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cfPost failed for $currentUrl: ${t.message}")
            null
        }
    }
}
