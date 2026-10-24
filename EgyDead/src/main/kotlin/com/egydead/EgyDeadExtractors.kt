package com.egydead

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.OkRuHTTP
import com.lagradost.cloudstream3.extractors.OkRuSSL
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pure-Kotlin Base64 helper using Kotlin stdlib (`kotlin.io.encoding.Base64`) so Base64 works
 * identically on Android API 21..35 AND JVM JUnit tests.
 */
@OptIn(ExperimentalEncodingApi::class)
object EgyDeadBase64 {
    fun decode(input: String): ByteArray {
        val clean = input.replace(Regex("""\s+"""), "")
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return Base64.Default.decode(padded)
    }

    fun decodeUrlSafe(input: String): ByteArray {
        val std = input.trim().replace('-', '+').replace('_', '/')
        return decode(std)
    }

    fun encodeUrlSafeNoPad(bytes: ByteArray): String =
        Base64.UrlSafe.encode(bytes).trimEnd('=')
}

/**
 * Shared pure-Kotlin utilities for EgyDead video & download extractors:
 * - Direct OkHttp execution (`EgyDeadNetwork.httpClient` with Cloudflare/Google DoH)
 * - Dean Edwards `eval(function(p,a,c,k,e,d))` unpacker (multi-pass, radices 2..62)
 * - OpenSSL `EVP_BytesToKey` (MD5) + `AES-256-CBC` decryptor (CryptoJS JSON `{"ct","iv","s"}` & `Salted__`)
 * - Master HLS (`#EXT-X-STREAM-INF`) multi-quality expansion (`Auto` + `1080p` + `720p` + `480p` + `360p`)
 * - Dead 2018-era host skip filter & cross-mirror canonical deduplication
 */
object EgyDeadExtractorKit {

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val DEAD_ARCHIVE_HOSTS = setOf(
        "openload.", "oload.", "streamango.", "streamcherry.", "estream.to",
        "mystream.to", "flashx.", "vshare.eu", "vidabc.", "rapidvideo.",
        "uptobox.", "uptostream.", "nitroflare.", "turbobit.", "katfile.",
        "rapidgator.", "1fichier.", "filerio.", "mirrorace.", "multiup.",
        "userscloud.", "uploadproper.", "thevideo.me", "vev.io"
    )

    fun isDeadHost(url: String): Boolean {
        val low = url.lowercase()
        return DEAD_ARCHIVE_HOSTS.any { low.contains(it) }
    }

    fun originOf(url: String): String? = EgyDeadNetwork.originOf(url)

    data class HttpTextResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val cookies: Map<String, String>
    )

    fun getText(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpTextResponse? = runCatching {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        EgyDeadNetwork.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            var r: okhttp3.Response? = resp
            while (r != null) {
                for (sc in r.headers("Set-Cookie")) {
                    val pair = sc.substringBefore(';')
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        cookies.putIfAbsent(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim())
                    }
                }
                r = r.priorResponse
            }
            HttpTextResponse(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                cookies = cookies
            )
        }
    }.getOrNull()

    fun postJson(
        url: String,
        jsonBody: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpTextResponse? = runCatching {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json; charset=utf-8")
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
            originOf(referer)?.let { reqBuilder.header("Origin", it) }
        } else {
            originOf(url)?.let { reqBuilder.header("Origin", it) }
        }
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        EgyDeadNetwork.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val cookies = LinkedHashMap<String, String>()
            resp.headers("Set-Cookie").forEach { sc ->
                val pair = sc.substringBefore(';')
                val eq = pair.indexOf('=')
                if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            HttpTextResponse(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                cookies = cookies
            )
        }
    }.getOrNull()

    fun formatCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun parseQuality(vararg hints: String?): Int {
        for (raw in hints) {
            if (raw.isNullOrBlank()) continue
            val s = raw.lowercase()
            when {
                s.contains("2160") || s.contains("4k") -> return Qualities.P2160.value
                s.contains("1440") || s.contains("2k") -> return Qualities.P1440.value
                s.contains("1080") || s.contains("fhd") || s.contains("full") -> return Qualities.P1080.value
                s.contains("720") || Regex("""\bhd\b""").containsMatchIn(s) -> return Qualities.P720.value
                s.contains("480") || Regex("""\bsd\b""").containsMatchIn(s) -> return Qualities.P480.value
                s.contains("360") -> return Qualities.P360.value
                s.contains("240") -> return Qualities.P240.value
            }
        }
        return Qualities.Unknown.value
    }

    fun formatQualityTag(qVal: Int): String = when (qVal) {
        Qualities.P2160.value -> "2160p"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        Qualities.P240.value -> "240p"
        else -> ""
    }

    // ---- Pure-Kotlin Dean Edwards p,a,c,k,e,d Unpacker ----

    private val PACKED_REGEX = Regex(
        """eval\(\s*function\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\).*?\}\s*\(\s*['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\(\s*['"]\|['"]\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private const val BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun encodeBaseN(num: Int, radix: Int): String {
        if (radix <= 10) return num.toString(radix)
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            val rem = n % radix
            sb.append(
                if (rem < BASE62_ALPHABET.length) BASE62_ALPHABET[rem]
                else (rem + 29).toChar()
            )
            n /= radix
        }
        return sb.reverse().toString()
    }

    fun unpackJs(htmlOrJs: String): String {
        val sb = StringBuilder(htmlOrJs)
        var current = htmlOrJs
        repeat(2) {
            val newlyUnpacked = StringBuilder()
            for (m in PACKED_REGEX.findAll(current)) {
                val payload = m.groupValues[1]
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val radix = m.groupValues[2].toIntOrNull() ?: 36
                val count = m.groupValues[3].toIntOrNull() ?: 0
                val symtab = m.groupValues[4].split('|')
                if (radix < 2 || count <= 0) continue

                val lookup = HashMap<String, String>(count * 2)
                for (i in (count - 1) downTo 0) {
                    val token = encodeBaseN(i, radix)
                    val value = symtab.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: token
                    lookup[token] = value
                }
                val unpacked = Regex("""\b[0-9a-zA-Z]+\b""").replace(payload) { tok ->
                    lookup[tok.value] ?: tok.value
                }
                newlyUnpacked.append('\n').append(unpacked)
            }
            if (newlyUnpacked.isEmpty()) return sb.toString()
            sb.append(newlyUnpacked)
            current = newlyUnpacked.toString()
        }
        return sb.toString()
    }

    // ---- OpenSSL EVP_BytesToKey (MD5) + AES-256-CBC (Salted__ & CryptoJS JSON) ----

    fun decryptOpenSslAes(base64Payload: String, passphrase: String): String? = runCatching {
        val raw = EgyDeadBase64.decode(base64Payload.trim())
        if (raw.size <= 16) return null
        val header = String(raw, 0, 8, Charsets.US_ASCII)
        if (header != "Salted__") return null
        val salt = raw.copyOfRange(8, 16)
        val cipherBytes = raw.copyOfRange(16, raw.size)
        evpDecrypt(cipherBytes, salt, null, passphrase)
    }.getOrNull()

    fun decryptOpenSslAesCbc(base64Payload: String, passphrase: String): String? =
        decryptOpenSslAes(base64Payload, passphrase)

    fun decryptCryptoJsJson(jsonStr: String, passphrase: String): String? = runCatching {
        val obj = JSONObject(jsonStr)
        val ct = EgyDeadBase64.decode(obj.getString("ct"))
        val iv = hexToBytes(obj.getString("iv"))
        val salt = hexToBytes(obj.getString("s"))
        evpDecrypt(ct, salt, iv, passphrase)
    }.getOrNull()

    private fun evpDecrypt(
        cipherBytes: ByteArray,
        salt: ByteArray,
        explicitIv: ByteArray?,
        passphrase: String
    ): String {
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
        val iv = explicitIv ?: derived.copyOfRange(32, 48)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    // ----HLS Multi-Quality Expansion & Direct MP4 Emitter ----

    suspend fun emitHlsWithVariants(
        sourceName: String,
        m3u8Url: String,
        referer: String,
        fallbackQuality: Int = Qualities.Unknown.value,
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = m3u8Url.trim()
        if (!cleanUrl.startsWith("http")) return false
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", DESKTOP_UA)
            if (referer.isNotBlank()) {
                put("Referer", referer)
                originOf(referer)?.let { put("Origin", it) }
            }
            putAll(extraHeaders)
        }

        var emittedAny = false
        val variants = runCatching {
            M3u8Helper.generateM3u8(sourceName, cleanUrl, referer, headers = headers)
        }.getOrNull().orEmpty()

        for (v in variants) {
            callback(v)
            emittedAny = true
        }

        if (!emittedAny) {
            val masterResp = getText(cleanUrl, referer = referer, headers = headers)
            val body = masterResp?.body.orEmpty()
            if (body.contains("#EXT-X-STREAM-INF")) {
                val baseUri = runCatching { URI(masterResp?.finalUrl ?: cleanUrl) }.getOrNull()
                val lines = body.lines()
                var pendingQuality = fallbackQuality
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        val resMatch = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
                        val nameMatch = Regex("""NAME="([^"]+)"""", RegexOption.IGNORE_CASE).find(trimmed)
                        pendingQuality = parseQuality(
                            resMatch?.groupValues?.get(1),
                            nameMatch?.groupValues?.get(1)
                        ).takeIf { it != Qualities.Unknown.value } ?: fallbackQuality
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val resolvedVariant = if (trimmed.startsWith("http")) {
                            trimmed
                        } else {
                            runCatching { baseUri?.resolve(trimmed)?.toString() }.getOrNull()
                        }
                        if (!resolvedVariant.isNullOrBlank()) {
                            val qTag = formatQualityTag(pendingQuality)
                            val label = if (qTag.isNotEmpty()) "$sourceName ($qTag)" else sourceName
                            callback(
                                newExtractorLink(
                                    source = sourceName,
                                    name = label,
                                    url = resolvedVariant,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = referer
                                    this.quality = pendingQuality
                                    this.headers = headers
                                }
                            )
                            emittedAny = true
                        }
                        pendingQuality = fallbackQuality
                    }
                }
            }
        }

        // Always emit the master playlist (Auto adaptive) when no variant emitted or as adaptive entry
        if (!emittedAny || variants.size > 1) {
            val qTag = formatQualityTag(fallbackQuality)
            val autoName = if (!emittedAny && qTag.isNotEmpty()) "$sourceName ($qTag)" else "$sourceName (Auto)"
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = if (emittedAny) "$sourceName (Auto)" else autoName,
                    url = cleanUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = fallbackQuality
                    this.headers = headers
                }
            )
            emittedAny = true
        }
        return emittedAny
    }

    suspend fun emitDirectVideo(
        sourceName: String,
        displayName: String,
        videoUrl: String,
        referer: String,
        quality: Int = Qualities.Unknown.value,
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = videoUrl.trim()
        if (!cleanUrl.startsWith("http")) return false
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", DESKTOP_UA)
            if (referer.isNotBlank()) put("Referer", referer)
            putAll(extraHeaders)
        }
        val linkType = if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(
                source = sourceName,
                name = displayName,
                url = cleanUrl,
                type = linkType
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = headers
            }
        )
        return true
    }
}

/**
 * Central dispatcher & canonical deduplicator for all EgyDead watch embeds and download links.
 */
object EgyDeadMirrorRouter {

    private val MEGAMAX_PATH_REGEX = Regex(
        """^/(?:iframe|download|e|d|f|mirror)/([A-Za-z0-9_-]{8,24})/?$""",
        RegexOption.IGNORE_CASE
    )

    private val STREAMHG_HOSTS = setOf(
        "vibuxer.com", "hanerix.com", "hgcloud.to", "cavanhabg.com", "hglink.to",
        "dumbalag.com", "streamhg.com", "streamwish.to", "streamwish.com",
        "wishembed.pro", "sfastwish.com", "awish.pro", "dwish.pro", "hlswish.com",
        "swdyu.com", "playerwish.com", "flaswish.com", "obeywish.com", "cdnwish.com",
        "asnwish.com", "jodwish.com", "swhoi.com", "multimovies.cloud", "kravaxxa.com"
    )

    private val EARNVIDS_HOSTS = setOf(
        "morencius.com", "smoothpre.com", "movearnpre.com", "dintezuvio.com",
        "dingtezuni.com", "minochinos.com", "earnvids.com", "earnvids.to",
        "vidhide.com", "vidhidepro.com", "vidhidevip.com", "vidhidepre.com",
        "peytonepre.com", "ryderjet.com", "callistanise.com"
    )

    private val DOOD_HOSTS = setOf(
        "playmogo.com", "dsvplay.com", "myvidplay.com", "d000d.com", "d0000d.com",
        "ds2play.com", "ds2video.com", "doodstream.com", "dood.li", "dood.wf",
        "dood.yt", "dood.pm", "dood.re", "dood.watch", "dood.to", "dood.so", "vide0.net"
    )

    private val MIXDROP_HOSTS = setOf(
        "mixdrop.top", "mixdrop.ps", "mixdrop.ag", "mixdrop.co", "mixdrop.to",
        "mixdrop.ch", "mixdrop.bz", "mixdrop.gl", "mixdrop.club", "mixdrop.vc",
        "mxdrop.to", "mxdrop.click", "m1xdrop.net", "mdy48tn97.com", "mdbekjwqa.pw"
    )

    private val FILEMOON_HOSTS = setOf(
        "filemoon.sx", "filemoon.to", "filemoon.in", "filemoon.nl", "filemoon.wf",
        "moonmov.to", "kerapoxy.cc", "1azayf9w.xyz", "81u6xl9d.xyz", "ico3c.com",
        "bysedikamoum.com"
    )

    private val VOE_HOSTS = setOf(
        "voe.sx", "alejandrocenturyoil.com", "walterprettytheir.com", "crystaltreatmenteast.com",
        "lauradaydo.com", "richardsignfish.com", "robertordercharacter.com", "maxfinishseveral.com",
        "diananatureforeign.com", "jilliandescribecompany.com"
    )

    private val BYSE_HOSTS = setOf(
        "byseraguci.com", "bysezejataos.com", "bysekoze.com", "bysebuho.com",
        "bysedikamoum.com", "bysesukior.com", "bysevepoin.com", "n1mwq.org"
    )

    fun isDeadArchiveHost(url: String): Boolean = EgyDeadExtractorKit.isDeadHost(url)

    fun canonicalDedupKey(url: String, serverHint: String = ""): String = canonicalKey(url, serverHint)

    fun isMegaMaxUrl(url: String, serverHint: String = ""): Boolean {
        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if (host.contains("megamax.") || host.contains("share4max.") || host.contains("q7v3k8m2p1.")) {
            return true
        }
        if (serverHint.contains("megamax", ignoreCase = true) || serverHint.contains("share4max", ignoreCase = true)) {
            return true
        }
        if (MEGAMAX_PATH_REGEX.matches(path) &&
            (path.startsWith("/iframe/", ignoreCase = true) || path.startsWith("/download/", ignoreCase = true))
        ) {
            return true
        }
        return false
    }

    /**
     * Produces a canonical deduplication key so `/watch/` + `/download/` links pointing to the
     * exact same underlying video ID (`q7v3k8m2p1.top/iframe/X` + `/download/X`, or
     * `hanerix.com/e/Y` + `hgcloud.to/Y`, or `morencius.com/file/Z`) are resolved once.
     */
    fun canonicalKey(url: String, serverHint: String = ""): String {
        val clean = url.trim()
        val host = runCatching { URI(clean).host?.lowercase()?.removePrefix("www.").orEmpty() }.getOrDefault("")
        val path = runCatching { URI(clean).path.orEmpty().trimEnd('/') }.getOrDefault("")

        if (isMegaMaxUrl(clean, serverHint)) {
            val id = path.substringAfterLast('/')
            if (id.isNotBlank()) return "megamax:$id"
        }
        if (STREAMHG_HOSTS.any { host == it || host.endsWith(".$it") }) {
            val id = path.substringAfterLast('/').removePrefix("embed-").removeSuffix(".html")
            if (id.isNotBlank()) return "streamhg:$id"
        }
        if (EARNVIDS_HOSTS.any { host == it || host.endsWith(".$it") }) {
            val id = path.substringAfterLast('/').removePrefix("embed-").removeSuffix(".html")
            if (id.isNotBlank()) return "earnvids:$id"
        }
        if (DOOD_HOSTS.any { host == it || host.endsWith(".$it") }) {
            val id = path.substringAfterLast('/')
            if (id.isNotBlank()) return "dood:$id"
        }
        if (MIXDROP_HOSTS.any { host == it || host.endsWith(".$it") }) {
            val id = path.substringAfterLast('/')
            if (id.isNotBlank()) return "mixdrop:$id"
        }
        if (host.contains("mp4upload.")) {
            val id = path.substringAfterLast('/').removePrefix("embed-").removeSuffix(".html")
            if (id.isNotBlank()) return "mp4upload:$id"
        }
        if (host.contains("uqload.")) {
            val id = path.substringAfterLast('/').removePrefix("embed-").removeSuffix(".html")
            if (id.isNotBlank()) return "uqload:$id"
        }
        if (VOE_HOSTS.any { host == it || host.endsWith(".$it") } || host.contains("voe.")) {
            val id = path.substringAfterLast('/')
            if (id.isNotBlank()) return "voe:$id"
        }
        return clean.lowercase().trimEnd('/')
    }

    suspend fun resolve(
        url: String,
        serverHint: String = "",
        qualityHint: Int = Qualities.Unknown.value,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        sourcePrefix: String? = null
    ) {
        val clean = when {
            url.startsWith("//") -> "https:$url"
            else -> url.trim()
        }
        if (!clean.startsWith("http") || EgyDeadExtractorKit.isDeadHost(clean)) return

        val buffered: MutableList<ExtractorLink>? = if (sourcePrefix.isNullOrBlank()) {
            null
        } else {
            java.util.Collections.synchronizedList(ArrayList<ExtractorLink>())
        }
        val effectiveCallback: (ExtractorLink) -> Unit = if (buffered == null) {
            callback
        } else {
            { link -> buffered.add(link) }
        }

        val host = runCatching { URI(clean).host?.lowercase()?.removePrefix("www.").orEmpty() }.getOrDefault("")
        val hintLow = serverHint.lowercase().trim()

        try {
            when {
                isMegaMaxUrl(clean, serverHint) -> {
                    EgyDeadMegaMaxExtractor().extractAll(clean, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("uns.bio") || host.contains("upns.") || host.contains("upnshare") || hintLow == "upnshare" -> {
                    EgyDeadUpnShareExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("strp2p.") || host.contains("streamp2p") || hintLow == "streamp2p" -> {
                    EgyDeadStreamP2PExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("rpmplay.") || host.contains("rpmshare") || host.contains("rpmvid.") ||
                    host.contains("rpmvip.") || hintLow == "rpmshare" -> {
                    EgyDeadRpmShareExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("firestream.") || hintLow == "firestream" -> {
                    EgyDeadFireStreamExtractor().extract(clean, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("vidara") || hintLow == "vidara" -> {
                    EgyDeadVidaraExtractor().extract(clean, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                host.contains("vidmoly.") || hintLow == "vidmoly" -> {
                    EgyDeadVidmolyExtractor().extract(clean, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                BYSE_HOSTS.any { host == it || host.endsWith(".$it") } || host.startsWith("byse") || hintLow == "byse" -> {
                    EgyDeadByseExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                STREAMHG_HOSTS.any { host == it || host.endsWith(".$it") } ||
                    hintLow.contains("streamhg") || hintLow.contains("streamwish") -> {
                    EgyDeadStreamHGExtractor().extract(clean, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                EARNVIDS_HOSTS.any { host == it || host.endsWith(".$it") } ||
                    hintLow.contains("earnvids") || hintLow.contains("vidhide") -> {
                    EgyDeadEarnVidsExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                DOOD_HOSTS.any { host == it || host.endsWith(".$it") } ||
                    host.startsWith("dood.") || hintLow.contains("dood") -> {
                    EgyDeadDoodExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                MIXDROP_HOSTS.any { host == it || host.endsWith(".$it") } ||
                    host.contains("mixdrop") || hintLow.contains("mixdrop") -> {
                    EgyDeadMixdropExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                FILEMOON_HOSTS.any { host == it || host.endsWith(".$it") } ||
                    host.contains("filemoon") || hintLow.contains("filemoon") -> {
                    EgyDeadFileMoonExtractor().extract(clean, referer, qualityHint, subtitleCallback, effectiveCallback)
                }
                VOE_HOSTS.any { host == it || host.endsWith(".$it") } ||
                    host.contains("voe.") || hintLow == "voe" -> {
                    EgyDeadVoeExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("mediafire.com") || hintLow == "mediafire" -> {
                    EgyDeadMediaFireExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("mp4upload.") || hintLow == "mp4upload" -> {
                    EgyDeadMp4UploadExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("uqload.") || hintLow == "uqload" -> {
                    EgyDeadUqloadExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("vkvideo.ru") || host == "vk.com" || host.endsWith(".vk.com") || host == "vk.ru" -> {
                    EgyDeadVkExtractor().extract(clean, referer, effectiveCallback)
                }
                host.contains("ok.ru") || host.contains("odnoklassniki.ru") -> {
                    EgyDeadOkRuExtractor().extract(clean, referer, subtitleCallback, effectiveCallback)
                }
                host.contains("vidoza.") || host.contains("videzz.") -> {
                    EgyDeadVidozaExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("koramaup.") -> {
                    EgyDeadKoramaUpExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("upvid.") -> {
                    EgyDeadUpvidExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("savefiles.") || host.contains("streamhls.") || hintLow == "savefiles" -> {
                    EgyDeadSaveFilesExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                host.contains("lulustream") || host.contains("luluvdo") || hintLow == "lulustream" -> {
                    EgyDeadLuluStreamExtractor().extract(clean, referer, qualityHint, effectiveCallback)
                }
                clean.endsWith(".mp4", ignoreCase = true) || clean.endsWith(".mkv", ignoreCase = true) -> {
                    val label = serverHint.ifBlank { "Direct MP4" }
                    EgyDeadExtractorKit.emitDirectVideo(label, label, clean, referer, qualityHint, callback = effectiveCallback)
                }
                clean.contains(".m3u8", ignoreCase = true) -> {
                    val label = serverHint.ifBlank { "HLS Stream" }
                    EgyDeadExtractorKit.emitHlsWithVariants(label, clean, referer, qualityHint, callback = effectiveCallback)
                }
                else -> {
                    loadExtractor(clean, referer, subtitleCallback, effectiveCallback)
                }
            }
        } catch (_: Throwable) {
            // Never let one mirror halt remaining servers
        }

        if (buffered != null && !sourcePrefix.isNullOrBlank()) {
            for (link in buffered) {
                val pSource = if (link.source.startsWith(sourcePrefix, ignoreCase = true)) {
                    link.source
                } else {
                    "$sourcePrefix - ${link.source}"
                }
                val pName = if (link.name.startsWith(sourcePrefix, ignoreCase = true)) {
                    link.name
                } else {
                    "$sourcePrefix - ${link.name}"
                }
                callback(
                    newExtractorLink(pSource, pName, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }
}

// =========================================================================
// 1. MegaMax Unified 4-Channel Extractor (`q7v3k8m2p1.top`, `megamax.me`, `share4max.com`)
//    - Channel 1: `/e/<id>` -> Inertia `files/leech/video` partial reload + CryptoJS AES
//    - Channel 2: `/d/<id>` -> Inertia `files/leech/download` `POST {"attach": hashId}`
//    - Channel 3: `/iframe/<id>` -> Inertia `files/mirror/video` partial reload (`streams`)
//    - Channel 4: `/download/<id>` -> Inertia `files/mirror/download` `POST {"attach": hashId}`
// =========================================================================

open class EgyDeadMegaMaxExtractor : ExtractorApi() {
    override val name = "MegaMax"
    override val mainUrl = "https://megamax.me"
    override val requiresReferer = true

    private data class InertiaSession(
        val url: String,
        val origin: String,
        val page: JSONObject,
        val version: String,
        val component: String,
        val cookieHeader: String,
        val xsrfToken: String
    )

    private data class MirrorTask(
        val mirrorUrl: String,
        val driver: String,
        val qualityLabel: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractAll(url, referer ?: mainUrl, Qualities.Unknown.value, subtitleCallback, callback)
    }

    suspend fun extractAll(
        url: String,
        referer: String,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hashId = Regex("""/(?:iframe|download|e|d|f|mirror)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val preferredOrigin = EgyDeadExtractorKit.originOf(url) ?: "https://megamax.me"
        val refUrl = referer.takeIf { it.startsWith("http") } ?: "$preferredOrigin/"
        val qHintLabel = EgyDeadExtractorKit.formatQualityTag(qualityHint)

        val mirrorOrigins = listOf(preferredOrigin, "https://share4max.com", "https://megamax.me").distinct()
        val leechOrigins = listOf(preferredOrigin, "https://megamax.me", "https://share4max.com").distinct()

        val (iframeResult, downloadTasks) = coroutineScope {
            val leechHlsJob = async(Dispatchers.IO) {
                extractLeechHls(hashId, leechOrigins, refUrl, subtitleCallback, callback)
            }
            val leechMp4Job = async(Dispatchers.IO) {
                extractLeechDirectMp4(hashId, leechOrigins, refUrl, callback)
            }
            val iframeJob = async(Dispatchers.IO) {
                fetchIframeMirrors(hashId, mirrorOrigins, refUrl, qHintLabel)
            }
            val downloadJob = async(Dispatchers.IO) {
                fetchDownloadMirrors(hashId, mirrorOrigins, refUrl, qHintLabel)
            }
            leechHlsJob.await()
            leechMp4Job.await()
            Pair(iframeJob.await(), downloadJob.await())
        }

        val activeIframeUrl = iframeResult.first ?: "$preferredOrigin/iframe/$hashId"
        val mergedTasks = ArrayList<MirrorTask>()
        val seenIdentities = HashSet<String>()

        for (task in iframeResult.second + downloadTasks) {
            if (EgyDeadExtractorKit.isDeadHost(task.mirrorUrl)) continue
            val idKey = mirrorIdentityKey(task.driver, task.mirrorUrl, task.qualityLabel)
            if (seenIdentities.add(idKey)) {
                mergedTasks.add(task)
            }
        }

        if (mergedTasks.isEmpty()) return

        val sem = Semaphore(6)
        coroutineScope {
            mergedTasks.map { task ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val tierQ = EgyDeadExtractorKit.parseQuality(task.qualityLabel)
                            .takeIf { it != Qualities.Unknown.value } ?: qualityHint
                        EgyDeadMirrorRouter.resolve(
                            url = task.mirrorUrl,
                            serverHint = task.driver,
                            qualityHint = tierQ,
                            referer = activeIframeUrl,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                            sourcePrefix = "MegaMax"
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private fun openInertiaSession(targetUrl: String, referer: String): InertiaSession? = runCatching {
        val resp = EgyDeadExtractorKit.getText(
            targetUrl,
            referer = referer,
            headers = mapOf(
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )
        ) ?: return null
        if (resp.code >= 400 || resp.body.isBlank()) return null

        val doc = Jsoup.parse(resp.body)
        val rawPage = doc.selectFirst("script[data-page=app], script[type=application/json][data-page]")
            ?.html()?.trim()?.takeIf { it.startsWith("{") }
            ?: doc.selectFirst("#app[data-page], [data-page]")?.attr("data-page")
                ?.let { Parser.unescapeEntities(it, false).trim() }
                ?.takeIf { it.startsWith("{") }
            ?: return null

        val pageJson = JSONObject(rawPage)
        val cookieHeader = EgyDeadExtractorKit.formatCookieHeader(resp.cookies)
        val xsrf = resp.cookies["XSRF-TOKEN"]?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }.orEmpty()
        val finalUrl = resp.finalUrl.ifBlank { targetUrl }
        val finalOrigin = EgyDeadExtractorKit.originOf(finalUrl)
            ?: EgyDeadExtractorKit.originOf(targetUrl)
            ?: mainUrl

        InertiaSession(
            url = finalUrl,
            origin = finalOrigin,
            page = pageJson,
            version = pageJson.optString("version"),
            component = pageJson.optString("component"),
            cookieHeader = cookieHeader,
            xsrfToken = xsrf
        )
    }.getOrNull()

    private fun buildInertiaHeaders(
        sess: InertiaSession,
        partialComponent: String,
        partialData: String,
        isPost: Boolean = false
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        put("Accept", "text/html, application/xhtml+xml")
        put("Content-Type", "application/json")
        put("Referer", sess.url)
        if (isPost) put("Origin", sess.origin)
        put("X-Requested-With", "XMLHttpRequest")
        put("X-Inertia", "true")
        if (sess.version.isNotBlank()) put("X-Inertia-Version", sess.version)
        put("X-Inertia-Partial-Component", partialComponent)
        put("X-Inertia-Partial-Data", partialData)
        if (sess.cookieHeader.isNotBlank()) put("Cookie", sess.cookieHeader)
        if (sess.xsrfToken.isNotBlank()) put("X-XSRF-TOKEN", sess.xsrfToken)
    }

    // Channel 1: `/e/<id>` -> `files/leech/video`
    private suspend fun extractLeechHls(
        hashId: String,
        origins: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (base in origins) {
            val sess = openInertiaSession("$base/e/$hashId", referer) ?: continue
            val comp = sess.component.ifBlank { "files/leech/video" }
            val reloadResp = EgyDeadExtractorKit.getText(
                sess.url,
                referer = sess.url,
                headers = buildInertiaHeaders(sess, comp, "streams,subtitles")
            ) ?: continue
            val props = runCatching { JSONObject(reloadResp.body).optJSONObject("props") }.getOrNull() ?: continue

            props.optJSONArray("subtitles")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val subUrl = (s.optString("file").ifBlank { s.optString("url") })
                        .takeIf { it.startsWith("http") } ?: continue
                    val lang = s.optString("label").ifBlank { "Arabic" }
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }

            val dataRaw = props.optJSONObject("streams")?.optString("data").orEmpty().trim()
            if (dataRaw.isBlank()) continue

            val plain = when {
                dataRaw.contains("\"ct\"") -> EgyDeadExtractorKit.decryptCryptoJsJson(dataRaw, LEECH_AES_PASSPHRASE)
                dataRaw.startsWith("U2FsdGVkX1") -> EgyDeadExtractorKit.decryptOpenSslAes(dataRaw, LEECH_AES_PASSPHRASE)
                else -> dataRaw
            }?.trim() ?: continue

            val streamHeaders = mapOf("Referer" to "${sess.origin}/", "Origin" to sess.origin)
            var emitted = false
            if (plain.startsWith("[")) {
                val arr = runCatching { JSONArray(plain) }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (emitLeechObj(obj, sess.origin, streamHeaders, callback)) emitted = true
                    }
                }
            } else if (plain.startsWith("{")) {
                val obj = runCatching { JSONObject(plain) }.getOrNull()
                if (obj != null && emitLeechObj(obj, sess.origin, streamHeaders, callback)) emitted = true
            }
            if (emitted) return
        }
    }

    private suspend fun emitLeechObj(
        obj: JSONObject,
        origin: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileUrl = (obj.optString("file").ifBlank { obj.optString("url") })
            .takeIf { it.startsWith("http") } ?: return false
        val qVal = EgyDeadExtractorKit.parseQuality(obj.optString("label"), fileUrl)
        return if (fileUrl.contains(".m3u8", ignoreCase = true) ||
            obj.optString("type").contains("mpegURL", ignoreCase = true)
        ) {
            EgyDeadExtractorKit.emitHlsWithVariants("MegaMax Direct", fileUrl, "$origin/", qVal, headers, callback)
        } else {
            val qTag = EgyDeadExtractorKit.formatQualityTag(qVal)
            val label = if (qTag.isNotEmpty()) "MegaMax Direct ($qTag)" else "MegaMax Direct"
            EgyDeadExtractorKit.emitDirectVideo("MegaMax Direct", label, fileUrl, "$origin/", qVal, headers, callback)
        }
    }

    // Channel 2: `/d/<id>` -> `files/leech/download`
    private suspend fun extractLeechDirectMp4(
        hashId: String,
        origins: List<String>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        for (base in origins) {
            val sess = openInertiaSession("$base/d/$hashId", referer) ?: continue
            val attachments = sess.page.optJSONObject("props")?.optJSONArray("attachments") ?: continue
            if (attachments.length() == 0) continue

            val comp = sess.component.ifBlank { "files/leech/download" }
            val headers = buildInertiaHeaders(sess, comp, "download", isPost = true)
            var emitted = false

            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val attachId = att.optString("hashId").ifBlank { att.optString("id") }
                if (attachId.isBlank()) continue
                val attLabel = att.optString("label").ifBlank { att.optString("name") }
                val attRes = att.optString("resolution")

                val postResp = EgyDeadExtractorKit.postJson(
                    sess.url,
                    jsonBody = JSONObject().put("attach", attachId).toString(),
                    referer = sess.url,
                    headers = headers
                ) ?: continue

                val directUrl = runCatching {
                    val dl = JSONObject(postResp.body).optJSONObject("props")?.optJSONObject("download")
                    dl?.optJSONObject("data")?.optString("url")
                        ?.ifBlank { dl.optString("url") }
                }.getOrNull()?.takeIf { it.startsWith("http") } ?: continue

                val qVal = EgyDeadExtractorKit.parseQuality(attLabel, attRes, directUrl)
                val qTag = EgyDeadExtractorKit.formatQualityTag(qVal)
                val label = if (qTag.isNotEmpty()) "MegaMax Direct MP4 ($qTag)" else "MegaMax Direct MP4"

                if (EgyDeadExtractorKit.emitDirectVideo("MegaMax Direct", label, directUrl, "${sess.origin}/", qVal, callback = callback)) {
                    emitted = true
                }
            }
            if (emitted) return
        }
    }

    // Channel 3: `/iframe/<id>` -> `files/mirror/video` (with Inertia partial reload!)
    private fun fetchIframeMirrors(
        hashId: String,
        origins: List<String>,
        referer: String,
        qualityHintLabel: String
    ): Pair<String?, List<MirrorTask>> {
        for (base in origins) {
            val sess = openInertiaSession("$base/iframe/$hashId", referer) ?: continue
            var tiers = parseStreamTiers(sess.page.optJSONObject("props")?.optJSONObject("streams"))
            if (tiers == null || tiers.length() == 0) {
                val comp = sess.component.ifBlank { "files/mirror/video" }
                val reloadResp = EgyDeadExtractorKit.getText(
                    sess.url,
                    referer = sess.url,
                    headers = buildInertiaHeaders(sess, comp, "streams")
                )
                if (reloadResp != null && reloadResp.body.isNotBlank()) {
                    tiers = runCatching {
                        parseStreamTiers(JSONObject(reloadResp.body).optJSONObject("props")?.optJSONObject("streams"))
                    }.getOrNull()
                }
            }
            if (tiers != null && tiers.length() > 0) {
                val list = ArrayList<MirrorTask>()
                for (i in 0 until tiers.length()) {
                    val tier = tiers.optJSONObject(i) ?: continue
                    val tierLabel = tier.optString("label", qualityHintLabel)
                    val mirrors = tier.optJSONArray("mirrors") ?: continue
                    collectMirrorsInto(mirrors, tierLabel, list)
                }
                if (list.isNotEmpty()) return Pair(sess.url, list)
            }
        }
        return Pair(null, emptyList())
    }

    private fun parseStreamTiers(streamsObj: JSONObject?): JSONArray? {
        if (streamsObj == null) return null
        streamsObj.optJSONArray("data")?.let { return it }
        return streamsObj.optJSONObject("data")?.optJSONArray("arr")
    }

    // Channel 4: `/download/<id>` -> `files/mirror/download` (`POST {"attach": hashId}`)
    private fun fetchDownloadMirrors(
        hashId: String,
        origins: List<String>,
        referer: String,
        qualityHintLabel: String
    ): List<MirrorTask> {
        for (base in origins) {
            val sess = openInertiaSession("$base/download/$hashId", referer) ?: continue
            val attachments = sess.page.optJSONObject("props")?.optJSONArray("attachments") ?: continue
            if (attachments.length() == 0) continue

            val comp = sess.component.ifBlank { "files/mirror/download" }
            val headers = buildInertiaHeaders(sess, comp, "download", isPost = true)
            val tasks = ArrayList<MirrorTask>()

            for (i in 0 until attachments.length()) {
                val att = attachments.optJSONObject(i) ?: continue
                val attachId = att.optString("hashId").ifBlank { att.optString("id") }
                if (attachId.isBlank()) continue
                val tierLabel = att.optString("label")
                    .ifBlank { att.optString("name") }
                    .ifBlank { att.optString("resolution") }
                    .ifBlank { qualityHintLabel }

                val postResp = EgyDeadExtractorKit.postJson(
                    sess.url,
                    jsonBody = JSONObject().put("attach", attachId).toString(),
                    referer = sess.url,
                    headers = headers
                ) ?: continue

                val mirrors = runCatching {
                    val dl = JSONObject(postResp.body).optJSONObject("props")?.optJSONObject("download")
                    dl?.optJSONObject("data")?.optJSONArray("mirrors")
                        ?: dl?.optJSONArray("mirrors")
                }.getOrNull() ?: continue

                collectMirrorsInto(mirrors, tierLabel, tasks)
            }
            if (tasks.isNotEmpty()) return tasks
        }
        return emptyList()
    }

    private fun collectMirrorsInto(mirrors: JSONArray, tierLabel: String, out: MutableList<MirrorTask>) {
        for (j in 0 until mirrors.length()) {
            val m = mirrors.optJSONObject(j) ?: continue
            val driver = m.optString("driver").ifBlank { m.optString("name") }.lowercase().trim()
            var link = (m.optString("link").ifBlank { m.optString("url") }).trim()
            if (link.isBlank()) continue
            if (link.startsWith("//")) link = "https:$link"
            if (!link.startsWith("http")) continue
            out.add(MirrorTask(link, driver, tierLabel))
        }
    }

    private fun mirrorIdentityKey(driver: String, mirrorUrl: String, tierLabel: String): String {
        val d = driver.lowercase().trim()
        val hashFrag = mirrorUrl.substringAfterLast('#', "")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
        if (hashFrag.isNotEmpty()) return "$d:$hashFrag"
        val cleanPath = mirrorUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        val seg = cleanPath.substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
            .trim()
        return if (seg.isNotEmpty()) "$d:$seg" else "$d:$mirrorUrl:$tierLabel"
    }

    companion object {
        private const val LEECH_AES_PASSPHRASE = "k7v2m4x9bqwpnj6t"

        fun isMegaMaxUrl(url: String, serverHint: String = ""): Boolean =
            EgyDeadMirrorRouter.isMegaMaxUrl(url, serverHint)

        fun extractHashId(url: String): String? =
            Regex("""/(?:iframe|download|e|d|f|mirror)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
    }
}

class EgyDeadShare4MaxExtractor : EgyDeadMegaMaxExtractor() {
    override val name = "Share4Max"
    override val mainUrl = "https://share4max.com"
}

class EgyDeadMegaMaxRotatingExtractor : EgyDeadMegaMaxExtractor() {
    override val name = "MegaMax"
    override val mainUrl = "https://q7v3k8m2p1.top"
}

// =========================================================================
// 2. Byse ECDSA P-256 Attestation + Stream PoW + AES-GCM Extractor
// =========================================================================

open class EgyDeadByseExtractor : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://byseraguci.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            ?: return
        val origin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val embedReferer = "$origin/e/$code"

        val details = fetchJsonGet("$origin/api/videos/$code/embed/details", embedReferer)
        val embedFrameUrl = details?.optString("embed_frame_url")?.takeIf { it.startsWith("http") }
        val embedDomain = embedFrameUrl?.let { EgyDeadExtractorKit.originOf(it) } ?: origin
        val playbackCode = details?.optString("code")?.takeIf { it.isNotBlank() }
            ?: embedFrameUrl?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')
                ?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: code
        val parentHost = runCatching { URI(origin).host }.getOrNull() ?: "byseraguci.com"

        val playbackObj = postEmbedPlayback(
            embedOrigin = embedDomain,
            code = playbackCode,
            embedPageUrl = embedFrameUrl ?: "$embedDomain/e/$playbackCode",
            parentHost = parentHost,
            parentFrameUrl = embedReferer,
            parentReferer = referer.ifBlank { "$origin/" }
        )
            ?: details?.optJSONObject("playback")
            ?: (if (embedDomain != origin) fetchJsonGet("$embedDomain/api/videos/$playbackCode/embed/details", "$embedDomain/e/$playbackCode")?.optJSONObject("playback") else null)
            ?: fetchJsonGet("$origin/api/videos/$code", embedReferer)?.optJSONObject("playback")
            ?: return

        val decryptedStr = decryptPlayback(playbackObj) ?: return
        val decrypted = runCatching { JSONObject(decryptedStr) }.getOrNull() ?: return
        val sources = decrypted.optJSONArray("sources") ?: return
        val edgeAuth = solveSha256EdgePow(playbackObj.optJSONObject("edge_challenge"))

        val headers = LinkedHashMap<String, String>().apply {
            put("Referer", "$embedDomain/")
            put("Origin", embedDomain)
            if (edgeAuth != null) put("X-Edge-Auth", edgeAuth)
        }

        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val streamUrl = (s.optString("url").ifBlank { s.optString("file") })
                .takeIf { it.startsWith("http") } ?: continue
            val qVal = EgyDeadExtractorKit.parseQuality(
                s.optString("label").ifBlank { s.optString("quality") },
                s.optInt("height", 0).takeIf { it > 0 }?.let { "${it}p" }
            ).takeIf { it != Qualities.Unknown.value } ?: qualityHint

            if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                EgyDeadExtractorKit.emitHlsWithVariants(name, streamUrl, "$embedDomain/", qVal, headers, callback)
            } else {
                val qTag = EgyDeadExtractorKit.formatQualityTag(qVal)
                val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
                EgyDeadExtractorKit.emitDirectVideo(name, label, streamUrl, "$embedDomain/", qVal, headers, callback)
            }
        }
    }

    private fun fetchJsonGet(apiUrl: String, referer: String): JSONObject? = runCatching {
        val origin = EgyDeadExtractorKit.originOf(referer) ?: mainUrl
        val resp = EgyDeadExtractorKit.getText(
            apiUrl,
            referer = referer,
            headers = mapOf("Accept" to "application/json", "Origin" to origin)
        ) ?: return null
        if (resp.code >= 400) return null
        JSONObject(resp.body)
    }.getOrNull()

    private suspend fun postEmbedPlayback(
        embedOrigin: String,
        code: String,
        embedPageUrl: String,
        parentHost: String,
        parentFrameUrl: String,
        parentReferer: String
    ): JSONObject? = runCatching {
        val baseHeaders = LinkedHashMap<String, String>().apply {
            put("Accept", "*/*")
            put("Origin", embedOrigin)
            put("Referer", embedPageUrl)
            put("X-Embed-Origin", parentHost)
            put("X-Embed-Parent", parentFrameUrl)
            if (parentReferer.startsWith("http")) put("X-Embed-Referer", parentReferer)
        }

        val settings = EgyDeadExtractorKit.getText(
            "$embedOrigin/api/videos/$code/embed/settings",
            referer = embedPageUrl,
            headers = baseHeaders
        )?.let { runCatching { JSONObject(it.body) }.getOrNull() }

        val fingerprint = obtainAttestedFingerprint(embedOrigin, embedPageUrl, baseHeaders)
        var captchaToken: String? = null

        if (settings?.optBoolean("captcha_required", false) == true) {
            val capBody = JSONObject().apply { if (fingerprint != null) put("fingerprint", fingerprint) }.toString()
            val capChallenge = EgyDeadExtractorKit.postJson(
                "$embedOrigin/api/videos/$code/embed/captcha",
                jsonBody = capBody,
                referer = embedPageUrl,
                headers = baseHeaders
            )?.let { runCatching { JSONObject(it.body) }.getOrNull() }

            if (capChallenge != null) {
                val powNonce = capChallenge.optString("pow_nonce")
                val powToken = capChallenge.optString("pow_token")
                val powDiff = capChallenge.optInt("pow_difficulty", 14)
                if (powNonce.isNotBlank() && powToken.isNotBlank()) {
                    val solution = solveByseStreamPowParallel(powNonce, powDiff)
                    if (solution != null) {
                        val verifyBody = JSONObject()
                            .put("pow_token", powToken)
                            .put("solution", solution)
                            .apply { if (fingerprint != null) put("fingerprint", fingerprint) }
                            .toString()
                        val verifyRes = EgyDeadExtractorKit.postJson(
                            "$embedOrigin/api/videos/$code/embed/captcha/verify",
                            jsonBody = verifyBody,
                            referer = embedPageUrl,
                            headers = baseHeaders
                        )?.let { runCatching { JSONObject(it.body) }.getOrNull() }
                        if (verifyRes?.optString("status") == "ok") {
                            captchaToken = verifyRes.optString("token").takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }

        val pbHeaders = HashMap(baseHeaders).apply {
            if (!captchaToken.isNullOrBlank()) put("X-Captcha-Token", captchaToken!!)
        }
        val resText = if (fingerprint != null) {
            EgyDeadExtractorKit.postJson(
                "$embedOrigin/api/videos/$code/embed/playback",
                jsonBody = JSONObject().put("fingerprint", fingerprint).toString(),
                referer = embedPageUrl,
                headers = pbHeaders
            )?.body
        } else {
            EgyDeadExtractorKit.getText(
                "$embedOrigin/api/videos/$code/embed/playback",
                referer = embedPageUrl,
                headers = pbHeaders
            )?.body
        } ?: return null

        val root = JSONObject(resText)
        root.optJSONObject("playback") ?: if (root.has("payload")) root else null
    }.getOrNull()

    private fun obtainAttestedFingerprint(
        embedOrigin: String,
        embedPageUrl: String,
        baseHeaders: Map<String, String>
    ): JSONObject? {
        val now = System.currentTimeMillis()
        attestCache[embedOrigin]?.let { (exp, cached) -> if (exp > now) return cached }
        return runCatching {
            val chResp = EgyDeadExtractorKit.postJson(
                "$embedOrigin/api/videos/access/challenge",
                jsonBody = "",
                referer = embedPageUrl,
                headers = baseHeaders
            ) ?: return null
            val ch = JSONObject(chResp.body)
            val challengeId = ch.optString("challenge_id").takeIf { it.isNotBlank() } ?: return null
            val nonce = ch.optString("nonce").takeIf { it.isNotBlank() } ?: return null

            val kpg = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }
            val kp = kpg.generateKeyPair()
            val pub = kp.public as ECPublicKey
            val xB64 = EgyDeadBase64.encodeUrlSafeNoPad(bigIntToFixed32(pub.w.affineX))
            val yB64 = EgyDeadBase64.encodeUrlSafeNoPad(bigIntToFixed32(pub.w.affineY))

            val derSig = Signature.getInstance("SHA256withECDSA").run {
                initSign(kp.private)
                update(nonce.toByteArray(Charsets.UTF_8))
                sign()
            }
            val p1363SigB64 = EgyDeadBase64.encodeUrlSafeNoPad(derToP1363(derSig))
            val randHash = EgyDeadBase64.encodeUrlSafeNoPad(
                MessageDigest.getInstance("SHA-256").digest("$embedOrigin|$nonce".toByteArray(Charsets.UTF_8))
            )

            val attestPayload = JSONObject().apply {
                put("viewer_id", "")
                put("device_id", "")
                put("challenge_id", challengeId)
                put("nonce", nonce)
                put("signature", p1363SigB64)
                put("public_key", JSONObject().apply {
                    put("kty", "EC")
                    put("crv", "P-256")
                    put("x", xB64)
                    put("y", yB64)
                })
                put("client", JSONObject().apply {
                    put("user_agent", EgyDeadExtractorKit.DESKTOP_UA)
                    put("pixel_ratio", 1)
                    put("screen_width", 1920)
                    put("screen_height", 1080)
                    put("color_depth", 24)
                    put("languages", JSONArray(listOf("en-US", "en")))
                    put("timezone", "UTC")
                    put("hardware_concurrency", 8)
                    put("device_memory", 8)
                    put("touch_points", 0)
                    put("webgl_vendor", "Google Inc. (Intel)")
                    put("webgl_renderer", "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)")
                    put("canvas_hash", randHash)
                    put("audio_hash", randHash)
                    put("pointer_type", "fine,hover")
                    put("media_devices", "ai1ao2vi1")
                    put("extra", JSONObject().apply {
                        put("vendor", "Google Inc.")
                        put("appVersion", EgyDeadExtractorKit.DESKTOP_UA.removePrefix("Mozilla/"))
                    })
                })
                put("storage", JSONObject())
                put("attributes", JSONObject().put("entropy", "medium"))
            }

            val attestResp = EgyDeadExtractorKit.postJson(
                "$embedOrigin/api/videos/access/attest",
                jsonBody = attestPayload.toString(),
                referer = embedPageUrl,
                headers = baseHeaders
            ) ?: return null
            val attestJson = JSONObject(attestResp.body)
            val token = attestJson.optString("token").takeIf { it.isNotBlank() } ?: return null
            val fp = JSONObject()
                .put("token", token)
                .put("viewer_id", attestJson.optString("viewer_id"))
                .put("device_id", attestJson.optString("device_id"))
                .put("confidence", attestJson.optString("confidence", "medium"))
            attestCache[embedOrigin] = (now + 240_000L) to fp
            fp
        }.getOrNull()
    }

    private suspend fun solveByseStreamPowParallel(nonceStr: String, difficulty: Int): String? {
        if (difficulty <= 0) return "0"
        val targetBits = difficulty.coerceAtMost(26)
        val prefixBytes = "$nonceStr:".toByteArray(Charsets.ISO_8859_1)
        val found = AtomicReference<String?>(null)
        val workers = 4
        val maxIterations = 8_000_000L
        coroutineScope {
            (0 until workers).map { workerId ->
                async(Dispatchers.Default) {
                    val e = IntArray(4)
                    val r = IntArray(512)
                    val buf = ByteArray(prefixBytes.size + 16)
                    System.arraycopy(prefixBytes, 0, buf, 0, prefixBytes.size)
                    var nVal = workerId.toLong()
                    while (nVal < maxIterations && found.get() == null) {
                        val sStr = nVal.toString()
                        val totalLen = prefixBytes.size + sStr.length
                        for (idx in sStr.indices) {
                            buf[prefixBytes.size + idx] = sStr[idx].code.toByte()
                        }
                        if (byseGrLeadingZeros(buf, totalLen, e, r) >= targetBits) {
                            found.compareAndSet(null, sStr)
                            break
                        }
                        nVal += workers
                    }
                }
            }.awaitAll()
        }
        return found.get()
    }

    private fun byseQuarterRound(e: IntArray) {
        e[0] = e[0] + e[1]
        e[3] = Integer.rotateLeft(e[3] xor e[0], 16)
        e[2] = e[2] + e[3]
        e[1] = Integer.rotateLeft(e[1] xor e[2], 12)
        e[0] = e[0] + e[1]
        e[3] = Integer.rotateLeft(e[3] xor e[0], 8)
        e[2] = e[2] + e[3]
        e[1] = Integer.rotateLeft(e[1] xor e[2], 7)
    }

    private fun byseGrLeadingZeros(input: ByteArray, length: Int, e: IntArray, r: IntArray): Int {
        e[0] = 0x6A09E667
        e[1] = -0x4498517B
        e[2] = 0x3C6EF372
        e[3] = -0x5AB00AC6
        for (i in 0 until length) {
            e[0] = e[0] + (input[i].toInt() and 0xFF)
            e[0] = Integer.rotateLeft(e[0], 7)
            byseQuarterRound(e)
        }
        for (i in 0 until 8) byseQuarterRound(e)
        for (i in 0 until 512) {
            byseQuarterRound(e)
            r[i] = e[0] xor e[2]
        }
        for (round in 0 until 2) {
            for (s in 0 until 512) {
                val a = r[s] and 511
                var c = r[s] + r[a]
                c = Integer.rotateLeft(c, 13)
                c = c xor (r[(s + 1) and 511] * (-0x61C8864F))
                r[s] = c
                e[0] = e[0] xor c
                byseQuarterRound(e)
            }
        }
        var lz = 0
        for (i in 0 until 8) {
            byseQuarterRound(e)
            var sVal = e[0]
            val base = i shl 6
            for (c in 0 until 64) {
                val d = r[base + c]
                sVal += d
                sVal = Integer.rotateLeft(sVal, 5)
                sVal = sVal xor (d * (-0x7A143589))
            }
            val word = sVal xor e[2]
            if (word == 0) {
                lz += 32
            } else {
                lz += Integer.numberOfLeadingZeros(word)
                break
            }
        }
        return lz
    }

    private fun bigIntToFixed32(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        val out = ByteArray(32)
        copyRightAligned32(raw, out, 0)
        return out
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2
        if ((der[1].toInt() and 0x80) != 0) offset += (der[1].toInt() and 0x7F)
        val rLen = der[offset + 1].toInt() and 0xFF
        val rBytes = der.copyOfRange(offset + 2, offset + 2 + rLen)
        val sPos = offset + 2 + rLen
        val sLen = der[sPos + 1].toInt() and 0xFF
        val sBytes = der.copyOfRange(sPos + 2, sPos + 2 + sLen)
        val out = ByteArray(64)
        copyRightAligned32(rBytes, out, 0)
        copyRightAligned32(sBytes, out, 32)
        return out
    }

    private fun copyRightAligned32(src: ByteArray, dst: ByteArray, dstOffset: Int) {
        val start = if (src.size > 32) src.size - 32 else 0
        val len = minOf(src.size - start, 32)
        System.arraycopy(src, start, dst, dstOffset + (32 - len), len)
    }

    private fun decryptPlayback(pb: JSONObject): String? {
        val keyParts = pb.optJSONArray("key_parts")
        if (keyParts != null && keyParts.length() >= 1) {
            val total = keyParts.length()
            val v = pb.optString("version", "1").trim().toIntOrNull() ?: pb.optInt("version", 1)
            val iv1 = runCatching { EgyDeadBase64.decodeUrlSafe(pb.optString("iv")) }.getOrNull()
            val payload1 = runCatching { EgyDeadBase64.decodeUrlSafe(pb.optString("payload")) }.getOrNull()
            if (iv1 != null && payload1 != null) {
                val candidateKeys = ArrayList<ByteArray>()
                if (v in 1..20 && v <= total && (31 - v) in 1..total) {
                    candidateKeys.add(
                        EgyDeadBase64.decodeUrlSafe(keyParts.optString(v - 1)) +
                            EgyDeadBase64.decodeUrlSafe(keyParts.optString(30 - v))
                    )
                }
                if (total >= 2) {
                    val idxA = (v - 1).coerceIn(0, total - 1)
                    val idxB = (total - v).coerceIn(0, total - 1)
                    candidateKeys.add(
                        EgyDeadBase64.decodeUrlSafe(keyParts.optString(idxA)) +
                            EgyDeadBase64.decodeUrlSafe(keyParts.optString(idxB))
                    )
                    candidateKeys.add(
                        EgyDeadBase64.decodeUrlSafe(keyParts.optString(0)) +
                            EgyDeadBase64.decodeUrlSafe(keyParts.optString(1))
                    )
                }
                runCatching {
                    var all = ByteArray(0)
                    for (idx in 0 until total) all += EgyDeadBase64.decodeUrlSafe(keyParts.optString(idx))
                    if (all.size in listOf(16, 24, 32)) candidateKeys.add(all)
                }
                for (key in candidateKeys) {
                    aesGcmDecrypt(key, iv1, payload1)?.let { plain ->
                        if (plain.contains("sources")) return plain
                    }
                }
            }
        }

        val p2 = pb.optString("payload2")
        val iv2s = pb.optString("iv2")
        if (p2.isNotBlank() && iv2s.isNotBlank()) {
            val dk = pb.optJSONObject("decrypt_keys")
            val e1 = dk?.optString("edge_1")?.takeIf { it.isNotBlank() }
            val e2 = dk?.optString("edge_2")?.takeIf { it.isNotBlank() }
            if (e1 != null && e2 != null) {
                val key2 = EgyDeadBase64.decodeUrlSafe(e1) + EgyDeadBase64.decodeUrlSafe(e2)
                aesGcmDecrypt(key2, EgyDeadBase64.decodeUrlSafe(iv2s), EgyDeadBase64.decodeUrlSafe(p2))?.let { return it }
            }
        }
        return null
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.getOrNull()

    private fun solveSha256EdgePow(challenge: JSONObject?): String? {
        if (challenge == null || !challenge.optBoolean("enabled", false)) return null
        val token = challenge.optString("token").takeIf { it.isNotBlank() } ?: return null
        val ts = challenge.optLong("timestamp", 0L)
        val difficulty = challenge.optInt("difficulty", 0)
        if (difficulty <= 0 || difficulty > 24) return null
        val fullBytes = difficulty / 8
        val remBits = difficulty % 8
        val mask = if (remBits == 0) 0 else (0xFF shl (8 - remBits)) and 0xFF
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = "$token$ts"
        for (nonce in 0..2_000_000) {
            val digest = md.digest("$prefix$nonce".toByteArray(Charsets.UTF_8))
            var ok = true
            for (b in 0 until fullBytes) {
                if (digest[b].toInt() != 0) {
                    ok = false
                    break
                }
            }
            if (ok && remBits > 0 && (digest[fullBytes].toInt() and mask) != 0) ok = false
            if (ok) return "$token.$ts.$nonce"
        }
        return null
    }

    companion object {
        private val attestCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    }
}

// =========================================================================
// 3. Vidstack AES-128-CBC SPA Extractors (`UPNShare`, `StreamP2P`, `RPMShare`)
// =========================================================================

abstract class EgyDeadVidstackExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast('#', "")
            .substringBefore('&')
            .substringBefore('?')
            .trim()
            .ifEmpty {
                url.substringBefore('?').substringBefore('#').trimEnd('/')
                    .substringAfterLast('/')
                    .takeIf { it.isNotBlank() && !it.contains('.') }
                    .orEmpty()
            }
        if (videoId.isBlank()) return

        val rawHost = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        val candidateOrigins = buildList {
            if (rawHost == "rpmplay.me" || rawHost == "www.rpmplay.me") add("https://megamax.rpmplay.me")
            if (rawHost == "uns.bio" || rawHost == "www.uns.bio") add("https://megamax.uns.bio")
            if (rawHost == "strp2p.site" || rawHost == "www.strp2p.site") add("https://megamax.strp2p.site")
            EgyDeadExtractorKit.originOf(url)?.let { add(it) }
            add(mainUrl)
        }.distinct()

        for (origin in candidateOrigins) {
            val resp = EgyDeadExtractorKit.getText(
                "$origin/api/v1/video?id=$videoId",
                referer = "$origin/",
                headers = mapOf("Origin" to origin, "Accept" to "*/*")
            ) ?: continue
            val rawHex = resp.body.trim().trim('"')
            if (rawHex.length < 32 || rawHex.startsWith("{")) continue

            val decrypted = decryptVidstackHex(rawHex) ?: continue
            val root = runCatching { JSONObject(decrypted) }.getOrNull() ?: continue

            val streamUrls = LinkedHashSet<String>()
            collectVidstackUrls(root.opt("cfNative"), origin, streamUrls)
            collectVidstackUrls(root.opt("source"), origin, streamUrls)
            if (streamUrls.isEmpty()) collectVidstackUrls(root.opt("cf"), origin, streamUrls)
            if (streamUrls.isEmpty()) continue

            val streamHeaders = mapOf("Referer" to "$origin/", "Origin" to origin)
            for (hlsUrl in streamUrls) {
                EgyDeadExtractorKit.emitHlsWithVariants(name, hlsUrl, "$origin/", qualityHint, streamHeaders, callback)
            }
            return
        }
    }

    private fun collectVidstackUrls(node: Any?, origin: String, out: MutableSet<String>) {
        when (node) {
            is String -> normalizeVidstackUrl(node, origin)?.let { out.add(it) }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    normalizeVidstackUrl(node.optString(keys.next()), origin)?.let { out.add(it) }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectVidstackUrls(node.opt(i), origin, out)
            }
        }
    }

    private fun normalizeVidstackUrl(raw: String, origin: String): String? {
        val clean = raw.trim()
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$origin$clean"
            else -> null
        }
    }

    private fun decryptVidstackHex(hexCipher: String): String? = runCatching {
        val clean = hexCipher.replace(Regex("""\s+"""), "")
        if (clean.length % 2 != 0) return null
        val cipherBytes = EgyDeadExtractorKit.hexToBytes(clean)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(VIDSTACK_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(VIDSTACK_AES_IV.toByteArray(Charsets.UTF_8))
        )
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim { it <= ' ' || it.code < 0x20 }
    }.getOrNull()

    companion object {
        private const val VIDSTACK_AES_KEY = "kiemtienmua911ca"
        private const val VIDSTACK_AES_IV = "1234567890oiuytr"
    }
}

class EgyDeadUpnShareExtractor : EgyDeadVidstackExtractor() {
    override val name = "UPNShare"
    override val mainUrl = "https://megamax.uns.bio"
}

class EgyDeadStreamP2PExtractor : EgyDeadVidstackExtractor() {
    override val name = "StreamP2P"
    override val mainUrl = "https://megamax.strp2p.site"
}

class EgyDeadRpmShareExtractor : EgyDeadVidstackExtractor() {
    override val name = "RPMShare"
    override val mainUrl = "https://megamax.rpmplay.me"
}

// =========================================================================
// 4. FireStream (`firestream.to/e/<slug>` -> `firestream.site` `/resolve`)
// =========================================================================

class EgyDeadFireStreamExtractor : ExtractorApi() {
    override val name = "FireStream"
    override val mainUrl = "https://firestream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            ?: return
        val initialOrigin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val embedUrl = "$initialOrigin/e/$slug"

        val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer.ifBlank { "$initialOrigin/" }) ?: return
        val finalUrl = resp.finalUrl.ifBlank { embedUrl }
        val finalOrigin = EgyDeadExtractorKit.originOf(finalUrl) ?: "https://firestream.site"
        val cookieHeader = EgyDeadExtractorKit.formatCookieHeader(resp.cookies)
        val doc = Jsoup.parse(resp.body)

        doc.selectFirst("script#video-data")?.let { scriptEl ->
            val rawVideoData = scriptEl.data().ifBlank { scriptEl.html() }.trim()
            runCatching {
                val subs = JSONObject(rawVideoData).optJSONObject("video")?.optJSONArray("subtitles")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val subUrl = (s.optString("url").ifBlank { s.optString("file") })
                            .takeIf { it.startsWith("http") } ?: continue
                        val lang = s.optString("label").ifBlank { s.optString("language").ifBlank { "Arabic" } }
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                }
            }
        }

        val tokenBlob = doc.selectFirst("script#token-blob")?.let {
            it.data().ifBlank { it.html() }.trim()
        }?.takeIf { it.isNotBlank() } ?: return

        val postHeaders = LinkedHashMap<String, String>().apply {
            put("Origin", finalOrigin)
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
        val resolveResp = EgyDeadExtractorKit.postJson(
            "$finalOrigin/api/videos/$slug/resolve",
            jsonBody = JSONObject().put("blob", tokenBlob).toString(),
            referer = finalUrl,
            headers = postHeaders
        ) ?: return

        val resolveJson = runCatching { JSONObject(resolveResp.body) }.getOrNull() ?: return
        val primaryUrl = resolveJson.optString("signedVideoUrl").takeIf { it.startsWith("http") }
        val sdUrl = resolveJson.optString("signedVideoSdUrl").takeIf { it.startsWith("http") }
        val streamHeaders = mapOf("Referer" to "$finalOrigin/", "Origin" to finalOrigin)

        if (primaryUrl != null) {
            emitFireMedia(primaryUrl, finalOrigin, streamHeaders, qualityHint, callback)
        }
        if (sdUrl != null && sdUrl != primaryUrl) {
            val sdQ = if (qualityHint in 1..Qualities.P480.value) qualityHint else Qualities.P480.value
            emitFireMedia(sdUrl, finalOrigin, streamHeaders, sdQ, callback)
        }
    }

    private suspend fun emitFireMedia(
        mediaUrl: String,
        origin: String,
        headers: Map<String, String>,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
            EgyDeadExtractorKit.emitHlsWithVariants(name, mediaUrl, "$origin/", quality, headers, callback)
        } else {
            val qTag = EgyDeadExtractorKit.formatQualityTag(quality)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
            EgyDeadExtractorKit.emitDirectVideo(name, label, mediaUrl, "$origin/", quality, headers, callback)
        }
    }
}

// =========================================================================
// 5. Vidara (`vidaraa.cc`, `vidara.so`, `vidara.to` -> `POST /api/stream`)
// =========================================================================

class EgyDeadVidaraExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidaraa.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filecode = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() }
            ?: return
        val initialOrigin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val embedUrl = "$initialOrigin/e/$filecode"

        val pageResp = EgyDeadExtractorKit.getText(embedUrl, referer = referer.ifBlank { "$initialOrigin/" })
        val finalEmbedUrl = pageResp?.finalUrl?.ifBlank { embedUrl } ?: embedUrl
        val finalOrigin = EgyDeadExtractorKit.originOf(finalEmbedUrl) ?: initialOrigin
        val cookieHeader = pageResp?.cookies?.let { EgyDeadExtractorKit.formatCookieHeader(it) }.orEmpty()

        val apiHeaders = LinkedHashMap<String, String>().apply {
            put("Origin", finalOrigin)
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
        val payload = JSONObject()
            .put("filecode", filecode)
            .put("device", "web")
            .put("codecs", JSONArray())
            .toString()

        val apiResp = EgyDeadExtractorKit.postJson(
            "$finalOrigin/api/stream",
            jsonBody = payload,
            referer = finalEmbedUrl,
            headers = apiHeaders
        ) ?: return

        val streamJson = runCatching { JSONObject(apiResp.body) }.getOrNull() ?: return
        streamJson.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                if (s.optInt("type", 0) != 0) continue
                val subUrl = s.optString("file_path").takeIf { it.startsWith("http") } ?: continue
                val lang = s.optString("language").ifBlank { s.optString("label").ifBlank { "Arabic" } }
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }
        }

        val streamingUrl = streamJson.optString("streaming_url").takeIf { it.startsWith("http") } ?: return
        val qVal = qualityHint.takeIf { it != Qualities.Unknown.value }
            ?: EgyDeadExtractorKit.parseQuality(streamJson.optString("title"))
        val streamHeaders = mapOf("Referer" to "$finalOrigin/", "Origin" to finalOrigin)
        EgyDeadExtractorKit.emitHlsWithVariants(name, streamingUrl, "$finalOrigin/", qVal, streamHeaders, callback)
    }
}

// =========================================================================
// 6. StreamHG / StreamWish Unified Extractor
//    (`vibuxer.com`, `hanerix.com`, `hgcloud.to/<id>`, `cavanhabg.com`, `hglink.to`)
// =========================================================================

open class EgyDeadStreamHGExtractor : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://vibuxer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val codeId = extractCode(url) ?: return
        val incomingHost = runCatching { URI(url).host?.lowercase()?.removePrefix("www.").orEmpty() }.getOrDefault("")
        val candidateEmbeds = LinkedHashSet<String>()
        if (incomingHost.isNotBlank() && incomingHost != "hgcloud.to" && incomingHost != "hglink.to") {
            candidateEmbeds.add("https://$incomingHost/e/$codeId")
        }
        candidateEmbeds.add("https://hanerix.com/e/$codeId")
        candidateEmbeds.add("https://vibuxer.com/e/$codeId")
        candidateEmbeds.add("https://cavanhabg.com/e/$codeId")
        candidateEmbeds.add("https://dumbalag.com/e/$codeId")

        for (embedUrl in candidateEmbeds) {
            val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer) ?: continue
            if (resp.code >= 400 || resp.body.length < 300) continue
            val finalUrl = resp.finalUrl.ifBlank { embedUrl }
            val activeOrigin = EgyDeadExtractorKit.originOf(finalUrl)
                ?: EgyDeadExtractorKit.originOf(embedUrl)
                ?: mainUrl
            val fullText = EgyDeadExtractorKit.unpackJs(resp.body)

            Regex("""file\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']\s*,\s*label\s*:\s*["']([^"']+)["']""")
                .findAll(fullText)
                .forEach { m ->
                    val subUrl = resolveStreamUrl(m.groupValues[1], activeOrigin)
                    if (subUrl != null) subtitleCallback(newSubtitleFile(m.groupValues[2], subUrl))
                }

            val m3u8Candidates = LinkedHashSet<String>()
            Regex("""var\s+links\s*=\s*(\{[^;]+\})""").find(fullText)?.groupValues?.get(1)?.let { jsonRaw ->
                runCatching {
                    val obj = JSONObject(jsonRaw)
                    for (k in listOf("hls4", "hls2", "hls3", "hls")) {
                        resolveStreamUrl(obj.optString(k), activeOrigin)?.let { m3u8Candidates.add(it) }
                    }
                }
            }
            for (k in listOf("hls4", "hls2", "hls3")) {
                Regex("""["']$k["']\s*:\s*["']([^"']+)["']""").findAll(fullText).forEach { m ->
                    resolveStreamUrl(m.groupValues[1], activeOrigin)?.let { m3u8Candidates.add(it) }
                }
            }
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(fullText).forEach { m ->
                resolveStreamUrl(m.groupValues[1], activeOrigin)?.let { m3u8Candidates.add(it) }
            }

            var emitted = false
            val streamHeaders = mapOf("Referer" to "$activeOrigin/", "Origin" to activeOrigin)
            for (hls in m3u8Candidates) {
                if (EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$activeOrigin/", qualityHint, streamHeaders, callback)) {
                    emitted = true
                    break
                }
            }
            if (emitted) return
        }
    }

    private fun resolveStreamUrl(raw: String, origin: String): String? {
        val clean = raw.trim().replace("\\/", "/")
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$origin$clean"
            else -> null
        }
    }

    private fun extractCode(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        if (path.isBlank()) return null
        return path.substringAfterLast('/')
            .removePrefix("embed-")
            .removeSuffix(".html")
            .takeIf { it.length >= 6 }
    }
}

class EgyDeadHanerixExtractor : EgyDeadStreamHGExtractor() { override val mainUrl = "https://hanerix.com" }
class EgyDeadHgCloudExtractor : EgyDeadStreamHGExtractor() { override val mainUrl = "https://hgcloud.to" }

// =========================================================================
// 7. EarnVids / VidHide Extractor (`morencius.com/file/<id>`, `/v/<id>`, `/embed/<id>`)
// =========================================================================

open class EgyDeadEarnVidsExtractor : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val codeId = Regex("""/(?:file|embed|e|v|f|d)/([A-Za-z0-9_-]{6,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: runCatching { URI(url).path.orEmpty().trim('/').substringAfterLast('/').removePrefix("embed-").removeSuffix(".html") }
                .getOrNull()?.takeIf { it.length >= 6 }
            ?: return

        val incomingOrigin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val candidates = LinkedHashSet<String>().apply {
            add("$incomingOrigin/v/$codeId")
            add("$incomingOrigin/embed/$codeId")
            add("https://morencius.com/v/$codeId")
            add("https://morencius.com/embed/$codeId")
            add("https://smoothpre.com/v/$codeId")
            add("https://movearnpre.com/v/$codeId")
        }

        for (candidate in candidates) {
            val resp = EgyDeadExtractorKit.getText(candidate, referer = referer.ifBlank { "$incomingOrigin/" }) ?: continue
            if (resp.code >= 400 || resp.body.length < 300) continue
            val finalEmbedUrl = resp.finalUrl.ifBlank { candidate }
            val activeOrigin = EgyDeadExtractorKit.originOf(finalEmbedUrl)
                ?: EgyDeadExtractorKit.originOf(candidate)
                ?: incomingOrigin
            val fullText = EgyDeadExtractorKit.unpackJs(resp.body)

            val streams = LinkedHashSet<String>()
            Regex("""var\s+links\s*=\s*(\{[^;]+\})""").find(fullText)?.groupValues?.get(1)?.let { rawJson ->
                runCatching {
                    val obj = JSONObject(rawJson)
                    for (k in listOf("hls4", "hls2", "hls3", "hls")) {
                        resolveUrl(obj.optString(k), activeOrigin)?.let { streams.add(it) }
                    }
                }
            }
            for (k in listOf("hls4", "hls2", "hls3", "hls")) {
                Regex("""["']$k["']\s*:\s*["']([^"']+)["']""").findAll(fullText).forEach { m ->
                    resolveUrl(m.groupValues[1], activeOrigin)?.let { streams.add(it) }
                }
            }
            Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(fullText).forEach { m ->
                streams.add(m.groupValues[1])
            }

            if (streams.isEmpty()) continue

            val streamHeaders = mapOf("Referer" to finalEmbedUrl, "Origin" to activeOrigin)
            var emitted = false
            for (hls in streams) {
                if (EgyDeadExtractorKit.emitHlsWithVariants(name, hls, finalEmbedUrl, qualityHint, streamHeaders, callback)) {
                    emitted = true
                    break
                }
            }
            if (emitted) return
        }
    }

    private fun resolveUrl(raw: String, origin: String): String? {
        val clean = raw.trim().replace("\\/", "/")
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$origin$clean"
            else -> null
        }
    }
}

// =========================================================================
// 8. Mixdrop Extractor (`mixdrop.top/f/<id>` -> redirect-aware `MDCore.wurl`)
// =========================================================================

open class EgyDeadMixdropExtractor : ExtractorApi() {
    override val name = "Mixdrop"
    override val mainUrl = "https://mixdrop.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val codeId = Regex("""/(?:f|e)/([A-Za-z0-9_-]{6,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val incomingOrigin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val candidates = listOf(
            "$incomingOrigin/e/$codeId",
            "https://mixdrop.top/e/$codeId",
            "https://mixdrop.ps/e/$codeId",
            "https://mixdrop.ag/e/$codeId"
        ).distinct()

        for (embedUrl in candidates) {
            val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer.ifBlank { "$incomingOrigin/" }) ?: continue
            if (resp.code >= 400 || resp.body.length < 300) continue
            val finalEmbedUrl = resp.finalUrl.ifBlank { embedUrl }
            val finalOrigin = EgyDeadExtractorKit.originOf(finalEmbedUrl)
                ?: EgyDeadExtractorKit.originOf(embedUrl)
                ?: incomingOrigin
            val unpacked = EgyDeadExtractorKit.unpackJs(resp.body)
            val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)?.trim()
                ?: continue
            val videoUrl = when {
                wurl.startsWith("//") -> "https:$wurl"
                wurl.startsWith("http") -> wurl
                wurl.startsWith("/") -> "$finalOrigin$wurl"
                else -> "https://$wurl"
            }
            val qVal = qualityHint.takeIf { it != Qualities.Unknown.value }
                ?: EgyDeadExtractorKit.parseQuality(videoUrl)
            val qTag = EgyDeadExtractorKit.formatQualityTag(qVal)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
            val headers = mapOf("Referer" to "$finalOrigin/", "Origin" to finalOrigin)
            if (EgyDeadExtractorKit.emitDirectVideo(name, label, videoUrl, "$finalOrigin/", qVal, headers, callback)) {
                return
            }
        }
    }
}

// =========================================================================
// 9. DoodStream / Playmogo Extractor (`playmogo.com/d/<id>`, `dsvplay.com`, `myvidplay.com`)
// =========================================================================

open class EgyDeadDoodExtractor : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val codeId = Regex("""/(?:d|e)/([A-Za-z0-9_-]{6,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val incomingOrigin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val candidates = listOf(
            "$incomingOrigin/e/$codeId",
            "https://dsvplay.com/e/$codeId",
            "https://myvidplay.com/e/$codeId",
            "https://d000d.com/e/$codeId"
        ).distinct()

        for (embedUrl in candidates) {
            val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer.ifBlank { "$incomingOrigin/" }) ?: continue
            if (resp.code >= 400 || resp.body.length < 300) continue
            val activeOrigin = EgyDeadExtractorKit.originOf(resp.finalUrl)
                ?: EgyDeadExtractorKit.originOf(embedUrl)
                ?: mainUrl
            val passMd5Path = Regex("""/pass_md5/[^'"\s]+""").find(resp.body)?.value ?: continue
            val token = passMd5Path.substringAfterLast('/')
            val md5Resp = EgyDeadExtractorKit.getText(
                "$activeOrigin$passMd5Path",
                referer = resp.finalUrl.ifBlank { embedUrl },
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ) ?: continue
            val streamPrefix = md5Resp.body.trim()
            if (!streamPrefix.startsWith("http")) continue

            val rand = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val finalMp4 = "$streamPrefix$rand?token=$token&expiry=${System.currentTimeMillis()}"
            val qVal = qualityHint.takeIf { it != Qualities.Unknown.value }
                ?: EgyDeadExtractorKit.parseQuality(Jsoup.parse(resp.body).title())
            val qTag = EgyDeadExtractorKit.formatQualityTag(qVal)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
            if (EgyDeadExtractorKit.emitDirectVideo(name, label, finalMp4, "$activeOrigin/", qVal, callback = callback)) {
                return
            }
        }
    }
}

// =========================================================================
// 10. VOE Extractor (Multi-Hop Redirect + 5-Step JSON Decryptor)
// =========================================================================

class EgyDeadVoeExtractor : ExtractorApi() {
    override val name = "VOE"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = Regex("""voe\.sx/(?:e/)?([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.let { "https://voe.sx/e/$it" } ?: url
        var body = ""
        repeat(4) {
            val resp = EgyDeadExtractorKit.getText(currentUrl, referer = referer) ?: return
            currentUrl = resp.finalUrl.ifBlank { currentUrl }
            body = resp.body
            val jsRedirect = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(body)?.groupValues?.get(1)
                ?: Regex("""location\.replace\(\s*['"](https?://[^'"]+)['"]\s*\)""").find(body)?.groupValues?.get(1)
                ?: Regex("""http-equiv=["']refresh["'][^>]*url=['"]?(https?://[^'">\s]+)""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
            if (jsRedirect != null && jsRedirect != currentUrl) {
                currentUrl = jsRedirect
            } else {
                return@repeat
            }
        }
        if (body.isBlank()) return
        val activeOrigin = EgyDeadExtractorKit.originOf(currentUrl) ?: mainUrl
        val headers = mapOf("Referer" to "$activeOrigin/", "Origin" to activeOrigin)

        var emitted = false
        val appJsonScripts = Regex(
            """<script[^>]*type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(body)

        for (match in appJsonScripts) {
            val decoded = decryptVoePayload(match.groupValues[1]) ?: continue
            val hls = decoded.optString("source").takeIf { it.startsWith("http") }
            val mp4 = decoded.optString("file").takeIf { it.startsWith("http") }
            val direct = decoded.optJSONArray("direct_access_url")?.optString(0)?.takeIf { it.startsWith("http") }
                ?: decoded.optString("direct_access_url").takeIf { it.startsWith("http") }

            if (hls != null && EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$activeOrigin/", qualityHint, headers, callback)) {
                emitted = true
            }
            if (mp4 != null && mp4 != hls) {
                EgyDeadExtractorKit.emitDirectVideo(name, "$name MP4", mp4, "$activeOrigin/", qualityHint, headers, callback)
                emitted = true
            }
            if (direct != null && direct != mp4) {
                EgyDeadExtractorKit.emitDirectVideo(name, "$name Direct", direct, "$activeOrigin/", qualityHint, headers, callback)
                emitted = true
            }
        }
        if (emitted) return

        Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(body)?.groupValues?.get(1)?.let { raw ->
            val hls = if (raw.startsWith("http")) raw else runCatching {
                String(EgyDeadBase64.decode(raw), Charsets.UTF_8)
            }.getOrNull()
            if (!hls.isNullOrBlank() && hls.startsWith("http")) {
                EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$activeOrigin/", qualityHint, headers, callback)
                return
            }
        }

        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(body)?.value?.let { hlsUrl ->
            EgyDeadExtractorKit.emitHlsWithVariants(name, hlsUrl, "$activeOrigin/", qualityHint, headers, callback)
        }
    }

    private fun decryptVoePayload(raw: String): JSONObject? = runCatching {
        val rot13 = buildString(raw.length) {
            for (c in raw) {
                when (c) {
                    in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                    in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                    else -> append(c)
                }
            }
        }
        var stripped = rot13
        for (pat in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            stripped = stripped.replace(pat, "_")
        }
        stripped = stripped.replace("_", "")
        val step1 = String(EgyDeadBase64.decode(stripped), Charsets.ISO_8859_1)
        val shiftedReversed = buildString(step1.length) {
            for (i in step1.length - 1 downTo 0) {
                append((step1[i].code - 3).toChar())
            }
        }
        val finalJson = String(EgyDeadBase64.decode(shiftedReversed), Charsets.UTF_8)
        JSONObject(finalJson)
    }.getOrNull()
}

// =========================================================================
// 11. FileMoon Extractor (Iframe Hop + Packed JS + Byse Fallback)
// =========================================================================

class EgyDeadFileMoonExtractor : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/d/", "/e/")
        val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer) ?: return
        var activeHtml = resp.body
        var activeReferer = resp.finalUrl.ifBlank { embedUrl }

        val innerIframe = Jsoup.parse(activeHtml).selectFirst("iframe[src]")?.attr("src")?.trim()
        if (!innerIframe.isNullOrBlank()) {
            val resolvedIframe = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
            if (resolvedIframe.contains("byse", ignoreCase = true)) {
                EgyDeadByseExtractor().extract(resolvedIframe, activeReferer, qualityHint, callback)
                return
            }
            val innerResp = EgyDeadExtractorKit.getText(resolvedIframe, referer = activeReferer)
            if (innerResp != null && innerResp.body.isNotBlank()) {
                activeHtml = innerResp.body
                activeReferer = innerResp.finalUrl.ifBlank { resolvedIframe }
            }
        }

        val fullText = EgyDeadExtractorKit.unpackJs(activeHtml)
        val hls = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(fullText)?.groupValues?.get(1)
        if (!hls.isNullOrBlank()) {
            val activeOrigin = EgyDeadExtractorKit.originOf(activeReferer) ?: mainUrl
            EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$activeOrigin/", qualityHint, callback = callback)
            return
        }

        // Modern FileMoon embeds also serve `/api/videos/<code>/embed/details` (Byse backend)
        EgyDeadByseExtractor().extract(embedUrl, referer, qualityHint, callback)
    }
}

// =========================================================================
// 12. MediaFire, Mp4Upload, Uqload, VK, OkRu, Vidoza, KoramaUp, Upvid, SaveFiles, LuluStream
// =========================================================================

class EgyDeadMediaFireExtractor : ExtractorApi() {
    override val name = "MediaFire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val resp = EgyDeadExtractorKit.getText(url, referer = referer) ?: return
        val doc = Jsoup.parse(resp.body)
        val dlBtn = doc.selectFirst("#downloadButton")
        val directUrl = dlBtn?.attr("href")?.takeIf { it.startsWith("http") }
            ?: dlBtn?.attr("data-scrambled-url")?.takeIf { it.isNotBlank() }?.let {
                runCatching { String(EgyDeadBase64.decode(it), Charsets.UTF_8) }.getOrNull()
            }?.takeIf { it.startsWith("http") }
            ?: Regex("""https?://download\d*\.mediafire\.com/[^"'\s<>\\]+""").find(resp.body)?.value
            ?: return
        val qVal = qualityHint.takeIf { it != Qualities.Unknown.value } ?: EgyDeadExtractorKit.parseQuality(doc.title(), directUrl)
        val qTag = EgyDeadExtractorKit.formatQualityTag(qVal)
        val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
        EgyDeadExtractorKit.emitDirectVideo(name, label, directUrl, url, qVal, callback = callback)
    }
}

class EgyDeadMp4UploadExtractor : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val codeId = Regex("""mp4upload\.[a-z]+/(?:embed-)?([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.removeSuffix(".html") ?: return
        val embedUrl = "https://www.mp4upload.com/embed-$codeId.html"
        val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer) ?: return
        val fullText = EgyDeadExtractorKit.unpackJs(resp.body)
        val mp4 = Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(fullText)?.groupValues?.get(1)
            ?: Regex("""https?://[a-z0-9.-]*mp4upload\.com(?::\d+)?/[^\s"'<>\\]+\.mp4""", RegexOption.IGNORE_CASE).find(fullText)?.value
            ?: return
        val heightHint = Regex("""\bheight\s*=\s*(\d{3,4})\b""").find(fullText)?.groupValues?.get(1)?.let { "${it}p" }
        val qVal = qualityHint.takeIf { it != Qualities.Unknown.value } ?: EgyDeadExtractorKit.parseQuality(heightHint, mp4)
        val qTag = EgyDeadExtractorKit.formatQualityTag(qVal)
        val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
        EgyDeadExtractorKit.emitDirectVideo(
            name, label, mp4, embedUrl, qVal,
            extraHeaders = mapOf("Origin" to "https://www.mp4upload.com"),
            callback = callback
        )
    }
}

class EgyDeadUqloadExtractor : ExtractorApi() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val codeId = Regex("""uqload\.[a-z]+/(?:e/|embed-)?([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.removeSuffix(".html") ?: return
        val incomingHost = runCatching { URI(url).host?.lowercase() }.getOrNull()
        val domains = listOfNotNull(incomingHost, "uqload.vc", "uqload.is", "uqload.net", "uqload.to").distinct()
        for (d in domains) {
            val embedUrl = "https://$d/embed-$codeId.html"
            val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer) ?: continue
            val origin = EgyDeadExtractorKit.originOf(resp.finalUrl) ?: "https://$d"
            val mp4 = Regex("""sources\s*:\s*\[\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(resp.body)?.groupValues?.get(1)
                ?: continue
            val qTag = EgyDeadExtractorKit.formatQualityTag(qualityHint)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
            if (EgyDeadExtractorKit.emitDirectVideo(name, label, mp4, "$origin/", qualityHint, callback = callback)) return
        }
    }
}

class EgyDeadVkExtractor : ExtractorApi() {
    override val name = "VK Video"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, callback)
    }

    suspend fun extract(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val origin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val resp = EgyDeadExtractorKit.getText(url, referer = "$origin/") ?: return
        val headers = mapOf("Referer" to "$origin/")
        val seen = HashSet<String>()
        for ((field, qVal, qLabel) in listOf(
            Triple("url2160", Qualities.P2160.value, "2160p"),
            Triple("url1440", Qualities.P1440.value, "1440p"),
            Triple("url1080", Qualities.P1080.value, "1080p"),
            Triple("url720", Qualities.P720.value, "720p"),
            Triple("url480", Qualities.P480.value, "480p"),
            Triple("url360", Qualities.P360.value, "360p"),
            Triple("url240", Qualities.P240.value, "240p")
        )) {
            val mp4 = Regex(""""$field"\s*:\s*"([^"]+)"""").find(resp.body)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.replace("\\u0026", "&") ?: continue
            if (mp4.startsWith("http") && seen.add(mp4)) {
                EgyDeadExtractorKit.emitDirectVideo(name, "$name ($qLabel)", mp4, "$origin/", qVal, headers, callback)
            }
        }
        Regex(""""hls"\s*:\s*"([^"]+)"""").find(resp.body)?.groupValues?.get(1)
            ?.replace("\\/", "/")?.replace("\\u0026", "&")
            ?.takeIf { it.startsWith("http") }
            ?.let { hls ->
                EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$origin/", Qualities.Unknown.value, headers, callback)
            }
    }
}

class EgyDeadOkRuExtractor {
    suspend fun extract(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalized = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url
        var count = 0
        runCatching {
            OkRuSSL().getUrl(normalized, referer, subtitleCallback) {
                count++
                callback(it)
            }
        }
        if (count == 0) {
            runCatching { OkRuHTTP().getUrl(url, referer, subtitleCallback, callback) }
        }
    }
}

class EgyDeadVidozaExtractor : ExtractorApi() {
    override val name = "Vidoza"
    override val mainUrl = "https://videzz.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val codeId = Regex("""(?:vidoza|videzz)\.[a-z]+/(?:embed-)?([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.removeSuffix(".html") ?: return
        for (domain in listOf("videzz.net", "vidoza.net")) {
            val embedUrl = "https://$domain/embed-$codeId.html"
            val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer) ?: continue
            val doc = Jsoup.parse(resp.body)
            var found = false
            doc.select("source[src]").forEach { srcEl ->
                val mp4 = srcEl.attr("src").trim()
                val res = srcEl.attr("res").ifBlank { srcEl.attr("label") }
                val q = EgyDeadExtractorKit.parseQuality(res).takeIf { it != Qualities.Unknown.value } ?: qualityHint
                val qTag = EgyDeadExtractorKit.formatQualityTag(q)
                val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
                if (EgyDeadExtractorKit.emitDirectVideo(name, label, mp4, embedUrl, q, callback = callback)) found = true
            }
            if (!found) {
                Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(resp.body)?.groupValues?.get(1)?.let { mp4 ->
                    found = EgyDeadExtractorKit.emitDirectVideo(name, name, mp4, embedUrl, qualityHint, callback = callback)
                }
            }
            if (found) return
        }
    }
}

class EgyDeadKoramaUpExtractor : ExtractorApi() {
    override val name = "KoramaUp"
    override val mainUrl = "https://koramaup.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val resp = EgyDeadExtractorKit.getText(url, referer = referer) ?: return
        val fullText = EgyDeadExtractorKit.unpackJs(resp.body)
        val directMp4 = Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .find(fullText)?.value
        if (!directMp4.isNullOrBlank()) {
            val qTag = EgyDeadExtractorKit.formatQualityTag(qualityHint)
            val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
            EgyDeadExtractorKit.emitDirectVideo(name, label, directMp4, url, qualityHint, callback = callback)
            return
        }
        val doc = Jsoup.parse(resp.body)
        val form = doc.selectFirst("form[method=POST]") ?: return
        val fields = LinkedHashMap<String, String>()
        form.select("input[name]").forEach { inp -> fields[inp.attr("name")] = inp.attr("value") }
        if (fields.isNotEmpty()) {
            val postResp = EgyDeadNetwork.executeRawPostForm(url, fields, referer = url) ?: return
            val mp4 = Jsoup.parse(postResp.body).selectFirst("a[href$=.mp4], a[href*=/d/], #direct_link a")
                ?.attr("href")
                ?: Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .find(postResp.body)?.value
            if (!mp4.isNullOrBlank()) {
                val qTag = EgyDeadExtractorKit.formatQualityTag(qualityHint)
                val label = if (qTag.isNotEmpty()) "$name ($qTag)" else name
                EgyDeadExtractorKit.emitDirectVideo(name, label, mp4, url, qualityHint, callback = callback)
            }
        }
    }
}

class EgyDeadUpvidExtractor : ExtractorApi() {
    override val name = "Upvid"
    override val mainUrl = "https://upvid.co"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val resp = EgyDeadExtractorKit.getText(url, referer = referer) ?: return
        val full = EgyDeadExtractorKit.unpackJs(resp.body)
        Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
            .find(full)?.value?.let { stream ->
                if (stream.contains(".m3u8", ignoreCase = true)) {
                    EgyDeadExtractorKit.emitHlsWithVariants(name, stream, url, qualityHint, callback = callback)
                } else {
                    EgyDeadExtractorKit.emitDirectVideo(name, name, stream, url, qualityHint, callback = callback)
                }
            }
    }
}

class EgyDeadSaveFilesExtractor : ExtractorApi() {
    override val name = "SaveFiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val code = url.substringBefore('?').trimEnd('/').substringAfterLast('/').removePrefix("embed-").removeSuffix(".html")
        val origin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val embedUrl = "$origin/e/$code"
        val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer) ?: return
        val full = EgyDeadExtractorKit.unpackJs(resp.body)
        Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(full)?.groupValues?.get(1)?.let { hls ->
            EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$origin/", qualityHint, callback = callback)
        }
    }
}

class EgyDeadLuluStreamExtractor : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://luluvdo.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, callback)
    }

    suspend fun extract(url: String, referer: String, qualityHint: Int, callback: (ExtractorLink) -> Unit) {
        val code = url.substringBefore('?').trimEnd('/').substringAfterLast('/').removePrefix("embed-").removeSuffix(".html")
        val origin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val embedUrl = "$origin/e/$code"
        val resp = EgyDeadExtractorKit.getText(embedUrl, referer = referer) ?: return
        val full = EgyDeadExtractorKit.unpackJs(resp.body)
        Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(full)?.groupValues?.get(1)?.let { hls ->
            EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$origin/", qualityHint, callback = callback)
        }
    }
}

class EgyDeadVidmolyExtractor : ExtractorApi() {
    override val name = "Vidmoly"
    override val mainUrl = "https://vidmoly.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer ?: mainUrl, Qualities.Unknown.value, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String,
        qualityHint: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""vidmoly\.[a-z]+/(?:embed-|w/)?([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.removeSuffix(".html")
        val incomingOrigin = EgyDeadExtractorKit.originOf(url) ?: mainUrl
        val embedUrl = if (code != null) "$incomingOrigin/embed-$code.html" else url

        val resp = EgyDeadExtractorKit.getText(
            embedUrl,
            referer = referer.ifBlank { "$incomingOrigin/" },
            headers = mapOf("Sec-Fetch-Dest" to "iframe")
        ) ?: return
        if (resp.code >= 400) return

        val finalOrigin = EgyDeadExtractorKit.originOf(resp.finalUrl) ?: incomingOrigin
        val full = EgyDeadExtractorKit.unpackJs(resp.body)

        // Single- or double-quoted `[{ "file": 'https://...m3u8' }]`
        val streams = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            .findAll(full)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val headers = mapOf("Referer" to "$finalOrigin/", "Origin" to finalOrigin)
        for (hls in streams) {
            EgyDeadExtractorKit.emitHlsWithVariants(name, hls, "$finalOrigin/", qualityHint, headers, callback)
        }
    }
}
