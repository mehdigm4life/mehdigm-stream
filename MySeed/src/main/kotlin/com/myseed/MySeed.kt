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
import org.json.JSONObject
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
        private val AJAX_HEADERS = REQUEST_HEADERS + mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to BASE_URL
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

        // MySeed's search mixes show, season, and episode pages. Normalize all three to a show
        // identity, retain the best (canonical show) card, and leave its URL intact so load() can
        // follow the page's breadcrumb instead of guessing away meaningful slug suffixes.
        return MySeedParser.canonicalizeSearchCards(cards).mapNotNull(::toSearchResponse)
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

    private suspend fun getFreshDocument(url: String, referer: String): Document? {
        return try {
            val response = app.get(url, headers = REQUEST_HEADERS, referer = referer, cacheTime = 0)
            if (!response.isSuccessful) {
                Log.i(TAG, "Fresh page request returned HTTP ${response.code} for $url")
                return null
            }
            response.document.takeUnless { MySeedParser.isNotFoundPage(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.i(TAG, "Fresh page request failed for $url: ${error.message}")
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
        val playbackState = watchDocument?.let { MySeedParser.parseWatchPlaybackState(it, watchUrl) }
        var found = false
        var directServerResolved = false
        for (option in watchOptions) {
            val resolved = resolveServerOption(option, watchUrl, "مشاهدة", false, subtitleCallback, callback, emitted, visited)
            if (resolved) found = true
            if (MySeedParser.isMySeedDirectServerUrl(option.url) && resolved) directServerResolved = true
        }

        // MySeed sometimes leaves the direct player's data-link empty and only returns it after
        // the selected server is requested through its CSRF-protected get__watch__server endpoint.
        if (!directServerResolved) {
            playbackState?.let { state ->
                val directChoice = state.serverChoices.firstOrNull { it.isDirectServer }
                if (directChoice != null) {
                    val directUrl = directChoice.url ?: requestMySeedDirectServerUrl(directChoice, state, watchUrl)
                    if (!directUrl.isNullOrBlank() && directUrl !in visited) {
                        val directOption = MySeedServerOption(
                            name = directChoice.name.ifBlank { "سيرفر ماي سيد المباشر" },
                            quality = directChoice.quality,
                            url = directUrl
                        )
                        if (resolveServerOption(directOption, watchUrl, "مشاهدة", false, subtitleCallback, callback, emitted, visited)) {
                            found = true
                            directServerResolved = true
                        }
                    }
                }
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

        if (MySeedParser.isMySeedDirectServerUrl(url)) {
            return resolveMySeedOwnServer(option, url, referer, routeLabel, emitted, callback)
        }

        val isInternalPage = MySeedParser.isInternalPlayerUrl(url)
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

    private suspend fun requestMySeedDirectServerUrl(
        initialChoice: MySeedWatchServerChoice,
        initialState: MySeedWatchPlaybackState,
        watchUrl: String
    ): String? {
        var state = initialState
        var choice = initialChoice
        repeat(2) { attempt ->
            val payload = MySeedParser.directServerRequestData(choice, state) ?: run {
                Log.i(TAG, "MySeed direct server is missing its playback token or server metadata")
                return null
            }
            val response = try {
                app.post(
                    "$mainUrl/get__watch__server/",
                    data = payload,
                    headers = AJAX_HEADERS,
                    referer = watchUrl
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.i(TAG, "MySeed direct server request failed: ${error.message}")
                return null
            }
            val result = runCatching { JSONObject(response.text) }.getOrNull()
            val server = result?.optString("server")?.takeIf { it.isNotBlank() && it != "null" }
            if (response.isSuccessful && result?.optString("type") == "success" && server != null) {
                return MySeedParser.resolvePlayerTarget(server, watchUrl)
            }

            val unauthorized = result?.optString("message").equals("unauthorized request", ignoreCase = true)
            if (attempt == 0 && unauthorized) {
                val freshDocument = getFreshDocument(watchUrl, "$mainUrl/") ?: return null
                state = MySeedParser.parseWatchPlaybackState(freshDocument, watchUrl)
                choice = state.serverChoices.firstOrNull { it.serverId == initialChoice.serverId }
                    ?: initialChoice.copy(quality = initialChoice.quality ?: state.serverChoices.firstOrNull()?.quality)
                return@repeat
            }
            Log.i(TAG, "MySeed direct server endpoint returned HTTP ${response.code} or an invalid response")
            return null
        }
        return null
    }

    private suspend fun resolveMySeedOwnServer(
        option: MySeedServerOption,
        playerUrl: String,
        watchUrl: String,
        routeLabel: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerResponse = try {
            app.get(
                playerUrl,
                headers = REQUEST_HEADERS,
                referer = watchUrl,
                allowRedirects = false,
                cacheTime = 0
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.i(TAG, "MySeed direct player request failed: ${error.message}")
            return false
        }

        val playerBaseUrl = playerResponse.url.ifBlank { playerUrl }
        val playerContentType = playerResponse.headers["Content-Type"].orEmpty()
        val playerResponseType = mediaTypeFromContentType(playerContentType)
        val playerHtml = if (playerResponseType == null) playerResponse.text else ""
        val playerLocation = playerResponse.headers["Location"] ?: playerResponse.headers["location"]
        val redirectedUrl = playerLocation?.let { MySeedParser.resolvePlayerTarget(it, playerBaseUrl) }
        val directSources = mutableListOf<MySeedDirectMediaSource>()
        var gatewayUrl = MySeedParser.extractMySeedPlayerGateway(playerHtml, playerBaseUrl)
        var gatewayForHeaders = gatewayUrl ?: playerBaseUrl
        var mediaHtml = playerHtml
        var mediaBaseUrl = playerBaseUrl

        if (redirectedUrl != null) {
            if (directMediaType(redirectedUrl) != null) {
                directSources += MySeedDirectMediaSource(redirectedUrl, option.quality)
                gatewayForHeaders = playerBaseUrl
            } else if (gatewayUrl == null) {
                gatewayUrl = redirectedUrl
                gatewayForHeaders = redirectedUrl
            }
        }

        if (directSources.isEmpty() && gatewayUrl != null && directMediaType(gatewayUrl) == null) {
            var nextUrl: String = gatewayUrl ?: return false
            var nextReferer = "$mainUrl/"
            for (hop in 0..2) {
                val gatewayResponse = try {
                    app.get(
                        nextUrl,
                        headers = REQUEST_HEADERS,
                        referer = nextReferer,
                        allowRedirects = false,
                        cacheTime = 0
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.i(TAG, "MySeed player gateway request failed: ${error.message}")
                    return false
                }
                val gatewayBase = gatewayResponse.url.ifBlank { nextUrl }
                val location = gatewayResponse.headers["Location"] ?: gatewayResponse.headers["location"]
                val nextLocation = location?.let { MySeedParser.resolvePlayerTarget(it, gatewayBase) }
                if (nextLocation != null && gatewayResponse.code in 300..399) {
                    val mediaType = directMediaType(nextLocation)
                    if (mediaType != null) {
                        directSources += MySeedDirectMediaSource(nextLocation, option.quality)
                        gatewayForHeaders = gatewayBase
                        break
                    }
                    if (hop < 2) {
                        nextUrl = nextLocation
                        nextReferer = "${MySeedParser.originOf(gatewayBase) ?: mainUrl}/"
                        gatewayForHeaders = gatewayBase
                        continue
                    }
                }
                val gatewayType = mediaTypeFromContentType(gatewayResponse.headers["Content-Type"].orEmpty())
                if (gatewayType != null) {
                    directSources += MySeedDirectMediaSource(gatewayBase, option.quality, gatewayType)
                    gatewayForHeaders = nextUrl
                    break
                }
                if (!gatewayResponse.isSuccessful) {
                    Log.i(TAG, "MySeed player gateway returned HTTP ${gatewayResponse.code}")
                    return false
                }
                mediaHtml = gatewayResponse.text
                mediaBaseUrl = gatewayBase
                gatewayForHeaders = nextUrl
                break
            }
        } else if (!playerResponse.isSuccessful && playerResponse.code !in 300..399) {
            Log.i(TAG, "MySeed direct player returned HTTP ${playerResponse.code}")
            return false
        }

        if (directSources.isEmpty()) {
            directSources += MySeedParser.parseMySeedDirectMediaSources(mediaHtml, mediaBaseUrl)
        }
        if (directSources.isEmpty() && playerResponseType != null) {
            directSources += MySeedDirectMediaSource(playerBaseUrl, option.quality, playerResponseType)
        }
        if (directSources.isEmpty()) {
            Log.i(TAG, "MySeed direct player did not expose a gateway or media source")
            return false
        }

        val origin = MySeedParser.originOf(gatewayForHeaders) ?: mainUrl
        val mediaReferer = "$origin/"
        val mediaHeaders = mapOf(
            "User-Agent" to REQUEST_HEADERS.getValue("User-Agent"),
            "Referer" to mediaReferer,
            "Origin" to origin
        )
        var emittedAny = false
        for (source in directSources.distinctBy { it.url }) {
            val type = source.type?.let(::cloudstreamMediaType) ?: directMediaType(source.url) ?: continue
            if (source.url.isBlank() || !emitted.add(source.url)) continue
            val sourceQuality = source.quality ?: option.quality
            callbackForOption(
                option.copy(quality = sourceQuality),
                source.url,
                type,
                mediaReferer,
                routeLabel,
                sourceQuality,
                null,
                callback,
                mediaHeaders
            )
            emittedAny = true
        }
        return emittedAny
    }

    private suspend fun callbackForOption(
        option: MySeedServerOption,
        url: String,
        type: ExtractorLinkType,
        referer: String,
        routeLabel: String,
        quality: Int?,
        extracted: ExtractorLink?,
        callback: (ExtractorLink) -> Unit,
        customHeaders: Map<String, String>? = null
    ) {
        val qualityLabel = quality?.let { "${it}p" }
            ?: extracted?.quality?.takeIf { it > 0 }?.let { "${it}p" }
        val name = listOfNotNull(routeLabel, qualityLabel, option.name.takeIf(String::isNotBlank))
            .joinToString(" · ")
        callback(
            newExtractorLink("MySeed", name.ifBlank { "MySeed · $routeLabel" }, url, type) {
                this.referer = extracted?.referer?.takeIf(String::isNotBlank)
                    ?: customHeaders?.get("Referer")?.takeIf(String::isNotBlank)
                    ?: referer
                this.quality = extracted?.quality?.takeIf { it > 0 } ?: cloudQuality(quality)
                this.headers = extracted?.headers?.takeIf { it.isNotEmpty() }
                    ?: customHeaders
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

    private fun mediaTypeFromContentType(contentType: String): MySeedDirectMediaKind? {
        val normalized = contentType.lowercase(Locale.ROOT)
        return when {
            normalized.contains("mpegurl") || normalized.contains("m3u8") -> MySeedDirectMediaKind.M3U8
            normalized.contains("dash+xml") -> MySeedDirectMediaKind.DASH
            normalized.startsWith("video/") -> MySeedDirectMediaKind.VIDEO
            else -> null
        }
    }

    private fun cloudstreamMediaType(type: MySeedDirectMediaKind): ExtractorLinkType = when (type) {
        MySeedDirectMediaKind.M3U8 -> ExtractorLinkType.M3U8
        MySeedDirectMediaKind.DASH -> ExtractorLinkType.DASH
        MySeedDirectMediaKind.VIDEO -> ExtractorLinkType.VIDEO
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

internal data class MySeedWatchServerChoice(
    val name: String,
    val serverId: String,
    val quality: Int?,
    val url: String?,
    val isDirectServer: Boolean
)

internal data class MySeedWatchPlaybackState(
    val postId: String?,
    val csrfToken: String?,
    val serverChoices: List<MySeedWatchServerChoice>
)

internal enum class MySeedDirectMediaKind {
    VIDEO,
    M3U8,
    DASH
}

internal data class MySeedDirectMediaSource(
    val url: String,
    val quality: Int?,
    val type: MySeedDirectMediaKind? = null
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
    private val MYSEED_PLAYER_HOSTS = setOf(SITE_HOST, "m.myseed.pics")

    private val PLAYER_URL_ATTRIBUTES = listOf(
        "src", "data", "content", "data-src", "data-url", "data-file", "data-video", "data-embed", "data-player",
        "data-link", "data-source", "data-href", "data-download", "href", "onclick"
    )
    private val URL_TOKEN = Regex("""(?i)(?:https?:)?//[^\s"'<>\\]+|/(?!/)[^\s"'<>\\]+""")
    private val BASE64_TOKEN = Regex("""(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_=-]{20,}(?![A-Za-z0-9+/_=-])""")
    private val BASE64_VALUE = Regex("""^[A-Za-z0-9+/_=-]{12,}$""")
    private val DOWNLOAD_SERVER_NAME_PREFIX = Regex("""^[\s\p{P}]*تحميل""")

    private val blockedPathSegments = setOf(
        "watch", "download", "l", "find", "movies", "series", "recently", "main",
        "category", "genre", "quality", "country", "release-year", "actor", "actors",
        "director", "escritor", "producer", "tag", "author", "wp-json", "wp-content"
    )

    private val seriesChildMarker = Regex(
        """(?i)(?:[-_ ](?:الموسم|موسم|season)(?:[-_ ]|(?=\d)|$)|[-_]s\d{1,2}(?=[-_\se]|$)|[-_ ](?:الحلقة|الحلقه|episode|eps?|ep)(?:[-_ ]|\d|$))"""
    )
    private val seriesSlugSuffixes = listOf(
        "مترجم-للعربية", "مترجمة-للعربية", "مدبلج-بالعربية", "مدبلجة-بالعربية",
        "مترجم-للعربيه", "مترجمة-للعربيه", "مدبلج-بالعربيه", "مدبلجة-بالعربيه",
        "مترجم-عربي", "مترجمة-عربي", "مدبلج-عربي", "مدبلجة-عربي",
        "مترجم", "مترجمة", "مدبلج", "مدبلجة", "translated", "subtitled", "subbed", "dubbed"
    )

    private data class SearchCardCandidate(
        val card: MySeedCatalogCard,
        val seriesIdentity: String?,
        val titleIdentity: String,
        val isChildPage: Boolean,
        val priority: Int
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

    /** Convert MySeed's mixed show/season/episode search hits to one result per show. */
    fun canonicalizeSearchCards(cards: List<MySeedCatalogCard>): List<MySeedCatalogCard> {
        val unique = mutableListOf<SearchCardCandidate>()
        for (card in cards) {
            if (card.kind == MySeedContentKind.MOVIE) {
                val existingIndex = unique.indexOfFirst {
                    it.card.kind == MySeedContentKind.MOVIE && it.card.url == card.url
                }
                if (existingIndex < 0) {
                    unique += SearchCardCandidate(card, null, "", false, 0)
                } else {
                    val existing = unique[existingIndex]
                    unique[existingIndex] = existing.copy(
                        card = existing.card.copy(
                            posterUrl = existing.card.posterUrl ?: card.posterUrl,
                            year = existing.card.year ?: card.year
                        )
                    )
                }
                continue
            }

            val seriesTitle = seriesTitleFromEpisode(card.title).ifBlank { card.title }
            val normalizedCard = card.copy(title = seriesTitle, kind = MySeedContentKind.SERIES)
            val identity = seriesIdentityKey(card.url)
            val titleIdentity = normalizeSeriesSlug(seriesTitle)
            val isChildPage = card.kind == MySeedContentKind.EPISODE ||
                seasonNumber(card.url, card.title) != null || isEpisodeUrl(card.url, card.title)
            val path = parsedPath(card.url, BASE_URL).orEmpty()
            val isCanonicalRoot = !isChildPage && path.startsWith("/selary/", ignoreCase = true)
            val priority = when {
                isCanonicalRoot -> 3
                card.kind == MySeedContentKind.SERIES && !isChildPage -> 2
                card.kind == MySeedContentKind.SERIES -> 1
                else -> 0
            }
            val candidate = SearchCardCandidate(normalizedCard, identity, titleIdentity, isChildPage, priority)
            val duplicateIndex = unique.indexOfFirst { existing ->
                if (existing.card.kind == MySeedContentKind.MOVIE) {
                    false
                } else {
                    val sameUrlIdentity = !identity.isNullOrBlank() && identity == existing.seriesIdentity
                    val yearsCompatible = existing.card.year == null || card.year == null || existing.card.year == card.year
                    val sameTitleIdentity = titleIdentity.isNotBlank() && titleIdentity == existing.titleIdentity &&
                        yearsCompatible &&
                        (identity == null || existing.seriesIdentity == null || isChildPage || existing.isChildPage)
                    sameUrlIdentity || sameTitleIdentity
                }
            }

            if (duplicateIndex < 0) {
                unique += candidate
            } else {
                val existing = unique[duplicateIndex]
                val preferred = if (candidate.priority > existing.priority) candidate else existing
                val other = if (preferred === candidate) existing else candidate
                unique[duplicateIndex] = preferred.copy(
                    card = preferred.card.copy(
                        posterUrl = preferred.card.posterUrl ?: other.card.posterUrl,
                        year = preferred.card.year ?: other.card.year
                    ),
                    seriesIdentity = preferred.seriesIdentity ?: other.seriesIdentity,
                    titleIdentity = preferred.titleIdentity.ifBlank { other.titleIdentity }
                )
            }
        }
        return unique.map { it.card }
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
    fun seriesRootUrl(rawUrl: String): String? = seriesRootSlug(rawUrl)
        ?.let { canonicalSiteUrl("/selary/$it/") }

    /** Stable show key for matching a root page to season/episode slugs with optional suffixes. */
    fun seriesIdentityKey(rawUrl: String): String? = seriesRootSlug(rawUrl)
        ?.let(::normalizeSeriesSlug)
        ?.takeIf(String::isNotBlank)

    private fun seriesRootSlug(rawUrl: String): String? {
        val normalized = normalizeContentUrl(rawUrl, BASE_URL) ?: return null
        val path = parsedPath(normalized, BASE_URL)?.trimEnd('/') ?: return null
        val segments = path.split('/').filter(String::isNotBlank)
        val isSeriesPath = segments.firstOrNull().equals("selary", ignoreCase = true)
        val slug = segments.lastOrNull() ?: return null

        val hasSeasonOrEpisode = seasonNumber(normalized) != null || isEpisodeUrl(normalized, "")
        if (!hasSeasonOrEpisode) return slug.takeIf { isSeriesPath }

        val marker = seriesChildMarker.find(slug)
            ?: return slug.takeIf { isSeriesPath }
        return slug.substring(0, marker.range.first).trimEnd('-', '_', ' ').takeIf(String::isNotBlank)
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
        val inferredIdentity = seriesIdentityKey(currentContentUrl)
        val sameSeries = candidates.filter { seriesIdentityKey(it) == inferredIdentity && inferredIdentity != null }
        return sameSeries.firstOrNull { seasonNumber(it) == null && !isEpisodeUrl(it, "") }
            ?: candidates.firstOrNull { seasonNumber(it) == null && !isEpisodeUrl(it, "") }
            ?: sameSeries.firstOrNull()
            ?: candidates.firstOrNull()
    }

    private fun canonicalSiteUrl(decodedPath: String): String? {
        val path = if (decodedPath.endsWith('/')) decodedPath else "$decodedPath/"
        return runCatching { URI("https", SITE_HOST, path, null, null).toASCIIString() }.getOrNull()
    }

    /** Extract this show's season pages only, and normalize MySeed's alphabetically ordered tabs. */
    fun parseSeasons(document: Document, pageUrl: String): List<MySeedSeason> {
        val base = document.baseUri().ifBlank { pageUrl.ifBlank { BASE_URL } }
        val rootIdentity = seriesIdentityKey(pageUrl) ?: return emptyList()
        val seasons = LinkedHashMap<Int, MySeedSeason>()

        for (anchor in document.select("a[href]")) {
            val seasonUrl = normalizeContentUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") }, base)
                ?: continue
            val path = parsedPath(seasonUrl, base)?.trimEnd('/').orEmpty()
            if (!path.startsWith("/selary/", ignoreCase = true)) continue

            val number = seasonNumber(seasonUrl, anchorText(anchor)) ?: continue
            if (seriesIdentityKey(seasonUrl) != rootIdentity) continue
            seasons.putIfAbsent(number, MySeedSeason(number, seasonUrl))
        }

        return seasons.values.sortedBy { it.number }
    }

    fun parseEpisodes(document: Document, pageUrl: String): List<MySeedEpisode> {
        val base = document.baseUri().ifBlank { pageUrl.ifBlank { BASE_URL } }
        val expectedRootIdentity = seriesIdentityKey(pageUrl)
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
            val candidateRootIdentity = seriesIdentityKey(url)
            if (expectedRootIdentity != null && candidateRootIdentity != expectedRootIdentity &&
                (candidateRootIdentity != null || !hasStructuredEpisodeList)
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

    fun isInternalPlayerUrl(rawUrl: String): Boolean {
        val host = runCatching { URI(rawUrl).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host in MYSEED_PLAYER_HOSTS
    }

    /** MySeed embeds providers behind its /vid(s).php and /vid/ player routes, including a mirror host. */
    fun isMySeedPlayerTarget(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host !in MYSEED_PLAYER_HOSTS) return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        fun hasQueryValue(key: String): Boolean = uri.rawQuery.orEmpty().split('&').any { parameter ->
            parameter.substringBefore('=').equals(key, ignoreCase = true) &&
                parameter.substringAfter('=', "").isNotBlank()
        }
        return when (path) {
            "/vids.php" -> hasQueryValue("t")
            "/vid.php" -> hasQueryValue("url")
            "/vid/" -> hasQueryValue("id")
            else -> path.startsWith("/l/") || path.contains("/embed/") || path.contains("/player/") ||
                Regex("\\.(?:m3u8|mpd|mp4|m4v|mkv|webm|mov|avi|ts)$").containsMatchIn(path)
        }
    }

    fun isMySeedDirectServerUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        return uri.host?.lowercase(Locale.ROOT) in MYSEED_PLAYER_HOSTS &&
            uri.path.equals("/vids.php", ignoreCase = true) &&
            uri.rawQuery.orEmpty().split('&').any { parameter ->
                parameter.substringBefore('=').equals("t", ignoreCase = true) &&
                    parameter.substringAfter('=', "").isNotBlank()
            }
    }

    fun parseWatchPlaybackState(document: Document, pageUrl: String): MySeedWatchPlaybackState {
        val source = document.html()
        val tokenPattern = Regex("""(?i)[\"']?(?:csrf__token|csrf_token)[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']""")
        val postIdPattern = Regex("""(?i)[\"']?(?:psot_id|post_id)[\"']?\s*[:=]\s*[\"']?(\d+)""")
        val csrfToken = tokenPattern.find(source)?.groupValues?.getOrNull(1)
            ?: document.select("input[name]").firstOrNull { it.attr("name").contains("csrf", ignoreCase = true) }
                ?.attr("value")?.takeIf(String::isNotBlank)
        val postId = postIdPattern.find(source)?.groupValues?.getOrNull(1)
            ?: document.selectFirst("[data-post-id], [data-post_id]")
                ?.let { it.attr("data-post-id").ifBlank { it.attr("data-post_id") } }
                ?.takeIf(String::isNotBlank)

        val choices = document.select("li[data-server]").mapNotNull { element ->
            val serverId = element.attr("data-server").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val context = elementContext(element)
            val rawName = listOf(
                element.attr("data-server-name"), element.attr("data-name"), element.attr("aria-label"), element.attr("title"),
                element.selectFirst(".server-name, .server-title, strong, b")?.text(), element.text()
            ).map(::cleanText).firstOrNull { label ->
                label.contains("سيرفر") || label.contains("server", ignoreCase = true) ||
                    isDownloadLabeledServerName(label)
            }.orEmpty()
            val rawLink = listOf(element.attr("data-link"), element.attr("data-url"), element.attr("data-src"))
                .firstOrNull(String::isNotBlank)
                ?: element.selectFirst("a[href], [data-link]")?.let { nested ->
                    listOf(nested.attr("data-link"), nested.attr("href")).firstOrNull(String::isNotBlank)
                }.orEmpty()
            val url = rawLink.takeIf(String::isNotBlank)?.let { resolvePlayerTarget(it, pageUrl) }
            val isDirect = serverId == "0" ||
                rawName.contains("مباشر") || rawName.contains("direct", ignoreCase = true) ||
                url?.let(::isMySeedDirectServerUrl) == true
            val name = rawName.ifBlank { if (isDirect) "سيرفر ماي سيد المباشر" else "سيرفر $serverId" }
            if (isDownloadLabeledServerName(name)) return@mapNotNull null
            val qualityContext = listOf(
                element.attr("data-qu"), element.attr("data-quality"), context,
                element.parents().take(3).joinToString(" ") { it.attr("data-quality") }
            ).joinToString(" ")
            MySeedWatchServerChoice(
                name = name.take(80),
                serverId = serverId,
                quality = qualityFromText(qualityContext),
                url = url,
                isDirectServer = isDirect
            )
        }.distinctBy { Triple(it.serverId, it.quality, it.url) }

        return MySeedWatchPlaybackState(postId, csrfToken, choices)
    }

    fun directServerRequestData(
        choice: MySeedWatchServerChoice,
        state: MySeedWatchPlaybackState
    ): Map<String, String>? {
        val postId = state.postId?.takeIf(String::isNotBlank) ?: return null
        val csrfToken = state.csrfToken?.takeIf(String::isNotBlank) ?: return null
        val quality = choice.quality?.takeIf { it > 0 }?.toString() ?: return null
        return mapOf(
            "post_id" to postId,
            "quality" to quality,
            "server" to choice.serverId,
            "csrf_token" to csrfToken
        )
    }

    fun extractMySeedPlayerGateway(html: String, pageUrl: String): String? {
        val document = org.jsoup.Jsoup.parse(html, pageUrl)
        val candidates = document.select("iframe[src]").mapNotNull { iframe ->
            val raw = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            absoluteHttpUrl(raw, pageUrl)
        } + extractUrlTokens(html).mapNotNull { token -> absoluteHttpUrl(token, pageUrl) }
        return candidates.firstOrNull { candidate ->
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return@firstOrNull false
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            host == "d.myseed.tv" || host.endsWith(".myseed.tv") &&
                (uri.path.orEmpty().contains("player", ignoreCase = true) || uri.path.orEmpty().contains("gateway", ignoreCase = true))
        }
    }

    fun parseMySeedDirectMediaSources(html: String, pageUrl: String): List<MySeedDirectMediaSource> {
        val document = org.jsoup.Jsoup.parse(html, pageUrl)
        val sources = document.select("video[src], video source[src], source[src]")
        return sources.mapNotNull { element ->
            val raw = element.absUrl("src").ifBlank { element.attr("src") }
            val url = absoluteHttpUrl(raw, pageUrl) ?: return@mapNotNull null
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("").lowercase(Locale.ROOT)
            val mimeType = element.attr("type").lowercase(Locale.ROOT)
            val mediaType = when {
                mimeType.contains("mpegurl") || mimeType.contains("m3u8") || path.endsWith(".m3u8") -> MySeedDirectMediaKind.M3U8
                mimeType.contains("dash+xml") || path.endsWith(".mpd") -> MySeedDirectMediaKind.DASH
                mimeType.startsWith("video/") || Regex("\\.(?:mp4|m4v|mkv|webm|mov|avi|ts)$").containsMatchIn(path) ||
                    element.tagName() == "video" || element.parents().any { it.tagName() == "video" } -> MySeedDirectMediaKind.VIDEO
                else -> return@mapNotNull null
            }
            val qualityContext = listOf(
                element.attr("label"), element.attr("size"), element.attr("res"), element.attr("data-quality"),
                element.attr("type"), element.parent()?.text()
            ).filterNotNull().joinToString(" ")
            MySeedDirectMediaSource(url, qualityFromText(qualityContext), mediaType)
        }.distinctBy { it.url }
    }

    fun originOf(rawUrl: String): String? = runCatching {
        val uri = URI(rawUrl)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) null
        else "${uri.scheme}://${uri.rawAuthority}"
    }.getOrNull()

    /**
     * Parse the selectable players from /watch/. MySeed has changed the player markup more than
     * once, so handle native media tags, server buttons carrying data-* URLs, iframe embeds, and
     * common inline-player scripts. Every output keeps the visible server and quality hint.
     */
    fun parseWatchServers(document: Document, pageUrl: String): List<MySeedServerOption> {
        val result = LinkedHashMap<String, MySeedServerOption>()
        val downloadLabeledUrls = mutableSetOf<String>()
        fun addWatchServerOption(option: MySeedServerOption) {
            if (isDownloadLabeledServerName(option.name)) {
                downloadLabeledUrls += option.url
                result.remove(option.url)
            } else if (option.url !in downloadLabeledUrls) {
                addServerOption(result, option)
            }
        }
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
                addWatchServerOption(MySeedServerOption(
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
                val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
                if (isStaticAsset(path) || isKnownNonMediaEndpoint(url)) continue
                if (isInternalPlayerUrl(url) && !isMySeedPlayerTarget(url)) continue
                val context = scriptContext(source, raw)
                val sourceAssignment = hasNearbyPlayerAssignment(source, raw)
                if (!isLikelyPlayerUrl(url) && !sourceAssignment) continue
                addWatchServerOption(MySeedServerOption(
                    name = serverName(null, context, url),
                    quality = qualityFromText(context) ?: qualityFromText(url),
                    url = url
                ))
            }
            if (playerScript) {
                for (encoded in BASE64_TOKEN.findAll(source).map { it.value }) {
                    val decoded = decodeBase64Url(encoded) ?: continue
                    val url = resolvePlayerTarget(decoded, pageUrl) ?: continue
                    val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
                    if (isStaticAsset(path) || isKnownNonMediaEndpoint(url)) continue
                    if (isInternalPlayerUrl(url) && !isMySeedPlayerTarget(url)) continue
                    val context = scriptContext(source, encoded)
                    val sourceAssignment = hasNearbyPlayerAssignment(source, encoded)
                    if (!isLikelyPlayerUrl(url) && !sourceAssignment) continue
                    addWatchServerOption(MySeedServerOption(
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

    fun resolvePlayerTarget(raw: String, base: String): String? {
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
        if (isStaticAsset(path) || isKnownNonMediaEndpoint(url)) return false
        if (isInternalPlayerUrl(url) && !isMySeedPlayerTarget(url)) return false
        if (isPlayerTag) return true
        val relevantAttribute = PLAYER_URL_ATTRIBUTES.any { attr ->
            attr != "href" && attr != "onclick" && element.hasAttr(attr)
        }
        return relevantAttribute || isPlayerOrServerElement(element) || isLikelyPlayerUrl(url)
    }

    private fun isLikelyPlayerUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (isStaticAsset(path) || isKnownNonMediaEndpoint(url)) return false
        if (isInternalPlayerUrl(url)) return isMySeedPlayerTarget(url)
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
                node.attr("data-server"), node.attr("data-server-name"), node.attr("data-quality"), node.attr("data-qu"), node.attr("data-name"),
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

    private fun mirrorProviderLabel(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val encoded = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
            parameter.substringBefore('=').takeIf { it.equals("id", ignoreCase = true) }
                ?.let { parameter.substringAfter('=', "") }
        } ?: return null
        val decodedUrl = decodeBase64Url(encoded) ?: return null
        val host = runCatching { URI(decodedUrl).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        return when {
            host.contains("vidara") -> "Vidara"
            host.contains("byse") -> "Byse"
            host == "voe.sx" || host.endsWith(".voe.sx") -> "VOE"
            else -> host.removePrefix("www.")
        }
    }

    private fun isDownloadLabeledServerName(value: String): Boolean =
        DOWNLOAD_SERVER_NAME_PREFIX.containsMatchIn(cleanText(value))

    private fun serverName(element: Element?, context: String, url: String): String {
        if (element != null) {
            val visibleDownloadLabel = cleanText(element.text())
            if (isDownloadLabeledServerName(visibleDownloadLabel)) return visibleDownloadLabel.take(60)
            for (attribute in listOf("data-server-name", "data-server", "data-name", "data-label", "aria-label", "title")) {
                val value = cleanText(element.attr(attribute))
                if (isUsefulServerLabel(value)) return value
            }
            val childLabel = element.selectFirst(".server-name, [class*=server-name], .server-title, strong, b")
                ?.text()?.let(::cleanText)
            if (isUsefulServerLabel(childLabel.orEmpty())) return childLabel.orEmpty()
            val ownLabel = cleanText(element.ownText())
            if (isUsefulServerLabel(ownLabel) &&
                (ownLabel.contains("سيرفر") || ownLabel.contains("server", true) || isDownloadLabeledServerName(ownLabel))
            ) {
                return ownLabel.take(60)
            }
        }
        mirrorProviderLabel(url)?.let { return it }
        val known = listOf("سيرفر ماي سيد المباشر", "سيرفر ماي سيد", "Fastdrive", "Userload", "Uptobox")
            .firstOrNull { context.contains(it, ignoreCase = true) }
        if (known != null) return known
        if (isInternalPlayerUrl(url) && runCatching { URI(url).path.equals("/vids.php", ignoreCase = true) }.getOrDefault(false)) {
            return "سيرفر ماي سيد المباشر"
        }
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

    private fun hasNearbyPlayerAssignment(source: String, token: String): Boolean {
        val index = source.indexOf(token)
        if (index < 0) return false
        val boundary = maxOf(
            source.lastIndexOf(';', index), source.lastIndexOf('{', index), source.lastIndexOf('}', index), index - 180
        ).coerceAtLeast(0)
        val prefix = source.substring(boundary, index)
        val keys = listOf("file", "src", "url", "embed", "player", "video", "server", "stream", "iframe", "source", "sources")
        return keys.any { key ->
            val keyIndex = prefix.lastIndexOf(key, ignoreCase = true)
            if (keyIndex < 0) return@any false
            val before = prefix.getOrNull(keyIndex - 1)
            val afterName = prefix.getOrNull(keyIndex + key.length)
            val isWord = (before == null || !before.isLetterOrDigit()) &&
                (afterName == null || !afterName.isLetterOrDigit())
            val assignment = prefix.substring(keyIndex + key.length).trimStart()
            isWord && (assignment.startsWith(":") || assignment.startsWith("="))
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
        return Regex("(?i)(?:eps?|episode|الحلقة|الحلقه)\\s*[-_ ]?(\\d{1,4})")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:^|[-_ ])s\\d{1,2}[-_ ]*e(?:ps?)?[-_ ]*(\\d{1,4})")
                .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun seasonNumber(url: String, label: String = ""): Int? {
        val text = normalizeDigits("${decodePath(url)} $label").replace(Regex("[-_]+"), " ")
        Regex("(?i)(?<![a-z0-9])(?:season|s)\\s*[-_ ]?(\\d{1,2})").find(text)
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
            .replace(
                Regex("(?i)(?:^|[\\s_-])(?:s\\d{1,2}[-_\\s]*e(?:ps?)?[-_\\s]*\\d{1,4}|(?:الحلقة|الحلقه)(?:[-_\\s]*(?:\\d{1,4}|الأولى|الاولى|الأول|الاول|الثانية|الثاني|الثالثة|الثالث|الرابعة|الرابع|الخامسة|الخامس|السادسة|السادس|السابعة|السابع|الثامنة|الثامن|التاسعة|التاسع|العاشرة|العاشر))?|episode[-_\\s]*\\d{1,4}|eps?[-_\\s]*\\d{1,4}).*$"
            ),
            ""
        ).trim()
        val withoutSeason = withoutEpisode
            .replace(Regex("(?i)(?:^|[\\s_-])(?:الموسم|موسم|season)[\\s_-]*.+$"), "")
            .replace(Regex("(?i)(?:^|[\\s_-])s\\d{1,2}(?:[-_\\s]+.*)?$"), "")
            .trim()
        return withoutSeason.ifBlank { withoutEpisode.ifBlank { clean } }
    }

    private fun normalizeSeriesSlug(value: String): String {
        var normalized = value.lowercase(Locale.ROOT)
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        while (true) {
            val suffix = seriesSlugSuffixes.firstOrNull { normalized.endsWith("-$it") } ?: break
            normalized = normalized.removeSuffix("-$suffix").trimEnd('-')
        }
        return normalized.removePrefix("مسلسل-").removePrefix("مسلسلات-").trim('-')
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
            .containsMatchIn(normalized) ||
            Regex("(?i)s\\d{1,2}[-_ ]*e(?:ps?)?[-_ ]*\\d{1,4}").containsMatchIn(normalized)
    }

    private fun isEpisodeAnchor(anchor: org.jsoup.nodes.Element): Boolean {
        val url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        val contentUrl = contentUrl(url, anchor.baseUri().ifBlank { BASE_URL }) ?: return false
        return isEpisodeUrl(contentUrl, anchorText(anchor))
    }

    private fun isEpisodeUrl(url: String, title: String): Boolean {
        val path = decodePath(url).lowercase(Locale.ROOT)
        val normalizedTitle = normalizeDigits(title).lowercase(Locale.ROOT)
        return Regex("(?i)(?:^|[-_/])s\\d{1,2}[-_\\s]*e(?:ps?)?[-_\\s]*\\d{1,4}(?:[-_/]|$)").containsMatchIn(path) ||
            Regex("(?i)(?:^|[-_/])(?:episode|eps?)[-_ ]?\\d{1,4}(?:[-_/]|$)").containsMatchIn(path) ||
            Regex("""(?:^|[-_/])(?:الحلقة|الحلقه)[-_ ]?\d{1,4}(?:[-_/]|$)""").containsMatchIn(path) ||
            Regex("(?i)s\\d{1,2}[-_\\s]*e(?:ps?)?[-_\\s]*\\d{1,4}").containsMatchIn(normalizedTitle) ||
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
