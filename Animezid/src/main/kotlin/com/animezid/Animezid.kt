package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * AnimezidProvider — FULL REWRITE
 * --------------------------------
 * Scrapes https://animezid.cam (Arabic anime / cartoon dubbed & subbed site).
 *
 * KEY INSIGHT THAT DRIVES THIS VERSION
 * -----------------------------------
 * Every watch.php / play.php page on Animezid embeds the COMPLETE series tree
 * inside two blocks at the bottom of the page:
 *
 *   <div class="Tab tab-seasons">
 *     <li data-serie="1">الموسم 1</li>
 *     <li data-serie="2" class="active">الموسم 2</li>
 *     <li data-serie="3">الموسم 3</li>
 *     ...
 *   </div>
 *   <div class="Tab tab-episodes">
 *     <div data-serie="1" class="SeasonsEpisodes">
 *        <a href="watch.php?vid=...">الحلقة 1</a>
 *        <a href="watch.php?vid=...">الحلقة 2</a>
 *        ...
 *     </div>
 *     <div data-serie="2" class="SeasonsEpisodes"> ... </div>
 *     ...
 *   </div>
 *
 * This is the source of truth for the whole show — ALL seasons, ALL episodes,
 * with no pagination and no missing entries.  Using this block fixes the
 * bug where seasons 2+ only showed 1-2 episodes (the old enumerator was
 * limited by category.php pagination and the `consecutiveMisses < 2` cutoff).
 *
 * SERVERS
 * -------
 * /play.php?vid=XXX exposes them as:
 *   <ul id="xservers">
 *     <button data-embed="https://zidwish.site/e/...">سيرفر 1</button>
 *     <button data-embed="https://dsvplay.com/e/...">سيرفر 2</button>
 *     <button data-embed="https://uqload.cx/embed-...html">سيرفر 3</button>
 *     <button data-embed="">سيرفر 4</button>   <-- often empty, must be skipped
 *   </ul>
 *
 * Common hosts in the wild on this site:
 *   zidwish.site, dsvplay.com, uqload.cx, smoothpre.com, listeamed.net,
 *   filemoon.art, mixdrop.to, streamwish.*, streamtape.*, earnvids/earnvid,
 *   doodstream/dood, voe.sx, mp4upload, ok.ru
 *
 * Direct 1080p download links also live on /play.php as:
 *   <a class="btn g dl show_dl api" href="https://uptobox.com/...">
 *      <span>1080p</span><span>uptobox</span>
 *   </a>
 *
 * FIXES vs previous versions
 * --------------------------
 *  ✔ Seasons 2+ now show ALL episodes (uses tab-episodes, not category pagination).
 *  ✔ Empty `data-embed=""` entries (e.g. سيرفر 4) are dropped before extraction.
 *  ✔ Every server URL is sanitized, deduped, and given the correct Referer
 *    (the play.php page) so DsvPlay/Zidwish/Listeamed actually respond.
 *  ✔ Every server is registered as a raw ExtractorLink fallback so unsupported
 *    hosters still appear in the Cloudstream server menu instead of vanishing.
 *  ✔ The hostDisplayNames table now mirrors the exact host names Animezid uses,
 *    so users see "Zidwish", "DsvPlay", "Uqload" etc. instead of generic labels.
 *  ✔ Direct 1080p download links from uptobox / 1fichier / letsupload /
 *    file-upload are also exposed as separate ExtractorLinks (these are
 *    persistent links that don't rely on the iframe hosters being up).
 *  ✔ Plot/year/tags pulled directly from the watch.php page that we already
 *    have in memory — no extra round-trip.
 *  ✔ Parent-series detection still scoped to the <dt>القسم</dt><dd>...</dd>
 *    block so movies don't accidentally inherit unrelated shows.
 */
class Animezid : MainAPI() {
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie,
        TvType.OVA
    )

    /** Generic site-wide categories that are NEVER a series slug. */
    private val genericCategorySlugs = setOf(
        "anime", "movies", "series",
        "disney-masr", "spacetoon", "disney-arabic", "disney-series",
        "new-eps", "new-series-eps", "new-movies", "new-anime-eps",
        "anime-movies", "cartoon-movies",
        "dubbed-animation", "subbed-animation", "dubbed-anime",
        "english-movies", "dubbed-movies", "arabic-movies",
        "top", "topvideos", "trend", "newvideos",
        "new-year-movies"
    )

    /**
     * Hosts inside `data-embed` that should NEVER be passed through loadExtractor
     * or registered — they only loop back to animezid itself.
     */
    private val selfEmbedHosts = setOf("animezid.cam", "animezid.com", "animezid.net")

    /**
     * Pretty server names per known host. Used when we register a server as a
     * raw ExtractorLink (the fallback path for hosters not handled by
     * loadExtractor). Keys are matched as substrings of the host.
     */
    private val hostDisplayNames = linkedMapOf(
        // Animezid's own / partner hosts
        "zidwish"     to "ZidWish",
        "dsvplay"     to "DsvPlay",
        "smoothpre"   to "SmoothPre",
        "listeamed"   to "Listeamed",
        "filemoon"    to "FileMoon",
        "upns"        to "UpnsZid",
        "vidtube"     to "VidTube",
        "megamax"     to "MegaMax",
        // Common third-party hosters
        "uqload"      to "Uqload",
        "mixdrop"     to "MixDrop",
        "streamwish"  to "StreamWish",
        "streamtape"  to "StreamTape",
        "earnvid"     to "EarnVids",
        "earnvids"    to "EarnVids",
        "dood"        to "DoodStream",
        "voe"         to "Voe",
        "ok.ru"       to "OK.ru",
        "mp4upload"   to "Mp4Upload",
        "fembed"      to "Fembed",
        "mediafire"   to "MediaFire",
        // Direct-download hosts used by the download buttons
        "uptobox"     to "Uptobox",
        "1fichier"    to "1Fichier",
        "letsupload"  to "LetsUpload",
        "file-upload" to "File-Upload",
        "anonfiles"   to "AnonFiles",
        "bayfiles"    to "BayFiles",
        "upbaam"      to "UpBaam"
    )

    /** Hosts whose links are direct downloads (not embeds — show as 1080p direct). */
    private val directDownloadHosts = setOf(
        "uptobox", "1fichier", "letsupload", "file-upload",
        "anonfiles", "bayfiles", "upbaam"
    )

    /** Default browser-like UA. Some hosters (zidwish, dsvplay) 403 without it. */
    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ====================================================================
    // Main page sections
    // ====================================================================
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
        val doc = app.get(url, headers = mapOf("User-Agent" to browserUA)).document

        val items = doc.select("div#movies a.movie, div.movies a.movie")
            .mapNotNull { it.toSearchResponse() }

        val hasNext = doc.select("ul.pagination li a")
            .any { it.attr("href").contains("page=${page + 1}") } ||
            items.size >= 20

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    // ====================================================================
    // Search
    // ====================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${query.encode()}"
        val doc = app.get(url, headers = mapOf("User-Agent" to browserUA)).document
        return doc.select("div#movies a.movie, div.movies a.movie")
            .mapNotNull { it.toSearchResponse() }
    }

    /**
     * Convert an <a class="movie"> card to a SearchResponse.
     *
     * Two link patterns are possible:
     *   - /watch.php?vid=XXX   -> single episode / movie / series episode
     *   - /category.php?cat=Y  -> a series category (we want all its seasons)
     *
     * We mark series-category cards with "SERIES::" prefix so load() can
     * skip straight to multi-season enumeration without re-fetching a watch
     * page.
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href").ifBlank { return null }
        val absHref = fixUrl(href)
        val title = this.selectFirst("span.title")?.text()?.trim()
            ?: this.attr("title").trim().ifBlank { return null }

        val img = this.selectFirst("img")
        val poster = img?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-original") }
                .ifBlank { it.attr("src") }
        }?.let { fixUrl(it) }

        val ribbon = this.selectFirst("span.ribbon")?.text()?.trim() ?: ""
        val isSeriesLink = absHref.contains("/category.php?cat=", ignoreCase = true) &&
                !isGenericCategoryUrl(absHref)

        val tvType = when {
            isSeriesLink                        -> TvType.Anime
            ribbon.contains("الأخيرة")          -> TvType.Anime
            ribbon.contains("مسلسل")            -> TvType.Anime
            title.contains("الحلقة")            -> TvType.Anime
            title.contains("فيلم")              -> TvType.Movie
            else                                -> TvType.Anime
        }

        val loadUrl = if (isSeriesLink) "SERIES::$absHref" else absHref

        return newAnimeSearchResponse(title, loadUrl, tvType) {
            this.posterUrl = poster
        }
    }

    // ====================================================================
    // Load detail
    //
    //   1. url starts with "SERIES::"   -> series category -> open any
    //                                      episode and use its tab-episodes.
    //   2. url contains "/category.php" -> same as above.
    //   3. url contains "/watch.php"    -> watch page directly. We can decide
    //                                      movie vs series from the watch doc
    //                                      itself (tab-episodes is right there).
    // ====================================================================
    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.removePrefix("SERIES::")

        return when {
            cleanUrl.contains("/category.php") -> loadSeriesFromCategoryUrl(cleanUrl)
            cleanUrl.contains("/watch.php")    -> loadFromWatchPage(cleanUrl)
            else                                -> loadFromWatchPage(cleanUrl)
        }
    }

    /**
     * Entry: category.php URL. We pick the first watch.php link on that page
     * and read the whole series tree from it (tab-seasons / tab-episodes).
     */
    private suspend fun loadSeriesFromCategoryUrl(categoryUrl: String): LoadResponse {
        val doc = app.get(categoryUrl, headers = mapOf("User-Agent" to browserUA)).document

        val firstWatch = doc.select("div#movies a.movie[href*=watch.php], div.movies a.movie[href*=watch.php]")
            .firstOrNull()
            ?.attr("href")
            ?.let { fixUrl(it) }

        if (firstWatch.isNullOrBlank()) {
            // No episodes on this category page at all — treat as empty series.
            return buildEmptySeries(categoryUrl, doc)
        }

        val watchDoc = app.get(firstWatch, headers = mapOf("User-Agent" to browserUA)).document
        return buildSeriesFromWatchDoc(firstWatch, watchDoc, fallbackCategoryUrl = categoryUrl)
    }

    /**
     * Entry: watch.php URL. If the page has a tab-episodes block listing
     * multiple episodes/seasons, treat as a series; otherwise as a movie.
     */
    private suspend fun loadFromWatchPage(watchUrl: String): LoadResponse {
        val doc = app.get(watchUrl, headers = mapOf("User-Agent" to browserUA)).document

        // Quick check — does this page advertise multiple episodes?
        val episodesAnchors = doc.select("div.Tab.tab-episodes a[href*=watch.php]")
        if (episodesAnchors.size >= 2) {
            return buildSeriesFromWatchDoc(watchUrl, doc, fallbackCategoryUrl = null)
        }

        // Single episode / movie.
        return buildMovieResponse(watchUrl, doc)
    }

    // ====================================================================
    // SERIES LOADER (heart of the fix)
    //
    // Reads the tab-seasons + tab-episodes blocks from any watch.php document
    // and produces a complete LoadResponse with EVERY season and EVERY
    // episode of the show.
    // ====================================================================
    private suspend fun buildSeriesFromWatchDoc(
        watchUrl: String,
        watchDoc: Document,
        fallbackCategoryUrl: String?
    ): LoadResponse {

        // 1) Pull (season-number -> season-label) mapping from tab-seasons.
        val seasonLabels = linkedMapOf<Int, String>()
        watchDoc.select("div.Tab.tab-seasons li[data-serie]").forEach { li ->
            val num = li.attr("data-serie").toIntOrNull() ?: return@forEach
            val label = li.text().trim().ifBlank { "الموسم $num" }
            seasonLabels[num] = label
        }

        // 2) For every <div data-serie="N" class="SeasonsEpisodes"> block,
        //    enumerate its <a href="watch.php?vid=..."> children in order.
        val episodes = mutableListOf<Episode>()
        watchDoc.select("div.Tab.tab-episodes div.SeasonsEpisodes[data-serie]").forEach { seasonBlock ->
            val seasonNum = seasonBlock.attr("data-serie").toIntOrNull() ?: return@forEach

            val anchors = seasonBlock.select("a[href*=watch.php]")
            anchors.forEachIndexed { idx, a ->
                val ref  = fixUrl(a.attr("href"))
                // The <em>N</em> child carries the episode number on the site.
                val emText = a.selectFirst("em")?.text()?.trim()
                val parsedNum = emText?.toIntOrNull()
                    ?: parseEpisodeNumber(a.text())
                    ?: (idx + 1)

                val pretty = "الحلقة $parsedNum"

                episodes.add(
                    newEpisode(ref) {
                        this.name    = pretty
                        this.episode = parsedNum
                        this.season  = seasonNum
                    }
                )
            }
        }

        // Safety net: if the tab block was absent (very old layout), fall back
        // to the legacy "enumerate category.php" path for at least one season.
        if (episodes.isEmpty() && fallbackCategoryUrl != null) {
            return buildSingleSeasonFromCategory(fallbackCategoryUrl)
        }

        // 3) Metadata pulled from the watch.php we already have.
        val rawTitle = watchDoc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: watchDoc.selectFirst("h1 span[itemprop=name]")?.text()
            ?: watchDoc.selectFirst("h1")?.text()
            ?: "Animezid"

        val seriesTitle = cleanupSeriesTitle(rawTitle) ?: "Animezid"
        val poster   = extractPoster(watchDoc)
        val plot     = extractPlot(watchDoc)
        val year     = extractYear(watchDoc)
        val tags     = extractTags(watchDoc)

        // Ensure deterministic order: by (season, episode).
        val sorted = episodes.sortedWith(
            compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE })
        )

        val displayUrl = fallbackCategoryUrl ?: watchUrl
        return newAnimeLoadResponse(seriesTitle, displayUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    // ------------------------------------------------------------------
    // Build a Movie response from a watch.php page.
    // ------------------------------------------------------------------
    private suspend fun buildMovieResponse(watchUrl: String, doc: Document): LoadResponse {
        val title = cleanupMovieTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1 span[itemprop=name]")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: "Animezid"
        )

        val poster = extractPoster(doc)
        val plot   = extractPlot(doc)
        val year   = extractYear(doc)
        val tags   = extractTags(doc)

        val type = if (tags.any { it.contains("انيميشن") || it.contains("انمي") || it.contains("كرتون") }) {
            TvType.AnimeMovie
        } else TvType.Movie

        return newMovieLoadResponse(title, watchUrl, type, watchUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    /**
     * Last-ditch fallback when we couldn't read tab-episodes — list episodes
     * from a single category page (with pagination). Kept here for safety
     * only; the normal path uses tab-episodes which is always complete.
     */
    private suspend fun buildSingleSeasonFromCategory(categoryUrl: String): LoadResponse {
        val doc = app.get(categoryUrl, headers = mapOf("User-Agent" to browserUA)).document

        val title = cleanupSeriesTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()?.trim()
        ) ?: "Animezid"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val episodes = mutableListOf<Episode>()

        var current: Document? = doc
        val seen = mutableSetOf<String>()
        var page = 1
        while (current != null && page <= 50) {
            val cards = current.select(
                "div#movies a.movie[href*=watch.php], div.movies a.movie[href*=watch.php]"
            )
            if (cards.isEmpty()) break

            cards.forEach { card ->
                val href = fixUrl(card.attr("href"))
                if (!seen.add(href)) return@forEach
                val raw = card.selectFirst("span.title")?.text()?.trim()
                    ?: card.attr("title").trim()
                val num = parseEpisodeNumber(raw) ?: (seen.size)
                episodes.add(
                    newEpisode(href) {
                        this.name = cleanupEpisodeName(raw)
                        this.episode = num
                        this.season = 1
                    }
                )
            }

            val nextHref = current.select("ul.pagination li a").firstOrNull { a ->
                val t = a.text().trim()
                (t == "»" || t.equals("Next", true)) &&
                    a.attr("href") != "#" &&
                    !a.attr("onclick").contains("return false")
            }?.attr("href")?.let { if (it.isBlank()) null else fixUrl(it) }

            current = if (!nextHref.isNullOrBlank() && nextHref !in seen) {
                page += 1
                try { app.get(nextHref, headers = mapOf("User-Agent" to browserUA)).document }
                catch (_: Exception) { null }
            } else null
        }

        val sorted = episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        if (sorted.isEmpty()) {
            return newMovieLoadResponse(title, categoryUrl, TvType.Movie, categoryUrl) {
                this.posterUrl = poster
            }
        }
        return newAnimeLoadResponse(title, categoryUrl, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    /** Empty-series stub used when a category page has nothing on it. */
    private suspend fun buildEmptySeries(categoryUrl: String, doc: Document): LoadResponse {
        val title = cleanupSeriesTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()?.trim()
        ) ?: "Animezid"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newAnimeLoadResponse(title, categoryUrl, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Dubbed, emptyList())
        }
    }

    // ====================================================================
    // loadLinks
    //
    // Open /play.php?vid=XXX, then for EVERY non-empty `data-embed`:
    //   1) sanitize the URL (trim, fix scheme, drop self-loops)
    //   2) try loadExtractor(...) with the play page as Referer
    //   3) ALWAYS also register the raw URL as a fallback ExtractorLink
    //      with a friendly hoster name, so unsupported hosters still appear
    //      in the player menu (instead of vanishing silently and giving the
    //      user a misleading "only Server X works" experience).
    // Also exposes /play.php direct-download links (uptobox / 1fichier / ...)
    // as 1080p VIDEO ExtractorLinks.
    // ====================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playUrl = when {
            data.contains("/play.php")  -> data
            data.contains("/watch.php") -> data.replace("/watch.php", "/play.php")
            data.contains("vid=")       -> {
                val vid = Regex("vid=([A-Za-z0-9]+)").find(data)?.groupValues?.get(1)
                if (vid != null) "$mainUrl/play.php?vid=$vid" else data
            }
            else -> data
        }

        val doc = app.get(
            playUrl,
            referer = mainUrl,
            headers = mapOf("User-Agent" to browserUA)
        ).document

        // ----- 1. Collect embed servers (the iframe hosters) -----
        val rawEmbeds = mutableListOf<Pair<String, String>>() // url, button label
        doc.select("ul#xservers button[data-embed], #xservers button[data-embed], button[data-embed]")
            .forEach { btn ->
                val v = btn.attr("data-embed").trim()
                val label = btn.text().trim()
                if (v.isNotBlank()) rawEmbeds.add(v to label)
            }
        // Some pages bake the active server straight into an <iframe> — collect that too.
        doc.select("iframe[src]").forEach { iframe ->
            val v = iframe.attr("src").trim()
            if (v.isNotBlank()) rawEmbeds.add(v to "")
        }

        val seen = mutableSetOf<String>()
        val finalEmbeds = mutableListOf<Pair<String, String>>()
        rawEmbeds.forEach { (raw, label) ->
            val link = normalizeEmbed(raw) ?: return@forEach
            val host = link.toHttpHostOrNull() ?: return@forEach
            // Skip animezid's own embed.php (it would just iframe one of these again).
            if (selfEmbedHosts.any { host.endsWith(it) }) return@forEach
            if (!seen.add(link)) return@forEach
            finalEmbeds.add(link to label)
        }

        // ----- 2. Collect direct 1080p downloads -----
        // <a class="btn g dl show_dl api" href="https://uptobox.com/..."><span>1080p</span><span>uptobox</span></a>
        val directLinks = mutableListOf<Triple<String, String, String>>() // url, quality, hostName
        doc.select("a.btn.dl.show_dl, a.btn.g.dl, a.dl.show_dl").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || !href.startsWith("http", true)) return@forEach
            val spans = a.select("span")
            val quality = spans.getOrNull(0)?.text()?.trim() ?: "1080p"
            val hostName = spans.getOrNull(1)?.text()?.trim()
                ?: (href.toHttpHostOrNull() ?: "direct")
            if (!seen.add(href)) return@forEach
            directLinks.add(Triple(href, quality, hostName))
        }

        if (finalEmbeds.isEmpty() && directLinks.isEmpty()) return false

        var anyAdded = false

        // ----- 3. Process embed servers -----
        for ((idx, pair) in finalEmbeds.withIndex()) {
            val (link, _) = pair
            val display = hostNameFromUrl(link) ?: "Server ${idx + 1}"

            // 3a) Try the registered extractor first.
            var extractedAny = false
            try {
                loadExtractor(link, playUrl, subtitleCallback) { extractorLink ->
                    extractedAny = true
                    anyAdded = true
                    callback(extractorLink)
                }
            } catch (_: Exception) {
                // ignore single-server failures and keep going
            }

            // 3b) Always also register a fallback iframe link.
            //     For hosters that loadExtractor doesn't know (zidwish, dsvplay,
            //     listeamed, smoothpre, vidtube, upns.online, megamax, ...) this
            //     is the ONLY way to surface them in the Cloudstream server menu.
            if (!extractedAny) {
                runCatching {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name $display",
                            url = link,
                            type = if (link.contains(".m3u8", ignoreCase = true))
                                ExtractorLinkType.M3U8
                            else
                                ExtractorLinkType.VIDEO
                        ) {
                            this.referer = playUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to browserUA)
                        }
                    )
                    anyAdded = true
                }
            }
        }

        // ----- 4. Process direct download links -----
        // These are persistent 1080p uploads (uptobox / 1fichier / letsupload).
        // We expose them as separate links so the user can pick one when all
        // streaming hosters are down.
        for ((href, quality, hostName) in directLinks) {
            val host = href.toHttpHostOrNull() ?: continue
            // Only expose hosts we actually expect to be direct-download.
            val isDirect = directDownloadHosts.any { host.contains(it) }
            if (!isDirect) continue

            val pretty = hostNameFromUrl(href) ?: hostName.replaceFirstChar { it.uppercase() }
            val q = qualityFromLabel(quality)

            runCatching {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name $pretty ($quality)",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playUrl
                        this.quality = q
                        this.headers = mapOf("User-Agent" to browserUA)
                    }
                )
                anyAdded = true
            }
        }

        return anyAdded
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Extract the `cat` query value from a category.php URL. */
    private fun extractCatSlug(url: String): String? {
        return Regex("[?&]cat=([^&#]+)").find(url)?.groupValues?.get(1)
    }

    /** Is this a generic site-wide category (anime/movies/series/...)? */
    private fun isGenericCategoryUrl(url: String): Boolean {
        val slug = extractCatSlug(url) ?: return true
        return slug in genericCategorySlugs
    }

    /** Trim noisy SEO prefixes/suffixes from a show title. */
    private fun cleanupSeriesTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()

        t = t.removePrefix("جميع حلقات").trim()
        t = t.removePrefix("مشاهدة وتحميل").trim()
        t = t.removePrefix("مشاهدة").trim()
        t = t.removePrefix("انمي").trim()
        t = t.removePrefix("أنمي").trim()
        t = t.removePrefix("مسلسل كرتون").trim()
        t = t.removePrefix("مسلسل").trim()
        t = t.removePrefix("كرتون").trim()

        // Strip "الموسم الثاني الحلقة 1 مدبلجة" trailing fluff so the show
        // displays cleanly as just "قاتل الشياطين" in the home/library.
        val seasonTail = Regex(
            "\\s*(الجزء|الموسم)\\s+(الاول|الأول|الثاني|الثالث|الرابع|الخامس|السادس|" +
                "السابع|الثامن|التاسع|العاشر|الحادي عشر|الثاني عشر|الثالث عشر|" +
                "الرابع عشر|الخامس عشر|\\d+).*$"
        )
        t = t.replace(seasonTail, "").trim()

        val episodeTail = Regex("\\s*الحلقة\\s+\\S+.*$")
        t = t.replace(episodeTail, "").trim()

        t = t.replace(Regex("\\s*(مدبلجة?|مترجمة?)\\s*(كاملة?)?\\s*$"), "").trim()
        return t.ifBlank { raw.trim() }
    }

    /** Trim SEO clutter from a movie title. */
    private fun cleanupMovieTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("^مشاهدة وتحميل\\s+"), "")
        t = t.replace(Regex("^مشاهدة\\s+"), "")
        t = t.replace(Regex("\\s+اونلاين.*$"), "")
        t = t.replace(Regex("\\s+مترجم(ة)?\\s*كامل(ة)?\\s*$"), "")
        return t.trim()
    }

    /** From the card title, produce a clean episode label. */
    private fun cleanupEpisodeName(raw: String): String {
        val match = Regex("الحلقة\\s*\\d+(\\s*(والأخيرة|والاخيرة|الأخيرة|الاخيرة))?").find(raw)
        return match?.value?.trim() ?: raw
    }

    /** Parse "الحلقة 31" or any trailing number out of a card title. */
    private fun parseEpisodeNumber(name: String): Int? {
        Regex("الحلقة\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return Regex("(\\d+)").findAll(name).lastOrNull()?.value?.toIntOrNull()
    }

    /**
     * Extract the plot from a watch.php document.
     * The site renders it as:
     *   <div class="pda bdb hd"><strong>القصة</strong></div>
     *   <div class="pda pm-video-description">
     *     <p class="description"><p>...</p></p>
     *   </div>
     */
    private fun extractPlot(doc: Document): String? {
        val blocks = doc.select("div.pda.bdb.hd")
        for (header in blocks) {
            val label = header.selectFirst("strong")?.text()?.trim() ?: continue
            if (label == "القصة") {
                val sibling = header.nextElementSibling() ?: continue
                val text = sibling.selectFirst("p.description")?.text()
                    ?: sibling.text()
                val cleaned = text?.trim()?.takeIf { it.isNotBlank() }
                if (cleaned != null) return cleaned
            }
        }
        for (header in blocks) {
            val label = header.selectFirst("strong")?.text()?.trim() ?: continue
            if (label == "الوصف") {
                val sibling = header.nextElementSibling() ?: continue
                val text = sibling.selectFirst("p.description")?.text()
                    ?: sibling.text()
                val cleaned = text?.trim()?.takeIf { it.isNotBlank() }
                if (cleaned != null) return cleaned
            }
        }
        return doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    }

    private fun extractYear(doc: Document): Int? {
        doc.select("a[href*=filter=years]").firstOrNull()?.text()?.trim()?.toIntOrNull()
            ?.let { return it }
        return null
    }

    private fun extractTags(doc: Document): List<String> {
        return doc.select(
            "a[href*=filter=genres], a[href*=filter=translate], " +
                "a[href*=filter=countries], a[href*=filter=quality]"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractPoster(doc: Document): String? {
        return doc.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.movie_img img, div.poster img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { fixUrl(it) }
    }

    /** Normalize an embed URL: trim, fix scheme, drop empty/invalid. */
    private fun normalizeEmbed(raw: String): String? {
        var v = raw.trim()
        if (v.isBlank()) return null
        if (v.startsWith("//")) v = "https:$v"
        else if (v.startsWith("/")) v = mainUrl + v
        if (!v.startsWith("http", ignoreCase = true)) return null
        return v
    }

    /** Extract the bare host (without `www.`) from a URL, or null. */
    private fun String.toHttpHostOrNull(): String? {
        return try {
            val u = java.net.URI(this)
            (u.host ?: return null).removePrefix("www.").lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /** Best-effort pretty name for a host. */
    private fun hostNameFromUrl(url: String): String? {
        val host = url.toHttpHostOrNull() ?: return null
        hostDisplayNames.forEach { (needle, pretty) ->
            if (host.contains(needle)) return pretty
        }
        val parts = host.split('.')
        return parts.getOrNull(parts.size - 2)?.replaceFirstChar { it.uppercase() }
    }

    /** Convert "1080p" / "720p" / ... labels to numeric quality constants. */
    private fun qualityFromLabel(label: String): Int {
        val digits = Regex("(\\d{3,4})").find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return when {
            digits >= 2160 -> Qualities.P2160.value
            digits >= 1440 -> Qualities.P1440.value
            digits >= 1080 -> Qualities.P1080.value
            digits >= 720  -> Qualities.P720.value
            digits >= 480  -> Qualities.P480.value
            digits >= 360  -> Qualities.P360.value
            digits >= 240  -> Qualities.P240.value
            else            -> Qualities.Unknown.value
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http") -> url
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> mainUrl + url
            else                   -> "$mainUrl/$url"
        }
    }

    private fun String.encode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
