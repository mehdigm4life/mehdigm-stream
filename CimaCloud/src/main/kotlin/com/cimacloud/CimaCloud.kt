package com.cimacloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CimaCloud : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://1654865.xyz/v1.3/api"
    override var name = "CimaCloud"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AnimeMovie
    )

    private fun getHeaders(): Map<String, String> {
        val devId = UUID.randomUUID().toString().replace("-", "").take(16)
        return mapOf(
            "User-Agent" to "okhttp/4.10.0",
            "Accept" to "application/json, text/plain, */*",
            "firebase_id" to devId
        )
    }

    private fun safeJson(text: String): JSONObject? {
        return try {
            if (text.isBlank()) return null
            val trimmed = text.trim()
            if (trimmed.startsWith("<") || !trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
            if (trimmed.startsWith("[")) return JSONObject("{\"data\":$trimmed}")
            JSONObject(trimmed)
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeType(t: String): TvType = when (t) {
        "serie", "series", "tv", "tvshow" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "cartoon" -> TvType.Cartoon
        "movie" -> TvType.Movie
        else -> TvType.Movie
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val home = app.get("$mainUrl/home", headers = getHeaders()).text
            val json = safeJson(home) ?: return newHomePageResponse(emptyList())
            val sections = json.optJSONArray("sections") ?: JSONArray()
            val pages = mutableListOf<HomePageList>()
            for (i in 0 until sections.length()) {
                val sec = sections.getJSONObject(i)
                val name = sec.optString("section_name", "")
                val items = sec.optJSONArray("section_items") ?: JSONArray()
                val list = mutableListOf<SearchResponse>()
                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j)
                    val id = item.optString("id")
                    val itemName = item.optString("name")
                    val poster = item.optString("poster")
                    val type = item.optString("type", "movie")
                    val year = item.optString("release_date", "").take(4).toIntOrNull()
                    val tvType = normalizeType(type)
                    list.add(
                        newMovieSearchResponse(
                            name = itemName,
                            url = "$mainUrl/$type/$id",
                            type = tvType
                        ) {
                            this.posterUrl = if (poster.isNotEmpty()) poster else null
                            this.year = year
                        }
                    )
                }
                if (list.isNotEmpty()) pages.add(HomePageList(name, list))
            }
            newHomePageResponse(pages)
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val res = app.post(
                "$mainUrl/search",
                headers = getHeaders() + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("title" to query, "type" to "2", "sort" to "1", "page" to "1")
            ).text
            val json = safeJson(res) ?: return emptyList()
            val arr = json.optJSONArray("results") ?: JSONArray()
            val results = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.optString("name")
                val type = item.optString("type", "movie")
                val poster = item.optString("poster")
                val year = item.optString("year", "0").toIntOrNull()
                val tvType = normalizeType(type)
                val id = item.optString("id")
                results.add(
                    newMovieSearchResponse(
                        name = name,
                        url = "$mainUrl/$type/$id",
                        type = tvType
                    ) {
                        this.posterUrl = if (poster.isNotEmpty()) poster else null
                        this.year = year
                    }
                )
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val jsonText = app.get(url, headers = getHeaders()).text
        val json = safeJson(jsonText) ?: throw Exception("Invalid response")
        if (json.optBoolean("blocked", false)) {
            throw Exception("Content blocked")
        }

        if (url.contains("/episode/")) {
            val ep = json.optJSONObject("episode") ?: JSONObject()
            val series = ep.optJSONObject("series") ?: JSONObject()
            val seriesId = series.optString("id")
            val epsUrl = "$mainUrl/series/$seriesId/episodes"
            val epsText = app.get(epsUrl, headers = getHeaders()).text
            val epsJson = safeJson(epsText) ?: JSONObject()
            val seasons = epsJson.optJSONArray("seasons") ?: JSONArray()
            val episodes = mutableListOf<Episode>()
            for (s in 0 until seasons.length()) {
                val season = seasons.getJSONObject(s)
                val eps = season.optJSONArray("episodes") ?: JSONArray()
                val seasonNum = season.optString("season_number", "1").toIntOrNull()
                for (e in 0 until eps.length()) {
                    val ei = eps.getJSONObject(e)
                    episodes.add(
                        newEpisode("$mainUrl/episode/${ei.optString("id")}/servers") {
                            this.name = ei.optString("title")
                            this.season = seasonNum
                            this.episode = ei.optString("episode_number", "0").toIntOrNull()
                            this.posterUrl = ei.optString("image").ifEmpty { ei.optString("cover") }
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(
                series.optString("name"),
                "$mainUrl/serie/$seriesId",
                TvType.TvSeries,
                episodes.sortedBy { it.episode }
            ) {
                this.posterUrl = series.optString("poster").ifEmpty { series.optString("backdrop") }
                this.plot = ep.optString("overview")
            }
        }

        val data = json.optJSONObject("data") ?: JSONObject()
        val item = if (data.has("series")) data.optJSONObject("series") else if (data.has("movie")) data.optJSONObject("movie") else JSONObject()
        val id = url.substringAfterLast("/").substringBefore("?")
        val type = if (url.contains("/serie/") || url.contains("/series/")) "series" else "movie"
        val name = item.optString("name")
        val poster = item.optString("poster")
        val backdrop = item.optString("backdrop")
        val overview = item.optString("overview")

        return if (type == "movie") {
            newMovieLoadResponse(
                name,
                "$mainUrl/movie/$id",
                TvType.Movie,
                "$mainUrl/movie/$id/servers"
            ) {
                this.posterUrl = poster.ifEmpty { backdrop }
                this.plot = overview
            }
        } else {
            val epsText = app.get("$mainUrl/serie/$id/episodes", headers = getHeaders()).text
            val epsJson = safeJson(epsText) ?: JSONObject()
            val seasons = epsJson.optJSONArray("seasons") ?: JSONArray()
            val episodes = mutableListOf<Episode>()
            for (s in 0 until seasons.length()) {
                val season = seasons.getJSONObject(s)
                val eps = season.optJSONArray("episodes") ?: JSONArray()
                val seasonNum = season.optString("season_number", "1").toIntOrNull()
                for (e in 0 until eps.length()) {
                    val ei = eps.getJSONObject(e)
                    episodes.add(
                        newEpisode("$mainUrl/episode/${ei.optString("id")}/servers") {
                            this.name = ei.optString("title")
                            this.season = seasonNum
                            this.episode = ei.optString("episode_number", "0").toIntOrNull()
                            this.posterUrl = ei.optString("image").ifEmpty { ei.optString("cover") }
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(name, "$mainUrl/serie/$id", TvType.TvSeries, episodes) {
                this.posterUrl = poster.ifEmpty { backdrop }
                this.plot = overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val text = app.get(data, headers = getHeaders(), allowRedirects = true).text
            val json = safeJson(text) ?: return false
            if (json.optBoolean("blocked", false)) return false
            val servers = json.optJSONArray("servers") ?: JSONArray()
            for (i in 0 until servers.length()) {
                val s = servers.getJSONObject(i)
                val link = s.optString("link")
                if (link.isNotEmpty()) {
                    if (link.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(this.name, link, "https://cima-cloud.com/").forEach(callback)
                    } else if (link.contains(".mp4")) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = link,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://cima-cloud.com/"
                            }
                        )
                    }
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
