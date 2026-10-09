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
 *    4. Fallback ............. CloudStream's global loadExtractor
 *
 *  Google Photos note: the backend exposes every quality through a single DASH
 *  manifest (`=mm,dash-vm-vf,...`). Its googlevideo segments are IP-pinned and
 *  answer HTTP 403 unless BOTH `Referer` and `Origin: https://photos.google.com`
 *  are sent — the app's own player does this. CloudStream forwards an
 *  [ExtractorLink]'s `referer` + `headers` to every manifest/segment request, so
 *  the full DASH manifest is emitted (all real renditions, audio included).
 *  The progressive `=m37` / `=m22` / `=m18` renditions are also probed and
 *  emitted as a fallback: those suffixes are NOT fixed resolutions, so each one
 *  is probed and its real size is read straight from the MP4 `tkhd` box, and
 *  renditions that do not exist (e.g. a 404 on `=m37`) are skipped.
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

    private val PHOTO_HEADERS = mapOf("Origin" to PHOTO_ORIGIN)

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

    /** Rotate the manifest nonce once per link-loading run (call from loadLinks). */
    fun newSession() {
        sessionNonce = System.currentTimeMillis().toString(36) + Integer.toHexString(Random.nextInt())
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
     * Emit the full DASH manifest (every real rendition, audio + video) plus the
     * progressive renditions that actually exist, each labelled with its true height.
     */
    internal suspend fun emitGooglePhotos(
        base: String,
        label: String,
        dashHint: String?,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val dash = freshDash(
            dashHint
                ?.takeIf { it.contains("dash", true) || it.contains("=mm") }
                ?: (base + DASH_OPTION)
        )
        if (done.add(dash)) {
            found = true
            callback(
                newExtractorLink(label, "$label (كل الجودات)", dash, ExtractorLinkType.DASH) {
                    this.referer = PHOTO_REFERER
                    this.headers = PHOTO_HEADERS
                    this.quality = 1080
                }
            )
        }

        for (suffix in SEEKABLE) {
            val url = base + suffix
            if (!done.add(url)) continue
            val size = probe(url) ?: continue
            found = true
            callback(
                newExtractorLink(label, "$label (${heightLabel(size.second)})", url, ExtractorLinkType.VIDEO) {
                    this.referer = PHOTO_REFERER
                    this.headers = PHOTO_HEADERS
                    this.quality = size.second
                }
            )
        }
        return found
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

    private fun heightLabel(h: Int): String = when {
        h >= 1000 -> "1080p"
        h >= 900 -> "960p"
        h >= 700 -> "720p"
        h >= 560 -> "640p"
        h >= 400 -> "480p"
        h >= 300 -> "360p"
        h >= 200 -> "240p"
        else -> "${h}p"
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

                        else -> emit(
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
