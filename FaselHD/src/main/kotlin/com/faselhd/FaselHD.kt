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
import com.lagradost.cloudstream3.CommonActivity
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
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    override val hasDownloadSupport = true
    override var mainUrl = "https://www.fasel-hd.co"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

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

    private fun activeActivity(): Activity? {
        val supplied = (context as? Activity)?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (supplied != null) return supplied
        return CommonActivity.activity?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    private val userAgent: String by lazy {
        val nativeWebViewAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        nativeWebViewAgent
            ?.replace("; wv)", ")")
            ?.replace("Version/4.0 ", "")
            ?.replace(" Version/4.0", "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val chromeMajorVersion: String by lazy {
        Regex("(?:Chrome|Chromium)/([0-9]+)")
            .find(userAgent)
            ?.groupValues
            ?.getOrNull(1)
            ?: "124"
    }

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

    // الموقع قد يحوّل إلى المضيف القياسي؛ احتفظ بجذر المضيف بعد أول تحويل ناجح.
    private suspend fun baseUrl(): String {
        redirectUrl?.let { return it }
        return try {
            val response = app.get(
                mainUrl,
                headers = getModernHeaders(mainUrl),
                timeout = 20L,
                allowRedirects = true,
            )
            val base = response.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                ?: mainUrl.trimEnd('/')
            redirectUrl = base
            base
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mainUrl.trimEnd('/')
        }
    }

    private fun getModernHeaders(url: String, referer: String? = null): MutableMap<String, String> {
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        val urlHost = runCatching { Uri.parse(url).host }.getOrNull()
        val refererHost = referer?.let { value -> runCatching { Uri.parse(value).host }.getOrNull() }
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "sec-ch-ua" to "\"Not:A-Brand\";v=\"99\", \"Chromium\";v=\"$chromeMajorVersion\", \"Google Chrome\";v=\"$chromeMajorVersion\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "upgrade-insecure-requests" to "1",
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "sec-fetch-site" to when {
                referer.isNullOrBlank() -> "none"
                urlHost != null && urlHost == refererHost -> "same-origin"
                else -> "cross-site"
            },
            "sec-fetch-mode" to "navigate",
            "sec-fetch-dest" to "document",
            "accept-language" to "ar-EG,ar;q=0.9",
            "priority" to "u=0, i"
        )
        if (cookies.isNotBlank()) headers["Cookie"] = cookies
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        return headers
    }

    private fun getProtectedHeaders(): Map<String, String> = getModernHeaders(mainUrl)

    private fun isCloudflareChallenge(code: Int, html: String): Boolean {
        if (code == 403 || code == 429 || code == 503) return true
        val lower = html.take(32_000).lowercase()
        val explicitMarkers = listOf(
            "<title>just a moment",
            "cf-browser-verification",
            "cf-chl-widget",
            "__cf_chl_opt",
            "challenge-running",
            "checking your browser",
            "checking if the site connection is secure",
            "attention required",
            "verify you are human",
            "/cdn-cgi/challenge-platform/",
        )
        val turnstileChallenge = lower.contains("cf-turnstile") &&
            (lower.contains("_cf_chl_") || lower.contains("verify you are human") || lower.contains("just a moment"))
        return explicitMarkers.any { marker -> lower.contains(marker) } || turnstileChallenge
    }

    // Keep ordinary HTTP attempts short; Cloudflare and network-level failures get one WebView fallback.
    private suspend fun smartGet(url: String, referer: String? = null): Document {
        val cleanUrl = toAbsolute(url, mainUrl) ?: url.trim()
        var challengeDetected = false
        for (attempt in 1..2) {
            try {
                val response = app.get(
                    cleanUrl,
                    headers = getModernHeaders(cleanUrl, referer),
                    timeout = 15L,
                    allowRedirects = true,
                )
                val html = response.text
                val document = Jsoup.parse(html, response.url)
                if (response.code in 200..299 && html.isNotBlank() &&
                    !isCloudflareChallenge(response.code, html)
                ) {
                    return document
                }
                if (isCloudflareChallenge(response.code, html) ||
                    (response.code in 200..299 && html.isBlank())
                ) {
                    challengeDetected = true
                    break
                }
                return document
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                when {
                    message.contains("403") || message.contains("429") || message.contains("503") -> {
                        challengeDetected = true
                        break
                    }
                    attempt == 2 -> {
                        // A WebView uses Chromium's network stack and can succeed where OkHttp times out.
                        challengeDetected = true
                        break
                    }
                    else -> delay(250L * attempt)
                }
            }
        }

        if (!challengeDetected) return Jsoup.parse("", cleanUrl)
        return cfLock.withLock {
            val solved = CloudflareSolver.solve(activeActivity(), cleanUrl, userAgent)
                ?: return@withLock Jsoup.parse("", cleanUrl)
            val solvedHtml = solved.outerHtml()

            val retried = try {
                app.get(
                    cleanUrl,
                    headers = getModernHeaders(cleanUrl, referer),
                    timeout = 15L,
                    allowRedirects = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (retried != null) {
                val retriedHtml = retried.text
                if (retried.code in 200..299 && retriedHtml.isNotBlank() &&
                    !isCloudflareChallenge(retried.code, retriedHtml)
                ) {
                    return@withLock Jsoup.parse(retriedHtml, retried.url)
                }
            }

            if (!isCloudflareChallenge(200, solvedHtml)) solved
            else Jsoup.parse("", cleanUrl)
        }
    }

    private suspend fun executeRequestWithCloudflareRetry(requestBlock: suspend (String) -> String): String? {
        val base = baseUrl()
        var currentCookies = runCatching { CookieManager.getInstance().getCookie(base) }.getOrNull().orEmpty()
        var challengeDetected = false
        try {
            val result = requestBlock(currentCookies)
            if (result.isNotBlank() && !isCloudflareChallenge(200, result)) return result
            challengeDetected = result.isNotBlank() && isCloudflareChallenge(200, result)
            if (!challengeDetected) return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            if (listOf("403", "429", "503").none { message.contains(it) }) return null
            challengeDetected = true
        }

        if (!challengeDetected) return null
        cfLock.withLock { CloudflareSolver.solve(activeActivity(), base, userAgent) }
        currentCookies = runCatching { CookieManager.getInstance().getCookie(base) }.getOrNull().orEmpty()

        return try {
            requestBlock(currentCookies).takeIf { it.isNotBlank() && !isCloudflareChallenge(200, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
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
        var currentMethod = "POST"
        var redirectCount = 0

        while (redirectCount < 5) {
            val refererUri = referer.toHttpUrlOrNull()
            val origin = refererUri?.let { "${it.scheme}://${it.host}" } ?: referer.trimEnd('/')
            val requestBuilder = OkRequest.Builder()
                .url(currentUrl)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("Origin", origin)
                .header("X-Requested-With", "XMLHttpRequest")
            if (cookies.isNotBlank()) requestBuilder.header("Cookie", cookies)
            if (currentMethod == "POST") requestBuilder.post(formBody) else requestBuilder.get()

            val response = withContext(Dispatchers.IO) {
                noRedirectClient.newCall(requestBuilder.build()).execute()
            }

            response.use { res ->
                when (res.code) {
                    403 -> throw IllegalStateException("403_FORBIDDEN")
                    429 -> throw IllegalStateException("429_TOO_MANY_REQUESTS")
                    503 -> throw IllegalStateException("503_SERVICE_UNAVAILABLE")
                    301, 302, 303, 307, 308 -> {
                        val location = res.header("Location") ?: return ""
                        currentUrl = location.toHttpUrlOrNull()?.toString()
                            ?: currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString()
                            ?: return ""
                        if (res.code == 301 || res.code == 302 || res.code == 303) currentMethod = "GET"
                        redirectCount++
                    }
                    200 -> return res.body?.string().orEmpty()
                    else -> return ""
                }
            }
        }
        return ""
    }

    private fun toAbsolute(url: String?, base: String = mainUrl): String? {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val lower = value.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("mailto:") || value.startsWith("#")) {
            return null
        }
        val baseUrl = base.toHttpUrlOrNull() ?: mainUrl.toHttpUrlOrNull() ?: return null
        val resolved = baseUrl.resolve(value) ?: return null
        return resolved.toString().takeIf { resolved.scheme == "https" || resolved.scheme == "http" }
    }

    private fun Element.posterUrl(base: String = mainUrl): String? {
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src")
            .asSequence()
            .map { attr(it).trim() }
            .firstOrNull { it.isNotBlank() }
        return toAbsolute(raw, base)
    }

    private fun Element.toSearchResult(base: String = mainUrl): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = toAbsolute(link.attr("href"), base) ?: return null
        val title = selectFirst(".h1, .h4, .h5, .title, h2, h3")?.text()
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst("img")?.posterUrl(base)
        val path = href.substringBefore('?').lowercase()
        val type = when {
            path.contains("/anime-movies") -> TvType.AnimeMovie
            path.contains("/anime") -> TvType.Anime
            path.contains("/movies") || title.contains("فيلم") -> TvType.Movie
            else -> TvType.TvSeries
        }

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
            TvType.Anime, TvType.AnimeMovie -> newAnimeSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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

    private fun hasNextPage(doc: Document, nextPage: Int): Boolean {
        val pagePath = Regex("""/page/$nextPage(?:/|[?#]|$)""", RegexOption.IGNORE_CASE)
        val pageQuery = Regex("""[?&](?:page|paged)=$nextPage(?:&|$)""", RegexOption.IGNORE_CASE)
        return doc.select("ul.pagination a[href], .pagination a[href], nav.pagination a[href]")
            .any { link -> pagePath.containsMatchIn(link.attr("href")) || pageQuery.containsMatchIn(link.attr("href")) }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/main" to "الرئيسية",
        "$mainUrl/movies" to "أفلام أجنبية",
        "$mainUrl/series" to "مسلسلات أجنبية",
        "$mainUrl/episodes" to "أحدث الحلقات",
        "$mainUrl/asian-episodes" to "حلقات آسيوية",
        "$mainUrl/anime-episodes" to "حلقات الأنمي",
        "$mainUrl/anime" to "الأنمي",
        "$mainUrl/anime-movies" to "أفلام الأنمي",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/tvepisodes" to "حلقات البرامج",
        "$mainUrl/tvshows" to "البرامج",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندية",
        "$mainUrl/all-movies" to "جميع الأفلام",
        "$mainUrl/most_recent" to "أحدث الإضافات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data.trimEnd('/').endsWith("/main")
        val url = when {
            isHome || page <= 1 -> request.data
            else -> request.data.trimEnd('/') + "/page/$page/"
        }

        val doc = smartGet(url)
        val docBase = doc.location().takeIf { it.startsWith("http", ignoreCase = true) } ?: url

        if (isHome) {
            val lists = mutableListOf<HomePageList>()

            val slides = doc.select("#homeSlide .swiper-slide").mapNotNull { slide ->
                val href = toAbsolute(slide.selectFirst("a")?.attr("href"), docBase) ?: return@mapNotNull null
                val title = slide.selectFirst(".h1 a, .h1, h1, h2")?.text()?.trim() ?: return@mapNotNull null
                val poster = slide.selectFirst(".poster img, img")?.posterUrl(docBase)
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            if (slides.isNotEmpty()) {
                lists.add(HomePageList("أحدث الإضافات", slides, isHorizontalImages = true))
            }

            doc.select("section#blockList").forEach { block ->
                val title = block.selectFirst(".blockHead .h3")?.text()?.trim() ?: return@forEach
                val items = block.select(".blockMovie, .postDiv, .epDivHome").mapNotNull { it.toSearchResult(docBase) }
                if (items.isNotEmpty()) {
                    lists.add(HomePageList(title, items))
                }
            }

            if (lists.isNotEmpty()) {
                return newHomePageResponse(lists)
            }
        }

        val items = groupResults(
            doc.select(".postDiv, .blockMovie").mapNotNull { it.toSearchResult(docBase) }
        )
        val hasNext = hasNextPage(doc, page + 1)
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ------------------------------------------------------------------
    // البحث (نفس آلية موقع فاصل)
    // ------------------------------------------------------------------

    private suspend fun liveSearchResults(query: String): List<SearchResponse> {
        return try {
            val base = baseUrl()
            val ajaxUrl = "$base/wp-admin/admin-ajax.php"
            val formBody = FormBody.Builder()
                .add("action", "dtc_live")
                .add("trsearch", query)
                .build()
            val bodyStr = executeRequestWithCloudflareRetry { cookies ->
                makeAjaxRequest(ajaxUrl, base, formBody, cookies)
            }
            if (bodyStr.isNullOrBlank()) return emptyList()
            val doc = Jsoup.parse(bodyStr, base)
            groupResults(
                doc.select("div.postDiv, article, .result, .search-item")
                    .mapNotNull { it.toSearchResult(doc.location()) }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
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
        val searchUrl = if (page == 1) {
            "$base/?s=$encoded"
        } else {
            "$base/page/$page/?s=$encoded"
        }

        val document = try {
            smartGet(searchUrl, referer = base)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Jsoup.parse("", searchUrl)
        }
        val docBase = document.location().takeIf { it.startsWith("http", ignoreCase = true) } ?: searchUrl
        var items = document.select("div#postList div.postDiv, div.postDiv, article")
            .mapNotNull { it.toSearchResult(docBase) }
        val hasNext = hasNextPage(document, page + 1)

        // If the ordinary WordPress search is blocked, use the site's own AJAX search.
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
        Regex("""(?i)\bseason\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
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

    private fun extractEpisodeNumber(text: String): Int? =
        Regex("""(?i)(?:الحلقة|episode|ep)\s*[:#-]?\s*(\d+)""").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d+)\s*$""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun addEpisode(
        el: Element,
        out: MutableList<Episode>,
        season: Int,
        poster: String?,
        base: String,
    ) {
        val href = toAbsolute(el.attr("href"), base) ?: return
        if (out.any { it.data == href }) return
        val name = el.ownText().ifBlank { el.text() }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (name.isBlank()) return
        val number = extractEpisodeNumber(name)
        out.add(
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = number
                this.posterUrl = poster
            }
        )
    }

    private fun episodeElements(doc: Document): List<Element> =
        doc.select("#epAll a[href], #episodeList a[href], #epList a[href], #episodesList a[href], .episodeList a[href], .episodesList a[href], .episodes-list a[href], .epAll a[href]")
            .distinctBy { it.attr("href") }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = toAbsolute(url, mainUrl) ?: return null
        val doc = smartGet(pageUrl)
        val docBase = doc.location().takeIf { it.startsWith("http", ignoreCase = true) } ?: pageUrl

        val title = doc.selectFirst(".singleInfo h1.title, h1.title, h1.entry-title, .movie-title, h1")
            ?.text()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val plot = doc.selectFirst(".singleDesc p, .story p, .entry-content p")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        val poster = doc.selectFirst("img.poster, .singleInfo .poster img, .poster img")?.posterUrl(docBase)
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { toAbsolute(it, docBase) }
        val tags = doc.select("#singleList a[href], .singleInfo .category a[href], .genres a[href], a[href*='/movies-cats/'], a[href*='/series_genres/'], a[href*='/anime-cats/'], a[href*='/anime_genres/']")
            .mapNotNull { it.text().trim().takeIf { text -> text.isNotBlank() } }
            .distinct()
        val isAnime = pageUrl.contains("anime", ignoreCase = true) ||
            title.contains("انمي", ignoreCase = true) ||
            tags.any { it.contains("انمي", ignoreCase = true) || it.contains("anime", ignoreCase = true) }

        val path = pageUrl.substringBefore('?').lowercase()
        val episodePage = listOf("/episodes/", "/tvepisodes/", "/anime-episodes/", "/asian-episodes/")
            .any { route -> path.contains(route) }
        val seasonCards = doc.select("#seasonList .seasonDiv, .season-list .seasonDiv, .season-card, .seasonDiv")
        val episodeLinks = episodeElements(doc)
        val onClickSeason = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
        val episodes = mutableListOf<Episode>()

        if (seasonCards.isNotEmpty()) {
            val activeIndex = seasonCards.indexOfFirst { it.hasClass("active") }
            val seasonInfos = seasonCards.mapIndexed { index, seasonEl ->
                val seasonName = seasonEl.selectFirst(".title, .season-title, h2, h3")?.text()
                    ?.replace(Regex("\\s+"), " ")?.trim()
                Triple(index, extractSeasonNumber(seasonName, index + 1), index == activeIndex)
            }
            val seasonSemaphore = Semaphore(3)
            val passiveDocs = coroutineScope {
                seasonCards.mapIndexed { index, seasonEl ->
                    async {
                        if (index == activeIndex) {
                            null
                        } else {
                            val seasonUrl = seasonEl.selectFirst("a[href]")?.attr("href")
                                ?.let { toAbsolute(it, docBase) }
                                ?: onClickSeason.find(seasonEl.attr("onclick"))?.groupValues?.get(1)
                                    ?.let { toAbsolute(it, docBase) }
                            if (seasonUrl == null) null else seasonSemaphore.withPermit {
                                try {
                                    smartGet(seasonUrl, referer = docBase)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            seasonInfos.forEachIndexed { index, (_, seasonNumber, isActive) ->
                val seasonDoc = when {
                    isActive -> doc
                    passiveDocs[index] != null -> passiveDocs[index]
                    activeIndex < 0 && index == 0 -> doc
                    else -> null
                } ?: return@forEachIndexed
                val seasonBase = seasonDoc.location().takeIf { it.startsWith("http", ignoreCase = true) } ?: docBase
                episodeElements(seasonDoc).forEach { addEpisode(it, episodes, seasonNumber, poster, seasonBase) }
            }
        } else {
            episodeLinks.forEach { addEpisode(it, episodes, 1, poster, docBase) }
        }

        if (episodePage && episodes.none { it.data == pageUrl }) {
            val episodeSeason = extractSeasonNumber(title, 1)
            episodes.add(newEpisode(pageUrl) {
                this.name = title
                this.season = episodeSeason
                this.episode = extractEpisodeNumber(title)
                this.posterUrl = poster
            })
        }

        val looksLikeSeries = seasonCards.isNotEmpty() || episodeLinks.isNotEmpty() || episodePage ||
            path.contains("/series/") || path.contains("/asian-series/") || path.contains("/tvshows/") ||
            path.contains("/anime/") || path.contains("/episode/") ||
            title.contains("مسلسل") || tags.any { it.contains("مسلسل") }

        if (looksLikeSeries) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            return newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        val movieType = if (isAnime) TvType.AnimeMovie else TvType.Movie
        return newMovieLoadResponse(title, pageUrl, movieType, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ------------------------------------------------------------------
    // روابط المشاهدة
    // ------------------------------------------------------------------

    private fun extractWatchUrls(doc: Document): List<String> {
        val results = linkedSetOf<String>()
        val pageBase = doc.location().takeIf { it.startsWith("http", ignoreCase = true) } ?: mainUrl
        val blocked = listOf("recaptcha", "google.com/ads", "googlesyndication.com", "googletagmanager.com", "doubleclick")

        fun add(raw: String?) {
            val clean = raw?.replace("&amp;", "&")
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&", ignoreCase = true)
                ?.trim()?.takeIf { it.isNotBlank() } ?: return
            if (blocked.any { clean.contains(it, ignoreCase = true) }) return
            toAbsolute(clean, pageBase)?.let(results::add)
        }

        val playerOnClick = Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")
        val windowOpen = Regex("""(?:window\.)?open\(\s*['"]([^'"]+)['"]""")
        doc.select("[onclick]").forEach { element ->
            val script = element.attr("onclick")
            (playerOnClick.find(script) ?: windowOpen.find(script))?.groupValues?.get(1)?.let(::add)
        }

        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url]").forEach { frame ->
            val source = listOf("data-src", "data-lazy-src", "data-url", "src")
                .asSequence()
                .map { frame.attr(it).trim() }
                .firstOrNull { it.isNotBlank() }
            add(source)
        }
        doc.select("video[src], video source[src], source[src]").forEach { add(it.attr("src")) }
        doc.select("a[href*='video_player'], a[href*='embed'], a[href*='/player/']").forEach { add(it.attr("href")) }
        doc.select("[data-embed], [data-player], [data-video], [data-iframe], [data-server*='http'], [data-url*='embed'], [data-url*='player'], [data-src*='embed'], [data-src*='player']")
            .forEach { element ->
                val candidate = listOf("data-embed", "data-player", "data-video", "data-iframe", "data-server", "data-url", "data-src")
                    .asSequence().map { element.attr(it).trim() }.firstOrNull { it.isNotBlank() }
                val lower = candidate.orEmpty().lowercase()
                if (candidate != null && (candidate.startsWith("http", true) || candidate.startsWith("/") ||
                        isSupportedMediaUrl(candidate) || listOf("embed", "player", "video", "watch").any { marker -> lower.contains(marker) })
                ) add(candidate)
            }

        val playerConfig = Regex("""(?i)(?:player_iframe\.location\.href|(?:file|src|source|url|embed)\s*[:=])\s*['"]([^'"]+)['"]""")
        doc.select("script").forEach { script ->
            playerConfig.findAll(script.data()).forEach { match ->
                val candidate = match.groupValues[1]
                val lower = candidate.lowercase()
                if (isSupportedMediaUrl(candidate) || listOf("embed", "player", "video", "watch").any { marker -> lower.contains(marker) }) {
                    add(candidate)
                }
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

    private fun isSupportedMediaUrl(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty() }
            .getOrDefault(url.substringBefore('?').substringBefore('#'))
            .lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi")
            .any { extension -> path.endsWith(extension) }
    }

    private suspend fun fetchPlayerSource(playerUrl: String, referer: String): String? {
        return try {
            val html = smartGet(playerUrl, referer = referer).outerHtml()
            val decryptedUrls = linkedSetOf<String>()
            val mediaUrls = linkedSetOf<String>()

            Regex("""enc:[A-Za-z0-9+/=_%.-]+""").findAll(html).forEach { match ->
                val decrypted = decryptEncryptedUrl(match.value)
                    .replace("&amp;", "&")
                    .trim()
                val absolute = toAbsolute(decrypted, playerUrl) ?: return@forEach
                decryptedUrls.add(absolute)
                if (isSupportedMediaUrl(absolute)) mediaUrls.add(absolute)
            }

            val keyedUrl = Regex("""(?i)["']?(?:file|src|source|url)["']?\s*[:=]\s*["']([^"']+)["']""")
            keyedUrl.findAll(html).forEach { match ->
                val absolute = toAbsolute(match.groupValues[1].replace("&amp;", "&"), playerUrl) ?: return@forEach
                if (isSupportedMediaUrl(absolute)) mediaUrls.add(absolute)
            }

            Regex("""(?i)https?://[^\s"'<>\\]+""").findAll(html).forEach { match ->
                val absolute = toAbsolute(match.value.replace("&amp;", "&"), playerUrl) ?: return@forEach
                if (isSupportedMediaUrl(absolute)) mediaUrls.add(absolute)
            }

            mediaUrls.firstOrNull() ?: decryptedUrls.firstOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun emitStream(mediaUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val url = toAbsolute(mediaUrl, referer) ?: mediaUrl
        val path = runCatching { Uri.parse(url).path.orEmpty() }
            .getOrDefault(url.substringBefore('?').substringBefore('#'))
            .lowercase()
        val type = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val quality = Regex("(?i)(2160|1440|1080|720|540|480|360|240)")
            .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
        val headers = mapOf("User-Agent" to userAgent, "Referer" to referer)

        runCatching {
            callback(
                ExtractorLink(
                    source = name,
                    name = if (type == ExtractorLinkType.M3U8) "$name Auto" else name,
                    url = url,
                    referer = referer,
                    quality = quality,
                    headers = headers,
                    extractorData = "",
                    type = type,
                    audioTracks = emptyList(),
                )
            )
        }

        if (type == ExtractorLinkType.M3U8) {
            try {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = url,
                    referer = referer,
                    headers = headers,
                ).forEach(callback)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            smartGet(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }

        val playerUrls = extractWatchUrls(doc)
        if (playerUrls.isEmpty()) return false
        val emitted = mutableSetOf<String>()
        var found = false
        fun emitOnce(link: ExtractorLink) {
            if (link.url.isNotBlank() && emitted.add(link.url.trim())) callback(link)
        }

        for (rawUrl in playerUrls) {
            val playerUrl = toAbsolute(rawUrl, doc.location()) ?: continue
            val host = runCatching { Uri.parse(playerUrl).host }.getOrNull() ?: continue
            val isInternalPlayer = host.contains("fasel-hd", ignoreCase = true) ||
                host.contains("faselhd", ignoreCase = true)

            if (!isInternalPlayer) {
                if (isSupportedMediaUrl(playerUrl)) {
                    emitStream(playerUrl, data, ::emitOnce)
                    found = true
                    continue
                }
                try {
                    if (loadExtractor(playerUrl, data, subtitleCallback, ::emitOnce)) found = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
                continue
            }

            val direct = fetchPlayerSource(playerUrl, data)
            if (direct != null) {
                if (isSupportedMediaUrl(direct)) {
                    emitStream(direct, playerUrl, ::emitOnce)
                    found = true
                    continue
                }
                try {
                    if (loadExtractor(direct, playerUrl, subtitleCallback, ::emitOnce)) {
                        found = true
                        continue
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
            }

            val resolved = resolveWithWebView(playerUrl, data)
            if (resolved != null) {
                if (isSupportedMediaUrl(resolved)) {
                    emitStream(resolved, playerUrl, ::emitOnce)
                    found = true
                } else {
                    try {
                        if (loadExtractor(resolved, playerUrl, subtitleCallback, ::emitOnce)) found = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }
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
            val activity = activeActivity()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val finalUrl = iframeUrl.replace("&amp;", "&").trim()
            val handler = Handler(Looper.getMainLooper())
            var cancellationCleanup: (() -> Unit)? = null
            cont.invokeOnCancellation {
                handler.post { cancellationCleanup?.invoke() }
            }

            activity.runOnUiThread {
                if (!cont.isActive || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
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

                val foundMedia = linkedSetOf<String>()
                var finished = false
                val finishLock = Any()

                fun cleanup() {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { webView.stopLoading() }
                    runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
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

                cancellationCleanup = { safeFinish(null) }

                fun chooseAndFinish() {
                    val candidates = synchronized(foundMedia) { foundMedia.toList() }
                    if (candidates.isEmpty()) {
                        safeFinish(null)
                        return
                    }
                    val hlsMaster = candidates.firstOrNull {
                        val path = it.substringBefore('?').substringBefore('#').lowercase()
                        path.endsWith(".m3u8") &&
                            (path.contains("master") || path.contains("playlist") || path.contains("index"))
                    }
                    val hls = candidates.firstOrNull { it.substringBefore('?').substringBefore('#').endsWith(".m3u8", true) }
                    val dash = candidates.firstOrNull { it.substringBefore('?').substringBefore('#').endsWith(".mpd", true) }
                    val media = hlsMaster ?: hls ?: dash ?: candidates.firstOrNull(::isSupportedMediaUrl)
                    safeFinish(media)
                }

                fun handleFoundLink(url: String) {
                    if (!isSupportedMediaUrl(url)) return
                    synchronized(foundMedia) {
                        if (foundMedia.add(url)) {
                            val delayMs = if (url.contains(".m3u8", true) || url.contains(".mpd", true)) 350L else 1_200L
                            handler.postDelayed({ chooseAndFinish() }, delayMs)
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
                        synchronized(foundMedia) {
                            if (foundMedia.isNotEmpty()) chooseAndFinish()
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
                                    document.querySelectorAll('button, video, [role="button"], .jw-icon, .vjs-control, .plyr__control, .play-button, .play').forEach(function(el) {
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
                                            const resolved = new URL(u, location.href).href;
                                            if (/\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|avi)(?:$|[?#])/i.test(resolved)) console.log('NET_MEDIA::' + resolved);
                                        } catch(e) {}
                                        return resp;
                                    });
                                };
                            }
                            const _open = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function(method, u) {
                                this.addEventListener('load', function() {
                                    try {
                                        if (typeof u === 'string') {
                                            const resolved = new URL(u, location.href).href;
                                            if (/\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|avi)(?:$|[?#])/i.test(resolved)) console.log('NET_MEDIA::' + resolved);
                                        }
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
                        if (!url.startsWith("http", ignoreCase = true)) return true
                        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return true
                        val lowerUrl = url.lowercase()

                        if (host.contains("fasel-hd", ignoreCase = true)) return false

                        if (host.contains("recaptcha") || lowerUrl.contains("google.com/ads") ||
                            lowerUrl.contains("googlesyndication.com") || lowerUrl.contains("doubleclick") ||
                            host.contains("melbet")
                        ) {
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

                        val mediaPath = lower.substringBefore("?").substringBefore("#")
                        if (method.equals("GET", ignoreCase = true) &&
                            (mediaPath.endsWith(".m3u8") || mediaPath.endsWith(".mpd"))
                        ) {
                            handleFoundLink(url)
                            try {
                                val refererUri = Uri.parse(referer)
                                val origin = "${refererUri.scheme}://${refererUri.host}"
                                val reqBuilder = OkRequest.Builder()
                                    .url(url)
                                    .header("User-Agent", userAgent)
                                    .header("Referer", referer)
                                    .header("Origin", origin)
                                val cookies = cookieManager.getCookie(url)
                                if (!cookies.isNullOrBlank()) reqBuilder.header("Cookie", cookies)

                                val response = httpClient.newCall(reqBuilder.build()).execute()
                                if (!response.isSuccessful) {
                                    response.close()
                                    return null
                                }
                                val contentType = response.header("content-type")?.substringBefore(";")
                                    ?: if (mediaPath.endsWith(".mpd")) "application/dash+xml"
                                    else "application/vnd.apple.mpegurl"
                                val body = response.body ?: run {
                                    response.close()
                                    return null
                                }
                                return WebResourceResponse(contentType, "utf-8", body.byteStream())
                            } catch (_: Exception) {
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
                        handler?.cancel()
                    }
                }

                webView.webViewClient = webClient
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        val text = msg?.message() ?: ""
                        if (text.startsWith("NET_MEDIA::")) {
                            handleFoundLink(text.substringAfter("::").trim())
                        }
                        return true
                    }
                }

                startAttempt()
            }
        }
}
