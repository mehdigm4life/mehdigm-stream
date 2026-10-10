package com.stardima

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Mixdrop (mixdrop.top, redirects to mxdrop.top) extractor for StarDima.
 *
 * The `/e/<code>` embed page answers `302` to a sibling host and ships its
 * player config inside a P.A.C.K.E.R.-obfuscated script. Once decoded it reads:
 *
 *   MDCore.ref = "<code>"; ... wurl = "//a-delivery44.mxcontent.net/v2/<code>.mp4?s=..&e=.."
 *
 * The `wurl` is a progressive MP4 (token baked into the query), so it is
 * emitted as a plain [ExtractorLinkType.VIDEO] link.
 */
class MixdropExtractor : ExtractorApi() {
    override var name = "Mixdrop"
    override var mainUrl = "https://mixdrop.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = url.trim()
        val response = try {
            app.get(embed, headers = PAGE_HEADERS, referer = referer ?: "$mainUrl/")
        } catch (e: Throwable) {
            Log.i(TAG, "fetch failed ${embed.take(120)}: $e")
            return
        }
        val pageReferer = response.url.ifBlank { embed }
        val html = response.text

        val decoded = JsPacker.unpack(html)
        val raw = (decoded?.let { WURL_RE.find(it)?.groupValues?.get(1) })
            ?: WURL_RE.find(html)?.groupValues?.get(1)
            ?: return
        val streamUrl = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> raw
        }

        val type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            ExtractorLink(
                source = streamUrl,
                name = name,
                url = streamUrl,
                referer = pageReferer,
                quality = -1,
                headers = mapOf("User-Agent" to BROWSER_UA),
                type = type
            )
        )
    }

    companion object {
        private const val TAG = "StarDimaMixdrop"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        private val WURL_RE = Regex("""\bwurl\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""")
    }
}
