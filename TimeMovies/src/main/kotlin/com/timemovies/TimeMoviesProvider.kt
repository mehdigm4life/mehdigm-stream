package com.timemovies

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.timemovies.TimeMoviesModels.GenreItem
import com.timemovies.TimeMoviesModels.LinkPayload
import com.timemovies.TimeMoviesModels.WorkCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TimeMovies : MainAPI() {

    override var mainUrl = "https://tmovies.app"
    override var name = "تايم موفيز (TimeMovies)"
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
        TvType.AsianDrama,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "filter:1" to "أحدث الأفلام",
        "filter:2" to "أحدث المسلسلات",
        "filter:3" to "أنمي وكرتون",
        "genres:movie" to "تصنيفات الأفلام",
        "genres:series" to "تصنيفات المسلسلات",
    )

    // Cursor caches for cursor-paginated Category & Genre shelves
    private val categoryCursors = ConcurrentHashMap<String, Pair<String?, String?>>()
    private val genreCursors = ConcurrentHashMap<String, Pair<String?, String?>>()

    @Volatile
    private var cachedGenres: List<GenreItem> = emptyList()

    private fun buildWorkUrl(id: String, isMovie: Boolean?): String {
        val typeHint = when (isMovie) {
            true -> "movie"
            false -> "series"
            null -> "work"
        }
        return "$mainUrl/$typeHint/$id"
    }

    private fun extractWorkIdFromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return clean.substringAfterLast('/')
    }

    private fun classifyTvType(isMovie: Boolean?, genresEn: List<String>, genresAr: List<String>): TvType {
        val joined = (genresEn + genresAr).joinToString(" ").lowercase()
        val isAnim = joined.contains("animation") || joined.contains("anime") ||
            joined.contains("انيميشن") || joined.contains("أنمي") || joined.contains("انمي") || joined.contains("كرتون")
        if (isMovie == true) {
            return if (isAnim) TvType.AnimeMovie else TvType.Movie
        }
        if (isAnim) return TvType.Anime
        return TvType.TvSeries
    }

    private fun WorkCard.toSearchResponse(preferAr: Boolean): SearchResponse {
        val title = displayTitle(preferAr)
        val href = buildWorkUrl(id, isMovie)
        val poster = bestPoster(preferAr)
        val tvType = classifyTvType(isMovie, genresEn, genresAr)

        return if (isMovie != false) {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                this.year = releaseYear
                this.score = rating?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                this.year = releaseYear
                this.score = rating?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            if (!TimeMoviesApi.hasUsableAuth()) {
                if (page > 1) return@withContext newHomePageResponse(emptyList(), hasNext = false)
                val noticeItem = newMovieSearchResponse(
                    name = "⚙️ اضغط على إعدادات إضافة TimeMovies لتسجيل الدخول أو تحديث الحسابات التلقائية",
                    url = "$mainUrl/auth-required",
                    type = TvType.Movie,
                )
                return@withContext newHomePageResponse(
                    listOf(HomePageList("تفعيل الجلسة مطلوب (TimeMovies)", listOf(noticeItem))),
                    hasNext = false,
                )
            }

            val preferAr = TimeMoviesPrefs.preferArabic
            val dataKey = request.data

            when {
                dataKey == "home" -> {
                    if (page > 1) return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    val home = TimeMoviesApi.getHome()
                        ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    val lists = ArrayList<HomePageList>()
                    if (home.featured.isNotEmpty()) {
                        lists.add(
                            HomePageList(
                                name = if (preferAr) "مختارات مميزة" else "Featured",
                                list = home.featured.map { it.toSearchResponse(preferAr) },
                                isHorizontalImages = false,
                            )
                        )
                    }
                    for (shelf in home.shelves) {
                        val items = shelf.works.map { it.toSearchResponse(preferAr) }
                        if (items.isNotEmpty()) {
                            lists.add(
                                HomePageList(
                                    name = shelf.category.displayTitle(preferAr),
                                    list = items,
                                )
                            )
                        }
                    }
                    newHomePageResponse(lists, hasNext = false)
                }

                dataKey.startsWith("filter:") -> {
                    val level = dataKey.substringAfter("filter:").toIntOrNull() ?: 0
                    val page0 = (page - 1).coerceAtLeast(0)
                    val res = TimeMoviesApi.getPageFilter(level = level, page0Based = page0)
                        ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    val items = res.works.map { it.toSearchResponse(preferAr) }
                    newHomePageResponse(
                        listOf(HomePageList(request.name, items)),
                        hasNext = !res.isLast && items.isNotEmpty(),
                    )
                }

                dataKey.startsWith("cat:") -> {
                    val catId = dataKey.substringAfter("cat:")
                    val cursorKey = "$catId:$page"
                    val (li, lv) = if (page <= 1) {
                        categoryCursors.keys.removeIf { it.startsWith("$catId:") }
                        null to null
                    } else {
                        categoryCursors[cursorKey] ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    }
                    val res = TimeMoviesApi.getCategoryPage(catId, li, lv)
                        ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    if (!res.isLast && (!res.lastId.isNullOrBlank() || !res.lastVal.isNullOrBlank())) {
                        categoryCursors["$catId:${page + 1}"] = res.lastId to res.lastVal
                    }
                    val items = res.works.map { it.toSearchResponse(preferAr) }
                    newHomePageResponse(
                        listOf(HomePageList(request.name, items)),
                        hasNext = !res.isLast && items.isNotEmpty(),
                    )
                }

                dataKey == "genres:movie" || dataKey == "genres:series" -> {
                    val isMovie = dataKey == "genres:movie"
                    val genres = cachedGenres.ifEmpty {
                        TimeMoviesApi.getGenres().also { if (it.isNotEmpty()) cachedGenres = it }
                    }
                    if (genres.isEmpty()) {
                        return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    }
                    // Each `page` loads the next batch of 4 genres so Home loads fast without hammering the API
                    val batchSize = 4
                    val startIdx = (page - 1) * batchSize
                    if (startIdx >= genres.size) {
                        return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    }
                    val slice = genres.subList(startIdx, minOf(startIdx + batchSize, genres.size))
                    val lists = ArrayList<HomePageList>()
                    for (genre in slice) {
                        val gRes = TimeMoviesApi.getGenreWorks(genreId = genre.id, isMovie = isMovie) ?: continue
                        val items = gRes.works.map {
                            val card = if (it.isMovie == null) it.copy(isMovie = isMovie) else it
                            card.toSearchResponse(preferAr)
                        }
                        if (items.isNotEmpty()) {
                            val prefix = if (isMovie) "أفلام" else "مسلسلات"
                            lists.add(HomePageList("$prefix ${genre.displayName(preferAr)}", items))
                        }
                    }
                    val hasMoreGenres = startIdx + batchSize < genres.size
                    newHomePageResponse(lists, hasNext = hasMoreGenres && lists.isNotEmpty())
                }

                dataKey.startsWith("genre:") -> {
                    val parts = dataKey.split(':')
                    val genreId = parts.getOrNull(1).orEmpty()
                    val isMovie = parts.getOrNull(2) != "series"
                    val cursorKey = "$genreId:$isMovie:$page"
                    val (la, li) = if (page <= 1) {
                        genreCursors.keys.removeIf { it.startsWith("$genreId:$isMovie:") }
                        null to null
                    } else {
                        genreCursors[cursorKey] ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    }
                    val res = TimeMoviesApi.getGenreWorks(
                        genreId = genreId,
                        isMovie = isMovie,
                        lastAt = la,
                        lastId = li,
                    ) ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
                    if (!res.isLast && (!res.lastAt.isNullOrBlank() || !res.lastId.isNullOrBlank())) {
                        genreCursors["$genreId:$isMovie:${page + 1}"] = res.lastAt to res.lastId
                    }
                    val items = res.works.map {
                        val card = if (it.isMovie == null) it.copy(isMovie = isMovie) else it
                        card.toSearchResponse(preferAr)
                    }
                    newHomePageResponse(
                        listOf(HomePageList(request.name, items)),
                        hasNext = !res.isLast && items.isNotEmpty(),
                    )
                }

                else -> newHomePageResponse(emptyList(), hasNext = false)
            }
        }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !TimeMoviesApi.hasUsableAuth()) return@withContext emptyList()
        val preferAr = TimeMoviesPrefs.preferArabic
        val res = TimeMoviesApi.searchTriple(query, page0Based = 0) ?: return@withContext emptyList()
        val combined = ArrayList<SearchResponse>(res.movies.size + res.series.size)
        res.movies.mapTo(combined) { it.toSearchResponse(preferAr) }
        res.series.mapTo(combined) { it.toSearchResponse(preferAr) }
        combined
    }

    override suspend fun search(query: String, page: Int): SearchResponseList = withContext(Dispatchers.IO) {
        if (query.isBlank() || !TimeMoviesApi.hasUsableAuth()) {
            return@withContext newSearchResponseList(emptyList(), hasNext = false)
        }
        val preferAr = TimeMoviesPrefs.preferArabic
        val page0 = (page - 1).coerceAtLeast(0)
        val res = TimeMoviesApi.searchTriple(query, page0Based = page0)
            ?: return@withContext newSearchResponseList(emptyList(), hasNext = false)
        val combined = ArrayList<SearchResponse>(res.movies.size + res.series.size)
        res.movies.mapTo(combined) { it.toSearchResponse(preferAr) }
        res.series.mapTo(combined) { it.toSearchResponse(preferAr) }
        val hasNext = (!res.isLastMovies || !res.isLastSeries) && combined.isNotEmpty()
        newSearchResponseList(combined, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        if (url.endsWith("/auth-required")) {
            throw ErrorLoadingException("يرجى فتح إعدادات إضافة TimeMovies ⚙️ لتسجيل الدخول أو مزامنة الحسابات التلقائية")
        }

        val workId = extractWorkIdFromUrl(url)
        if (workId.isBlank()) {
            throw ErrorLoadingException("معرف العمل غير صالح")
        }

        val details = TimeMoviesApi.getWorkDetails(workId)
            ?: throw ErrorLoadingException(
                if (!TimeMoviesApi.hasUsableAuth()) {
                    "يرجى فتح إعدادات إضافة TimeMovies ⚙️ لتسجيل الدخول أو مزامنة الحسابات التلقائية"
                } else {
                    "تعذر تحميل تفاصيل العمل ($workId)"
                }
            )

        val preferAr = TimeMoviesPrefs.preferArabic
        val title = details.displayTitle(preferAr)
        val plot = details.displayOverview(preferAr)
        val poster = details.bestPoster(preferAr)
        val background = details.backdropUrl ?: poster
        val tags = buildList {
            addAll(details.displayGenres(preferAr))
            details.certification?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()

        val actors = details.cast.map { member ->
            ActorData(
                actor = Actor(name = member.name, image = member.photoUrl),
                roleString = member.character,
            )
        }

        val recommendations = (details.collection + details.related)
            .distinctBy { it.id }
            .filter { it.id != details.id }
            .map { it.toSearchResponse(preferAr) }

        val youtubeTrailerUrls = details.trailers
            .filter { it.site == null || it.site.equals("YouTube", ignoreCase = true) }
            .map { "https://www.youtube.com/watch?v=${it.key}" }

        val tvType = classifyTvType(details.isMovie, details.genresEn, details.genresAr)
        val isAnimeWork = tvType == TvType.Anime || tvType == TvType.AnimeMovie

        if (details.isMovie) {
            val linkData = LinkPayload(
                workId = details.id,
                fileCode = details.fileCode.orEmpty(),
                isMovie = true,
                title = details.titleEn.ifBlank { title },
                titleAr = details.titleAr?.takeIf { it.isNotBlank() },
                year = details.releaseYear,
                isAnime = isAnimeWork,
            ).toJson()

            newMovieLoadResponse(
                name = title,
                url = buildWorkUrl(details.id, true),
                type = tvType,
                dataUrl = linkData,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = details.releaseYear
                this.duration = details.runtimeMin
                this.score = details.rating?.let { Score.from10(it) }
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
                if (youtubeTrailerUrls.isNotEmpty()) {
                    addTrailer(youtubeTrailerUrls)
                }
            }
        } else {
            val episodes = buildList {
                for (season in details.seasons) {
                    for (ep in season.episodes) {
                        val epData = LinkPayload(
                            workId = details.id,
                            fileCode = ep.fileCode.orEmpty(),
                            isMovie = false,
                            title = details.titleEn.ifBlank { title },
                            episodeId = ep.id,
                            seasonId = season.id,
                            seasonNum = ep.seasonNum,
                            episodeNum = ep.episodeNum,
                            titleAr = details.titleAr?.takeIf { it.isNotBlank() },
                            year = details.releaseYear,
                            isAnime = isAnimeWork,
                        ).toJson()

                        val epTitle = when {
                            ep.isMerged && ep.mergedEndEpisodeNum != null -> {
                                val baseName = ep.name?.takeIf { it.isNotBlank() }
                                if (baseName != null) "$baseName (E${ep.episodeNum}-E${ep.mergedEndEpisodeNum})"
                                else "الحلقة ${ep.episodeNum} - ${ep.mergedEndEpisodeNum}"
                            }
                            !ep.name.isNullOrBlank() -> ep.name
                            else -> "الحلقة ${ep.episodeNum}"
                        }

                        add(
                            newEpisode(epData) {
                                this.name = epTitle
                                this.season = ep.seasonNum
                                this.episode = ep.episodeNum
                                this.posterUrl = ep.thumbUrl ?: season.posterUrl ?: background
                                this.description = ep.summary ?: season.overview
                                this.runTime = ep.durationMin
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(
                name = title,
                url = buildWorkUrl(details.id, false),
                type = tvType,
                episodes = episodes,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = details.releaseYear
                this.duration = details.runtimeMin
                this.score = details.rating?.let { Score.from10(it) }
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
                if (youtubeTrailerUrls.isNotEmpty()) {
                    addTrailer(youtubeTrailerUrls)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val initialPayload = LinkPayload.fromJson(data) ?: return false
        val unlimitedActive = TimeMoviesUnlimitedEngine.isRuntimeActive()
        val hasCachedStream = TimeMoviesGrantCache.getValidEntry(initialPayload, verifyNetworkLiveness = false) != null

        if (!TimeMoviesApi.hasUsableAuth() && !unlimitedActive && !hasCachedStream) {
            TimeMoviesPlugin.notifyUserAr("يرجى فتح إعدادات إضافة TimeMovies ⚙️ لتسجيل الدخول أو مزامنة الحسابات التلقائية")
            return false
        }

        val needsMetadataEnrichment = initialPayload.fileCode.isBlank() ||
            (unlimitedActive && (initialPayload.titleAr.isNullOrBlank() || initialPayload.year == null))

        val resolvedPayload = if (!needsMetadataEnrichment) {
            initialPayload
        } else {
            val fresh = TimeMoviesApi.getWorkDetails(initialPayload.workId)
            if (fresh == null) {
                initialPayload
            } else {
                val freshCode = if (initialPayload.fileCode.isNotBlank()) {
                    initialPayload.fileCode
                } else if (initialPayload.isMovie) {
                    fresh.fileCode.orEmpty()
                } else {
                    val allEps = fresh.seasons.flatMap { s -> s.episodes.map { s to it } }
                    val matched = allEps.firstOrNull { (_, ep) -> ep.id == initialPayload.episodeId }
                        ?: allEps.firstOrNull { (_, ep) ->
                            ep.seasonNum == initialPayload.seasonNum && ep.episodeNum == initialPayload.episodeNum
                        }
                    matched?.second?.fileCode.orEmpty()
                }
                val detectedTvType = classifyTvType(fresh.isMovie, fresh.genresEn, fresh.genresAr)
                val freshIsAnime = initialPayload.isAnime ||
                    detectedTvType == TvType.Anime ||
                    detectedTvType == TvType.AnimeMovie

                initialPayload.copy(
                    fileCode = freshCode,
                    title = fresh.titleEn.ifBlank { initialPayload.title },
                    titleAr = initialPayload.titleAr ?: fresh.titleAr?.takeIf { it.isNotBlank() },
                    year = initialPayload.year ?: fresh.releaseYear,
                    isAnime = freshIsAnime,
                )
            }
        }

        if (resolvedPayload.fileCode.isBlank() && !unlimitedActive && !hasCachedStream) {
            TimeMoviesPlugin.notifyUserAr("تعذر العثور على رمز ملف التشغيل لهذا العمل — يرجى تحديث الصفحة")
            return false
        }

        val ok = TimeMoviesExtractors.extractAllLinks(
            payload = resolvedPayload,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
        if (!ok) {
            val reason = TimeMoviesApi.lastMediaRefusalReason
            if (!reason.isNullOrBlank()) {
                TimeMoviesPlugin.notifyUserAr(translateRefusalReasonAr(reason))
            }
        }
        return ok
    }

    private fun translateRefusalReasonAr(reason: String): String {
        return when (reason.lowercase(Locale.US)) {
            "account_not_activated", "email_not_verified", "not_activated" ->
                "هذا الحساب غير مفعّل بعد — افتح إعدادات TimeMovies ⚙️ وأدخل رمز التفعيل المرسل لبريدك"
            "quota_exhausted", "insufficient_quota" ->
                "انتهى الرصيد اليومي المجاني لهذا الحساب في TimeMovies"
            "blocked_by_plan" ->
                "هذه الميزة تتطلب اشتراك VIP في TimeMovies"
            "target_over_limit", "device_limit", "max_devices" ->
                "تم تجاوز الحد الأقصى للأجهزة المسموح بها في هذا الحساب"
            else -> "تعذر جلب الرابط من خادم TimeMovies ($reason)"
        }
    }
}
