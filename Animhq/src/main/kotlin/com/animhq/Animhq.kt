package com.animhq

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

/**
 * Animhq provider for CloudStream.
 *
 * مبني على فحص بنية صفحات الموقع الحالي (animhq.com):
 *   - الرئيسية: أقسام Swiper تُحمَّل عبر AJAX من مجلد الثيم
 *     wp-content/themes/animhq/Ajax/Home/{last-posts,most-views,most-download}.php
 *     (بنية البطاقة: <li class="singleBB"><a><img><h3><span></a></li>)
 *   - التصنيفات: /category/movies/?c=&c2=&y=&ot=&page=N (ترقيم حقيقي)
 *     والتصنيفات حسب النوع action/fantasy/isekai/magic/supernatural/free
 *     وبنية البطاقة: <div class="poster-img-container"><a class="poster-wrapper">
 *   - البحث: /?s=الكلمة (نفس بطاقات .poster-img-container)
 *   - صفحة المسلسل: العنوان في .main-serie-title، المواسم في
 *     .season-dropdown-menu a.season-dropdown-item (رابط كل موسم /serie/..//?episodes=id)
 *     والحلقات في .serie-episodes-watch-list-content ul li[data-watch] بحيث
 *     data-watch = https://animhq.com/?embed={id}&ep={n}
 *   - المشغّل: /?embed={id}&ep={n} يعرض مباشرة رابط تدفق MP4 مباشر في خاصية
 *     dld = https://fl.anime4paint.ovh:49731/stream/{b64}.mp4?token={JWT}
 *     (يعمل مع Referer صحيح، الروابط موقعة بالعنوان IP)
 */
class Animhq : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animhq.com"
    override var name = "Animhq"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1"
    )

    // ---------------------------------------------------------------
    // Network helpers
    // ---------------------------------------------------------------

    private suspend fun getPage(url: String, referer: String = "$mainUrl/"): Document {
        return try {
            app.get(url, headers = baseHeaders, referer = referer, timeout = 45).document
        } catch (_: Exception) {
            Jsoup.parse("")
        }
    }

    private suspend fun postPage(url: String, referer: String = "$mainUrl/"): Document {
        return try {
            app.post(
                url,
                data = mapOf<String, String>(),
                headers = baseHeaders,
                referer = referer,
                timeout = 45
            ).document
        } catch (_: Exception) {
            Jsoup.parse("")
        }
    }

    private fun String.fixUrl(): String {
        if (isBlank()) return this
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun String.getIntFromText(): Int? =
        Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun String.adjustEntities(): String =
        Parser.unescapeEntities(this, false)

    // ---------------------------------------------------------------
    // Item parsing (cards in lists)
    // ---------------------------------------------------------------

    /**
     * يحوّل عنصر قائمة إلى SearchResponse. يدعم النموذجين:
     *  1) <li class="singleBB"><a title href><img><h3>Title</h3><span>..</span></a></li>
     *     (يستخدمه AJAX في الرئيسية)
     *  2) <div class="poster-img-container">
     *        <div class="poster-img" data-src="...">
     *        <a class="poster-wrapper" href="...">
     *        <div class="poster-info-under"><h3>Title</h3>...
     *     (يستخدمه التصنيفات والبحث)
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst("a.poster-wrapper")
            ?: selectFirst("a[href]")
            ?: return null
        val url = anchor.attr("href").fixUrl()
        if (url.isBlank() || url == "#" || !url.startsWith("http")) return null

        val img = selectFirst("img[src], img[data-src]")
        val title = (
            selectFirst("h3")?.text()
                ?: selectFirst(".poster-info-under h3")?.text()
                ?: anchor.attr("title")
                ?: img?.attr("alt")
                ?: ""
            ).adjustEntities().trim()
        if (title.isBlank()) return null

        val posterUrl = img?.let { el ->
            listOf("data-src", "src", "data-image")
                .map { el.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        }

        val type = when {
            url.contains("/movie/") -> TvType.Movie
            url.contains("/serie/") -> TvType.Anime
            else -> TvType.Anime
        }

        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun Document.extractPosterCards(): List<SearchResponse> {
        // النموذج الأساسي (التصنيفات والبحث)
        val main = select(".poster-img-container, .swiper-slide.flex-item")
            .mapNotNull { it.toSearchResponse() }
        if (main.isNotEmpty()) return main.distinctBy { it.url }

        // نموذج AJAX الرئيسية: li.singleBB
        return select("li.singleBB").mapNotNull { it.toSearchResponse() }
    }

    // ---------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------

    override val mainPage = mainPageOf(
        "${mainUrl}/wp-content/themes/animhq/Ajax/Home/last-posts.php?x&page=" to "مضاف حديثاً",
        "${mainUrl}/wp-content/themes/animhq/Ajax/Home/most-views.php?x&page=" to "الأكثر مشاهدة",
        "${mainUrl}/wp-content/themes/animhq/Ajax/Home/most-download.php?x&page=" to "الأكثر تحميلاً",
        "$mainUrl/category/movies/?c=all&c2=series&y=all&ot=all&page=" to "مسلسلات أنمي",
        "$mainUrl/category/movies/?c=all&c2=movies&y=all&ot=all&page=" to "أفلام أنمي",
        "$mainUrl/category/free/?page=" to "شاهد مجاناً",
        "$mainUrl/category/action/?page=" to "أكشن",
        "$mainUrl/category/fantasy/?page=" to "فنتازيا",
        "$mainUrl/category/isekai/?page=" to "إسيكاي",
        "$mainUrl/category/magic/?page=" to "سحر",
        "$mainUrl/category/supernatural/?page=" to "خارق للطبيعة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = if (url.contains(".php")) {
            postPage(url)
        } else {
            getPage(url)
        }
        return newHomePageResponse(request.name, doc.extractPosterCards())
    }

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return getPage("$mainUrl/?s=$encoded").extractPosterCards()
    }

    // ---------------------------------------------------------------
    // Load (details + seasons + episodes)
    // ---------------------------------------------------------------

    private fun Document.extractSerieInfo(label: String): String? {
        val item = select(".serie-data-grid-item").firstOrNull { el ->
            el.selectFirst(".label")?.text()?.trim() == label
        }
        return item?.selectFirst(".value")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** يقرأ قائمة حلقات موسم معين من مستند صفحة المسلسل. */
    private fun Document.extractEpisodes(season: Int): List<Episode> {
        val result = arrayListOf<Episode>()
        select(".serie-episodes-watch-list-content ul li").forEach { li ->
            val watch = li.attr("data-watch").trim().fixUrl()
            if (watch.isBlank() || !watch.startsWith("http")) return@forEach
            val epNum = li.attr("epn").getIntFromText()
                ?: li.selectFirst("span")?.text()?.getIntFromText()
            result.add(
                newEpisode(watch) {
                    this.name = if (epNum != null) "الحلقة $epNum" else "الحلقة"
                    this.season = season
                    this.episode = epNum
                }
            )
        }
        return result
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url)

        val title = (
            doc.selectFirst(".main-serie-title")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title()
            ).adjustEntities().trim()

        val posterUrl = doc.selectFirst(".serie-info-poster .poster-img")?.let { el ->
            Regex("""url\(['"]?([^'")]+)['"]?\)""")
                .find(el.attr("style"))?.groupValues?.getOrNull(1)
                ?: el.attr("data-src").ifBlank { null }
        } ?: doc.selectFirst(".poster-img[data-src]")?.attr("data-src")

        val synopsis = (
            doc.selectFirst(".story-text")?.text()
                ?: doc.selectFirst("p.story-text")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            )?.trim()

        val year = doc.extractSerieInfo("العام")?.getIntFromText()

        val genres = doc.select(".serie-data-genres .value a")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }

        val isMovie = url.contains("/movie/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl?.let { it.fixUrl() }
                this.year = year
                this.plot = synopsis
                this.tags = genres
            }
        }

        // ---- مسلسل: مواسم + حلقات ----
        val episodes = arrayListOf<Episode>()

        val seasonMenu = doc.select(".season-dropdown-menu a.season-dropdown-item")
        if (seasonMenu.isEmpty()) {
            // موسم واحد معروض في الصفحة
            val seasonNum = doc.select(".season-dropdown-toggle span, .season-dropdown-toggle-disabled span")
                .firstOrNull()?.text()?.getIntFromText() ?: 1
            episodes.addAll(doc.extractEpisodes(seasonNum))
        } else {
            val currentSeasonNum = doc.select(".season-dropdown-item.active-season")
                .firstOrNull()?.text()?.getIntFromText()
                ?: doc.select(".season-dropdown-toggle span")
                    .firstOrNull()?.text()?.getIntFromText()
                ?: 1

            var index = 0
            for (seasonItem in seasonMenu) {
                index++
                val seasonNum = seasonItem.text().getIntFromText() ?: index
                val seasonUrl = seasonItem.attr("href").fixUrl()
                val seasonDoc = if (seasonNum == currentSeasonNum) doc else getPage(seasonUrl, referer = url)
                seasonDoc.extractEpisodes(seasonNum).let(episodes::addAll)
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Anime,
            uniqueEpisodes
        ) {
            this.posterUrl = posterUrl?.let { it.fixUrl() }
            this.year = year
            this.plot = synopsis
            this.tags = genres
        }
    }

    // ---------------------------------------------------------------
    // loadLinks (المشغّل)
    // ---------------------------------------------------------------

    /**
     * data قد يكون:
     *  1) صفحة embed: https://animhq.com/?embed={id}&ep={n}  (حلقة مسلسل)
     *  2) صفحة مسلسل/فيلم
     * نستخرج روابط التدفق dld من صفحة الـ embed إن كانت مباشرة،
     * وإلا نتبع data-watch/iframe للوصول إلى صفحة embed ثم نستخرج.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        suspend fun extractFromEmbed(embedPageUrl: String, referer: String): Boolean {
            val page = getPage(embedPageUrl, referer = referer)
            val clean = page.html().adjustEntities()
            var emitted = false

            val seen = mutableSetOf<String>()
            Regex("""https?://[^"'\s<>]+""").findAll(clean).forEach { match ->
                val link = match.value
                if (!link.contains("/stream/")) return@forEach
                if (!seen.add(link)) return@forEach
                runCatching {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name مباشر",
                            url = link,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to browserUA,
                                "Referer" to embedPageUrl,
                                "Origin" to mainUrl
                            )
                        }
                    )
                    emitted = true
                    foundAny = true
                }
            }
            return emitted
        }

        // 1) إن كان data صفحة embed مباشرة (حالة حلقات المسلسل) فاستخرج
        if (extractFromEmbed(data, "$mainUrl/")) return true

        // 2) صفحة مسلسل/فيلم: اتبع data-watch / iframes نحو صفحة embed
        val pageDoc = getPage(data)
        val watchUrls = linkedSetOf<String>()
        pageDoc.select("li[data-watch], a[data-watch], iframe[src*=embed], a[href*=embed=]")
            .forEach { el ->
                val raw = el.attr("data-watch")
                    .ifBlank { el.attr("src") }
                    .ifBlank { el.attr("href") }
                val fixed = raw.fixUrl()
                if (fixed.startsWith("http")) watchUrls.add(fixed)
            }

        for (watchUrl in watchUrls) {
            extractFromEmbed(watchUrl, referer = data)
        }

        return foundAny
    }
}