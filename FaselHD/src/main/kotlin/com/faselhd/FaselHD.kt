package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FaselHD : MainAPI() {
    override var lang = "ar"
    override var name = "FaselHD"
    override var mainUrl = "https://www.fasel-hd.cam"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val cfKiller = CloudflareKiller()
    private var _baseUrl: String? = null

    private val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val arabicSeasonMap = mapOf(
        "الاول" to 1, "الأول" to 1,
        "التاني" to 2, "الثاني" to 2, "الثانى" to 2,
        "التالت" to 3, "الثالث" to 3,
        "الرابع" to 4, "الخامس" to 5,
        "السادس" to 6, "السابع" to 7,
        "الثامن" to 8, "التاسع" to 9,
        "العاشر" to 10
    )

    private suspend fun baseUrl(): String {
        _baseUrl?.let { return it }
        return try {
            val resp = app.get(mainUrl, allowRedirects = true, timeout = 15)
            val uri = java.net.URI(resp.url)
            "${uri.scheme}://${uri.host}".also { _baseUrl = it }
        } catch (_: Exception) {
            mainUrl.also { _baseUrl = it }
        }
    }

    private fun String.fixUrl(): String {
        if (isBlank()) return this
        val base = _baseUrl ?: mainUrl
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$base$this"
            else -> "$base/$this"
        }
    }

    private suspend fun getPage(url: String, referer: String? = null): Document {
        val base = baseUrl()
        val cleanUrl = url.fixUrl()
        var response = app.get(cleanUrl, headers = baseHeaders, referer = referer ?: base, timeout = 120)
        val doc = response.document
        val title = doc.select("title").text()
        if (response.code == 403 || response.code == 503 ||
            title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true) ||
            doc.select("body").text().contains("cf-browser-verification")
        ) {
            response = app.get(cleanUrl, headers = baseHeaders, referer = referer ?: base, interceptor = cfKiller, timeout = 120)
        }
        return response.document
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val url = anchor.attr("href").fixUrl()
        if (url.isBlank() || url == "#" || !url.startsWith("http")) return null

        val title = anchor.attr("title").ifBlank {
            selectFirst(".postInner .h1, .h1, .title")?.text()?.trim()
                ?: anchor.text().trim()
        }
        if (title.isBlank()) return null

        val img = selectFirst("img") ?: anchor.selectFirst("img")
        val posterUrl = img?.let { el ->
            listOf("data-src", "data-lazy-src", "src")
                .map { el.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        }

        val type = when {
            url.contains("/seasons/") || url.contains("/series/") || title.contains("مسلسل") -> TvType.TvSeries
            url.contains("/anime/") || title.contains("انمي") || title.contains("أنمي") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun Document.extractItems(): List<SearchResponse> {
        for (selector in listOf("div.postDiv", "div.blockMovie", "div.epDivHome", "div.MovieBlock")) {
            val items = select(selector).mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) return items.distinctBy { it.url }
        }
        return select("a:has(img)").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    override val mainPage = mainPageOf(
        "/main" to "الرئيسية",
        "/movies" to "أفلام أجنبية",
        "/series" to "مسلسلات",
        "/tvshows" to "برامج تلفزيونية",
        "/anime" to "أنمي",
        "/asian-series" to "مسلسلات آسيوية",
        "/most_recent" to "المضاف حديثاً",
        "/episodes" to "أحدث الحلقات",
        "/dubbed-movies" to "أفلام مدبلجة",
        "/hindi" to "أفلام هندية",
        "/anime-movies" to "أفلام أنمي",
        "/asian-movies" to "أفلام آسيوية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}/page/$page" else request.data
        val doc = getPage(pageUrl)

        if (request.data == "/main") {
            val lists = mutableListOf<HomePageList>()

            val slider = doc.select("#homeSlide .swiper-slide").mapNotNull { slide ->
                val a = slide.selectFirst("a") ?: return@mapNotNull null
                val title = slide.select(".h1 a, .slideContent .h1 a, .post--content--inner .h1 a").text().trim()
                    .ifBlank { slide.selectFirst("img")?.attr("alt")?.trim() ?: return@mapNotNull null }
                val img = slide.selectFirst(".poster img, img")
                val poster = img?.let { el ->
                    listOf("data-src", "src").map { el.attr(it) }.firstOrNull { it.isNotBlank() }
                }
                newMovieSearchResponse(title, a.attr("href").fixUrl(), TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            if (slider.isNotEmpty()) {
                lists.add(HomePageList("أحدث الإضافات", slider, isHorizontalImages = true))
            }

            doc.select("section, div[id^=blockList]").forEach { block ->
                val title = block.selectFirst(".blockHead .h3")?.text()?.trim()
                    ?: block.selectFirst(".blockHead")?.text()?.trim()
                    ?: return@forEach
                val items = block.select(".blockMovie, .postDiv, .epDivHome").mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items, isHorizontalImages = block.select(".blockMovie").size > 2))
                }
            }

            return newHomePageResponse(lists.filter { it.list.isNotEmpty() }, hasNext = false)
        }

        val items = doc.extractItems()
        val hasNext = doc.select("a.page-numbers:not(.current)").any {
            Regex("/page/${page + 1}").containsMatchIn(it.attr("href"))
        } || doc.select("a.next, a:contains(»)").any { it.attr("href").isNotBlank() && it.attr("href") != "#" }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = arrayListOf<SearchResponse>()
        runCatching { results.addAll(getPage("/?s=$encoded").extractItems()) }
        if (results.isEmpty()) runCatching { results.addAll(getPage("/search/$encoded").extractItems()) }
        return results.distinctBy { it.url }
    }

    private fun extractSeasonNum(text: String): Int? {
        val clean = java.net.URLDecoder.decode(text, "UTF-8")
        for ((name, num) in arabicSeasonMap) {
            if (clean.contains(name)) return num
        }
        return Regex("""(?:الموسم|season|s)\s*[-_:]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url)

        val title = (
            doc.selectFirst(".singleInfo .h1, .postInner .h1")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            ).trim()

        val poster = (
            doc.selectFirst(".posterImg img, .imgdiv-class img")?.let { img ->
                listOf("data-src", "src").map { img.attr(it) }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            )?.fixUrl()

        val synopsis = (
            doc.selectFirst(".singleDesc, .story p")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            )?.trim()

        val tags = doc.select("a[href*=movies_cats], a[href*=series_genres]")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val year = doc.selectFirst("a[href*=movies_years]")?.text()
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

        val score = doc.selectFirst(".pImdb, .imdb")?.text()
            ?.let { Regex("""[\d.]+""").find(it)?.value?.toFloatOrNull() }

        val episodes = mutableListOf<Episode>()
        val epContainer = doc.selectFirst("#epAll")

        if (epContainer != null) {
            val seasonNum = extractSeasonNum(url) ?: 1
            epContainer.select("a[href]").forEach { ep ->
                val epUrl = ep.attr("href").fixUrl()
                if (epUrl.isBlank() || !epUrl.startsWith("http")) return@forEach
                val epText = ep.text().trim()
                if (epText.contains("باقي") || epText.contains("المزيد")) return@forEach
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                newEpisode(epUrl)?.let {
                    it.name = epText
                    it.season = seasonNum
                    it.episode = epNum
                    episodes.add(it)
                }
            }
        }

        val isSeries = episodes.isNotEmpty() || url.contains("/seasons/") || url.contains("/series/") || url.contains("/episodes/")

        if (isSeries) {
            val sorted = episodes.distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
                this.year = year
                this.score = Score.from10(score)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
            this.year = year
            this.score = Score.from10(score)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getPage(data)
        var foundAny = false
        val seen = mutableSetOf<String>()

        doc.select("iframe").amap { frame ->
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }
            if (src.isNotBlank() && src.contains("video_player")) {
                val playerUrl = src.fixUrl()
                val urls = extractHlsFromPlayer(playerUrl)
                for ((i, hlsUrl) in urls.withIndex()) {
                    val quality = when {
                        hlsUrl.contains("_hd1080") || hlsUrl.contains("master") -> Qualities.P1080.value
                        hlsUrl.contains("_hd720") -> Qualities.P720.value
                        hlsUrl.contains("_sd360") -> Qualities.P360.value
                        else -> Qualities.P1080.value
                    }
                    callback(
                        newExtractorLink("FaselHD", "FaselHD ${if (i == 0) "HD" else "SD $i"}", hlsUrl) {
                            this.referer = playerUrl
                            this.quality = quality
                        }
                    )
                    foundAny = true
                }
            } else if (src.isNotBlank()) {
                val url = src.fixUrl()
                foundAny = loadExtractor(url, data, subtitleCallback, callback) || foundAny
            }
        }

        doc.select("[onclick]").amap { el ->
            val onclick = el.attr("onclick")
            Regex("""(?:player_iframe\.location\.href|location\.href)\s*=\s*['"]([^'"]+)['"]""")
                .find(onclick)?.groupValues?.get(1)?.let { iframeUrl ->
                    val playerUrl = iframeUrl.fixUrl()
                    if (playerUrl.contains("video_player")) {
                        val urls = extractHlsFromPlayer(playerUrl)
                        for ((i, hlsUrl) in urls.withIndex()) {
                            val quality = when {
                                hlsUrl.contains("master") || hlsUrl.contains("_hd1080") -> Qualities.P1080.value
                                hlsUrl.contains("_hd720") -> Qualities.P720.value
                                hlsUrl.contains("_sd360") -> Qualities.P360.value
                                else -> Qualities.P1080.value
                            }
                            callback(
                                newExtractorLink("FaselHD", "FaselHD ${if (i == 0) "HD" else "SD $i"}", hlsUrl) {
                                    this.referer = playerUrl
                                    this.quality = quality
                                }
                            )
                            foundAny = true
                        }
                    } else {
                        foundAny = loadExtractor(playerUrl, data, subtitleCallback, callback) || foundAny
                    }
                }
        }

        doc.select("a[href*=t7meel], a[href*=/series_quality/], a.download__item, .downloads__links__list a").amap { anchor ->
            val link = anchor.attr("href").fixUrl()
            if (link.isNotBlank() && link.startsWith("http") && seen.add(link)) {
                foundAny = loadExtractor(link, data, subtitleCallback, callback) || foundAny
            }
        }

        return foundAny
    }

    @OptIn(Prerelease::class)
    private suspend fun extractHlsFromPlayer(playerUrl: String): List<String> {
        return try {
            val response = app.get(playerUrl, headers = baseHeaders, referer = baseUrl(), timeout = 60)
            val doc = Jsoup.parse(response.text)

            val obfuscatedScript = doc.select("script:not([src])").mapNotNull { it.data().takeIf { d -> d.isNotBlank() } }
                .firstOrNull { it.contains("function(_0x") && it.contains("while(!![])") }
                ?: return emptyList()

            val safeUrl = playerUrl.replace("'", "\\'").replace("\\", "\\\\").replace("\n", "\\n")

            val wrapper = """
var document = {
    _output: '',
    write: function(html) { this._output += html; },
    writeln: function(html) { this._output += html + '\n'; },
    getElementById: function() { return null; },
    querySelector: function() { return null; },
    querySelectorAll: function() { return []; },
    createElement: function() { return { innerHTML: '', setAttribute: function() {}, addEventListener: function() {}, className: '', style: {} }; },
    createTextNode: function() { return {}; },
    body: { appendChild: function() {}, insertAdjacentHTML: function() {}, innerHTML: '' },
    documentElement: { outerHTML: '' },
    cookie: ''
};
var window = this;
var location = { href: '$safeUrl', host: 'www.fasel-hd.cam', protocol: 'https:' };
var navigator = { userAgent: 'Mozilla/5.0', platform: 'Win32', language: 'en-US' };
var setTimeout = function(fn) { try { if (typeof fn === 'function') fn(); } catch(e) {} };
var setInterval = function() { return 0; };
var clearInterval = function() {};
var clearTimeout = function() {};
var console = { log: function() {}, warn: function() {}, error: function() {} };
var screen = { width: 1920, height: 1080 };
try {
    $obfuscatedScript
} catch(e) {}
document._output;
""".trimIndent().replace("$obfuscatedScript", obfuscatedScript)

            val rhino = getRhinoContext()
            val scope = rhino.initSafeStandardObjects()
            val result = rhino.evaluateString(scope, wrapper, "player.js", 1, null)
            val output = result?.toString() ?: return emptyList()

            Regex("""https://[^\s"'<>]+\.m3u8""").findAll(output)
                .map { it.value }
                .distinct()
                .toList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }
}
