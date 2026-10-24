package com.egydead

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Network & Cloudflare transport layer for EgyDead (v21).
 *
 * Why WebView transport is required for `egydead.*` HTML requests:
 * Cloudflare on `tv10.egydead.live` cryptographically binds `cf_clearance` to Android WebView's
 * Chromium BoringSSL JA3/JA4 TLS fingerprint + Client Hints (`Sec-CH-UA*`). Replaying a valid
 * WebView-captured `cf_clearance` cookie over OkHttp/Conscrypt TLS (`app.get` / `app.post`)
 * always receives HTTP 403 even with an identical `User-Agent`.
 *
 * Architecture:
 * 1. Keeps the proven background [CloudflareSolver] untouched (`0` modifications) to solve
 *    Turnstile and populate `CookieManager` + [EgyDeadPrefsEncoder].
 * 2. Executes all `egydead.*` GET and POST (`View=1` / AJAX) requests through a single hidden,
 *    persistent Chromium `WebView` (`window.fetch` anchored on same-origin `/__egydead_bridge__`,
 *    with automatic hidden `WebView` navigation fallback for cross-subdomain redirects), sharing
 *    the exact BoringSSL TLS stack, HTTP/2 connection pool, `User-Agent`, and `CookieManager`
 *    session as [CloudflareSolver].
 * 3. External video host extractors (`MegaMax`, `StreamHG`, `EarnVids`, `Dood`, etc.) continue
 *    using high-concurrency [httpClient] with [SmartDohDns].
 */
object EgyDeadNetwork {
    private const val TAG = "EgyDeadNet"
    private const val BRIDGE_PATH = "/__egydead_bridge__"
    private const val BRIDGE_HTML =
        "<!doctype html><html><head><meta charset=\"utf-8\"><link rel=\"icon\" href=\"data:,\"></head><body>ok</body></html>"

    const val DEFAULT_BASE_URL = "https://tv10.egydead.live"
    const val HOME_LANDING_PATH = "/h3/"

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private const val HOME_DOC_TTL_MS = 90_000L
    private const val FAILED_SOLVE_COOLDOWN_MS = 20_000L
    private const val WEBVIEW_FETCH_TIMEOUT_MS = 25_000L
    private const val WEBVIEW_NAV_TIMEOUT_MS = 20_000L

    @Volatile
    private var activeBaseUrl: String? = null

    @Volatile
    private var savedCookies: String? = null

    @Volatile
    private var lastFailedSolveAtMs: Long = 0L

    private val cfSolveEpoch = AtomicLong(0L)
    private val cfMutex = Mutex()
    private val homeDocMutex = Mutex()
    private val webViewInitMutex = Mutex()

    @Volatile
    private var cachedHomeDoc: Document? = null

    @Volatile
    private var cachedHomeDocAtMs: Long = 0L

    // Persistent hidden Chromium bridge WebView state (guarded by webViewInitMutex + main looper)
    @Volatile
    private var fetchWebView: WebView? = null

    @Volatile
    private var fetchAnchorOrigin: String? = null

    @Volatile
    private var webViewOwnerActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var bridgeReadyDeferred: CompletableDeferred<Boolean>? = null

    private val lifecycleRegistered = AtomicBoolean(false)
    private val jsFetchBridge = JsFetchBridge()

    val currentUserAgent: String
        get() = DEFAULT_USER_AGENT

    data class RawResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val headers: Headers
    )

    private data class SiteWebResult(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val error: String = ""
    )

    private class JsFetchBridge {
        val pending = ConcurrentHashMap<String, CompletableDeferred<SiteWebResult>>()

        @JavascriptInterface
        fun onComplete(reqId: String, status: Int, finalUrl: String, body: String, error: String) {
            pending.remove(reqId)?.complete(
                SiteWebResult(
                    code = status,
                    body = body,
                    finalUrl = finalUrl,
                    error = error
                )
            )
        }
    }

    private class NavDomBridge(
        private val onResult: (url: String, title: String, html: String) -> Unit
    ) {
        @JavascriptInterface
        fun onDom(url: String, title: String, html: String) {
            onResult(url, title, html)
        }
    }

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
        fetchAnchorOrigin = null
        StickyCookieJar.clear()
        runCatching {
            Handler(Looper.getMainLooper()).post {
                destroyFetchWebViewMainThread()
            }
        }
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
        } catch (_: Throwable) {
        }
    }

    internal fun encodeFormBody(formParams: Map<String, String>): String {
        return formParams.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    internal fun buildFetchConfigJson(
        reqId: String,
        url: String,
        method: String,
        referer: String?,
        body: String?,
        headers: Map<String, String>
    ): String {
        val hdrObj = JSONObject()
        for ((k, v) in headers) {
            val low = k.lowercase()
            if (low == "cookie" || low == "user-agent" || low == "host" || low == "origin" || low == "referer") {
                continue
            }
            hdrObj.put(k, v)
        }
        return JSONObject()
            .put("id", reqId)
            .put("url", url)
            .put("method", method)
            .put("referer", referer.orEmpty())
            .put("body", body.orEmpty())
            .put("headers", hdrObj)
            .toString()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
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
    // Cookie Persistence & CookieManager Synchronization
    // =========================================================================

    private fun resolveStoredCookieHeader(): String? {
        runCatching {
            val cmCookie = CookieManager.getInstance()?.getCookie(getBaseUrl())
            if (!cmCookie.isNullOrBlank() && cmCookie.contains("cf_clearance")) {
                savedCookies = cmCookie
                return cmCookie
            }
        }
        savedCookies?.takeIf { it.isNotBlank() }?.let { return it }
        EgyDeadPrefsEncoder.loadCookies()?.takeIf { it.isNotBlank() }?.let {
            savedCookies = it
            return it
        }
        EgyDeadPrefs.savedCookieHeader?.takeIf { it.isNotBlank() }?.let { return it }
        EgyDeadPrefs.cfClearance?.takeIf { it.isNotBlank() }?.let { return "cf_clearance=$it" }
        return null
    }

    private fun seedCookieManagerFromPrefsIfNeeded(targetOrigin: String) {
        runCatching {
            val cm = CookieManager.getInstance() ?: return
            val existing = cm.getCookie(targetOrigin)
            if (!existing.isNullOrBlank() && existing.contains("cf_clearance")) {
                savedCookies = existing
                return
            }
            val stored = EgyDeadPrefsEncoder.loadCookies() ?: EgyDeadPrefs.savedCookieHeader ?: return
            if (!stored.contains("cf_clearance")) return
            val host = runCatching { URI(targetOrigin).host }.getOrNull()
            val rootDomain = host?.split('.')?.takeLast(2)?.joinToString(".") ?: "egydead.live"
            for (part in stored.split(';')) {
                val kv = part.trim()
                if (kv.isBlank() || !kv.contains('=')) continue
                cm.setCookie(targetOrigin, "$kv; Domain=.$rootDomain; Path=/; Secure")
            }
            cm.flush()
        }
    }

    private fun syncCookiesFromCookieManager(targetUrl: String) {
        runCatching {
            val cm = CookieManager.getInstance() ?: return
            val cookieStr = cm.getCookie(targetUrl) ?: return
            if (cookieStr.contains("cf_clearance") && cookieStr != savedCookies) {
                savedCookies = cookieStr
                EgyDeadPrefsEncoder.saveCookies(cookieStr)
            }
        }
    }

    /**
     * Called ONLY when Chromium WebView itself received a Cloudflare challenge (proving any
     * existing `cf_clearance` in `CookieManager` is missing or genuinely expired) so that
     * [CloudflareSolver] solves a fresh Turnstile challenge instead of returning immediately
     * on a stale `cf_clearance` cookie.
     */
    private fun clearExpiredCfClearance(targetUrl: String) {
        savedCookies = null
        EgyDeadPrefs.clearCookies()
        runCatching {
            val cm = CookieManager.getInstance() ?: return
            val host = runCatching { URI(targetUrl).host }.getOrNull()
            val domains = buildList {
                add(".egydead.live")
                add("egydead.live")
                if (!host.isNullOrBlank()) {
                    add(host)
                    add(".$host")
                    val parts = host.split('.')
                    if (parts.size >= 2) {
                        val root = parts.takeLast(2).joinToString(".")
                        add(root)
                        add(".$root")
                    }
                }
            }.distinct()
            val urls = listOfNotNull(
                targetUrl,
                originOf(targetUrl),
                "https://egydead.live",
                DEFAULT_BASE_URL
            ).distinct()
            for (u in urls) {
                cm.setCookie(u, "cf_clearance=; Max-Age=0; Path=/")
                cm.setCookie(u, "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                for (d in domains) {
                    cm.setCookie(u, "cf_clearance=; Max-Age=0; Domain=$d; Path=/")
                    cm.setCookie(u, "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$d; Path=/")
                }
            }
            cm.flush()
        }
    }

    // =========================================================================
    // Raw OkHttp Helpers (used by external video extractors & headless tests)
    // =========================================================================

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
    // Persistent Hidden Chromium WebView Transport (`window.fetch` + Nav Fallback)
    // =========================================================================

    private fun resolveActivity(): Activity? {
        return try {
            CommonActivity.activity?.takeIf { !it.isFinishing && !it.isDestroyed }
        } catch (_: Throwable) {
            null
        }
    }

    private fun registerLifecycleCleanupIfNeeded(activity: Activity) {
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        runCatching {
            activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityResumed(a: Activity) = Unit
                override fun onActivityPaused(a: Activity) = Unit
                override fun onActivityStopped(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(a: Activity) {
                    if (webViewOwnerActivityRef?.get() === a) {
                        destroyFetchWebViewMainThread()
                    }
                }
            })
        }
    }

    private fun destroyFetchWebViewMainThread() {
        val wv = fetchWebView
        fetchWebView = null
        fetchAnchorOrigin = null
        webViewOwnerActivityRef = null
        runCatching {
            (wv?.parent as? ViewGroup)?.removeView(wv)
            wv?.stopLoading()
            wv?.destroy()
        }
    }

    private suspend fun ensureFetchWebView(activity: Activity, targetOrigin: String): WebView? =
        webViewInitMutex.withLock {
            val cleanOrigin = targetOrigin.trimEnd('/')
            seedCookieManagerFromPrefsIfNeeded(cleanOrigin)

            val existing = fetchWebView
            val existingOwner = webViewOwnerActivityRef?.get()
            if (existing != null &&
                existingOwner === activity &&
                !activity.isFinishing &&
                !activity.isDestroyed &&
                fetchAnchorOrigin == cleanOrigin
            ) {
                return@withLock existing
            }

            val readyDef = CompletableDeferred<Boolean>()
            bridgeReadyDeferred = readyDef

            val created = withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext null
                registerLifecycleCleanupIfNeeded(activity)

                var wv = fetchWebView
                if (wv == null || webViewOwnerActivityRef?.get() !== activity) {
                    destroyFetchWebViewMainThread()
                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                        ?: return@withContext null

                    wv = WebView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        alpha = 0f
                        translationX = 10000f
                        translationY = 10000f
                        isFocusable = false
                        isFocusableInTouchMode = false
                        isClickable = false

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            userAgentString = DEFAULT_USER_AGENT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }

                        addJavascriptInterface(jsFetchBridge, "EgyBridge")

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val path = request?.url?.path ?: return null
                                if (path == BRIDGE_PATH) {
                                    return WebResourceResponse(
                                        "text/html",
                                        "utf-8",
                                        200,
                                        "OK",
                                        mapOf("Cache-Control" to "no-store"),
                                        ByteArrayInputStream(BRIDGE_HTML.toByteArray(Charsets.UTF_8))
                                    )
                                }
                                if (path == "/favicon.ico") {
                                    return WebResourceResponse(
                                        "image/x-icon",
                                        "utf-8",
                                        204,
                                        "No Content",
                                        mapOf("Cache-Control" to "max-age=86400"),
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null && url.contains(BRIDGE_PATH)) {
                                    bridgeReadyDeferred?.complete(true)
                                }
                            }
                        }
                    }

                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)

                    rootView.addView(wv)
                    fetchWebView = wv
                    webViewOwnerActivityRef = WeakReference(activity)
                }

                wv.loadUrl("$cleanOrigin$BRIDGE_PATH")
                wv
            } ?: return@withLock null

            val anchored = withTimeoutOrNull(6_000L) { readyDef.await() } == true
            if (anchored) {
                fetchAnchorOrigin = cleanOrigin
                Log.d(TAG, "🌐 WebView bridge anchored at $cleanOrigin")
            }
            created
        }

    private suspend fun executeWebViewFetch(
        activity: Activity,
        method: String,
        targetUrl: String,
        formParams: Map<String, String>?,
        referer: String?,
        extraHeaders: Map<String, String>
    ): SiteWebResult? {
        val targetOrigin = originOf(targetUrl) ?: getBaseUrl()
        val wv = ensureFetchWebView(activity, targetOrigin) ?: return null

        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<SiteWebResult>()
        jsFetchBridge.pending[reqId] = deferred

        val fetchHeaders = LinkedHashMap<String, String>().apply {
            put(
                "Accept",
                extraHeaders["Accept"]
                    ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            put("Accept-Language", extraHeaders["Accept-Language"] ?: "ar,en-US;q=0.9,en;q=0.8")
            if (method.equals("POST", ignoreCase = true)) {
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                put("X-Requested-With", "XMLHttpRequest")
            }
            putAll(extraHeaders)
        }

        val formBody = if (method.equals("POST", ignoreCase = true) && !formParams.isNullOrEmpty()) {
            encodeFormBody(formParams)
        } else {
            null
        }

        val cfgJson = buildFetchConfigJson(
            reqId = reqId,
            url = targetUrl,
            method = method.uppercase(),
            referer = referer?.let { encodeUrl(it) },
            body = formBody,
            headers = fetchHeaders
        )

        val jsCode = """
            (function(cfg) {
              try {
                var opts = {
                  method: cfg.method || "GET",
                  credentials: "include",
                  redirect: "follow",
                  headers: cfg.headers || {}
                };
                if (cfg.referer) {
                  try {
                    var refU = new URL(cfg.referer, location.href);
                    if (refU.origin === location.origin) {
                      opts.referrer = refU.href;
                    }
                  } catch (_e) {}
                }
                if (opts.method === "POST" && typeof cfg.body === "string") {
                  opts.body = cfg.body;
                }
                window.fetch(cfg.url, opts).then(function(resp) {
                  return resp.text().then(function(txt) {
                    EgyBridge.onComplete(cfg.id, resp.status || 0, resp.url || cfg.url, txt || "", "");
                  });
                }).catch(function(err) {
                  EgyBridge.onComplete(cfg.id, 0, cfg.url, "", String(err || "fetch_error"));
                });
              } catch (outerErr) {
                EgyBridge.onComplete(cfg.id, 0, cfg.url, "", String(outerErr || "outer_error"));
              }
            })($cfgJson);
        """.trimIndent()

        return try {
            withContext(Dispatchers.Main) {
                wv.evaluateJavascript(jsCode, null)
            }
            val res = withTimeoutOrNull(WEBVIEW_FETCH_TIMEOUT_MS) { deferred.await() }
            if (res != null && res.code in 200..299 && !isCloudflareBlock(res.code, res.body)) {
                syncCookiesFromCookieManager(res.finalUrl.ifBlank { targetUrl })
                updateActiveDomainFromUrl(res.finalUrl.ifBlank { targetUrl })
            }
            res
        } catch (t: Throwable) {
            Log.w(TAG, "executeWebViewFetch error for $targetUrl: ${t.message}")
            null
        } finally {
            jsFetchBridge.pending.remove(reqId)
        }
    }

    /**
     * Hidden 1-shot document navigation fallback (`Sec-Fetch-Mode: navigate`) used when
     * `window.fetch` hits a cross-subdomain redirect (`tv10 -> tv11`, `code == 0`) or a
     * navigation-only WAF check while `cf_clearance` is already present.
     */
    private suspend fun loadHtmlViaWebViewNavigation(
        activity: Activity,
        targetUrl: String
    ): SiteWebResult? {
        val resultDef = CompletableDeferred<SiteWebResult>()
        var tempWebView: WebView? = null
        var rootGroup: ViewGroup? = null
        val finished = AtomicBoolean(false)

        fun cleanupAndComplete(res: SiteWebResult) {
            if (!finished.compareAndSet(false, true)) return
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    tempWebView?.let { wv ->
                        rootGroup?.removeView(wv)
                        wv.stopLoading()
                        wv.destroy()
                    }
                }
            }
            resultDef.complete(res)
        }

        val started = withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext false
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@withContext false
            rootGroup = root

            val wv = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                alpha = 0f
                translationX = 10000f
                translationY = 10000f
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = DEFAULT_USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                addJavascriptInterface(
                    NavDomBridge { landedUrl, title, html ->
                        val finalU = landedUrl.ifBlank { targetUrl }
                        val blocked = isCloudflareBlock(200, html) ||
                            title.contains("Just a moment", ignoreCase = true) ||
                            title.contains("لحظة")
                        val status = if (blocked) 403 else if (html.isNotBlank()) 200 else 0
                        if (status == 200) {
                            syncCookiesFromCookieManager(finalU)
                            updateActiveDomainFromUrl(finalU)
                        }
                        cleanupAndComplete(SiteWebResult(status, html, finalU))
                    },
                    "EgyNavBridge"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (finished.get() || view == null) return
                        view.evaluateJavascript(
                            "(function(){try{EgyNavBridge.onDom(location.href||'',document.title||'',document.documentElement?document.documentElement.outerHTML:'');}catch(e){EgyNavBridge.onDom('','','');}})();",
                            null
                        )
                    }
                }
            }

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            tempWebView = wv
            root.addView(wv)
            wv.loadUrl(targetUrl)
            true
        }

        if (!started) return null

        return withTimeoutOrNull(WEBVIEW_NAV_TIMEOUT_MS) {
            resultDef.await()
        } ?: run {
            cleanupAndComplete(SiteWebResult(0, "", targetUrl, "nav_timeout"))
            null
        }
    }

    // =========================================================================
    // Single-Flight Background CloudflareSolver Coordination
    // =========================================================================

    private suspend fun runSingleFlightSolverIfNeeded(
        activity: Activity,
        initialUrl: String,
        epochBefore: Long,
        clearStaleCookie: Boolean
    ): String {
        var updatedUrl = initialUrl
        cfMutex.withLock {
            if (cfSolveEpoch.get() != epochBefore) {
                updatedUrl = rewriteToActiveBase(updatedUrl)
                return@withLock
            }
            val now = System.currentTimeMillis()
            if (lastFailedSolveAtMs > 0L && now - lastFailedSolveAtMs < FAILED_SOLVE_COOLDOWN_MS) {
                return@withLock
            }
            if (activity.isFinishing || activity.isDestroyed) {
                return@withLock
            }

            if (clearStaleCookie) {
                clearExpiredCfClearance(updatedUrl)
            }

            Log.w(TAG, "🛡️ Cloudflare challenge active on WebView, invoking CloudflareSolver for $updatedUrl")
            val solver = CloudflareSolver.solve(activity, updatedUrl, DEFAULT_USER_AGENT)
            if (solver != null) {
                if (solver.finalUrl.isNotBlank() && solver.finalUrl != updatedUrl) {
                    updateActiveDomainFromUrl(solver.finalUrl)
                    updatedUrl = rewriteToActiveBase(updatedUrl)
                }
                if (!solver.cookies.isNullOrEmpty() && solver.cookies.contains("cf_clearance")) {
                    savedCookies = solver.cookies
                    EgyDeadPrefsEncoder.saveCookies(solver.cookies)
                    lastFailedSolveAtMs = 0L
                    cfSolveEpoch.incrementAndGet()
                } else {
                    lastFailedSolveAtMs = System.currentTimeMillis()
                }
            } else {
                lastFailedSolveAtMs = System.currentTimeMillis()
            }
        }
        return updatedUrl
    }

    private fun hasCookieManagerClearance(url: String): Boolean = runCatching {
        CookieManager.getInstance()?.getCookie(url)?.contains("cf_clearance") == true
    }.getOrDefault(false)

    private suspend fun executeSiteRequestOnAndroid(
        activity: Activity,
        method: String,
        initialUrl: String,
        formParams: Map<String, String>?,
        referer: String?,
        headers: Map<String, String>
    ): Document? {
        var currentUrl = initialUrl
        val isGet = method.equals("GET", ignoreCase = true)
        val epochBefore = cfSolveEpoch.get()

        // 1. Primary fast path: persistent Chromium WebView `window.fetch` (BoringSSL + HTTP/2)
        var res = executeWebViewFetch(activity, method, currentUrl, formParams, referer, headers)

        // 2. If `window.fetch` hit a cross-subdomain redirect (`code == 0`) or a navigation-only
        //    WAF check while `cf_clearance` is already present, try 1-shot WebView navigation.
        if (isGet && (res == null || res.code == 0 || (isCloudflareBlock(res.code, res.body) && hasCookieManagerClearance(currentUrl)))) {
            val navRes = loadHtmlViaWebViewNavigation(activity, currentUrl)
            if (navRes != null && navRes.code != 0) {
                res = navRes
                currentUrl = rewriteToActiveBase(currentUrl)
            }
        }

        // 3. If WebView itself received a Cloudflare challenge (cold start or expired cookie),
        //    clear any stale `cf_clearance`, run single-flight `CloudflareSolver`, and retry.
        if (res == null || res.code == 0 || isCloudflareBlock(res.code, res.body)) {
            val hadClearanceBefore = hasCookieManagerClearance(currentUrl)
            currentUrl = runSingleFlightSolverIfNeeded(
                activity = activity,
                initialUrl = currentUrl,
                epochBefore = epochBefore,
                clearStaleCookie = hadClearanceBefore
            )
            res = executeWebViewFetch(activity, method, currentUrl, formParams, referer, headers)
            if (isGet && (res == null || res.code == 0 || isCloudflareBlock(res.code, res.body))) {
                val navRetry = loadHtmlViaWebViewNavigation(activity, currentUrl)
                if (navRetry != null && navRetry.code != 0) {
                    res = navRetry
                }
            }
        }

        val finalRes = res ?: return null
        return if (finalRes.code in 200..299 && finalRes.body.isNotBlank() && !isCloudflareBlock(finalRes.code, finalRes.body)) {
            val resolvedUrl = finalRes.finalUrl.ifBlank { currentUrl }
            updateActiveDomainFromUrl(resolvedUrl)
            Jsoup.parse(finalRes.body, resolvedUrl)
        } else {
            Log.w(TAG, "Site $method returned non-usable status=${finalRes.code} len=${finalRes.body.length} for $currentUrl")
            null
        }
    }

    // =========================================================================
    // Public Document Entry Points (`getHomeLandingDocument`, `fetchDocument`, `postDocument`)
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
        val currentUrl = if (isSiteUrl) rewriteToActiveBase(url) else encodeUrl(url)

        if (!isSiteUrl) {
            val ext = executeRawGet(currentUrl, referer = referer, extraHeaders = headers)
            return@withContext if (ext != null && ext.body.isNotBlank() && !isCloudflareBlock(ext.code, ext.body)) {
                Jsoup.parse(ext.body, ext.finalUrl)
            } else {
                null
            }
        }

        val activity = resolveActivity()
        if (activity != null && !EgyDeadPrefs.useProxy) {
            return@withContext executeSiteRequestOnAndroid(
                activity = activity,
                method = "GET",
                initialUrl = currentUrl,
                formParams = null,
                referer = referer,
                headers = headers
            )
        }

        // Headless / custom-proxy fallback
        try {
            val reqHeaders = buildSiteHeaders(referer, headers)
            val res = app.get(currentUrl, headers = reqHeaders, timeout = 30)
            if (res.code in 200..299 && res.text.isNotBlank() && !isCloudflareBlock(res.code, res.text)) {
                updateActiveDomainFromUrl(res.url)
                res.document
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Headless cfGet failed for $currentUrl: ${t.message}")
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
        val currentUrl = if (isSiteUrl) rewriteToActiveBase(url) else encodeUrl(url)

        if (!isSiteUrl) {
            val ext = executeRawPostForm(currentUrl, formParams, referer = referer, extraHeaders = headers)
            return@withContext if (ext != null && ext.body.isNotBlank() && !isCloudflareBlock(ext.code, ext.body)) {
                Jsoup.parse(ext.body, ext.finalUrl)
            } else {
                null
            }
        }

        val postExtra = LinkedHashMap<String, String>().apply {
            put("X-Requested-With", "XMLHttpRequest")
            putAll(headers)
        }

        val activity = resolveActivity()
        if (activity != null && !EgyDeadPrefs.useProxy) {
            return@withContext executeSiteRequestOnAndroid(
                activity = activity,
                method = "POST",
                initialUrl = currentUrl,
                formParams = formParams,
                referer = referer ?: currentUrl,
                headers = postExtra
            )
        }

        // Headless / custom-proxy fallback
        try {
            val reqHeaders = buildSiteHeaders(referer ?: currentUrl, postExtra)
            val res = app.post(currentUrl, data = formParams, headers = reqHeaders, timeout = 30)
            if (res.code in 200..299 && res.text.isNotBlank() && !isCloudflareBlock(res.code, res.text)) {
                updateActiveDomainFromUrl(res.url)
                res.document
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Headless cfPost failed for $currentUrl: ${t.message}")
            null
        }
    }
}
