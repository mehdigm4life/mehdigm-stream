package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element

class FaselHD : MainAPI() {

    //region Provider metadata
    override var lang = "ar"
    override var mainUrl = "https://www.fasel-hd.cam"
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )
    //endregion

    private val cfKiller = CloudflareKiller()

    //region Helpers
    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst("div.postDiv a") ?: return null
        val url = anchor.attr("href") ?: return null
        val img = anchor.selectFirst("div.imgdiv-class img") ?: anchor.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
        val rawTitle = img?.attr("alt") ?: anchor.text()

        val quality = selectFirst(".quality")?.text()
            ?.replace("1080p | -".toRegex(), "")
            ?.trim()

        val type = when {
            url.contains("/movies/") || url.contains("/hindi/") || url.contains("/dubbed-movies/") || url.contains("/asian-movies/") || url.contains("/anime-movies/") -> TvType.Movie
            url.contains("/series/") || url.contains("/episodes/") || url.contains("/asian-series/") || url.contains("/asian_seasons/") || url.contains("/asian-episodes/") -> TvType.TvSeries
            url.contains("/anime/") || url.contains("/anime-episodes/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        val cleanTitle = rawTitle
            .replace("مسلسل|فيلم|انمي|أنمي|برنامج|مترجم|مدبلج|اون لاين|مشاهدة|تحميل".toRegex(), "")
            .trim()

        return newMovieSearchResponse(cleanTitle, url, type) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = cfKiller.getCookieHeaders(mainUrl).toMap()
        }
    }

    private suspend fun fetchDocument(url: String): Element {
        var doc = app.get(url, interceptor = cfKiller).document
        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = cfKiller, timeout = 120).document
        }
        return doc
    }
    //endregion

    //region Main Page
    override val mainPage = mainPageOf(
        "$mainUrl/all-movies" to "جميع الأفلام",
        "$mainUrl/movies" to "أفلام أجنبي",
        "$mainUrl/movies_top_views" to "الأفلام الأكثر مشاهدة",
        "$mainUrl/movies_top_imdb" to "الأفلام الأعلى تقييماً IMDB",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندي",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/anime-movies" to "أفلام أنمي",
        "$mainUrl/series" to "مسلسلات أجنبية",
        "$mainUrl/recent_series" to "المضاف حديثاً",
        "$mainUrl/anime" to "أنمي",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/tvshows" to "برامج تلفزيونية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.endsWith("/")) {
            "${request.data}page/$page"
        } else {
            "$request.data/page/$page"
        }
        val doc = fetchDocument(url)
        val list = doc.select("div#postList div.postDiv")
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, list)
    }
    //endregion

    //region Search
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val doc = fetchDocument("$mainUrl/?s=$q")
        return doc.select("div#postList div.postDiv")
            .mapNotNull { it.toSearchResponse() }
    }
    //endregion

    //region Load
    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url)
        val title = doc.select("title").text()
            .replace(" - فاصل إعلاني", "")
            .replace("مسلسل|فيلم|انمي|أنمي|برنامج|مترجم|مدبلج|اون لاين|مشاهدة|تحميل".toRegex(), "")
            .trim()

        val posterUrl = doc.selectFirst("div.posterImg img")?.attr("src")
            ?.ifBlank { doc.selectFirst("div.seasonDiv.active img")?.attr("data-src") }
            ?: doc.selectFirst("div.posterImg img")?.attr("data-src")
            ?: ""

        val year = doc.select("div#singleList div.col-xl-6, div#singleList div.col-lg-6").firstOrNull {
            it.text().contains("سنة|موعد|Released|تاريخ الصدور".toRegex())
        }?.text()?.getIntFromText()

        val duration = doc.select("div#singleList div.col-xl-6, div#singleList div.col-lg-6").firstOrNull {
            it.text().contains("مدة|توقيت|Duration".toRegex())
        }?.text()?.getIntFromText()

        val tags = doc.select("div#singleList div.col-xl-6 a, div#singleList div.col-lg-6 a")
            .map { it.text() }
            .filter { it.isNotBlank() }

        val synopsis = doc.selectFirst("div.singleDesc p")?.text()
            ?: doc.selectFirst("div.singleDesc")?.text()
            ?: ""

        val recommendations = doc.select("div#postList div.postDiv")
            .mapNotNull { it.toSearchResponse() }

        val ratingText = doc.selectFirst("span.pImdb")?.text()
            ?: doc.selectFirst("div.singleImdb")?.text()
        val score = Score.from10(ratingText)

        // Determine if it's a movie: no episode list and no season list
        val hasEpisodes = doc.select(".epAll a").isNotEmpty()
        val hasSeasons = doc.select("#seasonList .seasonDiv").isNotEmpty()

        return if (!hasEpisodes && !hasSeasons) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(mainUrl).toMap()
                this.score = score
            }
        } else {
            val episodes = ArrayList<Episode>()

            // Current season episodes
            val currentSeasonNumber = doc.selectFirst("div.seasonDiv.active div.title")?.text()
                ?.getIntFromText() ?: 1
            doc.select(".epAll a").map { ep ->
                episodes.add(
                    newEpisode(ep.attr("href")) {
                        this.name = ep.text().trim()
                        this.season = currentSeasonNumber
                        this.episode = ep.text().getIntFromText()
                    }
                )
            }

            // Other seasons
            doc.select("div#seasonList div.seasonDiv").not(".active").amap { season ->
                val onclick = season.attr("onclick")
                val seasonId = Regex("""\\?p=(\\d+)'""").find(onclick)?.groupValues?.get(1)
                if (seasonId != null) {
                    val seasonDoc = fetchDocument("$mainUrl/?p=$seasonId")
                    val seasonNumber = seasonDoc.selectFirst("div.seasonDiv.active div.title")?.text()
                        ?.getIntFromText() ?: currentSeasonNumber
                    seasonDoc.select(".epAll a").map { ep ->
                        episodes.add(
                            newEpisode(ep.attr("href")) {
                                this.name = ep.text().trim()
                                this.season = seasonNumber
                                this.episode = ep.text().getIntFromText()
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinct().sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(mainUrl).toMap()
                this.score = score
            }
        }
    }
    //endregion

    //region Load Links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchDocument(data)

        // 1) Try the download server first (direct MP4/MKV link)
        val downloadHref = doc.selectFirst(".downloadLinks a")?.attr("href")
        if (!downloadHref.isNullOrBlank()) {
            try {
                val playerDoc = app.post(
                    downloadHref,
                    interceptor = cfKiller,
                    referer = mainUrl,
                    timeout = 120
                ).document
                val directLink = playerDoc.selectFirst("div.dl-link a")?.attr("href")
                if (!directLink.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - Download Server",
                            url = directLink,
                            type = null
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (_: Exception) {
                // Ignore download errors, fallback to streaming
            }
        }

        // 2) HLS streaming via JWPlayer iframe
        val iframe = doc.selectFirst("iframe[name=\"player_iframe\"]")
        val iframeSrc = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") }

        if (!iframeSrc.isNullOrBlank()) {
            try {
                val webView = WebViewResolver(
                    Regex("""master\.m3u8""")
                ).resolveUsingWebView(
                    requestCreator(
                        method = "GET",
                        url = iframeSrc,
                        referer = mainUrl
                    )
                ).first

                val streamUrl = webView?.url?.toString()
                if (!streamUrl.isNullOrBlank() && streamUrl.contains("master.m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = streamUrl,
                        referer = mainUrl
                    ).forEach(callback)
                }
            } catch (_: Exception) {
                // WebView failed
            }
        }

        return true
    }
    //endregion
}
