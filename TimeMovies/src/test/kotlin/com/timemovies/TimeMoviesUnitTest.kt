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
    fun testBase64UrlRoundTripJwtClaimsAndOtpNormalization() {
        val sample = """{"_id":"674e4e33ecd3e83dbef4adaa","e":"mehdi@gmail.com","sid":"sess_01","fq":5,"dcr":2,"iv":false}"""
        val encoded = TimeMoviesCrypto.base64UrlEncodeNoPad(sample.toByteArray(Charsets.UTF_8))
        assertFalse(encoded.contains("="))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))

        val decoded = String(TimeMoviesCrypto.base64DecodeFlexible(encoded), Charsets.UTF_8)
        assertEquals(sample, decoded)

        val fakeJwt = "${TimeMoviesCrypto.base64UrlEncodeNoPad("""{"alg":"HS256"}""".toByteArray())}.$encoded.sig"
        val claims = TimeMoviesPrefs.decodeJwtClaims(fakeJwt)
        assertNotNull(claims)
        assertEquals("mehdi@gmail.com", claims!!.optString("e"))
        assertEquals("sess_01", claims.optString("sid"))

        // Arabic-Indic & Eastern-Arabic OTP digit normalization
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
    fun testEndToEndSignUpWithMehdiAndOtpActivationWithClockSkewAnd401Recovery() {
        val serverSkewMs = 300_000L
        val enrolledKeys = ConcurrentHashMap<String, String>()
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
                    val bodyObj = JSONObject(req.body)
                    val cid = bodyObj.getString("cid")
                    val pk = bodyObj.getString("pk")
                    val sig = bodyObj.getString("sig")
                    val msgBytes = TimeMoviesCrypto.buildEnrollmentChallengePayload(cid, "challenge_nonce_xyz", pk)
                    if (!TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(pk, msgBytes, sig)) {
                        return@MiniHttpServer jsonResp(401, """{"success":false,"message":"Invalid enrollment signature"}""", nowServer)
                    }
                    val newDk = "dk_${enrolledKeys.size + 1}"
                    enrolledKeys[newDk] = pk
                    jsonResp(200, """{"success":true,"status":200,"result":{"dk":"$newDk","alg":"ECDSA_SHA256_DER","st":1}}""", nowServer)
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

                    // Inject stale-dk verification failure on attempt 1 to prove automatic recovery!
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
            assertEquals("Default/example name must be Mehdi", "Mehdi", capturedSignUpName)
            assertTrue(Regex("^[0-9a-f]{32}$").matches(capturedSignUpRasplabId.orEmpty()))
            assertNotNull(TimeMoviesPrefs.pendingOtpToken)

            val otpRes = TimeMoviesApi.activateEmailOtp("٦٥٤-٣٢١")
            assertTrue("OTP activation failed: ${otpRes.message}", otpRes.success)
            assertEquals(2, otpAttempts.get())
            assertTrue(TimeMoviesPrefs.isSignedIn)
            assertTrue(TimeMoviesApi.looksLikeJwt(TimeMoviesPrefs.authToken))
            assertEquals("prof_mehdi_main", TimeMoviesPrefs.profileId)
            assertEquals("Mehdi", TimeMoviesPrefs.profileName)
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
                    jsonResp(200, """{"success":true,"status":200,"result":{"dk":"dk_live_1","alg":"ECDSA_SHA256_DER","st":1}}""")

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
            assertTrue(
                "Relative HLS variants must preserve signed master query token",
                hlsLinks.any { it.url.endsWith("1080p/index.m3u8?sig=tok123") && it.quality == 1080 },
            )
            assertEquals("Expected 1080p + 720p direct MP4 download links", 2, mp4Links.size)
            assertTrue(mp4Links.any { it.quality == 1080 && it.url.endsWith("interstellar_1080p.mp4") })
            assertTrue(mp4Links.any { it.quality == 720 && it.url.endsWith("interstellar_720p.mp4") })
            assertEquals("Expected Arabic + English HLS subtitle tracks", 2, subs.size)
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
                    jsonResp(200, """{"success":true,"result":{"dk":"dk_2","alg":"ECDSA_SHA256_DER","st":1}}""")

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
            assertTrue(m3u8Links.any { it.url == "$baseOrigin/cdn/live/dune_stream_token_xyz" })
            assertTrue(m3u8Links.any { it.quality == 1080 && it.url.endsWith("v1080/prog.m3u8") })
            assertEquals("Expected resolved DoomPlayer MP4 download link", 1, videoLinks.size)
            assertEquals("$baseOrigin/cdn/dl/dune_1080p.mp4", videoLinks[0].url)
            assertEquals(1, subs.size)
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
        val signUpProbe = TimeMoviesApi.signUpWithEmail("Mehdi", "mehdi.v2.check@mailinator.com", "MehdiPass2026!")
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
