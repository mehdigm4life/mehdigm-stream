package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * VidTube (vidtube.one) embed host used by Animezid (a doodle-family host).
 * Best-effort scrape of the media URL from the embed page.
 */
class AnimezidVidTubeExtractor : ExtractorApi() {
    override var name = "VidTube"
    override var mainUrl = "https://vidtube.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (_: Exception) {
            return
        }

        val unpacked = JsUnpacker(page).unpack() ?: page

        val fileUrl = listOf(
            Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").find(unpacked)?.value,
            Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1),
            Regex("""https?://[^"'<>\s]+\.mp4[^"'<>\s]*""").find(unpacked)?.value,
            Regex("""sources\s*:\s*\[?\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
        ).firstNotNullOfOrNull { it }

        if (fileUrl.isNullOrBlank()) return

        val isM3u8 = fileUrl.contains(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = fileUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            }
        )
    }
}