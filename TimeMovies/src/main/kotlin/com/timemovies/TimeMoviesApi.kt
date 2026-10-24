package com.timemovies

import android.os.Build
import com.timemovies.TimeMoviesModels.ApiEnvelope
import com.timemovies.TimeMoviesModels.CategoryPageResult
import com.timemovies.TimeMoviesModels.GenreItem
import com.timemovies.TimeMoviesModels.GenrePageResult
import com.timemovies.TimeMoviesModels.HomeResponse
import com.timemovies.TimeMoviesModels.MediaGrantPayload
import com.timemovies.TimeMoviesModels.MediaQualityItem
import com.timemovies.TimeMoviesModels.MediaV2Payload
import com.timemovies.TimeMoviesModels.PageFilterResult
import com.timemovies.TimeMoviesModels.SearchResultPage
import com.timemovies.TimeMoviesModels.UserProfile
import com.timemovies.TimeMoviesModels.WorkDetails
import com.timemovies.TimeMoviesModels.optDoubleOrNull
import com.timemovies.TimeMoviesModels.optIntOrNull
import com.timemovies.TimeMoviesModels.optNonBlank
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pure-Kotlin HTTP & authentication client for:
 *  - TimeMovies App API v4.7 (`https://api.apks.lol/api/v4.7/app/`)
 *  - TimeMovies MediaV2 Origin (`https://api.apks.lol/v2/media/`)
 *  - Doom Video Player API v1.6 (`https://api.apks.lol/api/player/v1.6/app/`)
 */
object TimeMoviesApi {

    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    // Endpoint UUIDs from `fx.java` / `ex.java` (`TimeMovies` v1.0.6.5 build 1092)
    const val EP_APP_SETTINGS = "b05a119684b640ea8589ec4134dadefa"
    const val EP_DK_CHALLENGE = "c81d2e64a4d64d8faea7e1abf4cb8c13"
    const val EP_DK_ENROLL = "e4ad21d4b1fc41bca62d94bd972e93f6"
    const val EP_EMAIL_SIGN_IN = "c205267f10be4a52a21ed4e7fa890263"
    const val EP_EMAIL_SIGN_UP = "983fd0915e2841ec914088970dc6f059"
    const val EP_EMAIL_ACTIVATE = "ddcc39a604b14148b9a00112e2e1095f"
    const val EP_REFRESH_TOKEN = "d4f34a2a4f0249bbb3c92a7e45f3e1c2"
    const val EP_GET_PROFILES = "2468218428cc4e65b0e37dc68c950505_placeholder" // overridden below
    const val EP_LIST_PROFILES = "2468218428cc4065b0e37dc68c950505"
    const val EP_CREATE_PROFILE = "83fd64bbed2640be9a849fbe975bad53"
    const val EP_HOME = "6ec4ebc493d342fba913e1fb7c3b1b2a"
    const val EP_PAGE_FILTER = "807f73ed5aec4e3faef1df605238f727"
    const val EP_CATEGORY_WORKS = "f406853b625443cdbfb82ff8c1c35037"
    const val EP_GENRES = "da8f8e755b58479f8e0385d6ea37261a"
    const val EP_GENRE_WORKS = "19863d67d52c43adb578717e8ab90faa"
    const val EP_SEARCH_TRIPLE = "3571075248fa4bb78355e487c9730be7"
    const val EP_WORK_DETAILS = "30df69a88f6e4ac2a7ea8b9797890911"
    const val EP_MEDIA_QUALITIES = "media/qualities"
    const val EP_MEDIA_GRANT = "media/grant"

    @Volatile
    private var clockOffsetMs: Long = 0L

    private val enrollLock = Any()
    private val refreshLock = Any()

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(35, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun appBaseUrl(): String = "${TimeMoviesPrefs.activeApiOrigin}/api/v4.7/app/"
    private fun mediaV2BaseUrl(): String = "${TimeMoviesPrefs.activeApiOrigin}/v2/media/"
    private fun doomPlayerBaseUrl(): String = "${TimeMoviesPrefs.activeApiOrigin}/api/player/v1.6/app/"

    private fun nowServerTimestampMs(): Long = System.currentTimeMillis() + clockOffsetMs

    private fun syncClockFromResponse(response: Response) {
        val dateHeader = response.header("Date") ?: return
        runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val parsed = fmt.parse(dateHeader)?.time ?: return
            clockOffsetMs = parsed - System.currentTimeMillis()
        }
    }

    /**
     * Executes a signed v4.7 API call with automatic ECDSA P-256 device-key enrollment,
     * server clock sync, and 401/403 recovery.
     */
    fun executeV47Request(
        method: String,
        relativePath: String,
        queryParams: Map<String, String?> = emptyMap(),
        jsonBody: String? = null,
        tokenOverride: String? = null,
        allowRetry: Boolean = true,
    ): Pair<Int, String> {
        val base = appBaseUrl().toHttpUrl()
        val urlBuilder = base.newBuilder()
        relativePath.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { seg ->
            urlBuilder.addPathSegment(seg)
        }
        for ((k, v) in queryParams) {
            if (v != null) {
                urlBuilder.addQueryParameter(k, v)
            }
        }
        val url = urlBuilder.build()

        val activeToken = tokenOverride ?: TimeMoviesPrefs.authToken
        if (activeToken.isNotBlank() &&
            TimeMoviesCrypto.shouldAttachDeviceSignature(url, activeToken) &&
            !TimeMoviesCrypto.isExemptFromDeviceVerification(url) &&
            TimeMoviesPrefs.deviceKeyId.isNullOrBlank()
        ) {
            runCatching { ensureDeviceKeyEnrolled(activeToken) }
        }

        val timestamp = nowServerTimestampMs().toString()
        val nonce = UUID.randomUUID().toString()
        val profileId = TimeMoviesPrefs.profileId
        val maturityStr = if (!profileId.isNullOrBlank()) TimeMoviesPrefs.profileMaturity.toString() else null

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("x-b", TimeMoviesCrypto.APP_BUILD)
            .header("ai", TimeMoviesCrypto.APP_ID)
            .header("av", TimeMoviesCrypto.APP_VERSION_ID)
            .header("x-t", activeToken)
            .header("n", nonce)
            .header("t", timestamp)

        if (!profileId.isNullOrBlank()) {
            reqBuilder.header("x-p", profileId)
            reqBuilder.header("m-a", maturityStr.orEmpty())
        }

        val bodyToSend = when (method.uppercase(Locale.US)) {
            "POST", "PUT", "PATCH" -> (jsonBody ?: "{}").also {
                reqBuilder.method(method.uppercase(Locale.US), it.toRequestBody(JSON_MEDIA_TYPE))
            }
            else -> {
                reqBuilder.method(method.uppercase(Locale.US), null)
                null
            }
        }

        val dk = TimeMoviesPrefs.deviceKeyId
        if (activeToken.isNotBlank() &&
            !dk.isNullOrBlank() &&
            TimeMoviesCrypto.shouldAttachDeviceSignature(url, activeToken)
        ) {
            runCatching {
                val kp = TimeMoviesPrefs.getOrCreateEcKeyPair()
                val signed = TimeMoviesCrypto.signV47Request(
                    privateKeyPkcs8B64Url = kp.privateKeyPkcs8B64Url,
                    method = method,
                    url = url,
                    timestamp = timestamp,
                    nonce = nonce,
                    rawBody = bodyToSend,
                    authToken = activeToken,
                    profileId = profileId,
                    maturity = maturityStr,
                    deviceKeyId = dk,
                )
                reqBuilder.header("dk", signed.dk)
                reqBuilder.header("ds", signed.ds)
                reqBuilder.header("da", signed.da)
            }
        }

        val (code, text) = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            syncClockFromResponse(resp)
            resp.code to resp.body?.string().orEmpty()
        }

        if (allowRetry && tokenOverride == null && activeToken.isNotBlank() && !TimeMoviesCrypto.isEnrollmentEndpoint(url.encodedPath)) {
            if (code == 403) {
                // Device key rejected or superseded -> re-enroll once and retry
                TimeMoviesPrefs.resetDeviceKeyEnrollment(regenerateKeyPair = false)
                if (runCatching { ensureDeviceKeyEnrolled(activeToken) }.getOrDefault(false)) {
                    return executeV47Request(method, relativePath, queryParams, jsonBody, null, allowRetry = false)
                }
            } else if (code == 401) {
                // Token expired or device key missing -> try refreshing token & re-enrolling once
                val refreshed = refreshAuthSessionIfPossible()
                if (refreshed) {
                    runCatching { ensureDeviceKeyEnrolled(TimeMoviesPrefs.authToken) }
                    return executeV47Request(method, relativePath, queryParams, jsonBody, null, allowRetry = false)
                }
            }
        }

        return code to text
    }

    /**
     * Enrolls the local NIST P-256 (`secp256r1`) public key with TimeMovies v4.7:
     *  1. `GET c81d2e64a4d64d8faea7e1abf4cb8c13` -> `{cid, ch, exp}`
     *  2. Sign `"$cid\n$ch\n$pk\nECDSA_SHA256_DER"` with `SHA256withECDSA`
     *  3. `POST e4ad21d4b1fc41bca62d94bd972e93f6` -> `{dk, alg, st}`
     */
    fun ensureDeviceKeyEnrolled(token: String = TimeMoviesPrefs.authToken): Boolean {
        if (token.isBlank()) return false
        val existingDk = TimeMoviesPrefs.deviceKeyId
        if (!existingDk.isNullOrBlank()) return true

        synchronized(enrollLock) {
            if (!TimeMoviesPrefs.deviceKeyId.isNullOrBlank()) return true
            val kp = TimeMoviesPrefs.getOrCreateEcKeyPair()

            val (chCode, chBody) = executeV47Request(
                method = "GET",
                relativePath = EP_DK_CHALLENGE,
                tokenOverride = token,
                allowRetry = false,
            )
            if (chCode !in 200..299 || chBody.isBlank()) return false

            val chEnv = JSONObject(chBody)
            val chRes = chEnv.optJSONObject("result") ?: chEnv
            val cid = chRes.optNonBlank("cid") ?: return false
            val ch = chRes.optNonBlank("ch") ?: return false

            val challengeBytes = TimeMoviesCrypto.buildEnrollmentChallengePayload(
                cid = cid,
                challenge = ch,
                publicKeySpkiB64Url = kp.publicKeySpkiB64Url,
            )
            val sig = TimeMoviesCrypto.signEcdsaSha256DerB64Url(
                privateKeyPkcs8B64Url = kp.privateKeyPkcs8B64Url,
                messageBytes = challengeBytes,
            )

            val enrollPayload = JSONObject().apply {
                put("cid", cid)
                put("pk", kp.publicKeySpkiB64Url)
                put("sig", sig)
                put("alg", TimeMoviesCrypto.DEVICE_SIGNATURE_ALGORITHM)
            }.toString()

            val (enCode, enBody) = executeV47Request(
                method = "POST",
                relativePath = EP_DK_ENROLL,
                jsonBody = enrollPayload,
                tokenOverride = token,
                allowRetry = false,
            )
            if (enCode !in 200..299 || enBody.isBlank()) return false

            val enEnv = JSONObject(enBody)
            val enRes = enEnv.optJSONObject("result") ?: enEnv
            val dk = enRes.optNonBlank("dk") ?: return false
            TimeMoviesPrefs.deviceKeyId = dk
            return true
        }
    }

    /**
     * Attempts to refresh an expired JWT session using `RefreshTokenBody(sid, rasplab_install_id)`
     * or falls back to saved Email + Password sign-in if configured.
     */
    fun refreshAuthSessionIfPossible(): Boolean {
        synchronized(refreshLock) {
            val currentToken = TimeMoviesPrefs.authToken
            val claims = TimeMoviesPrefs.decodeJwtClaims(currentToken)
            val sid = claims?.optNonBlank("sid")
            if (!sid.isNullOrBlank()) {
                val payload = JSONObject().apply {
                    put("sid", sid)
                    put("rasplab_install_id", TimeMoviesPrefs.getOrCreateInstallId())
                }.toString()
                val (code, body) = runCatching {
                    executeV47Request(
                        method = "POST",
                        relativePath = EP_REFRESH_TOKEN,
                        jsonBody = payload,
                        tokenOverride = currentToken,
                        allowRetry = false,
                    )
                }.getOrDefault(0 to "")
                if (code in 200..299 && body.isNotBlank()) {
                    val obj = runCatching { JSONObject(body) }.getOrNull()
                    val newToken = obj?.optNonBlank("result")
                    if (obj?.optBoolean("success") == true && !newToken.isNullOrBlank()) {
                        TimeMoviesPrefs.authToken = newToken
                        return true
                    }
                }
            }

            val email = TimeMoviesPrefs.savedEmail
            val pass = TimeMoviesPrefs.savedPassword
            if (email.isNotBlank() && pass.isNotBlank()) {
                val reLogin = runCatching { signInWithEmail(email, pass) }.getOrNull()
                if (reLogin?.success == true) return true
            }
            return false
        }
    }

    /**
     * Builds the realistic `UserDevice` JSON object expected by `UserEmailSingIn` and `UserEmailSignUp`.
     */
    fun buildUserDeviceJson(): JSONObject {
        val brand = runCatching { Build.BRAND.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "Samsung"
        val manufacturer = runCatching { Build.MANUFACTURER.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "samsung"
        val model = runCatching { Build.MODEL.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "SM-S918B"
        val device = runCatching { Build.DEVICE.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "dm3q"
        val product = runCatching { Build.PRODUCT.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "dm3qxxx"
        val board = runCatching { Build.BOARD.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "kalama"
        val hardware = runCatching { Build.HARDWARE.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "qcom"
        val sdkInt = runCatching { Build.VERSION.SDK_INT.takeIf { it > 0 } }.getOrNull() ?: 34
        val release = runCatching { Build.VERSION.RELEASE.takeIf { !it.isNullOrBlank() } }.getOrNull() ?: "14"
        val installId = TimeMoviesPrefs.getOrCreateInstallId()
        val deviceId = TimeMoviesPrefs.getOrCreateAndroidDeviceId()

        return JSONObject().apply {
            put("brand", brand)
            put("build_number", TimeMoviesCrypto.APP_BUILD)
            put("device_abis", "arm64-v8a,armeabi-v7a,armeabi")
            put("device_board", board)
            put("device_bootloader", "S918BXXS6CXFE")
            put("device_fingerprint", "$brand/$product/$device:$release/UP1A.231005.007/S918BXXS6CXFE:user/release-keys")
            put("device_hardware", hardware)
            put("device_host", "SWDD6218")
            put("device_id", deviceId)
            put("device_lang", "ar")
            put("device_manufacturer", manufacturer)
            put("device_model", model)
            put("device_name", model)
            put("device_odm_sku", "")
            put("device_package", "com.extremeqr.proapps")
            put("device_product", product)
            put("device_readable_version", "1.0.6.5")
            put("device_sku", "")
            put("device_soc_manufacturer", "Qualcomm")
            put("device_soc_model", "SM8550")
            put("device_tags", "release-keys")
            put("device_type", "Handset")
            put("device_version", sdkInt.toString())
            put("install_referrer", "utm_source=google-play&utm_medium=organic")
            put("installer_package", "com.android.vending")
            put("name", "$brand $model")
            put("rasplab_install_id", installId)
            put("system_name", "Android")
            put("system_version", release)
            put("total_memory", "128849018880")
            put("total_ram", "8589934592")
            put("unique_id", deviceId)
        }
    }

    // ---- Public App Settings & Auth Methods ----

    fun getAppSettings(): JSONObject? {
        val (code, body) = executeV47Request("GET", EP_APP_SETTINGS, allowRetry = false)
        if (code !in 200..299 || body.isBlank()) return null
        return JSONObject(body).optJSONObject("result")
    }

    fun signInWithEmail(email: String, password: String): ApiEnvelope<String> {
        val cleanEmail = email.trim()
        val payload = JSONObject().apply {
            put("email", cleanEmail)
            put("isEmailError", false)
            put("emailError", JSONObject.NULL)
            put("pass", password)
            put("isPassError", false)
            put("passError", JSONObject.NULL)
            put("device", buildUserDeviceJson())
            put("recheckEmail", false)
        }.toString()

        val (code, body) = executeV47Request(
            method = "POST",
            relativePath = EP_EMAIL_SIGN_IN,
            jsonBody = payload,
            tokenOverride = "",
            allowRetry = false,
        )
        if (body.isBlank()) {
            return ApiEnvelope(false, code, "فشل الاتصال بخادم TimeMovies ($code)", null)
        }
        val obj = JSONObject(body)
        val success = obj.optBoolean("success", false)
        val status = obj.optInt("status", code)
        val message = obj.optNonBlank("message")
        val token = obj.optNonBlank("result")
        val refusal = TimeMoviesModels.parseEmailRefusal(obj.optJSONObject("email_refusal"))

        if (success && !token.isNullOrBlank()) {
            TimeMoviesPrefs.savedEmail = cleanEmail
            TimeMoviesPrefs.savedPassword = password
            completeSessionSetup(token)
        }
        return ApiEnvelope(success, status, message, token, refusal)
    }

    fun signUpWithEmail(
        name: String,
        email: String,
        password: String,
        recheckEmail: Boolean = false,
    ): ApiEnvelope<String> {
        val cleanEmail = email.trim()
        val cleanName = name.trim().ifEmpty { cleanEmail.substringBefore('@').ifEmpty { "User" } }
        val payload = JSONObject().apply {
            put("name", cleanName)
            put("isNameError", false)
            put("nameError", JSONObject.NULL)
            put("email", cleanEmail)
            put("isEmailError", false)
            put("emailError", JSONObject.NULL)
            put("pass", password)
            put("isPassError", false)
            put("passError", JSONObject.NULL)
            put("confirmPass", password)
            put("isConfirmPassError", false)
            put("confirmPassError", JSONObject.NULL)
            put("device", buildUserDeviceJson())
            put("recheckEmail", recheckEmail)
        }.toString()

        val (code, body) = executeV47Request(
            method = "POST",
            relativePath = EP_EMAIL_SIGN_UP,
            jsonBody = payload,
            tokenOverride = "",
            allowRetry = false,
        )
        if (body.isBlank()) {
            return ApiEnvelope(false, code, "فشل الاتصال بخادم TimeMovies ($code)", null)
        }
        val obj = JSONObject(body)
        val success = obj.optBoolean("success", false)
        val status = obj.optInt("status", code)
        val message = obj.optNonBlank("message")
        val token = obj.optNonBlank("result")
        val refusal = TimeMoviesModels.parseEmailRefusal(obj.optJSONObject("email_refusal"))

        if (success && !token.isNullOrBlank()) {
            TimeMoviesPrefs.savedEmail = cleanEmail
            TimeMoviesPrefs.savedPassword = password
            val claims = TimeMoviesPrefs.decodeJwtClaims(token)
            val isActivated = claims?.optBoolean("ia", false) == true || claims?.optBoolean("is_active", false) == true
            if (isActivated) {
                completeSessionSetup(token)
            } else {
                TimeMoviesPrefs.pendingOtpToken = token
                runCatching { ensureDeviceKeyEnrolled(token) }
            }
        }
        return ApiEnvelope(success, status, message, token, refusal)
    }

    fun activateEmailOtp(otpCode: String): ApiEnvelope<String> {
        val pendingToken = TimeMoviesPrefs.pendingOtpToken.ifBlank { TimeMoviesPrefs.authToken }
        if (pendingToken.isBlank()) {
            return ApiEnvelope(false, 400, "لا يوجد تسجيل قيد التفعيل، يرجى إنشاء الحساب أولاً", null)
        }
        runCatching { ensureDeviceKeyEnrolled(pendingToken) }
        val payload = JSONObject().apply {
            put("otp", otpCode.trim())
            put("isOtpError", false)
            put("otpError", JSONObject.NULL)
        }.toString()

        val (code, body) = executeV47Request(
            method = "POST",
            relativePath = EP_EMAIL_ACTIVATE,
            jsonBody = payload,
            tokenOverride = pendingToken,
            allowRetry = false,
        )
        if (body.isBlank()) {
            return ApiEnvelope(false, code, "فشل الاتصال بالخادم ($code)", null)
        }
        val obj = JSONObject(body)
        val success = obj.optBoolean("success", false)
        val status = obj.optInt("status", code)
        val message = obj.optNonBlank("message")
        val activatedToken = obj.optNonBlank("result") ?: if (success) pendingToken else null

        if (success && !activatedToken.isNullOrBlank()) {
            TimeMoviesPrefs.pendingOtpToken = ""
            completeSessionSetup(activatedToken)
        }
        return ApiEnvelope(success, status, message, activatedToken)
    }

    fun applyManualToken(rawToken: String): Pair<Boolean, String> {
        val clean = rawToken.trim().removePrefix("Bearer ").trim()
        if (clean.count { it == '.' } < 2) {
            return false to "صيغة التوكن غير صالحة (يجب أن يكون JWT مكوناً من 3 أجزاء)"
        }
        completeSessionSetup(clean)
        val profiles = runCatching { fetchProfiles() }.getOrDefault(emptyList())
        return if (profiles.isNotEmpty() || !TimeMoviesPrefs.deviceKeyId.isNullOrBlank()) {
            true to "تم تفعيل التوكن وربط مفتاح الجهاز بنجاح"
        } else {
            true to "تم حفظ التوكن (تحقق من صلاحيته إذا لم يظهر المحتوى)"
        }
    }

    private fun completeSessionSetup(token: String) {
        TimeMoviesPrefs.authToken = token
        TimeMoviesPrefs.resetDeviceKeyEnrollment(regenerateKeyPair = false)
        runCatching { ensureDeviceKeyEnrolled(token) }
        runCatching { ensureActiveProfile() }
    }

    fun fetchProfiles(): List<UserProfile> {
        val (code, body) = executeV47Request("GET", EP_LIST_PROFILES)
        if (code !in 200..299 || body.isBlank()) return emptyList()
        val arr = JSONObject(body).optJSONArray("result") ?: return emptyList()
        val out = ArrayList<UserProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val pObj = arr.optJSONObject(i) ?: continue
            TimeMoviesModels.parseUserProfile(pObj)?.let { out.add(it) }
        }
        return out
    }

    fun ensureActiveProfile(): UserProfile? {
        val profiles = fetchProfiles()
        val currentId = TimeMoviesPrefs.profileId
        if (!currentId.isNullOrBlank()) {
            val matched = profiles.firstOrNull { it.id == currentId }
            if (matched != null) {
                TimeMoviesPrefs.profileName = matched.name
                TimeMoviesPrefs.profileMaturity = matched.maturity
                return matched
            }
        }
        val chosen = profiles.firstOrNull { !it.isPinSet && !it.isVipLocked && it.isMain }
            ?: profiles.firstOrNull { !it.isPinSet && !it.isVipLocked }
            ?: profiles.firstOrNull()
        if (chosen != null) {
            TimeMoviesPrefs.profileId = chosen.id
            TimeMoviesPrefs.profileName = chosen.name
            TimeMoviesPrefs.profileMaturity = chosen.maturity
            return chosen
        }

        // Create default profile (`USPE`) if newly registered account has none
        val createPayload = JSONObject().apply {
            put("name", "Main")
            put("isNameError", false)
            put("is_main", true)
            put("is_pin_set", false)
            put("is_vip_locked", false)
            put("maturity", 5)
            put("lang", "ar")
            put("pic", 1)
            put("pf", 0)
            put("pr", 0)
            put("season_order", 0)
            put("episode_order", 0)
            put("default_stream_quality", "auto")
            put("default_download_quality", "720")
            put("is_auto_play_next_episode", true)
            put("is_episode_thumb", true)
            put("is_download_wifi_only", false)
            put("is_download_completed_notification", true)
            put("is_download_completed_notification_sound", false)
            put("subtitle_language", "ar")
            put("subtitle_text_size", 18)
            put("subtitle_text_color", -1)
            put("subtitle_background_color", 0)
            put("subtitle_window_color", 0)
            put("subtitle_text_edge_style", 1)
            put("subtitle_text_edge_color", -16777216)
            put("subtitle_is_background_transparent", true)
            put("subtitle_is_window_color_transparent", true)
            put("subtitle_bottom_padding", 16.0)
        }.toString()

        val (cCode, cBody) = executeV47Request("POST", EP_CREATE_PROFILE, jsonBody = createPayload)
        if (cCode in 200..299 && cBody.isNotBlank()) {
            val resObj = JSONObject(cBody).optJSONObject("result")
            val created = resObj?.let { TimeMoviesModels.parseUserProfile(it) }
            if (created != null) {
                TimeMoviesPrefs.profileId = created.id
                TimeMoviesPrefs.profileName = created.name
                TimeMoviesPrefs.profileMaturity = created.maturity
                return created
            }
        }
        return fetchProfiles().firstOrNull()?.also {
            TimeMoviesPrefs.profileId = it.id
            TimeMoviesPrefs.profileName = it.name
            TimeMoviesPrefs.profileMaturity = it.maturity
        }
    }

    // ---- Catalog, Filter, Category, Genre, Search & Details ----

    fun getHome(): HomeResponse? {
        if (TimeMoviesPrefs.isSignedIn && TimeMoviesPrefs.profileId.isNullOrBlank()) {
            runCatching { ensureActiveProfile() }
        }
        val (code, body) = executeV47Request("GET", EP_HOME)
        if (code !in 200..299 || body.isBlank()) return null
        val env = JSONObject(body)
        if (!env.optBoolean("success", false)) return null
        val res = env.optJSONObject("result") ?: return null
        return TimeMoviesModels.parseHomeResponse(res)
    }

    fun getPageFilter(level: Int, page0Based: Int): PageFilterResult? {
        val (code, body) = executeV47Request(
            method = "GET",
            relativePath = EP_PAGE_FILTER,
            queryParams = mapOf(
                "page" to page0Based.toString(),
                "level" to level.toString(),
            ),
        )
        if (code !in 200..299 || body.isBlank()) return null
        val env = JSONObject(body)
        val res = env.optJSONObject("result") ?: return null
        return PageFilterResult(
            works = TimeMoviesModels.parseWorkCardList(res.optJSONArray("works")),
            page = res.optInt("page", page0Based),
            isLast = res.optBoolean("is_last", true),
        )
    }

    fun getCategoryPage(catId: String, lastId: String?, lastVal: String?): CategoryPageResult? {
        val (code, body) = executeV47Request(
            method = "GET",
            relativePath = EP_CATEGORY_WORKS,
            queryParams = mapOf(
                "catId" to catId,
                "li" to lastId,
                "lv" to lastVal,
            ),
        )
        if (code !in 200..299 || body.isBlank()) return null
        val res = JSONObject(body).optJSONObject("result") ?: return null
        return CategoryPageResult(
            works = TimeMoviesModels.parseWorkCardList(res.optJSONArray("works")),
            lastId = res.optNonBlank("last_id"),
            lastVal = res.optNonBlank("last_val"),
            isLast = res.optBoolean("is_last", true),
        )
    }

    fun getGenres(): List<GenreItem> {
        val (code, body) = executeV47Request("GET", EP_GENRES)
        if (code !in 200..299 || body.isBlank()) return emptyList()
        val arr = JSONObject(body).optJSONArray("result") ?: return emptyList()
        val out = ArrayList<GenreItem>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val id = g.optNonBlank("_id") ?: continue
            val nameEn = g.optNonBlank("n") ?: continue
            out.add(
                GenreItem(
                    id = id,
                    nameEn = nameEn,
                    nameAr = g.optNonBlank("nr"),
                    code = g.optNonBlank("cd"),
                )
            )
        }
        return out
    }

    fun getGenreWorks(
        genreId: String,
        isMovie: Boolean,
        lastAt: String? = null,
        lastId: String? = null,
        sort: Int = 0,
        sortDir: Int = 1,
    ): GenrePageResult? {
        val (code, body) = executeV47Request(
            method = "GET",
            relativePath = EP_GENRE_WORKS,
            queryParams = mapOf(
                "gId" to genreId,
                "ism" to isMovie.toString(),
                "la" to lastAt,
                "li" to lastId,
                "s" to sort.toString(),
                "sd" to sortDir.toString(),
            ),
        )
        if (code !in 200..299 || body.isBlank()) return null
        val res = JSONObject(body).optJSONObject("result") ?: return null
        return GenrePageResult(
            works = TimeMoviesModels.parseWorkCardList(res.optJSONArray("works")),
            lastAt = res.optNonBlank("last_at"),
            lastId = res.optNonBlank("last_id"),
            isLast = res.optBoolean("is_last", true),
        )
    }

    fun searchTriple(query: String, page0Based: Int = 0): SearchResultPage? {
        val (code, body) = executeV47Request(
            method = "GET",
            relativePath = EP_SEARCH_TRIPLE,
            queryParams = mapOf(
                "search" to query.trim(),
                "page" to page0Based.toString(),
            ),
        )
        if (code !in 200..299 || body.isBlank()) return null
        val res = JSONObject(body).optJSONObject("result") ?: return null
        val movies = TimeMoviesModels.parseWorkCardList(res.optJSONArray("movies"))
            .map { if (it.isMovie == null) it.copy(isMovie = true) else it }
        val series = TimeMoviesModels.parseWorkCardList(res.optJSONArray("series"))
            .map { if (it.isMovie == null) it.copy(isMovie = false) else it }
        return SearchResultPage(
            movies = movies,
            series = series,
            page = res.optInt("page", page0Based),
            isLastMovies = res.optBoolean("is_last_movies", true),
            isLastSeries = res.optBoolean("is_last_series", true),
        )
    }

    fun getWorkDetails(workId: String): WorkDetails? {
        if (TimeMoviesPrefs.isSignedIn) {
            val (code, body) = executeV47Request(
                method = "GET",
                relativePath = EP_WORK_DETAILS,
                queryParams = mapOf("wk" to workId),
            )
            if (code in 200..299 && body.isNotBlank()) {
                val res = JSONObject(body).optJSONObject("result")
                if (res != null) {
                    TimeMoviesModels.parseWorkDetails(res)?.let { return it }
                }
            }
        }
        // Fallback: DoomVideoPlayer's unauthenticated `apw?wk=` work metadata endpoint
        return getDoomPlayerWorkFallback(workId)
    }

    fun getDoomPlayerWorkFallback(workId: String): WorkDetails? {
        val url = "${doomPlayerBaseUrl()}apw".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("wk", workId)
            ?.build() ?: return null
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("x-b", TimeMoviesCrypto.DOOM_PLAYER_BUILD)
            .header("ai", TimeMoviesCrypto.DOOM_PLAYER_APP_ID)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return null
                val res = JSONObject(text).optJSONObject("result") ?: return null
                TimeMoviesModels.parseWorkDetails(res)
            }
        }.getOrNull()
    }

    // ---- Media V2 & Doom Video Player Resolution ----

    fun getMediaQualities(fileCode: String, action: String = "download"): List<MediaQualityItem> {
        val payload = JSONObject().apply {
            put("action", action)
            put("file_code", fileCode)
            put("surface", 1)
        }.toString()
        val (code, body) = executeV47Request("POST", EP_MEDIA_QUALITIES, jsonBody = payload)
        if (code !in 200..299 || body.isBlank()) return emptyList()
        val obj = JSONObject(body)
        val res = obj.optJSONObject("result") ?: obj
        val qArr: JSONArray = res.optJSONArray("qualities") ?: return emptyList()
        val out = ArrayList<MediaQualityItem>(qArr.length())
        for (i in 0 until qArr.length()) {
            val row = qArr.optJSONObject(i) ?: continue
            val q = row.optDoubleOrNull("quality")?.toInt() ?: row.optIntOrNull("quality") ?: continue
            out.add(
                MediaQualityItem(
                    quality = q,
                    size = row.optNonBlank("size"),
                    locked = row.optBoolean("locked", false),
                )
            )
        }
        return out
    }

    /**
     * Requests a media playback or download grant (`POST /api/v4.7/app/media/grant`),
     * matching `at5.java` (`surface = 1`, `package = "com.extremeqr.proapps"`).
     */
    fun requestMediaGrant(
        action: String,
        fileCode: String,
        workId: String,
        episodeId: String? = null,
        seasonId: String? = null,
        executorTarget: String? = null,
        playerInstalled: Boolean? = true,
        attemptKey: String = UUID.randomUUID().toString(),
    ): MediaGrantPayload? {
        val payload = JSONObject().apply {
            put("action", action)
            put("surface", 1)
            put("package", "com.extremeqr.proapps")
            put("file_code", fileCode)
            put("work_id", workId)
            if (!episodeId.isNullOrBlank()) put("episode_id", episodeId)
            if (!seasonId.isNullOrBlank()) put("season_id", seasonId)
            if (!executorTarget.isNullOrBlank()) put("executor_target", executorTarget)
            if (playerInstalled != null) put("player_installed", playerInstalled)
            put("client_attempt_key", attemptKey)
        }.toString()

        val (code, body) = executeV47Request("POST", EP_MEDIA_GRANT, jsonBody = payload)
        if (body.isBlank()) return null
        return runCatching {
            val obj = JSONObject(body)
            val unwrapped = obj.optJSONObject("result") ?: obj
            TimeMoviesModels.parseMediaGrant(unwrapped)
        }.getOrNull()?.takeIf { code in 200..499 }
    }

    /**
     * Exchanges a `grant` token at `<origin>/v2/media/{manifest|download|season-download|cast}`
     * using `X-Media-Grant`, matching `ex.D(url, grant)` in `ex.java`.
     */
    fun fetchOriginMedia(action: String, grantToken: String): MediaV2Payload? {
        val subPath = when (action) {
            "download" -> "download"
            "season_download" -> "season-download"
            "cast" -> "cast"
            else -> "manifest"
        }
        val url = "${mediaV2BaseUrl()}$subPath"
        val req = Request.Builder()
            .url(url)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("X-Media-Grant", grantToken)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return null
                val obj = JSONObject(text)
                TimeMoviesModels.parseMediaV2(obj.optJSONObject("result") ?: obj)
            }
        }.getOrNull()
    }

    /**
     * Resolves an encrypted Doom Video Player playback ticket (`resolve-play`) -> Master HLS `.m3u8` URL.
     */
    fun resolveDoomPlay(srcTicket: String): String? = resolveDoomEndpoint("resolve-play", srcTicket)

    /**
     * Resolves an encrypted Doom Video Player download ticket (`resolve-download`) -> Direct MP4/MKV URL.
     */
    fun resolveDoomDownload(srcTicket: String): String? = resolveDoomEndpoint("resolve-download", srcTicket)

    private fun resolveDoomEndpoint(endpoint: String, srcTicket: String): String? {
        if (srcTicket.isBlank()) return null
        if (srcTicket.startsWith("http://", ignoreCase = true) || srcTicket.startsWith("https://", ignoreCase = true)) {
            return srcTicket
        }
        val url = "${doomPlayerBaseUrl()}$endpoint"
        val body = JSONObject().apply { put("src", srcTicket) }.toString()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("x-b", TimeMoviesCrypto.DOOM_PLAYER_BUILD)
            .header("ai", TimeMoviesCrypto.DOOM_PLAYER_APP_ID)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return null
                val obj = JSONObject(text)
                if (!obj.optBoolean("success", false)) return null
                obj.optJSONObject("result")?.optNonBlank("url")
            }
        }.getOrNull()
    }
}
