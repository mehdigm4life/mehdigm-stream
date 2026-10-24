package com.egydead

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// =========================================================================
// Shared Pure-Kotlin Crypto, JS Unpacker, & HLS Master Helpers
// =========================================================================

object EgyDeadExtractorKit {

    fun originOf(url: String): String? = EgyDeadNetwork.originOf(url)

    fun parseQuality(label: String?): Int {
        if (label.isNullOrBlank()) return Qualities.Unknown.value
        val m = Regex("""(\d{3,4})\s*[pP]?""").find(label)
        val num = m?.groupValues?.get(1)?.toIntOrNull()
        return when {
            num != null && num >= 2160 -> Qualities.P2160.value
            num != null && num >= 1440 -> Qualities.P1440.value
            num != null && num in 960..1150 -> Qualities.P1080.value
            num != null && num in 680..780 -> Qualities.P720.value
            num != null && num in 460..560 -> Qualities.P480.value
            num != null && num in 340..400 -> Qualities.P360.value
            num != null && num in 220..280 -> Qualities.P240.value
            num != null && num in 120..180 -> Qualities.P144.value
            label.contains("FHD", ignoreCase = true) || label.contains("Full", ignoreCase = true) -> Qualities.P1080.value
            label.contains("HD", ignoreCase = true) -> Qualities.P720.value
            label.contains("SD", ignoreCase = true) -> Qualities.P480.value
            label.contains("LOW", ignoreCase = true) -> Qualities.P360.value
            else -> num ?: Qualities.Unknown.value
        }
    }

    fun getText(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): EgyDeadNetwork.HttpResult? = runCatching {
        val safeUrl = EgyDeadNetwork.encodeUrl(url)
        val b = Request.Builder()
            .url(safeUrl)
            .header("User-Agent", EgyDeadNetwork.currentUserAgent)
            .header("Accept", "*/*")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) {
            b.header("Referer", EgyDeadNetwork.encodeUrl(referer))
        }
        headers.forEach { (k, v) -> b.header(k, v) }
        EgyDeadNetwork.httpClient.newCall(b.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val pair = sc.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            EgyDeadNetwork.HttpResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                cookies = cookies
            )
        }
    }.getOrNull()

    fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): EgyDeadNetwork.HttpResult? = runCatching {
        val safeUrl = EgyDeadNetwork.encodeUrl(url)
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val b = Request.Builder()
            .url(safeUrl)
            .post(body)
            .header("User-Agent", EgyDeadNetwork.currentUserAgent)
            .header("Accept", "*/*")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) {
            val safeRef = EgyDeadNetwork.encodeUrl(referer)
            b.header("Referer", safeRef)
            originOf(safeRef)?.let { b.header("Origin", it) }
        }
        headers.forEach { (k, v) -> b.header(k, v) }
        EgyDeadNetwork.httpClient.newCall(b.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val pair = sc.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            EgyDeadNetwork.HttpResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                cookies = cookies
            )
        }
    }.getOrNull()

    /**
     * Dean Edwards `eval(function(p,a,c,k,e,d)...)` JS unpacker supporting base 2..62.
     */
    fun unpackJs(html: String): List<String> {
        val results = mutableListOf<String>()
        val regex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        for (m in regex.findAll(html)) {
            runCatching {
                val payload = m.groupValues[1]
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val radix = m.groupValues[2].toInt()
                val count = m.groupValues[3].toInt()
                val symtab = m.groupValues[4].split('|')
                val unpacked = replacePackedWords(payload, radix, count, symtab)
                if (unpacked.isNotBlank()) results.add(unpacked)
            }
        }
        return results
    }

    private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun decodeBase(token: String, radix: Int): Int {
        if (radix <= 36) return token.toInt(radix)
        var value = 0
        for (ch in token) {
            val idx = ALPHABET_62.indexOf(ch)
            if (idx < 0 || idx >= radix) return -1
            value = value * radix + idx
        }
        return value
    }

    private fun replacePackedWords(payload: String, radix: Int, count: Int, symtab: List<String>): String {
        return Regex("""\b[0-9a-zA-Z]+\b""").replace(payload) { mr ->
            val word = mr.value
            val idx = runCatching { decodeBase(word, radix) }.getOrDefault(-1)
            if (idx in 0 until count && idx < symtab.size && symtab[idx].isNotEmpty()) {
                symtab[idx]
            } else {
                word
            }
        }
    }

    /**
     * OpenSSL `Salted__` EVP_BytesToKey (MD5) AES-256-CBC decryption (used by MegaMax `/e/`).
     */
    fun decryptOpenSslAesCbc(base64Cipher: String, passphrase: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(base64Cipher.trim())
        if (raw.size < 17) return null
        val header = String(raw, 0, 8, Charsets.US_ASCII)
        if (header != "Salted__") return null
        val salt = raw.copyOfRange(8, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)

        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < 48) {
            md5.reset()
            if (block.isNotEmpty()) md5.update(block)
            md5.update(passBytes)
            md5.update(salt)
            block = md5.digest()
            derived += block
        }
        val key = derived.copyOfRange(0, 32)
        val iv = derived.copyOfRange(32, 48)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun b64UrlDecode(s: String): ByteArray {
        var padded = s.trim().replace('-', '+').replace('_', '/')
        val rem = padded.length % 4
        if (rem != 0) padded += "=".repeat(4 - rem)
        return Base64.getDecoder().decode(padded)
    }

    fun b64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    suspend fun emitHlsWithVariants(
        masterUrl: String,
        sourceName: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
        fallbackQuality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", EgyDeadNetwork.currentUserAgent)
            if (referer.isNotBlank()) put("Referer", referer)
            originOf(referer)?.let { put("Origin", it) }
            putAll(extraHeaders)
        }

        var count = 0
        val res = getText(masterUrl, referer = referer, headers = extraHeaders)
        val body = res?.body.orEmpty()

        if (res != null && res.code in 200..299 && body.contains("#EXT-X-STREAM-INF")) {
            val baseUri = runCatching { URI(res.finalUrl) }.getOrNull()
            val lines = body.lines()
            val seenUrls = HashSet<String>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                    val nameMatch = Regex("""NAME="([^"]+)"""").find(line)
                    val height = resMatch?.groupValues?.get(1)?.toIntOrNull()
                    val q = when {
                        height != null -> parseQuality("${height}p")
                        nameMatch != null -> parseQuality(nameMatch.groupValues[1])
                        else -> fallbackQuality
                    }
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                    if (j < lines.size) {
                        val rawVariant = lines[j].trim()
                        val resolved = if (rawVariant.startsWith("http")) rawVariant
                        else baseUri?.resolve(rawVariant)?.toString() ?: rawVariant
                        if (seenUrls.add(resolved)) {
                            callback(
                                newExtractorLink(
                                    source = sourceName,
                                    name = sourceName,
                                    url = resolved,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = referer
                                    this.quality = q
                                    this.headers = headers
                                }
                            )
                            count++
                        }
                        i = j
                    }
                }
                i++
            }
        }

        if (count == 0 && (res == null || (res.code in 200..299 && body.contains("#EXTM3U")))) {
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = fallbackQuality
                    this.headers = headers
                }
            )
            count++
        }
        count
    }

    suspend fun emitDirectVideo(
        videoUrl: String,
        sourceName: String,
        referer: String,
        quality: Int = Qualities.Unknown.value,
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", EgyDeadNetwork.currentUserAgent)
            if (referer.isNotBlank()) put("Referer", referer)
            putAll(extraHeaders)
        }
        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = headers
            }
        )
    }
}

// =========================================================================
// 1. MegaMax Unified 4-Channel Extractor (q7v3k8m2p1.top / megamax.me / share4max.com)
// =========================================================================

object EgyDeadMegaMaxExtractor {
    private const val LEECH_PASSPHRASE = "k7v2m4x9bqwpnj6t"

    fun isMegaMaxUrl(url: String, serverHint: String? = null): Boolean {
        val h = runCatching { URI(EgyDeadNetwork.encodeUrl(url)).host?.lowercase() }.getOrNull().orEmpty()
        if (h.contains("megamax.me") || h.contains("share4max.com") || h.contains("q7v3k8m2p1.top")) return true
        if (serverHint?.contains("megamax", ignoreCase = true) == true) return true
        return Regex("""https?://[^/]+/(?:iframe|download)/[A-Za-z0-9_-]{11,16}(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)
    }

    fun extractHashId(url: String): String? =
        Regex("""/(?:iframe|d|download|e|f|mirror)/([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)

    private fun extractInertiaPage(html: String): JSONObject? = runCatching {
        val doc = Jsoup.parse(html)
        val scriptJson = doc.selectFirst("script[data-page=app], script[type=application/json][data-page]")
            ?.data()?.trim()
        if (!scriptJson.isNullOrBlank() && scriptJson.startsWith("{")) {
            return JSONObject(scriptJson)
        }
        val attrJson = doc.selectFirst("#app[data-page], [data-page]")?.attr("data-page")?.trim()
        if (!attrJson.isNullOrBlank() && attrJson.startsWith("{")) {
            return JSONObject(attrJson)
        }
        null
    }.getOrNull()

    private fun embedHeaders(referer: String): Map<String, String> = mapOf(
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Upgrade-Insecure-Requests" to "1"
    )

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val hashId = extractHashId(url) ?: return@coroutineScope
        val preferredOrigin = EgyDeadExtractorKit.originOf(url) ?: "https://q7v3k8m2p1.top"
        val refUrl = referer?.takeIf { it.startsWith("http") } ?: "https://tv10.egydead.live/"
        val origins = listOf(
            preferredOrigin,
            "https://q7v3k8m2p1.top",
            "https://megamax.me",
            "https://share4max.com"
        ).distinct()

        val seenMirrorUrls = Collections.synchronizedSet(HashSet<String>())

        val jobs = listOf(
            // Channel 1: Direct CDN HLS (/e/{id})
            async(Dispatchers.IO) {
                extractDirectHls(hashId, origins, refUrl, subtitleCallback, callback)
            },
            // Channel 2: Direct CDN MP4 (/d/{id})
            async(Dispatchers.IO) {
                extractDirectMp4(hashId, origins, refUrl, callback)
            },
            // Channel 3: Watch Mirrors (/iframe/{id})
            async(Dispatchers.IO) {
                extractWatchMirrors(hashId, origins, refUrl, seenMirrorUrls, subtitleCallback, callback)
            },
            // Channel 4: Multi-quality Direct & Mirror Downloads (/download/{id})
            async(Dispatchers.IO) {
                extractDownloadPage(hashId, origins, refUrl, seenMirrorUrls, subtitleCallback, callback)
            }
        )
        jobs.awaitAll()
    }

    private suspend fun extractDirectHls(
        hashId: String,
        origins: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (origin in origins) {
            val embedUrl = "$origin/e/$hashId"
            val res = EgyDeadExtractorKit.getText(embedUrl, referer = referer, headers = embedHeaders(referer)) ?: continue
            if (res.code !in 200..299) continue
            val page = extractInertiaPage(res.body) ?: continue
            if (!page.optString("component").contains("leech/video", ignoreCase = true)) continue
            val props = page.optJSONObject("props") ?: continue
            val token = props.optString("token")
            if (token.isBlank()) continue
            val m3u8 = EgyDeadExtractorKit.decryptOpenSslAesCbc(token, LEECH_PASSPHRASE)
                ?.trim()?.takeIf { it.startsWith("http") } ?: continue

            val tracks = props.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val src = t.optString("src").takeIf { it.startsWith("http") } ?: continue
                    val label = t.optString("label").ifBlank { t.optString("srclang").ifBlank { "Arabic" } }
                    runCatching { subtitleCallback(newSubtitleFile(label, src)) }
                }
            }

            EgyDeadExtractorKit.emitHlsWithVariants(
                masterUrl = m3u8,
                sourceName = "MegaMax Direct [HLS]",
                referer = "$origin/",
                callback = callback
            )
            return
        }
    }

    private suspend fun extractDirectMp4(
        hashId: String,
        origins: List<String>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        for (origin in origins) {
            val dlUrl = "$origin/d/$hashId"
            val res = EgyDeadExtractorKit.getText(dlUrl, referer = referer, headers = embedHeaders(referer)) ?: continue
            if (res.code !in 200..299) continue
            val page = extractInertiaPage(res.body) ?: continue
            if (!page.optString("component").contains("leech/download", ignoreCase = true)) continue
            val attachments = page.optJSONObject("props")?.optJSONArray("attachments") ?: continue
            var emitted = 0
            for (i in 0 until attachments.length()) {
                val item = attachments.optJSONObject(i) ?: continue
                val link = item.optString("url").ifBlank { item.optString("link") }
                if (!link.startsWith("http")) continue
                val label = item.optString("label").ifBlank { "${item.optInt("height", 0)}p" }
                val q = EgyDeadExtractorKit.parseQuality(label)
                EgyDeadExtractorKit.emitDirectVideo(
                    videoUrl = link,
                    sourceName = "MegaMax Direct [$label MP4]",
                    referer = "$origin/",
                    quality = q,
                    callback = callback
                )
                emitted++
            }
            if (emitted > 0) return
        }
    }

    private data class MirrorTask(
        val link: String,
        val driver: String,
        val quality: Int,
        val referer: String
    )

    private suspend fun extractWatchMirrors(
        hashId: String,
        origins: List<String>,
        referer: String,
        seenMirrorUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        for (origin in origins) {
            val iframeUrl = "$origin/iframe/$hashId"
            val res = EgyDeadExtractorKit.getText(iframeUrl, referer = referer, headers = embedHeaders(referer)) ?: continue
            if (res.code !in 200..299) continue
            val page = extractInertiaPage(res.body) ?: continue
            val props = page.optJSONObject("props") ?: continue
            val streamsObj = props.optJSONObject("streams")
            val dataArr = streamsObj?.optJSONArray("data") ?: props.optJSONArray("streams") ?: continue

            val tasks = mutableListOf<MirrorTask>()
            for (i in 0 until dataArr.length()) {
                val qBlock = dataArr.optJSONObject(i) ?: continue
                val qLabel = qBlock.optString("label").ifBlank { qBlock.optString("quality") }
                val qVal = EgyDeadExtractorKit.parseQuality(qLabel)
                val mirrors = qBlock.optJSONArray("mirrors") ?: qBlock.optJSONArray("servers") ?: continue
                for (j in 0 until mirrors.length()) {
                    val m = mirrors.optJSONObject(j) ?: continue
                    val rawLink = m.optString("link").ifBlank { m.optString("url") }.trim()
                    if (rawLink.isBlank()) continue
                    val fullLink = when {
                        rawLink.startsWith("//") -> "https:$rawLink"
                        rawLink.startsWith("http") -> rawLink
                        else -> continue
                    }
                    val driver = m.optString("driver").ifBlank { m.optString("name").ifBlank { "Mirror" } }
                    val dedupKey = EgyDeadMirrorRouter.canonicalDedupKey(fullLink)
                    if (seenMirrorUrls.add(dedupKey)) {
                        tasks.add(MirrorTask(fullLink, driver, qVal, iframeUrl))
                    }
                }
            }

            if (tasks.isNotEmpty()) {
                val sem = Semaphore(6)
                tasks.map { t ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            EgyDeadMirrorRouter.resolve(
                                url = t.link,
                                serverHint = t.driver,
                                qualityHint = t.quality,
                                referer = t.referer,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                }.awaitAll()
                return@coroutineScope
            }
        }
    }

    private suspend fun extractDownloadPage(
        hashId: String,
        origins: List<String>,
        referer: String,
        seenMirrorUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        for (origin in origins) {
            val downloadUrl = "$origin/download/$hashId"
            val initRes = EgyDeadExtractorKit.getText(downloadUrl, referer = referer) ?: continue
            if (initRes.code !in 200..299) continue
            val page = extractInertiaPage(initRes.body) ?: continue
            val version = page.optString("version")
            val component = page.optString("component").ifBlank { "files/mirror/download" }
            val props = page.optJSONObject("props") ?: continue
            val attachments = props.optJSONArray("attachments") ?: continue
            if (attachments.length() == 0) continue

            val cookieHeader = initRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val xsrfDecoded = initRes.cookies["XSRF-TOKEN"]?.let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }.orEmpty()

            val tasks = mutableListOf<MirrorTask>()
            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val attachId = att.optString("id").takeIf { it.isNotBlank() } ?: continue
                val qLabel = att.optString("label").ifBlank {
                    val h = att.optInt("height", 0)
                    if (h > 0) "${h}p" else ""
                }
                val qVal = EgyDeadExtractorKit.parseQuality(qLabel)

                // Direct URL directly on attachment if already populated
                val inlineDirect = att.optString("url").ifBlank { att.optString("direct_url") }
                if (inlineDirect.startsWith("http")) {
                    EgyDeadExtractorKit.emitDirectVideo(
                        videoUrl = inlineDirect,
                        sourceName = "MegaMax Download [${qLabel.ifBlank { "MP4" }}]",
                        referer = "$origin/",
                        quality = qVal,
                        callback = callback
                    )
                }

                // Inertia partial reload POST {"attach": attachId}
                val inertiaHeaders = buildMap {
                    put("X-Requested-With", "XMLHttpRequest")
                    put("X-Inertia", "true")
                    if (version.isNotBlank()) put("X-Inertia-Version", version)
                    put("X-Inertia-Partial-Component", component)
                    put("X-Inertia-Partial-Data", "download")
                    if (xsrfDecoded.isNotBlank()) put("X-XSRF-TOKEN", xsrfDecoded)
                    if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                    put("Origin", origin)
                }
                val postRes = EgyDeadNetwork.executeRawPost(
                    url = downloadUrl,
                    jsonBody = JSONObject().put("attach", attachId).toString(),
                    referer = downloadUrl,
                    extraHeaders = inertiaHeaders
                ) ?: continue
                if (postRes.code !in 200..299 || !postRes.body.trimStart().startsWith("{")) continue

                val dlProp = runCatching {
                    JSONObject(postRes.body).optJSONObject("props")?.opt("download")
                }.getOrNull() ?: continue

                when (dlProp) {
                    is String -> {
                        if (dlProp.startsWith("http")) {
                            EgyDeadExtractorKit.emitDirectVideo(
                                videoUrl = dlProp,
                                sourceName = "MegaMax Download [${qLabel.ifBlank { "MP4" }}]",
                                referer = "$origin/",
                                quality = qVal,
                                callback = callback
                            )
                        }
                    }
                    is JSONObject -> {
                        val directLink = dlProp.optString("url").ifBlank {
                            dlProp.optString("direct").ifBlank { dlProp.optString("direct_link") }
                        }
                        if (directLink.startsWith("http")) {
                            val isDirectFile = directLink.contains(".mp4", ignoreCase = true) ||
                                directLink.contains(".mkv", ignoreCase = true) ||
                                directLink.contains("cdn", ignoreCase = true) ||
                                directLink.contains("token=", ignoreCase = true)
                            if (isDirectFile) {
                                EgyDeadExtractorKit.emitDirectVideo(
                                    videoUrl = directLink,
                                    sourceName = "MegaMax Download [${qLabel.ifBlank { "MP4" }}]",
                                    referer = "$origin/",
                                    quality = qVal,
                                    callback = callback
                                )
                            } else {
                                val key = EgyDeadMirrorRouter.canonicalDedupKey(directLink)
                                if (seenMirrorUrls.add(key)) {
                                    tasks.add(MirrorTask(directLink, "MegaMaxDL", qVal, downloadUrl))
                                }
                            }
                        }

                        val mirrors = dlProp.optJSONArray("mirrors")
                            ?: dlProp.optJSONArray("data")
                            ?: dlProp.optJSONArray("links")
                        if (mirrors != null) {
                            for (j in 0 until mirrors.length()) {
                                val m = mirrors.optJSONObject(j) ?: continue
                                val rawLink = m.optString("link").ifBlank { m.optString("url") }.trim()
                                val full = when {
                                    rawLink.startsWith("//") -> "https:$rawLink"
                                    rawLink.startsWith("http") -> rawLink
                                    else -> continue
                                }
                                val drv = m.optString("driver").ifBlank { m.optString("name").ifBlank { "Mirror" } }
                                val key = EgyDeadMirrorRouter.canonicalDedupKey(full)
                                if (seenMirrorUrls.add(key)) {
                                    tasks.add(MirrorTask(full, drv, qVal, downloadUrl))
                                }
                            }
                        }
                    }
                    is JSONArray -> {
                        for (j in 0 until dlProp.length()) {
                            val m = dlProp.optJSONObject(j) ?: continue
                            val rawLink = m.optString("link").ifBlank { m.optString("url") }.trim()
                            val full = when {
                                rawLink.startsWith("//") -> "https:$rawLink"
                                rawLink.startsWith("http") -> rawLink
                                else -> continue
                            }
                            val drv = m.optString("driver").ifBlank { m.optString("name").ifBlank { "Mirror" } }
                            val key = EgyDeadMirrorRouter.canonicalDedupKey(full)
                            if (seenMirrorUrls.add(key)) {
                                tasks.add(MirrorTask(full, drv, qVal, downloadUrl))
                            }
                        }
                    }
                }
            }

            if (tasks.isNotEmpty()) {
                val sem = Semaphore(6)
                tasks.map { t ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            EgyDeadMirrorRouter.resolve(
                                url = t.link,
                                serverHint = t.driver,
                                qualityHint = t.quality,
                                referer = t.referer,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                }.awaitAll()
            }
            return@coroutineScope
        }
    }
}

// =========================================================================
// 2. StreamHG / StreamWish / HGCloud (vibuxer.com, hanerix.com, hgcloud.to, hglink.to)
// =========================================================================

object EgyDeadStreamHgExtractor {
    private val PLAYER_BASES = listOf(
        "https://hanerix.com",
        "https://vibuxer.com",
        "https://cavanhabg.com",
        "https://dumbalag.com",
        "https://uasopt.com",
        "https://dhcplay.com",
        "https://medixiru.com"
    )

    private val GATEWAY_HOSTS = setOf("hgcloud.to", "hglink.to", "streamhg.com")

    fun isStreamHgHost(host: String, serverHint: String? = null): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (serverHint?.contains("streamhg", ignoreCase = true) == true ||
            serverHint?.contains("streamwish", ignoreCase = true) == true
        ) return true
        return h in GATEWAY_HOSTS ||
            h.contains("vibuxer.") ||
            h.contains("hanerix.") ||
            h.contains("cavanhabg.") ||
            h.contains("dumbalag.") ||
            h.contains("uasopt.") ||
            h.contains("dhcplay.") ||
            h.contains("gradehgplus.") ||
            h.contains("medixiru.") ||
            h.contains("tryzendm.") ||
            h.contains("shavtape.") ||
            h.contains("haxloppd.") ||
            h.contains("streamhg.") ||
            h.contains("streamwish.") ||
            h.contains("hlswish.") ||
            h.contains("wishembed.") ||
            h.contains("sfastwish.") ||
            h.contains("awish.") ||
            h.contains("dwish.") ||
            h.contains("flaswish.") ||
            h.contains("obeywish.") ||
            h.contains("cdnwish.") ||
            h.contains("asnwish.") ||
            h.contains("jodwish.") ||
            h.contains("swhoi.")
    }

    fun extractCode(url: String): String? {
        Regex("""/(?:e|f|d|v|embed|file)/([A-Za-z0-9]{8,20})""").find(url)?.let {
            return it.groupValues[1]
        }
        return Regex("""https?://[^/]+/([A-Za-z0-9]{10,20})(?:[/?#]|$)""").find(url)?.groupValues?.get(1)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val code = extractCode(url) ?: return 0
        val incomingOrigin = EgyDeadExtractorKit.originOf(url)
        val incomingHost = runCatching { URI(EgyDeadNetwork.encodeUrl(url)).host?.lowercase()?.removePrefix("www.") }.getOrNull().orEmpty()

        val bases = buildList {
            if (incomingOrigin != null && incomingHost !in GATEWAY_HOSTS) add(incomingOrigin)
            addAll(PLAYER_BASES)
        }.distinct()

        for (base in bases) {
            val embedUrl = "$base/e/$code"
            val ref = referer ?: "https://tv10.egydead.live/"
            val res = EgyDeadExtractorKit.getText(embedUrl, referer = ref) ?: continue
            if (res.code !in 200..299 || res.body.length < 300) continue
            val activeOrigin = EgyDeadExtractorKit.originOf(res.finalUrl) ?: base

            val scripts = buildList {
                addAll(EgyDeadExtractorKit.unpackJs(res.body))
                add(res.body)
            }

            // Subtitles
            for (blob in scripts) {
                Regex("""file\s*:\s*["'](https?://[^"']+\.(?:vtt|srt)[^"']*)["']\s*,\s*label\s*:\s*["']([^"']+)["']""")
                    .findAll(blob).forEach { m ->
                        val subUrl = m.groupValues[1]
                        val subLabel = m.groupValues[2]
                        if (!subLabel.contains("thumbnail", ignoreCase = true)) {
                            runCatching { subtitleCallback(newSubtitleFile(subLabel, subUrl)) }
                        }
                    }
            }

            val m3u8Candidates = LinkedHashSet<String>()
            for (blob in scripts) {
                val linksObjMatch = Regex("""(?:var|let|const)\s+links\s*=\s*(\{[^}]+\})""").find(blob)
                if (linksObjMatch != null) {
                    runCatching {
                        val jo = JSONObject(linksObjMatch.groupValues[1])
                        for (key in listOf("hls4", "hls2", "hls3")) {
                            val raw = jo.optString(key).trim()
                            if (raw.isNotBlank() && (raw.contains(".m3u8") || raw.contains("/stream/"))) {
                                val resolved = when {
                                    raw.startsWith("http") -> raw
                                    raw.startsWith("//") -> "https:$raw"
                                    raw.startsWith("/") -> "$activeOrigin$raw"
                                    else -> "$activeOrigin/$raw"
                                }
                                m3u8Candidates.add(resolved)
                            }
                        }
                    }
                }
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(blob).forEach { m ->
                    val raw = m.groupValues[1].trim()
                    val resolved = when {
                        raw.startsWith("http") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("/") -> "$activeOrigin$raw"
                        else -> "$activeOrigin/$raw"
                    }
                    m3u8Candidates.add(resolved)
                }
            }

            for (m3u8 in m3u8Candidates) {
                val count = EgyDeadExtractorKit.emitHlsWithVariants(
                    masterUrl = m3u8,
                    sourceName = "StreamHG",
                    referer = "$activeOrigin/",
                    fallbackQuality = qualityHint,
                    callback = callback
                )
                if (count > 0) return count
            }
        }
        return 0
    }
}

// =========================================================================
// 3. EarnVids / VidHide (morencius.com/file/..., smoothpre.com, movearnpre.com)
// =========================================================================

object EgyDeadEarnVidsExtractor {
    private val CANDIDATE_BASES = listOf(
        "https://morencius.com",
        "https://smoothpre.com",
        "https://movearnpre.com",
        "https://dintezuvio.com",
        "https://minochinos.com",
        "https://dingtezuni.com"
    )

    fun isEarnVidsHost(host: String, serverHint: String? = null): Boolean {
        val h = host.lowercase()
        if (serverHint?.contains("earnvids", ignoreCase = true) == true ||
            serverHint?.contains("vidhide", ignoreCase = true) == true
        ) return true
        return h.contains("morencius.") ||
            h.contains("smoothpre.") ||
            h.contains("movearnpre.") ||
            h.contains("dintezuvio.") ||
            h.contains("minochinos.") ||
            h.contains("dingtezuni.") ||
            h.contains("earnvids.") ||
            h.contains("vidhide") ||
            h.contains("vidroba.") ||
            h.contains("callistanise.") ||
            h.contains("ryderjet.")
    }

    fun extractCode(url: String): String? {
        Regex("""/(?:file|v|e|embed|d|f)/([A-Za-z0-9]{8,20})""").find(url)?.let {
            return it.groupValues[1]
        }
        return Regex("""https?://[^/]+/([A-Za-z0-9]{10,20})(?:[/?#]|$)""").find(url)?.groupValues?.get(1)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val code = extractCode(url) ?: return 0
        val incomingOrigin = EgyDeadExtractorKit.originOf(url)
        val bases = buildList {
            if (incomingOrigin != null) add(incomingOrigin)
            addAll(CANDIDATE_BASES)
        }.distinct()

        for (base in bases) {
            val embedUrl = "$base/embed/$code"
            val res = EgyDeadExtractorKit.getText(embedUrl, referer = referer ?: "$base/") ?: continue
            if (res.code !in 200..299 || res.body.length < 300) continue
            val activeOrigin = EgyDeadExtractorKit.originOf(res.finalUrl) ?: base

            val scripts = buildList {
                addAll(EgyDeadExtractorKit.unpackJs(res.body))
                add(res.body)
            }

            val m3u8Candidates = LinkedHashSet<String>()
            for (blob in scripts) {
                Regex("""file\s*:\s*["'](https?://[^"']+\.(?:vtt|srt)[^"']*)["']\s*,\s*label\s*:\s*["']([^"']+)["']""")
                    .findAll(blob).forEach { m ->
                        if (!m.groupValues[2].contains("thumbnail", ignoreCase = true)) {
                            runCatching { subtitleCallback(newSubtitleFile(m.groupValues[2], m.groupValues[1])) }
                        }
                    }

                val linksObjMatch = Regex("""(?:var|let|const)\s+links\s*=\s*(\{[^}]+\})""").find(blob)
                if (linksObjMatch != null) {
                    runCatching {
                        val jo = JSONObject(linksObjMatch.groupValues[1])
                        for (key in listOf("hls4", "hls2", "hls3")) {
                            val raw = jo.optString(key).trim()
                            if (raw.isNotBlank() && (raw.contains(".m3u8") || raw.contains("/stream/"))) {
                                val resolved = when {
                                    raw.startsWith("http") -> raw
                                    raw.startsWith("//") -> "https:$raw"
                                    raw.startsWith("/") -> "$activeOrigin$raw"
                                    else -> "$activeOrigin/$raw"
                                }
                                m3u8Candidates.add(resolved)
                            }
                        }
                    }
                }
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(blob).forEach { m ->
                    val raw = m.groupValues[1].trim()
                    val resolved = when {
                        raw.startsWith("http") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("/") -> "$activeOrigin$raw"
                        else -> "$activeOrigin/$raw"
                    }
                    m3u8Candidates.add(resolved)
                }
            }

            for (m3u8 in m3u8Candidates) {
                val count = EgyDeadExtractorKit.emitHlsWithVariants(
                    masterUrl = m3u8,
                    sourceName = "EarnVids",
                    referer = "$activeOrigin/",
                    fallbackQuality = qualityHint,
                    callback = callback
                )
                if (count > 0) return count
            }
        }
        return 0
    }
}

// =========================================================================
// 4. Vidstack AES-128-CBC (UPNShare / StreamP2P / RPMShare)
// =========================================================================

object EgyDeadVidstackExtractor {
    private val KEY_BYTES = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
    private val IV_BYTES = "1234567890oiuytr".toByteArray(Charsets.UTF_8)

    fun isVidstackHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("upns.pro") ||
            h.contains("upnshare") ||
            h.contains("streamp2p") ||
            h.contains("p2pstream") ||
            h.contains("rpmplay") ||
            h.contains("rpmshare") ||
            h.contains("rpmvid")
    }

    suspend fun extract(
        url: String,
        sourceLabel: String,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val origin = EgyDeadExtractorKit.originOf(url) ?: return 0
        val videoId = url.substringAfter('#', "").trim().ifBlank {
            Regex("""/(?:e|v|embed)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1).orEmpty()
        }
        if (videoId.isBlank()) return 0

        val apiRes = EgyDeadExtractorKit.getText(
            url = "$origin/api/v1/video?id=$videoId",
            referer = "$origin/#$videoId"
        ) ?: return 0
        val hexCipher = apiRes.body.trim().trim('"')
        if (apiRes.code !in 200..299 || hexCipher.length < 32 || !hexCipher.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return 0
        }

        val decryptedJson = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY_BYTES, "AES"), IvParameterSpec(IV_BYTES))
            String(cipher.doFinal(EgyDeadExtractorKit.hexToBytes(hexCipher)), Charsets.UTF_8)
        }.getOrNull() ?: return 0

        val obj = runCatching { JSONObject(decryptedJson) }.getOrNull() ?: return 0
        val rawSource = obj.optString("source").ifBlank { obj.optString("hls") }.trim()
        if (rawSource.isBlank()) return 0
        val m3u8Url = if (rawSource.startsWith("http")) rawSource else "$origin/${rawSource.trimStart('/')}"

        obj.optJSONArray("subtitle")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val subSrc = s.optString("src").trim()
                if (subSrc.isNotBlank()) {
                    val fullSub = if (subSrc.startsWith("http")) subSrc else "$origin/${subSrc.trimStart('/')}"
                    val name = s.optString("name").ifBlank { s.optString("language").ifBlank { "Arabic" } }
                    runCatching { subtitleCallback(newSubtitleFile(name, fullSub)) }
                }
            }
        }

        return EgyDeadExtractorKit.emitHlsWithVariants(
            masterUrl = m3u8Url,
            sourceName = sourceLabel,
            referer = "$origin/",
            fallbackQuality = qualityHint,
            callback = callback
        )
    }
}

// =========================================================================
// 5. Byse Pure-Kotlin JCA ECDSA P-256 + PoW + AES-256-GCM
// =========================================================================

object EgyDeadByseExtractor {
    fun isByseHost(host: String): Boolean {
        val h = host.lowercase()
        return h.startsWith("byse") || h.contains(".byse") || h.contains("f16px.")
    }

    suspend fun extract(
        url: String,
        referer: String?,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val origin = EgyDeadExtractorKit.originOf(url) ?: return@withContext 0
        val code = Regex("""/(?:e|d|v|embed)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return@withContext 0
        val embedUrl = "$origin/e/$code"

        val detailsRes = EgyDeadExtractorKit.getText(
            url = "$origin/api/videos/$code/embed/details",
            referer = referer ?: embedUrl
        ) ?: return@withContext 0
        if (detailsRes.code !in 200..299) return@withContext 0
        val details = runCatching { JSONObject(detailsRes.body) }.getOrNull() ?: return@withContext 0
        val embedFrameUrl = details.optString("embed_frame_url").takeIf { it.startsWith("http") } ?: embedUrl
        val frameOrigin = EgyDeadExtractorKit.originOf(embedFrameUrl) ?: origin

        // Generate ephemeral ECDSA P-256 keypair via JCA
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val pub = kp.public as ECPublicKey
        val xBytes = toFixed32(pub.w.affineX)
        val yBytes = toFixed32(pub.w.affineY)
        val rawUncompressedPub = ByteArray(65).apply {
            this[0] = 0x04
            System.arraycopy(xBytes, 0, this, 1, 32)
            System.arraycopy(yBytes, 0, this, 33, 32)
        }
        val pubB64Url = EgyDeadExtractorKit.b64UrlEncode(rawUncompressedPub)

        // Challenge request
        val chalRes = EgyDeadNetwork.executeRawPost(
            url = "$frameOrigin/api/videos/$code/embed/playback/challenge",
            jsonBody = JSONObject().put("public_key", pubB64Url).toString(),
            referer = embedFrameUrl
        ) ?: return@withContext 0
        if (chalRes.code !in 200..299) return@withContext 0
        val chalObj = runCatching { JSONObject(chalRes.body) }.getOrNull() ?: return@withContext 0
        val challengeId = chalObj.optString("challenge_id").takeIf { it.isNotBlank() } ?: return@withContext 0
        val nonce = chalObj.optString("nonce")
        val difficulty = chalObj.optInt("difficulty", 14)
        val grToken = chalObj.optString("gr")

        // Solve SHA-256 Proof-of-Work if required
        val powSalt = solveLeadingZeroBitsPow(challengeId, nonce, difficulty)

        // Sign attestation message "$challengeId.$nonce.$powSalt" with ECDSA P-256 (IEEE P1363 r||s)
        val message = "$challengeId.$nonce.$powSalt"
        val derSig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private)
            update(message.toByteArray(Charsets.UTF_8))
        }.sign()
        val p1363Sig = derToP1363(derSig)
        val sigB64Url = EgyDeadExtractorKit.b64UrlEncode(p1363Sig)

        val playbackHeaders = buildMap {
            put("X-Embed-Origin", URI(EgyDeadNetwork.encodeUrl(referer ?: "https://tv10.egydead.live/")).host ?: "tv10.egydead.live")
            put("X-Embed-Parent", embedUrl)
            put("X-Playback-Attestation", "$challengeId.$powSalt.$sigB64Url")
            if (grToken.isNotBlank()) put("X-Playback-Gr", solveGrHeader(grToken))
        }

        val pbRes = EgyDeadExtractorKit.getText(
            url = "$frameOrigin/api/videos/$code/embed/playback",
            referer = embedFrameUrl,
            headers = playbackHeaders
        ) ?: return@withContext 0
        if (pbRes.code !in 200..299) return@withContext 0
        val pbJson = runCatching { JSONObject(pbRes.body) }.getOrNull() ?: return@withContext 0
        val playbackObj = pbJson.optJSONObject("playback") ?: pbJson

        val decryptedStr = decryptBysePlayback(playbackObj) ?: return@withContext 0
        val decObj = runCatching { JSONObject(decryptedStr) }.getOrNull() ?: return@withContext 0
        val sources = decObj.optJSONArray("sources") ?: return@withContext 0

        var total = 0
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val m3u8 = s.optString("url").ifBlank { s.optString("file") }.trim()
            if (!m3u8.startsWith("http")) continue
            val q = EgyDeadExtractorKit.parseQuality(s.optString("label").ifBlank { s.optString("quality") })
                .let { if (it == Qualities.Unknown.value) qualityHint else it }
            total += EgyDeadExtractorKit.emitHlsWithVariants(
                masterUrl = m3u8,
                sourceName = "Byse",
                referer = "$frameOrigin/",
                fallbackQuality = q,
                callback = callback
            )
        }
        total
    }

    private fun toFixed32(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { System.arraycopy(raw, 0, it, 32 - raw.size, raw.size) }
        }
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var idx = 2
        if (der[1].toInt() and 0x80 != 0) idx += (der[1].toInt() and 0x7F)
        idx++ // 0x02
        val rLen = der[idx++].toInt() and 0xFF
        val r = BigInteger(1, der.copyOfRange(idx, idx + rLen))
        idx += rLen
        idx++ // 0x02
        val sLen = der[idx++].toInt() and 0xFF
        val s = BigInteger(1, der.copyOfRange(idx, idx + sLen))
        return toFixed32(r) + toFixed32(s)
    }

    private fun solveLeadingZeroBitsPow(challengeId: String, nonce: String, difficulty: Int): String {
        if (difficulty <= 0) return "0"
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = "$challengeId.$nonce."
        var counter = 0L
        while (counter < 5_000_000L) {
            val candidate = counter.toString(36)
            val digest = md.digest((prefix + candidate).toByteArray(Charsets.UTF_8))
            if (hasLeadingZeroBits(digest, difficulty)) return candidate
            counter++
        }
        return "0"
    }

    private fun hasLeadingZeroBits(bytes: ByteArray, bits: Int): Boolean {
        val fullBytes = bits / 8
        val remBits = bits % 8
        for (i in 0 until fullBytes) {
            if (bytes[i].toInt() != 0) return false
        }
        if (remBits > 0) {
            val mask = (0xFF shl (8 - remBits)) and 0xFF
            if ((bytes[fullBytes].toInt() and mask) != 0) return false
        }
        return true
    }

    private fun solveGrHeader(grToken: String): String = runCatching {
        val parts = grToken.split('.')
        if (parts.size < 2) return grToken
        val payload = JSONObject(String(EgyDeadExtractorKit.b64UrlDecode(parts[0]), Charsets.UTF_8))
        val n = payload.optString("n")
        val diff = payload.optInt("d", 12)
        val md = MessageDigest.getInstance("SHA-256")
        var c = 0L
        while (c < 2_000_000L) {
            val cand = c.toString(36)
            if (hasLeadingZeroBits(md.digest("$n.$cand".toByteArray(Charsets.UTF_8)), diff)) {
                return "$grToken.$cand"
            }
            c++
        }
        grToken
    }.getOrDefault(grToken)

    private fun decryptBysePlayback(pb: JSONObject): String? = runCatching {
        val iv = EgyDeadExtractorKit.b64UrlDecode(pb.getString("iv"))
        val payload = EgyDeadExtractorKit.b64UrlDecode(pb.getString("payload"))
        val keyParts = pb.optJSONArray("key_parts")
        val aesKey = if (keyParts != null && keyParts.length() >= 2) {
            EgyDeadExtractorKit.b64UrlDecode(keyParts.getString(0)) +
                EgyDeadExtractorKit.b64UrlDecode(keyParts.getString(1))
        } else {
            EgyDeadExtractorKit.b64UrlDecode(pb.getString("key"))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrNull()
}

// =========================================================================
// 6. FireStream, Vinovo, Vidara, Mixdrop, DoodStream, KoramaUp, Ok.ru, Vidoza,
//    VOE, LuluStream, SaveFiles, Uqload, Mp4Upload, MediaFire, VK, Vids.st, etc.
// =========================================================================

object EgyDeadFireStreamExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val origin = EgyDeadExtractorKit.originOf(url) ?: return 0
        val hash = url.trimEnd('/').substringAfterLast('/')
        if (hash.length < 8) return 0
        val res = EgyDeadExtractorKit.postForm(
            url = "$origin/player/api.php?data=$hash&do=getVideo",
            form = mapOf("hash" to hash, "r" to ""),
            referer = url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ) ?: return 0
        if (res.code !in 200..299) return 0
        val m3u8 = runCatching { JSONObject(res.body).optString("videoSource") }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: return 0
        return EgyDeadExtractorKit.emitHlsWithVariants(
            masterUrl = m3u8,
            sourceName = "FireStream",
            referer = "$origin/",
            fallbackQuality = qualityHint,
            callback = callback
        )
    }
}

object EgyDeadVinovoExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val origin = EgyDeadExtractorKit.originOf(url) ?: return 0
        val embedRes = EgyDeadExtractorKit.getText(url, referer = "https://tv10.egydead.live/") ?: return 0
        val fileCode = Regex("""data-file=["']([A-Za-z0-9_-]+)["']""").find(embedRes.body)?.groupValues?.get(1)
            ?: Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return 0
        val tokenRes = EgyDeadExtractorKit.postForm(
            url = "$origin/api/file/token",
            form = mapOf("file_code" to fileCode),
            referer = url
        ) ?: return 0
        val token = runCatching { JSONObject(tokenRes.body).optString("token") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return 0
        val streamUrl = "$origin/api/file/stream/$token"
        EgyDeadExtractorKit.emitDirectVideo(
            videoUrl = streamUrl,
            sourceName = "Vinovo",
            referer = "$origin/",
            quality = qualityHint,
            callback = callback
        )
        return 1
    }
}

object EgyDeadVidaraExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val origin = EgyDeadExtractorKit.originOf(url) ?: return 0
        val code = Regex("""/(?:e|v|d)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return 0
        val res = EgyDeadNetwork.executeRawPost(
            url = "$origin/api/stream",
            jsonBody = JSONObject().put("filecode", code).toString(),
            referer = "$origin/e/$code"
        ) ?: return 0
        if (res.code !in 200..299) return 0
        val m3u8 = runCatching { JSONObject(res.body).optString("streaming_url") }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: return 0
        return EgyDeadExtractorKit.emitHlsWithVariants(
            masterUrl = m3u8,
            sourceName = "Vidara",
            referer = "$origin/",
            fallbackQuality = qualityHint,
            callback = callback
        )
    }
}

object EgyDeadMixdropExtractor {
    fun isMixdropHost(host: String, serverHint: String? = null): Boolean {
        val h = host.lowercase()
        if (serverHint?.contains("mixdrop", ignoreCase = true) == true) return true
        return h.contains("mixdrop.") ||
            h.contains("m1xdrop.") ||
            h.contains("mdy48tn97.") ||
            h.contains("mdbekjwqa.") ||
            h.contains("mdfx9dc8n.") ||
            h.contains("mdzsmutpcvykb.")
    }

    fun extractCode(url: String): String? =
        Regex("""/(?:e|f)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)

    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val code = extractCode(url) ?: return 0
        val incomingHost = runCatching { URI(EgyDeadNetwork.encodeUrl(url)).host }.getOrNull()
        val hosts = listOfNotNull(
            incomingHost,
            "mixdrop.top",
            "mixdrop.ps",
            "mixdrop.ag",
            "mixdrop.co",
            "mixdrop.to"
        ).distinct()

        for (h in hosts) {
            val embedUrl = "https://$h/e/$code"
            val res = EgyDeadExtractorKit.getText(embedUrl, referer = "https://$h/") ?: continue
            if (res.code !in 200..299) continue
            val activeOrigin = EgyDeadExtractorKit.originOf(res.finalUrl) ?: "https://$h"
            val unpacked = EgyDeadExtractorKit.unpackJs(res.body)
            for (u in unpacked) {
                val m = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(u) ?: continue
                val raw = m.groupValues[1].trim()
                val videoUrl = if (raw.startsWith("//")) "https:$raw" else raw
                if (videoUrl.startsWith("http")) {
                    EgyDeadExtractorKit.emitDirectVideo(
                        videoUrl = videoUrl,
                        sourceName = "Mixdrop",
                        referer = "$activeOrigin/",
                        quality = qualityHint,
                        callback = callback
                    )
                    return 1
                }
            }
        }
        return 0
    }
}

object EgyDeadDoodExtractor {
    fun isDoodHost(host: String, serverHint: String? = null): Boolean {
        val h = host.lowercase()
        if (serverHint?.contains("dood", ignoreCase = true) == true) return true
        return h.contains("dood") ||
            h.contains("d000") ||
            h.contains("ds2play") ||
            h.contains("dsvplay") ||
            h.contains("myvidplay") ||
            h.contains("playmogo") ||
            h.contains("vide0.net") ||
            h.contains("vvide0.com") ||
            h.contains("do7go.")
    }

    fun extractCode(url: String): String? =
        Regex("""/(?:e|d)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)

    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val code = extractCode(url) ?: return 0
        val incomingOrigin = EgyDeadExtractorKit.originOf(url) ?: "https://playmogo.com"
        val bases = listOf(incomingOrigin, "https://myvidplay.com", "https://dsvplay.com").distinct()

        for (base in bases) {
            val res = EgyDeadExtractorKit.getText("$base/e/$code", referer = "$base/") ?: continue
            if (res.code !in 200..299) continue
            val activeOrigin = EgyDeadExtractorKit.originOf(res.finalUrl) ?: base
            val passMatch = Regex("""/pass_md5/([^'"\s]+)""").find(res.body) ?: continue
            val passPath = "/pass_md5/${passMatch.groupValues[1]}"
            val token = passMatch.groupValues[1].substringAfterLast('/')
            val md5Res = EgyDeadExtractorKit.getText("$activeOrigin$passPath", referer = res.finalUrl) ?: continue
            val prefix = md5Res.body.trim()
            if (!prefix.startsWith("http")) continue
            val rand = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val finalMp4 = "$prefix$rand?token=$token&expiry=${System.currentTimeMillis()}"
            EgyDeadExtractorKit.emitDirectVideo(
                videoUrl = finalMp4,
                sourceName = "DoodStream",
                referer = "$activeOrigin/",
                quality = qualityHint,
                callback = callback
            )
            return 1
        }
        return 0
    }
}

object EgyDeadKoramaUpExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val step1 = EgyDeadExtractorKit.getText(url, referer = "https://tv10.egydead.live/") ?: return 0
        if (step1.code !in 200..299) return 0
        val doc1 = Jsoup.parse(step1.body, step1.finalUrl)
        val form1 = doc1.selectFirst("form:has(input[name=op])") ?: return 0
        val fields1 = LinkedHashMap<String, String>()
        for (inp in form1.select("input[name]")) {
            val name = inp.attr("name")
            if (inp.attr("type").equals("submit", ignoreCase = true) && name != "method_free") continue
            fields1[name] = inp.attr("value")
        }
        fields1["method_free"] = fields1["method_free"]?.ifBlank { "Free Download" } ?: "Free Download"

        val step2 = EgyDeadExtractorKit.postForm(step1.finalUrl, fields1, referer = step1.finalUrl) ?: return 0
        val doc2 = Jsoup.parse(step2.body, step2.finalUrl)

        doc2.selectFirst("a#direct_link[href], a[href*=\"/d/\"], a.btn-download[href]")?.attr("abs:href")
            ?.takeIf { it.startsWith("http") && !it.endsWith(url.substringAfterLast('/')) }
            ?.let { dl ->
                EgyDeadExtractorKit.emitDirectVideo(dl, "KoramaUp", step2.finalUrl, qualityHint, callback = callback)
                return 1
            }

        val form2 = doc2.selectFirst("form[name=F1], form:has(input[name=op][value=download2])") ?: return 0
        val fields2 = LinkedHashMap<String, String>()
        for (inp in form2.select("input[name]")) {
            fields2[inp.attr("name")] = inp.attr("value")
        }
        val step3 = EgyDeadExtractorKit.postForm(step2.finalUrl, fields2, referer = step2.finalUrl) ?: return 0
        val doc3 = Jsoup.parse(step3.body, step3.finalUrl)
        val finalUrl = doc3.select("a[href]").firstOrNull { a ->
            val h = a.attr("abs:href")
            h.contains(".mp4", ignoreCase = true) || h.contains(".mkv", ignoreCase = true) || a.id() == "direct_link"
        }?.attr("abs:href") ?: Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv)(?:\?[^\s"'<>]*)?""")
            .find(step3.body)?.value ?: return 0

        EgyDeadExtractorKit.emitDirectVideo(finalUrl, "KoramaUp", step3.finalUrl, qualityHint, callback = callback)
        return 1
    }
}

object EgyDeadOkRuExtractor {
    private val OK_QUALITY_MAP = mapOf(
        "ultra" to Qualities.P2160.value,
        "quad" to Qualities.P1440.value,
        "full" to Qualities.P1080.value,
        "hd" to Qualities.P720.value,
        "sd" to Qualities.P480.value,
        "low" to Qualities.P360.value,
        "lowest" to Qualities.P240.value,
        "mobile" to Qualities.P144.value
    )

    suspend fun extract(url: String, callback: (ExtractorLink) -> Unit): Int {
        val embedUrl = if (url.contains("/videoembed/")) url
        else {
            val id = Regex("""(?:video|videoembed)/(\d+)""").find(url)?.groupValues?.get(1) ?: return 0
            "https://ok.ru/videoembed/$id"
        }
        val res = EgyDeadExtractorKit.getText(embedUrl, referer = "https://ok.ru/") ?: return 0
        if (res.code !in 200..299) return 0
        val doc = Jsoup.parse(res.body, res.finalUrl)
        val optsRaw = doc.selectFirst("div[data-options]")?.attr("data-options") ?: return 0
        val flashvars = runCatching { JSONObject(optsRaw).optJSONObject("flashvars") }.getOrNull() ?: return 0
        val metadataStr = flashvars.optString("metadata")
        val metadata = runCatching { JSONObject(metadataStr) }.getOrNull() ?: return 0

        var count = 0
        val videos = metadata.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val vUrl = v.optString("url").takeIf { it.startsWith("http") } ?: continue
                val name = v.optString("name").lowercase()
                val q = OK_QUALITY_MAP[name] ?: Qualities.Unknown.value
                EgyDeadExtractorKit.emitDirectVideo(
                    videoUrl = vUrl,
                    sourceName = "Ok.ru",
                    referer = "https://ok.ru/",
                    quality = q,
                    callback = callback
                )
                count++
            }
        }
        val hlsUrl = metadata.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
        if (hlsUrl != null && count == 0) {
            count += EgyDeadExtractorKit.emitHlsWithVariants(
                masterUrl = hlsUrl,
                sourceName = "Ok.ru [HLS]",
                referer = "https://ok.ru/",
                callback = callback
            )
        }
        return count
    }
}

object EgyDeadVidozaExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val res = EgyDeadExtractorKit.getText(url, referer = "https://tv10.egydead.live/") ?: return 0
        if (res.code !in 200..299) return 0
        val origin = EgyDeadExtractorKit.originOf(res.finalUrl) ?: "https://vidoza.net"
        var count = 0
        Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["'](?:\s*,\s*type\s*:[^,]+)?(?:\s*,\s*label\s*:\s*["']([^"']*)["'])?(?:\s*,\s*res\s*:\s*["']?(\d+)["']?)?""")
            .findAll(res.body).forEach { m ->
                val mp4 = m.groupValues[1]
                val qLabel = m.groupValues[3].ifBlank { m.groupValues[2] }
                val q = EgyDeadExtractorKit.parseQuality(qLabel).let {
                    if (it == Qualities.Unknown.value) qualityHint else it
                }
                EgyDeadExtractorKit.emitDirectVideo(mp4, "Vidoza", "$origin/", q, callback = callback)
                count++
            }
        if (count == 0) {
            Jsoup.parse(res.body).select("source[src*=.mp4]").forEach { s ->
                val mp4 = s.attr("src").takeIf { it.startsWith("http") } ?: return@forEach
                val q = EgyDeadExtractorKit.parseQuality(s.attr("res").ifBlank { s.attr("label") })
                EgyDeadExtractorKit.emitDirectVideo(mp4, "Vidoza", "$origin/", q, callback = callback)
                count++
            }
        }
        return count
    }
}

object EgyDeadVoeExtractor {
    fun isVoeHost(host: String, serverHint: String? = null): Boolean {
        val h = host.lowercase()
        if (serverHint?.contains("voe", ignoreCase = true) == true) return true
        return h.contains("voe.sx") ||
            h.contains("jilliandescribecompany.") ||
            h.contains("christopheruntilpoint.") ||
            h.contains("dianaavoidthey.") ||
            h.contains("walterprettytheir.") ||
            h.contains("mikaylaarealike.")
    }

    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        var res = EgyDeadExtractorKit.getText(url, referer = "https://tv10.egydead.live/") ?: return 0
        val jsRedirect = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(res.body)?.groupValues?.get(1)
        if (jsRedirect != null) {
            res = EgyDeadExtractorKit.getText(jsRedirect, referer = url) ?: return 0
        }
        val origin = EgyDeadExtractorKit.originOf(res.finalUrl) ?: "https://voe.sx"

        // Modern VOE obfuscated JSON inside <script type="application/json">
        val doc = Jsoup.parse(res.body)
        for (sc in doc.select("script[type=application/json]")) {
            val decoded = decodeVoePayload(sc.data().trim()) ?: continue
            val hls = decoded.optString("source").takeIf { it.startsWith("http") }
            if (hls != null) {
                return EgyDeadExtractorKit.emitHlsWithVariants(hls, "VOE", "$origin/", fallbackQuality = qualityHint, callback = callback)
            }
        }

        val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(res.body)?.groupValues?.get(1)
        if (hlsMatch != null) {
            val hlsUrl = if (hlsMatch.startsWith("http")) hlsMatch
            else runCatching { String(Base64.getDecoder().decode(hlsMatch), Charsets.UTF_8) }.getOrDefault("")
            if (hlsUrl.startsWith("http")) {
                return EgyDeadExtractorKit.emitHlsWithVariants(hlsUrl, "VOE", "$origin/", fallbackQuality = qualityHint, callback = callback)
            }
        }
        return 0
    }

    private fun decodeVoePayload(raw: String): JSONObject? = runCatching {
        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        var s = rot13(arr.getString(0))
        for (pat in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            s = s.replace(pat, "_")
        }
        s = s.replace("_", "")
        val step1 = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
        val shifted = buildString(step1.length) {
            for (c in step1) append((c.code - 3).toChar())
        }.reversed()
        val finalJson = String(Base64.getDecoder().decode(shifted), Charsets.UTF_8)
        JSONObject(finalJson)
    }.getOrNull()

    private fun rot13(input: String): String = buildString(input.length) {
        for (c in input) {
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                }
            )
        }
    }
}

object EgyDeadLuluStreamExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val origin = EgyDeadExtractorKit.originOf(url) ?: return 0
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return 0
        val embedUrl = "$origin/e/$code"
        val res = EgyDeadExtractorKit.getText(embedUrl, referer = "$origin/") ?: return 0
        val blobs = EgyDeadExtractorKit.unpackJs(res.body) + res.body
        for (b in blobs) {
            val m3u8 = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(b)?.groupValues?.get(1)
                ?: continue
            return EgyDeadExtractorKit.emitHlsWithVariants(m3u8, "LuluStream", "$origin/", fallbackQuality = qualityHint, callback = callback)
        }
        return 0
    }
}

object EgyDeadSaveFilesExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val origin = EgyDeadExtractorKit.originOf(url) ?: return 0
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: url.trimEnd('/').substringAfterLast('/')
        val dlRes = EgyDeadExtractorKit.postForm(
            url = "$origin/dl",
            form = mapOf("op" to "embed", "file_code" to code, "auto" to "1"),
            referer = "$origin/e/$code"
        )
        val bodies = listOfNotNull(dlRes?.body, EgyDeadExtractorKit.getText("$origin/e/$code", referer = "$origin/")?.body)
        for (body in bodies) {
            val blobs = EgyDeadExtractorKit.unpackJs(body) + body
            for (b in blobs) {
                val m3u8 = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(b)?.groupValues?.get(1)
                    ?: continue
                return EgyDeadExtractorKit.emitHlsWithVariants(m3u8, "SaveFiles", "$origin/", fallbackQuality = qualityHint, callback = callback)
            }
        }
        return 0
    }
}

object EgyDeadUqloadExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val origin = EgyDeadExtractorKit.originOf(url) ?: "https://uqload.net"
        val code = Regex("""(?:embed-|/)([A-Za-z0-9]{10,16})(?:\.html)?""").find(url)?.groupValues?.get(1) ?: return 0
        val embedUrl = "$origin/embed-$code.html"
        val res = EgyDeadExtractorKit.getText(embedUrl, referer = "$origin/") ?: return 0
        val mp4 = Regex("""sources\s*:\s*\[\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(res.body)?.groupValues?.get(1)
            ?: return 0
        EgyDeadExtractorKit.emitDirectVideo(mp4, "Uqload", "$origin/", qualityHint, callback = callback)
        return 1
    }
}

object EgyDeadMp4UploadExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val code = Regex("""(?:embed-|/)([A-Za-z0-9]{10,16})(?:\.html)?""").find(url)?.groupValues?.get(1) ?: return 0
        val embedUrl = "https://www.mp4upload.com/embed-$code.html"
        val res = EgyDeadExtractorKit.getText(embedUrl, referer = "https://www.mp4upload.com/") ?: return 0
        val blobs = EgyDeadExtractorKit.unpackJs(res.body) + res.body
        for (b in blobs) {
            val mp4 = Regex("""src\s*[:(]\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(b)?.groupValues?.get(1)
                ?: continue
            EgyDeadExtractorKit.emitDirectVideo(mp4, "Mp4Upload", embedUrl, qualityHint, callback = callback)
            return 1
        }
        return 0
    }
}

object EgyDeadMediaFireExtractor {
    suspend fun extract(url: String, qualityHint: Int, callback: (ExtractorLink) -> Unit): Int {
        val res = EgyDeadExtractorKit.getText(url, referer = "https://www.mediafire.com/") ?: return 0
        if (res.code !in 200..299) return 0
        val doc = Jsoup.parse(res.body, res.finalUrl)
        val dlBtn = doc.selectFirst("a#downloadButton[href], a.popsok[href]")
        val direct = dlBtn?.attr("data-scrambled-url")?.takeIf { it.isNotBlank() }?.let {
            runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
        } ?: dlBtn?.attr("href")?.takeIf { it.startsWith("http") } ?: return 0
        EgyDeadExtractorKit.emitDirectVideo(direct, "MediaFire", "https://www.mediafire.com/", qualityHint, callback = callback)
        return 1
    }
}

object EgyDeadVkExtractor {
    suspend fun extract(url: String, callback: (ExtractorLink) -> Unit): Int {
        val res = EgyDeadExtractorKit.getText(url, referer = "https://vk.com/") ?: return 0
        var count = 0
        for ((field, q) in listOf(
            "url2160" to Qualities.P2160.value,
            "url1440" to Qualities.P1440.value,
            "url1080" to Qualities.P1080.value,
            "url720" to Qualities.P720.value,
            "url480" to Qualities.P480.value,
            "url360" to Qualities.P360.value,
            "url240" to Qualities.P240.value
        )) {
            val m = Regex(""""$field"\s*:\s*"(https?:[^"]+)"""").find(res.body) ?: continue
            val clean = m.groupValues[1].replace("\\/", "/")
            EgyDeadExtractorKit.emitDirectVideo(clean, "VK", "https://vk.com/", q, callback = callback)
            count++
        }
        if (count == 0) {
            Regex(""""hls"\s*:\s*"(https?:[^"]+)"""").find(res.body)?.groupValues?.get(1)?.replace("\\/", "/")?.let { hls ->
                count += EgyDeadExtractorKit.emitHlsWithVariants(hls, "VK [HLS]", "https://vk.com/", callback = callback)
            }
        }
        return count
    }
}

// =========================================================================
// Central Mirror Router + Dead-Host Filter + CloudStream ExtractorApi Wrappers
// =========================================================================

object EgyDeadMirrorRouter {

    private val DEAD_HOST_KEYWORDS = listOf(
        "openload.", "oload.", "streamango.", "estream.", "mystream.", "flashx.",
        "uptobox.", "uptostream.", "nitroflare.", "filerio.", "1fichier.", "mirrorace.",
        "rapidgator.", "turbobit.", "uploaded.", "ul.to", "katfile.", "ddownload.",
        "letitbit.", "zippyshare.", "verystream.", "gounlimited.", "jetload.", "clipwatching.",
        "vidlox.", "thevid.", "vidtodo.", "vidup.", "jawcloud.", "tune.pk"
    )

    fun isDeadArchiveHost(url: String): Boolean {
        val host = runCatching { URI(EgyDeadNetwork.encodeUrl(url)).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isEmpty()) return true
        return DEAD_HOST_KEYWORDS.any { host.contains(it) || host == it.trimEnd('.') }
    }

    fun canonicalDedupKey(rawUrl: String): String {
        val u = rawUrl.trim()
        EgyDeadMegaMaxExtractor.extractHashId(u)?.let { id ->
            if (EgyDeadMegaMaxExtractor.isMegaMaxUrl(u)) return "megamax:$id"
        }
        val host = runCatching { URI(EgyDeadNetwork.encodeUrl(u)).host?.lowercase()?.removePrefix("www.") }.getOrNull().orEmpty()
        if (EgyDeadStreamHgExtractor.isStreamHgHost(host)) {
            EgyDeadStreamHgExtractor.extractCode(u)?.let { return "streamhg:$it" }
        }
        if (EgyDeadEarnVidsExtractor.isEarnVidsHost(host)) {
            EgyDeadEarnVidsExtractor.extractCode(u)?.let { return "earnvids:$it" }
        }
        if (EgyDeadMixdropExtractor.isMixdropHost(host)) {
            EgyDeadMixdropExtractor.extractCode(u)?.let { return "mixdrop:$it" }
        }
        if (EgyDeadDoodExtractor.isDoodHost(host)) {
            EgyDeadDoodExtractor.extractCode(u)?.let { return "dood:$it" }
        }
        return u.lowercase().trimEnd('/')
    }

    suspend fun resolve(
        url: String,
        serverHint: String? = null,
        qualityHint: Int = Qualities.Unknown.value,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = when {
            url.startsWith("//") -> "https:${url.trim()}"
            else -> url.trim()
        }
        if (!normalizedUrl.startsWith("http")) return
        if (isDeadArchiveHost(normalizedUrl)) return

        val host = runCatching { URI(EgyDeadNetwork.encodeUrl(normalizedUrl)).host?.lowercase().orEmpty() }.getOrDefault("")

        // 1. MegaMax (q7v3k8m2p1.top, megamax.me, share4max.com, or rotating /iframe/<id> & /download/<id>)
        if (EgyDeadMegaMaxExtractor.isMegaMaxUrl(normalizedUrl, serverHint)) {
            EgyDeadMegaMaxExtractor.extract(normalizedUrl, referer, subtitleCallback, callback)
            return
        }

        val emitted = runCatching {
            when {
                EgyDeadStreamHgExtractor.isStreamHgHost(host, serverHint) ->
                    EgyDeadStreamHgExtractor.extract(normalizedUrl, referer, qualityHint, subtitleCallback, callback)

                EgyDeadEarnVidsExtractor.isEarnVidsHost(host, serverHint) ->
                    EgyDeadEarnVidsExtractor.extract(normalizedUrl, referer, qualityHint, subtitleCallback, callback)

                EgyDeadVidstackExtractor.isVidstackHost(host) -> {
                    val label = when {
                        host.contains("rpm") -> "RPMShare"
                        host.contains("p2p") -> "StreamP2P"
                        else -> "UPNShare"
                    }
                    EgyDeadVidstackExtractor.extract(normalizedUrl, label, qualityHint, subtitleCallback, callback)
                }

                EgyDeadByseExtractor.isByseHost(host) ->
                    EgyDeadByseExtractor.extract(normalizedUrl, referer, qualityHint, callback)

                host.contains("firestream.") || host.contains("larooza.") ->
                    EgyDeadFireStreamExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("vinovo.") ->
                    EgyDeadVinovoExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("vidara.") ->
                    EgyDeadVidaraExtractor.extract(normalizedUrl, qualityHint, callback)

                EgyDeadMixdropExtractor.isMixdropHost(host, serverHint) ->
                    EgyDeadMixdropExtractor.extract(normalizedUrl, qualityHint, callback)

                EgyDeadDoodExtractor.isDoodHost(host, serverHint) ->
                    EgyDeadDoodExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("koramaup.") ->
                    EgyDeadKoramaUpExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("ok.ru") || host.contains("odnoklassniki.ru") ->
                    EgyDeadOkRuExtractor.extract(normalizedUrl, callback)

                host.contains("vidoza.") || host.contains("videzz.") ->
                    EgyDeadVidozaExtractor.extract(normalizedUrl, qualityHint, callback)

                EgyDeadVoeExtractor.isVoeHost(host, serverHint) ->
                    EgyDeadVoeExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("luluvdo.") || host.contains("lulustream.") || host.contains("lulu.st") || host.contains("732eg54de642sa.") ->
                    EgyDeadLuluStreamExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("savefiles.") || host.contains("streamhls.") ->
                    EgyDeadSaveFilesExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("uqload.") ->
                    EgyDeadUqloadExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("mp4upload.") ->
                    EgyDeadMp4UploadExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("mediafire.") ->
                    EgyDeadMediaFireExtractor.extract(normalizedUrl, qualityHint, callback)

                host.contains("vk.com") || host.contains("vkvideo.ru") ->
                    EgyDeadVkExtractor.extract(normalizedUrl, callback)

                normalizedUrl.contains(".mp4", ignoreCase = true) && !normalizedUrl.contains(".html", ignoreCase = true) -> {
                    EgyDeadExtractorKit.emitDirectVideo(
                        videoUrl = normalizedUrl,
                        sourceName = serverHint?.ifBlank { "Direct MP4" } ?: "Direct MP4",
                        referer = referer ?: "https://tv10.egydead.live/",
                        quality = qualityHint,
                        callback = callback
                    )
                    1
                }

                normalizedUrl.contains(".m3u8", ignoreCase = true) -> {
                    EgyDeadExtractorKit.emitHlsWithVariants(
                        masterUrl = normalizedUrl,
                        sourceName = serverHint?.ifBlank { "Direct HLS" } ?: "Direct HLS",
                        referer = referer ?: "https://tv10.egydead.live/",
                        fallbackQuality = qualityHint,
                        callback = callback
                    )
                }

                else -> 0
            }
        }.getOrDefault(0)

        // Universal CloudStream built-in fallback if custom extractor produced 0 links
        if (emitted <= 0) {
            runCatching {
                loadExtractor(normalizedUrl, referer, subtitleCallback, callback)
            }
        }
    }
}

open class EgyDeadMegaMaxApi(override val mainUrl: String = "https://q7v3k8m2p1.top") : ExtractorApi() {
    override val name = "MegaMax"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        EgyDeadMegaMaxExtractor.extract(url, referer, subtitleCallback, callback)
    }
}

class EgyDeadMegaMaxMeApi : EgyDeadMegaMaxApi("https://megamax.me")
class EgyDeadShare4MaxApi : EgyDeadMegaMaxApi("https://share4max.com")

open class EgyDeadStreamHgApi(override val mainUrl: String = "https://hanerix.com") : ExtractorApi() {
    override val name = "StreamHG"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        EgyDeadStreamHgExtractor.extract(url, referer, Qualities.Unknown.value, subtitleCallback, callback)
    }
}

class EgyDeadVibuxerApi : EgyDeadStreamHgApi("https://vibuxer.com")
class EgyDeadHgCloudApi : EgyDeadStreamHgApi("https://hgcloud.to")
