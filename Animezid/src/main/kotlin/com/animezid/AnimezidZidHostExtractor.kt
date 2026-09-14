package com.animezid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimezidRpmHostExtractor : AnimezidZidHostExtractor() {
    override var name = "RPMShare"
    override var mainUrl = "https://zid.rpmhub.site"
}

class AnimezidUpnShareExtractor : AnimezidZidHostExtractor() {
    override var name = "UPNShare"
    override var mainUrl = "https://zid.upns.online"
}

class AnimezidStreamP2PExtractor : AnimezidZidHostExtractor() {
    override var name = "StreamP2P"
    override var mainUrl = "https://zid.streamcasthub.store"
}

class AnimezidEasyVidPlayExtractor : AnimezidZidHostExtractor() {
    override var name = "EasyVidPlay"
    override var mainUrl = "https://zid.vidplayer.live"
}

/**
 * "Smart player" hosts used by Animezid (RPMShare / UPNShare / StreamP2P / EasyVidPlay).
 *
 * These SPA hosts expose a single API endpoint:
 *   GET https://{domain}/api/v1/video?id={videoId}
 * which returns a hex-encoded AES-CBC blob. The plaintext JSON contains the
 * direct HLS source. Same logic used by TukTuk's SmartPlayer.
 */
open class AnimezidZidHostExtractor : ExtractorApi() {
    override var name = "RPMShare"
    override var mainUrl = "https://zid.rpmhub.site"
    override val requiresReferer = false

    private val secretKey = SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.UTF_8), "AES")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hostUrl = if (url.startsWith("//")) "https:$url" else url
        val domain = try {
            URI(hostUrl).host
        } catch (_: Exception) {
            null
        } ?: return

        val videoId = when {
            url.contains("#") -> url.substringAfterLast("#").substringBefore("&")
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            else -> return
        }
        if (videoId.isBlank()) return

        val apiUrl = "https://$domain/api/v1/video?id=$videoId"
        val apiText = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to "https://$domain/",
                    "Origin" to "https://$domain",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        } catch (_: Exception) {
            return
        }
        if (apiText.isBlank()) return

        val m3u8 = smartDecrypt(apiText, domain, videoId) ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://$domain/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun smartDecrypt(hexBlob: String, domain: String, videoId: String): String? {
        val encrypted = decodeHex(hexBlob) ?: return null

        for (iv in generateIvCandidates(domain, videoId)) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
                val decrypted = String(cipher.doFinal(encrypted), Charsets.UTF_8)

                if (!decrypted.contains("m3u8")) continue

                // Strip PKCS5 padding + any surrounding garbage: keep "{...}" JSON body.
                val start = decrypted.indexOf('{')
                val end = decrypted.lastIndexOf('}')
                if (start < 0 || end <= start) continue
                val jsonPart = decrypted.substring(start, end + 1)

                val source = extractField(jsonPart, "source")
                val cfNative = extractField(jsonPart, "cfNative")
                val cf = extractField(jsonPart, "cf")

                val m3u8 = listOfNotNull(source, cfNative, cf)
                    .firstOrNull { it.contains(".m3u8") || it.contains(".txt") }
                if (m3u8 != null) return m3u8
            } catch (_: Exception) {
                // wrong IV candidate, keep trying
            }
        }
        return null
    }

    private fun extractField(json: String, fieldName: String): String? {
        val pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\""
        val value = Regex(pattern).find(json)?.groupValues?.get(1) ?: return null
        return sanitizeUrl(value).ifBlank { null }
    }

    private fun sanitizeUrl(raw: String): String {
        // The "source" field sometimes carries a magic byte prefix before the URL
        // (e.g. "\u0001\u00c3\u008d..." + "://94.131.x.x/..."); strip anything
        // that is not part of the URL itself.
        val cleaned = raw.replace("\\/", "/")
            .trimStart { !it.isLetterOrDigit() }
            .trimStart('/')
        val url = if (
            cleaned.startsWith("http://") || cleaned.startsWith("https://")
        ) cleaned else "https://$cleaned"
        return url
    }

    private fun decodeHex(hexIn: String): ByteArray? {
        return try {
            val clean = hexIn.trim().trim('"')
                .let { if (it.length % 2 != 0) it.dropLast(1) else it }
            clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun generateIvCandidates(domain: String, videoId: String): List<ByteArray> {
        val candidates = mutableListOf<ByteArray>()
        val dOpts = mutableListOf<Int>(48, 323)
        if (domain.isNotEmpty()) {
            dOpts.add(domain.length * (domain.length + 2))
            val parts = domain.split(".")
            if (parts.size >= 2) {
                val shortDomain = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
                dOpts.add(shortDomain.length * (shortDomain.length + 2))
            }
        }
        val wOpts = mutableListOf<Int>(0, 105, 141, 189, 63)
        if (videoId.isNotEmpty()) {
            wOpts.add(3 * videoId.first().code)
        }
        for (d in dOpts.distinct()) {
            for (w in wOpts.distinct()) {
                val part1 = (1..9).map { (it + d).toChar() }.joinToString("")
                val part2 = intArrayOf(d, 111, w, 128, 132, 97, 95).map { it.toChar() }.joinToString("")
                val ivString = part1 + part2
                candidates.add(ivString.toByteArray(Charsets.UTF_8).copyOfRange(0, 16))
            }
        }
        return candidates
    }
}