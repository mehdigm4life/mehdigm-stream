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
    fun episodeTypeWinsForEpisodeResultsUnderSelaryPaths() {
        val document = Jsoup.parse(
            """
            <a href="/selary/show-alpha-s2-eps3/">
              <img src="/episode.jpg"><h3>مسلسل Alpha الموسم الثاني الحلقة 3</h3>
            </a>
            <a href="/selary/show-alpha-season-2/">
              <img src="/season.jpg"><h3>مسلسل Alpha الموسم الثاني</h3>
            </a>
            """.trimIndent(),
            "$base/find/"
        )

        val episodes = MySeedParser.parseListing(document, MySeedContentKind.EPISODE)
        val shows = MySeedParser.parseListing(document, MySeedContentKind.SERIES)

        assertEquals(1, episodes.size)
        assertTrue(episodes.single().url.endsWith("/selary/show-alpha-s2-eps3/"))
        assertEquals(1, shows.size)
        assertTrue(shows.single().url.endsWith("/selary/show-alpha-season-2/"))
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
        assertEquals(listOf(1, 2), details.episodes.mapNotNull { it.number }.sorted())
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
        assertEquals("مسلسل Alpha", MySeedParser.seriesTitleFromEpisode("مسلسل Alpha الموسم الخامس"))
    }

    @Test
    fun seasonAndEpisodeUrlsResolveToTheSameParentSeries() {
        val parent = MySeedParser.normalizeContentUrl("/selary/show-alpha/", base)

        assertEquals(parent, MySeedParser.seriesRootUrl("$base/selary/show-alpha-season-5/"))
        assertEquals(parent, MySeedParser.seriesRootUrl("$base/selary/show-alpha-الموسم-الثالث-عشر/"))
        assertEquals(parent, MySeedParser.seriesRootUrl("$base/show-alpha-الموسم-الخامس-الحلقة-22/"))
        assertEquals(parent, MySeedParser.seriesRootUrl("$base/show-alpha-s2-eps3/"))
    }

    @Test
    fun seasonParserReturnsOnlySameShowsSeasonsInNumericOrder() {
        val slugs = listOf(
            "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن",
            "التاسع", "العاشر", "الحادي-عشر", "الثاني-عشر", "الثالث-عشر", "الرابع-عشر", "الخامس-عشر"
        )
        val labels = listOf(
            "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن",
            "التاسع", "العاشر", "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر"
        )
        val seasonLinks = (15 downTo 1).joinToString("\n") { number ->
            """<a href="/selary/show-alpha-الموسم-${slugs[number - 1]}/">مسلسل Alpha الموسم ${labels[number - 1]}</a>"""
        }
        val document = Jsoup.parse(
            """<h2>مواسم المسلسل</h2>$seasonLinks
                <a href="/selary/other-show-الموسم-الثالث/">مسلسل Other Show الموسم الثالث</a>""",
            "$base/selary/show-alpha/"
        )
        val root = MySeedParser.normalizeContentUrl("/selary/show-alpha/", base)
        val seasons = MySeedParser.parseSeasons(document, "$base/selary/show-alpha/")
        val seasonPageUrl = "$base/selary/show-alpha-الموسم-الخامس/"
        val seasonPage = Jsoup.parse(seasonLinks, seasonPageUrl)
        val seasonsFromSeasonPage = MySeedParser.parseSeasons(seasonPage, seasonPageUrl)

        assertEquals((1..15).toList(), seasons.map { it.number })
        assertEquals((1..15).toList(), seasonsFromSeasonPage.map { it.number })
        assertTrue(seasons.all { MySeedParser.seriesRootUrl(it.url) == root })
    }

    @Test
    fun seasonEpisodesExcludeSeasonTabsAndRetainEpisodeNumbers() {
        val seasonTabs = (15 downTo 1).joinToString("") { number ->
            """<a href="/selary/show-alpha-season-$number/">مسلسل Alpha الموسم $number</a>"""
        }
        val episodeLinks = (1..22).joinToString("") { number ->
            """<a href="/show-alpha-season-1-episode-$number/">الحلقة $number</a>"""
        }
        val document = Jsoup.parse(
            """<section><h2>الحلقات والمواسم</h2><nav class="season-tabs">$seasonTabs</nav>
                <div class="episodes-list">$episodeLinks</div></section>""",
            "$base/selary/show-alpha-season-1/"
        )
        val episodes = MySeedParser.parseEpisodes(document, "$base/selary/show-alpha-season-1/")

        assertEquals((1..22).toList(), episodes.mapNotNull { it.number }.sorted())
        assertTrue(episodes.all { it.season == 1 })
    }

    @Test
    fun realSupernaturalSeasonSlugsKeepAllFifteenSeasonsInNumericOrder() {
        val seasonPaths = listOf(
            "الاول", "الثاني", "الثاني-عشر", "الثالث", "الثالث-عشر", "الرابع-r26", "الرابع-عشر-مسلسل-supernatural",
            "الخامس", "الخامس-عشر-مسلسل-supernatural", "السادس", "السابع", "الثامن", "التاسع", "العاشر", "الحادي-عشر"
        )
        val seasonAnchors = seasonPaths.joinToString("\n") { ordinal ->
            val label = ordinal.substringBefore("-مسلسل").replace('-',' ')
            """<a href="/selary/مسلسل-supernatural-الموسم-$ordinal/">مسلسل Supernatural الموسم $label</a>"""
        }
        val rootUrl = "$base/selary/مسلسل-supernatural/"
        val document = Jsoup.parse("<h2>مواسم المسلسل</h2>$seasonAnchors", rootUrl)

        val seasons = MySeedParser.parseSeasons(document, rootUrl)

        assertEquals((1..15).toList(), seasons.map { it.number })
        assertTrue(seasons.all { MySeedParser.seriesRootUrl(it.url) == MySeedParser.seriesRootUrl(rootUrl) })
    }

    @Test
    fun realSupernaturalArabicEpisodeSlugRetainsSeasonFiveEpisodeTwentyTwo() {
        val pageUrl = "$base/selary/مسلسل-supernatural-الموسم-الخامس/"
        val document = Jsoup.parse(
            """
            <section>
              <h2>الحلقات والمواسم</h2>
              <nav class="season-tabs"><a href="/selary/مسلسل-supernatural-الموسم-السادس/">الموسم السادس</a></nav>
              <div class="episodes-list">
                <a href="/مسلسل-supernatural-الموسم-الخامس-الحلقة-1-الاولى/">الحلقة 1</a>
                <a href="/مسلسل-supernatural-الموسم-الخامس-الحلقة-22-الثانية/">الحلقة 22</a>
                <a href="/مسلسل-another-show-الموسم-الخامس-الحلقة-22-الثانية/">حلقة مسلسل آخر</a>
              </div>
            </section>
            """.trimIndent(),
            pageUrl
        )

        val episodes = MySeedParser.parseEpisodes(document, pageUrl)

        assertEquals(listOf(1, 22), episodes.mapNotNull { it.number }.sorted())
        assertTrue(episodes.all { it.season == 5 })
        assertEquals("مسلسل Supernatural", MySeedParser.seriesTitleFromEpisode("مسلسل Supernatural الموسم الخامس الحلقة 22 الثانية"))
    }

    @Test
    fun episodeWatchAndDownloadRoutesAreBuiltFromTheMetadataUrl() {
        val episode = "$base/مسلسل-supernatural-الموسم-الخامس-الحلقة-22-الثانية/"
        val watchUrl = MySeedParser.routeUrl(episode, "watch")
        val downloadUrl = MySeedParser.routeUrl(episode, "download")

        assertTrue(watchUrl.orEmpty().endsWith("/watch/"))
        assertTrue(downloadUrl.orEmpty().endsWith("/download/"))
        assertEquals(22, MySeedParser.episodeNumber(watchUrl.orEmpty()))
        val unwrapped = MySeedParser.episodeContentUrl(downloadUrl.orEmpty())
        assertEquals(MySeedParser.seriesRootUrl(episode), MySeedParser.seriesRootUrl(unwrapped.orEmpty()))
        assertEquals(22, MySeedParser.episodeNumber(unwrapped.orEmpty()))
        assertNull(MySeedParser.episodeContentUrl("https://other.example/episode-22/"))
    }

    @Test
    fun movieMetadataUrlsCanBuildWatchAndDownloadRoutes() {
        val movie = "$base/film-alpha-2026/"

        assertEquals(movie, MySeedParser.contentUrl(movie))
        assertEquals("${movie}watch/", MySeedParser.routeUrl(movie, "watch"))
        assertEquals("${movie}download/", MySeedParser.routeUrl(movie, "download"))
        assertNull(MySeedParser.episodeContentUrl(movie))
    }

    @Test
    fun dateBasedWweEpisodesAreKeptFromTheStructuredEpisodeList() {
        val seriesUrl = "$base/selary/wwe-smackdown-21-08-2018/"
        val document = Jsoup.parse(
            """
            <section class="episodes-list">
              <a href="/wwe-smackdown-28-08-2018/watch/">مشاهدة الحلقة 1</a>
              <a href="/wwe-smackdown-04-09-2018/">الحلقة 2</a>
            </section>
            <aside class="related">
              <a href="/other-show-s1-eps7/">الحلقة 7</a>
            </aside>
            """.trimIndent(),
            seriesUrl
        )

        val episodes = MySeedParser.parseEpisodes(document, seriesUrl)

        assertEquals(listOf(1, 2), episodes.mapNotNull { it.number })
        assertTrue(episodes.all { !it.url.endsWith("/watch/") })
        assertTrue(episodes.none { it.url.contains("other-show") })
    }

    @Test
    fun breadcrumbsOverrideWrongSlugDerivedSeriesRootAndNotFoundPagesAreRecognized() {
        val seasonUrl = "$base/selary/مسلسل-rivals-الموسم-الثاني-مترجم/"
        val seriesUrl = "$base/selary/مسلسل-rivals-مترجم/"
        val document = Jsoup.parse(
            """
            <nav class="breadcrumb">
              <a href="$seriesUrl">مسلسل Rivals مترجم</a>
              <a href="$seasonUrl">مسلسل Rivals الموسم الثاني مترجم</a>
            </nav>
            <h1>مسلسل Rivals الموسم الثاني مترجم</h1>
            """.trimIndent(),
            seasonUrl
        )
        val notFound = Jsoup.parse(
            """<html><head><title>WWE Smackdown - الصفحة غير موجودة</title></head>
                <body><h1>الصفحة غير موجودة</h1></body></html>"""
        )

        assertFalse(MySeedParser.seriesRootUrl(seasonUrl) == seriesUrl)
        assertEquals(seriesUrl, MySeedParser.seriesRootFromBreadcrumbs(document, seasonUrl))
        assertFalse(MySeedParser.isNotFoundPage(document))
        assertTrue(MySeedParser.isNotFoundPage(notFound))
    }

    @Test
    fun watchParserFindsSelectableServerAndQualityFromPlayerMarkup() {
        val watchUrl = "$base/episode-22/watch/"
        val document = Jsoup.parse(
            """
            <div class="player-area">
              <div class="server-option" data-quality="480p">
                <button data-server="سيرفر ماي سيد المباشر" data-url="https://media.example/episode-480.m3u8">
                  سيرفر ماي سيد المباشر
                </button>
              </div>
              <div class="server-option" data-quality="720p">
                <iframe title="سيرفر 1" src="https://embed.example/e/episode-720"></iframe>
              </div>
              <a class="server-option" href="https://embed.example/e/episode-1080">سيرفر 2 · 1080p</a>
            </div>
            """.trimIndent(),
            watchUrl
        )

        val servers = MySeedParser.parseWatchServers(document, watchUrl)
        val metaDocument = Jsoup.parse(
            """<meta property="og:video" content="https://media.example/episode-4k.m3u8">""",
            watchUrl
        )
        val metaServers = MySeedParser.parseWatchServers(metaDocument, watchUrl)

        assertEquals(3, servers.size)
        assertTrue(servers.any { it.name == "سيرفر ماي سيد المباشر" && it.quality == 480 && it.url.endsWith("episode-480.m3u8") })
        assertTrue(servers.any { it.name == "سيرفر 1" && it.quality == 720 && it.url == "https://embed.example/e/episode-720" })
        assertTrue(servers.any { it.quality == 1080 && it.url == "https://embed.example/e/episode-1080" })
        assertEquals(2160, metaServers.single().quality)
        assertEquals("https://media.example/episode-4k.m3u8", metaServers.single().url)
    }

    @Test
    fun watchParserDropsMySeedVideoPageAndKeepsPlayableAlternateServer() {
        val watchUrl = "$base/episode-22/watch/"
        val document = Jsoup.parse(
            """
            <div class="player-area">
              <video src="$base/video/489308.mp4"></video>
              <div class="server-option" data-quality="480p">
                <iframe title="سيرفر 1" src="https://embed.example/e/episode-480"></iframe>
              </div>
            </div>
            """.trimIndent(),
            watchUrl
        )

        val servers = MySeedParser.parseWatchServers(document, watchUrl)

        assertTrue(MySeedParser.isKnownNonMediaEndpoint("$base/video/489308.mp4"))
        assertFalse(MySeedParser.isKnownNonMediaEndpoint("https://media.example/video/489308.mp4"))
        assertEquals(1, servers.size)
        assertEquals("https://embed.example/e/episode-480", servers.single().url)
        assertEquals(480, servers.single().quality)
    }

    @Test
    fun downloadParserDecodesMySeedLBase64AndRetainsAllFour720pServers() {
        val downloadUrl = "$base/episode-22/download/"
        val document = Jsoup.parse(
            """
            <section class="downloads">
              <h3>تحميل 720p</h3>
              <a href="/l/aHR0cHM6Ly9kLmFyYWJzZWVkLnRvcC84NHUwcDBsanlpMGcvJTVCYXJhYnNlZWQlNUQuU3VwZXJuYXR1cmFsLlMwNUUyMi43MjBwLm1wNC5odG1s">
                <strong>سيرفر ماي سيد المباشر</strong><span>التحميل الآن - 720p</span>
              </a>
              <a href="/l/aHR0cHM6Ly9mYXN0ZHJpdmUuaW8vNWxMdC8lNUJhcmFic2VlZCU1RC5TdXBlcm5hdHVyYWwuUzA1RTIyLjcyMHAubXA0">
                <strong>Fastdrive</strong><span>التحميل الآن - 720p</span>
              </a>
              <a href="/l/aHR0cHM6Ly91c2VybG9hZC5jby9mLzk0MjAzYTgyZTVlZA==">
                <strong>Userload</strong><span>التحميل الآن - 720p</span>
              </a>
              <a href="/l/aHR0cHM6Ly91cHRvYm94LmNvbS9lbWc5bzBhb3RmdjA=">
                <strong>Uptobox</strong><span>التحميل الآن - 720p</span>
              </a>
            </section>
            """.trimIndent(),
            downloadUrl
        )

        val servers = MySeedParser.parseDownloadServers(document, downloadUrl)

        assertEquals(4, servers.size)
        assertTrue(servers.all { it.quality == 720 })
        assertTrue(servers.any { it.name == "سيرفر ماي سيد المباشر" && it.url.startsWith("https://d.arabseed.top/") })
        assertTrue(servers.any { it.name == "Fastdrive" && it.url.startsWith("https://fastdrive.io/") })
        assertTrue(servers.any { it.name == "Userload" && it.url.startsWith("https://userload.co/") })
        assertTrue(servers.any { it.name == "Uptobox" && it.url.startsWith("https://uptobox.com/") })
    }
}
