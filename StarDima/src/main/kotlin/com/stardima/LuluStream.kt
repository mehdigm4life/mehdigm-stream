package com.stardima

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * LuluStream (lulustream.com -> luluvdo.com) extractor for StarDima.
 *
 * The embed page ships its real player config inside a P.A.C.K.E.R.-obfuscated
 * script; once decoded it reads:
 *
 *   jwplayer("vplayer").setup({ sources: [ { file:
 *     "https://<rotating>.tnmr.org/hls2/<..>/<filecode>_h/master.m3u8?t=..&e=.." } ] })
 *
 * We decode the packer payload and hand the HLS master to [M3u8Helper]. The
 * generated segment URLs already carry their own token, so playback only needs
 * the embed page as `Referer`.
 */
open class StarDimaLuluStreamExtractor : ExtractorApi() {
    override var name = "LuluStream"
    override var mainUrl = "https://lulustream.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "$mainUrl/"
        val html = try {
            app.get(url, referer = pageReferer, headers = PAGE_HEADERS).text
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "LuluStream fetch failed ${url.take(120)}: $e")
            return
        }

        val m3u8 = streamUrl(html) ?: run {
            android.util.Log.i(TAG, "LuluStream no m3u8 in ${url.take(120)}")
            return
        }

        for (link in M3u8Helper.generateM3u8(name, m3u8, pageReferer)) {
            callback(link)
        }
    }

    companion object {
        private const val TAG = "StarDimaLuluStream"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ar,en;q=0.9"
        )

        private val PACKER_EVAL_RE = Regex(
            """(?s)\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)"""
        )
        private val PACKER_WORD_RE = Regex("""\b[a-zA-Z0-9_]+\b""")
        private val M3U8_RE = Regex("""[:=]\s*"([^"\s]+\.m3u8[^"\s]*)""")

        /** Pull the HLS master out of the embed page (decoding P.A.C.K.E.R. if needed). */
        fun streamUrl(html: String): String? {
            val decoded = unPack(html) ?: JsUnpacker(html).unpack()
            return (decoded?.let { M3U8_RE.find(it)?.groupValues?.get(1) })
                ?: M3U8_RE.find(html)?.groupValues?.get(1)
        }

        /**
         * Faithful port of the P.A.C.K.E.R. decoder: walk the payload's base-N
         * words and substitute each index that exists and is non-empty in the
         * symbol table (mirroring the packer's own `if (k[c])` guard).
         */
        private fun unPack(html: String): String? {
            val match = PACKER_EVAL_RE.find(html) ?: return null
            val payload = match.groupValues[1].replace("\\'", "'")
            val radix = match.groupValues[2].toIntOrNull() ?: return null
            val count = match.groupValues[3].toIntOrNull() ?: return null
            val symtab = match.groupValues[4].split("|")
            if (symtab.size != count) return null

            val decoded = StringBuilder(payload)
            var offset = 0
            for (word in PACKER_WORD_RE.findAll(payload)) {
                val index = word.value.toIntOrNull(radix) ?: continue
                val value = symtab.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: continue
                decoded.replace(word.range.first + offset, word.range.last + 1 + offset, value)
                offset += value.length - word.value.length
            }
            return decoded.toString()
        }
    }
}

/** lulustream.com answers `301` to luluvdo.com; cover both hosts. */
class StarDimaLuluvdoExtractor : StarDimaLuluStreamExtractor() {
    override var mainUrl = "https://luluvdo.com"
}
