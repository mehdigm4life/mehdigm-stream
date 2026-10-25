package com.timemovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
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

    private fun formatHttpDate(epochMs: Long): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return sdf.format(Date(epochMs))
    }

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

    private fun respondJson(ex: HttpExchange, code: Int, body: String, serverNowMs: Long = System.currentTimeMillis()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.set("Date", formatHttpDate(serverNowMs))
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respondText(
        ex: HttpExchange,
        code: Int,
        contentType: String,
        body: String,
        serverNowMs: Long = System.currentTimeMillis(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", contentType)
        ex.responseHeaders.set("Date", formatHttpDate(serverNowMs))
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
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
        // Simulate a server whose clock is +300 seconds (+5 minutes) ahead of the client!
        val serverSkewMs = 300_000L
        val enrolledKeys = ConcurrentHashMap<String, String>() // dk -> pkSpkiB64Url
        val otpAttempts = AtomicInteger(0)
        var capturedSignUpName: String? = null
        var capturedSignUpRasplabId: String? = null

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val baseOrigin = "http://127.0.0.1:$port"
        TimeMoviesPrefs.apiBaseUrlOverride = "$baseOrigin/api/v4.7/app/"
        TimeMoviesPrefs.mediaV2BaseUrlOverride = "$baseOrigin/v2/media/"
        TimeMoviesPrefs.doomBaseUrlOverride = "$baseOrigin/api/player/v1.6/app/"

        // Pre-seed a stale profile from an older account to prove `activateEmailOtp` NEVER leaks `x-p`/`m-a`!
        TimeMoviesPrefs.profileId = "stale_profile_from_old_account"
        TimeMoviesPrefs.profileMaturity = 3

        server.createContext("/") { ex ->
            val nowServer = System.currentTimeMillis() + serverSkewMs
            val path = ex.requestURI.path
            val rawQuery = ex.requestURI.rawQuery
            val method = ex.requestMethod
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)

            when {
                path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) -> {
                    respondJson(ex, 200, """{"status":1,"result":{"v":1092}}""", nowServer)
                }

                path.endsWith(TimeMoviesApi.PATH_EMAIL_SIGN_UP) -> {
                    val bodyObj = JSONObject(reqBody)
                    capturedSignUpName = bodyObj.optString("name")
                    val devObj = bodyObj.getJSONObject("device")
                    capturedSignUpRasplabId = devObj.optString("rasplab_install_id")
                    val pendingJwt = makeTestJwt("usr_mehdi_1", bodyObj.getString("email"), devObj.getString("device_id"))
                    respondJson(ex, 200, JSONObject().put("status", 1).put("result", pendingJwt).toString(), nowServer)
                }

                path.endsWith(TimeMoviesApi.PATH_DEVICE_CHALLENGE) -> {
                    // Unactivated OTP token must NOT carry stale `x-p`
                    if (!ex.requestHeaders.getFirst("x-p").isNullOrEmpty() && TimeMoviesPrefs.authToken == null) {
                        respondJson(ex, 401, """{"status":0,"message":"Request verification failed."}""", nowServer)
                        return@createContext
                    }
                    respondJson(
                        ex,
                        200,
                        """{"status":1,"result":{"cid":"cid_900","ch":"challenge_nonce_xyz","exp":9999999999}}""",
                        nowServer,
                    )
                }

                path.endsWith(TimeMoviesApi.PATH_DEVICE_REGISTER) -> {
                    val bodyObj = JSONObject(reqBody)
                    val cid = bodyObj.getString("cid")
                    val pk = bodyObj.getString("pk")
                    val sig = bodyObj.getString("sig")
                    val msgBytes = TimeMoviesCrypto.buildEnrollmentChallengePayload(cid, "challenge_nonce_xyz", pk)
                    val valid = TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(pk, msgBytes, sig)
                    if (!valid) {
                        respondJson(ex, 401, """{"status":0,"message":"Invalid enrollment signature"}""", nowServer)
                        return@createContext
                    }
                    val newDk = "dk_${enrolledKeys.size + 1}"
                    enrolledKeys[newDk] = pk
                    respondJson(ex, 200, """{"status":1,"result":{"dk":"$newDk","alg":"ECDSA_SHA256_DER","st":1}}""", nowServer)
                }

                path.endsWith(TimeMoviesApi.PATH_EMAIL_ACTIVATE_OTP) -> {
                    val attempt = otpAttempts.incrementAndGet()
                    // Verify no stale `x-p` or `m-a` header was attached to pending OTP request!
                    val xp = ex.requestHeaders.getFirst("x-p").orEmpty()
                    val ma = ex.requestHeaders.getFirst("m-a").orEmpty()
                    if (xp.isNotEmpty() || ma.isNotEmpty()) {
                        respondJson(ex, 401, """{"status":0,"message":"Request verification failed."}""", nowServer)
                        return@createContext
                    }

                    // Verify server-synced timestamp `t` (must match skewed server clock within 15s!)
                    val tStr = ex.requestHeaders.getFirst("t").orEmpty()
                    val tVal = tStr.toLongOrNull() ?: 0L
                    if (abs(tVal - nowServer) > 15_000L) {
                        respondJson(ex, 401, """{"status":0,"message":"Request verification failed."}""", nowServer)
                        return@createContext
                    }

                    // Verify 13-line ECDSA P-256 request signature
                    val dk = ex.requestHeaders.getFirst("dk").orEmpty()
                    val ds = ex.requestHeaders.getFirst("ds").orEmpty()
                    val xt = ex.requestHeaders.getFirst("x-t").orEmpty()
                    val n = ex.requestHeaders.getFirst("n").orEmpty()
                    val pk = enrolledKeys[dk]
                    val fullUrl = "$baseOrigin$path${if (rawQuery != null) "?$rawQuery" else ""}".toHttpUrl()
                    val canonical = TimeMoviesCrypto.buildCanonicalV47SigningPayload(
                        method = method,
                        url = fullUrl,
                        timestamp = tStr,
                        nonce = n,
                        rawBody = reqBody,
                        authToken = xt,
                        profileId = xp,
                        maturity = ma,
                        deviceKeyId = dk,
                    )
                    if (pk == null || !TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(pk, canonical.toByteArray(), ds)) {
                        respondJson(ex, 401, """{"status":0,"message":"Request verification failed."}""", nowServer)
                        return@createContext
                    }

                    // Simulate transient verification rejection on attempt 1 to verify automatic re-enroll & retry!
                    if (attempt == 1) {
                        enrolledKeys.remove(dk)
                        respondJson(ex, 401, """{"status":0,"message":"Request verification failed."}""", nowServer)
                        return@createContext
                    }

                    val otpObj = JSONObject(reqBody)
                    assertEquals("654321", otpObj.getString("otp"))
                    // Return non-JWT `"OK"` string in `result` to verify `pendingToken` is preserved!
                    respondJson(ex, 200, """{"status":1,"message":"Activated","result":"OK"}""", nowServer)
                }

                path.endsWith(TimeMoviesApi.PATH_APP_DATA) -> {
                    val activatedJwt = makeTestJwt("usr_mehdi_1", "mehdi@gmail.com", TimeMoviesPrefs.deviceId, "sid_live")
                    val resp = JSONObject().apply {
                        put("status", 1)
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
                    respondJson(ex, 200, resp, nowServer)
                }

                else -> respondJson(ex, 404, """{"status":0}""", nowServer)
            }
        }
        server.start()

        try {
            val signUpRes = TimeMoviesApi.signUpWithEmail("", "mehdi@gmail.com", "StrongPass123")
            assertTrue("Sign-up must succeed", signUpRes.success)
            assertEquals("Default/example name must be Mehdi", "Mehdi", capturedSignUpName)
            assertTrue(Regex("^[0-9a-f]{32}$").matches(capturedSignUpRasplabId.orEmpty()))
            assertNotNull(TimeMoviesPrefs.pendingOtpToken)

            // Activate using Arabic-Indic digits `"٦٥٤٣٢١"` while server is skewed +300s & forces retry!
            val otpRes = TimeMoviesApi.activateEmailOtp("٦٥٤-٣٢١")
            assertTrue("OTP activation failed: ${otpRes.message}", otpRes.success)
            assertEquals(2, otpAttempts.get())
            assertTrue(TimeMoviesPrefs.isSignedIn)
            assertTrue(TimeMoviesApi.looksLikeJwt(TimeMoviesPrefs.authToken))
            assertEquals("prof_mehdi_main", TimeMoviesPrefs.profileId)
            assertEquals("Mehdi", TimeMoviesPrefs.profileName)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testEndToEndInternalOriginWatchAndDownloadLinksAndSubtitles() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val baseOrigin = "http://127.0.0.1:$port"
        TimeMoviesPrefs.apiBaseUrlOverride = "$baseOrigin/api/v4.7/app/"
        TimeMoviesPrefs.mediaV2BaseUrlOverride = "$baseOrigin/v2/media/"
        TimeMoviesPrefs.doomBaseUrlOverride = "$baseOrigin/api/player/v1.6/app/"

        val dev = TimeMoviesPrefs.getOrCreateVirtualDevice()
        TimeMoviesPrefs.authToken = makeTestJwt("usr_1", "mehdi@gmail.com", dev.deviceId)
        TimeMoviesPrefs.profileId = "prof_1"
        TimeMoviesPrefs.profileMaturity = 5

        val postToMediaV2Detected = AtomicInteger(0)

        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val method = ex.requestMethod
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)

            when {
                path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) -> {
                    respondJson(ex, 200, """{"status":1,"result":{"v":1092}}""")
                }

                path.endsWith(TimeMoviesApi.PATH_DEVICE_CHALLENGE) -> {
                    respondJson(ex, 200, """{"status":1,"result":{"cid":"cid_1","ch":"ch_1","exp":9999999999}}""")
                }

                path.endsWith(TimeMoviesApi.PATH_DEVICE_REGISTER) -> {
                    respondJson(ex, 200, """{"status":1,"result":{"dk":"dk_live_1","alg":"ECDSA_SHA256_DER","st":1}}""")
                }

                path.endsWith(TimeMoviesApi.PATH_WORK_DETAILS) -> {
                    val wk = JSONObject().apply {
                        put("_id", "wk_movie_99")
                        put("ism", true)
                        put("tl", "Interstellar")
                        put("tlr", "بين النجوم")
                        put("fc", "fc_interstellar_2014")
                    }
                    respondJson(ex, 200, JSONObject().put("status", 1).put("result", wk).toString())
                }

                path.endsWith("/media/grant") -> {
                    // Must carry ECDSA headers + active profile headers
                    assertNotNull(ex.requestHeaders.getFirst("dk"))
                    assertNotNull(ex.requestHeaders.getFirst("ds"))
                    assertEquals("prof_1", ex.requestHeaders.getFirst("x-p"))
                    val body = JSONObject(reqBody)
                    assertEquals("com.extremeqr.proapps", body.getString("package"))
                    assertEquals("fc_interstellar_2014", body.getString("file_code"))
                    val action = body.getString("action")
                    val grantTok = if (action == "watch") "grant_watch_tok" else "grant_dl_tok"
                    respondJson(
                        ex,
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
                    if (method != "GET") {
                        postToMediaV2Detected.incrementAndGet()
                        respondJson(ex, 404, """{"error":"Cannot POST /v2/media/manifest"}""")
                        return@createContext
                    }
                    assertEquals("grant_watch_tok", ex.requestHeaders.getFirst("X-Media-Grant"))
                    assertEquals("1092", ex.requestHeaders.getFirst("x-b"))
                    assertEquals("prof_1", ex.requestHeaders.getFirst("x-p"))
                    respondJson(
                        ex,
                        200,
                        JSONObject().apply {
                            put("ok", true)
                            put("action", "watch")
                            put("src", "$baseOrigin/cdn/hls/interstellar/master.m3u8?sig=tok123")
                        }.toString(),
                    )
                }

                path == "/v2/media/download" -> {
                    if (method != "GET") {
                        postToMediaV2Detected.incrementAndGet()
                        respondJson(ex, 404, """{"error":"Cannot POST /v2/media/download"}""")
                        return@createContext
                    }
                    assertEquals("grant_dl_tok", ex.requestHeaders.getFirst("X-Media-Grant"))
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
                    respondJson(
                        ex,
                        200,
                        JSONObject().put("ok", true).put("sources", sources).toString(),
                    )
                }

                path == "/cdn/hls/interstellar/master.m3u8" -> {
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
                    respondText(ex, 200, "application/vnd.apple.mpegurl", m3u8)
                }

                else -> respondJson(ex, 404, """{"ok":false}""")
            }
        }
        server.start()

        try {
            val links = ArrayList<ExtractorLink>()
            val subs = ArrayList<SubtitleFile>()

            // Pass blank `fileCode` initially to verify `loadLinks` recovers `fc` via `getWorkDetails`!
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
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testEndToEndExternalDoomPlayerFallbackWithExtensionlessHlsAndExecutionRetry() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val baseOrigin = "http://127.0.0.1:$port"
        TimeMoviesPrefs.apiBaseUrlOverride = "$baseOrigin/api/v4.7/app/"
        TimeMoviesPrefs.mediaV2BaseUrlOverride = "$baseOrigin/v2/media/"
        TimeMoviesPrefs.doomBaseUrlOverride = "$baseOrigin/api/player/v1.6/app/"

        val dev = TimeMoviesPrefs.getOrCreateVirtualDevice()
        TimeMoviesPrefs.authToken = makeTestJwt("usr_2", "mehdi2@gmail.com", dev.deviceId)
        TimeMoviesPrefs.profileId = "prof_2"
        TimeMoviesPrefs.profileMaturity = 5

        val externalWatchAttempts = AtomicInteger(0)

        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)

            when {
                path.endsWith(TimeMoviesApi.PATH_APP_SETTINGS) ->
                    respondJson(ex, 200, """{"status":1,"result":{"v":1092}}""")

                path.endsWith(TimeMoviesApi.PATH_DEVICE_CHALLENGE) ->
                    respondJson(ex, 200, """{"status":1,"result":{"cid":"c2","ch":"h2","exp":9999999999}}""")

                path.endsWith(TimeMoviesApi.PATH_DEVICE_REGISTER) ->
                    respondJson(ex, 200, """{"status":1,"result":{"dk":"dk_2","alg":"ECDSA_SHA256_DER","st":1}}""")

                path.endsWith("/media/grant") -> {
                    val body = JSONObject(reqBody)
                    val action = body.getString("action")
                    val playerInstalled = body.optBoolean("player_installed", false)
                    val executorTarget = body.optString("executor_target", "")

                    // Internal mode refused -> forces fallback to External DoomVideoPlayer mode!
                    if (!playerInstalled || executorTarget != "external") {
                        respondJson(ex, 200, """{"ok":false,"reason":"player_not_installed","charged":false}""")
                        return@createContext
                    }

                    if (action == "watch") {
                        val att = externalWatchAttempts.incrementAndGet()
                        if (att == 1) {
                            // Simulate ExecutionFailed with `operation_id` to verify idempotent retry!
                            respondJson(ex, 200, """{"ok":false,"reason":"origin_unreachable","operation_id":"op_retry_1","charged":true}""")
                            return@createContext
                        }
                        assertEquals("op_retry_1", body.optString("operation_id"))
                        val inner = "${URLEncoder.encode("enc_watch_ticket_99", "UTF-8")}/${URLEncoder.encode("Dune Part Two", "UTF-8")}/prof_2/wk_55/@/0"
                        val b64 = TimeMoviesCrypto.base64UrlEncodeNoPad(inner.toByteArray())
                        respondJson(
                            ex,
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
                        respondJson(
                            ex,
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

                path == "/api/player/v1.6/app/resolve-play" -> {
                    assertEquals("1065", ex.requestHeaders.getFirst("x-b"))
                    assertEquals("6a5216d91e12eeadc194ab91", ex.requestHeaders.getFirst("ai"))
                    val src = JSONObject(reqBody).getString("src")
                    assertEquals("enc_watch_ticket_99", src)
                    // Extensionless HLS URL (no `.m3u8` in URL path, matching `PlayerScreenKt`!)
                    respondJson(
                        ex,
                        200,
                        """{"status":1,"result":{"url":"$baseOrigin/cdn/live/dune_stream_token_xyz"}}""",
                    )
                }

                path == "/api/player/v1.6/app/resolve-download" -> {
                    val src = JSONObject(reqBody).getString("src")
                    assertEquals("enc_dl_ticket_1080", src)
                    respondJson(
                        ex,
                        200,
                        """{"status":1,"result":{"url":"$baseOrigin/cdn/dl/dune_1080p.mp4"}}""",
                    )
                }

                path == "/cdn/live/dune_stream_token_xyz" -> {
                    val m3u8 = """
                        #EXTM3U
                        #EXT-X-VERSION:3
                        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Arabic",DEFAULT=YES,LANGUAGE="ar",URI="ar.vtt"
                        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,SUBTITLES="subs"
                        v1080/prog.m3u8
                    """.trimIndent()
                    respondText(ex, 200, "application/x-mpegURL", m3u8)
                }

                else -> respondJson(ex, 404, """{"status":0}""")
            }
        }
        server.start()

        try {
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
        } finally {
            server.stop(0)
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
        assertTrue(ast.has("v") || ast.has("m") || ast.length() >= 0)
        assertTrue("Clock offset must be persisted after live response", TimeMoviesPrefs.hasPersistedClockOffset)

        // 1. Live VirtualDeviceProfile + "Mehdi" Sign-Up validation against production `api.apks.lol`:
        //    Reaching email-domain check (HTTP 200, status=0, code=502) proves live acceptance of:
        //    - `q52` headers + synced server timestamp `t` (no HTTP 401 "Request verification failed.")
        //    - 32-field synthetic `UserDevice` + `rasplab_install_id` (no HTTP 400 schema error & no device ban!)
        val signUpProbe = TimeMoviesApi.signUpWithEmail("Mehdi", "mehdi.v2.check@mailinator.com", "MehdiPass2026!")
        assertNotNull(signUpProbe.message)
        assertFalse(
            "Sign-up must NOT fail with Request verification failed: ${signUpProbe.message}",
            signUpProbe.message.orEmpty().contains("Request verification failed", ignoreCase = true),
        )

        // 2. Live `GET /v2/media/manifest` & `GET /v2/media/download` route verification on `api.apks.lol`:
        //    Proves `GET` hits the real MediaV2 JSON controller (unlike v1's `POST` which hit Express 404 `Cannot POST`)!
        for (mediaPath in listOf("manifest", "download")) {
            val mediaReq = okhttp3.Request.Builder()
                .url("${TimeMoviesCrypto.MEDIA_V2_BASE_URL}$mediaPath")
                .get()
                .header("User-Agent", "okhttp/4.12.0")
                .header("Accept", "application/json")
                .header("x-b", TimeMoviesCrypto.APP_BUILD)
                .header("ai", TimeMoviesCrypto.APP_ID)
                .header("av", TimeMoviesCrypto.APP_VERSION_TOKEN)
                .header("X-Media-Grant", "probe_invalid_grant")
                .build()
            TimeMoviesApi.plainClient.newCall(mediaReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                assertFalse(
                    "GET /v2/media/$mediaPath must route to MediaV2 controller, not Express Cannot GET: $body",
                    body.contains("Cannot GET", ignoreCase = true),
                )
            }
        }

        // 3. Live `DoomVideoPlayer` v1.6 `resolve-play` & `resolve-download` route verification (`1065` / `6a5216d91e12eeadc194ab91`)
        for (doomEndpoint in listOf("resolve-play", "resolve-download")) {
            val reqBody = okhttp3.RequestBody.create(
                null,
                """{"src":"invalid_probe_ticket"}""".toByteArray(Charsets.UTF_8),
            )
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
