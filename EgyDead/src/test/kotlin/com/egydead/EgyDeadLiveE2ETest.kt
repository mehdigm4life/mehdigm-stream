package com.egydead

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

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
            val ok = resp.code in listOf(200, 206)
            val preview = if (link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8", ignoreCase = true)) {
                val head = resp.body?.string()?.take(200).orEmpty()
                head.contains("#EXTM3U")
            } else {
                (resp.body?.bytes()?.size ?: 0) > 0
            }
            println("  [VERIFY] ${link.name} (${link.quality}p, ${link.type}) -> HTTP ${resp.code}, validBody=$preview | ${link.url.take(90)}")
            ok && preview
        }
    }.getOrElse { e ->
        println("  [VERIFY-ERR] ${link.name} -> ${e.message} | ${link.url.take(90)}")
        false
    }

    @Test
    fun test1_LiveDomainDiscoveryAndCatalogPagination() = runBlocking {
        val base = EgyDeadNetwork.getBaseUrl(forceRefresh = true)
        println("Discovered live EgyDead base URL: $base")
        assertTrue("Expected https://*.egydead.* base URL, got $base", base.startsWith("https://") && base.contains("egydead"))

        for ((path, label) in listOf(
            "/movies/" to "أحدث الأفلام",
            "/episodes/" to "أحدث الحلقات",
            "/seasons/" to "أحدث المواسم",
            "/Series/" to "المسلسلات الكاملة",
            "/film-series/" to "سلاسل الأفلام"
        )) {
            val req = MainPageRequest(label, path, false)
            val p1 = api.getMainPage(1, req)
            val items1 = p1.items.firstOrNull()?.list.orEmpty()
            println("Shelf [$label] p1 count=${items1.size}, hasNext=${p1.hasNext}, sample=${items1.firstOrNull()?.name} -> ${items1.firstOrNull()?.url}")
            assertTrue("Shelf $label page 1 should not be empty", items1.isNotEmpty())
            assertTrue("Shelf $label items should have valid posters", items1.any { !it.posterUrl.isNullOrBlank() })
        }

        val moviesReq = MainPageRequest("أحدث الأفلام", "/movies/", false)
        val p1 = api.getMainPage(1, moviesReq).items.first().list
        val p2 = api.getMainPage(2, moviesReq).items.first().list
        assertTrue("Movies page 2 should not be empty", p2.isNotEmpty())
        assertFalse("Movies page 1 and page 2 should differ", p1.first().url == p2.first().url)
    }

    @Test
    fun test2_LiveSearchAndPagination() = runBlocking {
        val s1 = api.search("Lupin", 1)
        assertNotNull(s1)
        println("Search 'Lupin' p1=${s1!!.items.size}, hasNext=${s1.hasNext}")
        assertTrue("Search 'Lupin' p1 should return results", s1.items.isNotEmpty())

        val combined = api.search("Fall")
        println("Combined search 'Fall' count=${combined.size}")
        assertTrue("Search 'Fall' should return results", combined.isNotEmpty())
    }

    @Test
    fun test3_LiveLoadAcrossAllContentTypes() = runBlocking {
        val base = EgyDeadNetwork.getBaseUrl()

        // A. Movie
        val movieRes = api.load("$base/fall-2-deadpoint-2026-1080p-web-dl-1/")
        assertTrue("Expected MovieLoadResponse, got $movieRes", movieRes is MovieLoadResponse)
        movieRes as MovieLoadResponse
        println("Movie loaded: name='${movieRes.name}', year=${movieRes.year}, duration=${movieRes.duration}, tags=${movieRes.tags}, recs=${movieRes.recommendations?.size}")
        assertNotNull(movieRes.posterUrl)
        assertTrue(!movieRes.plot.isNullOrBlank())

        // B. Multi-Season Subbed + Dubbed Series via Season URL (`/seasons/lupin-s04/`)
        val seasonRes = api.load("$base/seasons/lupin-s04/")
        assertTrue("Expected AnimeLoadResponse for Sub+Dub Lupin, got $seasonRes", seasonRes is AnimeLoadResponse)
        seasonRes as AnimeLoadResponse
        val subEps = seasonRes.episodes[DubStatus.Subbed].orEmpty()
        val dubEps = seasonRes.episodes[DubStatus.Dubbed].orEmpty()
        println("Lupin Season load: name='${seasonRes.name}', subEps=${subEps.size}, dubEps=${dubEps.size}")
        assertTrue("Lupin should have Subbed episodes across multiple seasons", subEps.size >= 15)
        assertTrue("Lupin should have Dubbed episodes across multiple seasons", dubEps.size >= 15)

        // C. Fast-Path Episode URL (`/episodes/lupin-s04e01/`)
        val epRes = api.load("$base/episodes/lupin-s04e01/")
        assertNotNull("Episode load should succeed", epRes)
        if (epRes is AnimeLoadResponse) {
            println("Episode fast-path load: sub=${epRes.episodes[DubStatus.Subbed]?.size}, dub=${epRes.episodes[DubStatus.Dubbed]?.size}")
            assertTrue((epRes.episodes[DubStatus.Subbed]?.size ?: 0) >= 8)
        }

        // D. Root Series (`/Series/ao-no-hako-2024/`)
        val seriesRes = api.load("$base/Series/ao-no-hako-2024/")
        assertTrue("Expected AnimeLoadResponse for Ao no Hako, got $seriesRes", seriesRes is AnimeLoadResponse)
        seriesRes as AnimeLoadResponse
        println("Root Series Ao no Hako: sub=${seriesRes.episodes[DubStatus.Subbed]?.size}, dub=${seriesRes.episodes[DubStatus.Dubbed]?.size}")
        assertTrue((seriesRes.episodes[DubStatus.Subbed]?.size ?: 0) >= 20)

        // E. Movie Collection (`/film-series/fall-movies-collection/`)
        val colRes = api.load("$base/film-series/fall-movies-collection/")
        assertTrue("Expected TvSeriesLoadResponse for film-series, got $colRes", colRes is TvSeriesLoadResponse)
        colRes as TvSeriesLoadResponse
        println("Film Collection loaded: '${colRes.name}', movies=${colRes.episodes.map { it.name }}")
        assertTrue("Fall collection should contain >= 2 movies", colRes.episodes.size >= 2)
    }

    @Test
    fun test4_LiveMegaMaxAndMultiHostStreamExtraction() = runBlocking {
        val base = EgyDeadNetwork.getBaseUrl()

        // Inspect raw MegaMax /iframe/ & /download/ structure on Fall 2: Deadpoint 2026
        val watchDoc = EgyDeadNetwork.fetchDocument("$base/fall-2-deadpoint-2026-1080p-web-dl-1/watch/", referer = "$base/fall-2-deadpoint-2026-1080p-web-dl-1/")
        assertNotNull("watchDoc should load", watchDoc)
        val watchLis = watchDoc!!.select("ul.serversList li, [data-link]").map { "${it.text()}=${it.attr("data-link")}" }
        val dlLis = watchDoc.select("ul.donwload-servers-list li, ul.download-servers-list li").map {
            "${it.selectFirst(".ser-name")?.text()}(${it.selectFirst(".ser-quality, em")?.text()})=${it.selectFirst("a")?.attr("href")}"
        }
        println("Fall 2 Watch DOM servers: $watchLis")
        println("Fall 2 Download DOM servers: $dlLis")

        // 1. Test 2026 Movie (MegaMax 4-channel pipeline)
        val megaMaxLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val megaMaxSubs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val ok1 = api.loadLinks(
            data = "$base/fall-2-deadpoint-2026-1080p-web-dl-1/",
            isCasting = false,
            subtitleCallback = { megaMaxSubs.add(it) },
            callback = { megaMaxLinks.add(it) }
        )
        println("Fall 2 (MegaMax) total links=${megaMaxLinks.size}, subs=${megaMaxSubs.size}")
        megaMaxLinks.forEach { l -> println("  -> [${l.name}] q=${l.quality} type=${l.type} url=${l.url.take(100)}") }
        assertTrue("loadLinks on 2026 MegaMax movie must succeed", ok1 && megaMaxLinks.isNotEmpty())

        val verifiedMegaMax = megaMaxLinks.take(6).count { verifyPlayableLink(it) }
        println("Verified playable MegaMax streams: $verifiedMegaMax / ${minOf(6, megaMaxLinks.size)}")
        assertTrue("At least 1 extracted MegaMax stream must return HTTP 200/206 playable bytes/M3U8", verifiedMegaMax >= 1)

        // 2. Test Multi-Provider Archive Movie (`Cruella 2021` -> StreamHG, EarnVids, Mixdrop, DoodStream, KoramaUp)
        val multiLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val multiSubs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val ok2 = api.loadLinks(
            data = "$base/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-cruella-2021-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/",
            isCasting = false,
            subtitleCallback = { multiSubs.add(it) },
            callback = { multiLinks.add(it) }
        )
        println("Cruella 2021 (Multi-Host) total links=${multiLinks.size}, subs=${multiSubs.size}")
        multiLinks.forEach { l -> println("  -> [${l.name}] q=${l.quality} type=${l.type} url=${l.url.take(100)}") }
        assertTrue("loadLinks on Cruella 2021 must succeed", ok2 && multiLinks.isNotEmpty())

        // Verify at least 1 link per distinct provider source
        val byProvider = multiLinks.groupBy { it.name }.mapValues { it.value.first() }
        var verifiedProviders = 0
        for ((prov, link) in byProvider) {
            if (verifyPlayableLink(link)) verifiedProviders++
        }
        println("Verified playable distinct providers on Cruella 2021: $verifiedProviders / ${byProvider.size} (${byProvider.keys})")
        assertTrue("At least 1 multi-host provider on Cruella 2021 must stream HTTP 200/206", verifiedProviders >= 1)
    }
}
