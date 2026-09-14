package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

/**
 * MegaMax (megamax.me) embed host used by Animezid.
 *
 * The page loads an obfuscated "cvt" protected player (cvt-s1.agl006.host).
 * As a best effort we scrape any m3u8/mp4 manifest from the page and its
 * session script; when the DRM-ish protection is active we return nothing so
 * the broken provider is not shown to the user.
 */
class AnimezidMegaMaxExtractor : ExtractorApi() {
    override var name = "MegaMax"
    override var mainUrl = "https://megamax.me"
    override val requiresReferer = false

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

        val manifest = Regex("""https?://[^"'<>\s]+\.(?:m3u8|mp4|txt)[^"'<>\s]*""")
            .find(JsUnpacker(page).unpack() ?: page)?.value

        if (manifest.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = manifest,
                type = if (manifest.contains(".m3u8") || manifest.contains(".txt"))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
            }
        )
    }
}