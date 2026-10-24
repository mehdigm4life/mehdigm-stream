package com.cimacloud

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class CimaCloud : MainAPI() {

    override var mainUrl = CimaCloudCrypto.CANONICAL_SITE_URL
    override var name = "سيما كلاود (CimaCloud)"
    override var lang = "ar"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "section:1" to "المميزة",
        "section:2" to "آخر الحلقات المضافة",
        "section:3" to "أحدث المسلسلات",
        "section:4" to "أحدث الأفلام",
        "section:6" to "أحدث الأنميات والكرتون",
        "cat:series:5" to "مسلسلات أنمي وكرتون",
        "cat:movies:6" to "أفلام أنمي وكرتون",
        "cat:movies:4" to "أفلام أجنبية",
        "cat:series:3" to "مسلسلات أجنبية",
        "section:5" to "أحدث الأعمال التركية",
        "section:8" to "أحدث الأعمال الكورية",
        "section:10" to "أحدث الأعمال الآسيوية",
        "section:7" to "أحدث الأعمال الهندية",
        "all:movies" to "كل الأفلام",
        "all:series" to "كل المسلسلات",
    )

    @Volatile
    private var cachedHomeJson: JSONObject? = null

    @Volatile
    private var cachedHomeAtMs: Long = 0L
    private val homeMutex = Mutex()

    private suspend fun getHomeCached(): JSONObject? {
        val now = System.currentTimeMillis()
        cachedHomeJson?.let { if (now - cachedHomeAtMs < HOME_CACHE_TTL_MS) return it }
        return homeMutex.withLock {
            cachedHomeJson?.let { if (System.currentTimeMillis() - cachedHomeAtMs < HOME_CACHE_TTL_MS) return it }
            val fetched = CimaCloudCrypto.apiGet("home")
            if (fetched != null) {
                cachedHomeJson = fetched
                cachedHomeAtMs = System.currentTimeMillis()
            }
            fetched
        }
    }

    // ── Home & Paginated Catalogs ─────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val spec = request.data
        val seenUrls = LinkedHashSet<String>()
        val items = ArrayList<SearchResponse>()

        fun appendItems(arr: JSONArray?, isEpisodeFeed: Boolean = false) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val parsed = parseCatalogItem(obj, forceEpisodeFeed = isEpisodeFeed) ?: continue
                if (seenUrls.add(parsed.url)) {
                    items.add(parsed)
                }
            }
        }

        var hasNext = false

        when {
            spec == "section:1" -> {
                if (page == 1) {
                    val home = getHomeCached()
                    val sliderItems = findHomeSectionItems(home, 1)
                    appendItems(sliderItems)
                }
                hasNext = false
            }

            spec.startsWith("section:") -> {
                val secId = spec.removePrefix("section:").toIntOrNull() ?: 3
                val isEpFeed = (secId == 2)
                coroutineScope {
                    val homeDeferred = if (page == 1) async { getHomeCached() } else null
                    val secDeferred = async { CimaCloudCrypto.apiGet("section/$secId?page=$page") }

                    val homeJson = homeDeferred?.await()
                    val secJson = secDeferred.await()

                    // On page 1, lead with the exact /home section order so featured items
                    // (e.g. Phony in أحدث المسلسلات, Star One in أحدث الأفلام) always appear first,
                    // then append the 30-item paginated /section/{id}?page=1 feed.
                    if (page == 1 && homeJson != null) {
                        appendItems(findHomeSectionItems(homeJson, secId), isEpisodeFeed = isEpFeed)
                    }
                    val secArr = secJson?.optJSONArray("section_items")
                        ?: secJson?.optJSONArray("results")
                        ?: secJson?.optJSONArray("items")
                    val beforeSecCount = items.size
                    appendItems(secArr, isEpisodeFeed = isEpFeed)
                    val secRawLen = secArr?.length() ?: 0
                    hasNext = secRawLen >= 15 || (page == 1 && items.size > beforeSecCount && secRawLen >= 10)
                }
            }

            spec.startsWith("cat:movies:") -> {
                val catId = spec.removePrefix("cat:movies:")
                val resp = CimaCloudCrypto.apiPost(
                    "movies",
                    mapOf("category" to catId, "page" to page.toString()),
                )
                val arr = resp?.optJSONArray("results") ?: resp?.optJSONArray("movies")
                appendItems(arr)
                hasNext = resp?.optBoolean("has_more", (arr?.length() ?: 0) >= 10) == true && items.isNotEmpty()
            }

            spec.startsWith("cat:series:") -> {
                val catId = spec.removePrefix("cat:series:")
                val resp = CimaCloudCrypto.apiPost(
                    "series",
                    mapOf("category" to catId, "page" to page.toString()),
                )
                val arr = resp?.optJSONArray("results") ?: resp?.optJSONArray("series")
                appendItems(arr)
                hasNext = resp?.optBoolean("has_more", (arr?.length() ?: 0) >= 10) == true && items.isNotEmpty()
            }

            spec == "all:movies" -> {
                val resp = CimaCloudCrypto.apiGet("movies?page=$page")
                val arr = resp?.optJSONArray("results") ?: resp?.optJSONArray("movies")
                appendItems(arr)
                hasNext = resp?.optBoolean("has_more", (arr?.length() ?: 0) >= 10) == true && items.isNotEmpty()
            }

            spec == "all:series" -> {
                val resp = CimaCloudCrypto.apiGet("series?page=$page")
                val arr = resp?.optJSONArray("results") ?: resp?.optJSONArray("series")
                appendItems(arr)
                hasNext = resp?.optBoolean("has_more", (arr?.length() ?: 0) >= 10) == true && items.isNotEmpty()
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = (spec == "section:1"))),
            hasNext = hasNext,
        )
    }

    private fun findHomeSectionItems(home: JSONObject?, sectionId: Int): JSONArray? {
        val sections = home?.optJSONArray("sections") ?: return null
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            if (sec.optInt("section_id", -1) == sectionId) {
                return sec.optJSONArray("section_items")
            }
        }
        return null
    }

    // ── Search & QuickSearch (Paginated) ──────────────────────────────────────

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), false)

        val resp = CimaCloudCrypto.apiPost(
            "search",
            mapOf(
                "title" to trimmed,
                "type" to "0",
                "sort" to "1",
                "page" to page.toString(),
            ),
        ) ?: return newSearchResponseList(emptyList(), false)

        val arr = resp.optJSONArray("results")
            ?: resp.optJSONArray("items")
            ?: resp.optJSONArray("data")
        val seen = LinkedHashSet<String>()
        val results = ArrayList<SearchResponse>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val parsed = parseCatalogItem(obj) ?: continue
                if (seen.add(parsed.url)) results.add(parsed)
            }
        }
        val hasMore = resp.optBoolean("has_more", (arr?.length() ?: 0) >= 10) && results.isNotEmpty()
        return newSearchResponseList(results, hasMore)
    }

    // ── Catalog Item Mapping (Movies, Series, Anime, Cartoon, AsianDrama) ────

    private fun parseCatalogItem(obj: JSONObject, forceEpisodeFeed: Boolean = false): SearchResponse? {
        val rawType = obj.optString("type").lowercase().trim()
        val isEpisode = forceEpisodeFeed || rawType == "episode"

        val id: String
        val displayTitle: String
        val epNumber: Int?
        val targetKind: String

        if (isEpisode) {
            val showId = obj.optString("tv_show_id")
                .ifBlank { obj.optString("serie_id") }
                .ifBlank { obj.optString("series_id") }
            val epId = obj.optString("id")
            if (showId.isBlank() && epId.isBlank()) return null

            val showName = obj.optString("tv_show_name")
                .ifBlank { obj.optString("serie_name") }
                .ifBlank { obj.optString("name") }
                .trim()
            if (showName.isEmpty()) return null

            epNumber = obj.optInt("episode_number", 0).takeIf { it > 0 }
                ?: extractTrailingInt(obj.optString("name"))
            val seasonNum = obj.optInt("season_number", 0).takeIf { it > 0 }
            displayTitle = if (seasonNum != null && seasonNum > 1 && epNumber != null) {
                "$showName (م$seasonNum)"
            } else {
                showName
            }
            if (showId.isNotBlank()) {
                id = showId
                targetKind = "series"
            } else {
                id = epId
                targetKind = "episode"
            }
        } else {
            id = obj.optString("id").trim()
            if (id.isEmpty()) return null
            displayTitle = obj.optString("name")
                .ifBlank { obj.optString("title") }
                .trim()
            if (displayTitle.isEmpty()) return null
            epNumber = null
            targetKind = if (rawType == "movie") "movie" else "series"
        }

        val url = "$mainUrl/$targetKind/$id"
        val poster = normalizeImageUrl(
            obj.optString("poster").ifBlank { obj.optString("backdrop") },
        )
        val voteVal = obj.optString("vote_average").toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }
        val yearVal = extractYear(obj.optString("year").ifBlank { obj.optString("release_date") })
        val isDub = obj.optInt("dub", 0) == 1 || displayTitle.contains("مدبلج")

        val tvType = classifyTvType(
            isMovie = (targetKind == "movie"),
            title = displayTitle,
            categories = obj.optJSONArray("categories"),
            genres = obj.optJSONArray("genres"),
        )

        return when (tvType) {
            TvType.Movie, TvType.AnimeMovie -> {
                newMovieSearchResponse(displayTitle, url, tvType) {
                    this.posterUrl = poster
                    this.year = yearVal
                    if (voteVal != null) this.score = Score.from10(voteVal)
                }
            }

            TvType.Cartoon -> {
                if (targetKind == "movie") {
                    newMovieSearchResponse(displayTitle, url, TvType.Cartoon) {
                        this.posterUrl = poster
                        this.year = yearVal
                        if (voteVal != null) this.score = Score.from10(voteVal)
                    }
                } else {
                    newAnimeSearchResponse(displayTitle, url, TvType.Cartoon) {
                        this.posterUrl = poster
                        this.year = yearVal
                        if (voteVal != null) this.score = Score.from10(voteVal)
                        addDubStatus(isDub, epNumber)
                    }
                }
            }

            TvType.Anime -> {
                newAnimeSearchResponse(displayTitle, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = yearVal
                    if (voteVal != null) this.score = Score.from10(voteVal)
                    addDubStatus(isDub, epNumber)
                }
            }

            TvType.AsianDrama -> {
                if (epNumber != null || isDub) {
                    newAnimeSearchResponse(displayTitle, url, TvType.AsianDrama) {
                        this.posterUrl = poster
                        this.year = yearVal
                        if (voteVal != null) this.score = Score.from10(voteVal)
                        addDubStatus(isDub, epNumber)
                    }
                } else {
                    newTvSeriesSearchResponse(displayTitle, url, TvType.AsianDrama) {
                        this.posterUrl = poster
                        this.year = yearVal
                        if (voteVal != null) this.score = Score.from10(voteVal)
                    }
                }
            }

            else -> {
                if (epNumber != null || isDub) {
                    newAnimeSearchResponse(displayTitle, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = yearVal
                        if (voteVal != null) this.score = Score.from10(voteVal)
                        addDubStatus(isDub, epNumber)
                    }
                } else {
                    newTvSeriesSearchResponse(displayTitle, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = yearVal
                        if (voteVal != null) this.score = Score.from10(voteVal)
                    }
                }
            }
        }
    }

    private fun classifyTvType(
        isMovie: Boolean,
        title: String,
        categories: JSONArray?,
        genres: JSONArray?,
    ): TvType {
        val catIds = HashSet<Int>()
        val catNames = ArrayList<String>()
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val c = categories.optJSONObject(i) ?: continue
                val cid = c.optInt("id", 0)
                if (cid > 0) catIds.add(cid)
                val cname = c.optString("name").trim()
                if (cname.isNotEmpty()) catNames.add(cname)
            }
        }

        val genreIds = HashSet<Int>()
        val genreNames = ArrayList<String>()
        if (genres != null) {
            for (i in 0 until genres.length()) {
                val g = genres.optJSONObject(i) ?: continue
                val gid = g.optInt("id", 0)
                if (gid > 0) genreIds.add(gid)
                val gname = g.optString("name").trim()
                if (gname.isNotEmpty()) genreNames.add(gname)
            }
        }

        val isAnimCat = 5 in catIds || 6 in catIds || 16 in genreIds ||
            catNames.any { it.contains("أنمي") || it.contains("كرتون") } ||
            genreNames.any { it.contains("رسوم متحركة") || it.contains("أنمي") }

        val isKidsOrCartoon = 408 in genreIds ||
            title.contains("كرتون") ||
            title.contains("مدبلج مصري") ||
            genreNames.any { it.contains("أطفال") } ||
            (isAnimCat && 3 in genreIds && !title.contains("أنمي", ignoreCase = true) && !title.contains("anime", ignoreCase = true))

        if (isAnimCat) {
            if (isKidsOrCartoon) return TvType.Cartoon
            return if (isMovie) TvType.AnimeMovie else TvType.Anime
        }

        if (!isMovie && (7 in catIds || 11 in catIds || catNames.any { it.contains("كوري") || it.contains("آسيوي") })) {
            return TvType.AsianDrama
        }

        return if (isMovie) TvType.Movie else TvType.TvSeries
    }

    // ── Detail Page (Movie / Series / Anime / Cartoon / Episode) ─────────────

    override suspend fun load(url: String): LoadResponse? {
        val target = parseTargetPath(url) ?: return null
        return when (target.first) {
            "movie" -> loadMovieDetail(target.second)
            "series", "serie" -> loadSeriesDetail(target.second)
            "episode" -> loadEpisodeRedirectOrSingle(target.second)
            else -> null
        }
    }

    private suspend fun loadMovieDetail(movieId: String): LoadResponse? {
        val root = CimaCloudCrypto.apiGet("movie/$movieId") ?: return null
        val m = root.optJSONObject("movie") ?: root
        val title = m.optString("name").ifBlank { m.optString("title") }.trim()
        if (title.isEmpty()) return null

        val canonicalUrl = "$mainUrl/movie/$movieId"
        val serversDataUrl = "$mainUrl/movie/$movieId/servers"

        val poster = normalizeImageUrl(m.optString("poster"))
        val backdrop = normalizeImageUrl(m.optString("backdrop"))
        val plot = buildPlot(m)
        val year = extractYear(m.optString("year").ifBlank { m.optString("release_date") })
        val runtime = m.optString("runtime").filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 }
        val rating = m.optString("vote_average").toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }
        val trailer = normalizeTrailerUrl(m.optString("trailer"))
        val isDub = m.optInt("dub", 0) == 1 || title.contains("مدبلج")
        val tags = buildTags(m.optJSONArray("categories"), m.optJSONArray("genres"), isDub)
        val tvType = classifyTvType(
            isMovie = true,
            title = title,
            categories = m.optJSONArray("categories"),
            genres = m.optJSONArray("genres"),
        )

        return newMovieLoadResponse(title, canonicalUrl, tvType, serversDataUrl) {
            this.posterUrl = poster
            if (!backdrop.isNullOrBlank()) this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.duration = runtime
            this.tags = tags
            if (rating != null) this.score = Score.from10(rating)
            if (trailer != null) addTrailer(trailer)
        }
    }

    private suspend fun loadSeriesDetail(seriesId: String): LoadResponse? = coroutineScope {
        val detailDeferred = async { CimaCloudCrypto.apiGet("series/$seriesId") }
        val episodesDeferred = async { CimaCloudCrypto.apiGet("series/$seriesId/episodes") }

        val detailRoot = detailDeferred.await() ?: return@coroutineScope null
        val s = detailRoot.optJSONObject("series") ?: detailRoot
        val title = s.optString("name").ifBlank { s.optString("title") }.trim()
        if (title.isEmpty()) return@coroutineScope null

        val canonicalUrl = "$mainUrl/series/$seriesId"
        val poster = normalizeImageUrl(s.optString("poster"))
        val backdrop = normalizeImageUrl(s.optString("backdrop"))
        val plot = buildPlot(s)
        val year = extractYear(
            s.optString("year")
                .ifBlank { s.optString("first_air_date") }
                .ifBlank { s.optString("release_date") },
        )
        val rating = s.optString("vote_average").toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }
        val trailer = normalizeTrailerUrl(s.optString("trailer"))
        val isDub = s.optInt("dub", 0) == 1 || title.contains("مدبلج")
        val tags = buildTags(s.optJSONArray("categories"), s.optJSONArray("genres"), isDub)
        val showStatus = parseShowStatus(s.optString("status"))
        val tvType = classifyTvType(
            isMovie = false,
            title = title,
            categories = s.optJSONArray("categories"),
            genres = s.optJSONArray("genres"),
        )

        val epRoot = episodesDeferred.await()
        val seasonsArr = epRoot?.optJSONArray("seasons") ?: s.optJSONArray("seasons")
        val episodes = buildEpisodesList(seasonsArr, defaultPoster = poster)

        if (tvType == TvType.Anime) {
            newAnimeLoadResponse(title, canonicalUrl, TvType.Anime) {
                this.posterUrl = poster
                if (!backdrop.isNullOrBlank()) this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = showStatus
                if (rating != null) this.score = Score.from10(rating)
                if (trailer != null) addTrailer(trailer)
                addEpisodes(if (isDub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            }
        } else {
            newTvSeriesLoadResponse(title, canonicalUrl, tvType, episodes) {
                this.posterUrl = poster
                if (!backdrop.isNullOrBlank()) this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = showStatus
                if (rating != null) this.score = Score.from10(rating)
                if (trailer != null) addTrailer(trailer)
            }
        }
    }

    private suspend fun loadEpisodeRedirectOrSingle(episodeId: String): LoadResponse? {
        val epRoot = CimaCloudCrypto.apiGet("episode/$episodeId") ?: return null
        val ep = epRoot.optJSONObject("episode") ?: epRoot
        val showId = ep.optString("tv_show_id")
            .ifBlank { ep.optString("serie_id") }
            .ifBlank { ep.optString("series_id") }
        if (showId.isNotBlank()) {
            loadSeriesDetail(showId)?.let { return it }
        }
        val epTitle = ep.optString("name").ifBlank { "Episode $episodeId" }
        val canonicalUrl = "$mainUrl/episode/$episodeId"
        return newMovieLoadResponse(epTitle, canonicalUrl, TvType.TvSeries, "$mainUrl/episode/$episodeId/servers") {
            this.posterUrl = normalizeImageUrl(ep.optString("poster"))
            this.plot = ep.optString("overview").takeIf { it.isNotBlank() }
        }
    }

    private fun buildEpisodesList(seasonsArr: JSONArray?, defaultPoster: String?): List<Episode> {
        if (seasonsArr == null || seasonsArr.length() == 0) return emptyList()

        data class RawEp(
            val seasonNum: Int,
            val epNum: Int?,
            val orderIdx: Int,
            val episode: Episode,
        )

        val collected = ArrayList<RawEp>()
        val seenEpIds = HashSet<String>()

        for (sIdx in 0 until seasonsArr.length()) {
            val seasonObj = seasonsArr.optJSONObject(sIdx) ?: continue
            val seasonNum = seasonObj.optString("season_number").trim().toIntOrNull()?.takeIf { it > 0 }
                ?: seasonObj.optInt("season_number", 0).takeIf { it > 0 }
                ?: extractTrailingInt(seasonObj.optString("title").ifBlank { seasonObj.optString("name") })
                ?: (sIdx + 1)
            val epsArr = seasonObj.optJSONArray("episodes") ?: continue

            for (eIdx in 0 until epsArr.length()) {
                val epObj = epsArr.optJSONObject(eIdx) ?: continue
                val epId = epObj.optString("id").trim()
                if (epId.isEmpty() || !seenEpIds.add(epId)) continue

                // In CimaCloud /series/{id}/episodes, `title` holds "الحلقة 1" while `name` holds the series title
                val rawEpTitle = epObj.optString("title").trim()
                val rawEpName = epObj.optString("name").trim()
                val epLabelBase = rawEpTitle.ifEmpty { rawEpName }

                val epNum = epObj.optInt("episode_number", 0).takeIf { it > 0 }
                    ?: extractTrailingInt(rawEpTitle)
                    ?: extractTrailingInt(rawEpName)

                val isFiller = epObj.optBoolean("filer", false) || epObj.optBoolean("filler", false)
                val finalEpName = buildString {
                    append(epLabelBase.ifEmpty { if (epNum != null) "الحلقة $epNum" else "حلقة" })
                    if (isFiller && !contains("فلر")) append(" [فلر]")
                }

                val epPoster = normalizeImageUrl(
                    epObj.optString("image")
                        .ifBlank { epObj.optString("still_path") }
                        .ifBlank { epObj.optString("poster") }
                        .ifBlank { epObj.optString("cover") }
                        .ifBlank { seasonObj.optString("poster_path") },
                ) ?: defaultPoster
                val epOverview = epObj.optString("overview").trim().takeIf { it.isNotEmpty() && it != "null" }
                val airDate = epObj.optString("air_date").trim()

                val epItem = newEpisode("$mainUrl/episode/$epId/servers") {
                    this.name = finalEpName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.description = epOverview
                    if (airDate.isNotEmpty()) addDate(airDate, "yyyy-MM-dd")
                }
                collected.add(RawEp(seasonNum, epNum, collected.size, epItem))
            }
        }

        return collected
            .sortedWith(
                compareBy<RawEp> { it.seasonNum }
                    .thenBy { it.epNum ?: Int.MAX_VALUE }
                    .thenBy { it.orderIdx },
            )
            .map { it.episode }
    }

    // ── Watch & Download Links (Streaming Multi-Quality Extractors) ──────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val serversApiPath = resolveServersApiPath(data) ?: return false
        val root = CimaCloudCrypto.apiGet(serversApiPath) ?: return false

        val serversArr = root.optJSONArray("servers")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("data")
            ?: return false

        val embedUrls = LinkedHashSet<String>()
        for (i in 0 until serversArr.length()) {
            val item = serversArr.optJSONObject(i)
            val link = when {
                item != null -> item.optString("link").ifBlank { item.optString("url") }.trim()
                else -> serversArr.optString(i).trim()
            }
            if (link.startsWith("http://") || link.startsWith("https://")) {
                embedUrls.add(link)
            }
        }
        if (embedUrls.isEmpty()) return false

        return CimaCloudExtractors.extractAllServers(
            embedUrls = embedUrls.toList(),
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    // ── Parsing & Normalization Helpers ──────────────────────────────────────

    private fun parseTargetPath(url: String): Pair<String, String>? {
        val m = Regex("""/(movie|series|serie|episode)/(\d+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return m.groupValues[1].lowercase() to m.groupValues[2]
    }

    private fun resolveServersApiPath(data: String): String? {
        Regex("""/(movie|episode)/(\d+)/servers""", RegexOption.IGNORE_CASE).find(data)?.let {
            return "${it.groupValues[1].lowercase()}/${it.groupValues[2]}/servers"
        }
        Regex("""/(movie|episode)/(\d+)""", RegexOption.IGNORE_CASE).find(data)?.let {
            return "${it.groupValues[1].lowercase()}/${it.groupValues[2]}/servers"
        }
        return null
    }

    private fun normalizeImageUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "false" }
            ?: return null
        return when {
            trimmed.startsWith("http://image.tmdb.org/") -> "https://" + trimmed.removePrefix("http://")
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "https://image.tmdb.org/t/p/w500$trimmed"
            else -> null
        }
    }

    private fun normalizeTrailerUrl(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "false" } ?: return null
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            Regex("""^[A-Za-z0-9_-]{8,15}$""").matches(t) -> "https://www.youtube.com/watch?v=$t"
            else -> null
        }
    }

    private fun buildPlot(obj: JSONObject): String? {
        val overview = obj.optString("overview").trim().takeIf { it.isNotEmpty() && it != "null" }
        val origTitle = obj.optString("original_title").ifBlank { obj.optString("original_name") }
            .trim()
            .takeIf { it.isNotEmpty() && it != "null" && it != obj.optString("name").trim() }
        return when {
            overview != null && origTitle != null -> "$overview\n\nالعنوان الأصلي: $origTitle"
            overview != null -> overview
            origTitle != null -> "العنوان الأصلي: $origTitle"
            else -> null
        }
    }

    private fun buildTags(categories: JSONArray?, genres: JSONArray?, isDub: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        out.add(if (isDub) "مدبلج" else "مترجم")
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val n = categories.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (n.isNotEmpty()) out.add(n)
            }
        }
        if (genres != null) {
            for (i in 0 until genres.length()) {
                val n = genres.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (n.isNotEmpty()) out.add(n)
            }
        }
        return out.toList()
    }

    private fun parseShowStatus(raw: String?): ShowStatus? {
        val s = raw?.trim().orEmpty()
        return when {
            s.contains("مستمر") || s.contains("يعرض") || s.equals("Returning Series", ignoreCase = true) ->
                ShowStatus.Ongoing
            s.contains("مكتمل") || s.contains("منتهي") || s.equals("Ended", ignoreCase = true) ->
                ShowStatus.Completed
            else -> null
        }
    }

    private fun extractYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""\b(19\d{2}|20\d{2})\b""").find(raw) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun extractTrailingInt(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""(\d+)""").find(raw) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    companion object {
        private const val HOME_CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
