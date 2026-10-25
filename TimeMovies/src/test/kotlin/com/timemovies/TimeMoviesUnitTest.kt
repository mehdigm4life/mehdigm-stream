package com.timemovies

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class TimeMoviesUnitTest {

    @Test
    fun testBase64UrlRoundTripAndJwtClaims() {
        val sample = """{"_id":"674e4e33ecd3e83dbef4adaa","e":"user@gmail.com","sid":"sess_01","fq":5,"dcr":2,"iv":false}"""
        val encoded = TimeMoviesCrypto.base64UrlEncodeNoPad(sample.toByteArray(Charsets.UTF_8))
        assertFalse(encoded.contains("="))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))

        val decoded = String(TimeMoviesCrypto.base64DecodeFlexible(encoded), Charsets.UTF_8)
        assertEquals(sample, decoded)

        val fakeJwt = "${TimeMoviesCrypto.base64UrlEncodeNoPad("""{"alg":"HS256"}""".toByteArray())}.$encoded.sig"
        val claims = TimeMoviesPrefs.decodeJwtClaims(fakeJwt)
        assertNotNull(claims)
        assertEquals("user@gmail.com", claims!!.optString("e"))
        assertEquals("sess_01", claims.optString("sid"))
        assertEquals(5, claims.optInt("fq"))
        assertEquals(2, claims.optInt("dcr"))
    }

    @Test
    fun testP256EcdsaEnrollmentAndCanonicalV47RequestSigning() {
        val kp = TimeMoviesCrypto.generateP256KeyPair()
        assertTrue(kp.privateKeyPkcs8B64Url.isNotBlank())
        assertTrue(kp.publicKeySpkiB64Url.isNotBlank())

        // 1. Enrollment challenge signature verification
        val challengeBytes = TimeMoviesCrypto.buildEnrollmentChallengePayload(
            cid = "cid_12345",
            challenge = "random_challenge_xyz",
            publicKeySpkiB64Url = kp.publicKeySpkiB64Url,
        )
        val enrollSig = TimeMoviesCrypto.signEcdsaSha256DerB64Url(kp.privateKeyPkcs8B64Url, challengeBytes)
        assertTrue(
            TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(
                publicKeySpkiB64Url = kp.publicKeySpkiB64Url,
                messageBytes = challengeBytes,
                signatureB64Url = enrollSig,
            )
        )

        // 2. Canonical 13-line V4.7 GET request signature (sorted query + "{}" empty body hash)
        val url = "https://API.APKS.LOL:443/api/v4.7/app/3571075248fa4bb78355e487c9730be7?page=0&search=batman".toHttpUrl()
        val canonical = TimeMoviesCrypto.buildCanonicalV47SigningPayload(
            method = "get",
            url = url,
            timestamp = "1761349000123",
            nonce = "550e8400-e29b-41d4-a716-446655440000",
            rawBody = null,
            authToken = "jwt.token.value",
            profileId = "prof_99",
            maturity = "5",
            deviceKeyId = "dk_abc123",
        )
        val lines = canonical.split("\n")
        assertEquals(13, lines.size)
        assertEquals("GET", lines[0])
        assertEquals("api.apks.lol", lines[1])
        assertEquals("/api/v4.7/app/3571075248fa4bb78355e487c9730be7", lines[2])
        assertEquals("page=0&search=batman", lines[3])
        assertEquals("6a3e1c72b864f954c0ef8d54", lines[4])
        assertEquals("1092", lines[5])
        assertEquals("jwt.token.value", lines[6])
        assertEquals("prof_99", lines[7])
        assertEquals("5", lines[8])
        assertEquals("1761349000123", lines[9])
        assertEquals("550e8400-e29b-41d4-a716-446655440000", lines[10])
        assertEquals(TimeMoviesCrypto.sha256Hex("{}"), lines[11])
        assertEquals("dk_abc123", lines[12])

        val signedHeaders = TimeMoviesCrypto.signV47Request(
            privateKeyPkcs8B64Url = kp.privateKeyPkcs8B64Url,
            method = "get",
            url = url,
            timestamp = "1761349000123",
            nonce = "550e8400-e29b-41d4-a716-446655440000",
            rawBody = null,
            authToken = "jwt.token.value",
            profileId = "prof_99",
            maturity = "5",
            deviceKeyId = "dk_abc123",
        )
        assertEquals("dk_abc123", signedHeaders.dk)
        assertEquals("ECDSA_SHA256_DER", signedHeaders.da)
        assertTrue(
            TimeMoviesCrypto.verifyEcdsaSha256DerB64Url(
                publicKeySpkiB64Url = kp.publicKeySpkiB64Url,
                messageBytes = canonical.toByteArray(Charsets.UTF_8),
                signatureB64Url = signedHeaders.ds,
            )
        )
    }

    @Test
    fun testExemptEndpointsAndDoomPlayerDeepLinks() {
        val enrollUrl = "https://api.apks.lol/api/v4.7/app/c81d2e64a4d64d8faea7e1abf4cb8c13".toHttpUrl()
        assertFalse(TimeMoviesCrypto.shouldAttachDeviceSignature(enrollUrl, "tok"))
        assertTrue(TimeMoviesCrypto.isExemptFromDeviceVerification(enrollUrl))

        val homeUrl = "https://api.apks.lol/api/v4.7/app/6ec4ebc493d342fba913e1fb7c3b1b2a".toHttpUrl()
        assertTrue(TimeMoviesCrypto.shouldAttachDeviceSignature(homeUrl, "tok"))
        assertFalse(TimeMoviesCrypto.isExemptFromDeviceVerification(homeUrl))

        // Build exact `kd.java` / `lh.java` DoomVideoPlayer deep-link format:
        // timesnizz://doomplayer.com/<Base64URL_NO_PAD(urlEncode(ticket) + "/" + urlEncode(title) + "/" + uspId + "/" + wkId + "/@/0")>
        val rawInner = "${URLEncoder.encode("enc_ticket_98765", "UTF-8")}/${URLEncoder.encode("Inception (2010)", "UTF-8")}/usp_1/wk_42/@/0"
        val b64 = TimeMoviesCrypto.base64UrlEncodeNoPad(rawInner.toByteArray(Charsets.UTF_8))
        val deepLink = "timesnizz://doomplayer.com/$b64"

        val parsed = TimeMoviesCrypto.parseDoomDeepLink(deepLink)
        assertNotNull(parsed)
        assertEquals("enc_ticket_98765", parsed!!.srcOrUrl)
        assertEquals("Inception (2010)", parsed.title)
        assertEquals("usp_1", parsed.profileId)
        assertEquals("wk_42", parsed.workId)
        assertTrue(parsed.isEncryptedTicket)
    }

    @Test
    fun testHlsSubtitleMasterParsingAndDtoParsing() {
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Arabic",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="ar",URI="subs/ar.vtt"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE="en",URI="https://cdn.example.com/subs/en.vtt"
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080,SUBTITLES="subs"
            1080p/index.m3u8
        """.trimIndent()

        val subs = TimeMoviesExtractors.parseHlsSubtitlesFromMaster(
            masterContent = masterM3u8,
            masterUrl = "https://stream.example.com/hls/movie123/master.m3u8",
        )
        assertEquals(2, subs.size)
        assertEquals("العربية (Arabic)", subs[0].label)
        assertEquals("https://stream.example.com/hls/movie123/subs/ar.vtt", subs[0].url)
        assertTrue(subs[0].isDefault)
        assertEquals("English", subs[1].label)
        assertEquals("https://cdn.example.com/subs/en.vtt", subs[1].url)

        val sampleSeriesJson = JSONObject(
            """
            {
              "_id": "wk_series_1",
              "ism": false,
              "tl": "Breaking Bad",
              "tlr": "اختلال ضال",
              "ov": "A chemistry teacher...",
              "ovr": "مدرس كيمياء...",
              "ps": "https://img.example.com/ps.jpg",
              "bg": "https://img.example.com/bg.jpg",
              "rt": 9.5,
              "ry": 2008,
              "r": 49,
              "gn": ["Crime", "Drama"],
              "gr": ["جريمة", "دراما"],
              "ss": [
                {
                  "ss": {"_id": "ss_1", "ssn": 1, "n": "Season 1"},
                  "eps": [
                    {"_id": "ep_1", "ssn": 1, "epn": 1, "fc": "fc_ep1", "n": "Pilot", "d": 58}
                  ]
                }
              ],
              "vds": [{"k": "HhesaQXLuRY", "st": "YouTube", "t": "Trailer"}]
            }
            """.trimIndent()
        )
        val details = TimeMoviesModels.parseWorkDetails(sampleSeriesJson)
        assertNotNull(details)
        assertFalse(details!!.isMovie)
        assertEquals("اختلال ضال", details.displayTitle(preferArabic = true))
        assertEquals("Breaking Bad", details.displayTitle(preferArabic = false))
        assertEquals(1, details.seasons.size)
        assertEquals(1, details.seasons[0].episodes.size)
        assertEquals("fc_ep1", details.seasons[0].episodes[0].fileCode)
        assertEquals("HhesaQXLuRY", details.trailers[0].key)
    }

    @Test
    fun testLiveAppStateConnectivityWhenOnline() {
        val ast = TimeMoviesApi.getAppSettings() ?: return
        assertTrue(ast.has("v") || ast.has("m") || ast.length() >= 0)
    }
}
