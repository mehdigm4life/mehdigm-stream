package com.cimanow

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Multi-tier resilient HTTP + DoH + Session-Sticky Proxy transport for CimaNow (`cimanow.cc`).
 *
 * - Residential / Mobile IPs: Fast direct OkHttpClient with DNS-over-HTTPS fallback (`1.1.1.1` / `8.8.8.8`).
 * - VPN / Datacenter ASNs (`HTTP 403` `ستوب! المخرج عايز كدة` / `مشهد محذوف!`): Automatic harvest & sticky
 *   SOCKS5/HTTP proxy pool preserving `PHPSESSID` and exit IP across `rm.freex2line.online` -> `/watching/`.
 */
object CimaNowNetwork {

    const val DEFAULT_MAIN_URL = "https://cimanow.cc"

    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val PREFS_NAME = "cimanow_net_v1"
    private const val KEY_ACTIVE_DOMAIN = "active_domain"

    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    @Volatile
    private var appContextRef: WeakReference<Context>? = null

    @Volatile
    var mainUrl: String = DEFAULT_MAIN_URL
        private set

    @Volatile
    private var directBlockedUntilMs: Long = 0L

    private val proxyMutex = Mutex()
    private val workingProxies = CopyOnWriteArrayList<String>()
    private val deadProxies = ConcurrentHashMap.newKeySet<String>()
    private val proxyFailCounts = ConcurrentHashMap<String, Int>()
    private val sharedProxyClients = ConcurrentHashMap<String, OkHttpClient>()

    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private val PROXY_FEEDS = listOf(
        "socks5" to "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt",
        "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "socks5" to "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt",
        "socks5" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
    )

    fun init(context: Context) {
        appContextRef = WeakReference(context.applicationContext ?: context)
        runCatching {
            val prefs = (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_ACTIVE_DOMAIN, null)?.trim()?.trimEnd('/')
            if (!saved.isNullOrBlank() && saved.startsWith("https://")) {
                mainUrl = saved
            }
        }
    }

    private fun updateDomainIfRedirected(finalUrl: String) {
        val parsed = finalUrl.toHttpUrlOrNull() ?: return
        val host = parsed.host.lowercase()
        if (host.contains("cimanow") && !host.startsWith("media") && !host.startsWith("fr.") && !host.startsWith("reva.")) {
            val candidate = "${parsed.scheme}://${parsed.host}"
            if (candidate != mainUrl) {
                mainUrl = candidate
                runCatching {
                    appContextRef?.get()
                        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(KEY_ACTIVE_DOMAIN, candidate)
                        ?.apply()
                }
            }
        }
    }

    // ── SSL & DNS-over-HTTPS Infrastructure ──────────────────────────────────

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

            // 1. System DNS first unless sinkholed to loopback/unspecified
            val sysAddrs = runCatching { Dns.SYSTEM.lookup(hostname) }
                .getOrDefault(emptyList())
                .filter { addr ->
                    !addr.isLoopbackAddress && !addr.isAnyLocalAddress && addr.hostAddress != "0.0.0.0"
                }
            if (sysAddrs.isNotEmpty()) {
                dnsCache[key] = (now + 600_000L) to sysAddrs
                return sysAddrs
            }

            // 2. DNS-over-HTTPS fallback (Cloudflare 1.1.1.1 + Google 8.8.8.8)
            val dohUrls = listOf(
                "https://1.1.1.1/dns-query?name=$hostname&type=A",
                "https://8.8.8.8/resolve?name=$hostname&type=A",
            )
            for (dohUrl in dohUrls) {
                val resolved = runCatching {
                    val req = Request.Builder()
                        .url(dohUrl)
                        .header("Accept", "application/dns-json")
                        .build()
                    dohBootstrapClient.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@use emptyList()
                        val json = JSONObject(res.body?.string().orEmpty())
                        val ans = json.optJSONArray("Answer") ?: return@use emptyList()
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

    // ── Session-Sticky Cookie Jar ────────────────────────────────────────────

    class SessionCookieStore(private val syncWithWebKit: Boolean = false) : CookieJar {
        private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

        private fun domainKey(host: String): String {
            val clean = host.lowercase().trim('.')
            val parts = clean.split('.')
            return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else clean
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val bucket = store.getOrPut(domainKey(url.host)) { ConcurrentHashMap() }
            val now = System.currentTimeMillis()
            for (c in cookies) {
                if (c.expiresAt < now || c.value.equals("deleted", ignoreCase = true)) {
                    bucket.remove(c.name)
                } else {
                    bucket[c.name] = c
                }
            }
            if (syncWithWebKit) {
                runCatching {
                    val cm = CookieManager.getInstance()
                    val origin = "${url.scheme}://${url.host}"
                    for (c in cookies) cm.setCookie(origin, c.toString())
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val bucket = store[domainKey(url.host)] ?: return emptyList()
            val now = System.currentTimeMillis()
            val out = ArrayList<Cookie>(bucket.size)
            for ((name, c) in bucket) {
                if (c.expiresAt < now) {
                    bucket.remove(name)
                } else {
                    out.add(c)
                }
            }
            return out
        }

        fun exportCookieHeader(urlStr: String): String {
            val u = encodeUrl(urlStr).toHttpUrlOrNull() ?: return ""
            return loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" }
        }
    }

    private val directCookies = SessionCookieStore(syncWithWebKit = true)

    val directNoProxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(hybridDns)
            .cookieJar(directCookies)
            .sslSocketFactory(trustAllSslFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun newIsolatedDirectClient(
        cookieStore: SessionCookieStore = SessionCookieStore(false),
        followRedirects: Boolean = true,
        timeoutSec: Long = 16L,
    ): OkHttpClient = OkHttpClient.Builder()
        .dns(hybridDns)
        .cookieJar(cookieStore)
        .sslSocketFactory(trustAllSslFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .build()

    // ── Block / Canary Detection & URL Encoding ──────────────────────────────

    fun isCimaBlocked(code: Int, body: String): Boolean {
        if (code == 403 || code == 429 || code == 503) return true
        if (body.isBlank()) return true
        if (body.contains("ستوب! المخرج عايز كدة") || body.contains("مشهد محذوف")) return true
        if (body.length < 6000 && (body.contains("Just a moment...") || body.contains("cf-browser-verification"))) {
            return true
        }
        return false
    }

    internal fun isSoftRedirectedToHome(requestedEncodedUrl: String, finalUrl: String): Boolean {
        val reqPath = requestedEncodedUrl.toHttpUrlOrNull()?.encodedPath?.trim('/') ?: return false
        val finPath = finalUrl.toHttpUrlOrNull()?.encodedPath?.trim('/') ?: return false
        return reqPath.isNotEmpty() &&
            !reqPath.equals("home", ignoreCase = true) &&
            (finPath.isEmpty() || finPath.equals("home", ignoreCase = true))
    }

    private fun isValidPageResponse(requestedEncodedUrl: String, code: Int, finalUrl: String, body: String): Boolean {
        if (code !in 200..399) return false
        if (isCimaBlocked(code, body)) return false
        if (isSoftRedirectedToHome(requestedEncodedUrl, finalUrl)) return false
        return true
    }

    fun encodeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        val normalized = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            else -> trimmed
        }
        val sb = StringBuilder(normalized.length + 32)
        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            when {
                ch == '%' && i + 2 < normalized.length &&
                    normalized[i + 1].isHexDigit() && normalized[i + 2].isHexDigit() -> {
                    sb.append(ch).append(normalized[i + 1]).append(normalized[i + 2])
                    i += 3
                }
                ch.code in 0x21..0x7E && ch != ' ' && ch != '"' && ch != '<' && ch != '>' && ch != '\\' -> {
                    sb.append(ch)
                    i++
                }
                ch == ' ' -> {
                    sb.append("%20")
                    i++
                }
                else -> {
                    val cp = Character.codePointAt(normalized, i)
                    val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        val hex = (b.toInt() and 0xFF).toString(16).uppercase()
                        if (hex.length == 1) sb.append('0')
                        sb.append(hex)
                    }
                    i += Character.charCount(cp)
                }
            }
        }
        return sb.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // ── Proxy Pool Management (Datacenter / VPN 403 Fallback) ────────────────

    private fun buildJavaProxy(spec: String): Proxy? = runCatching {
        val scheme = spec.substringBefore("://", "socks5").lowercase()
        val hp = spec.substringAfter("://")
        val host = hp.substringBeforeLast(':').trim()
        val port = hp.substringAfterLast(':').trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
        // Resolved InetSocketAddress is required by Java's SocksSocketImpl!
        Proxy(type, InetSocketAddress(host, port))
    }.getOrNull()

    private fun buildProxyOkHttpClient(
        javaProxy: Proxy,
        cookieStore: SessionCookieStore,
        connectSec: Long = 8L,
        readSec: Long = 16L,
        followRedirects: Boolean = true,
    ): OkHttpClient = OkHttpClient.Builder()
        .proxy(javaProxy)
        .dns(hybridDns)
        .cookieJar(cookieStore)
        .sslSocketFactory(trustAllSslFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .build()

    private fun getSharedProxyClient(spec: String): OkHttpClient? {
        sharedProxyClients[spec]?.let { return it }
        val jp = buildJavaProxy(spec) ?: return null
        val client = buildProxyOkHttpClient(jp, SessionCookieStore(false))
        sharedProxyClients[spec] = client
        return client
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

    private suspend fun ensureProxiesAvailable(): List<String> {
        if (workingProxies.isNotEmpty()) return workingProxies.toList()
        return proxyMutex.withLock {
            if (workingProxies.isNotEmpty()) return@withLock workingProxies.toList()
            if (deadProxies.size > 600) deadProxies.clear()

            val candidates = coroutineScope {
                PROXY_FEEDS.map { (scheme, feedUrl) ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val req = Request.Builder().url(feedUrl).header("User-Agent", MOBILE_UA).build()
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
            for (batch in candidates.chunked(30)) {
                if (verified.size >= 8) break
                coroutineScope {
                    batch.map { spec ->
                        async(Dispatchers.IO) {
                            if (verified.size >= 8) return@async
                            sem.withPermit {
                                if (verified.size >= 8) return@withPermit
                                val jp = buildJavaProxy(spec) ?: return@withPermit
                                val probeClient = buildProxyOkHttpClient(
                                    jp,
                                    SessionCookieStore(false),
                                    connectSec = 6L,
                                    readSec = 8L,
                                )
                                val probeUrl = "$mainUrl/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9/"
                                val t0 = System.currentTimeMillis()
                                val ok = runCatching {
                                    val req = Request.Builder()
                                        .url(probeUrl)
                                        .header("User-Agent", MOBILE_UA)
                                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                        .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                                        .header("Referer", "$mainUrl/home/")
                                        .build()
                                    probeClient.newCall(req).execute().use { res ->
                                        val body = res.body?.string().orEmpty()
                                        val fin = res.request.url.toString()
                                        res.code in 200..299 &&
                                            isValidPageResponse(probeUrl, res.code, fin, body) &&
                                            body.contains("cimanow", true) &&
                                            body.contains("pagination", true)
                                    }
                                }.getOrDefault(false)
                                if (ok) {
                                    verified[spec] = System.currentTimeMillis() - t0
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

    // ── Public Page Fetching (`getDocument` / `getHtml`) ─────────────────────

    data class PageResult(
        val html: String,
        val finalUrl: String,
        val doc: Document,
    )

    private fun executeGet(client: OkHttpClient, encodedUrl: String, referer: String?): Triple<Int, String, String>? =
        runCatching {
            val rb = Request.Builder()
                .url(encodedUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) {
                rb.header("Referer", encodeUrl(referer))
            }
            client.newCall(rb.build()).execute().use { res ->
                Triple(res.code, res.request.url.toString(), res.body?.string().orEmpty())
            }
        }.getOrNull()

    suspend fun getPage(url: String, referer: String? = "$mainUrl/home/"): PageResult? = withContext(Dispatchers.IO) {
        val encodedUrl = encodeUrl(url)
        val now = System.currentTimeMillis()

        // 1. Direct path (instant on normal residential/mobile networks)
        if (now >= directBlockedUntilMs) {
            executeGet(directNoProxyClient, encodedUrl, referer)?.let { (code, finalUrl, body) ->
                if (isValidPageResponse(encodedUrl, code, finalUrl, body)) {
                    updateDomainIfRedirected(finalUrl)
                    return@withContext PageResult(body, finalUrl, Jsoup.parse(body, finalUrl))
                }
                if (code == 403 || isSoftRedirectedToHome(encodedUrl, finalUrl) ||
                    body.contains("ستوب! المخرج عايز كدة") || body.contains("مشهد محذوف")
                ) {
                    directBlockedUntilMs = now + 300_000L
                }
            }
        }

        // 2. Proxy pool path (for VPN / datacenter 403 blocks)
        var pool = ensureProxiesAvailable()
        if (pool.isEmpty()) {
            workingProxies.clear()
            pool = ensureProxiesAvailable()
        }

        for (spec in pool.take(6)) {
            val pClient = getSharedProxyClient(spec) ?: continue
            val res = executeGet(pClient, encodedUrl, referer)
            if (res != null && isValidPageResponse(encodedUrl, res.first, res.second, res.third)) {
                proxyFailCounts.remove(spec)
                updateDomainIfRedirected(res.second)
                return@withContext PageResult(res.third, res.second, Jsoup.parse(res.third, res.second))
            }
            markProxyFailure(spec)
        }

        // Refresh pool once if all cached proxies rotated out
        workingProxies.clear()
        val refreshed = ensureProxiesAvailable()
        for (spec in refreshed.take(5)) {
            val pClient = getSharedProxyClient(spec) ?: continue
            val res = executeGet(pClient, encodedUrl, referer)
            if (res != null && isValidPageResponse(encodedUrl, res.first, res.second, res.third)) {
                proxyFailCounts.remove(spec)
                updateDomainIfRedirected(res.second)
                return@withContext PageResult(res.third, res.second, Jsoup.parse(res.third, res.second))
            }
            markProxyFailure(spec)
        }

        null
    }

    // ── Isolated Sticky Session Runner (for Gateway -> `/watching/` -> `core.php`) ─

    data class NoRedirectResponse(
        val code: Int,
        val location: String?,
        val body: String,
    )

    class StickyHttpSession(
        val client: OkHttpClient,
        val cookieStore: SessionCookieStore,
        val proxySpec: String?,
    ) {
        private val noRedirectClient: OkHttpClient by lazy {
            client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        fun getNoRedirect(
            url: String,
            referer: String? = null,
            extraHeaders: Map<String, String> = emptyMap(),
        ): NoRedirectResponse? = runCatching {
            val rb = Request.Builder()
                .url(encodeUrl(url))
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) rb.header("Referer", encodeUrl(referer))
            for ((k, v) in extraHeaders) rb.header(k, v)
            noRedirectClient.newCall(rb.build()).execute().use { res ->
                NoRedirectResponse(
                    code = res.code,
                    location = res.header("Location")?.trim(),
                    body = res.body?.string().orEmpty(),
                )
            }
        }.getOrNull()

        fun getText(
            url: String,
            referer: String? = null,
            extraHeaders: Map<String, String> = emptyMap(),
        ): Pair<Int, String>? = runCatching {
            val rb = Request.Builder()
                .url(encodeUrl(url))
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) rb.header("Referer", encodeUrl(referer))
            for ((k, v) in extraHeaders) rb.header(k, v)
            client.newCall(rb.build()).execute().use { res ->
                res.code to res.body?.string().orEmpty()
            }
        }.getOrNull()

        fun postForm(
            url: String,
            rawFormBody: String,
            referer: String? = null,
            origin: String? = null,
            extraHeaders: Map<String, String> = emptyMap(),
        ): Pair<Int, String>? = runCatching {
            val rb = Request.Builder()
                .url(encodeUrl(url))
                .post(rawFormBody.toRequestBody(FORM_MEDIA_TYPE))
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) rb.header("Referer", encodeUrl(referer))
            if (!origin.isNullOrBlank()) rb.header("Origin", origin)
            for ((k, v) in extraHeaders) rb.header(k, v)
            client.newCall(rb.build()).execute().use { res ->
                res.code to res.body?.string().orEmpty()
            }
        }.getOrNull()
    }

    fun newDirectSession(): StickyHttpSession {
        val store = SessionCookieStore(false)
        return StickyHttpSession(newIsolatedDirectClient(store), store, null)
    }

    suspend fun <T> withStickySession(block: suspend (StickyHttpSession) -> T?): T? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now >= directBlockedUntilMs) {
            try {
                val store = SessionCookieStore(false)
                val session = StickyHttpSession(newIsolatedDirectClient(store), store, null)
                val directResult = block(session)
                if (directResult != null) return@withContext directResult
                directBlockedUntilMs = System.currentTimeMillis() + 300_000L
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                directBlockedUntilMs = System.currentTimeMillis() + 300_000L
            }
        }

        var pool = ensureProxiesAvailable()
        if (pool.isEmpty()) {
            workingProxies.clear()
            pool = ensureProxiesAvailable()
        }

        for (spec in pool.take(8)) {
            val jp = buildJavaProxy(spec) ?: continue
            val store = SessionCookieStore(false)
            val client = buildProxyOkHttpClient(jp, store, connectSec = 9L, readSec = 18L)
            val session = StickyHttpSession(client, store, spec)
            try {
                val res = block(session)
                if (res != null) {
                    proxyFailCounts.remove(spec)
                    return@withContext res
                }
                markProxyFailure(spec)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                markProxyFailure(spec)
            }
        }

        null
    }
}
