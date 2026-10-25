package com.timemovies

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
import com.timemovies.TimeMoviesModels.parseEmailRefusal
import com.timemovies.TimeMoviesModels.parseHomeResponse
import com.timemovies.TimeMoviesModels.parseMediaGrant
import com.timemovies.TimeMoviesModels.parseMediaV2
import com.timemovies.TimeMoviesModels.parseUserProfile
import com.timemovies.TimeMoviesModels.parseWorkCardList
import com.timemovies.TimeMoviesModels.parseWorkDetails
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
 * v3 Highlights:
 * - Isolated `AuthSessionContext` supporting Personal Account, Pending OTP Session, and Multi-Account
 *   Built-in Pool (`TimeMoviesPool`) with zero cross-contamination of device fingerprints or keys.
 * - Permanent fix for Android OTP `"Request verification failed."`:
 *   - Canonical 91-byte SPKI & 138-byte PKCS#8 keys (`AndroidOpenSSL` provider-pinned)
 *   - Never rotates keypair mid-session (`409 rotation_requires_signin` prevention)
 *   - Explicit enrollment verification & clock-sync retry before every signed request
 *   - Satisfies `b13.java:75` minimum 6-character `name` check on wire
 * - Automatic multi-account failover across `TimeMoviesPool` slots on quota exhaustion (`fq`/`dcr`) or `401`/`403`.
 */
object TimeMoviesApi {

    // ---- Verified Obfuscated v4.7 Endpoint UUIDs (`fx.java` / `ex.java`, build 1092) ----
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
    const val PATH_USER_PROFILES = "2468218428cc4065b0e37dc68c950505"         // GET -> List<USP>
    const val PATH_CREATE_PROFILE = "83fd64bbed2640be9a849fbe975bad53"        // POST -> USP (`USPE`)
    const val PATH_HOME = "6ec4ebc493d342fba913e1fb7c3b1b2a"                  // GET -> Home
    const val PATH_PAGE_FILTER = "807f73ed5aec4e3faef1df605238f727"           // GET ?page=&level= -> PageFilterResult
    const val PATH_CATEGORY_MORE = "f406853b625443cdbfb82ff8c1c35037"         // GET ?catId=&li=&lv= -> HomeMore
    const val PATH_GENRES_LIST = "da8f8e755b58479f8e0385d6ea37261a"           // GET -> List<GN>
    const val PATH_GENRE_WORKS = "19863d67d52c43adb578717e8ab90faa"           // GET ?gId=&ism=&la=&li=&s=&sd= -> GenreResult
    const val PATH_SEARCH = "3571075248fa4bb78355e487c9730be7"                // GET ?search=&page= -> SearchResult
    const val PATH_WORK_DETAILS = "30df69a88f6e4ac2a7ea8b9797890911"          // GET ?wk=<id> -> WKI
    const val PATH_MEDIA_QUALITIES = "media/qualities"                        // POST -> Qualities
    const val PATH_MEDIA_GRANT = "media/grant"                                // POST -> MediaGrantResponse

    // DoomVideoPlayer v1.6 + v1.5 paths (`ApiPath.java`)
    private const val DOOM_RESOLVE_PLAY = "resolve-play"
    private const val DOOM_RESOLVE_DOWNLOAD = "resolve-download"
    private const val DOOM_LEGACY_PLAY = "8cf0b2fa26d74fcbb3f08c4f1907a1bc"
    private const val DOOM_LEGACY_DOWNLOAD = "5d18c7a3f0414e6b9c210e9a78d5f4a2"

    private val QUOTA_OR_POOL_ROTATE_REASONS = setOf(
        "quota_exhausted",
        "insufficient_quota",
        "daily_limit",
        "limit_reached",
        "target_over_limit",
        "device_limit",
        "max_devices",
        "blocked_by_plan",
    )

    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    private val clockSyncedInProcess = AtomicBoolean(false)
    private val enrollmentLock = Any()
    private val refreshLock = Any()

    @Volatile
    var lastMediaRefusalReason: String? = null

    @Volatile
    var lastEnrollmentError: String? = null

    @Volatile
    private var lastSuccessfulGrantSession: AuthSessionContext? = null

    val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun resetRuntimeStateForTests() {
        clockSyncedInProcess.set(false)
        lastMediaRefusalReason = null
        lastEnrollmentError = null
        lastSuccessfulGrantSession = null
        TimeMoviesPool.resetForTests()
    }

    fun hasUsableAuth(allowNetworkFetch: Boolean = true): Boolean {
        return TimeMoviesPrefs.isSignedIn || TimeMoviesPool.hasAnySlot(allowNetworkFetch = allowNetworkFetch)
    }

    fun apiBaseUrl(): String = TimeMoviesPrefs.apiBaseUrlOverride ?: TimeMoviesCrypto.API_BASE_URL

    fun mediaV2BaseUrl(): String {
        TimeMoviesPrefs.mediaV2BaseUrlOverride?.let { return it }
        val apiBase = apiBaseUrl()
        val root = apiBase.substringBefore("/api/")
        return "${root.trimEnd('/')}/v2/media/"
    }

    fun doomBaseUrl(): String = TimeMoviesPrefs.doomBaseUrlOverride ?: TimeMoviesCrypto.DOOM_BASE_URL

    private fun isEnvelopeOk(obj: JSONObject): Boolean {
        return obj.optBoolean("success", false) || obj.optInt("status", 0) == 1
    }

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

    // ---- Isolated Authentication Session Contexts (Personal, Pending-OTP, Built-In Pool Slot) ----

    internal sealed class AuthSessionContext {
        abstract val authToken: String
        abstract val profileId: String
        abstract val profileMaturity: String
        abstract val virtualDevice: TimeMoviesPrefs.VirtualDeviceProfile
        abstract val keyPair: TimeMoviesCrypto.EcKeyMaterial
        abstract val canRefreshToken: Boolean
        abstract val canRegenerateKeyOnBadKey400: Boolean

        abstract fun getCachedDeviceKeyId(): String?
        abstract fun saveDeviceKeyId(dk: String?)
        abstract fun regenerateKeyPairOn400()
        abstract fun saveRefreshedToken(newToken: String)
        abstract fun saveResolvedProfile(profile: UserProfile)
    }

    internal object UnauthenticatedSessionContext : AuthSessionContext() {
        override val authToken: String = ""
        override val profileId: String = ""
        override val profileMaturity: String = ""
        override val virtualDevice: TimeMoviesPrefs.VirtualDeviceProfile
            get() = TimeMoviesPrefs.getOrCreateVirtualDevice()
        override val keyPair: TimeMoviesCrypto.EcKeyMaterial
            get() = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        override val canRefreshToken: Boolean = false
        override val canRegenerateKeyOnBadKey400: Boolean = false
        override fun getCachedDeviceKeyId(): String? = null
        override fun saveDeviceKeyId(dk: String?) = Unit
        override fun regenerateKeyPairOn400() = Unit
        override fun saveRefreshedToken(newToken: String) = Unit
        override fun saveResolvedProfile(profile: UserProfile) = Unit
    }

    internal object PersonalSessionContext : AuthSessionContext() {
        override val authToken: String
            get() = TimeMoviesPrefs.authToken.orEmpty()
        override val profileId: String
            get() = TimeMoviesPrefs.profileId.orEmpty()
        override val profileMaturity: String
            get() = if (profileId.isNotEmpty()) TimeMoviesPrefs.profileMaturity.toString() else ""
        override val virtualDevice: TimeMoviesPrefs.VirtualDeviceProfile
            get() = TimeMoviesPrefs.getOrCreateVirtualDevice()
        override val keyPair: TimeMoviesCrypto.EcKeyMaterial
            get() = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        override val canRefreshToken: Boolean = true
        override val canRegenerateKeyOnBadKey400: Boolean = true

        override fun getCachedDeviceKeyId(): String? {
            val tk = authToken
            if (tk.isBlank()) return null
            val scope = TimeMoviesPrefs.computeTokenScope(tk, virtualDevice.deviceId)
            return TimeMoviesPrefs.getDeviceKeyIdForScope(scope)
        }

        override fun saveDeviceKeyId(dk: String?) {
            if (dk.isNullOrBlank()) {
                TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
            } else {
                val scope = TimeMoviesPrefs.computeTokenScope(authToken, virtualDevice.deviceId)
                TimeMoviesPrefs.setDeviceKeyIdForScope(scope, dk)
            }
        }

        override fun regenerateKeyPairOn400() {
            TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = true)
        }

        override fun saveRefreshedToken(newToken: String) {
            val oldTk = authToken
            val devId = virtualDevice.deviceId
            val oldScope = TimeMoviesPrefs.computeTokenScope(oldTk, devId)
            val newScope = TimeMoviesPrefs.computeTokenScope(newToken, devId)
            val prevDk = TimeMoviesPrefs.getDeviceKeyIdForScope(oldScope)
            TimeMoviesPrefs.authToken = newToken
            if (prevDk != null && oldScope == newScope) {
                TimeMoviesPrefs.setDeviceKeyIdForScope(newScope, prevDk)
            }
        }

        override fun saveResolvedProfile(profile: UserProfile) {
            saveActiveProfile(profile)
        }
    }

    internal class OtpPendingSessionContext(@Volatile private var tokenValue: String) : AuthSessionContext() {
        override val authToken: String
            get() = tokenValue
        override val profileId: String = ""
        override val profileMaturity: String = ""
        override val virtualDevice: TimeMoviesPrefs.VirtualDeviceProfile
            get() = TimeMoviesPrefs.getOrCreateVirtualDevice()
        override val keyPair: TimeMoviesCrypto.EcKeyMaterial
            get() = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        override val canRefreshToken: Boolean = false
        override val canRegenerateKeyOnBadKey400: Boolean = true

        override fun getCachedDeviceKeyId(): String? {
            if (tokenValue.isBlank()) return null
            val scope = TimeMoviesPrefs.computeTokenScope(tokenValue, virtualDevice.deviceId)
            return TimeMoviesPrefs.getDeviceKeyIdForScope(scope)
        }

        override fun saveDeviceKeyId(dk: String?) {
            if (dk.isNullOrBlank()) {
                TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
            } else {
                val scope = TimeMoviesPrefs.computeTokenScope(tokenValue, virtualDevice.deviceId)
                TimeMoviesPrefs.setDeviceKeyIdForScope(scope, dk)
            }
        }

        override fun regenerateKeyPairOn400() {
            TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = true)
        }

        override fun saveRefreshedToken(newToken: String) {
            tokenValue = newToken
            TimeMoviesPrefs.pendingOtpToken = newToken
        }

        override fun saveResolvedProfile(profile: UserProfile) = Unit
    }

    internal class PoolSlotSessionContext(val slotId: String) : AuthSessionContext() {
        private fun currentSlot(): TimeMoviesPool.EffectivePoolSlot? =
            TimeMoviesPool.loadAllSlots(allowNetworkFetch = false).firstOrNull { it.slotId == slotId }

        override val authToken: String
            get() = currentSlot()?.effectiveToken.orEmpty()
        override val profileId: String
            get() = currentSlot()?.effectiveProfileId.orEmpty()
        override val profileMaturity: String
            get() = currentSlot()?.effectiveMaturity.orEmpty()
        override val virtualDevice: TimeMoviesPrefs.VirtualDeviceProfile
            get() = currentSlot()?.bundle?.virtualDevice ?: TimeMoviesPrefs.getOrCreateVirtualDevice()
        override val keyPair: TimeMoviesCrypto.EcKeyMaterial
            get() = currentSlot()?.bundle?.keyPair ?: TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        override val canRefreshToken: Boolean = true
        // Never regenerate a pool slot's keypair — its public key was permanently bound at account activation!
        override val canRegenerateKeyOnBadKey400: Boolean = false

        override fun getCachedDeviceKeyId(): String? = currentSlot()?.effectiveDeviceKeyId

        override fun saveDeviceKeyId(dk: String?) {
            TimeMoviesPool.updateSlotRuntime(slotId) { st ->
                st.copy(enrolledDeviceKeyId = dk)
            }
        }

        override fun regenerateKeyPairOn400() = Unit

        override fun saveRefreshedToken(newToken: String) {
            TimeMoviesPool.updateSlotRuntime(slotId) { st ->
                st.copy(refreshedAuthToken = newToken, authFailedUntilMs = 0L)
            }
        }

        override fun saveResolvedProfile(profile: UserProfile) {
            TimeMoviesPool.updateSlotRuntime(slotId) { st ->
                st.copy(resolvedProfileId = profile.id, resolvedMaturity = profile.maturity)
            }
        }
    }

    // ---- Core Signed v4.7 Request Executor (`q52.java` + `V47DeviceSigningInterceptor`) ----

    private data class RawHttpResult(
        val code: Int,
        val body: String,
    )

    private data class EnrollAttemptResult(
        val dk: String?,
        val httpCode: Int,
        val errorMessage: String?,
    )

    private fun isVerificationOrKeyFailure(code: Int, body: String): Boolean {
        if (code == 401 || code == 403) return true
        val lower = body.lowercase(Locale.US)
        return lower.contains("request verification failed") ||
            lower.contains("verification failed") ||
            lower.contains("device key") ||
            lower.contains("invalid signature")
    }

    private fun executeV47RequestWithSession(
        url: String,
        method: String = "GET",
        rawJsonBody: String? = null,
        session: AuthSessionContext,
        noAuth: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
        allowRetry: Boolean = true,
    ): RawHttpResult {
        val httpUrl = url.toHttpUrl()
        val tk = if (noAuth) "" else session.authToken
        val profileId = if (noAuth || tk.isEmpty()) "" else session.profileId
        val maturity = if (profileId.isNotEmpty()) session.profileMaturity else ""

        val mustSign = TimeMoviesCrypto.shouldAttachDeviceSignature(httpUrl, tk)
        var dk: String? = null
        if (mustSign) {
            syncServerClockIfNeeded(force = false)
            dk = ensureSessionDeviceKeyEnrolled(session)
            if (dk.isNullOrBlank()) {
                // Force clock resync and try enrollment once more before giving up
                syncServerClockIfNeeded(force = true)
                dk = ensureSessionDeviceKeyEnrolled(session)
            }
        }

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

        val effectiveBodyForHash: String?
        val upperMethod = method.uppercase(Locale.US)
        if (upperMethod == "GET" || upperMethod == "HEAD") {
            effectiveBodyForHash = null
            reqBuilder.method(upperMethod, null)
        } else {
            val jsonStr = rawJsonBody ?: "{}"
            effectiveBodyForHash = jsonStr
            reqBuilder.method(upperMethod, jsonStr.toRequestBody(JSON_MEDIA_TYPE))
        }

        if (mustSign && !dk.isNullOrBlank()) {
            val kp = session.keyPair
            val sigHeaders = TimeMoviesCrypto.signV47Request(
                privateKeyPkcs8B64Url = kp.privateKeyPkcs8B64Url,
                method = upperMethod,
                url = httpUrl,
                timestamp = timestamp,
                nonce = nonce,
                rawBody = effectiveBodyForHash,
                authToken = tk,
                profileId = profileId,
                maturity = maturity,
                deviceKeyId = dk,
            )
            reqBuilder.header("dk", sigHeaders.dk)
            reqBuilder.header("ds", sigHeaders.ds)
            reqBuilder.header("da", sigHeaders.da)
        }

        val initial = plainClient.newCall(reqBuilder.build()).execute().use { resp ->
            updateClockFromResponse(resp)
            RawHttpResult(resp.code, resp.body?.string().orEmpty())
        }

        if (allowRetry && tk.isNotEmpty() && !TimeMoviesCrypto.isExemptFromDeviceVerification(httpUrl)) {
            if (isVerificationOrKeyFailure(initial.code, initial.body)) {
                // Step 1: Sync server clock & re-enroll with the SAME keypair (never triggering 409 rotation_requires_signin)
                syncServerClockIfNeeded(force = true)
                session.saveDeviceKeyId(null)

                if (initial.code == 401 && session.canRefreshToken) {
                    tryRefreshSessionAuthToken(session)
                }

                val retryToken = session.authToken
                if (retryToken.isNotBlank()) {
                    ensureSessionDeviceKeyEnrolled(session)
                    return executeV47RequestWithSession(
                        url = url,
                        method = method,
                        rawJsonBody = rawJsonBody,
                        session = session,
                        noAuth = noAuth,
                        extraHeaders = extraHeaders,
                        allowRetry = false,
                    )
                }
            }
        }

        return initial
    }

    /**
     * Enrolls `session.keyPair` on `api.apks.lol` (`c81d2e...` -> `e4ad21...`).
     * Never rotates the keypair unless `400` bad-key was explicitly returned (`a.java:1121`),
     * preventing `409 rotation_requires_signin` (`a.java:1126`).
     */
    internal fun ensureSessionDeviceKeyEnrolled(session: AuthSessionContext): String? {
        if (session.authToken.isBlank()) return null
        session.getCachedDeviceKeyId()?.takeIf { it.isNotBlank() }?.let { return it }

        synchronized(enrollmentLock) {
            session.getCachedDeviceKeyId()?.takeIf { it.isNotBlank() }?.let { return it }

            syncServerClockIfNeeded(force = false)
            val first = enrollSessionOnce(session)
            if (!first.dk.isNullOrBlank()) {
                lastEnrollmentError = null
                return first.dk
            }

            syncServerClockIfNeeded(force = true)
            if (first.httpCode == 400 && session.canRegenerateKeyOnBadKey400) {
                session.regenerateKeyPairOn400()
            }
            val second = enrollSessionOnce(session)
            if (!second.dk.isNullOrBlank()) {
                lastEnrollmentError = null
                return second.dk
            }

            lastEnrollmentError = second.errorMessage ?: first.errorMessage ?: "HTTP ${second.httpCode}"
            return null
        }
    }

    fun ensureDeviceKeyEnrolled(
        authToken: String,
        forceRegenerateOnFail: Boolean = false,
    ): String? {
        if (authToken.isBlank()) return null
        val session: AuthSessionContext = when (authToken) {
            TimeMoviesPrefs.authToken -> PersonalSessionContext
            else -> OtpPendingSessionContext(authToken)
        }
        val dk = ensureSessionDeviceKeyEnrolled(session)
        if (!dk.isNullOrBlank()) return dk
        if (forceRegenerateOnFail && session.canRegenerateKeyOnBadKey400) {
            session.regenerateKeyPairOn400()
            return ensureSessionDeviceKeyEnrolled(session)
        }
        return null
    }

    private fun enrollSessionOnce(session: AuthSessionContext): EnrollAttemptResult {
        return try {
            val kp = session.keyPair

            // 1. `GET c81d2e64a4d64d8faea7e1abf4cb8c13` -> `{cid, ch, exp}`
            val chRes = executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_DEVICE_CHALLENGE",
                method = "GET",
                session = session,
                allowRetry = false,
            )
            if (chRes.code !in 200..299 || chRes.body.isBlank()) {
                val msg = runCatching { JSONObject(chRes.body).optNonBlank("message") }.getOrNull()
                return EnrollAttemptResult(null, chRes.code, msg ?: "Challenge HTTP ${chRes.code}")
            }
            val chJson = JSONObject(chRes.body)
            val chResult = chJson.optJSONObject("result") ?: chJson
            val cid = chResult.optNonBlank("cid")
            val ch = chResult.optNonBlank("ch")
            if (cid.isNullOrBlank() || ch.isNullOrBlank()) {
                val msg = chJson.optNonBlank("message") ?: "Missing challenge cid/ch"
                return EnrollAttemptResult(null, chJson.optInt("status", chRes.code), msg)
            }

            // 2. Sign `"$cid\n$ch\n$pk\nECDSA_SHA256_DER"` (`np0.N`)
            val challengePayload = TimeMoviesCrypto.buildEnrollmentChallengePayload(
                cid = cid,
                challenge = ch,
                publicKeySpkiB64Url = kp.publicKeySpkiB64Url,
            )
            val sigB64Url = TimeMoviesCrypto.signEcdsaSha256DerB64Url(
                privateKeyPkcs8B64Url = kp.privateKeyPkcs8B64Url,
                messageBytes = challengePayload,
            )

            // 3. `POST e4ad21d4b1fc41bca62d94bd972e93f6` -> `{dk, alg, st}`
            val registerBody = JSONObject().apply {
                put("cid", cid)
                put("pk", kp.publicKeySpkiB64Url)
                put("sig", sigB64Url)
                put("alg", TimeMoviesCrypto.SIGNING_ALGORITHM)
            }.toString()

            val regRes = executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_DEVICE_REGISTER",
                method = "POST",
                rawJsonBody = registerBody,
                session = session,
                allowRetry = false,
            )
            if (regRes.code !in 200..299 || regRes.body.isBlank()) {
                val msg = runCatching { JSONObject(regRes.body).optNonBlank("message") }.getOrNull()
                return EnrollAttemptResult(null, regRes.code, msg ?: "Enroll HTTP ${regRes.code}")
            }
            val regJson = JSONObject(regRes.body)
            val regResult = regJson.optJSONObject("result") ?: regJson
            val dk = regResult.optNonBlank("dk")
            if (dk.isNullOrBlank()) {
                val status = regJson.optInt("status", regRes.code)
                val msg = regJson.optNonBlank("message") ?: "Enroll rejected ($status)"
                return EnrollAttemptResult(null, status, msg)
            }

            session.saveDeviceKeyId(dk)
            EnrollAttemptResult(dk, regRes.code, null)
        } catch (err: Throwable) {
            EnrollAttemptResult(null, 0, err.message ?: err.javaClass.simpleName)
        }
    }

    /**
     * Refreshes JWT via `POST d4f34a2a4f0249bbb3c92a7e45f3e1c2` with `RefreshTokenBody(sid, rasplab_install_id)`
     * using `session`'s own isolated `rasplabInstallId`!
     */
    private fun tryRefreshSessionAuthToken(session: AuthSessionContext): Boolean = synchronized(refreshLock) {
        runCatching {
            val currentToken = session.authToken.takeIf { it.isNotBlank() } ?: return@runCatching false
            val claims = TimeMoviesPrefs.decodeJwtClaims(currentToken)
            val sid = claims?.optNonBlank("sid")
            val refreshBody = JSONObject().apply {
                if (!sid.isNullOrBlank()) put("sid", sid)
                put("rasplab_install_id", session.virtualDevice.rasplabInstallId)
            }.toString()

            val res = executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_REFRESH_TOKEN",
                method = "POST",
                rawJsonBody = refreshBody,
                session = session,
                allowRetry = false,
            )
            if (res.code !in 200..299 || res.body.isBlank()) return@runCatching false
            val obj = JSONObject(res.body)
            val newToken = obj.optNonBlank("result")
            if (isEnvelopeOk(obj) && looksLikeJwt(newToken)) {
                session.saveRefreshedToken(newToken!!)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    // ---- Session Failover Helpers (Personal + Built-In Pool) ----

    private fun <T> executeCatalogWithFailover(block: (AuthSessionContext) -> T?): T? {
        if (TimeMoviesPrefs.isSignedIn) {
            ensureSessionProfileInitialized(PersonalSessionContext)
            block(PersonalSessionContext)?.let { return it }
        }

        val candidates = TimeMoviesPool.getCatalogCandidateSlots()
        for (slot in candidates) {
            val slotSession = PoolSlotSessionContext(slot.slotId)
            ensureSessionProfileInitialized(slotSession)
            val result = block(slotSession)
            if (result != null) {
                TimeMoviesPool.markSlotHealthy(slot.slotId)
                return result
            }
            TimeMoviesPool.markSlotAuthFailed(slot.slotId)
        }
        return null
    }

    private fun ensureSessionProfileInitialized(session: AuthSessionContext) {
        if (session.profileId.isEmpty() && session.authToken.isNotBlank()) {
            syncAppDataAndSelectMainProfileForSession(session)
        }
    }

    // ---- Synthetic Virtual Device Telemetry (`UserDevice.java` + `wh7.java`) ----

    fun buildUserDeviceJson(): JSONObject = TimeMoviesPrefs.getOrCreateVirtualDevice().toApiUserDeviceJson()

    // ---- Authentication (`SignIn`, `SignUp`, `ActivateOtp`, `ResendOtp`, `ManualToken`) ----

    fun signInWithEmail(email: String, password: String, recheckEmail: Boolean = false): ApiEnvelope<String> {
        val cleanEmail = email.trim().lowercase(Locale.US)
        syncServerClockIfNeeded(force = true)

        val payload = JSONObject().apply {
            put("email", cleanEmail)
            put("isEmailError", false)
            put("emailError", JSONObject.NULL)
            put("pass", password)
            put("isPassError", false)
            put("passError", JSONObject.NULL)
            put("device", buildUserDeviceJson())
            put("recheckEmail", recheckEmail)
        }.toString()

        val res = runCatching {
            executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_EMAIL_SIGN_IN",
                method = "POST",
                rawJsonBody = payload,
                session = UnauthenticatedSessionContext,
                noAuth = true,
                allowRetry = false,
            )
        }.getOrElse { err ->
            return ApiEnvelope(false, 0, err.message ?: "Network error", null)
        }

        if (res.body.isBlank()) {
            return ApiEnvelope(false, res.code, "استجابة فارغة من الخادم (${res.code})", null)
        }

        return runCatching<ApiEnvelope<String>> {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", res.code)
            val ok = isEnvelopeOk(obj)
            val message = obj.optNonBlank("message")
            val refusal = parseEmailRefusal(obj.optJSONObject("email_refusal"))
            val token = obj.optNonBlank("result")

            if (res.code in 200..299 && ok && !token.isNullOrBlank()) {
                val claims = TimeMoviesPrefs.decodeJwtClaims(token)
                val explicitlyUnactivated = claims != null && (
                    (claims.has("ia") && !claims.optBoolean("ia", true)) ||
                        (claims.has("is_active") && !claims.optBoolean("is_active", true))
                    )
                if ((refusal != null && refusal.action == 2) || explicitlyUnactivated) {
                    TimeMoviesPrefs.pendingOtpToken = token
                    TimeMoviesPrefs.pendingOtpEmail = cleanEmail
                    TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
                    ensureSessionDeviceKeyEnrolled(OtpPendingSessionContext(token))
                    if (refusal == null) {
                        runCatching { resendActivationOtp(cleanEmail) }
                    }
                    return@runCatching ApiEnvelope(
                        false,
                        status,
                        message ?: "الحساب غير مفعل بعد — يرجى إدخال رمز التفعيل المرسل لبريدك",
                        token,
                        refusal,
                    )
                }

                TimeMoviesPrefs.authToken = token
                TimeMoviesPrefs.userEmail = cleanEmail
                TimeMoviesPrefs.pendingOtpToken = null
                TimeMoviesPrefs.pendingOtpEmail = null
                TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)

                ensureSessionDeviceKeyEnrolled(PersonalSessionContext)
                syncAppDataAndSelectMainProfile()
                TimeMoviesPool.autoArchiveCurrentPersonalSession()

                ApiEnvelope(true, status, message, token, refusal)
            } else {
                ApiEnvelope(false, status, message ?: "فشل تسجيل الدخول (${res.code})", null, refusal)
            }
        }.getOrElse { err ->
            ApiEnvelope(false, res.code, err.message ?: "خطأ في قراءة الاستجابة", null)
        }
    }

    /**
     * Registers a new free account (`UserEmailSignUp`).
     * Rotates to a brand-new synthetic retail Android handset + canonical 91B SPKI P-256 keypair first.
     */
    fun signUpWithEmail(
        name: String,
        email: String,
        password: String,
        recheckEmail: Boolean = false,
        rotateVirtualDevice: Boolean = true,
    ): ApiEnvelope<String> {
        val cleanEmail = email.trim().lowercase(Locale.US)
        val rawName = name.trim().ifEmpty { "Mehdi" }
        // `b13.java:75` enforces `name.length >= 6` — pad short names like `"Mehdi"` -> `"Mehdi M"` cleanly!
        val cleanName = if (rawName.length >= 6) rawName else "$rawName M".padEnd(6, 'i')

        if (rotateVirtualDevice) {
            TimeMoviesPrefs.rotateVirtualDeviceIdentity()
        }
        syncServerClockIfNeeded(force = true)

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

        val res = runCatching {
            executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_EMAIL_SIGN_UP",
                method = "POST",
                rawJsonBody = payload,
                session = UnauthenticatedSessionContext,
                noAuth = true,
                allowRetry = false,
            )
        }.getOrElse { err ->
            return ApiEnvelope(false, 0, err.message ?: "Network error", null)
        }

        if (res.body.isBlank()) {
            return ApiEnvelope(false, res.code, "استجابة فارغة من الخادم (${res.code})", null)
        }

        return runCatching<ApiEnvelope<String>> {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", res.code)
            val ok = isEnvelopeOk(obj)
            val message = obj.optNonBlank("message")
            val refusal = parseEmailRefusal(obj.optJSONObject("email_refusal"))
            val token = obj.optNonBlank("result")

            if (res.code in 200..299 && ok && !token.isNullOrBlank()) {
                val claims = TimeMoviesPrefs.decodeJwtClaims(token)
                val alreadyActive = claims?.optBoolean("ia", false) == true ||
                    claims?.optBoolean("is_active", false) == true
                if (alreadyActive) {
                    TimeMoviesPrefs.authToken = token
                    TimeMoviesPrefs.userEmail = cleanEmail
                    TimeMoviesPrefs.pendingOtpToken = null
                    TimeMoviesPrefs.pendingOtpEmail = null
                    ensureSessionDeviceKeyEnrolled(PersonalSessionContext)
                    syncAppDataAndSelectMainProfile()
                    TimeMoviesPool.autoArchiveCurrentPersonalSession()
                } else {
                    TimeMoviesPrefs.pendingOtpToken = token
                    TimeMoviesPrefs.pendingOtpEmail = cleanEmail
                    ensureSessionDeviceKeyEnrolled(OtpPendingSessionContext(token))
                }
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
     */
    fun activateEmailOtp(otpCode: String): ApiEnvelope<String> {
        val pendingToken = TimeMoviesPrefs.pendingOtpToken
            ?: return ApiEnvelope(false, 0, "لا توجد جلسة تسجيل معلقة — يرجى إنشاء الحساب أولاً", null)

        val cleanOtp = normalizeOtpCode(otpCode)
        if (cleanOtp.isEmpty()) {
            return ApiEnvelope(false, 0, "يرجى إدخال رمز التفعيل (OTP)", null)
        }

        val otpSession = OtpPendingSessionContext(pendingToken)
        syncServerClockIfNeeded(force = false)
        var dk = ensureSessionDeviceKeyEnrolled(otpSession)
        if (dk.isNullOrBlank()) {
            syncServerClockIfNeeded(force = true)
            dk = ensureSessionDeviceKeyEnrolled(otpSession)
        }
        if (dk.isNullOrBlank()) {
            val detail = lastEnrollmentError?.let { " ($it)" }.orEmpty()
            return ApiEnvelope(false, 0, "تعذر تسجيل مفتاح أمان الجهاز لدى الخادم قبل التفعيل$detail", null)
        }

        val payload = JSONObject().apply {
            put("otp", cleanOtp)
            put("isOtpError", false)
            put("otpError", JSONObject.NULL)
        }.toString()

        val res = runCatching {
            executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_EMAIL_ACTIVATE_OTP",
                method = "POST",
                rawJsonBody = payload,
                session = otpSession,
                allowRetry = true,
            )
        }.getOrElse { err ->
            return ApiEnvelope(false, 0, err.message ?: "Network error", null)
        }

        if (res.body.isBlank()) {
            return ApiEnvelope(false, res.code, "استجابة فارغة من الخادم (${res.code})", null)
        }

        return runCatching<ApiEnvelope<String>> {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", res.code)
            val ok = isEnvelopeOk(obj)
            val message = obj.optNonBlank("message")
            val refusal = parseEmailRefusal(obj.optJSONObject("email_refusal"))
            val resultStr = obj.optNonBlank("result")

            if (res.code in 200..299 && ok) {
                val returnedFreshJwt = looksLikeJwt(resultStr)
                val initialToken = if (returnedFreshJwt) resultStr!! else otpSession.authToken
                val devId = TimeMoviesPrefs.deviceId
                val oldScope = TimeMoviesPrefs.computeTokenScope(otpSession.authToken, devId)
                val existingDk = TimeMoviesPrefs.getDeviceKeyIdForScope(oldScope)

                TimeMoviesPrefs.authToken = initialToken
                TimeMoviesPrefs.userEmail = TimeMoviesPrefs.pendingOtpEmail
                TimeMoviesPrefs.pendingOtpToken = null
                TimeMoviesPrefs.pendingOtpEmail = null

                if (!existingDk.isNullOrBlank()) {
                    val initScope = TimeMoviesPrefs.computeTokenScope(initialToken, devId)
                    TimeMoviesPrefs.setDeviceKeyIdForScope(initScope, existingDk)
                } else {
                    ensureSessionDeviceKeyEnrolled(PersonalSessionContext)
                }

                // If `UserEmailActivate` did not embed a new JWT in `result`, refresh immediately
                // so JWT claims upgrade from `ia: false` -> `ia: true` before `media/grant`!
                if (!returnedFreshJwt) {
                    if (tryRefreshSessionAuthToken(PersonalSessionContext) && !existingDk.isNullOrBlank()) {
                        val refreshed = TimeMoviesPrefs.authToken.orEmpty()
                        if (refreshed.isNotBlank()) {
                            val refreshedScope = TimeMoviesPrefs.computeTokenScope(refreshed, devId)
                            TimeMoviesPrefs.setDeviceKeyIdForScope(refreshedScope, existingDk)
                        }
                    }
                }

                val finalToken = TimeMoviesPrefs.authToken ?: initialToken
                syncAppDataAndSelectMainProfile()
                TimeMoviesPool.autoArchiveCurrentPersonalSession()
                ApiEnvelope(true, status, message, finalToken, refusal)
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
                executeV47RequestWithSession(
                    url = "${apiBaseUrl()}$PATH_EMAIL_FORGOT_OR_RESEND",
                    method = "POST",
                    rawJsonBody = payload,
                    session = OtpPendingSessionContext(pendingTk),
                    allowRetry = false,
                )
            } else {
                executeV47RequestWithSession(
                    url = "${apiBaseUrl()}$PATH_EMAIL_FORGOT_OR_RESEND",
                    method = "POST",
                    rawJsonBody = payload,
                    session = UnauthenticatedSessionContext,
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

        return runCatching<ApiEnvelope<String>> {
            val obj = JSONObject(res.body)
            val status = obj.optInt("status", res.code)
            val ok = isEnvelopeOk(obj)
            val msg = obj.optNonBlank("message")
            val tok = obj.optNonBlank("result")
            if (res.code in 200..299 && ok) {
                if (looksLikeJwt(tok)) {
                    TimeMoviesPrefs.pendingOtpToken = tok
                    ensureSessionDeviceKeyEnrolled(OtpPendingSessionContext(tok!!))
                }
                ApiEnvelope(true, status, msg ?: "تم إرسال رمز تفعيل جديد إلى بريدك الإلكتروني", tok)
            } else {
                ApiEnvelope(false, status, msg ?: "تعذر إعادة إرسال الرمز (${res.code})", null)
            }
        }.getOrElse { err ->
            ApiEnvelope(false, res.code, err.message ?: "خطأ في قراءة الاستجابة", null)
        }
    }

    fun applyManualToken(rawToken: String): Pair<Boolean, String> {
        val clean = rawToken.trim().removePrefix("Bearer ").trim()
        if (!looksLikeJwt(clean)) {
            return false to "صيغة التوكن غير صالحة (يجب أن يكون JWT مكوناً من 3 أجزاء يبدأ بـ eyJ)"
        }
        TimeMoviesPrefs.authToken = clean
        TimeMoviesPrefs.pendingOtpToken = null
        TimeMoviesPrefs.pendingOtpEmail = null
        TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
        syncServerClockIfNeeded(force = true)
        val dk = ensureSessionDeviceKeyEnrolled(PersonalSessionContext)
        val profile = syncAppDataAndSelectMainProfile()
        TimeMoviesPool.autoArchiveCurrentPersonalSession()
        return if (profile != null || !dk.isNullOrBlank()) {
            true to "تم تفعيل التوكن وربط مفتاح الجهاز بنجاح"
        } else {
            true to "تم حفظ التوكن (تحقق من صلاحيته إذا لم يظهر المحتوى)"
        }
    }

    // ---- AppData, User Profiles (`USP`), & Default Profile Auto-Creation (`USPE`) ----

    fun syncAppDataAndSelectMainProfile(): UserProfile? {
        if (!TimeMoviesPrefs.isSignedIn) return null
        return syncAppDataAndSelectMainProfileForSession(PersonalSessionContext)
    }

    internal fun syncAppDataAndSelectMainProfileForSession(session: AuthSessionContext): UserProfile? {
        if (session.authToken.isBlank()) return null

        // 1. Sync `AppData` (`POST 546635c490224ed1a74961d8e06198d1`) — updates `tk` & `ausp` if returned
        runCatching {
            val body = JSONObject().apply {
                put("device", session.virtualDevice.toApiUserDeviceJson())
            }.toString()
            val res = executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_APP_DATA",
                method = "POST",
                rawJsonBody = body,
                session = session,
            )
            if (res.code in 200..299 && res.body.isNotBlank()) {
                val obj = JSONObject(res.body)
                val resObj = obj.optJSONObject("result") ?: obj

                val updatedTk = resObj.optNonBlank("tk")
                if (looksLikeJwt(updatedTk) && updatedTk != session.authToken) {
                    session.saveRefreshedToken(updatedTk!!)
                    ensureSessionDeviceKeyEnrolled(session)
                }

                val ausp = resObj.optJSONObject("ausp")
                    ?: resObj.optJSONObject("usp")
                    ?: resObj.optJSONObject("au")?.optJSONArray("usps")?.optJSONObject(0)
                if (ausp != null) {
                    parseUserProfile(ausp)?.let { prof ->
                        session.saveResolvedProfile(prof)
                        return prof
                    }
                }
            }
        }

        // 2. Fetch existing profiles (`GET 2468218428cc4065b0e37dc68c950505`)
        val profiles = fetchUserProfilesForSession(session)
        val chosen = profiles.firstOrNull { it.isMain && !it.isPinSet && !it.isVipLocked }
            ?: profiles.firstOrNull { !it.isPinSet && !it.isVipLocked }
            ?: profiles.firstOrNull()
        if (chosen != null) {
            session.saveResolvedProfile(chosen)
            return chosen
        }

        // 3. Newly registered account with zero profiles -> auto-create `"Mehdi"` main profile (`POST 83fd64...`)
        return createDefaultMainProfileForSession(session)
    }

    fun fetchUserProfiles(): List<UserProfile> {
        if (!TimeMoviesPrefs.isSignedIn) return emptyList()
        return fetchUserProfilesForSession(PersonalSessionContext)
    }

    private fun fetchUserProfilesForSession(session: AuthSessionContext): List<UserProfile> = runCatching {
        val res = executeV47RequestWithSession(
            url = "${apiBaseUrl()}$PATH_USER_PROFILES",
            method = "GET",
            session = session,
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

    private fun createDefaultMainProfileForSession(session: AuthSessionContext): UserProfile? = runCatching {
        val createPayload = JSONObject().apply {
            put("name", "Mehdi")
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

        val res = executeV47RequestWithSession(
            url = "${apiBaseUrl()}$PATH_CREATE_PROFILE",
            method = "POST",
            rawJsonBody = createPayload,
            session = session,
        )
        if (res.code in 200..299 && res.body.isNotBlank()) {
            val resObj = JSONObject(res.body).optJSONObject("result")
            val created = resObj?.let { parseUserProfile(it) }
            if (created != null) {
                session.saveResolvedProfile(created)
                return created
            }
        }
        fetchUserProfilesForSession(session).firstOrNull()?.also { session.saveResolvedProfile(it) }
    }.getOrNull()

    fun saveActiveProfile(profile: UserProfile) {
        TimeMoviesPrefs.profileId = profile.id
        TimeMoviesPrefs.profileName = profile.name
        TimeMoviesPrefs.profileMaturity = profile.maturity
    }

    // ---- Catalog, Home, Filters, Genres, Search, Work Details (Personal + Pool Failover) ----

    fun getAppSettings(): JSONObject? = runCatching {
        val session = if (TimeMoviesPrefs.isSignedIn) PersonalSessionContext else UnauthenticatedSessionContext
        val res = executeV47RequestWithSession(
            url = "${apiBaseUrl()}$PATH_APP_SETTINGS",
            method = "GET",
            session = session,
            noAuth = !TimeMoviesPrefs.isSignedIn,
            allowRetry = false,
        )
        if (res.code in 200..299 && res.body.isNotBlank()) {
            val obj = JSONObject(res.body)
            obj.optJSONObject("result") ?: obj
        } else {
            null
        }
    }.getOrNull()

    fun getHome(): HomeResponse? = executeCatalogWithFailover { session ->
        runCatching {
            val res = executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_HOME",
                method = "GET",
                session = session,
            )
            if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
            val obj = JSONObject(res.body)
            if (!isEnvelopeOk(obj)) return@runCatching null
            val resObj = obj.optJSONObject("result") ?: return@runCatching null
            parseHomeResponse(resObj)
        }.getOrNull()
    }

    fun getPageFilter(level: Int, page0Based: Int): PageFilterResult? = executeCatalogWithFailover { session ->
        runCatching {
            val url = "${apiBaseUrl()}$PATH_PAGE_FILTER".toHttpUrl().newBuilder()
                .addQueryParameter("page", page0Based.coerceAtLeast(0).toString())
                .addQueryParameter("level", level.toString())
                .build()
                .toString()

            val res = executeV47RequestWithSession(url = url, method = "GET", session = session)
            if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
            val resObj = JSONObject(res.body).optJSONObject("result") ?: return@runCatching null
            val works = parseWorkCardList(resObj.optJSONArray("works"))
            PageFilterResult(
                works = works,
                page = resObj.optInt("page", page0Based),
                isLast = resObj.optBoolean("is_last", works.isEmpty()),
            )
        }.getOrNull()
    }

    fun getCategoryPage(
        catId: String,
        lastId: String?,
        lastVal: String?,
    ): CategoryPageResult? = executeCatalogWithFailover { session ->
        runCatching {
            val urlBuilder = "${apiBaseUrl()}$PATH_CATEGORY_MORE".toHttpUrl().newBuilder()
                .addQueryParameter("catId", catId)
            if (!lastId.isNullOrBlank()) urlBuilder.addQueryParameter("li", lastId)
            if (!lastVal.isNullOrBlank()) urlBuilder.addQueryParameter("lv", lastVal)

            val res = executeV47RequestWithSession(
                url = urlBuilder.build().toString(),
                method = "GET",
                session = session,
            )
            if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
            val resObj = JSONObject(res.body).optJSONObject("result") ?: return@runCatching null
            val works = parseWorkCardList(resObj.optJSONArray("works"))
            CategoryPageResult(
                works = works,
                lastId = resObj.optNonBlank("last_id") ?: resObj.optNonBlank("lastId"),
                lastVal = resObj.optNonBlank("last_val") ?: resObj.optNonBlank("lastVal"),
                isLast = resObj.optBoolean("is_last", works.isEmpty()),
            )
        }.getOrNull()
    }

    fun getGenres(): List<GenreItem> = executeCatalogWithFailover { session ->
        runCatching {
            val res = executeV47RequestWithSession(
                url = "${apiBaseUrl()}$PATH_GENRES_LIST",
                method = "GET",
                session = session,
            )
            if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
            val arr = JSONObject(res.body).optJSONArray("result") ?: return@runCatching null
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
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    } ?: emptyList()

    fun getGenreWorks(
        genreId: String,
        isMovie: Boolean,
        lastAt: String? = null,
        lastId: String? = null,
        sort: Int = 0,
        sortDir: Int = 1,
    ): GenrePageResult? = executeCatalogWithFailover { session ->
        runCatching {
            val urlBuilder = "${apiBaseUrl()}$PATH_GENRE_WORKS".toHttpUrl().newBuilder()
                .addQueryParameter("gId", genreId)
                .addQueryParameter("ism", isMovie.toString())
                .addQueryParameter("s", sort.toString())
                .addQueryParameter("sd", sortDir.toString())
            if (!lastAt.isNullOrBlank()) urlBuilder.addQueryParameter("la", lastAt)
            if (!lastId.isNullOrBlank()) urlBuilder.addQueryParameter("li", lastId)

            val res = executeV47RequestWithSession(
                url = urlBuilder.build().toString(),
                method = "GET",
                session = session,
            )
            if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
            val resObj = JSONObject(res.body).optJSONObject("result") ?: return@runCatching null
            val works = parseWorkCardList(resObj.optJSONArray("works"))
            GenrePageResult(
                works = works,
                lastAt = resObj.optNonBlank("last_at") ?: resObj.optNonBlank("lastAt"),
                lastId = resObj.optNonBlank("last_id") ?: resObj.optNonBlank("lastId"),
                isLast = resObj.optBoolean("is_last", works.isEmpty()),
            )
        }.getOrNull()
    }

    fun searchTriple(query: String, page0Based: Int = 0): SearchResultPage? = executeCatalogWithFailover { session ->
        runCatching {
            val url = "${apiBaseUrl()}$PATH_SEARCH".toHttpUrl().newBuilder()
                .addQueryParameter("search", query.trim())
                .addQueryParameter("page", page0Based.coerceAtLeast(0).toString())
                .build()
                .toString()

            val res = executeV47RequestWithSession(url = url, method = "GET", session = session)
            if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
            val resObj = JSONObject(res.body).optJSONObject("result") ?: return@runCatching null
            val movies = parseWorkCardList(resObj.optJSONArray("movies"))
                .map { if (it.isMovie == null) it.copy(isMovie = true) else it }
            val series = parseWorkCardList(resObj.optJSONArray("series"))
                .map { if (it.isMovie == null) it.copy(isMovie = false) else it }
            SearchResultPage(
                movies = movies,
                series = series,
                page = resObj.optInt("page", page0Based),
                isLastMovies = resObj.optBoolean("is_last_movies", movies.isEmpty()),
                isLastSeries = resObj.optBoolean("is_last_series", series.isEmpty()),
            )
        }.getOrNull()
    }

    fun getWorkDetails(workId: String): WorkDetails? {
        val fromAuthenticated = executeCatalogWithFailover { session ->
            runCatching {
                val url = "${apiBaseUrl()}$PATH_WORK_DETAILS".toHttpUrl().newBuilder()
                    .addQueryParameter("wk", workId.trim())
                    .build()
                    .toString()
                val res = executeV47RequestWithSession(url = url, method = "GET", session = session)
                if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
                val resObj = JSONObject(res.body).optJSONObject("result") ?: return@runCatching null
                parseWorkDetails(resObj)
            }.getOrNull()
        }
        if (fromAuthenticated != null) return fromAuthenticated
        return getDoomPlayerWorkFallback(workId)
    }

    fun getDoomPlayerWorkFallback(workId: String): WorkDetails? = runCatching {
        val url = "${doomBaseUrl()}apw".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("wk", workId.trim())
            ?.build() ?: return null

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
            val resObj = JSONObject(body).optJSONObject("result") ?: return null
            parseWorkDetails(resObj)
        }
    }.getOrNull()

    // ---- Media V2 Qualities, Grant (with Multi-Account Quota Failover!), & DoomVideoPlayer Resolvers ----

    fun getMediaQualities(fileCode: String, action: String = "download"): List<MediaQualityItem> =
        executeCatalogWithFailover { session ->
            runCatching {
                val payload = JSONObject().apply {
                    put("action", action)
                    put("file_code", fileCode)
                    put("surface", 1)
                }.toString()
                val res = executeV47RequestWithSession(
                    url = "${apiBaseUrl()}$PATH_MEDIA_QUALITIES",
                    method = "POST",
                    rawJsonBody = payload,
                    session = session,
                )
                if (res.code !in 200..299 || res.body.isBlank()) return@runCatching null
                val obj = JSONObject(res.body)
                val resObj = obj.optJSONObject("result") ?: obj
                val qArr = resObj.optJSONArray("qualities") ?: return@runCatching null
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
                out
            }.getOrNull()
        } ?: emptyList()

    private fun shouldFailoverPoolOnGrantRefusal(reason: String?, httpCode: Int): Boolean {
        if (httpCode in setOf(401, 402, 403, 429)) return true
        val norm = reason?.trim()?.lowercase(Locale.US).orEmpty()
        if (norm.isEmpty()) return false
        if (norm in QUOTA_OR_POOL_ROTATE_REASONS) return true
        return norm.contains("quota") ||
            norm.contains("limit") ||
            norm.contains("device") ||
            norm.contains("plan") ||
            norm.contains("vip") ||
            norm.contains("entitled") ||
            norm.contains("suspend") ||
            norm.contains("ban")
    }

    private fun executeSingleGrantAttempt(
        session: AuthSessionContext,
        bodyJson: String,
    ): Pair<Int, MediaGrantPayload?> = runCatching {
        ensureSessionProfileInitialized(session)
        val res = executeV47RequestWithSession(
            url = "${apiBaseUrl()}$PATH_MEDIA_GRANT",
            method = "POST",
            rawJsonBody = bodyJson,
            session = session,
        )
        if (res.body.isBlank()) return res.code to null
        val obj = JSONObject(res.body)
        val resObj = obj.optJSONObject("result") ?: obj
        res.code to parseMediaGrant(resObj)
    }.getOrDefault(0 to null)

    /**
     * Calls `POST /api/v4.7/app/media/grant` (`MediaGrantRequest.java` + `at5.java`).
     * Automatically rotates across Built-in Pool slots whenever an account reaches its daily quota!
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
    ): MediaGrantPayload? {
        val buildGrantBody: (String?, String) -> String = { opId, attemptKey ->
            JSONObject().apply {
                put("action", action)
                put("surface", 1)
                put("package", TimeMoviesCrypto.APP_PACKAGE)
                put("file_code", fileCode)
                put("work_id", workId)
                if (!episodeId.isNullOrBlank()) put("episode_id", episodeId)
                if (!seasonId.isNullOrBlank()) put("season_id", seasonId)
                if (quality != null) put("quality", quality)
                if (!codes.isNullOrEmpty()) put("codes", JSONArray(codes))
                if (!executorTarget.isNullOrBlank()) put("executor_target", executorTarget)
                put("player_installed", playerInstalled)
                if (!opId.isNullOrBlank()) put("operation_id", opId)
                put("client_attempt_key", attemptKey)
            }.toString()
        }

        var lastRefusalPayload: MediaGrantPayload? = null
        var exhaustedPersonalUserId: String? = null

        // 1. Personal account first (if signed in)
        if (TimeMoviesPrefs.isSignedIn) {
            val personalUid = TimeMoviesPool.extractJwtUserId(TimeMoviesPrefs.authToken)
            val (code, parsed) = executeSingleGrantAttempt(
                session = PersonalSessionContext,
                bodyJson = buildGrantBody(operationId, clientAttemptKey),
            )
            if (parsed != null) {
                if (parsed.ok || parsed.reason == "player_not_installed" || parsed.reason == "execution_failed") {
                    lastSuccessfulGrantSession = PersonalSessionContext
                    if (parsed.ok) lastMediaRefusalReason = null
                    return parsed
                }
                lastRefusalPayload = parsed
                if (!parsed.reason.isNullOrBlank()) {
                    lastMediaRefusalReason = parsed.reason
                }
                // If personal failure is NOT a quota/device/plan limit, don't mask it
                if (!shouldFailoverPoolOnGrantRefusal(parsed.reason, code)) {
                    return parsed
                }
                exhaustedPersonalUserId = personalUid
                TimeMoviesPool.markUserIdQuotaExhausted(personalUid, action = action)
            }
        }

        // 2. Built-in Pre-Activated + Local Auto-Vault Pool Slots (automatic round-robin + quota failover!)
        val poolCandidates = TimeMoviesPool.getMediaCandidateSlots(
            action = action,
            excludeUserId = exhaustedPersonalUserId,
        )
        for ((idx, slot) in poolCandidates.withIndex()) {
            val slotSession = PoolSlotSessionContext(slot.slotId)
            // Fresh attempt key when failing over to a distinct pool account (`operationId` belongs to a single account)
            val effectiveOpId = if (idx == 0 && !TimeMoviesPrefs.isSignedIn) operationId else null
            val effectiveAttemptKey = if (idx == 0 && !TimeMoviesPrefs.isSignedIn) {
                clientAttemptKey
            } else {
                UUID.randomUUID().toString()
            }

            val (code, parsed) = executeSingleGrantAttempt(
                session = slotSession,
                bodyJson = buildGrantBody(effectiveOpId, effectiveAttemptKey),
            )
            if (parsed != null && (parsed.ok || parsed.reason == "player_not_installed" || parsed.reason == "execution_failed")) {
                TimeMoviesPool.markSlotHealthy(slot.slotId)
                lastSuccessfulGrantSession = slotSession
                if (parsed.ok) lastMediaRefusalReason = null
                return parsed
            }

            if (code == 401 || code == 403) {
                TimeMoviesPool.markSlotAuthFailed(slot.slotId)
            } else if (parsed != null && shouldFailoverPoolOnGrantRefusal(parsed.reason, code)) {
                TimeMoviesPool.markSlotQuotaExhausted(slot.slotId, action = action)
            }

            if (parsed != null) {
                lastRefusalPayload = parsed
                if (!parsed.reason.isNullOrBlank() && parsed.reason != "player_not_installed") {
                    lastMediaRefusalReason = parsed.reason
                }
            }
        }

        return lastRefusalPayload
    }

    /**
     * Executes `fx.D` / `fx.R0` (`Lex;->F`, `q52.java:512`) against `https://api.apks.lol/v2/media/`.
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

        val preferredSession = lastSuccessfulGrantSession
            ?: if (TimeMoviesPrefs.isSignedIn) PersonalSessionContext
            else TimeMoviesPool.getCatalogCandidateSlots().firstOrNull()?.let { PoolSlotSessionContext(it.slotId) }
            ?: return null

        val res = executeV47RequestWithSession(
            url = url,
            method = method,
            rawJsonBody = body,
            session = preferredSession,
            extraHeaders = mapOf("X-Media-Grant" to grantToken),
            allowRetry = true,
        )
        if (res.code !in 200..299 || res.body.isBlank()) return null
        val obj = JSONObject(res.body)
        parseMediaV2(obj.optJSONObject("result") ?: obj)
    }.getOrNull()

    fun resolveDoomPlayTicket(srcTicket: String): String? {
        if (srcTicket.startsWith("http://", true) || srcTicket.startsWith("https://", true)) {
            return srcTicket
        }
        resolveDoomEndpoint(DOOM_RESOLVE_PLAY, srcTicket)?.let { return it }
        return resolveDoomEndpoint(DOOM_LEGACY_PLAY, srcTicket)
    }

    fun resolveDoomDownloadTicket(srcTicket: String): String? {
        if (srcTicket.startsWith("http://", true) || srcTicket.startsWith("https://", true)) {
            return srcTicket
        }
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
