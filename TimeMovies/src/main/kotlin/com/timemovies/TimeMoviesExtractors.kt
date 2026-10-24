package com.timemovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.timemovies.TimeMoviesModels.LinkPayload
import com.timemovies.TimeMoviesModels.MediaGrantPayload
import com.timemovies.TimeMoviesModels.MediaSourceItem
import com.timemovies.TimeMoviesModels.MediaV2Payload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.util.Collections
import java.util.Locale

/**
 * Complete multi-quality playback, subtitle, and download extractor suite for TimeMovies v4.7
 * and Doom Video Player v1.6 (`timesnizz://` / `timesdizz://`).
 */
object TimeMoviesExtractors {

    data class HlsSubtitleTrack(
        val label: String,
        val languageCode: String?,
        val url: String,
        val isDefault: Boolean,
    )

    private val ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")

    /**
     * Parses `#EXT-X-MEDIA:TYPE=SUBTITLES,...` entries from an HLS master `.m3u8` playlist,
     * resolving relative `.vtt`/`.m3u8` subtitle URIs against `masterUrl`.
     */
    fun parseHlsSubtitlesFromMaster(masterContent: String, masterUrl: String): List<HlsSubtitleTrack> {
        if (masterContent.isBlank()) return emptyList()
        val tracks = ArrayList<HlsSubtitleTrack>()
        val seenUrls = HashSet<String>()

        for (rawLine in masterContent.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) continue
            val attrs = HashMap<String, String>()
            for (m in ATTRIBUTE_REGEX.findAll(line.substringAfter(":"))) {
                val key = m.groupValues[1].uppercase(Locale.US)
                val quoted = m.groups[3]?.value
                val value = quoted ?: m.groupValues[2].trim()
                attrs[key] = value
            }
            if (!attrs["TYPE"].equals("SUBTITLES", ignoreCase = true)) continue
            val uriAttr = attrs["URI"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val resolvedUrl = resolveRelativeUri(masterUrl, uriAttr)
            if (!seenUrls.add(resolvedUrl)) continue

            val langCode = attrs["LANGUAGE"]?.trim()?.lowercase(Locale.US)
            val rawName = attrs["NAME"]?.trim()?.takeIf { it.isNotEmpty() }
            val label = mapSubtitleDisplayName(rawName, langCode)
            val isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true)

            tracks.add(
                HlsSubtitleTrack(
                    label = label,
                    languageCode = langCode,
                    url = resolvedUrl,
                    isDefault = isDefault,
                )
            )
        }
        return tracks
    }

    /**
     * If an HLS subtitle track points to a subtitle variant `.m3u8` containing a single `.vtt`/`.webvtt` segment,
     * unwraps that direct `.vtt` URL when possible so external players can load it directly too.
     */
    private fun unwrapVttPlaylistIfSingleFile(subPlaylistUrl: String): String {
        val cleanPath = subPlaylistUrl.substringBefore('?').lowercase(Locale.US)
        if (cleanPath.endsWith(".vtt") || cleanPath.endsWith(".webvtt") || cleanPath.endsWith(".srt")) {
            return subPlaylistUrl
        }
        if (!cleanPath.endsWith(".m3u8")) return subPlaylistUrl
        return runCatching {
            val req = Request.Builder()
                .url(subPlaylistUrl)
                .get()
                .header("User-Agent", "okhttp/4.12.0")
                .build()
            TimeMoviesApi.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return subPlaylistUrl
                val body = resp.body?.string().orEmpty()
                val segLines = body.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
                if (segLines.size == 1) {
                    val single = segLines.first()
                    if (single.substringBefore('?').let {
                            it.endsWith(".vtt", true) || it.endsWith(".webvtt", true) || it.endsWith(".srt", true)
                        }
                    ) {
                        return resolveRelativeUri(subPlaylistUrl, single)
                    }
                }
                subPlaylistUrl
            }
        }.getOrDefault(subPlaylistUrl)
    }

    fun resolveRelativeUri(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true)) {
            return ref
        }
        return runCatching { URI(baseUrl).resolve(ref).toString() }.getOrDefault(ref)
    }

    private fun mapSubtitleDisplayName(rawName: String?, langCode: String?): String {
        val code = langCode?.substringBefore('-').orEmpty()
        return when {
            code == "ar" || rawName?.contains("arab", true) == true || rawName?.contains("عرب") == true -> "العربية (Arabic)"
            code == "en" || rawName?.contains("eng", true) == true -> "English"
            code == "fr" || rawName?.contains("fren", true) == true -> "Français"
            code == "es" || rawName?.contains("span", true) == true -> "Español"
            code == "tr" || rawName?.contains("turk", true) == true -> "Türkçe"
            code == "de" || rawName?.contains("germ", true) == true -> "Deutsch"
            !rawName.isNullOrBlank() -> rawName
            !langCode.isNullOrBlank() -> langCode.uppercase(Locale.US)
            else -> "Subtitle"
        }
    }

    suspend fun extractAllLinks(
        payload: LinkPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!TimeMoviesPrefs.isSignedIn) {
            return@withContext false
        }

        val emittedUrls = Collections.synchronizedSet(HashSet<String>())
        val emittedSubs = Collections.synchronizedSet(HashSet<String>())
        var foundAny = false

        // 1. Primary Watch Grant (handles BOTH Internal MediaV2 & External DoomVideoPlayer)
        var watchGrant = TimeMoviesApi.requestMediaGrant(
            action = "watch",
            fileCode = payload.fileCode,
            workId = payload.workId,
            episodeId = payload.episodeId,
            seasonId = payload.seasonId,
            executorTarget = null,
            playerInstalled = true,
        )

        // If uncharged & refused because server explicitly requires `executor_target = "external"`, retry with `"external"`
        if ((watchGrant == null || !watchGrant.ok) && watchGrant?.charged != true) {
            val extWatchGrant = TimeMoviesApi.requestMediaGrant(
                action = "watch",
                fileCode = payload.fileCode,
                workId = payload.workId,
                episodeId = payload.episodeId,
                seasonId = payload.seasonId,
                executorTarget = "external",
                playerInstalled = true,
            )
            if (extWatchGrant != null && extWatchGrant.ok) {
                watchGrant = extWatchGrant
            }
        }

        if (watchGrant != null && watchGrant.ok) {
            val resolvedWatch = emitFromGrant(
                action = "watch",
                grantPayload = watchGrant,
                sourcePrefix = "TimeMovies",
                emittedUrls = emittedUrls,
                emittedSubs = emittedSubs,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
            if (resolvedWatch) foundAny = true
        }

        // 2. Direct Multi-Quality MP4/MKV Download Grant (`360p` / `480p` / `720p` / `1080p` / `2160p`)
        if (TimeMoviesPrefs.fetchDownloadGrant) {
            var downloadGrant = TimeMoviesApi.requestMediaGrant(
                action = "download",
                fileCode = payload.fileCode,
                workId = payload.workId,
                episodeId = payload.episodeId,
                seasonId = payload.seasonId,
                executorTarget = null,
                playerInstalled = true,
            )
            if ((downloadGrant == null || !downloadGrant.ok) && downloadGrant?.charged != true) {
                val extDlGrant = TimeMoviesApi.requestMediaGrant(
                    action = "download",
                    fileCode = payload.fileCode,
                    workId = payload.workId,
                    episodeId = payload.episodeId,
                    seasonId = payload.seasonId,
                    executorTarget = "external",
                    playerInstalled = true,
                )
                if (extDlGrant != null && extDlGrant.ok) {
                    downloadGrant = extDlGrant
                }
            }
            if (downloadGrant != null && downloadGrant.ok) {
                val resolvedDl = emitFromGrant(
                    action = "download",
                    grantPayload = downloadGrant,
                    sourcePrefix = "TimeMovies Direct",
                    emittedUrls = emittedUrls,
                    emittedSubs = emittedSubs,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                if (resolvedDl) foundAny = true
            }
        }

        foundAny
    }

    private suspend fun emitFromGrant(
        action: String,
        grantPayload: MediaGrantPayload,
        sourcePrefix: String,
        emittedUrls: MutableSet<String>,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        var emittedCount = 0

        // Path A: External DoomVideoPlayer ticket or descriptor (`timesnizz://` / `timesdizz://`)
        val ext = grantPayload.external
        if (ext != null) {
            val directTicket = ext.ticket?.takeIf { it.isNotBlank() }
                ?: ext.descriptor?.let { TimeMoviesCrypto.parseDoomDeepLink(it)?.srcOrUrl }

            if (!directTicket.isNullOrBlank()) {
                val resolvedUrl = if (action == "download") {
                    TimeMoviesApi.resolveDoomDownload(directTicket)
                } else {
                    TimeMoviesApi.resolveDoomPlay(directTicket)
                }
                if (!resolvedUrl.isNullOrBlank()) {
                    if (emitResolvedStreamUrl(
                            streamUrl = resolvedUrl,
                            label = "$sourcePrefix (DoomPlayer)",
                            qualityHint = null,
                            emittedUrls = emittedUrls,
                            emittedSubs = emittedSubs,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                        )
                    ) {
                        emittedCount++
                    }
                }
            }
        }

        // Path B: Embedded `MediaV2Payload` or Origin `<origin>/v2/media/{manifest|download}` via `X-Media-Grant`
        val mediaV2: MediaV2Payload? = grantPayload.media
            ?: grantPayload.grant?.takeIf { it.isNotBlank() }?.let { grantToken ->
                TimeMoviesApi.fetchOriginMedia(action, grantToken)
            }

        if (mediaV2 != null) {
            val hlsSrc = mediaV2.src?.takeIf { it.isNotBlank() }
            if (!hlsSrc.isNullOrBlank()) {
                val realHlsUrl = if (hlsSrc.startsWith("http://", true) || hlsSrc.startsWith("https://", true)) {
                    hlsSrc
                } else {
                    TimeMoviesApi.resolveDoomPlay(hlsSrc)
                }
                if (!realHlsUrl.isNullOrBlank()) {
                    if (emitResolvedStreamUrl(
                            streamUrl = realHlsUrl,
                            label = "$sourcePrefix HLS",
                            qualityHint = null,
                            emittedUrls = emittedUrls,
                            emittedSubs = emittedSubs,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                        )
                    ) {
                        emittedCount++
                    }
                }
            }

            if (mediaV2.sources.isNotEmpty()) {
                val isExternalTicketMode = (grantPayload.target == 2) || (grantPayload.external != null)
                val resolvedSources = mediaV2.sources.map { item ->
                    async(Dispatchers.IO) {
                        resolveMediaSourceItem(item, isExternalTicketMode)
                    }
                }.awaitAll().filterNotNull()

                for ((item, finalUrl) in resolvedSources) {
                    if (!emittedUrls.add(finalUrl)) continue
                    val qVal = item.quality ?: parseQualityDigits(item.name) ?: Qualities.Unknown.value
                    val sizeBadge = item.size?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                    val qBadge = if (qVal > 0) "${qVal}p" else (item.name ?: "MP4")
                    val isM3u8 = finalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                    callback(
                        newExtractorLink(
                            source = sourcePrefix,
                            name = "$sourcePrefix [$qBadge$sizeBadge]",
                            url = finalUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = qVal
                            this.headers = mapOf("User-Agent" to "okhttp/4.12.0")
                        }
                    )
                    emittedCount++
                }
            }
        }

        emittedCount > 0
    }

    private fun resolveMediaSourceItem(
        item: MediaSourceItem,
        isExternalTicketMode: Boolean,
    ): Pair<MediaSourceItem, String>? {
        val raw = item.link.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return item to raw
        }
        if (raw.startsWith("timesdizz://", ignoreCase = true) || raw.startsWith("timesnizz://", ignoreCase = true)) {
            val parsed = TimeMoviesCrypto.parseDoomDeepLink(raw) ?: return null
            val resolved = if (parsed.isEncryptedTicket) {
                TimeMoviesApi.resolveDoomDownload(parsed.srcOrUrl) ?: TimeMoviesApi.resolveDoomPlay(parsed.srcOrUrl)
            } else {
                parsed.srcOrUrl
            } ?: return null
            return item to resolved
        }
        val resolved = if (isExternalTicketMode || !raw.startsWith("http", true)) {
            TimeMoviesApi.resolveDoomDownload(raw)
        } else {
            raw
        } ?: return null
        return item to resolved
    }

    suspend fun emitResolvedStreamUrl(
        streamUrl: String,
        label: String,
        qualityHint: Int?,
        emittedUrls: MutableSet<String>,
        emittedSubs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val cleanPath = streamUrl.substringBefore('?').lowercase(Locale.US)
        val isHls = cleanPath.endsWith(".m3u8") || streamUrl.contains("m3u8", ignoreCase = true)
        val headers = mapOf("User-Agent" to "okhttp/4.12.0")

        if (!isHls) {
            if (!emittedUrls.add(streamUrl)) return false
            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = qualityHint ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        }

        // Fetch master playlist once to extract embedded `#EXT-X-MEDIA:TYPE=SUBTITLES` tracks AND multi-quality variants
        val masterText = runCatching {
            val req = Request.Builder().url(streamUrl).get().header("User-Agent", "okhttp/4.12.0").build()
            TimeMoviesApi.httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")

        if (masterText.isNotBlank()) {
            val subTracks = parseHlsSubtitlesFromMaster(masterText, streamUrl)
            for (sub in subTracks) {
                val finalSubUrl = unwrapVttPlaylistIfSingleFile(sub.url)
                if (emittedSubs.add(finalSubUrl)) {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = sub.label,
                            url = finalSubUrl,
                        )
                    )
                }
            }
        }

        val generated = runCatching {
            M3u8Helper.generateM3u8(
                source = label,
                streamUrl = streamUrl,
                referer = "",
                headers = headers,
            )
        }.getOrDefault(emptyList())

        var count = 0
        for (link in generated) {
            if (emittedUrls.add(link.url)) {
                callback(link)
                count++
            }
        }

        // Always ensure master HLS stream itself is available (preserves embedded HLS audio/subtitles in ExoPlayer)
        if (emittedUrls.add(streamUrl)) {
            callback(
                newExtractorLink(
                    source = label,
                    name = "$label [Auto]",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.quality = qualityHint ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
            count++
        }

        return count > 0
    }

    private fun parseQualityDigits(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(2160|1440|1080|720|480|360|240)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
}

/**
 * Standalone CloudStream `ExtractorApi` for `timesnizz://doomplayer.com/...` playback links.
 */
class DoomPlayerPlayExtractor : ExtractorApi() {
    override val name = "Doom Video Player"
    override val mainUrl = "timesnizz://doomplayer.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val parsed = TimeMoviesCrypto.parseDoomDeepLink(url) ?: return
        val resolved = if (parsed.isEncryptedTicket) {
            withContext(Dispatchers.IO) { TimeMoviesApi.resolveDoomPlay(parsed.srcOrUrl) }
        } else {
            parsed.srcOrUrl
        } ?: return

        TimeMoviesExtractors.emitResolvedStreamUrl(
            streamUrl = resolved,
            label = name,
            qualityHint = null,
            emittedUrls = Collections.synchronizedSet(HashSet()),
            emittedSubs = Collections.synchronizedSet(HashSet()),
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}

/**
 * Standalone CloudStream `ExtractorApi` for `timesdizz://doomplayer.com/...` direct download links.
 */
class DoomPlayerDownloadExtractor : ExtractorApi() {
    override val name = "Doom Video Download"
    override val mainUrl = "timesdizz://doomplayer.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val parsed = TimeMoviesCrypto.parseDoomDeepLink(url) ?: return
        val resolved = if (parsed.isEncryptedTicket) {
            withContext(Dispatchers.IO) { TimeMoviesApi.resolveDoomDownload(parsed.srcOrUrl) }
        } else {
            parsed.srcOrUrl
        } ?: return

        TimeMoviesExtractors.emitResolvedStreamUrl(
            streamUrl = resolved,
            label = name,
            qualityHint = null,
            emittedUrls = Collections.synchronizedSet(HashSet()),
            emittedSubs = Collections.synchronizedSet(HashSet()),
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}
