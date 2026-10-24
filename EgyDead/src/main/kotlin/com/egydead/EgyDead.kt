package com.egydead

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections

class EgyDead : MainAPI() {
    override var mainUrl = EgyDeadNetwork.DEFAULT_BASE_URL
    override var name = "إيجي ديد (EgyDead)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "/h3/#featured" to "المواضيع المميزة",
        "/h3/#recent" to "المضاف حديثاً",
        "/movies/" to "أحدث الأفلام",
        "/episodes/" to "أحدث الحلقات",
        "/seasons/" to "أحدث المواسم",
        "/Series/" to "جميع المسلسلات",
        "/category/english-movies/" to "أفلام أجنبية",
        "/category/english-movies/english-movies-dubbed/" to "أفلام أجنبية مدبلجة",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/" to "أفلام عربية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/" to "أفلام تركية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "أفلام آسيوية",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/" to "أفلام أنمي",
        "/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d8%b1%d8%aa%d9%88%d9%86/" to "أفلام كرتون",
        "/film-series/" to "سلاسل الأفلام",
        "/series-category/english-series/" to "مسلسلات أجنبية",
        "/series-category/arabic-series/" to "مسلسلات عربية",
        "/series-category/turkish-series/" to "مسلسلات تركية",
        "/series-category/asian-series/" to "مسلسلات آسيوية وكورية",
        "/series-category/anime-series/" to "مسلسلات أنمي",
        "/series-category/anime-series-dubbed/" to "مسلسلات أنمي مدبلجة"
    )

    // =========================================================================
    // Helper Utilities (Title Cleaning, Season/Episode Parsing, Card Parsing)
    // =========================================================================

    companion object {
        private val ARABIC_ORDINALS = listOf(
            "الحادي والعشرون" to 21,
            "العشرون" to 20, "العشرين" to 20,
            "التاسع عشر" to 19,
            "الثامن عشر" to 18,
            "السابع عشر" to 17,
            "السادس عشر" to 16,
            "الخامس عشر" to 15,
            "الرابع عشر" to 14,
            "الثالث عشر" to 13,
            "الثاني عشر" to 12, "الثانى عشر" to 12,
            "الحادي عشر" to 11, "الحادى عشر" to 11,
            "العاشر" to 10,
            "التاسع" to 9,
            "الثامن" to 8,
            "السابع" to 7,
            "السادس" to 6,
            "الخامس" to 5,
            "الرابع" to 4,
            "الثالث" to 3,
            "الثاني" to 2, "الثانى" to 2,
            "الأول" to 1, "الاول" to 1, "الأولى" to 1, "الاولى" to 1
        )

        fun cleanTitle(raw: String, keepDubTag: Boolean = true): String {
            val wasDubbed = isDubbedText(raw)
            var s = raw
                .replace(Regex("""^\s*(?:تحميل\s+و\s*مشاهدة|مشاهدة\s+و\s*تحميل|مشاهدة|تحميل)\s+"""), "")
                .replace(Regex("""^\s*(?:فيلم|مسلسل|انمي|أنمي|كرتون|برنامج|عرض)\s+"""), "")
                .replace(Regex("""\s*\|\s*ايجي\s*ديد.*$"""), "")
                .replace(Regex("""\s+(?:اون\s*لاين|أون\s*لاين|اونلاين|بجودة\s+عالية)\b.*$"""), "")
                .replace(Regex("""\s+(?:مترجم[ةه]?|مدبلج[ةه]?)\s*(?:كامل[ةه]?)?\s*$"""), "")
                .replace(Regex("""\s+(?:مترجم[ةه]?|مدبلج[ةه]?)\s+كامل[ةه]?\b"""), "")
                .replace(Regex("""\s+كامل[ةه]?\s*$"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (s.isBlank()) s = raw.trim()
            if (keepDubTag && wasDubbed && !s.contains("مدبلج") && !s.contains("بالمصري")) {
                s = "$s (مدبلج)"
            }
            return s
        }

        fun cleanSeriesRootTitle(raw: String): String {
            return cleanTitle(raw, keepDubTag = false)
                .replace(
                    Regex("""\s*(?:الموسم|الجزء)\s+(?:الحادي\s+عشر|الحادى\s+عشر|الثاني\s+عشر|الثانى\s+عشر|الثالث\s+عشر|الرابع\s+عشر|الخامس\s+عشر|السادس\s+عشر|السابع\s+عشر|الثامن\s+عشر|التاسع\s+عشر|العشرون|العشرين|الأول|الاول|الثاني|الثانى|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر|\d+).*$"""),
                    ""
                )
                .replace(Regex("""\s*(?:الحلقة|الحلقه|حلقة|حلقه)\s*\d+.*$"""), "")
                .replace(Regex("""\s+[Ss]\d{1,2}(?:[Ee]\d{1,3})?.*$"""), "")
                .replace(Regex("""\s+(?:مترجم[ةه]?|مدبلج[ةه]?)\b.*$"""), "")
                .trim()
                .ifBlank { cleanTitle(raw, keepDubTag = false) }
        }

        fun isDubbedText(text: String, url: String = ""): Boolean {
            if (text.contains("مدبلج") || text.contains("بالمصري") || text.contains("باللهجة المصرية")) return true
            val u = url.lowercase()
            return u.contains("-dubbed") || u.endsWith("-ar/") || u.endsWith("-ar") || u.contains("-ar-s0")
        }

        fun parseSeasonNumber(text: String, url: String = ""): Int? {
            // 1. Slug patterns like -s04, /s04, season-4
            Regex("""(?:^|[-_/])[sS](\d{1,2})(?:[eE]\d+|[-_/]|$)""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
            Regex("""season[-_]?(\d{1,2})""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
            // 2. Numeric in text: الموسم 4 / الجزء 4 / Season 4 / S04 / Part 4
            Regex("""(?:الموسم|الجزء|موسم|جزء|[Ss]eason|[Pp]art)\s*(\d{1,2})""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
            Regex("""\b[Ss](\d{1,2})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
            // 3. Arabic ordinals (check longer phrases like الحادي عشر before الأول/العاشر)
            for ((word, num) in ARABIC_ORDINALS) {
                if (text.contains(word)) return num
            }
            return null
        }

        fun parseEpisodeNumber(text: String, url: String = ""): Int? {
            // 1. Arabic الحلقة 12 / حلقه 00
            Regex("""(?:الحلقة|الحلقه|حلقة|حلقه)\s*(\d{1,4})""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
            // 2. OVA / Special
            Regex("""(?:اوفا|أوفا|OVA)\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return 1000 + it
            }
            // 3. URL s04e01 or episode-12 or -12/
            Regex("""[sS]\d{1,2}[eE](\d{1,4})""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
            Regex("""(?:episode|ep)[-_]?(\d{1,4})""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
            // 4. Bare number if text is just "12"
            text.trim().toIntOrNull()?.let { return it }
            return null
        }

        fun mapQualityBadge(badge: String?): SearchQuality? {
            if (badge.isNullOrBlank()) return null
            val b = badge.uppercase()
            return when {
                b.contains("2160") || b.contains("4K") || b.contains("UHD") -> SearchQuality.UHD
                b.contains("BLURAY") || b.contains("BLU-RAY") -> SearchQuality.BlueRay
                b.contains("WEB") -> SearchQuality.WebRip
                b.contains("1080") || b.contains("FHD") -> SearchQuality.HD
                b.contains("720") || b.contains("HDTV") || b.contains("HDRIP") -> SearchQuality.HD
                b.contains("HDCAM") || b.contains("HDTS") || b.contains("TS") || b.contains("CAM") -> SearchQuality.Cam
                b.contains("DVD") -> SearchQuality.DVD
                else -> null
            }
        }

        fun stripWatchSuffix(url: String): String {
            val clean = url.trim()
            return clean.replace(Regex("""/watch/?$""", RegexOption.IGNORE_CASE), "/")
        }

        fun extractPoster(root: Element?): String? {
            if (root == null) return null
            val imgs = root.select("img")
            for (img in imgs) {
                val alt = img.attr("alt").trim().lowercase()
                if (alt == "logo" || alt == "لوجو") continue
                val candidates = listOf(
                    img.attr("data-src"),
                    img.attr("data-lazy-src"),
                    img.attr("data-original"),
                    img.attr("srcset").substringBefore(" ").trim(),
                    img.attr("src")
                )
                for (raw in candidates) {
                    val u = raw.trim()
                    if (u.startsWith("http") && !u.startsWith("data:") &&
                        !u.contains("-logo", ignoreCase = true) && !u.contains("لوجو")
                    ) {
                        return EgyDeadNetwork.encodeUrl(u)
                    }
                }
            }
            return null
        }
    }

    private fun classifyTvType(href: String, rawTitle: String, categoryText: String = ""): TvType {
        val u = href.lowercase()
        val combined = "$rawTitle $categoryText"
        val isAnime = combined.contains("انمي") || combined.contains("أنمي") ||
            u.contains("anime")
        val isCartoon = combined.contains("كرتون") || combined.contains("انيميشن") ||
            u.contains("cartoon")
        val isEpisodic = u.contains("/episodes/") || u.contains("/episode/") ||
            u.contains("/seasons/") || u.contains("/season/") ||
            u.contains("/series/") || u.contains("/serie/") ||
            u.contains("/film-series/") || u.contains("/assembly/") ||
            combined.contains("مسلسل") || combined.contains("الموسم") || combined.contains("الحلقة")

        return when {
            isEpisodic && isAnime -> TvType.Anime
            isEpisodic && isCartoon -> TvType.Cartoon
            isEpisodic -> TvType.TvSeries
            isAnime -> TvType.AnimeMovie
            isCartoon -> TvType.Cartoon
            else -> TvType.Movie
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: if (tagName() == "a") this else return null
        val rawHref = linkEl.attr("abs:href").ifBlank { linkEl.attr("href") }.trim()
        if (rawHref.isBlank()) return null

        val lowerHref = rawHref.lowercase()
        if (lowerHref.contains("/category/") ||
            lowerHref.contains("/series-category/") ||
            lowerHref.contains("/type/") ||
            lowerHref.contains("/tag/") ||
            lowerHref.contains("/actor/") ||
            lowerHref.endsWith("/h3/")
        ) return null

        val canonicalHref = stripWatchSuffix(EgyDeadNetwork.encodeUrl(rawHref))

        val rawTitle = selectFirst("h1.BottomTitle, h2.BottomTitle, h3.BottomTitle, .BottomTitle")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: linkEl.attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: linkEl.text().trim()
        if (rawTitle.isBlank()) return null

        val catText = selectFirst("span.cat_name, .cat_name")?.text().orEmpty()
        val ribbonText = select("span.ribbon, .ribbon, span.label, .label").joinToString(" ") { it.text() }
        val qualityBadge = selectFirst("span.label, .label")?.text()?.trim()
        val poster = extractPoster(this)
        val tvType = classifyTvType(canonicalHref, rawTitle, "$catText $ribbonText")
        val isDub = isDubbedText("$rawTitle $catText $ribbonText", canonicalHref)
        val isSub = rawTitle.contains("مترجم") || (!isDub && !catText.contains("عربي"))
        val epNum = selectFirst("span.number em, .number em, i.number em")?.text()?.trim()?.toIntOrNull()
            ?: if (canonicalHref.lowercase().contains("/episode")) parseEpisodeNumber(rawTitle, canonicalHref) else null

        val title = cleanTitle(rawTitle, keepDubTag = true)
        val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val sq = mapQualityBadge(qualityBadge)

        val imgHeaders = EgyDeadNetwork.getImageHeaders()
        return when (tvType) {
            TvType.Anime, TvType.Cartoon, TvType.TvSeries -> {
                if (isDub || epNum != null || tvType == TvType.Anime) {
                    newAnimeSearchResponse(title, canonicalHref, tvType) {
                        this.posterUrl = poster
                        this.posterHeaders = imgHeaders
                        this.year = yearMatch
                        if (sq != null) this.quality = sq
                        addDubStatus(isDub, isSub, epNum, epNum)
                    }
                } else {
                    newTvSeriesSearchResponse(title, canonicalHref, tvType) {
                        this.posterUrl = poster
                        this.posterHeaders = imgHeaders
                        this.year = yearMatch
                        if (sq != null) this.quality = sq
                    }
                }
            }
            TvType.AnimeMovie, TvType.Movie -> {
                newMovieSearchResponse(title, canonicalHref, tvType) {
                    this.posterUrl = poster
                    this.posterHeaders = imgHeaders
                    this.year = yearMatch
                    if (sq != null) this.quality = sq
                }
            }
            else -> null
        }
    }

    internal fun parseCardList(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (card in doc.select("div.movies-list li.movieItem, div.catHolder li.movieItem, ul.posts-list li.movieItem, li.movieItem")) {
            // Avoid sidebar / header widgets if main list exists
            val item = card.toSearchResult() ?: continue
            if (seen.add(item.url)) {
                out.add(item)
            }
        }
        return out
    }

    /**
     * Parses a specific curated section (`#featured` -> `المواضيع المميزه`, `#recent` -> `المضاف حديثا`)
     * from EgyDead's official landing page (`https://tv10.egydead.live/h3/`).
     */
    internal fun parseHomeLandingSection(doc: Document, sectionKey: String): List<SearchResponse> {
        val keywords = when (sectionKey) {
            "featured" -> listOf("المواضيع المميز", "المميزة", "المميزه", "المثبت")
            "recent" -> listOf("المضاف حديث", "اضيف حديث", "أحدث الإضافات")
            else -> emptyList()
        }

        if (keywords.isNotEmpty()) {
            for (heading in doc.select("h1, h2, h3, .TitleMaster, .section-title")) {
                val hText = heading.text().trim()
                if (keywords.any { hText.contains(it) }) {
                    // Check immediate sibling list or enclosing section block
                    val siblingCards = heading.nextElementSiblings()
                        .firstOrNull { it.selectFirst("li.movieItem") != null }
                        ?.select("li.movieItem")

                    val cards = if (!siblingCards.isNullOrEmpty()) {
                        siblingCards
                    } else {
                        var parent: Element? = heading.parent()
                        var found: List<Element> = emptyList()
                        var depth = 0
                        while (parent != null && depth < 3) {
                            val candidate = parent.select("li.movieItem")
                            // Avoid grabbing the entire page body wrapper (which has 80+ items)
                            if (candidate.isNotEmpty() && candidate.size <= 45) {
                                found = candidate
                                break
                            }
                            parent = parent.parent()
                            depth++
                        }
                        found
                    }

                    if (cards.isNotEmpty()) {
                        val seen = HashSet<String>()
                        val parsed = mutableListOf<SearchResponse>()
                        for (c in cards) {
                            val item = c.toSearchResult() ?: continue
                            if (seen.add(item.url)) parsed.add(item)
                        }
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            }
        }

        // Resilient fallback if section titles ever change in future theme updates
        val all = parseCardList(doc)
        return when (sectionKey) {
            "featured" -> all.take(26)
            "recent" -> if (all.size > 26) all.takeLast(26) else all
            else -> all
        }
    }

    // =========================================================================
    // Main Page & Infinite-Scroll Search
    // =========================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = EgyDeadNetwork.getBaseUrl()
        mainUrl = base
        val sectionPath = request.data.trim()

        // Fast shared `/h3/` landing shelves (both shelves share ONE cached `/h3/` request!)
        if (sectionPath.startsWith("/h3/#")) {
            if (page > 1) {
                return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            }
            val sectionKey = sectionPath.substringAfter("#")
            val homeDoc = EgyDeadNetwork.getHomeLandingDocument()
                ?: return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            val items = parseHomeLandingSection(homeDoc, sectionKey)
            return newHomePageResponse(HomePageList(request.name, items), hasNext = false)
        }

        val targetUrl = if (page <= 1) {
            "$base$sectionPath"
        } else {
            "$base${sectionPath.trimEnd('/')}/page/$page/"
        }

        val doc = EgyDeadNetwork.fetchDocument(targetUrl, referer = "$base${EgyDeadNetwork.HOME_LANDING_PATH}")
            ?: return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)

        val items = parseCardList(doc)
        val hasNextPagination = doc.selectFirst(
            ".pagination a.next, .pagination .next, a.next.page-numbers, .pagination a[href*=\"/page/${page + 1}/\"]"
        ) != null
        val hasNext = items.isNotEmpty() && (hasNextPagination || items.size >= 18)

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val first = search(q, 1) ?: return emptyList()
        if (!first.hasNext) return first.items.distinctBy { it.url }
        val second = search(q, 2)?.items.orEmpty()
        return (first.items + second).distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val base = EgyDeadNetwork.getBaseUrl()
        mainUrl = base
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchUrl = if (page <= 1) {
            "$base/?s=$encoded"
        } else {
            "$base/page/$page/?s=$encoded"
        }

        val doc = EgyDeadNetwork.fetchDocument(searchUrl, referer = "$base${EgyDeadNetwork.HOME_LANDING_PATH}") ?: return null
        val items = parseCardList(doc)
        val hasNext = items.isNotEmpty() && (
            doc.selectFirst(".pagination a.next, a.next.page-numbers, .pagination a[href*=\"/page/${page + 1}/\"]") != null ||
                items.size >= 18
            )
        return newSearchResponseList(items, hasNext)
    }

    // =========================================================================
    // Detail Page Metadata & Multi-Season / Sub+Dub / Franchise Load
    // =========================================================================

    private data class PageMetadata(
        val rawTitle: String,
        val poster: String?,
        val plot: String?,
        val year: Int?,
        val durationMinutes: Int?,
        val score: Score?,
        val tags: List<String>,
        val trailerUrl: String?,
        val showStatus: ShowStatus?,
        val isDubbed: Boolean,
        val categoryText: String,
        val recommendations: List<SearchResponse>
    )

    private fun extractPageMetadata(doc: Document, selfUrl: String): PageMetadata {
        val rawTitle = doc.selectFirst("div.infoBox div.singleTitle h1, .singleTitle h1, h1.entry-title, h1")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().replace(Regex("""\s*\|\s*ايجي\s*ديد.*$"""), "").trim()

        val poster = extractPoster(doc.selectFirst("div.singleThumbnail, .single-thumbnail, .Poster"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.startsWith("http") }?.let { EgyDeadNetwork.encodeUrl(it) }

        val plot = doc.selectFirst("div.infoBox div.extra-content p, div.extra-content, .singleStory p, .story p")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.select("h2, h3, span").firstOrNull { it.text().contains("القصه") || it.text().contains("القصة") }
                ?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        val tags = LinkedHashSet<String>()
        var year: Int? = null
        var durationMinutes: Int? = null
        var score: Score? = null
        var categoryText = ""
        var showStatus: ShowStatus? = null

        for (li in doc.select("div.LeftBox ul li, .RightTaxContent li, .singleInfo li, ul li:has(span)")) {
            val label = li.selectFirst("span")?.text()?.trim().orEmpty()
            val fullText = li.text().trim()
            val valueText = if (label.isNotEmpty()) fullText.removePrefix(label).trim(' ', ':', '：') else fullText
            val linksText = li.select("a").map { it.text().trim() }.filter { it.isNotBlank() }

            when {
                label.contains("القسم") -> {
                    categoryText = linksText.joinToString(" ").ifBlank { valueText }
                    linksText.forEach { tags.add(it) }
                }
                label.contains("النوع") || label.contains("التصنيف") -> {
                    linksText.forEach { tags.add(it) }
                }
                label.contains("الجوده") || label.contains("الجودة") -> {
                    linksText.forEach { tags.add(it) }
                    if (linksText.isEmpty() && valueText.isNotBlank()) tags.add(valueText)
                }
                label.contains("اللغه") || label.contains("اللغة") -> {
                    linksText.forEach { tags.add(it) }
                }
                label.contains("البلد") || label.contains("الدولة") -> {
                    linksText.forEach { tags.add(it) }
                }
                label.contains("السنه") || label.contains("السنة") -> {
                    year = Regex("""\b(19\d{2}|20\d{2})\b""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()
                }
                label.contains("مده") || label.contains("مدة") -> {
                    durationMinutes = parseDurationMinutes(valueText)
                }
                label.contains("التقييم") || fullText.contains("IMDB", ignoreCase = true) -> {
                    val r = Regex("""(\d+(?:\.\d+)?)\s*(?:/\s*10)?""").find(valueText)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (r != null && r in 0.1..10.0) {
                        score = Score.from10(r)
                    }
                }
                label.contains("الحاله") || label.contains("الحالة") -> {
                    showStatus = when {
                        valueText.contains("مكتمل") || valueText.contains("منتهي") -> ShowStatus.Completed
                        valueText.contains("مستمر") || valueText.contains("يعرض") -> ShowStatus.Ongoing
                        else -> null
                    }
                }
            }
        }

        if (year == null) {
            year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        }

        val rawTrailer = doc.selectFirst(
            "div.popupContent iframe[src], iframe[src*=\"youtube.com\"], iframe[src*=\"youtu.be\"], a[href*=\"youtube.com/watch\"], a[href*=\"youtu.be/\"]"
        )?.let { el ->
            el.attr("src").ifBlank { el.attr("href") }.trim()
        }
        val trailerUrl = normalizeYoutubeUrl(rawTrailer)

        val selfNorm = stripWatchSuffix(selfUrl).trimEnd('/').lowercase()
        val recSeen = HashSet<String>()
        val recommendations = mutableListOf<SearchResponse>()
        for (card in doc.select("div.related-posts li.movieItem, .AlsoWatch li.movieItem")) {
            val r = card.toSearchResult() ?: continue
            if (r.url.trimEnd('/').lowercase() == selfNorm) continue
            if (recSeen.add(r.url)) {
                recommendations.add(r)
            }
        }

        val isDub = isDubbedText("$rawTitle $categoryText", selfUrl)

        return PageMetadata(
            rawTitle = rawTitle,
            poster = poster,
            plot = plot,
            year = year,
            durationMinutes = durationMinutes,
            score = score,
            tags = tags.toList(),
            trailerUrl = trailerUrl,
            showStatus = showStatus,
            isDubbed = isDub,
            categoryText = categoryText,
            recommendations = recommendations
        )
    }

    private fun parseDurationMinutes(text: String): Int? {
        val hms = Regex("""(\d{1,2}):(\d{2})(?::\d{2})?""").find(text)
        if (hms != null) {
            val h = hms.groupValues[1].toIntOrNull() ?: 0
            val m = hms.groupValues[2].toIntOrNull() ?: 0
            return (h * 60 + m).takeIf { it > 0 }
        }
        val hours = Regex("""(\d+)\s*ساع""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("""(\d+)\s*دقيق""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(\d{2,3})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        return (hours * 60 + mins).takeIf { it > 0 }
    }

    private fun normalizeYoutubeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val u = if (raw.startsWith("//")) "https:$raw" else raw
        val embedId = Regex("""youtube\.com/embed/([A-Za-z0-9_-]{6,})""").find(u)?.groupValues?.get(1)
        if (embedId != null) return "https://www.youtube.com/watch?v=$embedId"
        if (u.contains("youtube.com/watch") || u.contains("youtu.be/")) return u
        return null
    }

    private data class SeasonEntry(
        val seasonUrl: String,
        val seasonTitle: String,
        val seasonNum: Int,
        val isDubbed: Boolean,
        val poster: String?
    )

    private fun extractSeasonEntries(doc: Document): List<SeasonEntry> {
        val seen = HashSet<String>()
        val list = mutableListOf<SeasonEntry>()
        for (li in doc.select("div.seasons-list li.movieItem, .seasons-list li.movieItem")) {
            val a = li.selectFirst("a[href]") ?: continue
            val href = stripWatchSuffix(EgyDeadNetwork.encodeUrl(a.attr("abs:href").ifBlank { a.attr("href") }))
            if (!href.startsWith("http") || !seen.add(href.lowercase())) continue
            val sTitle = li.selectFirst(".BottomTitle, h1, h2, h3")?.text()?.trim()
                ?.ifBlank { a.attr("title") }.orEmpty()
            val sNum = parseSeasonNumber(sTitle, href) ?: (list.size + 1)
            val isDub = isDubbedText(sTitle, href)
            val sPoster = extractPoster(li)
            list.add(SeasonEntry(href, sTitle, sNum, isDub, sPoster))
        }
        return list
    }

    private fun extractEpisodesFromSeasonDoc(
        seasonDoc: Document,
        seasonNum: Int,
        seasonPoster: String?
    ): List<Episode> {
        val anchors = seasonDoc.select("div.EpsList li a[href], .EpsList li a[href], .episodes-list li a[href]")
        if (anchors.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val rawEps = mutableListOf<Pair<Int, Episode>>()

        for ((idx, a) in anchors.withIndex()) {
            val rawHref = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
            if (rawHref.isBlank()) continue
            val text = a.text().trim().ifBlank { a.attr("title").trim() }
            if (text.contains("السابق") || text.contains("التالي")) continue

            val epUrl = stripWatchSuffix(EgyDeadNetwork.encodeUrl(rawHref))
            if (!seen.add(epUrl.lowercase())) continue

            val epNum = parseEpisodeNumber(text, epUrl)
            val sortKey = epNum ?: (anchors.size - idx)
            val epLabel = when {
                epNum != null && epNum >= 1000 -> "OVA ${epNum - 1000}"
                epNum != null -> "الحلقة $epNum"
                text.isNotBlank() -> cleanTitle(text, keepDubTag = false)
                else -> "الحلقة ${idx + 1}"
            }

            val epObj = newEpisode(epUrl) {
                this.name = epLabel
                this.season = seasonNum
                this.episode = if (epNum != null && epNum >= 1000) epNum else epNum
                this.posterUrl = seasonPoster
            }
            rawEps.add(sortKey to epObj)
        }

        return rawEps.sortedBy { it.first }.mapIndexed { idx, (_, ep) ->
            if (ep.episode == null) ep.apply { this.episode = idx + 1 } else ep
        }
    }

    /**
     * Parses inline multi-season episode blocks on `/episodes/<slug>/watch/`
     * (`## حلقات المسلسل` -> `<h3>Season Title</h3>` + episode links).
     */
    private fun extractGroupedEpisodesFromWatchDoc(
        watchDoc: Document,
        seasonPostersByUrlOrTitle: List<SeasonEntry>,
        fallbackPoster: String?
    ): Pair<List<Episode>, List<Episode>> {
        val subbed = mutableListOf<Episode>()
        val dubbed = mutableListOf<Episode>()

        // Each season block typically has an h3 heading followed by a ul/div of episode links
        val headings = watchDoc.select("h3")
        val usedAnchors = HashSet<Element>()

        for (h3 in headings) {
            val headingText = h3.text().trim()
            if (headingText.isBlank()) continue
            val container = h3.nextElementSibling() ?: continue
            val epLinks = container.select("a[href*=\"/episode\"]")
            if (epLinks.isEmpty()) continue

            val isDub = isDubbedText(headingText)
            val sNum = parseSeasonNumber(headingText) ?: 1
            val matchedPoster = seasonPostersByUrlOrTitle.firstOrNull {
                it.seasonNum == sNum && it.isDubbed == isDub
            }?.poster ?: fallbackPoster

            val seenUrls = HashSet<String>()
            val parsed = mutableListOf<Pair<Int, Episode>>()
            for ((idx, a) in epLinks.withIndex()) {
                usedAnchors.add(a)
                val rawHref = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                val text = a.text().trim().ifBlank { a.attr("title").trim() }
                if (text.contains("السابق") || text.contains("التالي")) continue
                val epUrl = stripWatchSuffix(EgyDeadNetwork.encodeUrl(rawHref))
                if (!seenUrls.add(epUrl.lowercase())) continue

                val epNum = parseEpisodeNumber(text, epUrl)
                val sortKey = epNum ?: (idx + 1)
                val epLabel = when {
                    epNum != null && epNum >= 1000 -> "OVA ${epNum - 1000}"
                    epNum != null -> "الحلقة $epNum"
                    text.isNotBlank() -> text
                    else -> "الحلقة ${idx + 1}"
                }
                val epObj = newEpisode(epUrl) {
                    this.name = epLabel
                    this.season = sNum
                    this.episode = epNum ?: (idx + 1)
                    this.posterUrl = matchedPoster
                }
                parsed.add(sortKey to epObj)
            }

            val ordered = parsed.sortedBy { it.first }.map { it.second }
            if (isDub) dubbed.addAll(ordered) else subbed.addAll(ordered)
        }

        // Fallback if single EpsList without h3 headings on watchDoc
        if (subbed.isEmpty() && dubbed.isEmpty()) {
            val directEps = extractEpisodesFromSeasonDoc(watchDoc, 1, fallbackPoster)
            if (directEps.isNotEmpty()) {
                subbed.addAll(directEps)
            }
        }

        return subbed to dubbed
    }

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val activeUrl = stripWatchSuffix(EgyDeadNetwork.rewriteToActiveBase(url))
        mainUrl = EgyDeadNetwork.getBaseUrl()

        val doc = EgyDeadNetwork.fetchDocument(activeUrl) ?: return@coroutineScope null
        val resolvedUrl = stripWatchSuffix(doc.location().ifBlank { activeUrl })
        val lowerUrl = resolvedUrl.lowercase()

        val meta = extractPageMetadata(doc, resolvedUrl)
        val tvType = classifyTvType(resolvedUrl, meta.rawTitle, meta.categoryText)

        // 1. Movie Collection / Franchise (`/film-series/<slug>/` or `/assembly/<slug>/`)
        if (lowerUrl.contains("/film-series/") || lowerUrl.contains("/assembly/")) {
            return@coroutineScope loadFilmSeriesCollection(doc, resolvedUrl, meta)
        }

        val isSeriesRoot = lowerUrl.contains("/series/") || lowerUrl.contains("/serie/")
        val isSeasonPage = lowerUrl.contains("/seasons/") || lowerUrl.contains("/season/")
        val isEpisodePage = lowerUrl.contains("/episodes/") || lowerUrl.contains("/episode/")
        val hasSeasonsOrEpisodesDom = doc.selectFirst("div.EpsList, .EpsList, div.seasons-list, .seasons-list") != null

        if (isSeriesRoot || isSeasonPage || isEpisodePage || hasSeasonsOrEpisodesDom) {
            return@coroutineScope loadEpisodicContent(
                doc = doc,
                resolvedUrl = resolvedUrl,
                meta = meta,
                tvType = if (tvType == TvType.Movie) TvType.TvSeries else if (tvType == TvType.AnimeMovie) TvType.Anime else tvType,
                isEpisodeEntry = isEpisodePage
            )
        }

        // 2. Standalone Movie / AnimeMovie / Special Show
        val movieTitle = cleanTitle(meta.rawTitle, keepDubTag = true)
        newMovieLoadResponse(movieTitle, resolvedUrl, tvType, resolvedUrl) {
            this.posterUrl = meta.poster
            this.posterHeaders = EgyDeadNetwork.getImageHeaders()
            this.plot = meta.plot
            this.year = meta.year
            this.duration = meta.durationMinutes
            this.score = meta.score
            this.tags = meta.tags
            if (meta.recommendations.isNotEmpty()) {
                this.recommendations = meta.recommendations
            }
            if (!meta.trailerUrl.isNullOrBlank()) {
                addTrailer(meta.trailerUrl)
            }
        }
    }

    private suspend fun loadFilmSeriesCollection(
        doc: Document,
        resolvedUrl: String,
        meta: PageMetadata
    ): LoadResponse? {
        val relatedElements = doc.select("div.related-posts li.movieItem, .AlsoWatch li.movieItem").toSet()
        val franchiseCards = doc.select("li.movieItem").filterNot { it in relatedElements }

        data class FranchiseMovie(val year: Int, val orderIdx: Int, val title: String, val href: String, val poster: String?)
        val seen = HashSet<String>()
        val movies = mutableListOf<FranchiseMovie>()

        for ((idx, li) in franchiseCards.withIndex()) {
            val a = li.selectFirst("a[href]") ?: continue
            val href = stripWatchSuffix(EgyDeadNetwork.encodeUrl(a.attr("abs:href").ifBlank { a.attr("href") }))
            if (!href.startsWith("http") || href.equals(resolvedUrl, ignoreCase = true)) continue
            if (!seen.add(href.lowercase())) continue
            val rawName = li.selectFirst(".BottomTitle, h1, h2, h3")?.text()?.trim()
                ?.ifBlank { a.attr("title") }.orEmpty()
            if (rawName.isBlank()) continue
            val movieYear = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawName)?.groupValues?.get(1)?.toIntOrNull() ?: 9999
            movies.add(
                FranchiseMovie(
                    year = movieYear,
                    orderIdx = franchiseCards.size - idx,
                    title = cleanTitle(rawName, keepDubTag = true),
                    href = href,
                    poster = extractPoster(li) ?: meta.poster
                )
            )
        }

        val episodes = movies
            .sortedWith(compareBy<FranchiseMovie> { it.year }.thenBy { it.orderIdx })
            .mapIndexed { i, m ->
                newEpisode(m.href) {
                    this.name = m.title
                    this.season = 1
                    this.episode = i + 1
                    this.posterUrl = m.poster
                }
            }

        val collectionTitle = cleanTitle(meta.rawTitle, keepDubTag = false)
        return newTvSeriesLoadResponse(collectionTitle, resolvedUrl, TvType.TvSeries, episodes) {
            this.posterUrl = meta.poster
            this.posterHeaders = EgyDeadNetwork.getImageHeaders()
            this.plot = meta.plot
            this.year = meta.year ?: movies.minOfOrNull { it.year }?.takeIf { it < 9999 }
            this.tags = meta.tags
            if (meta.recommendations.isNotEmpty()) {
                this.recommendations = meta.recommendations
            }
            if (!meta.trailerUrl.isNullOrBlank()) {
                addTrailer(meta.trailerUrl)
            }
        }
    }

    private suspend fun loadEpisodicContent(
        doc: Document,
        resolvedUrl: String,
        meta: PageMetadata,
        tvType: TvType,
        isEpisodeEntry: Boolean
    ): LoadResponse = coroutineScope {
        val subbedByKey = LinkedHashMap<String, Episode>()
        val dubbedByKey = LinkedHashMap<String, Episode>()

        fun addEpisodeDedup(isDub: Boolean, ep: Episode) {
            val target = if (isDub) dubbedByKey else subbedByKey
            val key = ep.data.trimEnd('/').lowercase()
            if (!target.containsKey(key)) {
                target[key] = ep
            }
        }

        var watchDocRecommendations: List<SearchResponse> = emptyList()

        if (isEpisodeEntry) {
            // Fast path: `/episodes/<slug>/watch/` contains ALL seasons & episodes inline!
            val watchUrl = "${resolvedUrl.trimEnd('/')}/watch/"
            val watchDoc = EgyDeadNetwork.fetchDocument(watchUrl, referer = resolvedUrl)
            if (watchDoc != null) {
                val watchSeasons = extractSeasonEntries(watchDoc)
                val (wSub, wDub) = extractGroupedEpisodesFromWatchDoc(watchDoc, watchSeasons, meta.poster)
                wSub.forEach { addEpisodeDedup(false, it) }
                wDub.forEach { addEpisodeDedup(true, it) }
                if (meta.recommendations.isEmpty()) {
                    watchDocRecommendations = extractPageMetadata(watchDoc, resolvedUrl).recommendations
                }
                // If watchDoc had seasons list but no inline grouped episodes, fetch seasons in parallel
                if (subbedByKey.isEmpty() && dubbedByKey.isEmpty() && watchSeasons.isNotEmpty()) {
                    fetchAllSeasonsInto(watchSeasons, null, resolvedUrl, meta.poster, ::addEpisodeDedup)
                }
            }
            // Ensure current episode is always playable even on standalone specials
            if (subbedByKey.isEmpty() && dubbedByKey.isEmpty()) {
                val epNum = parseEpisodeNumber(meta.rawTitle, resolvedUrl) ?: 1
                val sNum = parseSeasonNumber(meta.rawTitle, resolvedUrl) ?: 1
                val single = newEpisode(resolvedUrl) {
                    this.name = "الحلقة $epNum"
                    this.season = sNum
                    this.episode = epNum
                    this.posterUrl = meta.poster
                }
                addEpisodeDedup(meta.isDubbed, single)
            }
        } else {
            // `/Series/<slug>/` or `/seasons/<slug>/`
            val seasonEntries = extractSeasonEntries(doc).toMutableList()
            val currentSeasonNum = parseSeasonNumber(meta.rawTitle, resolvedUrl) ?: 1
            val currentDirectEps = extractEpisodesFromSeasonDoc(doc, currentSeasonNum, meta.poster)

            if (currentDirectEps.isNotEmpty()) {
                currentDirectEps.forEach { addEpisodeDedup(meta.isDubbed, it) }
            }

            if (seasonEntries.isNotEmpty()) {
                fetchAllSeasonsInto(
                    seasons = seasonEntries,
                    alreadyLoadedUrl = if (currentDirectEps.isNotEmpty()) resolvedUrl else null,
                    referer = resolvedUrl,
                    fallbackPoster = meta.poster,
                    addFn = ::addEpisodeDedup
                )
            }
        }

        val subbedList = subbedByKey.values.sortedWith(compareBy<Episode>({ it.season ?: 1 }, { it.episode ?: 0 }))
        val dubbedList = dubbedByKey.values.sortedWith(compareBy<Episode>({ it.season ?: 1 }, { it.episode ?: 0 }))

        val seriesTitle = cleanSeriesRootTitle(meta.rawTitle)
        val recs = meta.recommendations.ifEmpty { watchDocRecommendations }

        // Use AnimeLoadResponse whenever Anime/Cartoon OR when BOTH Subbed & Dubbed tracks exist
        // so CloudStream displays the native [Subbed | Dubbed] switcher + clean Season 1..N tabs!
        if (tvType == TvType.Anime || (subbedList.isNotEmpty() && dubbedList.isNotEmpty())) {
            newAnimeLoadResponse(seriesTitle, resolvedUrl, tvType) {
                this.posterUrl = meta.poster
                this.posterHeaders = EgyDeadNetwork.getImageHeaders()
                this.plot = meta.plot
                this.year = meta.year
                this.duration = meta.durationMinutes
                this.score = meta.score
                this.tags = meta.tags
                this.showStatus = meta.showStatus
                if (recs.isNotEmpty()) this.recommendations = recs
                if (!meta.trailerUrl.isNullOrBlank()) addTrailer(meta.trailerUrl)
                if (subbedList.isNotEmpty()) addEpisodes(DubStatus.Subbed, subbedList)
                if (dubbedList.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubbedList)
            }
        } else {
            val finalEpisodes = subbedList.ifEmpty { dubbedList }
            val displayTitle = if (dubbedList.isNotEmpty() && !seriesTitle.contains("مدبلج")) {
                "$seriesTitle (مدبلج)"
            } else {
                seriesTitle
            }
            newTvSeriesLoadResponse(displayTitle, resolvedUrl, tvType, finalEpisodes) {
                this.posterUrl = meta.poster
                this.posterHeaders = EgyDeadNetwork.getImageHeaders()
                this.plot = meta.plot
                this.year = meta.year
                this.duration = meta.durationMinutes
                this.score = meta.score
                this.tags = meta.tags
                this.showStatus = meta.showStatus
                if (recs.isNotEmpty()) this.recommendations = recs
                if (!meta.trailerUrl.isNullOrBlank()) addTrailer(meta.trailerUrl)
            }
        }
    }

    private suspend fun fetchAllSeasonsInto(
        seasons: List<SeasonEntry>,
        alreadyLoadedUrl: String?,
        referer: String,
        fallbackPoster: String?,
        addFn: (Boolean, Episode) -> Unit
    ) = coroutineScope {
        val skipNorm = alreadyLoadedUrl?.trimEnd('/')?.lowercase()
        val orderedSeasons = seasons.sortedWith(compareBy<SeasonEntry>({ it.isDubbed }, { it.seasonNum }))
        val sem = Semaphore(5)

        val fetched = orderedSeasons.map { s ->
            async(Dispatchers.IO) {
                if (skipNorm != null && s.seasonUrl.trimEnd('/').lowercase() == skipNorm) {
                    return@async s.isDubbed to emptyList()
                }
                sem.withPermit {
                    val sDoc = EgyDeadNetwork.fetchDocument(s.seasonUrl, referer = referer)
                        ?: return@withPermit s.isDubbed to emptyList()
                    val eps = extractEpisodesFromSeasonDoc(sDoc, s.seasonNum, s.poster ?: fallbackPoster)
                    s.isDubbed to eps
                }
            }
        }.awaitAll()

        for ((isDub, eps) in fetched) {
            for (ep in eps) {
                addFn(isDub, ep)
            }
        }
    }

    // =========================================================================
    // Load Watch & Download Video Links (`/watch/` + Legacy Fallback)
    // =========================================================================

    internal data class CandidateServer(
        val url: String,
        val serverName: String,
        val qualityHint: Int,
        val referer: String
    )

    internal fun collectCandidateServers(doc: Document, pageReferer: String): List<CandidateServer> {
        val candidates = mutableListOf<CandidateServer>()

        // 1. Watch servers list (`ul.serversList li[data-link]`, etc.)
        for (li in doc.select("ul.serversList li, .serversList li, .watch-servers li, [data-link], [data-embed]")) {
            val rawLink = li.attr("data-link")
                .ifBlank { li.attr("data-embed") }
                .ifBlank { li.attr("data-src") }
                .ifBlank { li.attr("data-url") }
                .trim()
            if (rawLink.isBlank()) continue
            val fullLink = when {
                rawLink.startsWith("//") -> "https:$rawLink"
                rawLink.startsWith("http") -> rawLink
                else -> continue
            }
            val sName = li.text().trim().ifBlank { "WatchServer" }
            candidates.add(CandidateServer(fullLink, sName, Qualities.Unknown.value, pageReferer))
        }

        // 2. Active player iframes (excluding YouTube trailers & blank frames)
        for (iframe in doc.select(".watch_player iframe, .player-iframe iframe, .PlayerHolder iframe, #player iframe, iframe[src]")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            if (src.isBlank()) continue
            val full = if (src.startsWith("//")) "https:$src" else src
            if (!full.startsWith("http")) continue
            val low = full.lowercase()
            if (low.contains("youtube.com") || low.contains("youtu.be") ||
                low.contains("googletagmanager") || low.contains("facebook.com") ||
                low.contains("doubleclick")
            ) continue
            candidates.add(CandidateServer(full, "Player", Qualities.Unknown.value, pageReferer))
        }

        // 3. Download servers (`ul.donwload-servers-list li` & `ul.download-servers-list li`)
        for (li in doc.select("ul.donwload-servers-list li, .donwload-servers-list li, ul.download-servers-list li, .download-servers-list li")) {
            val a = li.selectFirst("a.ser-link[href], a[href]") ?: continue
            val rawHref = a.attr("href").trim()
            val full = when {
                rawHref.startsWith("//") -> "https:$rawHref"
                rawHref.startsWith("http") -> rawHref
                else -> continue
            }
            val sName = li.selectFirst(".ser-name, span.ser-name")?.text()?.trim()
                ?.ifBlank { li.ownText().trim() } ?: "Download"
            val qLabel = li.selectFirst(".ser-quality, span.ser-quality, em")?.text()?.trim()
            val qHint = EgyDeadExtractorKit.parseQuality(qLabel)
            candidates.add(CandidateServer(full, sName, qHint, pageReferer))
        }

        return candidates
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val detailUrl = "${stripWatchSuffix(EgyDeadNetwork.rewriteToActiveBase(data)).trimEnd('/')}/"
        val watchUrl = "${detailUrl}watch/"

        // Primary 2026 method: GET `<detailUrl>/watch/`
        var activeReferer = watchUrl
        var watchDoc = EgyDeadNetwork.fetchDocument(watchUrl, referer = detailUrl)
        var candidates = if (watchDoc != null) collectCandidateServers(watchDoc, watchUrl) else emptyList()

        // Fallback 1: Legacy POST `View=1` on `detailUrl`
        if (candidates.isEmpty()) {
            val postDoc = EgyDeadNetwork.postDocument(detailUrl, mapOf("View" to "1"), referer = detailUrl)
            if (postDoc != null) {
                activeReferer = detailUrl
                candidates = collectCandidateServers(postDoc, detailUrl)
            }
        }

        // Fallback 2: Inline servers on `detailUrl`
        if (candidates.isEmpty()) {
            val detailDoc = EgyDeadNetwork.fetchDocument(detailUrl, referer = detailUrl)
            if (detailDoc != null) {
                // Follow explicit watch button href if custom slug
                val customWatchHref = detailDoc.selectFirst("a[href*=\"/watch\"]")?.attr("abs:href")
                if (!customWatchHref.isNullOrBlank() && !customWatchHref.equals(watchUrl, ignoreCase = true)) {
                    val customWatchDoc = EgyDeadNetwork.fetchDocument(customWatchHref, referer = detailUrl)
                    if (customWatchDoc != null) {
                        activeReferer = customWatchHref
                        candidates = collectCandidateServers(customWatchDoc, customWatchHref)
                    }
                }
                if (candidates.isEmpty()) {
                    activeReferer = detailUrl
                    candidates = collectCandidateServers(detailDoc, detailUrl)
                }
            }
        }

        if (candidates.isEmpty()) return@coroutineScope false

        // Canonical deduplication (merges `/iframe/<hash>` + `/download/<hash>` into unified MegaMax 4-channel pipeline)
        val seenKeys = HashSet<String>()
        val uniqueCandidates = candidates.filter { cand ->
            !EgyDeadMirrorRouter.isDeadArchiveHost(cand.url) &&
                seenKeys.add(EgyDeadMirrorRouter.canonicalDedupKey(cand.url))
        }

        if (uniqueCandidates.isEmpty()) return@coroutineScope false

        val emittedLinks = Collections.synchronizedSet(HashSet<String>())
        val dedupCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedLinks.add(link.url)) {
                callback(link)
            }
        }

        val sem = Semaphore(6)
        uniqueCandidates.map { cand ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    EgyDeadMirrorRouter.resolve(
                        url = cand.url,
                        serverHint = cand.serverName,
                        qualityHint = cand.qualityHint,
                        referer = activeReferer,
                        subtitleCallback = subtitleCallback,
                        callback = dedupCallback
                    )
                }
            }
        }.awaitAll()

        emittedLinks.isNotEmpty()
    }
}
