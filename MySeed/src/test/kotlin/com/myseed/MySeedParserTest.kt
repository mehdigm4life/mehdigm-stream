package com.myseed

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MySeedParserTest {
    private val base = "https://m.myseed.tv"

    @Test
    fun listingParserSeparatesMoviesShowsAndEpisodeMetadata() {
        val document = Jsoup.parse(
            """
            <main class="results">
              <a href="/film-alpha-2026/"><img src="/wp-content/uploads/alpha.jpg" alt="فيلم Alpha 2026"><h3>فيلم Alpha 2026</h3></a>
              <a href="/selary/show-alpha/"><img src="/wp-content/uploads/show.jpg" alt="مسلسل Alpha"><h3>مسلسل Alpha</h3></a>
              <a href="/show-alpha-s2-eps3/"><img src="/wp-content/uploads/ep.jpg" alt="الحلقة 3"><h3>مسلسل Alpha الحلقة 3</h3></a>
              <a href="/show-alpha-s2-eps3/watch/"><img src="/wp-content/uploads/player.jpg" alt="مشاهدة الحلقة 3"><h3>مشاهدة الحلقة 3</h3></a>
              <a href="https://other.example/video/"><img src="/wp-content/uploads/external.jpg" alt="External"></a>
            </main>
            """.trimIndent(),
            "$base/find/"
        )

        val movies = MySeedParser.parseListing(document, MySeedContentKind.MOVIE)
        val shows = MySeedParser.parseListing(document, MySeedContentKind.SERIES)
        val episodes = MySeedParser.parseListing(document, MySeedContentKind.EPISODE)

        assertEquals(listOf("فيلم Alpha 2026"), movies.map { it.title })
        assertEquals(listOf("مسلسل Alpha"), shows.map { it.title })
        assertEquals(listOf("مسلسل Alpha الحلقة 3"), episodes.map { it.title })
        assertEquals(2026, movies.single().year)
        assertTrue(movies.single().posterUrl.orEmpty().endsWith("alpha.jpg"))
    }

    @Test
    fun seriesDetailsExtractMetadataAndOnlyCatalogEpisodePages() {
        val document = Jsoup.parse(
            """
            <html>
              <head>
                <meta property="og:image" content="https://m.myseed.tv/wp-content/uploads/series-poster.jpg">
                <meta name="description" content="قصة مسلسل تجريبية.">
              </head>
              <body>
                <h1>مسلسل Alpha</h1>
                <div class="details">
                  <span>سنة العرض : 2026</span>
                  <a href="/quality/web-dl/">WEB-DL</a>
                  <a href="/genre/action/">أكشن</a>
                </div>
                <h2>الحلقات</h2>
                <div class="episodes-list">
                  <a href="/show-alpha-s2-eps2/"><span>الحلقة 2 الثانية</span></a>
                  <a href="/show-alpha-s2-eps1/"><span>الحلقة 1 الأولى</span></a>
                  <a href="/show-alpha-s2-eps2/watch/">مشاهدة الحلقة 2</a>
                  <a href="/show-alpha-s2-eps2/download/">تحميل الحلقة 2</a>
                </div>
              </body>
            </html>
            """.trimIndent(),
            "$base/selary/show-alpha/"
        )

        val details = MySeedParser.parseDetails(document, "$base/selary/show-alpha/")

        assertEquals("مسلسل Alpha", details.title)
        assertEquals(MySeedContentKind.SERIES, details.kind)
        assertEquals(2026, details.year)
        assertEquals("قصة مسلسل تجريبية.", details.plot)
        assertTrue(details.tags.contains("WEB-DL"))
        assertTrue(details.tags.contains("أكشن"))
        assertEquals(listOf(1, 2), details.episodes.map { it.number }.sorted())
        assertTrue(details.episodes.all { it.season == 2 })
        assertTrue(details.episodes.none { it.url.contains("/watch/") || it.url.contains("/download/") })
    }

    @Test
    fun metadataUrlPolicyRejectsWatchDownloadAndForeignHosts() {
        assertEquals("https://m.myseed.tv/film-alpha/", MySeedParser.normalizeContentUrl("/film-alpha/", base))
        assertEquals("https://m.myseed.tv/selary/show-alpha/", MySeedParser.normalizeContentUrl("/selary/show-alpha/", base))
        assertNull(MySeedParser.normalizeContentUrl("/film-alpha/watch/", base))
        assertNull(MySeedParser.normalizeContentUrl("/film-alpha/download/", base))
        assertNull(MySeedParser.normalizeContentUrl("/media/sample.m3u8", base))
        assertNull(MySeedParser.normalizeContentUrl("/l/aHR0cHM6Ly9leGFtcGxlLmNvbQ==", base))
        assertNull(MySeedParser.normalizeContentUrl("https://other.example/film-alpha/", base))
    }

    @Test
    fun episodeNumbersAndArabicSeasonOrdinalsAreNormalized() {
        assertEquals(11, MySeedParser.episodeNumber("$base/show-s2-eps11/", "الحلقة ١١"))
        assertEquals(2, MySeedParser.seasonNumber("$base/show-s2-eps11/"))
        assertEquals(3, MySeedParser.seasonNumber("$base/selary/show/", "الموسم الثالث"))
        assertNull(MySeedParser.seasonNumber("$base/show/", "الحلقة 2 الثانية"))
        assertEquals("مسلسل Alpha", MySeedParser.seriesTitleFromEpisode("مسلسل Alpha الموسم الثاني الحلقة ١١ الحادية عشر"))
    }
}
