package com.cimanow

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Complete multi-quality Watch & Download extractor suite for CimaNow:
 *  - Gateway unlock (`rm.freex2line.online` -> `/watching/?token=...`) + `core.php?action=switch`
 *  - CimaNow Direct MP4 (`reva.cimanowtv.com/uploads/...mp4?dl=1`)
 *  - VK Docs Direct MP4 (`vk.com/doc<owner>_<id>` -> `302` `psv4.userapi.com`)
 *  - Jetload Multi-Quality Direct MP4 (`jetload.top/Jetloadon4/?link=<b64>` -> `cdn1..4.jetload.top`)
 *  - UpnShare / Vidstack AES-128-CBC (`cimanow.upns.online/#<code>`)
 *  - Byse / Filemoon ECDSA P-256 + PoW + AES-256-GCM (`bysetayico.com/e/<code>`)
 *  - SaveFiles / StreamHLS (`streamhls.to/e/<code>`, `savefiles.com`)
 *  - VK Video (`vkvideo.ru/video_ext.php`, `vk.com/video_ext.php`)
 *  - Ok.ru (`ok.ru/videoembed/<id>`)
 *  - Uqload (`uqload.is` / `.cx` / `.vc` / `.net`)
 *  - VidGuard (`vid-guard.com` / `vgfplay.com` / `listeamed.net` / `bembed.net`)
 *  - Generic Dean Edwards P.A.C.K.E.R. + JWPlayer / VideoJS + CloudStream `loadExtractor` fallback
 */
object CimaNowExtractors {

    private const val DESKTOP_UA = CimaNowNetwork.DESKTOP_UA
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    private val UPN_AES_KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
    private val UPN_AES_IVS = listOf(
        "1234567890oiuytr".toByteArray(Charsets.UTF_8),
        "0123456789abcdef".toByteArray(Charsets.UTF_8),
    )

    @Volatile
    internal var debugLogger: ((String) -> Unit)? = null

    private val byseAttestCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val unlockedSourcesCache = ConcurrentHashMap<String, Pair<Long, UnlockedSources>>()
    private const val UNLOCKED_CACHE_TTL_MS = 12 * 60 * 1000L

    private fun canonicalItemKey(url: String): String =
        runCatching { java.net.URLDecoder.decode(url, "UTF-8") }
            .getOrDefault(url)
            .trim()
            .substringBefore('?')
            .trimEnd('/')
            .lowercase()

    private data class CandidateTask(
        val dedupKey: String,
        val url: String,
        val referer: String,
        val qualityHint: Int?,
        val labelHint: String,
    )

    private data class UnlockedSources(
        val watchingUrl: String,
        val watchEmbeds: List<Pair<String, String>>,
        val downloads: List<CimaNowCrypto.DownloadEntry>,
    )

    // ── Entry Point Called from `CimaNow.loadLinks` ──────────────────────────

    suspend fun loadAllLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val parsedJson = CimaNowCrypto.safeJsonObject(data)
        val itemUrl = parsedJson?.optString("itemUrl")?.takeIf { it.isNotBlank() } ?: data.trim()
        val cachedShine = parsedJson?.optString("shineUrl")?.takeIf { it.isNotBlank() }
        if (itemUrl.isBlank()) return@withContext false

        val unlocked = unlockWatchingPage(itemUrl, cachedShine) ?: return@withContext false
        val tasks = ArrayList<CandidateTask>()
        val seenKeys = HashSet<String>()

        fun registerTask(url: String, referer: String, qHint: Int?, label: String) {
            val clean = CimaNowCrypto.normalizeEmbedUrl(url) ?: return
            if (isSkippedHtmlDownloadLanding(clean)) return
            val key = canonicalTaskKey(clean)
            if (seenKeys.add(key)) {
                tasks.add(CandidateTask(key, clean, referer, qHint, label))
            }
        }

        for ((srvName, embedUrl) in unlocked.watchEmbeds) {
            registerTask(embedUrl, unlocked.watchingUrl, null, srvName)
        }
        for (dl in unlocked.downloads) {
            registerTask(dl.url, unlocked.watchingUrl, dl.qualityHint, dl.label)
        }

        if (tasks.isEmpty()) return@withContext false

        val totalEmitted = AtomicInteger(0)
        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val dedupCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add("${link.url}|${link.quality}")) {
                totalEmitted.incrementAndGet()
                callback(link)
            }
        }

        // Run fast multi-quality extractors concurrently first; only invoke the 10s-timer Jetload
        // fallback if fast watch/download hosts emitted fewer than 2 playable streams.
        val (jetloadTasks, standardTasks) = tasks.partition { hostOf(it.url).contains("jetload.") }

        withTimeoutOrNull(22_000L) {
            coroutineScope {
                val stdSem = Semaphore(6)
                standardTasks.map { task ->
                    async(Dispatchers.IO) {
                        stdSem.withPermit {
                            withTimeoutOrNull(15_000L) {
                                dispatchCandidate(task, subtitleCallback, dedupCallback)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        if (totalEmitted.get() < 2 && jetloadTasks.isNotEmpty()) {
            withTimeoutOrNull(22_000L) {
                coroutineScope {
                    jetloadTasks.take(4).map { task ->
                        async(Dispatchers.IO) {
                            try {
                                withTimeoutOrNull(20_000L) {
                                    extractJetload(task.url, task.qualityHint, dedupCallback)
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        totalEmitted.get() > 0
    }

    // ── Step 1: Unlock `rm.freex2line.online` -> `/watching/` -> `core.php` ──

    private fun unwrapHrefLiLocation(loc: String?, origin: String): String? {
        val raw = loc?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val stripped = raw
            .removePrefix("https://href.li/?")
            .removePrefix("http://href.li/?")
            .trim()
        return when {
            stripped.startsWith("http://") || stripped.startsWith("https://") -> stripped
            stripped.startsWith("//") -> "https:$stripped"
            stripped.startsWith("/") -> "$origin$stripped"
            else -> null
        }
    }

    private suspend fun fetchAndDecodeWatchingUrl(
        mintedUrl: String,
        gatewayVaultReferer: String,
    ): UnlockedSources? {
        return CimaNowNetwork.withStickySession { session ->
            val pTag = session.proxySpec ?: "direct"
            val (wCode, watchHtml) = session.getText(
                url = mintedUrl,
                referer = gatewayVaultReferer,
            ) ?: run {
                debugLogger?.invoke("[$pTag] watching fetch null")
                return@withStickySession null
            }
            if (CimaNowNetwork.isCimaBlocked(wCode, watchHtml)) {
                debugLogger?.invoke("[$pTag] watching blocked code=$wCode len=${watchHtml.length}")
                return@withStickySession null
            }

            val vault = CimaNowCrypto.unlockWatchingVault(watchHtml) ?: run {
                debugLogger?.invoke("[$pTag] unlockWatchingVault null (watchLen=${watchHtml.length})")
                return@withStickySession null
            }
            val decoded = vault.decoded
            debugLogger?.invoke(
                "[$pTag] unlocked watching! initial=${decoded.initialEmbedUrl != null} tabs=${decoded.serverTabs.size} downloads=${decoded.downloads.size} coreTokLen=${decoded.coreToken.length}",
            )

            val watchOrigin = originOf(mintedUrl)
            val embeds = ArrayList<Pair<String, String>>()
            decoded.initialEmbedUrl?.let { embeds.add("Default" to it) }

            if (decoded.coreToken.isNotBlank() && decoded.serverTabs.isNotEmpty()) {
                val switched = coroutineScope {
                    val tabSem = Semaphore(5)
                    decoded.serverTabs.map { tab ->
                        async(Dispatchers.IO) {
                            tabSem.withPermit {
                                val encTok = URLEncoder.encode(decoded.coreToken, "UTF-8")
                                val encIdx = URLEncoder.encode(tab.dataIndex, "UTF-8")
                                val encId = URLEncoder.encode(tab.dataId, "UTF-8")
                                val rawCoreUrl =
                                    "$watchOrigin/wp-content/themes/Cima%20Now%20New/core.php" +
                                        "?token=$encTok&action=switch&index=$encIdx&id=$encId"
                                val signedCoreUrl = vault.signCoreSwitchUrl(rawCoreUrl, tab)
                                val resp = session.getText(
                                    url = signedCoreUrl,
                                    referer = mintedUrl,
                                    extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                ) ?: return@withPermit null
                                if (resp.first !in 200..299) return@withPermit null
                                val iframeUrl = vault.decodeCoreIframe(resp.second) ?: return@withPermit null
                                tab.name to iframeUrl
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                embeds.addAll(switched)
            }

            if (embeds.isEmpty() && decoded.downloads.isEmpty()) return@withStickySession null
            UnlockedSources(mintedUrl, embeds, decoded.downloads)
        }
    }

    private suspend fun unlockWatchingPage(itemUrl: String, cachedShineUrl: String?): UnlockedSources? {
        val itemKey = canonicalItemKey(itemUrl)
        unlockedSourcesCache[itemKey]?.let { (exp, cached) ->
            if (exp > System.currentTimeMillis()) return cached
            unlockedSourcesCache.remove(itemKey)
        }

        var effectiveItemUrl = itemUrl
        var shineUrl = cachedShineUrl?.trim()?.takeIf { it.startsWith("http") }
        if (shineUrl == null) {
            val detailPage = CimaNowNetwork.getPage(itemUrl, "${CimaNowNetwork.mainUrl}/home/")
            if (detailPage != null) {
                effectiveItemUrl = detailPage.finalUrl
                shineUrl = extractShineUrlFromDetailHtml(detailPage.html)
            }
        }
        val gwEntry = shineUrl ?: run {
            val watchTarget = "${effectiveItemUrl.trimEnd('/')}/watching/"
            val b64 = CimaNowCrypto.b64EncodeNoWrap(watchTarget.toByteArray(Charsets.UTF_8))
            "https://rm.freex2line.online/loadon/?link=$b64"
        }
        val gwOrigin = originOf(gwEntry).ifBlank { "https://rm.freex2line.online" }
        val vaultReferer = "$gwOrigin/2020/02/blog-post.html/"

        // 1. Primary path: Turnstile Gateway Solver (Android stealth WebView / CI Chromium)
        val tMint0 = System.currentTimeMillis()
        val wasTokenCached = CimaNowGatewaySolver.getCachedWatchingUrl(effectiveItemUrl) != null
        var mintedUrl = CimaNowGatewaySolver.mintWatchingUrl(gwEntry, effectiveItemUrl)
        debugLogger?.invoke("mintWatchingUrl cached=$wasTokenCached ok=${!mintedUrl.isNullOrBlank()} ms=${System.currentTimeMillis() - tMint0}")
        if (!mintedUrl.isNullOrBlank()) {
            fetchAndDecodeWatchingUrl(mintedUrl, vaultReferer)?.let { unlocked ->
                unlockedSourcesCache[itemKey] = (System.currentTimeMillis() + UNLOCKED_CACHE_TTL_MS) to unlocked
                return unlocked
            }
            if (wasTokenCached) {
                CimaNowGatewaySolver.invalidateWatchingUrl(effectiveItemUrl)
                mintedUrl = CimaNowGatewaySolver.mintWatchingUrl(gwEntry, effectiveItemUrl)
                if (!mintedUrl.isNullOrBlank()) {
                    fetchAndDecodeWatchingUrl(mintedUrl, vaultReferer)?.let { unlocked ->
                        unlockedSourcesCache[itemKey] = (System.currentTimeMillis() + UNLOCKED_CACHE_TTL_MS) to unlocked
                        return unlocked
                    }
                }
            }
        }

        // 2. Direct legacy static HMAC fallback (never through slow public SOCKS5 proxies; capped at 16s)
        val legacyMinted = if (mintedUrl.isNullOrBlank()) {
            withTimeoutOrNull(16_000L) {
                val directSession = CimaNowNetwork.newDirectSession()
                val hop1 = directSession.getNoRedirect(gwEntry, effectiveItemUrl) ?: return@withTimeoutOrNull null
                if (hop1.code !in 200..399) return@withTimeoutOrNull null
                val hop2Url = unwrapHrefLiLocation(hop1.location, gwOrigin)
                    ?.takeIf { originOf(it) == gwOrigin }
                    ?: "$gwOrigin/redirectingfree/"
                val hop2 = directSession.getNoRedirect(hop2Url, gwEntry) ?: return@withTimeoutOrNull null
                if (hop2.code !in 200..399) return@withTimeoutOrNull null
                val vUrl = unwrapHrefLiLocation(hop2.location, gwOrigin)
                    ?.takeIf { originOf(it) == gwOrigin && !it.endsWith("/redirectingfree/") }
                    ?: vaultReferer
                val (vCode, vaultHtml) = directSession.getText(vUrl, hop2Url) ?: return@withTimeoutOrNull null
                if (vCode !in 200..299 || vaultHtml.length < 512) return@withTimeoutOrNull null
                val postBody = CimaNowCrypto.solveGatewayPostBody(vaultHtml) ?: return@withTimeoutOrNull null
                delay(10_400L)
                val (mCode, mintedBody) = directSession.postForm(
                    url = "${vUrl.trimEnd('/')}/get-link.php",
                    rawFormBody = postBody,
                    referer = vUrl,
                    origin = gwOrigin,
                    extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
                ) ?: return@withTimeoutOrNull null
                if (mCode !in 200..299) return@withTimeoutOrNull null
                val trimmed = mintedBody.trim()
                val parsedUrl = if (trimmed.startsWith("{")) {
                    runCatching { JSONObject(trimmed).optString("url").trim() }.getOrDefault("")
                } else {
                    trimmed
                }
                parsedUrl.takeIf { it.startsWith("http") && it.contains("token=") }
            }
        } else {
            null
        }

        if (!legacyMinted.isNullOrBlank()) {
            fetchAndDecodeWatchingUrl(legacyMinted, vaultReferer)?.let { unlocked ->
                unlockedSourcesCache[itemKey] = (System.currentTimeMillis() + UNLOCKED_CACHE_TTL_MS) to unlocked
                return unlocked
            }
        }
        return null
    }

    internal fun extractShineUrlFromDetailHtml(html: String): String? {
        Regex("""href=["'](https?://[^"']*/loadon/\?link=[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.let { return it }
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.selectFirst("a.shine[href]")?.attr("href")?.trim()?.takeIf { it.startsWith("http") }
    }

    private fun isSkippedHtmlDownloadLanding(url: String): Boolean {
        val h = hostOf(url)
        val path = url.substringBefore('?').lowercase()
        // Forafile & Frdl non-embed download pages require interactive Google reCAPTCHA v2
        if ((h.contains("forafile.") || h.contains("frdl.") || h.contains("fr.cimanow")) &&
            !path.contains("embed-")
        ) {
            return true
        }
        return false
    }

    private fun canonicalTaskKey(url: String): String {
        val h = hostOf(url)
        return when {
            h.contains("upns.") || h.contains("upnshare.") || h.contains("p2pstream.") -> {
                val id = url.substringAfter('#', "").substringBefore('&').substringBefore('?').trim()
                "upns:$h:$id"
            }
            h.contains("byse") || h.contains("filemoon") -> {
                val code = runCatching { URI(CimaNowNetwork.encodeUrl(url)).path }.getOrDefault("")
                    .trim('/').substringAfterLast('/').trim()
                "byse:$code"
            }
            h.contains("streamhls.") || h.contains("savefiles.") -> {
                val code = runCatching { URI(CimaNowNetwork.encodeUrl(url)).path }.getOrDefault("")
                    .trim('/').substringAfterLast('/').removePrefix("embed-").removeSuffix(".html").trim()
                "savefiles:$code"
            }
            h.contains("vid-guard.") || h.contains("vgfplay.") || h.contains("listeamed.") || h.contains("bembed.") -> {
                val code = runCatching { URI(CimaNowNetwork.encodeUrl(url)).path }.getOrDefault("")
                    .trim('/').substringAfterLast('/').trim()
                "vidguard:$code"
            }
            else -> url
        }
    }

    // ── Step 2: Per-Host Extractor Dispatcher ────────────────────────────────

    private suspend fun dispatchCandidate(
        task: CandidateTask,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = task.url
        val host = hostOf(url)
        val localCount = AtomicInteger(0)
        val countingCallback: (ExtractorLink) -> Unit = {
            localCount.incrementAndGet()
            callback(it)
        }

        try {
            when {
                // 1. Direct CimaNow MP4 (`reva.cimanowtv.com/uploads/...mp4?dl=1`)
                host.contains("cimanowtv.") || isVerifiedDirectMediaHost(host, url) -> {
                    emitDirectMp4(url, task.qualityHint, task.labelHint, countingCallback)
                }

                // 2. VK Docs Direct MP4 (`vk.com/doc<owner>_<id>`)
                (host == "vk.com" || host.endsWith(".vk.com") || host == "vk.ru") && url.contains("/doc") -> {
                    extractVkDoc(url, task.qualityHint, task.labelHint, countingCallback)
                }

                // 3. VK Video Player (`vkvideo.ru/video_ext.php`, `vk.com/video_ext.php`)
                host.contains("vkvideo.") || (host.contains("vk.") && url.contains("video_ext.php")) -> {
                    extractVkVideo(url, countingCallback)
                }

                // 4. Ok.ru (`ok.ru/videoembed/...`, `odnoklassniki.ru`)
                host.contains("ok.ru") || host.contains("odnoklassniki.") -> {
                    extractOkRu(url, countingCallback)
                }

                // 5. UpnShare / Vidstack AES-128-CBC (`cimanow.upns.online/#<code>`)
                host.contains("upns.") || host.contains("upnshare.") ||
                    host.contains("p2pstream.") || host.contains("rpmplay.") || host.contains("uns.bio") -> {
                    extractUpnShare(url, subtitleCallback, countingCallback)
                }

                // 6. Byse / Filemoon (`bysetayico.com/e/<code>`)
                host.contains("byse") || host.contains("filemoon") -> {
                    extractByse(url, task.referer, subtitleCallback, countingCallback)
                }

                // 7. SaveFiles / StreamHLS (`streamhls.to/e/<code>`, `savefiles.com`)
                host.contains("streamhls.") || host.contains("savefiles.") -> {
                    extractSaveFiles(url, task.referer, task.qualityHint, countingCallback)
                }

                // 8. VidGuard (`vid-guard.com`, `vgfplay.com`, `listeamed.net`, `bembed.net`)
                host.contains("vid-guard.") || host.contains("vgfplay.") ||
                    host.contains("listeamed.") || host.contains("bembed.") || host.contains("vidguard") -> {
                    extractVidGuard(url, task.referer, countingCallback)
                }

                // 9. Uqload & Packer / JWPlayer / VideoJS HTML Embeds
                else -> {
                    extractPackerAndHtmlEmbed(url, prettyProviderName(host), task.referer, subtitleCallback, countingCallback)
                }
            }

            if (localCount.get() == 0 && android.os.Build.VERSION.SDK_INT > 0) {
                runCatching {
                    loadExtractor(url, task.referer, subtitleCallback, countingCallback)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Isolate individual host failures
        }
    }

    private fun isVerifiedDirectMediaHost(host: String, url: String): Boolean {
        if (host.contains("frdl.") || host.contains("forafile.") || host.contains("fr.cimanow") ||
            host.contains("jetload.top") && url.contains("Jetloadon", true)
        ) {
            return false
        }
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        return (host.contains("userapi.com") || host.contains("cdn") && host.contains("jetload.")) &&
            (cleanPath.endsWith(".mp4") || cleanPath.endsWith(".mkv"))
    }

    // ── Host 1: Direct CimaNow MP4 (`reva.cimanowtv.com`) ────────────────────

    private suspend fun emitDirectMp4(
        url: String,
        qualityHint: Int?,
        labelHint: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val encoded = CimaNowNetwork.encodeUrl(url)
        val qual = qualityHint ?: detectQualityInt("$labelHint $url")
        val ref = "${CimaNowNetwork.mainUrl}/"
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to ref,
        )
        callback(
            newExtractorLink("CimaNow Direct", "CimaNow Direct [MP4]", encoded, ExtractorLinkType.VIDEO) {
                this.referer = ref
                this.quality = qual
                this.headers = headers
            },
        )
    }

    // ── Host 2: VK Docs Direct MP4 (`vk.com/doc<owner>_<id>`) ────────────────

    private suspend fun extractVkDoc(
        url: String,
        qualityHint: Int?,
        labelHint: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val noRedirClient = CimaNowNetwork.newIsolatedDirectClient(followRedirects = false, timeoutSec = 12L)
        val location = runCatching {
            val req = Request.Builder()
                .url(CimaNowNetwork.encodeUrl(url))
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://vk.com/")
                .build()
            noRedirClient.newCall(req).execute().use { res ->
                if (res.code in listOf(301, 302, 303, 307, 308)) {
                    res.header("Location")?.trim()
                } else {
                    null
                }
            }
        }.getOrNull() ?: return@withContext

        if (!location.startsWith("http") || location.contains("security_check", true)) return@withContext
        val qual = qualityHint ?: detectQualityInt("$labelHint $location")
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://vk.com/",
        )
        callback(
            newExtractorLink("VK Direct", "VK Direct [MP4]", location, ExtractorLinkType.VIDEO) {
                this.referer = "https://vk.com/"
                this.quality = qual
                this.headers = headers
            },
        )
    }

    // ── Host 3: Jetload Multi-Quality Direct MP4 (`jetload.top`) ─────────────

    private suspend fun extractJetload(
        jetGatewayUrl: String,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val cookieStore = CimaNowNetwork.SessionCookieStore(false)
        val client = CimaNowNetwork.newIsolatedDirectClient(cookieStore, followRedirects = true, timeoutSec = 14L)
        val noRedirClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val origin = originOf(jetGatewayUrl)

        // 1. Prime `PHPSESSID` via `Jetloadon4/?link=<b64>` without following `Location: https://href.li/?...`
        val step1Ok = runCatching {
            val req = Request.Builder()
                .url(CimaNowNetwork.encodeUrl(jetGatewayUrl))
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "${CimaNowNetwork.mainUrl}/")
                .build()
            noRedirClient.newCall(req).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
        if (!step1Ok) return@withContext

        // 2. Load `/Jetload4/` landing page to extract `data-token` & `window.extraToken`
        val landingHtml = runCatching {
            val req = Request.Builder()
                .url("$origin/Jetload4/")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", CimaNowNetwork.encodeUrl(jetGatewayUrl))
                .build()
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        if (landingHtml.isBlank()) return@withContext

        val dataToken = Regex("""data-token=["']([^"']+)["']""").find(landingHtml)?.groupValues?.get(1)?.trim()
            ?: return@withContext
        val extraToken = Regex("""window\.extraToken\s*=\s*['"]([^'"]+)['"]""").find(landingHtml)?.groupValues?.get(1)?.trim()
            .orEmpty()

        // 3. Server-enforced 10s countdown before `get-link.php`
        delay(10_350L)

        val baseDirectUrl = runCatching {
            val req = Request.Builder()
                .url("$origin/Jetload4/get-link.php?token=${URLEncoder.encode(dataToken, "UTF-8")}")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$origin/Jetload4/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string()?.trim().orEmpty() else "" }
        }.getOrDefault("")

        if (!baseDirectUrl.startsWith("http")) return@withContext
        val finalMp4Url = if (extraToken.isNotEmpty()) {
            val sep = if (baseDirectUrl.contains('?')) '&' else '?'
            "$baseDirectUrl${sep}t=$extraToken"
        } else {
            baseDirectUrl
        }

        // 4. Activate the 5-minute CDN download token via `action=start_timer`
        runCatching {
            val req = Request.Builder()
                .url("$origin/Jetload4/")
                .post("action=start_timer".toRequestBody(FORM_MEDIA_TYPE))
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$origin/Jetload4/")
                .header("Origin", origin)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().close()
        }

        val qual = qualityHint ?: detectQualityInt(finalMp4Url)
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/",
        )
        callback(
            newExtractorLink("Jetload", "Jetload [MP4]", CimaNowNetwork.encodeUrl(finalMp4Url), ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = qual
                this.headers = headers
            },
        )
    }

    // ── Host 4: UpnShare / Vidstack AES-128-CBC (`cimanow.upns.online/#id`) ──

    internal suspend fun extractUpnShare(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val id = url.substringAfterLast('#', "")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
            .ifBlank { url.substringBefore('?').trimEnd('/').substringAfterLast('/') }
        if (id.isBlank()) return@withContext

        val origin = originOf(url)
        val reqHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Accept" to "*/*",
            "Referer" to "$origin/",
            "Origin" to origin,
        )
        val hexPayload = fetchText("$origin/api/v1/video?id=$id", reqHeaders)?.trim() ?: return@withContext
        val cleanHex = hexPayload.trim('"').replace(Regex("""\s+"""), "")
        if (cleanHex.length < 32 || cleanHex.length % 2 != 0) return@withContext

        val cipherBytes = runCatching {
            ByteArray(cleanHex.length / 2) { idx ->
                cleanHex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull() ?: return@withContext

        var json: JSONObject? = null
        for (iv in UPN_AES_IVS) {
            val plain = runCatching {
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(UPN_AES_KEY, "AES"), IvParameterSpec(iv))
                String(c.doFinal(cipherBytes), Charsets.UTF_8)
            }.getOrElse {
                runCatching {
                    val c = Cipher.getInstance("AES/CBC/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(UPN_AES_KEY, "AES"), IvParameterSpec(iv))
                    String(c.doFinal(cipherBytes), Charsets.UTF_8)
                }.getOrNull()
            }?.trim { it <= ' ' || it.code < 0x20 }

            val candidate = CimaNowCrypto.safeJsonObject(plain)
            if (candidate != null) {
                json = candidate
                break
            }
        }
        val dec = json ?: return@withContext

        val streamHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/",
            "Origin" to origin,
        )

        val emitted = HashSet<String>()
        for (key in listOf("cfNative", "source", "cf")) {
            val rawLink = dec.optString(key).trim()
            val fullLink = resolveRelativeUrl(rawLink, origin) ?: continue
            if (emitted.add(fullLink)) {
                emitHlsVariantsOrMaster("UpnShare", fullLink, "$origin/", streamHeaders, Qualities.Unknown.value, callback)
            }
        }

        // Optional subtitle tracks in UpnShare response
        val subObj = dec.optJSONObject("subtitle")
        if (subObj != null) {
            val keys = subObj.keys()
            while (keys.hasNext()) {
                val lang = keys.next()
                val subPath = subObj.optString(lang).substringBefore('#').trim()
                val fullSub = resolveRelativeUrl(subPath, origin)
                if (fullSub != null) subtitleCallback(newSubtitleFile(lang, fullSub))
            }
        }
    }

    // ── Host 5: Byse / Filemoon ECDSA P-256 + PoW + AES-256-GCM ──────────────

    internal suspend fun extractByse(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url)
        val code = runCatching { URI(CimaNowNetwork.encodeUrl(url)).path }.getOrDefault("")
            .trim('/').substringAfterLast('/').substringBefore('?').trim()
        if (code.isBlank()) return@withContext

        val embedReferer = "$origin/e/$code"
        val candidateParents = listOf(
            "cimanow.cc" to "https://cimanow.cc/",
            "tuktukcima.com" to "https://tuktukcima.com/",
            hostOf(origin) to embedReferer,
        )

        var details: JSONObject? = null
        var chosenParent = candidateParents[0]
        for (parent in candidateParents) {
            val d = byseGetJson("$origin/api/videos/$code/embed/details", parent.second, "https://${parent.first}")
            if (d != null && !d.has("error")) {
                details = d
                chosenParent = parent
                break
            }
        }

        val embedFrameUrl = details?.optString("embed_frame_url")?.takeIf { it.startsWith("http") }
        val embedDomain = embedFrameUrl?.let { originOf(it) } ?: origin
        val playbackCode = details?.optString("code")?.takeIf { it.isNotBlank() } ?: code

        val playbackObj = bysePostPlayback(
            embedOrigin = embedDomain,
            code = playbackCode,
            embedPageUrl = embedFrameUrl ?: "$embedDomain/e/$playbackCode",
            parentHost = chosenParent.first,
            parentFrameUrl = chosenParent.second,
            parentReferer = referer ?: chosenParent.second,
        ) ?: details?.optJSONObject("playback") ?: return@withContext

        val plainJson = decryptBysePlayback(playbackObj) ?: return@withContext
        val decObj = CimaNowCrypto.safeJsonObject(plainJson) ?: return@withContext
        val edgeAuth = solveByseEdgePow(playbackObj.optJSONObject("edge_challenge"))

        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", DESKTOP_UA)
            put("Referer", "$embedDomain/")
            put("Origin", embedDomain)
            if (edgeAuth != null) put("X-Edge-Auth", edgeAuth)
        }

        val sources = decObj.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until sources.length()) {
            val sObj = sources.optJSONObject(i) ?: continue
            val sUrl = sObj.optString("url").ifBlank { sObj.optString("file") }.trim()
            if (!sUrl.startsWith("http")) continue
            val qText = "${sObj.optString("label")} ${sObj.optString("quality")} ${sObj.optInt("height", 0)}p"
            val qual = detectQualityInt(qText)
            if (sUrl.contains(".m3u8", true)) {
                emitHlsVariantsOrMaster("Byse", sUrl, "$embedDomain/", headers, qual, callback)
            } else {
                callback(
                    newExtractorLink("Byse", "Byse [MP4]", sUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$embedDomain/"
                        this.quality = qual
                        this.headers = headers
                    },
                )
            }
        }

        val tracks = decObj.optJSONArray("tracks") ?: playbackObj.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracks.length()) {
            val tObj = tracks.optJSONObject(i) ?: continue
            if (tObj.optString("kind").equals("thumbnails", true)) continue
            val tUrl = tObj.optString("url").ifBlank { tObj.optString("file") }.trim()
            if (tUrl.startsWith("http")) {
                subtitleCallback(newSubtitleFile(tObj.optString("label").ifBlank { "Arabic" }, tUrl))
            }
        }
    }

    private fun byseGetJson(apiUrl: String, referer: String, origin: String): JSONObject? = runCatching {
        val req = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .header("User-Agent", DESKTOP_UA)
            .header("Origin", origin)
            .header("Referer", CimaNowNetwork.encodeUrl(referer))
            .build()
        CimaNowNetwork.directNoProxyClient.newCall(req).execute().use { res ->
            CimaNowCrypto.safeJsonObject(res.body?.string())
        }
    }.getOrNull()

    private suspend fun bysePostPlayback(
        embedOrigin: String,
        code: String,
        embedPageUrl: String,
        parentHost: String,
        parentFrameUrl: String,
        parentReferer: String,
    ): JSONObject? = runCatching {
        val baseHeaders = LinkedHashMap<String, String>().apply {
            put("User-Agent", DESKTOP_UA)
            put("Accept", "*/*")
            put("Origin", embedOrigin)
            put("Referer", embedPageUrl)
            put("X-Embed-Origin", parentHost)
            put("X-Embed-Parent", parentFrameUrl)
            if (parentReferer.startsWith("http")) put("X-Embed-Referer", CimaNowNetwork.encodeUrl(parentReferer))
        }

        val settings = runCatching {
            val req = Request.Builder().url("$embedOrigin/api/videos/$code/embed/settings")
            for ((k, v) in baseHeaders) req.header(k, v)
            CimaNowNetwork.directNoProxyClient.newCall(req.build()).execute().use {
                if (it.isSuccessful) CimaNowCrypto.safeJsonObject(it.body?.string()) else null
            }
        }.getOrNull()

        val fingerprint = byseObtainFingerprint(embedOrigin, baseHeaders)
        var captchaToken: String? = null

        if (settings?.optBoolean("captcha_required", false) == true) {
            val capBody = JSONObject().apply { if (fingerprint != null) put("fingerprint", fingerprint) }
            val capChallenge = runCatching {
                val req = Request.Builder()
                    .url("$embedOrigin/api/videos/$code/embed/captcha")
                    .post(capBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                for ((k, v) in baseHeaders) req.header(k, v)
                CimaNowNetwork.directNoProxyClient.newCall(req.build()).execute().use {
                    if (it.isSuccessful) CimaNowCrypto.safeJsonObject(it.body?.string()) else null
                }
            }.getOrNull()

            if (capChallenge != null) {
                val powNonce = capChallenge.optString("pow_nonce")
                val powToken = capChallenge.optString("pow_token")
                val powDiff = capChallenge.optInt("pow_difficulty", 14)
                if (powNonce.isNotBlank() && powToken.isNotBlank()) {
                    val solution = solveByseStreamPow(powNonce, powDiff)
                    if (solution != null) {
                        val verifyJson = JSONObject()
                            .put("pow_token", powToken)
                            .put("solution", solution)
                            .apply { if (fingerprint != null) put("fingerprint", fingerprint) }
                        val verifyRes = runCatching {
                            val req = Request.Builder()
                                .url("$embedOrigin/api/videos/$code/embed/captcha/verify")
                                .post(verifyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                            for ((k, v) in baseHeaders) req.header(k, v)
                            CimaNowNetwork.directNoProxyClient.newCall(req.build()).execute().use {
                                if (it.isSuccessful) CimaNowCrypto.safeJsonObject(it.body?.string()) else null
                            }
                        }.getOrNull()
                        if (verifyRes?.optString("status") == "ok") {
                            captchaToken = verifyRes.optString("token").takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }

        val pbUrl = "$embedOrigin/api/videos/$code/embed/playback"
        val bodyJson = if (fingerprint != null) JSONObject().put("fingerprint", fingerprint).toString() else "{}"
        val req = Request.Builder().url(pbUrl).post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
        for ((k, v) in baseHeaders) req.header(k, v)
        if (!captchaToken.isNullOrBlank()) req.header("X-Captcha-Token", captchaToken)
        val resText = CimaNowNetwork.directNoProxyClient.newCall(req.build()).execute().use { it.body?.string().orEmpty() }
        val root = CimaNowCrypto.safeJsonObject(resText) ?: return@runCatching null
        root.optJSONObject("playback") ?: if (root.has("payload")) root else null
    }.getOrNull()

    private fun byseObtainFingerprint(embedOrigin: String, baseHeaders: Map<String, String>): JSONObject? {
        val now = System.currentTimeMillis()
        byseAttestCache[embedOrigin]?.let { (exp, fp) -> if (exp > now) return fp }
        return runCatching {
            val chReq = Request.Builder()
                .url("$embedOrigin/api/videos/access/challenge")
                .post("".toRequestBody(null))
            for ((k, v) in baseHeaders) chReq.header(k, v)
            val ch = CimaNowNetwork.directNoProxyClient.newCall(chReq.build()).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                CimaNowCrypto.safeJsonObject(res.body?.string())
            } ?: return@runCatching null

            val challengeId = ch.optString("challenge_id").takeIf { it.isNotBlank() } ?: return@runCatching null
            val nonce = ch.optString("nonce").takeIf { it.isNotBlank() } ?: return@runCatching null

            val kp = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val pub = kp.public as ECPublicKey
            val xB64 = CimaNowCrypto.b64UrlNoPad(toFixed32(pub.w.affineX))
            val yB64 = CimaNowCrypto.b64UrlNoPad(toFixed32(pub.w.affineY))

            val derSig = Signature.getInstance("SHA256withECDSA").run {
                initSign(kp.private)
                update(nonce.toByteArray(Charsets.UTF_8))
                sign()
            }
            val p1363Sig = CimaNowCrypto.b64UrlNoPad(derToP1363(derSig))
            val randHash = CimaNowCrypto.b64UrlNoPad(
                MessageDigest.getInstance("SHA-256").digest("$embedOrigin|$nonce".toByteArray(Charsets.UTF_8)),
            )

            val attestBody = JSONObject().apply {
                put("viewer_id", "")
                put("device_id", "")
                put("challenge_id", challengeId)
                put("nonce", nonce)
                put("signature", p1363Sig)
                put("public_key", JSONObject().put("kty", "EC").put("crv", "P-256").put("x", xB64).put("y", yB64))
                put("client", JSONObject().apply {
                    put("user_agent", DESKTOP_UA)
                    put("pixel_ratio", 1)
                    put("screen_width", 1920)
                    put("screen_height", 1080)
                    put("color_depth", 24)
                    put("languages", JSONArray(listOf("en-US", "en")))
                    put("timezone", "UTC")
                    put("hardware_concurrency", 8)
                    put("device_memory", 8)
                    put("touch_points", 0)
                    put("webgl_vendor", "Google Inc. (Intel)")
                    put("webgl_renderer", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)")
                    put("canvas_hash", randHash)
                    put("audio_hash", randHash)
                    put("pointer_type", "fine,hover")
                    put("media_devices", "ai1ao2vi1")
                    put("extra", JSONObject().put("vendor", "Google Inc.").put("appVersion", DESKTOP_UA.removePrefix("Mozilla/")))
                })
                put("storage", JSONObject())
                put("attributes", JSONObject().put("entropy", "medium"))
            }

            val atReq = Request.Builder()
                .url("$embedOrigin/api/videos/access/attest")
                .post(attestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            for ((k, v) in baseHeaders) atReq.header(k, v)
            val atRes = CimaNowNetwork.directNoProxyClient.newCall(atReq.build()).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                CimaNowCrypto.safeJsonObject(res.body?.string())
            } ?: return@runCatching null

            val token = atRes.optString("token").takeIf { it.isNotBlank() } ?: return@runCatching null
            val fp = JSONObject()
                .put("token", token)
                .put("viewer_id", atRes.optString("viewer_id"))
                .put("device_id", atRes.optString("device_id"))
                .put("confidence", atRes.optString("confidence", "medium"))
            byseAttestCache[embedOrigin] = (now + 240_000L) to fp
            fp
        }.getOrNull()
    }

    private suspend fun solveByseStreamPow(nonceStr: String, difficulty: Int): String? {
        if (difficulty <= 0) return "0"
        val targetBits = difficulty.coerceAtMost(26)
        val prefixBytes = "$nonceStr:".toByteArray(Charsets.ISO_8859_1)
        val found = AtomicReference<String?>(null)
        val workers = 4
        coroutineScope {
            (0 until workers).map { wid ->
                async(Dispatchers.Default) {
                    val e = IntArray(4)
                    val r = IntArray(512)
                    val buf = ByteArray(prefixBytes.size + 16)
                    System.arraycopy(prefixBytes, 0, buf, 0, prefixBytes.size)
                    var nVal = wid.toLong()
                    while (nVal < 6_000_000L && found.get() == null) {
                        val sStr = nVal.toString()
                        for (idx in sStr.indices) buf[prefixBytes.size + idx] = sStr[idx].code.toByte()
                        if (byseLeadingZeros(buf, prefixBytes.size + sStr.length, e, r) >= targetBits) {
                            found.compareAndSet(null, sStr)
                            break
                        }
                        nVal += workers
                    }
                }
            }.awaitAll()
        }
        return found.get()
    }

    private fun byseQuarter(e: IntArray) {
        e[0] += e[1]; e[3] = Integer.rotateLeft(e[3] xor e[0], 16)
        e[2] += e[3]; e[1] = Integer.rotateLeft(e[1] xor e[2], 12)
        e[0] += e[1]; e[3] = Integer.rotateLeft(e[3] xor e[0], 8)
        e[2] += e[3]; e[1] = Integer.rotateLeft(e[1] xor e[2], 7)
    }

    private fun byseLeadingZeros(input: ByteArray, length: Int, e: IntArray, r: IntArray): Int {
        e[0] = 0x6A09E667; e[1] = -0x4498517B; e[2] = 0x3C6EF372; e[3] = -0x5AB00AC6
        for (i in 0 until length) {
            e[0] = Integer.rotateLeft(e[0] + (input[i].toInt() and 0xFF), 7)
            byseQuarter(e)
        }
        repeat(8) { byseQuarter(e) }
        for (i in 0 until 512) { byseQuarter(e); r[i] = e[0] xor e[2] }
        repeat(2) {
            for (s in 0 until 512) {
                var c = Integer.rotateLeft(r[s] + r[r[s] and 511], 13)
                c = c xor (r[(s + 1) and 511] * (-0x61C8864F))
                r[s] = c; e[0] = e[0] xor c; byseQuarter(e)
            }
        }
        var lz = 0
        for (i in 0 until 8) {
            byseQuarter(e)
            var sVal = e[0]
            val base = i shl 6
            for (c in 0 until 64) {
                val d = r[base + c]
                sVal = Integer.rotateLeft(sVal + d, 5) xor (d * (-0x7A143589))
            }
            val word = sVal xor e[2]
            if (word == 0) lz += 32 else { lz += Integer.numberOfLeadingZeros(word); break }
        }
        return lz
    }

    private fun decryptBysePlayback(pb: JSONObject): String? {
        val keyParts = pb.optJSONArray("key_parts")
        if (keyParts != null && keyParts.length() >= 1) {
            val total = keyParts.length()
            val v = pb.optString("version", "1").trim().toIntOrNull() ?: pb.optInt("version", 1)
            val iv = runCatching { CimaNowCrypto.b64UrlDecode(pb.optString("iv")) }.getOrNull()
            val payload = runCatching { CimaNowCrypto.b64UrlDecode(pb.optString("payload")) }.getOrNull()
            if (iv != null && payload != null && iv.isNotEmpty() && payload.isNotEmpty()) {
                val keys = ArrayList<ByteArray>()
                if (v in 1..20 && v <= total && (31 - v) in 1..total) {
                    keys.add(CimaNowCrypto.b64UrlDecode(keyParts.optString(v - 1)) + CimaNowCrypto.b64UrlDecode(keyParts.optString(30 - v)))
                }
                if (total >= 2) {
                    keys.add(
                        CimaNowCrypto.b64UrlDecode(keyParts.optString((v - 1).coerceIn(0, total - 1))) +
                            CimaNowCrypto.b64UrlDecode(keyParts.optString((total - v).coerceIn(0, total - 1))),
                    )
                    keys.add(CimaNowCrypto.b64UrlDecode(keyParts.optString(0)) + CimaNowCrypto.b64UrlDecode(keyParts.optString(1)))
                }
                for (k in keys) {
                    runCatching {
                        val c = Cipher.getInstance("AES/GCM/NoPadding")
                        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
                        String(c.doFinal(payload), Charsets.UTF_8)
                    }.getOrNull()?.takeIf { it.contains("sources") }?.let { return it }
                }
            }
        }
        val p2 = pb.optString("payload2")
        val iv2 = pb.optString("iv2")
        val dk = pb.optJSONObject("decrypt_keys")
        if (p2.isNotBlank() && iv2.isNotBlank() && dk != null) {
            val e1 = dk.optString("edge_1")
            val e2 = dk.optString("edge_2")
            if (e1.isNotBlank() && e2.isNotBlank()) {
                return runCatching {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(CimaNowCrypto.b64UrlDecode(e1) + CimaNowCrypto.b64UrlDecode(e2), "AES"),
                        GCMParameterSpec(128, CimaNowCrypto.b64UrlDecode(iv2)),
                    )
                    String(c.doFinal(CimaNowCrypto.b64UrlDecode(p2)), Charsets.UTF_8)
                }.getOrNull()
            }
        }
        return null
    }

    private fun solveByseEdgePow(challenge: JSONObject?): String? {
        if (challenge == null || !challenge.optBoolean("enabled", false)) return null
        val token = challenge.optString("token").takeIf { it.isNotBlank() } ?: return null
        val ts = challenge.optLong("timestamp", 0L)
        val diff = challenge.optInt("difficulty", 0)
        if (diff !in 1..24) return null
        val full = diff / 8
        val rem = diff % 8
        val mask = if (rem == 0) 0 else (0xFF shl (8 - rem)) and 0xFF
        val md = MessageDigest.getInstance("SHA-256")
        for (nonce in 0 until 2_000_000) {
            val d = md.digest("$token$ts$nonce".toByteArray(Charsets.UTF_8))
            var ok = true
            for (b in 0 until full) {
                if (d[b].toInt() != 0) {
                    ok = false
                    break
                }
            }
            if (ok && rem > 0 && (d[full].toInt() and mask) != 0) ok = false
            if (ok) return "$token.$ts.$nonce"
        }
        return null
    }

    private fun toFixed32(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { System.arraycopy(raw, 0, it, 32 - raw.size, raw.size) }
        }
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var idx = 2
        if (der[1].toInt() and 0x80 != 0) idx += (der[1].toInt() and 0x7F)
        idx++
        val rLen = der[idx++].toInt() and 0xFF
        val rBytes = der.copyOfRange(idx, idx + rLen); idx += rLen + 1
        val sLen = der[idx++].toInt() and 0xFF
        val sBytes = der.copyOfRange(idx, idx + sLen)
        val out = ByteArray(64)
        val rClean = if (rBytes.size > 32) rBytes.copyOfRange(rBytes.size - 32, rBytes.size) else rBytes
        val sClean = if (sBytes.size > 32) sBytes.copyOfRange(sBytes.size - 32, sBytes.size) else sBytes
        System.arraycopy(rClean, 0, out, 32 - rClean.size, rClean.size)
        System.arraycopy(sClean, 0, out, 64 - sClean.size, sClean.size)
        return out
    }

    // ── Host 6: SaveFiles / StreamHLS (`streamhls.to`, `savefiles.com`) ──────

    internal suspend fun extractSaveFiles(
        url: String,
        referer: String?,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url)
        val fileCode = runCatching { URI(CimaNowNetwork.encodeUrl(url)).path }.getOrDefault("")
            .trim('/')
            .substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
            .trim()
        if (fileCode.isBlank()) return@withContext

        val embedUrl = "$origin/e/$fileCode"
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$origin/",
            "Origin" to origin,
        )

        var m3u8Url: String? = null

        val getHtml = fetchText(embedUrl, mapOf("User-Agent" to DESKTOP_UA, "Referer" to CimaNowNetwork.encodeUrl(referer ?: "$origin/")))
        if (!getHtml.isNullOrBlank()) {
            val combined = (unpackAllPacker(getHtml) + getHtml).joinToString("\n").replace("\\/", "/")
            m3u8Url = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(combined)?.value
        }

        if (m3u8Url == null) {
            val postHtml = runCatching {
                val form = FormBody.Builder()
                    .add("op", "embed")
                    .add("file_code", fileCode)
                    .add("auto", "1")
                    .add("referer", CimaNowNetwork.encodeUrl(referer ?: "$origin/"))
                    .build()
                val req = Request.Builder()
                    .url("$origin/dl")
                    .post(form)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Origin", origin)
                    .header("Referer", embedUrl)
                    .build()
                CimaNowNetwork.directNoProxyClient.newCall(req).execute().use {
                    if (it.isSuccessful) it.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")

            if (postHtml.isNotBlank()) {
                val combined = (unpackAllPacker(postHtml) + postHtml).joinToString("\n").replace("\\/", "/")
                m3u8Url = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(combined)?.value
            }
        }

        val stream = m3u8Url ?: return@withContext
        emitHlsVariantsOrMaster("SaveFiles", stream, "$origin/", headers, qualityHint ?: Qualities.P1080.value, callback)
    }

    // ── Host 7: VK Video (`vkvideo.ru/video_ext.php`, `vk.com/video_ext.php`) ─

    private suspend fun extractVkVideo(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val html = fetchText(
            url,
            mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://vk.ru/",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ) ?: return@withContext

        val unescaped = html.replace("\\/", "/")
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://vk.ru/",
            "Origin" to "https://vk.ru",
        )

        val seen = HashSet<String>()
        for (m in Regex(""""(?:mp4_|url)(\d{3,4})"\s*:\s*"(https?://[^"]+)"""").findAll(unescaped)) {
            val height = m.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
            val mp4Url = m.groupValues[2].replace("\\u0026", "&").trim()
            if (mp4Url.startsWith("http") && seen.add(mp4Url)) {
                callback(
                    newExtractorLink("VK", "VK [MP4]", mp4Url, ExtractorLinkType.VIDEO) {
                        this.referer = "https://vk.ru/"
                        this.quality = height
                        this.headers = headers
                    },
                )
            }
        }

        for (m in Regex(""""hls"\s*:\s*"(https?://[^"]+)"""").findAll(unescaped)) {
            val hlsUrl = m.groupValues[1].replace("\\u0026", "&").trim()
            if (hlsUrl.startsWith("http") && seen.add(hlsUrl)) {
                emitHlsVariantsOrMaster("VK", hlsUrl, "https://vk.ru/", headers, Qualities.Unknown.value, callback)
            }
        }
    }

    // ── Host 8: Ok.ru (`ok.ru/videoembed/<id>`) ──────────────────────────────

    private suspend fun extractOkRu(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val embedUrl = if (url.contains("/video/") && !url.contains("/videoembed/")) {
            url.replace("/video/", "/videoembed/")
        } else {
            url
        }
        val html = fetchText(
            embedUrl,
            mapOf("User-Agent" to DESKTOP_UA, "Accept-Language" to "en-US,en;q=0.9"),
        ) ?: return@withContext

        val dataOptsRaw = Regex("""data-options=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return@withContext
        val optsJson = CimaNowCrypto.safeJsonObject(Parser.unescapeEntities(dataOptsRaw, false))
            ?: return@withContext
        val flashvars = optsJson.optJSONObject("flashvars") ?: return@withContext
        val metaJson = flashvars.optJSONObject("metadata") ?: run {
            val metaRaw = flashvars.optString("metadata")
            CimaNowCrypto.safeJsonObject(metaRaw)
                ?: CimaNowCrypto.safeJsonObject(Parser.unescapeEntities(metaRaw, false))
        } ?: return@withContext

        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://ok.ru/",
            "Origin" to "https://ok.ru",
        )
        val qualityMap = mapOf(
            "mobile" to Qualities.P144.value,
            "lowest" to Qualities.P240.value,
            "low" to Qualities.P360.value,
            "sd" to Qualities.P480.value,
            "hd" to Qualities.P720.value,
            "full" to Qualities.P1080.value,
            "quad" to Qualities.P1440.value,
            "ultra" to Qualities.P2160.value,
        )

        val videos = metaJson.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val vUrl = v.optString("url").trim()
                if (!vUrl.startsWith("http")) continue
                val qName = v.optString("name").lowercase().trim()
                val qual = qualityMap[qName] ?: Qualities.Unknown.value
                callback(
                    newExtractorLink("OkRu", "OkRu [MP4]", vUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://ok.ru/"
                        this.quality = qual
                        this.headers = headers
                    },
                )
            }
        }

        val hlsUrl = metaJson.optString("hlsManifestUrl")
            .ifBlank { metaJson.optString("ondemandHls") }
            .trim()
        if (hlsUrl.startsWith("http")) {
            emitHlsVariantsOrMaster("OkRu", hlsUrl, "https://ok.ru/", headers, Qualities.Unknown.value, callback)
        }
    }

    // ── Host 9: VidGuard (`vid-guard.com`, `vgfplay.com`, `listeamed.net`) ───

    private suspend fun extractVidGuard(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url)
        val code = runCatching { URI(CimaNowNetwork.encodeUrl(url)).path }.getOrDefault("")
            .trim('/').substringAfterLast('/').trim()
        val embedUrl = if (code.isNotBlank()) "$origin/e/$code" else url
        val html = fetchText(embedUrl, mapOf("User-Agent" to DESKTOP_UA, "Referer" to CimaNowNetwork.encodeUrl(referer ?: "$origin/")))
            ?: return@withContext

        // Look for plain or base64 stream JSON inside `window.svg` or script payload
        val streamCandidate = Regex(""""stream"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!streamCandidate.isNullOrBlank()) {
            val signed = signVidGuardStreamUrl(streamCandidate)
            val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$origin/", "Origin" to origin)
            emitHlsVariantsOrMaster("VidGuard", signed, "$origin/", headers, Qualities.Unknown.value, callback)
        }
    }

    private fun signVidGuardStreamUrl(rawUrl: String): String {
        val sigMatch = Regex("""([?&]sig=)([0-9a-fA-F]+)""").find(rawUrl) ?: return rawUrl
        val hexSig = sigMatch.groupValues[2]
        if (hexSig.length < 12 || hexSig.length % 2 != 0) return rawUrl
        return runCatching {
            val xored = String(
                CharArray(hexSig.length / 2) { idx ->
                    ((hexSig.substring(idx * 2, idx * 2 + 2).toInt(16)) xor 2).toChar()
                },
            )
            val decoded = String(CimaNowCrypto.b64Decode(xored), Charsets.UTF_8)
            if (decoded.length <= 10) return@runCatching rawUrl
            val trimmed = decoded.dropLast(5).reversed().dropLast(5).let { s ->
                // Swap adjacent pairs
                val arr = s.toCharArray()
                var i = 0
                while (i + 1 < arr.size) {
                    val t = arr[i]
                    arr[i] = arr[i + 1]
                    arr[i + 1] = t
                    i += 2
                }
                String(arr)
            }
            val newSig = CimaNowCrypto.b64EncodeNoWrap(trimmed.toByteArray(Charsets.UTF_8)).trimEnd('=')
            rawUrl.replaceRange(sigMatch.range, "${sigMatch.groupValues[1]}$newSig")
        }.getOrDefault(rawUrl)
    }

    // ── Host 10: Uqload + Generic P.A.C.K.E.R. & HTML Video Extractor ────────

    private suspend fun extractPackerAndHtmlEmbed(
        url: String,
        providerLabel: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val fetched = fetchTextWithFinalUrl(
            url,
            mapOf(
                "User-Agent" to DESKTOP_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to CimaNowNetwork.encodeUrl(referer ?: "${CimaNowNetwork.mainUrl}/"),
            ),
        ) ?: return@withContext

        val (finalUrl, html) = fetched
        if (html.isBlank()) return@withContext

        val activeOrigin = originOf(finalUrl)
        val effRef = "$activeOrigin/"
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to effRef,
            "Origin" to activeOrigin,
        )

        val combined = (unpackAllPacker(html) + html).joinToString("\n").replace("\\/", "/")
        val hlsCandidates = LinkedHashSet<String>()
        val mp4Candidates = LinkedHashSet<String>()

        for (m in Regex("""var\s+links\s*=\s*(\{[^}]+\})""").findAll(combined)) {
            val obj = CimaNowCrypto.safeJsonObject(m.groupValues[1]) ?: continue
            for (k in listOf("hls4", "hls2", "hls3", "hls")) {
                val full = resolveRelativeUrl(obj.optString(k).trim(), activeOrigin) ?: continue
                if (full.contains(".m3u8", true)) hlsCandidates.add(full)
            }
        }

        for (m in Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""", RegexOption.IGNORE_CASE).findAll(combined)) {
            hlsCandidates.add(m.groupValues[1])
        }

        // Uqload `sources: ["https://.../v.mp4"]` & JWPlayer `{file: "https://...mp4"}`
        for (m in Regex("""["'](https?://[^"'\s<>]+\.(?:mp4|mkv)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE).findAll(combined)) {
            val cand = m.groupValues[1]
            if (!cand.contains("blank.", true) && !cand.contains("preroll", true)) {
                mp4Candidates.add(cand)
            }
        }

        for (hls in hlsCandidates.take(3)) {
            emitHlsVariantsOrMaster(providerLabel, hls, effRef, headers, Qualities.Unknown.value, callback)
        }

        for (mp4 in mp4Candidates.take(4)) {
            val qual = detectQualityInt("$mp4 $html")
            callback(
                newExtractorLink(providerLabel, "$providerLabel [MP4]", CimaNowNetwork.encodeUrl(mp4), ExtractorLinkType.VIDEO) {
                    this.referer = effRef
                    this.quality = qual
                    this.headers = headers
                },
            )
        }

        // Subtitles in JWPlayer `tracks`
        for (m in Regex("""\{\s*["']?file["']?\s*:\s*["'](https?://[^"']+\.(?:vtt|srt|ass)[^"']*)["'][^}]*["']?label["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(combined)) {
            subtitleCallback(newSubtitleFile(m.groupValues[2], m.groupValues[1]))
        }
    }

    // ── HLS Multi-Variant Expander (Pure OkHttp — Works in Android & JUnit) ──

    private suspend fun emitHlsVariantsOrMaster(
        label: String,
        masterUrl: String,
        referer: String,
        headers: Map<String, String>,
        fallbackQuality: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val safeMaster = CimaNowNetwork.encodeUrl(masterUrl)
        val playlistText = fetchText(safeMaster, headers)
        var emittedVariants = 0

        if (!playlistText.isNullOrBlank() && playlistText.contains("#EXT-X-STREAM-INF")) {
            val lines = playlistText.lines()
            var pendingQuality = Qualities.Unknown.value
            var sawStreamInf = false
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val resHeight = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val nameQual = Regex("""NAME=["']?(\d{3,4})p?["']?""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.toIntOrNull()
                    pendingQuality = resHeight ?: nameQual ?: fallbackQuality
                    sawStreamInf = true
                } else if (!line.startsWith("#") && sawStreamInf) {
                    sawStreamInf = false
                    val variantUrl = resolveM3u8ChildUrl(safeMaster, line)
                    if (variantUrl != null) {
                        emittedVariants++
                        callback(
                            newExtractorLink(label, "$label [HLS]", variantUrl, ExtractorLinkType.M3U8) {
                                this.referer = referer
                                this.quality = pendingQuality
                                this.headers = headers
                            },
                        )
                    }
                }
            }
        }

        if (emittedVariants == 0) {
            callback(
                newExtractorLink(label, "$label [HLS]", safeMaster, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = fallbackQuality
                    this.headers = headers
                },
            )
        }
    }

    private fun resolveM3u8ChildUrl(baseM3u8: String, child: String): String? = runCatching {
        when {
            child.startsWith("http://") || child.startsWith("https://") -> CimaNowNetwork.encodeUrl(child)
            else -> URI(baseM3u8).resolve(child).toString()
        }
    }.getOrNull()

    // ── Dean Edwards P.A.C.K.E.R. Unpacker ───────────────────────────────────

    private fun unpackAllPacker(html: String): List<String> {
        val out = ArrayList<String>()
        val packerRe = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{[\s\S]*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)""",
        )
        for (m in packerRe.findAll(html)) {
            val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
            val radix = m.groupValues[2].toIntOrNull() ?: continue
            val count = m.groupValues[3].toIntOrNull() ?: continue
            val symtab = m.groupValues[4].split('|')
            if (radix < 2 || radix > 62 || count <= 0) continue

            val unpacked = Regex("""\b[0-9a-zA-Z]+\b""").replace(payload) { tok ->
                val idx = fromPackerBase(tok.value, radix)
                if (idx != null && idx in symtab.indices && symtab[idx].isNotEmpty()) symtab[idx] else tok.value
            }
            if (unpacked.isNotBlank() && unpacked !in out) out.add(unpacked)
        }
        return out
    }

    private fun fromPackerBase(token: String, radix: Int): Int? {
        if (radix <= 36) return token.toIntOrNull(radix)
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var acc = 0
        for (ch in token) {
            val d = if (ch.code < 128) alphabet.indexOf(ch) else -1
            if (d < 0 || d >= radix) return null
            acc = acc * radix + d
        }
        return acc
    }

    // ── Shared HTTP & String Utilities ───────────────────────────────────────

    private fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String? =
        fetchTextWithFinalUrl(url, headers)?.second

    private fun fetchTextWithFinalUrl(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String>? =
        runCatching {
            val rb = Request.Builder().url(CimaNowNetwork.encodeUrl(url))
            for ((k, v) in headers) rb.header(k, v)
            CimaNowNetwork.directNoProxyClient.newCall(rb.build()).execute().use { res ->
                if (res.code >= 500) return@use null
                res.request.url.toString() to res.body?.string().orEmpty()
            }
        }.getOrNull()

    private fun resolveRelativeUrl(raw: String?, origin: String): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> CimaNowNetwork.encodeUrl(t)
            t.startsWith("//") -> CimaNowNetwork.encodeUrl("https:$t")
            t.startsWith("/") -> CimaNowNetwork.encodeUrl("${origin.trimEnd('/')}$t")
            else -> null
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URI(CimaNowNetwork.encodeUrl(url)).host?.lowercase().orEmpty().removePrefix("www.") }
            .getOrDefault("")

    private fun originOf(url: String): String =
        runCatching {
            val u = URI(CimaNowNetwork.encodeUrl(url))
            val scheme = u.scheme ?: "https"
            val host = u.host ?: return@runCatching CimaNowNetwork.mainUrl
            val portPart = if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""
            "$scheme://$host$portPart"
        }.getOrDefault(CimaNowNetwork.mainUrl)

    internal fun detectQualityInt(text: String): Int {
        val lower = text.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1440") || lower.contains("2k") -> Qualities.P1440.value
            lower.contains("1080") || lower.contains("fhd") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            lower.contains("240") -> Qualities.P240.value
            lower.contains("144") -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private fun prettyProviderName(host: String): String = when {
        host.contains("uqload") -> "Uqload"
        host.contains("upns") || host.contains("upnshare") -> "UpnShare"
        host.contains("byse") || host.contains("filemoon") -> "Byse"
        host.contains("streamhls") || host.contains("savefiles") -> "SaveFiles"
        host.contains("ok.ru") || host.contains("odnoklassniki") -> "OkRu"
        host.contains("vk") -> "VK"
        host.contains("jetload") -> "Jetload"
        host.contains("vid-guard") || host.contains("vgfplay") || host.contains("listeamed") -> "VidGuard"
        host.contains("vidmoly") -> "Vidmoly"
        host.contains("luluvdo") || host.contains("lulustream") -> "LuluStream"
        host.contains("mp4upload") -> "Mp4Upload"
        host.contains("streamwish") -> "StreamWish"
        host.contains("vidhide") -> "VidHide"
        else -> host.substringBefore('.').replaceFirstChar { it.uppercase() }.ifEmpty { "Server" }
    }
}

// ── Standalone CloudStream `ExtractorApi` Registrations ──────────────────────

class CimaNowUpnShareExtractor : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://cimanow.upns.online"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CimaNowExtractors.extractUpnShare(url, subtitleCallback, callback)
    }
}

class CimaNowByseExtractor : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysetayico.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CimaNowExtractors.extractByse(url, referer, subtitleCallback, callback)
    }
}

class CimaNowSaveFilesExtractor : ExtractorApi() {
    override val name = "SaveFiles"
    override val mainUrl = "https://streamhls.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CimaNowExtractors.extractSaveFiles(url, referer, null, callback)
    }
}
