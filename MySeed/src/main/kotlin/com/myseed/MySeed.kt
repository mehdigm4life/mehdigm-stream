package com.myseed

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * MySeed catalog metadata provider.
 *
 * This module only reads public catalog pages and their descriptive metadata. It deliberately
 * does not visit watch/download routes, inspect embeds, or resolve media/server URLs.
 */
class MySeed : MainAPI() {
    companion object {
        private const val TAG = "MySeed"
        private const val BASE_URL = "https://m.myseed.tv"

        private val REQUEST_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
        )
    }

    override var name = "MySeed (ماي سيد)"
    override var mainUrl = BASE_URL
    override var lang = "ar"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies" to "أحدث الأفلام",
        "series" to "أحدث المسلسلات",
        "recently" to "المضاف حديثًا"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data.trim('/').takeIf { it in setOf("movies", "series", "recently") }
            ?: "recently"
        val safePage = page.coerceAtLeast(1)
        val url = if (safePage == 1) {
            "$mainUrl/$section/"
        } else {
            "$mainUrl/$section/page/$safePage/"
        }
        val document = getDocument(url)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val expectedKind = when (section) {
            "series" -> MySeedContentKind.SERIES
            "movies" -> MySeedContentKind.MOVIE
            else -> null
        }
        val cards = MySeedParser.parseListing(document, expectedKind)
        val results = cards.mapNotNull(::toSearchResponse)
        val hasNext = MySeedParser.hasNextPage(document, safePage, results.size)
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val encodedQuery = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        val filters = listOf(
            MySeedContentKind.MOVIE,
            MySeedContentKind.SERIES,
            MySeedContentKind.EPISODE
        )
        val cards = supervisorScope {
            filters.map { filter ->
                async(Dispatchers.IO) {
                    val url = "$mainUrl/find/?word=$encodedQuery&type=${filter.searchType}"
                    val document = getDocument(url) ?: return@async emptyList()
                    MySeedParser.parseListing(document, filter)
                }
            }.awaitAll().flatten()
        }

        return cards.distinctBy { it.url }.mapNotNull(::toSearchResponse)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    /** MySeed's filtered search pages expose no stable page token; don't guess one. */
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        return newSearchResponseList(search(query), hasNext = false)
    }

    private fun toSearchResponse(card: MySeedCatalogCard): SearchResponse {
        val type = if (card.kind == MySeedContentKind.MOVIE) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(card.title, card.url, type) {
            this.posterUrl = card.posterUrl
            this.year = card.year
        }
    }

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse {
        val metadataUrl = MySeedParser.normalizeContentUrl(url, mainUrl) ?: return unavailable(url)
        val document = getDocument(metadataUrl) ?: return unavailable(metadataUrl)
        val details = MySeedParser.parseDetails(document, metadataUrl)

        return when (details.kind) {
            MySeedContentKind.MOVIE -> newMovieLoadResponse(
                details.title.ifBlank { "بدون عنوان" },
                metadataUrl,
                TvType.Movie,
                ""
            ) {
                this.posterUrl = details.posterUrl
                this.plot = details.plot
                this.year = details.year
                this.tags = details.tags
            }

            MySeedContentKind.SERIES -> newTvSeriesLoadResponse(
                details.title.ifBlank { "بدون عنوان" },
                metadataUrl,
                TvType.TvSeries,
                toEpisodes(details.episodes, details.posterUrl)
            ) {
                this.posterUrl = details.posterUrl
                this.plot = details.plot
                this.year = details.year
                this.tags = details.tags
            }

            MySeedContentKind.EPISODE -> {
                val episodeItems = details.episodes.ifEmpty {
                    listOf(
                        MySeedEpisode(
                            name = "الحلقة ${MySeedParser.episodeNumber(metadataUrl, details.title) ?: 1}",
                            url = metadataUrl,
                            season = MySeedParser.seasonNumber(metadataUrl, details.title),
                            number = MySeedParser.episodeNumber(metadataUrl, details.title)
                        )
                    )
                }
                newTvSeriesLoadResponse(
                    MySeedParser.seriesTitleFromEpisode(details.title),
                    metadataUrl,
                    TvType.TvSeries,
                    toEpisodes(episodeItems, details.posterUrl)
                ) {
                    this.posterUrl = details.posterUrl
                    this.plot = details.plot
                    this.year = details.year
                    this.tags = details.tags
                }
            }
        }
    }

    private fun toEpisodes(items: List<MySeedEpisode>, posterUrl: String?): List<Episode> =
        items.mapIndexed { index, item ->
            val episodeNumber = item.number ?: index + 1
            item to episodeNumber
        }.sortedWith(compareBy({ it.first.season ?: 1 }, { it.second }))
            .map { (item, episodeNumber) ->
                newEpisode(item.url) {
                    this.name = item.name.ifBlank { "الحلقة $episodeNumber" }
                    this.season = item.season ?: 1
                    this.episode = episodeNumber
                    this.posterUrl = posterUrl
                }
            }

    private fun unavailable(url: String) = newMovieLoadResponse(
        "تعذّر تحميل بيانات MySeed",
        url,
        TvType.Movie,
        ""
    ) {
        this.plot = "تعذّر تحميل بيانات الفهرس حاليًا. حاول مرة أخرى لاحقًا."
    }

    private suspend fun getDocument(url: String): Document? {
        return try {
            app.get(url, headers = REQUEST_HEADERS, referer = "$mainUrl/").document
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.i(TAG, "Catalog request failed for $url: ${error.message}")
            null
        }
    }

    /** Metadata-only provider: no watch, download, embed, or third-party media resolution. */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}

internal enum class MySeedContentKind(val searchType: String) {
    MOVIE("movies"),
    SERIES("series"),
    EPISODE("episodes")
}

internal data class MySeedCatalogCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val kind: MySeedContentKind
)

internal data class MySeedEpisode(
    val name: String,
    val url: String,
    val season: Int?,
    val number: Int?
)

internal data class MySeedDetails(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val plot: String?,
    val year: Int?,
    val tags: List<String>,
    val kind: MySeedContentKind,
    val episodes: List<MySeedEpisode>
)

/** Pure parsing and URL-policy helpers, split out so the catalog behavior can be unit-tested. */
internal object MySeedParser {
    private const val BASE_URL = "https://m.myseed.tv"
    private const val SITE_HOST = "m.myseed.tv"
    private const val PAGE_SIZE = 20

    private val blockedPathSegments = setOf(
        "watch", "download", "l", "find", "movies", "series", "recently", "main",
        "category", "genre", "quality", "country", "release-year", "actor", "actors",
        "director", "escritor", "producer", "tag", "author", "wp-json", "wp-content"
    )

    private val arabicSeasonNumbers = linkedMapOf(
        "الحادي عشر" to 11,
        "الحادية عشر" to 11,
        "الثاني عشر" to 12,
        "الثالث عشر" to 13,
        "الرابع عشر" to 14,
        "الخامس عشر" to 15,
        "السادس عشر" to 16,
        "السابع عشر" to 17,
        "الثامن عشر" to 18,
        "التاسع عشر" to 19,
        "العشرون" to 20,
        "العشرين" to 20,
        "الأولى" to 1,
        "الاولى" to 1,
        "الأول" to 1,
        "الاول" to 1,
        "الثانية" to 2,
        "الثاني" to 2,
        "الثالثة" to 3,
        "الثالث" to 3,
        "الرابعة" to 4,
        "الرابع" to 4,
        "الخامسة" to 5,
        "الخامس" to 5,
        "السادسة" to 6,
        "السادس" to 6,
        "السابعة" to 7,
        "السابع" to 7,
        "الثامنة" to 8,
        "الثامن" to 8,
        "التاسعة" to 9,
        "التاسع" to 9,
        "العاشرة" to 10,
        "العاشر" to 10
    )

    fun parseListing(
        document: Document,
        expectedKind: MySeedContentKind?
    ): List<MySeedCatalogCard> {
        val base = document.baseUri().ifBlank { BASE_URL }
        val result = LinkedHashMap<String, MySeedCatalogCard>()

        for (anchor in document.select("a[href]")) {
            // Real result cards have poster art; this avoids navigation, popular-search text,
            // footer links, and most incidental links on the page.
            val image = anchor.selectFirst("img") ?: continue
            val url = normalizeContentUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, base)
                ?: continue
            val kind = classify(url, anchorText(anchor))
            if (expectedKind != null && kind != expectedKind) continue

            val title = anchorTitle(anchor).ifBlank { titleFromUrl(url) }
            if (title.isBlank()) continue

            if (!result.containsKey(url)) {
                result[url] = MySeedCatalogCard(
                    title = title,
                    url = url,
                    posterUrl = imageUrl(image, base),
                    year = yearFrom(title) ?: yearFrom(anchor.text()),
                    kind = kind
                )
            }
        }
        return result.values.toList()
    }

    fun parseDetails(document: Document, url: String): MySeedDetails {
        val title = cleanText(document.selectFirst("h1")?.text())
            .ifBlank { cleanText(document.selectFirst("meta[property=og:title]")?.attr("content")) }
            .ifBlank { titleFromUrl(url) }
        val imageBase = document.baseUri().ifBlank { BASE_URL }
        val poster = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst("""img[src*='/wp-content/uploads'], img[data-src*='/wp-content/uploads']""")?.let {
                listOf("data-src", "data-lazy-src", "data-original", "src")
                    .firstNotNullOfOrNull { attr -> it.attr(attr).takeIf(String::isNotBlank) }
            }
        ).filterNotNull().mapNotNull { safeImageUrl(it, imageBase) }.firstOrNull()

        val bodyText = document.text()
        val description = extractDescription(document)
        val tags = document.select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                val path = parsedPath(href, imageBase)?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                val isMetadataTerm = listOf("/category/", "/genre/", "/quality/", "/country/", "/release-year/")
                    .any { prefix -> path.contains(prefix) }
                if (!isMetadataTerm || path.contains("/category/dl/")) return@mapNotNull null
                cleanText(anchor.text()).takeIf(String::isNotBlank)
            }
            .distinct()

        val kind = classify(url, title)
        val posterUrl = poster ?: document.select("img[src], img[data-src], img[data-lazy-src]")
            .asSequence()
            .mapNotNull { imageUrl(it, imageBase) }
            .firstOrNull()
        val episodes = if (kind == MySeedContentKind.MOVIE) emptyList() else parseEpisodes(document, url)

        return MySeedDetails(
            title = title,
            url = url,
            posterUrl = posterUrl,
            plot = description,
            year = yearFrom(bodyText) ?: yearFrom(title),
            tags = tags,
            kind = kind,
            episodes = episodes
        )
    }

    fun parseEpisodes(document: Document, pageUrl: String): List<MySeedEpisode> {
        val base = document.baseUri().ifBlank { pageUrl.ifBlank { BASE_URL } }
        val explicitAnchors = document.select(
            ".episodes a[href], .episode-list a[href], .episodes-list a[href], " +
                "[class*=episodes] a[href], [id*=episodes] a[href]"
        ).filter(::isEpisodeAnchor)

        val headingAnchors = if (explicitAnchors.isNotEmpty()) emptyList() else {
            val heading = document.select("h2, h3, h4, h5").firstOrNull {
                val text = cleanText(it.text())
                text.contains("الحلقات") || text.contains("episodes", ignoreCase = true)
            }
            if (heading == null) emptyList() else {
                heading.parents().asSequence().take(6)
                    .map { parent -> parent.select("a[href]").filter(::isEpisodeAnchor) }
                    .firstOrNull { it.isNotEmpty() && it.size <= 150 }
                    .orEmpty()
            }
        }

        val candidates = explicitAnchors.ifEmpty { headingAnchors.ifEmpty { document.select("a[href]").filter(::isEpisodeAnchor) } }
        val episodes = LinkedHashMap<String, MySeedEpisode>()
        for (anchor in candidates) {
            val url = normalizeContentUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, base)
                ?: continue
            if (!isEpisodeUrl(url, anchorText(anchor))) continue
            val number = episodeNumber(url, anchorText(anchor))
            val season = seasonNumber(url, anchorText(anchor))
            val rawName = anchorTitle(anchor)
            val label = episodeLabel(rawName, number)
            if (!episodes.containsKey(url)) {
                episodes[url] = MySeedEpisode(label, url, season, number)
            }
        }
        return episodes.values.toList()
    }

    fun hasNextPage(document: Document, currentPage: Int, itemCount: Int): Boolean {
        if (itemCount == 0) return false
        val nextPageFragment = "/page/${currentPage + 1}/"
        val hasNextLink = document.select("a[href]").any { anchor ->
            val url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            url.contains(nextPageFragment) ||
                anchor.attr("rel").equals("next", ignoreCase = true) ||
                anchor.classNames().any { it.equals("next", ignoreCase = true) }
        }
        return hasNextLink || itemCount >= PAGE_SIZE
    }

    fun normalizeContentUrl(raw: String, base: String = BASE_URL): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("javascript:", ignoreCase = true) || value.startsWith("data:", ignoreCase = true)) {
            return null
        }
        val resolved = runCatching { URI(base).resolve(value.replace(" ", "%20")).normalize() }.getOrNull()
            ?: return null
        val host = resolved.host?.lowercase(Locale.ROOT) ?: return null
        if (host != SITE_HOST) return null
        if (resolved.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null

        val path = decodePath(resolved.path.orEmpty()).trimEnd('/')
        if (path.isBlank() || path == "/") return null
        if (Regex("""(?i)\.(?:m3u8|mp4|mkv|webm|m4v|ts)(?:$|/)""").containsMatchIn(path)) return null
        val segments = path.split('/').filter(String::isNotBlank)
        if (segments.any { it.lowercase(Locale.ROOT) in blockedPathSegments }) return null

        val rawPath = resolved.rawPath.orEmpty().ifBlank { "/" }
        val normalizedPath = if (rawPath.endsWith('/')) rawPath else "$rawPath/"
        return runCatching { URI("https://$SITE_HOST$normalizedPath").toASCIIString() }.getOrNull()
    }

    fun episodeNumber(url: String, label: String = ""): Int? {
        val text = normalizeDigits("${decodePath(url)} $label")
        return Regex("(?i)(?:eps?|episode|الحلقة)\\s*[-_ ]?(\\d{1,4})")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun seasonNumber(url: String, label: String = ""): Int? {
        val text = normalizeDigits("${decodePath(url)} $label")
        Regex("(?i)(?:season|s)\\s*[-_ ]?(\\d{1,2})").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("(?:الموسم|موسم)\\s*(?:رقم\\s*)?(\\d{1,2})").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val seasonLabelIndex = listOf("الموسم", "موسم")
            .map { text.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (seasonLabelIndex != null) {
            val seasonLabel = text.substring(seasonLabelIndex)
            for ((word, number) in arabicSeasonNumbers) {
                if (seasonLabel.contains(word)) return number
            }
        }
        return null
    }

    fun seriesTitleFromEpisode(title: String): String {
        val clean = normalizeDigits(cleanText(title))
        val withoutEpisode = clean
            .replace(Regex("(?i)\\s*(?:الحلقة|episode|eps?|ep)\\s*[-_ ]?\\d+.*$"), "")
            .replace(Regex("(?i)[-_]s\\d+[-_]eps?\\d+.*$"), "")
            .trim()
        val withoutSeason = withoutEpisode
            .replace(Regex("(?i)\\s+(?:الموسم|موسم|season)\\s+.+$"), "")
            .trim()
        return withoutSeason.ifBlank { withoutEpisode.ifBlank { clean } }
    }

    private fun classify(url: String, title: String): MySeedContentKind {
        val path = parsedPath(url, BASE_URL)?.lowercase(Locale.ROOT).orEmpty()
        if (path.split('/').any { it == "selary" }) return MySeedContentKind.SERIES
        if (isEpisodeUrl(url, title)) return MySeedContentKind.EPISODE
        return MySeedContentKind.MOVIE
    }

    private fun isEpisodeAnchor(anchor: org.jsoup.nodes.Element): Boolean {
        val url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        return normalizeContentUrl(url, anchor.baseUri().ifBlank { BASE_URL }) != null &&
            isEpisodeUrl(url, anchorText(anchor))
    }

    private fun isEpisodeUrl(url: String, title: String): Boolean {
        val path = decodePath(url).lowercase(Locale.ROOT)
        val normalizedTitle = normalizeDigits(title).lowercase(Locale.ROOT)
        return Regex("(?i)(?:^|[-_/])s\\d{1,2}[-_]eps?\\d{1,4}(?:[-_/]|$)").containsMatchIn(path) ||
            Regex("(?i)(?:^|[-_/])(?:episode|eps?)[-_ ]?\\d{1,4}(?:[-_/]|$)").containsMatchIn(path) ||
            normalizedTitle.contains("الحلقة") || normalizedTitle.contains("episode")
    }

    private fun anchorTitle(anchor: org.jsoup.nodes.Element): String {
        val preferredSelectors = listOf("h1", "h2", "h3", "h4", ".title", "[itemprop=name]", "strong", "b")
        for (selector in preferredSelectors) {
            val value = cleanText(anchor.selectFirst(selector)?.text())
            if (value.isNotBlank()) return value
        }
        val titleAttribute = cleanText(anchor.attr("title"))
        if (titleAttribute.isNotBlank()) return titleAttribute
        val imageAlt = cleanText(anchor.selectFirst("img")?.attr("alt"))
        if (imageAlt.isNotBlank()) return imageAlt
        return cleanText(anchor.text())
    }

    private fun anchorText(anchor: org.jsoup.nodes.Element): String =
        cleanText(listOf(anchor.text(), anchor.attr("title"), anchor.selectFirst("img")?.attr("alt")).filterNotNull().joinToString(" "))

    private fun titleFromUrl(url: String): String {
        val path = parsedPath(url, BASE_URL).orEmpty().trimEnd('/')
        val lastPart = path.substringAfterLast('/').takeIf(String::isNotBlank).orEmpty()
        return decodePath(lastPart).replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun imageUrl(image: org.jsoup.nodes.Element, base: String): String? {
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { attr -> image.attr(attr).trim().takeIf(String::isNotBlank) }
            ?: return null
        return safeImageUrl(raw.substringBefore(',').substringBefore(' '), base)
    }

    private fun safeImageUrl(raw: String, base: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("data:", ignoreCase = true)) return null
        val uri = runCatching { URI(base).resolve(value.replace(" ", "%20")).normalize() }.getOrNull()
            ?: return null
        if (uri.host?.lowercase(Locale.ROOT) != SITE_HOST) return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val imagePath = uri.rawPath.orEmpty()
        if (imagePath.isBlank() || imagePath.contains("logo", ignoreCase = true)) return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$SITE_HOST$imagePath$query"
    }

    private fun extractDescription(document: Document): String? {
        val storyHeading = document.select("h2, h3, h4").firstOrNull {
            cleanText(it.text()).contains("قصة العرض") || cleanText(it.text()).contains("synopsis", ignoreCase = true)
        }
        val storyText = storyHeading?.nextElementSibling()?.text()?.let(::cleanText)
            ?.takeIf(String::isNotBlank)
            ?: storyHeading?.parent()?.text()?.removePrefix(storyHeading.text())?.let(::cleanText)
                ?.takeIf(String::isNotBlank)
        if (!storyText.isNullOrBlank()) return storyText

        val descriptionMeta = document.selectFirst("meta[name=description], meta[property=og:description]")
            ?.attr("content")?.let(::cleanText)?.takeIf(String::isNotBlank)
        if (!descriptionMeta.isNullOrBlank()) return descriptionMeta

        return document.selectFirst(".storyline, .description, [class*=synopsis]")?.text()
            ?.let(::cleanText)?.takeIf(String::isNotBlank)
    }

    private fun yearFrom(value: String): Int? {
        val normalized = normalizeDigits(value)
        val explicit = Regex("(?:سنة العرض|سنة الإصدار|release year|year)\\s*[:：]?\\s*((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return explicit ?: Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
            .find(normalized)?.value?.toIntOrNull()
    }

    private fun episodeLabel(rawTitle: String, number: Int?): String {
        val title = cleanText(rawTitle)
        if (title.isBlank()) return "الحلقة ${number ?: 1}"
        val episodeIndex = title.indexOf("الحلقة")
        if (episodeIndex >= 0) {
            val label = title.substring(episodeIndex).trim()
            if (label.length <= 50) return label
        }
        if (title.length <= 40 && !title.contains("مسلسل")) return title
        return "الحلقة ${number ?: 1}"
    }

    private fun parsedPath(raw: String, base: String): String? {
        val uri = runCatching { URI(base).resolve(raw).normalize() }.getOrNull() ?: return null
        if (uri.host?.lowercase(Locale.ROOT) != SITE_HOST) return null
        return uri.path
    }

    private fun decodePath(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    private fun cleanText(value: String?): String =
        value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { char ->
            val normalized = when (char) {
                in '٠'..'٩' -> ('0'.code + char.code - '٠'.code).toChar()
                in '۰'..'۹' -> ('0'.code + char.code - '۰'.code).toChar()
                else -> char
            }
            append(normalized)
        }
    }
}
