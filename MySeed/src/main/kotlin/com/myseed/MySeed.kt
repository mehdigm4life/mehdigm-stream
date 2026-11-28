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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * MySeed catalog and episode provider.
 *
 * Catalog parsing stays separate from playback parsing, while /watch/ and /download/ routes are
 * resolved only when CloudStream asks for links for a selected episode.
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
    override val hasDownloadSupport = true
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

        // MySeed's search mixes show, season, and episode pages. Keep the exact result URL here:
        // season slugs can contain extra suffixes that cannot safely be truncated. load() follows
        // the page's own breadcrumb to the canonical series page and falls back to slug parsing.
        val canonicalCards = cards.map { card ->
            if (card.kind == MySeedContentKind.MOVIE) {
                card
            } else {
                card.copy(
                    title = MySeedParser.seriesTitleFromEpisode(card.title).ifBlank { card.title },
                    kind = MySeedContentKind.SERIES
                )
            }
        }
        val uniqueCards = LinkedHashMap<String, MySeedCatalogCard>()
        canonicalCards.forEach { card ->
            val key = if (card.kind == MySeedContentKind.MOVIE) {
                "movie:${card.url}"
            } else {
                "series:${card.title.trim().lowercase(Locale.ROOT)}"
            }
            uniqueCards.putIfAbsent(key, card)
        }
        return uniqueCards.values.mapNotNull(::toSearchResponse)
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
        val inputDocument = getDocument(metadataUrl) ?: return unavailable(metadataUrl)
        if (MySeedParser.isNotFoundPage(inputDocument)) return unavailable(metadataUrl)
        val inputDetails = MySeedParser.parseDetails(inputDocument, metadataUrl)

        if (inputDetails.kind == MySeedContentKind.MOVIE) {
            return newMovieLoadResponse(
                inputDetails.title.ifBlank { "بدون عنوان" },
                metadataUrl,
                TvType.Movie,
                metadataUrl
            ) {
                this.posterUrl = inputDetails.posterUrl
                this.plot = inputDetails.plot
                this.year = inputDetails.year
                this.tags = inputDetails.tags
            }
        }

        // Search can return the parent show, one of its season pages, or an episode page.
        // Resolve all three to the same parent catalog before constructing the series response.
        val candidateSeriesUrl = MySeedParser.seriesRootFromBreadcrumbs(inputDocument, metadataUrl)
            ?: MySeedParser.seriesRootUrl(metadataUrl)
            ?: metadataUrl
        val fetchedParent = if (candidateSeriesUrl == metadataUrl) inputDocument else getDocument(candidateSeriesUrl)
        val parentDocument = fetchedParent?.takeUnless { MySeedParser.isNotFoundPage(it) }
        // If MySeed's inferred parent is a 404, keep the real selected page instead of showing
        // the site's generic not-found page and any unrelated links it happens to contain.
        val seriesUrl = if (parentDocument != null) candidateSeriesUrl else metadataUrl
        val catalogDocument = parentDocument ?: inputDocument
        val catalogUrl = seriesUrl
        val details = if (parentDocument != null && parentDocument !== inputDocument) {
            MySeedParser.parseDetails(catalogDocument, catalogUrl)
        } else {
            inputDetails
        }
        val fallbackEpisodes = details.episodes.ifEmpty {
            inputDetails.episodes.ifEmpty {
                if (inputDetails.kind == MySeedContentKind.EPISODE) {
                    listOf(
                        MySeedEpisode(
                            name = "الحلقة ${MySeedParser.episodeNumber(metadataUrl, inputDetails.title) ?: 1}",
                            url = metadataUrl,
                            season = MySeedParser.seasonNumber(metadataUrl, inputDetails.title),
                            number = MySeedParser.episodeNumber(metadataUrl, inputDetails.title)
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }
        val episodeItems = loadSeriesEpisodes(catalogDocument, catalogUrl, fallbackEpisodes)
        val title = MySeedParser.seriesTitleFromEpisode(details.title).ifBlank { details.title.ifBlank { "بدون عنوان" } }

        return newTvSeriesLoadResponse(
            title,
            seriesUrl,
            TvType.TvSeries,
            toEpisodes(episodeItems, details.posterUrl)
        ) {
            this.posterUrl = details.posterUrl
            this.plot = details.plot
            this.year = details.year
            this.tags = details.tags
        }
    }

    private suspend fun loadSeriesEpisodes(
        document: Document,
        pageUrl: String,
        fallbackEpisodes: List<MySeedEpisode>
    ): List<MySeedEpisode> {
        val seasons = MySeedParser.parseSeasons(document, pageUrl)
        if (seasons.isEmpty()) return fallbackEpisodes

        // Do not launch every season request at once: MySeed/Cloudflare can throttle large bursts,
        // which matches the reported six-season cutoff. Limit concurrency and retry a failed page
        // once so all available seasons can be read without overwhelming the site.
        val seasonRequestSemaphore = Semaphore(3)
        val seasonEpisodes = supervisorScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    seasonRequestSemaphore.withPermit {
                        loadSeasonEpisodes(season, pageUrl, document)
                    }
                }
            }.awaitAll().flatten()
        }

        val uniqueEpisodes = LinkedHashMap<String, MySeedEpisode>()
        seasonEpisodes.forEach { episode -> uniqueEpisodes.putIfAbsent(episode.url, episode) }
        return uniqueEpisodes.values.toList().ifEmpty { fallbackEpisodes }
    }

    private suspend fun loadSeasonEpisodes(
        season: MySeedSeason,
        pageUrl: String,
        parentDocument: Document
    ): List<MySeedEpisode> {
        var seasonDocument = if (season.url == pageUrl) parentDocument else getDocument(season.url)
        if (seasonDocument == null && season.url != pageUrl) {
            delay(350)
            seasonDocument = getDocument(season.url)
        }
        return seasonDocument?.let { document ->
            MySeedParser.parseEpisodes(document, season.url)
                .filter { it.season == null || it.season == season.number }
                .mapIndexed { index, episode ->
                    episode.copy(
                        season = episode.season ?: season.number,
                        number = episode.number ?: index + 1
                    )
                }
        }.orEmpty()
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

    private suspend fun unavailable(url: String) = newMovieLoadResponse(
        "تعذّر تحميل بيانات MySeed",
        url,
        TvType.Movie,
        ""
    ) {
        this.plot = "تعذّر تحميل بيانات الفهرس حاليًا. حاول مرة أخرى لاحقًا."
    }

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document? {
        return try {
            val response = app.get(url, headers = REQUEST_HEADERS, referer = referer)
            if (!response.isSuccessful) {
                Log.i(TAG, "Page request returned HTTP ${response.code} for $url")
                return null
            }
            response.document.takeUnless { MySeedParser.isNotFoundPage(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.i(TAG, "Page request failed for $url: ${error.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val contentUrl = MySeedParser.contentUrl(data) ?: return false
        val watchUrl = MySeedParser.routeUrl(contentUrl, "watch") ?: return false
        val downloadUrl = MySeedParser.routeUrl(contentUrl, "download") ?: return false
        val emitted = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        val watchDocument = getDocument(watchUrl, contentUrl)
        val watchOptions = watchDocument?.let { MySeedParser.parseWatchServers(it, watchUrl) }.orEmpty()
        var found = false
        for (option in watchOptions) {
            if (resolveServerOption(option, watchUrl, "مشاهدة", false, subtitleCallback, callback, emitted, visited)) {
                found = true
            }
        }

        // Download links live on a separate /download/ page. MySeed wraps each destination in
        // /l/<base64>; the parser unwraps it before the standard CloudStream extractors run.
        val downloadDocument = getDocument(downloadUrl, contentUrl)
        val downloadOptions = downloadDocument?.let { MySeedParser.parseDownloadServers(it, downloadUrl) }.orEmpty()
        for (option in downloadOptions) {
            if (resolveServerOption(option, downloadUrl, "تحميل", true, subtitleCallback, callback, emitted, visited)) {
                found = true
            }
        }
        return found
    }

    private suspend fun resolveServerOption(
        option: MySeedServerOption,
        referer: String,
        routeLabel: String,
        isDownload: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>,
        visited: MutableSet<String>,
        depth: Int = 0
    ): Boolean {
        val url = option.url.trim()
        if (url.isBlank() || MySeedParser.isKnownNonMediaEndpoint(url) || !visited.add(url)) return false

        val quality = option.quality ?: MySeedParser.qualityFromText(url)
        val mediaType = directMediaType(url)
        if (mediaType != null) {
            if (emitted.add(url)) {
                callbackForOption(option, url, mediaType, referer, routeLabel, quality, null, callback)
            }
            return true
        }

        val uri = runCatching { URI(url) }.getOrNull()
        val isInternalPage = uri?.host.equals("m.myseed.tv", ignoreCase = true)
        // Scrape MySeed pages only; third-party pages must go through their own extractor.
        if (isInternalPage && depth < 2) {
            val nestedDocument = getDocument(url, referer)
            if (nestedDocument != null) {
                val nestedOptions = if (isDownload) {
                    MySeedParser.parseDownloadServers(nestedDocument, url)
                } else {
                    MySeedParser.parseWatchServers(nestedDocument, url)
                }
                // Keep every discovered hoster so a 429 from one does not hide the alternatives.
                var resolvedNested = false
                for (nested in nestedOptions) {
                    if (resolveServerOption(
                            nested,
                            url,
                            routeLabel,
                            isDownload,
                            subtitleCallback,
                            callback,
                            emitted,
                            visited,
                            depth + 1
                        )
                    ) {
                        resolvedNested = true
                    }
                }
                if (resolvedNested) return true
            }
        }

        val before = emitted.size
        val extractedLinks = mutableListOf<ExtractorLink>()
        val extracted = try {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (link.url.isNotBlank()) extractedLinks += link
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.i(TAG, "Could not resolve MySeed server ${option.name}: ${error.message}")
            false
        }
        for (link in extractedLinks) {
            val linkUrl = link.url.trim()
            if (linkUrl.isNotBlank() && emitted.add(linkUrl)) {
                callbackForOption(option, linkUrl, link.type, referer, routeLabel, quality, link, callback)
            }
        }
        return extracted || emitted.size > before
    }

    private suspend fun callbackForOption(
        option: MySeedServerOption,
        url: String,
        type: ExtractorLinkType,
        referer: String,
        routeLabel: String,
        quality: Int?,
        extracted: ExtractorLink?,
        callback: (ExtractorLink) -> Unit
    ) {
        val qualityLabel = quality?.let { "${it}p" }
            ?: extracted?.quality?.takeIf { it > 0 }?.let { "${it}p" }
        val name = listOfNotNull(routeLabel, qualityLabel, option.name.takeIf(String::isNotBlank))
            .joinToString(" · ")
        callback(
            newExtractorLink("MySeed", name.ifBlank { "MySeed · $routeLabel" }, url, type) {
                this.referer = extracted?.referer?.takeIf(String::isNotBlank) ?: referer
                this.quality = extracted?.quality?.takeIf { it > 0 } ?: cloudQuality(quality)
                this.headers = extracted?.headers?.takeIf { it.isNotEmpty() }
                    ?: mapOf("User-Agent" to REQUEST_HEADERS.getValue("User-Agent"), "Referer" to referer)
                if (extracted != null) this.extractorData = extracted.extractorData
            }
        )
    }

    private fun directMediaType(url: String): ExtractorLinkType? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url).lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            Regex("\\.(?:mp4|m4v|mkv|webm|mov|avi|ts)$").containsMatchIn(path) -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    private fun cloudQuality(value: Int?): Int = when (value) {
        2160 -> Qualities.P2160.value
        1440 -> Qualities.P1440.value
        1080 -> Qualities.P1080.value
        720 -> Qualities.P720.value
        540 -> Qualities.Unknown.value
        480 -> Qualities.P480.value
        360 -> Qualities.P360.value
        240 -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }
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

internal data class MySeedSeason(
    val number: Int,
    val url: String
)

internal data class MySeedServerOption(
    val name: String,
    val quality: Int?,
    val url: String
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
    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val PLAYER_URL_ATTRIBUTES = listOf(
        "src", "data", "content", "data-src", "data-url", "data-file", "data-video", "data-embed", "data-player",
        "data-link", "data-source", "data-href", "data-download", "href", "onclick"
    )
    private val URL_TOKEN = Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+|/(?!/)[^\s"'<>\\]+""")
    private val BASE64_TOKEN = Regex("""(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_=-]{20,}(?![A-Za-z0-9+/_=-])""")
    private val BASE64_VALUE = Regex("""^[A-Za-z0-9+/_=-]{12,}$""")

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

    fun isNotFoundPage(document: Document): Boolean {
        val heading = cleanText(document.selectFirst("h1")?.text())
        val title = cleanText(document.title())
        val markers = listOf(
            "الصفحة غير موجودة",
            "لم يتم العثور على الصفحة",
            "page not found",
            "404 not found"
        )
        return markers.any { marker ->
            heading.contains(marker, ignoreCase = true) || title.contains(marker, ignoreCase = true)
        } || listOf(heading, title).any { Regex("(?i)^404(?:\\b|\\s*[-|])").containsMatchIn(it) }
    }

    /**
     * Returns the parent /selary/ URL for a main-show, season, or episode URL. MySeed's episode
     * slugs contain the show slug followed by Arabic season/episode markers (or Sxx/Epx markers).
     */
    fun seriesRootUrl(rawUrl: String): String? {
        val normalized = normalizeContentUrl(rawUrl, BASE_URL) ?: return null
        val path = parsedPath(normalized, BASE_URL)?.trimEnd('/') ?: return null
        val segments = path.split('/').filter(String::isNotBlank)
        val isSeriesPath = segments.firstOrNull().equals("selary", ignoreCase = true)
        val slug = segments.lastOrNull() ?: return null

        val hasSeasonOrEpisode = seasonNumber(normalized) != null || isEpisodeUrl(normalized, "")
        if (!hasSeasonOrEpisode) return if (isSeriesPath) canonicalSiteUrl(path) else null

        val marker = Regex(
            """(?i)(?:[-_ ](?:الموسم|موسم|season)(?:[-_ ]|$)|[-_]s\d{1,2}(?:[-_]|$)|[-_ ](?:الحلقة|episode|eps?|ep)(?:[-_ ]|\d|$))"""
        ).find(slug) ?: return if (isSeriesPath) canonicalSiteUrl(path) else null
        val rootSlug = slug.substring(0, marker.range.first).trimEnd('-', '_', ' ')
        if (rootSlug.isBlank()) return null

        return canonicalSiteUrl("/selary/$rootSlug/")
    }

    /** Prefer the actual series link in MySeed's breadcrumbs over guessing from unusual slugs. */
    fun seriesRootFromBreadcrumbs(document: Document, currentUrl: String): String? {
        val currentContentUrl = contentUrl(currentUrl)
            ?: normalizeContentUrl(currentUrl, BASE_URL)
            ?: return null
        val candidates = document.select(
            ".breadcrumb a[href], .breadcrumbs a[href], #breadcrumb a[href], #breadcrumbs a[href], " +
                "[class*=breadcrumb] a[href], [id*=breadcrumb] a[href], nav[aria-label*=breadcrumb] a[href]"
        ).mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val candidate = contentUrl(href, document.baseUri().ifBlank { BASE_URL }) ?: return@mapNotNull null
            val path = parsedPath(candidate, BASE_URL).orEmpty()
            candidate.takeIf { path.startsWith("/selary/", ignoreCase = true) && it != currentContentUrl }
        }.distinct()
        val inferredRoot = seriesRootUrl(currentContentUrl)
        return candidates.firstOrNull { it == inferredRoot } ?: candidates.firstOrNull()
    }

    private fun canonicalSiteUrl(decodedPath: String): String? {
        val path = if (decodedPath.endsWith('/')) decodedPath else "$decodedPath/"
        return runCatching { URI("https", SITE_HOST, path, null, null).toASCIIString() }.getOrNull()
    }

    /** Extract this show's season pages only, and normalize MySeed's alphabetically ordered tabs. */
    fun parseSeasons(document: Document, pageUrl: String): List<MySeedSeason> {
        val base = document.baseUri().ifBlank { pageUrl.ifBlank { BASE_URL } }
        val rootUrl = seriesRootUrl(pageUrl) ?: return emptyList()
        val seasons = LinkedHashMap<Int, MySeedSeason>()

        for (anchor in document.select("a[href]")) {
            val seasonUrl = normalizeContentUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, base)
                ?: continue
            val path = parsedPath(seasonUrl, base)?.trimEnd('/').orEmpty()
            if (!path.startsWith("/selary/", ignoreCase = true) || seasonUrl == rootUrl) continue

            val number = seasonNumber(seasonUrl, anchorText(anchor)) ?: continue
            if (seriesRootUrl(seasonUrl) != rootUrl) continue
            seasons.putIfAbsent(number, MySeedSeason(number, seasonUrl))
        }

        return seasons.values.sortedBy { it.number }
    }

    fun parseEpisodes(document: Document, pageUrl: String): List<MySeedEpisode> {
        val base = document.baseUri().ifBlank { pageUrl.ifBlank { BASE_URL } }
        val expectedRootUrl = seriesRootUrl(pageUrl)
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

        val hasStructuredEpisodeList = explicitAnchors.isNotEmpty() || headingAnchors.isNotEmpty()
        val candidates = explicitAnchors.ifEmpty { headingAnchors.ifEmpty { document.select("a[href]").filter(::isEpisodeAnchor) } }
        val episodes = LinkedHashMap<String, MySeedEpisode>()
        for (anchor in candidates) {
            val url = contentUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, base)
                ?: continue
            if (!isEpisodeUrl(url, anchorText(anchor))) continue
            val candidateRootUrl = seriesRootUrl(url)
            if (expectedRootUrl != null && candidateRootUrl != expectedRootUrl &&
                (candidateRootUrl != null || !hasStructuredEpisodeList)
            ) continue
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

    /** Normalize a movie, series, or episode URL and remove MySeed's watch/download suffix. */
    fun contentUrl(rawUrl: String, base: String = BASE_URL): String? {
        val resolved = runCatching { URI(base).resolve(rawUrl.trim()).normalize() }.getOrNull()
            ?: return null
        if (resolved.host?.lowercase(Locale.ROOT) != SITE_HOST) return null
        val contentPath = resolved.path.orEmpty()
            .replace(Regex("(?i)/(?:watch|download)/?$"), "")
            .ifBlank { return null }
        return normalizeContentUrl(contentPath, BASE_URL)
    }

    fun episodeContentUrl(rawUrl: String): String? = contentUrl(rawUrl)
        ?.takeIf { isEpisodeUrl(it, "") }

    fun routeUrl(contentPageUrl: String, route: String): String? {
        if (route !in setOf("watch", "download")) return null
        val contentUrl = contentUrl(contentPageUrl) ?: return null
        return "${contentUrl.trimEnd('/')}/$route/"
    }

    /**
     * Parse the selectable players from /watch/. MySeed has changed the player markup more than
     * once, so handle native media tags, server buttons carrying data-* URLs, iframe embeds, and
     * common inline-player scripts. Every output keeps the visible server and quality hint.
     */
    fun parseWatchServers(document: Document, pageUrl: String): List<MySeedServerOption> {
        val result = LinkedHashMap<String, MySeedServerOption>()
        val playerNodes = document.select(
            "iframe[src], video[src], video source[src], source[src], embed[src], object[data], a[href], " +
                "meta[property*=video][content], meta[name*=video][content], meta[property*=player][content], " +
                "[data-src], [data-url], [data-file], [data-video], [data-embed], [data-player], " +
                "[data-link], [data-source], [data-server], [data-quality], [onclick]"
        )
        for (element in playerNodes) {
            val isPlayerTag = element.tagName() in setOf("iframe", "video", "source", "embed", "object")
            val values = mutableListOf<String>()
            for (attribute in PLAYER_URL_ATTRIBUTES) {
                val value = element.attr(attribute).takeIf(String::isNotBlank) ?: continue
                if (attribute == "href" && value.trimStart().startsWith("#")) continue
                if (attribute == "href" && !isPlayerTag && !isPlayerOrServerElement(element) && !value.contains("/l/")) {
                    continue
                }
                if (attribute == "onclick") {
                    values += extractUrlTokens(value)
                } else {
                    values += value
                }
            }
            val serverValue = element.attr("data-server").trim()
            if (serverValue.startsWith("http", ignoreCase = true) || serverValue.startsWith("/") || BASE64_VALUE.matches(serverValue)) {
                values += serverValue
            }

            val context = elementContext(element)
            for (raw in values) {
                val url = resolvePlayerTarget(raw, pageUrl) ?: continue
                if (!isPotentialPlayerUrl(url, element, isPlayerTag)) continue
                addServerOption(result, MySeedServerOption(
                    name = serverName(element, context, url),
                    quality = qualityFromText(context) ?: qualityFromText(url),
                    url = url
                ))
            }
        }

        for (script in document.select("script:not([src])")) {
            val source = script.data().ifBlank { script.html() }
                .replace("\\/", "/")
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("\\x26", "&", ignoreCase = true)
            val playerScript = Regex("(?i)(player|server|video|source|embed|stream|file)").containsMatchIn(source)
            for (raw in extractUrlTokens(source)) {
                val url = resolvePlayerTarget(raw, pageUrl) ?: continue
                val context = scriptContext(source, raw)
                val sourceAssignment = Regex("(?i)(file|src|url|embed|player|video|server|stream)\\s*[:=(]")
                    .containsMatchIn(context)
                if (!isLikelyPlayerUrl(url) && !sourceAssignment) continue
                addServerOption(result, MySeedServerOption(
                    name = serverName(null, context, url),
                    quality = qualityFromText(context) ?: qualityFromText(url),
                    url = url
                ))
            }
            if (playerScript) {
                for (encoded in BASE64_TOKEN.findAll(source).map { it.value }) {
                    val decoded = decodeBase64Url(encoded) ?: continue
                    val url = resolvePlayerTarget(decoded, pageUrl) ?: continue
                    val context = scriptContext(source, encoded)
                    val sourceAssignment = Regex("(?i)(file|src|url|embed|player|video|server|stream)\\s*[:=(]")
                        .containsMatchIn(context)
                    if (!isLikelyPlayerUrl(url) && !sourceAssignment) continue
                    addServerOption(result, MySeedServerOption(
                        name = serverName(null, context, url),
                        quality = qualityFromText(context) ?: qualityFromText(url),
                        url = url
                    ))
                }
            }
        }
        return result.values.toList()
    }

    /** Parse download targets; MySeed's /l/<base64> wrapper is decoded before extraction. */
    fun parseDownloadServers(document: Document, pageUrl: String): List<MySeedServerOption> {
        val result = LinkedHashMap<String, MySeedServerOption>()
        val nodes = document.select("a[href], [data-href], [data-url], [data-download], [data-link]")
        for (element in nodes) {
            val values = listOf("href", "data-href", "data-url", "data-download", "data-link")
                .mapNotNull { key -> element.attr(key).takeIf(String::isNotBlank) }
            val context = elementContext(element)
            for (raw in values) {
                val url = resolvePlayerTarget(raw, pageUrl) ?: continue
                val uri = runCatching { URI(url) }.getOrNull() ?: continue
                val path = uri.path.orEmpty().lowercase(Locale.ROOT)
                if (uri.host.equals(SITE_HOST, ignoreCase = true) || isStaticAsset(path)) continue
                addServerOption(result, MySeedServerOption(
                    name = serverName(element, context, url),
                    quality = qualityFromText(context) ?: qualityFromText(url),
                    url = url
                ))
            }
        }

        // Some revisions print the links directly in a JavaScript array instead of anchors.
        for (script in document.select("script:not([src])")) {
            val source = script.data().ifBlank { script.html() }
                .replace("\\/", "/")
                .replace("\\u0026", "&", ignoreCase = true)
            for (raw in extractUrlTokens(source)) {
                val url = resolvePlayerTarget(raw, pageUrl) ?: continue
                val uri = runCatching { URI(url) }.getOrNull() ?: continue
                if (uri.host.equals(SITE_HOST, ignoreCase = true) || isStaticAsset(uri.path.orEmpty())) continue
                val context = scriptContext(source, raw)
                val downloadAssignment = Regex("(?i)(download|file|href|url|link|server)\\s*[:=(]")
                    .containsMatchIn(context)
                if (!isLikelyPlayerUrl(url) && !downloadAssignment) continue
                addServerOption(result, MySeedServerOption(
                    name = serverName(null, context, url),
                    quality = qualityFromText(context) ?: qualityFromText(url),
                    url = url
                ))
            }
        }
        return result.values.toList()
    }

    fun qualityFromText(value: String): Int? {
        val text = normalizeDigits(value).lowercase(Locale.ROOT)
        if (Regex("(?<![a-z0-9])4k(?![a-z0-9])").containsMatchIn(text)) return 2160
        return Regex("(?<!\\d)(2160|1440|1080|720|540|480|360|240)\\s*p?(?!\\d)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** MySeed's legacy /video/<post-id>.mp4 route serves an HTML page, not a media stream. */
    fun isKnownNonMediaEndpoint(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host.equals(SITE_HOST, ignoreCase = true) &&
            Regex("(?i)^/video/\\d+\\.mp4$").matches(uri.path.orEmpty())
    }

    private fun resolvePlayerTarget(raw: String, base: String): String? {
        val clean = cleanUrl(raw)
        if (clean.isBlank()) return null
        if (BASE64_VALUE.matches(clean)) {
            decodeBase64Url(clean)?.let { decoded -> absoluteHttpUrl(decoded, base)?.let { return it } }
        }
        val absolute = absoluteHttpUrl(clean, base)
        if (absolute != null) {
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return absolute
            val path = uri.rawPath.orEmpty()
            if (uri.host.equals(SITE_HOST, ignoreCase = true) && path.contains("/l/")) {
                val payload = decodePath(path.substringAfter("/l/").trim('/'))
                val decoded = decodeBase64Url(payload) ?: return null
                return absoluteHttpUrl(decoded, base)
            }
            return absolute
        }
        val decoded = decodeBase64Url(clean) ?: return null
        return absoluteHttpUrl(decoded, base)
    }

    private fun absoluteHttpUrl(raw: String, base: String): String? {
        val clean = cleanUrl(raw)
        if (clean.isBlank() || clean.startsWith("javascript:", ignoreCase = true) || clean.startsWith("data:", ignoreCase = true)) {
            return null
        }
        val normalized = when {
            clean.startsWith("//") -> "https:$clean"
            else -> clean
        }
        val resolved = runCatching { URI(base).resolve(normalized).normalize() }.getOrNull() ?: return null
        if (resolved.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || resolved.host.isNullOrBlank()) return null
        return runCatching { resolved.toASCIIString() }.getOrNull()
    }

    private fun cleanUrl(raw: String): String = Parser.unescapeEntities(raw, true)
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\x26", "&", ignoreCase = true)
        .trim()
        .trim('"', '\'', '`')
        .trimEnd(',', ';', ')', ']', '}')

    private fun extractUrlTokens(raw: String): List<String> {
        val text = Parser.unescapeEntities(raw, true)
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\x26", "&", ignoreCase = true)
        return URL_TOKEN.findAll(text).map { cleanUrl(it.value) }.filter(String::isNotBlank).distinct().toList()
    }

    private fun isPotentialPlayerUrl(url: String, element: Element, isPlayerTag: Boolean): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (isStaticAsset(path)) return false
        if (isPlayerTag) return true
        val relevantAttribute = PLAYER_URL_ATTRIBUTES.any { attr ->
            attr != "href" && attr != "onclick" && element.hasAttr(attr)
        }
        return relevantAttribute || isPlayerOrServerElement(element) || isLikelyPlayerUrl(url)
    }

    private fun isLikelyPlayerUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (isStaticAsset(path)) return false
        return path.contains("/l/") ||
            path.contains("embed") || path.contains("player") || path.contains("video") ||
            path.contains("stream") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            Regex("\\.(?:mp4|m4v|mkv|webm|mov|avi|ts)$").containsMatchIn(path)
    }

    private fun isStaticAsset(path: String): Boolean =
        Regex("(?i)\\.(?:css|js|json|jpe?g|png|gif|webp|svg|ico|woff2?|ttf|map)(?:$|[?#])")
            .containsMatchIn(path)

    private fun isPlayerOrServerElement(element: Element): Boolean =
        sequenceOf(element).plus(element.parents().asSequence().take(4)).any { node ->
            val hint = "${node.id()} ${node.className()} ${node.attr("data-server")} ${node.attr("data-quality")}"
            Regex("(?i)(player|server|quality|source|embed|video|stream)").containsMatchIn(hint)
        }

    private fun elementContext(element: Element): String {
        val pieces = mutableListOf<String>()
        var current: Element? = element
        repeat(5) { depth ->
            val node = current ?: return@repeat
            val own = listOf(
                node.attr("data-server"), node.attr("data-quality"), node.attr("data-name"),
                node.attr("aria-label"), node.attr("title"), node.attr("label"), node.attr("data-label"), node.id(), node.className(),
                node.selectFirst(".server-name, [class*=server-name], .quality, strong, b")?.text(),
                node.text().takeIf { it.length <= 220 }
            ).filterNotNull().filter(String::isNotBlank)
            pieces += own
            current = node.parent()
            if (depth >= 2 && (node.id() + node.className()).contains(Regex("(?i)(player|server|quality|source|embed|video)"))) {
                return pieces.joinToString(" ")
            }
        }
        return pieces.joinToString(" ").take(900)
    }

    private fun serverName(element: Element?, context: String, url: String): String {
        if (element != null) {
            for (attribute in listOf("data-server", "data-name", "data-label", "aria-label", "title")) {
                val value = cleanText(element.attr(attribute))
                if (isUsefulServerLabel(value)) return value
            }
            val childLabel = element.selectFirst(".server-name, [class*=server-name], .server-title, strong, b")
                ?.text()?.let(::cleanText)
            if (isUsefulServerLabel(childLabel.orEmpty())) return childLabel.orEmpty()
            val ownLabel = cleanText(element.ownText())
            if (isUsefulServerLabel(ownLabel) && (ownLabel.contains("سيرفر") || ownLabel.contains("server", true))) {
                return ownLabel.take(60)
            }
        }
        val known = listOf("سيرفر ماي سيد المباشر", "سيرفر ماي سيد", "Fastdrive", "Userload", "Uptobox")
            .firstOrNull { context.contains(it, ignoreCase = true) }
        if (known != null) return known
        Regex("(?i)(?:سيرفر|server)\\s*#?\\s*(\\d+)").find(context)?.groupValues?.getOrNull(1)
            ?.let { return "سيرفر $it" }
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().removePrefix("www.")
        return host.ifBlank { "MySeed" }
    }

    private fun isUsefulServerLabel(value: String): Boolean =
        value.length in 2..80 && !value.contains("http", ignoreCase = true) &&
            !Regex("(?i)^\\d{3,4}p$").matches(value)

    private fun addServerOption(result: LinkedHashMap<String, MySeedServerOption>, option: MySeedServerOption) {
        if (isKnownNonMediaEndpoint(option.url)) return
        val existing = result[option.url]
        if (existing == null || (existing.name == "MySeed" && option.name != "MySeed") ||
            (existing.quality == null && option.quality != null)
        ) {
            result[option.url] = option
        }
    }

    private fun scriptContext(source: String, token: String): String {
        val index = source.indexOf(token)
        if (index < 0) return source.take(240)
        val start = (index - 160).coerceAtLeast(0)
        val end = (index + token.length + 160).coerceAtMost(source.length)
        return source.substring(start, end)
    }

    private fun decodeBase64Url(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        if (normalized.length < 8 || normalized.any { it !in BASE64_ALPHABET && it != '=' }) return null
        val output = ByteArray((normalized.length * 3) / 4 + 3)
        var outIndex = 0
        var buffer = 0
        var bits = 0
        for (char in normalized) {
            if (char == '=') break
            val value6 = BASE64_ALPHABET.indexOf(char)
            if (value6 < 0) return null
            buffer = (buffer shl 6) or value6
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[outIndex++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        if (outIndex == 0) return null
        val decoded = String(output, 0, outIndex, Charsets.UTF_8).trim()
        return decoded.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("//") || it.startsWith("/")
        }
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
        val text = normalizeDigits("${decodePath(url)} $label").replace(Regex("[-_]+"), " ")
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
            val episodeMarker = Regex("""(?i)(?:الحلقة|الحلقه|episode|eps?)(?=[\s_-]|\d|$)""")
                .find(seasonLabel)?.range?.first
            val seasonOnlyLabel = seasonLabel.substring(0, episodeMarker ?: seasonLabel.length)
            for ((word, number) in arabicSeasonNumbers) {
                if (seasonOnlyLabel.contains(word)) return number
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
        if (isEpisodeUrl(url, "")) return MySeedContentKind.EPISODE
        if (path.split('/').any { it == "selary" }) {
            return if (isExplicitEpisodeTitle(title)) MySeedContentKind.EPISODE else MySeedContentKind.SERIES
        }
        if (isEpisodeUrl(url, title)) return MySeedContentKind.EPISODE
        return MySeedContentKind.MOVIE
    }

    private fun isExplicitEpisodeTitle(title: String): Boolean {
        val normalized = normalizeDigits(title).lowercase(Locale.ROOT)
        return Regex("(?i)(?:الحلقة|الحلقه|episode|eps?)\\s*[-_ ]?\\d{1,4}")
            .containsMatchIn(normalized)
    }

    private fun isEpisodeAnchor(anchor: org.jsoup.nodes.Element): Boolean {
        val url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        val contentUrl = contentUrl(url, anchor.baseUri().ifBlank { BASE_URL }) ?: return false
        return isEpisodeUrl(contentUrl, anchorText(anchor))
    }

    private fun isEpisodeUrl(url: String, title: String): Boolean {
        val path = decodePath(url).lowercase(Locale.ROOT)
        val normalizedTitle = normalizeDigits(title).lowercase(Locale.ROOT)
        return Regex("(?i)(?:^|[-_/])s\\d{1,2}[-_]eps?\\d{1,4}(?:[-_/]|$)").containsMatchIn(path) ||
            Regex("(?i)(?:^|[-_/])(?:episode|eps?)[-_ ]?\\d{1,4}(?:[-_/]|$)").containsMatchIn(path) ||
            Regex("""(?:^|[-_/])(?:الحلقة|الحلقه)[-_ ]?\d{1,4}(?:[-_/]|$)""").containsMatchIn(path) ||
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
            val code = char.code
            val digitOffset = when {
                code in 1632..1641 -> code - 1632
                code in 1776..1785 -> code - 1776
                else -> null
            }
            val normalized = digitOffset?.let { ('0'.code + it).toChar() } ?: char
            append(normalized)
        }
    }
}
