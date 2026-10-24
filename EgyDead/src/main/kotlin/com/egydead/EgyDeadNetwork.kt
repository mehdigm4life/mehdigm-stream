package com.egydead

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.FormBody
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
import java.util.concurrent.TimeUnit

/**
 * Resilient network & dynamic domain discovery engine for EgyDead:
 * 1. Discovers the active `tvXX.egydead.live` mirror automatically via `egydead.live` /
 *    `egydead.fyi` redirect beacons (stripping landing suffixes such as `/h3/`).
 * 2. Bypasses Arab ISP DNS poisoning via Cloudflare (`1.1.1.1`) & Google (`8.8.8.8`) DoH.
 * 3. Validates saved/custom domains so dead legacy mirrors (e.g. `tv5.egydead.live`)
 *    never break users upgrading from older versions.
 * 4. Integrates with `CloudflareSolver` (Android WebView) when Cloudflare JS/Turnstile challenges occur.
 */
object EgyDeadNetwork {
    private const val TAG = "EgyDeadNet"

    const val DEFAULT_BASE_URL = "https://tv10.egydead.live"

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val BEACON_DOMAINS = listOf(
        "https://tv10.egydead.live",
        "https://egydead.live",
        "https://egydead.fyi",
        "https://tv11.egydead.live",
        "https://tv12.egydead.live",
        "https://tv9.egydead.live"
    )

    private const val DOMAIN_CACHE_TTL_MS = 30 * 60 * 1000L

    private val domainMutex = Mutex()

    @Volatile
    private var resolvedBaseUrl: String? = null

    @Volatile
    private var resolvedAtMs: Long = 0L

    val currentUserAgent: String
        get() = EgyDeadPrefs.savedUserAgent ?: DEFAULT_UA

    // ---- Hybrid DNS (System DNS -> Cloudflare 1.1.1.1 DoH -> Google 8.8.8.8 DoH) ----

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
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
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("8.8.4.4")
            )
            .build()
    }

    val hybridDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            runCatching { Dns.SYSTEM.lookup(hostname) }
                .getOrNull()
                ?.takeIf { list -> list.isNotEmpty() && list.none { it.isLoopbackAddress || it.isAnyLocalAddress } }
                ?.let { return it }

            runCatching { cloudflareDoh.lookup(hostname) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            return googleDoh.lookup(hostname)
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hybridDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 3, TimeUnit.MINUTES))
            .build()
    }

    data class RawHttpResult(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val headers: Map<String, List<String>>
    )

    fun originOf(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(encodeUrl(rawUrl.trim()))
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val portPart = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$portPart"
        }.getOrNull()
    }

    /**
     * Percent-encodes non-ASCII (Arabic) characters while preserving existing `%XX` escapes.
     */
    fun encodeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val sb = StringBuilder(trimmed.length + 16)
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            when {
                ch == '%' && i + 2 < trimmed.length &&
                    trimmed[i + 1].isHexDigit() && trimmed[i + 2].isHexDigit() -> {
                    sb.append(trimmed, i, i + 3)
                    i += 3
                }
                ch == ' ' -> {
                    sb.append("%20")
                    i++
                }
                ch.code > 127 || ch == '"' || ch == '<' || ch == '>' || ch == '\\' ||
                    ch == '^' || ch == '`' || ch == '{' || ch == '|' || ch == '}' -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append(String.format("%02X", b.toInt() and 0xFF))
                    }
                    i++
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Converts legacy singular paths (`/episode/`, `/season/`, `/serie/`, `/assembly/`)
     * to current plural EgyDead paths (`/episodes/`, `/seasons/`, `/Series/`, `/film-series/`).
     */
    fun upgradeLegacyPath(url: String): String =
        url
            .replace(Regex("(?i)/episode/"), "/episodes/")
            .replace(Regex("(?i)/season/"), "/seasons/")
            .replace(Regex("(?i)/serie/"), "/Series/")
            .replace(Regex("(?i)/series/"), "/Series/")
            .replace(Regex("(?i)/assembly/"), "/film-series/")

    fun isCloudflareBlock(code: Int, body: String): Boolean {
        if (code in listOf(403, 429, 503)) {
            if (
                body.contains("cf-mitigated", ignoreCase = true) ||
                body.contains("Just a moment", ignoreCase = true) ||
                body.contains("challenge-platform", ignoreCase = true) ||
                body.contains("cf_chl_opt", ignoreCase = true) ||
                body.contains("cf-browser-verification", ignoreCase = true) ||
                body.contains("Attention Required! | Cloudflare", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    fun isValidEgyDeadHtml(body: String): Boolean {
        if (body.length < 400) return false
        return body.contains("movieItem", ignoreCase = true) ||
            body.contains("singleTitle", ignoreCase = true) ||
            body.contains("EpsList", ignoreCase = true) ||
            body.contains("watch_player", ignoreCase = true) ||
            body.contains("serversList", ignoreCase = true) ||
            body.contains("donwload-servers-list", ignoreCase = true) ||
            body.contains("salery-list", ignoreCase = true) ||
            body.contains("EgyDead", ignoreCase = true) ||
            body.contains("ايجي ديد", ignoreCase = true)
    }

    fun invalidateDomainCache() {
        resolvedBaseUrl = null
        resolvedAtMs = 0L
    }

    suspend fun getBaseUrl(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        val cached = resolvedBaseUrl
        if (!forceRefresh && !cached.isNullOrBlank() && (now - resolvedAtMs) < DOMAIN_CACHE_TTL_MS) {
            return cached
        }
        return domainMutex.withLock {
            val again = resolvedBaseUrl
            if (!forceRefresh && !again.isNullOrBlank() && (System.currentTimeMillis() - resolvedAtMs) < DOMAIN_CACHE_TTL_MS) {
                return@withLock again
            }
            val discovered = discoverLiveDomain()
            resolvedBaseUrl = discovered
            resolvedAtMs = System.currentTimeMillis()
            EgyDeadPrefs.discoveredDomain = discovered
            discovered
        }
    }

    private suspend fun discoverLiveDomain(): String = withContext(Dispatchers.IO) {
        val candidates = LinkedHashSet<String>()
        EgyDeadPrefs.customDomain?.takeIf { it.startsWith("http") }?.let { candidates.add(it.trimEnd('/')) }
        EgyDeadPrefs.discoveredDomain?.takeIf { it.startsWith("http") }?.let { candidates.add(it.trimEnd('/')) }
        BEACON_DOMAINS.forEach { candidates.add(it.trimEnd('/')) }

        for (candidate in candidates) {
            val probe = executeRawGet("$candidate/", referer = "$candidate/", timeoutSec = 10) ?: continue
            val landedOrigin = originOf(probe.finalUrl) ?: originOf(candidate) ?: continue
            if (probe.code in 200..299 && isValidEgyDeadHtml(probe.body) && !isCloudflareBlock(probe.code, probe.body)) {
                runCatching { Log.d(TAG, "Discovered live EgyDead origin: $landedOrigin (from $candidate)") }
                return@withContext landedOrigin
            }
            if (landedOrigin != candidate.trimEnd('/') && landedOrigin.contains("egydead.")) {
                return@withContext landedOrigin
            }
        }
        DEFAULT_BASE_URL
    }

    suspend fun rewriteToActiveBase(rawUrl: String): String {
        val activeBase = getBaseUrl()
        val upgraded = upgradeLegacyPath(rawUrl.trim())
        if (upgraded.isEmpty()) return activeBase
        if (upgraded.startsWith("/")) return encodeUrl("$activeBase$upgraded")
        if (!upgraded.startsWith("http://") && !upgraded.startsWith("https://")) {
            return encodeUrl("$activeBase/${upgraded.trimStart('/')}")
        }
        val encoded = encodeUrl(upgraded)
        return runCatching {
            val uri = URI(encoded)
            val host = uri.host?.lowercase().orEmpty()
            if (host.contains("egydead.")) {
                val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
                val query = uri.rawQuery?.let { "?$it" } ?: ""
                "$activeBase$path$query"
            } else {
                encoded
            }
        }.getOrDefault(encoded)
    }

    private fun resolveClient(timeoutSec: Long): OkHttpClient {
        val customProxyStr = if (EgyDeadPrefs.useProxy) EgyDeadPrefs.customProxy else null
        val customProxy = parseUserProxy(customProxyStr)
        if (customProxy == null && timeoutSec == 20L) return httpClient
        return httpClient.newBuilder().apply {
            if (timeoutSec != 20L) {
                connectTimeout(timeoutSec, TimeUnit.SECONDS)
                readTimeout(timeoutSec, TimeUnit.SECONDS)
            }
            if (customProxy != null) proxy(customProxy)
        }.build()
    }

    private fun parseUserProxy(spec: String?): Proxy? = runCatching {
        if (spec.isNullOrBlank()) return null
        val s = spec.trim()
        val isSocks = s.startsWith("socks", ignoreCase = true)
        val hp = s.substringAfter("://", s)
        val host = hp.substringBefore(':').trim()
        val port = hp.substringAfter(':', "").trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        Proxy(if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
    }.getOrNull()

    private fun buildHeaders(
        safeUrl: String,
        referer: String?,
        extraHeaders: Map<String, String>
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        put("User-Agent", currentUserAgent)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        put("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) {
            put("Referer", encodeUrl(referer))
        } else {
            originOf(safeUrl)?.let { put("Referer", "$it/") }
        }
        EgyDeadPrefs.cfClearance?.let { token ->
            if (safeUrl.contains("egydead.", ignoreCase = true)) {
                val existing = extraHeaders["Cookie"]
                val cookieValue = if (token.contains("cf_clearance=")) token else "cf_clearance=$token"
                put("Cookie", if (existing.isNullOrBlank()) cookieValue else "$existing; $cookieValue")
            }
        }
        for ((k, v) in extraHeaders) {
            if (!k.equals("Cookie", ignoreCase = true) || !containsKey("Cookie")) {
                put(k, v)
            }
        }
    }

    fun executeRawGet(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20L
    ): RawHttpResult? = runCatching {
        val safeUrl = encodeUrl(url)
        val reqBuilder = Request.Builder().url(safeUrl)
        buildHeaders(safeUrl, referer, extraHeaders).forEach { (k, v) -> reqBuilder.header(k, v) }
        resolveClient(timeoutSec).newCall(reqBuilder.build()).execute().use { resp ->
            RawHttpResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                headers = resp.headers.toMultimap()
            )
        }
    }.getOrNull()

    fun executeRawPostForm(
        url: String,
        formFields: Map<String, String>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): RawHttpResult? = runCatching {
        val safeUrl = encodeUrl(url)
        val formBuilder = FormBody.Builder()
        formFields.forEach { (k, v) -> formBuilder.add(k, v) }
        val reqBuilder = Request.Builder().url(safeUrl).post(formBuilder.build())
        buildHeaders(safeUrl, referer, extraHeaders).forEach { (k, v) -> reqBuilder.header(k, v) }
        originOf(safeUrl)?.let { reqBuilder.header("Origin", it) }
        resolveClient(20L).newCall(reqBuilder.build()).execute().use { resp ->
            RawHttpResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                headers = resp.headers.toMultimap()
            )
        }
    }.getOrNull()

    suspend fun postDocument(
        rawUrl: String,
        formFields: Map<String, String>,
        referer: String? = null
    ): Document? = fetchDocument(rawUrl, referer = referer, postFields = formFields)

    suspend fun fetchDocument(
        rawUrl: String,
        referer: String? = null,
        postFields: Map<String, String>? = null
    ): Document? = withContext(Dispatchers.IO) {
        val base = getBaseUrl(false)
        var targetUrl = rewriteToActiveBase(rawUrl)
        val effectiveReferer = referer?.let { rewriteToActiveBase(it) } ?: "$base/"

        var result = if (!postFields.isNullOrEmpty()) {
            executeRawPostForm(targetUrl, postFields, effectiveReferer)
                ?.takeIf { it.code in 200..299 && isValidEgyDeadHtml(it.body) && !isCloudflareBlock(it.code, it.body) }
                ?: executeRawGet(targetUrl, effectiveReferer)
        } else {
            executeRawGet(targetUrl, effectiveReferer)
        }

        if ((result == null || (result.code >= 500 && !isCloudflareBlock(result.code, result.body))) &&
            targetUrl.contains("egydead.", ignoreCase = true)
        ) {
            val refreshedBase = getBaseUrl(forceRefresh = true)
            if (refreshedBase != base) {
                targetUrl = rewriteToActiveBase(rawUrl)
                result = executeRawGet(targetUrl, "$refreshedBase/")
            }
        }

        if (result != null && isCloudflareBlock(result.code, result.body)) {
            val ctx = EgyDeadPrefs.appContext
            if (ctx != null) {
                val solved = runCatching { CloudflareSolver.solve(ctx, targetUrl, currentUserAgent) }.getOrNull()
                if (solved != null) {
                    solved.cfClearance?.takeIf { it.isNotBlank() }?.let { EgyDeadPrefs.cfClearance = it }
                    solved.userAgent.takeIf { it.isNotBlank() }?.let { EgyDeadPrefs.savedUserAgent = it }
                    result = if (!postFields.isNullOrEmpty()) {
                        executeRawPostForm(targetUrl, postFields, effectiveReferer)
                            ?.takeIf { it.code in 200..299 && isValidEgyDeadHtml(it.body) }
                            ?: executeRawGet(targetUrl, effectiveReferer)
                    } else {
                        executeRawGet(targetUrl, effectiveReferer)
                    }
                }
            }
        }

        val finalBody = result?.body
        if (!finalBody.isNullOrBlank() && !isCloudflareBlock(result?.code ?: 0, finalBody)) {
            return@withContext Jsoup.parse(finalBody, result?.finalUrl ?: targetUrl)
        }
        null
    }
}
