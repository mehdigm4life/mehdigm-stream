package com.animeday

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import kotlin.random.Random

/**
 * ============================================================================
 *  AnimeDay — professional extractor suite (stable, self-contained).
 * ============================================================================
 *  Handles every source the AnimeDay backend can return:
 *
 *    1. Direct streams ....... .m3u8 (expanded), .mp4 / .mkv, .mpd
 *    2. Google Photos ........ lh3.googleusercontent.com/pw/... (+ wrappers)
 *    3. AnimeDay PHP chain ... extractor.php / html_extractor.php / advanced_extractor.php
 *    4. vidspeed ............. vidspeed.org JWPlayer embed pages (P.A.C.K.E.R.)
 *    5. Fallback ............. CloudStream's global loadExtractor
 *
 *  Google Photos note: the backend exposes every quality through a single DASH
 *  manifest (`=mm,dash-vm-vf,...`). Its googlevideo segments are IP-pinned and
 *  answer HTTP 403 unless BOTH `Referer` and `Origin: https://photos.google.com`
 *  are sent, and they are pinned to the `User-Agent` that minted them — exactly
 *  the headers [PHOTO_HEADERS] carries. Every video rendition is emitted as its
 *  own independent DASH source (a single-rendition manifest served over the
 *  loopback [PhotoDashServer]), so each quality is directly selectable and
 *  stable instead of relying on the adaptive manifest's "tracks" switches, which
 *  intermittently fail. The full adaptive manifest and the probed progressive
 *  `=m37` / `=m22` / `=m18` MP4 renditions are emitted as fallbacks.
 *
 *  This file is intentionally the single place where extraction lives; the main
 *  API only fetches/decrypts the server list and hands each link to [emit].
 */
object AnimeDayExtractors {

    const val UA = "okhttp/4.10.0"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
    private const val PHP_BASE = "https://cloud-day.online/cimacloud"
    private const val HEAD_BYTES = 128 * 1024

    private const val PHOTO_REFERER = "https://photos.google.com/"
    private const val PHOTO_ORIGIN = "https://photos.google.com"

    /** DASH option that lists every real rendition (audio + video), exactly like the apps' player. */
    private const val DASH_OPTION =
        "=mm,dash-vm-vf,dr.sdr,sdrCodec.vp9.h264?alr=true&mpd_version=5&pacing=0"

    /**
     * Google Photos googlevideo URLs are pinned to the `User-Agent` that minted
     * them, so the player must send the exact same UA the manifest/probe used.
     * Without this the player falls back to CloudStream's own UA and every
     * segment answers HTTP 403.
     */
    private val PHOTO_HEADERS = mapOf(
        "Origin" to PHOTO_ORIGIN,
        "User-Agent" to BROWSER_UA
    )

    private val PHP_ENDPOINTS = listOf(
        "$PHP_BASE/extractor.php",
        "$PHP_BASE/html_extractor.php",
        "$PHP_BASE/advanced_extractor.php"
    )
    private val SEEKABLE = listOf("=m37", "=m22", "=m18")

    /**
     * A per-play nonce appended to the DASH manifest URL.
     *
     * Google's DASH manifest embeds googlevideo `BaseURL`s that are pinned to the
     * fetching IP and carry a short `expire`/`sig`. CloudStream caches by URL, so a
     * manifest cached on an earlier play is served again later with already-expired
     * segment URLs: the stream then starts (cached first segments) but every seek to
     * an unbuffered position hits an expired segment (HTTP 403), the player reports
     * an error and falls back to the next link. Changing the URL every play forces a
     * brand-new manifest with fresh, valid segment URLs. The nonce is stable for the
     * whole duration of one [newSession] so the same episode never emits duplicates.
     */
    @Volatile
    private var sessionNonce: String = System.currentTimeMillis().toString(36)

    /** One (URL → body) manifest per session, so every listed base reuses the same
     *  freshly-minted googlevideo segment URLs; reset by [newSession]. */
    @Volatile
    private var cachedManifestUrl: String? = null

    @Volatile
    private var cachedManifestBody: String? = null

    /** Rotate the manifest nonce once per link-loading run (call from loadLinks). */
    fun newSession() {
        sessionNonce = System.currentTimeMillis().toString(36) + Integer.toHexString(Random.nextInt())
        cachedManifestUrl = null
        cachedManifestBody = null
    }

    private fun freshDash(url: String): String =
        if (url.contains("?")) "$url&_=$sessionNonce" else "$url?_=$sessionNonce"

    private val PW_RE = Regex("""https://lh3\.googleusercontent\.com/pw/[^=?"'\s\\]+""")
    private val HTML_RE = Regex("""data-url="(https://lh3\.googleusercontent\.com/pw/[^\s"?]+)""")
    private val WRAP_RE = Regex("[?&]url=([^&]+)")

    fun plainHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*"
    )

    fun pageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept-Language" to "ar,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    fun isGooglePhotosLink(link: String): Boolean =
        link.contains("photos.google.com") || link.contains("photos.app.goo.gl") ||
                link.contains("googleusercontent.com") || link.contains("googlefinal") ||
                link.contains("hrrejhp")

    // ------------------------------------------------------------------ vidspeed (JWPlayer P.A.C.K.E.R. embeds)

    private val PACKER_EVAL_RE = Regex(
        """(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)"""
    )
    private val PACKER_WORD_RE = Regex("""\b[a-zA-Z0-9_]+\b""")
    private val VIDSPEED_STREAM_RE =
        Regex("""[:=]\s*"([^"\s]+(?:\.m3u8|master\.txt)[^"\s]*)""")

    /**
     * vidspeed.org (and mirror hosts) serve their real HLS master inside a
     * P.A.C.K.E.R.-obfuscated JWPlayer script:
     * `eval(function(p,a,c,k,e,d){...}('payload',36,345,'symtab'|...))`. The CDN
     * answers 403 unless the fetch is referer-pinned to the fresh embed page, so
     * the page must be re-fetched and its token re-decoded at play time.
     *
     * This is the faithful port of CloudStream's own JsUnpacker.unpack(): it walks
     * the payload's base-N words in order and substitutes each index that exists
     * and is non-empty in the symbol table (empty entries — which otherwise would
     * eat the URL's `t`/`s`/`e`/`v`/`i` keys — are skipped, exactly like the
     * packer's own `if(k[c])` guard).
     */
    private fun unPacker(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val match = PACKER_EVAL_RE.find(html) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val symtab = match.groupValues[4].split("|")
        if (symtab.size != count) return null
        val decoded = StringBuilder(payload)
        var offset = 0
        PACKER_WORD_RE.findAll(payload).forEach { word ->
            val x = runCatching { word.value.toInt(radix) }.getOrNull() ?: return@forEach
            val value = symtab.getOrNull(x).takeIf { !it.isNullOrEmpty() } ?: return@forEach
            decoded.setRange(word.range.first + offset, word.range.last + 1 + offset, value)
            offset += value.length - word.value.length
        }
        return decoded.toString()
    }

    /** Pull the first HLS URL out of a decoded JWPlayer setup script. */
    private fun vidspeedStreamUrl(script: String?): String? {
        if (script.isNullOrBlank()) return null
        return VIDSPEED_STREAM_RE.find(script)?.groupValues?.get(1)
    }

    /** True for a vidspeed embed page (the JWPlayer-in-packer host). */
    fun isVidspeedLink(link: String): Boolean =
        runCatching { Uri.parse(link.trim()).host?.contains("vidspeed", ignoreCase = true) == true }
            .getOrDefault(false)

    /** Fetch the embed page, decode its P.A.C.K.E.R. and emit the real HLS master. */
    private suspend fun emitVidspeed(
        embedUrl: String,
        label: String,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(embedUrl, headers = pageHeaders()).text
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "vidspeed page FAILED ${embedUrl.take(100)}: $e")
            return false
        }
        val script = unPacker(html)
        val m3u8 = vidspeedStreamUrl(script)
            ?: return false
        var found = false
        for (v in M3u8Helper.generateM3u8(label, m3u8, embedUrl)) {
            found = true
            emit(v)
        }
        return found
    }

    // ------------------------------------------------------------------ entry point

    /**
     * Single entry point: classify [link] and emit every playable stream it yields.
     *
     * NOTE: [emit] is the caller's callback and may itself deduplicate by URL, so
     * this suite must never pre-register a URL it is about to emit. Internal
     * de-duplication uses a private set ([done]) created per extraction.
     */
    suspend fun emit(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                link.contains(".m3u8") -> {
                    var found = false
                    for (v in M3u8Helper.generateM3u8(label, link, referer)) {
                        found = true
                        emit(v)
                    }
                    found
                }

                link.contains(".mp4") || link.contains(".mkv") || link.contains(".mpd") ->
                    emitDirect(link, referer, label, quality, emit)

                isVidspeedLink(link) ->
                    emitVidspeed(link, label, emit)

                isGooglePhotosLink(link) ->
                    emitGooglePhotosLink(link, referer, label, quality, subtitleCallback, emit)

                else ->
                    emitUnknown(link, referer, label, quality, subtitleCallback, emit)
            }
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "emit FAILED for ${link.take(120)}: $e")
            false
        }
    }

    // ------------------------------------------------------------------ direct

    private suspend fun emitDirect(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        emit(
            newExtractorLink(
                label,
                label,
                link,
                if (link.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                if (quality != null) this.quality = quality
            }
        )
        return true
    }

    // ------------------------------------------------------------------ generic fallback

    private suspend fun emitUnknown(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val done = mutableSetOf<String>()
        var found = false
        try {
            if (loadExtractor(link, referer, subtitleCallback, emit)) found = true
        } catch (_: Throwable) {
        }
        if (emitPhpChain(link, referer, label, quality, done, emit)) found = true
        return found
    }

    // ------------------------------------------------------------------ Google Photos

    private suspend fun emitGooglePhotosLink(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val base = resolveGphotoBase(link)
        android.util.Log.i("AnimeDayExtractors", "gphotos base=${base != null} link=${link.take(140)}")
        if (base != null) return emitGooglePhotos(base, label, link, mutableSetOf(), emit)
        return emitUnknown(link, referer, label, quality, subtitleCallback, emit)
    }

    /** Bare `.../pw/<token>` base, stripped of any `=modifier` and query string. */
    internal fun gphotoBase(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return PW_RE.find(url.trim())?.value
    }

    private fun gphotoBaseFromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val raw = HTML_RE.find(html)?.groupValues?.get(1) ?: return null
        return gphotoBase(raw)
    }

    /** Resolve any wrapper (googlefinal.php, photos.google.com share page, direct lh3) to the pw base. */
    private suspend fun resolveGphotoBase(url: String): String? {
        val trimmed = url.trim()
        gphotoBase(trimmed)?.let { return it }

        WRAP_RE.find(trimmed)?.groupValues?.get(1)
            ?.let { runCatching { Uri.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != trimmed }
            ?.let { return resolveGphotoBase(it) }

        if (trimmed.contains("photos.google.com") || trimmed.contains("photos.app.goo.gl")) {
            val html = try {
                app.get(trimmed, headers = pageHeaders()).text
            } catch (_: Throwable) {
                null
            }
            return gphotoBaseFromHtml(html)
        }
        return null
    }

    /**
     * Emit every real Google Photos rendition:
     *
     *   1. One independent DASH source per video rendition (192p…1080p), each served
     *      as a small single-rendition manifest over the loopback [PhotoDashServer].
     *      CloudStream's player ignores an [ExtractorLink]'s `quality` for track
     *      selection and the "tracks" switches inside the adaptive manifest
     *      intermittently fail — so each rendition becomes its own directly
     *      selectable, stable source. The segments inside are absolute googlevideo
     *      URLs, fetched by the player with this link's referer/headers.
     *   2. The full adaptive DASH manifest (`DASH تلقائي`) as a fallback.
     *   3. The progressive muxed MP4 renditions (`=m37`/`=m22`/`=m18`). Those
     *      suffixes are NOT fixed resolutions, so each one is probed and its real
     *      size is read straight from the MP4 `tkhd` box; renditions that do not
     *      exist (e.g. a 404 on `=m37`) are skipped.
     *
     * Google Photos googlevideo URLs answer HTTP 403 unless BOTH the `Referer`
     * and `Origin: https://photos.google.com` are sent, and they are pinned to
     * the `User-Agent` that requested the manifest — hence [PHOTO_HEADERS] carries
     * the browser UA. CloudStream forwards an [ExtractorLink]'s `referer` +
     * `headers` to every manifest/segment request, so every emitted link plays.
     */
    internal suspend fun emitGooglePhotos(
        base: String,
        label: String,
        dashHint: String?,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val dashUrl = base + DASH_OPTION

        // One independent DASH source per video rendition, served as a tiny
        // single-rendition manifest so each quality is its own stable link.
        val manifest = manifestFor(dashUrl)
        val byHeight = LinkedHashMap<Int, VideoRep>()
        if (!manifest.isNullOrBlank()) {
            for (rep in videoReps(manifest)) {
                val current = byHeight[rep.height]
                if (current == null || rep.width > current.width) byHeight[rep.height] = rep
            }
        }
        if (!manifest.isNullOrBlank() && byHeight.isNotEmpty()) {
            for ((height, rep) in byHeight.entries.sortedByDescending { it.key }) {
                if (!done.add("gq:$base:$height")) continue
                val single = filterToRep(manifest, rep.id)
                val local = PhotoDashServer.put("$base|${rep.id}", single.toByteArray(Charsets.UTF_8)) ?: continue
                found = true
                callback(
                    newExtractorLink(label, "جودة ${height}p", local, ExtractorLinkType.DASH) {
                        this.referer = PHOTO_REFERER
                        this.headers = PHOTO_HEADERS
                        this.quality = height
                    }
                )
            }
        }

        // Direct adaptive DASH manifest — a fresh URL every session so the
        // player's cache never replays a manifest with already-expired segments.
        val adaptiveUrl = freshDash(dashUrl)
        if (done.add("ga:$adaptiveUrl")) {
            found = true
            callback(
                newExtractorLink(label, "DASH تلقائي", adaptiveUrl, ExtractorLinkType.DASH) {
                    this.referer = PHOTO_REFERER
                    this.headers = PHOTO_HEADERS
                }
            )
        }

        // Direct progressive MP4 qualities (muxed video+audio).
        for (suffix in SEEKABLE) {
            val url = base + suffix
            if (!done.add(url)) continue
            val size = probe(url) ?: continue
            found = true
            callback(
                newExtractorLink(label, "MP4 ${size.second}p", url, ExtractorLinkType.VIDEO) {
                    this.referer = PHOTO_REFERER
                    this.headers = PHOTO_HEADERS
                    this.quality = size.second
                }
            )
        }
        return found
    }

    /** The current session's freshly-fetched manifest for [url] (see [manifestFor]). */
    private suspend fun manifestFor(url: String): String? {
        if (cachedManifestUrl == url) return cachedManifestBody
        val body = fetchManifest(url)
        cachedManifestUrl = url
        cachedManifestBody = body
        return body
    }

    private suspend fun fetchManifest(url: String): String? {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Accept" to "*/*",
                    "Referer" to PHOTO_REFERER,
                    "Origin" to PHOTO_ORIGIN
                ),
                allowRedirects = true
            )
            if (res.code in 200..299) res.text else null
        } catch (_: Throwable) {
            null
        }
    }

    internal data class VideoRep(val id: String, val width: Int, val height: Int)

    private val REP_BLOCK_RE = Regex("(?s)<Representation\\b[^>]*>.*?</Representation>")
    private val REP_HEIGHT_RE = Regex("\\bheight=\"(\\d+)\"")
    private val REP_WIDTH_RE = Regex("\\bwidth=\"(\\d+)\"")
    private val REP_ID_RE = Regex("\\bid=\"(\\d+)\"")

    /** Video renditions (those carrying a `height`), largest first. */
    internal fun videoReps(manifest: String): List<VideoRep> {
        val out = ArrayList<VideoRep>()
        for (m in REP_BLOCK_RE.findAll(manifest)) {
            val block = m.value
            val h = REP_HEIGHT_RE.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val id = REP_ID_RE.find(block)?.groupValues?.get(1) ?: continue
            val w = REP_WIDTH_RE.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out.add(VideoRep(id, w, h))
        }
        return out
    }

    /** Drop every video rendition except [keepId], preserving the audio rendition(s). */
    internal fun filterToRep(manifest: String, keepId: String): String =
        REP_BLOCK_RE.replace(manifest) { m ->
            val block = m.value
            if (!REP_HEIGHT_RE.containsMatchIn(block)) block
            else if (REP_ID_RE.find(block)?.groupValues?.get(1) == keepId) block
            else ""
        }

    /** (width, height) when the rendition exists and its MP4 header is readable, otherwise null. */
    private suspend fun probe(url: String): Pair<Int, Int>? {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Accept" to "*/*",
                    "Range" to "bytes=0-$HEAD_BYTES",
                    "Referer" to "https://photos.google.com/"
                ),
                allowRedirects = true
            )
            if (res.code != 200 && res.code != 206) return null
            val bytes = res.body?.byteStream()?.use { readCapped(it, HEAD_BYTES) }
            fromTkhd(bytes)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** Read width/height out of the first non-empty `tkhd` (track header) box. */
    private fun fromTkhd(data: ByteArray?): Pair<Int, Int>? {
        if (data == null || data.size < 108) return null
        val needle = byteArrayOf('t'.code.toByte(), 'k'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
        var i = indexOf(data, needle)
        while (i != -1) {
            val ver = data[i + 4].toInt() and 0xFF
            val wOff = if (ver == 1) i + 92 else i + 80
            val hOff = wOff + 4
            if (hOff + 4 <= data.size) {
                val w = readUInt32(data, wOff) ushr 16
                val h = readUInt32(data, hOff) ushr 16
                if (w in 16..7680 && h in 16..4320) return w to h
            }
            i = indexOf(data, needle, i + 1)
        }
        return null
    }

    private fun readUInt32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
                ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or
                (b[o + 3].toInt() and 0xFF)

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from
        outer@ for (i in from..(data.size - needle.size)) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------ PHP extractor chain

    /** Mirror the app's extractor chain for ANY source page; all three endpoints run and merge, deduped by URL. */
    private suspend fun emitPhpChain(
        pageUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        done: MutableSet<String>,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (endpoint in PHP_ENDPOINTS) {
            if (emitPhpServers(endpoint, pageUrl, referer, label, quality, done, emit)) found = true
        }
        return found
    }

    private suspend fun emitPhpServers(
        endpoint: String,
        pageUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        done: MutableSet<String>,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = plainHeaders() + mapOf("Referer" to referer, "Accept-Language" to "ar,en;q=0.9")
            val json = safeJson(app.get(endpoint + "?url=" + Uri.encode(pageUrl), headers = headers).text) ?: return false
            val servers = json.optJSONArray("servers") ?: return false
            var emitted = false
            val gpBases = linkedMapOf<String, String>()
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val u = s.optString("url").trim()
                val ref = s.optString("referer").ifEmpty { s.optString("origin") }.ifEmpty { referer }
                val labelName = s.optString("name").ifEmpty { label }
                val q = s.optString("height").toIntOrNull() ?: quality

                val gpBase = gphotoBase(u)
                if (gpBase != null) {
                    if (!gpBases.containsKey(gpBase)) gpBases[gpBase] = u
                    continue
                }

                if (u.isNotEmpty() && done.add(u)) {
                    emitted = true
                    when {
                        u.contains(".m3u8") -> {
                            for (v in M3u8Helper.generateM3u8(labelName, u, ref)) {
                                emit(v)
                            }
                        }

                        u.contains(".mpd") -> emit(
                            newExtractorLink(labelName, labelName, u, ExtractorLinkType.DASH) {
                                this.referer = ref
                                if (q != null) this.quality = q
                            }
                        )

                        else -> if (isVidspeedLink(u)) {
                            if (!emitVidspeed(u, labelName, emit)) {
                                emit(
                                    newExtractorLink(labelName, labelName, u, ExtractorLinkType.VIDEO) {
                                        this.referer = ref
                                        if (q != null) this.quality = q
                                    }
                                )
                            }
                        } else emit(
                            newExtractorLink(labelName, labelName, u, ExtractorLinkType.VIDEO) {
                                this.referer = ref
                                if (q != null) this.quality = q
                            }
                        )
                    }
                }

                val dl = s.optString("url_download").trim()
                if (dl.isNotEmpty() && done.add(dl)) {
                    emitted = true
                    emit(
                        newExtractorLink("$labelName (تحميل)", "$labelName (download)", dl, ExtractorLinkType.VIDEO) {
                            this.referer = ref
                        }
                    )
                }
            }
            for ((base, dashHint) in gpBases) {
                if (emitGooglePhotos(base, label, dashHint, done, emit)) emitted = true
            }
            emitted
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "emitPhpServers FAILED $endpoint : $e")
            false
        }
    }

    private fun safeJson(text: String?): JSONObject? {
        return try {
            if (text.isNullOrBlank()) return null
            val t = text.trim()
            if (!t.startsWith("{")) return null
            JSONObject(t)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Registered CloudStream extractor for raw Google Photos URLs
 * (lh3.googleusercontent.com/pw/...). Share pages are handled by the main API
 * before this is ever reached, but registering it makes direct links work too.
 */
class GooglePhotosExtractor : ExtractorApi() {
    override val name = "GooglePhotos"
    override val mainUrl = "https://lh3.googleusercontent.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = AnimeDayExtractors.gphotoBase(url) ?: return
        AnimeDayExtractors.emitGooglePhotos(base, name, url, mutableSetOf(), callback)
    }
}

/**
 * Minimal loopback HTTP server that exposes per-rendition DASH manifests.
 *
 * CloudStream's player only accepts http(s) manifest URLs (no `data:` / `file:`),
 * and its track selector ignores an [ExtractorLink]'s quality — so the only way
 * to turn each rendition into its own *source* is to serve a manifest that
 * contains only that rendition. The segments inside are absolute googlevideo
 * URLs and are therefore fetched directly by the player with the link's
 * referer/headers (which carry the browser UA and the photos Origin/Referer).
 *
 * The socket is bound to 127.0.0.1 on an ephemeral port (the same technique
 * CloudStream uses for its torrent streaming server), so nothing is exposed.
 * Casting devices cannot reach this localhost URL — those sessions fall back to
 * the direct adaptive DASH and progressive MP4 links.
 */
internal object PhotoDashServer {
    private const val TAG = "PhotoDashServer"
    private const val MAX_ENTRIES = 128

    @Volatile
    private var server: java.net.ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private val store = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    @Synchronized
    private fun ensure(): Int {
        val current = server
        if (current != null && !current.isClosed && port > 0) return port
        return try {
            val ss = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            server = ss
            port = ss.localPort
            val worker = Thread {
                while (!ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    Thread { serve(client) }.apply { isDaemon = true }.start()
                }
            }
            worker.isDaemon = true
            worker.name = TAG
            worker.start()
            port
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "loopback bind failed: $e")
            server = null
            port = 0
            0
        }
    }

    /** Publish [body] under a stable [key]; returns its loopback URL, or null when unavailable. */
    fun put(key: String, body: ByteArray): String? {
        val p = ensure()
        if (p <= 0) return null
        if (store.size >= MAX_ENTRIES && !store.containsKey(sha1(key))) store.clear()
        val token = sha1(key)
        store[token] = body
        return "http://127.0.0.1:$p/dash/$token.mpd"
    }

    private fun sha1(value: String): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        Integer.toHexString(value.hashCode())
    }

    private fun serve(sock: java.net.Socket) {
        try {
            sock.soTimeout = 8000
            val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val token = path.substringBefore('?').substringAfterLast('/').substringBefore('.')
            val body = store[token]
            val out = sock.getOutputStream()
            if (body == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/dash+xml\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
            }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }
}

