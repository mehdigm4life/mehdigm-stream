package com.timemovies

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed data models and resilient `org.json` parsers for TimeMovies v4.7 & DoomVideoPlayer v1.6.
 */
object TimeMoviesModels {

    data class ApiEnvelope<T>(
        val success: Boolean,
        val status: Int,
        val message: String?,
        val result: T?,
        val emailRefusal: EmailRefusalInfo? = null,
    )

    data class EmailRefusalInfo(
        val refusal: Int,
        val action: Int,
        val waitSeconds: Long,
        val attemptsLeft: Int?,
        val recheckAllowed: Boolean,
    )

    data class UserProfile(
        val id: String,
        val name: String,
        val maturity: Int,
        val isMain: Boolean,
        val isPinSet: Boolean,
        val isVipLocked: Boolean,
        val lang: String?,
    )

    data class HomeCategory(
        val id: String,
        val order: Int,
        val hasMore: Boolean,
        val isHistory: Boolean,
        val titleEn: String,
        val titleAr: String?,
    ) {
        fun displayTitle(preferArabic: Boolean): String {
            return if (preferArabic) {
                titleAr?.takeIf { it.isNotBlank() } ?: titleEn
            } else {
                titleEn.ifBlank { titleAr.orEmpty() }
            }
        }
    }

    data class WorkCard(
        val id: String,
        val isMovie: Boolean?,
        val titleEn: String,
        val titleAr: String?,
        val posterUrl: String?,
        val posterArUrl: String?,
        val backdropUrl: String?,
        val rating: Double?,
        val releaseYear: Int?,
        val certification: String?,
        val badgeText: String?,
        val genresEn: List<String> = emptyList(),
        val genresAr: List<String> = emptyList(),
    ) {
        fun displayTitle(preferArabic: Boolean): String {
            return if (preferArabic) {
                titleAr?.takeIf { it.isNotBlank() } ?: titleEn
            } else {
                titleEn.ifBlank { titleAr.orEmpty() }
            }
        }

        fun bestPoster(preferArabic: Boolean): String? {
            if (preferArabic && !posterArUrl.isNullOrBlank()) return posterArUrl
            return posterUrl?.takeIf { it.isNotBlank() }
                ?: posterArUrl?.takeIf { it.isNotBlank() }
                ?: backdropUrl?.takeIf { it.isNotBlank() }
        }
    }

    data class HomeShelf(
        val category: HomeCategory,
        val works: List<WorkCard>,
    )

    data class HomeResponse(
        val featured: List<WorkCard>,
        val shelves: List<HomeShelf>,
    )

    data class GenreItem(
        val id: String,
        val nameEn: String,
        val nameAr: String?,
        val code: String?,
    ) {
        fun displayName(preferArabic: Boolean): String {
            return if (preferArabic) {
                nameAr?.takeIf { it.isNotBlank() } ?: nameEn
            } else {
                nameEn.ifBlank { nameAr.orEmpty() }
            }
        }
    }

    data class PageFilterResult(
        val works: List<WorkCard>,
        val page: Int,
        val isLast: Boolean,
    )

    data class CategoryPageResult(
        val works: List<WorkCard>,
        val lastId: String?,
        val lastVal: String?,
        val isLast: Boolean,
    )

    data class GenrePageResult(
        val works: List<WorkCard>,
        val lastAt: String?,
        val lastId: String?,
        val isLast: Boolean,
    )

    data class SearchResultPage(
        val movies: List<WorkCard>,
        val series: List<WorkCard>,
        val page: Int,
        val isLastMovies: Boolean,
        val isLastSeries: Boolean,
    )

    data class CastMember(
        val id: String,
        val name: String,
        val character: String?,
        val photoUrl: String?,
        val order: Int,
    )

    data class TrailerVideo(
        val key: String,
        val site: String?,
        val type: String?,
        val name: String?,
    )

    data class EpisodeInfo(
        val id: String,
        val seasonNum: Int,
        val episodeNum: Int,
        val mergedEndEpisodeNum: Int?,
        val isMerged: Boolean,
        val fileCode: String?,
        val name: String?,
        val summary: String?,
        val thumbUrl: String?,
        val durationMin: Int?,
    )

    data class SeasonBlock(
        val id: String,
        val seasonNum: Int,
        val name: String?,
        val overview: String?,
        val posterUrl: String?,
        val episodes: List<EpisodeInfo>,
    )

    data class WorkDetails(
        val id: String,
        val isMovie: Boolean,
        val titleEn: String,
        val titleAr: String?,
        val overviewEn: String?,
        val overviewAr: String?,
        val posterUrl: String?,
        val posterArUrl: String?,
        val backdropUrl: String?,
        val rating: Double?,
        val certification: String?,
        val releaseYear: Int?,
        val runtimeMin: Int?,
        val seasonCount: Int?,
        val fileCode: String?,
        val genresEn: List<String>,
        val genresAr: List<String>,
        val seasons: List<SeasonBlock>,
        val cast: List<CastMember>,
        val related: List<WorkCard>,
        val collection: List<WorkCard>,
        val trailers: List<TrailerVideo>,
        val originalTitle: String? = null,
    ) {
        fun displayTitle(preferArabic: Boolean): String {
            return if (preferArabic) {
                titleAr?.takeIf { it.isNotBlank() } ?: titleEn
            } else {
                titleEn.ifBlank { titleAr.orEmpty() }
            }
        }

        fun displayOverview(preferArabic: Boolean): String? {
            return if (preferArabic) {
                overviewAr?.takeIf { it.isNotBlank() } ?: overviewEn?.takeIf { it.isNotBlank() }
            } else {
                overviewEn?.takeIf { it.isNotBlank() } ?: overviewAr?.takeIf { it.isNotBlank() }
            }
        }

        fun displayGenres(preferArabic: Boolean): List<String> {
            return if (preferArabic && genresAr.isNotEmpty()) genresAr else genresEn.ifEmpty { genresAr }
        }

        fun bestPoster(preferArabic: Boolean): String? {
            if (preferArabic && !posterArUrl.isNullOrBlank()) return posterArUrl
            return posterUrl?.takeIf { it.isNotBlank() }
                ?: posterArUrl?.takeIf { it.isNotBlank() }
                ?: backdropUrl?.takeIf { it.isNotBlank() }
        }
    }

    data class MediaQualityItem(
        val quality: Int,
        val size: String?,
        val locked: Boolean,
    )

    data class MediaSourceItem(
        val link: String,
        val name: String?,
        val quality: Int?,
        val size: String?,
    )

    data class MediaEpisodeItem(
        val code: String?,
        val name: String?,
        val sources: List<MediaSourceItem>,
    )

    data class MediaV2Payload(
        val ok: Boolean,
        val reason: String?,
        val src: String?,
        val sources: List<MediaSourceItem>,
        val episodes: List<MediaEpisodeItem> = emptyList(),
        val qualities: List<Int> = emptyList(),
    )

    data class ExternalExecutionInfo(
        val descriptor: String?,
        val ticket: String?,
        val executor: String?,
        val isTicket: Boolean,
        val expiresAt: Long?,
    )

    data class MediaGrantPayload(
        val ok: Boolean,
        val reason: String?,
        val grant: String?,
        val target: Int?,
        val charged: Boolean,
        val unavailable: Boolean,
        val notConfigured: Boolean = false,
        val operationId: String? = null,
        val codes: List<String> = emptyList(),
        val period: String? = null,
        val limit: Int? = null,
        val count: Int? = null,
        val media: MediaV2Payload?,
        val external: ExternalExecutionInfo?,
    )

    data class LinkPayload(
        val workId: String,
        val fileCode: String,
        val isMovie: Boolean,
        val title: String,
        val episodeId: String? = null,
        val seasonId: String? = null,
        val seasonNum: Int? = null,
        val episodeNum: Int? = null,
        val titleAr: String? = null,
        val year: Int? = null,
        val isAnime: Boolean = false,
        val originalTitle: String? = null,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("wk", workId)
            put("fc", fileCode)
            put("ism", isMovie)
            put("tl", title)
            if (!episodeId.isNullOrBlank()) put("ep", episodeId)
            if (!seasonId.isNullOrBlank()) put("ss", seasonId)
            if (seasonNum != null) put("ssn", seasonNum)
            if (episodeNum != null) put("epn", episodeNum)
            if (!titleAr.isNullOrBlank()) put("tlr", titleAr)
            if (year != null) put("ry", year)
            if (isAnime) put("ani", true)
            if (!originalTitle.isNullOrBlank()) put("otl", originalTitle)
        }.toString()

        companion object {
            fun fromJson(raw: String): LinkPayload? = runCatching {
                val jsonSlice = raw.indexOf('{').let { if (it >= 0) raw.substring(it) else raw }
                val obj = JSONObject(jsonSlice)
                val wk = obj.optNonBlank("wk") ?: return null
                val fc = obj.optNonBlank("fc").orEmpty()
                LinkPayload(
                    workId = wk,
                    fileCode = fc,
                    isMovie = obj.optBoolean("ism", true),
                    title = obj.optNonBlank("tl") ?: "TimeMovies",
                    episodeId = obj.optNonBlank("ep"),
                    seasonId = obj.optNonBlank("ss"),
                    seasonNum = obj.optIntOrNull("ssn"),
                    episodeNum = obj.optIntOrNull("epn"),
                    titleAr = obj.optNonBlank("tlr"),
                    year = obj.optIntOrNull("ry"),
                    isAnime = obj.optBoolean("ani", false),
                    originalTitle = obj.optNonBlank("otl"),
                )
            }.getOrNull()
        }
    }

    // ---- JSON Parsing Helpers ----

    fun JSONObject.optNonBlank(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key, "").trim()
        return v.takeIf { it.isNotEmpty() && it != "null" }
    }

    fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
    }

    fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "").trim()
            if (s.isNotEmpty() && s != "null") out.add(s)
        }
        return out
    }

    fun parseEmailRefusal(obj: JSONObject?): EmailRefusalInfo? {
        if (obj == null) return null
        return EmailRefusalInfo(
            refusal = obj.optInt("refusal", 0),
            action = obj.optInt("action", 0),
            waitSeconds = obj.optLong("wait_seconds", 0L),
            attemptsLeft = obj.optIntOrNull("attempts_left"),
            recheckAllowed = obj.optBoolean("recheck_allowed", false),
        )
    }

    fun parseWorkCard(obj: JSONObject): WorkCard? {
        val id = obj.optNonBlank("_id") ?: return null
        val titleEn = obj.optNonBlank("tl") ?: obj.optNonBlank("tlr") ?: return null
        val isMovie: Boolean? = when {
            obj.has("ism") && !obj.isNull("ism") -> obj.optBoolean("ism")
            obj.has("isf") && !obj.isNull("isf") -> obj.optBoolean("isf")
            else -> null
        }
        return WorkCard(
            id = id,
            isMovie = isMovie,
            titleEn = titleEn,
            titleAr = obj.optNonBlank("tlr"),
            posterUrl = obj.optNonBlank("ps"),
            posterArUrl = obj.optNonBlank("psr"),
            backdropUrl = obj.optNonBlank("bg") ?: obj.optNonBlank("fg"),
            rating = obj.optDoubleOrNull("rt"),
            releaseYear = obj.optIntOrNull("ry"),
            certification = obj.optNonBlank("cr"),
            badgeText = obj.optNonBlank("rb") ?: obj.optNonBlank("tg"),
            genresEn = obj.optJSONArray("gn").toStringList(),
            genresAr = obj.optJSONArray("gr").toStringList(),
        )
    }

    fun parseWorkCardList(arr: JSONArray?): List<WorkCard> {
        if (arr == null) return emptyList()
        val out = ArrayList<WorkCard>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseWorkCard(item)?.let { out.add(it) }
        }
        return out
    }

    fun parseHomeResponse(resultObj: JSONObject): HomeResponse {
        val featured = parseWorkCardList(resultObj.optJSONArray("featured"))
        val shelvesArr = resultObj.optJSONArray("list")
        val shelves = ArrayList<HomeShelf>()
        if (shelvesArr != null) {
            for (i in 0 until shelvesArr.length()) {
                val shelfObj = shelvesArr.optJSONObject(i) ?: continue
                val catObj = shelfObj.optJSONObject("cat") ?: continue
                val catId = catObj.optNonBlank("_id") ?: continue
                val isHistory = catObj.optBoolean("ish", false)
                if (isHistory) continue
                val works = parseWorkCardList(shelfObj.optJSONArray("works"))
                if (works.isEmpty()) continue
                val category = HomeCategory(
                    id = catId,
                    order = catObj.optInt("catord", i),
                    hasMore = catObj.optBoolean("ismr", false),
                    isHistory = false,
                    titleEn = catObj.optNonBlank("tln") ?: "Category",
                    titleAr = catObj.optNonBlank("tlr"),
                )
                shelves.add(HomeShelf(category, works))
            }
        }
        return HomeResponse(featured = featured, shelves = shelves)
    }

    fun parseWorkDetails(obj: JSONObject): WorkDetails? {
        val id = obj.optNonBlank("_id") ?: return null
        val titleEn = obj.optNonBlank("tl") ?: obj.optNonBlank("tlr") ?: return null
        val isMovie = obj.optBoolean("ism", true)

        val seasons = ArrayList<SeasonBlock>()
        val ssArr = obj.optJSONArray("ss")
        if (ssArr != null) {
            for (i in 0 until ssArr.length()) {
                val wssObj = ssArr.optJSONObject(i) ?: continue
                val ssMeta = wssObj.optJSONObject("ss") ?: wssObj
                val seasonId = ssMeta.optNonBlank("_id") ?: "s_$i"
                val seasonNum = ssMeta.optIntOrNull("ssn") ?: (i + 1)
                val epsArr = wssObj.optJSONArray("eps")
                val episodes = ArrayList<EpisodeInfo>()
                if (epsArr != null) {
                    for (j in 0 until epsArr.length()) {
                        val epObj = epsArr.optJSONObject(j) ?: continue
                        val epId = epObj.optNonBlank("_id") ?: continue
                        episodes.add(
                            EpisodeInfo(
                                id = epId,
                                seasonNum = epObj.optIntOrNull("ssn") ?: seasonNum,
                                episodeNum = epObj.optIntOrNull("epn") ?: (j + 1),
                                mergedEndEpisodeNum = epObj.optIntOrNull("mrn"),
                                isMerged = epObj.optBoolean("ismd", false),
                                fileCode = epObj.optNonBlank("fc"),
                                name = epObj.optNonBlank("n"),
                                summary = epObj.optNonBlank("sm"),
                                thumbUrl = epObj.optNonBlank("imgr") ?: epObj.optNonBlank("img"),
                                durationMin = epObj.optIntOrNull("d"),
                            )
                        )
                    }
                }
                seasons.add(
                    SeasonBlock(
                        id = seasonId,
                        seasonNum = seasonNum,
                        name = ssMeta.optNonBlank("n"),
                        overview = ssMeta.optNonBlank("ov"),
                        posterUrl = ssMeta.optNonBlank("psr") ?: ssMeta.optNonBlank("ps"),
                        episodes = episodes.sortedBy { it.episodeNum },
                    )
                )
            }
        }

        val cast = ArrayList<CastMember>()
        val csArr = obj.optJSONArray("cs")
        if (csArr != null) {
            for (i in 0 until csArr.length()) {
                val cObj = csArr.optJSONObject(i) ?: continue
                val name = cObj.optNonBlank("n") ?: cObj.optNonBlank("on") ?: continue
                cast.add(
                    CastMember(
                        id = cObj.optNonBlank("_id") ?: "c_$i",
                        name = name,
                        character = cObj.optNonBlank("chr"),
                        photoUrl = cObj.optNonBlank("pp"),
                        order = cObj.optInt("ord", i),
                    )
                )
            }
        }

        val trailers = ArrayList<TrailerVideo>()
        val vdsArr = obj.optJSONArray("vds")
        if (vdsArr != null) {
            for (i in 0 until vdsArr.length()) {
                val vObj = vdsArr.optJSONObject(i) ?: continue
                val key = vObj.optNonBlank("k") ?: continue
                trailers.add(
                    TrailerVideo(
                        key = key,
                        site = vObj.optNonBlank("st"),
                        type = vObj.optNonBlank("t"),
                        name = vObj.optNonBlank("n"),
                    )
                )
            }
        }

        return WorkDetails(
            id = id,
            isMovie = isMovie,
            titleEn = titleEn,
            titleAr = obj.optNonBlank("tlr"),
            overviewEn = obj.optNonBlank("ov"),
            overviewAr = obj.optNonBlank("ovr"),
            posterUrl = obj.optNonBlank("ps"),
            posterArUrl = obj.optNonBlank("psr"),
            backdropUrl = obj.optNonBlank("bg") ?: obj.optNonBlank("fg"),
            rating = obj.optDoubleOrNull("rt"),
            certification = obj.optNonBlank("cr"),
            releaseYear = obj.optIntOrNull("ry"),
            runtimeMin = obj.optIntOrNull("r"),
            seasonCount = obj.optIntOrNull("sc"),
            fileCode = obj.optNonBlank("fc"),
            genresEn = obj.optJSONArray("gn").toStringList(),
            genresAr = obj.optJSONArray("gr").toStringList(),
            seasons = seasons.sortedBy { it.seasonNum },
            cast = cast.sortedBy { it.order },
            related = parseWorkCardList(obj.optJSONArray("rlt")),
            collection = parseWorkCardList(obj.optJSONArray("wcl")),
            trailers = trailers,
            originalTitle = obj.optNonBlank("otl") ?: obj.optNonBlank("ot") ?: obj.optNonBlank("on"),
        )
    }

    fun parseUserProfile(obj: JSONObject): UserProfile? {
        val id = obj.optNonBlank("_id") ?: return null
        return UserProfile(
            id = id,
            name = obj.optNonBlank("name") ?: "Profile",
            maturity = obj.optInt("maturity", 5),
            isMain = obj.optBoolean("is_main", false),
            isPinSet = obj.optBoolean("is_pin_set", false),
            isVipLocked = obj.optBoolean("is_vip_locked", false),
            lang = obj.optNonBlank("lang"),
        )
    }

    private fun parseMediaSourceList(sArr: JSONArray?): List<MediaSourceItem> {
        if (sArr == null) return emptyList()
        val sources = ArrayList<MediaSourceItem>(sArr.length())
        for (i in 0 until sArr.length()) {
            val sObj = sArr.optJSONObject(i) ?: continue
            val link = sObj.optNonBlank("link") ?: continue
            sources.add(
                MediaSourceItem(
                    link = link,
                    name = sObj.optNonBlank("name"),
                    quality = sObj.optIntOrNull("quality"),
                    size = sObj.optNonBlank("size"),
                )
            )
        }
        return sources
    }

    fun parseMediaV2(obj: JSONObject?): MediaV2Payload? {
        if (obj == null) return null
        val sources = parseMediaSourceList(obj.optJSONArray("sources"))
        val episodes = ArrayList<MediaEpisodeItem>()
        val epArr = obj.optJSONArray("episodes")
        if (epArr != null) {
            for (i in 0 until epArr.length()) {
                val eObj = epArr.optJSONObject(i) ?: continue
                episodes.add(
                    MediaEpisodeItem(
                        code = eObj.optNonBlank("code"),
                        name = eObj.optNonBlank("name"),
                        sources = parseMediaSourceList(eObj.optJSONArray("sources")),
                    )
                )
            }
        }
        val qualities = ArrayList<Int>()
        val qArr = obj.optJSONArray("qualities")
        if (qArr != null) {
            for (i in 0 until qArr.length()) {
                val q = qArr.optInt(i, -1)
                if (q > 0) qualities.add(q)
            }
        }
        return MediaV2Payload(
            ok = obj.optBoolean("ok", true),
            reason = obj.optNonBlank("reason"),
            src = obj.optNonBlank("src"),
            sources = sources,
            episodes = episodes,
            qualities = qualities,
        )
    }

    fun parseMediaGrant(obj: JSONObject): MediaGrantPayload {
        val extObj = obj.optJSONObject("external")
        val external = if (extObj != null) {
            ExternalExecutionInfo(
                descriptor = extObj.optNonBlank("descriptor"),
                ticket = extObj.optNonBlank("ticket"),
                executor = extObj.optNonBlank("executor"),
                isTicket = extObj.optBoolean("is_ticket", !extObj.optNonBlank("ticket").isNullOrBlank()),
                expiresAt = extObj.optLongOrNull("expires_at"),
            )
        } else {
            null
        }
        val denialObj = obj.optJSONObject("denial") ?: obj.optJSONObject("error")
        val resolvedReason = obj.optNonBlank("reason")
            ?: denialObj?.optNonBlank("reason")
            ?: denialObj?.optNonBlank("code")
        return MediaGrantPayload(
            ok = obj.optBoolean("ok", false),
            reason = resolvedReason,
            grant = obj.optNonBlank("grant"),
            target = obj.optIntOrNull("target"),
            charged = obj.optBoolean("charged", false),
            unavailable = obj.optBoolean("unavailable", false),
            notConfigured = obj.optBoolean("not_configured", false),
            operationId = obj.optNonBlank("operation_id"),
            codes = obj.optJSONArray("codes").toStringList(),
            period = obj.optNonBlank("period"),
            limit = obj.optIntOrNull("limit"),
            count = obj.optIntOrNull("count"),
            media = parseMediaV2(obj.optJSONObject("media")),
            external = external,
        )
    }
}
