package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class AnimezidUqloadToExtractor : AnimezidUqloadExtractor() {
    override var mainUrl = "https://uqload.to"
}

/**
 * Uqload on the domains Animezid serves (uqload.vc / uqload.to).
 * It mirrors the same file id across uqload domains as Cloudstream's built-in
 * extractor, which only covers uqload.com / .co / .cx / .bz.
 */
open class AnimezidUqloadExtractor : ExtractorApi() {
    override var name = "Uqload"
    override var mainUrl = "https://uqload.vc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoUrl = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (_: Exception) {
            return
        }

        val unpacked = JsUnpacker(videoUrl).unpack() ?: videoUrl

        val fileUrl = listOf(
            Regex("""sources\s*:\s*\[\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1),
            Regex("""sources\s*:\s*\{[^}]*"?file"?\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1),
            Regex("""sources\s*:\s*\["[^"]*"\s*,\s*"([^"]+\.mp4[^"]*)"\s*\]""").find(unpacked)?.groupValues?.get(1),
            Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").find(unpacked)?.value
        ).firstNotNullOfOrNull { it }

        if (fileUrl.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = fileUrl,
                type = ExtractorLinkType.VIDEO
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