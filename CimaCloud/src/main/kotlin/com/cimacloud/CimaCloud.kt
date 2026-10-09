package com.cimacloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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

    private val headers = mapOf(
        "User-Agent" to "okhttp/4.10.0",
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val home = app.get("$mainUrl/home", headers = headers).text
            val json = JSONObject(home)
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
                    val tvType = when (type) {
                        "serie", "series", "tv", "tvshow" -> TvType.TvSeries
                        "anime" -> TvType.Anime
                        "cartoon" -> TvType.Cartoon
                        else -> TvType.Movie
                    }
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
            val results = mutableListOf<SearchResponse>()
            val encoded = URLEncoder.encode(query, "UTF-8")
            val res = app.post(
                "$mainUrl/search",
                headers = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("title" to query, "type" to "2", "sort" to "1", "page" to "1")
            ).text
            val json = JSONObject(res)
            val arr = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.optString("name")
                val type = item.optString("type", "movie")
                val poster = item.optString("poster")
                val year = item.optString("year", "0").toIntOrNull()
                val tvType = when (type) {
                    "serie", "series" -> TvType.TvSeries
                    "anime" -> TvType.Anime
                    else -> TvType.Movie
                }
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
}
