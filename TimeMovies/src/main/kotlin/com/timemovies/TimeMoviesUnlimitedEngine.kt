package com.timemovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-quota Unlimited Watch & Direct-MP4 Download Engine for `TimeMovies` v5.
 *
 * Permanently eliminates the `تم انتهاء رصيد` (`quota_exhausted` / `insufficient_quota`) wall by:
 * 1. Resolving exact Movie / Series / Anime matches (by English title, Arabic title, release year,
 *    season, and episode) across pure-Kotlin encrypted Cloud-Day catalogs (`CimaCloud v1.3` for
 *    Movies/Series + `AnimeDay v3.8` for Anime/Animation) plus opportunistic residential `CimaNow`
 *    direct progressive `.mp4` mirrors — requiring **0 accounts** and **0 daily credits**.
 * 2. Extracting multi-quality adaptive HLS streams (`1080p` / `720p` / `480p` / `360p` / `Auto`),
 *    direct progressive `.mp4` download links (`ExtractorLinkType.VIDEO`), and external Arabic
 *    `.srt`/`.vtt` subtitles across 16+ pure-Kotlin server extractors (`usesWebView = false`).
 */
object TimeMoviesUnlimitedEngine {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val CLOUDDAY_APP_UA = "okhttp/4.10.0"

    // Shared pure-Kotlin Cloud-Day JNI cryptographic constants (verified identical on CimaCloud v1.3 & AnimeDay v3.8)
    private const val CLOUDDAY_CERT_SHA256 =
        "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7"
    private const val CLOUDDAY_HMAC_SECRET =
        "97a54e5dec15b4f1754abc5939643bbe57a0d8ae8db88529e268289457728002"
    private const val CLOUDDAY_DECRYPT_SALT = "Gm4il@G00Gl3.Com"
    private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val HEX_CHARS = "0123456789abcdef"

    private const val CLOUDDAY_RSA_PUB_SPKI_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6+BtHI/" +
            "Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3QCh65Nbi4mKRFFJyDk" +
            "JVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9FtgJkuz17n265rLB6ruUUrHQVeEDl8" +
            "ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvsq4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IE" +
            "YV47OyddG32qbriJbxdvW+n5OzowiYQrsC8hG65GPBGCuj3zO7wIDAQAB"

    private const val CIMACLOUD_WORKER_URL = "https://config-backup2.animegateshorts.workers.dev/"
    private const val CIMACLOUD_FALLBACK_HOST = "https://1654865.xyz"
    private const val CIMACLOUD_API_PATH = "/v1.3/api"
    private const val CIMACLOUD_APK_PATH = "/data/app/com.app.cimacloud/base.apk"

    private const val ANIMEDAY_WORKER_URL = "https://backup-animeday.animegateshorts.workers.dev/"
    private const val ANIMEDAY_FALLBACK_HOST = "https://20042026.site"
    private const val ANIMEDAY_API_PATH = "/v3.8/api"
    private const val ANIMEDAY_APK_PATH = "/data/app/com.anime.day/base.apk"

    private const val DEFAULT_REMOTE_EXTRACTOR_URL = "https://cloud-day.online/cimacloud/extractor.php"
    private const val CIMANOW_BASE_URL = "https://cimanow.cc"

    private const val CONFIG_TTL_MS = 20L * 60L * 1000L
    private const val EMBED_CACHE_TTL_MS = 4L * 60L * 60L * 1000L

    private val secureRandom = SecureRandom()

    private val apiHttpClient: OkHttpClient by lazy {
        TimeMoviesApi.plainClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val fastProbeClient: OkHttpClient by lazy {
        TimeMoviesApi.plainClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private data class CloudDayBackend(
        val id: String,
        val displayPrefix: String,
        val workerUrl: String,
        val fallbackHost: String,
        val apiPath: String,
        val apkPath: String,
    )

    private val cimaCloudBackend = CloudDayBackend(
        id = "cimacloud",
        displayPrefix = "TimeMovies ♾️",
        workerUrl = CIMACLOUD_WORKER_URL,
        fallbackHost = CIMACLOUD_FALLBACK_HOST,
        apiPath = CIMACLOUD_API_PATH,
        apkPath = CIMACLOUD_APK_PATH,
    )

    private val animeDayBackend = CloudDayBackend(
        id = "animeday",
        displayPrefix = "TimeMovies ♾️ Anime",
        workerUrl = ANIMEDAY_WORKER_URL,
        fallbackHost = ANIMEDAY_FALLBACK_HOST,
        apiPath = ANIMEDAY_API_PATH,
        apkPath = ANIMEDAY_APK_PATH,
    )

    private data class ResolvedBackendUrls(
        val apiBase: String,
        val extractorUrl: String,
        val fetchedAtMs: Long,
    )

    data class UnlimitedServerEmbed(
        val name: String,
        val url: String,
        val sourceBackend: String,
    )

    private data class CachedServerList(
        val servers: List<UnlimitedServerEmbed>,
        val savedAtMs: Long,
    )

    private val resolvedBackendCache = ConcurrentHashMap<String, ResolvedBackendUrls>()
    private val embedListCache = ConcurrentHashMap<String, CachedServerList>()

    @Volatile
    private var cimaNowDatacenterBlockedUntilMs: Long = 0L

    internal fun clearCachesForTests() {
        resolvedBackendCache.clear()
        embedListCache.clear()
        cimaNowDatacenterBlockedUntilMs = 0L
    }

    /**
     * Returns `true` when `TimeMoviesUnlimitedEngine` is permitted to run in the current runtime.
     * In mock-HTTP unit tests (`apiBaseUrlOverride != null`), external internet probing is skipped
     * unless `unlimitedCloudApiOverride` or `forceUnlimitedInMockTests` is explicitly set.
     */
    fun isRuntimeActive(): Boolean {
        if (!TimeMoviesPrefs.unlimitedBypassEnabled) return false
        if (TimeMoviesPrefs.apiBaseUrlOverride != null) {
            return TimeMoviesPrefs.unlimitedCloudApiOverride != null ||
                TimeMoviesPrefs.forceUnlimitedInMockTests
        }
        return true
    }

    // =========================================================================
    // 1. Pure-Kotlin Cloud-Day Request Signing & AES-256-CBC Response Decryption
    // =========================================================================

    private val sessionDeviceKey: String by lazy { randomHexLower(8) }

    private val staticAttestationMac: String by lazy {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(CLOUDDAY_HMAC_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        toHexLower(mac.doFinal("err|$CLOUDDAY_CERT_SHA256|000|err_open".toByteArray(StandardCharsets.UTF_8)))
    }

    private val cloudDayRsaPublicKey by lazy {
        val spkiBytes = TimeMoviesCrypto.base64DecodeFlexible(CLOUDDAY_RSA_PUB_SPKI_B64)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spkiBytes))
    }

    private fun toHexLower(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    private fun randomHexLower(byteCount: Int): String {
        val buf = ByteArray(byteCount).also { secureRandom.nextBytes(it) }
        return toHexLower(buf)
    }

    private fun randomAlnum(length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(ALPHANUM[secureRandom.nextInt(ALPHANUM.length)])
        }
        return sb.toString()
    }

    private fun md5Hex(input: String): String =
        toHexLower(MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8)))

    private fun generateCloudflarePair(): Pair<String, String> {
        val prefix = randomAlnum(15)
        val key = sessionDeviceKey
        return (prefix + key) to key
    }

    private fun buildCloudDaySecureToken(apkPath: String): String {
        val nowSec = System.currentTimeMillis() / 1000L
        val nonce = randomHexLower(8)
        val payloadJson =
            """{"a":"err","b":"$CLOUDDAY_CERT_SHA256|$apkPath","c":"false",""" +
                """"d":"empty","e":"empty","f":"false","g":"false","h":"false","i":"false",""" +
                """"j":"false","r":"false","s":"err_open","m":"$staticAttestationMac","t":$nowSec,"n":"$nonce"}"""

        val aesKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
        gcmCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val sealedBytes = gcmCipher.doFinal(payloadJson.toByteArray(StandardCharsets.UTF_8))

        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, cloudDayRsaPublicKey)
        val encKeyBytes = rsaCipher.doFinal(aesKey)

        return "${TimeMoviesCrypto.base64Encode(encKeyBytes)}.${TimeMoviesCrypto.base64Encode(iv)}.${TimeMoviesCrypto.base64Encode(sealedBytes)}"
    }

    internal fun decryptCloudDayCipherB64(encryptedB64: String, key: String): String? {
        if (encryptedB64.isBlank() || key.isEmpty()) return null
        return runCatching {
            val aesKeyBytes = md5Hex(CLOUDDAY_DECRYPT_SALT + key).toByteArray(StandardCharsets.UTF_8)
            val ivBytes = md5Hex(key + CLOUDDAY_DECRYPT_SALT).substring(0, 16).toByteArray(StandardCharsets.UTF_8)
            val cipherBytes = TimeMoviesCrypto.base64DecodeFlexible(encryptedB64.trim())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    internal fun encryptCloudDayPayloadForTest(
        plainJson: String,
        cloudflareId: String,
        rawCipherOnly: Boolean = false,
    ): String {
        val key = if (cloudflareId.length > 15) cloudflareId.substring(15) else cloudflareId
        val aesKeyBytes = md5Hex(CLOUDDAY_DECRYPT_SALT + key).toByteArray(StandardCharsets.UTF_8)
        val ivBytes = md5Hex(key + CLOUDDAY_DECRYPT_SALT).substring(0, 16).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), IvParameterSpec(ivBytes))
        val b64 = TimeMoviesCrypto.base64Encode(cipher.doFinal(plainJson.toByteArray(StandardCharsets.UTF_8)))
        return if (rawCipherOnly) {
            b64
        } else {
            JSONObject()
                .put("encrypted", true)
                .put("data", b64)
                .toString()
        }
    }

    private fun unwrapCloudDayResponseBody(rawBody: String, key: String): JSONObject? {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return null

        // Case 1: Raw Base64 ciphertext body (used by `/movie/{id}/servers` & `/episode/{id}/servers`)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            val decrypted = decryptCloudDayCipherB64(trimmed, key)?.trim() ?: return null
            return when {
                decrypted.startsWith("{") -> {
                    val obj = runCatching { JSONObject(decrypted) }.getOrNull() ?: return null
                    if (obj.optBoolean("blocked", false)) null else (obj.optJSONObject("data") ?: obj)
                }
                decrypted.startsWith("[") -> JSONObject().put("items", JSONArray(decrypted))
                else -> null
            }
        }

        if (trimmed.startsWith("[")) {
            return JSONObject().put("items", JSONArray(trimmed))
        }

        // Case 2 & 3: JSON envelope (`{"encrypted":true,"data":"..."}` or plain JSON)
        val outer = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        if (outer.optBoolean("blocked", false)) return null

        if (outer.optBoolean("encrypted", false)) {
            val encStr = outer.optString("data", "")
            if (encStr.isNotBlank()) {
                val decrypted = decryptCloudDayCipherB64(encStr, key)?.trim() ?: return null
                if (decrypted.startsWith("{")) {
                    val obj = runCatching { JSONObject(decrypted) }.getOrNull() ?: return null
                    return obj.optJSONObject("data") ?: obj
                } else if (decrypted.startsWith("[")) {
                    return JSONObject().put("items", JSONArray(decrypted))
                }
            }
        }

        outer.optJSONObject("data")?.let { return it }
        return outer
    }

    private fun resolveBackendUrls(backend: CloudDayBackend): ResolvedBackendUrls {
        TimeMoviesPrefs.unlimitedCloudApiOverride?.trim()?.takeIf { it.isNotEmpty() }?.let { overrideApi ->
            val ext = TimeMoviesPrefs.unlimitedExtractorOverride?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_REMOTE_EXTRACTOR_URL
            return ResolvedBackendUrls(
                apiBase = overrideApi.trimEnd('/'),
                extractorUrl = ext,
                fetchedAtMs = System.currentTimeMillis(),
            )
        }

        val now = System.currentTimeMillis()
        val cached = resolvedBackendCache[backend.id]
        if (cached != null && (now - cached.fetchedAtMs) in 0 until CONFIG_TTL_MS) {
            return cached
        }

        var host = backend.fallbackHost
        var extractor = DEFAULT_REMOTE_EXTRACTOR_URL
        runCatching {
            val req = Request.Builder()
                .url(backend.workerUrl)
                .get()
                .header("User-Agent", CLOUDDAY_APP_UA)
                .header("Accept", "application/json")
                .build()
            fastProbeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val root = JSONObject(resp.body?.string().orEmpty())
                val cfg = root.optJSONArray("data")?.optJSONObject(0)
                    ?: root.optJSONObject("data")
                    ?: root
                val dynamicHost = cfg.optString("cloudday", "")
                    .ifBlank { cfg.optString("animeday", "") }
                    .ifBlank { cfg.optString("api_url", "") }
                    .ifBlank { cfg.optString("domain", "") }
                    .trim()
                    .trimEnd('/')
                if (dynamicHost.startsWith("http")) {
                    host = dynamicHost
                }
                val dynamicExt = cfg.optString("extractorUrl", "")
                    .ifBlank { cfg.optString("extractor", "") }
                    .trim()
                if (dynamicExt.startsWith("http")) {
                    extractor = dynamicExt
                }
            }
        }

        val resolved = ResolvedBackendUrls(
            apiBase = if (host.endsWith(backend.apiPath) || host.contains("/api")) host else "$host${backend.apiPath}",
            extractorUrl = extractor,
            fetchedAtMs = now,
        )
        resolvedBackendCache[backend.id] = resolved
        return resolved
    }

    private fun cloudDayGet(backend: CloudDayBackend, endpoint: String): JSONObject? = runCatching {
        val urls = resolveBackendUrls(backend)
        val (cfId, key) = generateCloudflarePair()
        val cleanEndpoint = endpoint.trimStart('/')
        val req = Request.Builder()
            .url("${urls.apiBase}/$cleanEndpoint")
            .get()
            .header("User-Agent", CLOUDDAY_APP_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("cloudflare-id", cfId)
            .header("firebase_id", buildCloudDaySecureToken(backend.apkPath))
            .build()
        apiHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            unwrapCloudDayResponseBody(resp.body?.string().orEmpty(), key)
        }
    }.getOrNull()

    private fun cloudDayPost(backend: CloudDayBackend, endpoint: String, formFields: Map<String, String>): JSONObject? =
        runCatching {
            val urls = resolveBackendUrls(backend)
            val (cfId, key) = generateCloudflarePair()
            val formBuilder = FormBody.Builder()
            formFields.forEach { (k, v) -> formBuilder.add(k, v) }
            val cleanEndpoint = endpoint.trimStart('/')
            val req = Request.Builder()
                .url("${urls.apiBase}/$cleanEndpoint")
                .post(formBuilder.build())
                .header("User-Agent", CLOUDDAY_APP_UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("cloudflare-id", cfId)
                .header("firebase_id", buildCloudDaySecureToken(backend.apkPath))
                .build()
            apiHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                unwrapCloudDayResponseBody(resp.body?.string().orEmpty(), key)
            }
        }.getOrNull()

    // =========================================================================
    // 2. Precision Title / Year / Season / Episode Catalog Matching
    // =========================================================================

    private val ARABIC_DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val STRIP_NOISE_WORDS_REGEX = Regex(
        "\\b(?:فيلم|مسلسل|انمي|أنمي|كرتون|مترجم|مترجمة|مدبلج|مدبلجة|اون\\s*لاين|كامل|جودة\\s*عالية|hd|bluray|web-dl)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_YEAR_PARENS_REGEX = Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$")
    private val SEASON_SUFFIX_REGEX = Regex(
        "(?:\\s*[-:]?\\s*(?:الموسم|موسم|season|part|الجزء)\\s*(?:الاول|الأول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر|\\d+))+$",
        RegexOption.IGNORE_CASE,
    )
    private val NON_ALNUM_ARABIC_REGEX = Regex("[^a-z0-9\\u0621-\\u064A]+")
    private val YEAR_EXTRACT_REGEX = Regex("((?:19|20)\\d{2})")

    internal fun normalizeCompareTitle(raw: String?, stripSeasonSuffix: Boolean = false): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim().lowercase()
        s = s.replace(TRAILING_YEAR_PARENS_REGEX, " ")
        s = ARABIC_DIACRITICS_REGEX.replace(s, "")
        s = s
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
        s = STRIP_NOISE_WORDS_REGEX.replace(s, " ")
        if (stripSeasonSuffix) {
            s = SEASON_SUFFIX_REGEX.replace(s, " ")
        }
        // Normalize standalone Roman numerals II..VIII for sequel accuracy
        s = s
            .replace(Regex("\\bviii\\b"), "8")
            .replace(Regex("\\bvii\\b"), "7")
            .replace(Regex("\\bvi\\b"), "6")
            .replace(Regex("\\biv\\b"), "4")
            .replace(Regex("\\biii\\b"), "3")
            .replace(Regex("\\bii\\b"), "2")
        s = NON_ALNUM_ARABIC_REGEX.replace(s, " ").trim()
        return s.replace(Regex("\\s+"), " ")
    }

    private fun extractSeasonHintFromTitle(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val lower = ARABIC_DIACRITICS_REGEX.replace(raw.lowercase(), "")
        Regex("(?:season|الموسم|موسم|الجزء|part)\\s*(\\d{1,2})").find(lower)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return when {
            lower.contains("الموسم الاول") || lower.contains("الموسم الأول") -> 1
            lower.contains("الموسم الثاني") || lower.contains("الجزء الثاني") -> 2
            lower.contains("الموسم الثالث") || lower.contains("الجزء الثالث") -> 3
            lower.contains("الموسم الرابع") || lower.contains("الجزء الرابع") -> 4
            lower.contains("الموسم الخامس") || lower.contains("الجزء الخامس") -> 5
            else -> null
        }
    }

    private fun computeTitleSimilarity(targetNorm: String, candidateNorm: String): Double {
        if (targetNorm.isEmpty() || candidateNorm.isEmpty()) return 0.0
        if (targetNorm == candidateNorm) return 1.0

        val tTokens = targetNorm.split(' ').filter { it.isNotEmpty() }
        val cTokens = candidateNorm.split(' ').filter { it.isNotEmpty() }
        if (tTokens.isEmpty() || cTokens.isEmpty()) return 0.0

        val tSet = tTokens.toSet()
        val cSet = cTokens.toSet()
        val inter = tSet.intersect(cSet).size
        if (inter == 0) return 0.0

        val union = tSet.union(cSet).size
        val jaccard = inter.toDouble() / union.toDouble()
        val containment = inter.toDouble() / minOf(tSet.size, cSet.size).toDouble()

        // Guard short 1-token titles (e.g., "Venom" vs "Venom The Last Dance")
        if (minOf(tSet.size, cSet.size) == 1 && maxOf(tSet.size, cSet.size) >= 3) {
            return jaccard
        }
        return maxOf(jaccard, containment * 0.86)
    }

    internal data class ScoredCatalogCandidate(
        val id: Int,
        val isMovieItem: Boolean,
        val score: Int,
        val rawTitle: String,
        val seasonHint: Int?,
    )

    internal fun scoreCatalogItem(
        item: JSONObject,
        payload: TimeMoviesModels.LinkPayload,
        targetNorms: List<String>,
    ): ScoredCatalogCandidate? {
        val rawType = item.optString("type", "").lowercase().trim()
        val isEpisodeCard = rawType == "episode"

        val id = if (isEpisodeCard) {
            item.optString("tv_show_id", "")
                .ifBlank { item.optString("serie_id", "") }
                .ifBlank { item.optString("series_id", "") }
                .trim()
                .toIntOrNull() ?: 0
        } else {
            item.optString("id", "").trim().toIntOrNull() ?: item.optInt("id", 0)
        }
        if (id <= 0) return null

        val isMovieItem = when {
            isEpisodeCard -> false
            rawType.contains("serie") || rawType == "tv" || rawType.contains("anime_series") -> false
            rawType.contains("movie") || rawType == "film" -> true
            item.has("seasons_count") || item.has("episodes_count") -> false
            else -> payload.isMovie
        }
        if (isMovieItem != payload.isMovie) return null

        val rawNames = buildList {
            if (isEpisodeCard) {
                item.optString("tv_show_name", "").takeIf { it.isNotBlank() }?.let { add(it) }
                item.optString("serie_name", "").takeIf { it.isNotBlank() }?.let { add(it) }
            }
            item.optString("name", "").takeIf { it.isNotBlank() }?.let { add(it) }
            item.optString("title", "").takeIf { it.isNotBlank() }?.let { add(it) }
            item.optString("original_title", "").takeIf { it.isNotBlank() }?.let { add(it) }
            item.optString("original_name", "").takeIf { it.isNotBlank() }?.let { add(it) }
            item.optString("english_title", "").takeIf { it.isNotBlank() }?.let { add(it) }
            item.optString("arabic_title", "").takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (rawNames.isEmpty()) return null

        val stripSeason = !payload.isMovie
        var bestSim = 0.0
        for (candRaw in rawNames) {
            val candFull = normalizeCompareTitle(candRaw, stripSeasonSuffix = false)
            val candStripped = normalizeCompareTitle(candRaw, stripSeasonSuffix = stripSeason)
            for (tNorm in targetNorms) {
                bestSim = maxOf(
                    bestSim,
                    computeTitleSimilarity(tNorm, candFull),
                    computeTitleSimilarity(tNorm, candStripped),
                )
            }
        }
        if (bestSim < 0.52) return null

        var score = when {
            bestSim >= 0.999 -> 100
            bestSim >= 0.85 -> 82
            bestSim >= 0.72 -> 68
            else -> 50
        }

        val itemYear = YEAR_EXTRACT_REGEX.find(
            item.optString("year", "").ifBlank {
                item.optString("release_date", "").ifBlank { item.optString("first_air_date", "") }
            }
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (payload.year != null && itemYear != null) {
            val diff = kotlin.math.abs(payload.year - itemYear)
            when {
                diff == 0 -> score += 35
                diff == 1 -> score += 20
                diff == 2 -> score += 5
                payload.isMovie -> score -= 65
                else -> score -= 15
            }
        }

        val seasonHint = rawNames.firstNotNullOfOrNull { extractSeasonHintFromTitle(it) }
        if (!payload.isMovie && payload.seasonNum != null && seasonHint != null) {
            if (seasonHint == payload.seasonNum) {
                score += 30
            } else {
                score -= 25
            }
        }

        if (score < 45) return null
        return ScoredCatalogCandidate(
            id = id,
            isMovieItem = isMovieItem,
            score = score,
            rawTitle = rawNames.first(),
            seasonHint = seasonHint,
        )
    }

    private fun buildSearchQueries(payload: TimeMoviesModels.LinkPayload): List<String> {
        val queries = LinkedHashSet<String>()
        val cleanEn = payload.title.replace(TRAILING_YEAR_PARENS_REGEX, "").trim()
        if (cleanEn.length >= 2 && !cleanEn.equals("TimeMovies", ignoreCase = true)) {
            queries.add(cleanEn)
        }
        val cleanAr = payload.titleAr?.replace(TRAILING_YEAR_PARENS_REGEX, "")?.trim().orEmpty()
        if (cleanAr.length >= 2 && !cleanAr.equals(cleanEn, ignoreCase = true)) {
            queries.add(cleanAr)
        }
        // Fallback shortened main title before `:` or ` - ` (scored strictly with year check)
        val baseBeforeColon = cleanEn.substringBefore(':').substringBefore(" - ").trim()
        if (baseBeforeColon.length >= 4 && baseBeforeColon.length < cleanEn.length) {
            queries.add(baseBeforeColon)
        }
        return queries.take(3)
    }

    private fun parseServerArray(json: JSONObject?, backendLabel: String): List<UnlimitedServerEmbed> {
        if (json == null) return emptyList()
        val arr = json.optJSONArray("servers")
            ?: json.optJSONArray("items")
            ?: json.optJSONArray("data")
            ?: json.optJSONObject("data")?.optJSONArray("servers")
            ?: return emptyList()

        val out = ArrayList<UnlimitedServerEmbed>(arr.length())
        for (i in 0 until arr.length()) {
            val srv = arr.optJSONObject(i)
            val rawUrl: String
            val srvName: String
            if (srv != null) {
                rawUrl = srv.optString("link", "")
                    .ifBlank { srv.optString("url", "") }
                    .ifBlank { srv.optString("embed_url", "") }
                    .trim()
                srvName = srv.optString("name", "")
                    .ifBlank { srv.optString("server_name", "") }
                    .ifBlank { extractHostLabel(rawUrl, i + 1) }
                    .trim()
            } else {
                rawUrl = arr.optString(i, "").trim()
                srvName = extractHostLabel(rawUrl, i + 1)
            }
            if (!rawUrl.startsWith("http")) continue
            out.add(
                UnlimitedServerEmbed(
                    name = "$backendLabel - $srvName",
                    url = rawUrl,
                    sourceBackend = backendLabel,
                )
            )
        }
        return out
    }

    private fun extractHostLabel(url: String, idx: Int): String = runCatching {
        URI(url).host?.removePrefix("www.")?.substringBefore('.')?.replaceFirstChar { it.uppercase() }
            ?: "Server $idx"
    }.getOrDefault("Server $idx")

    private fun extractTrailingEpisodeInt(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(\d+)\s*$""").find(text.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun findEpisodeIdInSeasons(
        seasonsData: JSONArray,
        targetSeason: Int,
        targetEpisode: Int,
        candidateSeasonHint: Int?,
    ): Int? {
        data class EpEntry(val id: Int, val seasonNum: Int, val epNum: Int)

        val allEps = ArrayList<EpEntry>()
        for (sIdx in 0 until seasonsData.length()) {
            val sObj = seasonsData.optJSONObject(sIdx) ?: continue
            val sNum = sObj.optString("season_number", "").trim().toIntOrNull()?.takeIf { it > 0 }
                ?: sObj.optInt("season_number", 0).takeIf { it > 0 }
                ?: extractTrailingEpisodeInt(sObj.optString("title", "").ifBlank { sObj.optString("name", "") })
                ?: (sIdx + 1)
            val epArr = sObj.optJSONArray("episodes") ?: continue
            for (eIdx in 0 until epArr.length()) {
                val epObj = epArr.optJSONObject(eIdx) ?: continue
                val epId = epObj.optString("id", "").trim().toIntOrNull() ?: epObj.optInt("id", 0)
                if (epId <= 0) continue
                val epNum = epObj.optInt("episode_number", 0).takeIf { it > 0 }
                    ?: extractTrailingEpisodeInt(epObj.optString("title", ""))
                    ?: extractTrailingEpisodeInt(epObj.optString("name", ""))
                    ?: (eIdx + 1)
                allEps.add(EpEntry(epId, sNum, epNum))
            }
        }
        if (allEps.isEmpty()) return null

        // 1. Exact (seasonNum, episodeNum) match
        allEps.firstOrNull { it.seasonNum == targetSeason && it.epNum == targetEpisode }
            ?.let { return it.id }

        // 2. Series entry itself is already season-specific (e.g. "Solo Leveling Season 2" stored as season 1)
        if (candidateSeasonHint == targetSeason || (seasonsData.length() == 1 && targetSeason == 1)) {
            allEps.firstOrNull { it.epNum == targetEpisode }?.let { return it.id }
        }

        // 3. Single-season continuous anime numbering
        if (seasonsData.length() == 1 && candidateSeasonHint == null) {
            allEps.firstOrNull { it.epNum == targetEpisode }?.let { return it.id }
        }
        return null
    }

    private fun resolveEmbedsFromBackend(
        backend: CloudDayBackend,
        payload: TimeMoviesModels.LinkPayload,
    ): List<UnlimitedServerEmbed> {
        val queries = buildSearchQueries(payload)
        if (queries.isEmpty()) return emptyList()

        val targetNorms = buildList {
            normalizeCompareTitle(payload.title, stripSeasonSuffix = !payload.isMovie)
                .takeIf { it.isNotEmpty() }?.let { add(it) }
            normalizeCompareTitle(payload.title, stripSeasonSuffix = false)
                .takeIf { it.isNotEmpty() }?.let { add(it) }
            normalizeCompareTitle(payload.titleAr, stripSeasonSuffix = !payload.isMovie)
                .takeIf { it.isNotEmpty() }?.let { add(it) }
            normalizeCompareTitle(payload.titleAr, stripSeasonSuffix = false)
                .takeIf { it.isNotEmpty() }?.let { add(it) }
        }.distinct()
        if (targetNorms.isEmpty()) return emptyList()

        val candidatesById = LinkedHashMap<Int, ScoredCatalogCandidate>()
        for (query in queries) {
            val res = cloudDayPost(
                backend = backend,
                endpoint = "search",
                formFields = mapOf(
                    "title" to query,
                    "type" to "0",
                    "sort" to "1",
                    "page" to "1",
                ),
            ) ?: continue
            val items = res.optJSONArray("results")
                ?: res.optJSONArray("items")
                ?: res.optJSONArray("data")
                ?: res.optJSONObject("data")?.optJSONArray("data")
                ?: continue

            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val scored = scoreCatalogItem(obj, payload, targetNorms) ?: continue
                val prev = candidatesById[scored.id]
                if (prev == null || scored.score > prev.score) {
                    candidatesById[scored.id] = scored
                }
            }
            if (candidatesById.values.any { it.score >= 115 }) break
        }

        if (candidatesById.isEmpty()) return emptyList()
        val ranked = candidatesById.values.sortedByDescending { it.score }

        if (payload.isMovie) {
            val best = ranked.first()
            return parseServerArray(cloudDayGet(backend, "movie/${best.id}/servers"), backend.displayPrefix)
        }

        val targetSeason = payload.seasonNum ?: 1
        val targetEpisode = payload.episodeNum ?: 1
        for (cand in ranked.take(3)) {
            val epResp = cloudDayGet(backend, "series/${cand.id}/episodes") ?: continue
            val seasonsArr = epResp.optJSONArray("seasons")
                ?: epResp.optJSONArray("items")
                ?: epResp.optJSONArray("data")
                ?: continue
            val epId = findEpisodeIdInSeasons(seasonsArr, targetSeason, targetEpisode, cand.seasonHint)
                ?: continue
            val servers = parseServerArray(cloudDayGet(backend, "episode/$epId/servers"), backend.displayPrefix)
            if (servers.isNotEmpty()) return servers
        }
        return emptyList()
    }

    /**
     * Resolves all zero-quota server embeds for `payload`, caching per `(workId, episodeId)` for 4 hours.
     */
    fun resolveUnlimitedEmbeds(payload: TimeMoviesModels.LinkPayload): List<UnlimitedServerEmbed> {
        val cacheKey = "${payload.workId}|${payload.episodeId.orEmpty()}|${payload.seasonNum ?: 0}|${payload.episodeNum ?: 0}"
        val now = System.currentTimeMillis()
        embedListCache[cacheKey]?.let { cached ->
            if ((now - cached.savedAtMs) in 0 until EMBED_CACHE_TTL_MS && cached.servers.isNotEmpty()) {
                return cached.servers
            }
        }

        val orderedBackends = if (payload.isAnime) {
            listOf(animeDayBackend, cimaCloudBackend)
        } else {
            listOf(cimaCloudBackend, animeDayBackend)
        }

        val collected = LinkedHashMap<String, UnlimitedServerEmbed>()
        for (backend in orderedBackends) {
            val found = resolveEmbedsFromBackend(backend, payload)
            for (srv in found) {
                collected.putIfAbsent(srv.url, srv)
            }
            // If primary backend already yielded rich servers, skip secondary unless anime
            if (collected.size >= 3 && !payload.isAnime) break
        }

        val finalList = collected.values.toList()
        if (finalList.isNotEmpty()) {
            embedListCache[cacheKey] = CachedServerList(finalList, now)
        }
        return finalList
    }

    // =========================================================================
    // 3. Multi-Server Pure-Kotlin Video & Direct-MP4 Extractor Suite
    // =========================================================================

    private val isAndroidDeviceRuntime: Boolean by lazy {
        runCatching { android.os.Build.VERSION.SDK_INT > 0 }.getOrDefault(false)
    }

    private fun originOf(url: String): String = runCatching {
        val u = URI(url)
        if (u.port > 0) "${u.scheme}://${u.host}:${u.port}" else "${u.scheme}://${u.host}"
    }.getOrDefault("https://cloud-day.online")

    private fun resolveRelativeUrl(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrDefault(trimmed)
    }

    private const val MAX_TEXT_BODY_BYTES = 2 * 1024 * 1024 // 2 MiB hard ceiling on scraped HTML/M3U8/JSON

    private sealed class FetchOutcome {
        data class Text(val body: String, val finalUrl: String) : FetchOutcome()
        data class DirectVideo(val finalUrl: String) : FetchOutcome()
    }

    private fun isDirectBinaryMediaResponse(contentType: String, contentDisp: String, finalUrl: String): Boolean {
        val ct = contentType.lowercase()
        val cd = contentDisp.lowercase()
        val u = finalUrl.lowercase()
        if (ct.contains("mpegurl") || ct.contains("x-mpegurl") || u.contains(".m3u8")) return false
        if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("matroska")) return true
        if (ct.startsWith("application/octet-stream") || ct.startsWith("binary/octet-stream")) return true
        if (cd.contains("attachment") && (cd.contains(".mp4") || cd.contains(".mkv") || cd.contains(".webm"))) return true
        return false
    }

    private fun readBoundedUtf8(stream: java.io.InputStream, maxBytes: Int = MAX_TEXT_BODY_BYTES): String {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 32_768))
        val buf = ByteArray(8_192)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buf.size, maxBytes - total)
            val n = stream.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun fetchTextOrDirectVideo(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        fast: Boolean = false,
    ): FetchOutcome? = runCatching {
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }
        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        val client = if (fast) fastProbeClient else apiHttpClient
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val finalUrl = resp.request.url.toString()
            val ct = resp.header("Content-Type").orEmpty()
            val cd = resp.header("Content-Disposition").orEmpty()
            if (isDirectBinaryMediaResponse(ct, cd, finalUrl)) {
                return FetchOutcome.DirectVideo(finalUrl)
            }
            val stream = resp.body?.byteStream() ?: return null
            FetchOutcome.Text(readBoundedUtf8(stream), finalUrl)
        }
    }.getOrNull()

    private fun httpGetText(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        fast: Boolean = false,
    ): String? = (fetchTextOrDirectVideo(url, referer, extraHeaders, fast) as? FetchOutcome.Text)?.body

    private fun parseQualityInt(label: String?, url: String = ""): Int {
        val combined = "${label.orEmpty()} $url".lowercase()
        Regex("(?<!\\d)(2160|1440|1080|720|480|360|240|144)p?(?!\\d)").find(combined)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return when {
            combined.contains("4k") || combined.contains("uhd") -> Qualities.P2160.value
            combined.contains("full") || combined.contains("fhd") -> Qualities.P1080.value
            Regex("""\bhd\b""").containsMatchIn(combined) -> Qualities.P720.value
            Regex("""\b(?:sd|low)\b""").containsMatchIn(combined) -> Qualities.P480.value
            combined.contains("mobile") || combined.contains("lowest") -> Qualities.P240.value
            else -> 0
        }
    }

    private fun unpackDeanEdwards(packedHtml: String): String? {
        val evalRegex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = evalRegex.find(packedHtml) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val symtab = match.groupValues[4].split('|')
        if (radix !in 2..62) return null

        fun encodeBase(num: Int, base: Int): String {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(alphabet[n % base])
                n /= base
            }
            return sb.reverse().toString()
        }

        val lookup = HashMap<String, String>(symtab.size * 2)
        for (i in symtab.indices) {
            val replacement = symtab[i]
            if (replacement.isNotEmpty()) {
                lookup[encodeBase(i, radix)] = replacement
            }
        }
        return Regex("\\b[0-9a-zA-Z]+\\b").replace(payload) { mr ->
            lookup[mr.value] ?: mr.value
        }
    }

    private val STREAM_INF_RESOLUTION_REGEX = Regex("RESOLUTION=\\d+x(\\d{3,4})", RegexOption.IGNORE_CASE)
    private val MEDIA_SUBTITLE_REGEX = Regex(
        """#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*""",
        RegexOption.IGNORE_CASE,
    )
    private val M3U8_ATTR_NAME_REGEX = Regex("""NAME="([^"]+)"""")
    private val M3U8_ATTR_LANG_REGEX = Regex("""LANGUAGE="([^"]+)"""")
    private val M3U8_ATTR_URI_REGEX = Regex("""URI="([^"]+)"""")

    /**
     * Emits `[Auto HLS]` + parsed `#EXT-X-STREAM-INF` multi-quality variants (`1080p`/`720p`/`480p`/`360p`)
     * + any embedded `#EXT-X-MEDIA:TYPE=SUBTITLES` tracks using pure OkHttp so it runs identically on
     * Android and JVM unit tests while preserving CDN `Referer`/`Origin` headers.
     */
    private suspend fun emitHlsStreamAndVariants(
        m3u8Url: String,
        sourceName: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
        forcedQuality: Int = 0,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val cleanUrl = m3u8Url.trim()
        if (!cleanUrl.startsWith("http")) return 0

        val reqHeaders = LinkedHashMap<String, String>()
        reqHeaders["User-Agent"] = BROWSER_UA
        if (referer.isNotBlank()) {
            reqHeaders["Referer"] = referer
            reqHeaders["Origin"] = originOf(referer)
        }
        reqHeaders.putAll(extraHeaders)

        val isSpecificQuality = forcedQuality > 0 && forcedQuality != Qualities.Unknown.value
        var emitted = 0
        callback(
            newExtractorLink(
                source = sourceName,
                name = if (isSpecificQuality) "$sourceName [${forcedQuality}p HLS]" else "$sourceName [Auto HLS]",
                url = cleanUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer
                this.quality = if (isSpecificQuality) forcedQuality else Qualities.Unknown.value
                this.headers = reqHeaders
            }
        )
        emitted++

        if (isSpecificQuality) return emitted

        val manifestText = httpGetText(cleanUrl, referer = referer, extraHeaders = extraHeaders, fast = true)
            ?: return emitted
        if (!manifestText.contains("#EXT-X-STREAM-INF")) return emitted

        // Extract any subtitle tracks referenced in the master playlist
        for (match in MEDIA_SUBTITLE_REGEX.findAll(manifestText)) {
            val line = match.value
            val subUri = M3U8_ATTR_URI_REGEX.find(line)?.groupValues?.getOrNull(1) ?: continue
            val subName = M3U8_ATTR_NAME_REGEX.find(line)?.groupValues?.getOrNull(1)
                ?: M3U8_ATTR_LANG_REGEX.find(line)?.groupValues?.getOrNull(1)
                ?: "Arabic"
            subtitleCallback(newSubtitleFile(subName, resolveRelativeUrl(cleanUrl, subUri)))
        }

        val lines = manifestText.lines()
        val seenVariantUrls = HashSet<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val height = STREAM_INF_RESOLUTION_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) {
                    j++
                }
                if (j < lines.size) {
                    val variantUrl = resolveRelativeUrl(cleanUrl, lines[j].trim())
                    val q = height ?: parseQualityInt(null, variantUrl)
                    if (variantUrl.startsWith("http") && seenVariantUrls.add(variantUrl)) {
                        val qBadge = if (q > 0) "${q}p" else "HLS"
                        callback(
                            newExtractorLink(
                                source = sourceName,
                                name = "$sourceName [$qBadge]",
                                url = variantUrl,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = referer
                                this.quality = if (q > 0) q else Qualities.Unknown.value
                                this.headers = reqHeaders
                            }
                        )
                        emitted++
                    }
                    i = j
                }
            }
            i++
        }
        return emitted
    }

    private suspend fun emitProgressiveDownloadVideo(
        mp4Url: String,
        sourceName: String,
        referer: String,
        quality: Int,
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val cleanUrl = mp4Url.trim()
        if (!cleanUrl.startsWith("http")) return 0

        val reqHeaders = LinkedHashMap<String, String>()
        reqHeaders["User-Agent"] = BROWSER_UA
        if (referer.isNotBlank()) {
            reqHeaders["Referer"] = referer
        }
        reqHeaders.putAll(extraHeaders)

        val qVal = if (quality > 0) quality else Qualities.P720.value
        callback(
            newExtractorLink(
                source = sourceName,
                name = "$sourceName [${qVal}p MP4]",
                url = cleanUrl,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = qVal
                this.headers = reqHeaders
            }
        )
        return 1
    }

    // ---- Individual Pure-Kotlin Server Extractors ----

    private suspend fun extractGooglePhotos(
        embedUrl: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val html = httpGetText(embedUrl, referer = "https://photos.google.com/") ?: return 0
        val baseStreamUrl = Regex("""(https://video-downloads\.googleusercontent\.com/[^\s"'\\<>]+)""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\u003d", "=")
            ?.replace("\\u0026", "&")
            ?.substringBefore("=m")
            ?: return 0

        var count = 0
        count += emitProgressiveDownloadVideo("$baseStreamUrl=m37", sourceName, "https://photos.google.com/", 1080, callback = callback)
        count += emitProgressiveDownloadVideo("$baseStreamUrl=m22", sourceName, "https://photos.google.com/", 720, callback = callback)
        count += emitProgressiveDownloadVideo("$baseStreamUrl=m18", sourceName, "https://photos.google.com/", 360, callback = callback)
        return count
    }

    private suspend fun extractCloudWorker(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val fetched = fetchTextOrDirectVideo(embedUrl, referer = embedUrl) ?: return 0
        if (fetched is FetchOutcome.DirectVideo) {
            val q = parseQualityInt(sourceName, fetched.finalUrl)
            return emitProgressiveDownloadVideo(fetched.finalUrl, sourceName, "${originOf(fetched.finalUrl)}/", q, callback = callback)
        }
        val html = (fetched as FetchOutcome.Text).body
        val objText = Regex("""const\s+availableQualities\s*=\s*(\{[^;]+\})""").find(html)?.groupValues?.getOrNull(1)
            ?: return 0
        val obj = runCatching { JSONObject(objText) }.getOrNull() ?: return 0
        var count = 0
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val vUrl = obj.optString(k, "").trim()
            if (!vUrl.startsWith("http")) continue
            val q = parseQualityInt(k, vUrl)
            count += if (vUrl.contains(".m3u8", ignoreCase = true)) {
                emitHlsStreamAndVariants(vUrl, sourceName, embedUrl, forcedQuality = q, subtitleCallback = subtitleCallback, callback = callback)
            } else {
                emitProgressiveDownloadVideo(vUrl, sourceName, embedUrl, q, callback = callback)
            }
        }
        return count
    }

    private suspend fun extractPixelDrain(
        embedUrl: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val id = Regex("""pixeldrain\.com/(?:u|l|api/file)/([A-Za-z0-9_-]+)""")
            .find(embedUrl)?.groupValues?.getOrNull(1) ?: return 0
        return emitProgressiveDownloadVideo(
            mp4Url = "https://pixeldrain.com/api/file/$id?download",
            sourceName = sourceName,
            referer = "https://pixeldrain.com/",
            quality = Qualities.P1080.value,
            callback = callback,
        )
    }

    private suspend fun extractMediaFire(
        embedUrl: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val html = httpGetText(embedUrl, referer = "https://www.mediafire.com/") ?: return 0
        val direct = Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1) ?: return 0
        val q = parseQualityInt(null, direct).let { if (it > 0) it else Qualities.P720.value }
        return emitProgressiveDownloadVideo(direct, sourceName, "https://www.mediafire.com/", q, callback = callback)
    }

    private suspend fun extractOkRu(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val normalized = if (embedUrl.contains("videoembed")) {
            embedUrl
        } else {
            val vid = embedUrl.substringAfterLast('/').substringBefore('?')
            "https://ok.ru/videoembed/$vid"
        }
        val html = httpGetText(normalized, referer = "https://ok.ru/") ?: return 0
        val dataOptions = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?: return 0
        val root = runCatching { JSONObject(dataOptions) }.getOrNull() ?: return 0
        val metadataStr = root.optJSONObject("flashvars")?.optString("metadata", "").orEmpty()
        if (metadataStr.isBlank()) return 0
        val meta = runCatching { JSONObject(metadataStr) }.getOrNull() ?: return 0

        var count = 0
        val hlsUrl = meta.optString("hlsManifestUrl", "")
            .ifBlank { meta.optString("hlsMasterPlaylistUrl", "") }
            .trim()
        if (hlsUrl.startsWith("http")) {
            count += emitHlsStreamAndVariants(hlsUrl, sourceName, "https://ok.ru/", subtitleCallback = subtitleCallback, callback = callback)
        }

        val videos = meta.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val u = v.optString("url", "").trim()
                if (!u.startsWith("http")) continue
                val qName = v.optString("name", "")
                val q = when (qName.lowercase()) {
                    "ultra", "quad" -> 2160
                    "full" -> 1080
                    "hd" -> 720
                    "sd" -> 480
                    "low" -> 360
                    "lowest" -> 240
                    "mobile" -> 144
                    else -> parseQualityInt(qName, u)
                }
                count += emitProgressiveDownloadVideo(u, sourceName, "https://ok.ru/", q, callback = callback)
            }
        }
        return count
    }

    private suspend fun extractVkVideo(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val html = httpGetText(embedUrl, referer = "https://vk.com/") ?: return 0
        var count = 0
        val seen = HashSet<String>()

        Regex(""""hls"\s*:\s*"(https?:[^"]+)"""").find(html)?.groupValues?.getOrNull(1)?.let { raw ->
            val u = raw.replace("\\/", "/")
            if (seen.add(u)) {
                count += emitHlsStreamAndVariants(u, sourceName, "https://vk.com/", subtitleCallback = subtitleCallback, callback = callback)
            }
        }

        for (m in Regex(""""url(\d{3,4})"\s*:\s*"(https?:[^"]+)"""").findAll(html)) {
            val q = m.groupValues[1].toIntOrNull() ?: 720
            val u = m.groupValues[2].replace("\\/", "/")
            if (seen.add(u)) {
                count += emitProgressiveDownloadVideo(u, sourceName, "https://vk.com/", q, callback = callback)
            }
        }
        return count
    }

    private suspend fun extractVidara(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val fileCode = Regex("""/(?:e|d|v|embed)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.groupValues?.getOrNull(1)
            ?: embedUrl.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('.').trim()
        if (fileCode.isBlank()) return 0

        val primaryOrigin = originOf(embedUrl)
        var root: JSONObject? = null
        var activeOrigin = primaryOrigin

        for (candOrigin in linkedSetOf(primaryOrigin, "https://vidaraa.cc", "https://vidara.so")) {
            val postBody = JSONObject()
                .put("filecode", fileCode)
                .put("device", "web")
                .put("codecs", JSONArray())
                .toString()
                .toRequestBody("application/json".toMediaType())

            val postObj = runCatching {
                val req = Request.Builder()
                    .url("$candOrigin/api/stream")
                    .post(postBody)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json")
                    .header("Origin", candOrigin)
                    .header("Referer", "$candOrigin/e/$fileCode")
                    .build()
                apiHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else JSONObject(resp.body?.string().orEmpty())
                }
            }.getOrNull()

            if (postObj != null && postObj.optString("streaming_url").startsWith("http")) {
                root = postObj
                activeOrigin = candOrigin
                break
            }

            val getJson = httpGetText("$candOrigin/api/stream?filecode=$fileCode", referer = "$candOrigin/e/$fileCode")
            val getObj = getJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (getObj != null && (getObj.optString("streaming_url").startsWith("http") || getObj.optJSONObject("data")?.optString("streaming_url").orEmpty().startsWith("http"))) {
                root = getObj
                activeOrigin = candOrigin
                break
            }
        }
        val resolved = root ?: return 0

        val subsArr = resolved.optJSONArray("subtitles") ?: resolved.optJSONObject("data")?.optJSONArray("subtitles")
        if (subsArr != null) {
            for (i in 0 until subsArr.length()) {
                val s = subsArr.optJSONObject(i) ?: continue
                val sUrl = s.optString("file", "").ifBlank { s.optString("src", "") }.trim()
                if (sUrl.startsWith("http")) {
                    val label = s.optString("label", "").ifBlank { s.optString("lang", "Arabic") }
                    subtitleCallback(newSubtitleFile(label, sUrl))
                }
            }
        }

        val streamUrl = resolved.optString("streaming_url", "")
            .ifBlank { resolved.optJSONObject("data")?.optString("streaming_url", "").orEmpty() }
            .trim()
        if (!streamUrl.startsWith("http")) return 0

        return if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            emitHlsStreamAndVariants(streamUrl, sourceName, "$activeOrigin/", subtitleCallback = subtitleCallback, callback = callback)
        } else {
            emitProgressiveDownloadVideo(streamUrl, sourceName, "$activeOrigin/", Qualities.P1080.value, callback = callback)
        }
    }

    private suspend fun extractVoe(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        var html = httpGetText(embedUrl, referer = embedUrl) ?: return 0
        var activeUrl = embedUrl
        Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)?.let { redir ->
            activeUrl = redir
            html = httpGetText(redir, referer = embedUrl) ?: return 0
        }

        val refOrigin = "${originOf(activeUrl)}/"
        var count = 0

        // Decode VOE obfuscated `<script type="application/json">["..."]</script>`
        val encodedToken = Regex("""<script[^>]+type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*</script>""")
            .find(html)?.groupValues?.getOrNull(1)
        if (!encodedToken.isNullOrBlank()) {
            val decodedJson = runCatching { decodeVoePayload(encodedToken) }.getOrNull()
            if (decodedJson != null) {
                val hls = decodedJson.optString("source", "").trim()
                if (hls.startsWith("http")) {
                    count += emitHlsStreamAndVariants(hls, sourceName, refOrigin, subtitleCallback = subtitleCallback, callback = callback)
                }
                val directMp4 = decodedJson.optString("direct_access_url", "").trim()
                if (directMp4.startsWith("http")) {
                    count += emitProgressiveDownloadVideo(directMp4, "$sourceName Direct", refOrigin, Qualities.P720.value, callback = callback)
                }
                val captions = decodedJson.optJSONArray("captions")
                if (captions != null) {
                    for (i in 0 until captions.length()) {
                        val c = captions.optJSONObject(i) ?: continue
                        val cFile = c.optString("file", "").trim()
                        if (cFile.startsWith("http")) {
                            subtitleCallback(newSubtitleFile(c.optString("label", "Arabic"), cFile))
                        }
                    }
                }
            }
        }

        if (count == 0) {
            Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)?.let { raw ->
                val resolved = if (raw.startsWith("http")) raw else runCatching {
                    String(TimeMoviesCrypto.base64DecodeFlexible(raw), StandardCharsets.UTF_8)
                }.getOrDefault("")
                if (resolved.startsWith("http")) {
                    count += emitHlsStreamAndVariants(resolved, sourceName, refOrigin, subtitleCallback = subtitleCallback, callback = callback)
                }
            }
        }
        return count
    }

    private fun decodeVoePayload(input: String): JSONObject? {
        val rot13 = buildString(input.length) {
            for (ch in input) {
                append(
                    when (ch) {
                        in 'a'..'z' -> 'a' + (ch - 'a' + 13) % 26
                        in 'A'..'Z' -> 'A' + (ch - 'A' + 13) % 26
                        else -> ch
                    }
                )
            }
        }
        var cleaned = rot13
        for (marker in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            cleaned = cleaned.replace(marker, "_")
        }
        cleaned = cleaned.replace("_", "")
        val step1 = String(TimeMoviesCrypto.base64DecodeFlexible(cleaned), StandardCharsets.UTF_8)
        val shifted = buildString(step1.length) {
            for (ch in step1) append((ch.code - 3).toChar())
        }.reversed()
        val finalJson = String(TimeMoviesCrypto.base64DecodeFlexible(shifted), StandardCharsets.UTF_8)
        return JSONObject(finalJson)
    }

    private suspend fun extractMixDrop(
        embedUrl: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val normalized = embedUrl.replace("/f/", "/e/")
        val html = httpGetText(normalized, referer = normalized) ?: return 0
        val unpacked = unpackDeanEdwards(html) ?: html
        val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.getOrNull(1)
            ?: return 0
        val fullUrl = resolveRelativeUrl(normalized, wurl)
        return emitProgressiveDownloadVideo(fullUrl, sourceName, "${originOf(normalized)}/", Qualities.P720.value, callback = callback)
    }

    private suspend fun extractByse(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int = runCatching {
        val code = embedUrl.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (code.isBlank()) return 0
        val siteOrigin = originOf(embedUrl)

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val kp = kpg.generateKeyPair()
        val pubEc = kp.public as java.security.interfaces.ECPublicKey

        fun pad32(bi: BigInteger): ByteArray {
            val raw = bi.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }

        val xB64Url = TimeMoviesCrypto.base64UrlEncodeNoPad(pad32(pubEc.w.affineX))
        val yB64Url = TimeMoviesCrypto.base64UrlEncodeNoPad(pad32(pubEc.w.affineY))
        val clientJwk = JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("x", xB64Url)
            .put("y", yB64Url)
            .toString()
        val clientKeyB64Url = TimeMoviesCrypto.base64UrlEncodeNoPad(clientJwk.toByteArray(StandardCharsets.UTF_8))

        val chReq = Request.Builder()
            .url("$siteOrigin/api/videos/$code/playback/challenge")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("User-Agent", BROWSER_UA)
            .header("Referer", embedUrl)
            .header("Origin", siteOrigin)
            .header("X-Playback-Key", clientKeyB64Url)
            .build()
        val chBody = apiHttpClient.newCall(chReq).execute().use { resp ->
            if (!resp.isSuccessful) return 0
            resp.body?.string().orEmpty()
        }
        val chJson = JSONObject(chBody)
        val challengeId = chJson.optString("challenge_id", "")
        val nonceB64 = chJson.optString("nonce", "")
        val difficulty = chJson.optInt("difficulty", 12)
        if (challengeId.isBlank() || nonceB64.isBlank()) return 0

        // Solve SHA-256 leading-zero-bits PoW
        var counter = 0L
        var powSolution = ""
        val sha256 = MessageDigest.getInstance("SHA-256")
        while (counter < 2_000_000L) {
            val candidate = "$challengeId:$nonceB64:$counter"
            val digest = sha256.digest(candidate.toByteArray(StandardCharsets.UTF_8))
            var zeroBits = 0
            for (b in digest) {
                val unsigned = b.toInt() and 0xFF
                if (unsigned == 0) {
                    zeroBits += 8
                } else {
                    zeroBits += Integer.numberOfLeadingZeros(unsigned) - 24
                    break
                }
            }
            if (zeroBits >= difficulty) {
                powSolution = counter.toString()
                break
            }
            counter++
        }
        if (powSolution.isEmpty()) return 0

        val signPayload = "$challengeId.$nonceB64.$powSolution".toByteArray(StandardCharsets.UTF_8)
        val ecdsa = Signature.getInstance("SHA256withECDSA")
        ecdsa.initSign(kp.private)
        ecdsa.update(signPayload)
        val p1363Sig = derEcdsaToP1363(ecdsa.sign())
        val sigB64Url = TimeMoviesCrypto.base64UrlEncodeNoPad(p1363Sig)

        val pbPayload = JSONObject()
            .put("challenge_id", challengeId)
            .put("pow", powSolution)
            .put("signature", sigB64Url)
            .toString()
        val pbReq = Request.Builder()
            .url("$siteOrigin/api/videos/$code/playback")
            .post(pbPayload.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", BROWSER_UA)
            .header("Referer", embedUrl)
            .header("Origin", siteOrigin)
            .header("X-Playback-Key", clientKeyB64Url)
            .build()
        val pbBody = apiHttpClient.newCall(pbReq).execute().use { resp ->
            if (!resp.isSuccessful) return 0
            resp.body?.string().orEmpty()
        }
        val pbJson = JSONObject(pbBody)

        var count = 0
        val directSources = pbJson.optJSONArray("sources")
        if (directSources != null) {
            for (i in 0 until directSources.length()) {
                val s = directSources.optJSONObject(i) ?: continue
                val u = s.optString("url", "").ifBlank { s.optString("file", "") }.trim()
                if (u.startsWith("http")) {
                    count += emitHlsStreamAndVariants(u, sourceName, "$siteOrigin/", subtitleCallback = subtitleCallback, callback = callback)
                }
            }
        }
        val tracks = pbJson.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val f = t.optString("file", "").trim()
                if (f.startsWith("http")) {
                    subtitleCallback(newSubtitleFile(t.optString("label", "Arabic"), f))
                }
            }
        }
        count
    }.getOrDefault(0)

    private fun derEcdsaToP1363(der: ByteArray): ByteArray {
        var idx = 2
        if (der[1].toInt() and 0x80 != 0) idx += (der[1].toInt() and 0x7F)
        idx++ // 0x02
        val rLen = der[idx++].toInt() and 0xFF
        val rBytes = der.copyOfRange(idx, idx + rLen)
        idx += rLen
        idx++ // 0x02
        val sLen = der[idx++].toInt() and 0xFF
        val sBytes = der.copyOfRange(idx, idx + sLen)

        fun norm32(b: ByteArray): ByteArray = when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
        return norm32(rBytes) + norm32(sBytes)
    }

    /**
     * Queries Cloud-Day's remote `extractor.php?url=...` AND performs direct JWPlayer / Packer HTML
     * scraping so both multi-quality HLS streams and direct progressive `.mp4` download links are
     * extracted cleanly for `VidTube`, `Mp4Plus`, `Mp4Upload`, `StreamHG`, `EarnVids`, `Uqload`, etc.
     */
    private suspend fun extractViaRemotePhpAndHtml(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        var count = 0
        val seenUrls = HashSet<String>()
        val embedOrigin = "${originOf(embedUrl)}/"

        // 1. Remote `extractor.php` (instant JSON multi-quality links for VidTube / Mp4Plus / StreamHG)
        val extractorEndpoint = resolveBackendUrls(cimaCloudBackend).extractorUrl
        val encoded = URLEncoder.encode(embedUrl, "UTF-8")
        val sep = if (extractorEndpoint.contains("?")) "&" else "?"
        val phpJsonText = httpGetText(
            url = "$extractorEndpoint${sep}url=$encoded",
            extraHeaders = mapOf("User-Agent" to CLOUDDAY_APP_UA),
            fast = true,
        )
        if (!phpJsonText.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(phpJsonText)
                if (root.has("error")) return@runCatching
                val topHeaders = LinkedHashMap<String, String>()
                root.optJSONObject("headers")?.let { hdrObj ->
                    val kIter = hdrObj.keys()
                    while (kIter.hasNext()) {
                        val hk = kIter.next()
                        val hv = hdrObj.optString(hk, "").trim()
                        if (hv.isNotEmpty()) topHeaders[hk] = hv
                    }
                }

                val subsArr = root.optJSONArray("subtitles") ?: root.optJSONArray("tracks")
                if (subsArr != null) {
                    for (i in 0 until subsArr.length()) {
                        val s = subsArr.optJSONObject(i) ?: continue
                        val sUrl = s.optString("file", "").ifBlank { s.optString("url", "") }.trim()
                        if (sUrl.startsWith("http")) {
                            subtitleCallback(newSubtitleFile(s.optString("label", "Arabic"), sUrl))
                        }
                    }
                }

                val linksArr = root.optJSONArray("servers")
                    ?: root.optJSONArray("links")
                    ?: root.optJSONArray("sources")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONObject("data")?.optJSONArray("links")
                if (linksArr != null) {
                    for (i in 0 until linksArr.length()) {
                        val item = linksArr.optJSONObject(i) ?: continue
                        val mediaUrl = item.optString("url", "")
                            .ifBlank { item.optString("file", "") }
                            .ifBlank { item.optString("src", "") }
                            .trim()
                        if (!mediaUrl.startsWith("http") || !seenUrls.add(mediaUrl)) continue

                        val itemHeaders = LinkedHashMap<String, String>(topHeaders)
                        item.optString("referer", "").trim().takeIf { it.isNotEmpty() }?.let { itemHeaders["Referer"] = it }
                        item.optString("origin", "").trim().takeIf { it.isNotEmpty() }?.let { itemHeaders["Origin"] = it }
                        item.optJSONObject("headers")?.let { hObj ->
                            val kIter = hObj.keys()
                            while (kIter.hasNext()) {
                                val hk = kIter.next()
                                val hv = hObj.optString(hk, "").trim()
                                if (hv.isNotEmpty()) itemHeaders[hk] = hv
                            }
                        }
                        val effRef = itemHeaders["Referer"] ?: itemHeaders["referer"] ?: embedOrigin

                        val qLabel = item.optString("quality", "")
                            .ifBlank { item.optString("label", "") }
                            .ifBlank { item.optString("name", "") }
                        val q = parseQualityInt(qLabel, mediaUrl)
                        val typeStr = item.optString("type", "").lowercase()
                        val isHls = mediaUrl.contains(".m3u8", ignoreCase = true) || typeStr.contains("hls") || typeStr.contains("m3u8")
                        val isAutoMaster = qLabel.equals("auto", ignoreCase = true) || mediaUrl.contains("master", ignoreCase = true)
                        count += if (isHls) {
                            emitHlsStreamAndVariants(
                                m3u8Url = mediaUrl,
                                sourceName = sourceName,
                                referer = effRef,
                                extraHeaders = itemHeaders,
                                forcedQuality = if (q > 0 && !isAutoMaster) q else 0,
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                            )
                        } else {
                            emitProgressiveDownloadVideo(
                                mp4Url = mediaUrl,
                                sourceName = sourceName,
                                referer = effRef,
                                quality = q,
                                extraHeaders = itemHeaders,
                                callback = callback,
                            )
                        }
                    }
                }

                // Single `url` / `file` fallback in `extractor.php`
                val singleUrl = root.optString("url", "").ifBlank { root.optString("file", "") }.trim()
                if (singleUrl.startsWith("http") && seenUrls.add(singleUrl)) {
                    val effRef = topHeaders["Referer"] ?: topHeaders["referer"] ?: embedOrigin
                    count += if (singleUrl.contains(".m3u8", ignoreCase = true)) {
                        emitHlsStreamAndVariants(singleUrl, sourceName, effRef, topHeaders, subtitleCallback = subtitleCallback, callback = callback)
                    } else {
                        emitProgressiveDownloadVideo(singleUrl, sourceName, effRef, parseQualityInt(null, singleUrl), topHeaders, callback = callback)
                    }
                }
            }
        }
        if (count > 0) return count

        // 2. Direct HTML / Dean Edwards Packer / JWPlayer `sources` scraping
        val rawHtml = httpGetText(embedUrl, referer = embedOrigin) ?: return 0
        val combinedBody = buildString {
            append(rawHtml)
            unpackDeanEdwards(rawHtml)?.let {
                append('\n')
                append(it)
            }
        }

        // Extract subtitle tracks (`kind:"captions"` / `.vtt` / `.srt`)
        for (subM in Regex("""file\s*:\s*["'](https?://[^"']+\.(?:vtt|srt)(?:\?[^"']*)?)["'][^}]*?label\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(combinedBody)) {
            subtitleCallback(newSubtitleFile(subM.groupValues[2], subM.groupValues[1]))
        }

        // Extract `{file:"...", label:"..."}` objects first so quality labels are preserved
        val fileObjRegex = Regex(
            """\{\s*file\s*:\s*["'](https?://[^"']+)["'](?:\s*,\s*label\s*:\s*["']([^"']*)["'])?""",
            RegexOption.IGNORE_CASE,
        )
        for (m in fileObjRegex.findAll(combinedBody)) {
            val u = m.groupValues[1].trim()
            if (u.endsWith(".vtt", true) || u.endsWith(".srt", true) || u.endsWith(".jpg", true) || u.endsWith(".png", true)) continue
            if (!seenUrls.add(u)) continue
            val lbl = m.groupValues.getOrNull(2).orEmpty()
            val q = parseQualityInt(lbl, u)
            count += if (u.contains(".m3u8", ignoreCase = true)) {
                emitHlsStreamAndVariants(
                    m3u8Url = u,
                    sourceName = sourceName,
                    referer = embedOrigin,
                    forcedQuality = if (q > 0 && !u.contains("master", ignoreCase = true)) q else Qualities.Unknown.value,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            } else if (u.contains(".mp4", ignoreCase = true) || u.contains(".mkv", ignoreCase = true)) {
                emitProgressiveDownloadVideo(u, sourceName, embedOrigin, q, callback = callback)
            } else {
                0
            }
        }

        if (count == 0) {
            val genericMediaRegex = Regex("""["'](https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
            for (m in genericMediaRegex.findAll(combinedBody)) {
                val u = m.groupValues[1].trim()
                if (!seenUrls.add(u)) continue
                count += if (u.contains(".m3u8", ignoreCase = true)) {
                    emitHlsStreamAndVariants(u, sourceName, embedOrigin, subtitleCallback = subtitleCallback, callback = callback)
                } else {
                    emitProgressiveDownloadVideo(u, sourceName, embedOrigin, parseQualityInt(null, u), callback = callback)
                }
            }
        }
        return count
    }

    private suspend fun dispatchSingleServer(
        embed: UnlimitedServerEmbed,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val url = embed.url.trim()
        val host = url.lowercase()
        val sourceName = embed.name

        var count = when {
            host.endsWith(".mp4") || host.endsWith(".mkv") || Regex("""\.(?:mp4|mkv)\?""").containsMatchIn(host) ->
                emitProgressiveDownloadVideo(url, sourceName, "${originOf(url)}/", parseQualityInt(sourceName, url), callback = callback)

            host.endsWith(".m3u8") || Regex("""\.m3u8\?""").containsMatchIn(host) ->
                emitHlsStreamAndVariants(url, sourceName, "${originOf(url)}/", subtitleCallback = subtitleCallback, callback = callback)

            host.contains("photos.app.goo.gl") || host.contains("photos.google.com") ->
                extractGooglePhotos(url, sourceName, callback)

            host.contains("workers.dev") ->
                extractCloudWorker(url, sourceName, subtitleCallback, callback)

            host.contains("pixeldrain.com") ->
                extractPixelDrain(url, sourceName, callback)

            host.contains("mediafire.com") ->
                extractMediaFire(url, sourceName, callback)

            host.contains("ok.ru") || host.contains("odnoklassniki.ru") ->
                extractOkRu(url, sourceName, subtitleCallback, callback)

            host.contains("vk.com") || host.contains("vkvideo.ru") ->
                extractVkVideo(url, sourceName, subtitleCallback, callback)

            host.contains("vidara.") ->
                extractVidara(url, sourceName, subtitleCallback, callback)

            host.contains("byse") || host.contains("bysetayico") || host.contains("bysezejataos") ->
                extractByse(url, sourceName, subtitleCallback, callback)

            host.contains("voe.sx") || host.contains("dianaavoidthey") || host.contains("lancewhosedifficult") ->
                extractVoe(url, sourceName, subtitleCallback, callback)

            host.contains("mixdrop.") || host.contains("mxdrop.") || host.contains("m1xdrop.") ->
                extractMixDrop(url, sourceName, callback)

            else ->
                extractViaRemotePhpAndHtml(url, sourceName, subtitleCallback, callback)
        }

        // Universal CloudStream built-in extractor fallback on Android runtime if custom handler yielded 0 links
        if (count == 0 && isAndroidDeviceRuntime && TimeMoviesPrefs.apiBaseUrlOverride == null) {
            val beforeFallback = java.util.concurrent.atomic.AtomicInteger(0)
            runCatching {
                loadExtractor(
                    url = url,
                    referer = "${originOf(url)}/",
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        beforeFallback.incrementAndGet()
                        callback(link)
                    },
                )
            }
            count += beforeFallback.get()
        }
        return count
    }

    // =========================================================================
    // 4. Opportunistic Residential `CimaNow` Direct MP4 Download Harvester
    // =========================================================================

    private suspend fun extractOpportunisticCimaNowDirectMp4s(
        payload: TimeMoviesModels.LinkPayload,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        if (TimeMoviesPrefs.apiBaseUrlOverride != null) return 0
        val now = System.currentTimeMillis()
        if (now < cimaNowDatacenterBlockedUntilMs) return 0

        val q = payload.titleAr?.takeIf { it.isNotBlank() }
            ?: payload.title.takeIf { it.isNotBlank() && !it.equals("TimeMovies", true) }
            ?: return 0

        val searchUrl = "$CIMANOW_BASE_URL/?s=${URLEncoder.encode(q.replace(TRAILING_YEAR_PARENS_REGEX, "").trim(), "UTF-8")}"
        val html = runCatching {
            val req = Request.Builder()
                .url(searchUrl)
                .get()
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html")
                .build()
            fastProbeClient.newCall(req).execute().use { resp ->
                if (resp.code == 403) {
                    // Datacenter IP gate — pause CimaNow probes for 30 minutes so zero time is wasted
                    cimaNowDatacenterBlockedUntilMs = now + 30L * 60L * 1000L
                    return@use null
                }
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return 0

        val decodedHtml = decodeCimaNowObfuscatedHtmlIfNeeded(html)
        val targetNorms = listOfNotNull(
            normalizeCompareTitle(payload.title, stripSeasonSuffix = !payload.isMovie).takeIf { it.isNotEmpty() },
            normalizeCompareTitle(payload.titleAr, stripSeasonSuffix = !payload.isMovie).takeIf { it.isNotEmpty() },
        )
        if (targetNorms.isEmpty()) return 0

        val articleRegex = Regex("""<article[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
        var matchedPageUrl: String? = null
        var bestSim = 0.0
        for (m in articleRegex.findAll(decodedHtml)) {
            val block = m.groupValues[1]
            val href = Regex("""href=["'](https?://[^"']+cimanow[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1) ?: continue
            val isSelary = href.contains("/selary/", ignoreCase = true) || block.contains("مسلسل")
            if (payload.isMovie && isSelary) continue
            if (!payload.isMovie && !isSelary) continue

            val titleText = Regex("""<li[^>]*aria-label=["']title["'][^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), " ")
                ?: URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
            val normCand = normalizeCompareTitle(titleText, stripSeasonSuffix = !payload.isMovie)
            val sim = targetNorms.maxOfOrNull { computeTitleSimilarity(it, normCand) } ?: 0.0
            if (sim > bestSim && sim >= 0.60) {
                bestSim = sim
                matchedPageUrl = href
            }
        }

        var watchTarget = matchedPageUrl ?: return 0
        if (!payload.isMovie) {
            val seriesHtml = httpGetText(watchTarget, referer = "$CIMANOW_BASE_URL/", fast = true)
                ?.let { decodeCimaNowObfuscatedHtmlIfNeeded(it) } ?: return 0
            val epNum = payload.episodeNum ?: 1
            val epHref = Regex("""href=["'](https?://[^"']+(?:ep|حلقة|ح)-?0*$epNum(?:[^0-9][^"']*|$))["']""", RegexOption.IGNORE_CASE)
                .find(seriesHtml)?.groupValues?.getOrNull(1) ?: return 0
            watchTarget = epHref
        }

        val watchingUrl = if (watchTarget.endsWith("/watching/")) watchTarget else "${watchTarget.trimEnd('/')}/watching/"
        val watchHtml = httpGetText(watchingUrl, referer = watchTarget, fast = true)
            ?.let { decodeCimaNowObfuscatedHtmlIfNeeded(it) } ?: return 0

        var count = 0
        val mp4DownloadRegex = Regex(
            """<a[^>]+href=["'](https?://[^"']+\.(?:mp4|mkv)(?:\?[^"']*)?)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        for (dl in mp4DownloadRegex.findAll(watchHtml)) {
            val dlUrl = dl.groupValues[1].trim()
            val labelText = dl.groupValues[2].replace(Regex("<[^>]+>"), " ")
            val q = parseQualityInt(labelText, dlUrl)
            count += emitProgressiveDownloadVideo(
                mp4Url = dlUrl,
                sourceName = "TimeMovies ♾️ CimaNow Direct",
                referer = "$CIMANOW_BASE_URL/",
                quality = q,
                callback = callback,
            )
        }
        return count
    }

    private fun decodeCimaNowObfuscatedHtmlIfNeeded(html: String): String {
        if (!html.contains("CimaNow", ignoreCase = false) && html.contains("<article", ignoreCase = true)) {
            return html
        }
        val b64Chunks = Regex("""['"]([A-Za-z0-9+/=]{80,})['"]""").findAll(html).map { it.groupValues[1] }.toList()
        if (b64Chunks.isEmpty()) return html
        return runCatching {
            val joined = b64Chunks.joinToString("")
            val decoded = URLDecoder.decode(
                String(TimeMoviesCrypto.base64DecodeFlexible(joined), StandardCharsets.ISO_8859_1),
                "UTF-8",
            )
            if (decoded.contains("<html", true) || decoded.contains("<article", true)) decoded else html
        }.getOrDefault(html)
    }

    // =========================================================================
    // 5. Public Entry Point for `TimeMoviesExtractors`
    // =========================================================================

    /**
     * Resolves multi-quality HLS Watch streams + Direct Progressive `.mp4` Download links + Arabic
     * subtitles at **zero quota cost**.
     */
    suspend fun extractUnlimitedLinks(
        payload: TimeMoviesModels.LinkPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        if (!isRuntimeActive()) return 0

        val totalLinks = java.util.concurrent.atomic.AtomicInteger(0)
        val countCallback: (ExtractorLink) -> Unit = { link ->
            totalLinks.incrementAndGet()
            callback(link)
        }

        val embeds = withContext(Dispatchers.IO) { resolveUnlimitedEmbeds(payload) }
        val semaphore = Semaphore(8)

        withContext(Dispatchers.IO) {
            val cloudDayJobs = embeds.map { embed ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        withTimeoutOrNull(14_000L) {
                            runCatching {
                                dispatchSingleServer(embed, subtitleCallback, countCallback)
                            }.getOrDefault(0)
                        } ?: 0
                    }
                }
            }
            val cimaNowJob = async(Dispatchers.IO) {
                withTimeoutOrNull(7_000L) {
                    runCatching {
                        extractOpportunisticCimaNowDirectMp4s(payload, countCallback)
                    }.getOrDefault(0)
                } ?: 0
            }

            cloudDayJobs.awaitAll()
            cimaNowJob.await()
        }

        return totalLinks.get()
    }
}
