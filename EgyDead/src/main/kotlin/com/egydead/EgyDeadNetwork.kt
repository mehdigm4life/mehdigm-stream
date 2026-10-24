package com.egydead

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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
        "https://tv13.egydead.live",
        "https://tv9.egydead.live"
    )

    private val PUBLIC_PROXIES = listOf(
        "https://api.codetabs.com/v1/proxy?quest=",
        "https://api.allorigins.win/raw?url=",
        "https://corsproxy.io/?url="
    )

    private const val DOMAIN_TTL_MS = 20 * 60 * 1000L

    private val domainMutex = Mutex()

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val currentUserAgent: String
        get() = EgyDeadPrefs.savedUserAgent ?: DEFAULT_UA

    data class HttpResult(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val cookies: Map<String, String> = emptyMap()
    )

    fun originOf(rawUrl: String): String? = runCatching {
        val uri = URI(encodeUrl(rawUrl))
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val portPart = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
        "$scheme://$host$portPart"
    }.getOrNull()

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

    fun isCloudflareBlock(code: Int, body: String): Boolean {
        if (code == 403 || code == 503 || code == 429) {
            if (body.contains("cf-browser-verification") ||
                body.contains("challenge-platform") ||
                body.contains("cf_chl_opt") ||
                body.contains("Just a moment") ||
                body.contains("Attention Required! | Cloudflare")
            ) return true
        }
        return body.contains("cf_chl_opt") && body.contains("challenge-platform")
    }

    fun isValidEgyDeadHtml(body: String): Boolean {
        if (body.length < 400) return false
        return body.contains("movieItem") ||
            body.contains("singleTitle") ||
            body.contains("serversList") ||
            body.contains("donwload-servers-list") ||
            body.contains("ايجي ديد") ||
            body.contains("EgyDead", ignoreCase = true)
    }

    private fun isEgyDeadHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("egydead")
    }

    suspend fun getBaseUrl(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        cachedBaseUrl?.let { cached ->
            if (!forceRefresh && now - cachedAtMs < DOMAIN_TTL_MS) return cached
        }
        return domainMutex.withLock {
            val recheckNow = System.currentTimeMillis()
            cachedBaseUrl?.let { cached ->
                if (!forceRefresh && recheckNow - cachedAtMs < DOMAIN_TTL_MS) return@withLock cached
            }
            val resolved = discoverLiveDomain()
            cachedBaseUrl = resolved
            cachedAtMs = System.currentTimeMillis()
            EgyDeadPrefs.discoveredDomain = resolved
            resolved
        }
    }

    private suspend fun discoverLiveDomain(): String = withContext(Dispatchers.IO) {
        val candidates = buildList {
            EgyDeadPrefs.customDomain?.let { add(it) }
            EgyDeadPrefs.discoveredDomain?.let { add(it) }
            addAll(BEACON_DOMAINS)
        }.map { it.trim().trimEnd('/') }.distinct()

        for (candidate in candidates) {
            val probe = runCatching { executeRawGet("$candidate/", referer = "$candidate/") }.getOrNull()
                ?: continue
            if (probe.code in 200..299 && !isCloudflareBlock(probe.code, probe.body) && isValidEgyDeadHtml(probe.body)) {
                val activeOrigin = originOf(probe.finalUrl) ?: candidate
                runCatching { Log.d(TAG, "Discovered live EgyDead origin: $activeOrigin (from $candidate)") }
                return@withContext activeOrigin
            }
        }
        EgyDeadPrefs.discoveredDomain ?: DEFAULT_BASE_URL
    }

    suspend fun rewriteToActiveBase(rawUrl: String): String {
        val activeBase = getBaseUrl()
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return activeBase
        if (trimmed.startsWith("/")) return "$activeBase$trimmed"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "$activeBase/${trimmed.trimStart('/')}"
        }
        return runCatching {
            val encoded = encodeUrl(trimmed)
            val uri = URI(encoded)
            val host = uri.host ?: return@runCatching encoded
            if (isEgyDeadHost(host)) {
                val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
                val query = uri.rawQuery?.let { "?$it" } ?: ""
                "$activeBase$path$query"
            } else {
                encoded
            }
        }.getOrDefault(encodeUrl(trimmed))
    }

    /**
     * Converts legacy singular paths (/episode/, /season/, /serie/, /assembly/)
     * to current plural EgyDead paths (/episodes/, /seasons/, /Series/, /film-series/).
     */
    fun upgradeLegacyPath(url: String): String {
        return url
            .replace(Regex("(?i)/episode/"), "/episodes/")
            .replace(Regex("(?i)/season/"), "/seasons/")
            .replace(Regex("(?i)/serie/"), "/Series/")
            .replace(Regex("(?i)/series/"), "/Series/")
            .replace(Regex("(?i)/assembly/"), "/film-series/")
    }

    fun executeRawGet(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResult? = runCatching {
        val safeUrl = encodeUrl(url)
        val reqBuilder = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", currentUserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", encodeUrl(referer))
        }
        EgyDeadPrefs.cfClearance?.let { token ->
            val existingCookie = extraHeaders["Cookie"]
            val combined = if (existingCookie.isNullOrBlank()) "cf_clearance=$token" else "$existingCookie; cf_clearance=$token"
            reqBuilder.header("Cookie", combined)
        }
        for ((k, v) in extraHeaders) {
            if (!k.equals("Cookie", ignoreCase = true) || EgyDeadPrefs.cfClearance == null) {
                reqBuilder.header(k, v)
            }
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val pair = sc.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
            }
            val bodyStr = resp.body?.string().orEmpty()
            HttpResult(
                code = resp.code,
                body = bodyStr,
                finalUrl = resp.request.url.toString(),
                cookies = cookies
            )
        }
    }.getOrNull()

    fun executeRawPost(
        url: String,
        formData: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResult? = runCatching {
        val safeUrl = encodeUrl(url)
        val body = if (jsonBody != null) {
            jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        } else {
            FormBody.Builder().apply {
                formData.forEach { (k, v) -> add(k, v) }
            }.build()
        }

        val reqBuilder = Request.Builder()
            .url(safeUrl)
            .post(body)
            .header("User-Agent", currentUserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

        if (!referer.isNullOrBlank()) {
            val safeRef = encodeUrl(referer)
            reqBuilder.header("Referer", safeRef)
            originOf(safeRef)?.let { reqBuilder.header("Origin", it) }
        }
        EgyDeadPrefs.cfClearance?.let { token ->
            val existingCookie = extraHeaders["Cookie"]
            val combined = if (existingCookie.isNullOrBlank()) "cf_clearance=$token" else "$existingCookie; cf_clearance=$token"
            reqBuilder.header("Cookie", combined)
        }
        for ((k, v) in extraHeaders) {
            if (!k.equals("Cookie", ignoreCase = true) || EgyDeadPrefs.cfClearance == null) {
                reqBuilder.header(k, v)
            }
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val pair = sc.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
            }
            val bodyStr = resp.body?.string().orEmpty()
            HttpResult(
                code = resp.code,
                body = bodyStr,
                finalUrl = resp.request.url.toString(),
                cookies = cookies
            )
        }
    }.getOrNull()

    suspend fun fetchDocument(
        rawUrl: String,
        referer: String? = null
    ): Document? = withContext(Dispatchers.IO) {
        val targetUrl = rewriteToActiveBase(rawUrl)
        val effectiveRef = referer?.let { rewriteToActiveBase(it) } ?: "${getBaseUrl()}/"

        if (EgyDeadPrefs.useProxy) {
            fetchViaProxy(targetUrl)?.let { return@withContext it }
        }

        var res = executeRawGet(targetUrl, referer = effectiveRef)

        // If a legacy singular path (/episode/, /season/, /serie/) 404s, automatically retry plural path.
        if (res == null || res.code == 404) {
            val upgraded = upgradeLegacyPath(targetUrl)
            if (upgraded != targetUrl) {
                res = executeRawGet(upgraded, referer = effectiveRef)
            }
        }

        if (res != null && res.code in 200..299 && !isCloudflareBlock(res.code, res.body)) {
            val activeOrigin = originOf(res.finalUrl)
            if (activeOrigin != null && isEgyDeadHost(activeOrigin)) {
                cachedBaseUrl = activeOrigin
                cachedAtMs = System.currentTimeMillis()
            }
            return@withContext Jsoup.parse(res.body, res.finalUrl)
        }

        // If domain rotated mid-session, force-refresh domain discovery once and retry
        val refreshedBase = getBaseUrl(forceRefresh = true)
        val retriedUrl = rewriteToActiveBase(targetUrl)
        if (retriedUrl != targetUrl || refreshedBase != originOf(targetUrl)) {
            val retryRes = executeRawGet(retriedUrl, referer = "$refreshedBase/")
            if (retryRes != null && retryRes.code in 200..299 && !isCloudflareBlock(retryRes.code, retryRes.body)) {
                return@withContext Jsoup.parse(retryRes.body, retryRes.finalUrl)
            }
        }

        // Silent WebView Cloudflare solver on Android if blocked
        if (res != null && isCloudflareBlock(res.code, res.body)) {
            val ctx = EgyDeadPrefs.appContext
            if (ctx != null) {
                val solved = runCatching { CloudflareSolver.solve(ctx, targetUrl, currentUserAgent) }.getOrNull()
                if (solved != null && !solved.cfClearance.isNullOrBlank()) {
                    EgyDeadPrefs.cfClearance = solved.cfClearance
                    EgyDeadPrefs.savedUserAgent = solved.userAgent
                    val afterSolve = executeRawGet(targetUrl, referer = effectiveRef)
                    if (afterSolve != null && !isCloudflareBlock(afterSolve.code, afterSolve.body) && afterSolve.body.isNotBlank()) {
                        return@withContext Jsoup.parse(afterSolve.body, afterSolve.finalUrl)
                    }
                }
            }
        }

        // Proxy last-resort fallback (e.g. ISP DNS block)
        fetchViaProxy(targetUrl)
    }

    suspend fun postDocument(
        rawUrl: String,
        data: Map<String, String>,
        referer: String? = null
    ): Document? = withContext(Dispatchers.IO) {
        val targetUrl = rewriteToActiveBase(rawUrl)
        val effectiveRef = referer?.let { rewriteToActiveBase(it) } ?: targetUrl
        val res = executeRawPost(targetUrl, formData = data, referer = effectiveRef) ?: return@withContext null
        if (res.code in 200..299 && !isCloudflareBlock(res.code, res.body) && res.body.isNotBlank()) {
            return@withContext Jsoup.parse(res.body, res.finalUrl)
        }
        null
    }

    private fun fetchViaProxy(targetUrl: String): Document? {
        val proxies = buildList {
            EgyDeadPrefs.customProxy?.let { cp ->
                add(if (cp.contains("=") || cp.endsWith("/")) cp else "$cp?url=")
            }
            addAll(PUBLIC_PROXIES)
        }
        val encodedTarget = runCatching { URLEncoder.encode(encodeUrl(targetUrl), "UTF-8") }.getOrDefault(targetUrl)
        for (prefix in proxies) {
            val res = executeRawGet("$prefix$encodedTarget") ?: continue
            if (res.code in 200..299 && isValidEgyDeadHtml(res.body)) {
                return Jsoup.parse(res.body, targetUrl)
            }
        }
        return null
    }
}
