package com.cartoonanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * CartoonAnime provider for CloudStream.
 *
 * مبني على فحص تطبيق "مسلسلات كرتون وانمي" (com.anime.rashon.speed.loyert).
 * الـ API:
 *   - المفتاح العام: https://base-v3.apps-anime.com/ => {"base_url": "..."}
 *   - التوثيق: HTTP Basic (Authorization: Basic ...) على كل طلبات الـ API.
 *   - الرئيسية/البحث: files under cartoon_with_info (تُرجع قائمة أفلام/مسلسلات)
 *   - التفاصيل: playlist/read.php => قائمة المواسم، ثم episode/readPaging.php
 *   - الحلقات: كل حلقة تحتوي حقول video, video1..video5, video_url
 *   - السيرفرات: workers.dev لحل الروابط (test-stream / link / cdnlink)
 */
class CartoonAnime : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://apps-anime.com"
    override var name = "مسلسلات كرتون وأنمي"

    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AnimeMovie
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Basic base64("rabee3yamen:d^AFi%Mu9Th5Wc7uLwh1nEMG8fp2*CW@")
    private val auth = "Basic cmFiZWUzeWFtZW46ZF5BRmklTXU5VGg1V2M3dUx3aDFuRU1HOGZwMipDV0A="

    private val baseV3 = "https://base-v3.apps-anime.com/"
    private val fallbackApi = "https://anime-cartoon.developer-pro.workers.dev/API/"

    private val headers = mapOf(
        "Authorization" to auth,
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "ar,en;q=0.9"
    )

    @Volatile private var cachedApi: String? = null

    // ---------------------------------------------------------------
    // Network / JSON helpers
    // ---------------------------------------------------------------

    private suspend fun api(): String {
        cachedApi?.let { return it }
        val base = try {
            val txt = app.get(baseV3, headers = headers, timeout = 30).text
            JSONObject(txt).optString("base_url").trim().ifBlank { fallbackApi }
        } catch (_: Exception) {
            fallbackApi
        }
        val fixed = if (base.endsWith("/")) base else "$base/"
        cachedApi = fixed
        return fixed
    }

    private suspend fun getArray(path: String): JSONArray =
        try {
            JSONArray(app.get(api() + path, headers = headers, timeout = 30).text)
        } catch (_: Exception) {
            JSONArray()
        }

    private suspend fun postArray(path: String, data: Map<String, String>): JSONArray =
        try {
            JSONArray(
                app.post(api() + path, data = data, headers = headers, timeout = 30).text
            )
        } catch (_: Exception) {
            JSONArray()
        }

    private suspend fun postObject(path: String, data: Map<String, String>): JSONObject? =
        try {
            JSONObject(app.post(api() + path, data = data, headers = headers, timeout = 30).text)
        } catch (_: Exception) {
            null
        }

    private fun JSONObject.str(key: String): String =
        optString(key, "").trim().let { if (it == "null") "" else it }

    private fun JSONObject.intOf(key: String, def: Int = 0): Int {
        val v = opt(key)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toDoubleOrNull()?.toInt() ?: def
            else -> def
        }
    }

    // ---------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------

    private data class Row(val title: String, val path: String, val paged: Boolean)

    private val rows = listOf(
        Row("الأكثر مشاهدة", "cartoon_with_info/getMostViewedCartoons.php", false),
        Row("أحدث الحلقات", "episodeWithInfo/latest.php", false),
        Row("تابع المشاهدة", "cartoon_with_info/readPagingContinueAnime.php", true),
        Row("أنمي مترجم", "cartoon_with_info/readPagingTranslatedSeriesAnime.php", true),
        Row("أنمي مدبلج", "cartoon_with_info/readPagingDUBBEDSeriesAnime.php", true),
        Row("أفلام مترجمة", "cartoon_with_info/readPagingTranslatedFilms.php", true),
        Row("أفلام مدبلجة", "cartoon_with_info/readPagingDUBBEDFilms.php", true),
        Row("مواعيد الحلقات", "episode_dates_with_info/read.php", false)
    )

    override val mainPage: List<MainPageData> =
        rows.map { MainPageData(it.title, it.path, it.paged) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val row = rows.firstOrNull { it.title == request.name }
        val paged = row?.paged ?: false
        val path = row?.path ?: request.data
        val url = if (paged) "$path?page=$page" else path
        val arr = getArray(url)
        val list = (0 until arr.length()).mapNotNull { toSearch(arr.optJSONObject(it)) }
        return newHomePageResponse(request.name, list, hasNext = paged && list.isNotEmpty())
    }

    // ---------------------------------------------------------------
    // Search / item parsing
    // ---------------------------------------------------------------

    private fun toSearch(obj: JSONObject?): SearchResponse? {
        if (obj == null) return null
        val cartoon = obj.optJSONObject("cartoon") ?: obj
        val id = cartoon.str("id").ifBlank { obj.str("cartoon_id") }.ifBlank { obj.str("id") }
        if (id.isBlank() || id == "0") return null
        val title = cartoon.str("title").ifBlank { obj.str("name") }
        if (title.isBlank()) return null
        val thumb = cartoon.str("thumb").ifBlank { obj.str("thumb") }
        val type = cartoon.intOf("type", obj.intOf("type", 1))
        val cls = cartoon.intOf("classification", obj.intOf("classification", 0))
        val tvType = if (type == 2) TvType.Movie else TvType.Anime
        val url = buildUrl(id, title, thumb, type, cls)
        return newMovieSearchResponse(title, url, tvType) {
            this.posterUrl = thumb.ifBlank { null }
        }
    }

    private fun buildUrl(id: String, title: String, thumb: String, type: Int, cls: Int): String =
        "$mainUrl/anime/$id?t=${enc(title)}&p=${enc(thumb)}&ty=$type&cls=$cls"

    override suspend fun search(query: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val q = enc(query)
        for (t in listOf(1, 2)) {
            for (c in listOf(1, 2)) {
                val arr = getArray("cartoon_with_info/searchCartoon.php?search=$q&type=$t&classification=$c")
                for (i in 0 until arr.length()) {
                    val r = toSearch(arr.optJSONObject(i)) ?: continue
                    out[r.url] = r
                }
            }
        }
        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------------------------------------------------------------
    // Load (details + episodes)
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfter("/anime/").substringBefore("?").substringBefore("/").trim()
        val query = url.substringAfter("?", "")
        val title = param(query, "t")?.let { dec(it) }?.takeIf { it.isNotBlank() } ?: "بدون عنوان"
        val poster = param(query, "p")?.let { dec(it) }?.takeIf { it.isNotBlank() }
        val type = param(query, "ty")?.toIntOrNull() ?: 1
        val cls = param(query, "cls")?.toIntOrNull() ?: 0

        val info = postObject("information/readOne.php", mapOf("cartoon_id" to id))
        val plot = info?.str("story")?.ifBlank { null }
        val year = info?.str("view_date")?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
        val tags = (info?.str("category") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .toMutableList()
        when (cls) {
            1 -> tags.add("مدبلج")
            2 -> tags.add("مترجم")
        }

        val playlists = postArray("playlist/read.php", mapOf("cartoon_id" to id))

        val episodes = ArrayList<Episode>()
        for (p in 0 until playlists.length()) {
            val pl = playlists.optJSONObject(p) ?: continue
            val plId = pl.str("id")
            if (plId.isBlank()) continue
            val season = p + 1
            var page = 1
            while (page <= 60) {
                val arr = postArray("episode/readPaging.php?page=$page", mapOf("playlist_id" to plId))
                if (arr.length() == 0) break
                for (e in 0 until arr.length()) {
                    val ep = arr.optJSONObject(e) ?: continue
                    episodes.add(makeEpisode(ep, season, poster))
                }
                page++
            }
        }

        val uniq = episodes.distinctBy { it.data }

        return if (type == 2) {
            val first = uniq.firstOrNull()
            newMovieLoadResponse(title, url, TvType.Movie, first?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniq) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private fun makeEpisode(ep: JSONObject, season: Int, poster: String?): Episode {
        val epTitle = ep.str("title").ifBlank { "الحلقة" }
        val num = Regex("\\d+").find(epTitle)?.value?.toIntOrNull()
        val links = ArrayList<String>()
        for (k in listOf("video", "video1", "video2", "video3", "video4", "video5", "video_url")) {
            val v = ep.str(k)
            if (v.isNotEmpty()) links.add(v)
        }
        return newEpisode(links.joinToString("\n")) {
            this.name = epTitle
            this.season = season
            this.episode = num
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------
    // Link resolution
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val sources = data.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (link in sources) {
            try {
                when {
                    link.contains(".workers.dev/video?url=") -> {
                        val txt = app.get(link, headers = headers, timeout = 40).text
                        val arr = JSONObject(txt).optJSONArray("availableQualities")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val vurl = o.optString("url").trim()
                                if (vurl.isBlank()) continue
                                emit(link, vurl, o.optString("quality"), callback)
                                found = true
                            }
                        }
                    }

                    link.contains(".workers.dev/") -> {
                        val resp = app.get(link, headers = headers, timeout = 40)
                        val finalUrl = resp.url
                        if (finalUrl.isNotBlank() && finalUrl != link) {
                            emit(link, finalUrl, null, callback)
                            found = true
                        }
                    }

                    link.contains("photos.google.com") -> {
                        // غير قابل للتشغيل المباشر
                    }

                    else -> {
                        if (link.contains(".mp4") || link.contains(".m3u8") ||
                            link.contains("vkuser") || link.contains("okcdn")
                        ) {
                            emit(link, link, null, callback)
                            found = true
                        }
                    }
                }
            } catch (_: Exception) {
                // تابع بقية السيرفرات
            }
        }
        return found
    }

    private suspend fun emit(
        referer: String,
        url: String,
        quality: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val q = (quality ?: "").ifBlank { null }
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name + if (q != null) " - $q" else "",
                url = url,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = parseQuality(q)
                this.referer = referer
                this.headers = mapOf("User-Agent" to ua)
            }
        )
    }

    private fun parseQuality(q: String?): Int = when (q?.lowercase()) {
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        "240p" -> Qualities.P240.value
        "144p" -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }

    // ---------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Exception) { s }

    private fun dec(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun param(query: String, key: String): String? {
        for (pair in query.split("&")) {
            val i = pair.indexOf('=')
            if (i > 0 && pair.substring(0, i) == key) return pair.substring(i + 1)
        }
        return null
    }
}
