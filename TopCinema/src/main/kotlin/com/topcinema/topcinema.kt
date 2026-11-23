package com.topcinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TopCinema : MainAPI() {
    override var mainUrl = "https://topcinemaa.one"
    override var name = "توب سينما (TopCinema)"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasDownloadSupport = true

    private val ajaxBase = "$mainUrl/wp-content/themes/movies2023/Ajaxat"

    override val mainPage = mainPageOf(
        "$mainUrl/recent/" to "المضاف حديثاً",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-8/" to "أفلام أجنبية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a-2/" to "أفلام أنمي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a/" to "أفلام آسيوية",
        "$mainUrl/netflix-movies/" to "أفلام نتفليكس",
        "$mainUrl/movies-collections/" to "سلاسل الأفلام",
        "$mainUrl/top-rating-imdb/" to "الأعلى تقييماً IMDb",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات أجنبية",
        "$mainUrl/top-rating-imdb-series/" to "المسلسلات الأعلى تقييماً IMDb",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات آسيوية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "أنمي"
    )

    // ------------------------------------------------------------------ helpers

    private fun posterOf(img: Element?): String? {
        if (img == null) return null
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val v = img.attr(attr)
            if (v.isBlank() || v.startsWith("data:")) continue
            if (v.endsWith("cover.jpg", true)) continue
            return v
        }
        return null
    }

    private fun typeOf(title: String, url: String): TvType {
        return if (url.contains("/series/") || title.contains("مسلسل") || title.contains("الحلقة")) {
            TvType.TvSeries
        } else TvType.Movie
    }

    private fun toSearchResult(el: Element): SearchResponse? {
        val a = el.selectFirst("a[href]") ?: return null
        val href = a.attr("href")
        if (!href.startsWith(mainUrl)) return null
        val title = a.attr("title").ifBlank {
            (el.selectFirst("h3.title") ?: el.selectFirst("h2") ?: el.selectFirst(".ep-info h2"))
                ?.text().orEmpty()
        }.ifBlank { a.text() }.trim()
        if (title.isBlank()) return null

        val poster = posterOf(el.selectFirst(".Poster img") ?: el.selectFirst("img"))?.let { fixUrl(it) }
        return if (typeOf(title, href) == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun cards(doc: Document): List<SearchResponse> =
        doc.select("div.Small--Box").mapNotNull { toSearchResult(it) }

    // ------------------------------------------------------------------ paging

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val response = app.get(url, referer = mainUrl)
        if (!response.isSuccessful) return newHomePageResponse(request.name, emptyList(), false)
        val items = cards(response.document)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val doc = app.post(
            "$ajaxBase/Searching.php",
            data = mapOf("search" to query, "type" to "all"),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = mainUrl
        ).document
        val items = cards(doc)
        return newSearchResponseList(items, items.isNotEmpty() && page <= 1)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items ?: emptyList()

    // ------------------------------------------------------------------ detail

    private fun metaValue(doc: Document, label: String): String? {
        val li = doc.select("ul.RightTaxContent > li").firstOrNull {
            it.selectFirst("span")?.text()?.contains(label) == true
        } ?: return null
        return li.select("a, strong").firstOrNull()?.text()?.trim()
    }

    private fun metaLinks(doc: Document, label: String): List<String> {
        val li = doc.select("ul.RightTaxContent > li").firstOrNull {
            it.selectFirst("span")?.text()?.contains(label) == true
        } ?: return emptyList()
        return li.select("a").mapNotNull { a -> a.text().trim().takeIf { it.isNotBlank() } }
    }

    private fun detailPoster(doc: Document): String? =
        posterOf(doc.selectFirst(".MainSingle .left .image img"))
            ?: posterOf(doc.selectFirst(".MainSingle .image img"))
            ?: posterOf(doc.selectFirst("article .image img, .MainSingle img"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

    private fun detailPlot(doc: Document): String? =
        doc.selectFirst(".story p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".story")?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun detailYear(doc: Document, fallback: String): Int? =
        metaValue(doc, "موعد الصدور")?.filter { it.isDigit() }?.takeIf { it.length == 4 }?.toIntOrNull()
            ?: Regex("""(19|20)\d{2}""").find(fallback)?.value?.toIntOrNull()

    private fun detailRating(doc: Document): Double? {
        val raw = doc.selectFirst(".imdbR span")?.text()
            ?: doc.selectFirst(".imdbR")?.text()
            ?: return null
        return Regex("""\d+(?:\.\d+)?""").find(raw)?.value?.toDoubleOrNull()
    }

    private fun detailTitle(doc: Document, url: String): String {
        val h1 = doc.selectFirst("h1.post-title")?.text()?.trim().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
        val cleaned = h1.replace(Regex("""\s*-\s*توب سينما.*$"""), "").trim()
        if (cleaned.isNotBlank()) return cleaned
        val path = url.trimEnd('/').substringAfterLast('/')
        return java.net.URLDecoder.decode(path, "UTF-8")
    }

    /**
     * The show landing page is always the first `/series/` crumb: season pages and
     * episode pages both link back to it.
     */
    private fun resolveShowUrl(doc: Document, self: String): String {
        val crumbs = doc.select("#mpbreadcrumbs a[href]").mapNotNull { a ->
            val h = a.attr("href")
            if (h.startsWith(mainUrl) && h.contains("/series/")) h to a.text() else null
        }
        val withoutSeason = crumbs.firstOrNull { !it.second.contains("الموسم") }?.first
        return withoutSeason ?: crumbs.firstOrNull()?.first ?: self
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document
        val showUrl = resolveShowUrl(doc, url)
        val showDoc = if (showUrl != url) try {
            app.get(showUrl, referer = mainUrl).document
        } catch (_: Throwable) {
            doc
        } else doc

        val title = detailTitle(showDoc, showUrl)
        val poster = detailPoster(showDoc)?.let { fixUrl(it) }
        val plot = detailPlot(showDoc)
        val year = detailYear(showDoc, title)
        val rating = detailRating(showDoc)
        val tags = metaLinks(showDoc, "نوع ")

        val looksSeries = showUrl.contains("/series/") ||
            url.contains("/series/") ||
            showDoc.selectFirst("section.allseasonss") != null ||
            showDoc.selectFirst("section.allepcont") != null ||
            title.contains("مسلسل")

        if (!looksSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
            }
        }

        val episodes = collectEpisodes(showDoc, showUrl)
        if (episodes.isEmpty()) {
            collectEpisodeCards(doc, 1, episodes)
        }

        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, episodes.values.toList()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(rating)
        }
    }

    // ------------------------------------------------------------------ episodes

    private fun collectEpisodeCards(
        doc: Document,
        season: Int,
        into: LinkedHashMap<String, Episode>
    ) {
        val container = doc.selectFirst("section.allepcont") ?: return
        container.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (!href.startsWith(mainUrl) || into.containsKey(href)) return@forEach
            val epTitle = a.attr("title").ifBlank {
                a.selectFirst("h2, h3")?.text().orEmpty()
            }.ifBlank { a.text() }.trim()
            if (epTitle.isBlank()) return@forEach
            val epNum = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: a.selectFirst(".epnum")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            into[href] = newEpisode(href) {
                this.name = epTitle
                this.season = season
                this.episode = epNum
                this.posterUrl = posterOf(a.selectFirst("img"))?.let { fixUrl(it) }
            }
        }
    }

    private suspend fun collectEpisodes(
        showDoc: Document,
        showUrl: String
    ): LinkedHashMap<String, Episode> {
        val episodes = LinkedHashMap<String, Episode>()

        val seasons = showDoc.select("section.allseasonss a[href]").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            val label = a.attr("title").ifBlank {
                a.selectFirst("h3.title, h2")?.text().orEmpty()
            }.ifBlank { a.selectFirst(".epnum")?.text().orEmpty() }.trim()
            val num = Regex("""الموسم\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""الموسم\s*(\d+)""").find(a.selectFirst(".epnum")?.text().orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            Triple(num, href, label)
        }.distinctBy { it.second }

        if (seasons.isEmpty()) {
            collectEpisodeCards(showDoc, 1, episodes)
            return episodes
        }

        for ((num, href, _) in seasons.take(MAX_SEASONS)) {
            val samePage = href == showUrl || href.trimEnd('/') == showUrl.trimEnd('/')
            val sdoc = if (samePage) showDoc else runCatching {
                app.get(href, referer = mainUrl).document
            }.getOrNull()
            if (sdoc != null) collectEpisodeCards(sdoc, num, episodes)
            delay(250)
        }
        return episodes
    }

    // ------------------------------------------------------------------ links

    private fun serverElements(doc: Document): List<Element> =
        doc.select("li.server--item[data-server][data-id]")

    private suspend fun embedUrl(id: String, index: String, referer: String): String? {
        val body = try {
            app.post(
                "$ajaxBase/Single/Server.php",
                data = mapOf("id" to id, "i" to index),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "text/html, */*; q=0.01"
                ),
                referer = referer
            ).text
        } catch (_: Throwable) {
            return null
        }
        return Regex("""src\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
    }

    private suspend fun emit(
        provider: String,
        embed: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val p = provider.lowercase()
        val custom: ExtractorApi? = when {
            p.contains("streamwish") -> TCStreamWish()
            p.contains("dood") -> TCDoodstream()
            p.contains("streamtape") -> TCStreamTape()
            p.contains("uqload") -> TCUqload()
            p.contains("vidtube") -> TCVidTube()
            p.contains("okstream") -> TCOKStream()
            p.contains("updown") -> TCUpDown()
            p.contains("filelions") || p.contains("vidhide") -> TCEarnVids()
            p.contains("byse") -> TCByse()
            p.contains("lulu") -> TCLuluStream()
            else -> null
        }
        return if (custom != null) {
            custom.getUrl(embed, referer, subtitleCallback, callback)
            true
        } else {
            loadExtractor(embed, referer, subtitleCallback, callback)
        }
    }

    /** Only trust the label when it advertises exactly one resolution. */
    private fun soleQuality(label: String): Int? =
        Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)
            .findAll(label)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .singleOrNull()

    /**
     * File-host download pages link to a short URL; the playable packed embed lives
     * under a deterministic path on the same provider.
     */
    private fun resolveDownload(href: String): String? {
        Regex("""/d/([A-Za-z0-9]+)\.html""").find(href)?.let { m ->
            return "https://down.vidtube.one/embed-${m.groupValues[1]}.html"
        }
        Regex("""//updown\.[a-z]+/([A-Za-z0-9]+)/?$""").find(href)?.let { m ->
            return "https://updown.icu/embed-${m.groupValues[1]}.html"
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val watchUrl = if (data.endsWith("/")) "${data}watch/" else "$data/watch/"
        val watchDoc = try {
            app.get(watchUrl, referer = mainUrl).document
        } catch (_: Throwable) {
            null
        }

        val servers = watchDoc?.let { serverElements(it) } ?: emptyList()
        if (servers.isNotEmpty()) {
            val id = servers.first().attr("data-id")
            for (server in servers) {
                val index = server.attr("data-server")
                val provider = server.selectFirst("span")?.text()?.trim()?.ifBlank { null }
                    ?: "Server $index"
                val embed = embedUrl(id, index, watchUrl) ?: continue
                if (emit(provider, embed, watchUrl, subtitleCallback, callback)) found = true
                delay(120)
            }
        }

        // download page: direct file-host mirrors (VideoTube / UpDown expose real qualities)
        val downloadDoc = try {
            val dUrl = if (data.endsWith("/")) "${data}download/" else "$data/download/"
            app.get(dUrl, referer = mainUrl).document
        } catch (_: Throwable) {
            null
        }
        downloadDoc?.select("a.downloadsLink[href]")?.forEach { a ->
            val href = a.attr("href")
            if (!href.startsWith("http")) return@forEach
            val provider = a.selectFirst("span")?.text()?.trim().orEmpty()
            val label = a.selectFirst("p")?.text()?.trim().orEmpty()
            val quality = soleQuality(label)
            val p = provider.lowercase()
            val custom: ExtractorApi? = when {
                p.contains("vidtube") -> TCVidTube()
                p.contains("updown") -> TCUpDown()
                else -> null
            }
            if (custom == null) return@forEach
            custom.getUrl(resolveDownload(href) ?: href, data, subtitleCallback) { link ->
                if (link.quality <= 0 && quality != null) link.quality = quality
                callback(link)
                found = true
            }
        }

        return found
    }

    companion object {
        private const val MAX_SEASONS = 40
    }
}
