package com.egydead

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.Collections
import java.util.concurrent.TimeUnit

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EgyDeadLiveE2ETest {

    companion object {
        private val bridgeHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        @JvmStatic
        @BeforeClass
        fun installLiveBrowserBridgeIfConfigured() {
            val bridgeUrl = System.getenv("EGYDEAD_LIVE_BRIDGE_URL")?.trim()?.takeIf { it.isNotBlank() }
                ?: return
            println("[LIVE-BRIDGE] Installing live Turnstile-solved browser bridge -> $bridgeUrl")
            CloudflareSolver.jvmTestFetchBridge = { url, referer, method, formBody ->
                val payload = JSONObject().apply {
                    put("url", url)
                    put("referer", referer)
                    put("method", method)
                    if (formBody != null) {
                        val formJson = JSONObject()
                        formBody.forEach { (k, v) -> formJson.put(k, v) }
                        put("form", formJson)
                    }
                }
                val req = Request.Builder()
                    .url(bridgeUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                bridgeHttp.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || raw.isBlank()) {
                        null
                    } else {
                        val json = JSONObject(raw)
                        val status = json.optInt("status", 0)
                        val body = json.optString("body", "")
                        if (status > 0 && body.isNotEmpty()) status to body else null
                    }
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun clearLiveBrowserBridge() {
            CloudflareSolver.jvmTestFetchBridge = null
        }
    }

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
    fun test1_LiveMultiHostExtractorsDirectVerification() = runBlocking {
        assumeTrue(
            "Skipping live extractor test unless EGYDEAD_LIVE_TESTS=1 or EGYDEAD_REQUIRE_LIVE_CF=1",
            System.getenv("EGYDEAD_LIVE_TESTS") == "1" || System.getenv("EGYDEAD_REQUIRE_LIVE_CF") == "1"
        )
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
        for ((_, group) in megaLinks.groupBy { it.name }) {
            if (verifyPlayableLink(group.first())) verifiedMega++
        }
        println("[EXTRACTOR] MegaMax verified playable distinct sources: $verifiedMega / ${megaLinks.groupBy { it.name }.size}")
        assertTrue("Expected >= 1 MegaMax mirror/direct stream to pass HTTP 200/206 verification", verifiedMega >= 1)

        // 2. StreamHG (`hanerix.com`), EarnVids (`morencius.com`), Mixdrop (`mixdrop.top`), DoodStream (`playmogo.com`)
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
    fun test2_LiveCloudflareBypassFullEndToEnd() = runBlocking {
        val requireLiveCf = System.getenv("EGYDEAD_REQUIRE_LIVE_CF") == "1"
        assumeTrue(
            "Skipping live Cloudflare E2E unless EGYDEAD_REQUIRE_LIVE_CF=1 or EGYDEAD_LIVE_BRIDGE_URL is set",
            requireLiveCf || !System.getenv("EGYDEAD_LIVE_BRIDGE_URL").isNullOrBlank()
        )

        val t0 = System.currentTimeMillis()
        val homeDoc = EgyDeadNetwork.getHomeLandingDocument(forceRefresh = true)
        val dtHome = System.currentTimeMillis() - t0
        println("[LIVE-CF-BYPASS] getHomeLandingDocument(/h3/) -> ok=${homeDoc != null}, elapsed=${dtHome}ms, transport=(${EgyDeadNetwork.activeTransportSummary()}), title=${homeDoc?.title()}, cards=${homeDoc?.select("li.movieItem")?.size}")

        assertNotNull("CRITICAL: Must bypass Cloudflare LIVE on https://tv10.egydead.live/h3/!", homeDoc)
        assertTrue(
            "Expected real movieItem cards on https://tv10.egydead.live/h3/, got 0",
            homeDoc!!.select("li.movieItem").size >= 20
        )

        // 1. MainPage Featured + Recent shelves (should use shared 90s home landing cache -> sub-50ms!)
        val tShelves = System.currentTimeMillis()
        val featShelf = api.getMainPage(1, MainPageRequest("المواضيع المميزة", "/h3/#featured", false)).items.first().list
        val recentShelf = api.getMainPage(1, MainPageRequest("المضاف حديثاً", "/h3/#recent", false)).items.first().list
        val dtShelves = System.currentTimeMillis() - tShelves
        println("[LIVE-CF-BYPASS] /h3/#featured=${featShelf.size} items (first=${featShelf.firstOrNull()?.name}), /h3/#recent=${recentShelf.size} items (first=${recentShelf.firstOrNull()?.name}) in ${dtShelves}ms")
        assertTrue("Featured shelf on /h3/ must not be empty", featShelf.isNotEmpty())
        assertTrue("Recent shelf on /h3/ must not be empty", recentShelf.isNotEmpty())

        // 2. Paginated Movies Category (page 1 & page 2)
        val moviesReq = MainPageRequest("أحدث الأفلام", "/movies/", false)
        val p1 = api.getMainPage(1, moviesReq).items.first().list
        val p2 = api.getMainPage(2, moviesReq).items.first().list
        println("[LIVE-CF-BYPASS] /movies/ page1=${p1.size} (first=${p1.firstOrNull()?.name}), page2=${p2.size} (first=${p2.firstOrNull()?.name})")
        assertTrue("Movies page 1 must not be empty", p1.isNotEmpty())
        assertTrue("Movies page 2 must not be empty", p2.isNotEmpty())
        assertFalse("Movies page 1 and page 2 must contain distinct items", p1.first().url == p2.first().url)

        // 3. Live Search (`/?s=Lupin`) + Live Series/Season Load
        val s1 = api.search("Lupin", 1)
        println("[LIVE-CF-BYPASS] search(Lupin) -> count=${s1?.items?.size}, first=${s1?.items?.firstOrNull()?.name} (${s1?.items?.firstOrNull()?.url})")
        assertNotNull("Search must return results", s1)
        assertTrue("Search for Lupin must return >= 1 result", s1!!.items.isNotEmpty())

        val seriesCandidate = s1.items.first()
        val seriesLoad = api.load(seriesCandidate.url)
        println("[LIVE-CF-BYPASS] load(${seriesCandidate.url}) -> type=${seriesLoad?.javaClass?.simpleName}, name=${seriesLoad?.name}")
        assertNotNull("Loading live search item must succeed", seriesLoad)
        assertFalse("Series title must not be navbar header", seriesLoad!!.name.contains("القائمه"))

        // 4. Live Movie Load + Live `POST View=1` Watch-Gate Unlock + Stream Extraction (`loadLinks`)
        val liveMovieItem = p1.first()
        val movieLoad = api.load(liveMovieItem.url) as? MovieLoadResponse
        println("[LIVE-CF-BYPASS] loadMovie(${liveMovieItem.url}) -> name=${movieLoad?.name}, year=${movieLoad?.year}, tags=${movieLoad?.tags?.size}, recs=${movieLoad?.recommendations?.size}, dataUrl=${movieLoad?.dataUrl}")
        assertNotNull("Loading live movie must return MovieLoadResponse", movieLoad)
        assertFalse("Movie title must not be navbar header", movieLoad!!.name.contains("القائمه"))

        val liveMovieLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val liveMovieSubs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val unlockedOk = api.loadLinks(
            data = movieLoad!!.dataUrl,
            isCasting = false,
            subtitleCallback = { liveMovieSubs.add(it) },
            callback = { liveMovieLinks.add(it) }
        )
        println("[LIVE-CF-BYPASS] loadLinks(${movieLoad.dataUrl}) -> unlockedOk=$unlockedOk, extractedLinks=${liveMovieLinks.size}, subs=${liveMovieSubs.size}")
        liveMovieLinks.forEach { println("  -> [${it.name}] q=${it.quality} type=${it.type} url=${it.url.take(100)}") }
        assertTrue(
            "Expected live POST View=1 + loadLinks() on ${movieLoad.dataUrl} to extract >= 1 playable video stream!",
            unlockedOk && liveMovieLinks.isNotEmpty()
        )

        var liveVerifiedCount = 0
        for ((_, group) in liveMovieLinks.groupBy { it.name }) {
            if (verifyPlayableLink(group.first())) liveVerifiedCount++
        }
        println("[LIVE-CF-BYPASS] Live Movie verified playable distinct streams: $liveVerifiedCount / ${liveMovieLinks.groupBy { it.name }.size}")
        assertTrue("Expected >= 1 live movie stream to pass HTTP 200/206 verification", liveVerifiedCount >= 1)
    }
}
