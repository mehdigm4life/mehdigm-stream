package com.timemovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class TimeMoviesUnitTest {

    @Before
    fun setUp() {
        TimeMoviesPrefs.resetAllForTests()
        TimeMoviesApi.resetRuntimeStateForTests()
    }

    @After
    fun tearDown() {
        TimeMoviesPrefs.resetAllForTests()
        TimeMoviesApi.resetRuntimeStateForTests()
    }

    // ---- Pure `java.net.ServerSocket` HTTP/1.1 Test Server (works on Android SDK + JVM) ----

    private data class MiniRequest(
        val method: String,
        val path: String,
        val rawQuery: String?,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    }

    private data class MiniResponse(
        val code: Int,
        val contentType: String,
        val body: String,
        val serverNowMs: Long = System.currentTimeMillis(),
    )

    private class MiniHttpServer(
        private val handler: (MiniRequest) -> MiniResponse,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        private val pool = Executors.newCachedThreadPool()
        val port: Int = serverSocket.localPort
        val baseOrigin: String = "http://127.0.0.1:$port"

        init {
            pool.execute {
                while (running.get()) {
                    val sock = try {
                        serverSocket.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    pool.execute { handleClient(sock) }
                }
            }
        }

        private fun handleClient(socket: Socket) {
            socket.use { sock ->
                sock.soTimeout = 10_000
                val input = BufferedInputStream(sock.getInputStream())
                val reqLine = readAsciiLine(input) ?: return
                val parts = reqLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].trim()
                val targetUri = URI("http://127.0.0.1${parts[1].trim()}")

                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val hLine = readAsciiLine(input) ?: break
                    if (hLine.isEmpty()) break
                    val idx = hLine.indexOf(':')
                    if (idx > 0) {
                        val k = hLine.substring(0, idx).trim().lowercase(Locale.US)
                        val v = hLine.substring(idx + 1).trim()
                        headers[k] = v
                    }
                }

                val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(contentLen)
                var readTotal = 0
                while (readTotal < contentLen) {
                    val r = input.read(bodyBytes, readTotal, contentLen - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                val reqBody = String(bodyBytes, 0, readTotal, Charsets.UTF_8)

                val resp = try {
                    handler(
                        MiniRequest(
                            method = method,
                            path = targetUri.rawPath ?: "/",
                            rawQuery = targetUri.rawQuery,
                            headers = headers,
                            body = reqBody,
                        )
                    )
                } catch (t: Throwable) {
                    MiniResponse(500, "application/json", """{"error":"${t.message}"}""")
                }

                val respBytes = resp.body.toByteArray(Charsets.UTF_8)
                val statusText = when (resp.code) {
                    200 -> "OK"
                    401 -> "Unauthorized"
                    403 -> "Forbidden"
                    404 -> "Not Found"
                    409 -> "Conflict"
                    else -> "Status"
                }
                val head = buildString {
                    append("HTTP/1.1 ${resp.code} $statusText\r\n")
                    append("Content-Type: ${resp.contentType}\r\n")
                    append("Date: ${formatHttpDate(resp.serverNowMs)}\r\n")
                    append("Content-Length: ${respBytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)

                val out = sock.getOutputStream()
                out.write(head)
                out.write(respBytes)
                out.flush()
            }
        }

        private fun readAsciiLine(input: BufferedInputStream): String? {
            val buf = ByteArrayOutputStream()
            var sawByte = false
            while (true) {
                val b = input.read()
                if (b == -1) return if (sawByte) buf.toString("UTF-8") else null
                sawByte = true
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString("UTF-8")
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }

        companion object {
            fun formatHttpDate(epochMs: Long): String {
                val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                return sdf.format(Date(epochMs))
            }
        }
    }

    private fun jsonResp(code: Int, body: String, serverNowMs: Long = System.currentTimeMillis()): MiniResponse =
        MiniResponse(code, "application/json; charset=utf-8", body, serverNowMs)

    private fun makeTestJwt(userId: String, email: String, deviceId: String, sid: String = "sid_01"): String {
        val hdr = TimeMoviesCrypto.base64UrlEncodeNoPad("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = JSONObject().apply {
            put("_id", userId)
            put("e", email)
            put("d", deviceId)
            put("sid", sid)
            put("iv", false)
            put("fq", 5)
            put("dcr", 3)
        }.toString()
        val body = TimeMoviesCrypto.base64UrlEncodeNoPad(payload.toByteArray())
        return "$hdr.$body.test_sig"
    }

    @Test
    fun testCanonicalP256SpkiPkcs8SelfTestAndOtpNormalization() {
        val kp = TimeMoviesCrypto.generateP256KeyPair()
        val spkiBytes = TimeMoviesCrypto.base64DecodeFlexible(kp.publicKeySpkiB64Url)
        val pkcs8Bytes = TimeMoviesCrypto.base64DecodeFlexible(kp.privateKeyPkcs8B64Url)

        assertEquals("SPKI must be strictly canonical 91 bytes", 91, spkiBytes.size)
        assertEquals("PKCS#8 must be canonical 138 bytes", 138, pkcs8Bytes.size)
        assertTrue(kp.publicKeySpkiB64Url.startsWith("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"))
        assertNotNull(TimeMoviesCrypto.normalizeOrValidateKeyPair(kp.privateKeyPkcs8B64Url, kp.publicKeySpkiB64Url))

        val sample = """{"_id":"674e4e33ecd3e83dbef4adaa","e":"mehdi@gmail.com","sid":"sess_01","fq":5,"dcr":2,"iv":false}"""
        val sig = TimeMoviesCrypto.signEcdsaSha256DerB64Url(kp.privateKeyPkcs8B64Url, sample.toByteArray(Charsets.UTF_8))
        assertTrue(TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(kp.publicKeySpkiB64Url, sample.toByteArray(Charsets.UTF_8), sig))

        val encoded = TimeMoviesCrypto.base64UrlEncodeNoPad(sample.toByteArray(Charsets.UTF_8))
        assertFalse(encoded.contains("="))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))

        val fakeJwt = "${TimeMoviesCrypto.base64UrlEncodeNoPad("""{"alg":"HS256"}""".toByteArray())}.$encoded.sig"
        val claims = TimeMoviesPrefs.decodeJwtClaims(fakeJwt)
        assertNotNull(claims)
        assertEquals("mehdi@gmail.com", claims!!.optString("e"))
        assertEquals("sess_01", claims.optString("sid"))

        assertEquals("482910", TimeMoviesApi.normalizeOtpCode(" ٤٨٢-٩١٠ "))
        assertEquals("739105", TimeMoviesApi.normalizeOtpCode("۷۳۹ ۱۰۵"))
    }

    @Test
    fun testVirtualDeviceBypassAndIdentityRotation() {
        val dev1 = TimeMoviesPrefs.getOrCreateVirtualDevice()
        val json1 = TimeMoviesApi.buildUserDeviceJson()

        assertTrue(Regex("^[0-9a-f]{16}$").matches(json1.getString("unique_id")))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(json1.getString("device_id")))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(json1.getString("rasplab_install_id")))
        assertEquals("Handset", json1.getString("device_type"))
        assertEquals("Android", json1.getString("system_name"))
        assertEquals("1092", json1.getString("build_number"))
        assertEquals("com.extremeqr.proapps", json1.getString("device_package"))
        assertTrue(json1.getString("device_fingerprint").contains(":user/release-keys"))

        val kp1 = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        val dev2 = TimeMoviesPrefs.rotateVirtualDeviceIdentity()
        val kp2 = TimeMoviesPrefs.getOrCreateDeviceKeyPair()

        assertNotEquals(dev1.uniqueId, dev2.uniqueId)
        assertNotEquals(dev1.deviceId, dev2.deviceId)
        assertNotEquals(dev1.rasplabInstallId, dev2.rasplabInstallId)
        assertNotEquals(dev1.model, dev2.model)
        assertNotEquals(kp1.publicKeySpkiB64Url, kp2.publicKeySpkiB64Url)
    }

    @Test
    fun testEndToEndSignUpWithMehdiAndOtpActivationWithoutKeyRotation409() {
        val serverSkewMs = 300_000L
        val enrolledKeys = ConcurrentHashMap<String, String>()
        val sessionBoundPk = ConcurrentHashMap<String, String>()
        val otpAttempts = AtomicInteger(0)
        var capturedSignUpName: String? = null
        var capturedSignUpRasplabId: String? = null
        lateinit var baseOrigin: String

        MiniHttpServer { req ->
            val nowServer = System.currentTimeMillis() + serverSkewMs
            val path = req.path
            val rawQuery = req.rawQuery

            when {
                path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) ->
                    jsonResp(200, """{"success":true,"status":200,"result":{"v":1092}}""", nowServer)

                path.endsWith(TimeMoviesApi.PATH_EMAIL_SIGN_UP) -> {
                    val bodyObj = JSONObject(req.body)
                    capturedSignUpName = bodyObj.optString("name")
                    val devObj = bodyObj.getJSONObject("device")
                    capturedSignUpRasplabId = devObj.optString("rasplab_install_id")
                    val pendingJwt = makeTestJwt("usr_mehdi_1", bodyObj.getString("email"), devObj.getString("device_id"))
                    jsonResp(
                        200,
                        JSONObject().put("success", true).put("status", 200).put("result", pendingJwt).toString(),
                        nowServer,
                    )
                }

                path.endsWith(TimeMoviesApi.PATH_DEVICE_CHALLENGE) -> {
                    if (!req.header("x-p").isNullOrEmpty() && TimeMoviesPrefs.authToken == null) {
                        return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Request verification failed."}""", nowServer)
                    }
                    jsonResp(
                        200,
                        """{"success":true,"status":200,"result":{"cid":"cid_900","ch":"challenge_nonce_xyz","exp":9999999999}}""",
                        nowServer,
                    )
                }

                path.endsWith(TimeMoviesApi.PATH_DEVICE_REGISTER) -> {
                    val xt = req.header("x-t").orEmpty()
                    val bodyObj = JSONObject(req.body)
                    val cid = bodyObj.getString("cid")
                    val pk = bodyObj.getString("pk")
                    val sig = bodyObj.getString("sig")

                    // Enforce strict 91-byte SPKI & `409 rotation_requires_signin` on session key rotation!
                    val spkiBytes = TimeMoviesCrypto.base64DecodeFlexible(pk)
                    if (spkiBytes.size != 91) {
                        return@MiniHttpServer jsonResp(400, """{"success":false,"message":"invalid_spki_len"}""", nowServer)
                    }
                    val previousPk = sessionBoundPk.putIfAbsent(xt, pk)
                    if (previousPk != null && previousPk != pk) {
                        return@MiniHttpServer jsonResp(409, """{"success":false,"message":"rotation_requires_signin","status":409}""", nowServer)
                    }

                    val msgBytes = TimeMoviesCrypto.buildEnrollmentChallengePayload(cid, "challenge_nonce_xyz", pk)
                    if (!TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(pk, msgBytes, sig)) {
                        return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Invalid enrollment signature"}""", nowServer)
                    }
                    val newDk = "dk_${enrolledKeys.size + 1}"
                    enrolledKeys[newDk] = pk
                    jsonResp(200, """{"success":true,"status":200,"result":{"dk":"$newDk","alg":"ECDSA_SHA256_DER","st":"active"}}""", nowServer)
                }

                path.endsWith(TimeMoviesApi.PATH_EMAIL_ACTIVATE_OTP) -> {
                    val attempt = otpAttempts.incrementAndGet()
                    val xp = req.header("x-p").orEmpty()
                    val ma = req.header("m-a").orEmpty()
                    if (xp.isNotEmpty() || ma.isNotEmpty()) {
                        return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Request verification failed."}""", nowServer)
                    }

                    val tStr = req.header("t").orEmpty()
                    val tVal = tStr.toLongOrNull() ?: 0L
                    if (abs(tVal - nowServer) > 15_000L) {
                        return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Request verification failed."}""", nowServer)
                    }

                    val dk = req.header("dk").orEmpty()
                    val ds = req.header("ds").orEmpty()
                    val xt = req.header("x-t").orEmpty()
                    val n = req.header("n").orEmpty()
                    val pk = enrolledKeys[dk]
                    val fullUrl = "$baseOrigin$path${if (rawQuery != null) "?$rawQuery" else ""}".toHttpUrl()
                    val canonical = TimeMoviesCrypto.buildCanonicalV47SigningPayload(
                        method = req.method,
                        url = fullUrl,
                        timestamp = tStr,
                        nonce = n,
                        rawBody = req.body,
                        authToken = xt,
                        profileId = xp,
                        maturity = ma,
                        deviceKeyId = dk,
                    )
                    if (pk == null || !TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(pk, canonical.toByteArray(), ds)) {
                        return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Request verification failed."}""", nowServer)
                    }

                    // Inject stale-dk verification failure on attempt 1 to prove non-rotating re-enrollment recovery!
                    if (attempt == 1) {
                        enrolledKeys.remove(dk)
                        return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Request verification failed."}""", nowServer)
                    }

                    val otpObj = JSONObject(req.body)
                    assertEquals("654321", otpObj.getString("otp"))
                    jsonResp(200, """{"success":true,"status":200,"message":"Activated","result":"OK"}""", nowServer)
                }

                path.endsWith(TimeMoviesApi.PATH_APP_DATA) -> {
                    val activatedJwt = makeTestJwt("usr_mehdi_1", "mehdi@gmail.com", TimeMoviesPrefs.deviceId, "sid_live")
                    val resp = JSONObject().apply {
                        put("success", true)
                        put("status", 200)
                        put(
                            "result",
                            JSONObject().apply {
                                put("tk", activatedJwt)
                                put(
                                    "ausp",
                                    JSONObject().apply {
                                        put("_id", "prof_mehdi_main")
                                        put("name", "Mehdi")
                                        put("maturity", 5)
                                        put("is_main", true)
                                    },
                                )
                            },
                        )
                    }.toString()
                    jsonResp(200, resp, nowServer)
                }

                else -> jsonResp(404, """{"success":false}""", nowServer)
            }
        }.use { server ->
            baseOrigin = server.baseOrigin
            TimeMoviesPrefs.apiBaseUrlOverride = "$baseOrigin/api/v4.7/app/"
            TimeMoviesPrefs.mediaV2BaseUrlOverride = "$baseOrigin/v2/media/"
            TimeMoviesPrefs.doomBaseUrlOverride = "$baseOrigin/api/player/v1.6/app/"
            TimeMoviesPrefs.profileId = "stale_profile_from_old_account"
            TimeMoviesPrefs.profileMaturity = 3

            val signUpRes = TimeMoviesApi.signUpWithEmail("", "mehdi@gmail.com", "StrongPass123")
            assertTrue("Sign-up must succeed", signUpRes.success)
            assertTrue("Default name must start with Mehdi and be >= 6 chars per b13.java:75",
                capturedSignUpName.orEmpty().startsWith("Mehdi") && capturedSignUpName.orEmpty().length >= 6)
            assertTrue(Regex("^[0-9a-f]{32}$").matches(capturedSignUpRasplabId.orEmpty()))
            assertNotNull(TimeMoviesPrefs.pendingOtpToken)

            val otpRes = TimeMoviesApi.activateEmailOtp("٦٥٤-٣٢١")
            assertTrue("OTP activation failed: ${otpRes.message}", otpRes.success)
            assertEquals(2, otpAttempts.get())
            assertTrue(TimeMoviesPrefs.isSignedIn)
            assertTrue(TimeMoviesApi.looksLikeJwt(TimeMoviesPrefs.authToken))
            assertEquals("prof_mehdi_main", TimeMoviesPrefs.profileId)
            assertEquals("Mehdi", TimeMoviesPrefs.profileName)

            // Also verify 1-click Pool bundle export of the activated session
            val exported = TimeMoviesPool.exportCurrentPersonalSessionBundle().getOrThrow()
            assertTrue(exported.startsWith("tm3pool:v1:"))
            val unsealed = JSONObject(TimeMoviesCrypto.unsealPoolBundleJson(exported)!!)
            assertEquals(TimeMoviesPrefs.authToken, unsealed.getString("tk"))
            assertEquals("prof_mehdi_main", unsealed.getString("pid"))
        }
    }

    @Test
    fun testPoolBundleSealUnsealLoginlessBrowsingAndQuotaExhaustedFailover() = runBlocking {
        val kp1 = TimeMoviesCrypto.generateP256KeyPair()
        val kp2 = TimeMoviesCrypto.generateP256KeyPair()
        val tk1 = makeTestJwt("pool_usr_1", "pool1@gmail.com", "aabbccddeeff0011", "sid_pool_1")
        val tk2 = makeTestJwt("pool_usr_2", "pool2@gmail.com", "aabbccddeeff0022", "sid_pool_2")

        val enrolledKeys = ConcurrentHashMap<String, String>().apply {
            put("dk_pool_1", kp1.publicKeySpkiB64Url)
            put("dk_pool_2", kp2.publicKeySpkiB64Url)
        }
        val grantCallsByToken = ConcurrentHashMap<String, AtomicInteger>()
        lateinit var baseOrigin: String

        MiniHttpServer { req ->
            val path = req.path
            val rawQuery = req.rawQuery

            // Verify ECDSA v4.7 signature on every protected v4.7 pool request!
            if (path.startsWith("/api/v4.7/") && !path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS)) {
                val dk = req.header("dk").orEmpty()
                val ds = req.header("ds").orEmpty()
                val xt = req.header("x-t").orEmpty()
                val xp = req.header("x-p").orEmpty()
                val ma = req.header("m-a").orEmpty()
                val tStr = req.header("t").orEmpty()
                val n = req.header("n").orEmpty()
                val pk = enrolledKeys[dk]
                val fullUrl = "$baseOrigin$path${if (rawQuery != null) "?$rawQuery" else ""}".toHttpUrl()
                val canonical = TimeMoviesCrypto.buildCanonicalV47SigningPayload(
                    method = req.method,
                    url = fullUrl,
                    timestamp = tStr,
                    nonce = n,
                    rawBody = req.body,
                    authToken = xt,
                    profileId = xp,
                    maturity = ma,
                    deviceKeyId = dk,
                )
                if (pk == null || !TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(pk, canonical.toByteArray(), ds)) {
                    return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Request verification failed."}""")
                }
            }

            when {
                path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) ->
                    jsonResp(200, """{"success":true,"status":200,"result":{"v":1092}}""")

                path.endsWith(TimeMoviesApi.PATH_WORK_DETAILS) -> {
                    val wk = JSONObject().apply {
                        put("_id", "wk_pool_movie_77")
                        put("ism", true)
                        put("tl", "The Odyssey")
                        put("tlr", "الأوديسة")
                        put("fc", "fc_odyssey_2026")
                    }
                    jsonResp(200, JSONObject().put("success", true).put("result", wk).toString())
                }

                path.endsWith("/media/grant") -> {
                    val xt = req.header("x-t").orEmpty()
                    grantCallsByToken.computeIfAbsent(xt) { AtomicInteger(0) }.incrementAndGet()

                    // Pool Slot #1 has exhausted its daily free quota!
                    if (xt == tk1) {
                        return@MiniHttpServer jsonResp(
                            200,
                            """{"ok":false,"charged":false,"denial":{"reason":"quota_exhausted","message":"Daily limit reached"}}""",
                        )
                    }

                    // Pool Slot #2 succeeds!
                    assertEquals(tk2, xt)
                    assertEquals("prof_pool_2", req.header("x-p"))
                    val action = JSONObject(req.body).getString("action")
                    val grantTok = if (action == "watch") "pool2_watch_grant" else "pool2_dl_grant"
                    jsonResp(
                        200,
                        JSONObject().apply {
                            put("ok", true)
                            put("target", 1)
                            put("charged", true)
                            put("grant", grantTok)
                        }.toString(),
                    )
                }

                path == "/v2/media/manifest" -> {
                    assertEquals("pool2_watch_grant", req.header("X-Media-Grant"))
                    assertEquals("prof_pool_2", req.header("x-p"))
                    jsonResp(
                        200,
                        JSONObject().apply {
                            put("ok", true)
                            put("action", "watch")
                            put("src", "$baseOrigin/cdn/pool/odyssey_1080p.m3u8")
                        }.toString(),
                    )
                }

                path == "/v2/media/download" -> {
                    assertEquals("pool2_dl_grant", req.header("X-Media-Grant"))
                    assertEquals("prof_pool_2", req.header("x-p"))
                    val sources = JSONArray().put(
                        JSONObject().apply {
                            put("quality", 1080)
                            put("name", "1080p FHD")
                            put("link", "$baseOrigin/cdn/pool/odyssey_1080p.mp4")
                        }
                    )
                    jsonResp(200, JSONObject().put("ok", true).put("sources", sources).toString())
                }

                path == "/cdn/pool/odyssey_1080p.m3u8" -> {
                    val m3u8 = """
                        #EXTM3U
                        #EXT-X-VERSION:3
                        #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
                        prog_1080.m3u8
                    """.trimIndent()
                    MiniResponse(200, "application/vnd.apple.mpegurl", m3u8)
                }

                else -> jsonResp(404, """{"ok":false}""")
            }
        }.use { server ->
            baseOrigin = server.baseOrigin
            TimeMoviesPrefs.apiBaseUrlOverride = "$baseOrigin/api/v4.7/app/"
            TimeMoviesPrefs.mediaV2BaseUrlOverride = "$baseOrigin/v2/media/"
            TimeMoviesPrefs.doomBaseUrlOverride = "$baseOrigin/api/player/v1.6/app/"

            // Confirm user is NOT personally signed in!
            TimeMoviesPrefs.signOut()
            assertFalse(TimeMoviesPrefs.isSignedIn)

            // Seal and register 2 pre-activated pool accounts
            val sealed1 = TimeMoviesCrypto.sealPoolBundleJson(
                JSONObject().apply {
                    put("id", "slot_1")
                    put("tk", tk1)
                    put("dk", "dk_pool_1")
                    put("pk", kp1.publicKeySpkiB64Url)
                    put("sk", kp1.privateKeyPkcs8B64Url)
                    put("pid", "prof_pool_1")
                    put("mat", 5)
                }.toString()
            )
            val sealed2 = TimeMoviesCrypto.sealPoolBundleJson(
                JSONObject().apply {
                    put("id", "slot_2")
                    put("tk", tk2)
                    put("dk", "dk_pool_2")
                    put("pk", kp2.publicKeySpkiB64Url)
                    put("sk", kp2.privateKeyPkcs8B64Url)
                    put("pid", "prof_pool_2")
                    put("mat", 5)
                }.toString()
            )
            assertEquals(2, TimeMoviesPool.addLocalPoolBundles("$sealed1\n$sealed2"))
            assertTrue("Pool must satisfy hasUsableAuth without personal login", TimeMoviesApi.hasUsableAuth(allowNetworkFetch = false))

            // 1. Loginless `load()` details fetch via Pool
            val provider = TimeMovies()
            val loadResp = provider.load("https://timemovies.app/work/wk_pool_movie_77?type=movie")
            assertNotNull("Loginless load() must return MovieLoadResponse", loadResp)
            assertEquals("الأوديسة", loadResp!!.name)

            // 2. Loginless `loadLinks()` where Slot #1 fails with `quota_exhausted` -> auto-rotates to Slot #2!
            val links = ArrayList<ExtractorLink>()
            val subs = ArrayList<SubtitleFile>()
            val ok = provider.loadLinks(
                data = TimeMoviesModels.LinkPayload(
                    workId = "wk_pool_movie_77",
                    fileCode = "",
                    isMovie = true,
                    title = "الأوديسة",
                ).toJson(),
                isCasting = false,
                subtitleCallback = { subs.add(it) },
                callback = { links.add(it) },
            )

            assertTrue("Loginless Pool failover loadLinks() must succeed", ok)
            assertEquals("Slot #1 must be tried once then cooled down on quota_exhausted", 1, grantCallsByToken[tk1]?.get() ?: 0)
            assertEquals("Slot #2 must handle both watch + download grants", 2, grantCallsByToken[tk2]?.get() ?: 0)
            assertTrue(links.any { it.type == ExtractorLinkType.M3U8 && it.url.endsWith("prog_1080.m3u8") })
            assertTrue(links.any { it.type == ExtractorLinkType.VIDEO && it.url.endsWith("odyssey_1080p.mp4") })
        }
    }

    @Test
    fun testEndToEndInternalOriginWatchAndDownloadLinksAndSubtitles() = runBlocking {
        val postToMediaV2Detected = AtomicInteger(0)
        lateinit var baseOrigin: String

        MiniHttpServer { req ->
            when {
                req.path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) ->
                    jsonResp(200, """{"success":true,"status":200,"result":{"v":1092}}""")

                req.path.endsWith(TimeMoviesApi.PATH_DEVICE_CHALLENGE) ->
                    jsonResp(200, """{"success":true,"status":200,"result":{"cid":"cid_1","ch":"ch_1","exp":9999999999}}""")

                req.path.endsWith(TimeMoviesApi.PATH_DEVICE_REGISTER) ->
                    jsonResp(200, """{"success":true,"status":200,"result":{"dk":"dk_live_1","alg":"ECDSA_SHA256_DER","st":"active"}}""")

                req.path.endsWith(TimeMoviesApi.PATH_WORK_DETAILS) -> {
                    val wk = JSONObject().apply {
                        put("_id", "wk_movie_99")
                        put("ism", true)
                        put("tl", "Interstellar")
                        put("tlr", "بين النجوم")
                        put("fc", "fc_interstellar_2014")
                    }
                    jsonResp(200, JSONObject().put("success", true).put("result", wk).toString())
                }

                req.path.endsWith("/media/grant") -> {
                    assertNotNull(req.header("dk"))
                    assertNotNull(req.header("ds"))
                    assertEquals("prof_1", req.header("x-p"))
                    val body = JSONObject(req.body)
                    assertEquals("com.extremeqr.proapps", body.getString("package"))
                    assertEquals("fc_interstellar_2014", body.getString("file_code"))
                    val action = body.getString("action")
                    val grantTok = if (action == "watch") "grant_watch_tok" else "grant_dl_tok"
                    jsonResp(
                        200,
                        JSONObject().apply {
                            put("ok", true)
                            put("target", 1)
                            put("charged", true)
                            put("grant", grantTok)
                        }.toString(),
                    )
                }

                req.path == "/v2/media/manifest" -> {
                    if (req.method != "GET") {
                        postToMediaV2Detected.incrementAndGet()
                        return@MiniHttpServer jsonResp(404, """{"error":"Cannot POST /v2/media/manifest"}""")
                    }
                    assertEquals("grant_watch_tok", req.header("X-Media-Grant"))
                    assertEquals("1092", req.header("x-b"))
                    assertEquals("prof_1", req.header("x-p"))
                    jsonResp(
                        200,
                        JSONObject().apply {
                            put("ok", true)
                            put("action", "watch")
                            put("src", "$baseOrigin/cdn/hls/interstellar/master.m3u8?sig=tok123")
                        }.toString(),
                    )
                }

                req.path == "/v2/media/download" -> {
                    if (req.method != "GET") {
                        postToMediaV2Detected.incrementAndGet()
                        return@MiniHttpServer jsonResp(404, """{"error":"Cannot POST /v2/media/download"}""")
                    }
                    assertEquals("grant_dl_tok", req.header("X-Media-Grant"))
                    val sources = JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("quality", 1080)
                                put("name", "1080p FHD")
                                put("size", "2.1 GB")
                                put("link", "$baseOrigin/cdn/files/interstellar_1080p.mp4")
                            },
                        )
                        put(
                            JSONObject().apply {
                                put("quality", 720)
                                put("name", "720p HD")
                                put("size", "1.1 GB")
                                put("link", "$baseOrigin/cdn/files/interstellar_720p.mp4")
                            },
                        )
                    }
                    jsonResp(200, JSONObject().put("ok", true).put("sources", sources).toString())
                }

                req.path == "/cdn/hls/interstellar/master.m3u8" -> {
                    val m3u8 = """
                        #EXTM3U
                        #EXT-X-VERSION:3
                        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Arabic",DEFAULT=YES,LANGUAGE="ar",URI="subs/ar.vtt"
                        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,LANGUAGE="en",URI="subs/en.vtt"
                        #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,SUBTITLES="subs"
                        1080p/index.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1280x720,SUBTITLES="subs"
                        720p/index.m3u8
                    """.trimIndent()
                    MiniResponse(200, "application/vnd.apple.mpegurl", m3u8)
                }

                else -> jsonResp(404, """{"ok":false}""")
            }
        }.use { server ->
            baseOrigin = server.baseOrigin
            TimeMoviesPrefs.apiBaseUrlOverride = "$baseOrigin/api/v4.7/app/"
            TimeMoviesPrefs.mediaV2BaseUrlOverride = "$baseOrigin/v2/media/"
            TimeMoviesPrefs.doomBaseUrlOverride = "$baseOrigin/api/player/v1.6/app/"

            val dev = TimeMoviesPrefs.getOrCreateVirtualDevice()
            TimeMoviesPrefs.authToken = makeTestJwt("usr_1", "mehdi@gmail.com", dev.deviceId)
            TimeMoviesPrefs.profileId = "prof_1"
            TimeMoviesPrefs.profileMaturity = 5

            val links = ArrayList<ExtractorLink>()
            val subs = ArrayList<SubtitleFile>()
            val linkData = TimeMoviesModels.LinkPayload(
                workId = "wk_movie_99",
                fileCode = "",
                isMovie = true,
                title = "Interstellar",
            ).toJson()

            val ok = TimeMovies().loadLinks(
                data = linkData,
                isCasting = false,
                subtitleCallback = { subs.add(it) },
                callback = { links.add(it) },
            )

            assertTrue("loadLinks must succeed", ok)
            assertEquals("Origin /v2/media/* must use GET, never POST", 0, postToMediaV2Detected.get())

            val hlsLinks = links.filter { it.type == ExtractorLinkType.M3U8 }
            val mp4Links = links.filter { it.type == ExtractorLinkType.VIDEO }

            assertTrue("Expected Auto + 1080p + 720p HLS streams, got: $hlsLinks", hlsLinks.size >= 3)
            val link1080 = hlsLinks.firstOrNull { it.name.contains("[1080p]") && it.quality == 1080 }
            assertNotNull("Expected 1080p HLS variant link", link1080)
            assertTrue("1080p variant with #EXT-X-MEDIA must be served via local Master M3U8", link1080!!.url.startsWith("http://127.0.0.1:"))
            val local1080Body = fetchRawHttpText(link1080.url)
            assertTrue(
                "Synthesized 1080p Master M3U8 must preserve signed query token on relative variant & subtitles",
                local1080Body.contains("$baseOrigin/cdn/hls/interstellar/1080p/index.m3u8?sig=tok123") &&
                    local1080Body.contains("$baseOrigin/cdn/hls/interstellar/subs/ar.vtt?sig=tok123"),
            )
            assertEquals("Expected 1080p + 720p direct MP4 download links", 2, mp4Links.size)
            assertTrue(mp4Links.any { it.quality == 1080 && it.url.endsWith("interstellar_1080p.mp4") })
            assertTrue(mp4Links.any { it.quality == 720 && it.url.endsWith("interstellar_720p.mp4") })
            assertEquals("Expected Arabic + English standalone .vtt subtitle tracks", 2, subs.size)
            assertTrue(subs.any { it.lang.contains("العربية") && it.url.contains("subs/ar.vtt?sig=tok123") })
        }
    }

    @Test
    fun testEndToEndExternalDoomPlayerFallbackWithExtensionlessHlsAndExecutionRetry() = runBlocking {
        val externalWatchAttempts = AtomicInteger(0)
        lateinit var baseOrigin: String

        MiniHttpServer { req ->
            when {
                req.path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) ->
                    jsonResp(200, """{"success":true,"result":{"v":1092}}""")

                req.path.endsWith(TimeMoviesApi.PATH_DEVICE_CHALLENGE) ->
                    jsonResp(200, """{"success":true,"result":{"cid":"c2","ch":"h2","exp":9999999999}}""")

                req.path.endsWith(TimeMoviesApi.PATH_DEVICE_REGISTER) ->
                    jsonResp(200, """{"success":true,"result":{"dk":"dk_2","alg":"ECDSA_SHA256_DER","st":"active"}}""")

                req.path.endsWith("/media/grant") -> {
                    val body = JSONObject(req.body)
                    val action = body.getString("action")
                    val playerInstalled = body.optBoolean("player_installed", false)
                    val executorTarget = body.optString("executor_target", "")

                    if (!playerInstalled || executorTarget != "external") {
                        return@MiniHttpServer jsonResp(200, """{"ok":false,"reason":"player_not_installed","charged":false}""")
                    }

                    if (action == "watch") {
                        val att = externalWatchAttempts.incrementAndGet()
                        if (att == 1) {
                            return@MiniHttpServer jsonResp(
                                200,
                                """{"ok":false,"reason":"origin_unreachable","operation_id":"op_retry_1","charged":true}""",
                            )
                        }
                        assertEquals("op_retry_1", body.optString("operation_id"))
                        val inner = "${URLEncoder.encode("enc_watch_ticket_99", "UTF-8")}/${URLEncoder.encode("Dune Part Two", "UTF-8")}/prof_2/wk_55/@/0"
                        val b64 = TimeMoviesCrypto.base64UrlEncodeNoPad(inner.toByteArray())
                        jsonResp(
                            200,
                            JSONObject().apply {
                                put("ok", true)
                                put("target", 2)
                                put("charged", true)
                                put(
                                    "external",
                                    JSONObject().apply {
                                        put("descriptor", "timesnizz://doomplayer.com/$b64")
                                        put("is_ticket", true)
                                    },
                                )
                            }.toString(),
                        )
                    } else {
                        val dlInner = "${URLEncoder.encode("enc_dl_ticket_1080", "UTF-8")}/${URLEncoder.encode("Dune 1080p", "UTF-8")}/prof_2/wk_55/@/0"
                        val dlB64 = TimeMoviesCrypto.base64UrlEncodeNoPad(dlInner.toByteArray())
                        jsonResp(
                            200,
                            JSONObject().apply {
                                put("ok", true)
                                put("target", 2)
                                put("charged", true)
                                put(
                                    "external",
                                    JSONObject().apply {
                                        put("descriptor", "timesdizz://doomplayer.com/$dlB64")
                                        put("is_ticket", true)
                                    },
                                )
                            }.toString(),
                        )
                    }
                }

                req.path == "/api/player/v1.6/app/resolve-play" -> {
                    assertEquals("1065", req.header("x-b"))
                    assertEquals("6a5216d91e12eeadc194ab91", req.header("ai"))
                    val src = JSONObject(req.body).getString("src")
                    assertEquals("enc_watch_ticket_99", src)
                    jsonResp(200, """{"success":true,"status":200,"result":{"url":"$baseOrigin/cdn/live/dune_stream_token_xyz"}}""")
                }

                req.path == "/api/player/v1.6/app/resolve-download" -> {
                    val src = JSONObject(req.body).getString("src")
                    assertEquals("enc_dl_ticket_1080", src)
                    jsonResp(200, """{"success":true,"status":200,"result":{"url":"$baseOrigin/cdn/dl/dune_1080p.mp4"}}""")
                }

                req.path == "/cdn/live/dune_stream_token_xyz" -> {
                    val m3u8 = """
                        #EXTM3U
                        #EXT-X-VERSION:3
                        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Arabic",DEFAULT=YES,LANGUAGE="ar",URI="ar.vtt"
                        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,SUBTITLES="subs"
                        v1080/prog.m3u8
                    """.trimIndent()
                    MiniResponse(200, "application/x-mpegURL", m3u8)
                }

                else -> jsonResp(404, """{"success":false}""")
            }
        }.use { server ->
            baseOrigin = server.baseOrigin
            TimeMoviesPrefs.apiBaseUrlOverride = "$baseOrigin/api/v4.7/app/"
            TimeMoviesPrefs.mediaV2BaseUrlOverride = "$baseOrigin/v2/media/"
            TimeMoviesPrefs.doomBaseUrlOverride = "$baseOrigin/api/player/v1.6/app/"

            val dev = TimeMoviesPrefs.getOrCreateVirtualDevice()
            TimeMoviesPrefs.authToken = makeTestJwt("usr_2", "mehdi2@gmail.com", dev.deviceId)
            TimeMoviesPrefs.profileId = "prof_2"
            TimeMoviesPrefs.profileMaturity = 5

            val links = ArrayList<ExtractorLink>()
            val subs = ArrayList<SubtitleFile>()
            val payload = TimeMoviesModels.LinkPayload(
                workId = "wk_55",
                fileCode = "fc_dune_2",
                isMovie = true,
                title = "Dune Part Two",
            )

            val ok = TimeMoviesExtractors.extractAllLinks(
                payload = payload,
                subtitleCallback = { subs.add(it) },
                callback = { links.add(it) },
            )

            assertTrue("External DoomVideoPlayer fallback must succeed", ok)
            assertEquals("Must retry ExecutionFailed with operation_id", 2, externalWatchAttempts.get())

            val m3u8Links = links.filter { it.type == ExtractorLinkType.M3U8 }
            val videoLinks = links.filter { it.type == ExtractorLinkType.VIDEO }

            assertTrue("Extensionless DoomPlayer play URL must be emitted as M3U8", m3u8Links.size >= 2)
            val autoHls = m3u8Links.firstOrNull { it.name.contains("[Auto HLS]") }
            val hls1080 = m3u8Links.firstOrNull { it.name.contains("[1080p]") && it.quality == 1080 }
            assertNotNull("Expected Auto HLS link", autoHls)
            assertNotNull("Expected 1080p HLS link", hls1080)
            assertTrue(autoHls!!.url.startsWith("http://127.0.0.1:"))
            assertTrue(hls1080!!.url.startsWith("http://127.0.0.1:"))
            assertTrue(fetchRawHttpText(autoHls.url).contains("$baseOrigin/cdn/live/v1080/prog.m3u8"))
            assertTrue(fetchRawHttpText(hls1080.url).contains("$baseOrigin/cdn/live/v1080/prog.m3u8"))
            assertEquals("Expected resolved DoomPlayer MP4 download link", 1, videoLinks.size)
            assertEquals("$baseOrigin/cdn/dl/dune_1080p.mp4", videoLinks[0].url)
            assertEquals(1, subs.size)
        }
    }

    private fun fetchRawHttpText(url: String): String {
        val req = okhttp3.Request.Builder().url(url).get().build()
        TimeMoviesApi.plainClient.newCall(req).execute().use { resp ->
            assertEquals("Expected HTTP 200 for $url", 200, resp.code)
            return resp.body?.string().orEmpty()
        }
    }

    @Test
    fun testDemuxedDoomPlayerHlsAudioSubtitlesAndExpiredProxy404Immunity() = runBlocking {
        val proxyMasterReads = AtomicInteger(0)
        lateinit var baseOrigin: String

        MiniHttpServer { req ->
            when {
                // Simulates `https://api.apks.lol/api/player/v1.6/app/m?t=...&s=...`:
                // returns 200 OK ONLY once during `loadLinks`, then returns HTTP 404 (`ERROR_CODE_IO_BAD_HTTP_STATUS 2004`)
                // on every subsequent request!
                req.path == "/api/player/v1.6/app/m" -> {
                    if (proxyMasterReads.incrementAndGet() > 1) {
                        return@MiniHttpServer MiniResponse(404, "text/plain", "Not Found")
                    }
                    // Exact Kaltura `nginx-vod-module` (`sv505.egy.rocks`) demuxed layout (`#EXT-X-MEDIA` after `#EXT-X-STREAM-INF`)
                    val cdnDir = "https://sv505.egy.rocks/gx5wj0z6m4m8obow/hls/6a660677e726aa532689b2fb.json"
                    val tok = "token=st=1792922228~exp=1792936628~acl=/gx5wj0z6m4m8obow/hls/6a660677e726aa532689b2fb.json/*~hmac=93b9b57da"
                    val m3u8 = """
                        #EXTM3U
                        #EXT-X-INDEPENDENT-SEGMENTS
                        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3800000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="audio",SUBTITLES="subs"
                        $cdnDir/index-f1-v1.m3u8?$tok
                        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1850000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2",AUDIO="audio",SUBTITLES="subs"
                        $cdnDir/index-f2-v1.m3u8?$tok
                        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=620000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="audio",SUBTITLES="subs"
                        $cdnDir/index-f3-v1.m3u8?$tok
                        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en",URI="$cdnDir/index-f1-a1.m3u8?$tok"
                        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Ara",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="ar",URI="$cdnDir/index-f4-v1.m3u8?$tok"
                        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Eng-SDH",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE="en",URI="$cdnDir/index-f5-v1.m3u8?$tok"
                    """.trimIndent()
                    MiniResponse(200, "application/vnd.apple.mpegurl", m3u8)
                }

                else -> jsonResp(404, """{"ok":false}""")
            }
        }.use { server ->
            baseOrigin = server.baseOrigin
            val links = ArrayList<ExtractorLink>()
            val subs = ArrayList<SubtitleFile>()

            val ok = TimeMoviesExtractors.emitResolvedHttpUrl(
                url = "$baseOrigin/api/player/v1.6/app/m?t=ephemeral_tok&s=sig_1",
                sourceLabel = "DoomPlayer Stream",
                qualityHint = null,
                isWatch = true,
                subtitleCallback = { subs.add(it) },
                callback = { links.add(it) },
            )

            assertTrue(ok)
            assertEquals("Ephemeral /app/m proxy URL must be read once during loadLinks", 1, proxyMasterReads.get())
            assertEquals(
                "Segmented HLS .m3u8 subtitle playlists must NOT be emitted to SubripParser subtitleCallback",
                0,
                subs.size,
            )

            assertEquals("Expected Auto HLS + 1080p + 720p + 360p", 4, links.size)
            val autoLink = links.first { it.name == "DoomPlayer Stream [Auto HLS]" }
            val link1080 = links.first { it.name == "DoomPlayer Stream [1080p]" }
            val link720 = links.first { it.name == "DoomPlayer Stream [720p]" }
            val link360 = links.first { it.name == "DoomPlayer Stream [360p]" }

            assertEquals(1080, autoLink.quality)
            assertEquals(1080, link1080.quality)
            assertEquals(720, link720.quality)
            assertEquals(360, link360.quality)

            // Re-fetch `[Auto HLS]` THREE times (simulating PreviewImgM3u8 + ExoPlayer + switching back 10 mins later
            // when upstream `/api/player/v1.6/app/m` already returns HTTP 404!):
            repeat(3) {
                val autoBody = fetchRawHttpText(autoLink.url)
                assertTrue(autoBody.contains("index-f1-v1.m3u8"))
                assertTrue(autoBody.contains("index-f2-v1.m3u8"))
                assertTrue(autoBody.contains("index-f3-v1.m3u8"))
                assertTrue(autoBody.contains("TYPE=AUDIO") && autoBody.contains("index-f1-a1.m3u8"))
                assertTrue(autoBody.contains("TYPE=SUBTITLES") && autoBody.contains("index-f4-v1.m3u8"))
                assertTrue(autoBody.contains("TYPE=SUBTITLES") && autoBody.contains("index-f5-v1.m3u8"))
            }
            assertEquals("Local HLS server must never touch expired /app/m again", 1, proxyMasterReads.get())

            // Verify EVERY single-quality link (`1080p`, `720p`, `360p`) is a complete RFC 8216 Master M3U8
            // containing ONLY its locked video resolution PLUS FULL AUDIO + FULL ARABIC & ENGLISH SUBTITLES!
            val body720 = fetchRawHttpText(link720.url)
            assertTrue("720p must contain 720p video stream", body720.contains("index-f2-v1.m3u8"))
            assertFalse("720p must NOT contain 1080p video stream", body720.contains("index-f1-v1.m3u8"))
            assertFalse("720p must NOT contain 360p video stream", body720.contains("index-f3-v1.m3u8"))
            assertTrue("720p must preserve demuxed audio rendition", body720.contains("TYPE=AUDIO") && body720.contains("index-f1-a1.m3u8"))
            assertTrue("720p must preserve Arabic & English HLS subtitles", body720.contains("index-f4-v1.m3u8") && body720.contains("index-f5-v1.m3u8"))

            val body360 = fetchRawHttpText(link360.url)
            assertTrue("360p must contain 360p video stream", body360.contains("index-f3-v1.m3u8"))
            assertFalse("360p must NOT contain 720p video stream", body360.contains("index-f2-v1.m3u8"))
            assertTrue("360p must preserve demuxed audio rendition", body360.contains("TYPE=AUDIO") && body360.contains("index-f1-a1.m3u8"))
            assertTrue("360p must preserve Arabic & English HLS subtitles", body360.contains("index-f4-v1.m3u8") && body360.contains("index-f5-v1.m3u8"))
        }
    }

    @Test
    fun testUnactivatedAccountSignInTriggersOtpAndRefreshesJwt() {
        val resendCount = AtomicInteger(0)
        val refreshCount = AtomicInteger(0)
        lateinit var unactiveJwt: String
        lateinit var activeJwt: String

        MiniHttpServer { req ->
            when {
                req.path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) ->
                    jsonResp(200, """{"success":true,"result":{"v":1092}}""")

                req.path.endsWith(TimeMoviesApi.PATH_DEVICE_CHALLENGE) ->
                    jsonResp(200, """{"success":true,"result":{"cid":"c_ia","ch":"h_ia","exp":9999999999}}""")

                req.path.endsWith(TimeMoviesApi.PATH_DEVICE_REGISTER) ->
                    jsonResp(200, """{"success":true,"result":{"dk":"dk_ia_1","alg":"ECDSA_SHA256_DER","st":"active"}}""")

                req.path.endsWith(TimeMoviesApi.PATH_EMAIL_SIGN_IN) ->
                    jsonResp(200, JSONObject().put("success", true).put("status", 200).put("result", unactiveJwt).toString())

                req.path.endsWith(TimeMoviesApi.PATH_EMAIL_FORGOT_OR_RESEND) -> {
                    resendCount.incrementAndGet()
                    jsonResp(200, """{"success":true,"status":200,"result":"sent"}""")
                }

                req.path.endsWith(TimeMoviesApi.PATH_EMAIL_ACTIVATE_OTP) ->
                    jsonResp(200, """{"success":true,"status":200,"result":"OK"}""")

                req.path.endsWith(TimeMoviesApi.PATH_REFRESH_TOKEN) -> {
                    refreshCount.incrementAndGet()
                    jsonResp(200, JSONObject().put("success", true).put("status", 200).put("result", activeJwt).toString())
                }

                req.path.endsWith(TimeMoviesApi.PATH_APP_DATA) -> {
                    val prof = JSONObject().put("_id", "prof_ia").put("n", "Main").put("im", true).put("m", 5)
                    val res = JSONObject().put("profiles", JSONArray().put(prof))
                    jsonResp(200, JSONObject().put("success", true).put("result", res).toString())
                }

                else -> jsonResp(404, """{"success":false}""")
            }
        }.use { server ->
            TimeMoviesPrefs.apiBaseUrlOverride = "${server.baseOrigin}/api/v4.7/app/"
            val dev = TimeMoviesPrefs.getOrCreateVirtualDevice()

            val hdr = TimeMoviesCrypto.base64UrlEncodeNoPad("""{"alg":"HS256"}""".toByteArray())
            val pFalse = JSONObject().put("_id", "u_ia").put("e", "friend@gmail.com").put("d", dev.deviceId).put("sid", "s_ia").put("ia", false).toString()
            val pTrue = JSONObject().put("_id", "u_ia").put("e", "friend@gmail.com").put("d", dev.deviceId).put("sid", "s_ia").put("ia", true).toString()
            unactiveJwt = "$hdr.${TimeMoviesCrypto.base64UrlEncodeNoPad(pFalse.toByteArray())}.sig1"
            activeJwt = "$hdr.${TimeMoviesCrypto.base64UrlEncodeNoPad(pTrue.toByteArray())}.sig2"

            val signInRes = TimeMoviesApi.signInWithEmail("friend@gmail.com", "Pass1234!")
            assertFalse("Unactivated JWT (ia=false) must NOT complete sign-in silently", signInRes.success)
            assertEquals("Must stage pendingOtpToken for OTP dialog", unactiveJwt, TimeMoviesPrefs.pendingOtpToken)
            assertEquals("Must auto-request OTP resend when ia=false without refusal", 1, resendCount.get())

            val otpRes = TimeMoviesApi.activateEmailOtp("654321")
            assertTrue("OTP activation must succeed: ${otpRes.message}", otpRes.success)
            assertEquals("Must refresh JWT when activate returns non-JWT 'OK'", 1, refreshCount.get())
            assertEquals("Saved authToken must be upgraded to ia=true JWT", activeJwt, TimeMoviesPrefs.authToken)
        }
    }

    @Test
    fun testExternalHosterGenericHtmlAndSubtitleExtraction() = runBlocking {
        val sampleHtml = """
            <!DOCTYPE html>
            <html><body>
            <script>
            var links = {"hls4": "/stream/hls/master_1080p.m3u8?tok=abc", "file": "https://cdn.example.org/video/direct_720p.mp4"};
            var sub = "https://cdn.example.org/subs/arabic.vtt";
            </script>
            </body></html>
        """.trimIndent()

        val links = ArrayList<ExtractorLink>()
        val subs = ArrayList<SubtitleFile>()
        val ok = TimeMoviesExtractors.extractGenericHtmlEmbed(
            embedUrl = "https://cavanhabg.com/e/xyz123",
            sourceLabel = "StreamHG",
            prefetchedHtml = sampleHtml,
            subtitleCallback = { subs.add(it) },
            callback = { links.add(it) },
        )

        assertTrue(ok)
        assertTrue(links.any { it.type == ExtractorLinkType.M3U8 && it.url == "https://cavanhabg.com/stream/hls/master_1080p.m3u8?tok=abc" })
        assertTrue(links.any { it.type == ExtractorLinkType.VIDEO && it.url == "https://cdn.example.org/video/direct_720p.mp4" })
        assertEquals(1, subs.size)
        assertEquals("https://cdn.example.org/subs/arabic.vtt", subs[0].url)
    }

    @Test
    fun testLiveApiApksLolConnectivityClockSyncAndDoomPlayerWhenOnline() {
        val ast = TimeMoviesApi.getAppSettings() ?: return
        assertTrue(ast.optBoolean("iv47", true))
        assertTrue(ast.optString("vdl").startsWith("timesnizz://") || ast.length() > 0)
        assertTrue(ast.optString("ddl").startsWith("timesdizz://") || ast.length() > 0)
        assertTrue("Clock offset must be persisted after live response", TimeMoviesPrefs.hasPersistedClockOffset)

        // 1. Live VirtualDeviceProfile + "Mehdi" Sign-Up validation against production `api.apks.lol`
        val signUpProbe = TimeMoviesApi.signUpWithEmail("Mehdi", "mehdi.v3.check@mailinator.com", "MehdiPass2026!")
        assertNotNull(signUpProbe.message)
        assertFalse(
            "Sign-up must NOT fail with Request verification failed: ${signUpProbe.message}",
            signUpProbe.message.orEmpty().contains("Request verification failed", ignoreCase = true),
        )

        // 2. Live `POST /api/v4.7/app/media/grant` route verification on `api.apks.lol`
        val grantBody = """{"action":"watch","surface":1,"package":"com.extremeqr.proapps","file_code":"probe_fc","work_id":"probe_wk"}"""
            .toByteArray(Charsets.UTF_8)
            .toRequestBody(null)
        val grantReq = okhttp3.Request.Builder()
            .url("${TimeMoviesCrypto.API_BASE_URL}media/grant")
            .post(grantBody)
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("x-b", TimeMoviesCrypto.APP_BUILD)
            .header("ai", TimeMoviesCrypto.APP_ID)
            .header("av", TimeMoviesCrypto.APP_VERSION_TOKEN)
            .build()
        TimeMoviesApi.plainClient.newCall(grantReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            assertNotEquals("POST media/grant must exist (not 404)", 404, resp.code)
            assertFalse(body.contains("Cannot POST", ignoreCase = true))
        }

        // 3. Live `DoomVideoPlayer` v1.6 `settings`, `resolve-play`, & `resolve-download` route verification
        val settingsReq = okhttp3.Request.Builder()
            .url("${TimeMoviesCrypto.DOOM_BASE_URL}settings")
            .get()
            .header("User-Agent", "okhttp/4.12.0")
            .header("Accept", "application/json")
            .header("x-b", TimeMoviesCrypto.DOOM_BUILD)
            .header("ai", TimeMoviesCrypto.DOOM_APP_ID)
            .build()
        TimeMoviesApi.plainClient.newCall(settingsReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            assertNotEquals("DoomPlayer v1.6 settings must exist (not 404)", 404, resp.code)
            assertFalse(body.contains("Cannot GET", ignoreCase = true))
        }

        for (doomEndpoint in listOf("resolve-play", "resolve-download")) {
            val reqBody = """{"src":"invalid_probe_ticket"}"""
                .toByteArray(Charsets.UTF_8)
                .toRequestBody(null)
            val doomReq = okhttp3.Request.Builder()
                .url("${TimeMoviesCrypto.DOOM_BASE_URL}$doomEndpoint")
                .post(reqBody)
                .header("User-Agent", "okhttp/4.12.0")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("x-b", TimeMoviesCrypto.DOOM_BUILD)
                .header("ai", TimeMoviesCrypto.DOOM_APP_ID)
                .build()
            TimeMoviesApi.plainClient.newCall(doomReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                assertNotEquals("DoomPlayer v1.6 $doomEndpoint must exist (not 404)", 404, resp.code)
                assertFalse(body.contains("Cannot POST", ignoreCase = true))
            }
        }
    }
}
