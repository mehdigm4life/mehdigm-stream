package com.cimacloud

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Google Photos `/pw/` resolver shared by CimaCloud.
 *
 * Google Photos exposes a DASH manifest for every video, but its googlevideo segment
 * URLs are IP-pinned and answer HTTP 403 to third party players, so the manifest is
 * useless outside the official page. The only reliably seekable streams are the
 * progressive MP4 renditions selected with the `=m37` / `=m22` / `=m18` modifiers.
 *
 * The `mNN` suffixes are NOT fixed resolutions (itag 22 can be 480p for one video and
 * 960p for another), therefore every rendition is probed and its real resolution is
 * read straight from the MP4 `tkhd` box. Only renditions that actually exist are
 * emitted, which is what fixes both the HTTP 404 on 1080p and the lying quality names.
 */
object GooglePhotos {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

    private const val HEAD_BYTES = 128 * 1024
    private val SEEKABLE = listOf("=m37", "=m22", "=m18")

    private val PW_RE = Regex("""https://lh3\.googleusercontent\.com/pw/[^=?"'\s\\]+""")
    private val HTML_RE = Regex("""data-url="(https://lh3\.googleusercontent\.com/pw/[^\s"?]+)""")
    private val WRAP_RE = Regex("[?&]url=([^&]+)")

    fun pageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept-Language" to "ar,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    /** Bare `.../pw/<token>` base, stripped of any `=modifier` and query string. */
    fun baseOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return PW_RE.find(url.trim())?.value
    }

    fun baseOfHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val raw = HTML_RE.find(html)?.groupValues?.get(1) ?: return null
        return baseOf(raw)
    }

    /** Resolve any wrapper (googlefinal.php, photos.google.com share page, direct lh3) to the pw base. */
    suspend fun resolveBase(url: String): String? {
        val trimmed = url.trim()
        baseOf(trimmed)?.let { return it }

        WRAP_RE.find(trimmed)?.groupValues?.get(1)
            ?.let { runCatching { Uri.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != trimmed }
            ?.let { return resolveBase(it) }

        if (trimmed.contains("photos.google.com") || trimmed.contains("photos.app.goo.gl")) {
            val html = try {
                app.get(trimmed, headers = pageHeaders()).text
            } catch (_: Throwable) {
                null
            }
            return baseOfHtml(html)
        }
        return null
    }

    /** Probe every progressive rendition and emit only those that exist, labelled with their true height. */
    suspend fun emit(base: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        for (suffix in SEEKABLE) {
            val url = base + suffix
            val size = probe(url) ?: continue
            found = true
            callback(
                newExtractorLink(label, "$label (${heightLabel(size.second)})", url, ExtractorLinkType.VIDEO) {
                    this.referer = "https://photos.google.com/"
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

    /** Read width/height out of the first non-empty `tkhd` (movie header) box. */
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
}

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
        val base = GooglePhotos.resolveBase(url) ?: return
        GooglePhotos.emit(base, name, callback)
    }
}
