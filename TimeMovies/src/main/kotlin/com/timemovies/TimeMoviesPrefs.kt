package com.timemovies

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent preference, auth session, ECDSA P-256 keypair, Virtual Retail Handset Identity,
 * and Built-in Account Pool overlay store for TimeMovies v4.7 (v3).
 *
 * Security & Privacy note:
 * Never reads or transmits physical `android.os.Build.*` or `Settings.Secure.ANDROID_ID` properties
 * from the host phone, preventing hardware-fingerprint bans from carrying over across accounts.
 */
object TimeMoviesPrefs {
    private const val PREFS_NAME = "timemovies_plugin_prefs_v2"

    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_PENDING_OTP_TOKEN = "pending_otp_token"
    private const val KEY_PENDING_OTP_EMAIL = "pending_otp_email"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_PROFILE_MATURITY = "profile_maturity"
    private const val KEY_EC_PRIV = "ec_p256_priv_b64u"
    private const val KEY_EC_PUB = "ec_p256_pub_b64u"
    private const val KEY_DEVICE_KEY_ID = "device_key_id"
    private const val KEY_DEVICE_KEY_SCOPE = "device_key_scope"
    private const val KEY_VIRTUAL_DEVICE_JSON = "virtual_device_json"
    private const val KEY_CLOCK_OFFSET_MS = "clock_offset_ms"
    private const val KEY_CLOCK_OFFSET_SET = "clock_offset_set"
    private const val KEY_PREFER_ARABIC = "prefer_arabic_titles"

    // Built-in Pre-Activated Pool keys
    private const val KEY_REMOTE_POOL_BUNDLES = "remote_pool_bundles_raw"
    private const val KEY_REMOTE_POOL_FETCHED_MS = "remote_pool_fetched_ms"
    private const val KEY_LOCAL_POOL_BUNDLES = "local_pool_bundles_raw"
    private const val KEY_POOL_RUNTIME_STATE = "pool_runtime_state_json"

    private val SYNC_COMMIT_KEYS = setOf(
        KEY_AUTH_TOKEN,
        KEY_PENDING_OTP_TOKEN,
        KEY_PENDING_OTP_EMAIL,
        KEY_EC_PRIV,
        KEY_EC_PUB,
        KEY_DEVICE_KEY_ID,
        KEY_DEVICE_KEY_SCOPE,
        KEY_VIRTUAL_DEVICE_JSON,
        KEY_LOCAL_POOL_BUNDLES,
    )

    data class VirtualDeviceProfile(
        val displayLabel: String,
        val name: String,
        val deviceName: String,
        val uniqueId: String,
        val deviceId: String,
        val rasplabInstallId: String,
        val brand: String,
        val manufacturer: String,
        val model: String,
        val systemVersion: String,
        val board: String,
        val product: String,
        val host: String,
        val fingerprint: String,
        val bootloader: String,
        val hardware: String,
        val sku: String,
        val odmSku: String,
        val socManufacturer: String,
        val socModel: String,
        val totalRam: String,
        val totalMemory: String,
        val abis: String = "arm64-v8a,armeabi-v7a,armeabi",
        val lang: String = "ar",
    ) {
        fun toStorageJson(): String = JSONObject().apply {
            put("displayLabel", displayLabel)
            put("name", name)
            put("deviceName", deviceName)
            put("uniqueId", uniqueId)
            put("deviceId", deviceId)
            put("rasplabInstallId", rasplabInstallId)
            put("brand", brand)
            put("manufacturer", manufacturer)
            put("model", model)
            put("systemVersion", systemVersion)
            put("board", board)
            put("product", product)
            put("host", host)
            put("fingerprint", fingerprint)
            put("bootloader", bootloader)
            put("hardware", hardware)
            put("sku", sku)
            put("odmSku", odmSku)
            put("socManufacturer", socManufacturer)
            put("socModel", socModel)
            put("totalRam", totalRam)
            put("totalMemory", totalMemory)
            put("abis", abis)
            put("lang", lang)
        }.toString()

        fun toApiUserDeviceJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("device_name", deviceName)
            put("unique_id", uniqueId)
            put("brand", brand)
            put("device_id", deviceId)
            put("device_type", "Handset")
            put("device_model", model)
            put("device_version", TimeMoviesCrypto.APP_VERSION_NAME)
            put("device_readable_version", "${TimeMoviesCrypto.APP_VERSION_NAME}.${TimeMoviesCrypto.APP_BUILD}")
            put("system_name", "Android")
            put("system_version", systemVersion)
            put("total_memory", totalMemory)
            put("total_ram", totalRam)
            put("device_manufacturer", manufacturer)
            put("build_number", TimeMoviesCrypto.APP_BUILD)
            put("installer_package", "com.android.vending")
            put("install_referrer", "utm_source=google-play&utm_medium=organic")
            put("device_lang", lang)
            put("device_board", board)
            put("device_product", product)
            put("device_host", host)
            put("device_fingerprint", fingerprint)
            put("device_bootloader", bootloader)
            put("device_hardware", hardware)
            put("device_tags", "release-keys")
            put("device_abis", abis)
            put("device_sku", sku)
            put("device_odm_sku", odmSku)
            put("device_soc_manufacturer", socManufacturer)
            put("device_soc_model", socModel)
            put("device_package", TimeMoviesCrypto.APP_PACKAGE)
            put("rasplab_install_id", rasplabInstallId)
        }

        companion object {
            fun fromStorageJson(raw: String): VirtualDeviceProfile? = runCatching {
                val o = JSONObject(raw)
                val uniqueId = o.optString("uniqueId", "").trim()
                val deviceId = o.optString("deviceId", "").trim()
                val rasplabId = o.optString("rasplabInstallId", "").trim()
                val model = o.optString("model", "").trim()
                if (uniqueId.length != 16 || deviceId.length != 16 || rasplabId.length != 32 || model.isEmpty()) {
                    return null
                }
                VirtualDeviceProfile(
                    displayLabel = o.optString("displayLabel", model),
                    name = o.optString("name", model),
                    deviceName = o.optString("deviceName", model),
                    uniqueId = uniqueId,
                    deviceId = deviceId,
                    rasplabInstallId = rasplabId,
                    brand = o.optString("brand", "samsung"),
                    manufacturer = o.optString("manufacturer", "samsung"),
                    model = model,
                    systemVersion = o.optString("systemVersion", "14"),
                    board = o.optString("board", "kalama"),
                    product = o.optString("product", "dm1qxxx"),
                    host = o.optString("host", "21HH1E16"),
                    fingerprint = o.optString("fingerprint", ""),
                    bootloader = o.optString("bootloader", ""),
                    hardware = o.optString("hardware", "qcom"),
                    sku = o.optString("sku", model),
                    odmSku = o.optString("odmSku", model),
                    socManufacturer = o.optString("socManufacturer", "Qualcomm"),
                    socModel = o.optString("socModel", "SM8550"),
                    totalRam = o.optString("totalRam", "8589934592"),
                    totalMemory = o.optString("totalMemory", "128000000000"),
                    abis = o.optString("abis", "arm64-v8a,armeabi-v7a,armeabi"),
                    lang = o.optString("lang", "ar"),
                )
            }.getOrNull()
        }
    }

    private data class HandsetTemplate(
        val displayLabel: String,
        val marketingName: String,
        val codename: String,
        val brand: String,
        val manufacturer: String,
        val model: String,
        val systemVersion: String,
        val buildId: String,
        val board: String,
        val product: String,
        val hostPrefix: String,
        val bootloaderPrefix: String,
        val hardware: String,
        val socManufacturer: String,
        val socModel: String,
        val totalRam: String,
        val totalMemory: String,
    )

    private val HANDSET_TEMPLATES = listOf(
        HandsetTemplate(
            displayLabel = "Samsung Galaxy S24 Ultra (SM-S928B)",
            marketingName = "Galaxy S24 Ultra",
            codename = "e3q",
            brand = "samsung",
            manufacturer = "samsung",
            model = "SM-S928B",
            systemVersion = "14",
            buildId = "UP1A.231005.007",
            board = "pineapple",
            product = "e3qxxx",
            hostPrefix = "SWDD",
            bootloaderPrefix = "S928BXXS3AX",
            hardware = "qcom",
            socManufacturer = "Qualcomm",
            socModel = "SM8650",
            totalRam = "12884901888",
            totalMemory = "256000000000",
        ),
        HandsetTemplate(
            displayLabel = "Samsung Galaxy S23 (SM-S911B)",
            marketingName = "Galaxy S23",
            codename = "dm1q",
            brand = "samsung",
            manufacturer = "samsung",
            model = "SM-S911B",
            systemVersion = "14",
            buildId = "UP1A.231005.007",
            board = "kalama",
            product = "dm1qxxx",
            hostPrefix = "21HH",
            bootloaderPrefix = "S911BXXS6CX",
            hardware = "qcom",
            socManufacturer = "Qualcomm",
            socModel = "SM8550",
            totalRam = "8589934592",
            totalMemory = "256000000000",
        ),
        HandsetTemplate(
            displayLabel = "Samsung Galaxy A55 5G (SM-A556E)",
            marketingName = "Galaxy A55 5G",
            codename = "a55x",
            brand = "samsung",
            manufacturer = "samsung",
            model = "SM-A556E",
            systemVersion = "14",
            buildId = "UP1A.231005.007",
            board = "s5e8845",
            product = "a55xnsxx",
            hostPrefix = "SWDH",
            bootloaderPrefix = "A556EXXS4AX",
            hardware = "s5e8845",
            socManufacturer = "Samsung",
            socModel = "S5E8845",
            totalRam = "8589934592",
            totalMemory = "128000000000",
        ),
        HandsetTemplate(
            displayLabel = "Google Pixel 8 Pro",
            marketingName = "Pixel 8 Pro",
            codename = "husky",
            brand = "google",
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            systemVersion = "15",
            buildId = "AP3A.241005.015",
            board = "husky",
            product = "husky",
            hostPrefix = "abfarm",
            bootloaderPrefix = "ripcurrent-15.0-",
            hardware = "husky",
            socManufacturer = "Google",
            socModel = "Tensor G3",
            totalRam = "12884901888",
            totalMemory = "256000000000",
        ),
        HandsetTemplate(
            displayLabel = "Google Pixel 7a",
            marketingName = "Pixel 7a",
            codename = "lynx",
            brand = "google",
            manufacturer = "Google",
            model = "Pixel 7a",
            systemVersion = "14",
            buildId = "UQ1A.240205.002",
            board = "lynx",
            product = "lynx",
            hostPrefix = "abfarm",
            bootloaderPrefix = "lynx-14.0-",
            hardware = "lynx",
            socManufacturer = "Google",
            socModel = "Tensor G2",
            totalRam = "8589934592",
            totalMemory = "128000000000",
        ),
        HandsetTemplate(
            displayLabel = "Xiaomi 14 (23127PN0CG)",
            marketingName = "Xiaomi 14",
            codename = "houji",
            brand = "Xiaomi",
            manufacturer = "Xiaomi",
            model = "23127PN0CG",
            systemVersion = "14",
            buildId = "UKQ1.231003.002",
            board = "pineapple",
            product = "houji_global",
            hostPrefix = "pangu-build",
            bootloaderPrefix = "OS1.0.",
            hardware = "qcom",
            socManufacturer = "Qualcomm",
            socModel = "SM8650",
            totalRam = "12884901888",
            totalMemory = "256000000000",
        ),
        HandsetTemplate(
            displayLabel = "Redmi Note 13 Pro 5G (2312DRA50G)",
            marketingName = "Redmi Note 13 Pro 5G",
            codename = "garnet",
            brand = "Redmi",
            manufacturer = "Xiaomi",
            model = "2312DRA50G",
            systemVersion = "14",
            buildId = "UKQ1.231003.002",
            board = "parrot",
            product = "garnet_global",
            hostPrefix = "pangu-build",
            bootloaderPrefix = "OS1.0.",
            hardware = "qcom",
            socManufacturer = "Qualcomm",
            socModel = "SM7435",
            totalRam = "8589934592",
            totalMemory = "256000000000",
        ),
        HandsetTemplate(
            displayLabel = "OnePlus 12 (CPH2581)",
            marketingName = "OnePlus 12",
            codename = "OP5929L1",
            brand = "OnePlus",
            manufacturer = "OnePlus",
            model = "CPH2581",
            systemVersion = "14",
            buildId = "UKQ1.230924.001",
            board = "pineapple",
            product = "CPH2581",
            hostPrefix = "dg02-pool",
            bootloaderPrefix = "CPH2581_14.0.0.",
            hardware = "qcom",
            socManufacturer = "Qualcomm",
            socModel = "SM8650",
            totalRam = "17179869184",
            totalMemory = "512000000000",
        ),
        HandsetTemplate(
            displayLabel = "POCO F6 (24069PC21G)",
            marketingName = "POCO F6",
            codename = "peridot",
            brand = "POCO",
            manufacturer = "Xiaomi",
            model = "24069PC21G",
            systemVersion = "14",
            buildId = "UKQ1.240116.001",
            board = "cliffs",
            product = "peridot_global",
            hostPrefix = "pangu-build",
            bootloaderPrefix = "OS1.0.",
            hardware = "qcom",
            socManufacturer = "Qualcomm",
            socModel = "SM8635",
            totalRam = "12884901888",
            totalMemory = "256000000000",
        ),
        HandsetTemplate(
            displayLabel = "realme GT 6 (RMX3851)",
            marketingName = "realme GT 6",
            codename = "RE5C4FL1",
            brand = "realme",
            manufacturer = "realme",
            model = "RMX3851",
            systemVersion = "14",
            buildId = "UKQ1.230924.001",
            board = "cliffs",
            product = "RMX3851",
            hostPrefix = "dg02-pool",
            bootloaderPrefix = "RMX3851_14.A.",
            hardware = "qcom",
            socManufacturer = "Qualcomm",
            socModel = "SM8635",
            totalRam = "12884901888",
            totalMemory = "256000000000",
        ),
    )

    /**
     * Strong `Application` context reference so ART GC never collects CloudStream's temporary
     * `PluginContext` wrapper while the user switches to Gmail to copy an OTP code.
     */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var unitTestIsolatedMode: Boolean = false

    private val memoryFallback = ConcurrentHashMap<String, String>()
    private val secureRandom = SecureRandom()

    /** Test-only URL overrides so JVM unit tests can run full end-to-end HTTP flows locally. */
    @Volatile
    internal var apiBaseUrlOverride: String? = null

    @Volatile
    internal var mediaV2BaseUrlOverride: String? = null

    @Volatile
    internal var doomBaseUrlOverride: String? = null

    @Volatile
    internal var remotePoolUrlsOverride: List<String>? = null

    fun init(context: Context) {
        unitTestIsolatedMode = false
        appContext = context.applicationContext ?: resolveHostApplicationContext() ?: context
    }

    private fun resolveHostApplicationContext(): Context? {
        if (unitTestIsolatedMode) return null
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getMethod("currentApplication")
            (m.invoke(null) as? Context)?.let { return it }
            null
        }.getOrNull() ?: runCatching {
            val acra = Class.forName("com.lagradost.cloudstream3.AcraApplication")
            val companionField = acra.getDeclaredField("Companion")
            companionField.isAccessible = true
            val companion = companionField.get(null)
            val getCtx = companion.javaClass.getMethod("getContext")
            getCtx.invoke(companion) as? Context
        }.getOrNull()
    }

    private fun prefs(): SharedPreferences? {
        if (unitTestIsolatedMode) return null
        val ctx = appContext ?: resolveHostApplicationContext()?.also { appContext = it } ?: return null
        return runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    private fun getString(key: String, default: String? = null): String? {
        val sp = prefs()
        if (sp != null) {
            val v = sp.getString(key, null)
            if (!v.isNullOrBlank()) {
                memoryFallback[key] = v
                return v
            }
        }
        return memoryFallback[key]?.takeIf { it.isNotBlank() } ?: default
    }

    private fun putString(key: String, value: String?) {
        val syncCommit = key in SYNC_COMMIT_KEYS
        if (value.isNullOrBlank()) {
            memoryFallback.remove(key)
            prefs()?.edit()?.remove(key)?.let { editor ->
                if (syncCommit) editor.commit() else editor.apply()
            }
        } else {
            memoryFallback[key] = value
            prefs()?.edit()?.putString(key, value)?.let { editor ->
                if (syncCommit) editor.commit() else editor.apply()
            }
        }
    }

    private fun randomHex(byteCount: Int): String {
        val buf = ByteArray(byteCount)
        secureRandom.nextBytes(buf)
        val out = CharArray(byteCount * 2)
        val hex = "0123456789abcdef"
        for (i in buf.indices) {
            val v = buf[i].toInt() and 0xFF
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(out)
    }

    internal fun buildSyntheticDevice(excludeModel: String? = null): VirtualDeviceProfile {
        val candidates = HANDSET_TEMPLATES.filter { it.model != excludeModel }.ifEmpty { HANDSET_TEMPLATES }
        val tpl = candidates[secureRandom.nextInt(candidates.size)]
        val uniqueId = randomHex(8) // 16 lowercase hex chars (ANDROID_ID format)
        val deviceId = randomHex(8) // 16 lowercase hex chars
        val rasplabId = randomHex(16) // 32 lowercase hex chars (`^[0-9a-f]{32}$` per `wh7.java`)
        val incNum = 10 + secureRandom.nextInt(89)
        val hostSuffix = randomHex(2).uppercase()
        val bootloader = "${tpl.bootloaderPrefix}$incNum"
        val fingerprint = "${tpl.brand}/${tpl.product}/${tpl.codename}:${tpl.systemVersion}/${tpl.buildId}/$bootloader:user/release-keys"

        return VirtualDeviceProfile(
            displayLabel = tpl.displayLabel,
            name = tpl.marketingName,
            deviceName = tpl.codename,
            uniqueId = uniqueId,
            deviceId = deviceId,
            rasplabInstallId = rasplabId,
            brand = tpl.brand,
            manufacturer = tpl.manufacturer,
            model = tpl.model,
            systemVersion = tpl.systemVersion,
            board = tpl.board,
            product = tpl.product,
            host = "${tpl.hostPrefix}$hostSuffix",
            fingerprint = fingerprint,
            bootloader = bootloader,
            hardware = tpl.hardware,
            sku = tpl.model,
            odmSku = tpl.model,
            socManufacturer = tpl.socManufacturer,
            socModel = tpl.socModel,
            totalRam = tpl.totalRam,
            totalMemory = tpl.totalMemory,
        )
    }

    @Synchronized
    fun getOrCreateVirtualDevice(): VirtualDeviceProfile {
        val existingRaw = getString(KEY_VIRTUAL_DEVICE_JSON)
        if (!existingRaw.isNullOrBlank()) {
            VirtualDeviceProfile.fromStorageJson(existingRaw)?.let { return it }
        }
        val created = buildSyntheticDevice()
        putString(KEY_VIRTUAL_DEVICE_JSON, created.toStorageJson())
        return created
    }

    /**
     * Rotates the virtual retail handset identity, generates a brand-new canonical P-256 ECDSA keypair,
     * and clears any previous personal session/device-key bindings so a fresh account cannot be linked
     * to any prior device or banned hardware fingerprint.
     */
    @Synchronized
    fun rotateVirtualDeviceIdentity(): VirtualDeviceProfile {
        val currentModel = getString(KEY_VIRTUAL_DEVICE_JSON)
            ?.let { VirtualDeviceProfile.fromStorageJson(it)?.model }
        val fresh = buildSyntheticDevice(excludeModel = currentModel)
        putString(KEY_VIRTUAL_DEVICE_JSON, fresh.toStorageJson())
        clearSessionAndDeviceKeys()
        val kp = TimeMoviesCrypto.generateP256KeyPair()
        putString(KEY_EC_PRIV, kp.privateKeyPkcs8B64Url)
        putString(KEY_EC_PUB, kp.publicKeySpkiB64Url)
        return fresh
    }

    // ---- Personal Auth Token & User Session ----

    var authToken: String?
        get() = getString(KEY_AUTH_TOKEN)
        set(value) = putString(KEY_AUTH_TOKEN, value?.trim())

    var pendingOtpToken: String?
        get() = getString(KEY_PENDING_OTP_TOKEN)
        set(value) = putString(KEY_PENDING_OTP_TOKEN, value?.trim())

    var pendingOtpEmail: String?
        get() = getString(KEY_PENDING_OTP_EMAIL)
        set(value) = putString(KEY_PENDING_OTP_EMAIL, value?.trim())

    var userEmail: String?
        get() = getString(KEY_USER_EMAIL)
        set(value) = putString(KEY_USER_EMAIL, value?.trim())

    var profileId: String?
        get() = getString(KEY_PROFILE_ID)
        set(value) = putString(KEY_PROFILE_ID, value?.trim())

    var profileName: String?
        get() = getString(KEY_PROFILE_NAME)
        set(value) = putString(KEY_PROFILE_NAME, value?.trim())

    var profileMaturity: Int
        get() = getString(KEY_PROFILE_MATURITY)?.toIntOrNull() ?: 5
        set(value) = putString(KEY_PROFILE_MATURITY, value.toString())

    var preferArabic: Boolean
        get() = getString(KEY_PREFER_ARABIC)?.toBooleanStrictOrNull() ?: true
        set(value) = putString(KEY_PREFER_ARABIC, value.toString())

    val isSignedIn: Boolean
        get() = !authToken.isNullOrBlank()

    // ---- Built-in Account Pool Persistence ----

    var remotePoolBundlesRaw: String?
        get() = getString(KEY_REMOTE_POOL_BUNDLES)
        set(value) = putString(KEY_REMOTE_POOL_BUNDLES, value)

    var remotePoolFetchedAtMs: Long
        get() = getString(KEY_REMOTE_POOL_FETCHED_MS)?.toLongOrNull() ?: 0L
        set(value) = putString(KEY_REMOTE_POOL_FETCHED_MS, value.toString())

    var localPoolBundlesRaw: String?
        get() = getString(KEY_LOCAL_POOL_BUNDLES)
        set(value) = putString(KEY_LOCAL_POOL_BUNDLES, value)

    var poolRuntimeStateJson: String?
        get() = getString(KEY_POOL_RUNTIME_STATE)
        set(value) = putString(KEY_POOL_RUNTIME_STATE, value)

    // ---- Server Clock Offset ----

    var clockOffsetMs: Long
        get() = getString(KEY_CLOCK_OFFSET_MS)?.toLongOrNull() ?: 0L
        set(value) {
            putString(KEY_CLOCK_OFFSET_MS, value.toString())
            putString(KEY_CLOCK_OFFSET_SET, "true")
        }

    val hasPersistedClockOffset: Boolean
        get() = getString(KEY_CLOCK_OFFSET_SET) == "true"

    // ---- Virtual Device Identifiers & P-256 Device Key ----

    val deviceId: String
        get() = getOrCreateVirtualDevice().deviceId

    val uniqueId: String
        get() = getOrCreateVirtualDevice().uniqueId

    val rasplabInstallId: String
        get() = getOrCreateVirtualDevice().rasplabInstallId

    val virtualDeviceLabel: String
        get() = getOrCreateVirtualDevice().displayLabel

    var deviceKeyId: String?
        get() = getString(KEY_DEVICE_KEY_ID)
        set(value) = putString(KEY_DEVICE_KEY_ID, value?.trim())

    var deviceKeyScope: String?
        get() = getString(KEY_DEVICE_KEY_SCOPE)
        set(value) = putString(KEY_DEVICE_KEY_SCOPE, value?.trim())

    @Synchronized
    fun getDeviceKeyIdForScope(scope: String): String? {
        val dk = deviceKeyId ?: return null
        val savedScope = deviceKeyScope
        return if (savedScope == scope) dk else null
    }

    @Synchronized
    fun setDeviceKeyIdForScope(scope: String, dk: String) {
        deviceKeyId = dk
        deviceKeyScope = scope
    }

    /**
     * Returns the stored P-256 keypair, validating & upgrading its ASN.1 DER encoding to canonical
     * 91-byte SPKI + 138-byte PKCS#8. If a legacy v2 `BC` keypair was malformed, heals it automatically.
     */
    @Synchronized
    fun getOrCreateDeviceKeyPair(): TimeMoviesCrypto.EcKeyMaterial {
        val priv = getString(KEY_EC_PRIV)
        val pub = getString(KEY_EC_PUB)
        if (!priv.isNullOrBlank() && !pub.isNullOrBlank()) {
            val validated = TimeMoviesCrypto.normalizeOrValidateKeyPair(priv, pub)
            if (validated != null) {
                if (validated.privateKeyPkcs8B64Url != priv || validated.publicKeySpkiB64Url != pub) {
                    putString(KEY_EC_PRIV, validated.privateKeyPkcs8B64Url)
                    putString(KEY_EC_PUB, validated.publicKeySpkiB64Url)
                }
                return validated
            }
            // Stored keypair failed cryptographic validation — clear stale `dk` and heal keypair
            deviceKeyId = null
            deviceKeyScope = null
        }
        val generated = TimeMoviesCrypto.generateP256KeyPair()
        putString(KEY_EC_PRIV, generated.privateKeyPkcs8B64Url)
        putString(KEY_EC_PUB, generated.publicKeySpkiB64Url)
        return generated
    }

    @Synchronized
    fun clearDeviceKey(regenerateKeyPair: Boolean = false) {
        deviceKeyId = null
        deviceKeyScope = null
        if (regenerateKeyPair) {
            val generated = TimeMoviesCrypto.generateP256KeyPair()
            putString(KEY_EC_PRIV, generated.privateKeyPkcs8B64Url)
            putString(KEY_EC_PUB, generated.publicKeySpkiB64Url)
        }
    }

    private fun clearSessionAndDeviceKeys() {
        authToken = null
        pendingOtpToken = null
        pendingOtpEmail = null
        userEmail = null
        profileId = null
        profileName = null
        putString(KEY_PROFILE_MATURITY, null)
        deviceKeyId = null
        deviceKeyScope = null
        putString(KEY_EC_PRIV, null)
        putString(KEY_EC_PUB, null)
    }

    @Synchronized
    fun signOut() {
        authToken = null
        pendingOtpToken = null
        pendingOtpEmail = null
        userEmail = null
        profileId = null
        profileName = null
        putString(KEY_PROFILE_MATURITY, null)
        clearDeviceKey(regenerateKeyPair = true)
    }

    @Synchronized
    internal fun resetAllForTests() {
        unitTestIsolatedMode = true
        appContext = null
        memoryFallback.clear()
        apiBaseUrlOverride = null
        mediaV2BaseUrlOverride = null
        doomBaseUrlOverride = null
        remotePoolUrlsOverride = null
    }

    // ---- JWT Claim Inspection Helper (`AU` Claims) ----

    fun decodeJwtClaims(token: String? = authToken): JSONObject? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val bytes = TimeMoviesCrypto.base64DecodeFlexible(parts[1])
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    fun computeTokenScope(token: String, fallbackDeviceId: String = deviceId): String {
        val claims = decodeJwtClaims(token)
        val userId = claims?.optString("_id", "")?.trim().orEmpty()
        val devClaim = claims?.optString("d", "")?.trim().orEmpty().ifEmpty { fallbackDeviceId }
        return if (userId.isNotEmpty()) {
            "$userId|$devClaim"
        } else {
            TimeMoviesCrypto.sha256Hex(token)
        }
    }

    fun getAccountSummaryAr(): String {
        val tk = authToken
        val poolCount = TimeMoviesPool.countConfiguredSlots(allowNetworkFetch = false)
        val poolSuffix = if (poolCount > 0) {
            "\nمخزن الحسابات التلقائي (Pool): $poolCount حساب نشط للتبديل التلقائي"
        } else {
            ""
        }

        if (tk.isNullOrBlank()) {
            val pendingMail = pendingOtpEmail
            return if (!pendingMail.isNullOrBlank() && !pendingOtpToken.isNullOrBlank()) {
                "حساب شخصي بانتظار التفعيل (OTP): $pendingMail\nالجهاز الافتراضي: $virtualDeviceLabel$poolSuffix"
            } else if (poolCount > 0) {
                "وضع التشغيل التلقائي المفعّل (Pool: $poolCount حساب جاهز بدون تسجيل دخول)\nالجهاز الافتراضي الشخصي: $virtualDeviceLabel"
            } else {
                "غير مسجل الدخول\nالجهاز الافتراضي: $virtualDeviceLabel"
            }
        }
        val claims = decodeJwtClaims(tk)
        val email = userEmail
            ?: claims?.optString("e")?.takeIf { it.isNotBlank() }
            ?: "حساب نشط"
        val isVip = claims?.optBoolean("iv", false) == true
        val fq = claims?.optInt("fq", -1) ?: -1
        val dcr = claims?.optInt("dcr", -1) ?: -1
        val planBadge = if (isVip) "VIP ⭐" else "مجاني"
        val quotaInfo = buildList {
            if (!isVip && fq >= 0) add("مشاهدة: $fq")
            if (!isVip && dcr >= 0) add("تحميل: $dcr")
        }.joinToString(" | ")
        val prof = profileName?.takeIf { it.isNotBlank() }?.let { " — الملف: $it" }.orEmpty()
        val line1 = if (quotaInfo.isNotEmpty()) "$email ($planBadge — $quotaInfo)$prof" else "$email ($planBadge)$prof"
        return "$line1\nالجهاز الافتراضي: $virtualDeviceLabel$poolSuffix"
    }
}
