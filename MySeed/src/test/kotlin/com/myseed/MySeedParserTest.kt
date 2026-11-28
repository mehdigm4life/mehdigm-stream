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
    fun translatedSuffixesDoNotBreakSeriesSeasonOrEpisodeMatching() {
        val rootUrl = "$base/selary/مسلسل-house-of-the-dragon-مترجم/"
        val seasonOneUrl = "$base/selary/مسلسل-house-of-the-dragon-الموسم-الاول-مترجم/"
        val seasonTwoUrl = "$base/selary/مسلسل-house-of-the-dragon-الموسم-الثاني-مترجم/"
        val firstEpisodeUrl = "$base/مسلسل-house-of-the-dragon-الموسم-الاول-الحلقة-1-الاولى/"
        val secondEpisodeUrl = "$base/مسلسل-house-of-the-dragon-الموسم-الاول-الحلقة-2-الثانية/"
        val unrelatedSeasonUrl = "$base/selary/مسلسل-another-show-الموسم-الاول-مترجم/"
        val seriesPage = Jsoup.parse(
            """
            <nav class="season-tabs">
              <a href="$seasonTwoUrl">الموسم الثاني مترجم</a>
              <a href="$seasonOneUrl">الموسم الاول مترجم</a>
              <a href="$unrelatedSeasonUrl">مسلسل آخر الموسم الاول مترجم</a>
            </nav>
            <div class="episodes-list">
              <a href="$firstEpisodeUrl">الحلقة 1 الاولى</a>
            </div>
            """.trimIndent(),
            rootUrl
        )
        val seasonPage = Jsoup.parse(
            """
            <nav class="season-tabs">
              <a href="$seasonTwoUrl">الموسم الثاني مترجم</a>
              <a href="$seasonOneUrl">الموسم الاول مترجم</a>
            </nav>
            <div class="episodes-list">
              <a href="$firstEpisodeUrl">الحلقة 1 الاولى</a>
              <a href="$secondEpisodeUrl">الحلقة 2 الثانية</a>
            </div>
            """.trimIndent(),
            seasonOneUrl
        )

        val rootIdentity = MySeedParser.seriesIdentityKey(rootUrl)
        val seasonsFromRoot = MySeedParser.parseSeasons(seriesPage, rootUrl)
        val rootEpisodes = MySeedParser.parseEpisodes(seriesPage, rootUrl)
        val seasonsFromSeasonPage = MySeedParser.parseSeasons(seasonPage, seasonOneUrl)
        val seasonEpisodes = MySeedParser.parseEpisodes(seasonPage, seasonOneUrl)

        assertEquals(rootIdentity, MySeedParser.seriesIdentityKey(seasonOneUrl))
        assertEquals(rootIdentity, MySeedParser.seriesIdentityKey(firstEpisodeUrl))
        assertEquals(listOf(1, 2), seasonsFromRoot.map { it.number })
        assertEquals(listOf(1, 2), seasonsFromSeasonPage.map { it.number })
        assertEquals(listOf(1), rootEpisodes.mapNotNull { it.number })
        assertEquals(listOf(1, 2), seasonEpisodes.mapNotNull { it.number })
        assertTrue(seasonEpisodes.all { it.season == 1 })
        assertFalse(seasonsFromRoot.any { it.url == unrelatedSeasonUrl })
    }

    @Test
    fun searchCardsFromRootSeasonAndEpisodePagesCollapseToTheCanonicalShow() {
        val rootUrl = "$base/selary/مسلسل-outlander-blood-of-my-blood-مترجم/"
        val seasonUrl = "$base/selary/مسلسل-outlander-blood-of-my-blood-الموسم-الاول-مترجم/"
        val episodeUrl = "$base/مسلسل-outlander-blood-of-my-blood-الموسم-الثاني-الحلقة-2-الثانية/"
        val cards = listOf(
            MySeedCatalogCard(
                title = "مسلسل Outlander Blood of my Blood الموسم الثاني الحلقة 2 الثانية مترجمة",
                url = episodeUrl,
                posterUrl = "https://m.myseed.tv/episode.jpg",
                year = 2025,
                kind = MySeedContentKind.EPISODE
            ),
            MySeedCatalogCard(
                title = "مسلسل Outlander Blood of my Blood الموسم الاول مترجم",
                url = seasonUrl,
                posterUrl = null,
                year = null,
                kind = MySeedContentKind.SERIES
            ),
            MySeedCatalogCard(
                title = "مسلسل Outlander Blood of my Blood مترجم",
                url = rootUrl,
                posterUrl = "https://m.myseed.tv/series.jpg",
                year = 2025,
                kind = MySeedContentKind.SERIES
            ),
            MySeedCatalogCard(
                title = "فيلم Outlander 2025",
                url = "$base/film-outlander-2025/",
                posterUrl = null,
                year = 2025,
                kind = MySeedContentKind.MOVIE
            )
        )

        val results = MySeedParser.canonicalizeSearchCards(cards)

        assertEquals(2, results.size)
        val series = results.single { it.kind == MySeedContentKind.SERIES }
        assertEquals(rootUrl, series.url)
        assertEquals("مسلسل Outlander Blood of my Blood مترجم", series.title)
        assertEquals("https://m.myseed.tv/series.jpg", series.posterUrl)
        assertEquals(MySeedContentKind.MOVIE, results.single { it.kind == MySeedContentKind.MOVIE }.kind)
    }

    @Test
    fun compactSeasonEpisodeSlugsAreParsedWithoutLeakingSeasonIntoShowTitle() {
        val parentUrl = "$base/selary/show-alpha/"
        val episodeUrl = "$base/show-alpha-s02e03/"

        assertEquals(2, MySeedParser.seasonNumber(episodeUrl))
        assertEquals(3, MySeedParser.episodeNumber(episodeUrl))
        assertNull(MySeedParser.seasonNumber("$base/selary/series-2025/"))
        assertEquals(MySeedParser.seriesIdentityKey(parentUrl), MySeedParser.seriesIdentityKey(episodeUrl))
        assertEquals("Show Alpha", MySeedParser.seriesTitleFromEpisode("Show Alpha S02E03"))
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

        val canonicalSeriesUrl = requireNotNull(MySeedParser.normalizeContentUrl(seriesUrl, base))
        assertFalse(MySeedParser.seriesRootUrl(seasonUrl) == canonicalSeriesUrl)
        assertEquals(canonicalSeriesUrl, MySeedParser.seriesRootFromBreadcrumbs(document, seasonUrl))
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
              <a class="server-option" href="https://embed.example/e/episode-download">(تحميل) سيرفر 3 · 720p</a>
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
        assertFalse(servers.any { it.url == "https://embed.example/e/episode-download" })
        assertEquals(2160, metaServers.single().quality)
        assertEquals("https://media.example/episode-4k.m3u8", metaServers.single().url)
    }

    @Test
    fun watchParserKeepsMySeedPlayerEndpointsAndFiltersScriptNoise() {
        val direct = "$base/vids.php?t=c545f3e5ae1263525d6c2846a442a5c17c478a01b3360ca753037bd02f03b02e"
        val mirror = "https://m.myseed.pics/vid/?id=aHR0cHM6Ly92aWRhcmEudG8vZS9vaDdJRWtTZG5UUlI1"
        val byse = "https://m.myseed.pics/vid/?id=aHR0cHM6Ly9ieXNlemVqYXRhb3MuY29tL2UvNTR6Ymg0YWhicHY3"
        val voe = "https://m.myseed.pics/vid/?id=aHR0cHM6Ly92b2Uuc3gvZS8wdHlvdnk4OXZ2bGI"
        val document = Jsoup.parse(
            """
            <script>
              const player = { file: "$direct" };
              const alternate = { src: "$mirror" };
              const alternateByse = { src: "$byse" };
              const alternateVoe = { src: "$voe" };
              const fakePath = "https://m.myseed.tv/film-7/watch/video/mp4";
              const fakeQuality = "https://m.myseed.tv/film-7/watch/720";
              const emptyWrapper = "https://m.myseed.tv/vid.php?url=";
              const staticAsset = "https://llvpn.com/tag.min.js";
              const regexText = "https://e/i.test(url)";
            </script>
            """.trimIndent(),
            "$base/film-7-dogs-2026/watch/"
        )

        val options = MySeedParser.parseWatchServers(document, "$base/film-7-dogs-2026/watch/")

        assertEquals(setOf(direct, mirror, byse, voe), options.map { it.url }.toSet())
        assertEquals("Vidara", options.single { it.url == mirror }.name)
        assertEquals("Byse", options.single { it.url == byse }.name)
        assertEquals("VOE", options.single { it.url == voe }.name)
        assertTrue(MySeedParser.isInternalPlayerUrl(mirror))
        assertTrue(MySeedParser.isMySeedPlayerTarget(direct))
        assertTrue(MySeedParser.isMySeedPlayerTarget(mirror))
        assertTrue(MySeedParser.isMySeedPlayerTarget(byse))
        assertTrue(MySeedParser.isMySeedPlayerTarget(voe))
        assertFalse(MySeedParser.isMySeedPlayerTarget("$base/vid.php?url="))
        assertFalse(MySeedParser.isMySeedPlayerTarget("$base/film-7-dogs-2026/watch/720"))
        assertFalse(MySeedParser.isInternalPlayerUrl("https://other.example/vid/"))
    }

    @Test
    fun mySeedMirrorPlayerPageExposesExternalExtractorLinks() {
        val mirrorUrl = "https://m.myseed.pics/vid/?id=aHR0cHM6Ly92aWRhcmEudG8vZS9vaDdJRWtTZG5UUlI1"
        val finalPlayerUrl = "$base/vid/?id=aHR0cHM6Ly92aWRhcmEudG8vZS9vaDdJRWtTZG5UUlI1"
        val document = Jsoup.parse(
            """<div class="player__iframe"><iframe title="Vidara" src="https://vidara.to/e/oh7IEkSdnTRR5"></iframe></div>""",
            finalPlayerUrl
        )

        val options = MySeedParser.parseWatchServers(document, mirrorUrl)

        assertTrue(MySeedParser.isInternalPlayerUrl(mirrorUrl))
        assertEquals(1, options.size)
        assertEquals("https://vidara.to/e/oh7IEkSdnTRR5", options.single().url)
        assertEquals("Vidara", options.single().name)
    }

    @Test
    fun mySeedDirectServerStateBuildsItsCsrfAjaxRequest() {
        val watchUrl = "$base/show-alpha-s1-eps1/watch/"
        val document = Jsoup.parse(
            """
            <script>
              const myseedPlayer = { psot_id: 9274, csrf__token: "csrf-live-token" };
            </script>
            <ul class="servers__list">
              <li data-server="0" data-qu="480"><span>سيرفر ماي سيد المباشر</span></li>
              <li data-server="1" data-qu="480" data-link="https://embed.example/e/alpha">سيرفر 1</li>
              <li data-server="2" data-qu="720" data-link="https://embed.example/e/download">تحميل سيرفر 2</li>
            </ul>
            """.trimIndent(),
            watchUrl
        )

        val state = MySeedParser.parseWatchPlaybackState(document, watchUrl)
        val direct = state.serverChoices.single { it.isDirectServer }
        val request = MySeedParser.directServerRequestData(direct, state)

        assertEquals("9274", state.postId)
        assertEquals("csrf-live-token", state.csrfToken)
        assertEquals("0", direct.serverId)
        assertEquals(480, direct.quality)
        assertNull(direct.url)
        assertTrue(state.serverChoices.none { it.serverId == "2" })
        assertEquals(
            mapOf("post_id" to "9274", "quality" to "480", "server" to "0", "csrf_token" to "csrf-live-token"),
            request
        )
    }

    @Test
    fun mySeedDirectPlayerUnwrapsGatewayAndQualitySources() {
        val playerUrl = "$base/vids.php?t=0123456789abcdef"
        val playerHtml = """<div class="player"><iframe src="https://d.myseed.tv/player-gateway.php?id=abc"></iframe></div>"""
        val gatewayUrl = requireNotNull(MySeedParser.extractMySeedPlayerGateway(playerHtml, playerUrl))
        val gatewayHtml = """
            <video controls>
              <source src="/media/got-480.mp4" type="video/mp4" label="480p">
              <source src="https://cdn.myseed.tv/media/got-720.m3u8?token=x" type="application/x-mpegURL" label="720p">
            </video>
        """.trimIndent()

        val sources = MySeedParser.parseMySeedDirectMediaSources(gatewayHtml, gatewayUrl)

        assertEquals("https://d.myseed.tv/player-gateway.php?id=abc", gatewayUrl)
        assertEquals(2, sources.size)
        assertTrue(sources.any { it.url == "https://d.myseed.tv/media/got-480.mp4" && it.quality == 480 })
        assertTrue(sources.any { it.url == "https://cdn.myseed.tv/media/got-720.m3u8?token=x" && it.quality == 720 })
        assertEquals("https://d.myseed.tv", MySeedParser.originOf(gatewayUrl))
    }

    @Test
    fun liveGameOfThronesDirectServerResolvesToPlayableMediaUrl() {
        val watchUrl = "$base/%d9%85%d8%b3%d9%84%d8%b3%d9%84-game-of-thrones-%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-%d8%a7%d9%84%d8%a7%d9%88%d9%84-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a-y60/watch/"
        val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val session = Jsoup.newSession()
        val watchResponse = try {
            session.newRequest(watchUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$base/")
                .timeout(45_000)
                .ignoreHttpErrors(true)
                .execute()
        } catch (error: Exception) {
            throw AssertionError("Game of Thrones watch page request failed: ${error.javaClass.simpleName}: ${error.message}")
        }
        val watchDocument = watchResponse.parse()
        val state = MySeedParser.parseWatchPlaybackState(watchDocument, watchUrl)
        val parsedDirectOption = MySeedParser.parseWatchServers(watchDocument, watchUrl)
            .firstOrNull { MySeedParser.isMySeedDirectServerUrl(it.url) }
        val directChoice = state.serverChoices.firstOrNull { it.isDirectServer }
            ?: parsedDirectOption?.let {
                MySeedWatchServerChoice(it.name, "0", it.quality, it.url, isDirectServer = true)
            }
        val reportPrefix = "status=${watchResponse.statusCode()} title=${watchDocument.title()} direct=${directChoice?.name} serverId=${directChoice?.serverId} quality=${directChoice?.quality} postIdPresent=${state.postId != null} csrfPresent=${state.csrfToken != null}"
        assertTrue("Game of Thrones page did not expose the direct MySeed server. $reportPrefix", watchResponse.statusCode() in 200..299 && directChoice != null)

        var directUrl = directChoice?.url
        if (directUrl.isNullOrBlank()) {
            val requestData = directChoice?.let { MySeedParser.directServerRequestData(it, state) }
                ?: throw AssertionError("Direct server has no CSRF AJAX metadata. $reportPrefix")
            val ajaxResponse = try {
                session.newRequest("$base/get__watch__server/")
                    .method(org.jsoup.Connection.Method.POST)
                    .data(requestData)
                    .userAgent(userAgent)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", base)
                    .referrer(watchUrl)
                    .timeout(45_000)
                    .ignoreHttpErrors(true)
                    .execute()
            } catch (error: Exception) {
                throw AssertionError("Direct server AJAX request failed: ${error.javaClass.simpleName}: ${error.message}. $reportPrefix")
            }
            val ajaxBody = ajaxResponse.body()
            fun jsonString(name: String): String? = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"([^\"]*)\"")
                .find(ajaxBody)?.groupValues?.getOrNull(1)
            directUrl = jsonString("server")?.takeIf(String::isNotBlank)
                ?.let { MySeedParser.resolvePlayerTarget(it, watchUrl) }
            assertTrue(
                "Direct server AJAX did not return a player URL (HTTP ${ajaxResponse.statusCode()}, message=${jsonString("message")}). $reportPrefix",
                ajaxResponse.statusCode() in 200..299 && jsonString("type") == "success" && !directUrl.isNullOrBlank()
            )
        }

        val playerResponse = try {
            session.newRequest(requireNotNull(directUrl))
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .referrer(watchUrl)
                .timeout(45_000)
                .ignoreHttpErrors(true)
                .followRedirects(false)
                .execute()
        } catch (error: Exception) {
            throw AssertionError("Direct player request failed: ${error.javaClass.simpleName}: ${error.message}. $reportPrefix")
        }
        val playerBase = playerResponse.url().toString()
        val redirect = playerResponse.header("Location")?.let { MySeedParser.resolvePlayerTarget(it, playerBase) }
        val directMediaRedirect = redirect?.takeIf { url ->
            val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
            Regex("""(?i)\.(?:m3u8|mpd|mp4|m4v|mkv|webm|mov|avi|ts)$""").containsMatchIn(path)
        }
        val gateway = if (directMediaRedirect != null) null else {
            redirect ?: MySeedParser.extractMySeedPlayerGateway(playerResponse.body(), playerBase)
        }
        var sources = directMediaRedirect?.let { listOf(MySeedDirectMediaSource(it, directChoice?.quality)) }
            ?: MySeedParser.parseMySeedDirectMediaSources(playerResponse.body(), playerBase)
        var sourceRefererBase = gateway ?: playerBase

        if (sources.isEmpty() && gateway != null) {
            val gatewayResponse = try {
                session.newRequest(gateway)
                    .userAgent(userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .referrer("$base/")
                    .timeout(45_000)
                    .ignoreHttpErrors(true)
                    .followRedirects(false)
                    .execute()
            } catch (error: Exception) {
                throw AssertionError("Direct player gateway request failed: ${error.javaClass.simpleName}: ${error.message}. $reportPrefix")
            }
            val gatewayBase = gatewayResponse.url().toString()
            val mediaRedirect = gatewayResponse.header("Location")?.let { MySeedParser.resolvePlayerTarget(it, gatewayBase) }
            sources = if (mediaRedirect != null) {
                listOf(MySeedDirectMediaSource(mediaRedirect, directChoice?.quality))
            } else {
                MySeedParser.parseMySeedDirectMediaSources(gatewayResponse.body(), gatewayBase)
            }
            sourceRefererBase = gateway
            assertTrue(
                "MySeed gateway returned HTTP ${gatewayResponse.statusCode()} without a media source. gateway=${MySeedParser.originOf(gateway)} body=${gatewayResponse.body().take(500)}. $reportPrefix",
                gatewayResponse.statusCode() in 200..299 || !mediaRedirect.isNullOrBlank()
            )
        }

        val playableSource = sources.firstOrNull { source ->
            Regex("(?i)\\.(?:m3u8|mpd|mp4|m4v|mkv|webm|mov|avi|ts)$")
                .containsMatchIn(runCatching { java.net.URI(source.url).path.orEmpty() }.getOrDefault(""))
        }
        assertTrue(
            "MySeed direct player did not expose a playable MP4/HLS/DASH source. status=${playerResponse.statusCode()} location=${playerResponse.header("Location")?.substringBefore('?')} body=${playerResponse.body().take(800)}. $reportPrefix",
            playableSource != null
        )

        val mediaOrigin = requireNotNull(MySeedParser.originOf(sourceRefererBase))
        val mediaUrl = requireNotNull(playableSource).url
        val probe = try {
            session.newRequest(mediaUrl)
                .userAgent(userAgent)
                .header("Referer", "$mediaOrigin/")
                .header("Origin", mediaOrigin)
                .apply {
                    if (!Regex("(?i)\\.(?:m3u8|mpd)$").containsMatchIn(mediaUrl.substringBefore('?'))) {
                        header("Range", "bytes=0-0")
                    }
                }
                .timeout(45_000)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .maxBodySize(64)
                .execute()
        } catch (error: Exception) {
            throw AssertionError("Direct media probe failed: ${error.javaClass.simpleName}: ${error.message}; url=${mediaUrl.substringBefore('?')}. $reportPrefix")
        }
        assertTrue(
            "MySeed direct media returned HTTP ${probe.statusCode()} content-type=${probe.contentType()} range=${probe.header("Content-Range")}; url=${mediaUrl.substringBefore('?')}. $reportPrefix",
            probe.statusCode() in 200..206
        )
    }

    @Test
    fun liveFilm7DogsWatchPageExposesAllFourProviderTargets() {
        val watchUrl = "$base/%d9%81%d9%8a%d9%84%d9%85-7-%d8%af%d9%88%d8%ac%d8%b2-2026/watch/"
        val response = try {
            Jsoup.connect(watchUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$base/")
                .timeout(45_000)
                .ignoreHttpErrors(true)
                .execute()
        } catch (error: Exception) {
            throw AssertionError("Live MySeed watch-page request failed: ${error.javaClass.simpleName}: ${error.message}")
        }
        val document = response.parse()
        val options = MySeedParser.parseWatchServers(document, watchUrl)
        val visibleProviders = document.select(".servers__list li[data-server]").map { it.text().trim() }.distinct()
        val recognizedTargets = options.filter { MySeedParser.isMySeedPlayerTarget(it.url) }
        val providerNodes = document.select(
            "button, a, [role=button], [onclick], [data-server], [data-url], [data-src], [data-file], " +
                "[data-source], [data-player], [data-quality], [data-link], [data-href], [class*=server], [id*=server]"
        ).filter { element ->
            element.text().contains("سيرفر") || listOf(
                "data-server", "data-url", "data-src", "data-file", "data-source", "data-player", "data-link", "data-href", "onclick"
            ).any(element::hasAttr)
        }.take(40).joinToString("\n") { element ->
            val attrs = listOf(
                "id", "class", "data-server", "data-name", "data-label", "data-url", "data-src", "data-file",
                "data-source", "data-player", "data-link", "data-href", "data-quality", "href", "src", "onclick"
            ).mapNotNull { key ->
                element.attr(key).takeIf(String::isNotBlank)?.let { "$key=${it.take(220)}" }
            }.joinToString(" ")
            "<${element.tagName()}> text=${element.text().take(100)} $attrs"
        }
        val playerScripts = document.select("script:not([src])").flatMap { script ->
            val source = script.data().ifBlank { script.html() }.replace(Regex("\\s+"), " ")
            listOf(".servers__list", "data-server", "data-link", "asPlayerFrame", "admin-ajax.php", "XMLHttpRequest", "fetch(")
                .mapNotNull { marker ->
                    val index = source.indexOf(marker, ignoreCase = true)
                    if (index < 0) null else {
                        val start = (index - 260).coerceAtLeast(0)
                        val end = (index + 680).coerceAtMost(source.length)
                        "$marker: ${source.substring(start, end)}"
                    }
                }
        }.take(14).joinToString("\\n")
        val parsed = options.joinToString("\n") { "${it.name} | ${it.quality}p | ${it.url}" }
        val report = """status=${response.statusCode()} title=${document.title()}
            |parsed providers (${options.size}):
            |$parsed
            |visible provider buttons: $visibleProviders
            |recognized first-party target URLs (${recognizedTargets.size}):
            |${recognizedTargets.joinToString("\\n") { it.url }}
            |provider DOM:
            |$providerNodes
            |player scripts:
            |$playerScripts""".trimMargin()

        assertTrue("Live MySeed returned a non-success status.\n$report", response.statusCode() in 200..299)
        assertTrue("Live watch page title did not match the selected film.\n$report", document.title().contains("7 دوجز", ignoreCase = true))
        assertEquals("Live page should expose its four selectable watch providers.\n$report", 4, visibleProviders.size)
        assertEquals("Live page should yield exactly four distinct valid provider targets.\n$report", 4, recognizedTargets.map { it.url }.distinct().size)
        assertTrue("Parser returned an invalid internal player route.\n$report", options.filter { MySeedParser.isInternalPlayerUrl(it.url) }.all { MySeedParser.isMySeedPlayerTarget(it.url) })
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
