package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

/**
 * AnimezidProvider
 * ----------------
 * Scrapes https://animezid.cam (Arabic anime / cartoon dubbed & subbed site).
 *
 * Site layout (egybest-by-mrbrooks template):
 *  - /                        : home page, several sections of .mbox each with .movies a.movie
 *  - /search.php?keywords=X   : search results, list of .movies a.movie (each is an episode/movie card)
 *  - /category.php?cat=anime  : top-level category, lists "series category" cards
 *                               (ribbon r1 = مسلسل / series with own category slug)
 *  - /category.php?cat=movies : top-level category for movies, cards link directly to /watch.php
 *  - /category.php?cat=<slug> : per-series page, all episodes as .movies a.movie cards
 *  - /watch.php?vid=XXX       : metadata + button to /play.php?vid=XXX
 *  - /play.php?vid=XXX        : player page, contains <ul id="xservers"> <button data-embed="..."> servers
 *
 * We use a sentinel prefix "SERIES::" in load() URL to mark links that are actually
 * series category pages (so we know to enumerate their episodes).
 */
class Animezid : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie,
        TvType.OVA
    )

    // ====================================================================
    // Main page categories (shown as sections in Cloudstream home UI)
    // ====================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/category.php?cat=anime&page="        to "الأنمي",
        "$mainUrl/category.php?cat=movies&page="       to "الأفلام",
        "$mainUrl/category.php?cat=series&page="       to "المسلسلات",
        "$mainUrl/category.php?cat=disney-masr&page="  to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon&page="    to "سبيستون",
        "$mainUrl/topvideos.php?page="                 to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url).document

        val items = doc.select("div#movies a.movie, div.movies a.movie")
            .mapNotNull { it.toSearchResponse() }

        // hasNext if there is a "next" page link or we got a full grid of items
        val hasNext = doc.select("a.pagination_next, a[rel=next], div.pagination a")
            .any { it.attr("href").contains("page=${page + 1}") } || items.size >= 20

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    // ====================================================================
    // Search
    // ====================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${query.encode()}"
        val doc = app.get(url).document
        return doc.select("div#movies a.movie, div.movies a.movie")
            .mapNotNull { it.toSearchResponse() }
    }

    // ====================================================================
    // Convert an <a class="movie"> card to a SearchResponse
    //
    // Two link patterns are possible:
    //   - /watch.php?vid=XXX   -> single episode / movie (terminal)
    //   - /category.php?cat=Y  -> a series (we want to LOAD its episode list)
    // We mark series ones with "SERIES::" prefix so load() knows.
    // ====================================================================
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href").ifBlank { return null }
        val absHref = fixUrl(href)
        val title = this.selectFirst("span.title")?.text()?.trim()
            ?: this.attr("title").trim().ifBlank { return null }

        val img = this.selectFirst("img")
        val poster = img?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-original") }
                .ifBlank { it.attr("src") }
        }?.let { fixUrl(it) }

        val ribbon = this.selectFirst("span.ribbon")?.text()?.trim()?.lowercase() ?: ""
        val isSeriesLink = absHref.contains("/category.php?cat=", ignoreCase = true)

        // Heuristic for type
        val tvType = when {
            ribbon.contains("مسلسل") || isSeriesLink -> TvType.Anime
            title.contains("فيلم")                    -> TvType.Movie
            else                                      -> TvType.Anime
        }

        // For series links we mark them so load() will enumerate episodes
        val loadUrl = if (isSeriesLink) "SERIES::$absHref" else absHref

        return newAnimeSearchResponse(title, loadUrl, tvType) {
            this.posterUrl = poster
        }
    }

    // ====================================================================
    // Load detail
    //
    // Two cases:
    //   1. url starts with "SERIES::"  -> series category page: enumerate all episode cards
    //   2. url contains "/watch.php?"  -> a single episode/movie watch page (treated as Movie)
    //   3. url contains "/category.php?cat=" without prefix (e.g. came from history)
    //      -> probe it: if it has many episode cards, treat as series; else fallback to single
    // ====================================================================
    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.startsWith("SERIES::") ||
                (url.contains("/category.php?cat=") && !url.contains("cat=movies") &&
                        !url.contains("cat=series") && !url.contains("cat=anime") &&
                        !url.contains("cat=disney-masr") && !url.contains("cat=spacetoon"))

        val cleanUrl = url.removePrefix("SERIES::")

        return if (isSeries) {
            loadSeries(cleanUrl)
        } else {
            loadSingle(cleanUrl)
        }
    }

    // ---- Series (multi-episode) loader --------------------------------------
    private suspend fun loadSeries(url: String): LoadResponse {
        val doc = app.get(url).document

        val seriesTitle = doc.selectFirst("div.nav .rs_scroll a.active")?.text()?.trim()
            ?: doc.selectFirst("h1, .movie_title h1")?.text()?.trim()
            ?: "Animezid Series"

        val firstCard = doc.selectFirst("div#movies a.movie, div.movies a.movie")
        val poster = firstCard?.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.let { fixUrl(it) }

        // Collect all episodes across pagination
        val allEpisodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        var page = 1
        var currentDoc: Document? = doc

        while (currentDoc != null && page <= 100) {
            val episodeCards = currentDoc.select("div#movies a.movie, div.movies a.movie")
            if (episodeCards.isEmpty()) break

            var addedAny = false
            episodeCards.forEach { card ->
                val href = card.attr("href").let { fixUrl(it) }
                if (!href.contains("/watch.php")) return@forEach
                if (!seen.add(href)) return@forEach
                addedAny = true

                val name = card.selectFirst("span.title")?.text()?.trim()
                    ?: card.attr("title").trim()
                val epPoster = card.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }?.let { fixUrl(it) }

                // Extract episode number from text like "الحلقة 31"
                val epNum = Regex("الحلقة\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").findAll(name).lastOrNull()?.value?.toIntOrNull()

                allEpisodes.add(
                    newEpisode(href) {
                        this.name = name
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }
                )
            }

            if (!addedAny) break

            // Look for "next page" link
            val nextHref = currentDoc.select("a").firstOrNull { a ->
                val t = a.text().trim()
                t == "التالي" || t == "»" || t.contains("Next", ignoreCase = true)
            }?.attr("href")?.let { if (it.isNotBlank()) fixUrl(it) else null }

            currentDoc = if (!nextHref.isNullOrBlank()) {
                page += 1
                try { app.get(nextHref).document } catch (_: Exception) { null }
            } else null
        }

        // sort episodes ascending by number (site shows newest first)
        val sortedEpisodes = allEpisodes.sortedBy { it.episode ?: Int.MAX_VALUE }

        // If there are no episodes at all, fallback to treating like a single movie
        if (sortedEpisodes.isEmpty()) {
            return newMovieLoadResponse(seriesTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }

        return newAnimeLoadResponse(seriesTitle, url, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Dubbed, sortedEpisodes)
        }
    }

    // ---- Single watch / movie loader ----------------------------------------
    private suspend fun loadSingle(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1 span[itemprop=name]")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Animezid"

        val poster = doc.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.movie_img img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { fixUrl(it) }

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("div.story, div.plot, p.story")?.text()

        val year = doc.selectFirst("a[href*=filter=years]")?.text()?.trim()?.toIntOrNull()
        val rating = doc.selectFirst("i.i-fav.rating strong, .rating strong")?.text()?.toRatingInt()

        val tags = doc.select("a[href*=filter=genres], a[href*=filter=translate], a[href*=filter=countries]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val isMovie = title.contains("فيلم") || tags.any { it.contains("Movie", true) }
        val tvType = if (isMovie) TvType.Movie else TvType.Anime

        // data passed to loadLinks: always the canonical watch.php url
        val watchUrl = url

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster?.toString()
                this.plot = plot
                this.year = year
                this.rating = rating
                this.tags = tags
            }
        } else {
            // single episode loaded directly: wrap into a 1-episode anime
            val episode = newEpisode(watchUrl) {
                this.name = title
                this.episode = Regex("الحلقة\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            }
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster?.toString()
                this.plot = plot
                this.year = year
                this.rating = rating
                this.tags = tags
                addEpisodes(DubStatus.Dubbed, listOf(episode))
            }
        }
    }

    // ====================================================================
    // loadLinks: open /play.php?vid=XXX and extract server iframes
    // ====================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Derive play.php url from the given watch.php url
        val playUrl = when {
            data.contains("/play.php")  -> data
            data.contains("/watch.php") -> data.replace("/watch.php", "/play.php")
            data.contains("vid=")       -> {
                val vid = Regex("vid=([A-Za-z0-9]+)").find(data)?.groupValues?.get(1)
                if (vid != null) "$mainUrl/play.php?vid=$vid" else data
            }
            else -> data
        }

        val doc = app.get(playUrl, referer = mainUrl).document

        // primary location: <ul id="xservers"> <button data-embed="...">
        val servers = doc.select("ul#xservers button[data-embed], #xservers button[data-embed]")
            .mapNotNull { it.attr("data-embed").trim().ifBlank { null } }
            .toMutableList()

        // fallback: any iframe on the page
        if (servers.isEmpty()) {
            doc.select("iframe[src]").forEach { iframe ->
                iframe.attr("src").trim().takeIf { it.isNotBlank() }?.let { servers.add(it) }
            }
        }

        val seen = mutableSetOf<String>()
        var anyAdded = false
        servers.forEach { raw ->
            val link = if (raw.startsWith("//")) "https:$raw" else raw
            if (link.isBlank() || !seen.add(link)) return@forEach
            try {
                loadExtractor(link, playUrl, subtitleCallback, callback)
                anyAdded = true
            } catch (_: Exception) {
                // ignore single-server failures
            }
        }

        return anyAdded
    }

    // ====================================================================
    // Helpers
    // ====================================================================
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http") -> url
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> mainUrl + url
            else                   -> "$mainUrl/$url"
        }
    }

    private fun String.encode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private fun String.toRatingInt(): Int? {
        return try {
            (this.trim().replace(",", ".").toDoubleOrNull()?.times(10))?.toInt()
        } catch (_: Exception) { null }
    }
}
