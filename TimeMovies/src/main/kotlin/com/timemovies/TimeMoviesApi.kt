package com.timemovies

import com.timemovies.TimeMoviesModels.ApiEnvelope
import com.timemovies.TimeMoviesModels.CategoryPageResult
import com.timemovies.TimeMoviesModels.GenreItem
import com.timemovies.TimeMoviesModels.GenrePageResult
import com.timemovies.TimeMoviesModels.HomeResponse
import com.timemovies.TimeMoviesModels.MediaGrantPayload
import com.timemovies.TimeMoviesModels.MediaV2Payload
import com.timemovies.TimeMoviesModels.PageFilterResult
import com.timemovies.TimeMoviesModels.SearchResultPage
import com.timemovies.TimeMoviesModels.UserProfile
import com.timemovies.TimeMoviesModels.WorkCard
import com.timemovies.TimeMoviesModels.WorkDetails
import com.timemovies.TimeMoviesModels.optNonBlank
import com.timemovies.TimeMoviesModels.parseEmailRefusal
import com.timemovies.TimeMoviesModels.parseHomeResponse
import com.timemovies.TimeMoviesModels.parseMediaGrant
import com.timemovies.TimeMoviesModels.parseMediaV2
import com.timemovies.TimeMoviesModels.parseUserProfile
import com.timemovies.TimeMoviesModels.parseWorkCardList
import com.timemovies.TimeMoviesModels.parseWorkDetails
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Kotlin HTTP client for TimeMovies v4.7 (`api.apks.lol`) & DoomVideoPlayer v1.6 (`1065` / `1126`).
 *
 * Implements:
 * - Proactive + response-driven server clock synchronization (`Date` HTTP header + persisted offset)
 * - Token-scoped NIST P-256 ECDSA challenge-response device key enrollment (`c81d2e...` -> `e4ad21...`)
 * - 13-line canonical request signing (`dk`, `ds`, `da = ECDSA_SHA256_DER`)
 * - 100% synthetic retail handset `UserDevice` telemetry (device-ban bypass; zero `Build.*` reads)
 * - Automatic 401/403 clock/key re-sync & `RefreshTokenBody` session refresh
 * - MediaV2 grant + `GET /v2/media/{manifest|download|cast}` + DoomVideoPlayer v1.6/v1.5 resolvers
 */
object TimeMoviesApi {

    // ---- Obfuscated v4.7 Endpoint Paths (`fx.java`) ----
    const val PATH_APP_SETTINGS = "b05a119684b640ea8589ec4134dadefa"          // GET -> AST
    const val PATH_APP_UPDATES = "d638de3fc27049219b82f9d54ed09d97"           // GET -> List<AppUpdate>
    const val PATH_APP_DATA = "546635c490224ed1a74961d8e06198d1"              // POST -> AppData
    const val PATH_DEVICE_CHALLENGE = "c81d2e64a4d64d8faea7e1abf4cb8c13"      // GET -> DeviceKeyChallengeDto
    const val PATH_DEVICE_REGISTER = "e4ad21d4b1fc41bca62d94bd972e93f6"       // POST -> DeviceKeyRegisterDto
    const val PATH_EMAIL_SIGN_IN = "c205267f10be4a52a21ed4e7fa890263"         // POST -> JWT String
    const val PATH_EMAIL_SIGN_UP = "983fd0915e2841ec914088970dc6f059"         // POST -> Pending JWT String
    const val PATH_EMAIL_ACTIVATE_OTP = "ddcc39a604b14148b9a00112e2e1095f"    // POST -> Activated JWT / status
    const val PATH_EMAIL_FORGOT_OR_RESEND = "2b1ea8f4c8174afb90d75f29e38c1ab3" // POST -> Resend/Reset OTP
    const val PATH_REFRESH_TOKEN = "d4f34a2a4f0249bbb3c92a7e45f3e1c2"         // POST -> New JWT String
    const val PATH_USER_PROFILES = "bf6a2c9a68b24d78ba836f9fdcb5cb05"         // GET -> List<USP>
    const val PATH_HOME = "6ec4ebc493d342fba913e1fb7c3b1b2a"                  // GET -> Home
    const val PATH_PAGE_MOVIES = "912f55579bb14bc1903647589dcac5d2"           // POST -> PageFilterResult
    const val PATH_PAGE_SERIES = "993c336270e94ac4ae1c2920612d393f"           // POST -> PageFilterResult
    const val PATH_CATEGORY_MORE = "54fa9550c12648f2ae032a279bf891ca"         // GET -> HomeMore
    const val PATH_GENRES_LIST = "919d2b5785e74f6bb35d520aa25b6e01"           // GET -> List<GN>
    const val PATH_GENRE_WORKS = "e1727b4b8b824756ade7ed299c0e6c78"           // POST -> GenreResult
    const val PATH_SEARCH = "3571075248fa4bb78355e487c9730be7"                // GET -> SearchResult
    const val PATH_WORK_DETAILS = "30df69a88f6e4ac2a7ea8b9797890911"          // GET ?wk=<id> -> WKI
    const val PATH_MEDIA_GRANT = "media/grant"                                // POST -> MediaGrantResponse

    // DoomVideoPlayer v1.6 + v1.5 paths (`ApiPath.java`)
    private const val DOOM_RESOLVE_PLAY = "resolve-play"
    private const val DOOM_RESOLVE_DOWNLOAD = "resolve-download"
    private const val DOOM_LEGACY_PLAY = "8cf0b2fa26d74fcbb3f08c4f1907a1bc"
    private const val DOOM_LEGACY_DOWNLOAD = "5d18c7a3f0414e6b9c210e9a78d5f4a2"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val clockSyncedInProcess = AtomicBoolean(false)
    private val enrollmentLock = Any()

    @Volatile
    var lastMediaRefusalReason: String? = null

    val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    internal fun resetRuntimeStateForTests() {
        clockSyncedInProcess.set(false)
        lastMediaRefusalReason = null
    }

    fun apiBaseUrl(): String = TimeMoviesPrefs.apiBaseUrlOverride ?: TimeMoviesCrypto.API_BASE_URL

    fun mediaV2BaseUrl(): String {
        TimeMoviesPrefs.mediaV2BaseUrlOverride?.let { return it }
        val apiBase = apiBaseUrl()
        val root = apiBase.substringBefore("/api/")
        return "${root.trimEnd('/')}/v2/media/"
    }

    fun doomBaseUrl(): String = TimeMoviesPrefs.doomBaseUrlOverride ?: TimeMoviesCrypto.DOOM_BASE_URL

    private fun updateClockFromResponse(response: Response) {
        val dateHeader = response.header("Date") ?: return
        val parsedMs = parseHttpDateMs(dateHeader) ?: return
        val offset = parsedMs - System.currentTimeMillis()
        TimeMoviesPrefs.clockOffsetMs = offset
        clockSyncedInProcess.set(true)
    }

    private fun parseHttpDateMs(dateStr: String): Long? {
        val patterns = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val parsed = sdf.parse(dateStr.trim())
                if (parsed != null) return parsed.time
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun serverNowMs(): Long = System.currentTimeMillis() + TimeMoviesPrefs.clockOffsetMs

    /**
     * Proactively synchronizes server time via `GET b05a119684b640ea8589ec4134dadefa` (`AST`)
     * so the very first enrollment/OTP/grant signature uses exact server epoch milliseconds.
     */
    fun syncServerClockIfNeeded(force: Boolean = false) {
        if (!force && clockSyncedInProcess.get()) return
        runCatching {
            val url = "${apiBaseUrl()}$PATH_APP_SETTINGS"
            val timestamp = serverNowMs().toString()
            val nonce = UUID.randomUUID().toString()
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "okhttp/4.12.0")
                .header("Accept", "application/json")
                .header("x-b", TimeMoviesCrypto.APP_BUILD)
                .header("ai", TimeMoviesCrypto.APP_ID)
                .header("av", TimeMoviesCrypto.APP_VERSION_TOKEN)
                .header("x-t", "")
                .header("n", nonce)
                .header("t", timestamp)
                .build()
            plainClient.newCall(req).execute().use { resp ->
                updateClockFromResponse(resp)
            }
        }
    }

    fun looksLikeJwt(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val trimmed = candidate.trim()
        return trimmed.startsWith("eyJ") && trimmed.count { it == '.' } >= 2
    }

    fun normalizeOtpCode(rawOtp: String): String {
        val sb = StringBuilder(rawOtp.length)
        for (ch in rawOtp) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
                ' ', '-', '\t', '\n', '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    // ---- Core Signed v4.7 Request Executor (`q52.java` + `V47DeviceSigningInterceptor`) ----

    private data class RawHttpResult(
        val code: Int,
        val body: String,
    )

    private fun isVerificationOrKeyFailure(code: Int, body: String): Boolean {
        if (code == 401 || code == 403) return true
        val lower = body.lowercase(Locale.US)
        return lower.contains("request verification failed") ||
            lower.contains("verification failed") ||
            lower.contains("device key") ||
            lower.contains("invalid signature")
    }

    private fun executeV47Request(
        url: String,
        method: String = "GET",
        rawJsonBody: String? = null,
        tokenOverride: String? = null,
        noAuth: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
        allowRetry: Boolean = true,
    ): RawHttpResult {
        val httpUrl = url.toHttpUrl()
        val tk = when {
            noAuth -> ""
            tokenOverride != null -> tokenOverride
            else -> TimeMoviesPrefs.authToken.orEmpty()
        }

        // CRITICAL (`q52.java` + OTP flow):
        // Attach `x-p` and `m-a` ONLY when authenticating with the active signed-in session token!
        // Never attach stale profile headers to an unactivated `pendingOtpToken` (`tokenOverride`).
        val isActiveSessionToken = !noAuth &&
            tk.isNotEmpty() &&
            (tokenOverride == null || tokenOverride == TimeMoviesPrefs.authToken)

        val profileId = if (isActiveSessionToken) TimeMoviesPrefs.profileId.orEmpty() else ""
        val maturity = if (isActiveSessionToken && profileId.isNotEmpty()) {
            TimeMoviesPrefs.profileMaturity.toString()
        } else {
            ""
        }

        val mustSign = TimeMoviesCrypto.shouldAttachDeviceSignature(httpUrl, tk)
        var dk: String? = null
        if (mustSign) {
            syncServerClockIfNeeded(force = false)
            dk = ensureDeviceKeyEnrolled(tk)
        }

        // Compute `timestamp` AFTER clock sync / enrollment so `t` matches server `Date` within milliseconds
        val timestamp = serverNowMs().toString()
        val nonce = UUID.randomUUID().toString()

        val reqBuilder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("x-b", TimeMoviesCrypto.APP_BUILD)
            .header("ai", TimeMoviesCrypto.APP_ID)
            .header("av", TimeMoviesCrypto.APP_VERSION_TOKEN)
            .header("x-t", tk)
            .header("n", nonce)
            .header("t", timestamp)

        if (profileId.isNotEmpty()) {
            reqBuilder.header("x-p", profileId)
            reqBuilder.header("m-a", maturity)
        }

        for ((k, v) in extraHeaders) {
            reqBuilder.header(k, v)
        }

        if (mustSign && !dk.isNullOrBlank()) {
            val keyPair = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
            val sigHeaders = TimeMoviesCrypto.signV47Request(
                privateKeyPkcs8B64Url = keyPair.privateKeyPkcs8B64Url,
                method = method,
                url = httpUrl,
                timestamp = timestamp,
                nonce = nonce,
                rawBody = rawJsonBody,
                authToken = tk,
                profileId = profileId,
                maturity = maturity,
                deviceKeyId = dk,
            )
            reqBuilder.header("dk", sigHeaders.dk)
            reqBuilder.header("ds", sigHeaders.ds)
            reqBuilder.header("da", sigHeaders.da)
        }

        val upperMethod = method.uppercase(Locale.US)
        if (upperMethod == "GET") {
            reqBuilder.get()
        } else {
            val bodyPayload = rawJsonBody ?: "{}"
            reqBuilder.method(upperMethod, bodyPayload.toRequestBody(JSON_MEDIA_TYPE))
        }

        val response = plainClient.newCall(reqBuilder.build()).execute()
        val code = response.code
        val respBody = response.use { resp ->
            updateClockFromResponse(resp)
            resp.body?.string().orEmpty()
        }

        // Automatic clock-skew / stale-device-key / expired-JWT recovery
        if (allowRetry && tk.isNotEmpty() && !TimeMoviesCrypto.isExemptFromDeviceVerification(httpUrl)) {
            if (isVerificationOrKeyFailure(code, respBody)) {
                val lowerBody = respBody.lowercase(Locale.US)
                val regenerateKeys = lowerBody.contains("device key") || lowerBody.contains("invalid key") || code == 403
                TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = regenerateKeys)
                syncServerClockIfNeeded(force = true)

                // If using the active session token and verification still fails or token expired, refresh JWT if needed
                val retried = executeV47Request(
                    url = url,
                    method = method,
                    rawJsonBody = rawJsonBody,
                    tokenOverride = tokenOverride,
                    noAuth = noAuth,
                    extraHeaders = extraHeaders,
                    allowRetry = false,
                )
                if ((retried.code == 401 || retried.code == 403) && tokenOverride == null && refreshAuthSessionIfPossible()) {
                    return executeV47Request(
                        url = url,
                        method = method,
                        rawJsonBody = rawJsonBody,
                        tokenOverride = null,
                        noAuth = noAuth,
                        extraHeaders = extraHeaders,
                        allowRetry = false,
                    )
                }
                return retried
            }
        }

        return RawHttpResult(code = code, body = respBody)
    }

    /**
     * Enrolls a NIST P-256 device public key bound to `tokenScope` (`"${_id}|${deviceId}"`):
     * 1. `GET /c81d2e64a4d64d8faea7e1abf4cb8c13` -> `{cid, ch, exp}`
     * 2. Sign `"$cid\n$ch\n$pk\nECDSA_SHA256_DER"` with `SHA256withECDSA` (DER Base64URL no-pad)
     * 3. `POST /e4ad21d4b1fc41bca62d94bd972e93f6` -> `{dk, alg, st}`
     *
     * Retries once with forced server clock sync + fresh keypair if the first attempt fails.
     */
    fun ensureDeviceKeyEnrolled(authToken: String): String? {
        if (authToken.isBlank()) return null
        val scope = TimeMoviesPrefs.computeTokenScope(authToken)
        TimeMoviesPrefs.getDeviceKeyIdForScope(scope)?.let { return it }

        synchronized(enrollmentLock) {
            TimeMoviesPrefs.getDeviceKeyIdForScope(scope)?.let { return it }

            enrollDeviceKeyOnce(authToken, scope)?.let { return it }

            // Retry once after forcing server clock sync & rotating ECDSA keypair
            syncServerClockIfNeeded(force = true)
            TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = true)
            return enrollDeviceKeyOnce(authToken, scope)
        }
    }

    private fun enrollDeviceKeyOnce(authToken: String, scope: String): String? = runCatching {
        val keyPair = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        val challengeUrl = "${apiBaseUrl()}$PATH_DEVICE_CHALLENGE"
        val chRes = executeV47Request(
            url = challengeUrl,
            method = "GET",
            tokenOverride = authToken,
            allowRetry = false,
        )
        if (chRes.code !in 200..299 || chRes.body.isBlank()) return null

        val chEnvelope = JSONObject(chRes.body)
        val chObj = chEnvelope.optJSONObject("result") ?: chEnvelope
        val cid = chObj.optNonBlank("cid") ?: return null
        val ch = chObj.optNonBlank("ch") ?: return null

        val challengePayload = TimeMoviesCrypto.buildEnrollmentChallengePayload(
            cid = cid,
            challenge = ch,
            publicKeySpkiB64Url = keyPair.publicKeySpkiB64Url,
        )
        val sigB64Url = TimeMoviesCrypto.signEcdsaSha256DerB64Url(
            privateKeyPkcs8B64Url = keyPair.privateKeyPkcs8B64Url,
            messageBytes = challengePayload,
        )

        val regJson = JSONObject().apply {
            put("cid", cid)
            put("pk", keyPair.publicKeySpkiB64Url)
            put("sig", sigB64Url)
            put("alg", TimeMoviesCrypto.SIGNING_ALGORITHM)
        }.toString()

        val regUrl = "${apiBaseUrl()}$PATH_DEVICE_REGISTER"
        val regRes = executeV47Request(
            url = regUrl,
            method = "POST",
            rawJsonBody = regJson,
            tokenOverride = authToken,
            allowRetry = false,
        )
        if (regRes.code !in 200..299 || regRes.body.isBlank()) return null

        val regEnvelope = JSONObject(regRes.body)
        val regObj = regEnvelope.optJSONObject("result") ?: regEnvelope
        val dk = regObj.optNonBlank("dk") ?: return null
        TimeMoviesPrefs.setDeviceKeyIdForScope(scope, dk)
        dk
    }.getOrNull()

    /**
     * Refreshes an expired JWT using exact `RefreshTokenBody` (`k0.java` / `RefreshTokenBody.java`):
     * `{"rasplab_install_id": "<32-hex>"}`.
     */
    private fun refreshAuthSessionIfPossible(): Boolean {
        val currentToken = TimeMoviesPrefs.authToken ?: return false
        return runCatching {
            val bodyJson = JSONObject().apply {
                put("rasplab_install_id", TimeMoviesPrefs.rasplabInstallId)
            }.toString()

            val res = executeV47Request(
                url = "${apiBaseUrl()}$PATH_REFRESH_TOKEN",
                method = "POST",
                rawJsonBody = bodyJson,
                tokenOverride = currentToken,
                allowRetry = false,
            )
            if (res.code !in 200..299 || res.body.isBlank()) return false
            val obj = JSONObject(res.body)
            val newToken = obj.optNonBlank("result") ?: return false
            if (looksLikeJwt(newToken)) {
                val oldScope = TimeMoviesPrefs.computeTokenScope(currentToken)
                val newScope = TimeMoviesPrefs.computeTokenScope(newToken)
                val existingDk = TimeMoviesPrefs.getDeviceKeyIdForScope(oldScope)
                TimeMoviesPrefs.authToken = newToken
                if (existingDk != null && oldScope == newScope) {
                    TimeMoviesPrefs.setDeviceKeyIdForScope(newScope, existingDk)
                } else {
                    TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
                }
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    // ---- Synthetic Retail Handset Telemetry (`UserDevice.java` — Device-Ban Bypass) ----

    fun buildUserDeviceJson(): JSONObject {
        return TimeMoviesPrefs.getOrCreateVirtualDevice().toApiUserDeviceJson()
    }

    // ---- Authentication & Account Lifecycle ----

    fun signInWithEmail(email: String, password: String): ApiEnvelope<String> {
        val cleanEmail = email.trim().lowercase(Locale.US)
        syncServerClockIfNeeded(force = false)

        // Ensure virtual device exists and clear any stale previous session before signing in
        TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
        TimeMoviesPrefs.profileId = null
        TimeMoviesPrefs.profileName = null

        val payload = JSONObject().apply {
            put("email", cleanEmail)
            put("password", password)
            put("device", buildUserDeviceJson())
        }.toString()

        val res = runCatching {
            executeV47Request(
                url = "${apiBaseUrl()}$PATH_EMAIL_SIGN_IN",
                method = "POST",
                rawJsonBody = payload,
                noAuth = true,
                allowRetry = false,
            )
        }.getOrElse { err ->
            return ApiEnvelope(false, 0, err.message ?: "Network error", null)
        }

        if (res.body.isBlank()) {
            return ApiEnvelope(false, res.code, "استجابة فارغة من الخادم (${res.code})", null)
        }

        return runCatching {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", if (res.code in 200..299) 1 else 0)
            val message = obj.optNonBlank("message")
            val refusal = parseEmailRefusal(obj.optJSONObject("email_refusal"))
            val token = obj.optNonBlank("result")

            if (res.code in 200..299 && status == 1 && looksLikeJwt(token)) {
                TimeMoviesPrefs.authToken = token
                TimeMoviesPrefs.userEmail = cleanEmail
                TimeMoviesPrefs.pendingOtpToken = null
                TimeMoviesPrefs.pendingOtpEmail = null
                ensureDeviceKeyEnrolled(token!!)
                syncAppDataAndSelectMainProfile()
                ApiEnvelope(true, status, message, token, refusal)
            } else if (res.code in 200..299 && looksLikeJwt(token) && refusal?.action == 2) {
                // Unactivated account returning a pending OTP JWT
                TimeMoviesPrefs.pendingOtpToken = token
                TimeMoviesPrefs.pendingOtpEmail = cleanEmail
                ensureDeviceKeyEnrolled(token!!)
                ApiEnvelope(false, status, message ?: "الحساب بحاجة إلى رمز التفعيل (OTP)", token, refusal)
            } else {
                ApiEnvelope(false, status, message ?: "فشل تسجيل الدخول (${res.code})", null, refusal)
            }
        }.getOrElse { err ->
            ApiEnvelope(false, res.code, err.message ?: "خطأ في قراءة الاستجابة", null)
        }
    }

    /**
     * Registers a new account using a freshly rotated Virtual Retail Handset Identity so
     * hardware/device bans from the official app cannot carry over to new accounts.
     */
    fun signUpWithEmail(name: String, email: String, password: String): ApiEnvelope<String> {
        val cleanEmail = email.trim().lowercase(Locale.US)
        val cleanName = name.trim().ifEmpty { "Mehdi" }

        // Rotate virtual handset + device IDs + rasplab_install_id + P-256 keypair for clean registration
        TimeMoviesPrefs.rotateVirtualDeviceIdentity()
        syncServerClockIfNeeded(force = false)

        val payload = JSONObject().apply {
            put("email", cleanEmail)
            put("password", password)
            put("name", cleanName)
            put("device", buildUserDeviceJson())
        }.toString()

        val res = runCatching {
            executeV47Request(
                url = "${apiBaseUrl()}$PATH_EMAIL_SIGN_UP",
                method = "POST",
                rawJsonBody = payload,
                noAuth = true,
                allowRetry = false,
            )
        }.getOrElse { err ->
            return ApiEnvelope(false, 0, err.message ?: "Network error", null)
        }

        if (res.body.isBlank()) {
            return ApiEnvelope(false, res.code, "استجابة فارغة من الخادم (${res.code})", null)
        }

        return runCatching {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", 0)
            val message = obj.optNonBlank("message")
            val refusal = parseEmailRefusal(obj.optJSONObject("email_refusal"))
            val token = obj.optNonBlank("result")

            if (res.code in 200..299 && status == 1 && !token.isNullOrBlank()) {
                TimeMoviesPrefs.pendingOtpToken = token
                TimeMoviesPrefs.pendingOtpEmail = cleanEmail
                // Pre-enroll ECDSA P-256 device key for the pending OTP session immediately
                runCatching { ensureDeviceKeyEnrolled(token) }
                ApiEnvelope(true, status, message, token, refusal)
            } else {
                ApiEnvelope(false, status, message ?: "تعذر إنشاء الحساب (${res.code})", null, refusal)
            }
        }.getOrElse { err ->
            ApiEnvelope(false, res.code, err.message ?: "خطأ في قراءة الاستجابة", null)
        }
    }

    /**
     * Activates a newly registered account with the 6-digit Gmail OTP (`UserEmailActivate`).
     * Uses server-synced clock + token-scoped P-256 ECDSA signature (without stale `x-p`/`m-a` headers)
     * and automatic retry on clock/key verification mismatch.
     */
    fun activateEmailOtp(otpCode: String): ApiEnvelope<String> {
        val pendingToken = TimeMoviesPrefs.pendingOtpToken
            ?: return ApiEnvelope(false, 0, "لا توجد جلسة تسجيل معلقة — يرجى إنشاء الحساب أولاً", null)

        val cleanOtp = normalizeOtpCode(otpCode)
        if (cleanOtp.isEmpty()) {
            return ApiEnvelope(false, 0, "يرجى إدخال رمز التفعيل (OTP)", null)
        }

        val payload = JSONObject().apply {
            put("otp", cleanOtp)
            put("isOtpError", false)
            put("otpError", JSONObject.NULL)
        }.toString()

        val res = runCatching {
            executeV47Request(
                url = "${apiBaseUrl()}$PATH_EMAIL_ACTIVATE_OTP",
                method = "POST",
                rawJsonBody = payload,
                tokenOverride = pendingToken,
                allowRetry = true,
            )
        }.getOrElse { err ->
            return ApiEnvelope(false, 0, err.message ?: "Network error", null)
        }

        if (res.body.isBlank()) {
            return ApiEnvelope(false, res.code, "استجابة فارغة من الخادم (${res.code})", null)
        }

        return runCatching {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", 0)
            val message = obj.optNonBlank("message")
            val refusal = parseEmailRefusal(obj.optJSONObject("email_refusal"))
            val resultStr = obj.optNonBlank("result")

            if (res.code in 200..299 && status == 1) {
                // Only replace `pendingToken` if `result` is an actual 3-part JWT (`eyJ...`)
                val activatedToken = if (looksLikeJwt(resultStr)) resultStr!! else pendingToken
                val oldScope = TimeMoviesPrefs.computeTokenScope(pendingToken)
                val newScope = TimeMoviesPrefs.computeTokenScope(activatedToken)
                val existingDk = TimeMoviesPrefs.getDeviceKeyIdForScope(oldScope)

                TimeMoviesPrefs.authToken = activatedToken
                TimeMoviesPrefs.userEmail = TimeMoviesPrefs.pendingOtpEmail
                TimeMoviesPrefs.pendingOtpToken = null
                TimeMoviesPrefs.pendingOtpEmail = null

                if (existingDk != null && oldScope == newScope) {
                    TimeMoviesPrefs.setDeviceKeyIdForScope(newScope, existingDk)
                } else {
                    ensureDeviceKeyEnrolled(activatedToken)
                }

                syncAppDataAndSelectMainProfile()
                ApiEnvelope(true, status, message, activatedToken, refusal)
            } else {
                ApiEnvelope(false, status, message ?: "فشل تفعيل الرمز (${res.code})", null, refusal)
            }
        }.getOrElse { err ->
            ApiEnvelope(false, res.code, err.message ?: "خطأ في قراءة الاستجابة", null)
        }
    }

    /**
     * Requests resending the OTP email via `POST 2b1ea8f4c8174afb90d75f29e38c1ab3`.
     */
    fun resendActivationOtp(emailOverride: String? = null): ApiEnvelope<String> {
        val targetEmail = (emailOverride ?: TimeMoviesPrefs.pendingOtpEmail ?: TimeMoviesPrefs.userEmail)
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: return ApiEnvelope(false, 0, "لا يوجد بريد إلكتروني معلق لإعادة الإرسال", null)

        val payload = JSONObject().apply {
            put("email", targetEmail)
        }.toString()

        val pendingTk = TimeMoviesPrefs.pendingOtpToken
        val res = runCatching {
            if (!pendingTk.isNullOrBlank()) {
                executeV47Request(
                    url = "${apiBaseUrl()}$PATH_EMAIL_FORGOT_OR_RESEND",
                    method = "POST",
                    rawJsonBody = payload,
                    tokenOverride = pendingTk,
                    allowRetry = true,
                )
            } else {
                executeV47Request(
                    url = "${apiBaseUrl()}$PATH_EMAIL_FORGOT_OR_RESEND",
                    method = "POST",
                    rawJsonBody = payload,
                    noAuth = true,
                    allowRetry = false,
                )
            }
        }.getOrElse { err ->
            return ApiEnvelope(false, 0, err.message ?: "Network error", null)
        }

        if (res.body.isBlank()) {
            return ApiEnvelope(false, res.code, "استجابة فارغة من الخادم (${res.code})", null)
        }

        return runCatching {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", if (res.code in 200..299) 1 else 0)
            val msg = obj.optNonBlank("message")
            val resultStr = obj.optNonBlank("result")
            if (res.code in 200..299 && status == 1) {
                if (looksLikeJwt(resultStr)) {
                    TimeMoviesPrefs.pendingOtpToken = resultStr
                    ensureDeviceKeyEnrolled(resultStr!!)
                }
                ApiEnvelope(true, status, msg ?: "تم إرسال الرمز مجدداً إلى $targetEmail", resultStr)
            } else {
                ApiEnvelope(false, status, msg ?: "تعذر إعادة إرسال الرمز (${res.code})", null)
            }
        }.getOrElse { err ->
            ApiEnvelope(false, res.code, err.message ?: "خطأ في قراءة الاستجابة", null)
        }
    }

    /**
     * Syncs `AppData` (`POST 546635c490224ed1a74961d8e06198d1`) and selects the primary `USP` profile.
     * Also updates `authToken` if `AppData.tk` returns a refreshed JWT (`AppData.java`).
     */
    fun syncAppDataAndSelectMainProfile(): UserProfile? {
        if (!TimeMoviesPrefs.isSignedIn) return null
        val claims = TimeMoviesPrefs.decodeJwtClaims()
        val sid = claims?.optString("sid", "")?.takeIf { it.isNotBlank() }

        runCatching {
            val bodyJson = buildUserDeviceJson().apply {
                if (sid != null) put("sid", sid)
            }.toString()
            val res = executeV47Request(
                url = "${apiBaseUrl()}$PATH_APP_DATA",
                method = "POST",
                rawJsonBody = bodyJson,
            )
            if (res.code in 200..299 && res.body.isNotBlank()) {
                val obj = JSONObject(res.body)
                val resObj = obj.optJSONObject("result") ?: obj

                // `AppData.java` can include an updated `tk` JWT
                val updatedTk = resObj.optNonBlank("tk")
                if (looksLikeJwt(updatedTk) && updatedTk != TimeMoviesPrefs.authToken) {
                    val prevToken = TimeMoviesPrefs.authToken.orEmpty()
                    val prevScope = TimeMoviesPrefs.computeTokenScope(prevToken)
                    val nextScope = TimeMoviesPrefs.computeTokenScope(updatedTk!!)
                    val prevDk = TimeMoviesPrefs.getDeviceKeyIdForScope(prevScope)
                    TimeMoviesPrefs.authToken = updatedTk
                    if (prevDk != null && prevScope == nextScope) {
                        TimeMoviesPrefs.setDeviceKeyIdForScope(nextScope, prevDk)
                    } else {
                        ensureDeviceKeyEnrolled(updatedTk)
                    }
                }

                val ausp = resObj.optJSONObject("ausp")
                    ?: resObj.optJSONObject("usp")
                    ?: resObj.optJSONObject("au")?.optJSONArray("usps")?.optJSONObject(0)
                if (ausp != null) {
                    parseUserProfile(ausp)?.let { prof ->
                        saveActiveProfile(prof)
                        return prof
                    }
                }
            }
        }

        val profiles = fetchUserProfiles()
        val chosen = profiles.firstOrNull { it.isMain && !it.isPinSet && !it.isVipLocked }
            ?: profiles.firstOrNull { !it.isPinSet && !it.isVipLocked }
            ?: profiles.firstOrNull()
        if (chosen != null) {
            saveActiveProfile(chosen)
        }
        return chosen
    }

    fun fetchUserProfiles(): List<UserProfile> {
        if (!TimeMoviesPrefs.isSignedIn) return emptyList()
        return runCatching {
            val res = executeV47Request(
                url = "${apiBaseUrl()}$PATH_USER_PROFILES",
                method = "GET",
            )
            if (res.code !in 200..299 || res.body.isBlank()) return emptyList()
            val obj = JSONObject(res.body)
            val arr = obj.optJSONArray("result") ?: return emptyList()
            val out = ArrayList<UserProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                parseUserProfile(item)?.let { out.add(it) }
            }
            out
        }.getOrDefault(emptyList())
    }

    fun saveActiveProfile(profile: UserProfile) {
        TimeMoviesPrefs.profileId = profile.id
        TimeMoviesPrefs.profileName = profile.name
        TimeMoviesPrefs.profileMaturity = profile.maturity
    }

    // ---- Catalog, Home, Filters, Genres, Search, Work Details ----

    fun getAppSettings(): JSONObject? = runCatching {
        val res = executeV47Request(
            url = "${apiBaseUrl()}$PATH_APP_SETTINGS",
            method = "GET",
            noAuth = !TimeMoviesPrefs.isSignedIn,
        )
        if (res.code in 200..299 && res.body.isNotBlank()) {
            val obj = JSONObject(res.body)
            obj.optJSONObject("result") ?: obj
        } else {
            null
        }
    }.getOrNull()

    fun getHome(): HomeResponse? = runCatching {
        val res = executeV47Request(
            url = "${apiBaseUrl()}$PATH_HOME",
            method = "GET",
        )
        if (res.code !in 200..299 || res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        val resObj = obj.optJSONObject("result") ?: return null
        parseHomeResponse(resObj)
    }.getOrNull()

    fun getMoviesOrSeriesPage(
        isMovies: Boolean,
        pageZeroBased: Int,
        sort: String = "LATEST",
        direction: String = "DESC",
        genreIds: List<String> = emptyList(),
        isAnimation: Boolean? = null,
    ): PageFilterResult? = runCatching {
        val endpoint = if (isMovies) PATH_PAGE_MOVIES else PATH_PAGE_SERIES
        val body = JSONObject().apply {
            put("page", pageZeroBased.coerceAtLeast(0))
            put("sort", sort)
            put("direction", direction)
            put("genres", JSONArray(genreIds))
            put("countries", JSONArray())
            put("languages", JSONArray())
            if (isAnimation != null) put("isAnimation", isAnimation)
        }.toString()

        val res = executeV47Request(
            url = "${apiBaseUrl()}$endpoint",
            method = "POST",
            rawJsonBody = body,
        )
        if (res.code !in 200..299 || res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        val resObj = obj.optJSONObject("result") ?: return null
        val works = parseWorkCardList(resObj.optJSONArray("works"))
        PageFilterResult(
            works = works,
            page = resObj.optInt("page", pageZeroBased),
            isLast = resObj.optBoolean("is_last", works.isEmpty()),
        )
    }.getOrNull()

    fun getCategoryMore(
        categoryId: String,
        lastId: String? = null,
        lastVal: String? = null,
    ): CategoryPageResult? = runCatching {
        val urlBuilder = "${apiBaseUrl()}$PATH_CATEGORY_MORE".toHttpUrl().newBuilder()
            .addQueryParameter("cat", categoryId)
        if (!lastId.isNullOrBlank()) urlBuilder.addQueryParameter("lastId", lastId)
        if (!lastVal.isNullOrBlank()) urlBuilder.addQueryParameter("lastVal", lastVal)

        val res = executeV47Request(
            url = urlBuilder.build().toString(),
            method = "GET",
        )
        if (res.code !in 200..299 || res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        val resObj = obj.optJSONObject("result") ?: return null
        val works = parseWorkCardList(resObj.optJSONArray("works"))
        CategoryPageResult(
            works = works,
            lastId = resObj.optNonBlank("lastId"),
            lastVal = resObj.optNonBlank("lastVal"),
            isLast = resObj.optBoolean("is_last", works.isEmpty()),
        )
    }.getOrNull()

    fun getGenresList(): List<GenreItem> = runCatching {
        val res = executeV47Request(
            url = "${apiBaseUrl()}$PATH_GENRES_LIST",
            method = "GET",
        )
        if (res.code !in 200..299 || res.body.isBlank()) return emptyList()
        val obj = JSONObject(res.body)
        val arr = obj.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<GenreItem>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val id = g.optNonBlank("_id") ?: continue
            val nEn = g.optNonBlank("n") ?: g.optNonBlank("nr") ?: continue
            out.add(
                GenreItem(
                    id = id,
                    nameEn = nEn,
                    nameAr = g.optNonBlank("nr"),
                    code = g.optNonBlank("cd"),
                )
            )
        }
        out
    }.getOrDefault(emptyList())

    fun getGenreWorks(
        genreId: String,
        lastAt: String? = null,
        lastId: String? = null,
    ): GenrePageResult? = runCatching {
        val body = JSONObject().apply {
            put("gn", genreId)
            if (!lastAt.isNullOrBlank()) put("lastAt", lastAt)
            if (!lastId.isNullOrBlank()) put("lastId", lastId)
        }.toString()

        val res = executeV47Request(
            url = "${apiBaseUrl()}$PATH_GENRE_WORKS",
            method = "POST",
            rawJsonBody = body,
        )
        if (res.code !in 200..299 || res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        val resObj = obj.optJSONObject("result") ?: return null
        val works = parseWorkCardList(resObj.optJSONArray("works"))
        GenrePageResult(
            works = works,
            lastAt = resObj.optNonBlank("lastAt"),
            lastId = resObj.optNonBlank("lastId"),
            isLast = resObj.optBoolean("is_last", works.isEmpty()),
        )
    }.getOrNull()

    fun searchWorks(query: String, pageZeroBased: Int = 0): SearchResultPage? = runCatching {
        val url = "${apiBaseUrl()}$PATH_SEARCH".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("page", pageZeroBased.coerceAtLeast(0).toString())
            .build()
            .toString()

        val res = executeV47Request(url = url, method = "GET")
        if (res.code !in 200..299 || res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        val resObj = obj.optJSONObject("result") ?: return null
        val movies = parseWorkCardList(resObj.optJSONArray("movies"))
        val series = parseWorkCardList(resObj.optJSONArray("series"))
        SearchResultPage(
            movies = movies,
            series = series,
            page = resObj.optInt("page", pageZeroBased),
            isLastMovies = resObj.optBoolean("is_last_movies", movies.isEmpty()),
            isLastSeries = resObj.optBoolean("is_last_series", series.isEmpty()),
        )
    }.getOrNull()

    fun getWorkDetails(workId: String): WorkDetails? {
        if (TimeMoviesPrefs.isSignedIn && TimeMoviesPrefs.profileId.isNullOrBlank()) {
            syncAppDataAndSelectMainProfile()
        }

        runCatching {
            val url = "${apiBaseUrl()}$PATH_WORK_DETAILS".toHttpUrl().newBuilder()
                .addQueryParameter("wk", workId.trim())
                .build()
                .toString()
            val res = executeV47Request(url = url, method = "GET")
            if (res.code in 200..299 && res.body.isNotBlank()) {
                val obj = JSONObject(res.body)
                val resObj = obj.optJSONObject("result")
                if (resObj != null) {
                    parseWorkDetails(resObj)?.let { return it }
                }
            }
        }

        return getDoomPlayerWorkFallback(workId)
    }

    fun getDoomPlayerWorkFallback(workId: String): WorkDetails? = runCatching {
        val url = "${doomBaseUrl()}$PATH_WORK_DETAILS".toHttpUrl().newBuilder()
            .addQueryParameter("wk", workId.trim())
            .build()

        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("x-b", TimeMoviesCrypto.DOOM_BUILD)
            .header("ai", TimeMoviesCrypto.DOOM_APP_ID)
            .build()

        plainClient.newCall(req).execute().use { resp ->
            updateClockFromResponse(resp)
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            val obj = JSONObject(body)
            val resObj = obj.optJSONObject("result") ?: return null
            parseWorkDetails(resObj)
        }
    }.getOrNull()

    // ---- Media V2 Grant & DoomVideoPlayer v1.6/v1.5 Resolvers ----

    /**
     * Calls `POST /api/v4.7/app/media/grant` (`MediaGrantRequest.java` + `at5.java`).
     * Supports `operationId` & `clientAttemptKey` for idempotent `ExecutionFailed` retry.
     */
    fun requestMediaGrant(
        action: String,
        workId: String,
        fileCode: String,
        episodeId: String? = null,
        seasonId: String? = null,
        quality: Int? = null,
        executorTarget: String? = null,
        playerInstalled: Boolean = true,
        codes: List<String>? = null,
        operationId: String? = null,
        clientAttemptKey: String = UUID.randomUUID().toString(),
    ): MediaGrantPayload? = runCatching {
        if (TimeMoviesPrefs.isSignedIn && TimeMoviesPrefs.profileId.isNullOrBlank()) {
            syncAppDataAndSelectMainProfile()
        }

        val bodyJson = JSONObject().apply {
            put("action", action)
            put("surface", 1)
            put("package", TimeMoviesCrypto.APP_PACKAGE)
            put("wk", workId)
            put("code", fileCode)
            if (!episodeId.isNullOrBlank()) put("ep", episodeId)
            if (!seasonId.isNullOrBlank()) put("ss", seasonId)
            if (quality != null) put("quality", quality)
            if (!codes.isNullOrEmpty()) put("codes", JSONArray(codes))
            if (!executorTarget.isNullOrBlank()) put("executor_target", executorTarget)
            put("player_installed", playerInstalled)
            if (!operationId.isNullOrBlank()) put("operation_id", operationId)
            put("client_attempt_key", clientAttemptKey)
        }.toString()

        val res = executeV47Request(
            url = "${apiBaseUrl()}$PATH_MEDIA_GRANT",
            method = "POST",
            rawJsonBody = bodyJson,
        )
        if (res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        val resObj = obj.optJSONObject("result") ?: obj
        val parsed = parseMediaGrant(resObj)
        if (!parsed.ok && !parsed.reason.isNullOrBlank() && parsed.reason != "player_not_installed") {
            lastMediaRefusalReason = parsed.reason
        }
        parsed
    }.getOrNull()

    /**
     * Executes `fx.D` / `fx.R0` (`Lex;->F`, `q52.java:512`) against `https://api.apks.lol/v2/media/`:
     * - `GET /v2/media/manifest` (`action == "watch"`)
     * - `GET /v2/media/download` (`action == "download"`)
     * - `GET /v2/media/cast` (`action == "cast"`)
     * - `POST /v2/media/season` (`action == "season_download"`, body `{"codes":[...]}`)
     *
     * Sends `X-Media-Grant: <grantToken>` together with `q52` app headers (`x-b`, `ai`, `av`,
     * `x-t`, `x-p`, `m-a`, `n`, `t`; no `dk`/`ds`/`da` since `/v2/media/` is outside `/api/v4.7/`).
     */
    fun fetchOriginMedia(
        action: String,
        grantToken: String,
        codes: List<String> = emptyList(),
    ): MediaV2Payload? = runCatching {
        if (grantToken.isBlank()) return null
        val isSeason = action == "season_download"
        val subPath = when (action) {
            "download" -> "download"
            "season_download" -> "season"
            "cast" -> "cast"
            else -> "manifest"
        }
        val url = "${mediaV2BaseUrl()}$subPath"
        val method = if (isSeason) "POST" else "GET"
        val body = if (isSeason) {
            JSONObject().put("codes", JSONArray(codes)).toString()
        } else {
            null
        }

        val res = executeV47Request(
            url = url,
            method = method,
            rawJsonBody = body,
            extraHeaders = mapOf("X-Media-Grant" to grantToken),
            allowRetry = true,
        )
        if (res.code !in 200..299 || res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        parseMediaV2(obj.optJSONObject("result") ?: obj)
    }.getOrNull()

    fun resolveDoomPlayTicket(srcTicket: String): String? {
        resolveDoomEndpoint(DOOM_RESOLVE_PLAY, srcTicket)?.let { return it }
        return resolveDoomEndpoint(DOOM_LEGACY_PLAY, srcTicket)
    }

    fun resolveDoomDownloadTicket(srcTicket: String): String? {
        resolveDoomEndpoint(DOOM_RESOLVE_DOWNLOAD, srcTicket)?.let { return it }
        return resolveDoomEndpoint(DOOM_LEGACY_DOWNLOAD, srcTicket)
    }

    private fun resolveDoomEndpoint(subPath: String, src: String): String? = runCatching {
        if (src.isBlank()) return null
        val url = "${doomBaseUrl()}$subPath".toHttpUrlOrNull() ?: return null
        val bodyJson = JSONObject().put("src", src).toString()

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("x-b", TimeMoviesCrypto.DOOM_BUILD)
            .header("ai", TimeMoviesCrypto.DOOM_APP_ID)
            .build()

        plainClient.newCall(req).execute().use { resp ->
            updateClockFromResponse(resp)
            if (!resp.isSuccessful) return null
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) return null
            val obj = JSONObject(raw)
            val resultNode = obj.opt("result")
            when (resultNode) {
                is JSONObject -> resultNode.optNonBlank("url") ?: resultNode.optNonBlank("src")
                is String -> resultNode.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
                else -> obj.optNonBlank("url")
            }
        }
    }.getOrNull()
}
