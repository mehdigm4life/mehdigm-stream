package com.animezid

import android.util.Base64
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
import java.security.MessageDigest

class Animezid : MainAPI() {
    override var mainUrl = "https://animezid.org/"
    // Keep the canonical trailing slash without creating double slashes in generated paths.
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
        private const val PB_ROTATION_SOURCE_ROUTE = 1
        private const val PB_ROTATION_ENCRYPTED_RESPONSE = 2
        private const val PB_ROTATION_SALTED_PROOF = 4
        private const val PB_ROTATION_WARMUP = 8
        private const val PB_ROTATION_SCRIPT_COOKIE = 16
        private const val PB_DEFAULT_PROOF_SALT = "a7c3e91f5d20b846"
        private const val HEX = "0123456789abcdef"
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

    private data class PlaybackProof(
        val csrf: String,
        val token: String,
        val rotation: Int,
        val salt: String
    ) {
        val sessionToken: String get() = csrf.ifBlank { token }
    }

    private data class PlaybackSession(
        val create: String,
        val id: String,
        val headers: MutableMap<String, String>,
        val embedded: List<JSONObject>,
        val proof: PlaybackProof
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
        var sources = first.embedded
        var found = false
        var index = 0

        while (index < first.embedded.size) {
            if (index > 0 && index % batchSize == 0) {
                session = createPlaybackSession(playUrl) ?: return found
                sources = session.embedded
            }
            val end = minOf(index + batchSize, first.embedded.size)
            for (i in index until end) {
                val src = sources.getOrNull(i) ?: continue
                val ok = try {
                    resolveAndEmit(src, session, playUrl, subtitleCallback, callback)
                } catch (_: RateLimited) {
                    val rotated = createPlaybackSession(playUrl)
                    if (rotated != null) {
                        session = rotated
                        sources = rotated.embedded
                        val rotatedSource = sources.getOrNull(i)
                            ?: sources.firstOrNull { it.optString("provider") == src.optString("provider") }
                        if (rotatedSource != null) {
                            try {
                                resolveAndEmit(rotatedSource, rotated, playUrl, subtitleCallback, callback)
                            } catch (_: Exception) {
                                false
                            }
                        } else {
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
     * non-session Cookie value requests a fresh PHP session; its returned PHPSESSID is
     * then carried through session creation and every source resolve.
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
        val csrf = doc.selectFirst("[data-playback-csrf]")?.attr("data-playback-csrf").orEmpty()
        val sealedA = doc.selectFirst("[data-pb-a]")?.attr("data-pb-a").orEmpty()
        val sealedB = doc.selectFirst("[data-pb-b]")?.attr("data-pb-b").orEmpty()
        val token = unsealPlaybackToken(sealedA, sealedB).orEmpty()
        val rotation = doc.selectFirst("[data-pb-v]")?.attr("data-pb-v")?.toIntOrNull() ?: 0
        if (csrf.isBlank() && token.isBlank()) return null

        val rawCreate = doc.selectFirst("[data-playback-create-url]")?.attr("data-playback-create-url") ?: return null
        val create = fixUrl(rawCreate).ifBlank { return null }
        val vid = doc.selectFirst("[data-video-uniq]")?.attr("data-video-uniq")
            ?.ifBlank { null }
            ?: Regex("vid=([A-Za-z0-9]+)").find(playUrl)?.groupValues?.get(1)
            ?: return null

        var proof = PlaybackProof(csrf, token, rotation, PB_DEFAULT_PROOF_SALT)
        if (csrf.isBlank() && (rotation and PB_ROTATION_SALTED_PROOF) != 0) {
            proof = proof.copy(salt = readPlaybackProofSalt(doc, playUrl) ?: PB_DEFAULT_PROOF_SALT)
        }
        val headers = playbackHeaders(playUrl, resp.cookies)

        var extraHeaders: Map<String, String> = emptyMap()
        if ((rotation and PB_ROTATION_WARMUP) != 0) {
            val warmup = retryPost(
                create,
                headers,
                proof,
                mapOf<String, Any?>("content_id" to vid, "warm" to 1),
                playUrl,
                maxRetries = 1
            ) ?: return null
            val warmToken = warmup.optString("w").ifBlank { return null }
            delay(1200L)
            extraHeaders = mapOf("X-Playback-Warm" to warmToken)
        }

        val session = retryPost(
            create,
            headers,
            proof,
            mapOf("content_id" to vid),
            playUrl,
            extraHeaders = extraHeaders
        ) ?: return null
        val sid = session.optString("session_id").ifBlank { return null }
        val arr = session.optJSONArray("sources") ?: return null
        val embedded = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("type") == "embedded_web" }
        return PlaybackSession(create, sid, headers, embedded, proof)
    }

    private suspend fun resolveAndEmit(
        src: JSONObject,
        session: PlaybackSession,
        playUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val provider = src.optString("provider")
        val resolveUrl = if ((session.proof.rotation and PB_ROTATION_SOURCE_ROUTE) != 0) {
            val route = src.optString("r").trim()
            if (!route.startsWith("/web-playback/sessions/")) return false
            fixUrl(route)
        } else {
            val srcId = src.optString("id").ifBlank { return false }
            "${session.create.trimEnd('/')}/${session.id}/sources/$srcId/resolve"
        }
        val resolved = resolveSource(resolveUrl, session.headers, session.proof, playUrl)
        val launchUrl = fixUrl(resolved.optString("launch_url").ifBlank { return false })

        // The launch URL is opened in the page's iframe, not as an API fetch.
        val launchHeaders = pageHeaders.toMutableMap().apply {
            this["Referer"] = playUrl
            this["Sec-Fetch-Dest"] = "iframe"
            this["Sec-Fetch-Mode"] = "navigate"
            this["Sec-Fetch-Site"] = "same-origin"
            session.headers["Cookie"]?.let { this["Cookie"] = it }
        }
        val finalUrl = try {
            val response = app.get(launchUrl, headers = launchHeaders, referer = playUrl)
            if (isSelfHost(response.url)) {
                mergeResponseCookies(session.headers, response.cookies)
            }
            response.url
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
        headers: MutableMap<String, String>,
        proof: PlaybackProof,
        jsonBody: Map<String, Any?>,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
        maxRetries: Int = 3
    ): JSONObject? {
        repeat(maxRetries) { attempt ->
            try {
                val requestHeaders = playbackRequestHeaders(proof, headers, url, extraHeaders)
                val resp = app.post(url, headers = requestHeaders, json = jsonBody, referer = referer)
                mergeResponseCookies(headers, resp.cookies)
                if (resp.code == 200 || resp.code == 201) {
                    return decodePlaybackResponse(resp.text, proof)
                }
            } catch (_: Exception) {
                // Retry brief transport failures; the API's proof is deterministic per route.
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
        headers: MutableMap<String, String>,
        proof: PlaybackProof,
        referer: String
    ): JSONObject {
        var rateLimited = false
        repeat(3) { attempt ->
            val resp = try {
                val requestHeaders = playbackRequestHeaders(proof, headers, url)
                app.post(
                    url,
                    headers = requestHeaders,
                    json = emptyMap<String, Any?>(),
                    referer = referer
                )
            } catch (_: Exception) {
                null
            }
            if (resp != null) {
                mergeResponseCookies(headers, resp.cookies)
                if (resp.code == 200 || resp.code == 201) {
                    val obj = decodePlaybackResponse(resp.text, proof)
                    if (obj != null && obj.optString("error") != "rate_limited") return obj
                    if (obj?.optString("error") == "rate_limited") rateLimited = true
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

    // Base browser/API headers; the per-request proof and rotating cookie are added below.
    private fun playbackHeaders(
        referer: String,
        cookies: Map<String, String>
    ): MutableMap<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Content-Type" to "application/json",
            "Origin" to siteRoot,
            "Referer" to referer,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )
        mergeResponseCookies(headers, cookies)
        return headers
    }

    /** Build the same route-bound authentication headers that visitor.js sends. */
    private fun playbackRequestHeaders(
        proof: PlaybackProof,
        baseHeaders: MutableMap<String, String>,
        url: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): MutableMap<String, String> {
        val headers = baseHeaders.toMutableMap()
        headers.remove("X-Playback-CSRF")
        headers.remove("X-Playback-Proof")
        if (proof.csrf.isNotBlank()) {
            headers["X-Playback-CSRF"] = proof.csrf
        } else {
            val saltPrefix = if ((proof.rotation and PB_ROTATION_SALTED_PROOF) != 0) {
                "${proof.salt}|"
            } else {
                ""
            }
            headers["X-Playback-Proof"] = sha256Hex("$saltPrefix${proof.token}|${playbackPath(url)}")
        }

        if ((proof.rotation and PB_ROTATION_SCRIPT_COOKIE) != 0) {
            val playerCookie = sha256Hex("pc|${proof.sessionToken}").take(32)
            mergeResponseCookies(headers, mapOf("az_pc" to playerCookie))
        }
        extraHeaders.forEach { (key, value) -> headers[key] = value }
        return headers
    }

    private fun playbackPath(url: String): String {
        return try {
            java.net.URI(url).rawPath?.takeIf { it.isNotBlank() } ?: "/"
        } catch (_: Exception) {
            url.substringAfter("://", url).substringAfter('/', "/").substringBefore('?')
                .ifBlank { "/" }
        }
    }

    /** Decode the optional block-XOR response wrapper used by rotation card 2. */
    private fun decodePlaybackResponse(text: String, proof: PlaybackProof): JSONObject? {
        val payload = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if ((proof.rotation and PB_ROTATION_ENCRYPTED_RESPONSE) == 0) return payload

        val encoded = payload.optString("d")
        val nonce = payload.optString("n")
        if (encoded.isBlank() || nonce.isBlank()) return payload

        return try {
            val cipher = Base64.decode(encoded, Base64.DEFAULT)
            val plain = ByteArray(cipher.size)
            var block = 0
            while (block * 32 < cipher.size) {
                val stream = sha256Bytes("${proof.sessionToken}|enc|$nonce|$block")
                val start = block * 32
                val end = minOf(start + 32, cipher.size)
                for (index in start until end) {
                    plain[index] = (cipher[index].toInt() xor stream[index - start].toInt()).toByte()
                }
                block++
            }
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Bytes(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(value: String): String = sha256Bytes(value).toHex()

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    private fun unsealPlaybackToken(sealed: String, mask: String): String? {
        val sealedBytes = hexToBytes(sealed) ?: return null
        val maskBytes = hexToBytes(mask) ?: return null
        if (sealedBytes.size != 32 || maskBytes.size != 32) return null
        return ByteArray(sealedBytes.size) { index ->
            (sealedBytes[index].toInt() xor maskBytes[index].toInt()).toByte()
        }.toHex()
    }

    private fun hexToBytes(value: String): ByteArray? {
        val text = value.trim()
        if (text.isEmpty() || text.length % 2 != 0) return null
        val result = ByteArray(text.length / 2)
        for (index in result.indices) {
            val high = Character.digit(text[index * 2], 16)
            val low = Character.digit(text[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }

    private suspend fun readPlaybackProofSalt(doc: Document, playUrl: String): String? {
        val scriptUrl = doc.select("script[src]")
            .map { it.attr("src") }
            .firstOrNull { it.contains("visitor.js", ignoreCase = true) }
            ?.let { fixUrl(it) }
            ?: return null
        return try {
            val response = app.get(scriptUrl, headers = pageHeaders, referer = playUrl)
            if (!response.isSuccessful) return null
            Regex("""playbackProofSalt\s*=\s*['"]([^'"]+)['"]""")
                .find(response.text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeResponseCookies(headers: MutableMap<String, String>, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val merged = linkedMapOf<String, String>()
        headers["Cookie"]?.split(';')?.forEach { cookie ->
            val separator = cookie.indexOf('=')
            if (separator > 0) {
                val key = cookie.substring(0, separator).trim()
                val value = cookie.substring(separator + 1).trim()
                if (key.isNotBlank() && value.isNotBlank()) merged[key] = value
            }
        }
        cookies.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) merged[key] = value
        }
        if (merged.isNotEmpty()) {
            headers["Cookie"] = merged.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        }
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
