package com.egydead

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.Collections

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EgyDeadLiveE2ETest {

    private val api = EgyDead()

    private fun verifyPlayableLink(link: ExtractorLink): Boolean = runCatching {
        val reqBuilder = Request.Builder()
            .url(link.url)
            .header("User-Agent", EgyDeadNetwork.currentUserAgent)
        if (link.referer.isNotBlank()) {
            reqBuilder.header("Referer", link.referer)
        }
        link.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        if (link.type != ExtractorLinkType.M3U8 && !link.url.contains(".m3u8", ignoreCase = true)) {
            reqBuilder.header("Range", "bytes=0-1023")
        }
        EgyDeadNetwork.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val ct = resp.header("Content-Type").orEmpty().lowercase()
            val ok = resp.code in listOf(200, 206)
            val preview = if (link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8", ignoreCase = true)) {
                val head = resp.body?.string()?.take(240).orEmpty()
                head.contains("#EXTM3U")
            } else {
                !ct.contains("text/html") && (resp.body?.bytes()?.size ?: 0) > 0
            }
            println("  [VERIFY] ${link.name} (${link.quality}p, ${link.type}, ct=$ct) -> HTTP ${resp.code}, valid=$preview | ${link.url.take(95)}")
            ok && preview
        }
    }.getOrElse { e ->
        println("  [VERIFY-ERR] ${link.name} -> ${e.javaClass.simpleName}: ${e.message} | ${link.url.take(95)}")
        false
    }

    @Test
    fun test0_DiagnoseRawEndpoints() {
        val r1 = EgyDeadNetwork.executeRawGet("https://tv10.egydead.live/movies/", referer = "https://tv10.egydead.live/")
        println("[DIAG] tv10.egydead.live/movies/ -> code=${r1?.code}, finalUrl=${r1?.finalUrl}, len=${r1?.body?.length}, snippet=${r1?.body?.take(350)?.replace('\n', ' ')}")

        val refWatch = "https://tv10.egydead.live/fall-2-deadpoint-2026-1080p-web-dl-1/watch/"
        val r2 = EgyDeadExtractorKit.getText(
            "https://q7v3k8m2p1.top/iframe/Uw0W7pOtd12FY",
            referer = refWatch,
            headers = mapOf(
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )
        )
        println("[DIAG] q7v3k8m2p1.top/iframe/Uw0W7pOtd12FY -> code=${r2?.code}, len=${r2?.body?.length}, hasDataPage=${r2?.body?.contains("data-page")}")
        if (r2 != null && r2.body.isNotEmpty()) {
            println("[DIAG] iframe snippet: ${r2.body.take(500).replace('\n', ' ')}")
        }

        val r3 = EgyDeadExtractorKit.getText("https://q7v3k8m2p1.top/download/Uw0W7pOtd12FY", referer = refWatch)
        println("[DIAG] q7v3k8m2p1.top/download/Uw0W7pOtd12FY -> code=${r3?.code}, len=${r3?.body?.length}, hasDataPage=${r3?.body?.contains("data-page")}")
    }

    @Test
    fun test1_LiveMultiHostExtractorsDirectVerification() = runBlocking {
        val refWatch = "https://tv10.egydead.live/fall-2-deadpoint-2026-1080p-web-dl-1/watch/"

        // 1. MegaMax Unified 4-Channel Extractor on Fall 2: Deadpoint 2026
        val megaLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val megaSubs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        EgyDeadMirrorRouter.resolve(
            url = "https://q7v3k8m2p1.top/iframe/Uw0W7pOtd12FY",
            serverHint = "MegaMax",
            qualityHint = Qualities.P1080.value,
            referer = refWatch,
            subtitleCallback = { megaSubs.add(it) },
            callback = { megaLinks.add(it) }
        )
        println("[EXTRACTOR] MegaMax (Fall 2 2026) -> links=${megaLinks.size}, subs=${megaSubs.size}")
        megaLinks.forEach { println("  -> [${it.name}] q=${it.quality} type=${it.type} url=${it.url.take(100)}") }
        assertTrue("Expected MegaMax to extract >= 1 playable stream, got ${megaLinks.size}", megaLinks.isNotEmpty())

        var verifiedMega = 0
        for ((prov, group) in megaLinks.groupBy { it.name }) {
            if (verifyPlayableLink(group.first())) verifiedMega++
        }
        println("[EXTRACTOR] MegaMax verified playable distinct sources: $verifiedMega / ${megaLinks.groupBy { it.name }.size}")
        assertTrue("Expected >= 1 MegaMax mirror/direct stream to pass HTTP 200/206 verification", verifiedMega >= 1)

        // 2. StreamHG (`hanerix.com` & `hgcloud.to`), EarnVids (`morencius.com`), Mixdrop (`mixdrop.top`), DoodStream (`playmogo.com`)
        val archiveTargets = listOf(
            "StreamHG" to "https://hanerix.com/e/kldh1j2cvzgm",
            "EarnVids" to "https://morencius.com/file/51ku97w3c7sn",
            "Mixdrop" to "https://mixdrop.top/f/owqnogzdhxmnk9",
            "DoodStream" to "https://playmogo.com/d/3yeuye187d59"
        )
        val archiveLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        for ((hint, targetUrl) in archiveTargets) {
            val before = archiveLinks.size
            EgyDeadMirrorRouter.resolve(
                url = targetUrl,
                serverHint = hint,
                qualityHint = Qualities.P1080.value,
                referer = "https://tv10.egydead.live/",
                subtitleCallback = {},
                callback = { archiveLinks.add(it) }
            )
            println("[EXTRACTOR] $hint ($targetUrl) -> extracted ${archiveLinks.size - before} links")
        }
        archiveLinks.forEach { println("  -> [${it.name}] q=${it.quality} type=${it.type} url=${it.url.take(100)}") }
        assertTrue("Expected archive multi-host extractors to yield links", archiveLinks.isNotEmpty())

        var verifiedArchive = 0
        for ((_, group) in archiveLinks.groupBy { it.name }) {
            if (verifyPlayableLink(group.first())) verifiedArchive++
        }
        println("[EXTRACTOR] Archive verified playable providers: $verifiedArchive")
        assertTrue("Expected >= 1 archive provider to pass HTTP 200/206 verification", verifiedArchive >= 1)
    }

    @Test
    fun test2_LiveSiteCatalogSearchLoadIfReachable() = runBlocking {
        val probe = EgyDeadNetwork.fetchDocument("https://tv10.egydead.live/movies/")
        println("[SITE] fetchDocument(/movies/) reachable=${probe != null}, title=${probe?.title()}")
        assumeTrue(
            "Skipping direct site scrape assertions when Cloudflare challenges GitHub Actions Azure ASN",
            probe != null && probe.select("li.movieItem").isNotEmpty()
        )

        val base = EgyDeadNetwork.getBaseUrl()
        val moviesReq = MainPageRequest("أحدث الأفلام", "/movies/", false)
        val p1 = api.getMainPage(1, moviesReq).items.first().list
        val p2 = api.getMainPage(2, moviesReq).items.first().list
        assertTrue(p1.isNotEmpty() && p2.isNotEmpty())
        assertFalse(p1.first().url == p2.first().url)

        val s1 = api.search("Lupin", 1)
        assertNotNull(s1)
        assertTrue(s1!!.items.isNotEmpty())

        val seasonRes = api.load("$base/seasons/lupin-s04/") as? AnimeLoadResponse
        assertNotNull(seasonRes)
        assertTrue((seasonRes!!.episodes[DubStatus.Subbed]?.size ?: 0) >= 15)
        assertTrue((seasonRes.episodes[DubStatus.Dubbed]?.size ?: 0) >= 15)

        val colRes = api.load("$base/film-series/fall-movies-collection/") as? TvSeriesLoadResponse
        assertNotNull(colRes)
        assertTrue(colRes!!.episodes.size >= 2)
    }
}
