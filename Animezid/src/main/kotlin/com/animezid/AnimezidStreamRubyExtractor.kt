package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class AnimezidStreamRubyComExtractor : AnimezidStreamRubyExtractor() {
    override var mainUrl = "https://streamruby.com"
}

/**
 * StreamRuby / rubyvidhub.com embed host used by Animezid.
 *
 * Flow:
 *   GET /e/{code}          -> seeds session cookie + code
 *   POST /dl               -> op=embed&file_code={code}&auto=1&referer=...
 *   decode packed JS       -> jwplayer("vplayer").setup({sources:[{file:"...m3u8"}]})
 */
open class AnimezidStreamRubyExtractor : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubyvidhub.com"
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
        } catch (_: Exception) {
            return
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
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl),
                referer = embedUrl
            ).text
        } catch (_: Exception) {
            return
        }

        val unpacked = JsUnpacker(dlText).unpack() ?: dlText

        val streamUrl = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(unpacked)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']+)["']""")
                .find(unpacked)?.groupValues?.get(1)
                ?.takeIf { it.contains("/hls") || it.endsWith(".mp4") }

        if (streamUrl.isNullOrBlank()) return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
            }
        )
    }
}