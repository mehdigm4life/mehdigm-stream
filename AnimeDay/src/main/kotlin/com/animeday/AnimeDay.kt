package com.animeday

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.anime.day.Utils.NativeLib
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

@SuppressLint("HardwareIds")
class AnimeDay : MainAPI() {
    companion object {
        @Volatile
        var appContext: Context? = null

        private const val UA = "okhttp/4.10.0"
        private const val REFERER = "https://www.anime-day.com/"
        private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val DEFAULT_API = "https://20042026.site/v3.8/api"
        private const val CONFIG_API = "https://backup-animeday.animegateshorts.workers.dev"
        private const val GP_WATCH_SUFFIX = "=m18"
        private val GP_QUALITIES = listOf(
            Triple("=m37", 1080, "1080p"),
            Triple("=m22", 720, "720p"),
            Triple("=m18", 360, "360p")
        )

        internal fun randomString(len: Int): String {
            val sb = StringBuilder(len)
            for (i in 0 until len) sb.append(ALPHANUM[Random.nextInt(ALPHANUM.length)])
            return sb.toString()
        }

        private fun safeJson(text: String?): JSONObject? {
            return try {
                if (text.isNullOrBlank()) return null
                val t = text.trim()
                if (!t.startsWith("{")) return null
                JSONObject(t)
            } catch (_: Throwable) {
                null
            }
        }
    }

    override var lang = "ar"
    override var mainUrl = DEFAULT_API
    override var name = "AnimeDay"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AnimeMovie
    )

    @Volatile
    private var resolvedApi: String? = null

    private suspend fun resolveApi(): String {
        resolvedApi?.let { return it }
        val fetched = tryFetchCloudday()
        if (fetched != null) {
            synchronized(this) {
                if (resolvedApi == null) resolvedApi = fetched
            }
            return fetched
        }
        return DEFAULT_API
    }

    private suspend fun tryFetchCloudday(): String? {
        return try {
            val body = app.get(CONFIG_API).text
            val obj = safeJson(body) ?: return null
            val data = obj.optJSONArray("data")
            val first = data?.optJSONObject(0)
            val base = first?.optString("cloudday").orEmpty()
                .ifEmpty { obj.optJSONObject("data")?.optString("cloudday").orEmpty() }
            base.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { it.trimEnd('/') + "/v3.8/api" }
        } catch (_: Throwable) {
            null
        }
    }

    private fun plainHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*"
    )

    private fun androidId(): String {
        val ctx = appContext
        val id = try {
            if (ctx != null) Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) else null
        } catch (_: Throwable) {
            null
        }
        return if (id.isNullOrEmpty()) randomString(16) else id
    }

    private fun freshToken(): String? {
        val ctx = appContext ?: return null
        return try {
            android.util.Log.i("AnimeDayNative", "freshToken: calling buildSecure")
            val t = NativeLib.secureId(ctx)
            android.util.Log.i("AnimeDayNative", "freshToken: got token len=" + (t?.length ?: -1) + " prefix=" + t?.take(24))
            t
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayNative", "freshToken: threw " + e)
            null
        }
    }

    /** Headers used for the encrypted /servers endpoints. Returns the cloudflare-id too. */
    private fun serverHeaders(): Pair<Map<String, String>, String> {
        val cf = randomString(15) + androidId()
        val headers = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "cloudflare-id" to cf
        )
        freshToken()?.let { headers["firebase_id"] = it }
        return headers to cf
    }

    private fun toAsciiDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** The API does not expose episode numbers, so parse them from the title (e.g. "الحلقة 8"). */
    private fun episodeNumber(title: String?, fallback: Int): Int {
        if (title.isNullOrBlank()) return fallback
        val match = Regex("(\\d{1,4})").find(toAsciiDigits(title)) ?: return fallback
        return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: fallback
    }

    private fun normalizeType(t: String?): TvType = when (t) {
        "serie", "series", "tv", "tvshow" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "cartoon" -> TvType.Cartoon
        else -> TvType.Movie
    }

    private suspend fun detailPath(type: String?, id: String): String =
        if (normalizeType(type) == TvType.Movie) "${resolveApi()}/movie/$id" else "${resolveApi()}/series/$id"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = mutableListOf<com.lagradost.cloudstream3.HomePageList>()
        try {
            val json = safeJson(app.get("${resolveApi()}/home", headers = plainHeaders()).text)
            val sections = json?.optJSONArray("sections") ?: JSONArray()
            for (i in 0 until sections.length()) {
                val sec = sections.optJSONObject(i) ?: continue
                val items = sec.optJSONArray("section_items") ?: JSONArray()
                val list = mutableListOf<SearchResponse>()
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val type = item.optString("type", "movie")
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    if (type == "episode") {
                        val showId = item.optString("tv_show_id")
                        if (showId.isEmpty()) continue
                        list.add(
                            newMovieSearchResponse(
                                name = item.optString("tv_show_name").ifEmpty { item.optString("name") },
                                url = "${resolveApi()}/series/$showId",
                                type = TvType.TvSeries
                            ) {
                                this.posterUrl = item.optString("poster").ifEmpty { null }
                            }
                        )
                    } else {
                        val tvType = normalizeType(type)
                        list.add(
                            newMovieSearchResponse(
                                name = item.optString("name"),
                                url = detailPath(type, id),
                                type = tvType
                            ) {
                                this.posterUrl = item.optString("poster").ifEmpty { null }
                                this.year = item.optString("release_date").take(4).toIntOrNull()
                            }
                        )
                    }
                }
                if (list.isNotEmpty()) pages.add(com.lagradost.cloudstream3.HomePageList(sec.optString("section_name"), list))
            }
        } catch (_: Throwable) {
        }
        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val json = safeJson(
                app.post(
                    "${resolveApi()}/search",
                    headers = plainHeaders() + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("title" to query, "type" to "0", "sort" to "1", "page" to "1")
                ).text
            ) ?: return emptyList()
            val arr = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val id = item.optString("id")
                if (id.isEmpty()) continue
                val tvType = normalizeType(type)
                results.add(
                    newMovieSearchResponse(
                        name = item.optString("name"),
                        url = detailPath(type, id),
                        type = tvType
                    ) {
                        this.posterUrl = item.optString("poster").ifEmpty { null }
                        this.year = item.optString("year", "0").toIntOrNull()
                    }
                )
            }
        } catch (_: Throwable) {
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val clean = url.trimEnd('/')
        val id = clean.substringAfterLast("/").substringBefore("?")
        val isSeries = clean.contains("/series/") || clean.contains("/serie/") ||
                clean.contains("/tv/") || clean.contains("/episode/")

        return if (isSeries) loadSeries(clean, id) else loadMovie(clean, id)
    }

    private suspend fun loadSeries(url: String, id: String): LoadResponse {
        var showId = id
        if (url.contains("/episode/")) {
            // legacy episode detail URL: resolve its series id when possible
            val ep = fetchJson("${resolveApi()}/episode/$id", withToken = true)
            showId = ep?.optJSONObject("episode")?.optJSONObject("series")?.optString("id").orEmpty().ifEmpty { id }
        }

        val epsJson = fetchJson("${resolveApi()}/series/$showId/episodes", withToken = false)
            ?: fetchJson("${resolveApi()}/serie/$showId/episodes", withToken = false)

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val seasons = epsJson?.optJSONArray("seasons") ?: JSONArray()
        for (s in 0 until seasons.length()) {
            val season = seasons.optJSONObject(s) ?: continue
            val seasonNum = season.optString("season_number", "1").toIntOrNull() ?: (s + 1)
            val eps = season.optJSONArray("episodes") ?: JSONArray()
            for (e in 0 until eps.length()) {
                val ei = eps.optJSONObject(e) ?: continue
                val epId = ei.optString("id")
                if (epId.isEmpty()) continue
                val title = ei.optString("title").ifEmpty { ei.optString("name") }
                val explicit = ei.optString("episode_number", "").toIntOrNull()
                episodes.add(
                    newEpisode("${resolveApi()}/episode/$epId/servers") {
                        this.name = title
                        this.season = seasonNum
                        this.episode = explicit?.takeIf { it > 0 } ?: episodeNumber(title, e + 1)
                        this.posterUrl = ei.optString("image").ifEmpty { ei.optString("cover") }.ifEmpty { null }
                    }
                )
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        val detail = fetchJson("${resolveApi()}/series/$showId", withToken = true)?.optJSONObject("series")
        val name = detail?.optString("name").orEmpty().ifEmpty { "مسلسل $showId" }
        val poster = detail?.optString("poster").orEmpty()
        val backdrop = detail?.optString("backdrop").orEmpty()
        val plot = detail?.optString("overview").orEmpty()

        return newTvSeriesLoadResponse(name, "${resolveApi()}/series/$showId", TvType.TvSeries, episodes) {
            this.posterUrl = poster.ifEmpty { backdrop }.ifEmpty { null }
            this.plot = plot.ifEmpty { null }
        }
    }

    private suspend fun loadMovie(url: String, id: String): LoadResponse {
        val movie = fetchJson("${resolveApi()}/movie/$id", withToken = true)?.optJSONObject("movie")
        val name = movie?.optString("name").orEmpty().ifEmpty { "فيلم $id" }
        val poster = movie?.optString("poster").orEmpty()
        val backdrop = movie?.optString("backdrop").orEmpty()
        val plot = movie?.optString("overview").orEmpty()
        return newMovieLoadResponse(name, "${resolveApi()}/movie/$id", TvType.Movie, "${resolveApi()}/movie/$id/servers") {
            this.posterUrl = poster.ifEmpty { backdrop }.ifEmpty { null }
            this.plot = plot.ifEmpty { null }
        }
    }

    private suspend fun fetchJson(endpoint: String, withToken: Boolean): JSONObject? {
        return try {
            val headers = if (withToken) {
                val (h, _) = serverHeaders()
                h
            } else plainHeaders()
            val res = app.get(endpoint, headers = headers)
            val body = res.text
            android.util.Log.i("AnimeDay", "fetchJson GET $endpoint token=$withToken -> ${res.code} len=${body.length} body=${body.take(240)}")
            safeJson(body)?.takeIf { !it.optBoolean("blocked", false) }
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDay", "fetchJson GET $endpoint FAILED: $e")
            null
        }
    }

    /** Google Photos share pages embed the video as <c-wiz data-url="https://lh3.googleusercontent.com/pw/<TOKEN>" ...>. */
    private fun gphotosBase(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val re = Regex("data-url=\"(https://lh3\\.googleusercontent\\.com/pw/[^\"]+)\"")
        return re.find(html)?.groupValues?.get(1)
    }

    /** Headers the original app uses for the Google Photos share page (mirrors the app config). */
    private fun gpHtmlHeaders() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    /** Call one of the app's PHP extractors (extractor/html/advanced) with ?url= and emit the returned playable + download links. */
    private suspend fun extractPhpServers(
        endpoint: String,
        pageUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = plainHeaders() + mapOf("Referer" to referer, "Accept-Language" to "ar,en;q=0.9")
            val json = safeJson(app.get(endpoint + "?url=" + Uri.encode(pageUrl), headers = headers).text) ?: return false
            val servers = json.optJSONArray("servers") ?: return false
            var emitted = false
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val u = s.optString("url").trim()
                val ref = s.optString("referer").ifEmpty { s.optString("origin") }.ifEmpty { referer }
                val labelName = s.optString("name").ifEmpty { label }
                val q = s.optString("height").toIntOrNull() ?: quality
                val gToken = gphotosToken(u)
                if (gToken != null) {
                    emitted = true
                    emitGphotoLinks(gToken, labelName, callback)
                } else if (u.isNotEmpty() && seen.add(u)) {
                    emitted = true
                    when {
                        u.contains(".m3u8") -> {
                            for (v in M3u8Helper.generateM3u8(labelName, u, ref)) {
                                seen.add(v.url)
                                callback(v)
                            }
                        }
                        u.contains(".mpd") -> callback(
                            newExtractorLink(labelName, labelName, u, ExtractorLinkType.DASH) {
                                this.referer = ref
                                if (q != null) this.quality = q
                            }
                        )
                        else -> callback(
                            newExtractorLink(labelName, labelName, u, ExtractorLinkType.VIDEO) {
                                this.referer = ref
                                if (q != null) this.quality = q
                            }
                        )
                    }
                }
                val dl = s.optString("url_download").trim()
                if (gToken == null && dl.isNotEmpty() && seen.add(dl)) {
                    emitted = true
                    callback(
                        newExtractorLink("$labelName (تحميل)", labelName + " (download)", dl, ExtractorLinkType.VIDEO) {
                            this.referer = ref
                        }
                    )
                }
            }
            emitted
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDay", "extractPhpServers FAILED $endpoint : $e")
            false
        }
    }

    /** Mirror the app's extractor chain for ANY source page: PhpExtractor, then HtmlSenderExtractor, then advanced. Merges ALL links (dedup by URL). */
    private suspend fun resolvePhpMulti(
        pageUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        seen: MutableSet<String>,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (endpoint in listOf(
            "https://cloud-day.online/cimacloud/extractor.php",
            "https://cloud-day.online/cimacloud/html_extractor.php",
            "https://cloud-day.online/cimacloud/advanced_extractor.php"
        )) {
            if (extractPhpServers(endpoint, pageUrl, referer, label, quality, seen, callback)) found = true
        }
        return found
    }

    /** Strip any =<suffix> / =mm,... from a Google Photos URL to get its streamable token. */
    private fun gphotosToken(url: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        val base = u.substringBefore("=").trim()
        return base.takeIf { it.startsWith("https://") && it.contains("googleusercontent.com") && it.contains("/pw/") }
    }

    private suspend fun emitGphotoLinks(base: String, label: String, emit: (ExtractorLink) -> Unit) {
        for ((suffix, q, qname) in GP_QUALITIES) {
            emit(
                newExtractorLink(label, "$label ($qname)", base + suffix, ExtractorLinkType.VIDEO) {
                    this.quality = q
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val (headers, cf) = serverHeaders()
            android.util.Log.i("AnimeDay", "loadLinks GET $data cf=$cf")
            val res = app.get(data, headers = headers, allowRedirects = true)
            val raw = res.text
            android.util.Log.i("AnimeDay", "loadLinks -> ${res.code} len=${raw.length} body=${raw.take(240)}")
            if (raw.isBlank()) return false

            val decrypted = if (raw.trim().startsWith("{") || raw.trim().startsWith("[")) {
                raw
            } else {
                val ctx = appContext
                val keyArg = cf.substring(15)
                val out = if (ctx != null) NativeLib.decrypt(ctx, raw.trim(), keyArg) else null
                out ?: raw
            }
            android.util.Log.i("AnimeDay", "loadLinks decrypted len=${decrypted.length} head=${decrypted.take(240)}")

            val json = safeJson(decrypted) ?: run {
                android.util.Log.i("AnimeDay", "loadLinks: JSON parse failed")
                return false
            }
            val servers = json.optJSONArray("servers") ?: JSONArray()
            android.util.Log.i("AnimeDay", "loadLinks: status=${json.optString("status")} servers=${servers.length()} keys=${json.keys().asSequence().toList()}")
            val seen = mutableSetOf<String>()
            val emit: (ExtractorLink) -> Unit = { link -> if (seen.add(link.url)) callback(link) }
            for (i in 0 until servers.length()) {
                val srv = servers.optJSONObject(i) ?: continue
                val raw = srv.optString("link").trim().replace("\\s+".toRegex(), "")
                if (raw.isEmpty()) continue

                val link = if (raw.startsWith("http")) {
                    raw
                } else {
                    REFERER.trimEnd('/') + "/" + raw.trimStart('/')
                }
                val referer = srv.optString("referer")
                    .ifEmpty { srv.optString("origin") }
                    .ifEmpty { REFERER }
                val label = srv.optString("title")
                    .ifEmpty { srv.optString("name") }
                    .ifEmpty { name }
                val quality = srv.optString("height").toIntOrNull()

                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(label, link, referer).forEach {
                        found = true
                        emit(it)
                    }
                } else if (link.contains(".mp4") || link.contains(".mkv") || link.contains(".mpd")) {
                    found = true
                    emit(
                        newExtractorLink(
                            label,
                            label,
                            link,
                            if (link.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            if (quality != null) this.quality = quality
                        }
                    )
                } else if (link.contains("photos.google.com") || link.contains("googleusercontent.com") || link.contains("googlefinal")) {
                    val target = Regex("[?&]url=([^&]+)").find(link)?.groupValues?.get(1)
                        ?.let { Uri.decode(it) }
                        ?.takeIf { it.contains("photos.google.com") || it.contains("googleusercontent.com") }
                        ?: link
                    val base = if (target.contains("photos.google.com")) {
                        val pageHtml = try {
                            app.get(target, headers = gpHtmlHeaders()).text
                        } catch (_: Throwable) {
                            null
                        }
                        gphotosBase(pageHtml)
                    } else {
                        gphotosToken(target)
                    }
                    android.util.Log.i("AnimeDay", "loadLinks gphotos mirror: base=${base != null} target=${target.take(140)}")
                    if (base != null) {
                        found = true
                        emitGphotoLinks(base, label, emit)
                    }
                } else {
                    try {
                        if (loadExtractor(link, referer, subtitleCallback, emit)) found = true
                    } catch (_: Throwable) {
                    }
                    if (resolvePhpMulti(link, referer, label, quality, seen, subtitleCallback, emit)) {
                        found = true
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return found
    }
}
