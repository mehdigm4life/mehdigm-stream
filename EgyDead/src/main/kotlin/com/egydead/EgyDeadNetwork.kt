package com.egydead

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object EgyDeadNetwork {
    private const val TAG = "EgyDeadNet"

    const val DEFAULT_BASE_URL = "https://tv10.egydead.live"

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val BEACON_DOMAINS = listOf(
        "https://tv10.egydead.live",
        "https://egydead.live",
        "https://egydead.fyi",
        "https://tv11.egydead.live",
        "https://tv12.egydead.live",
        "https://tv13.egydead.live",
        "https://tv9.egydead.live"
    )

    private val PROXY_FEEDS = listOf(
        "socks5" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt",
        "http" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt",
        "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "socks5" to "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt",
        "socks5" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt"
    )

    private const val DOMAIN_TTL_MS = 20 * 60 * 1000L

    private val domainMutex = Mutex()
    private val proxyMutex = Mutex()

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var directBlockedUntilMs: Long = 0L

    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val workingProxies = CopyOnWriteArrayList<String>()
    private val deadProxies = ConcurrentHashMap.newKeySet<String>()
    private val proxyFailCounts = ConcurrentHashMap<String, Int>()
    private val sharedProxyClients = ConcurrentHashMap<String, OkHttpClient>()

    private val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllSslFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }.socketFactory

    private val dohBootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(trustAllSslFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val hybridDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val key = hostname.lowercase()
            val now = System.currentTimeMillis()
            dnsCache[key]?.let { (exp, addrs) ->
                if (exp > now && addrs.isNotEmpty()) return addrs
            }

            val sysAddrs = runCatching { Dns.SYSTEM.lookup(hostname) }
                .getOrDefault(emptyList())
                .filter { addr ->
                    !addr.isLoopbackAddress && !addr.isAnyLocalAddress && addr.hostAddress != "0.0.0.0"
                }
            if (sysAddrs.isNotEmpty()) {
                dnsCache[key] = (now + 600_000L) to sysAddrs
                return sysAddrs
            }

            for (dohUrl in listOf(
                "https://1.1.1.1/dns-query?name=$hostname&type=A",
                "https://8.8.8.8/resolve?name=$hostname&type=A"
            )) {
                val resolved = runCatching {
                    val req = Request.Builder().url(dohUrl).header("Accept", "application/dns-json").build()
                    dohBootstrapClient.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@use emptyList()
                        val ans = JSONObject(res.body?.string().orEmpty()).optJSONArray("Answer") ?: return@use emptyList()
                        val out = ArrayList<InetAddress>()
                        for (i in 0 until ans.length()) {
                            val rec = ans.optJSONObject(i) ?: continue
                            if (rec.optInt("type") == 1) {
                                val ip = rec.optString("data").trim()
                                if (ip.isNotEmpty() && ip != "0.0.0.0" && !ip.startsWith("127.")) {
                                    out.add(InetAddress.getByName(ip))
                                }
                            }
                        }
                        out
                    }
                }.getOrDefault(emptyList())

                if (resolved.isNotEmpty()) {
                    dnsCache[key] = (now + 600_000L) to resolved
                    return resolved
                }
            }
            throw UnknownHostException("Unable to resolve $hostname via System DNS or DoH")
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hybridDns)
            .sslSocketFactory(trustAllSslFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(14, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
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
                    val cp = Character.codePointAt(trimmed, i)
                    val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append(String.format("%02X", b.toInt() and 0xFF))
                    }
                    i += Character.charCount(cp)
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
        if (code == 403 || code == 503 || code == 429) return true
        if (body.length < 8000 && (
                body.contains("cf-browser-verification") ||
                    body.contains("challenge-platform") ||
                    body.contains("cf_chl_opt") ||
                    body.contains("Just a moment") ||
                    body.contains("Attention Required! | Cloudflare")
                )
        ) return true
        return false
    }

    fun isValidEgyDeadHtml(body: String): Boolean {
        if (body.length < 500) return false
        return body.contains("movieItem") ||
            body.contains("singleTitle") ||
            body.contains("serversList") ||
            body.contains("donwload-servers-list") ||
            body.contains("ايجي ديد")
    }

    private fun isEgyDeadHost(host: String): Boolean =
        host.lowercase().contains("egydead")

    // =========================================================================
    // SOCKS5 / HTTP Proxy Pool (Automatic VPN / Datacenter 403 Bypass)
    // =========================================================================

    private fun buildJavaProxy(spec: String): Proxy? = runCatching {
        val scheme = spec.substringBefore("://", "socks5").lowercase()
        val hp = spec.substringAfter("://")
        val host = hp.substringBeforeLast(':').trim()
        val port = hp.substringAfterLast(':').trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
        Proxy(type, InetSocketAddress(host, port))
    }.getOrNull()

    private fun buildProxyClient(javaProxy: Proxy, connectSec: Long = 7L, readSec: Long = 15L): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(javaProxy)
            .dns(hybridDns)
            .sslSocketFactory(trustAllSslFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    private fun getSharedProxyClient(spec: String): OkHttpClient? {
        sharedProxyClients[spec]?.let { return it }
        val jp = buildJavaProxy(spec) ?: return null
        return buildProxyClient(jp).also { sharedProxyClients[spec] = it }
    }

    private fun markProxyFailure(spec: String) {
        val fails = (proxyFailCounts[spec] ?: 0) + 1
        proxyFailCounts[spec] = fails
        if (fails >= 3) {
            deadProxies.add(spec)
            workingProxies.remove(spec)
            sharedProxyClients.remove(spec)
        } else if (workingProxies.size > 1 && workingProxies.remove(spec)) {
            workingProxies.add(spec)
        }
    }

    private suspend fun ensureProxiesAvailable(probeBaseUrl: String = DEFAULT_BASE_URL): List<String> {
        if (workingProxies.isNotEmpty()) return workingProxies.toList()
        return proxyMutex.withLock {
            if (workingProxies.isNotEmpty()) return@withLock workingProxies.toList()
            if (deadProxies.size > 600) deadProxies.clear()

            val candidates = coroutineScope {
                PROXY_FEEDS.map { (scheme, feedUrl) ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val req = Request.Builder().url(feedUrl).header("User-Agent", DEFAULT_UA).build()
                            dohBootstrapClient.newCall(req).execute().use { res ->
                                if (!res.isSuccessful) return@use emptyList()
                                res.body?.string().orEmpty()
                                    .lineSequence()
                                    .map { line ->
                                        line.trim()
                                            .removePrefix("socks5h://")
                                            .removePrefix("socks5://")
                                            .removePrefix("https://")
                                            .removePrefix("http://")
                                    }
                                    .filter { Regex("""^\d+\.\d+\.\d+\.\d+:\d+$""").matches(it) }
                                    .take(45)
                                    .map { "$scheme://$it" }
                                    .filterNot { deadProxies.contains(it) }
                                    .toList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten().distinct()
            }

            val sem = Semaphore(25)
            val verified = ConcurrentHashMap<String, Long>()
            for (batch in candidates.chunked(35)) {
                if (verified.size >= 6) break
                coroutineScope {
                    batch.map { spec ->
                        async(Dispatchers.IO) {
                            if (verified.size >= 6) return@async
                            sem.withPermit {
                                if (verified.size >= 6) return@withPermit
                                val jp = buildJavaProxy(spec) ?: return@withPermit
                                val probeClient = buildProxyClient(jp, connectSec = 5L, readSec = 8L)
                                val t0 = System.currentTimeMillis()
                                val res = executeOnClient(probeClient, "$probeBaseUrl/movies/", "$probeBaseUrl/")
                                if (res != null && res.code in 200..299 &&
                                    !isCloudflareBlock(res.code, res.body) &&
                                    isValidEgyDeadHtml(res.body)
                                ) {
                                    verified[spec] = System.currentTimeMillis() - t0
                                    originOf(res.finalUrl)?.takeIf { isEgyDeadHost(it) }?.let { active ->
                                        cachedBaseUrl = active
                                        cachedAtMs = System.currentTimeMillis()
                                    }
                                } else {
                                    deadProxies.add(spec)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            val sorted = verified.entries.sortedBy { it.value }.map { it.key }
            for (s in sorted) {
                if (!workingProxies.contains(s)) workingProxies.add(s)
            }
            workingProxies.toList()
        }
    }

    // =========================================================================
    // Domain Discovery & URL Rewriting
    // =========================================================================

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

        // 1. Try direct HTTP first unless direct is known-blocked
        if (System.currentTimeMillis() >= directBlockedUntilMs && !EgyDeadPrefs.useProxy) {
            var sawBlock = false
            for (candidate in candidates.take(4)) {
                val probe = executeRawGet("$candidate/", referer = "$candidate/") ?: continue
                if (probe.code in 200..299 && !isCloudflareBlock(probe.code, probe.body) && isValidEgyDeadHtml(probe.body)) {
                    val activeOrigin = originOf(probe.finalUrl) ?: candidate
                    runCatching { Log.d(TAG, "Discovered live EgyDead origin (direct): $activeOrigin") }
                    return@withContext activeOrigin
                }
                if (isCloudflareBlock(probe.code, probe.body)) {
                    sawBlock = true
                    break
                }
            }
            if (sawBlock) {
                directBlockedUntilMs = System.currentTimeMillis() + 10 * 60 * 1000L
            }
        }

        // 2. Discover via verified SOCKS5/HTTP proxy pool when direct IP is Cloudflare-blocked
        val proxies = ensureProxiesAvailable(candidates.firstOrNull() ?: DEFAULT_BASE_URL)
        cachedBaseUrl?.let { return@withContext it }
        for (spec in proxies.take(3)) {
            val pClient = getSharedProxyClient(spec) ?: continue
            for (candidate in listOf("https://egydead.live", DEFAULT_BASE_URL)) {
                val probe = executeOnClient(pClient, "$candidate/", "$candidate/") ?: continue
                if (probe.code in 200..299 && !isCloudflareBlock(probe.code, probe.body) && isValidEgyDeadHtml(probe.body)) {
                    return@withContext originOf(probe.finalUrl) ?: candidate
                }
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

    fun upgradeLegacyPath(url: String): String {
        return url
            .replace(Regex("(?i)/episode/"), "/episodes/")
            .replace(Regex("(?i)/season/"), "/seasons/")
            .replace(Regex("(?i)/serie/"), "/Series/")
            .replace(Regex("(?i)/series/"), "/Series/")
            .replace(Regex("(?i)/assembly/"), "/film-series/")
    }

    // =========================================================================
    // HTTP Execution (Direct + Automatic Proxy Pool + Cloudflare Solver)
    // =========================================================================

    private fun executeOnClient(
        client: OkHttpClient,
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

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val pair = sc.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
            }
            HttpResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                cookies = cookies
            )
        }
    }.getOrNull()

    fun executeRawGet(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResult? = executeOnClient(httpClient, url, referer, extraHeaders)

    /**
     * Executes GET directly first, and if blocked (403/503) retries via verified SOCKS5/HTTP proxy pool.
     */
    suspend fun executeGetWithProxyFallback(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResult? = withContext(Dispatchers.IO) {
        val direct = executeOnClient(httpClient, url, referer, extraHeaders)
        if (direct != null && direct.code in 200..299 && !isCloudflareBlock(direct.code, direct.body)) {
            return@withContext direct
        }
        val proxies = ensureProxiesAvailable()
        for (spec in proxies.take(4)) {
            val pClient = getSharedProxyClient(spec) ?: continue
            val res = executeOnClient(pClient, url, referer, extraHeaders)
            if (res != null && res.code in 200..299) {
                return@withContext res
            }
            markProxyFailure(spec)
        }
        direct
    }

    fun executeRawPost(
        url: String,
        formData: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        client: OkHttpClient = httpClient
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

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val pair = sc.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
            }
            HttpResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
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

        // 1. Direct HTTP when not known-blocked and user didn't force proxy
        if (!EgyDeadPrefs.useProxy && System.currentTimeMillis() >= directBlockedUntilMs) {
            var res = executeRawGet(targetUrl, referer = effectiveRef)
            if (res == null || res.code == 404) {
                val upgraded = upgradeLegacyPath(targetUrl)
                if (upgraded != targetUrl) {
                    res = executeRawGet(upgraded, referer = effectiveRef)
                }
            }
            if (res != null && res.code in 200..299 && !isCloudflareBlock(res.code, res.body)) {
                originOf(res.finalUrl)?.takeIf { isEgyDeadHost(it) }?.let { active ->
                    cachedBaseUrl = active
                    cachedAtMs = System.currentTimeMillis()
                }
                return@withContext Jsoup.parse(res.body, res.finalUrl)
            }
            if (res != null && isCloudflareBlock(res.code, res.body)) {
                // Try silent WebView Cloudflare solver on Android first
                val ctx = EgyDeadPrefs.appContext
                if (ctx != null) {
                    val solved = runCatching { CloudflareSolver.solve(ctx, targetUrl, currentUserAgent) }.getOrNull()
                    if (solved != null && !solved.cfClearance.isNullOrBlank()) {
                        EgyDeadPrefs.cfClearance = solved.cfClearance
                        EgyDeadPrefs.savedUserAgent = solved.userAgent
                        val afterSolve = executeRawGet(targetUrl, referer = effectiveRef)
                        if (afterSolve != null && afterSolve.code in 200..299 && !isCloudflareBlock(afterSolve.code, afterSolve.body)) {
                            return@withContext Jsoup.parse(afterSolve.body, afterSolve.finalUrl)
                        }
                    }
                }
                directBlockedUntilMs = System.currentTimeMillis() + 10 * 60 * 1000L
            }
        }

        // 2. Custom Web Proxy if configured by user
        EgyDeadPrefs.customProxy?.let { cp ->
            val prefix = if (cp.contains("=") || cp.endsWith("/")) cp else "$cp?url="
            val encodedTarget = runCatching { URLEncoder.encode(encodeUrl(targetUrl), "UTF-8") }.getOrDefault(targetUrl)
            val res = executeRawGet("$prefix$encodedTarget")
            if (res != null && res.code in 200..299 && isValidEgyDeadHtml(res.body)) {
                return@withContext Jsoup.parse(res.body, targetUrl)
            }
        }

        // 3. Verified SOCKS5 / HTTP Proxy Pool (fast parallel rotation)
        for (attempt in 0..1) {
            val proxies = ensureProxiesAvailable(getBaseUrl())
            for (spec in proxies.take(5)) {
                val pClient = getSharedProxyClient(spec) ?: continue
                var res = executeOnClient(pClient, targetUrl, effectiveRef)
                if (res != null && res.code == 404) {
                    val upgraded = upgradeLegacyPath(targetUrl)
                    if (upgraded != targetUrl) {
                        res = executeOnClient(pClient, upgraded, effectiveRef)
                    }
                }
                if (res != null && res.code in 200..299 && !isCloudflareBlock(res.code, res.body) && res.body.isNotBlank()) {
                    return@withContext Jsoup.parse(res.body, res.finalUrl)
                }
                markProxyFailure(spec)
            }
            if (workingProxies.isNotEmpty()) break
        }
        null
    }

    suspend fun postDocument(
        rawUrl: String,
        data: Map<String, String>,
        referer: String? = null
    ): Document? = withContext(Dispatchers.IO) {
        val targetUrl = rewriteToActiveBase(rawUrl)
        val effectiveRef = referer?.let { rewriteToActiveBase(it) } ?: targetUrl

        if (!EgyDeadPrefs.useProxy && System.currentTimeMillis() >= directBlockedUntilMs) {
            val res = executeRawPost(targetUrl, formData = data, referer = effectiveRef)
            if (res != null && res.code in 200..299 && !isCloudflareBlock(res.code, res.body) && res.body.isNotBlank()) {
                return@withContext Jsoup.parse(res.body, res.finalUrl)
            }
        }

        val proxies = ensureProxiesAvailable(getBaseUrl())
        for (spec in proxies.take(4)) {
            val pClient = getSharedProxyClient(spec) ?: continue
            val res = executeRawPost(targetUrl, formData = data, referer = effectiveRef, client = pClient)
            if (res != null && res.code in 200..299 && !isCloudflareBlock(res.code, res.body) && res.body.isNotBlank()) {
                return@withContext Jsoup.parse(res.body, res.finalUrl)
            }
            markProxyFailure(spec)
        }
        null
    }
}
