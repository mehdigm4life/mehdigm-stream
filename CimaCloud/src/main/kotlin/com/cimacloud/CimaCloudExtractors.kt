package com.cimacloud

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming, time-bounded multi-quality watch & download extractor suite for CimaCloud v35.
 *
 * Resolves HLS multi-variant playlists (240p..2160p via M3u8Helper) AND progressive MP4/MKV
 * watch/download streams across:
 * - VidTube (`down.vidtube.one` via CimaCloud extractor API + direct fallback)
 * - StreamRuby (`stmruby.com`, `streamruby.*`, `rubystm.*`)
 * - Morencius (`morencius.com`)
 * - LuluStream / LuluVdo (`luluvdo.com`, `lulustream.com`)
 * - 1Vid (`1vid.xyz`, `1vid.online`)
 * - Vidmoly (`vidmoly.org`, `vidmoly.to`, `vidmoly.me`)
 * - VidSpeed (`vidspeed.org`, `vidspeed.*`)
 * - Mp4Plus (`mp4plus.org` direct progressive MP4)
 * - Mp4Upload (`mp4upload.com` direct progressive MP4)
 * - Uqload (`uqload.vc`, `uqload.is`, `uqload.net`, `uqload.to`, `uqload.io` HLS + MP4)
 * - StreamHG / VidHide (`hanerix.com`, `hgplaycdn.com`, `hgcloud.to` -> `vibuxer.com`)
 * - Vidara (`vidaraa.cc`, `vidara.so` `/api/stream` JSON + external subtitles)
 * - Byse (`bysesukior.com`, `bysezejataos.com` ECDSA attestation + PoW + AES-GCM)
 * - VOE (`voe.sx` dynamic JS mirror redirect + 7-step payload decode: HLS + direct MP4 download)
 * - MixDrop (`mixdrop.top`, `miixdrop.top`, `mixdrop.to`, `mxdrop.click` progressive MP4)
 * - Ok.ru (`ok.ru`, `odnoklassniki.ru` multi-quality MP4 144p..2160p + HLS)
 * - VK (`vk.ru`, `vk.com`, `vkvideo.ru` multi-quality MP4 144p..2160p + HLS)
 * - DeveloperPro Worker (`*.developer-pro.workers.dev/video?url=...` multi-quality MP4)
 * - Google Photos (`photos.google.com/share/...` verified MP4 360p/720p/1080p)
 * - PixelDrain (`pixeldrain.com/u/{id}` -> `/api/file/{id}` progressive MP4)
 * - MediaFire (`mediafire.com/file/...` direct download MP4)
 * - Direct CDN streams (`stardima.worldnow.top`, `.mp4`, `.mkv`, `.m3u8`)
 * - HyperWatching (`v2.hyperwatching.com/watch/{hashid}` multi-server Inertia aggregator)
 * - Universal CloudStream `loadExtractor` + remote `extractor.php` fallback
 */
internal object CimaCloudExtractors {

    internal const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val TOTAL_EXTRACTION_BUDGET_MS = 35_000L
    private const val PER_SERVER_TIMEOUT_MS = 14_000L
    private const val MAX_CONCURRENT_SERVERS = 8
    private const val MAX_RECURSION_DEPTH = 2

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val byseAttestCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    suspend fun extractAllServers(
        embedUrls: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (embedUrls.isEmpty()) return false

        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val emittedSubs = ConcurrentHashMap.newKeySet<String>()
        val emittedCount = AtomicInteger(0)

        val dedupLinkCallback: (ExtractorLink) -> Unit = { link ->
            val key = link.url.trim()
            if (key.isNotEmpty() && emittedUrls.add(key)) {
                emittedCount.incrementAndGet()
                callback(link)
            }
        }
        val dedupSubCallback: (SubtitleFile) -> Unit = { sub ->
            val key = sub.url.trim()
            if (key.isNotEmpty() && emittedSubs.add(key)) {
                subtitleCallback(sub)
            }
        }

        val semaphore = Semaphore(MAX_CONCURRENT_SERVERS)

        withTimeoutOrNull(TOTAL_EXTRACTION_BUDGET_MS) {
            supervisorScope {
                embedUrls.distinct().map { rawUrl ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            withTimeoutOrNull(PER_SERVER_TIMEOUT_MS) {
                                dispatchSingleUrl(
                                    rawUrl = rawUrl,
                                    referer = CimaCloudCrypto.CANONICAL_SITE_URL + "/",
                                    depth = 0,
                                    subtitleCallback = dedupSubCallback,
                                    callback = dedupLinkCallback,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        return emittedCount.get() > 0
    }

    internal suspend fun dispatchSingleUrl(
        rawUrl: String,
        referer: String?,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (depth > MAX_RECURSION_DEPTH) return
        val url = sanitizeHttpUrl(rawUrl.trim())
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        val host = hostOf(url)
        val localCount = AtomicInteger(0)
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            localCount.incrementAndGet()
            callback(link)
        }

        try {
            when {
                // 1. Direct progressive MP4 / MKV / WEBM or direct M3U8 URL
                isDirectMediaUrl(url) -> {
                    emitDirectMedia(url, referer, countingCallback)
                }

                // 2. Google Photos Share (`photos.google.com/share/...`)
                host.contains("photos.google.com") || host.contains("photos.app.goo.gl") -> {
                    extractGooglePhotos(url, countingCallback)
                }

                // 3. DeveloperPro Worker (`*.developer-pro.workers.dev/video?url=...`)
                host.contains("developer-pro.workers.dev") -> {
                    extractDeveloperProWorker(url, countingCallback)
                }

                // 4. PixelDrain (`pixeldrain.com/u/{id}`)
                host.contains("pixeldrain.") -> {
                    extractPixelDrain(url, countingCallback)
                }

                // 5. MediaFire (`mediafire.com/file/...`)
                host.contains("mediafire.com") -> {
                    extractMediaFire(url, subtitleCallback, countingCallback)
                }

                // 6. VidTube (`down.vidtube.one`, `vidtube.*`) — Cloudflare-gated on direct GET,
                //    resolved cleanly into 5 HLS variants via CimaCloud remote extractor.php
                host.contains("vidtube.") -> {
                    extractViaRemotePhp(url, "VidTube", countingCallback)
                    if (localCount.get() == 0) {
                        extractPackerAndHtmlEmbed(url, "VidTube", subtitleCallback, countingCallback)
                    }
                }

                // 7. Vidara (`vidaraa.cc`, `vidara.so`, `vidara.*`)
                host.contains("vidara") -> {
                    extractVidara(url, subtitleCallback, countingCallback)
                }

                // 8. Byse (`bysesukior.com`, `bysezejataos.com`, `byse*`)
                host.startsWith("byse") || host.contains(".byse") -> {
                    extractByse(url, referer, subtitleCallback, countingCallback)
                }

                // 9. VOE (`voe.sx` + dynamic mirror redirect + 7-step HLS + direct MP4 decoder)
                host == "voe.sx" || host.endsWith(".voe.sx") || isKnownVoeHost(host) -> {
                    extractVoe(url, referer, subtitleCallback, countingCallback)
                }

                // 10. MixDrop (`mixdrop.*`, `miixdrop.*`, `mxdrop.*`)
                host.contains("mixdrop") || host.contains("miixdrop") || host.contains("mxdrop") -> {
                    extractMixDrop(url, countingCallback)
                }

                // 11. Ok.ru (`ok.ru`, `odnoklassniki.ru`) — 144p..2160p MP4 + HLS
                host == "ok.ru" || host.endsWith(".ok.ru") || host.contains("odnoklassniki.ru") -> {
                    extractOkRu(url, countingCallback)
                }

                // 12. VK (`vk.ru`, `vk.com`, `vkvideo.ru`) — 144p..2160p MP4 + HLS
                host == "vk.ru" || host.endsWith(".vk.ru") ||
                    host == "vk.com" || host.endsWith(".vk.com") ||
                    host.contains("vkvideo.ru") -> {
                    extractVk(url, countingCallback)
                }

                // 13. HGCloud / StreamHG stub mirrors (`hgcloud.to`, `hglink.to`, `dumbalag.com`, `uasopt.com`)
                host.contains("hgcloud.") || host.contains("hglink.") ||
                    host.contains("dumbalag.") || host.contains("uasopt.") ||
                    host.contains("dhcplay.") -> {
                    extractStreamHgMirror(url, subtitleCallback, countingCallback)
                }

                // 14. HyperWatching multi-server aggregator (`v2.hyperwatching.com/watch/{hashid}`)
                host.contains("hyperwatching.") -> {
                    extractHyperWatching(url, depth, subtitleCallback, countingCallback)
                }

                // 15. Known HTML / JWPlayer / P.A.C.K.E.R. / MP4 hosts
                isPackerOrDirectHtmlHost(host) -> {
                    val label = prettyProviderName(host)
                    extractPackerAndHtmlEmbed(url, label, subtitleCallback, countingCallback)
                }
            }

            // Universal fallback chain if primary handler yielded nothing
            if (localCount.get() == 0) {
                runCatching {
                    loadExtractor(url, referer, subtitleCallback, countingCallback)
                }
            }
            if (localCount.get() == 0 && !host.contains("vidtube.")) {
                extractPackerAndHtmlEmbed(url, prettyProviderName(host), subtitleCallback, countingCallback)
            }
            if (localCount.get() == 0 && !host.contains("vidtube.")) {
                extractViaRemotePhp(url, prettyProviderName(host), countingCallback)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Isolated per-server failure; never aborts sibling servers
        }
    }

    // ── 1. Direct Progressive (.mp4 / .mkv) & Direct .m3u8 ───────────────────

    private fun isDirectMediaUrl(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        return cleanPath.endsWith(".mp4") ||
            cleanPath.endsWith(".mkv") ||
            cleanPath.endsWith(".webm") ||
            cleanPath.endsWith(".m3u8")
    }

    private suspend fun emitDirectMedia(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url)
        val effRef = referer?.let { asciiSafeHeaderUrl(it) } ?: "$origin/"
        val label = prettyProviderName(hostOf(url))
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        val quality = detectQualityInt(url)
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to effRef,
        )

        if (cleanPath.endsWith(".m3u8")) {
            emitHlsVariantsOrMaster(label, url, effRef, headers, quality, callback)
        } else {
            val tag = if (cleanPath.endsWith(".mkv")) "$label [MKV]" else "$label [MP4]"
            callback(
                newExtractorLink(label, tag, url, ExtractorLinkType.VIDEO) {
                    this.referer = effRef
                    this.quality = quality
                    this.headers = headers
                },
            )
        }
    }

    // ── 2. Google Photos Share (`photos.google.com/share/...`) ───────────────

    internal suspend fun extractGooglePhotos(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val html = fetchText(
            url,
            headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ) ?: return@withContext

        val normalized = html
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val streamHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://photos.google.com/",
            "Origin" to "https://photos.google.com",
        )

        val candidates = LinkedHashSet<String>()
        Regex("""https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]{20,}""")
            .findAll(normalized)
            .forEach { candidates.add(it.value) }
        Regex("""https://[a-zA-Z0-9._-]*googlevideo\.com/videoplayback[^\s"'\\<>)}\]]*""")
            .findAll(normalized)
            .forEach { m ->
                candidates.add(m.value.replace(Regex("""=m\d+$"""), ""))
            }

        val tiers = listOf(
            "=m37" to Qualities.P1080.value,
            "=m22" to Qualities.P720.value,
            "=m18" to Qualities.P360.value,
        )

        for (base in candidates.take(4)) {
            if (base.contains("googlevideo.com/videoplayback?")) {
                callback(
                    newExtractorLink("GooglePhotos", "Google Photos [Direct]", base, ExtractorLinkType.VIDEO) {
                        this.referer = "https://photos.google.com/"
                        this.quality = Qualities.P720.value
                        this.headers = streamHeaders
                    },
                )
                continue
            }

            // Probe =m18 with a tiny 32-byte range request to distinguish video items from album cover JPEGs
            if (!isVideoMp4Endpoint("$base=m18", streamHeaders)) continue

            for ((suffix, qual) in tiers) {
                val tierUrl = "$base$suffix"
                if (suffix == "=m18" || isVideoMp4Endpoint(tierUrl, streamHeaders)) {
                    callback(
                        newExtractorLink("GooglePhotos", "Google Photos [MP4]", tierUrl, ExtractorLinkType.VIDEO) {
                            this.referer = "https://photos.google.com/"
                            this.quality = qual
                            this.headers = streamHeaders
                        },
                    )
                }
            }
            break
        }
    }

    private fun isVideoMp4Endpoint(url: String, headers: Map<String, String>): Boolean {
        return try {
            val reqBuilder = Request.Builder().url(url).header("Range", "bytes=0-31")
            for ((k, v) in headers) reqBuilder.header(k, v)
            CimaCloudCrypto.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code != 200 && resp.code != 206) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("video/") || ct.contains("octet-stream")) return true
                val bytes = resp.body?.bytes() ?: return false
                bytes.size >= 8 &&
                    bytes[4] == 'f'.code.toByte() &&
                    bytes[5] == 't'.code.toByte() &&
                    bytes[6] == 'y'.code.toByte() &&
                    bytes[7] == 'p'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ── 3. DeveloperPro Worker (`*.developer-pro.workers.dev/video?url=...`) ─

    internal suspend fun extractDeveloperProWorker(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val text = fetchText(url, headers = mapOf("User-Agent" to DESKTOP_UA, "Accept" to "application/json,*/*"))
            ?: return@withContext
        val json = CimaCloudCrypto.safeJsonObject(text) ?: return@withContext
        val arr = json.optJSONArray("availableQualities")
            ?: json.optJSONArray("sources")
            ?: json.optJSONArray("links")
            ?: return@withContext

        val origin = originOf(url)
        val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$origin/")

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val mediaUrl = item.optString("url").ifBlank { item.optString("file") }.trim()
            if (!mediaUrl.startsWith("http")) continue
            val qStr = item.optString("quality").ifBlank { item.optString("label") }
            val qual = detectQualityInt(qStr)
            val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    "CloudWorker",
                    "CloudStream Direct [MP4]",
                    mediaUrl,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$origin/"
                    this.quality = qual
                    this.headers = headers
                },
            )
        }
    }

    // ── 4. PixelDrain (`pixeldrain.com/u/{id}`) ──────────────────────────────

    internal suspend fun extractPixelDrain(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = Regex("""pixeldrain\.[a-z]+/(?:u|api/file)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val directUrl = "https://pixeldrain.com/api/file/$id"
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://pixeldrain.com/u/$id",
        )
        callback(
            newExtractorLink("PixelDrain", "PixelDrain [Download MP4]", directUrl, ExtractorLinkType.VIDEO) {
                this.referer = "https://pixeldrain.com/"
                this.quality = detectQualityInt(url).takeIf { it != Qualities.Unknown.value } ?: Qualities.P720.value
                this.headers = headers
            },
        )
    }

    // ── 5. MediaFire (`mediafire.com/file/...`) ──────────────────────────────

    private suspend fun extractMediaFire(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val html = fetchText(url, headers = mapOf("User-Agent" to DESKTOP_UA))
        if (!html.isNullOrBlank()) {
            var directHref = Regex("""id=["']downloadButton["'][^>]*href=["'](https?://download[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
                ?: Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)

            if (directHref == null) {
                val scrambled = Regex("""data-scrambled-url=["']([A-Za-z0-9+/=]+)["']""")
                    .find(html)?.groupValues?.get(1)
                if (!scrambled.isNullOrBlank()) {
                    directHref = runCatching {
                        String(Base64.decode(scrambled, Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrNull()?.takeIf { it.startsWith("http") }
                }
            }

            if (directHref != null) {
                val qual = detectQualityInt("$url $directHref")
                callback(
                    newExtractorLink("MediaFire", "MediaFire [Download MP4]", sanitizeHttpUrl(directHref), ExtractorLinkType.VIDEO) {
                        this.referer = "https://www.mediafire.com/"
                        this.quality = qual
                        this.headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://www.mediafire.com/")
                    },
                )
                return@withContext
            }
        }
        runCatching { loadExtractor(url, "https://www.mediafire.com/", subtitleCallback, callback) }
    }

    // ── 6. Remote CimaCloud PHP Extractor (`extractor.php`, VidTube) ─────────

    private suspend fun extractViaRemotePhp(
        embedUrl: String,
        providerLabel: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val cfg = CimaCloudCrypto.resolveConfig()
        val encoded = URLEncoder.encode(embedUrl, "UTF-8")
        val reqUrl = "${cfg.extractorUrl}?url=$encoded"
        val body = fetchText(reqUrl, headers = mapOf("User-Agent" to CimaCloudCrypto.APP_USER_AGENT))
            ?: return@withContext
        val json = CimaCloudCrypto.safeJsonObject(body) ?: return@withContext
        if (json.has("error")) return@withContext

        val linksArr = json.optJSONArray("servers")
            ?: json.optJSONArray("links")
            ?: json.optJSONArray("sources")
            ?: json.optJSONArray("data")
            ?: return@withContext

        val defaultRef = "${originOf(embedUrl)}/"
        for (i in 0 until linksArr.length()) {
            val item = linksArr.optJSONObject(i) ?: continue
            val mediaUrl = item.optString("url").ifBlank { item.optString("file") }.trim()
            if (!mediaUrl.startsWith("http")) continue

            val customHeaders = LinkedHashMap<String, String>()
            customHeaders["User-Agent"] = DESKTOP_UA

            item.optString("referer").trim().takeIf { it.isNotEmpty() }?.let {
                customHeaders["Referer"] = asciiSafeHeaderUrl(it)
            }
            item.optString("origin").trim().takeIf { it.isNotEmpty() }?.let {
                customHeaders["Origin"] = asciiSafeHeaderUrl(it)
            }
            item.optJSONObject("headers")?.let { hObj ->
                val keys = hObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = hObj.optString(k).trim()
                    if (v.isNotEmpty()) customHeaders[k] = asciiSafeHeaderUrl(v)
                }
            }
            val effRef = customHeaders["Referer"] ?: customHeaders["referer"] ?: defaultRef
            customHeaders.putIfAbsent("Referer", effRef)

            val qualStr = item.optString("quality")
                .ifBlank { item.optString("label") }
                .ifBlank { item.optString("name") }
            val qual = detectQualityInt("$qualStr $mediaUrl")
            val isM3u8 = item.optString("type").equals("hls", ignoreCase = true) ||
                mediaUrl.contains(".m3u8", ignoreCase = true)
            val isMasterPlaylist = mediaUrl.contains("master.m3u8", ignoreCase = true) &&
                qual == Qualities.Unknown.value

            if (isM3u8 && (qualStr.equals("auto", ignoreCase = true) || isMasterPlaylist)) {
                emitHlsVariantsOrMaster(providerLabel, mediaUrl, effRef, customHeaders, qual, callback)
            } else {
                val displayTag = if (qualStr.isNotBlank() && !qualStr.contains("سيرفر")) {
                    "$providerLabel ($qualStr)"
                } else if (isM3u8) {
                    providerLabel
                } else {
                    "$providerLabel [MP4]"
                }
                callback(
                    newExtractorLink(
                        providerLabel,
                        displayTag,
                        mediaUrl,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = effRef
                        this.quality = qual
                        this.headers = customHeaders
                    },
                )
            }
        }
    }

    // ── 7. Vidara (`vidaraa.cc`, `vidara.so`) — `/api/stream` + Subtitles ────

    internal suspend fun extractVidara(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val fileCode = Regex("""/(?:e|d|v|embed)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?').trim()
        if (fileCode.isEmpty()) return@withContext

        val primaryOrigin = originOf(url)
        val candidateOrigins = linkedSetOf(primaryOrigin, "https://vidaraa.cc", "https://vidara.so")

        var data: JSONObject? = null
        var activeOrigin = primaryOrigin

        for (candOrigin in candidateOrigins) {
            val postBody = JSONObject()
                .put("filecode", fileCode)
                .put("device", "web")
                .put("codecs", JSONArray())
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val postObj = runCatching {
                val req = Request.Builder()
                    .url("$candOrigin/api/stream")
                    .post(postBody)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "application/json")
                    .header("Origin", candOrigin)
                    .header("Referer", "$candOrigin/e/$fileCode")
                    .build()
                CimaCloudCrypto.httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else CimaCloudCrypto.safeJsonObject(resp.body?.string())
                }
            }.getOrNull()

            if (postObj != null && postObj.optString("streaming_url").startsWith("http")) {
                data = postObj
                activeOrigin = candOrigin
                break
            }

            // GET fallback (`?filecode=`)
            val getObj = fetchText(
                "$candOrigin/api/stream?filecode=$fileCode",
                headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$candOrigin/e/$fileCode"),
            )?.let { CimaCloudCrypto.safeJsonObject(it) }
            if (getObj != null && getObj.optString("streaming_url").startsWith("http")) {
                data = getObj
                activeOrigin = candOrigin
                break
            }
        }

        val resolved = data ?: return@withContext
        val streamUrl = resolved.optString("streaming_url").trim()
        if (!streamUrl.startsWith("http")) return@withContext

        val titleQual = detectQualityInt(resolved.optString("title"))
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$activeOrigin/",
            "Origin" to activeOrigin,
        )

        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            emitHlsVariantsOrMaster("Vidara", streamUrl, "$activeOrigin/", headers, titleQual, callback)
        } else {
            callback(
                newExtractorLink("Vidara", "Vidara [MP4]", streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$activeOrigin/"
                    this.quality = titleQual
                    this.headers = headers
                },
            )
        }

        val subsArr = resolved.optJSONArray("subtitles")
        if (subsArr != null) {
            for (i in 0 until subsArr.length()) {
                val sObj = subsArr.optJSONObject(i) ?: continue
                val subPath = sObj.optString("file_path").ifBlank { sObj.optString("file") }.trim()
                val fullSub = resolveRelativeUrl(subPath, activeOrigin) ?: continue
                val lang = sObj.optString("language").ifBlank { sObj.optString("label") }.ifBlank { "Arabic" }
                subtitleCallback(newSubtitleFile(lang, fullSub))
            }
        }
    }

    // ── 8. Byse (`bysesukior.com`, `bysezejataos.com`) — ECDSA + PoW + AES-GCM

    private suspend fun extractByse(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val origin = originOf(url)
        val code = runCatching { URI(url).path }.getOrDefault("")
            .trim('/').substringAfterLast('/').substringBefore('?').trim()
        if (code.isBlank()) return@withContext

        val embedReferer = "$origin/e/$code"
        // Try multiple parent embedder origins in case uploader restricted domain embedding
        val candidateParents = listOf(
            "tuktukcima.com" to "https://tuktukcima.com/",
            "www.cima-cloud.com" to "https://www.cima-cloud.com/",
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
        val decObj = CimaCloudCrypto.safeJsonObject(plainJson) ?: return@withContext
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
            if (sUrl.contains(".m3u8", ignoreCase = true)) {
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
            if (tObj.optString("kind").equals("thumbnails", ignoreCase = true)) continue
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
            .header("Referer", referer)
            .build()
        CimaCloudCrypto.httpClient.newCall(req).execute().use { res ->
            CimaCloudCrypto.safeJsonObject(res.body?.string())
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
            if (parentReferer.startsWith("http")) put("X-Embed-Referer", asciiSafeHeaderUrl(parentReferer))
        }

        val settings = runCatching {
            val req = Request.Builder().url("$embedOrigin/api/videos/$code/embed/settings")
            baseHeaders.forEach { (k, v) -> req.header(k, v) }
            CimaCloudCrypto.httpClient.newCall(req.build()).execute().use {
                if (it.isSuccessful) CimaCloudCrypto.safeJsonObject(it.body?.string()) else null
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
                baseHeaders.forEach { (k, v) -> req.header(k, v) }
                CimaCloudCrypto.httpClient.newCall(req.build()).execute().use {
                    if (it.isSuccessful) CimaCloudCrypto.safeJsonObject(it.body?.string()) else null
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
                            baseHeaders.forEach { (k, v) -> req.header(k, v) }
                            CimaCloudCrypto.httpClient.newCall(req.build()).execute().use {
                                if (it.isSuccessful) CimaCloudCrypto.safeJsonObject(it.body?.string()) else null
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
        val req = Request.Builder()
            .url(pbUrl)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
        baseHeaders.forEach { (k, v) -> req.header(k, v) }
        if (!captchaToken.isNullOrBlank()) req.header("X-Captcha-Token", captchaToken)
        val resText = CimaCloudCrypto.httpClient.newCall(req.build()).execute().use { it.body?.string().orEmpty() }
        val root = CimaCloudCrypto.safeJsonObject(resText) ?: return@runCatching null
        root.optJSONObject("playback") ?: if (root.has("payload")) root else null
    }.getOrNull()

    private fun byseObtainFingerprint(embedOrigin: String, baseHeaders: Map<String, String>): JSONObject? {
        val now = System.currentTimeMillis()
        byseAttestCache[embedOrigin]?.let { (exp, fp) -> if (exp > now) return fp }
        return runCatching {
            val chReq = Request.Builder()
                .url("$embedOrigin/api/videos/access/challenge")
                .post("".toRequestBody(null))
            baseHeaders.forEach { (k, v) -> chReq.header(k, v) }
            val ch = CimaCloudCrypto.httpClient.newCall(chReq.build()).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                CimaCloudCrypto.safeJsonObject(res.body?.string())
            } ?: return@runCatching null

            val challengeId = ch.optString("challenge_id").takeIf { it.isNotBlank() } ?: return@runCatching null
            val nonce = ch.optString("nonce").takeIf { it.isNotBlank() } ?: return@runCatching null

            val kp = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val pub = kp.public as ECPublicKey
            val xB64 = b64UrlNoPad(toFixed32(pub.w.affineX))
            val yB64 = b64UrlNoPad(toFixed32(pub.w.affineY))

            val derSig = Signature.getInstance("SHA256withECDSA").run {
                initSign(kp.private)
                update(nonce.toByteArray(Charsets.UTF_8))
                sign()
            }
            val p1363Sig = b64UrlNoPad(derToP1363(derSig))
            val randHash = b64UrlNoPad(
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
            baseHeaders.forEach { (k, v) -> atReq.header(k, v) }
            val atRes = CimaCloudCrypto.httpClient.newCall(atReq.build()).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                CimaCloudCrypto.safeJsonObject(res.body?.string())
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
            val iv = runCatching { b64UrlDec(pb.optString("iv")) }.getOrNull()
            val payload = runCatching { b64UrlDec(pb.optString("payload")) }.getOrNull()
            if (iv != null && payload != null) {
                val keys = ArrayList<ByteArray>()
                if (v in 1..20 && v <= total && (31 - v) in 1..total) {
                    keys.add(b64UrlDec(keyParts.optString(v - 1)) + b64UrlDec(keyParts.optString(30 - v)))
                }
                if (total >= 2) {
                    keys.add(b64UrlDec(keyParts.optString((v - 1).coerceIn(0, total - 1))) + b64UrlDec(keyParts.optString((total - v).coerceIn(0, total - 1))))
                    keys.add(b64UrlDec(keyParts.optString(0)) + b64UrlDec(keyParts.optString(1)))
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
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(b64UrlDec(e1) + b64UrlDec(e2), "AES"), GCMParameterSpec(128, b64UrlDec(iv2)))
                    String(c.doFinal(b64UrlDec(p2)), Charsets.UTF_8)
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
            for (b in 0 until full) if (d[b].toInt() != 0) { ok = false; break }
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

    private fun b64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64UrlDec(s: String): ByteArray {
        var norm = s.trim().replace('-', '+').replace('_', '/')
        while (norm.length % 4 != 0) norm += "="
        return Base64.decode(norm, Base64.DEFAULT)
    }

    // ── 9. VOE (`voe.sx` + Dynamic JS Mirror Redirect + 7-Step Decoder) ──────

    private fun isKnownVoeHost(host: String): Boolean =
        host.contains("derricknationtop.") ||
            host.contains("voe-network.") ||
            host.contains("launchreliantcleaverriver.") ||
            host.contains("richardsignfish.") ||
            host.contains("alejandrocenturyoil.")

    private suspend fun extractVoe(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var currentUrl = url
        var html = fetchText(currentUrl, headers = mapOf("User-Agent" to DESKTOP_UA)) ?: return@withContext

        // Follow up to 3 JS `window.location.href = 'https://...'` mirror redirects
        repeat(3) {
            val jsRedirect = Regex("""window\.location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""<meta[^>]+http-equiv=["']refresh["'][^>]+url=(https?://[^"'\s>]+)""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)
            if (jsRedirect != null && jsRedirect != currentUrl) {
                val nextHtml = fetchText(
                    jsRedirect,
                    headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to currentUrl),
                )
                if (!nextHtml.isNullOrBlank()) {
                    currentUrl = jsRedirect
                    html = nextHtml
                }
            }
        }

        val activeOrigin = originOf(currentUrl)
        val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$activeOrigin/", "Origin" to activeOrigin)

        var emittedAny = false

        // Decode VOE `<script type="application/json">["..."]</script>` 7-step payload
        val appJsonRaw = Regex("""<script[^>]*type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*</script>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

        if (!appJsonRaw.isNullOrBlank()) {
            val decoded = decodeVoePayload(appJsonRaw)
            if (decoded != null) {
                val hlsUrl = decoded.optString("source").trim()
                if (hlsUrl.startsWith("http")) {
                    emitHlsVariantsOrMaster("VOE", hlsUrl, "$activeOrigin/", headers, Qualities.Unknown.value, callback)
                    emittedAny = true
                }
                val mp4Download = decoded.optString("direct_access_url").trim()
                if (mp4Download.startsWith("http")) {
                    callback(
                        newExtractorLink("VOE", "VOE [Download MP4]", mp4Download, ExtractorLinkType.VIDEO) {
                            this.referer = "$activeOrigin/"
                            this.quality = detectQualityInt(mp4Download).takeIf { it != Qualities.Unknown.value }
                                ?: Qualities.P720.value
                            this.headers = headers
                        },
                    )
                    emittedAny = true
                }
                val captions = decoded.optJSONArray("captions")
                if (captions != null) {
                    for (i in 0 until captions.length()) {
                        val cObj = captions.optJSONObject(i) ?: continue
                        val cFile = resolveRelativeUrl(cObj.optString("file"), activeOrigin) ?: continue
                        val cLabel = cObj.optString("label").ifBlank { "Subtitle" }
                        subtitleCallback(newSubtitleFile(cLabel, cFile))
                    }
                }
            }
        }

        // Legacy VOE inline base64 `hls` fallback
        if (!emittedAny) {
            Regex("""['"]hls['"]\s*:\s*['"]([A-Za-z0-9+/=]+)['"]""").find(html)?.groupValues?.get(1)?.let { b64 ->
                val dec = runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                if (dec != null && dec.startsWith("http")) {
                    emitHlsVariantsOrMaster("VOE", dec, "$activeOrigin/", headers, Qualities.Unknown.value, callback)
                    emittedAny = true
                }
            }
        }

        if (!emittedAny) {
            runCatching { loadExtractor(currentUrl, referer, subtitleCallback, callback) }
        }
    }

    private fun decodeVoePayload(input: String): JSONObject? = runCatching {
        // 1. ROT13 over ASCII letters
        val rot = StringBuilder(input.length)
        for (ch in input) {
            val c = when (ch) {
                in 'a'..'z' -> 'a' + (ch - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (ch - 'A' + 13) % 26
                else -> ch
            }
            rot.append(c)
        }
        // 2. Strip the 7 VOE junk markers
        var cleaned = rot.toString()
        for (marker in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            cleaned = cleaned.replace(marker, "")
        }
        // 3. Base64 decode -> ISO-8859-1 string
        val step1Bytes = Base64.decode(cleaned, Base64.DEFAULT)
        val step1Str = String(step1Bytes, Charsets.ISO_8859_1)
        // 4. Shift each char code - 3, then reverse
        val shifted = CharArray(step1Str.length) { i -> (step1Str[i].code - 3).toChar() }
        val reversed = String(shifted).reversed()
        // 5. Final Base64 decode -> UTF-8 JSON
        val jsonStr = String(Base64.decode(reversed, Base64.DEFAULT), Charsets.UTF_8)
        CimaCloudCrypto.safeJsonObject(jsonStr)
    }.getOrNull()

    // ── 10. MixDrop (`mixdrop.top` -> `mxdrop.click` Progressive MP4) ────────

    private suspend fun extractMixDrop(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val embedUrl = url.replace("/f/", "/e/")
        val fetched = fetchTextWithFinalUrl(embedUrl, headers = mapOf("User-Agent" to DESKTOP_UA))
            ?: return@withContext
        val (finalUrl, html) = fetched
        val activeOrigin = originOf(finalUrl)

        val blob = buildString {
            append(html)
            for (u in unpackAllPacker(html)) {
                append('\n').append(u)
            }
        }

        val wurlMatch = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(blob)?.groupValues?.get(1)?.trim()
            ?: Regex("""wurl\s*=\s*["']((?:https?:)?//[^"']+\.mp4[^"']*)["']""").find(blob)?.groupValues?.get(1)?.trim()
            ?: return@withContext

        val fullMp4 = when {
            wurlMatch.startsWith("//") -> "https:$wurlMatch"
            wurlMatch.startsWith("http") -> wurlMatch
            else -> return@withContext
        }
        val headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$activeOrigin/", "Origin" to activeOrigin)
        callback(
            newExtractorLink("MixDrop", "MixDrop [Download MP4]", fullMp4, ExtractorLinkType.VIDEO) {
                this.referer = "$activeOrigin/"
                this.quality = detectQualityInt(html).takeIf { it != Qualities.Unknown.value } ?: Qualities.P720.value
                this.headers = headers
            },
        )
    }

    // ── 11. Ok.ru (`ok.ru`, `odnoklassniki.ru`) — Multi-Quality MP4 + HLS ────

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
            headers = mapOf("User-Agent" to DESKTOP_UA, "Accept-Language" to "en-US,en;q=0.9"),
        ) ?: return@withContext

        val dataOptsRaw = Regex("""data-options=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return@withContext
        val unescaped = Parser.unescapeEntities(dataOptsRaw, false)
        val optsJson = CimaCloudCrypto.safeJsonObject(unescaped) ?: return@withContext
        val flashvars = optsJson.optJSONObject("flashvars") ?: return@withContext
        val metaJson = flashvars.optJSONObject("metadata") ?: run {
            val metaRaw = flashvars.optString("metadata")
            CimaCloudCrypto.safeJsonObject(metaRaw)
                ?: CimaCloudCrypto.safeJsonObject(Parser.unescapeEntities(metaRaw, false))
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
            callback(
                newExtractorLink("OkRu", "OkRu [HLS]", hlsUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                },
            )
        }
    }

    // ── 12. VK (`vk.ru`, `vk.com`, `vkvideo.ru`) — Multi-Quality MP4 + HLS ───

    private suspend fun extractVk(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val html = fetchText(
            url,
            headers = mapOf(
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

        val seenVk = HashSet<String>()
        Regex(""""(?:mp4_|url)(\d{3,4})"\s*:\s*"(https?://[^"]+)"""")
            .findAll(unescaped)
            .forEach { m ->
                val height = m.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
                val mp4Url = m.groupValues[2].replace("\\u0026", "&").trim()
                if (mp4Url.startsWith("http") && seenVk.add(mp4Url)) {
                    callback(
                        newExtractorLink("VK", "VK [MP4]", mp4Url, ExtractorLinkType.VIDEO) {
                            this.referer = "https://vk.ru/"
                            this.quality = height
                            this.headers = headers
                        },
                    )
                }
            }

        Regex(""""hls"\s*:\s*"(https?://[^"]+)"""")
            .findAll(unescaped)
            .forEach { m ->
                val hlsUrl = m.groupValues[1].replace("\\u0026", "&").trim()
                if (hlsUrl.startsWith("http") && seenVk.add(hlsUrl)) {
                    callback(
                        newExtractorLink("VK", "VK [HLS]", hlsUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://vk.ru/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        },
                    )
                }
            }
    }

    // ── 13. StreamHG / HGCloud Redirect Mirrors (`hgcloud.to` -> `vibuxer.com`)

    private suspend fun extractStreamHgMirror(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val localCount = AtomicInteger(0)
        val counting: (ExtractorLink) -> Unit = {
            localCount.incrementAndGet()
            callback(it)
        }
        extractPackerAndHtmlEmbed(url, "StreamHG", subtitleCallback, counting)
        if (localCount.get() > 0) return

        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""embed-([A-Za-z0-9_-]+)\.html""").find(url)?.groupValues?.get(1)
            ?: return

        for (mirrorHost in listOf("https://vibuxer.com", "https://hanerix.com")) {
            extractPackerAndHtmlEmbed("$mirrorHost/e/$code", "StreamHG", subtitleCallback, counting)
            if (localCount.get() > 0) break
        }
    }

    // ── 14. HyperWatching Multi-Server Aggregator (`v2.hyperwatching.com`) ───

    private suspend fun extractHyperWatching(
        url: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val html = fetchText(url, headers = mapOf("User-Agent" to DESKTOP_UA)) ?: return@withContext
        val dataPageAttr = Regex("""data-page=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return@withContext
        val pageJson = CimaCloudCrypto.safeJsonObject(Parser.unescapeEntities(dataPageAttr, false))
            ?: return@withContext
        val videoObj = pageJson.optJSONObject("props")?.optJSONObject("video") ?: return@withContext
        val hashId = videoObj.optString("hashid").takeIf { it.isNotBlank() } ?: return@withContext
        val serversArr = videoObj.optJSONArray("servers") ?: return@withContext

        val origin = originOf(url)
        val subUrls = ArrayList<String>()
        for (i in 0 until serversArr.length()) {
            val srv = serversArr.optJSONObject(i) ?: continue
            val linkId = srv.optLong("id", -1L)
            if (linkId <= 0L) continue
            val apiResp = fetchText(
                "$origin/embed/$hashId/server/$linkId/url",
                headers = mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to url,
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
            ) ?: continue
            val subUrl = CimaCloudCrypto.safeJsonObject(apiResp)
                ?.let { it.optString("url").ifBlank { it.optString("embed_url") } }
                ?.trim()
                ?.takeIf { it.startsWith("http") }
            if (subUrl != null) subUrls.add(subUrl)
        }

        coroutineScope {
            subUrls.distinct().take(6).map { sub ->
                async(Dispatchers.IO) {
                    dispatchSingleUrl(sub, url, depth + 1, subtitleCallback, callback)
                }
            }.awaitAll()
        }
    }

    // ── 15. Unified HTML + JWPlayer + P.A.C.K.E.R. Extractor Engine ──────────

    private fun isPackerOrDirectHtmlHost(host: String): Boolean =
        host.contains("stmruby.") ||
            host.contains("streamruby.") ||
            host.contains("rubystm.") ||
            host.contains("morencius.") ||
            host.contains("luluvdo.") ||
            host.contains("lulustream.") ||
            host.contains("lulust.") ||
            host.contains("1vid.") ||
            host.contains("vidmoly.") ||
            host.contains("vidspeed.") ||
            host.contains("mp4plus.") ||
            host.contains("mp4upload.") ||
            host.contains("uqload.") ||
            host.contains("hanerix.") ||
            host.contains("hgplaycdn.") ||
            host.contains("vibuxer.") ||
            host.contains("premilkyway.") ||
            host.contains("streamhg.") ||
            host.contains("vidhide") ||
            host.contains("streamwish") ||
            host.contains("filemoon") ||
            host.contains("savefiles.") ||
            host.contains("goodstream.")

    private suspend fun extractPackerAndHtmlEmbed(
        url: String,
        providerLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val fetched = fetchTextWithFinalUrl(
            url,
            headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to CimaCloudCrypto.CANONICAL_SITE_URL + "/",
            ),
        ) ?: return@withContext

        val (finalUrl, html) = fetched
        if (html.isBlank()) return@withContext

        val activeOrigin = originOf(finalUrl)
        val effReferer = "$activeOrigin/"
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to effReferer,
            "Origin" to activeOrigin,
        )

        val unpackedBlocks = unpackAllPacker(html)
        val combined = buildString(html.length + unpackedBlocks.sumOf { it.length } + 16) {
            for (u in unpackedBlocks) append(u).append('\n')
            append(html)
        }.replace("\\/", "/")

        val hlsCandidates = LinkedHashSet<String>()
        val mp4Candidates = LinkedHashSet<String>()

        // A. `var links = {"hls4":"...", "hls2":"...", ...}` JSON map (StreamHG / Morencius / VidHide)
        Regex("""var\s+links\s*=\s*(\{[^}]+\})""").findAll(combined).forEach { m ->
            val obj = CimaCloudCrypto.safeJsonObject(m.groupValues[1]) ?: return@forEach
            for (key in listOf("hls4", "hls2", "hls3", "hls")) {
                val raw = obj.optString(key).trim()
                val full = resolveRelativeUrl(raw, activeOrigin) ?: continue
                if (full.contains(".m3u8", ignoreCase = true)) {
                    hlsCandidates.add(full)
                }
            }
        }

        // B. Explicit HLS `.m3u8` URLs in JWPlayer / VideoJS configs
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(combined)
            .forEach { m ->
                val u = m.value.trimEnd(',', ';', ')', ']', '\'', '"')
                if (!u.endsWith(".js", ignoreCase = true)) {
                    hlsCandidates.add(u)
                }
            }

        // C. Explicit progressive `.mp4` URLs in `sources`, `src:`, `file:`
        Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(combined)
            .forEach { mp4Candidates.add(it.groupValues[1].trim()) }

        Regex("""sources\s*:\s*\[\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(combined)
            .forEach { mp4Candidates.add(it.groupValues[1].trim()) }

        for (hlsUrl in hlsCandidates) {
            emitHlsVariantsOrMaster(
                providerLabel = providerLabel,
                m3u8Url = hlsUrl,
                referer = effReferer,
                headers = headers,
                fallbackQuality = detectQualityInt(hlsUrl),
                callback = callback,
            )
        }

        for (mp4Url in mp4Candidates) {
            callback(
                newExtractorLink(providerLabel, "$providerLabel [Download MP4]", mp4Url, ExtractorLinkType.VIDEO) {
                    this.referer = effReferer
                    this.quality = detectQualityInt("$mp4Url $html")
                    this.headers = headers
                },
            )
        }

        // D. Subtitle tracks (`tracks: [{file: "...", label: "...", kind: "captions"}]`)
        Regex("""\{\s*file\s*:\s*["']([^"']+\.(?:vtt|srt|ass)[^"']*)["']\s*,\s*label\s*:\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(combined)
            .forEach { m ->
                val rawSub = m.groupValues[1].trim()
                if (rawSub.contains("empty.", ignoreCase = true)) return@forEach
                val fullSub = resolveRelativeUrl(rawSub, activeOrigin) ?: return@forEach
                val subLabel = m.groupValues[2].trim().ifEmpty { "Subtitle" }
                if (!subLabel.equals("Upload captions", ignoreCase = true)) {
                    subtitleCallback(newSubtitleFile(subLabel, fullSub))
                }
            }
    }

    // ── Shared HLS Expansion, Packer Unpacking & HTTP Utilities ──────────────

    private suspend fun emitHlsVariantsOrMaster(
        providerLabel: String,
        m3u8Url: String,
        referer: String,
        headers: Map<String, String>,
        fallbackQuality: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        val variants = runCatching {
            M3u8Helper.generateM3u8(
                source = providerLabel,
                streamUrl = m3u8Url,
                referer = referer,
                quality = fallbackQuality.takeIf { it != Qualities.Unknown.value },
                headers = headers,
            )
        }.getOrDefault(emptyList())

        if (variants.isNotEmpty()) {
            variants.forEach(callback)
        } else {
            callback(
                newExtractorLink(providerLabel, providerLabel, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = fallbackQuality
                    this.headers = headers
                },
            )
        }
    }

    internal fun unpackAllPacker(html: String): List<String> {
        if (!html.contains("eval(function(p,a,c,k,e,")) return emptyList()
        val results = ArrayList<String>()

        // CloudStream built-in JsUnpacker first
        runCatching {
            JsUnpacker(html).unpack()?.takeIf { it.isNotBlank() }?.let { results.add(it) }
        }

        // Multi-block regex unpacker supporting multiple eval blocks & high radices (up to 95)
        val pattern = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        for (match in pattern.findAll(html)) {
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: continue
            val count = match.groupValues[3].toIntOrNull() ?: continue
            val symtab = match.groupValues[4].split('|')
            if (radix < 2 || count <= 0) continue

            val unpacked = Regex("""\b[0-9a-zA-Z]+\b""").replace(payload) { tokMatch ->
                val idx = fromPackerBase(tokMatch.value, radix)
                if (idx != null && idx in 0 until count && idx < symtab.size && symtab[idx].isNotEmpty()) {
                    symtab[idx]
                } else {
                    tokMatch.value
                }
            }
            if (unpacked.isNotBlank() && unpacked !in results) {
                results.add(unpacked)
            }
        }
        return results
    }

    private fun fromPackerBase(token: String, radix: Int): Int? {
        if (radix <= 36) return token.toIntOrNull(radix)
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var acc = 0
        for (ch in token) {
            val digit = if (ch.code < 128) alphabet.indexOf(ch) else -1
            if (digit < 0 || digit >= radix) return null
            acc = acc * radix + digit
        }
        return acc
    }

    private fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String? =
        fetchTextWithFinalUrl(url, headers)?.second

    private fun fetchTextWithFinalUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<String, String>? {
        return try {
            val reqBuilder = Request.Builder().url(sanitizeHttpUrl(url))
            for ((k, v) in headers) {
                reqBuilder.header(k, if (k.equals("Referer", true) || k.equals("Origin", true)) asciiSafeHeaderUrl(v) else v)
            }
            CimaCloudCrypto.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code >= 500) return null
                val bodyStr = resp.body?.string() ?: return null
                resp.request.url.toString() to bodyStr
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun sanitizeHttpUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        // Encode raw spaces and non-ASCII characters without double-encoding existing %XX sequences
        val sb = StringBuilder(trimmed.length + 16)
        for (ch in trimmed) {
            when {
                ch == ' ' -> sb.append("%20")
                ch.code in 0x21..0x7E -> sb.append(ch)
                else -> {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append(String.format("%%%02X", b.toInt() and 0xFF))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun asciiSafeHeaderUrl(raw: String): String =
        runCatching { URI(sanitizeHttpUrl(raw)).toASCIIString() }.getOrDefault(sanitizeHttpUrl(raw))

    private fun resolveRelativeUrl(raw: String?, origin: String): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> sanitizeHttpUrl(t)
            t.startsWith("//") -> sanitizeHttpUrl("https:$t")
            t.startsWith("/") -> sanitizeHttpUrl("${origin.trimEnd('/')}$t")
            else -> null
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URI(sanitizeHttpUrl(url)).host?.lowercase().orEmpty().removePrefix("www.") }.getOrDefault("")

    private fun originOf(url: String): String =
        runCatching {
            val u = URI(sanitizeHttpUrl(url))
            val scheme = u.scheme ?: "https"
            val host = u.host ?: return@runCatching CimaCloudCrypto.CANONICAL_SITE_URL
            val portPart = if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""
            "$scheme://$host$portPart"
        }.getOrDefault(CimaCloudCrypto.CANONICAL_SITE_URL)

    private fun detectQualityInt(text: String): Int {
        val lower = text.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1440") || lower.contains("2k") -> Qualities.P1440.value
            lower.contains("1080") || lower.contains("fhd") -> Qualities.P1080.value
            lower.contains("988p") || lower.contains("960p") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("660p") || lower.contains("640p") -> Qualities.P720.value
            lower.contains("480") || lower.contains("440p") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            lower.contains("240") || lower.contains("220p") -> Qualities.P240.value
            lower.contains("144") -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private fun prettyProviderName(host: String): String = when {
        host.contains("vidtube") -> "VidTube"
        host.contains("stmruby") || host.contains("streamruby") || host.contains("rubystm") -> "StreamRuby"
        host.contains("morencius") -> "Morencius"
        host.contains("luluvdo") || host.contains("lulustream") || host.contains("lulust") -> "LuluStream"
        host.contains("1vid") -> "1Vid"
        host.contains("vidmoly") -> "Vidmoly"
        host.contains("vidspeed") -> "VidSpeed"
        host.contains("mp4plus") -> "Mp4Plus"
        host.contains("mp4upload") -> "Mp4Upload"
        host.contains("uqload") -> "Uqload"
        host.contains("hanerix") || host.contains("hgplaycdn") || host.contains("vibuxer") || host.contains("hgcloud") -> "StreamHG"
        host.contains("vidara") -> "Vidara"
        host.contains("byse") -> "Byse"
        host.contains("voe") || host.contains("derricknationtop") -> "VOE"
        host.contains("mixdrop") || host.contains("miixdrop") || host.contains("mxdrop") -> "MixDrop"
        host.contains("ok.ru") || host.contains("odnoklassniki") -> "OkRu"
        host.contains("vk.") || host.contains("vkvideo") -> "VK"
        host.contains("savefiles") -> "SaveFiles"
        host.contains("goodstream") -> "GoodStream"
        host.contains("stardima") || host.contains("worldnow") -> "Stardima Direct"
        host.contains("pixeldrain") -> "PixelDrain"
        host.contains("mediafire") -> "MediaFire"
        else -> host.substringBefore('.').replaceFirstChar { it.uppercase() }.ifEmpty { "Server" }
    }
}

// ── Registered Standalone CloudStream ExtractorApi Wrappers ──────────────────

class GooglePhotosExtractor : ExtractorApi() {
    override val name = "GooglePhotos"
    override val mainUrl = "https://photos.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CimaCloudExtractors.extractGooglePhotos(url, callback)
    }
}

class VidaraCimaExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidaraa.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CimaCloudExtractors.extractVidara(url, subtitleCallback, callback)
    }
}

class DeveloperProWorkerExtractor : ExtractorApi() {
    override val name = "CloudWorker"
    override val mainUrl = "https://test-stream.developer-pro.workers.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        CimaCloudExtractors.extractDeveloperProWorker(url, callback)
    }
}
