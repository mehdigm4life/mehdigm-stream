package com.stardima

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
 *
 * The hosts front their pages with a Cloudflare JS challenge for non-browser
 * clients, so extraction is layered (each layer is guarded and may yield
 * nothing without ever throwing):
 *  1. plain OkHttp GET of the embed page (fast path, works when CF allows);
 *  2. POST `op=embed` to `/dl`, which returns the same player page;
 *  3. WebView-based challenge solve (see [CfxSolver]) then re-fetch with the
 *     earned cookies;
 *  4. try the sibling host. If nothing produced a stream, the source is
 *     silently skipped so other sources keep working.
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
        val code = url.trim().trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return

        val givenHost = try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Throwable) {
            null
        }
        val hosts = (givenHost?.let { listOf(it) } ?: emptyList()) +
            (SIBLING_HOSTS.filterNot { it == givenHost })

        for (host in hosts.distinct()) {
            val embed = "https://$host/e/$code"
            if (extractFrom(embed, pageReferer, cookie = null, callback)) return
            if (extractFromDl(host, code, embed, pageReferer, callback)) return
            val cookie = try {
                CfxSolver.cookiesFor(embed)
            } catch (_: Throwable) {
                null
            }
            if (cookie != null) {
                if (extractFrom(embed, pageReferer, cookie, callback)) return
                if (extractFromDl(host, code, embed, pageReferer, callback, cookie)) return
            }
        }
    }

    private suspend fun extractFrom(
        embed: String,
        pageReferer: String,
        cookie: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(embed, referer = pageReferer, headers = pageHeaders(cookie)).text
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "fetch failed ${embed.take(120)}: $e")
            return false
        }
        val m3u8 = streamUrl(html) ?: return false
        return emitM3u8(m3u8, embed, callback)
    }

    private suspend fun extractFromDl(
        host: String,
        code: String,
        embed: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit,
        cookie: String? = null
    ): Boolean {
        val dl = try {
            app.post(
                "https://$host/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to code,
                    "auto" to "1",
                    "referer" to embed,
                ),
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to embed,
                    "Origin" to "https://$host",
                ) + (cookie?.let { mapOf("Cookie" to it) } ?: emptyMap()),
                referer = embed
            ).text
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "/dl failed ${embed.take(120)}: $e")
            return false
        }
        val m3u8 = streamUrl(dl) ?: return false
        return emitM3u8(m3u8, embed, callback)
    }

    /**
     * The HLS CDN (e.g. ...tnmr.org) gates every request (playlist, variants,
     * segments, AES key) behind a browser TLS fingerprint: plain OkHttp gets
     * nginx 403 no matter the headers (verified: identical request from
     * Chrome 200, from curl 403). The core's M3u8Helper validation fetch uses
     * OkHttp, so it always drops our links, while the actual exoplayer uses
     * Cronet (browser-like TLS) and plays this CDN fine.
     *
     * So we ask M3u8Helper first (for variant labels when its validation
     * works, e.g. on non-gated hosts) and fall back to emitting a direct M3U8
     * ExtractorLink without the OkHttp validation, letting the player's Cronet
     * fetch master/variants/segments/key.
     */
    private suspend fun emitM3u8(m3u8: String, embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val generated = M3u8Helper.generateM3u8(name, m3u8, embed, headers = m3u8Headers(embed))
        if (generated.isNotEmpty()) {
            generated.forEach { callback(it) }
            return true
        }
        callback(
            ExtractorLink(
                source = m3u8,
                name = name,
                url = m3u8,
                referer = embed,
                quality = -1,
                headers = m3u8Headers(embed),
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }

    private fun pageHeaders(cookie: String?): Map<String, String> =
        PAGE_HEADERS + (cookie?.let { mapOf("Cookie" to it) } ?: emptyMap())

    /**
     * The HLS CDN (e.g. ...tnmr.org) answers 403 when the master playlist is
     * requested without the embedding page as `Referer`, and the core's
     * M3u8Helper validation fetch only sends the stream headers, not its
     * referer. Carrying both Referer and the browser UA makes the validation
     * (and the ordering of emitted variants) pass, and the emitted links keep
     * the embed URL as their player referer.
     */
    private fun m3u8Headers(embed: String): Map<String, String> =
        mapOf(
            "Referer" to embed,
            "User-Agent" to BROWSER_UA
        )

    companion object {
        private const val TAG = "StarDimaLuluStream"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val SIBLING_HOSTS = listOf("lulustream.com", "luluvdo.com")

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