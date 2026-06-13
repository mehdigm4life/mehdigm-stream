package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONObject

/**
 * ArabSeed provider for CloudStream
 *
 * بُنيت بناءً على فحص بنية صفحات الموقع الحالية:
 *   https://m.arabseed.show  (النطاق الفعلي، النطاق m.asd.ink مجرد إعادة توجيه)
 *
 * النطاق متغيّر باستمرار. إذا تغيّر فقم بتحديث mainUrl.
 * نطاقات سابقة/بديلة: m.asd.ink, m.asd.homes, asd.rest, arabseed.show
 *
 * يستخدم CloudflareKiller (cfKiller) لتجاوز Cloudflare عند الحاجة.
 *
 * يدعم اكتشاف كل المواسم تلقائياً عبر صفحة /selary/ (صفحة المسلسل الأم).
 */
class ArabSeed : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://m.arabseed.show"
    override var name = "ArabSeed"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ---------- CloudflareKiller ----------
    private val cfKiller = CloudflareKiller()

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1"
    )

    /** جلب صفحة مع تمرير cfKiller تلقائياً إذا اكتُشف تحدّي Cloudflare. */
    private suspend fun getPage(url: String, referer: String = "$mainUrl/"): Document {
        // المحاولة الأولى بدون اعتراض
        val response = app.get(
            url,
            headers = baseHeaders,
            referer = referer,
            timeout = 120
        )
        val doc = response.document
        val title = doc.select("title").text()
        val blocked = response.code == 403 ||
                response.code == 503 ||
                title.contains("Just a moment", ignoreCase = true) ||
                title.contains("Attention Required", ignoreCase = true) ||
                doc.select("body").text().contains("cf-browser-verification")

        if (blocked) {
            return app.get(
                url,
                headers = baseHeaders,
                referer = referer,
                interceptor = cfKiller,
                timeout = 120
            ).document
        }
        return doc
    }

    /** POST مع نفس المنطق */
    private suspend fun postPage(
        url: String,
        data: Map<String, String>,
        referer: String = "$mainUrl/"
    ): Document {
        val response = app.post(
            url,
            data = data,
            headers = baseHeaders,
            referer = referer,
            timeout = 120
        )
        if (response.code in listOf(403, 503)) {
            return app.post(
                url,
                data = data,
                headers = baseHeaders,
                referer = referer,
                interceptor = cfKiller,
                timeout = 120
            ).document
        }
        return response.document
    }

    // ---------- Helpers ----------

    private fun String.getIntFromText(): Int? =
        Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun String.fixUrl(): String {
        if (this.isBlank()) return this
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            this.startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    /** فك تشفير base64 المستخدم في /play.php?url=... */
    private fun decodeBase64Url(b64: String): String? = try {
        String(Base64.getDecoder().decode(b64.trim()))
    } catch (_: Throwable) {
        try {
            String(Base64.getUrlDecoder().decode(b64.trim()))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * استخراج رابط المضمَّن (embed) الحقيقي من قيمة data-link / iframe src.
     *  - إذا كان رابطاً مباشراً → يُعاد كما هو
     *  - إذا كان من شكل /play.php?url=BASE64  → نفكّ الـ base64 ونعيد الـ URL الأصلي
     *  - يُكمل أي مسار نسبي بـ mainUrl
     */
    private fun resolveServerLink(raw: String): String? {
        if (raw.isBlank()) return null
        val link = raw.fixUrl()

        // /play.php?url=  أو  /play/?id=
        val regex = Regex("""[?&](?:url|id)=([A-Za-z0-9+/=_\-]+)""")
        regex.find(link)?.groupValues?.getOrNull(1)?.let { encoded ->
            decodeBase64Url(encoded)?.let { decoded ->
                if (decoded.startsWith("http")) return decoded
            }
        }
        return link
    }

    // ---------- Arabic season number parsing ----------

    /**
     * يحوّل اسم الموسم العربي إلى رقم.
     * "الأول"→1, "الثاني عشر"→12, "الخامس عشر"→15, "15"→15, "S5"→5
     */
    private fun parseArabicSeasonNumber(text: String): Int? {
        if (text.isBlank()) return null
        // أولاً: محاولة رقمية مباشرة (S5, الموسم 5, ...)
        Regex("""(?:الموسم|season|s)\s*[-_:]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        // ثانياً: أسماء عربية ترتيبية
        // ملاحظة: نتحقق من المركّبة (عشر) قبل البسيطة لتجنّب التطابق الخاطئ
        val arabicOrdinals = listOf(
            // المركّبة أولاً
            "الخامس عشر" to 15,
            "الرابع عشر" to 14,
            "الثالث عشر" to 13,
            "الثاني عشر" to 12,
            "الحادي عشر" to 11,
            "الحاديه عشر" to 11,
            "الحاديةعشر" to 11,
            // البسيطة
            "العاشر" to 10,
            "التاسع" to 9,
            "الثامن" to 8,
            "السابع" to 7,
            "السادس" to 6,
            "الخامس" to 5,
            "الرابع" to 4,
            "الثالث" to 3,
            "الثاني" to 2,
            "الثانى" to 2,
            "الأول" to 1,
            "الاول" to 1,
            "الأولى" to 1,
            "الاولى" to 1
        )
        for ((name, num) in arabicOrdinals) {
            if (text.contains(name)) return num
        }
        // ثالثاً: رقم أي أرقام مرتبطة بكلمة الموسم (احتياط)
        val rawNum = Regex("""الموسم[^0-9]*?(\d{1,2})""").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return rawNum
    }

    /**
     * يستخرج اسم المسلسل من العنوان عبر إزالة "الموسم X" و "الحلقة Y" والوصف الزائد.
     */
    private fun extractSeriesBaseTitle(fullTitle: String): String {
        var t = fullTitle
        // إزالة "الموسم ..." حتى نهاية النص أو حتى "الحلقة"
        t = t.replace(
            Regex("""\s*الموسم\s+\S+(?:\s+عشر)?(?:\s+الحلقة.*)?$"""),
            ""
        )
        // إزالة "الحلقة ..."
        t = t.replace(Regex("""\s*الحلقة\s+.*$"""), "")
        // إزالة "مترجم/مترجمة" وما يتبعها
        t = t.replace(Regex("""\s*(?:مترجم(?:ة)?|مدبلج(?:ة)?).*$"""), "")
        // إزالة "S5", "season 5"
        t = t.replace(Regex("""\s*(?:season|s)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
        return t.trim()
    }

    // ---------- Item parsing (cards in lists) ----------

    /**
     * يحوّل عنصر <li>/<div> داخل قوائم الموقع إلى SearchResponse.
     * الهيكل الحالي:
     *   <li class="box__xs__2 ...">
     *     <div class="item__contents">
     *       <a class="movie__block" href="..." title="...">
     *         <div class="post__image"><img alt="..." src="..."></div>
     *         <div class="post__category hide__md">أفلام أجنبية</div>
     *         <div class="post__name">...</div>
     *       </a>
     *     </div>
     *   </li>
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName() == "a") this
        else selectFirst("a.movie__block")
            ?: selectFirst("a[href]")
            ?: return null

        val url = anchor.attr("href").fixUrl()
        if (url.isBlank() || url == "#" || !url.startsWith("http")) return null

        val img = selectFirst("img") ?: anchor.selectFirst("img")
        val title = (
            selectFirst(".post__name, .movie__name, .item__name, h4, h3")?.text()
                ?: anchor.attr("title")
                ?: img?.attr("alt")
                ?: ""
            ).ifBlank { return null }

        val posterUrl = img?.let { el ->
            listOf("data-src", "data-image", "data-lazy-src", "src")
                .map { el.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        }

        // محاولة تخمين النوع
        val cat = selectFirst(".post__category, .__category")?.text().orEmpty()
        val isSeries = title.contains("مسلسل") ||
                title.contains("الموسم") ||
                title.contains("حلقة") ||
                cat.contains("مسلسل") ||
                url.contains("series") ||
                url.contains("/selary/")
        val isAnime = cat.contains("انمي") || cat.contains("أنمي") || title.contains("انمي")

        val tvType = when {
            isAnime -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, url, tvType) {
            this.posterUrl = posterUrl
        }
    }

    /** قراءة جميع العناصر من صفحة قائمة، مع fallback عام إن لم تتطابق المحددات. */
    private fun Document.extractItems(): List<SearchResponse> {
        val selectors = listOf(
            "ul.movie__blocks__ul > li",
            "ul.boxs__wrapper > li",
            "ul.Blocks-UL > li",
            "ul.Blocks-UL > div",
            "div.MovieBlock",
            "div.BlockItem",
            "div.postDiv",
            "li.MovieBlock",
            ".box__xs__2"
        )
        for (selector in selectors) {
            val items = select(selector).mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) return items.distinctBy { it.url }
        }
        // fallback عام: أي رابط فيه صورة وعنوان معقول
        return select("a:has(img)")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    /**
     * يجمع نتائج البحث: إذا ظهر نفس المسلسل عدة مرات (موسم 1، موسم 2، …) نحتفظ
     * بنتيجة واحدة فقط ونفضّل رابط /selary/ بدون "الموسم" (الصفحة الأم) إن وُجد.
     */
    private fun List<SearchResponse>.collapseSeasons(): List<SearchResponse> {
        // نقسم النتائج إلى:
        //   - مرشحون للتجميع: TvSeries / Anime يحتويون "الموسم" أو "/selary/"
        //   - نتائج لا تتغير: الباقي
        val keepAsIs = arrayListOf<SearchResponse>()
        val groups = linkedMapOf<String, MutableList<SearchResponse>>()

        forEach { item ->
            val isCandidate = (item.type == TvType.TvSeries || item.type == TvType.Anime) &&
                    (item.name.contains("الموسم") ||
                            item.name.contains("الحلقة") ||
                            item.url.contains("/selary/") ||
                            Regex("""s\d+""", RegexOption.IGNORE_CASE).containsMatchIn(item.url))
            if (!isCandidate) {
                keepAsIs.add(item)
                return@forEach
            }
            val baseTitle = extractSeriesBaseTitle(item.name).lowercase().trim()
            if (baseTitle.isBlank()) {
                keepAsIs.add(item)
                return@forEach
            }
            groups.getOrPut(baseTitle) { arrayListOf() }.add(item)
        }

        val collapsed = arrayListOf<SearchResponse>()
        for ((base, items) in groups) {
            // الأفضلية: رابط /selary/ بدون "الموسم"، ثم رابط /selary/، ثم أول رابط بالموسم الأول
            val selaryParent = items.firstOrNull {
                it.url.contains("/selary/") && !it.name.contains("الموسم")
            }
            val anySelary = items.firstOrNull { it.url.contains("/selary/") }
            val firstSeason = items.minByOrNull { result ->
                parseArabicSeasonNumber(result.name) ?: Int.MAX_VALUE
            }
            val chosen = selaryParent ?: anySelary ?: firstSeason ?: items.first()

            // نُنظّف العنوان من "الموسم X"
            val type = chosen.type ?: TvType.Movie

val unified = newMovieSearchResponse(extractSeriesBaseTitle(chosen.name), chosen.url, type) {
    this.posterUrl = chosen.posterUrl
}
            collapsed.add(unified)
        }

        return keepAsIs + collapsed
    }

    // ---------- Main page ----------

    override val mainPage = mainPageOf(
        "$mainUrl/main10/?page_number="                       to "أحدث الإضافات",
        "$mainUrl/recently/?page_number="                     to "مضاف حديثاً",
        "$mainUrl/trend/?page_number="                        to "تريند",
        "$mainUrl/movies-5/?page_number="                     to "الأفلام",
        "$mainUrl/series-5/?page_number="                     to "المسلسلات",
        "$mainUrl/category/foreign-movies-17/?page_number="   to "أفلام أجنبية",
        "$mainUrl/category/foreign-series-9/?page_number="    to "مسلسلات أجنبية",
        "$mainUrl/category/netflix/netflix-movies/?page_number=" to "أفلام نتفليكس",
        "$mainUrl/category/netflix/netflix-series/?page_number=" to "مسلسلات نتفليكس",
        "$mainUrl/category/asian-movies-2/?page_number="      to "أفلام آسيوية",
        "$mainUrl/category/turkish-series-2/?page_number="    to "مسلسلات تركية",
        "$mainUrl/category/arabic-movies-14/?page_number="    to "أفلام عربية",
        "$mainUrl/category/arabic-series-14/?page_number="    to "مسلسلات عربية",
        "$mainUrl/category/indian-movies-2/?page_number="     to "أفلام هندية",
        "$mainUrl/category/dubbed-movies/?page_number="       to "أفلام مدبلجة",
        "$mainUrl/category/cartoon-series/?page_number="      to "مسلسلات كرتون"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var document = getPage(request.data + page)
        var list = document.extractItems()

        // fallback: المسار بدون باراميتر
        if (list.isEmpty() && page == 1) {
            document = getPage(request.data.substringBefore("?"))
            list = document.extractItems()
        }
        // fallback أخير: home7
        if (list.isEmpty() && page == 1) {
            document = getPage("$mainUrl/home7/")
            list = document.extractItems()
        }

        // دمج المواسم المتعددة لنفس المسلسل في نتيجة واحدة
        return newHomePageResponse(request.name, list.collapseSeasons())
    }

    // ---------- Search ----------

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = arrayListOf<SearchResponse>()

        // 1) صفحة البحث الرسمية الحالية: /find/?word=...&type=
        runCatching {
            results.addAll(getPage("$mainUrl/find/?word=$encoded&type=").extractItems())
        }

        // 2) البحث القديم لمشاركة الـ ووردبريس s=
        if (results.isEmpty()) runCatching {
            results.addAll(getPage("$mainUrl/?s=$encoded").extractItems())
        }

        // 3) AJAX قديم: قد لا يزال يعمل على بعض المرايا
        if (results.isEmpty()) {
            listOf("movies", "series").forEach { type ->
                runCatching {
                    val doc = postPage(
                        "$mainUrl/wp-content/themes/Elshaikh2021/Ajaxat/SearchingTwo.php",
                        mapOf("search" to query, "type" to type)
                    )
                    results.addAll(doc.extractItems())
                }
            }
        }

        // دمج المواسم المتعددة لنفس المسلسل في نتيجة واحدة
        return results.distinctBy { it.url }.collapseSeasons()
    }

    // ---------- Load (details + episodes) ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url)

        val title = (
            doc.selectFirst("h1.post__name")?.text()
                ?: doc.selectFirst("h1.Title, div.Title, h1.post-title, h1.entry-title")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("title")?.text()
                ?: ""
            ).trim()

        // البوستر: الصورة الكبيرة في .poster__single ثم meta og:image
        val posterUrl = (
            doc.selectFirst(".poster__single img")?.let { img ->
                listOf("data-src", "src", "data-image").map { img.attr(it) }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            }
                ?: doc.selectFirst(".post__image img")?.let { img ->
                    listOf("data-src", "src").map { img.attr(it) }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            )?.fixUrl()

        // القصة
        val synopsis = (
            doc.selectFirst(".post__story p")?.text()
                ?: doc.selectFirst(".post__story")?.text()
                ?: doc.selectFirst(".mobile__story")?.text()
                ?: doc.selectFirst("div.StoryLine p, div.story")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            )?.trim()

        // تفاصيل العرض: السنة / النوع / التصنيف من .info__area__ul > li
        val infoLis = doc.select(".info__area__ul > li")
        var year: Int? = null
        val tags = arrayListOf<String>()
        var siteCategory = ""

        infoLis.forEach { li ->
            val label = li.selectFirst(".title__kit span")?.text()?.trim().orEmpty()
            when {
                "سنة" in label -> {
                    year = li.select("a").firstOrNull()?.text()?.getIntFromText() ?: year
                }
                "نوع" in label -> {
                    li.select("a").forEach { a ->
                        val t = a.text().trim()
                        if (t.isNotBlank() && t != ":") tags.add(t)
                    }
                }
                "تصنيف" in label -> {
                    siteCategory = li.select("a").joinToString(",") { it.text() }
                }
            }
        }
        // fallback للسنة من العنوان
        if (year == null) {
            year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        }

        // ============= جمع الحلقات =============
        val episodes = arrayListOf<Episode>()

        // 1) قائمة المواسم عبر #seasons__list li[data-term]
        //    كل موسم يُجلب عبر AJAX: POST {mainUrl}/season__episodes/ {season_id, csrf_token} ← JSON {html}
        //    الموسم النشط حالياً في الصفحة محمّل مسبقاً في .episodes__list
        val seasonElements = doc.select("#seasons__list li[data-term]")
        if (seasonElements.isNotEmpty()) {
            // استخرج CSRF token من كود JavaScript في الصفحة
            val pageHtml = doc.html()
            val csrfToken = Regex("""csrf__token['"]?\s*:\s*['"]([^'"]+)['"]""")
                .find(pageHtml)?.groupValues?.getOrNull(1)

            // الحلقات المحملة مسبقاً (الموسم النشط)
            doc.select(".episodes__list > li > a").forEach { ep ->
                val href = ep.attr("href").fixUrl()
                if (href.isNotBlank() && href.startsWith("http")) {
                    val epNum = ep.selectFirst(".epi__num b")?.text()?.getIntFromText()
                    episodes.add(newEpisode(href) {
                        this.name = if (epNum != null) "الحلقة $epNum" else ep.text()
                        this.episode = epNum
                    })
                }
            }

            // المواسم المتبقية: نجلبها تتابعياً عبر AJAX
            for (seasonElement in seasonElements) {
                if (seasonElement.hasClass("selected") && episodes.isNotEmpty()) continue
                val termId = seasonElement.attr("data-term")
                if (termId.isBlank()) continue
                val seasonNumber = parseArabicSeasonNumber(seasonElement.text()) ?: continue
                if (seasonNumber > 30) continue

                runCatching {
                    val resp = app.post(
                        "$mainUrl/season__episodes/",
                        data = mapOf("season_id" to termId, "csrf_token" to (csrfToken ?: "")),
                        headers = baseHeaders,
                        referer = url,
                        timeout = 25
                    )
                    val bodyText = if (resp.code in listOf(403, 503)) {
                        app.post(
                            "$mainUrl/season__episodes/",
                            data = mapOf("season_id" to termId, "csrf_token" to (csrfToken ?: "")),
                            headers = baseHeaders,
                            referer = url,
                            interceptor = cfKiller,
                            timeout = 25
                        ).text
                    } else {
                        resp.text
                    }
                    val episodeHtml = try {
                        JSONObject(bodyText).optString("html")
                    } catch (_: Exception) { null }
                    if (episodeHtml.isNullOrBlank()) return@runCatching
                    val episodeDoc = Jsoup.parse(episodeHtml)
                    episodeDoc.select("a[href]").forEach { ep ->
                        val href = ep.attr("href").fixUrl()
                        if (href.isNotBlank() && href.startsWith("http")) {
                            val epNum = ep.selectFirst(".epi__num b")?.text()?.getIntFromText()
                            episodes.add(newEpisode(href) {
                                this.name = if (epNum != null) "الحلقة $epNum" else ep.text()
                                this.season = seasonNumber
                                this.episode = epNum
                            })
                        }
                    }
                }
            }
        }

        // 2) احتياط: .episodes__list في حالة عدم وجود seasons__list
        if (episodes.isEmpty()) {
            val seasonGuess = Regex("""(?:الموسم|S)\s*([0-9]{1,2})""", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

            doc.select(".episodes__list li a, .episodes__list > a").forEach { ep ->
                val href = ep.attr("href").fixUrl()
                if (href.isBlank() || !href.startsWith("http")) return@forEach
                val epNumText = ep.selectFirst(".epi__num b")?.text()
                    ?: ep.selectFirst(".epi__num")?.text()
                    ?: ep.text()
                val epNum = epNumText.getIntFromText()
                episodes.add(newEpisode(href) {
                    this.name = if (epNum != null) "الحلقة $epNum" else ep.text()
                    this.season = seasonGuess
                    this.episode = epNum
                })
            }
        }

        // 3) أنماط حلقات قديمة محتملة
        if (episodes.isEmpty()) {
            doc.select(
                "div.ContainerEpisodesList > a, div.EpisodesList > a, " +
                "ul.episodes-list a, div.epAll a"
            ).forEach { ep ->
                val href = ep.attr("href").fixUrl()
                if (href.isNotBlank() && href.startsWith("http")) {
                    episodes.add(newEpisode(href) {
                        this.name = ep.text()
                        this.episode = ep.text().getIntFromText()
                    })
                }
            }
        }

        // الممثلون (إن وجدوا)
        val actors = doc.select(".__actor__item, div.WorkTeamIteM").mapNotNull { item ->
            val name = item.selectFirst(".__actor__name, h4 > em, .name")?.text()
                ?: return@mapNotNull null
            val image = item.selectFirst("img")?.let {
                listOf("data-src", "src").map { a -> it.attr(a) }
                    .firstOrNull { v -> v.isNotBlank() }
            }
            ActorData(
                actor = Actor(name, image),
                roleString = item.selectFirst(".__actor__role, h4 > span")?.text()
            )
        }

        // تحديد النوع: الأنمي ← TvType.Anime، مسلسل بدون حلقات لكن العنوان "مسلسل" ← TvSeries
        val isAnime = siteCategory.contains("انمي") || siteCategory.contains("أنمي") ||
                tags.any { "انمي" in it || "أنمي" in it }
        val finalSeriesType = if (isAnime) TvType.Anime else TvType.TvSeries

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.actors = actors
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                finalSeriesType,
                episodes
                    .distinctBy { it.data }
                    .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.actors = actors
            }
        }
    }

    // ---------- loadLinks (servers + iframe + download mirrors) ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        val postDoc = getPage(data)

        // اكتشاف رابط صفحة المشاهدة
        val watchUrl = postDoc.selectFirst(
            "a.btton.watch__btn, " +
            "a.watch__btn, a.watchBTn, a.WatchButton, " +
            "a[href$='/watch/']"
        )?.attr("href")?.fixUrl()
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: (data.trimEnd('/') + "/watch/")

        val watchDoc = runCatching { getPage(watchUrl, referer = data) }
            .getOrNull() ?: postDoc

        // 1) قائمة السيرفرات في صفحة المشاهدة
        watchDoc.select(
            ".servers__list li[data-link], " +
            "ul.servers__list li[data-link], " +
            "li[data-link], li[data-server], " +
            ".containerServers ul li, ul.serversList li"
        ).amap { server ->
            val raw = server.attr("data-link")
                .ifBlank { server.attr("data-server") }
                .ifBlank { server.attr("data-src") }
            val resolved = resolveServerLink(raw)
            if (resolved != null && resolved.startsWith("http")) {
                runCatching {
                    if (loadExtractor(resolved, watchUrl, subtitleCallback, callback)) {
                        foundAny = true
                    }
                }
            }
        }

        // 2) iframes ظاهرة (قد تكون داخل /play.php?url=BASE64)
        watchDoc.select("iframe").amap { frame ->
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }
            val resolved = resolveServerLink(src)
            if (resolved != null && resolved.startsWith("http")) {
                runCatching {
                    if (loadExtractor(resolved, watchUrl, subtitleCallback, callback)) {
                        foundAny = true
                    }
                }
            }
        }

        // 3) صفحة التحميل (روابط بدائل بجودات مختلفة)
        val downloadUrl = postDoc.selectFirst("a.download__btn, a.downloadBTn")
            ?.attr("href")
            ?.fixUrl()
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: (data.trimEnd('/') + "/download/")

        runCatching {
            val dlDoc = getPage(downloadUrl, referer = data)
            dlDoc.select(
                ".downloads__links__list a.download__item, " +
                ".downloads__links__list a, " +
                ".download__box a[href], " +
                "div.DownloadsArea a[href], div.DownloadArea a[href], a.downloadBTn"
            ).amap { anchor ->
                val link = anchor.attr("href").fixUrl()
                if (link.isNotBlank() && link.startsWith("http") &&
                    link != data && link != watchUrl && link != downloadUrl &&
                    !link.contains("arabseed") && !link.contains("asd.ink")
                ) {
                    runCatching {
                        if (loadExtractor(link, downloadUrl, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                }
            }
        }

        return foundAny
    }
}
