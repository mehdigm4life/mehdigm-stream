package com.stardima

import android.net.Uri
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * StarDima (ستارديما) provider.
 *
 * Backend: a Laravel API behind a dynamic host. The app bootstraps through
 * `/api/app/info` (which returns the real `server_url`) and authenticates every
 * request with a static API key plus a (tampered) secure token. Episode/movie
 * sources are returned by `/api/episodes/{id}/servers` and are embedded in the
 * movie detail payload. Each item always ships a direct `backup` stream, which
 * is emitted as a plain video link; the remaining hosts go through CloudStream's
 * global [loadExtractor].
 */
class StarDima : MainAPI() {
    companion object {
        private const val API_KEY = "vGIu8q9aap55zyANSD3jvmbttClhuRmykbQjzsIQxoWloFmp29W2qqdTSrwR"
        private const val APP_UA = "jcartoonApp/1.0.8 (Android)"
        private const val APP_VERSION = "1.0.8"
        private const val SECURE_TOKEN = "HACKER_DETECTED_TAMPERED_APK"
        private const val DEVICE = "Xiaomi M2006C3MG"

        private val BASE_CANDIDATES = listOf(
            "https://f5f-efgeg59852-zfz2d-mmltizwwcvb5567r2r63e-zd46.stardima.app",
            "https://app.stardima.com",
            "https://jcartoonapp.wiib.top"
        )

        private const val DETAIL_SCHEME = "https://stardima.app/video/"
        private const val EPISODE_DATA = "stardima:episode:"
        private const val MOVIE_DATA = "stardima:movie:"
    }

    override var name = "StarDima"
    override var lang = "ar"
    override var mainUrl = "https://stardima.app"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AnimeMovie
    )

    @Volatile
    private var resolvedBase: String? = null

    // ---------------------------------------------------------------- plumbing

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to APP_UA,
        "Accept" to "application/json",
        "Accept-Language" to "ar",
        "x-api-key" to API_KEY,
        "x-app-version" to APP_VERSION,
        "x-app-name" to "stardima",
        "x-device" to DEVICE,
        "x-platform" to "Android 14",
        "X-Timestamp" to (System.currentTimeMillis() / 1000L).toString(),
        "X-Secure-Token" to SECURE_TOKEN
    )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun query(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private suspend fun getJson(url: String): JSONObject? {
        return try {
            val text = app.get(url, headers = headers()).text.trim()
            if (text.startsWith("{")) JSONObject(text) else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Resolves the live API host through `/api/app/info`, falling back to the baked-in hosts. */
    private suspend fun base(): String {
        resolvedBase?.let { return it }
        for (candidate in BASE_CANDIDATES) {
            val item = getJson("$candidate/api/app/info")
            val data = item?.optJSONObject("data")
            if (data != null) {
                val server = data.optString("server_url").trimEnd('/')
                val base = server.ifBlank { candidate }
                synchronized(this) { if (resolvedBase == null) resolvedBase = base }
                return base
            }
        }
        return BASE_CANDIDATES.first()
    }

    private fun normalizeType(raw: String?): String =
        if (raw.equals("series", true) || raw.equals("tv", true)) "series" else "movie"

    // ---------------------------------------------------------------- home

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = base()
        val lists = mutableListOf<HomePageList>()
        try {
            val tabs = getJson("$base/api/tabs?all=true")?.optJSONArray("tabs") ?: JSONArray()
            for (i in 0 until tabs.length()) {
                val tab = tabs.optJSONObject(i) ?: continue
                val title = tab.optString("title").ifBlank { "قائمة" }
                val type = tab.optString("type").ifBlank { "last_episodes" }
                val options = tab.optString("options")
                val items = searchByOption(base, type, options, page)
                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items, isHorizontalImages = true))
                }
            }
        } catch (_: Throwable) {
        }
        return newHomePageResponse(lists)
    }

    private suspend fun searchByOption(
        base: String,
        type: String,
        options: String,
        page: Int
    ): List<SearchResponse> {
        val url = "$base/api/searchByOption?" + query(
            "type" to type,
            "query" to "",
            "options" to options,
            "per_page" to "24",
            "page" to page.toString(),
            "subscription_user" to "false"
        )
        val json = getJson(url) ?: return emptyList()
        return parseVideos(json.optJSONArray("videos"))
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val base = base()
        val url = "$base/api/search?" + query(
            "categories[]" to "all",
            "query" to query,
            "language" to "all",
            "media" to "all",
            "status" to "all",
            "premium" to "all",
            "per_page" to "40",
            "page" to "1",
            "gener" to "all",
            "sort" to "newest",
            "year" to "all"
        )
        val json = getJson(url) ?: return emptyList()
        return parseVideos(json.optJSONArray("videos"))
    }

    private fun parseVideos(array: JSONArray?): List<SearchResponse> {
        if (array == null) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val title = item.optString("title").ifBlank { "بدون عنوان" }
            val poster = item.optString("poster_url")
                .ifBlank { item.optString("background_url") }
                .ifBlank { null }
            val isSeries = item.optBoolean("is_series", false) ||
                item.has("episode_id") ||
                (item.optString("seasons_count").toIntOrNull() ?: 0) > 0
            out.add(
                newMovieSearchResponse(
                    title,
                    "$DETAIL_SCHEME$id",
                    if (isSeries) TvType.TvSeries else TvType.Movie
                ) {
                    this.posterUrl = poster
                    this.year = item.optString("year").take(4).toIntOrNull()
                }
            )
        }
        return out
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse {
        val base = base()
        val id = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        val detail = getJson("$base/api/video/$id")
            ?: return error("تعذّر تحميل تفاصيل المحتوى.")

        val title = detail.optString("title").ifBlank { "بدون عنوان" }
        val poster = detail.optString("poster_url")
            .ifBlank { detail.optString("background_url") }
            .ifBlank { null }
        val plot = detail.optString("description").ifBlank { null }
        val year = detail.optString("year").take(4).toIntOrNull()
        val tags = detail.optString("tags").split(",").map { it.trim() }.filter { it.isNotEmpty() }

        return if (normalizeType(detail.optString("type")) == "series") {
            val episodes = mutableListOf<Episode>()
            val seasons = detail.optJSONArray("seasons") ?: JSONArray()
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val seasonId = season.optInt("id")
                val seasonNumber = season.optInt("season_number", s + 1)
                if (seasonId <= 0) continue
                val body = getJson("$base/api/episodes/$seasonId?page=1&per_page=200&q=")
                val data = body?.optJSONArray("data") ?: JSONArray()
                for (e in 0 until data.length()) {
                    val ep = data.optJSONObject(e) ?: continue
                    val epId = ep.optInt("id")
                    if (epId <= 0) continue
                    val epNumber = ep.optInt("episode_number", e + 1)
                    episodes.add(
                        newEpisode("$EPISODE_DATA$epId") {
                            this.name = ep.optString("title").ifBlank { "الحلقة $epNumber" }
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = poster
                            this.description = ep.optString("overview").ifBlank { null }
                        }
                    )
                }
            }
            episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$MOVIE_DATA$id") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private suspend fun error(message: String): LoadResponse =
        newMovieLoadResponse(message, mainUrl, TvType.Movie, "") {
            this.plot = message
        }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = base()
        val servers = try {
            when {
                data.startsWith(MOVIE_DATA) -> {
                    val id = data.removePrefix(MOVIE_DATA)
                    getJson("$base/api/video/$id")?.optJSONArray("servers")
                }
                data.startsWith(EPISODE_DATA) -> {
                    val id = data.removePrefix(EPISODE_DATA)
                    getJson("$base/api/episodes/$id/servers")?.optJSONArray("servers")
                }
                else -> null
            } ?: JSONArray()
        } catch (_: Throwable) {
            JSONArray()
        }

        var found = false
        val seen = mutableSetOf<String>()
        val emit: (ExtractorLink) -> Unit = { link -> if (seen.add(link.url)) callback(link) }

        val direct = mutableListOf<Pair<String, String>>()
        val external = mutableListOf<String>()

        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            var link = server.optString("url").trim()
            if (link.isEmpty()) continue
            val label = server.optString("label").ifBlank { "سيرفر" }
            val type = server.optString("type")

            unwrapStrema(link)?.let { link = it }

            val isDirect = type.equals("backup", true) ||
                label.equals("backup", true) ||
                link.contains("worldnow.top") ||
                link.endsWith(".mkv", true) ||
                link.endsWith(".mp4", true)

            if (isDirect) {
                direct.add(label to link)
            } else {
                external.add(link)
            }
        }

        for ((label, link) in direct) {
            emit(
                newExtractorLink("StarDima - $label", "StarDima - $label", link, ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                }
            )
            found = true
        }

        for (link in external) {
            try {
                if (loadExtractor(link, "$base/", subtitleCallback, emit)) found = true
            } catch (_: Throwable) {
            }
        }

        return found
    }

    /** `https://strema.top/embed2/?id=<encoded>` wraps a real embed; unwrap it. */
    private fun unwrapStrema(url: String): String? {
        if (!url.contains("strema.top/embed")) return null
        return try {
            Uri.parse(url).getQueryParameter("id")?.takeIf { it.startsWith("http") }
        } catch (_: Throwable) {
            null
        }
    }
}
