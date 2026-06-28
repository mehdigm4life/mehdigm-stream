package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimezidProvider : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Movie,
        TvType.TvSeries,
        TvType.OVA,
    )

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /** Removes typical Arabic noise words so we can detect a clean series title. */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("الحلقة\\s*\\d+.*"), "")
            .replace(Regex("الموسم\\s*\\S+"), "")
            .replace("مدبلجة", "")
            .replace("مترجمة", "")
            .replace("مدبلج", "")
            .replace("مترجم", "")
            .replace("انمي", "")
            .replace("مسلسل", "")
            .replace("فيلم", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Detects whether the given title looks like a single movie (no episode marker). */
    private fun isMovie(title: String): Boolean {
        return title.contains("فيلم") && !title.contains("الحلقة")
    }

    /** Gets the poster image url out of a video card / page, tolerant to lazy-loading. */
    private fun Element.posterUrl(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("data-original") }
            .ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    /** Turns a "watch.php?vid=..." card into a SearchResponse. */
    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href*=watch.php]") ?: this
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("watch.php")) return null

        val title = link.attr("title").ifBlank { link.text() }.trim()
        if (title.isBlank()) return null

        val poster = this.posterUrl() ?: link.posterUrl()
        val type = if (isMovie(title)) TvType.Movie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ----------------------------------------------------------------------
    // Main page
    // ----------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/topvideos.php?page=" to "الأكثر مشاهدة",
        "$mainUrl/newvideos.php?page=" to "أحدث الإضافات",
        "$mainUrl/category.php?cat=new-anime-eps&page=" to "أحدث حلقات الأنمي",
        "$mainUrl/category.php?cat=new-series-eps&page=" to "أحدث حلقات الكرتون",
        "$mainUrl/category.php?cat=new-movies&page=" to "أحدث الأفلام",
        "$mainUrl/category.php?cat=dubbed-anime&page=" to "أنمي مدبلج",
        "$mainUrl/category.php?cat=anime&page=" to "الأنمي المترجم",
        "$mainUrl/category.php?cat=disney-masr&page=" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon&page=" to "سبيستون",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(request.data + page).document

        // Category landing pages may show either video cards (watch.php)
        // or sub-series cards (category.php). Handle both.
        val items = doc.select("a[href*=watch.php], a[href*=category.php?cat=]")
            .mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val title = a.attr("title").ifBlank { a.text() }.trim()
                if (title.isBlank()) return@mapNotNull null

                when {
                    href.contains("watch.php") -> {
                        val type = if (isMovie(title)) TvType.Movie else TvType.Anime
                        newAnimeSearchResponse(title, href, type) {
                            this.posterUrl = a.posterUrl()
                        }
                    }
                    // sub-category that represents a show
                    href.contains("category.php?cat=") &&
                            !href.contains("filter=") &&
                            title.length > 1 -> {
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = a.posterUrl()
                        }
                    }
                    else -> null
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ----------------------------------------------------------------------
    // Search
    // ----------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search.php?keywords=$query").document
        return doc.select("a[href*=watch.php]")
            .mapNotNull { it.parent()?.toSearchResponse() ?: it.toSearchResponse() }
            .distinctBy { it.url }
    }

    // ----------------------------------------------------------------------
    // Load (details + episodes)
    // ----------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        // Category url -> redirect to a listing-style anime
        if (url.contains("category.php")) {
            return loadCategory(url)
        }

        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: name
        val cleanName = cleanTitle(rawTitle).ifBlank { rawTitle }

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.select("div.story img, .single img").firstOrNull()
                ?.let { fixUrlNull(it.attr("src")) }

        val plot = doc.select("div:contains(القصة) ~ p, .story p").firstOrNull()?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        // genres / year scraped from the meta filter links
        val tags = doc.select("a[href*=filter=genres]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val year = doc.selectFirst("a[href*=filter=years]")?.text()?.trim()?.toIntOrNull()

        // Collect episode links (season tabs each carry their own list).
        val episodes = mutableListOf<Episode>()
        val tabContents = doc.select("div.tab-content, .episodes-tab, ul.tabs-content > *")
        val seasonBlocks = if (tabContents.isNotEmpty()) tabContents
        else listOf(doc.body())

        seasonBlocks.forEachIndexed { sIndex, block ->
            block.select("a[href*=watch.php]").forEachIndexed { _, a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEachIndexed
                val label = a.text().trim()
                // episode number from "الحلقة_52_" style labels
                val epNum = Regex("(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                // skip "related videos" cards which carry a title attribute & rating
                if (a.hasAttr("title") && a.attr("title").contains("الحلقة")
                    && href == url
                ) return@forEachIndexed

                if (label.contains("الحلقة") || a.parent()?.text()?.contains("الحلقة") == true) {
                    episodes.add(
                        newEpisode(href) {
                            this.name = label.ifBlank { "الحلقة $epNum" }
                            this.episode = epNum
                            this.season = if (seasonBlocks.size > 1) sIndex + 1 else null
                        }
                    )
                }
            }
        }

        val distinctEps = episodes.distinctBy { it.data }

        return if (distinctEps.size > 1 && !isMovie(rawTitle)) {
            newAnimeLoadResponse(cleanName, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                addEpisodes(DubStatus.Dubbed, distinctEps)
            }
        } else {
            // Treat as a movie / single episode -> the page itself is the playable item.
            newMovieLoadResponse(cleanName, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    /** A category page that lists episodes/videos of one show. */
    private suspend fun loadCategory(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .page-title")?.text()?.trim()
            ?: cleanTitle(doc.title())

        val episodes = doc.select("a[href*=watch.php]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val label = a.attr("title").ifBlank { a.text() }.trim()
            val epNum = Regex("الحلقة\\s*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = label
                this.episode = epNum
            }
        }.distinctBy { it.data }

        // some category pages list sub-series (category.php) instead of episodes
        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(cleanTitle(title), url, TvType.Anime) {
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        } else {
            newMovieLoadResponse(cleanTitle(title), url, TvType.Movie, url) {}
        }
    }

    // ----------------------------------------------------------------------
    // Links
    // ----------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is a watch.php url -> convert to play.php which exposes servers & downloads
        val vid = Regex("vid=([a-zA-Z0-9]+)").find(data)?.groupValues?.get(1)
        val playUrl = if (vid != null) "$mainUrl/play.php?vid=$vid" else data

        val doc = app.get(playUrl, referer = mainUrl).document
        var found = false

        // 1) Streaming servers (iframes / data-embed)
        doc.select("iframe[src], [data-embed], [data-src*=http]").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-embed") }
                .ifBlank { el.attr("data-src") }
            val embed = fixUrlNull(src) ?: return@forEach
            if (embed.startsWith("http")) {
                if (loadExtractor(embed, playUrl, subtitleCallback, callback)) found = true
            }
        }

        // 2) Direct download / host links (1fichier, qiwi, lbx, 1cloudfile ...)
        doc.select("a[href^=http]").forEach { a ->
            val href = a.attr("href")
            val isHost = listOf(
                "1fichier", "qiwi", "lbx.to", "1cloudfile",
                "file-upload", "uupbom", "mp4upload", "dood",
                "streamtape", "voe", "vidmoly", "ok.ru", "mega"
            ).any { href.contains(it, ignoreCase = true) }
            if (isHost) {
                if (loadExtractor(href, playUrl, subtitleCallback, callback)) found = true
            }
        }

        return found
    }
}
