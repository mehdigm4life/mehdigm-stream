package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class AnimezidTurboViPlayComExtractor : AnimezidTurboViPlayExtractor() {
    override var mainUrl = "https://turboviplay.com"
}

/**
 * TurboViPlay / turbovidhls.com embed host used by Animezid.
 *
 * The direct m3u8 URL is embedded right in the page; no POST is required.
 */
open class AnimezidTurboViPlayExtractor : ExtractorApi() {
    override var name = "TurboViPlay"
    override var mainUrl = "https://turbovidhls.com"
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

        val m3u8 = Regex("""https?://[^"'<>\s]+\.(?:m3u8|txt)[^"'<>\s]*""")
            .find(unpacked)?.value ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
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