package com.stardima

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * SaveFiles (savefiles.com) extractor for StarDima.
 *
 * The `/e/<filecode>` embed page does not ship the stream; the real player
 * config is returned by POSTing `op=embed&file_code=..&auto=1&referer=..` to
 * `/dl`. The response is a plain JWPlayer setup whose sources carry the HLS
 * master on s1.savefiles.com.
 */
class SaveFilesExtractor : ExtractorApi() {
    override var name = "SaveFiles"
    override var mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.trim().trimEnd('/')
        val fileCode = embedUrl.substringAfterLast("/")
        if (fileCode.isBlank()) return

        // Seed the session cookie used by the /dl endpoint.
        try {
            app.get(embedUrl, referer = referer ?: mainUrl)
        } catch (_: Throwable) {
            // continue; /dl may still answer
        }

        val dlText = try {
            app.post(
                "$mainUrl/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "1",
                    "referer" to embedUrl,
                ),
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                ),
                referer = embedUrl
            ).text
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "SaveFiles /dl failed ${embedUrl.take(120)}: $e")
            return
        }

        val m3u8 = M3U8_RE.find(dlText)?.groupValues?.get(1) ?: run {
            android.util.Log.i(TAG, "SaveFiles no m3u8 for ${embedUrl.take(120)}")
            return
        }

        for (link in M3u8Helper.generateM3u8(name, m3u8, embedUrl)) {
            callback(link)
        }
    }

    companion object {
        private const val TAG = "StarDimaSaveFiles"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val M3U8_RE = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
    }
}