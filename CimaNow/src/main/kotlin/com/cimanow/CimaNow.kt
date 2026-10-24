package com.cimanow

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class CimaNow : MainAPI() {

    override var mainUrl: String
        get() = CimaNowNetwork.mainUrl
        set(_) {}

    override var name = "سيما ناو (CimaNow)"
    override var lang = "ar"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "الاحدث" to "أحدث الإضافات",
        "الاكثر-مشاهدة" to "الأكثر مشاهدة",
        "category/افلام-اجنبية" to "أفلام أجنبية",
        "category/افلام-عربية" to "أفلام عربية",
        "category/افلام-تركية" to "أفلام تركية",
        "category/افلام-كورية" to "أفلام كورية",
        "category/افلام-هندية" to "أفلام هندية",
        "category/افلام-انيميشن" to "أفلام أنيميشن",
        "category/افلام-مارفل" to "أفلام مارفل",
        "category/افلام-نتفليكس" to "أفلام نتفليكس",
        "category/مسلسلات-اجنبية" to "مسلسلات أجنبية",
        "category/مسلسلات-عربية" to "مسلسلات عربية",
        "category/مسلسلات-تركية" to "مسلسلات تركية",
        "category/مسلسلات-كورية" to "مسلسلات كورية",
        "category/مسلسلات-انيميشن" to "مسلسلات أنيميشن",
        "category/مسلسلات-نتفليكس" to "مسلسلات نتفليكس",
        "category/رمضان-2026" to "مسلسلات رمضان 2026",
        "category/رمضان-2025" to "مسلسلات رمضان 2025",
        "category/رمضان" to "أرشيف مسلسلات رمضان",
        "category/البرامج-التلفزيونية" to "البرامج التلفزيونية",
        "category/مسرحيات" to "مسرحيات",
        "category/حفلات" to "حفلات",
        "category/اطفال" to "أطفال",
    )

    private val posterHeaders: Map<String, String>
        get() = mapOf("Referer" to "$mainUrl/")

    // ── 1. Main Page Catalogs & Pagination ───────────────────────────────────

    private val singlePageHubSlugs = setOf(
        "الاكثر-مشاهدة",
        "category/رمضان",
        "category/اطفال",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data.trim('/')
        val isKnownSinglePage = slug in singlePageHubSlugs || slug.contains("الاكثر-مشاهدة")
        if (page > 1 && isKnownSinglePage) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val targetUrl = if (page <= 1) {
            "$mainUrl/$slug/"
        } else {
            "$mainUrl/$slug/page/$page/"
        }

        val pageRes = CimaNowNetwork.getPage(targetUrl, "$mainUrl/home/")
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val isPaginatedArchive = pageRes.doc.selectFirst("section[aria-label=posts]") != null ||
            pageRes.doc.selectFirst("ul[aria-label=pagination]") != null

        if (page > 1 && !isPaginatedArchive) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = parseListingCards(pageRes.doc)
        val hasNext = if (isKnownSinglePage || !isPaginatedArchive) {
            false
        } else {
            val nextMarker = "/page/${page + 1}/"
            pageRes.doc.select("ul[aria-label=pagination] li a[href]").any { a ->
                a.attr("href").contains(nextMarker)
            }
        }

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ── 2. Search & Quick Search ─────────────────────────────────────────────

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return newSearchResponseList(emptyList(), false)

        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val searchUrl = if (page <= 1) {
            "$mainUrl/?s=$encodedQ"
        } else {
            "$mainUrl/page/$page/?s=$encodedQ"
        }

        val pageRes = CimaNowNetwork.getPage(searchUrl, "$mainUrl/home/")
            ?: return newSearchResponseList(emptyList(), false)

        val items = parseListingCards(pageRes.doc)
        val nextMarker = "/page/${page + 1}/"
        val hasNext = pageRes.doc.select("ul[aria-label=pagination] li a[href]").any { a ->
            a.attr("href").contains(nextMarker)
        }
        return newSearchResponseList(items, hasNext)
    }

    // ── 3. Listing Card Parser ───────────────────────────────────────────────

    private fun parseListingCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seenUrls = HashSet<String>()

        val anchors = doc.select("section[aria-label=posts] article[aria-label=post] > a[href]")
            .ifEmpty { doc.select("article[aria-label=post] > a[href]") }
            .ifEmpty { doc.select("section a[href]:has(li[aria-label=title]), section a[href]:has(ul.info), a[href]:has(li[aria-label=title])") }
            .ifEmpty { doc.select("section article > a[href], div.owl-carousel article > a[href], article > a[href]") }

        for (a in anchors) {
            val item = parseCardAnchor(a) ?: continue
            if (seenUrls.add(item.url)) {
                out.add(item)
            }
        }
        return out
    }

    private fun isLogoImage(img: Element): Boolean {
        val alt = img.attr("alt").trim()
        if (alt.equals("logo", ignoreCase = true) || alt == "لوجو") return true
        val imgUrl = decodeUrlReadable(extractCleanImageUrl(img).orEmpty())
        return imgUrl.contains("لوجو") ||
            Regex("""(?:^|[-_/])logo(?:\.[a-z0-9]+|\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(imgUrl)
    }

    private fun extractTitleFromSlug(decodedHref: String): String {
        val slug = decodedHref.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return ""
        return slug.replace('-', ' ').replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
    }

    internal fun parseCardAnchor(a: Element): SearchResponse? {
        val rawHref = a.attr("href").trim()
        if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", true)) return null
        val fullUrl = CimaNowNetwork.encodeUrl(rawHref)
        val decodedHref = decodeUrlReadable(fullUrl)
        if (decodedHref.endsWith("/home/") || decodedHref.contains("/category/") ||
            decodedHref.contains("/release-year/") || decodedHref.contains("/actor/") ||
            decodedHref.contains("/director/") || decodedHref.contains("/escritor/") ||
            decodedHref.contains("/translator/") || decodedHref.contains("/tag/") ||
            decodedHref.contains("/watching/") ||
            decodedHref.contains("/news/") || decodedHref.endsWith("/news")
        ) {
            return null
        }

        val titleLi = a.selectFirst("li[aria-label=title]")
            ?: a.select("ul.info > li").lastOrNull { !it.hasAttr("aria-label") || it.attr("aria-label") == "title" }

        val nonLogoAlt = a.select("img[alt]")
            .firstOrNull { img ->
                val alt = img.attr("alt").trim()
                alt.isNotBlank() && !alt.equals("logo", ignoreCase = true) && alt != "لوجو" && !isLogoImage(img)
            }
            ?.attr("alt")
            ?.trim()
            .orEmpty()
            .ifBlank {
                a.select("img[alt]")
                    .map { it.attr("alt").trim() }
                    .firstOrNull { it.isNotBlank() && !it.equals("logo", ignoreCase = true) && it != "لوجو" }
                    .orEmpty()
            }

        val rawTitle = titleLi?.text()?.trim()?.takeIf { !it.equals("logo", ignoreCase = true) }.orEmpty()
            .ifBlank { a.attr("title").trim().takeIf { !it.equals("logo", ignoreCase = true) }.orEmpty() }
            .ifBlank { nonLogoAlt }
            .ifBlank { a.ownText().trim().takeIf { !it.equals("logo", ignoreCase = true) }.orEmpty() }
            .ifBlank { extractTitleFromSlug(decodedHref) }
        if (rawTitle.isBlank()) return null

        val yearText = a.selectFirst("li[aria-label=year]")?.text()?.trim()
        val year = yearText?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").findAll(rawTitle).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

        val cleanTitle = cleanCardTitle(titleLi, rawTitle, year)
        if (cleanTitle.isBlank()) return null

        val ribbon = a.selectFirst("li[aria-label=ribbon]")?.text()?.trim().orEmpty()
        val tabBadges = a.select("ul.info li[aria-label=tab], li[aria-label=tab]").joinToString(" ") { it.text().trim() }
        val epText = a.selectFirst("li[aria-label=episode]")?.text()?.trim().orEmpty()
        val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
            ?: Regex("""(?:ح|حلقة|الحلقة)\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Recommendation (`#related`) cards contain `<img alt="logo" src="...-لوجو.png">` before the real cover `<img>`
        val imgs = a.select("img[data-src], img[data-lazy-src], img[src]")
        val imgEl = imgs.firstOrNull { img ->
            extractCleanImageUrl(img) != null && !isLogoImage(img)
        } ?: imgs.lastOrNull()
        val poster = extractCleanImageUrl(imgEl)

        val contextStr = "$rawTitle $ribbon $tabBadges $decodedHref"
        val isSeries = fullUrl.contains("/selary/", true) ||
            epNum != null ||
            contextStr.contains("مسلسل") ||
            contextStr.contains("برنامج") ||
            contextStr.contains("رمضان") ||
            Regex("""(?:^|\s)ج\d+(?:\s|$)""").containsMatchIn(rawTitle)

        val isAnime = contextStr.contains("انيميشن") || contextStr.contains("أنيميشن") ||
            contextStr.contains("انمي") || contextStr.contains("أنمي")
        val isCartoon = contextStr.contains("كرتون") || contextStr.contains("اطفال") || contextStr.contains("أطفال")
        val isAsian = contextStr.contains("كوري") || contextStr.contains("آسيوي") ||
            contextStr.contains("اسيوي") || contextStr.contains("ياباني") || contextStr.contains("صيني")

        val tvType = when {
            isAnime && isSeries -> TvType.Anime
            isAnime -> TvType.AnimeMovie
            isCartoon && !isSeries -> TvType.Cartoon
            isAsian && isSeries -> TvType.AsianDrama
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        val isDub = contextStr.contains("مدبلج")
        val isSub = contextStr.contains("مترجم")
        val quality = getQualityFromString("$ribbon $tabBadges")

        return if (isSeries) {
            newAnimeSearchResponse(cleanTitle, fullUrl, tvType) {
                this.posterUrl = poster
                this.posterHeaders = this@CimaNow.posterHeaders
                this.year = year
                this.quality = quality
                addDubStatus(isDub, isSub || !isDub, epNum, epNum)
            }
        } else {
            newMovieSearchResponse(cleanTitle, fullUrl, tvType) {
                this.posterUrl = poster
                this.posterHeaders = this@CimaNow.posterHeaders
                this.year = year
                this.quality = quality
            }
        }
    }

    private fun cleanCardTitle(titleLi: Element?, fallback: String, year: Int?): String {
        if (titleLi != null) {
            val own = titleLi.ownText().trim()
            val seasonEm = titleLi.select("em").map { it.text().trim() }
                .filter { it.startsWith("ج") || it.contains("موسم") }
                .joinToString(" ")
            val base = if (own.isNotBlank()) {
                if (seasonEm.isNotBlank() && !own.contains(seasonEm)) "$own $seasonEm" else own
            } else {
                fallback
            }
            var cleaned = base.replace(Regex("""\s+"""), " ").trim()
            if (year != null) {
                val withoutYear = cleaned.replace(Regex("""\s+$year$"""), "").trim()
                if (withoutYear.isNotBlank()) cleaned = withoutYear
            }
            return cleaned.ifBlank { fallback }
        }

        // Recommendation (`#related`) or `img[alt]` / URL-slug title cleaning:
        // e.g. "فيلم Tenzing 2026 مترجم" -> "Tenzing", "مسلسل The Terminal List الموسم الثاني مترجم" -> "The Terminal List الموسم الثاني"
        var cleaned = fallback.replace(Regex("""\s+"""), " ").trim()
        cleaned = cleaned.replace(
            Regex("""^(?:مشاهدة\s+(?:و\s*تحميل\s+)?)?(?:فيلم|مسلسل|برنامج|انمي|أنمي|مسرحية|حفل|حفلة|كرتون)\s+"""),
            "",
        ).trim()
        cleaned = cleaned.replace(
            Regex("""(?:\s+(?:مترجمة?|مدبلجة?|اون\s*لاين|أون\s*لاين|كاملة?|بجودة\s+عالية|HD))+?$""", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        if (year != null) {
            val withoutYear = cleaned.replace(Regex("""\s+$year$"""), "").trim()
            if (withoutYear.isNotBlank()) cleaned = withoutYear
        }
        return cleaned.ifBlank { fallback.trim() }
    }

    // ── 4. Rich Detail / Series / Episode `load(url)` ────────────────────────

    private data class RichMeta(
        val title: String,
        val posterUrl: String?,
        val backgroundUrl: String?,
        val plot: String?,
        val year: Int?,
        val durationMinutes: Int?,
        val tags: List<String>,
        val actors: List<ActorData>,
        val trailerUrl: String?,
        val shineUrl: String?,
        val recommendations: List<SearchResponse>,
    )

    override suspend fun load(url: String): LoadResponse? {
        val targetUrl = CimaNowNetwork.encodeUrl(url)
        val pageRes = CimaNowNetwork.getPage(targetUrl, "$mainUrl/home/") ?: return null
        val doc = pageRes.doc
        val finalUrl = pageRes.finalUrl

        val isSelaryLanding = finalUrl.contains("/selary/", true)
        val seasonLinks = parseSeasonLinks(doc)
        val directEpsOnPage = parseEpisodesFromContainer(doc, inferSeasonNumberFromPage(doc, finalUrl))

        val isSeries = isSelaryLanding || seasonLinks.isNotEmpty() || directEpsOnPage.isNotEmpty()

        if (!isSeries) {
            val meta = extractRichMetadata(doc, finalUrl)
            val contextStr = "${meta.title} ${meta.tags.joinToString(" ")} ${decodeUrlReadable(finalUrl)}"
            val tvType = when {
                contextStr.contains("انيميشن") || contextStr.contains("انمي") || contextStr.contains("أنمي") -> TvType.AnimeMovie
                contextStr.contains("كرتون") -> TvType.Cartoon
                else -> TvType.Movie
            }
            val dataPayload = JSONObject()
                .put("itemUrl", finalUrl)
                .apply { if (!meta.shineUrl.isNullOrBlank()) put("shineUrl", meta.shineUrl) }
                .toString()

            return newMovieLoadResponse(meta.title, finalUrl, tvType, dataPayload) {
                this.posterUrl = meta.posterUrl
                this.posterHeaders = this@CimaNow.posterHeaders
                this.backgroundPosterUrl = meta.backgroundUrl ?: meta.posterUrl
                this.plot = meta.plot
                this.year = meta.year
                this.duration = meta.durationMinutes
                this.tags = meta.tags
                this.actors = meta.actors
                this.recommendations = meta.recommendations
                meta.trailerUrl?.let { addTrailer(it) }
            }
        }

        // Series / Multi-Season Resolution
        val allEpisodes = ArrayList<Episode>()
        val seenEpUrls = HashSet<String>()

        if (seasonLinks.isNotEmpty()) {
            val sem = Semaphore(4)
            val perSeasonLists = coroutineScope {
                seasonLinks.map { (seasonNum, seasonUrl) ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val sDoc = if (normalizeUrlKey(seasonUrl) == normalizeUrlKey(finalUrl)) {
                                doc
                            } else {
                                CimaNowNetwork.getPage(seasonUrl, finalUrl)?.doc
                            }
                            if (sDoc != null) parseEpisodesFromContainer(sDoc, seasonNum) else emptyList()
                        }
                    }
                }.awaitAll()
            }
            for (list in perSeasonLists) {
                for (ep in list) {
                    if (seenEpUrls.add(normalizeUrlKey(ep.data))) {
                        allEpisodes.add(ep)
                    }
                }
            }
        }

        if (allEpisodes.isEmpty()) {
            for (ep in directEpsOnPage) {
                if (seenEpUrls.add(normalizeUrlKey(ep.data))) {
                    allEpisodes.add(ep)
                }
            }
        }

        // Sort seasons ascending, and episodes within each season ascending
        allEpisodes.sortWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE },
        )

        var meta = extractRichMetadata(doc, finalUrl)

        // If user opened a `/selary/` landing page that lacks plot/cast/trailer/recommendations, enrich from first episode
        if ((meta.plot.isNullOrBlank() || meta.actors.isEmpty() || meta.recommendations.isEmpty()) && allEpisodes.isNotEmpty()) {
            val firstEpUrl = CimaNowCrypto.safeJsonObject(allEpisodes.first().data)?.optString("itemUrl")
                ?: allEpisodes.first().data
            val epPage = CimaNowNetwork.getPage(firstEpUrl, finalUrl)
            if (epPage != null) {
                val epMeta = extractRichMetadata(epPage.doc, epPage.finalUrl)
                meta = meta.copy(
                    posterUrl = meta.posterUrl ?: epMeta.posterUrl,
                    backgroundUrl = meta.backgroundUrl ?: epMeta.backgroundUrl,
                    plot = meta.plot?.takeIf { it.isNotBlank() } ?: epMeta.plot,
                    year = meta.year ?: epMeta.year,
                    durationMinutes = meta.durationMinutes ?: epMeta.durationMinutes,
                    tags = (meta.tags + epMeta.tags).distinct(),
                    actors = meta.actors.ifEmpty { epMeta.actors },
                    trailerUrl = meta.trailerUrl ?: epMeta.trailerUrl,
                    recommendations = meta.recommendations.ifEmpty { epMeta.recommendations },
                )
            }
        }

        val cleanSeriesName = stripEpisodeAndSeasonSuffix(meta.title)
        val contextStr = "$cleanSeriesName ${meta.tags.joinToString(" ")} ${decodeUrlReadable(finalUrl)}"
        val seriesType = when {
            contextStr.contains("انيميشن") || contextStr.contains("انمي") || contextStr.contains("أنمي") -> TvType.Anime
            contextStr.contains("كوري") || contextStr.contains("آسيوي") || contextStr.contains("اسيوي") -> TvType.AsianDrama
            else -> TvType.TvSeries
        }

        return newTvSeriesLoadResponse(cleanSeriesName, finalUrl, seriesType, allEpisodes) {
            this.posterUrl = meta.posterUrl
            this.posterHeaders = this@CimaNow.posterHeaders
            this.backgroundPosterUrl = meta.backgroundUrl ?: meta.posterUrl
            this.plot = meta.plot
            this.year = meta.year
            this.duration = meta.durationMinutes
            this.tags = meta.tags
            this.actors = meta.actors
            this.recommendations = meta.recommendations
            meta.trailerUrl?.let { addTrailer(it) }
        }
    }

    private fun parseSeasonLinks(doc: Document): List<Pair<Int, String>> {
        val primary = doc.select("section[aria-label=seasons] ul li a[href], #seasons a[href], ul[aria-label=seasons] a[href]")
        val anchors: List<Element> = if (primary.isNotEmpty()) {
            primary
        } else {
            doc.select("a[href*='/selary/']").filter { a ->
                a.parents().none { p -> p.id() == "related" || p.attr("aria-label") == "posts" } &&
                    Regex("""(?:الموسم|الجزء)\s*(?:\d+|الأول|الاول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر)""")
                        .containsMatchIn(a.text())
            }
        }
        if (anchors.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, String>>(anchors.size)
        val seen = HashSet<String>()
        for (idx in anchors.indices) {
            val a = anchors[idx]
            val rawHref = a.attr("href").trim()
            if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", true)) continue
            val encHref = CimaNowNetwork.encodeUrl(rawHref)
            if (!seen.add(normalizeUrlKey(encHref))) continue
            val text = a.text().trim()
            val sNum = parseArabicSeasonNumber(text.substringBefore("(")) ?: (idx + 1)
            out.add(sNum to encHref)
        }
        return out
    }

    private fun extractExplicitEpisodeSeason(epUrl: String, a: Element): Int? {
        val decodedHref = decodeUrlReadable(epUrl)
        Regex("""(?:^|-|/)ج(\d+)(?=-|/|$)""").find(decodedHref)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""(?:موسم|الموسم|الجزء)-(\d+)(?=-|/|$)""").find(decodedHref)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val epAlt = a.select("img[alt]")
            .map { it.attr("alt").trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals("logo", true) }
            .orEmpty()
        val beforeEp = epAlt.substringBefore("الحلقة").substringBefore("حلقة")
        if (Regex("""(?:الموسم|موسم|الجزء|season)\s*\S+|\bج\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(beforeEp)) {
            parseArabicSeasonNumber(beforeEp)?.let { return it }
        }
        return null
    }

    private data class RawEpisodeEntry(
        val epUrl: String,
        val explicitSeason: Int?,
        val epNum: Int?,
        val epTitle: String,
        val epPoster: String?,
    )

    private fun parseEpisodesFromContainer(doc: Document, seasonNum: Int): List<Episode> {
        val anchors = doc.select("ul#eps li a[href], ul#episodes li a[href]")
        if (anchors.isEmpty()) return emptyList()
        val rawEntries = ArrayList<RawEpisodeEntry>(anchors.size)
        val seen = HashSet<String>()

        for (a in anchors) {
            val rawHref = a.attr("href").trim()
            if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", true)) continue
            val epUrl = CimaNowNetwork.encodeUrl(rawHref)
            if (!seen.add(normalizeUrlKey(epUrl))) continue

            val explicitSeason = extractExplicitEpisodeSeason(epUrl, a)

            val emNums = a.select("em").mapNotNull { it.text().trim().toIntOrNull() }
            val textAll = a.text().trim()
            val epNum = emNums.lastOrNull()
                ?: Regex("""(?:ح|حلقة|الحلقة)\s*(\d+)""").find(textAll)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-ح(\d+)(?:-|/|$)""").find(decodeUrlReadable(epUrl))?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""الحلقة-(\d+)(?:-|/|$)""").find(decodeUrlReadable(epUrl))?.groupValues?.get(1)?.toIntOrNull()

            // Episode cards on `/selary/` often contain 2 `<img>` tags (logo + episode still/poster)
            val imgs = a.select("img[data-src], img[src]")
            val thumbImg = imgs.lastOrNull { img ->
                val u = extractCleanImageUrl(img).orEmpty()
                u.isNotBlank() && !u.contains("لوجو") && !u.contains("logo", true)
            } ?: imgs.firstOrNull()
            val epPoster = extractCleanImageUrl(thumbImg)

            val epTitle = if (epNum != null) "الحلقة $epNum" else textAll.ifBlank { "حلقة" }
            rawEntries.add(RawEpisodeEntry(epUrl, explicitSeason, epNum, epTitle, epPoster))
        }

        // On combined root `/selary/` pages (no season switcher), Seasons 2+ have explicit `-جN-` markers
        // while Season 1 episodes omit any season suffix even though page `<h1>` shows the latest season.
        val explicitSeasons = rawEntries.mapNotNull { it.explicitSeason }.toSet()
        val fallbackForUntagged = if (explicitSeasons.isNotEmpty() && 1 !in explicitSeasons && rawEntries.any { it.explicitSeason == null }) {
            1
        } else {
            seasonNum
        }

        return rawEntries.map { entry ->
            val dataJson = JSONObject().put("itemUrl", entry.epUrl).toString()
            newEpisode(dataJson) {
                this.name = entry.epTitle
                this.season = entry.explicitSeason ?: fallbackForUntagged
                this.episode = entry.epNum
                this.posterUrl = entry.epPoster
            }
        }
    }

    private fun inferSeasonNumberFromPage(doc: Document, url: String): Int {
        val activeSeasonText = doc.selectFirst("section[aria-label=seasons] li.active")?.text()?.trim()
        if (!activeSeasonText.isNullOrBlank()) {
            parseArabicSeasonNumber(activeSeasonText)?.let { return it }
        }
        val h1 = doc.selectFirst("h1")?.text().orEmpty()
        parseArabicSeasonNumber("$h1 ${decodeUrlReadable(url)}")?.let { return it }
        return 1
    }

    private fun parseArabicSeasonNumber(text: String): Int? {
        Regex("""(?:ج|موسم|الموسم|الجزء|season)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val normalized = text
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')

        val ordinals = listOf(
            "الثاني عشر" to 12,
            "الحادي عشر" to 11,
            "العاشر" to 10,
            "التاسع" to 9,
            "الثامن" to 8,
            "السابع" to 7,
            "السادس" to 6,
            "الخامس" to 5,
            "الرابع" to 4,
            "الثالث" to 3,
            "الثاني" to 2,
            "الاول" to 1,
        )
        for ((word, num) in ordinals) {
            if (normalized.contains(word)) return num
        }
        return Regex("""\b(\d{1,2})\b""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun stripEpisodeAndSeasonSuffix(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""^(?:مشاهدة\s+)?(?:مسلسل|فيلم|انمي|أنمي|برنامج)\s+"""), "")
            .replace(Regex("""\s+(?:الحلقة|حلقة|ح)\s*\d+.*$"""), "")
            .replace(Regex("""\s+ج\d+\s*$"""), "")
            .replace(Regex("""\s+(?:الموسم|الجزء)\s+\S+(?:\s+عشر)?.*$"""), "")
            .replace(Regex("""\s+(?:مترجمة?|مدبلجة?)\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { rawTitle.trim() }
    }

    private fun extractRichMetadata(doc: Document, pageUrl: String): RichMeta {
        // Parse JSON-LD (`@type: "Movie"` / `"TVSeries"`) when present
        var ldJson: JSONObject? = null
        for (s in doc.select("script[type=application/ld+json]")) {
            val raw = s.data().trim()
            val obj = CimaNowCrypto.safeJsonObject(raw) ?: continue
            val t = obj.optString("@type").lowercase()
            if (t == "movie" || t == "tvseries" || t == "tvseason" || t == "tvepisode" || obj.has("name")) {
                ldJson = obj
                break
            }
        }

        val h1Text = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val ldName = ldJson?.optString("name")?.trim().orEmpty()
        val rawTitle = h1Text.ifBlank { ldName }.ifBlank {
            doc.title().substringBefore(" - ").substringBefore(" | ").trim()
        }
        val cleanTitle = rawTitle
            .replace(Regex("""^(?:مشاهدة\s+فيلم|فيلم)\s+"""), "")
            .replace(Regex("""\s+(19\d{2}|20\d{2})\s+(?:مترجم|مدبلج).*$"""), "")
            .trim()
            .ifBlank { rawTitle }

        // Vertical clean poster (`<figure><img src="...-كلين.jpg">`)
        val figureImg = doc.select("figure img[data-src], figure img[src], article img[data-src], article img[src]")
            .mapNotNull { extractCleanImageUrl(it) }
            .firstOrNull { !it.contains("لوجو") && !it.contains("logo", true) }

        // Wide backdrop cover (`image` in JSON-LD `...-كوفر.jpg` or `og:image` / inline style)
        val ldCover = ldJson?.optString("image")?.takeIf { it.isNotBlank() }?.let { cleanMediaUrl(it) }
        val ogImg = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?.let { cleanMediaUrl(it) }

        val posterUrl = figureImg ?: ogImg ?: ldCover
        val backgroundUrl = ldCover ?: ogImg ?: figureImg

        val domStory = doc.select("li[aria-label=story] p, section[aria-label=details] li[aria-label=story]")
            .firstOrNull()?.text()?.trim()
        val ldDesc = ldJson?.optString("description")?.trim()
        val plot = domStory?.takeIf { it.isNotBlank() } ?: ldDesc?.takeIf { it.isNotBlank() }

        val yearFromLink = doc.selectFirst("a[href*=/release-year/]")?.text()?.trim()?.toIntOrNull()
        val year = yearFromLink
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find("$rawTitle ${decodeUrlReadable(pageUrl)}")
                ?.groupValues?.get(1)?.toIntOrNull()

        // ISO-8601 duration (`PT97M0S` or `PT1H42M`)
        val durationIso = ldJson?.optString("duration").orEmpty()
        val durationMin = parseIsoDurationMinutes(durationIso)

        // Genres & category tags
        val tagsSet = LinkedHashSet<String>()
        val ldGenre = ldJson?.opt("genre")
        when (ldGenre) {
            is JSONArray -> for (i in 0 until ldGenre.length()) {
                ldGenre.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { tagsSet.add(it) }
            }
            is String -> ldGenre.split(',', '،').map { it.trim() }.filter { it.isNotBlank() }.forEach { tagsSet.add(it) }
        }
        for (a in doc.select("ul li a[href*=/category/], section[aria-label=details] a[href*=/category/]")) {
            val t = a.text().trim()
            if (t.isNotBlank() && t.length <= 32) tagsSet.add(t)
        }
        if (rawTitle.contains("مدبلج") || decodeUrlReadable(pageUrl).contains("مدبلج")) tagsSet.add("مدبلج")
        if (rawTitle.contains("مترجم") || decodeUrlReadable(pageUrl).contains("مترجم")) tagsSet.add("مترجم")

        // Cast / Actors
        val actorNames = LinkedHashSet<String>()
        for (a in doc.select("a[href*=/actor/]")) {
            val n = a.text().trim()
            if (n.isNotBlank()) actorNames.add(n)
        }
        val ldActors = ldJson?.optJSONArray("actor")
        if (ldActors != null) {
            for (i in 0 until ldActors.length()) {
                val n = ldActors.optJSONObject(i)?.optString("name")?.trim()
                if (!n.isNullOrBlank()) actorNames.add(n)
            }
        }
        val actors = actorNames.map { ActorData(Actor(it)) }

        // Trailer (`#watch iframe[data-src]` or `iframe[src]` pointing to YouTube)
        val trailerRaw = doc.select("#watch iframe[data-src], #watch iframe[src], iframe[data-src*=youtube], iframe[src*=youtube]")
            .mapNotNull { el -> el.attr("data-src").ifBlank { el.attr("src") }.trim().takeIf { it.isNotBlank() } }
            .firstOrNull { it.contains("youtube.", true) || it.contains("youtu.be", true) }
            ?.let { normalizeYoutubeTrailerUrl(it) }

        val shineUrl = CimaNowExtractors.extractShineUrlFromDetailHtml(doc.outerHtml())

        // Recommendations (`#related`)
        val recs = ArrayList<SearchResponse>()
        val seenRec = HashSet<String>()
        for (a in doc.select("#related a[href], section#related article a[href]")) {
            val card = parseCardAnchor(a) ?: continue
            if (seenRec.add(card.url)) recs.add(card)
        }

        return RichMeta(
            title = cleanTitle,
            posterUrl = posterUrl,
            backgroundUrl = backgroundUrl,
            plot = plot,
            year = year,
            durationMinutes = durationMin,
            tags = tagsSet.toList(),
            actors = actors,
            trailerUrl = trailerRaw,
            shineUrl = shineUrl,
            recommendations = recs,
        )
    }

    private fun parseIsoDurationMinutes(iso: String): Int? {
        if (iso.isBlank()) return null
        val hours = Regex("""(\d+)H""", RegexOption.IGNORE_CASE).find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("""(\d+)M""", RegexOption.IGNORE_CASE).find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        return total.takeIf { it > 0 }
    }

    private fun normalizeYoutubeTrailerUrl(raw: String): String {
        val full = if (raw.startsWith("//")) "https:$raw" else raw
        val embedId = Regex("""youtube(?:-nocookie)?\.com/embed/([A-Za-z0-9_-]{6,})""", RegexOption.IGNORE_CASE)
            .find(full)?.groupValues?.get(1)
        return if (!embedId.isNullOrBlank()) "https://www.youtube.com/watch?v=$embedId" else full
    }

    // ── 5. Video Link Extraction (`loadLinks`) ───────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return CimaNowExtractors.loadAllLinks(data, subtitleCallback, callback)
    }

    // ── Image & URL Helpers ──────────────────────────────────────────────────

    private fun extractCleanImageUrl(img: Element?): String? {
        if (img == null) return null
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("src"),
        )
        for (c in candidates) {
            val trimmed = c.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("data:", true)) {
                return cleanMediaUrl(trimmed)
            }
        }
        return null
    }

    private fun cleanMediaUrl(raw: String): String {
        val withScheme = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> raw
        }
        // Strip low-res `?quality=10` / `?width=300` resize params on `media*.cimanow.cc`
        val stripped = if (withScheme.contains("cimanow.", true) && withScheme.contains("/wp-content/uploads/")) {
            withScheme.substringBefore('?')
        } else {
            withScheme
        }
        return CimaNowNetwork.encodeUrl(stripped)
    }

    private fun decodeUrlReadable(url: String): String =
        runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)

    private fun normalizeUrlKey(url: String): String =
        decodeUrlReadable(url).trim().trimEnd('/').lowercase()
}
