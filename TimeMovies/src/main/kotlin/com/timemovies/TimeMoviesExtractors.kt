package com.timemovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.timemovies.TimeMoviesModels.ExternalExecutionInfo
import com.timemovies.TimeMoviesModels.LinkPayload
import com.timemovies.TimeMoviesModels.MediaGrantPayload
import com.timemovies.TimeMoviesModels.MediaSourceItem
import com.timemovies.TimeMoviesModels.MediaV2Payload
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Multi-path stream, download, hoster, and subtitle extraction engine for TimeMovies v4.7 & DoomVideoPlayer v1.6.
 *
 * Resolution Strategy:
 * 1. Ensures an active `USP` profile is selected (`x-p`, `m-a`) before requesting grants.
 * 2. Probes `POST /api/v4.7/app/media/grant` in Internal Origin mode (`executor_target = null`, `player_installed = false`)
 *    with idempotent `ExecutionFailed` retry (`operation_id` + `client_attempt_key`), then fetches
 *    `GET /v2/media/manifest` (`watch`) and `GET /v2/media/download` (`download`) with `X-Media-Grant`.
 * 3. Automatically falls back to External `DoomVideoPlayer` mode (`executor_target = "external"`, `player_installed = true`)
 *    whenever internal origin is not configured or refused (`player_not_installed`), resolving encrypted
 *    `timesnizz://` & `timesdizz://` tickets via `POST /api/player/v1.6/app/resolve-play` and `resolve-download`.
 * 4. Captures ephemeral DoomPlayer proxy Master M3U8s (`/api/player/v1.6/app/m?t=...&s=...`, ~210s TTL)
 *    once during `loadLinks`, absolutizes all 4h–24h CDN renditions (`sv*.egy.rocks`), and serves both
 *    `[Auto HLS]` and per-quality single-variant Master M3U8s (`1080p`, `720p`, `480p`, `360p`) from
 *    [TimeMoviesLocalHlsServer] (`http://127.0.0.1:<port>/hls/...`) so EVERY quality plays with full
 *    demuxed `#EXT-X-MEDIA:TYPE=AUDIO` + native `#EXT-X-MEDIA:TYPE=SUBTITLES` and zero `HTTP 404` (`2004`) errors.
 */
internal object TimeMoviesLocalHlsServer {
    private const val MAX_ENTRIES = 128

    private val store = object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    @Synchronized
    private fun ensureStarted(): Int {
        val current = serverSocket
        if (boundPort > 0 && current != null && !current.isClosed) {
            return boundPort
        }
        return runCatching {
            val sock = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            serverSocket = sock
            boundPort = sock.localPort
            Thread(
                {
                    while (!sock.isClosed) {
                        val client = try {
                            sock.accept()
                        } catch (_: Throwable) {
                            if (sock.isClosed) break else continue
                        }
                        Thread({ handleClient(client) }, "TimeMoviesHlsConn").apply {
                            isDaemon = true
                            start()
                        }
                    }
                },
                "TimeMoviesHlsServer",
            ).apply {
                isDaemon = true
                start()
            }
            boundPort
        }.getOrDefault(0)
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream(128)
        var sawByte = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (sawByte) buf.toString("UTF-8") else null
            sawByte = true
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = 8_000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readHttpLine(input) ?: return
                while (true) {
                    val hdr = readHttpLine(input) ?: break
                    if (hdr.isEmpty()) break
                }

                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase(Locale.US).orEmpty()
                val rawTarget = parts.getOrNull(1).orEmpty().substringBefore('?').substringBefore('#')
                val key = rawTarget.substringAfterLast('/').removeSuffix(".m3u8")
                val out = sock.getOutputStream()

                if (method == "OPTIONS") {
                    val cors = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(cors.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                val payload = synchronized(store) { store[key] }
                if (payload == null) {
                    val notFound = "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(notFound.toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") {
                    out.write(payload)
                }
                out.flush()
            }
        }
    }

    fun registerM3u8(m3u8Content: String, slug: String = "master"): String? {
        val normalized = m3u8Content.removePrefix("\uFEFF").trim()
        if (!normalized.startsWith("#EXTM3U", ignoreCase = true)) return null
        val bytes = "$normalized\n".toByteArray(Charsets.UTF_8)
        val safeSlug = slug.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "hls" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$safeSlug\n$normalized".toByteArray(Charsets.UTF_8))
        val hash = buildString(20) {
            for (i in 0 until 10) {
                append("%02x".format(digest[i]))
            }
        }
        val key = "$safeSlug-$hash"
        synchronized(store) {
            store[key] = bytes
        }
        val port = ensureStarted()
        if (port <= 0) return null
        return "http://127.0.0.1:$port/hls/$key.m3u8"
    }
}

object TimeMoviesExtractors {

    private val KNOWN_EXTERNAL_HOSTER_KEYWORDS = listOf(
        "dood", "d000d", "ds2play", "dsvplay",
        "mixdrop", "mxdrop",
        "streamtape", "strtape",
        "vidmoly",
        "voe.sx", "voeunblock",
        "streamwish", "wishembed", "sfastwish", "flaswish", "obeywish",
        "filemoon", "moonmov",
        "hglink", "cavanhabg", "dumbalag", "uasopt", "dhcplay", "earnvids", "streamhg",
        "vidara", "vidroba",
        "bysezejataos", "bysevepoin", "bysedikamoum", "bysekoze",
        "lulustream", "luluvdo",
        "mp4upload",
        "sendvid",
        "ok.ru", "odnoklassniki",
        "mediafire.com",
        "pixeldrain.com",
        "gofile.io",
        "uqload",
        "vidoza",
        "upstream.to",
        "vtube",
    )

    private val inspectClient by lazy {
        TimeMoviesApi.plainClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    data class ParsedSubtitleTrack(
        val label: String,
        val langCode: String?,
        val url: String,
        val isDefault: Boolean,
    )

    data class ParsedHlsVariant(
        val url: String,
        val quality: Int,
        val label: String,
    )

    internal data class ParsedMediaRendition(
        val rawLine: String,
        val type: String,
        val groupId: String?,
        val name: String?,
        val language: String?,
        val uri: String?,
        val isDefault: Boolean,
    )

    internal data class HlsVariantStream(
        val streamInfLine: String,
        val url: String,
        val quality: Int,
        val bandwidth: Long,
        val baseLabel: String,
        val displayLabel: String,
        val audioGroupId: String?,
        val subtitleGroupId: String?,
    )

    internal data class ParsedMasterManifest(
        val isMaster: Boolean,
        val absolutizedContent: String,
        val globalHeaderLines: List<String>,
        val mediaLines: List<ParsedMediaRendition>,
        val variants: List<HlsVariantStream>,
    )

    suspend fun extractAllLinks(
        payload: LinkPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!TimeMoviesApi.hasUsableAuth()) return false
        if (TimeMoviesPrefs.isSignedIn && TimeMoviesPrefs.profileId.isNullOrBlank()) {
            TimeMoviesApi.syncAppDataAndSelectMainProfile()
        }

        TimeMoviesApi.lastMediaRefusalReason = null
        val emittedUrls = Collections.synchronizedSet( LinkedHashSet<String>() )
        val emittedSubs = Collections.synchronizedSet( LinkedHashSet<String>() )
        var foundAny = false

        val dedupCallback: (ExtractorLink) -> Unit = { link ->
            val key = "${link.type}:${link.url}"
            if (emittedUrls.add(key)) {
                foundAny = true
                callback(link)
            }
        }
        val dedupSubCallback: (SubtitleFile) -> Unit = { sub ->
            if (emittedSubs.add(sub.url)) {
                subtitleCallback(sub)
            }
        }

        // 1. Watch stream links (Internal Origin -> External DoomVideoPlayer fallback)
        val watchOk = extractForAction(
            action = "watch",
            payload = payload,
            subtitleCallback = dedupSubCallback,
            callback = dedupCallback,
        )
        if (watchOk) foundAny = true

        // 2. Multi-quality progressive MP4/MKV download links (Internal Origin -> External DoomVideoPlayer fallback)
        val downloadOk = extractForAction(
            action = "download",
            payload = payload,
            subtitleCallback = dedupSubCallback,
            callback = dedupCallback,
        )
        if (downloadOk) foundAny = true

        return foundAny
    }

    private suspend fun extractForAction(
        action: String,
        payload: LinkPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        // Attempt 1: Auto-target with `player_installed = true` (`executor_target = null` per `at5.java`),
        // allowing `api.apks.lol` to return inline `media`, `external` DoomPlayer ticket, or `grant` on 1st call.
        val primaryGrant = requestGrantWithExecutionRetry(
            action = action,
            payload = payload,
            executorTarget = null,
            playerInstalled = true,
        )
        if (primaryGrant != null && primaryGrant.ok) {
            processGrantPayload(
                action = action,
                grantPayload = primaryGrant,
                subtitleCallback = subtitleCallback,
                callback = trackingCallback,
            )
        }

        if (emitted) return true

        // Attempt 2: Explicit External `DoomVideoPlayer` Mode (`executor_target = "external"`, `player_installed = true`)
        val reuseOpId = primaryGrant?.operationId?.takeIf { primaryGrant.charged && it.isNotBlank() }
        val externalGrant = requestGrantWithExecutionRetry(
            action = action,
            payload = payload,
            executorTarget = "external",
            playerInstalled = true,
            initialOperationId = reuseOpId,
        )
        if (externalGrant != null && externalGrant.ok) {
            processGrantPayload(
                action = action,
                grantPayload = externalGrant,
                subtitleCallback = subtitleCallback,
                callback = trackingCallback,
            )
        }

        if (emitted) return true

        // Attempt 3: Explicit Internal Origin Mode (`executor_target = null`, `player_installed = false`)
        if (primaryGrant == null || !primaryGrant.charged) {
            val internalOnlyGrant = requestGrantWithExecutionRetry(
                action = action,
                payload = payload,
                executorTarget = null,
                playerInstalled = false,
            )
            if (internalOnlyGrant != null && internalOnlyGrant.ok) {
                processGrantPayload(
                    action = action,
                    grantPayload = internalOnlyGrant,
                    subtitleCallback = subtitleCallback,
                    callback = trackingCallback,
                )
            }
        }

        return emitted
    }

    /**
     * Calls `requestMediaGrant` and, if `ExecutionFailed` is returned (`!ok` with non-blank `operationId`),
     * retries once with the same `operation_id` + `client_attempt_key` (`at5.java:185–215`).
     */
    private fun requestGrantWithExecutionRetry(
        action: String,
        payload: LinkPayload,
        executorTarget: String?,
        playerInstalled: Boolean,
        initialOperationId: String? = null,
    ): MediaGrantPayload? {
        val attemptKey = UUID.randomUUID().toString()
        val first = TimeMoviesApi.requestMediaGrant(
            action = action,
            workId = payload.workId,
            fileCode = payload.fileCode,
            episodeId = payload.episodeId,
            seasonId = payload.seasonId,
            executorTarget = executorTarget,
            playerInstalled = playerInstalled,
            operationId = initialOperationId,
            clientAttemptKey = attemptKey,
        ) ?: return null

        if (!first.ok && !first.operationId.isNullOrBlank()) {
            val retried = TimeMoviesApi.requestMediaGrant(
                action = action,
                workId = payload.workId,
                fileCode = payload.fileCode,
                episodeId = payload.episodeId,
                seasonId = payload.seasonId,
                executorTarget = executorTarget,
                playerInstalled = playerInstalled,
                operationId = first.operationId,
                clientAttemptKey = attemptKey,
            )
            if (retried != null) return retried
        }
        return first
    }

    private suspend fun processGrantPayload(
        action: String,
        grantPayload: MediaGrantPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false

        // 1. Inline `media` inside `MediaGrantResponse` (`Lex;->F` priority 1)
        if (grantPayload.media != null) {
            if (processMediaV2Payload(action, grantPayload.media, subtitleCallback, callback)) {
                found = true
            }
        }

        // 2. External `DoomVideoPlayer` execution descriptor / ticket (`timesnizz://` / `timesdizz://`, priority 2)
        if (grantPayload.external != null) {
            if (processExternalExecution(action, grantPayload.external, subtitleCallback, callback)) {
                found = true
            }
        }

        // 3. Origin grant token fallback (`X-Media-Grant` -> `GET /v2/media/{manifest|download}`) when neither inline nor external emitted
        if (!found && !grantPayload.grant.isNullOrBlank()) {
            val originMedia = TimeMoviesApi.fetchOriginMedia(
                action = action,
                grantToken = grantPayload.grant,
                codes = grantPayload.codes,
            )
            if (originMedia != null) {
                if (processMediaV2Payload(action, originMedia, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }

    private suspend fun processMediaV2Payload(
        action: String,
        media: MediaV2Payload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val isWatch = action == "watch" || action == "cast"

        // Primary `src` stream (HLS manifest or ticket)
        val src = media.src?.trim()
        if (!src.isNullOrEmpty()) {
            if (handleCandidateLink(
                    rawLink = src,
                    sourceLabel = if (isWatch) "TimeMovies Stream" else "TimeMovies Direct",
                    qualityHint = null,
                    isWatch = isWatch,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            ) {
                found = true
            }
        }

        // Multi-quality `sources` list
        if (processSourceItems(media.sources, isWatch, subtitleCallback, callback)) {
            found = true
        }

        // Optional `episodes` list (`MediaV2Episode`)
        for (ep in media.episodes) {
            if (processSourceItems(ep.sources, isWatch, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }

    private suspend fun processSourceItems(
        sources: List<MediaSourceItem>,
        isWatch: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        for (sourceItem in sources) {
            val rawLink = sourceItem.link.trim()
            if (rawLink.isEmpty()) continue

            val qualityInt = sourceItem.quality ?: parseQualityNumber(sourceItem.name)
            val qualityTag = buildList {
                sourceItem.name?.takeIf { it.isNotBlank() }?.let { add(it) }
                    ?: qualityInt?.let { add("${it}p") }
                sourceItem.size?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" • ")

            val basePrefix = if (isWatch) "TimeMovies Stream" else "TimeMovies Download"
            val label = if (qualityTag.isNotEmpty()) "$basePrefix [$qualityTag]" else basePrefix

            if (handleCandidateLink(
                    rawLink = rawLink,
                    sourceLabel = label,
                    qualityHint = qualityInt,
                    isWatch = isWatch,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            ) {
                found = true
            }
        }
        return found
    }

    private suspend fun processExternalExecution(
        action: String,
        ext: ExternalExecutionInfo,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val isWatch = action != "download"
        val descriptor = ext.descriptor?.trim()
        if (!descriptor.isNullOrEmpty()) {
            val deepLink = TimeMoviesCrypto.parseDoomDeepLink(descriptor)
            if (deepLink != null) {
                val resolvedUrl = if (deepLink.isEncryptedTicket) {
                    if (!isWatch || deepLink.scheme == "timesdizz") {
                        TimeMoviesApi.resolveDoomDownloadTicket(deepLink.srcOrUrl)
                    } else {
                        TimeMoviesApi.resolveDoomPlayTicket(deepLink.srcOrUrl)
                    }
                } else {
                    deepLink.srcOrUrl
                }
                if (!resolvedUrl.isNullOrBlank()) {
                    return emitResolvedHttpUrl(
                        url = resolvedUrl,
                        sourceLabel = if (!isWatch || deepLink.scheme == "timesdizz") {
                            "DoomPlayer Download"
                        } else {
                            "DoomPlayer Stream"
                        },
                        qualityHint = null,
                        isWatch = isWatch && deepLink.scheme != "timesdizz",
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                }
            } else if (descriptor.startsWith("http://", true) || descriptor.startsWith("https://", true)) {
                return emitResolvedHttpUrl(
                    url = descriptor,
                    sourceLabel = if (isWatch) "DoomPlayer Stream" else "DoomPlayer Download",
                    qualityHint = null,
                    isWatch = isWatch,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            }
        }

        val rawTicket = ext.ticket?.trim()
        if (!rawTicket.isNullOrEmpty()) {
            val resolvedUrl = if (rawTicket.startsWith("http://", true) || rawTicket.startsWith("https://", true)) {
                rawTicket
            } else if (!isWatch) {
                TimeMoviesApi.resolveDoomDownloadTicket(rawTicket)
            } else {
                TimeMoviesApi.resolveDoomPlayTicket(rawTicket)
            }
            if (!resolvedUrl.isNullOrBlank()) {
                return emitResolvedHttpUrl(
                    url = resolvedUrl,
                    sourceLabel = if (isWatch) "DoomPlayer Stream" else "DoomPlayer Download",
                    qualityHint = null,
                    isWatch = isWatch,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            }
        }

        return false
    }

    private suspend fun handleCandidateLink(
        rawLink: String,
        sourceLabel: String,
        qualityHint: Int?,
        isWatch: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val trimmed = rawLink.trim()
        if (trimmed.isEmpty()) return false

        // 1. `timesnizz://` or `timesdizz://` DoomVideoPlayer deep-link
        if (trimmed.startsWith("timesnizz://", true) || trimmed.startsWith("timesdizz://", true)) {
            val parsed = TimeMoviesCrypto.parseDoomDeepLink(trimmed) ?: return false
            val resolved = if (parsed.isEncryptedTicket) {
                if (parsed.scheme == "timesdizz" || !isWatch) {
                    TimeMoviesApi.resolveDoomDownloadTicket(parsed.srcOrUrl)
                } else {
                    TimeMoviesApi.resolveDoomPlayTicket(parsed.srcOrUrl)
                }
            } else {
                parsed.srcOrUrl
            } ?: return false

            return emitResolvedHttpUrl(
                url = resolved,
                sourceLabel = sourceLabel,
                qualityHint = qualityHint,
                isWatch = isWatch && parsed.scheme != "timesdizz",
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }

        // 2. Non-URL encrypted ticket string -> resolve via DoomVideoPlayer v1.6/v1.5 API
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            // URL-encoded HTTP link check (`DownloadScreenKt`: `CommonExtensionKt.urlDecode($url)`)
            val urlDecoded = runCatching { URLDecoder.decode(trimmed, "UTF-8").trim() }.getOrNull()
            if (urlDecoded != null && (urlDecoded.startsWith("http://", true) || urlDecoded.startsWith("https://", true))) {
                return emitResolvedHttpUrl(
                    url = urlDecoded,
                    sourceLabel = sourceLabel,
                    qualityHint = qualityHint,
                    isWatch = isWatch,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            }

            val resolved = if (isWatch) {
                TimeMoviesApi.resolveDoomPlayTicket(trimmed)
                    ?: TimeMoviesApi.resolveDoomDownloadTicket(trimmed)
            } else {
                TimeMoviesApi.resolveDoomDownloadTicket(trimmed)
                    ?: TimeMoviesApi.resolveDoomPlayTicket(trimmed)
            } ?: return false

            return emitResolvedHttpUrl(
                url = resolved,
                sourceLabel = sourceLabel,
                qualityHint = qualityHint,
                isWatch = isWatch,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }

        // 3. Direct HTTP(S) stream, progressive file, or external hoster URL
        return emitResolvedHttpUrl(
            url = trimmed,
            sourceLabel = sourceLabel,
            qualityHint = qualityHint,
            isWatch = isWatch,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    /**
     * Emits playable `ExtractorLink`(s) from a resolved `http(s)://` URL:
     * - Known third-party hosters -> `loadExtractor(...)` + generic embed unpacker
     * - MPEG-DASH `.mpd` -> `ExtractorLinkType.DASH`
     * - Progressive `.mp4`/`.mkv`/`.webm` or download link -> `ExtractorLinkType.VIDEO`
     * - Watch stream (`isWatch == true` or `.m3u8`) -> `ExtractorLinkType.M3U8`
     *   (matching `DoomVideoPlayer` `PlayerScreenKt`: `.setMimeType("application/x-mpegURL")`),
     *   emitting BOTH the untouched master URL AND parsed multi-quality `#EXT-X-STREAM-INF` variants + subtitles!
     */
    internal suspend fun emitResolvedHttpUrl(
        url: String,
        sourceLabel: String,
        qualityHint: Int?,
        isWatch: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", true) && !cleanUrl.startsWith("https://", true)) {
            return false
        }

        // 1. Known third-party hoster / embed URL -> delegate to CloudStream + registered custom extractors
        if (isKnownExternalHoster(cleanUrl)) {
            if (delegateToHosterExtractors(cleanUrl, sourceLabel, subtitleCallback, callback)) {
                return true
            }
        }

        val pathPart = cleanUrl.substringBefore('?').substringBefore('#').lowercase(Locale.US)

        // 2. MPEG-DASH manifest (`.mpd`)
        if (pathPart.endsWith(".mpd") || cleanUrl.lowercase(Locale.US).contains(".mpd?")) {
            callback(
                newExtractorLink(
                    source = sourceLabel,
                    name = sourceLabel,
                    url = cleanUrl,
                    type = ExtractorLinkType.DASH,
                ) {
                    this.quality = qualityHint ?: parseQualityNumber(cleanUrl) ?: Qualities.Unknown.value
                }
            )
            return true
        }

        val isExplicitProgressiveFile = pathPart.endsWith(".mp4") ||
            pathPart.endsWith(".mkv") ||
            pathPart.endsWith(".webm") ||
            pathPart.endsWith(".avi") ||
            pathPart.endsWith(".mov")

        val isExplicitHls = isLikelyHlsUrl(cleanUrl)

        // 3. HLS adaptive stream:
        //    In both TimeMovies (`manifest`) and DoomVideoPlayer (`PlayerScreenKt`: `application/x-mpegURL`),
        //    every watch URL that isn't an explicit `.mp4`/`.mkv` file is an HLS playlist!
        //    v4 Fix:
        //    - `/api/player/v1.6/app/m?t=...&s=...` expires in ~210s (`HTTP 404` -> `ERROR_CODE_IO_BAD_HTTP_STATUS 2004`),
        //      while its inner `sv*.egy.rocks` CDN URLs have 4h–24h tokens! Serving the absolutized manifest via
        //      `TimeMoviesLocalHlsServer` (`http://127.0.0.1:<port>/hls/...`) eliminates `2004` errors completely.
        //    - DoomPlayer's Kaltura `nginx-vod-module` streams (`index-f2-v1.m3u8`, `index-f3-v1.m3u8`) are demuxed
        //      video-only tracks paired with `#EXT-X-MEDIA:TYPE=AUDIO` + `#EXT-X-MEDIA:TYPE=SUBTITLES` (`index-f4-v1.m3u8`).
        //      Synthesizing a single-variant Master M3U8 per quality keeps FULL audio + native embedded subtitles on
        //      EVERY resolution (`1080p`, `720p`, `480p`, `360p`), and avoids feeding segmented `.m3u8` subtitle
        //      playlists into CloudStream's standalone `SubripParser`!
        if (isExplicitHls || (isWatch && !isExplicitProgressiveFile)) {
            val inspected = inspectHttpPayloadIfText(cleanUrl)
            if (inspected != null && looksLikeHtmlEmbed(inspected)) {
                if (delegateToHosterExtractors(cleanUrl, sourceLabel, subtitleCallback, callback, inspected)) {
                    return true
                }
            }

            val autoLabel = if (sourceLabel.contains("[")) sourceLabel else "$sourceLabel [Auto HLS]"
            val baseLabel = sourceLabel.substringBefore(" [").trim().ifEmpty { sourceLabel }

            if (inspected != null && inspected.contains("#EXTM3U", ignoreCase = true)) {
                val absolutized = absolutizeM3u8Manifest(inspected, cleanUrl)
                val master = parseMasterManifestDetails(absolutized, cleanUrl)

                // Only pass standalone `.vtt`/`.srt`/`.ass` subtitle files to `subtitleCallback`;
                // segmented HLS `.m3u8` subtitle playlists (`index-f4-v1.m3u8`) MUST stay inside the
                // Master M3U8 so Media3 `HlsMediaSource` renders them natively as `EMBEDDED_IN_VIDEO`.
                for (sub in parseHlsSubtitlesFromMaster(absolutized, cleanUrl)) {
                    if (isStandaloneSubtitleFileUrl(sub.url)) {
                        subtitleCallback(
                            newSubtitleFile(
                                lang = sub.label,
                                url = sub.url,
                            )
                        )
                    }
                }

                val autoPlayableUrl = TimeMoviesLocalHlsServer.registerM3u8(absolutized, slug = "auto") ?: cleanUrl
                val bestVariantQuality = master.variants
                    .maxOfOrNull { it.quality }
                    ?.takeIf { it > Qualities.Unknown.value }

                callback(
                    newExtractorLink(
                        source = baseLabel,
                        name = autoLabel,
                        url = autoPlayableUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = bestVariantQuality
                            ?: qualityHint
                            ?: parseQualityNumber(cleanUrl)
                            ?: Qualities.Unknown.value
                    }
                )

                if (master.isMaster && master.variants.isNotEmpty()) {
                    val hasSeparateMediaTracks = master.mediaLines.any {
                        it.type == "AUDIO" || it.type == "SUBTITLES" || it.type == "CLOSED-CAPTIONS"
                    }

                    for (variant in master.variants) {
                        if (variant.url == cleanUrl) continue
                        val variantPlayableUrl = if (hasSeparateMediaTracks) {
                            val singleMaster = synthesizeSingleVariantMasterM3u8(master, variant)
                            TimeMoviesLocalHlsServer.registerM3u8(singleMaster, slug = variant.displayLabel) ?: variant.url
                        } else {
                            variant.url
                        }
                        callback(
                            newExtractorLink(
                                source = baseLabel,
                                name = "$baseLabel [${variant.displayLabel}]",
                                url = variantPlayableUrl,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.quality = variant.quality
                            }
                        )
                    }
                }
                return true
            }

            // Fallback when `#EXTM3U` text could not be pre-inspected
            callback(
                newExtractorLink(
                    source = baseLabel,
                    name = autoLabel,
                    url = cleanUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.quality = qualityHint ?: parseQualityNumber(cleanUrl) ?: Qualities.Unknown.value
                }
            )
            return true
        }

        // 4. Progressive MP4 / MKV direct video download or stream
        val resolvedQuality = qualityHint
            ?: parseQualityNumber(sourceLabel)
            ?: parseQualityNumber(cleanUrl)
            ?: Qualities.Unknown.value

        callback(
            newExtractorLink(
                source = sourceLabel,
                name = sourceLabel,
                url = cleanUrl,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.quality = resolvedQuality
            }
        )
        return true
    }

    private fun isKnownExternalHoster(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        if (host.isEmpty()) return false
        return KNOWN_EXTERNAL_HOSTER_KEYWORDS.any { kw -> host.contains(kw) }
    }

    private fun isLikelyHlsUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("playlist") ||
            lower.contains("manifest") ||
            lower.contains("mpegurl")
    }

    private fun looksLikeHtmlEmbed(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.contains("eval(function(p,a,c,k,e,d)") ||
            trimmed.contains("jwplayer(")
    }

    /**
     * Lightly inspects a stream URL ONLY if it returns small text (`#EXTM3U` or `text/html`),
     * immediately aborting without reading binary video segments if `Content-Type` is binary video.
     */
    private fun inspectHttpPayloadIfText(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "*/*")
            .build()
        inspectClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val ct = resp.header("Content-Type")?.lowercase(Locale.US).orEmpty()
            if (ct.startsWith("video/") || ct.contains("octet-stream") || ct.contains("mp4")) {
                return null
            }
            val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > 2_000_000L) return null
            resp.body?.string()
        }
    }.getOrNull()

    private suspend fun delegateToHosterExtractors(
        url: String,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        prefetchedHtml: String? = null,
    ): Boolean {
        var found = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        runCatching {
            if (loadExtractor(url, url, subtitleCallback, wrappedCallback)) {
                found = true
            }
        }
        if (found) return true

        // Generic packed-JS / JWPlayer / Plyr HTML sniffer fallback
        return extractGenericHtmlEmbed(
            embedUrl = url,
            sourceLabel = sourceLabel,
            prefetchedHtml = prefetchedHtml,
            subtitleCallback = subtitleCallback,
            callback = wrappedCallback,
        )
    }

    internal suspend fun extractGenericHtmlEmbed(
        embedUrl: String,
        sourceLabel: String,
        prefetchedHtml: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = prefetchedHtml ?: runCatching { app.get(embedUrl, referer = embedUrl).text }.getOrNull() ?: return false
        val combined = buildString {
            append(html)
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\.split\(['"]\|['"]\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            for (m in packedRegex.findAll(html)) {
                val unpacked = runCatching { JsUnpacker(m.value).unpack() }.getOrNull()
                if (!unpacked.isNullOrBlank()) {
                    append('\n')
                    append(unpacked)
                }
            }
        }

        var emitted = false
        val origin = runCatching {
            val u = URI(embedUrl)
            "${u.scheme}://${u.host}"
        }.getOrDefault(embedUrl)

        // Extract relative or absolute `.m3u8` / `.mp4` stream candidates (`hls2`, `hls4`, `hls3`, `file:"..."`)
        val streamCandidates = LinkedHashSet<String>()
        val jsonLinkKeys = listOf("hls4", "hls2", "hls3", "file", "src")
        for (key in jsonLinkKeys) {
            val rgx = Regex("""["']$key["']\s*:\s*["']([^"']+)["']""")
            for (m in rgx.findAll(combined)) {
                val candidate = m.groupValues[1].replace("\\/", "/").trim()
                if (candidate.contains(".m3u8") || candidate.contains(".mp4") || candidate.startsWith("/stream/")) {
                    streamCandidates.add(resolveRelativeUrl(embedUrl, candidate))
                }
            }
        }
        val directUrlRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        for (m in directUrlRegex.findAll(combined)) {
            streamCandidates.add(m.value.replace("\\/", "/"))
        }

        for (streamUrl in streamCandidates) {
            val isM3u8 = streamUrl.lowercase(Locale.US).contains(".m3u8")
            callback(
                newExtractorLink(
                    source = sourceLabel,
                    name = sourceLabel,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$origin/"
                    this.quality = parseQualityNumber(streamUrl) ?: Qualities.Unknown.value
                }
            )
            emitted = true
        }

        // Subtitle `.vtt` / `.srt` tracks inside HTML player configs
        val subRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:vtt|srt)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        for (m in subRegex.findAll(combined)) {
            val subUrl = m.value.replace("\\/", "/")
            subtitleCallback(newSubtitleFile("Subtitle", subUrl))
        }

        return emitted
    }

    fun parseQualityNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        Regex("""(2160|1440|1080|720|480|360|240)\s*p?""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        val lower = text.lowercase(Locale.US)
        return when {
            lower.contains("4k") || lower.contains("uhd") -> Qualities.P2160.value
            lower.contains("fhd") -> Qualities.P1080.value
            lower.contains("hd") -> Qualities.P720.value
            lower.contains("sd") -> Qualities.P480.value
            else -> getQualityFromName(text).takeIf { it != Qualities.Unknown.value }
        }
    }

    // ---- HLS Master Rewriter, Demuxed Single-Variant Synthesizer & Track Parsers ----

    internal fun isStandaloneSubtitleFileUrl(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        if (cleanPath.endsWith(".m3u8") || url.lowercase(Locale.US).contains(".m3u8?")) {
            return false
        }
        return cleanPath.endsWith(".vtt") ||
            cleanPath.endsWith(".srt") ||
            cleanPath.endsWith(".ass") ||
            cleanPath.endsWith(".ssa") ||
            cleanPath.endsWith(".ttml") ||
            cleanPath.endsWith(".dfxp")
    }

    private fun isProxyManifestEndpoint(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("/api/player/") && (lower.contains("/app/m?") || lower.endsWith("/app/m"))
    }

    private fun absolutizeChildUri(masterUrl: String, masterQuery: String?, rawUri: String): String {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        var resolved = resolveRelativeUrl(masterUrl, trimmed)
        if (!masterQuery.isNullOrBlank() && !resolved.contains('?') && !isProxyManifestEndpoint(masterUrl)) {
            resolved = "$resolved?$masterQuery"
        }
        return resolved
    }

    internal fun absolutizeM3u8Manifest(rawM3u8: String, masterUrl: String): String {
        val masterQuery = runCatching { URI(masterUrl).rawQuery?.takeIf { it.isNotBlank() } }.getOrNull()
        val uriAttrRegex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        val normalizedLines = rawM3u8.removePrefix("\uFEFF").lines()

        return buildString(rawM3u8.length + 256) {
            for (rawLine in normalizedLines) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#")) {
                    val rewrittenTag = uriAttrRegex.replace(trimmed) { m ->
                        val abs = absolutizeChildUri(masterUrl, masterQuery, m.groupValues[1])
                        "URI=\"$abs\""
                    }
                    append(rewrittenTag).append('\n')
                } else {
                    append(absolutizeChildUri(masterUrl, masterQuery, trimmed)).append('\n')
                }
            }
        }
    }

    internal fun parseMasterManifestDetails(
        absolutizedContent: String,
        masterUrl: String,
    ): ParsedMasterManifest {
        val lines = absolutizedContent.lines()
        val globalHeaders = LinkedHashSet<String>()
        val mediaRenditions = ArrayList<ParsedMediaRendition>()
        val rawVariants = ArrayList<HlsVariantStream>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.equals("#EXTM3U", ignoreCase = true)) {
                i++
                continue
            }

            when {
                line.startsWith("#EXT-X-VERSION:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS", ignoreCase = true) ||
                    line.startsWith("#EXT-X-START:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-SESSION-DATA:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) -> {
                    globalHeaders.add(line)
                }

                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    val attrs = line.substringAfter(':')
                    val typeAttr = parseM3u8Attribute(attrs, "TYPE")?.uppercase(Locale.US).orEmpty()
                    if (typeAttr.isNotEmpty()) {
                        mediaRenditions.add(
                            ParsedMediaRendition(
                                rawLine = line,
                                type = typeAttr,
                                groupId = parseM3u8Attribute(attrs, "GROUP-ID"),
                                name = parseM3u8Attribute(attrs, "NAME"),
                                language = parseM3u8Attribute(attrs, "LANGUAGE"),
                                uri = parseM3u8Attribute(attrs, "URI"),
                                isDefault = parseM3u8Attribute(attrs, "DEFAULT")
                                    ?.equals("YES", ignoreCase = true) == true,
                            )
                        )
                    }
                }

                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    val attrs = line.substringAfter(':')
                    val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                        .find(attrs)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    val bw = Regex("""(?:^|,)BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                        .find(attrs)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                        ?: Regex("""AVERAGE-BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                            .find(attrs)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                        ?: 0L
                    val nameAttr = parseM3u8Attribute(attrs, "NAME")
                    val audioGroup = parseM3u8Attribute(attrs, "AUDIO")
                    val subGroup = parseM3u8Attribute(attrs, "SUBTITLES")

                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j].trim()
                        if (next.isEmpty()) {
                            j++
                            continue
                        }
                        if (next.startsWith("#")) {
                            if (next.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) break
                            j++
                            continue
                        }
                        val resolvedUrl = absolutizeChildUri(masterUrl, null, next)
                        val quality = height
                            ?: parseQualityNumber(nameAttr)
                            ?: parseQualityNumber(resolvedUrl)
                            ?: Qualities.Unknown.value
                        val baseLabel = when {
                            height != null -> "${height}p"
                            !nameAttr.isNullOrBlank() -> nameAttr
                            quality > Qualities.Unknown.value -> "${quality}p"
                            else -> "HLS"
                        }
                        rawVariants.add(
                            HlsVariantStream(
                                streamInfLine = line,
                                url = resolvedUrl,
                                quality = quality,
                                bandwidth = bw,
                                baseLabel = baseLabel,
                                displayLabel = baseLabel,
                                audioGroupId = audioGroup,
                                subtitleGroupId = subGroup,
                            )
                        )
                        i = j
                        break
                    }
                }
            }
            i++
        }

        val dedupedVariants = rawVariants
            .distinctBy { it.url }
            .sortedWith(compareByDescending<HlsVariantStream> { it.quality }.thenByDescending { it.bandwidth })

        val countsByBaseLabel = dedupedVariants.groupingBy { it.baseLabel }.eachCount()
        val finalVariants = dedupedVariants.map { v ->
            if ((countsByBaseLabel[v.baseLabel] ?: 0) > 1 && v.bandwidth > 0L) {
                val mbps = String.format(Locale.US, "%.1f Mbps", v.bandwidth / 1_000_000.0)
                v.copy(displayLabel = "${v.baseLabel} • $mbps")
            } else {
                v
            }
        }

        return ParsedMasterManifest(
            isMaster = finalVariants.isNotEmpty(),
            absolutizedContent = absolutizedContent,
            globalHeaderLines = globalHeaders.toList(),
            mediaLines = mediaRenditions,
            variants = finalVariants,
        )
    }

    internal fun synthesizeSingleVariantMasterM3u8(
        master: ParsedMasterManifest,
        variant: HlsVariantStream,
    ): String {
        val allAudio = master.mediaLines.filter { it.type == "AUDIO" }
        val selectedAudio = if (!variant.audioGroupId.isNullOrBlank()) {
            allAudio.filter { it.groupId.equals(variant.audioGroupId, ignoreCase = true) }
                .ifEmpty { allAudio }
        } else {
            allAudio
        }

        val allSubs = master.mediaLines.filter { it.type == "SUBTITLES" }
        val selectedSubs = if (!variant.subtitleGroupId.isNullOrBlank()) {
            allSubs.filter { it.groupId.equals(variant.subtitleGroupId, ignoreCase = true) }
                .ifEmpty { allSubs }
        } else {
            allSubs
        }

        val allCaptions = master.mediaLines.filter { it.type == "CLOSED-CAPTIONS" }

        var effectiveStreamInf = variant.streamInfLine
        val fallbackAudioGroup = selectedAudio.firstOrNull { !it.groupId.isNullOrBlank() }?.groupId
        if (variant.audioGroupId.isNullOrBlank() && !fallbackAudioGroup.isNullOrBlank() &&
            !effectiveStreamInf.contains("AUDIO=", ignoreCase = true)
        ) {
            effectiveStreamInf = "$effectiveStreamInf,AUDIO=\"$fallbackAudioGroup\""
        }
        val fallbackSubGroup = selectedSubs.firstOrNull { !it.groupId.isNullOrBlank() }?.groupId
        if (variant.subtitleGroupId.isNullOrBlank() && !fallbackSubGroup.isNullOrBlank() &&
            !effectiveStreamInf.contains("SUBTITLES=", ignoreCase = true)
        ) {
            effectiveStreamInf = "$effectiveStreamInf,SUBTITLES=\"$fallbackSubGroup\""
        }

        return buildString(1024) {
            append("#EXTM3U\n")
            if (master.globalHeaderLines.none { it.startsWith("#EXT-X-VERSION:", ignoreCase = true) }) {
                append("#EXT-X-VERSION:3\n")
            }
            for (hdr in master.globalHeaderLines) {
                append(hdr).append('\n')
            }
            for (aud in selectedAudio) {
                append(aud.rawLine).append('\n')
            }
            for (sub in selectedSubs) {
                append(sub.rawLine).append('\n')
            }
            for (cc in allCaptions) {
                append(cc.rawLine).append('\n')
            }
            append(effectiveStreamInf).append('\n')
            append(variant.url).append('\n')
        }
    }

    fun parseHlsVariantsFromMaster(
        masterContent: String,
        masterUrl: String,
    ): List<ParsedHlsVariant> {
        val out = ArrayList<ParsedHlsVariant>()
        val seen = HashSet<String>()
        val lines = masterContent.lines()
        val masterQuery = runCatching { URI(masterUrl).rawQuery?.takeIf { it.isNotBlank() } }.getOrNull()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val attrs = line.substringAfter(':')
                val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                    .find(attrs)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                val nameAttr = parseM3u8Attribute(attrs, "NAME")
                val quality = height ?: parseQualityNumber(nameAttr) ?: Qualities.Unknown.value
                val label = when {
                    height != null -> "${height}p"
                    !nameAttr.isNullOrBlank() -> nameAttr
                    else -> "HLS"
                }

                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) {
                    j++
                }
                if (j < lines.size) {
                    var resolved = resolveRelativeUrl(masterUrl, lines[j].trim())
                    // Preserve signed query token from `masterUrl` when relative variant URI lacks a query string
                    if (!masterQuery.isNullOrBlank() && !resolved.contains('?') && !lines[j].trim().startsWith("http", true)) {
                        resolved = "$resolved?$masterQuery"
                    }
                    if (seen.add(resolved)) {
                        out.add(ParsedHlsVariant(url = resolved, quality = quality, label = label))
                    }
                    i = j
                }
            }
            i++
        }
        return out.sortedByDescending { it.quality }
    }

    fun parseHlsSubtitlesFromMaster(
        masterContent: String,
        masterUrl: String,
    ): List<ParsedSubtitleTrack> {
        val out = ArrayList<ParsedSubtitleTrack>()
        val seenUrls = HashSet<String>()
        val masterQuery = runCatching { URI(masterUrl).rawQuery?.takeIf { it.isNotBlank() } }.getOrNull()

        for (rawLine in masterContent.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) continue
            if (!line.contains("TYPE=SUBTITLES", ignoreCase = true)) continue

            val uriAttr = parseM3u8Attribute(line, "URI") ?: continue
            var resolvedUri = resolveRelativeUrl(masterUrl, uriAttr)
            if (!masterQuery.isNullOrBlank() && !resolvedUri.contains('?') && !uriAttr.startsWith("http", true)) {
                resolvedUri = "$resolvedUri?$masterQuery"
            }
            if (!seenUrls.add(resolvedUri)) continue

            val nameAttr = parseM3u8Attribute(line, "NAME")
            val langAttr = parseM3u8Attribute(line, "LANGUAGE")
            val defaultAttr = parseM3u8Attribute(line, "DEFAULT")?.equals("YES", ignoreCase = true) == true

            val label = when {
                !nameAttr.isNullOrBlank() && !langAttr.isNullOrBlank() &&
                    !nameAttr.contains(langAttr, ignoreCase = true) ->
                    "${normalizeSubtitleLabel(nameAttr, langAttr)}"
                !nameAttr.isNullOrBlank() -> normalizeSubtitleLabel(nameAttr, langAttr)
                !langAttr.isNullOrBlank() -> normalizeSubtitleLabel(langAttr, langAttr)
                else -> "Subtitle"
            }

            out.add(
                ParsedSubtitleTrack(
                    label = label,
                    langCode = langAttr,
                    url = resolvedUri,
                    isDefault = defaultAttr,
                )
            )
        }
        return out
    }

    private fun parseM3u8Attribute(line: String, key: String): String? {
        val quoted = Regex("""(?:^|,)\s*$key="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)
        if (quoted != null) return quoted.groupValues[1].trim()
        val unquoted = Regex("""(?:^|,)\s*$key=([^,\s]+)""", RegexOption.IGNORE_CASE).find(line)
        return unquoted?.groupValues?.get(1)?.trim()
    }

    private fun normalizeSubtitleLabel(name: String, lang: String?): String {
        val code = lang?.lowercase(Locale.US).orEmpty()
        return when {
            code.startsWith("ar") || name.contains("arab", ignoreCase = true) || name.contains("عرب") ->
                if (name.contains("عرب")) name else "العربية ($name)"
            code.startsWith("en") || name.contains("eng", ignoreCase = true) -> "English"
            code.startsWith("fr") -> "Français"
            code.startsWith("es") -> "Español"
            code.startsWith("tr") -> "Türkçe"
            else -> name
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeOrAbs: String): String {
        if (relativeOrAbs.startsWith("http://", true) || relativeOrAbs.startsWith("https://", true)) {
            return relativeOrAbs
        }
        return runCatching {
            URI(baseUrl).resolve(relativeOrAbs).toString()
        }.getOrElse {
            val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/', "")
            if (relativeOrAbs.startsWith('/')) {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value.orEmpty()
                "$origin$relativeOrAbs"
            } else {
                "$baseDir/$relativeOrAbs"
            }
        }
    }
}

// =========================================================================
// Registered CloudStream `ExtractorApi` Classes (`DoomVideoPlayer` + Hosters)
// =========================================================================

open class DoomPlayerPlayExtractor : ExtractorApi() {
    override val name: String = "DoomVideoPlayer"
    override val mainUrl: String = "timesnizz://doomplayer.com"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val parsed = TimeMoviesCrypto.parseDoomDeepLink(url)
        val resolved = when {
            parsed != null && parsed.isEncryptedTicket -> TimeMoviesApi.resolveDoomPlayTicket(parsed.srcOrUrl)
            parsed != null -> parsed.srcOrUrl
            else -> TimeMoviesApi.resolveDoomPlayTicket(url.substringAfterLast('/'))
        } ?: return

        TimeMoviesExtractors.emitResolvedHttpUrl(
            url = resolved,
            sourceLabel = name,
            qualityHint = null,
            isWatch = true,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}

class DoomPlayerHttpsPlayExtractor : DoomPlayerPlayExtractor() {
    override val name: String = "DoomVideoPlayer Play"
    override val mainUrl: String = "https://doomplayer.com/play"
}

open class DoomPlayerDownloadExtractor : ExtractorApi() {
    override val name: String = "DoomVideoPlayer Download"
    override val mainUrl: String = "timesdizz://doomplayer.com"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val parsed = TimeMoviesCrypto.parseDoomDeepLink(url)
        val resolved = when {
            parsed != null && parsed.isEncryptedTicket -> TimeMoviesApi.resolveDoomDownloadTicket(parsed.srcOrUrl)
            parsed != null -> parsed.srcOrUrl
            else -> TimeMoviesApi.resolveDoomDownloadTicket(url.substringAfterLast('/'))
        } ?: return

        TimeMoviesExtractors.emitResolvedHttpUrl(
            url = resolved,
            sourceLabel = name,
            qualityHint = null,
            isWatch = false,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}

class DoomPlayerHttpsDownloadExtractor : DoomPlayerDownloadExtractor() {
    override val name: String = "DoomVideoPlayer Direct"
    override val mainUrl: String = "https://doomplayer.com/download"
}

class TimeMoviesMediaV2Extractor : ExtractorApi() {
    override val name: String = "TimeMovies MediaV2"
    override val mainUrl: String = "https://api.apks.lol/v2/media"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val grant = referer?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: return
        val action = if (url.contains("/download")) "download" else "watch"
        val media = TimeMoviesApi.fetchOriginMedia(action, grant) ?: return
        val src = media.src?.trim()
        if (!src.isNullOrEmpty()) {
            TimeMoviesExtractors.emitResolvedHttpUrl(
                url = src,
                sourceLabel = name,
                qualityHint = null,
                isWatch = action == "watch",
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }
        for (item in media.sources) {
            TimeMoviesExtractors.emitResolvedHttpUrl(
                url = item.link,
                sourceLabel = "$name [${item.name ?: "${item.quality ?: ""}p"}]",
                qualityHint = item.quality,
                isWatch = action == "watch",
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }
    }
}

// ---- StreamHG / EarnVids Family Extractors ----

open class TmStreamHgExtractor : ExtractorApi() {
    override val name: String = "StreamHG"
    override val mainUrl: String = "https://hglink.to"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        TimeMoviesExtractors.extractGenericHtmlEmbed(
            embedUrl = url,
            sourceLabel = name,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}

class TmCavanhabgExtractor : TmStreamHgExtractor() {
    override val mainUrl: String = "https://cavanhabg.com"
}

class TmDumbalagExtractor : TmStreamHgExtractor() {
    override val mainUrl: String = "https://dumbalag.com"
}

class TmUasoptExtractor : TmStreamHgExtractor() {
    override val mainUrl: String = "https://uasopt.com"
}

class TmDhcplayExtractor : TmStreamHgExtractor() {
    override val mainUrl: String = "https://dhcplay.com"
}

class TmEarnVidsExtractor : TmStreamHgExtractor() {
    override val name: String = "EarnVids"
    override val mainUrl: String = "https://earnvids.com"
}

// ---- Vidara / Vidroba Family Extractors ----

open class TmVidaraExtractor : ExtractorApi() {
    override val name: String = "Vidara"
    override val mainUrl: String = "https://vidara.to"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fileCode = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
        if (!fileCode.isNullOrBlank()) {
            val origin = runCatching {
                val u = URI(url)
                "${u.scheme}://${u.host}"
            }.getOrDefault(mainUrl)
            val apiText = runCatching {
                app.get("$origin/api/stream?filecode=$fileCode", referer = "$origin/e/$fileCode").text
            }.getOrNull()
            if (!apiText.isNullOrBlank() && apiText.trimStart().startsWith("{")) {
                val json = runCatching { JSONObject(apiText) }.getOrNull()
                val streamUrl = json?.optString("streaming_url", "")?.trim().orEmpty()
                if (streamUrl.startsWith("http")) {
                    TimeMoviesExtractors.emitResolvedHttpUrl(
                        url = streamUrl,
                        sourceLabel = name,
                        qualityHint = null,
                        isWatch = true,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                    return
                }
            }
        }
        TimeMoviesExtractors.extractGenericHtmlEmbed(
            embedUrl = url,
            sourceLabel = name,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}

class TmVidaraSoExtractor : TmVidaraExtractor() {
    override val mainUrl: String = "https://vidara.so"
}

class TmVidrobaExtractor : TmVidaraExtractor() {
    override val name: String = "Vidroba"
    override val mainUrl: String = "https://vidroba.com"
}

// ---- Byse Family Extractors ----

open class TmByseExtractor : ExtractorApi() {
    override val name: String = "Byse"
    override val mainUrl: String = "https://bysezejataos.com"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        TimeMoviesExtractors.extractGenericHtmlEmbed(
            embedUrl = url,
            sourceLabel = name,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}

class TmByseVepoinExtractor : TmByseExtractor() {
    override val mainUrl: String = "https://bysevepoin.com"
}

class TmByseDikamoumExtractor : TmByseExtractor() {
    override val mainUrl: String = "https://bysedikamoum.com"
}

class TmByseKozeExtractor : TmByseExtractor() {
    override val mainUrl: String = "https://bysekoze.com"
}
