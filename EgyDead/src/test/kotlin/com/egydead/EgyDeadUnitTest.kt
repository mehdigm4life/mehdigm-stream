package com.egydead

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EgyDeadUnitTest {

    @Test
    fun testTitleCleaningAndDubDetection() {
        assertEquals(
            "Fall 2 Deadpoint 2026",
            EgyDead.cleanTitle("مشاهدة فيلم Fall 2 Deadpoint 2026 مترجم")
        )
        assertEquals(
            "لوبين الجزء الرابع (مدبلج)",
            EgyDead.cleanTitle("مسلسل لوبين الجزء الرابع مدبلج كامل")
        )
        assertEquals(
            "Lupin",
            EgyDead.cleanSeriesRootTitle("مسلسل Lupin الموسم الرابع مترجم كامل")
        )
        assertEquals(
            "Lupin",
            EgyDead.cleanSeriesRootTitle("مسلسل Lupin الموسم الاول الحلقة 3 مترجمة")
        )
        assertTrue(EgyDead.isDubbedText("مسلسل لوبين مدبلج", "https://tv10.egydead.live/seasons/lupin-s04-ar/"))
        assertFalse(EgyDead.isDubbedText("مسلسل Lupin مترجم", "https://tv10.egydead.live/seasons/lupin-s04/"))
    }

    @Test
    fun testSeasonAndEpisodeNumberParsing() {
        assertEquals(1, EgyDead.parseSeasonNumber("مسلسل Rome الموسم الاول مترجم"))
        assertEquals(2, EgyDead.parseSeasonNumber("مسلسل Rome الموسم الثاني مترجم"))
        assertEquals(4, EgyDead.parseSeasonNumber("مسلسل Lupin الموسم الرابع", "https://tv10.egydead.live/seasons/lupin-s04/"))
        assertEquals(11, EgyDead.parseSeasonNumber("الموسم الحادي عشر"))
        assertEquals(12, EgyDead.parseSeasonNumber("الموسم الثاني عشر"))

        assertEquals(0, EgyDead.parseEpisodeNumber("حلقه 00", "https://tv10.egydead.live/episodes/ao-no-hako-ep-00/"))
        assertEquals(1, EgyDead.parseEpisodeNumber("الحلقة 1", "https://tv10.egydead.live/episodes/lupin-s04e01/"))
        assertEquals(24, EgyDead.parseEpisodeNumber("مشاهدة انمي Lupin III Part V الحلقة 24 الاخيرة"))
        assertEquals(1001, EgyDead.parseEpisodeNumber("اوفا 1"))
    }

    @Test
    fun testUrlEncodingAndLegacyPathUpgrade() {
        val rawArabic = "https://tv10.egydead.live/مشاهدة-فيلم-fall-2022-مترجم/"
        val encoded = EgyDeadNetwork.encodeUrl(rawArabic)
        assertFalse(encoded.contains("مشاهدة"))
        assertEquals(encoded, EgyDeadNetwork.encodeUrl(encoded))

        assertEquals(
            "https://tv10.egydead.live/episodes/lupin-s04e01/",
            EgyDeadNetwork.upgradeLegacyPath("https://tv10.egydead.live/episode/lupin-s04e01/")
        )
        assertEquals(
            "https://tv10.egydead.live/seasons/lupin-s04/",
            EgyDeadNetwork.upgradeLegacyPath("https://tv10.egydead.live/season/lupin-s04/")
        )
        assertEquals(
            "https://tv10.egydead.live/Series/ao-no-hako-2024/",
            EgyDeadNetwork.upgradeLegacyPath("https://tv10.egydead.live/serie/ao-no-hako-2024/")
        )
        assertEquals(
            "https://tv10.egydead.live/film-series/fall-movies-collection/",
            EgyDeadNetwork.upgradeLegacyPath("https://tv10.egydead.live/assembly/fall-movies-collection/")
        )
    }

    @Test
    fun testMegaMaxRotatingDomainsAndCanonicalDedup() {
        val iframeUrl = "https://q7v3k8m2p1.top/iframe/Uw0W7pOtd12FY"
        val downloadUrl = "https://q7v3k8m2p1.top/download/Uw0W7pOtd12FY"
        val futureRotateUrl = "https://z9x8c7v6b5.top/iframe/Uw0W7pOtd12FY"

        assertTrue(EgyDeadMegaMaxExtractor.isMegaMaxUrl(iframeUrl))
        assertTrue(EgyDeadMegaMaxExtractor.isMegaMaxUrl(downloadUrl))
        assertTrue(EgyDeadMegaMaxExtractor.isMegaMaxUrl(futureRotateUrl))
        assertEquals("Uw0W7pOtd12FY", EgyDeadMegaMaxExtractor.extractHashId(iframeUrl))
        assertEquals(
            EgyDeadMirrorRouter.canonicalDedupKey(iframeUrl),
            EgyDeadMirrorRouter.canonicalDedupKey(downloadUrl)
        )
        assertEquals(
            "streamhg:kldh1j2cvzgm",
            EgyDeadMirrorRouter.canonicalDedupKey("https://hanerix.com/e/kldh1j2cvzgm")
        )
        assertEquals(
            "streamhg:kldh1j2cvzgm",
            EgyDeadMirrorRouter.canonicalDedupKey("https://hgcloud.to/kldh1j2cvzgm")
        )
        assertEquals(
            "earnvids:51ku97w3c7sn",
            EgyDeadMirrorRouter.canonicalDedupKey("https://morencius.com/file/51ku97w3c7sn")
        )
    }

    @Test
    fun testOpenSslSaltedAndCryptoJsJsonAes256CbcRoundTrip() {
        val pass = "k7v2m4x9bqwpnj6t"
        val salt = "12345678".toByteArray(Charsets.US_ASCII)
        val plain = """[{"file":"https://cdn777.megamax.me/hls/master.m3u8","label":"1080p"}]"""

        val md5 = MessageDigest.getInstance("MD5")
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < 48) {
            md5.reset()
            if (block.isNotEmpty()) md5.update(block)
            md5.update(pass.toByteArray(Charsets.UTF_8))
            md5.update(salt)
            block = md5.digest()
            derived += block
        }
        val key = derived.copyOfRange(0, 32)
        val iv = derived.copyOfRange(32, 48)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        // 1. OpenSSL Salted__ format
        val openSslBlob = Base64.getEncoder().encodeToString("Salted__".toByteArray(Charsets.US_ASCII) + salt + enc)
        assertEquals(plain, EgyDeadExtractorKit.decryptOpenSslAesCbc(openSslBlob, pass))

        // 2. CryptoJS JSON {"ct","iv","s"} format used by MegaMax /e/<id>
        val cryptoJsJson = JSONObject()
            .put("ct", Base64.getEncoder().encodeToString(enc))
            .put("iv", iv.joinToString("") { "%02x".format(it) })
            .put("s", salt.joinToString("") { "%02x".format(it) })
            .toString()
        assertEquals(plain, EgyDeadExtractorKit.decryptCryptoJsJson(cryptoJsJson, pass))
    }

    @Test
    fun testJsUnpacker() {
        val packed = """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1={"2":"3://4.5/6.7"};',8,8,'var|links|hls4|https|cdn|example|master|m3u8'.split('|'),0,{}))"""
        val unpacked = EgyDeadExtractorKit.unpackJs(packed)
        assertTrue(unpacked.contains("""var links={"hls4":"https://cdn.example/master.m3u8"};"""))
    }

    @Test
    fun testCatalogCardAndWatchServerDomParsing() {
        val api = EgyDead()
        val catalogHtml = """
            <html><body>
              <ul class="posts-list">
                <li class="movieItem">
                  <a href="https://tv10.egydead.live/fall-2-deadpoint-2026-1080p-web-dl-1/" title="مشاهدة فيلم Fall 2 Deadpoint 2026 مترجم">
                    <img src="https://tv10.egydead.live/wp-content/uploads/2026/04/Fall-2.jpg" />
                    <span class="label">1080p WEB-DL</span>
                    <h1 class="BottomTitle">مشاهدة فيلم Fall 2 Deadpoint 2026 مترجم</h1>
                  </a>
                </li>
                <li class="movieItem">
                  <a href="https://tv10.egydead.live/Series/lupin-all-seasons/" title="مسلسل Lupin جميع المواسم">
                    <img data-src="https://tv10.egydead.live/wp-content/uploads/2026/04/Lupin.jpg" />
                    <h1 class="BottomTitle">مسلسل Lupin جميع المواسم</h1>
                  </a>
                </li>
                <li class="movieItem">
                  <a href="https://tv10.egydead.live/episodes/ao-no-hako-s01e05/watch/" title="انمي Ao no Hako الحلقة 5 مترجمة">
                    <img src="https://tv10.egydead.live/wp-content/uploads/2026/04/Ao.jpg" />
                    <h1 class="BottomTitle">انمي Ao no Hako الحلقة 5 مترجمة</h1>
                  </a>
                </li>
              </ul>
            </body></html>
        """.trimIndent()

        val cards = api.parseCardList(Jsoup.parse(catalogHtml, "https://tv10.egydead.live/"))
        assertEquals(3, cards.size)
        assertEquals("Fall 2 Deadpoint 2026", cards[0].name)
        assertEquals(TvType.Movie, cards[0].type)
        assertEquals(TvType.TvSeries, cards[1].type)
        assertEquals(TvType.Anime, cards[2].type)
        // Ensure `/watch/` suffix was stripped cleanly on episode card
        assertEquals("https://tv10.egydead.live/episodes/ao-no-hako-s01e05/", cards[2].url)

        val watchHtml = """
            <html><body>
              <div class="watch_player"><iframe src="https://www.youtube.com/embed/trailer123"></iframe></div>
              <ul class="serversList">
                <li data-link="https://q7v3k8m2p1.top/iframe/Uw0W7pOtd12FY">MegaMax</li>
                <li data-link="https://hanerix.com/e/kldh1j2cvzgm">StreamHG</li>
              </ul>
              <ul class="donwload-servers-list">
                <li>
                  <span class="ser-name">MegaMax</span>
                  <span class="ser-quality">1080p</span>
                  <a class="ser-link" href="https://q7v3k8m2p1.top/download/Uw0W7pOtd12FY">تحميل</a>
                </li>
                <li>
                  <span class="ser-name">StreamHG</span>
                  <span class="ser-quality">720p</span>
                  <a class="ser-link" href="https://hgcloud.to/kldh1j2cvzgm">تحميل</a>
                </li>
                <li>
                  <span class="ser-name">OpenLoad</span>
                  <span class="ser-quality">720p</span>
                  <a class="ser-link" href="https://openload.co/f/deadfile123">تحميل</a>
                </li>
              </ul>
            </body></html>
        """.trimIndent()

        val rawCandidates = api.collectCandidateServers(
            Jsoup.parse(watchHtml, "https://tv10.egydead.live/fall-2/watch/"),
            "https://tv10.egydead.live/fall-2/watch/"
        )
        // YouTube trailer iframe must be excluded, leaving 2 watch + 3 download = 5 raw candidates
        assertEquals(5, rawCandidates.size)
        assertEquals(Qualities.P1080.value, rawCandidates[2].qualityHint)

        // Canonical dedup + dead host filter merges MegaMax iframe+download & StreamHG e+hgcloud and drops OpenLoad -> 2 unique!
        val seen = HashSet<String>()
        val deduped = rawCandidates.filter {
            !EgyDeadMirrorRouter.isDeadArchiveHost(it.url) &&
                seen.add(EgyDeadMirrorRouter.canonicalDedupKey(it.url))
        }
        assertEquals(2, deduped.size)
    }

    @Test
    fun testDomainNormalizationCloudflareDetectionAndHomeH3Sections() {
        assertEquals(
            "https://tv10.egydead.live",
            EgyDeadPrefs.normalizeDomainOrigin("https://tv10.egydead.live/h3/")
        )
        assertEquals(
            "https://tv10.egydead.live",
            EgyDeadPrefs.normalizeDomainOrigin("tv10.egydead.live/h3")
        )
        assertTrue(EgyDeadNetwork.isCloudflareBlock(403, "<html><head><title>Just a moment...</title></head><body>__cf_chl_opt</body></html>"))
        assertFalse(EgyDeadNetwork.isValidEgyDeadHtml(403, "<html><head><title>Just a moment...</title></head></html>"))
        assertTrue(EgyDeadNetwork.isValidEgyDeadHtml(200, "<html><body><li class=\"movieItem\">ايجي ديد</li></body></html>"))

        // Decode evaluateJavascript string payloads safely
        assertEquals(
            "{\"ok\":true,\"title\":\"ايجي ديد\"}",
            CloudflareSolver.decodeJsString("\"{\\\"ok\\\":true,\\\"title\\\":\\\"ايجي ديد\\\"}\"")
        )

        val h3Html = """
            <html><body>
              <div class="PinHome">
                <h1>المواضيع المميزه</h1>
                <ul class="posts-list">
                  <li class="movieItem">
                    <a href="https://tv10.egydead.live/episodes/black-clover-s02e04/" title="انمي Black Clover الموسم الثاني الحلقة 4 مترجمة">
                      <img src="https://tv10.egydead.live/wp-content/uploads/2026/10/Black-Clover.jpg" />
                      <h1 class="BottomTitle">انمي Black Clover الموسم الثاني الحلقة 4 مترجمة</h1>
                    </a>
                  </li>
                </ul>
              </div>
              <div class="catHolder">
                <h1>المضاف حديثا</h1>
                <ul class="posts-list">
                  <li class="movieItem">
                    <a href="https://tv10.egydead.live/spider-man-brand-new-day-2026/" title="مشاهدة فيلم Spider Man Brand New Day 2026 مترجم">
                      <img src="https://tv10.egydead.live/wp-content/uploads/2026/08/Spider-Man.jpg" />
                      <h1 class="BottomTitle">مشاهدة فيلم Spider Man Brand New Day 2026 مترجم</h1>
                    </a>
                  </li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()

        val api = EgyDead()
        val h3Doc = Jsoup.parse(h3Html, "https://tv10.egydead.live/h3/")
        val featured = api.parseHomeLandingSection(h3Doc, "featured")
        val recent = api.parseHomeLandingSection(h3Doc, "recent")

        assertEquals(1, featured.size)
        assertEquals("https://tv10.egydead.live/episodes/black-clover-s02e04/", featured[0].url)
        assertEquals(1, recent.size)
        assertEquals("Spider Man Brand New Day 2026", recent[0].name)
    }

    @Test
    fun testV19CloudflareChallengeAndPositiveDomGuards() {
        val arabicChallenge200 = """
            <html dir="rtl" lang="ar">
              <head><title>لحظة من فضلك...</title></head>
              <body>
                <div class="main-wrapper"><h1>tv10.egydead.live</h1></div>
                <div id="XWAst9"><div><div><input type="hidden" name="cf-turnstile-response" /></div></div></div>
                <script>window._cf_chl_opt={cZone:'tv10.egydead.live'};</script>
              </body>
            </html>
        """.trimIndent()
        assertTrue(EgyDeadNetwork.isCloudflareBlock(200, arabicChallenge200))
        assertTrue(EgyDeadNetwork.isCloudflareBlock(403, arabicChallenge200))
        assertTrue(!EgyDeadNetwork.isValidEgyDeadHtml(200, arabicChallenge200))

        val englishChallenge200 = """
            <html dir="ltr" lang="en-US">
              <head><title>Just a moment...</title></head>
              <body>
                <h1>tv10.egydead.live</h1>
                <script src="https://challenges.cloudflare.com/turnstile/v0/g/f17545a2/api.js"></script>
                <script>window._cf_chl_opt={cZone:'tv10.egydead.live'};</script>
              </body>
            </html>
        """.trimIndent()
        assertTrue(EgyDeadNetwork.isCloudflareBlock(200, englishChallenge200))
        assertTrue(!EgyDeadNetwork.isValidEgyDeadHtml(200, englishChallenge200))

        val realWatchUnlockedHtml = """
            <html><body>
              <div class="singleTitle">مشاهدة فيلم Fall 2 2026 مترجم</div>
              <ul class="serversList">
                <li data-link="https://q7v3k8m2p1.top/iframe/Uw0W7pOtd12FY"><p>MegaMax</p></li>
              </ul>
            </body></html>
        """.trimIndent()
        assertTrue(!EgyDeadNetwork.isCloudflareBlock(200, realWatchUnlockedHtml))
        assertTrue(EgyDeadNetwork.isValidEgyDeadHtml(200, realWatchUnlockedHtml))
    }
}
