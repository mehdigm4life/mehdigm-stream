package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder

class Shahid4u : MainAPI() {
    override var mainUrl = "https://shaheid4u.name/"
    override var name = "شاهد فور يو (Shahid4u)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val logTag = "Shahid4uProvider"
    private var resolvedReferer: String? = null

    private val TRANSPARENT_PNG_DATA_URI =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller
    private fun encodeUri(url: String): String {
        return try {
            url.toCharArray().joinToString("") { char ->
                if (char.code <= 127) char.toString() else URLEncoder.encode(
                    char.toString(),
                    "UTF-8"
                )
            }
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun buildBrowserHeaders(referer: String? = null): Map<String, String> {
        val ref = referer ?: resolvedReferer ?: mainUrl
        val safeRef = encodeUri(ref) // <-- تنظيف الرابط هنا

        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to safeRef, // <-- استخدام الرابط الآمن
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun posterheader(referer: String? = null): Map<String, String> {
        val ref = referer ?: resolvedReferer ?: mainUrl
        val safeRef = encodeUri(ref) // <-- تنظيف الرابط هنا

        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to safeRef, // <-- استخدام الرابط الآمن
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun buildMergedHeaders(url: String, referer: String? = null): Map<String, String> {
        val base = buildBrowserHeaders(referer).toMutableMap()

        return try {
            val cloudHeaders = cloudflareKiller.getCookieHeaders(url).toMultimap()
                .mapValues { entry -> entry.value.joinToString("; ") }
            base.putAll(cloudHeaders)
            base
        } catch (e: Exception) {
            Log.w(logTag, "buildMergedHeaders -> failed to get cloudflare headers: ${e.message}")
            base
        }
    }

    private fun makeAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val p = url.trim()
        return when {
            p.startsWith("http://", true) || p.startsWith("https://", true) -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> mainUrl.trimEnd('/') + p
            else -> {
                mainUrl + p
            }
        }
    }

    private suspend fun httpGet(url: String, referer: String? = null): org.jsoup.nodes.Document {
        val headers = buildMergedHeaders(url, referer)
        val safeRef = encodeUri(referer ?: mainUrl) // <-- تنظيف الرابط هنا

        val response = app.get(
            url,
            referer = safeRef, // <-- استخدام الرابط الآمن
            headers = headers,
            interceptor = cfInterceptor
        )
        if (resolvedReferer == null) {
            val finalUrl = response.url
            val match = Regex("^(https?://[^/]+/)").find(finalUrl)
            resolvedReferer = match?.value ?: mainUrl
            Log.d(logTag, "تم التقاط الرابط النهائي للصور (Referer): $resolvedReferer")
        }

        return response.document
    }

    private fun parseCard(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.show-card, a.glide_post, a.glide-item, a")
            ?: return null

        val href = linkElement.attr("href").ifBlank { linkElement.absUrl("href") }
        if (href.isBlank()) return null

        val mainTitle = element.selectFirst("p.title")?.text()?.trim()
            ?: linkElement.selectFirst("p.title")?.text()?.trim()
        val description = element.selectFirst("p.description")?.text()?.trim()
        val title = if (!mainTitle.isNullOrBlank()) {
            if (!description.isNullOrBlank()) "$mainTitle - $description" else mainTitle
        } else {
            element.selectFirst("div.card-content")?.text()?.trim()
                ?: element.selectFirst("h3")?.text()?.trim()
                ?: element.selectFirst("img")?.attr("alt")?.trim()
        }
        if (title.isNullOrBlank()) return null

        val posterStyle = linkElement.attr("style")
        var posterUrl = Regex("""(?:--background-image-url|background-image)\s*:\s*url\(['"]?(.*?)['"]?\)""")
            .find(posterStyle)?.groupValues?.get(1)
        if (posterUrl.isNullOrBlank()) posterUrl = Regex("""url\(['"]?(.*?)['"]?\)""")
            .find(posterStyle)?.groupValues?.get(1)
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("src")
        posterUrl = makeAbsoluteUrl(posterUrl) ?: TRANSPARENT_PNG_DATA_URI

        val isTvSeries = href.contains("/episode/") || href.contains("/series/") ||
                href.contains("/season/") || element.selectFirst(".ep_num, .ep, .الحلقة") != null

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheader()
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheader()
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isNotEmpty()) {
            val categoryUrl = "${request.data}?page=$page"
            val document = httpGet(categoryUrl, referer = mainUrl)
            val items = document.select("div.shows-container.row div[class*=col-]").mapNotNull {
                parseCard(it)
            }
            val hasNext =
                document.selectFirst("ul.pagination li.page-item.active + li.page-item a") != null
            return newHomePageResponse(request.name, items, hasNext)
        }

        if (page > 1) return newHomePageResponse(emptyList())

        val homePageList = mutableListOf<HomePageList>()
        val document = httpGet(mainUrl, referer = mainUrl)

        try {
            val sliderItems =
                document.select("div.glide li.glide__slide:not(.glide__slide--clone)").mapNotNull {
                    parseCard(it)
                }
            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("أبرز العروض", sliderItems))
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error parsing slider items: ${e.message}")
        }

        val categories = listOf(
            "مسلسلات أجنبي" to "${mainUrl}category/مسلسلات-اجنبي",
            "مسلسلات عربي" to "${mainUrl}category/مسلسلات-عربي",
            "مسلسلات تركية" to "${mainUrl}category/مسلسلات-تركية",
            "مسلسلات انمي" to "${mainUrl}category/مسلسلات-انمي",
        )

        for ((title, url) in categories) {
            try {
                val doc = httpGet(url, referer = mainUrl)
                val items =
                    doc.select("div.shows-container.row div[class*=col-]").take(40).mapNotNull {
                        parseCard(it)
                    }
                if (items.isNotEmpty()) homePageList.add(HomePageList(title, items, true))
            } catch (e: Exception) {
                Log.e(logTag, "Failed to load category '$title': ${e.message}")
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "${mainUrl}search?s=$encoded"

        return try {
            val document = httpGet(searchUrl, referer = mainUrl)
            val resultItems = document.select("div.shows-container.row div[class*=col-]")

            if (resultItems.isEmpty()) return emptyList()

            resultItems.mapIndexedNotNull { index, element ->
                try {
                    parseCard(element)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = httpGet(url)

        val title = document.selectFirst("span.title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.title().trim().ifBlank { null }
            ?: "غير متوفر"
        val posterStyle = document.selectFirst("div.poster-side div.poster")?.attr("style").orEmpty()
        val poster = Regex("""(?:--background-image-url|background-image)\s*:\s*url\(['"]?(.*?)['"]?\)""")
            .find(posterStyle)?.groupValues?.get(1)
            ?: document.selectFirst("div.poster-side img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("span.description")?.text()?.trim()
            ?: document.selectFirst("div.description")?.text()?.trim()
        val tags = document.select("span.qualities a, div.qualities a, a.btn.btn-gray")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val isAnime = url.contains("انمي") || title.contains("انمي") ||
                url.contains("anime", ignoreCase = true)

        val base = url.trimEnd('/')
        val episodes: List<Episode> = when {
            url.contains("/series/") -> collectSeriesEpisodes(base, poster)

            url.contains("/season/") -> {
                val seasonNumber = parseSeasonNumber(title, url)
                collectSeasonEpisodes(base, seasonNumber, poster)
            }

            url.contains("/episode/") -> {
                val seriesLink = document.selectFirst("a[href*='/series/']")
                    ?.let { absHref(it) }
                val fromSeries = if (!seriesLink.isNullOrBlank()) {
                    collectSeriesEpisodes(seriesLink.trimEnd('/'), poster)
                } else emptyList()

                if (fromSeries.isNotEmpty()) fromSeries
                else {
                    val episodeNumber = Regex("""(?:الحلقة|حلقة|Episode)\s*([0-9٠-٩]+)""")
                        .find(title)?.groupValues?.get(1)?.let { toIntLoose(it) }
                    listOf(newEpisode(url) {
                        this.name = title
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    })
                }
            }

            else -> emptyList()
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (sortedEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = posterheader()
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = posterheader()
                this.plot = plot
                this.tags = tags
            }
        }
    }

    /**
     * يجمع كل حلقات المسلسل عبر صفحة المواسم [seriesUrl]/seasons ثم [seasonUrl]/episodes.
     */
    private suspend fun collectSeriesEpisodes(seriesUrl: String, poster: String?): List<Episode> {
        val seasonsDoc = httpGet(seriesUrl.trimEnd('/') + "/seasons", referer = seriesUrl)
        val seasonAnchors = seasonsDoc.select("a.show-card[href*='/season/']").ifEmpty {
            seasonsDoc.select("a[href*='/season/']")
        }
        val seen = HashSet<String>()
        val seasonCards = seasonAnchors.filter { a ->
            val h = absHref(a) ?: return@filter false
            seen.add(h)
        }

        val perSeason = seasonCards.amap { a ->
            val seasonUrl = absHref(a) ?: return@amap emptyList()
            val seasonTitle = a.selectFirst("p.title")?.text()?.trim() ?: a.text().trim()
            val seasonNumber = parseSeasonNumber(seasonTitle, seasonUrl)
            collectSeasonEpisodes(seasonUrl, seasonNumber, poster)
        }
        return perSeason.flatten()
    }

    /**
     * يجمع حلقات موسم واحد عبر صفحة [seasonUrl]/episodes.
     */
    private suspend fun collectSeasonEpisodes(
        seasonUrl: String,
        seasonNumber: Int?,
        poster: String?
    ): List<Episode> {
        val doc = httpGet(seasonUrl.trimEnd('/') + "/episodes", referer = seasonUrl)
        val anchors = doc.select("a.show-card[href*='/episode/']").ifEmpty {
            doc.select("a[href*='/episode/']")
        }
        val seen = HashSet<String>()
        return anchors.mapNotNull { a ->
            val epUrl = absHref(a) ?: return@mapNotNull null
            if (!seen.add(epUrl)) return@mapNotNull null
            val epTitle = a.selectFirst("p.title")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
            val name = epTitle?.takeIf { it.isNotBlank() } ?: "حلقة"
            val episodeNumber = Regex("""(?:الحلقة|حلقة|Episode)\s*([0-9٠-٩]+)""")
                .find(name)?.groupValues?.get(1)?.let { toIntLoose(it) }
            newEpisode(epUrl) {
                this.name = name
                this.episode = episodeNumber
                this.season = seasonNumber
                this.posterUrl = poster
            }
        }
    }

    private fun absHref(a: Element?): String? {
        if (a == null) return null
        val abs = a.absUrl("href")
        return when {
            abs.isNotBlank() -> abs
            else -> makeAbsoluteUrl(a.attr("href"))
        }
    }

    private fun parseSeasonNumber(title: String?, url: String): Int? {
        val text = "${title.orEmpty()} $url"
        Regex("""(?:الموسم|season)\s*([0-9٠-٩]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.let { return toIntLoose(it) }
        val ordinals = listOf(
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
            "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9,
            "العاشر" to 10
        )
        for ((k, v) in ordinals) if (text.contains(k)) return v
        return null
    }

    private fun toIntLoose(value: String): Int? {
        val normalized = value.map { c -> if (c in '\u0660'..'\u0669') ('0' + (c - '\u0660')) else c }
            .joinToString("")
        return normalized.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = toWatchUrl(data)
        val browserHeaders = buildBrowserHeaders(watchUrl)

        val servers: List<ServerEntry>
        var cookies: Map<String, String> = emptyMap()
        try {
            val watchResponse = app.get(
                watchUrl,
                headers = browserHeaders,
                interceptor = cfInterceptor,
                timeout = 60L
            )
            cookies = watchResponse.cookies
            servers = parseServers(watchResponse.text)
        } catch (e: Exception) {
            Log.e(logTag, "loadLinks -> failed to fetch watch page $watchUrl: ${e.message}")
            return false
        }

        if (servers.isEmpty()) {
            Log.e(logTag, "loadLinks -> no servers found on $watchUrl")
            return false
        }

        val results = servers.amap { server ->
            try {
                val mUrl = makeAbsoluteUrl(server.url) ?: return@amap false
                val resp = app.get(
                    mUrl,
                    headers = browserHeaders,
                    cookies = cookies,
                    interceptor = cfInterceptor,
                    timeout = 60L
                )
                val finalUrl = resp.url
                if (finalUrl.isBlank() || sameHost(finalUrl, mainUrl)) {
                    Log.w(logTag, "server '${server.name}' did not resolve (final=$finalUrl)")
                    return@amap false
                }
                emitServerLink(server.name, finalUrl, watchUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.w(logTag, "server '${server.name}' failed: ${e.message}")
                false
            }
        }
        return results.any { it }
    }

    private fun toWatchUrl(url: String): String = url
        .replace("/film/", "/watch/")
        .replace("/episode/", "/watch/")
        .replace("/series/", "/watch/")
        .replace("/season/", "/watch/")
        .replace("/download/", "/watch/")

    private fun sameHost(a: String, b: String): Boolean {
        val ha = runCatching { URI(a).host }.getOrNull() ?: return false
        val hb = runCatching { URI(b).host }.getOrNull() ?: return false
        return ha.equals(hb, ignoreCase = true)
    }

    private data class ServerEntry(val name: String, val url: String, val rank: Int)

    /**
     * استخراج مصفوفة السيرفرات `let servers = [...]` من صفحة المشاهدة.
     * كل عنصر: {"name":"earnvids","url":"/m/<hash>","rank":2,...}
     */
    private fun parseServers(html: String): List<ServerEntry> {
        val out = LinkedHashMap<String, ServerEntry>()
        val cleaned = html
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")

        val blockRegex = Regex("""(?:let|var|const)\s+servers\s*=\s*(\[[\s\S]*?\])\s*;""")
        val block = blockRegex.find(cleaned)?.groupValues?.get(1)

        if (block != null) {
            try {
                val array = JSONArray(block.replace("\\/", "/"))
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").ifBlank { obj.optString("src") }
                    if (url.isBlank()) continue
                    val name = obj.optString("name").ifBlank { "Server ${i + 1}" }
                    val rank = obj.optInt("rank", i)
                    if (!isCanaryServer(rank, name, url)) out[url] = ServerEntry(name, url, rank)
                }
            } catch (e: Exception) {
                Log.w(logTag, "parseServers -> JSON parse failed: ${e.message}")
            }
        }

        if (out.isEmpty()) {
            Regex("""["']url["']\s*:\s*["']((?:\\.|[^"'\\])+)["']""")
                .findAll(cleaned)
                .forEachIndexed { index, m ->
                    val u = m.groupValues[1].replace("\\/", "/")
                    if (u.isNotBlank()) out[u] = ServerEntry("Server ${index + 1}", u, index)
                }
        }

        return out.values.sortedBy { it.rank }
    }

    /**
     * فحص إدخالات سيرفرات الفخ (canary) التي يضيفها الموقع ولا تمثل سيرفراً حقيقياً.
     */
    private fun isCanaryServer(rank: Int, name: String?, url: String?): Boolean {
        if (rank >= 900000) return true
        val n = (name ?: "").lowercase()
        if (n.contains("backup") || n.contains("mirror") || n.contains("cdn player")) return true
        val u = (url ?: "").lowercase()
        return u.contains("/media/watch/") || u.contains("/media/api/") || u.contains("/media/page/")
    }

    /**
     * إرسال رابط السيرفر النهائي: رابط m3u8/mp4 مباشر، أو تمريره إلى loadExtractor
     * (مثل fastvid.cam عبر ExternalEarnVidsExtractor).
     */
    private suspend fun emitServerLink(
        serverName: String,
        target: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lower = target.lowercase()
        val isM3u8 = lower.contains(".m3u8") || lower.contains("/hls/")
        val isVideo = lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".webm")
        if (isM3u8 || isVideo) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = serverName.ifBlank { "مباشر" },
                    url = target,
                ) {
                    this.referer = referer
                    this.quality = -1
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            return true
        }
        return loadExtractor(target, referer, subtitleCallback, callback)
    }
}