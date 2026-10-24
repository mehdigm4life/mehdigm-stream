package com.animeday

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin implementation of the AnimeDay Android v3.8 (`com.anime.day`) native security
 * and API transport layer.
 *
 * Replaces `libnative-lib.so` (`NativeLib.buildSecure` + `NativeLib.decryptResponse`) and
 * `FakeContext` / `FakePackageManager` reflection spoofing with standard JCA primitives,
 * working cleanly across all Android CPU architectures (arm64-v8a, armeabi-v7a, x86, x86_64).
 */
internal object AnimeDayCrypto {

    const val APP_USER_AGENT = "okhttp/4.10.0"
    const val CANONICAL_SITE_URL = "https://www.anime-day.com"
    private const val DEFAULT_API_BASE = "https://20042026.site"
    private const val API_VERSION_PATH = "/v3.8/api"

    private const val DEFAULT_EXTRACTOR_URL = "https://cloud-day.online/cimacloud/extractor.php"
    private const val DEFAULT_HTML_EXTRACTOR_URL = "https://cloud-day.online/cimacloud/html_extractor.php"
    private const val DEFAULT_ADVANCED_EXTRACTOR_URL = "https://cloud-day.online/cimacloud/advanced_extractor.php"

    private val CONFIG_WORKER_URLS = listOf(
        "https://backup-animeday.animegateshorts.workers.dev/",
    )

    private const val CERT_SHA256 = "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7"
    private const val APK_PATH = "/data/app/com.anime.day/base.apk"
    private const val HMAC_SECRET = "97a54e5dec15b4f1754abc5939643bbe57a0d8ae8db88529e268289457728002"
    private const val DECRYPT_SALT = "Gm4il@G00Gl3.Com"
    private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val HEX_CHARS = "0123456789abcdef"

    private const val RSA_PUB_SPKI_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6+BtHI/" +
            "Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3QCh65Nbi4mKRFFJyDk" +
            "JVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9FtgJkuz17n265rLB6ruUUrHQVeEDl8" +
            "ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvsq4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IE" +
            "YV47OyddG32qbriJbxdvW+n5OzowiYQrsC8hG65GPBGCuj3zO7wIDAQAB"

    private const val CONFIG_TTL_MS = 30 * 60 * 1000L

    private val rng = SecureRandom()

    // Stable 16-char hex device identifier per session (mirrors Settings.Secure.ANDROID_ID)
    private val sessionDeviceKey: String by lazy { randomHex(8) }

    // Precomputed static HMAC-SHA256 over "err|<cert>|000|err_open"
    private val staticAttestationMac: String by lazy {
        hmacSha256Hex(HMAC_SECRET, "err|$CERT_SHA256|000|err_open")
    }

    private val rsaPublicKey: PublicKey by lazy {
        val spkiBytes = Base64.decode(RSA_PUB_SPKI_B64, Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spkiBytes))
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class ConfigEndpoints(
        val apiRoot: String = DEFAULT_API_BASE + API_VERSION_PATH,
        val siteUrl: String = CANONICAL_SITE_URL,
        val extractorUrl: String = DEFAULT_EXTRACTOR_URL,
        val htmlExtractorUrl: String = DEFAULT_HTML_EXTRACTOR_URL,
        val advancedExtractorUrl: String = DEFAULT_ADVANCED_EXTRACTOR_URL,
        val fetchedAtMs: Long = 0L,
    ) {
        val phpEndpoints: List<String>
            get() = listOf(extractorUrl, htmlExtractorUrl, advancedExtractorUrl).distinct()
    }

    @Volatile
    private var cachedConfig: ConfigEndpoints? = null
    private val configMutex = Mutex()

    suspend fun resolveConfig(): ConfigEndpoints {
        val now = System.currentTimeMillis()
        cachedConfig?.let { if (now - it.fetchedAtMs < CONFIG_TTL_MS) return it }
        return configMutex.withLock {
            cachedConfig?.let { if (System.currentTimeMillis() - it.fetchedAtMs < CONFIG_TTL_MS) return it }
            val loaded = fetchRemoteConfig() ?: ConfigEndpoints(fetchedAtMs = System.currentTimeMillis())
            cachedConfig = loaded
            loaded
        }
    }

    private suspend fun fetchRemoteConfig(): ConfigEndpoints? = withContext(Dispatchers.IO) {
        for (workerUrl in CONFIG_WORKER_URLS) {
            try {
                val req = Request.Builder()
                    .url(workerUrl)
                    .header("User-Agent", APP_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                val text = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                } ?: continue
                val root = safeJsonObject(text) ?: continue
                val entry = root.optJSONArray("data")?.optJSONObject(0)
                    ?: root.optJSONObject("data")
                    ?: root

                val cloudday = entry.optString("cloudday").trim().trimEnd('/')
                val apiRoot = if (cloudday.startsWith("http://") || cloudday.startsWith("https://")) {
                    if (cloudday.contains("/api")) cloudday else cloudday + API_VERSION_PATH
                } else {
                    DEFAULT_API_BASE + API_VERSION_PATH
                }
                val site = entry.optString("original_app_url")
                    .ifBlank { entry.optString("cimacloud") }
                    .ifBlank { entry.optString("animeday") }
                    .trim()
                    .trimEnd('/')
                    .takeIf { it.startsWith("http") } ?: CANONICAL_SITE_URL
                val extUrl = entry.optString("extractorUrl").trim()
                    .takeIf { it.startsWith("http") } ?: DEFAULT_EXTRACTOR_URL
                val htmlExtUrl = entry.optString("htmlExtractorUrl").trim()
                    .takeIf { it.startsWith("http") } ?: DEFAULT_HTML_EXTRACTOR_URL
                val advExtUrl = entry.optString("advancedExtractorUrl").trim()
                    .takeIf { it.startsWith("http") } ?: DEFAULT_ADVANCED_EXTRACTOR_URL

                return@withContext ConfigEndpoints(
                    apiRoot = apiRoot,
                    siteUrl = site,
                    extractorUrl = extUrl,
                    htmlExtractorUrl = htmlExtUrl,
                    advancedExtractorUrl = advExtUrl,
                    fetchedAtMs = System.currentTimeMillis(),
                )
            } catch (_: Throwable) {
                // Fall through to verified default endpoints
            }
        }
        null
    }

    /**
     * Generates a fresh `cloudflare-id` header and returns `(cloudflareId, decryptionKey)`.
     * Format: 15 random alphanumeric chars + 16-char device key; `key = cloudflareId.substring(15)`.
     */
    fun generateCloudflarePair(): Pair<String, String> {
        val prefix = randomAlnum(15)
        val key = sessionDeviceKey
        return (prefix + key) to key
    }

    /**
     * Pure-Kotlin replica of `com.anime.day.Utils.NativeLib.buildSecure(Context)` producing the
     * `firebase_id` hybrid RSA-2048-PKCS1v1.5 + AES-256-GCM attestation envelope:
     * `B64(encKey).B64(iv).B64(ct||tag)`.
     */
    fun buildSecureToken(): String {
        val nowSec = System.currentTimeMillis() / 1000L
        val nonce = randomHex(8)
        val payloadJson =
            """{"a":"err","b":"$CERT_SHA256|$APK_PATH","c":"false",""" +
                """"d":"empty","e":"empty","f":"false","g":"false","h":"false","i":"false",""" +
                """"j":"false","r":"false","s":"err_open","m":"$staticAttestationMac","t":$nowSec,"n":"$nonce"}"""

        val aesKey = ByteArray(32).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }

        val gcmCipher = Cipher.getInstance("AES/GCM/NoPadding")
        gcmCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val sealedBytes = gcmCipher.doFinal(payloadJson.toByteArray(Charsets.UTF_8))

        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
        val encKeyBytes = rsaCipher.doFinal(aesKey)

        return "${b64NoWrap(encKeyBytes)}.${b64NoWrap(iv)}.${b64NoWrap(sealedBytes)}"
    }

    /**
     * Pure-Kotlin replica of `com.anime.day.Utils.NativeLib.decryptResponse(String, String)`.
     * Key = 32-byte UTF-8 hex of MD5(salt + key); IV = first 16 UTF-8 hex chars of MD5(key + salt).
     */
    fun decryptResponse(encryptedB64: String, key: String): String? {
        if (encryptedB64.isBlank() || key.isEmpty()) return null
        return try {
            val aesKeyBytes = md5Hex(DECRYPT_SALT + key).toByteArray(Charsets.UTF_8)
            val ivBytes = md5Hex(key + DECRYPT_SALT).substring(0, 16).toByteArray(Charsets.UTF_8)
            val cipherBytes = Base64.decode(encryptedB64.trim(), Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKeyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Executes an authenticated GET request against the AnimeDay v3.8 API and transparently
     * unwraps raw Base64 ciphertexts, encrypted envelopes, or plain JSON responses.
     */
    suspend fun apiGet(pathOrUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        val cfg = resolveConfig()
        val fullUrl = resolveApiUrl(cfg.apiRoot, pathOrUrl)
        val (cfId, key) = generateCloudflarePair()
        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", APP_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("cloudflare-id", cfId)
            .header("firebase_id", buildSecureToken())
            .get()
            .build()
        executeAndUnwrap(req, key)
    }

    /**
     * Executes an authenticated form-encoded POST request against the AnimeDay v3.8 API and
     * transparently unwraps encrypted or plain JSON payloads.
     */
    suspend fun apiPost(pathOrUrl: String, formFields: Map<String, String>): JSONObject? =
        withContext(Dispatchers.IO) {
            val cfg = resolveConfig()
            val fullUrl = resolveApiUrl(cfg.apiRoot, pathOrUrl)
            val (cfId, key) = generateCloudflarePair()
            val formBuilder = FormBody.Builder()
            for ((k, v) in formFields) {
                formBuilder.add(k, v)
            }
            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", APP_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("cloudflare-id", cfId)
                .header("firebase_id", buildSecureToken())
                .post(formBuilder.build())
                .build()
            executeAndUnwrap(req, key)
        }

    private fun executeAndUnwrap(request: Request, key: String): JSONObject? {
        return try {
            val rawBody = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            // Case 1: Raw Base64 ciphertext body (used by /movie/{id}/servers & /episode/{id}/servers)
            if (!rawBody.startsWith("{") && !rawBody.startsWith("[")) {
                val decryptedText = decryptResponse(rawBody, key)?.trim() ?: return null
                return parseDecryptedJson(decryptedText)
            }

            if (rawBody.startsWith("[")) {
                return JSONObject().put("items", JSONArray(rawBody))
            }

            // Case 2 & 3: JSON envelope (either encrypted data field or plain JSON response)
            val outer = safeJsonObject(rawBody) ?: return null
            if (outer.optBoolean("blocked", false)) return null

            if (outer.optBoolean("encrypted", false)) {
                val encStr = outer.optString("data", "")
                if (encStr.isNotBlank()) {
                    val decryptedText = decryptResponse(encStr, key)?.trim() ?: return null
                    return parseDecryptedJson(decryptedText)
                }
            }

            // Unwrap nested encrypted base64 in `data` string even if `encrypted` flag was omitted
            val rawDataStr = outer.opt("data") as? String
            if (!rawDataStr.isNullOrBlank() && !rawDataStr.startsWith("{") && !rawDataStr.startsWith("[")) {
                decryptResponse(rawDataStr, key)?.trim()?.let { dec ->
                    parseDecryptedJson(dec)?.let { return it }
                }
            }

            outer.optJSONObject("data")?.let { return it }
            outer.optJSONArray("data")?.takeIf { it.length() > 0 }?.let {
                // Keep outer fields while also exposing array if outer has no other array keys
                if (!outer.has("servers") && !outer.has("results") && !outer.has("section_items") && !outer.has("seasons")) {
                    return JSONObject(outer.toString()).put("items", it)
                }
            }
            outer
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseDecryptedJson(decryptedText: String): JSONObject? = when {
        decryptedText.startsWith("{") -> {
            val obj = safeJsonObject(decryptedText) ?: return null
            if (obj.optBoolean("blocked", false)) null else (obj.optJSONObject("data") ?: obj)
        }
        decryptedText.startsWith("[") -> JSONObject().put("items", JSONArray(decryptedText))
        else -> null
    }

    private fun resolveApiUrl(apiRoot: String, pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val apiMatch = Regex("""/(?:v\d+(?:\.\d+)?/api/)?((?:home|section|movies|series|search|movie|episode)\b.*)$""")
                .find(trimmed)
            if (apiMatch != null) {
                return "${apiRoot.trimEnd('/')}/${apiMatch.groupValues[1].trimStart('/')}"
            }
            return trimmed
        }
        return "${apiRoot.trimEnd('/')}/${trimmed.trimStart('/')}"
    }

    internal fun safeJsonObject(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        if (!t.startsWith("{")) return null
        return try {
            JSONObject(t)
        } catch (_: Throwable) {
            null
        }
    }

    private fun b64NoWrap(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun randomAlnum(length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(ALPHANUM[rng.nextInt(ALPHANUM.length)])
        }
        return sb.toString()
    }

    private fun randomHex(byteCount: Int): String {
        val buf = ByteArray(byteCount).also { rng.nextBytes(it) }
        return buf.toHexLower()
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .toHexLower()

    private fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHexLower()
    }

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }
}
