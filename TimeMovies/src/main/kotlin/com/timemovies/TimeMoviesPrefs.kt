package com.timemovies

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe persistent storage for TimeMovies auth, profile, device keypair, and user settings.
 * Falls back gracefully to an in-memory map during JVM unit tests where Android `Context` is absent.
 */
object TimeMoviesPrefs {
    private const val PREFS_NAME = "timemovies_plugin_prefs_v1"

    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_PENDING_OTP_TOKEN = "pending_otp_token"
    private const val KEY_SAVED_EMAIL = "saved_email"
    private const val KEY_SAVED_PASSWORD = "saved_password"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_PROFILE_MATURITY = "profile_maturity"
    private const val KEY_DEVICE_KEY_ID = "device_key_id"
    private const val KEY_EC_PRIV = "ec_private_pkcs8_b64u"
    private const val KEY_EC_PUB = "ec_public_spki_b64u"
    private const val KEY_INSTALL_ID = "rasplab_install_id"
    private const val KEY_DEVICE_ID = "android_device_id"
    private const val KEY_PREFER_ARABIC = "prefer_arabic"
    private const val KEY_FETCH_DOWNLOAD_GRANT = "fetch_download_grant"
    private const val KEY_CUSTOM_API_ORIGIN = "custom_api_origin"

    const val DEFAULT_API_ORIGIN = "https://api.apks.lol"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val memoryStore = ConcurrentHashMap<String, Any>()

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun getString(key: String): String? {
        val sp = prefs
        return if (sp != null) sp.getString(key, null) else memoryStore[key] as? String
    }

    private fun putString(key: String, value: String?) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        val sp = prefs
        if (sp != null) {
            sp.edit().apply {
                if (trimmed == null) remove(key) else putString(key, trimmed)
            }.apply()
        } else {
            if (trimmed == null) memoryStore.remove(key) else memoryStore[key] = trimmed
        }
    }

    private fun getInt(key: String, default: Int): Int {
        val sp = prefs
        return if (sp != null) sp.getInt(key, default) else (memoryStore[key] as? Int) ?: default
    }

    private fun putInt(key: String, value: Int) {
        val sp = prefs
        if (sp != null) {
            sp.edit().putInt(key, value).apply()
        } else {
            memoryStore[key] = value
        }
    }

    private fun getBoolean(key: String, default: Boolean): Boolean {
        val sp = prefs
        return if (sp != null) sp.getBoolean(key, default) else (memoryStore[key] as? Boolean) ?: default
    }

    private fun putBoolean(key: String, value: Boolean) {
        val sp = prefs
        if (sp != null) {
            sp.edit().putBoolean(key, value).apply()
        } else {
            memoryStore[key] = value
        }
    }

    var authToken: String
        get() = getString(KEY_AUTH_TOKEN).orEmpty()
        set(value) = putString(KEY_AUTH_TOKEN, value)

    var pendingOtpToken: String
        get() = getString(KEY_PENDING_OTP_TOKEN).orEmpty()
        set(value) = putString(KEY_PENDING_OTP_TOKEN, value)

    var savedEmail: String
        get() = getString(KEY_SAVED_EMAIL).orEmpty()
        set(value) = putString(KEY_SAVED_EMAIL, value)

    var savedPassword: String
        get() = getString(KEY_SAVED_PASSWORD).orEmpty()
        set(value) = putString(KEY_SAVED_PASSWORD, value)

    var profileId: String?
        get() = getString(KEY_PROFILE_ID)
        set(value) = putString(KEY_PROFILE_ID, value)

    var profileName: String?
        get() = getString(KEY_PROFILE_NAME)
        set(value) = putString(KEY_PROFILE_NAME, value)

    var profileMaturity: Int
        get() = getInt(KEY_PROFILE_MATURITY, 5)
        set(value) = putInt(KEY_PROFILE_MATURITY, value)

    var deviceKeyId: String?
        get() = getString(KEY_DEVICE_KEY_ID)
        set(value) = putString(KEY_DEVICE_KEY_ID, value)

    var preferArabic: Boolean
        get() = getBoolean(KEY_PREFER_ARABIC, true)
        set(value) = putBoolean(KEY_PREFER_ARABIC, value)

    var fetchDownloadGrant: Boolean
        get() = getBoolean(KEY_FETCH_DOWNLOAD_GRANT, true)
        set(value) = putBoolean(KEY_FETCH_DOWNLOAD_GRANT, value)

    var customApiOrigin: String?
        get() = getString(KEY_CUSTOM_API_ORIGIN)
        set(value) = putString(KEY_CUSTOM_API_ORIGIN, value?.trimEnd('/'))

    val activeApiOrigin: String
        get() = customApiOrigin?.takeIf { it.startsWith("http") }?.trimEnd('/') ?: DEFAULT_API_ORIGIN

    val isSignedIn: Boolean
        get() = authToken.isNotBlank()

    /**
     * Persistent 32-hex RASP Lab install ID (`rasplab_install_id`).
     */
    @Synchronized
    fun getOrCreateInstallId(): String {
        val existing = getString(KEY_INSTALL_ID)
        if (!existing.isNullOrBlank() && existing.length == 32) return existing
        val fresh = TimeMoviesCrypto.randomHex(16)
        putString(KEY_INSTALL_ID, fresh)
        return fresh
    }

    /**
     * Persistent 16-hex Android device ID (`device_id`).
     */
    @Synchronized
    fun getOrCreateAndroidDeviceId(): String {
        val existing = getString(KEY_DEVICE_ID)
        if (!existing.isNullOrBlank() && existing.length == 16) return existing
        val fresh = TimeMoviesCrypto.randomHex(8)
        putString(KEY_DEVICE_ID, fresh)
        return fresh
    }

    /**
     * Returns or generates the persistent NIST P-256 (`secp256r1`) keypair for this device.
     */
    @Synchronized
    fun getOrCreateEcKeyPair(): TimeMoviesCrypto.EcKeyMaterial {
        val priv = getString(KEY_EC_PRIV)
        val pub = getString(KEY_EC_PUB)
        if (!priv.isNullOrBlank() && !pub.isNullOrBlank()) {
            val valid = runCatching {
                TimeMoviesCrypto.loadPrivateKey(priv)
                TimeMoviesCrypto.loadPublicKey(pub)
                true
            }.getOrDefault(false)
            if (valid) {
                return TimeMoviesCrypto.EcKeyMaterial(priv, pub)
            }
        }
        val generated = TimeMoviesCrypto.generateP256KeyPair()
        putString(KEY_EC_PRIV, generated.privateKeyPkcs8B64Url)
        putString(KEY_EC_PUB, generated.publicKeySpkiB64Url)
        putString(KEY_DEVICE_KEY_ID, null)
        return generated
    }

    @Synchronized
    fun resetDeviceKeyEnrollment(regenerateKeyPair: Boolean = false) {
        putString(KEY_DEVICE_KEY_ID, null)
        if (regenerateKeyPair) {
            putString(KEY_EC_PRIV, null)
            putString(KEY_EC_PUB, null)
        }
    }

    @Synchronized
    fun signOut() {
        authToken = ""
        pendingOtpToken = ""
        savedEmail = ""
        savedPassword = ""
        profileId = null
        profileName = null
        profileMaturity = 5
        deviceKeyId = null
    }

    /**
     * Decodes JWT payload claims (`AU` user claims: `email`/`e`, `sid`, `_id`, `iv`, `fq`, `dcr`, `dvlm`, `exp`).
     */
    fun decodeJwtClaims(token: String = authToken): JSONObject? {
        val parts = token.trim().split('.')
        if (parts.size < 2) return null
        return runCatching {
            val jsonStr = String(TimeMoviesCrypto.base64DecodeFlexible(parts[1]), Charsets.UTF_8)
            JSONObject(jsonStr)
        }.getOrNull()
    }
}
