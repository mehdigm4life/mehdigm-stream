package com.faselhd

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.resume

class FaselHD(private val context: Context) : MainAPI() {

    override var name = "فاصل إعلاني (FaselHD)"
    override val hasQuickSearch = true
    override var mainUrl = "https://www.fasel-hd.co/"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override var sequentialMainPage = false
    override var sequentialMainPageDelay = 0L

    companion object {
        @Volatile
        var redirectUrl: String? = null
    }

    // ------------------------------------------------------------------
    // قاعدة الطلبات + حل Cloudflare (WebView مخفي)
    // ------------------------------------------------------------------

    private val cfLock = Mutex()
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val httpClient by lazy {
        app.baseClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val noRedirectClient by lazy {
        app.baseClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // الموقع يعيد التوجيه بين مرايا متغيرة باستمرار، فنتتبع الرابط الأساسي مرة واحدة
    private suspend fun baseUrl(): String {
        redirectUrl?.let { return it }
        return try {
            val response = app.get(mainUrl, allowRedirects = true)
            val finalUrl = response.url
            val base = try {
                val uri = java.net.URI(finalUrl)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                mainUrl
            }
            redirectUrl = base
            base
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun getModernHeaders(url: String): MutableMap<String, String> {
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: ""
        return mutableMapOf(
            "Cookie" to cookies,
            "User-Agent" to userAgent,
            "sec-ch-ua" to "\"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "upgrade-insecure-requests" to "1",
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "sec-fetch-site" to "none",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-dest" to "document",
            "accept-language" to "ar-EG,ar;q=0.9",
            "priority" to "u=0, i"
        )
    }

    private fun getProtectedHeaders(): Map<String, String> = getModernHeaders(mainUrl)

    // ثلاث محاولات عادية، ثم حل Cloudflare عبر WebView كحل أخير
    private suspend fun smartGet(url: String, referer: String? = null): Document {
        val cleanUrl =
            if (!url.contains("?") && !url.endsWith("/") && !url.substringAfterLast("/").contains(".")) "$url/" else url

        for (attempt in 1..3) {
            try {
                val headers = getModernHeaders(cleanUrl)
                if (referer != null) headers["Referer"] = referer

                val response = app.get(
                    cleanUrl,
                    headers = headers,
                    timeout = 15L,
                    allowRedirects = true
                )

                if (response.code == 200 || response.code in 300..308) {
                    return response.document
                }
                if (response.code == 429) {
                    delay(1000L * attempt)
                    continue
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    delay(1000L * attempt)
                    continue
                }
            }
        }

        return cfLock.withLock {
            val activity = context as? Activity
            CloudflareSolver.solve(activity, cleanUrl, userAgent) ?: Jsoup.parse("", cleanUrl)
        }
    }

    // طلب POST (مثل admin-ajax) مع إعادة حل Cloudflare تلقائياً عند 403
    private suspend fun executeRequestWithCloudflareRetry(requestBlock: suspend (String) -> String): String? {
        val base = baseUrl()
        var currentCookies = runCatching { CookieManager.getInstance().getCookie(base) }.getOrNull() ?: ""
        try {
            val result = requestBlock(currentCookies)
            if (result.isNotBlank()) return result
        } catch (e: Exception) {
            if (e.message != "403_FORBIDDEN") return null
        }

        cfLock.withLock {
            val activity = context as? Activity
            CloudflareSolver.solve(activity, base, userAgent)
        }
        currentCookies = runCatching { CookieManager.getInstance().getCookie(base) }.getOrNull() ?: ""
        if (!currentCookies.contains("cf_clearance")) return null

        return try {
            requestBlock(currentCookies)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun makeAjaxRequest(
        ajaxUrl: String,
        referer: String,
        formBody: FormBody,
        cookies: String
    ): String {
        var currentUrl = ajaxUrl
        var redirectCount = 0

        while (redirectCount < 5) {
            val requestBuilder = OkRequest.Builder()
                .url(currentUrl)
                .post(formBody)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
            if (cookies.isNotBlank()) requestBuilder.header("Cookie", cookies)

            val response = withContext(Dispatchers.IO) {
                noRedirectClient.newCall(requestBuilder.build()).execute()
            }

            response.use { res ->
                when (res.code) {
                    403 -> throw IllegalStateException("403_FORBIDDEN")
                    301, 302, 307, 308 -> {
                        val location = res.header("Location")
                        if (location != null) {
                            currentUrl = if (location.startsWith("http")) location else "${baseUrl()}$location"
                            redirectCount++
                        } else {
                            return ""
                        }
                    }
                    200 -> return res.body?.string() ?: ""
                    else -> return ""
                }
            }
        }
        return ""
    }

    private fun toAbsolute(url: String?): String? {
        val value = url?.trim() ?: return null
        return when {
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            else -> mainUrl.trimEnd('/') + "/" + value.trimStart('/')
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = toAbsolute(link.attr("href")) ?: return null
        val title = selectFirst(".h1, .h4, .h5, .title")?.text()
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim() ?: return null
        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }?.let { toAbsolute(it) }
        val type = if (href.contains("/movies", ignoreCase = true)) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ------------------------------------------------------------------
    // دمج بطاقات المواسم المبعثرة في بطاقة واحدة لكل مسلسل
    // (كل موسم في الموقع يمثل post مستقل بعنوان "مسلسل X الموسم ...")
    // ------------------------------------------------------------------

    private val seriesTitleMarkers = setOf("موسم", "الموسم", "Season", "season")

    private fun normalizeSeriesTitle(title: String): String {
        val tokens = title.trim().split(Regex("\\s+"))
        val cut = tokens.indexOfFirst { it in seriesTitleMarkers }
        if (cut >= 0) return tokens.take(cut).joinToString(" ").trim()
        return title.trim()
            .replace(Regex("""\s+الحلقة\s+\d+([-–]\s*\d+)?\s*$"""), "")
            .trim()
    }

    private fun groupResults(items: List<SearchResponse>): List<SearchResponse> {
        val grouped = LinkedHashMap<String, SearchResponse>()
        for (item in items) {
            val base = normalizeSeriesTitle(item.name)
            val existing = grouped[base]
            when {
                existing == null -> grouped[base] = item
                existing.name != base && item.name == base -> grouped[base] = item
            }
        }
        return grouped.values.toList()
    }

    // ------------------------------------------------------------------
    // الصفحة الرئيسية + الأقسام
    // ------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/main" to "الرئيسية",
        "$mainUrl/movies" to "أفلام أجنبية",
        "$mainUrl/series" to "مسلسلات أجنبية",
        "$mainUrl/episodes" to "أحدث الحلقات",
        "$mainUrl/anime" to "الأنمي",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/tvshows" to "البرامج",
        "$mainUrl/anime-movies" to "أفلام الأنمي",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندية",
        "$mainUrl/most_recent" to "أحدث الإضافات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data.endsWith("/main")
        val url = when {
            isHome || page <= 1 -> request.data
            else -> request.data.trimEnd('/') + "/page/$page"
        }

        val doc = smartGet(url)

        if (isHome) {
            val lists = mutableListOf<HomePageList>()

            val slides = doc.select("#homeSlide .swiper-slide").mapNotNull { slide ->
                val href = toAbsolute(slide.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val title = slide.selectFirst(".h1 a, .h1")?.text()?.trim() ?: return@mapNotNull null
                val poster = slide.selectFirst(".poster img")?.attr("src")?.let { toAbsolute(it) }
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            if (slides.isNotEmpty()) {
                lists.add(HomePageList("أحدث الإضافات", slides, isHorizontalImages = true))
            }

            doc.select("section#blockList").forEach { block ->
                val title = block.selectFirst(".blockHead .h3")?.text()?.trim() ?: return@forEach
                val items = block.select(".blockMovie, .postDiv, .epDivHome").mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items))
                }
            }

            if (lists.isNotEmpty()) {
                return newHomePageResponse(lists)
            }
        }

        val items = groupResults(
            doc.select(".postDiv, .blockMovie").mapNotNull { it.toSearchResult() }
        )
        val hasNext = doc.select("ul.pagination a[href]")
            .any { it.attr("href").contains("page/${page + 1}") }
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ------------------------------------------------------------------
    // البحث (نفس آلية موقع فاصل)
    // ------------------------------------------------------------------

    private suspend fun liveSearchResults(query: String): List<SearchResponse> {
        return try {
            val ajaxUrl = "${baseUrl()}/wp-admin/admin-ajax.php"
            val formBody = FormBody.Builder()
                .add("action", "dtc_live")
                .add("trsearch", query)
                .build()
            val bodyStr = executeRequestWithCloudflareRetry { cookies ->
                makeAjaxRequest(ajaxUrl, mainUrl, formBody, cookies)
            }
            if (bodyStr.isNullOrBlank()) return emptyList()
            val doc = Jsoup.parse(bodyStr, baseUrl())
            groupResults(
                doc.select("div.postDiv, article, .result, .search-item")
                    .mapNotNull { it.toSearchResult() }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        if (query.trim().length < 3) return emptyList()
        return liveSearchResults(query)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val base = baseUrl()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val originalSearch = if (page == 1) {
            "$base/?s=$encoded"
        } else {
            "$base/page/$page/?s=$encoded"
        }

        var finalSearchUrl = originalSearch
        try {
            val resp = app.get(originalSearch, allowRedirects = true)
            val final = resp.url
            finalSearchUrl =
                if (final.contains("?s=", ignoreCase = true)) final else "$final?s=$encoded"
        } catch (_: Exception) {
            finalSearchUrl = originalSearch
        }

        val document = try {
            smartGet(finalSearchUrl, referer = base)
        } catch (e: Exception) {
            Jsoup.parse("", finalSearchUrl)
        }

        var items = document.select("div#postList div.postDiv, div.postDiv, article")
            .mapNotNull { it.toSearchResult() }
        var hasNext = document.select("ul.pagination a[href*='/page/${page + 1}']").isNotEmpty()

        // إذا فشل /?s= (محمي بـ Cloudflare) نستخدم محرك الموقع الفوري نفسه
        if (items.isEmpty() && page == 1) {
            items = liveSearchResults(query)
        }

        return newSearchResponseList(groupResults(items), hasNext)
    }

    // ------------------------------------------------------------------
    // التفاصيل: فيلم أو مسلسل بمواسمه وحلقاته الحقيقية
    // ------------------------------------------------------------------

    private fun extractSeasonNumber(titleText: String?, fallback: Int): Int {
        val text = titleText ?: return fallback
        Regex("""موسم\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val arabic = mapOf(
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4,
            "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8, "التاسع" to 9, "العاشر" to 10
        )
        Regex("""الموسم\s*(\S+)""").find(text)?.groupValues?.get(1)?.trim()?.let { word ->
            arabic[word]?.let { return it }
        }
        return fallback
    }

    private fun addEpisode(
        el: Element,
        out: MutableList<Episode>,
        season: Int,
        poster: String?
    ) {
        val href = toAbsolute(el.attr("href")) ?: return
        val name = el.ownText().ifBlank { el.text() }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (name.isBlank()) return
        val number = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
        out.add(
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = number
                this.posterUrl = poster
            }
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = toAbsolute(url) ?: return null
        val doc = smartGet(pageUrl)

        val title = doc.selectFirst(".singleInfo h1.title, h1.title")?.ownText()
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?: return null

        val plot = doc.selectFirst(".singleDesc p, .story p")?.text()?.trim()
        val poster = doc.selectFirst("img.poster")?.attr("src")?.let { toAbsolute(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { toAbsolute(it) }
        val tags = doc.select("#singleList a[href]")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
            .distinct()
        val isAnime = pageUrl.contains("/anime", ignoreCase = true) ||
            tags.any { it.contains("انمي", ignoreCase = true) }

        val seasonCards = doc.select("#seasonList .seasonDiv")
        val episodeLinks = doc.select("#epAll a[href]")
        val onClickSeason = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")

        if (seasonCards.isNotEmpty() || episodeLinks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()

            if (seasonCards.isNotEmpty()) {
                // الموسم النشط يُقرأ من الصفحة الحالية، والبقية تُجلب بالتوازي
                // مع الحفاظ على ترتيب المواسم لتجنّب تخليط الأرقام.
                val seasonInfos = seasonCards.mapIndexed { index, seasonEl ->
                    val seasonName = seasonEl.selectFirst(".title")?.text()
                        ?.replace(Regex("\\s+"), " ")?.trim()
                    val seasonNumber = extractSeasonNumber(seasonName, index + 1)
                    Triple(index, seasonNumber, seasonEl.hasClass("active"))
                }

                val passiveDocs = coroutineScope {
                    seasonCards.map { seasonEl ->
                        async {
                            if (seasonEl.hasClass("active")) {
                                null
                            } else {
                                val seasonUrl = onClickSeason.find(seasonEl.attr("onclick"))
                                    ?.groupValues?.get(1)?.let { toAbsolute(it) }
                                if (seasonUrl == null) {
                                    null
                                } else {
                                    try {
                                        smartGet(seasonUrl, referer = pageUrl)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                seasonInfos.forEachIndexed { i, (_, seasonNumber, isActive) ->
                    if (isActive) {
                        doc.select("#epAll a[href]").forEach { addEpisode(it, episodes, seasonNumber, poster) }
                    } else {
                        passiveDocs[i]?.select("#epAll a[href]")
                            ?.forEach { addEpisode(it, episodes, seasonNumber, poster) }
                    }
                }
            } else {
                episodeLinks.forEach { addEpisode(it, episodes, 1, poster) }
            }

            if (episodes.isNotEmpty()) {
                val type = if (isAnime) TvType.Anime else TvType.TvSeries
                return newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ------------------------------------------------------------------
    // روابط المشاهدة
    // ------------------------------------------------------------------

    private fun extractWatchUrls(doc: Document): List<String> {
        val results = mutableSetOf<String>()
        val blocked = listOf("recaptcha", "google.com/ads", "googlesyndication.com", "googletagmanager.com")

        fun add(url: String) {
            val clean = url.replace("&amp;", "&").trim()
            if (clean.isBlank()) return
            if (blocked.any { clean.contains(it) }) return
            results.add(clean)
        }

        val onClick = Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")
        doc.select("[onclick]").forEach { el ->
            onClick.find(el.attr("onclick"))?.let { add(it.groupValues[1]) }
        }

        doc.select("iframe[data-src]").forEach { el ->
            val src = el.attr("data-src")
            if (src.isNotBlank() && (src.contains("video_player") || src.contains("embed") || src.contains("player"))) {
                add(src)
            }
        }
        doc.select("iframe[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank() && (src.contains("video_player") || src.contains("embed") || src.contains("player"))) {
                add(src)
            }
        }

        return results.toList()
    }

    // فك تشفير روابط المشغل الداخلي (بنفس مفاتيح الموقع)
    private val decryptAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/"
    private val decryptKey1 = "V2@%YSU2B]G~"
    private val decryptKey2 = "bv0fim4qf17"

    private fun ie(char: Char): Int = decryptAlphabet.indexOf(char).let { if (it < 0) 0 else it }

    private fun bn(value: Int): Char = decryptAlphabet[value]

    private fun decryptString(enc: String, key: String): String {
        val sb = StringBuilder(enc.length)
        for (i in enc.indices) {
            val keyChar = key[i % (key.length - 1)]
            var diff = ie(enc[i]) - ie(keyChar)
            if (diff < 0) diff += 64
            sb.append(bn(diff))
        }
        return sb.toString()
    }

    private fun decryptEncryptedUrl(enc: String): String {
        val value = enc.removePrefix("enc:")
        return decryptString(decryptString(value, decryptKey2), decryptKey1)
    }

    private suspend fun fetchPlayerSource(playerUrl: String, referer: String): String? {
        return try {
            val html = smartGet(playerUrl, referer = referer).outerHtml()

            Regex("""enc:[A-Za-z0-9+/=_%]+""").findAll(html).forEach { match ->
                val decrypted = decryptEncryptedUrl(match.value)
                if (decrypted.startsWith("http")) return decrypted
            }

            Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""").findAll(html).forEach { match ->
                return match.value.replace("&amp;", "&")
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun emitStream(m3u8: String, referer: String, callback: (ExtractorLink) -> Unit) {
        // المصدر "Auto" أولاً — الافتراضي الذي يُجلب ويُشغَّل تلقائياً في كل حلقة/فيلم.
        // يترك المشغّل يتبدل بين الجودات لحظياً حسب سرعة الإنترنت (رفع/خفض) لتفادي الـ buffering.
        runCatching {
            callback(
                ExtractorLink(
                    source = "${name} Auto",
                    name = "${name} Auto",
                    url = m3u8,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer
                    ),
                    extractorData = "",
                    type = ExtractorLinkType.M3U8,
                    audioTracks = emptyList()
                )
            )
        }

        // الرابط الأساسي البديل (يُعرض بعده في قائمة المصادر)
        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = m3u8,
            referer = referer,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to referer
            )
        ).forEach(callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            smartGet(data)
        } catch (e: Exception) {
            return false
        }

        val playerUrls = extractWatchUrls(doc)
        if (playerUrls.isEmpty()) return false

        var found = false

        for (url in playerUrls) {
            val playerUrl = url.replace("&amp;", "&")
            val host = runCatching { Uri.parse(playerUrl).host }.getOrNull() ?: continue

            // روابط استضافة خارجية → المستخرجات الجاهزة
            if (!host.contains("fasel-hd", ignoreCase = true)) {
                runCatching {
                    if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
                continue
            }

            // مشغل فاصل الداخلي: جلب مباشر + فك تشفير، ثم WebView كاحتياط
            val direct = fetchPlayerSource(playerUrl, data)
            if (direct != null) {
                emitStream(direct, playerUrl, callback)
                found = true
                continue
            }

            val m3u8 = resolveWithWebView(playerUrl, data)
            if (m3u8 != null) {
                emitStream(m3u8, playerUrl, callback)
                found = true
            }
        }

        return found
    }

    // ------------------------------------------------------------------
    // مستخرج WebView لمشغل فاصل (فك تشفير enc: + التقاط m3u8)
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(iframeUrl: String, referer: String): String? =
        suspendCancellableCoroutine { cont ->
            val activity = context as? Activity
            if (activity == null || activity.isFinishing) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val finalUrl = iframeUrl.replace("&amp;", "&").trim()

            activity.runOnUiThread {
                val dialog = Dialog(activity)
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

                val webView = WebView(activity)
                webView.layoutParams = ViewGroup.LayoutParams(1, 1)
                webView.visibility = View.INVISIBLE

                dialog.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setDimAmount(0f)
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    )
                    attributes = attributes?.apply {
                        width = 1
                        height = 1
                        x = -10000
                        y = -10000
                        gravity = Gravity.START or Gravity.TOP
                    }
                }

                try {
                    dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                    dialog.show()
                } catch (e: Exception) {
                    try {
                        val decor = activity.window?.decorView as? ViewGroup
                        decor?.addView(webView, ViewGroup.LayoutParams(1, 1))
                    } catch (_: Exception) {
                    }
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(true)
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = userAgent
                    blockNetworkImage = true
                }

                // تمرير كوكيز Cloudflare المحلولة إلى الـ WebView
                val cookieManager = CookieManager.getInstance()
                runCatching {
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                    cookieManager.flush()
                }

                val foundM3u8 = linkedSetOf<String>()
                var finished = false
                val finishLock = Any()
                val handler = Handler(Looper.getMainLooper())

                fun cleanup() {
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                    runCatching { dialog.dismiss() }
                }

                fun safeFinish(result: String?) {
                    synchronized(finishLock) {
                        if (finished) return
                        finished = true
                    }
                    runCatching { if (cont.isActive) cont.resume(result) }
                    cleanup()
                }

                fun chooseAndFinish() {
                    if (foundM3u8.isEmpty()) {
                        safeFinish(null)
                        return
                    }
                    val strict = foundM3u8.firstOrNull {
                        val clean = it.substringBefore("?")
                        clean.endsWith(".m3u8") &&
                            (clean.contains("master") || clean.contains("playlist") || clean.contains("index"))
                    } ?: foundM3u8.firstOrNull { it.substringBefore("?").endsWith(".m3u8") }
                    safeFinish(strict ?: foundM3u8.first())
                }

                fun handleFoundLink(url: String) {
                    val clean = url.substringBefore("?")
                    if (!clean.lowercase().endsWith(".m3u8")) return
                    synchronized(foundM3u8) {
                        if (foundM3u8.add(url) && (clean.contains("master") || clean.contains("playlist"))) {
                            handler.postDelayed({ chooseAndFinish() }, 300)
                        }
                    }
                }

                var attempt = 0
                val maxAttempts = 2
                var attemptTimeout: Runnable? = null

                fun startAttempt() {
                    synchronized(finishLock) { if (finished) return }
                    if (attempt >= maxAttempts) {
                        chooseAndFinish()
                        return
                    }
                    attemptTimeout?.let { handler.removeCallbacks(it) }
                    attemptTimeout = Runnable {
                        synchronized(foundM3u8) {
                            if (foundM3u8.isNotEmpty()) chooseAndFinish()
                            else {
                                attempt++
                                startAttempt()
                            }
                        }
                    }
                    handler.postDelayed(attemptTimeout!!, 11_000L)

                    runCatching {
                        webView.loadUrl(finalUrl, mapOf("Referer" to referer))
                    }
                }

                fun strategyJs(attempt: Int): String {
                    return """
                        (function() {
                            const attempt = $attempt;
                            const Decryptor = {
                                key1: "V2@%YSU2B]G~", key2: "bv0fim4qf17",
                                ie: function(c) {
                                    const x = c.charCodeAt(0);
                                    if (x >= 97 && x <= 122) return x - 97;
                                    if (x >= 65 && x <= 90) return x - 65 + 26;
                                    if (x >= 48 && x <= 57) return x - 48 + 52;
                                    if (x === 43) return 62;
                                    if (x === 47) return 63;
                                    return 0;
                                },
                                bn: function(x) {
                                    if (x <= 25) return String.fromCharCode(x + 97);
                                    if (x <= 51) return String.fromCharCode(x - 26 + 65);
                                    if (x <= 61) return String.fromCharCode(x - 52 + 48);
                                    if (x === 62) return '+';
                                    return '/';
                                },
                                dec: function(e, k) {
                                    let r = '';
                                    for (let i = 0; i < e.length; i++) {
                                        const kc = k[i % (k.length - 1)];
                                        const M = this.ie(e[i]) - this.ie(kc);
                                        r += this.bn(M < 0 ? M + 64 : M);
                                    }
                                    return r;
                                },
                                parse: function(url) {
                                    if (!url || !url.startsWith('enc:')) return url;
                                    try { return this.dec(this.dec(url.substring(4), this.key2), this.key1); } catch(e) { return url; }
                                }
                            };

                            if (!window.__netHooked) {
                                const jw = setInterval(function() {
                                    if (typeof window.jwplayer === 'function' && !window.jwplayer.__hooked) {
                                        const orig = window.jwplayer;
                                        window.jwplayer = function() {
                                            const p = orig.apply(this, arguments);
                                            if (!p.__hooked) {
                                                p.__hooked = true;
                                                const oSetup = p.setup;
                                                p.setup = function(cfg) {
                                                    try {
                                                        const s = cfg.sources || (cfg.playlist && cfg.playlist[0] ? cfg.playlist[0].sources : []);
                                                        if (s) s.forEach(function(x) {
                                                            if (x.file && x.file.startsWith('enc:')) x.file = Decryptor.parse(x.file);
                                                        });
                                                    } catch(e) {}
                                                    cfg.autostart = true;
                                                    cfg.mute = true;
                                                    return oSetup.call(this, cfg);
                                                };
                                            }
                                            return p;
                                        };
                                        Object.assign(window.jwplayer, orig);
                                        window.jwplayer.prototype = orig.prototype;
                                        window.jwplayer.__hooked = true;
                                        clearInterval(jw);
                                    }
                                }, 10);
                            }

                            try {
                                let p = typeof window.jwplayer === 'function' ? window.jwplayer("player") : null;
                                if (attempt === 0 || attempt === 1) {
                                    document.querySelectorAll('button, a, video, [role="button"], .jw-icon, .vjs-control, .plyr__control').forEach(function(el) {
                                        try { el.click(); } catch(e) {}
                                    });
                                }
                                if (p) {
                                    try { p.setMute(true); } catch(e) {}
                                    try { p.play(); } catch(e) {}
                                }
                            } catch(e) {}
                        })();
                    """.trimIndent()
                }

                val snifferJs = """
                    (function() {
                        if (!window.__netHookedFetch) {
                            window.__netHookedFetch = true;
                            const _fetch = window.fetch;
                            if (_fetch) {
                                window.fetch = function() {
                                    return _fetch.apply(this, arguments).then(function(resp) {
                                        try {
                                            const u = resp && resp.url ? resp.url : '';
                                            if (u.indexOf('.m3u8') !== -1) console.log('NET_M3U8::' + u);
                                        } catch(e) {}
                                        return resp;
                                    });
                                };
                            }
                            const _open = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function(method, u) {
                                this.addEventListener('load', function() {
                                    try {
                                        if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) console.log('NET_M3U8::' + u);
                                    } catch(e) {}
                                });
                                return _open.apply(this, arguments);
                            };
                        }
                    })();
                """.trimIndent()

                val webClient = object : WebViewClient() {

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (!url.startsWith("http")) return true
                        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return true

                        if (host.contains("fasel-hd")) return false

                        if (host.contains("recaptcha") || host.contains("google.com/ads") || host.contains("melbet")) {
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        runCatching { view?.evaluateJavascript(snifferJs, null) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        runCatching { view?.evaluateJavascript(snifferJs, null) }
                        if (!finished) {
                            view?.evaluateJavascript(strategyJs(attempt), null)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        val method = request.method
                        val lower = url.lowercase()

                        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".woff2") || lower.endsWith(".css")) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        if (method.equals("GET", ignoreCase = true) && lower.substringBefore("?").endsWith(".m3u8")) {
                            handleFoundLink(url)
                            try {
                                val reqBuilder = OkRequest.Builder()
                                    .url(url)
                                    .header("User-Agent", userAgent)
                                    .header("Referer", referer)
                                    .header("Origin", mainUrl)
                                val cookies = cookieManager.getCookie(url)
                                if (cookies.isNullOrBlank().not()) reqBuilder.header("Cookie", cookies!!)

                                val response = httpClient.newCall(reqBuilder.build()).execute()
                                if (!response.isSuccessful) return null
                                val contentType =
                                    response.header("content-type")?.substringBefore(";")
                                        ?: "application/vnd.apple.mpegurl"
                                return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                            } catch (e: Exception) {
                                return null
                            }
                        }

                        if (method.equals("GET", ignoreCase = true) &&
                            (lower.contains("fasel") || lower.contains("jwplayer") || lower.contains("config") || lower.contains("player"))
                        ) {
                            try {
                                val reqBuilder = OkRequest.Builder()
                                    .url(url)
                                    .header("User-Agent", userAgent)
                                    .header("Referer", referer)
                                val cookies = cookieManager.getCookie(url)
                                if (cookies.isNullOrBlank().not()) reqBuilder.header("Cookie", cookies!!)

                                val response = httpClient.newCall(reqBuilder.build()).execute()
                                val contentType =
                                    response.header("content-type")?.substringBefore(";") ?: "text/html"
                                return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                            } catch (e: Exception) {
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }

                webView.webViewClient = webClient
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        val text = msg?.message() ?: ""
                        if (text.startsWith("NET_M3U8::")) {
                            handleFoundLink(text.substringAfter("::").trim())
                        }
                        return true
                    }
                }

                startAttempt()

                cont.invokeOnCancellation {
                    handler.post { safeFinish(null) }
                }
            }
        }
}