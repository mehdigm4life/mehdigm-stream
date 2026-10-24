package com.cimanow

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CimaNowLiveE2ETest {

    @Test
    fun testCryptoPrimitivesDeterministic() {
        // 1. V8-equivalent expression evaluator
        val expr =
            "Math.floor(((((Math.min(11, 28)) << (('abcZ'.indexOf('Z') + (-2)))) | 1) + " +
                "(('abcdefgZ'.indexOf('Z') + (202)) + Math.max(398, 418)) + " +
                "(((Math.abs(-9)) * 54) + (('abZ'.indexOf('Z') + (-2)))) + " +
                "((Math.min(83, 106)) ^ (Math.max(16, 40))) + " +
                "((Math.min(571, 595)) * (typeof window.decodeURIComponent === 'function' ? 1 : 0))) / 3)"
        assertEquals(610L, CimaNowCrypto.evalMathExpr(expr))
        assertEquals(280L, CimaNowCrypto.evalMathExpr("0xFF + 0b1010 + 0o17"))

        // 2. FNV-1a base36 `core.php` signature
        val sig = CimaNowCrypto.calcCoreSig(
            "d224f3b047d641a53111e7a19b7091c688038510dbfeca943dbe964114b13c25",
            "139241",
            "66",
            "cnSig12",
        )
        assertTrue(sig.isNotBlank())

        // 3. `__cnBootFrame` (`data-cnk` + `data-cna`) iframe decryption
        val sampleIframe =
            """<iframe id="cn_fr_02bd5628" src="about:blank" data-cnk="633637376630323664653663303836346663363863613432" data-cna="12.86.98.105.46.66.72.123.104.112.210.148.253.231.252.208.196.247.169.181.70.22.127.108.53.20.11.126.54.223.203.194.170.226.156"></iframe>"""
        assertEquals("https://cimanow.upns.online/#otni3k", CimaNowCrypto.decodeCnBootFrame(sampleIframe))

        // 4. `solveGatewayPostBody` repeating-XOR + `window["..."] = [...]` unpacker
        val innerScript =
            "<script>window.d={'k1':'chVal','k2':'riVal','k3':'AAECAw==','k4':'abcd'};" +
                "window.map_a1={ch:'k1',ri:'k2',ke:'k3',se:'k4'};</script>"
        val xorKey = byteArrayOf(0x5A, 0x33, 0x71)
        val xored = ByteArray(innerScript.length) { i ->
            (innerScript[i].code xor (xorKey[i % xorKey.size].toInt() and 0xFF)).toByte()
        }
        val b64Full = java.util.Base64.getEncoder().encodeToString(xored)
        val chunkedJs = b64Full.chunked(17).joinToString(",") { "\"$it\"" }
        val mockVault = """<html><body><script>window["_0x9f2a"] = [$chunkedJs];</script></body></html>"""
        val solvedPost = CimaNowCrypto.solveGatewayPostBody(mockVault)
        assertNotNull("Expected solveGatewayPostBody to unpack window[\"_0x9f2a\"] array", solvedPost)
        assertTrue(solvedPost!!.contains("request_id=riVal") && solvedPost.contains("ch=chVal") && solvedPost.contains("hmac_token="))
    }

    @Test
    fun testFullLiveEndToEndSuite() = runBlocking {
        assumeTrue("Set CIMANOW_LIVE_E2E=1 to run live network E2E verification", System.getenv("CIMANOW_LIVE_E2E") == "1")

        val api = CimaNow()

        // ── 1. Verify Catalogs & Pagination ──────────────────────────────────
        val shelvesToCheck = listOf(
            MainPageRequest("أحدث الإضافات", "الاحدث", false),
            MainPageRequest("الأكثر مشاهدة", "الاكثر-مشاهدة", false),
            MainPageRequest("أفلام أجنبية", "category/افلام-اجنبية", false),
            MainPageRequest("أفلام عربية", "category/افلام-عربية", false),
            MainPageRequest("مسلسلات أجنبية", "category/مسلسلات-اجنبية", false),
            MainPageRequest("مسلسلات رمضان 2026", "category/رمضان-2026", false),
            MainPageRequest("أرشيف مسلسلات رمضان", "category/رمضان", false),
            MainPageRequest("مسلسلات نتفليكس", "category/مسلسلات-نتفليكس", false),
            MainPageRequest("مسلسلات أنيميشن", "category/مسلسلات-انيميشن", false),
            MainPageRequest("أطفال", "category/اطفال", false),
        )

        for (req in shelvesToCheck) {
            val p1 = api.getMainPage(1, req)
            val p1Items = p1.items.firstOrNull()?.list.orEmpty()
            println("[E2E][Catalog] ${req.name} p1 -> count=${p1Items.size} hasNext=${p1.hasNext}")
            assertTrue("Expected items in shelf ${req.name}", p1Items.isNotEmpty())
            assertTrue("Expected valid posters in shelf ${req.name}", p1Items.any { !it.posterUrl.isNullOrBlank() })
        }

        // Verify page 1 vs page 2 pagination produces distinct items
        val foreignMoviesReq = MainPageRequest("أفلام أجنبية", "category/افلام-اجنبية", false)
        val fmP1 = api.getMainPage(1, foreignMoviesReq).items.first().list
        val fmP2 = api.getMainPage(2, foreignMoviesReq).items.first().list
        println("[E2E][Pagination] Foreign Movies p1=${fmP1.size}, p2=${fmP2.size}")
        assertTrue("Expected page 2 items", fmP2.isNotEmpty())
        assertFalse("Expected distinct page 2 URLs", fmP1.first().url == fmP2.first().url)

        // ── 2. Verify Search & Paginated Search ──────────────────────────────
        val lupinSearch = api.search("Lupin", 1)
        assertNotNull(lupinSearch)
        println("[E2E][Search] 'Lupin' -> count=${lupinSearch!!.items.size}")
        assertTrue("Expected Lupin search results", lupinSearch.items.isNotEmpty())

        val movieSearchP1 = api.search("فيلم", 1)!!
        val movieSearchP2 = api.search("فيلم", 2)!!
        println("[E2E][SearchPagination] 'فيلم' p1=${movieSearchP1.items.size} (hasNext=${movieSearchP1.hasNext}), p2=${movieSearchP2.items.size}")
        assertTrue(movieSearchP1.items.isNotEmpty() && movieSearchP2.items.isNotEmpty())

        // ── 3. Verify `load()` on Movies & Multi-Season Series ───────────────
        val fall2Url = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/"
        val fall2Load = api.load(fall2Url) as? MovieLoadResponse
        assertNotNull("Expected MovieLoadResponse for Fall 2", fall2Load)
        println(
            "[E2E][Load Movie] title='${fall2Load!!.name}' year=${fall2Load.year} dur=${fall2Load.duration}m " +
                "tags=${fall2Load.tags?.size ?: 0} actors=${fall2Load.actors?.size ?: 0} recs=${fall2Load.recommendations?.size ?: 0} poster=${fall2Load.posterUrl}",
        )
        assertFalse(fall2Load.posterUrl.isNullOrBlank())
        assertFalse(fall2Load.plot.isNullOrBlank())
        assertEquals(2026, fall2Load.year)

        val lupinSelaryUrl = "https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin/"
        val lupinLoad = api.load(lupinSelaryUrl) as? TvSeriesLoadResponse
        assertNotNull("Expected TvSeriesLoadResponse for Lupin /selary/", lupinLoad)
        val seasonsFound = lupinLoad!!.episodes.mapNotNull { it.season }.distinct().sorted()
        println(
            "[E2E][Load Series Root] title='${lupinLoad.name}' seasons=$seasonsFound totalEpisodes=${lupinLoad.episodes.size} " +
                "plotLen=${lupinLoad.plot?.length ?: 0} actors=${lupinLoad.actors?.size ?: 0}",
        )
        assertEquals("Expected all 4 seasons [1, 2, 3, 4] on root /selary/, got $seasonsFound", listOf(1, 2, 3, 4), seasonsFound)
        assertTrue("Expected >= 15 total episodes across Lupin seasons", lupinLoad.episodes.size >= 15)

        val lupinS4SelaryUrl = "https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/"
        val lupinS4Load = api.load(lupinS4SelaryUrl) as? TvSeriesLoadResponse
        assertNotNull("Expected TvSeriesLoadResponse for Lupin S4 /selary/", lupinS4Load)
        val s4SeasonsFound = lupinS4Load!!.episodes.mapNotNull { it.season }.distinct().sorted()
        println(
            "[E2E][Load Series S4] title='${lupinS4Load.name}' seasons=$s4SeasonsFound totalEpisodes=${lupinS4Load.episodes.size}",
        )
        assertEquals("Expected all 4 seasons [1, 2, 3, 4] on S4 /selary/, got $s4SeasonsFound", listOf(1, 2, 3, 4), s4SeasonsFound)

        // ── 4. Verify `loadLinks()` + Live Stream HTTP 200/206 Probing ───────
        CimaNowExtractors.debugLogger = { println("    [E2E][Trace] $it") }
        val movieLinks = CopyOnWriteArrayList<ExtractorLink>()
        val movieSubs = CopyOnWriteArrayList<SubtitleFile>()
        val okMovie = api.loadLinks(fall2Load.dataUrl, false, { movieSubs.add(it) }, { movieLinks.add(it) })
        val movieProviders = movieLinks.map { it.source }.distinct()
        println("[E2E][LoadLinks Movie] ok=$okMovie links=${movieLinks.size} subs=${movieSubs.size} providers=$movieProviders")
        for (lnk in movieLinks) {
            println("  -> [${lnk.source}] ${lnk.name} q=${lnk.quality} type=${lnk.type} url=${lnk.url.take(100)}")
        }
        assertTrue("Expected loadLinks=true on Fall 2", okMovie)
        assertTrue("Expected >= 5 extracted links on Fall 2, got ${movieLinks.size}", movieLinks.size >= 5)
        assertTrue("Expected >= 3 distinct providers on Fall 2, got $movieProviders", movieProviders.size >= 3)

        // Probe HTTP 200/206 reachability on at least 3 distinct providers!
        var verifiedPlayableProviders = 0
        for ((prov, group) in movieLinks.groupBy { it.source }) {
            val sample = group.first()
            val playable = probePlayableStream(sample)
            println("  [StreamProbe] provider=$prov playable=$playable url=${sample.url.take(90)}")
            if (playable) verifiedPlayableProviders++
        }
        assertTrue("Expected >= 3 live playable providers returning 200/206 media, got $verifiedPlayableProviders", verifiedPlayableProviders >= 3)

        // Also verify `loadLinks()` on a Series Episode (Lupin S4E1 / latest episode)
        val sampleEp = lupinLoad.episodes.last()
        val epLinks = CopyOnWriteArrayList<ExtractorLink>()
        val okEp = api.loadLinks(sampleEp.data, false, {}, { epLinks.add(it) })
        println("[E2E][LoadLinks Episode] ep='${sampleEp.name}' S${sampleEp.season}E${sampleEp.episode} ok=$okEp links=${epLinks.size} providers=${epLinks.map { it.source }.distinct()}")
        assertTrue("Expected loadLinks=true on series episode", okEp && epLinks.isNotEmpty())
    }

    private fun probePlayableStream(link: ExtractorLink): Boolean = runCatching {
        val rb = Request.Builder().url(link.url)
        for ((k, v) in link.headers) rb.header(k, v)
        if (link.referer.isNotBlank() && !link.headers.containsKey("Referer")) {
            rb.header("Referer", link.referer)
        }
        if (link.type == ExtractorLinkType.M3U8) {
            CimaNowNetwork.directNoProxyClient.newCall(rb.build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                res.code in 200..299 && body.contains("#EXTM3U")
            }
        } else {
            rb.header("Range", "bytes=0-1023")
            CimaNowNetwork.directNoProxyClient.newCall(rb.build()).execute().use { res ->
                val ct = res.header("Content-Type").orEmpty().lowercase()
                (res.code == 200 || res.code == 206) &&
                    !ct.contains("text/html") &&
                    (ct.contains("video") || ct.contains("octet-stream") || ct.contains("mp4"))
            }
        }
    }.getOrDefault(false)
}
