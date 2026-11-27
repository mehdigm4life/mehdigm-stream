package com.animeday

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.app.cimacloud.Utils.NativeLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AnimeDay v3.8 API transport and response decoder.
 *
 * Server authentication is delegated to the official native `buildSecure(Context)` routine;
 * response decryption and API parsing remain in Kotlin for testability.
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

    private const val API_LOG_TAG = "AnimeDayLinks"
    private const val OFFICIAL_PACKAGE = "com.anime.day"
    private const val DECRYPT_SALT = "Gm4il@G00Gl3.Com"
    private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val HEX_CHARS = "0123456789abcdef"

    private const val CONFIG_TTL_MS = 30 * 60 * 1000L

    private val rng = SecureRandom()

    // Stable fallback for devices that cannot expose ANDROID_ID through the app context.
    private val sessionDeviceKey: String by lazy { randomHex(8) }

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext ?: context
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
    @SuppressLint("HardwareIds")
    fun generateCloudflarePair(): Pair<String, String> {
        val prefix = randomAlnum(15)
        val context = appContext
        val androidId = try {
            context?.let { Settings.Secure.getString(it.contentResolver, Settings.Secure.ANDROID_ID) }
        } catch (_: Throwable) {
            null
        }
        val key = androidId?.takeIf { it.isNotBlank() } ?: sessionDeviceKey.also {
            Log.w(API_LOG_TAG, "Android ID unavailable; using a session key for cloudflare-id")
        }
        return (prefix + key) to key
    }

    /** Uses the official app's native attestation routine; never sends the old `err` sentinel token. */
    private fun buildSecureToken(resource: String): String? {
        val context = appContext
        if (context == null) {
            Log.w(API_LOG_TAG, "API request skipped: plugin context unavailable resource=$resource")
            return null
        }
        return try {
            NativeLib.secureId(context)?.takeIf { it.isNotBlank() }?.also {
                Log.i(API_LOG_TAG, "native secure token generated resource=$resource")
            } ?: run {
                Log.w(API_LOG_TAG, "native secure token unavailable resource=$resource")
                null
            }
        } catch (error: Throwable) {
            Log.w(
                API_LOG_TAG,
                "native secure token failed resource=$resource error=${error.javaClass.simpleName}",
            )
            null
        }
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
        val resource = apiResourceKind(pathOrUrl)
        val secureToken = buildSecureToken(resource) ?: return@withContext null
        val cfg = resolveConfig()
        val fullUrl = resolveApiUrl(cfg.apiRoot, pathOrUrl)
        val (cfId, key) = generateCloudflarePair()
        Log.i(API_LOG_TAG, "API request method=GET route=${safeApiRoute(pathOrUrl)}")
        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", APP_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("cloudflare-id", cfId)
            .header("firebase_id", secureToken)
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
            val resource = apiResourceKind(pathOrUrl)
            val secureToken = buildSecureToken(resource) ?: return@withContext null
            val cfg = resolveConfig()
            val fullUrl = resolveApiUrl(cfg.apiRoot, pathOrUrl)
            val (cfId, key) = generateCloudflarePair()
            val formBuilder = FormBody.Builder()
            for ((k, v) in formFields) {
                formBuilder.add(k, v)
            }
            Log.i(API_LOG_TAG, "API request method=POST route=${safeApiRoute(pathOrUrl)}")
            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", APP_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("cloudflare-id", cfId)
                .header("firebase_id", secureToken)
                .post(formBuilder.build())
                .build()
            executeAndUnwrap(req, key)
        }

    private fun executeAndUnwrap(request: Request, key: String): JSONObject? {
        val route = safeApiRoute(request.url.encodedPath)
        return try {
            val rawBody = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(API_LOG_TAG, "API HTTP status=${resp.code} method=${request.method} route=$route")
                    return null
                }
                resp.body?.string()
            }?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                Log.w(API_LOG_TAG, "API response empty method=${request.method} route=$route")
                return null
            }

            // Case 1: Raw Base64 ciphertext body (used by /movie/{id}/servers & /episode/{id}/servers)
            if (!rawBody.startsWith("{") && !rawBody.startsWith("[")) {
                val decryptedText = decryptResponse(rawBody, key)?.trim() ?: run {
                    Log.w(API_LOG_TAG, "API response decryption failed method=${request.method} route=$route")
                    return null
                }
                return parseDecryptedJson(decryptedText, route)
            }

            if (rawBody.startsWith("[")) {
                return JSONObject().put("items", JSONArray(rawBody))
            }

            // Case 2 & 3: JSON envelope (either encrypted data field or plain JSON response)
            val outer = safeJsonObject(rawBody) ?: run {
                Log.w(API_LOG_TAG, "API response invalid JSON method=${request.method} route=$route")
                return null
            }
            if (outer.optBoolean("blocked", false)) {
                Log.w(API_LOG_TAG, "API response blocked method=${request.method} route=$route")
                return null
            }

            if (outer.optBoolean("encrypted", false)) {
                val encStr = outer.optString("data", "")
                if (encStr.isNotBlank()) {
                    val decryptedText = decryptResponse(encStr, key)?.trim() ?: run {
                        Log.w(API_LOG_TAG, "encrypted API response decryption failed route=$route")
                        return null
                    }
                    return parseDecryptedJson(decryptedText, route)
                }
            }

            // Unwrap nested encrypted base64 in `data` string even if `encrypted` flag was omitted
            val rawDataStr = outer.opt("data") as? String
            if (!rawDataStr.isNullOrBlank() && !rawDataStr.startsWith("{") && !rawDataStr.startsWith("[")) {
                decryptResponse(rawDataStr, key)?.trim()?.let { dec ->
                    parseDecryptedJson(dec, route)?.let { return it }
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(
                API_LOG_TAG,
                "API request failed method=${request.method} route=$route error=${error.javaClass.simpleName}",
            )
            null
        }
    }

    private fun parseDecryptedJson(decryptedText: String, route: String): JSONObject? = when {
        decryptedText.startsWith("{") -> {
            val obj = safeJsonObject(decryptedText) ?: run {
                Log.w(API_LOG_TAG, "decrypted API response invalid JSON route=$route")
                return null
            }
            if (obj.optBoolean("blocked", false)) {
                Log.w(API_LOG_TAG, "decrypted API response blocked route=$route")
                null
            } else {
                obj.optJSONObject("data") ?: obj
            }
        }
        decryptedText.startsWith("[") -> JSONObject().put("items", JSONArray(decryptedText))
        else -> {
            Log.w(API_LOG_TAG, "decrypted API response unexpected format route=$route")
            null
        }
    }

    private fun apiResourceKind(pathOrUrl: String): String =
        Regex("""(?:^|/)(home|section|movies|series|search|movie|episode)(?:/|[?#]|$)""", RegexOption.IGNORE_CASE)
            .find(pathOrUrl)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            ?: "other"

    private fun safeApiRoute(pathOrUrl: String): String {
        val route = pathOrUrl
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter(API_VERSION_PATH, pathOrUrl)
            .trimStart('/')
        return route.take(160).ifBlank { "unknown" }
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
