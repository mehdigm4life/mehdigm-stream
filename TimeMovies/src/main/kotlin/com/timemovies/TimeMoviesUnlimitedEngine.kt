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
import kotlinx.coroutines.supervisorScope
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
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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

    // Engine 3: Egywatch EasyPlex JSON API (Movies + Series + Anime, zero-quota)
    private const val EGYWATCH_CODE = "p2lbgWkFrykA4QyUmpHihzmc5BNzIABq"
    private const val EGYWATCH_APP_UA = "EasyPlex (Android 14; M2006C3MG; Redmi angelica; en)"
    private const val EGYWATCH_PACKAGE = "com.linkletter.app"
    private val EGYWATCH_BASES = listOf(
        "https://gateway.egywatch.live/egywatchapp/public/api/",
        "https://rn62mwg.com/egywatchapp/public/api/",
    )

    // Engine 4: DramaWorld ArabyPros JSON API (Movies + Series + Arabic Subtitles, zero-quota)
    private const val DRAMAWORLD_KEY = "4F5A9C3D9A86FA54EACEDDD635185"
    private const val DRAMAWORLD_UUID = "d506abfd-9fe2-4b71-b979-feff21bcad13"
    private const val DRAMAWORLD_UA = "okhttp/4.12.0"
    private val DRAMAWORLD_BASES = listOf(
        "https://dwapp.arabypros.com/api",
        "https://test.arabypros.com/api",
    )

    // Engine 5: StarDima JSON API (Animated / Cartoon / Disney / Pixar / Anime Movies, zero-quota)
    private const val STARDIMA_API_KEY = "vGIu8q9aap55zyANSD3jvmbttClhuRmykbQjzsIQxoWloFmp29W2qqdTSrwR"
    private const val STARDIMA_UA = "jcartoonApp/1.0.8 (Android)"
    private const val STARDIMA_SECURE_TOKEN = "HACKER_DETECTED_TAMPERED_APK"
    private val STARDIMA_BASES = listOf(
        "https://f5f-efgeg59852-zfz2d-mmltizwwcvb5567r2r63e-zd46.stardima.app",
        "https://app.stardima.com",
        "https://jcartoonapp.wiib.top",
    )

    // Engine 6: Web Multi-Mirror Movie Harvesters + Universal 50-Host Server Scraper
    private const val DEFAULT_REMOTE_EXTRACTOR_URL = "https://cloud-day.online/cimacloud/extractor.php"
    private const val MAWDHOU3_SCRAPER_BASE = "https://mawdhou3.com/"
    private const val SHAHID4U_SEED_URL = "https://shaahidd4u.net"
    private const val CIMAWBAS_BASE_URL = "https://vid.mycima.cc"
    private const val TOPCINEMA_BASE_URL = "https://topcinema.film"
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
        val referer: String? = null,
        val origin: String? = null,
        val userAgent: String? = null,
    )

    private data class CachedServerList(
        val servers: List<UnlimitedServerEmbed>,
        val savedAtMs: Long,
    )

    private val resolvedBackendCache = ConcurrentHashMap<String, ResolvedBackendUrls>()
    private val embedListCache = ConcurrentHashMap<String, CachedServerList>()

    @Volatile
    private var resolvedDramaWorldBase: String? = null

    @Volatile
    private var resolvedStarDimaBase: String? = null

    @Volatile
    private var cimaNowDatacenterBlockedUntilMs: Long = 0L

    @Volatile
    private var cimawbasBlockedUntilMs: Long = 0L

    @Volatile
    private var topCinemaBlockedUntilMs: Long = 0L

    internal fun clearCachesForTests() {
        resolvedBackendCache.clear()
        embedListCache.clear()
        resolvedDramaWorldBase = null
        resolvedStarDimaBase = null
        cimaNowDatacenterBlockedUntilMs = 0L
        cimawbasBlockedUntilMs = 0L
        topCinemaBlockedUntilMs = 0L
    }

    /**
     * Returns `true` when `TimeMoviesUnlimitedEngine` is permitted to run in the current runtime.
     * In mock-HTTP unit tests (`apiBaseUrlOverride != null`), external internet probing is skipped
     * unless an unlimited backend override or `forceUnlimitedInMockTests` is explicitly set.
     */
    fun isRuntimeActive(): Boolean {
        if (!TimeMoviesPrefs.unlimitedBypassEnabled) return false
        if (TimeMoviesPrefs.apiBaseUrlOverride != null) {
            return TimeMoviesPrefs.unlimitedCloudApiOverride != null ||
                TimeMoviesPrefs.unlimitedEgywatchApiOverride != null ||
                TimeMoviesPrefs.unlimitedDramaWorldApiOverride != null ||
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
    // 2. Precision Bilingual Movie & Series Title / Year / Sequel / Season Matching
    // =========================================================================

    private val ARABIC_DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val TRAILING_YEAR_PARENS_REGEX = Regex("\\s*[\\(\\[]\\s*(?:19|20)\\d{2}\\s*[\\)\\]]\\s*$")
    private val HYPHENATED_WORDS_REGEX = Regex("([A-Za-z\\u0621-\\u064A])[\\-\u2010-\u2015]([A-Za-z\\u0621-\\u064A])")
    private val SEASON_SUFFIX_REGEX = Regex(
        "(?:\\s*[-:]?\\s*(?:الموسم|موسم|season|الجزء)\\s*(?:الاول|الأول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر|\\d+))+$",
        RegexOption.IGNORE_CASE,
    )
    private val NON_ALNUM_ARABIC_REGEX = Regex("[^a-z0-9\\u0621-\\u064A]+")
    private val LATIN_HAS_CHAR_REGEX = Regex("[a-z]")
    private val ARABIC_HAS_CHAR_REGEX = Regex("[\\u0621-\\u064A]")
    private val YEAR_EXTRACT_REGEX = Regex("((?:19|20)\\d{2})")

    // Token-level noise set (avoids Java ASCII `\b` bug on Arabic script)
    private val NOISE_TOKENS = setOf(
        "فيلم", "افلام", "مسلسل", "انمي", "كرتون", "مترجم", "مترجمه", "مدبلج", "مدبلجه",
        "اون", "لاين", "اونلاين", "كامل", "كامله", "حصري", "حصريا", "مشاهده", "تحميل",
        "بجوده", "جوده", "عاليه", "نسخه", "اصليه", "عربي", "بالعربي", "اجنبي", "اسيوي",
        "الجزء", "جزء", "part", "chapter",
        "hd", "fhd", "uhd", "4k", "2160p", "1080p", "720p", "480p", "360p",
        "bluray", "webdl", "webrip", "brrip", "bdrip", "dvdrip", "hdrip", "hdtv",
        "hdcam", "cam", "ts", "hc", "subbed", "dubbed", "online",
    )

    private fun mapSequelToken(token: String, index: Int): String = when (token) {
        "اولاد" -> "ولاد"
        "ii", "two", "الثاني", "ثاني" -> "2"
        "iii", "three", "الثالث", "ثالث" -> "3"
        "iv", "four", "الرابع", "رابع" -> "4"
        "five", "الخامس", "خامس" -> "5"
        "vi", "six", "السادس", "سادس" -> "6"
        "vii", "seven", "السابع", "سابع" -> "7"
        "viii", "eight", "الثامن", "ثامن" -> "8"
        "ix", "nine", "التاسع", "تاسع" -> "9"
        "ten", "العاشر", "عاشر" -> "10"
        // Map single-letter Roman numerals V and X only after the first word (preserves "X" and "V for Vendetta")
        "v" -> if (index > 0) "5" else token
        "x" -> if (index > 0) "10" else token
        else -> token
    }

    private fun isReleaseYearToken(token: String): Boolean {
        if (token.length != 4) return false
        val y = token.toIntOrNull() ?: return false
        return y in 1925..2035
    }

    internal fun normalizeCompareTitle(
        raw: String?,
        stripSeasonSuffix: Boolean = false,
        stripTrailingYearToken: Boolean = true,
    ): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        s = s.replace(TRAILING_YEAR_PARENS_REGEX, " ")
        // Strip Latin accents ("Pokémon" -> "Pokemon", "Folie à Deux" -> "Folie a Deux")
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = COMBINING_MARKS_REGEX.replace(s, "")
        s = s.lowercase()
            .replace("&", " and ")
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
        if (stripSeasonSuffix) {
            s = SEASON_SUFFIX_REGEX.replace(s, " ")
        }
        val rawTokens = NON_ALNUM_ARABIC_REGEX.split(s).filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) return ""

        val cleaned = ArrayList<String>(rawTokens.size)
        for (tok in rawTokens) {
            if (tok in NOISE_TOKENS) continue
            cleaned.add(tok)
        }
        if (cleaned.isEmpty()) return ""

        val mapped = cleaned.mapIndexed { idx, tok -> mapSequelToken(tok, idx) }.toMutableList()
        if (stripTrailingYearToken && mapped.size >= 2 && isReleaseYearToken(mapped.last())) {
            mapped.removeAt(mapped.lastIndex)
        }
        return mapped.joinToString(" ")
    }

    private fun stripEmbeddedReleaseYears(norm: String): String {
        val tokens = norm.split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 2) return norm
        val nonYears = tokens.filterNot { isReleaseYearToken(it) }
        return if (nonYears.isNotEmpty()) nonYears.joinToString(" ") else tokens.first()
    }

    /**
     * Produces normalized views of a title across Full, Year-Free, Latin-only, Arabic-only, and
     * joined-hyphen forms so bilingual catalog entries like `"فيلم Oppenheimer 2023 اوبنهايمر مترجم"`
     * or `"Spider-Man"` vs `"Spiderman"` achieve 1.00 exact similarity.
     */
    internal fun extractTitleNormViews(raw: String?, stripSeasonSuffix: Boolean): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val variants = LinkedHashSet<String>()
        val inputs = buildList {
            add(raw)
            if (HYPHENATED_WORDS_REGEX.containsMatchIn(raw)) {
                add(HYPHENATED_WORDS_REGEX.replace(raw, "$1$2"))
            }
        }
        for (inp in inputs) {
            val full = normalizeCompareTitle(inp, stripSeasonSuffix = stripSeasonSuffix, stripTrailingYearToken = true)
            val fullWithYear = normalizeCompareTitle(inp, stripSeasonSuffix = stripSeasonSuffix, stripTrailingYearToken = false)
            val yearFree = stripEmbeddedReleaseYears(full)
            for (norm in linkedSetOf(full, yearFree, fullWithYear)) {
                if (norm.isEmpty()) continue
                variants.add(norm)
                val tokens = norm.split(' ').filter { it.isNotEmpty() }
                val hasLatin = tokens.any { LATIN_HAS_CHAR_REGEX.containsMatchIn(it) }
                val hasArabic = tokens.any { ARABIC_HAS_CHAR_REGEX.containsMatchIn(it) }
                if (hasLatin && hasArabic) {
                    val latinTokens = tokens.filter {
                        (LATIN_HAS_CHAR_REGEX.containsMatchIn(it) || it.all(Char::isDigit)) && !isReleaseYearToken(it)
                    }
                    if (latinTokens.isNotEmpty() && latinTokens.any { LATIN_HAS_CHAR_REGEX.containsMatchIn(it) }) {
                        variants.add(latinTokens.joinToString(" "))
                    }

                    val arabicTokens = tokens.filter {
                        (ARABIC_HAS_CHAR_REGEX.containsMatchIn(it) || it.all(Char::isDigit)) && !isReleaseYearToken(it)
                    }
                    if (arabicTokens.isNotEmpty() && arabicTokens.any { ARABIC_HAS_CHAR_REGEX.containsMatchIn(it) }) {
                        variants.add(arabicTokens.joinToString(" "))
                    }
                }
            }
        }
        return variants.toList()
    }

    internal fun extractSequelNumber(norm: String): Int? {
        if (norm.isBlank()) return null
        val tokens = norm.split(' ').filter { it.isNotEmpty() && it != "part" && it != "الجزء" && it != "جزء" }
        if (tokens.size < 2) return null
        // Inspect from the end for sequel numbers 1..15 (ignoring years or titles like "District 9" only if identical on both sides)
        val lastNum = tokens.last().toIntOrNull()
        if (lastNum != null && lastNum in 1..15) return lastNum
        for (i in tokens.lastIndex downTo 1) {
            val n = tokens[i].toIntOrNull()
            if (n != null && n in 2..15) return n
        }
        return null
    }

    private fun extractColonOrDashPrefixes(raw: String?, stripSeasonSuffix: Boolean): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val clean = raw.replace(TRAILING_YEAR_PARENS_REGEX, "").trim()
        val prefixes = LinkedHashSet<String>()
        if (clean.contains(':')) {
            val p = clean.substringBefore(':').trim()
            if (p.length >= 4 && p.length < clean.length) prefixes.add(p)
        }
        for (sep in listOf(" - ", " – ", " — ")) {
            if (clean.contains(sep)) {
                val p = clean.substringBefore(sep).trim()
                if (p.length >= 4 && p.length < clean.length) prefixes.add(p)
            }
        }
        return prefixes.flatMap { extractTitleNormViews(it, stripSeasonSuffix) }
            .filter { norm ->
                val tokCount = norm.split(' ').count { it.isNotEmpty() }
                tokCount >= 2 || norm.length >= 6
            }
            .distinct()
    }

    internal fun buildPrimaryTargetNorms(payload: TimeMoviesModels.LinkPayload): List<String> {
        val stripSeason = !payload.isMovie
        return buildList {
            addAll(extractTitleNormViews(payload.title, stripSeasonSuffix = stripSeason))
            if (stripSeason) addAll(extractTitleNormViews(payload.title, stripSeasonSuffix = false))
            addAll(extractTitleNormViews(payload.titleAr, stripSeasonSuffix = stripSeason))
            if (stripSeason) addAll(extractTitleNormViews(payload.titleAr, stripSeasonSuffix = false))
            addAll(extractTitleNormViews(payload.originalTitle, stripSeasonSuffix = stripSeason))
        }.distinct()
    }

    private fun buildPrefixTargetNorms(payload: TimeMoviesModels.LinkPayload, primaryNorms: List<String>): List<String> {
        val stripSeason = !payload.isMovie
        val primarySet = primaryNorms.toSet()
        return buildList {
            addAll(extractColonOrDashPrefixes(payload.title, stripSeason))
            addAll(extractColonOrDashPrefixes(payload.titleAr, stripSeason))
            addAll(extractColonOrDashPrefixes(payload.originalTitle, stripSeason))
        }.filter { it !in primarySet }.distinct()
    }

    private fun extractSeasonHintFromTitle(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val lower = ARABIC_DIACRITICS_REGEX.replace(raw.lowercase(), "")
        Regex("(?:season|الموسم|موسم|الجزء)\\s*(\\d{1,2})").find(lower)
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

    private fun areSequelNumbersIncompatible(targetNorms: List<String>, candidateViews: List<String>): Boolean {
        val targetSeqs = targetNorms.mapNotNull { extractSequelNumber(it) }.toSet()
        val candSeqs = candidateViews.mapNotNull { extractSequelNumber(it) }.toSet()
        if (targetSeqs.isEmpty() && candSeqs.isEmpty()) return false
        if (targetSeqs.isEmpty()) {
            // Target is Part 1 (unnumbered): reject candidate if it explicitly carries sequel >= 2
            return candSeqs.any { it >= 2 }
        }
        if (candSeqs.isEmpty()) {
            // Target explicitly asks for sequel >= 2, candidate has no sequel number -> incompatible unless prefix matched
            return targetSeqs.any { it >= 2 }
        }
        return targetSeqs.intersect(candSeqs).isEmpty()
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
        targetNorms: List<String> = buildPrimaryTargetNorms(payload),
    ): ScoredCatalogCandidate? {
        if (targetNorms.isEmpty()) return null
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
            item.has("seasons_count") || item.has("episodes_count") || item.has("seasons") -> false
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

        val itemYear = YEAR_EXTRACT_REGEX.find(
            item.optString("year", "").ifBlank {
                item.optString("release_date", "").ifBlank { item.optString("first_air_date", "") }
            }
        )?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1920..2035 }
            ?: rawNames.firstNotNullOfOrNull { rn ->
                TRAILING_YEAR_PARENS_REGEX.find(rn)?.let { m ->
                    YEAR_EXTRACT_REGEX.find(m.value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }

        val stripSeason = !payload.isMovie
        val allCandViews = rawNames.flatMap { candRaw ->
            buildList {
                addAll(extractTitleNormViews(candRaw, stripSeasonSuffix = stripSeason))
                if (stripSeason) addAll(extractTitleNormViews(candRaw, stripSeasonSuffix = false))
            }
        }.distinct()
        if (allCandViews.isEmpty()) return null

        var bestSim = 0.0
        for (cView in allCandViews) {
            for (tNorm in targetNorms) {
                bestSim = maxOf(bestSim, computeTitleSimilarity(tNorm, cView))
            }
        }

        // Prefix fallback (e.g., "Furiosa: A Mad Max Saga" -> "Furiosa" or "ولاد رزق 3: القاضية" -> "ولاد رزق 3")
        // strictly gated by release year agreement (±1 year) when both years are known.
        var usedPrefixMatch = false
        if (bestSim < 0.72 && payload.isMovie) {
            val yearAllowsPrefix = payload.year == null || itemYear == null ||
                kotlin.math.abs(payload.year - itemYear) <= 1
            if (yearAllowsPrefix) {
                val prefixTargets = buildPrefixTargetNorms(payload, targetNorms)
                var prefixSim = 0.0
                for (cView in allCandViews) {
                    for (pNorm in prefixTargets) {
                        prefixSim = maxOf(prefixSim, computeTitleSimilarity(pNorm, cView))
                    }
                }
                if (prefixSim >= 0.92 && prefixSim > bestSim) {
                    bestSim = prefixSim * 0.88
                    usedPrefixMatch = true
                }
            }
        }

        if (bestSim < 0.52) return null

        var score = when {
            bestSim >= 0.999 -> 100
            bestSim >= 0.85 -> 84
            bestSim >= 0.72 -> 68
            else -> 50
        }

        if (!usedPrefixMatch && areSequelNumbersIncompatible(targetNorms, allCandViews)) {
            score -= 55
        }

        if (payload.year != null && itemYear != null) {
            val diff = kotlin.math.abs(payload.year - itemYear)
            when {
                diff == 0 -> score += 35
                diff == 1 -> score += 22
                diff == 2 -> score += 10
                diff == 3 -> score -= 8
                payload.isMovie && diff >= 6 -> score -= 65
                payload.isMovie -> score -= 45
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

    internal fun buildSearchQueries(payload: TimeMoviesModels.LinkPayload): List<String> {
        val queries = ArrayList<String>(8)
        val seenLower = HashSet<String>()

        fun addIfValid(q: String?) {
            val trimmed = q?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            if (trimmed.isNotEmpty() && !trimmed.equals("TimeMovies", ignoreCase = true)) {
                // Allow 1-char movie titles like "X" as well as numeric titles like "1917"
                if ((trimmed.length >= 2 || trimmed.any { it.isLetterOrDigit() }) && seenLower.add(trimmed.lowercase())) {
                    queries.add(trimmed)
                }
            }
        }

        val cleanEn = payload.title.replace(TRAILING_YEAR_PARENS_REGEX, "").trim()
        val cleanAr = payload.titleAr?.replace(TRAILING_YEAR_PARENS_REGEX, "")?.trim().orEmpty()
        val cleanOrig = payload.originalTitle?.replace(TRAILING_YEAR_PARENS_REGEX, "")?.trim().orEmpty()

        // 1. Primary English, Original, & Punctuation-clean Arabic titles
        addIfValid(cleanEn)
        if (cleanOrig.isNotEmpty()) {
            addIfValid(cleanOrig)
        }
        if (cleanAr.isNotEmpty()) {
            val bareAr = ARABIC_DIACRITICS_REGEX.replace(cleanAr, "")
                .replace(Regex("[:\\-–—،,?!؟\"'()]"), " ")
                .trim()
            val hamzaFreeAr = bareAr
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي')
            addIfValid(hamzaFreeAr)
            addIfValid(bareAr)
            addIfValid(cleanAr)
        }

        // 2. Shortened franchise prefix before `:` or ` - ` (Arabic & English — critical for "ولاد رزق 3: القاضية" & "Furiosa: A Mad Max Saga")
        for (src in listOf(cleanAr, cleanEn, cleanOrig)) {
            if (src.isEmpty()) continue
            val beforeColon = src.substringBefore(':').substringBefore(" - ").substringBefore(" – ").trim()
            if (beforeColon.length >= 4 && beforeColon.length < src.length) {
                val cleanPrefix = ARABIC_DIACRITICS_REGEX.replace(beforeColon, "")
                    .replace('أ', 'ا')
                    .replace('إ', 'ا')
                    .replace('آ', 'ا')
                addIfValid(cleanPrefix)
                addIfValid(beforeColon)
            }
        }

        // 3. Punctuation/hyphen/sequel-normalized English query ("Spider-Man: No Way Home" -> "Spiderman", "Dune: Part Two" -> "Dune 2")
        if (cleanEn.isNotEmpty()) {
            if (HYPHENATED_WORDS_REGEX.containsMatchIn(cleanEn)) {
                addIfValid(HYPHENATED_WORDS_REGEX.replace(cleanEn, "$1$2").substringBefore(':').trim())
            }
            val normEn = normalizeCompareTitle(cleanEn, stripSeasonSuffix = !payload.isMovie)
            if (normEn.isNotEmpty()) {
                addIfValid(normEn)
            }
            val noPunctEn = Normalizer.normalize(cleanEn, Normalizer.Form.NFD)
                .let { COMBINING_MARKS_REGEX.replace(it, "") }
                .replace("&", " and ")
                .replace(Regex("[:\\-–—.,?!'\"()]"), " ")
            addIfValid(noPunctEn)
        }

        // 4.Arabic spelling & sequel-root fallbacks (e.g. "ولاد رزق 3" -> "اولاد رزق 3" & "ولاد رزق" so SQL LIKE finds "فيلم اولاد رزق الجزء الثالث")
        if (cleanAr.isNotEmpty()) {
            val baseAr = cleanAr.substringBefore(':').substringBefore(" - ").trim()
            if (baseAr.startsWith("ولاد ")) {
                addIfValid("اولاد " + baseAr.removePrefix("ولاد "))
            }
            val rootNoTrailingNum = baseAr.replace(Regex("\\s+\\d+\\s*$"), "").trim()
            if (rootNoTrailingNum.length >= 5 && rootNoTrailingNum.length < baseAr.length) {
                addIfValid(rootNoTrailingNum)
            }
        }

        return queries.take(9)
    }

    private fun parseServerArray(json: JSONObject?, backendLabel: String): List<UnlimitedServerEmbed> {
        if (json == null) return emptyList()
        val arr = json.optJSONArray("servers")
            ?: json.optJSONArray("items")
            ?: json.optJSONArray("data")
            ?: json.optJSONObject("data")?.optJSONArray("servers")
            ?: json.optJSONObject("movie")?.optJSONArray("servers")
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
                    .ifBlank { srv.optString("file", "") }
                    .trim()
                srvName = srv.optString("name", "")
                    .ifBlank { srv.optString("server_name", "") }
                    .ifBlank { srv.optString("title", "") }
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

        val targetNorms = buildPrimaryTargetNorms(payload)
        if (targetNorms.isEmpty()) return emptyList()

        val candidatesById = LinkedHashMap<Int, ScoredCatalogCandidate>()
        for (query in queries) {
            var page1Count = 0
            for (pageNum in listOf("1", "2")) {
                if (pageNum == "2" && (page1Count < 15 || candidatesById.isNotEmpty())) break
                val res = cloudDayPost(
                    backend = backend,
                    endpoint = "search",
                    formFields = mapOf(
                        "title" to query,
                        "type" to "0",
                        "sort" to "1",
                        "page" to pageNum,
                    ),
                ) ?: break
                val items = res.optJSONArray("results")
                    ?: res.optJSONArray("items")
                    ?: res.optJSONArray("data")
                    ?: res.optJSONObject("data")?.optJSONArray("data")
                    ?: break
                if (pageNum == "1") page1Count = items.length()

                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    val scored = scoreCatalogItem(obj, payload, targetNorms) ?: continue
                    val prev = candidatesById[scored.id]
                    if (prev == null || scored.score > prev.score) {
                        candidatesById[scored.id] = scored
                    }
                }
            }
            if (candidatesById.values.any { it.score >= 115 }) break
        }

        if (candidatesById.isEmpty()) return emptyList()
        val ranked = candidatesById.values.sortedByDescending { it.score }

        if (payload.isMovie) {
            // Merge servers across top-ranked matching movie entries (e.g. Subbed + Dubbed + 4K/1080p copies,
            // or fall back cleanly if the #1 duplicate row has empty servers)
            val topScore = ranked.first().score
            val movieMerged = LinkedHashMap<String, UnlimitedServerEmbed>()
            for (cand in ranked.take(4)) {
                if (movieMerged.isNotEmpty() && cand.score < topScore - 22) break
                var servers = parseServerArray(cloudDayGet(backend, "movie/${cand.id}/servers"), backend.displayPrefix)
                if (servers.isEmpty()) {
                    // Fallback: inspect inline movie detail payload (`GET movie/{id}`)
                    servers = parseServerArray(cloudDayGet(backend, "movie/${cand.id}"), backend.displayPrefix)
                }
                for (srv in servers) {
                    movieMerged.putIfAbsent(srv.url, srv)
                }
                if (movieMerged.size >= 8) break
            }
            return movieMerged.values.toList()
        }

        val targetSeason = payload.seasonNum ?: 1
        val targetEpisode = payload.episodeNum ?: 1
        for (cand in ranked.take(4)) {
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
     * Resolves primary Cloud-Day (`CimaCloud` + `AnimeDay`) zero-quota server embeds for `payload`,
     * caching per `(workId, episodeId)` for 4 hours.
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
            // For series, 3 servers on primary backend is plenty; for movies, prefer >= 4 or check secondary if anime
            val enoughThreshold = if (payload.isMovie) 4 else 3
            if (collected.size >= enoughThreshold && !payload.isAnime) break
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

    private fun probeDirectVideoAlive(url: String, headers: Map<String, String>): Boolean {
        if (TimeMoviesPrefs.apiBaseUrlOverride != null) return true
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-11")
                .header("User-Agent", headers["User-Agent"] ?: BROWSER_UA)
            for ((k, v) in headers) {
                if (!k.equals("Range", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            fastProbeClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 404 || resp.code == 410 || resp.code == 416 || resp.code >= 500) {
                    return@use false
                }
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html")) return@use false
                resp.isSuccessful || resp.code == 206
            }
        }.getOrDefault(false)
    }

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
        for ((suffix, quality) in listOf("=m37" to 1080, "=m22" to 720, "=m18" to 360)) {
            val streamUrl = "$baseStreamUrl$suffix"
            val isValidMp4 = if (TimeMoviesPrefs.apiBaseUrlOverride != null) {
                true
            } else {
                runCatching {
                    val req = Request.Builder()
                        .url(streamUrl)
                        .get()
                        .header("User-Agent", BROWSER_UA)
                        .header("Referer", "https://photos.google.com/")
                        .header("Range", "bytes=0-11")
                        .build()
                    fastProbeClient.newCall(req).execute().use { resp ->
                        if (!(resp.isSuccessful || resp.code == 206)) return@use false
                        val head = ByteArray(12)
                        val n = resp.body?.byteStream()?.read(head) ?: 0
                        n >= 8 && String(head, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
                    }
                }.getOrDefault(false)
            }
            if (isValidMp4) {
                count += emitProgressiveDownloadVideo(streamUrl, sourceName, "https://photos.google.com/", quality, callback = callback)
            }
        }
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
        var count = 0

        // 1. `const availableQualities = {...}` worker format
        Regex("""const\s+availableQualities\s*=\s*(\{[^;]+\})""").find(html)?.groupValues?.getOrNull(1)?.let { objText ->
            val obj = runCatching { JSONObject(objText) }.getOrNull()
            if (obj != null) {
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
            }
        }
        if (count > 0) return count

        // 2. `*.developer-pro.workers.dev` JSON / direct media worker format
        if (html.trimStart().startsWith("{")) {
            runCatching {
                val json = JSONObject(html)
                val single = json.optString("url", "").ifBlank { json.optString("file", "") }.ifBlank { json.optString("stream", "") }.trim()
                if (single.startsWith("http")) {
                    count += if (single.contains(".m3u8", true)) {
                        emitHlsStreamAndVariants(single, sourceName, embedUrl, subtitleCallback = subtitleCallback, callback = callback)
                    } else {
                        emitProgressiveDownloadVideo(single, sourceName, embedUrl, parseQualityInt(sourceName, single), callback = callback)
                    }
                }
                val arr = json.optJSONArray("sources") ?: json.optJSONArray("links") ?: json.optJSONArray("qualities")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val u = s.optString("url", "").ifBlank { s.optString("file", "") }.trim()
                        if (!u.startsWith("http")) continue
                        val q = parseQualityInt(s.optString("quality", s.optString("label", "")), u)
                        count += if (u.contains(".m3u8", true)) {
                            emitHlsStreamAndVariants(u, sourceName, embedUrl, forcedQuality = q, subtitleCallback = subtitleCallback, callback = callback)
                        } else {
                            emitProgressiveDownloadVideo(u, sourceName, embedUrl, q, callback = callback)
                        }
                    }
                }
            }
        }
        if (count > 0) return count

        // 3. Embedded `<source>` / JWPlayer links inside HTML worker
        for (m in Regex("""["'](https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|mkv)(?:\?[^"'\\\s<>]*)?)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
            val u = m.groupValues[1].trim()
            count += if (u.contains(".m3u8", true)) {
                emitHlsStreamAndVariants(u, sourceName, embedUrl, subtitleCallback = subtitleCallback, callback = callback)
            } else {
                emitProgressiveDownloadVideo(u, sourceName, embedUrl, parseQualityInt(sourceName, u), callback = callback)
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
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""data-scrambled-url=["']([A-Za-z0-9+/=]+)["']""")
                .find(html)?.groupValues?.getOrNull(1)?.let { b64 ->
                    runCatching { String(TimeMoviesCrypto.base64DecodeFlexible(b64), StandardCharsets.UTF_8) }
                        .getOrNull()?.takeIf { it.startsWith("http") }
                }
            ?: return 0
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
                    forcedQuality = if (q > 0 && !u.contains("master", ignoreCase = true)) q else 0,
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

        // 3. Universal ~50-Host `mawdhou3.com/scrapefinal/*.php` Server Scraper Fallback
        if (count == 0) {
            count += extractViaMawdhou3Scraper(embedUrl, rawHtml, sourceName, embedOrigin, subtitleCallback, callback)
        }
        return count
    }

    private suspend fun extractHyperWatching(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val html = httpGetText(embedUrl, referer = "${originOf(embedUrl)}/") ?: return 0
        val dataPageRaw = Regex("""data-page=["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?.replace("&#039;", "'")
            ?: return 0
        val root = runCatching { JSONObject(dataPageRaw) }.getOrNull() ?: return 0
        val props = root.optJSONObject("props") ?: root
        var count = 0
        val seen = HashSet<String>()
        val ref = "${originOf(embedUrl)}/"

        for (arrKey in listOf("streams", "sources", "links", "qualities")) {
            val arr = props.optJSONArray(arrKey) ?: props.optJSONObject("video")?.optJSONArray(arrKey) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val u = item.optString("url", "").ifBlank { item.optString("src", "") }.ifBlank { item.optString("file", "") }.trim()
                if (!u.startsWith("http") || !seen.add(u)) continue
                val q = parseQualityInt(item.optString("quality", item.optString("label", "")), u)
                count += if (u.contains(".m3u8", true)) {
                    emitHlsStreamAndVariants(u, sourceName, ref, forcedQuality = q, subtitleCallback = subtitleCallback, callback = callback)
                } else {
                    emitProgressiveDownloadVideo(u, sourceName, ref, q, callback = callback)
                }
            }
        }
        val single = props.optJSONObject("video")?.optString("url", "")
            ?.ifBlank { props.optString("stream_url", "") }
            .orEmpty().trim()
        if (single.startsWith("http") && seen.add(single)) {
            count += if (single.contains(".m3u8", true)) {
                emitHlsStreamAndVariants(single, sourceName, ref, subtitleCallback = subtitleCallback, callback = callback)
            } else {
                emitProgressiveDownloadVideo(single, sourceName, ref, parseQualityInt(sourceName, single), callback = callback)
            }
        }
        return count
    }

    private suspend fun extractStreamHgVibuxer(
        embedUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val code = Regex("""/(?:e|v|d|f|embed)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
            ?: embedUrl.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('.')
        if (code.isNotBlank()) {
            for (mirrorHost in listOf("https://vibuxer.com", "https://hanerix.com")) {
                val mirrorUrl = "$mirrorHost/e/$code"
                val html = httpGetText(mirrorUrl, referer = embedUrl, fast = true) ?: continue
                val combined = buildString {
                    append(html)
                    unpackDeanEdwards(html)?.let {
                        append('\n')
                        append(it)
                    }
                }
                val hls = Regex("""["'](https?://[^"'\\\s<>]+?\.m3u8(?:\?[^"'\\\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                    .find(combined)?.groupValues?.getOrNull(1)
                if (!hls.isNullOrBlank()) {
                    val emitted = emitHlsStreamAndVariants(
                        m3u8Url = hls,
                        sourceName = sourceName,
                        referer = "$mirrorHost/",
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                    )
                    if (emitted > 0) return emitted
                }
            }
        }
        return extractViaRemotePhpAndHtml(embedUrl, sourceName, subtitleCallback, callback)
    }

    private data class Mawdhou3Endpoint(
        val scriptPath: String,
        val isPost: Boolean = true,
        val defaultReferer: String? = null,
    )

    private fun resolveMawdhou3Endpoint(embedUrl: String): Mawdhou3Endpoint? {
        val u = embedUrl.lowercase()
        return when {
            u.contains("vidtube.") -> Mawdhou3Endpoint("scrapefinal/vidtube_new.php", true, embedUrl)
            u.contains("vidspeed.") || u.contains("vidspeeds.") -> Mawdhou3Endpoint("scrapefinal/vidspeed.php", true, "https://vidspeed.org/")
            u.contains("vidmoly.") -> Mawdhou3Endpoint("scrapefinal/vidmoly.php", false, "https://vidmoly.net/")
            u.contains("uqload.") -> Mawdhou3Endpoint("scrapefinal/uqload_new.php", true, "https://uqload.cx/")
            u.contains("mp4upload.") -> Mawdhou3Endpoint("scrapefinal/mp4upload_new.php", true, "https://www.mp4upload.com/")
            u.contains("filemoon.") -> Mawdhou3Endpoint("scrapefinal/filemoon.php", true, "${originOf(embedUrl)}/")
            u.contains("wishonly.") || u.contains("streamwish.") || u.contains("wishembed.") ||
                u.contains("hlswish.") || u.contains("sfastwish.") || u.contains("hglink.") ||
                u.contains("cavanhabg.") -> Mawdhou3Endpoint("scrapefinal/wishonly.php", true, "https://hglink.to/")
            u.contains("savefiles.") || u.contains("streamhls.") -> Mawdhou3Endpoint("scrapefinal/savefiles.php", true, "${originOf(embedUrl)}/")
            u.contains("lulustream.") || u.contains("luluvdo.") -> Mawdhou3Endpoint("scrapefinal/lulustream.php", true, "${originOf(embedUrl)}/")
            u.contains("earnvids.") || u.contains("vidhide") || u.contains("smoothpre.") ||
                u.contains("dintezuvio.") || u.contains("ryderjet.") -> Mawdhou3Endpoint("scrapefinal/earnvids.php", true, "${originOf(embedUrl)}/")
            u.contains("streamtape.") -> Mawdhou3Endpoint("scrapefinal/streamtape.php", true, "https://streamtape.com/")
            u.contains("vidoza.") -> Mawdhou3Endpoint("scrapefinal/vidoza.php", true, "https://vidoza.net/")
            u.contains("vtube.") || u.contains("vtbe.") -> Mawdhou3Endpoint("scrapefinal/vtube.php", true, "https://vtbe.to/")
            u.contains("upstream.") -> Mawdhou3Endpoint("scrapefinal/upstream.php", true, "${originOf(embedUrl)}/")
            u.contains("goodstream.") -> Mawdhou3Endpoint("scrapefinal/goodstream.php", true, "${originOf(embedUrl)}/")
            u.contains("files.im") -> Mawdhou3Endpoint("scrapefinal/filesim.php", true, "https://files.im/")
            u.contains("turboviplay.") || u.contains("emturbovid.") -> Mawdhou3Endpoint("scrapefinal/turboviplay.php", true, "${originOf(embedUrl)}/")
            u.contains("hexupload.") -> Mawdhou3Endpoint("scrapefinal/hexupload.php", true, "https://hexupload.net/")
            u.contains("streamruby.") || u.contains("rubystm.") -> Mawdhou3Endpoint("scrapefinal/streamruby.php", true, "${originOf(embedUrl)}/")
            u.contains("send.cm") -> Mawdhou3Endpoint("scrapefinal/sendcm.php", true, "https://send.cm/")
            u.contains("drop.download") -> Mawdhou3Endpoint("scrapefinal/dropdownload.php", true, "https://drop.download/")
            u.contains("govad.") -> Mawdhou3Endpoint("scrapefinal/govad.php", true, "${originOf(embedUrl)}/")
            u.contains("dood") || u.contains("d000") || u.contains("ds2play") -> Mawdhou3Endpoint("scrapefinal/doodstream.php", false, "${originOf(embedUrl)}/")
            else -> null
        }
    }

    private suspend fun extractViaMawdhou3Scraper(
        embedUrl: String,
        prefetchedHtml: String?,
        sourceName: String,
        fallbackReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val scraperBase = TimeMoviesPrefs.unlimitedScraperOverride?.trimEnd('/')?.plus("/")
            ?: if (TimeMoviesPrefs.apiBaseUrlOverride != null) return 0 else MAWDHOU3_SCRAPER_BASE
        val ep = resolveMawdhou3Endpoint(embedUrl) ?: return 0
        val endpointUrl = "$scraperBase${ep.scriptPath}"

        val responseJsonText: String? = if (ep.isPost) {
            val htmlContent = prefetchedHtml ?: httpGetText(embedUrl, referer = fallbackReferer, fast = true) ?: return 0
            runCatching {
                val form = FormBody.Builder()
                    .add("content", htmlContent)
                    .add("url", embedUrl)
                    .build()
                val req = Request.Builder()
                    .url(endpointUrl)
                    .post(form)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                fastProbeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.byteStream()?.use { readBoundedUtf8(it) }
                }
            }.getOrNull()
        } else {
            val encoded = URLEncoder.encode(embedUrl, "UTF-8")
            httpGetText("$endpointUrl?api=$encoded&url=$encoded", fast = true)
        }
        if (responseJsonText.isNullOrBlank()) return 0

        var count = 0
        val seen = HashSet<String>()
        val effRef = ep.defaultReferer ?: fallbackReferer

        runCatching {
            val trimmed = responseJsonText.trim()
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val single = root.optString("url", "")
                    .ifBlank { root.optString("file", "") }
                    .ifBlank { root.optString("direct_url", "") }
                    .ifBlank { root.optString("video", "") }
                    .trim()
                if (single.startsWith("http") && seen.add(single)) {
                    count += if (single.contains(".m3u8", true)) {
                        emitHlsStreamAndVariants(single, sourceName, effRef, subtitleCallback = subtitleCallback, callback = callback)
                    } else {
                        emitProgressiveDownloadVideo(single, sourceName, effRef, parseQualityInt(sourceName, single), callback = callback)
                    }
                }
                for (arrKey in listOf("sources", "links", "servers", "qualities", "data")) {
                    val arr = root.optJSONArray(arrKey) ?: continue
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i)
                        val u = (obj?.optString("url", "")?.ifBlank { obj.optString("file", "") } ?: arr.optString(i, "")).trim()
                        if (!u.startsWith("http") || !seen.add(u)) continue
                        val q = parseQualityInt(obj?.optString("quality", obj.optString("label", "")), u)
                        count += if (u.contains(".m3u8", true)) {
                            emitHlsStreamAndVariants(u, sourceName, effRef, forcedQuality = q, subtitleCallback = subtitleCallback, callback = callback)
                        } else {
                            emitProgressiveDownloadVideo(u, sourceName, effRef, q, callback = callback)
                        }
                    }
                }
            } else if (trimmed.startsWith("http")) {
                val u = trimmed.lineSequence().first().trim()
                if (seen.add(u)) {
                    count += if (u.contains(".m3u8", true)) {
                        emitHlsStreamAndVariants(u, sourceName, effRef, subtitleCallback = subtitleCallback, callback = callback)
                    } else {
                        emitProgressiveDownloadVideo(u, sourceName, effRef, parseQualityInt(sourceName, u), callback = callback)
                    }
                }
            }
        }
        return count
    }

    private fun unwrapEmbedWrapperUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.contains("strema.top/embed", ignoreCase = true) && trimmed.contains("id=")) {
            val idParam = trimmed.substringAfter("id=").substringBefore('&')
            val decoded = runCatching { URLDecoder.decode(idParam, "UTF-8") }.getOrDefault(idParam).trim()
            if (decoded.startsWith("http")) return decoded
        }
        return trimmed
    }

    private suspend fun dispatchSingleServer(
        embed: UnlimitedServerEmbed,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val url = unwrapEmbedWrapperUrl(embed.url)
        if (!url.startsWith("http")) return 0
        val host = url.lowercase()
        val sourceName = embed.name
        val customHeaders = LinkedHashMap<String, String>().apply {
            embed.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            embed.origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
            embed.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        val effectiveReferer = embed.referer?.takeIf { it.isNotBlank() } ?: "${originOf(url)}/"

        var count = when {
            host.endsWith(".mp4") || host.endsWith(".mkv") || Regex("""\.(?:mp4|mkv)\?""").containsMatchIn(host) ||
                host.contains("/dl/") && (host.contains(".mp4") || host.contains(".mkv")) -> {
                if (!probeDirectVideoAlive(url, customHeaders)) 0
                else emitProgressiveDownloadVideo(
                    mp4Url = url,
                    sourceName = sourceName,
                    referer = effectiveReferer,
                    quality = parseQualityInt(sourceName, url),
                    extraHeaders = customHeaders,
                    callback = callback,
                )
            }

            host.endsWith(".m3u8") || Regex("""\.m3u8\?""").containsMatchIn(host) ->
                emitHlsStreamAndVariants(
                    m3u8Url = url,
                    sourceName = sourceName,
                    referer = effectiveReferer,
                    extraHeaders = customHeaders,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )

            host.contains("photos.app.goo.gl") || host.contains("photos.google.com") ->
                extractGooglePhotos(url, sourceName, callback)

            host.contains("workers.dev") ->
                extractCloudWorker(url, sourceName, subtitleCallback, callback)

            host.contains("hyperwatching.") ->
                extractHyperWatching(url, sourceName, subtitleCallback, callback)

            host.contains("cavanhabg.") || host.contains("streamhg.") || host.contains("vibuxer.") || host.contains("hanerix.") ->
                extractStreamHgVibuxer(url, sourceName, subtitleCallback, callback)

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
            val beforeFallback = AtomicInteger(0)
            runCatching {
                loadExtractor(
                    url = url,
                    referer = effectiveReferer,
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
    // 4. Multi-Engine Zero-Quota Movie & Series Catalog Federation
    //    (`Egywatch`, `DramaWorld`, `StarDima`, `Cimawbas`, `TopCinema`, `CimaNow`)
    // =========================================================================

    private fun parsePipeHeaderMap(rawHeader: String?): Pair<String?, String?> {
        if (rawHeader.isNullOrBlank()) return null to null
        var referer: String? = null
        var origin: String? = null
        for (segment in rawHeader.split('|', '\n', ';')) {
            val idx = segment.indexOf(':')
            if (idx <= 0) continue
            val k = segment.substring(0, idx).trim().lowercase()
            val v = segment.substring(idx + 1).trim()
            if (v.isEmpty()) continue
            when (k) {
                "referer", "referrer" -> referer = v
                "origin" -> origin = v
            }
        }
        return referer to origin
    }

    private fun egywatchGetJson(base: String, path: String): JSONObject? = runCatching {
        val cleanBase = base.trimEnd('/') + "/"
        val req = Request.Builder()
            .url(cleanBase + path.trimStart('/'))
            .get()
            .header("User-Agent", EGYWATCH_APP_UA)
            .header("Accept", "application/json")
            .header("packagename", EGYWATCH_PACKAGE)
            .build()
        fastProbeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.byteStream()?.use { readBoundedUtf8(it) }.orEmpty().trim()
            if (!body.startsWith("{")) null else JSONObject(body)
        }
    }.getOrNull()

    internal fun resolveEgywatchEmbeds(payload: TimeMoviesModels.LinkPayload): List<UnlimitedServerEmbed> {
        val override = TimeMoviesPrefs.unlimitedEgywatchApiOverride?.trim()
        if (TimeMoviesPrefs.apiBaseUrlOverride != null && override.isNullOrBlank()) return emptyList()
        val bases = if (!override.isNullOrBlank()) listOf(override) else EGYWATCH_BASES

        val queries = buildSearchQueries(payload).take(6)
        val targetNorms = buildPrimaryTargetNorms(payload)
        if (queries.isEmpty() || targetNorms.isEmpty()) return emptyList()

        data class EgyCand(val id: Int, val rawType: String, val score: Int, val base: String)

        val candidates = LinkedHashMap<Int, EgyCand>()
        for (base in bases) {
            var baseReachable = false
            for (q in queries) {
                val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
                val json = egywatchGetJson(base, "search/$enc/$EGYWATCH_CODE") ?: continue
                baseReachable = true
                val arr = json.optJSONArray("search")
                    ?: json.optJSONArray("data")
                    ?: json.optJSONArray("results")
                    ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    // Normalize Egywatch `type` (`"movie"`, `"serie"`, `"anime"`) for `scoreCatalogItem`
                    val itemCopy = JSONObject(obj.toString())
                    val ewType = obj.optString("type", "").lowercase()
                    if (ewType == "anime" && payload.isMovie) {
                        // In EasyPlex, anime movies have no `seasons` array on `animes/show` or appear as movie
                        if (!obj.has("seasons") && obj.optInt("seasons_count", 0) == 0) {
                            itemCopy.put("type", "movie")
                        }
                    }
                    val scored = scoreCatalogItem(itemCopy, payload, targetNorms) ?: continue
                    val prev = candidates[scored.id]
                    if (prev == null || scored.score > prev.score) {
                        candidates[scored.id] = EgyCand(scored.id, ewType, scored.score, base)
                    }
                }
                if (candidates.values.any { it.score >= 115 }) break
            }
            if (candidates.isNotEmpty() || baseReachable) break
        }
        if (candidates.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, UnlimitedServerEmbed>()
        fun collectVideosArray(videos: JSONArray?) {
            if (videos == null) return
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val link = v.optString("link", "").ifBlank { v.optString("url", "") }.trim()
                if (!link.startsWith("http")) continue
                val srvLabel = v.optString("server", "").ifBlank { v.optString("lang", "") }
                    .ifBlank { extractHostLabel(link, i + 1) }.trim()
                val ua = v.optString("useragent", "").trim().takeIf { it.isNotEmpty() }
                val (hdrRef, hdrOrigin) = parsePipeHeaderMap(v.optString("header", ""))
                val finalRef = hdrRef ?: v.optString("referer", "").trim().takeIf { it.isNotEmpty() }
                out.putIfAbsent(
                    link,
                    UnlimitedServerEmbed(
                        name = "TimeMovies ♾️ EgyWatch - $srvLabel",
                        url = link,
                        sourceBackend = "TimeMovies ♾️ EgyWatch",
                        referer = finalRef,
                        origin = hdrOrigin,
                        userAgent = ua,
                    ),
                )
            }
        }

        val ranked = candidates.values.sortedByDescending { it.score }
        if (payload.isMovie) {
            for (cand in ranked.take(3)) {
                val detail = egywatchGetJson(cand.base, "media/detail/${cand.id}/$EGYWATCH_CODE")
                    ?: egywatchGetJson(cand.base, "animes/show/${cand.id}/$EGYWATCH_CODE")
                    ?: continue
                collectVideosArray(detail.optJSONArray("videos"))
                if (out.size >= 6) break
            }
        } else {
            val targetSeason = payload.seasonNum ?: 1
            val targetEpisode = payload.episodeNum ?: 1
            for (cand in ranked.take(2)) {
                val showJson = egywatchGetJson(cand.base, "series/show/${cand.id}/$EGYWATCH_CODE")
                    ?: egywatchGetJson(cand.base, "animes/show/${cand.id}/$EGYWATCH_CODE")
                    ?: continue
                val seasons = showJson.optJSONArray("seasons") ?: continue
                var matchedSeasonObj: JSONObject? = null
                for (sIdx in 0 until seasons.length()) {
                    val sObj = seasons.optJSONObject(sIdx) ?: continue
                    val sNum = sObj.optInt("season_number", 0).takeIf { it > 0 }
                        ?: extractTrailingEpisodeInt(sObj.optString("name", ""))
                        ?: (sIdx + 1)
                    if (sNum == targetSeason || (seasons.length() == 1 && targetSeason == 1)) {
                        matchedSeasonObj = sObj
                        break
                    }
                }
                val sObj = matchedSeasonObj ?: continue
                val inlineEps = sObj.optJSONArray("episodes")
                val epsArr = if (inlineEps != null && inlineEps.length() > 0) {
                    inlineEps
                } else {
                    val sId = sObj.optInt("id", 0)
                    if (sId > 0) egywatchGetJson(cand.base, "series/season/$sId/$EGYWATCH_CODE")?.optJSONArray("episodes") else null
                } ?: continue
                for (eIdx in 0 until epsArr.length()) {
                    val epObj = epsArr.optJSONObject(eIdx) ?: continue
                    val epNum = epObj.optInt("episode_number", 0).takeIf { it > 0 }
                        ?: extractTrailingEpisodeInt(epObj.optString("name", ""))
                        ?: (eIdx + 1)
                    if (epNum == targetEpisode) {
                        collectVideosArray(epObj.optJSONArray("videos"))
                        break
                    }
                }
                if (out.isNotEmpty()) break
            }
        }
        return out.values.toList()
    }

    private fun dramaWorldGetRaw(base: String, path: String): String? = runCatching {
        val cleanBase = base.trimEnd('/')
        val req = Request.Builder()
            .url("$cleanBase/${path.trimStart('/')}")
            .get()
            .header("User-Agent", DRAMAWORLD_UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        fastProbeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.byteStream()?.use { readBoundedUtf8(it) }?.trim()
        }
    }.getOrNull()

    private fun decodeDramaWorldJsonArray(raw: String?): JSONArray? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().trim('"')
        if (trimmed.startsWith("[")) return runCatching { JSONArray(trimmed) }.getOrNull()
        if (trimmed.startsWith("{")) return runCatching {
            val obj = JSONObject(trimmed)
            obj.optJSONArray("sources") ?: obj.optJSONArray("posters") ?: obj.optJSONArray("data")
        }.getOrNull()
        // ArabyPros `movie/source/by` & `episode/source/by` prepend a token before the Base64 of `[{` ("W3s...")
        return runCatching {
            val w3sIdx = trimmed.indexOf("W3s")
            val b64Slice = if (w3sIdx >= 0) trimmed.substring(w3sIdx) else trimmed
            val decoded = String(TimeMoviesCrypto.base64DecodeFlexible(b64Slice), StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("[")) JSONArray(decoded) else null
        }.getOrNull()
    }

    internal suspend fun resolveDramaWorldEmbedsAndSubtitles(
        payload: TimeMoviesModels.LinkPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<UnlimitedServerEmbed> {
        val override = TimeMoviesPrefs.unlimitedDramaWorldApiOverride?.trim()
        if (TimeMoviesPrefs.apiBaseUrlOverride != null && override.isNullOrBlank()) return emptyList()
        val bases = when {
            !override.isNullOrBlank() -> listOf(override)
            resolvedDramaWorldBase != null -> (listOf(resolvedDramaWorldBase!!) + DRAMAWORLD_BASES).distinct()
            else -> DRAMAWORLD_BASES
        }

        val queries = buildSearchQueries(payload).take(6)
        val targetNorms = buildPrimaryTargetNorms(payload)
        if (queries.isEmpty() || targetNorms.isEmpty()) return emptyList()

        data class DwCand(val id: Int, val score: Int, val base: String)

        val candidates = LinkedHashMap<Int, DwCand>()
        for (base in bases) {
            var baseOk = false
            for (q in queries) {
                val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
                val raw = dramaWorldGetRaw(base, "search/$enc/0/$DRAMAWORLD_KEY/$DRAMAWORLD_UUID/") ?: continue
                baseOk = true
                if (override.isNullOrBlank()) resolvedDramaWorldBase = base
                val posters = runCatching {
                    val t = raw.trim()
                    if (t.startsWith("{")) JSONObject(t).optJSONArray("posters")
                    else if (t.startsWith("[")) JSONArray(t)
                    else null
                }.getOrNull() ?: continue

                for (i in 0 until posters.length()) {
                    val p = posters.optJSONObject(i) ?: continue
                    val pCopy = JSONObject(p.toString())
                    // ArabyPros uses `classification` / `label` / `sublabel` in addition to `title` and `type`
                    val pType = p.optString("type", "").lowercase()
                    if (pType.isBlank()) {
                        pCopy.put("type", if (payload.isMovie) "movie" else "serie")
                    }
                    val scored = scoreCatalogItem(pCopy, payload, targetNorms) ?: continue
                    val prev = candidates[scored.id]
                    if (prev == null || scored.score > prev.score) {
                        candidates[scored.id] = DwCand(scored.id, scored.score, base)
                    }
                }
                if (candidates.values.any { it.score >= 115 }) break
            }
            if (candidates.isNotEmpty() || baseOk) break
        }
        if (candidates.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, UnlimitedServerEmbed>()
        suspend fun collectDramaWorldSubtitles(base: String, subPath: String) {
            val subRaw = dramaWorldGetRaw(base, subPath) ?: return
            val langArr = decodeDramaWorldJsonArray(subRaw) ?: return
            for (i in 0 until langArr.length()) {
                val langObj = langArr.optJSONObject(i) ?: continue
                val langLabel = langObj.optString("language", "Arabic").ifBlank { "Arabic" }
                val subList = langObj.optJSONArray("subtitles")
                if (subList != null) {
                    for (j in 0 until subList.length()) {
                        val s = subList.optJSONObject(j) ?: continue
                        val u = s.optString("url", "").ifBlank { s.optString("file", "") }.trim()
                        if (u.startsWith("http")) subtitleCallback(newSubtitleFile(langLabel, u))
                    }
                } else {
                    val directSub = langObj.optString("url", "").trim()
                    if (directSub.startsWith("http")) subtitleCallback(newSubtitleFile(langLabel, directSub))
                }
            }
        }

        fun collectSourcesArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val u = s.optString("url", "").ifBlank { s.optString("link", "") }.trim()
                if (!u.startsWith("http")) continue
                val lbl = s.optString("title", "").ifBlank { s.optString("quality", "") }
                    .ifBlank { extractHostLabel(u, i + 1) }.trim()
                out.putIfAbsent(
                    u,
                    UnlimitedServerEmbed(
                        name = "TimeMovies ♾️ DramaWorld - $lbl",
                        url = u,
                        sourceBackend = "TimeMovies ♾️ DramaWorld",
                    ),
                )
            }
        }

        val ranked = candidates.values.sortedByDescending { it.score }
        if (payload.isMovie) {
            for (cand in ranked.take(2)) {
                val srcRaw = dramaWorldGetRaw(cand.base, "movie/source/by/${cand.id}/$DRAMAWORLD_KEY/$DRAMAWORLD_UUID/")
                val srcArr = decodeDramaWorldJsonArray(srcRaw)
                if (srcArr != null && srcArr.length() > 0) {
                    collectSourcesArray(srcArr)
                    runCatching {
                        collectDramaWorldSubtitles(cand.base, "subtitles/by/movie/${cand.id}/$DRAMAWORLD_KEY/$DRAMAWORLD_UUID/")
                    }
                }
                if (out.size >= 5) break
            }
        } else {
            val targetSeason = payload.seasonNum ?: 1
            val targetEpisode = payload.episodeNum ?: 1
            for (cand in ranked.take(2)) {
                val seasonsRaw = dramaWorldGetRaw(cand.base, "season/by/serie/${cand.id}/$DRAMAWORLD_KEY/$DRAMAWORLD_UUID/")
                val seasonsArr = decodeDramaWorldJsonArray(seasonsRaw) ?: continue
                var matchedEpId = 0
                for (sIdx in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.optJSONObject(sIdx) ?: continue
                    val sNum = extractTrailingEpisodeInt(sObj.optString("title", "")) ?: (sIdx + 1)
                    if (sNum == targetSeason || (seasonsArr.length() == 1 && targetSeason == 1)) {
                        val eps = sObj.optJSONArray("episodes") ?: continue
                        for (eIdx in 0 until eps.length()) {
                            val epObj = eps.optJSONObject(eIdx) ?: continue
                            val epNum = extractTrailingEpisodeInt(epObj.optString("title", "")) ?: (eIdx + 1)
                            if (epNum == targetEpisode) {
                                matchedEpId = epObj.optInt("id", 0)
                                break
                            }
                        }
                        if (matchedEpId > 0) break
                    }
                }
                if (matchedEpId > 0) {
                    val epSrcRaw = dramaWorldGetRaw(cand.base, "episode/source/by/$matchedEpId/$DRAMAWORLD_KEY/$DRAMAWORLD_UUID/")
                    collectSourcesArray(decodeDramaWorldJsonArray(epSrcRaw))
                    runCatching {
                        collectDramaWorldSubtitles(cand.base, "subtitles/by/episode/$matchedEpId/$DRAMAWORLD_KEY/$DRAMAWORLD_UUID/")
                    }
                    if (out.isNotEmpty()) break
                }
            }
        }
        return out.values.toList()
    }

    private fun starDimaGetJson(base: String, path: String): JSONObject? = runCatching {
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/${path.trimStart('/')}")
            .get()
            .header("User-Agent", STARDIMA_UA)
            .header("Accept", "application/json")
            .header("x-api-key", STARDIMA_API_KEY)
            .header("X-Secure-Token", STARDIMA_SECURE_TOKEN)
            .build()
        fastProbeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.byteStream()?.use { readBoundedUtf8(it) }.orEmpty().trim()
            if (!body.startsWith("{")) null else JSONObject(body)
        }
    }.getOrNull()

    private fun resolveStarDimaActiveBase(): String? {
        resolvedStarDimaBase?.let { return it }
        for (seed in STARDIMA_BASES) {
            val info = starDimaGetJson(seed, "api/app/info") ?: continue
            val newDomain = info.optJSONObject("data")?.optString("new_domain", "")
                ?.ifBlank { info.optString("new_domain", "") }
                .orEmpty().trim().trimEnd('/')
            val active = if (newDomain.startsWith("http")) newDomain else seed
            resolvedStarDimaBase = active
            return active
        }
        return null
    }

    internal fun resolveStarDimaEmbeds(payload: TimeMoviesModels.LinkPayload): List<UnlimitedServerEmbed> {
        if (TimeMoviesPrefs.apiBaseUrlOverride != null) return emptyList()
        val base = resolveStarDimaActiveBase() ?: return emptyList()
        val queries = buildSearchQueries(payload).take(4)
        val targetNorms = buildPrimaryTargetNorms(payload)
        if (queries.isEmpty() || targetNorms.isEmpty()) return emptyList()

        val candidates = LinkedHashMap<String, Pair<String, Double>>()
        for (q in queries) {
            val enc = URLEncoder.encode(q, "UTF-8")
            val json = starDimaGetJson(base, "api/search?q=$enc&query=$enc&page=1") ?: continue
            val dataObj = json.optJSONObject("data")
            val arrays = listOfNotNull(
                json.optJSONArray("data"),
                dataObj?.optJSONArray("videos"),
                dataObj?.optJSONArray("movies"),
                dataObj?.optJSONArray("series"),
                dataObj?.optJSONArray("results"),
                json.optJSONArray("results"),
            )
            for (arr in arrays) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val slugOrId = item.optString("slug", "").ifBlank { item.optString("id", "") }.trim()
                    if (slugOrId.isEmpty()) continue
                    val names = listOfNotNull(
                        item.optString("title", "").takeIf { it.isNotBlank() },
                        item.optString("name", "").takeIf { it.isNotBlank() },
                        item.optString("english_title", "").takeIf { it.isNotBlank() },
                    )
                    val views = names.flatMap { extractTitleNormViews(it, stripSeasonSuffix = !payload.isMovie) }
                    var sim = 0.0
                    for (v in views) {
                        for (t in targetNorms) sim = maxOf(sim, computeTitleSimilarity(t, v))
                    }
                    if (sim >= 0.62 && !areSequelNumbersIncompatible(targetNorms, views)) {
                        val prev = candidates[slugOrId]
                        if (prev == null || sim > prev.second) {
                            candidates[slugOrId] = slugOrId to sim
                        }
                    }
                }
            }
            if (candidates.values.any { it.second >= 0.98 }) break
        }
        if (candidates.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, UnlimitedServerEmbed>()
        for ((vidId, _) in candidates.values.sortedByDescending { it.second }.take(2)) {
            val detail = starDimaGetJson(base, "api/video/$vidId") ?: continue
            val rootData = detail.optJSONObject("data") ?: detail
            val servers = rootData.optJSONArray("servers") ?: rootData.optJSONObject("video")?.optJSONArray("servers")
            if (servers != null) {
                for (i in 0 until servers.length()) {
                    val s = servers.optJSONObject(i) ?: continue
                    val rawUrl = s.optString("url", "").ifBlank { s.optString("embed_url", "") }.ifBlank { s.optString("link", "") }.trim()
                    val unwrapped = unwrapEmbedWrapperUrl(rawUrl)
                    if (!unwrapped.startsWith("http")) continue
                    val name = s.optString("name", "").ifBlank { extractHostLabel(unwrapped, i + 1) }.trim()
                    out.putIfAbsent(
                        unwrapped,
                        UnlimitedServerEmbed(
                            name = "TimeMovies ♾️ StarDima - $name",
                            url = unwrapped,
                            sourceBackend = "TimeMovies ♾️ StarDima",
                        ),
                    )
                }
            }
            val backups = rootData.optJSONArray("backup") ?: rootData.optJSONArray("downloads")
            if (backups != null) {
                for (i in 0 until backups.length()) {
                    val b = backups.optJSONObject(i) ?: continue
                    val bUrl = b.optString("url", "").ifBlank { b.optString("link", "") }.trim()
                    if (!bUrl.startsWith("http")) continue
                    val qLbl = b.optString("quality", "720p")
                    out.putIfAbsent(
                        bUrl,
                        UnlimitedServerEmbed(
                            name = "TimeMovies ♾️ StarDima Direct ($qLbl)",
                            url = bUrl,
                            sourceBackend = "TimeMovies ♾️ StarDima",
                            referer = "$base/",
                        ),
                    )
                }
            }
            if (out.isNotEmpty()) break
        }
        return out.values.toList()
    }

    private fun resolveCimawbasMovieEmbeds(payload: TimeMoviesModels.LinkPayload): List<UnlimitedServerEmbed> {
        if (!payload.isMovie || TimeMoviesPrefs.apiBaseUrlOverride != null) return emptyList()
        val now = System.currentTimeMillis()
        if (now < cimawbasBlockedUntilMs) return emptyList()

        val targetNorms = buildPrimaryTargetNorms(payload)
        val queries = buildSearchQueries(payload).take(5)
        if (targetNorms.isEmpty() || queries.isEmpty()) return emptyList()

        var bestVid: String? = null
        var bestSim = 0.0
        for (q in queries) {
            val searchUrl = "$CIMAWBAS_BASE_URL/search.php?keywords=${URLEncoder.encode(q, "UTF-8")}"
            val html = runCatching {
                val req = Request.Builder()
                    .url(searchUrl)
                    .get()
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html")
                    .header("Referer", "$CIMAWBAS_BASE_URL/")
                    .build()
                fastProbeClient.newCall(req).execute().use { resp ->
                    if (resp.code == 403 || resp.code == 503) {
                        cimawbasBlockedUntilMs = now + 20L * 60L * 1000L
                        return@use null
                    }
                    if (!resp.isSuccessful) null else resp.body?.byteStream()?.use { readBoundedUtf8(it) }
                }
            }.getOrNull() ?: continue

            val linkRegex = Regex("""<a[^>]+href=["']([^"']*watch\.php\?vid=([A-Za-z0-9]+)[^"']*)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            for (m in linkRegex.findAll(html)) {
                val vid = m.groupValues[2].trim()
                val rawAnchor = m.groupValues[3].replace(Regex("<[^>]+>"), " ").trim()
                val titleAttr = Regex("""title=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.getOrNull(1).orEmpty()
                val label = titleAttr.ifBlank { rawAnchor }
                if (label.contains("مسلسل") || label.contains("حلقة")) continue
                val views = extractTitleNormViews(label, stripSeasonSuffix = false)
                if (areSequelNumbersIncompatible(targetNorms, views)) continue
                val sim = views.maxOfOrNull { c -> targetNorms.maxOfOrNull { t -> computeTitleSimilarity(t, c) } ?: 0.0 } ?: 0.0
                if (sim > bestSim && sim >= 0.65) {
                    bestSim = sim
                    bestVid = vid
                }
            }
            if (bestSim >= 0.95) break
        }

        val vid = bestVid ?: return emptyList()
        val out = LinkedHashMap<String, UnlimitedServerEmbed>()

        // Direct fetch to `play.php?vid=...` + `downloads.php?vid=...`
        val playHtml = httpGetText("$CIMAWBAS_BASE_URL/play.php?vid=$vid", referer = "$CIMAWBAS_BASE_URL/watch.php?vid=$vid", fast = true)
        if (!playHtml.isNullOrBlank()) {
            for (m in Regex("""(?:data-embed|data-url|src)=["'](https?://[^"'\s<>]+)["']""", RegexOption.IGNORE_CASE).findAll(playHtml)) {
                val u = m.groupValues[1].trim()
                if (u.contains("mycima.cc", true) || u.endsWith(".js", true) || u.endsWith(".css", true) || u.endsWith(".png", true)) continue
                out.putIfAbsent(
                    u,
                    UnlimitedServerEmbed(
                        name = "TimeMovies ♾️ CimaWbas - ${extractHostLabel(u, out.size + 1)}",
                        url = u,
                        sourceBackend = "TimeMovies ♾️ CimaWbas",
                        referer = "$CIMAWBAS_BASE_URL/",
                    ),
                )
            }
        }
        val dlHtml = httpGetText("$CIMAWBAS_BASE_URL/downloads.php?vid=$vid", referer = "$CIMAWBAS_BASE_URL/watch.php?vid=$vid", fast = true)
        if (!dlHtml.isNullOrBlank()) {
            for (m in Regex("""href=["'](https?://[^"'\s<>]+)["']""", RegexOption.IGNORE_CASE).findAll(dlHtml)) {
                val u = m.groupValues[1].trim()
                if (u.contains("mycima.cc", true) || u.contains("facebook.", true) || u.contains("twitter.", true) || u.contains("t.me/", true)) continue
                out.putIfAbsent(
                    u,
                    UnlimitedServerEmbed(
                        name = "TimeMovies ♾️ CimaWbas DL - ${extractHostLabel(u, out.size + 1)}",
                        url = u,
                        sourceBackend = "TimeMovies ♾️ CimaWbas",
                        referer = "$CIMAWBAS_BASE_URL/",
                    ),
                )
                if (out.size >= 10) break
            }
        }
        return out.values.toList()
    }

    private fun resolveTopCinemaMovieEmbeds(payload: TimeMoviesModels.LinkPayload): List<UnlimitedServerEmbed> {
        if (!payload.isMovie || TimeMoviesPrefs.apiBaseUrlOverride != null) return emptyList()
        val now = System.currentTimeMillis()
        if (now < topCinemaBlockedUntilMs) return emptyList()

        val targetNorms = buildPrimaryTargetNorms(payload)
        val queries = buildSearchQueries(payload).take(5)
        if (targetNorms.isEmpty() || queries.isEmpty()) return emptyList()

        var matchedUrl: String? = null
        var bestSim = 0.0
        for (q in queries) {
            val html = runCatching {
                val form = FormBody.Builder()
                    .add("search", q)
                    .add("type", "all")
                    .build()
                val req = Request.Builder()
                    .url("$TOPCINEMA_BASE_URL/wp-content/themes/movies2023/Ajaxat/Searching.php")
                    .post(form)
                    .header("User-Agent", BROWSER_UA)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "$TOPCINEMA_BASE_URL/")
                    .header("Origin", TOPCINEMA_BASE_URL)
                    .build()
                fastProbeClient.newCall(req).execute().use { resp ->
                    if (resp.code == 403 || resp.code == 503) {
                        topCinemaBlockedUntilMs = now + 20L * 60L * 1000L
                        return@use null
                    }
                    if (!resp.isSuccessful) null else resp.body?.byteStream()?.use { readBoundedUtf8(it) }
                }
            }.getOrNull() ?: continue

            for (m in Regex("""<a[^>]+href=["'](https?://[^"']+)["'][^>]*title=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                val href = m.groupValues[1].trim()
                val title = m.groupValues[2].trim()
                if (title.contains("مسلسل") || title.contains("حلقة") || href.contains("/series/", true)) continue
                val views = extractTitleNormViews(title, stripSeasonSuffix = false)
                if (areSequelNumbersIncompatible(targetNorms, views)) continue
                val sim = views.maxOfOrNull { c -> targetNorms.maxOfOrNull { t -> computeTitleSimilarity(t, c) } ?: 0.0 } ?: 0.0
                if (sim > bestSim && sim >= 0.65) {
                    bestSim = sim
                    matchedUrl = href
                }
            }
            if (bestSim >= 0.95) break
        }

        val movieUrl = matchedUrl ?: return emptyList()
        val out = LinkedHashMap<String, UnlimitedServerEmbed>()
        val watchPageUrl = "${movieUrl.trimEnd('/')}/watch/"
        val watchHtml = httpGetText(watchPageUrl, referer = movieUrl, fast = true)
        if (!watchHtml.isNullOrBlank()) {
            // Initial iframe + AJAX `Single/Server.php` `data-id` & `data-server`
            Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).find(watchHtml)?.groupValues?.getOrNull(1)?.let { ifr ->
                out.putIfAbsent(
                    ifr,
                    UnlimitedServerEmbed(
                        name = "TimeMovies ♾️ TopCinema - ${extractHostLabel(ifr, 1)}",
                        url = ifr,
                        sourceBackend = "TimeMovies ♾️ TopCinema",
                        referer = "$TOPCINEMA_BASE_URL/",
                    ),
                )
            }
            for (srvMatch in Regex("""data-id=["'](\d+)["'][^>]+data-server=["'](\d+)["']""", RegexOption.IGNORE_CASE).findAll(watchHtml).take(5)) {
                val postId = srvMatch.groupValues[1]
                val srvIdx = srvMatch.groupValues[2]
                val ajaxHtml = runCatching {
                    val form = FormBody.Builder().add("id", postId).add("i", srvIdx).build()
                    val req = Request.Builder()
                        .url("$TOPCINEMA_BASE_URL/wp-content/themes/movies2023/Ajaxat/Single/Server.php")
                        .post(form)
                        .header("User-Agent", BROWSER_UA)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", watchPageUrl)
                        .header("Origin", TOPCINEMA_BASE_URL)
                        .build()
                    fastProbeClient.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.byteStream()?.use { readBoundedUtf8(it) }
                    }
                }.getOrNull() ?: continue
                Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).find(ajaxHtml)?.groupValues?.getOrNull(1)?.trim()?.let { ifr ->
                    out.putIfAbsent(
                        ifr,
                        UnlimitedServerEmbed(
                            name = "TimeMovies ♾️ TopCinema - ${extractHostLabel(ifr, out.size + 1)}",
                            url = ifr,
                            sourceBackend = "TimeMovies ♾️ TopCinema",
                            referer = "$TOPCINEMA_BASE_URL/",
                        ),
                    )
                }
            }
        }
        return out.values.toList()
    }

    private fun resolveShahid4uMovieEmbeds(payload: TimeMoviesModels.LinkPayload): List<UnlimitedServerEmbed> {
        if (!payload.isMovie || TimeMoviesPrefs.apiBaseUrlOverride != null) return emptyList()
        val targetNorms = buildPrimaryTargetNorms(payload)
        val prefixNorms = buildPrefixTargetNorms(payload, targetNorms)
        val queries = buildSearchQueries(payload).take(6)
        if (targetNorms.isEmpty() || queries.isEmpty()) return emptyList()

        val cookies = LinkedHashMap<String, String>()
        fun absorbSetCookies(headers: okhttp3.Headers) {
            for (sc in headers.values("Set-Cookie")) {
                val pair = sc.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
        }
        fun cookieHeaderValue(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        fun getPageSticky(url: String, referer: String? = null): Pair<String, String>? = runCatching {
            val rb = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) rb.header("Referer", referer)
            if (cookies.isNotEmpty()) rb.header("Cookie", cookieHeaderValue())
            fastProbeClient.newCall(rb.build()).execute().use { resp ->
                absorbSetCookies(resp.headers)
                if (!resp.isSuccessful) return null
                val body = resp.body?.byteStream()?.use { readBoundedUtf8(it) } ?: return null
                body to resp.request.url.toString()
            }
        }.getOrNull()

        val noRedir = fastProbeClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        fun resolveMRedirect(mUrl: String, referer: String): String? = runCatching {
            var cur = mUrl
            for (hop in 0 until 4) {
                val rb = Request.Builder()
                    .url(cur)
                    .get()
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", referer)
                if (cookies.isNotEmpty()) rb.header("Cookie", cookieHeaderValue())
                val nextLoc = noRedir.newCall(rb.build()).execute().use { resp ->
                    absorbSetCookies(resp.headers)
                    if (resp.code in 300..399) {
                        resp.header("Location")?.let { resolveRelativeUrl(cur, it) }
                    } else null
                } ?: break
                cur = nextLoc
                if (!cur.contains("shaahid", true) && !cur.contains("shaaheid", true) && !cur.contains("shahid4u", true)) {
                    return cur
                }
            }
            if (!cur.contains("shaahid", true) && !cur.contains("shaaheid", true) && !cur.contains("shahid4u", true)) cur else null
        }.getOrNull()

        var activeOrigin = SHAHID4U_SEED_URL
        var matchedFilmUrl: String? = null
        var bestSim = 0.0

        for (q in queries) {
            val enc = URLEncoder.encode(q, "UTF-8")
            val (html, finalUrl) = getPageSticky("$activeOrigin/search?s=$enc", "$activeOrigin/") ?: continue
            activeOrigin = originOf(finalUrl)

            val cardRegex = Regex("""<a[^>]+class=["'][^"']*show-card[^"']*["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            for (m in cardRegex.findAll(html)) {
                val fullTag = m.value
                if (Regex("""(?:display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0|aria-hidden\s*=\s*["']true["'])""", RegexOption.IGNORE_CASE).containsMatchIn(fullTag)) {
                    continue
                }
                val rawHref = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(fullTag)?.groupValues?.getOrNull(1) ?: continue
                val absUrl = resolveRelativeUrl(finalUrl, rawHref)
                val decUrl = runCatching { URLDecoder.decode(absUrl, "UTF-8") }.getOrDefault(absUrl)
                if (decUrl.contains("/episode/", true) || decUrl.contains("/series/", true) || decUrl.contains("/season/", true) || decUrl.contains("/m/")) {
                    continue
                }
                val titleText = Regex("""class=["'][^"']*title[^"']*["'][^>]*>([\s\S]*?)</""", RegexOption.IGNORE_CASE)
                    .find(m.groupValues[1])?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), " ")?.trim()
                    ?: decUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ')
                if (titleText.contains("مسلسل") || titleText.contains("حلقة")) continue

                val views = extractTitleNormViews(titleText, stripSeasonSuffix = false)
                if (areSequelNumbersIncompatible(targetNorms, views)) continue
                var sim = views.maxOfOrNull { c -> targetNorms.maxOfOrNull { t -> computeTitleSimilarity(t, c) } ?: 0.0 } ?: 0.0
                if (sim < 0.72 && prefixNorms.isNotEmpty()) {
                    val pSim = views.maxOfOrNull { c -> prefixNorms.maxOfOrNull { p -> computeTitleSimilarity(p, c) } ?: 0.0 } ?: 0.0
                    if (pSim >= 0.92) sim = maxOf(sim, pSim * 0.88)
                }
                if (sim > bestSim && sim >= 0.65) {
                    bestSim = sim
                    matchedFilmUrl = absUrl
                }
            }
            if (bestSim >= 0.92) break
        }

        val filmUrl = matchedFilmUrl ?: return emptyList()
        val dlUrl = when {
            filmUrl.contains("/film/") -> filmUrl.replaceFirst("/film/", "/download/")
            filmUrl.contains("/post/") -> filmUrl.replaceFirst("/post/", "/download/")
            filmUrl.contains("/movie/") -> filmUrl.replaceFirst("/movie/", "/download/")
            else -> return emptyList()
        }

        val (dlHtml, dlFinalUrl) = getPageSticky(dlUrl, filmUrl) ?: return emptyList()
        val out = LinkedHashMap<String, UnlimitedServerEmbed>()
        val btnRegex = Regex("""<a[^>]+class=["'][^"']*btn-down[^"']*["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (bm in btnRegex.findAll(dlHtml)) {
            val tag = bm.value
            if (Regex("""(?:display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0)""", RegexOption.IGNORE_CASE).containsMatchIn(tag)) continue
            val mHref = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val textInside = bm.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (textInside.contains("Server 1", ignoreCase = true)) continue

            val absM = resolveRelativeUrl(dlFinalUrl, mHref)
            val extUrl = resolveMRedirect(absM, dlFinalUrl) ?: continue
            if (!extUrl.startsWith("http")) continue
            out.putIfAbsent(
                extUrl,
                UnlimitedServerEmbed(
                    name = "TimeMovies ♾️ Shahid4u - ${extractHostLabel(extUrl, out.size + 1)}",
                    url = extUrl,
                    sourceBackend = "TimeMovies ♾️ Shahid4u",
                    referer = dlFinalUrl,
                ),
            )
            if (out.size >= 6) break
        }
        return out.values.toList()
    }

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
                resp.body?.byteStream()?.use { readBoundedUtf8(it) }
            }
        }.getOrNull() ?: return 0

        val decodedHtml = decodeCimaNowObfuscatedHtmlIfNeeded(html)
        val targetNorms = buildPrimaryTargetNorms(payload)
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
            val candViews = extractTitleNormViews(titleText, stripSeasonSuffix = !payload.isMovie)
            if (areSequelNumbersIncompatible(targetNorms, candViews)) continue
            val sim = candViews.maxOfOrNull { c -> targetNorms.maxOfOrNull { t -> computeTitleSimilarity(t, c) } ?: 0.0 } ?: 0.0
            if (sim > bestSim && sim >= 0.62) {
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
     * subtitles at **zero quota cost** across the 6-engine catalog federation (`CimaCloud v1.3`,
     * `AnimeDay v3.8`, `Egywatch`, `DramaWorld`, `StarDima`, and `Cimawbas`/`TopCinema`/`CimaNow`).
     */
    suspend fun extractUnlimitedLinks(
        payload: TimeMoviesModels.LinkPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        if (!isRuntimeActive()) return 0

        val totalLinks = AtomicInteger(0)
        val hasDirectMp4 = AtomicBoolean(false)
        val emittedLinkUrls = ConcurrentHashMap.newKeySet<String>()
        val emittedSubUrls = ConcurrentHashMap.newKeySet<String>()

        val dedupLinkCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedLinkUrls.add(link.url)) {
                if (link.type == ExtractorLinkType.VIDEO) {
                    hasDirectMp4.set(true)
                }
                totalLinks.incrementAndGet()
                callback(link)
            }
        }
        val dedupSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (emittedSubUrls.add(sub.url)) {
                subtitleCallback(sub)
            }
        }

        val dispatchedEmbedUrls = ConcurrentHashMap.newKeySet<String>()
        val semaphore = Semaphore(8)

        suspend fun dispatchBatch(embeds: List<UnlimitedServerEmbed>, perServerTimeoutMs: Long = 13_000L) {
            val fresh = embeds.filter { dispatchedEmbedUrls.add(unwrapEmbedWrapperUrl(it.url)) }
            if (fresh.isEmpty()) return
            supervisorScope {
                fresh.map { embed ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            withTimeoutOrNull(perServerTimeoutMs) {
                                runCatching {
                                    dispatchSingleServer(embed, dedupSubtitleCallback, dedupLinkCallback)
                                }.getOrDefault(0)
                            } ?: 0
                        }
                    }
                }.awaitAll()
            }
        }

        withContext(Dispatchers.IO) {
            // Stage 1: Primary encrypted Cloud-Day catalogs (`CimaCloud` + `AnimeDay`) + `StarDima` for Animation/Cartoon/Anime
            supervisorScope {
                val primaryDef = async(Dispatchers.IO) {
                    runCatching { resolveUnlimitedEmbeds(payload) }.getOrDefault(emptyList())
                }
                val starDimaStage1Def = async(Dispatchers.IO) {
                    if (!payload.isAnime) {
                        emptyList()
                    } else {
                        withTimeoutOrNull(8_000L) {
                            runCatching { resolveStarDimaEmbeds(payload) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                }
                val cimaNowDef = async(Dispatchers.IO) {
                    withTimeoutOrNull(7_000L) {
                        runCatching { extractOpportunisticCimaNowDirectMp4s(payload, dedupLinkCallback) }.getOrDefault(0)
                    } ?: 0
                }

                val stage1Embeds = ArrayList<UnlimitedServerEmbed>().apply {
                    addAll(primaryDef.await())
                    addAll(starDimaStage1Def.await())
                }
                dispatchBatch(stage1Embeds, perServerTimeoutMs = 13_000L)
                cimaNowDef.await()
            }

            // Stage 2: Secondary Zero-Quota Movie & Series Federation (`Egywatch` + `DramaWorld` + `Cimawbas` + `TopCinema` + `StarDima`)
            // Triggered automatically whenever Stage 1 yielded < 2 links OR when a Movie still needs progressive MP4 download mirrors!
            val needsStage2 = totalLinks.get() < 2 || (payload.isMovie && (!hasDirectMp4.get() || totalLinks.get() < 4))
            if (needsStage2) {
                val runCimawbas = payload.isMovie && totalLinks.get() < 3
                val runTopCinema = payload.isMovie && totalLinks.get() < 2
                val runShahid4u = payload.isMovie && totalLinks.get() < 3
                val runStarDimaFallback = payload.isMovie && !payload.isAnime && totalLinks.get() == 0
                val stage2Embeds = supervisorScope {
                    val egywatchDef = async(Dispatchers.IO) {
                        withTimeoutOrNull(9_000L) {
                            runCatching { resolveEgywatchEmbeds(payload) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                    val dramaWorldDef = async(Dispatchers.IO) {
                        withTimeoutOrNull(9_000L) {
                            runCatching { resolveDramaWorldEmbedsAndSubtitles(payload, dedupSubtitleCallback) }
                                .getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                    val shahid4uDef = async(Dispatchers.IO) {
                        if (!runShahid4u) emptyList()
                        else withTimeoutOrNull(9_000L) {
                            runCatching { resolveShahid4uMovieEmbeds(payload) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                    val cimawbasDef = async(Dispatchers.IO) {
                        if (!runCimawbas) emptyList()
                        else withTimeoutOrNull(8_000L) {
                            runCatching { resolveCimawbasMovieEmbeds(payload) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                    val topCinemaDef = async(Dispatchers.IO) {
                        if (!runTopCinema) emptyList()
                        else withTimeoutOrNull(8_000L) {
                            runCatching { resolveTopCinemaMovieEmbeds(payload) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                    val starDimaFallbackDef = async(Dispatchers.IO) {
                        if (!runStarDimaFallback) emptyList()
                        else withTimeoutOrNull(8_000L) {
                            runCatching { resolveStarDimaEmbeds(payload) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }

                    ArrayList<UnlimitedServerEmbed>().apply {
                        addAll(egywatchDef.await())
                        addAll(dramaWorldDef.await())
                        addAll(shahid4uDef.await())
                        addAll(cimawbasDef.await())
                        addAll(topCinemaDef.await())
                        addAll(starDimaFallbackDef.await())
                    }
                }
                dispatchBatch(stage2Embeds, perServerTimeoutMs = 12_000L)
            }
        }

        return totalLinks.get()
    }
}
