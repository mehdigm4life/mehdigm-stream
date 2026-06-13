package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FaselHD : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://web61312x.faselhdx.bid"
    override var name = "FaselHD"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val cfKiller = CloudflareKiller()
    private var _baseUrl: String? = null

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1"
    )

    private suspend fun baseUrl(): String {
        _baseUrl?.let { return it }
        return try {
            val resp = app.get(mainUrl, allowRedirects = true)
            val finalUrl = resp.url
            val uri = java.net.URI(finalUrl)
            "${uri.scheme}://${uri.host}".also { _baseUrl = it }
        } catch (_: Exception) {
            mainUrl.also { _baseUrl = it }
        }
    }

    private suspend fun getPage(url: String, referer: String? = null): Document {
        val base = baseUrl()
        val cleanUrl = if (url.startsWith("http")) url else "${base}${if (url.startsWith("/")) "" else "/"}$url"
        var response = app.get(
            cleanUrl,
            headers = baseHeaders,
            referer = referer ?: base,
            timeout = 120
        )
        val doc = response.document
        val title = doc.select("title").text()
        val blocked = response.code == 403 || response.code == 503 ||
                title.contains("Just a moment", ignoreCase = true) ||
                title.contains("Attention Required", ignoreCase = true) ||
                doc.select("body").text().contains("cf-browser-verification")

        if (blocked) {
            response = app.get(
                cleanUrl,
                headers = baseHeaders,
                referer = referer ?: base,
                interceptor = cfKiller,
                timeout = 120
            )
        }
        return response.document
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

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val url = anchor.attr("href").fixUrl()
        if (url.isBlank() || url == "#" || !url.startsWith("http")) return null

        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        if (title.isBlank()) return null

        val img = selectFirst("img") ?: anchor.selectFirst("img")
        val posterUrl = img?.let { el ->
            listOf("data-src", "src", "data-lazy-src")
                .map { el.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        }

        val type = when {
            url.contains("/series/") || url.contains("/seasons/") || title.contains("مسلسل") -> TvType.TvSeries
            url.contains("/anime/") || title.contains("انمي") || title.contains("أنمي") -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun Document.extractItems(): List<SearchResponse> {
        val selectors = listOf(
            "div.postDiv",
            "div.blockMovie",
            "div.MovieBlock",
            "div.BlockItem",
            "article"
        )
        for (selector in selectors) {
            val items = select(selector).mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) return items.distinctBy { it.url }
        }
        return select("a:has(img)").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    override val mainPage = mainPageOf(
        "/main" to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getPage(request.data)
        val lists = mutableListOf<HomePageList>()

        val slider = doc.select("#homeSlide .swiper-slide").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst(".h1 a")?.text()?.trim() ?: return@mapNotNull null
            val img = it.selectFirst(".poster img")
            val poster = img?.let { el ->
                listOf("data-src", "src").map { a -> el.attr(a) }
                    .firstOrNull { v -> v.isNotBlank() && !v.startsWith("data:") }
            }
            newMovieSearchResponse(title, a.attr("href").fixUrl(), TvType.Movie) {
                this.posterUrl = poster
            }
        }
        if (slider.isNotEmpty()) {
            lists.add(HomePageList("أحدث الإضافات", slider, isHorizontalImages = true))
        }

        doc.select("section#blockList").forEach { block ->
            val title = block.selectFirst(".blockHead .h3")?.text()?.trim() ?: return@forEach
            val items = block.select(".blockMovie, .postDiv, .epDivHome").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                lists.add(HomePageList(title, items))
            }
        }

        doc.select("div.slider").firstOrNull { it.selectFirst(".h4")?.text()?.contains("مشاهدة") == true }
            ?.let { mostWatched ->
                val title = mostWatched.selectFirst(".h4")?.text()?.trim() ?: "الأكثر مشاهدة"
                val items = mostWatched.select(".itemviews .postDiv").mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items, isHorizontalImages = true))
                }
            }

        return newHomePageResponse(lists.filter { it.list.isNotEmpty() }, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = arrayListOf<SearchResponse>()

        runCatching {
            results.addAll(getPage("/search/$encoded").extractItems())
        }

        if (results.isEmpty()) runCatching {
            results.addAll(getPage("/?s=$encoded").extractItems())
        }

        return results.distinctBy { it.url }
    }

    private fun extractSeasonNumFromUrl(url: String): Int? {
        val arabicNumbers = mapOf(
            "الأول" to 1, "الاول" to 1,
            "الثاني" to 2, "التاني" to 2,
            "الثالث" to 3, "التالت" to 3,
            "الرابع" to 4,
            "الخامس" to 5,
            "السادس" to 6,
            "السابع" to 7,
            "الثامن" to 8,
            "التاسع" to 9,
            "العاشر" to 10
        )
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        for ((name, num) in arabicNumbers) {
            if (decoded.contains(name)) return num
        }
        return Regex("""الموسم[^\d]*?(\d+)""").find(decoded)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url)

        val title = (
            doc.selectFirst("h1.Title, h1.title, .singleInfo .title.h1")?.ownText()
                ?: doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            ).trim()

        val poster = (
            doc.selectFirst(".posterImg img.poster, .poster img, .poster_single img")?.let { img ->
                listOf("data-src", "src").map { img.attr(it) }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            )?.fixUrl()

        val synopsis = (
            doc.selectFirst(".singleDesc p, .story p, .desc p")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            )?.trim()

        val tags = doc.select(".singleInfo a[href*='/series_genres/'], .singleInfo a[href*='/movies-cats/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year = doc.selectFirst(".singleInfo a[href*='/movies_years/']")?.text()
            ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

        val score = doc.selectFirst(".singleInfo .imdb span, .singleInfo .rating")?.text()
            ?.let { Regex("""[\d.]+""").find(it)?.value?.toFloatOrNull() }

        val episodes = mutableListOf<Episode>()

        val seasonDivs = doc.select(".seasonDiv")
        if (seasonDivs.isNotEmpty()) {
            val seasonUrlRegex = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
            seasonDivs.forEachIndexed { idx, seasonDiv ->
                val onclick = seasonDiv.attr("onclick")
                val seasonUrlRaw = seasonUrlRegex.find(onclick)?.groupValues?.get(1) ?: return@forEachIndexed
                val seasonUrl = seasonUrlRaw.fixUrl()
                val seasonName = seasonDiv.selectFirst(".title")?.text()?.trim() ?: "الموسم ${idx + 1}"
                val seasonNum = extractSeasonNumFromUrl(seasonUrl) ?: (idx + 1)

                val seasonDoc = if (seasonUrl == url) doc else runCatching { getPage(seasonUrl) }.getOrNull()
                    ?: return@forEachIndexed

                seasonDoc.select("#epAll a").forEach { ep ->
                    val epUrl = ep.attr("href").fixUrl()
                    if (epUrl.isBlank() || !epUrl.startsWith("http")) return@forEach
                    val epText = ep.ownText().ifBlank { ep.text() }.trim()
                    if (epText.contains("باقي الحلقات") || epText.contains("المزيد")) return@forEach
                    val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                    episodes.add(newEpisode(epUrl) {
                        this.name = "$seasonName - $epText"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            doc.select("#epAll a").forEach { ep ->
                val epUrl = ep.attr("href").fixUrl()
                if (epUrl.isBlank() || !epUrl.startsWith("http")) return@forEach
                val epText = ep.ownText().ifBlank { ep.text() }.trim()
                if (epText.contains("باقي الحلقات") || epText.contains("المزيد")) return@forEach
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                val seasonNum = extractSeasonNumFromUrl(url) ?: 1
                episodes.add(newEpisode(epUrl) {
                    this.name = epText
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        }

        val isSeries = episodes.isNotEmpty() || url.contains("/series/") || url.contains("/seasons/")

        if (isSeries) {
            val sortedEpisodes = episodes.distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
                this.year = year
                this.score = score
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
                this.year = year
                this.score = score
            }
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

        doc.select("iframe").amap { frame ->
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }
            if (src.isNotBlank()) {
                val url = src.fixUrl()
                if (loadExtractor(url, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            }
        }

        doc.select("[onclick]").amap { el ->
            val onclick = el.attr("onclick")
            val match = Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""").find(onclick)
            match?.groupValues?.get(1)?.let { iframeUrl ->
                val url = iframeUrl.fixUrl()
                if (loadExtractor(url, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            }
        }

        doc.select("a[href*='t7meel'], a.download__item, .downloads__links__list a").amap { anchor ->
            val link = anchor.attr("href").fixUrl()
            if (link.isNotBlank() && link.startsWith("http")) {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
