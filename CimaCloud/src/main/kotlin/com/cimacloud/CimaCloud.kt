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
        // Try common plausible firebase_id values
        val devId = "1ecf0bf45eb04ff8b6445c3a36a3966a"
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
        fun normalizeUrl(u: String): String {
            var s = u
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }

        suspend fun fetchDetail(u: String): Pair<String, JSONObject?> {
            val fbIds = listOf(
                "1ecf0bf45eb04ff8b6445c3a36a3966a",
                "0123456789abcdef",
                "1234567890123456",
                "abc123def4567890",
                "0000000000000000",
                "94c24a0bc4fb8d34",
                "cf95dc53f383f9a8",
                "4032af8d61035123",
                "f60ed56a9c827589",
                "263a4dbe41488fb8",
                "ce30d1c21815f7b9",
                "d7462c26174b077c",
                "d41d8cd98f00b204e9800998ecf8427e",
                "ffffffffffffffffffffffffffffffff"
            )
            val cfIds = listOf(
                "abcdefghijklmnop0123456789ABCDEF",
                "0123456789abcdef0123456789ABCDEF"
            )
            for (fid in fbIds) {
                for (cf in cfIds) {
                    try {
                        val h = mapOf(
                            "User-Agent" to "okhttp/4.10.0",
                            "Accept" to "application/json, text/plain, */*",
                            "firebase_id" to fid,
                            "cloudflare-id" to cf
                        )
                        val t = app.get(u, headers = h).text
                        val j = safeJson(t)
                        if (j != null) return Pair(t, j)
                    } catch (e: Exception) {
                    }
                }
            }
            try {
                val t = app.get(u, headers = getHeaders()).text
                val j = safeJson(t)
                if (j != null) return Pair(t, j)
            } catch (e: Exception) {
            }
            try {
                val h2 = mapOf(
                    "User-Agent" to "okhttp/4.10.0",
                    "Accept" to "application/json, text/plain, */*"
                )
                val t = app.get(u, headers = h2).text
                val j = safeJson(t)
                if (j != null) return Pair(t, j)
            } catch (e: Exception) {
            }
            try {
                val h3 = mapOf(
                    "User-Agent" to "okhttp/4.10.0",
                    "Accept" to "application/json, text/plain, */*",
                    "cloudflare-id" to "abcdefghijklmnop0123456789ABCDEF"
                )
                val t = app.get(u, headers = h3).text
                return Pair(t, safeJson(t))
            } catch (e: Exception) {
                return Pair("", null)
            }
        }

        var base = normalizeUrl(url)
        var fetch = fetchDetail(base)
        var jsonText: String = fetch.first
        var json: JSONObject? = fetch.second

        // Try alternate series/serie paths
        if (json == null) {
            val alt = when {
                base.contains("/series/") -> base.replace("/series/", "/serie/")
                base.contains("/serie/") -> base.replace("/serie/", "/series/")
                else -> base
            }
            if (alt != base) {
                fetch = fetchDetail(alt)
                jsonText = fetch.first
                json = fetch.second
                if (json != null) {
                    base = alt
                }
            }
        }

        if (json == null) {
            throw Exception("Invalid response")
        }
        if (json.optBoolean("blocked", false)) {
            // Don't throw - try to continue; some endpoints may return blocked but still be usable
        }

        if (url.contains("/episode/")) {
            val ep = json.optJSONObject("episode") ?: JSONObject()
            val series = ep.optJSONObject("series") ?: JSONObject()
            val seriesId = series.optString("id")
            val epsUrl = "$mainUrl/series/$seriesId/episodes"
            var epsText = ""
            var epsJson: JSONObject? = null
            // try with various headers
            try {
                epsText = app.get(epsUrl, headers = getHeaders()).text
                epsJson = safeJson(epsText)
            } catch (e: Exception) {}
            if (epsJson == null) {
                try {
                    epsText = app.get(epsUrl, headers = mapOf("User-Agent" to "okhttp/4.10.0","Accept" to "application/json")).text
                    epsJson = safeJson(epsText)
                } catch (e: Exception) {}
            }
            if (epsJson == null) epsJson = JSONObject()
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
                name.ifEmpty { id },
                "$mainUrl/movie/$id",
                TvType.Movie,
                "$mainUrl/movie/$id/servers"
            ) {
                this.posterUrl = poster.ifEmpty { backdrop }
                this.plot = overview
            }
        } else {
            // Fallback if detail is blocked/missing
            if (name.isEmpty() && poster.isEmpty()) {
                // Try to fetch episodes directly
                var epsText2 = ""
                var epsJson2: JSONObject? = null
                try {
                    epsText2 = app.get("$mainUrl/series/$id/episodes", headers = mapOf("User-Agent" to "okhttp/4.10.0","Accept" to "application/json")).text
                    epsJson2 = safeJson(epsText2)
                } catch (e: Exception) {}
                if (epsJson2 == null) {
                    try {
                        epsText2 = app.get("$mainUrl/serie/$id/episodes", headers = mapOf("User-Agent" to "okhttp/4.10.0","Accept" to "application/json")).text
                        epsJson2 = safeJson(epsText2)
                    } catch (e: Exception) {}
                }
                if (epsJson2 == null) epsJson2 = JSONObject()
                val seasons = epsJson2.optJSONArray("seasons") ?: JSONArray()
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
                var showName = id
                try {
                    val firstSeason = epsJson2.optJSONArray("seasons")?.optJSONObject(0)
                    val firstEp = firstSeason?.optJSONArray("episodes")?.optJSONObject(0)
                    val seriesObj = firstEp?.optJSONObject("series")
                    if (seriesObj != null && seriesObj.optString("name").isNotEmpty()) {
                        showName = seriesObj.optString("name")
                    }
                } catch (e: Exception) {}
                return newTvSeriesLoadResponse(showName, "$mainUrl/serie/$id", TvType.TvSeries, episodes)
            }
            var epsText2 = ""
            var epsJson2: JSONObject? = null
            try {
                epsText2 = app.get("$mainUrl/serie/$id/episodes", headers = getHeaders()).text
                epsJson2 = safeJson(epsText2)
            } catch (e: Exception) {}
            if (epsJson2 == null) {
                try {
                    epsText2 = app.get("$mainUrl/serie/$id/episodes", headers = mapOf("User-Agent" to "okhttp/4.10.0","Accept" to "application/json")).text
                    epsJson2 = safeJson(epsText2)
                } catch (e: Exception) {}
            }
            if (epsJson2 == null) epsJson2 = JSONObject()
            val epsJson = epsJson2
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
        val attempts = mutableListOf<Map<String,String>>()
        attempts.add(getHeaders())
        listOf("0123456789abcdef","1234567890123456","94c24a0bc4fb8d34","f60ed56a9c827589","1ecf0bf45eb04ff8b6445c3a36a3966a").forEach { fid ->
            attempts.add(mapOf("User-Agent" to "okhttp/4.10.0", "Accept" to "application/json, text/plain, */*", "firebase_id" to fid))
        }
        attempts.add(mapOf("User-Agent" to "okhttp/4.10.0", "Accept" to "application/json, text/plain, */*"))
        attempts.add(mapOf("User-Agent" to "okhttp/4.10.0", "Accept" to "application/json"))
        for (h in attempts) {
            try {
                val text = app.get(data, headers = h, allowRedirects = true).text
                val json = safeJson(text) ?: continue
                val servers = json.optJSONArray("servers") ?: JSONArray()
                for (i in 0 until servers.length()) {
                    val srv = servers.getJSONObject(i)
                    val link = srv.optString("link")
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
                if (servers.length() > 0) return true
            } catch (e: Exception) {
            }
        }
        return false
    }
}
