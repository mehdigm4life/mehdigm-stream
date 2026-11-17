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
    override var mainUrl = "https://animezid.org/"
    private val siteRoot: String get() = mainUrl.trimEnd('/')

    override var name = "انمي زد (Animezid)"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.Cartoon,
        TvType.TvSeries, TvType.Movie, TvType.OVA
    )

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val pageHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1"
    )

    // =========================================================================
    // Main page sections
    // =========================================================================

    override val mainPage = mainPageOf(
        "$siteRoot/newvideos.php?page=" to "أحدث الإضافات",
        "$siteRoot/category.php?cat=anime&series_sort=newest&series_page=" to "الأنمي",
        "$siteRoot/category.php?cat=cartoon&series_sort=newest&series_page=" to "مسلسلات كرتون",
        "$siteRoot/category.php?cat=dubbed-anime&series_sort=newest&series_page=" to "أنمي مدبلج",
        "$siteRoot/category.php?cat=series&series_sort=newest&series_page=" to "المسلسلات",
        "$siteRoot/category.php?cat=movies&page=" to "الأفلام",
        "$siteRoot/category.php?cat=new-movies&page=" to "أحدث الأفلام",
        "$siteRoot/category.php?cat=dubbed-animation&page=" to "انيميشن مدبلج",
        "$siteRoot/category.php?cat=subbed-animation&page=" to "انيميشن مترجم",
        "$siteRoot/category.php?cat=disney-masr&page=" to "ديزني بالمصري",
        "$siteRoot/category.php?cat=spacetoon&series_sort=newest&series_page=" to "سبيستون",
        "$siteRoot/topvideos.php?page=" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getPage(request.data + page)
        val items = collectCards(doc)
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.size >= 40
        )
    }

    // =========================================================================
    // Search
    // =========================================================================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getPage("$siteRoot/search.php?keywords=${query.encode()}")
        return collectCards(doc)
    }

    /** Collect all title cards from a listing page, dropping season sub-pages. */
    private fun collectCards(doc: Document): List<SearchResponse> =
        doc.select("a.az-card__link, a.az-showcase-card__link")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val absHref = fixUrl(href)
        if (absHref.contains("/season/")) return null

        val title = selectFirst(".az-card__title")?.text()?.trim()
            ?: selectFirst(".az-showcase-card__copy strong")?.text()?.trim()
            ?: attr("aria-label").trim()
        if (title.isBlank()) return null

        val poster = (selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-fallback") }
        })?.ifBlank { null }?.let { fixUrl(it) }

        val cardText = this.outerHtml() + " " + title

        val qualityText = Regex("""az-badge--quality">([^<]+)""")
            .find(cardText)?.groupValues?.get(1)?.trim()
        val ratingStr = Regex("""az-badge--rating[^>]*>.*?([0-9]+(?:\.[0-9]+)?)""")
            .find(cardText)?.groupValues?.get(1)
        val ribbon = Regex("""az-badge--ribbon[^>]*>\s*<span[^>]*>([^<]+)</span>""")
            .find(cardText)?.groupValues?.get(1)

        val isSeries = absHref.contains("/series/")
        val type = typeFromText(title, default = if (isSeries) TvType.Anime else TvType.Anime)

        val dubStatusSet = when {
            ribbon?.contains("مدبلج") == true -> setOf(DubStatus.Dubbed)
            ribbon?.contains("مترجم") == true -> setOf(DubStatus.Subbed)
            title.contains("مدبلج") || title.contains("مدبلجة") -> setOf(DubStatus.Dubbed)
            title.contains("مترجم") || title.contains("مترجمة") -> setOf(DubStatus.Subbed)
            else -> null
        }

        val searchQuality = when (qualityText) {
            "4K", "UHD" -> SearchQuality.UHD
            "FHD", "HD", "1080p" -> SearchQuality.HD
            "WEB-DL", "WEBRip", "WEB" -> SearchQuality.WebRip
            "BluRay", "BD" -> SearchQuality.BlueRay
            "HDRip", "HDTV" -> SearchQuality.HDR
            "DVDRip", "DVD" -> SearchQuality.DVD
            "CAM", "TS" -> SearchQuality.Cam
            else -> null
        }

        val score = ratingStr?.toDoubleOrNull()?.let { Score.from10(it) }

        return if (type == TvType.Movie || type == TvType.AnimeMovie) {
            newMovieSearchResponse(title, absHref, type) {
                this.posterUrl = poster
                searchQuality?.let { this.quality = it }
                score?.let { this.score = it }
            }
        } else {
            newAnimeSearchResponse(title, absHref, type) {
                this.posterUrl = poster
                searchQuality?.let { this.quality = it }
                score?.let { this.score = it }
                dubStatusSet?.let { this.dubStatus = it.toMutableSet() }
            }
        }
    }

    // =========================================================================
    // Load
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("/series/") -> buildSeries(url)
            url.contains("watch.php") -> {
                val doc = getPage(url)
                val seriesLink = doc.selectFirst("a[href*='/series/']")?.attr("href")?.let { fixUrl(it) }
                val isEpisode =
                    doc.selectFirst("section.az-series-episodes a[href*='watch.php']") != null
                if (seriesLink != null && isEpisode) buildSeries(seriesLink)
                else buildMovie(url, doc)
            }
            else -> buildMovie(url, getPage(url))
        }
    }

    // -----------------------------------------------------------------
    // Series: enumerate every season page and its episodes
    // -----------------------------------------------------------------

    private suspend fun buildSeries(seriesUrl: String): LoadResponse {
        val doc = getPage(seriesUrl)

        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Animezid"
        val title = cleanupSeriesTitle(rawTitle)

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }?.let { fixUrl(it) }

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("section.az-series-detail-hero p")?.text()?.trim()

        val year = Regex("""(19|20)\d{2}""").find(doc.text())?.value?.toIntOrNull()

        val seasonLinks = doc.select("article.az-season-poster-card a[href*=/season/]")
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val num = Regex("/season/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (num != null) num to href else null
            }
            .sortedBy { it.first }
            .distinctBy { it.first }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        for ((seasonNum, seasonUrl2) in seasonLinks) {
            var page = 1
            while (page <= 60) {
                val pageUrl = if (page == 1) seasonUrl2 else "$seasonUrl2?page=$page"
                val pageDoc = try {
                    getPage(pageUrl, referer = seriesUrl)
                } catch (_: Exception) {
                    break
                }

                val cards = pageDoc.select("section.az-series-episodes a.az-card__link[href*='watch.php']")
                if (cards.isEmpty()) break

                var added = 0
                for (card in cards) {
                    val href = card.attr("href").ifBlank { continue }
                    val absHref = fixUrl(href)
                    if (!seen.add(absHref)) continue

                    val label = card.attr("aria-label") + " " + card.text()
                    val number = Regex("""الحلقة\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0

                    episodes.add(newEpisode(absHref) {
                        this.name = if (number > 0) "الحلقة $number" else null
                        this.episode = number
                        this.season = seasonNum
                    })
                    added++
                }

                if (added == 0) break
                page++
            }
        }

        val sorted = episodes.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE })
        )

        val type = typeFromText(title, default = TvType.Anime)

        return newAnimeLoadResponse(title, seriesUrl, type) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addEpisodes(DubStatus.Subbed, sorted)
        }
    }

    // -----------------------------------------------------------------
    // Movie (single watch.php title)
    // -----------------------------------------------------------------

    private suspend fun buildMovie(watchUrl: String, doc: Document): LoadResponse {
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Animezid"
        val title = cleanupMovieTitle(rawTitle)

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }?.let { fixUrl(it) }

        val plot = doc.selectFirst("p.az-cinema-summary")?.text()?.trim()
            ?: doc.selectFirst(".az-watch-page .az-rich-text")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = Regex("""(19|20)\d{2}""").find(doc.text())?.value?.toIntOrNull()
        val tags = doc.select(".az-watch-tags__list a").mapNotNull { it.text().trim().ifBlank { null } }
        val type = typeFromText(title, tags, default = TvType.AnimeMovie)

        return newMovieLoadResponse(title, watchUrl, type, watchUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // =========================================================================
    // Load links (protected playback API)
    // =========================================================================

    private data class PlaybackSession(
        val create: String,
        val id: String,
        val headers: Map<String, String>,
        val embedded: List<JSONObject>
    )

    private class RateLimited : Exception()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playUrl = toPlayUrl(data)

        val first = createPlaybackSession(playUrl)
            ?: return loadLegacyEmbeds(getPage(playUrl), playUrl, subtitleCallback, callback)
        if (first.embedded.isEmpty()) {
            return loadLegacyEmbeds(getPage(playUrl), playUrl, subtitleCallback, callback)
        }

        // The playback API allows only ~5 resolves per session, so the sources are
        // processed in batches, each with a freshly opened playback session.
        val batchSize = 5
        var session = first
        var found = false
        var index = 0

        while (index < first.embedded.size) {
            if (index > 0 && index % batchSize == 0) {
                session = createPlaybackSession(playUrl) ?: session
            }
            val end = minOf(index + batchSize, first.embedded.size)
            for (i in index until end) {
                val src = first.embedded[i]
                val ok = try {
                    resolveAndEmit(src, session, playUrl, subtitleCallback, callback)
                } catch (_: RateLimited) {
                    val rotated = createPlaybackSession(playUrl)
                    if (rotated != null) {
                        session = rotated
                        try {
                            resolveAndEmit(src, rotated, playUrl, subtitleCallback, callback)
                        } catch (_: Exception) {
                            false
                        }
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
                if (ok) found = true
                delay(600L)
            }
            index = end
            if (index < first.embedded.size) delay(1200L)
        }
        return found
    }

    /**
     * Open a brand new playback session and return its embedded sources. The explicit
     * Cookie header (a non-session value) prevents any shared cookie jar from reusing a
     * previous PHPSESSID, which is what forces the server to hand out a new session.
     */
    private suspend fun createPlaybackSession(playUrl: String): PlaybackSession? {
        val resp = try {
            app.get(
                playUrl,
                headers = pageHeaders + ("Cookie" to "az_player=1"),
                referer = mainUrl
            )
        } catch (_: Exception) {
            return null
        }
        if (!resp.isSuccessful) return null

        val doc = resp.document
        val csrf = doc.selectFirst("[data-playback-csrf]")?.attr("data-playback-csrf") ?: return null
        val create = doc.selectFirst("[data-playback-create-url]")?.attr("data-playback-create-url") ?: return null
        val vid = doc.selectFirst("[data-video-uniq]")?.attr("data-video-uniq")
            ?: Regex("vid=([A-Za-z0-9]+)").find(playUrl)?.groupValues?.get(1)
            ?: return null

        val headers = playbackHeaders(csrf, playUrl, resp.cookies)
        val session = retryPost(create, headers, mapOf("content_id" to vid), playUrl) ?: return null
        val sid = session.optString("session_id").ifBlank { return null }
        val arr = session.optJSONArray("sources") ?: return null
        val embedded = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("type") == "embedded_web" }
        return PlaybackSession(create, sid, headers, embedded)
    }

    private suspend fun resolveAndEmit(
        src: JSONObject,
        session: PlaybackSession,
        playUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val srcId = src.optString("id").ifBlank { return false }
        val provider = src.optString("provider")

        val resolveUrl = "${session.create}/${session.id}/sources/$srcId/resolve"
        val resolved = resolveSource(resolveUrl, session.headers, playUrl)
        val launchUrl = resolved.optString("launch_url").ifBlank { return false }

        val finalUrl = try {
            app.get(launchUrl, headers = session.headers, referer = playUrl).url
        } catch (_: Exception) {
            launchUrl
        }
        if (finalUrl.isBlank() || isSelfHost(finalUrl)) return false

        val referer = "https://" + finalUrl.substringAfter("://").substringBefore("/") + "/"

        val ok = try {
            loadExtractor(finalUrl, referer, subtitleCallback) { callback(it) }
        } catch (_: Exception) {
            false
        }
        if (ok) return true

        if (looksLikeMedia(finalUrl)) {
            emitMedia(finalUrl, provider, playUrl, callback)
            return true
        }
        return false
    }

    private suspend fun emitMedia(
        url: String,
        provider: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$name $provider",
                url = url,
                type = if (url.contains(".m3u8") || url.contains(".txt"))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to BROWSER_UA)
            }
        )
    }

    private suspend fun loadLegacyEmbeds(
        doc: Document,
        playUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = (doc.select("a[data-embed]").map { it.attr("data-embed") } +
            doc.select("iframe[src]").map { it.attr("src") })
            .filter { it.isNotBlank() }
            .map { fixUrl(it) }
            .distinct()
        var found = false
        for (link in links) {
            try {
                if (loadExtractor(link, playUrl, subtitleCallback) { callback(it) }) found = true
            } catch (_: Exception) {
                // ignore
            }
        }
        return found
    }

    private suspend fun retryPost(
        url: String,
        headers: Map<String, String>,
        jsonBody: Map<String, String>,
        referer: String,
        maxRetries: Int = 3
    ): JSONObject? {
        repeat(maxRetries) { attempt ->
            try {
                val resp = app.post(url, headers = headers, json = jsonBody, referer = referer)
                if (resp.code == 200 || resp.code == 201) {
                    return try { JSONObject(resp.text) } catch (_: Exception) { null }
                }
            } catch (_: Exception) {
                // retry
            }
            delay(600L * (attempt + 1))
        }
        return null
    }

    /**
     * Resolve one source. A couple of quick retries smooth over transient errors;
     * a persistent rate-limit throws [RateLimited] so the caller can rotate session.
     */
    private suspend fun resolveSource(
        url: String,
        headers: Map<String, String>,
        referer: String
    ): JSONObject {
        var rateLimited = false
        repeat(3) { attempt ->
            val resp = try {
                app.post(url, headers = headers, json = mapOf<String, String>(), referer = referer)
            } catch (_: Exception) {
                null
            }
            if (resp != null) {
                if (resp.code == 200 || resp.code == 201) {
                    val obj = try { JSONObject(resp.text) } catch (_: Exception) { null }
                    if (obj != null && obj.optString("error") != "rate_limited") return obj
                    if (obj != null) rateLimited = true
                } else if (resp.code == 429) {
                    rateLimited = true
                } else {
                    return JSONObject()
                }
            }
            delay(1200L * (attempt + 1))
        }
        if (rateLimited) throw RateLimited()
        return JSONObject()
    }

    private fun playbackHeaders(
        csrf: String,
        referer: String,
        cookies: Map<String, String>
    ): Map<String, String> {
        val base = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "X-Playback-CSRF" to csrf,
            "Origin" to siteRoot,
            "Referer" to referer
        )
        val cookieHeader = cookies.entries
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }
        return if (cookieHeader.isBlank()) base else base + ("Cookie" to cookieHeader)
    }

    private fun toPlayUrl(data: String): String {
        return when {
            data.contains("/play.php") -> data
            data.contains("vid=") -> {
                val vid = Regex("vid=([A-Za-z0-9]+)").find(data)?.groupValues?.get(1)
                if (vid != null) "$siteRoot/play.php?vid=$vid" else data
            }
            else -> data.replace("/watch.php", "/play.php")
        }
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
        return host.endsWith("animezid.org") ||
            host.contains("animezid.cam") ||
            host.contains("animezid.com") ||
            host.contains("animezid.net")
    }

    private fun looksLikeMedia(url: String): Boolean {
        val clean = url.substringBefore('?')
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") ||
            clean.endsWith(".mkv") || clean.endsWith(".webm") || clean.endsWith(".txt")
    }

    private suspend fun getPage(url: String, referer: String? = null): Document =
        app.get(url, headers = pageHeaders, referer = referer ?: mainUrl).document

    private fun typeFromText(
        text: String,
        tags: List<String>? = null,
        default: TvType
    ): TvType {
        val combined = text.lowercase() + " " + (tags?.joinToString(" ")?.lowercase() ?: "")
        return when {
            combined.contains("كرتون") && combined.contains("فيلم") -> TvType.AnimeMovie
            combined.contains("كرتون") || combined.contains("cartoon") -> TvType.Cartoon
            combined.contains("انيميشن") && combined.contains("فيلم") -> TvType.AnimeMovie
            combined.contains("انيميشن") -> TvType.Cartoon
            combined.contains("فيلم") &&
                (combined.contains("انمي") || combined.contains("انيميشن")) -> TvType.AnimeMovie
            combined.contains("فيلم") -> TvType.Movie
            combined.contains("مسلسل") -> TvType.TvSeries
            combined.contains("مانغا") || combined.contains("اوفا") -> TvType.OVA
            else -> default
        }
    }

    private fun cleanupSeriesTitle(raw: String): String {
        var t = raw.trim()
        t = t.removePrefix("انمي").trim()
        t = t.removePrefix("أنمي").trim()
        t = t.removePrefix("مسلسل كرتون").trim()
        t = t.removePrefix("مسلسل").trim()
        t = t.removePrefix("كرتون").trim()
        t = t.replace(Regex("""\s*\|\s*.*$"""), "").trim()
        t = t.replace(Regex("""\s*(الموسم|المواسم)\s+\S+.*$"""), "").trim()
        t = t.replace(Regex("""\s*الحلقة\s+\S+.*$"""), "").trim()
        t = t.replace(Regex("""\s*(مترجم(?:ة)?|مدبلج(?:ة)?)\s*$"""), "").trim()
        return t.ifBlank { raw.trim() }
    }

    private fun cleanupMovieTitle(raw: String): String {
        var t = raw.trim()
        t = t.removePrefix("مشاهدة وتحميل").trim()
        t = t.removePrefix("مشاهدة").trim()
        t = t.replace(Regex("""\s*اونلاين.*$"""), "").trim()
        t = t.replace(Regex("""\s*مترجم(?:ة)?\s*$"""), "").trim()
        t = t.replace(Regex("""\s*مدبلج(?:ة)?\s*$"""), "").trim()
        return t
    }

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> siteRoot + url
            else -> "$siteRoot/$url"
        }
    }
}
