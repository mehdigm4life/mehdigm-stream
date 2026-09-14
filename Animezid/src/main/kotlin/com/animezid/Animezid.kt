package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Animezid : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.Cartoon,
        TvType.TvSeries, TvType.Movie, TvType.OVA
    )

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun pageHeaders(referer: String? = null) = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "Referer" to (referer ?: mainUrl)
    )

    // =========================================================================
    // Main page sections
    // =========================================================================

    override val mainPage = mainPageOf(
        "$mainUrl/category.php?cat=new-anime-eps&page="    to "أحدث حلقات الأنمي",
        "$mainUrl/category.php?cat=new-eps&page="          to "أحدث الحلقات",
        "$mainUrl/category.php?cat=anime-movies&page="     to "أفلام الأنمي",
        "$mainUrl/category.php?cat=dubbed-animation&page=" to "أفلام الأنيميشن المدبلجة",
        "$mainUrl/category.php?cat=disney-masr&page="      to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon&page="        to "سبيستون",
        "$mainUrl/category.php?cat=new-movies&page="       to "أحدث الأفلام",
        "$mainUrl/topvideos.php?page="                     to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val doc = getPage(url)

        var items = doc.select("a.az-card__link").mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) {
            items = doc.select("a.az-showcase-card__link").mapNotNull { it.toSearchResponse() }
        }

        val hasNext = items.size >= 40

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    // =========================================================================
    // Search
    // =========================================================================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${query.encode()}"
        val doc = getPage(url)
        var items = doc.select("a.az-card__link").mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) {
            items = doc.select("a.az-showcase-card__link").mapNotNull { it.toSearchResponse() }
        }
        return items
    }

    /**
     * Parse an <a class="az-card__link"> or <a class="az-showcase-card__link">
     * into a SearchResponse. Two link patterns:
     *   - /series/{slug}/            → a full series page
     *   - /watch.php?vid={id}        → a single episode / movie
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        val absHref = fixUrl(href)

        val title = selectFirst(".az-card__title")?.text()?.trim()
            ?: attr("aria-label").trim().ifBlank { return null }

        val poster = selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-fallback") }
        }?.ifBlank { null }?.let { fixUrl(it) }

        val isSeriesLink = absHref.contains("/series/")

        val tvType = when {
            isSeriesLink -> typeFromText(title, default = TvType.Anime)
            title.contains("فيلم") -> TvType.Movie
            title.contains("الحلقة") -> TvType.Anime
            else -> typeFromText(title, default = TvType.Anime)
        }

        return if (tvType == TvType.Movie || tvType == TvType.AnimeMovie) {
            newMovieSearchResponse(title, absHref, tvType) { this.posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, absHref, tvType) { this.posterUrl = poster }
        }
    }

    // =========================================================================
    // Load
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        val clean = url.removePrefix("SERIES::")
        return when {
            clean.contains("/series/") -> buildSeriesFromSeriesPage(clean)
            clean.contains("watch.php") -> {
                val doc = getPage(clean)
                val hasSeasonTabs =
                    doc.selectFirst("nav.az-cinema-season-tabs a[data-season-link]") != null
                if (hasSeasonTabs) buildSeriesFromWatchPage(clean, doc)
                else buildMovieFromWatchPage(clean, doc)
            }
            else -> {
                val doc = getPage(clean)
                buildMovieFromWatchPage(clean, doc)
            }
        }
    }

    // -----------------------------------------------------------------
    // Series from /series/{slug} page (AJAX season enumeration)
    // -----------------------------------------------------------------

    private suspend fun buildSeriesFromSeriesPage(seriesUrl: String): LoadResponse {
        val doc = getPage(seriesUrl)

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Animezid"
        val title = cleanupSeriesTitle(rawTitle) ?: rawTitle
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.let { cleanupDescription(it) }

        val seasonLinks = doc.select("a.az-card__link[href*=/season/]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val num = Regex("/season/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (href.isNotBlank() && num != null) fixUrl(href) to num else null
            }
            .sortedBy { it.second }
            .distinctBy { it.second }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        for ((seasonUrl, seasonNum) in seasonLinks) {
            var page = 1

            while (page <= 15) {
                val ajaxUrl = "${seasonUrl}?ajax=episodes&page=$page"
                val ajaxDoc = try {
                    val resp = app.get(
                        ajaxUrl,
                        headers = pageHeaders(seasonUrl) +
                            ("X-Requested-With" to "XMLHttpRequest"),
                        referer = seasonUrl
                    )
                    if (!resp.isSuccessful) break
                    resp.document
                } catch (_: Exception) {
                    break
                }

                val items = ajaxDoc.select("div.az-series-episode-grid-item")
                if (items.isEmpty()) break

                var newCount = 0
                for (item in items) {
                    val href = item.selectFirst("a.az-card__link")?.attr("href")
                        ?: continue
                    if (!seen.add(href)) continue
                    val num = item.attr("data-episode-number").toDoubleOrNull()?.toInt()
                        ?: Regex("(\\d+)").find(item.attr("data-episode-title"))
                            ?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0
                    episodes.add(newEpisode(href) {
                        this.name = "الحلقة $num"
                        this.episode = num
                        this.season = seasonNum
                    })
                    newCount++
                }

                if (newCount == 0) break
                page++
            }
        }

        val sorted = episodes.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE })
        )

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    // -----------------------------------------------------------------
    // Series from watch.php page (rich metadata + season tabs + AJAX)
    // -----------------------------------------------------------------

    private suspend fun buildSeriesFromWatchPage(
        watchUrl: String,
        doc: Document
    ): LoadResponse {
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Animezid"
        val title = cleanupSeriesTitle(rawTitle) ?: rawTitle

        val poster = doc.selectFirst("figure.az-cinema-poster img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-fallback") }
        }?.ifBlank { null }?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("p.az-cinema-summary")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.let { cleanupDescription(it) }

        val year = extractYearFromMeta(doc)
        val tags = extractTagsFromMeta(doc)
        val type = typeFromText(title, tags, default = TvType.Anime)

        // Collect season tabs
        val seasonTabs = doc.select("nav.az-cinema-season-tabs a[data-season-link]")
            .mapNotNull { a ->
                val seasonNum = a.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                val seasonCount = a.attr("data-season-count").toIntOrNull() ?: 0
                val seasonUrl = fixUrl(a.attr("href"))
                Triple(seasonNum, seasonCount, seasonUrl)
            }
            .sortedBy { it.first }
            .distinctBy { it.first }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        // Determine which season is active (shown in episode grid)
        val activeSeasonHref = doc.selectFirst("a[data-season-link].is-active")
            ?.attr("href")?.let { fixUrl(it) }
        val activeSeasonNum = seasonTabs
            .firstOrNull { it.third == activeSeasonHref }?.first
            ?: seasonTabs.firstOrNull()?.first

        // Episodes from the currently-displayed season grid
        if (activeSeasonNum != null) {
            doc.select("div.az-cinema-episode-grid a[role=listitem]").forEach { a ->
                val href = a.attr("href")
                if (!seen.add(href)) return@forEach
                val num = a.selectFirst("strong")?.text()?.trim()?.toIntOrNull() ?: 0
                episodes.add(newEpisode(href) {
                    this.name = "الحلقة $num"
                    this.episode = num
                    this.season = activeSeasonNum
                })
            }
        }

        // Fetch other seasons (and fill any missing episodes of active) via AJAX
        for ((seasonNum, seasonCount, seasonUrl) in seasonTabs) {
            var page = 1
            var done = seasonCount > 0 &&
                episodes.count { it.season == seasonNum } >= seasonCount

            while (!done && page <= 15) {
                val ajaxUrl = "${seasonUrl}?ajax=episodes&page=$page"
                val ajaxDoc = try {
                    val resp = app.get(
                        ajaxUrl,
                        headers = pageHeaders(seasonUrl) +
                            ("X-Requested-With" to "XMLHttpRequest"),
                        referer = seasonUrl
                    )
                    if (!resp.isSuccessful) break
                    resp.document
                } catch (_: Exception) {
                    break
                }

                val items = ajaxDoc.select("div.az-series-episode-grid-item")
                if (items.isEmpty()) break

                var newCount = 0
                for (item in items) {
                    val href = item.selectFirst("a.az-card__link")?.attr("href")
                        ?: continue
                    if (!seen.add(href)) continue
                    val num = item.attr("data-episode-number").toDoubleOrNull()?.toInt() ?: 0
                    episodes.add(newEpisode(href) {
                        this.name = "الحلقة $num"
                        this.episode = num
                        this.season = seasonNum
                    })
                    newCount++
                }

                if (newCount == 0) break
                done = seasonCount > 0 &&
                    episodes.count { it.season == seasonNum } >= seasonCount
                page++
            }
        }

        val sorted = episodes.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE })
        )

        return newAnimeLoadResponse(title, watchUrl, type) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    // -----------------------------------------------------------------
    // Movie from watch.php page
    // -----------------------------------------------------------------

    private suspend fun buildMovieFromWatchPage(watchUrl: String, doc: Document): LoadResponse {
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Animezid"
        val title = cleanupMovieTitle(rawTitle)

        val poster = doc.selectFirst("figure.az-cinema-poster img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-fallback") }
        }?.ifBlank { null }?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("p.az-cinema-summary")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.let { cleanupDescription(it) }

        val year = extractYearFromMeta(doc)
        val tags = extractTagsFromMeta(doc)
        val type = typeFromText(title, tags, default = TvType.AnimeMovie)

        return newMovieLoadResponse(title, watchUrl, type, watchUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // =========================================================================
    // loadLinks
    //
    // 1) Protected web-playback API path (preferred):
    //      GET  play.php?vid=XXX        → CSRF + session cookie
    //      POST /web-playback/sessions  → session_id + sources[]
    //      POST /sessions/{sid}/sources/{srcId}/resolve → launch_url
    //      GET  launch_url (follow redirect) → final embed host
    //      loadExtractor(finalUrl) or generic m3u8 fallback
    //
    // 2) Legacy fallback (old play.php layout):
    //      button[data-embed] / iframe[src] → loadExtractor
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
        val playUrl = when {
            data.contains("/play.php") -> data
            data.contains("vid=") -> {
                val vid = Regex("vid=([A-Za-z0-9]+)").find(data)?.groupValues?.get(1)
                if (vid != null) "$mainUrl/play.php?vid=$vid" else data
            }
            else -> data.replace("/watch.php", "/play.php")
        }

        val playResp = try {
            app.get(playUrl, headers = pageHeaders(mainUrl), referer = mainUrl)
        } catch (_: Exception) {
            return false
        }
        if (playResp.code !in 200..299) return false
        val playDoc = playResp.document
        val cookieHeader = buildCookieHeader(playResp.cookies)

        // ----- Protected playback API -----
        val csrf = playDoc.selectFirst("[data-playback-csrf]")
            ?.attr("data-playback-csrf")
        val createUrl = playDoc.selectFirst("[data-playback-create-url]")
            ?.attr("data-playback-create-url")
        val videoUniq = playDoc.selectFirst("[data-video-uniq]")
            ?.attr("data-video-uniq")
            ?: Regex("vid=([A-Za-z0-9]+)").find(playUrl)?.groupValues?.get(1)
            ?: return false

        if (csrf != null && createUrl != null) {
            val found = loadViaProtectedApi(
                createUrl, csrf, videoUniq, playUrl, cookieHeader,
                subtitleCallback, callback
            )
            if (found) return true
        }

        // ----- Legacy fallback: old button[data-embed] / iframe -----
        return loadViaLegacyEmbeds(playDoc, playUrl, subtitleCallback, callback)
    }

    // -----------------------------------------------------------------
    // Protected playback API
    // -----------------------------------------------------------------

    private suspend fun loadViaProtectedApi(
        createUrl: String,
        csrf: String,
        contentId: String,
        playUrl: String,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = playbackHeaders(csrf, playUrl, cookies)

        val sessionJson = retryPost(createUrl, headers, mapOf("content_id" to contentId), playUrl)
            ?: return false

        val sessionId = sessionJson.optString("session_id")
        if (sessionId.isBlank()) return false
        val sources = sessionJson.optJSONArray("sources") ?: return false

        val all = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
        val embedded = all.filter { it.optString("type") == "embedded_web" }
        val downloads = all.filter { it.optString("type") == "download" }
            .distinctBy { it.optString("provider") + "|" + it.optString("quality") }

        var found = false

        // Watch sources first (sequential + gentle pacing so the API never rate-limits us)
        for (src in embedded) {
            val ok = handlePlaybackSource(
                src, createUrl, sessionId, headers, playUrl,
                isDownload = false, subtitleCallback, callback
            )
            if (ok) found = true
            delay(900L)
        }

        // Download-type sources resolve to file-host pages; only surfaced when a
        // real media URL can be produced, otherwise silently skipped.
        for (src in downloads.take(10)) {
            val ok = handlePlaybackSource(
                src, createUrl, sessionId, headers, playUrl,
                isDownload = true, subtitleCallback, callback
            )
            if (ok) found = true
            delay(1100L)
        }

        return found
    }

    /**
     * Resolve a single source and produce an [ExtractorLink].
     * 1) Resolve source -> launch_url (with rate-limit backoff)
     * 2) Follow redirect to the real embed host
     * 3) Run the registered custom/built-in extractors
     * 4) Generic m3u8/mp4 scrape of the embed page
     * 5) Raw link fallback only for direct media URLs (prevents HTTP-status
     *    playback errors like ERROR_CODE_IO_BAD_HTTP_STATUS 2004)
     */
    private suspend fun handlePlaybackSource(
        src: JSONObject,
        createUrl: String,
        sessionId: String,
        headers: Map<String, String>,
        playUrl: String,
        isDownload: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val srcId = src.optString("id")
        if (srcId.isBlank()) return false
        val providerName = src.optString("provider")
        val qualityInt = src.optString("quality").toIntOrNull()

        // 1) Resolve source → launch_url
        val resolveUrl = "$createUrl/$sessionId/sources/$srcId/resolve"
        val resolveJson = retryResolve(resolveUrl, headers, playUrl) ?: return false
        val launchUrl = resolveJson.optString("launch_url").ifBlank { return false }

        // 2) Follow redirect to real embed host
        val finalUrl = try {
            val resp = app.get(
                launchUrl,
                headers = headers + pageHeaders(playUrl),
                referer = playUrl
            )
            resp.url
        } catch (_: Exception) {
            launchUrl
        }

        if (finalUrl.isBlank() || isSelfHost(finalUrl)) return false

        // Download sources that resolve straight to a media file can be used as-is.
        if (isDownload && looksLikeMedia(finalUrl)) {
            emitRawMediaLink(finalUrl, providerName, qualityInt, playUrl, callback)
            return true
        }

        // 3) Try registered extractors (custom + built-ins)
        var extracted = false
        val extractorUrl = normalizeEmbedUrl(finalUrl)
        try {
            extracted = loadExtractor(
                extractorUrl, playUrl, subtitleCallback
            ) { link ->
                callback(link)
            }
        } catch (_: Exception) {
            // ignore
        }

        // 4) Provider-aware m3u8/mp4 extraction from embed page
        //    (direct regex + packed-JS decode for protected hosts)
        if (!extracted) {
            extracted = tryProviderExtract(finalUrl, providerName, playUrl, callback)
        }

        // 5) Raw fallback only for direct media URLs; page-based hosts are dropped
        //    so the menu never shows links that fail with HTTP-status errors.
        if (!extracted && looksLikeMedia(finalUrl)) {
            runCatching {
                emitRawMediaLink(finalUrl, providerName, qualityInt, playUrl, callback)
                extracted = true
            }
        }

        return extracted
    }

    private suspend fun emitRawMediaLink(
        url: String,
        providerName: String,
        qualityInt: Int?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = url.contains(".m3u8") || url.contains(".txt")
        callback(
            newExtractorLink(
                source = this.name,
                name = "$name $providerName",
                url = url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = qualityInt ?: Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to browserUA)
            }
        )
    }

    private fun looksLikeMedia(url: String): Boolean {
        val clean = Regex("""([^?#]+)""").find(url)?.groupValues?.get(1) ?: url
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") ||
            clean.endsWith(".mkv") || clean.endsWith(".webm") || clean.endsWith(".txt")
    }

    /** Regex extraction of m3u8/mp4 URLs from an embed page.
     *  Handles direct URLs and Dean Edwards (p,a,c,k,e,d) packed-JS pages
     *  used by Uqload, StreamRuby/cdn, StreamWish, TurboViPlay etc. */
    private suspend fun tryProviderExtract(
        embedUrl: String,
        providerName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawText = try {
            val resp = app.get(embedUrl, headers = pageHeaders(referer), referer = referer)
            resp.text
        } catch (_: Exception) {
            return false
        }

        val decodePacked = decodePacker(rawText)
        val texts = listOfNotNull(rawText, decodePacked)

        val host = getHostFromUrl(embedUrl)

        // Prefer HLS (signed/plain), then direct mp4; dedupe across variants.
        val m3u8Set = linkedSetOf<String>()
        for (t in texts) {
            Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""")
                .findAll(t)
                .forEach { m3u8Set.add(it.value) }
        }
        val m3u8 = m3u8Set.firstOrNull()
        if (m3u8 != null) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name $providerName",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to browserUA,
                        "Origin" to host,
                        "Referer" to embedUrl
                    )
                }
            )
            return true
        }

        // Fallback: direct mp4
        val mp4Set = linkedSetOf<String>()
        for (t in texts) {
            Regex("""https?://[^"'<>\s]+\.mp4[^"'<>\s]*""")
                .findAll(t)
                .forEach { mp4Set.add(it.value) }
        }
        val mp4 = mp4Set.firstOrNull()
        if (mp4 != null) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name $providerName",
                    url = mp4,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to browserUA,
                        "Referer" to embedUrl
                    )
                }
            )
            return true
        }

        return false
    }

    /** Decode a Dean Edwards packer: eval(function(p,a,c,k,e,d){...})('PAYLOAD',a,c,'KEY...'.split('|'),...)
     *  Returns the unpacked JavaScript source, or null when not a packer. */
    private fun decodePacker(html: String): String? {
        val start = html.indexOf("function(p,a,c,k,e,d)")
        if (start < 0) return null

        val tail = html.substring(start)
        val matcher = Regex(
            """\((['"])(.*?)\1,\s*(\d+),(\d+),\s*['\"]([^'\"]*)['\"]\.split\(['\"]\|['\"]\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(tail) ?: return null

        val payload = matcher.groupValues[2]
        val radix = matcher.groupValues[3].toIntOrNull() ?: return null
        val count = matcher.groupValues[4].toIntOrNull() ?: return null
        val keys = matcher.groupValues[5].split("|")

        if (payload.isEmpty() || radix !in 2..36 || count < 1) return null

        var out = payload
        for (i in (count - 1) downTo 0) {
            val symbol = packerSymbol(i, radix)
            val key = keys.getOrNull(i) ?: continue
            out = Regex("\\b" + Regex.escape(symbol) + "\\b")
                .replace(out) { key }
        }
        return out
    }

    /** Base-`radix` word representation used by the packer (like JS toString(radix)). */
    private fun packerSymbol(value: Int, radix: Int): String {
        if (value < radix) return Integer.toString(value, radix)
        return packerSymbol(value / radix, radix) + Integer.toString(value % radix, radix)
    }

    /** Retry POST with exponential back-off (for 403 / rate-limit). */
    private suspend fun retryPost(
        url: String,
        headers: Map<String, String>,
        jsonBody: Map<String, String>,
        referer: String,
        maxRetries: Int = 3
    ): JSONObject? {
        repeat(maxRetries) { attempt ->
            try {
                val resp = app.post(
                    url,
                    headers = headers,
                    json = jsonBody,
                    referer = referer
                )
                if (resp.code == 200 || resp.code == 201) {
                    return try { JSONObject(resp.text) } catch (_: Exception) { null }
                }
            } catch (_: Exception) {
                // will retry
            }
            delay(600L * (attempt + 1))
        }
        return null
    }

    /** Resolve a playback source with back-off when the API replies "rate_limited". */
    private suspend fun retryResolve(
        url: String,
        headers: Map<String, String>,
        referer: String,
        maxRetries: Int = 4
    ): JSONObject? {
        repeat(maxRetries) { attempt ->
            try {
                val resp = app.post(
                    url,
                    headers = headers,
                    json = mapOf<String, String>(),
                    referer = referer
                )
                if (resp.code == 200 || resp.code == 201) {
                    val obj = try { JSONObject(resp.text) } catch (_: Exception) { return null }
                    // The API answers 200 + {"error":"rate_limited"}; keep retrying.
                    if (obj.optString("error") != "rate_limited") return obj
                } else if (resp.code != 429) {
                    return null
                }
            } catch (_: Exception) {
                // transient network error -> retry
            }
            delay(1200L * (attempt + 1))
        }
        return null
    }

    private fun playbackHeaders(csrf: String, referer: String, cookies: Map<String, String>) =
        mapOf(
            "User-Agent" to browserUA,
            "Accept" to "*/*",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "X-Playback-CSRF" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to referer,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        ) + cookies

private fun buildCookieHeader(cookies: Map<String, String>): Map<String, String> {
    val value = cookies.entries
        .filter { it.value.isNotBlank() }
        .joinToString("; ") { "${it.key}=${it.value}" }
    return if (value.isBlank()) emptyMap() else mapOf("Cookie" to value)
}

    // -----------------------------------------------------------------
    // Legacy fallback (old button[data-embed] / iframe layout)
    // -----------------------------------------------------------------

    private suspend fun loadViaLegacyEmbeds(
        playDoc: Document,
        playUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val embeds = mutableListOf<String>()

        playDoc.select("button[data-embed]").forEach { btn ->
            val v = btn.attr("data-embed").trim()
            if (v.isNotBlank()) embeds.add(fixUrl(v))
        }
        playDoc.select("iframe[src]").forEach { iframe ->
            val v = iframe.attr("src").trim()
            if (v.isNotBlank()) embeds.add(fixUrl(v))
        }

        val seen = mutableSetOf<String>()
        for (link in embeds) {
            if (!seen.add(link)) continue
            try {
                if (loadExtractor(
                        link, playUrl, subtitleCallback
                    ) { callback(it) }
                ) found = true
            } catch (_: Exception) {
                // ignore
            }
        }
        return found
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun isSelfHost(url: String): Boolean {
        val host = try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        } ?: return false
        return host.contains("animezid.cam") ||
                host.contains("animezid.com") ||
                host.contains("animezid.net")
    }

    /** Embed hosts that need rewriting to a domain handled by Cloudstream's extractors. */
    private fun normalizeEmbedUrl(url: String): String = url

    private fun getHostFromUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "https://${uri.host}"
        } catch (_: Exception) {
            url
        }
    }

    private suspend fun getPage(
        url: String,
        referer: String? = null
    ): Document {
        return app.get(
            url,
            headers = pageHeaders(referer),
            referer = referer ?: mainUrl
        ).document
    }

    private fun typeFromText(
        text: String,
        tags: List<String>? = null,
        default: TvType
    ): TvType {
        val combined = text.lowercase() + " " + (tags?.joinToString(" ")?.lowercase() ?: "")
        return when {
            combined.contains("كرتون") && combined.contains("فيلم") -> TvType.AnimeMovie
            combined.contains("كرتون") || combined.contains("cartoon") -> TvType.Cartoon
            combined.contains("انميشن") && combined.contains("فيلم") -> TvType.AnimeMovie
            combined.contains("انميشن") -> TvType.Cartoon
            combined.contains("فيلم") &&
                (combined.contains("انمي") || combined.contains("انيميشن")) -> TvType.AnimeMovie
            combined.contains("فيلم") -> TvType.Movie
            combined.contains("مانغا") || combined.contains("اوفا") -> TvType.OVA
            else -> default
        }
    }

    private fun extractYearFromMeta(doc: Document): Int? {
        doc.select("ul.az-cinema-meta li").forEach { li ->
            val label = li.selectFirst("small")?.text()?.trim() ?: return@forEach
            if ("السنة" in label) {
                return li.selectFirst("a")?.text()?.trim()?.toIntOrNull()
            }
        }
        return null
    }

    private fun extractTagsFromMeta(doc: Document): List<String> {
        val tags = mutableListOf<String>()
        doc.select("ul.az-cinema-meta li").forEach { li ->
            val label = li.selectFirst("small")?.text()?.trim() ?: return@forEach
            val value = li.selectFirst("a")?.text()?.trim() ?: return@forEach
            if (label in listOf("النوع", "البلد", "الترجمة", "الجودة") && value.isNotBlank()) {
                tags.add(value)
            }
        }
        return tags
    }

    private fun cleanupSeriesTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
        t = t.removePrefix("انمي").trim()
        t = t.removePrefix("أنمي").trim()
        t = t.removePrefix("مسلسل كرتون").trim()
        t = t.removePrefix("مسلسل").trim()
        t = t.removePrefix("كرتون").trim()
        t = t.replace(Regex("\\s*(الموسم|المواسم)\\s+\\S+.*$"), "").trim()
        t = t.replace(Regex("\\s*الحلقة\\s+\\S+.*$"), "").trim()
        t = t.replace(Regex("\\s*(مترجم(?:ة)?|مدبلج(?:ة)?)\\s*$"), "").trim()
        t = t.replace(Regex("\\s*\\|\\s*$"), "").trim()
        return t.ifBlank { raw.trim() }
    }

    private fun cleanupMovieTitle(raw: String): String {
        var t = raw.trim()
        t = t.removePrefix("مشاهدة وتحميل").trim()
        t = t.removePrefix("مشاهدة").trim()
        t = t.replace(Regex("\\s*اونلاين.*$"), "").trim()
        t = t.replace(Regex("\\s*مترجم(?:ة)?\\s*$"), "").trim()
        t = t.replace(Regex("\\s*مدبلج(?:ة)?\\s*$"), "").trim()
        return t.trim()
    }

    private fun cleanupDescription(raw: String): String {
        return raw.trim()
            .replace(Regex("^تحميل ومشاهدة\\s*"), "")
            .replace(Regex("\\s*اونلاين.*$"), "")
            .replace(Regex("\\s*انمي زد.*$"), "")
            .replace(Regex("\\s*الحلقة.*$"), "")
            .replace(Regex("\\s*(مترجم|مدبلج)(ة)?\\s*(كامل(ة)?)?\\s*$"), "")
            .replace(Regex("\\s*\\|\\s*$"), "")
            .trim()
    }

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http") -> url
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> mainUrl + url
            else                   -> "$mainUrl/$url"
        }
    }
}
