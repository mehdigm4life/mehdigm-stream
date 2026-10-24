package com.egydead

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
        // Idempotent when already encoded
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
    fun testOpenSslAes256CbcRoundTrip() {
        val pass = "k7v2m4x9bqwpnj6t"
        val salt = "12345678".toByteArray(Charsets.US_ASCII)
        val plain = "https://cdn777.megamax.me/hls/master.m3u8"

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
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(derived.copyOfRange(0, 32), "AES"),
            IvParameterSpec(derived.copyOfRange(32, 48))
        )
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val openSslBlob = Base64.getEncoder().encodeToString("Salted__".toByteArray(Charsets.US_ASCII) + salt + enc)

        val decrypted = EgyDeadExtractorKit.decryptOpenSslAesCbc(openSslBlob, pass)
        assertNotNull(decrypted)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testJsUnpacker() {
        val packed = """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1={"2":"3://4.5/6.7"};',8,8,'var|links|hls4|https|cdn|example|master|m3u8'.split('|'),0,{}))"""
        val unpacked = EgyDeadExtractorKit.unpackJs(packed)
        assertEquals(1, unpacked.size)
        assertEquals("""var links={"hls4":"https://cdn.example/master.m3u8"};""", unpacked.first())
    }
}
