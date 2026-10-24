package com.egydead

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

class EgyDeadLiveE2ETest {

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
            ok && preview
        }
    }.getOrDefault(false)

    @Test
    fun testLiveMultiHostExtractorsDirectVerification() = runBlocking {
        assumeTrue(
            "Skipping live extractor test unless EGYDEAD_LIVE_TESTS=1",
            System.getenv("EGYDEAD_LIVE_TESTS") == "1"
        )
        val refWatch = "https://tv10.egydead.live/fall-2-deadpoint-2026-1080p-web-dl-1/watch/"

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
        assertTrue("Expected MegaMax to extract >= 1 playable stream", megaLinks.isNotEmpty())

        var verifiedMega = 0
        for ((_, group) in megaLinks.groupBy { it.name }) {
            if (verifyPlayableLink(group.first())) verifiedMega++
        }
        assertTrue("Expected >= 1 MegaMax stream to pass HTTP 200/206 verification", verifiedMega >= 1)
    }
}
