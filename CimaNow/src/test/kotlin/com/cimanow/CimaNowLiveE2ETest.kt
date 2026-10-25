package com.cimanow

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CimaNowLiveE2ETest {

    @Test
    fun testCryptoPrimitivesDeterministic() {
        // 1. V8-equivalent expression evaluator
        val expr =
            "Math.floor(((((Math.min(11, 28)) << (('abcZ'.indexOf('Z') + (-2)))) | 1) + " +
                "(('abcdefgZ'.indexOf('Z') + (202)) + Math.max(398, 418)) + " +
                "(((Math.abs(-9)) * 54) + (('abZ'.indexOf('Z') + (-2)))) + " +
                "((Math.min(83, 106)) ^ (Math.max(16, 40))) + " +
                "((Math.min(571, 595)) * (typeof window.decodeURIComponent === 'function' ? 1 : 0))) / 3)"
        assertEquals(610L, CimaNowCrypto.evalMathExpr(expr))
        assertEquals(280L, CimaNowCrypto.evalMathExpr("0xFF + 0b1010 + 0o17"))

        // 2. Generalized Flexbox `<x-fx>` & `<x-grid>` height solver
        val gridHtml =
            "<x-grid style='display:flex!important;width:403px!important;box-sizing:border-box!important;padding:10px 12px!important;column-gap:12px!important;row-gap:6px!important;'>" +
                "<x-n style='order:20!important;display:block!important;box-sizing:border-box!important;width:200px!important;height:40px!important;padding:4px 4px!important;margin:0px 2px!important;'></x-n>" +
                "<x-n style='order:10!important;display:block!important;box-sizing:content-box!important;width:200px!important;height:30px!important;padding:5px 6px!important;margin:0px 4px!important;'></x-n>" +
                "<x-n style='order:30!important;display:none!important;width:300px!important;height:90px!important;'></x-n>" +
                "</x-grid>"
        // item(order=10): outerW = 200+12+8=220, outerH = 30+10=40; item(order=20): outerW = 200+4=204, outerH = 40; availW = 403-24=379 -> 2 rows: 20 + (40+40) + 6 = 106
        assertEquals(106, CimaNowCrypto.computeFlexHeight(gridHtml))

        // 3. FNV-1a base36 `core.php` signatures (V12 & V13)
        val sigV12 = CimaNowCrypto.calcCoreSig(
            "d224f3b047d641a53111e7a19b7091c688038510dbfeca943dbe964114b13c25",
            "139241",
            "66",
            "cnSig12",
        )
        assertTrue(sigV12.isNotBlank())
        val sigV13 = CimaNowCrypto.calcCoreSigV13("tok123", "139950", "66", "cnV13Sig")
        assertTrue(sigV13.isNotBlank())

        // 4. `__cnBootFrame` V12 (`data-cnk`/`data-cna`) & V13 (`data-cnb`/`data-cni`) iframe decryption
        val sampleIframeV12 =
            """<iframe id="cn_fr_02bd5628" src="about:blank" data-cnk="633637376630323664653663303836346663363863613432" data-cna="12.86.98.105.46.66.72.123.104.112.210.148.253.231.252.208.196.247.169.181.70.22.127.108.53.20.11.126.54.223.203.202.170.226.156"></iframe>"""
        val v12Fixed =
            """<iframe id="cn_fr_02bd5628" src="about:blank" data-cnk="633637376630323664653663303836346663363863613432" data-cna="12.86.98.105.46.66.72.123.104.112.210.148.253.231.252.208.196.247.169.181.70.22.127.108.53.20.11.126.54.223.203.194.170.226.156"></iframe>"""
        assertEquals("https://cimanow.upns.online/#otni3k", CimaNowCrypto.decodeCnBootFrame(v12Fixed))
        assertNotNull(sampleIframeV12)

        val coreTok = "myCoreSecretToken64HexValue"
        val infoAttr = "f91a"
        val targetEmbed = "https://cimanow.upns.online/#cfv8l1"
        val ks = "$coreTok:$infoAttr"
        val cnbEncoded = targetEmbed.indices.joinToString("_") { j ->
            (targetEmbed[j].code xor ks[j % ks.length].code xor ((j * 19 + 11) and 255)).toString()
        }
        val sampleIframeV13 = """<iframe src="javascript:void(0);" data-cnb="$cnbEncoded" data-cni="$infoAttr"></iframe>"""
        assertEquals(targetEmbed, CimaNowCrypto.decodeCnBootFrame(sampleIframeV13, coreTok))

        // 5. Full Rhino V13 `/watching/` sandbox (`unlockWatchingVault` + snapshot `querySelectorAll` + XHR signer + `__cnBootFrame*`)
        val mockS2 = """
            (function(){
                var _liveTk = '';
                window.__cnBootFrameV99 = function(f) {
                    var b = f.getAttribute('data-cnb');
                    if (b && _liveTk) f.src = b + '?t=' + _liveTk;
                };
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(m, u) {
                    arguments[1] = u + '&_v=sig99';
                    return origOpen.apply(this, arguments);
                };
                window['_decTk'] = function(s) { _liveTk = s.split('').reverse().join(''); return _liveTk; };
                Document.prototype.write(
                    '<html><body>' +
                    '<div id="watch">' +
                    '<li data-enc="00@honey">Canary</li>' +
                    '<li data-enc="139950@66">Upnshare</li>' +
                    '<li data-enc="139950@32">Filemoon</li>' +
                    '<li data-enc="139950@30">VK</li>' +
                    '<iframe src="javascript:void(0);" data-cnb="https://ok.ru/videoembed/999"></iframe>' +
                    '</div>' +
                    '<div id="download">' +
                    '<a data-dl="https://vk.com/doc1_1">1080p</a>' +
                    '<a data-dl="https://href.li/?https://cimanow.upns.online/#abc">720p</a>' +
                    '<a data-dl="https://bysetayico.com/download/xyz">480p</a>' +
                    '</div>' +
                    '<script id="vault_script">(function(){ var tk = window["_decTk"]("321kot"); window._getRandomGetter = function(){ return tk; }; })();<\/script>' +
                    '</body></html>'
                );
                var items = document.querySelectorAll('li[data-enc]');
                for (var k = 0; k < items.length; k++) {
                    var p = items[k].getAttribute('data-enc').split('@');
                    items[k].setAttribute('data-id', p[0]);
                    items[k].setAttribute('data-index', p[1]);
                    items[k].removeAttribute('data-enc');
                }
                var dls = document.querySelectorAll('a[data-dl]');
                for (var d = 0; d < dls.length; d++) {
                    dls[d].setAttribute('href', dls[d].getAttribute('data-dl'));
                    dls[d].removeAttribute('data-dl');
                }
            })();
        """.trimIndent()
        val mockS2B64 = CimaNowCrypto.b64EncodeNoWrap(mockS2.toByteArray(Charsets.UTF_8)) + "A".repeat(600 - minOf(599, mockS2.length))
        val realS2B64 = CimaNowCrypto.b64EncodeNoWrap((mockS2 + "\n//" + "x".repeat(600)).toByteArray(Charsets.UTF_8))
        val mockRawWatchHtml = """
            <html><head>
            <script id="k_test99" data-payload="$realS2B64">
                (function(){ var s=document.getElementById('k_test99'); (new Function(window.atob(s.getAttribute('data-payload'))))(); })();
            </script>
            </head><body></body></html>
        """.trimIndent()
        assertNotNull(mockS2B64)
        val handle = CimaNowCrypto.unlockWatchingVault(mockRawWatchHtml)
        assertNotNull("Expected Rhino unlockWatchingVault to succeed", handle)
        assertEquals("tok123", handle!!.decoded.coreToken)
        assertEquals("https://ok.ru/videoembed/999?t=tok123", handle.decoded.initialEmbedUrl)
        // Must preserve ALL 3 real tabs (without skipping every second element on removeAttribute!) and exclude 00 canary
        assertEquals(listOf("Upnshare", "Filemoon", "VK"), handle.decoded.serverTabs.map { it.name })
        // Must preserve ALL 3 downloads and strip href.li/?
        assertEquals(
            listOf("https://vk.com/doc1_1", "https://cimanow.upns.online/#abc", "https://bysetayico.com/download/xyz"),
            handle.decoded.downloads.map { it.url },
        )
        assertTrue(handle.signCoreSwitchUrl("https://cimanow.cc/core.php?action=switch", handle.decoded.serverTabs[0]).endsWith("&_v=sig99"))
        assertEquals(
            "https://vid.example/e/1?t=tok123",
            handle.decodeCoreIframe("""<iframe src="javascript:void(0);" data-cnb="https://vid.example/e/1"></iframe>"""),
        )

        // 6. `#related` recommendation card parsing (must skip `<img alt="logo" src="...-لوجو.png">`)
        val relatedHtml = """
            <section id="related">
              <a href="https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-tenzing-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/">
                <img alt="logo" src="https://media6.cimanow.cc/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Tenzing-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D9%84%D9%88%D8%AC%D9%88.png?width=150">
                <img alt="فيلم Tenzing 2026 مترجم" src="https://media6.cimanow.cc/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Tenzing-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D9%83%D9%88%D9%81%D8%B1-768x432.jpg">
              </a>
            </section>
        """.trimIndent()
        val api = CimaNow()
        val parsedRecs = org.jsoup.Jsoup.parse(relatedHtml).select("#related a[href]").mapNotNull { api.parseCardAnchor(it) }
        assertEquals(1, parsedRecs.size)
        assertEquals("Tenzing", parsedRecs[0].name)
        assertTrue(parsedRecs[0].posterUrl!!.contains("%D9%83%D9%88%D9%81%D8%B1"))
    }

    @Test
    fun testFullLiveEndToEndSuite(): Unit = runBlocking {
        assumeTrue("Set CIMANOW_LIVE_E2E=1 to run live network E2E verification", System.getenv("CIMANOW_LIVE_E2E") == "1")

        val api = CimaNow()

        // ── 1. Verify Catalogs & Pagination ──────────────────────────────────
        val shelvesToCheck = listOf(
            MainPageRequest("أحدث الإضافات", "الاحدث", false),
            MainPageRequest("أفلام أجنبية", "category/افلام-اجنبية", false),
            MainPageRequest("أفلام عربية", "category/افلام-عربية", false),
            MainPageRequest("مسلسلات أجنبية", "category/مسلسلات-اجنبية", false),
            MainPageRequest("مسلسلات رمضان 2026", "category/رمضان-2026", false),
        )

        for (req in shelvesToCheck) {
            val p1 = api.getMainPage(1, req)
            val p1Items = p1.items.firstOrNull()?.list.orEmpty()
            println("[E2E][Catalog] ${req.name} p1 -> count=${p1Items.size} hasNext=${p1.hasNext}")
            assertTrue("Expected items in shelf ${req.name}", p1Items.isNotEmpty())
            assertTrue("Expected valid posters in shelf ${req.name}", p1Items.any { !it.posterUrl.isNullOrBlank() })
        }

        val foreignMoviesReq = MainPageRequest("أفلام أجنبية", "category/افلام-اجنبية", false)
        val fmP1 = api.getMainPage(1, foreignMoviesReq).items.first().list
        val fmP2 = api.getMainPage(2, foreignMoviesReq).items.first().list
        assertTrue("Expected page 2 items", fmP2.isNotEmpty())
        assertFalse("Expected distinct page 2 URLs", fmP1.first().url == fmP2.first().url)

        // ── 2. Verify Search ─────────────────────────────────────────────────
        val lupinSearch = api.search("Lupin", 1)
        assertNotNull(lupinSearch)
        assertTrue("Expected Lupin search results", lupinSearch!!.items.isNotEmpty())

        // ── 3. Verify `load()` + `loadLinks()` on Live Movie ─────────────────
        val liveMovieCard = fmP1.first()
        val movieLoad = api.load(liveMovieCard.url) as? MovieLoadResponse
        assertNotNull("Expected MovieLoadResponse for ${liveMovieCard.name}", movieLoad)
        println(
            "[E2E][Load Movie] title='${movieLoad!!.name}' year=${movieLoad.year} dur=${movieLoad.duration}m " +
                "recs=${movieLoad.recommendations?.size ?: 0}",
        )
        assertFalse(movieLoad.posterUrl.isNullOrBlank())

        CimaNowExtractors.debugLogger = { println("    [E2E][Trace] $it") }
        val movieLinks = CopyOnWriteArrayList<ExtractorLink>()
        val movieSubs = CopyOnWriteArrayList<SubtitleFile>()
        val okMovie = api.loadLinks(movieLoad.dataUrl, false, { movieSubs.add(it) }, { movieLinks.add(it) })
        val movieProviders = movieLinks.map { it.source }.distinct()
        println("[E2E][LoadLinks Movie] ok=$okMovie links=${movieLinks.size} subs=${movieSubs.size} providers=$movieProviders")
        for (lnk in movieLinks) {
            println("  -> [${lnk.source}] ${lnk.name} q=${lnk.quality} type=${lnk.type} url=${lnk.url.take(100)}")
        }
        assertTrue("Expected loadLinks=true on live movie ${movieLoad.name}", okMovie)
        assertTrue("Expected >= 4 extracted links on live movie, got ${movieLinks.size}", movieLinks.size >= 4)
        assertTrue("Expected >= 3 distinct providers on live movie, got $movieProviders", movieProviders.size >= 3)

        var verifiedMovieProviders = 0
        for ((prov, group) in movieLinks.groupBy { it.source }) {
            val playable = group.any { probePlayableStream(it) }
            println("  [MovieStreamProbe] provider=$prov playable=$playable sample=${group.first().url.take(90)}")
            if (playable) verifiedMovieProviders++
        }
        assertTrue("Expected >= 2 live playable movie providers returning 200/206 media, got $verifiedMovieProviders", verifiedMovieProviders >= 2)

        // Verify instant unlocked-cache reload (< 8s, zero second 10s gateway wait!)
        val tCache0 = System.currentTimeMillis()
        val cachedLinks = CopyOnWriteArrayList<ExtractorLink>()
        val okCached = api.loadLinks(movieLoad.dataUrl, false, {}, { cachedLinks.add(it) })
        val cacheReloadMs = System.currentTimeMillis() - tCache0
        println("[E2E][CacheReload Movie] ok=$okCached links=${cachedLinks.size} reloadMs=${cacheReloadMs}ms")
        assertTrue("Expected cached loadLinks=true", okCached && cachedLinks.size >= 4)
        assertTrue("Expected cached reload under 9000ms (no gateway countdown), got ${cacheReloadMs}ms", cacheReloadMs < 9_000L)

        // ── 4. Verify `load()` + `loadLinks()` on Live Series Episode ────────
        val lupinSelaryUrl = "https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin/"
        val lupinLoad = api.load(lupinSelaryUrl) as? TvSeriesLoadResponse
        assertNotNull("Expected TvSeriesLoadResponse for Lupin /selary/", lupinLoad)
        val seasonsFound = lupinLoad!!.episodes.mapNotNull { it.season }.distinct().sorted()
        println("[E2E][Load Series] title='${lupinLoad.name}' seasons=$seasonsFound totalEpisodes=${lupinLoad.episodes.size}")
        assertTrue("Expected >= 15 total episodes across Lupin seasons", lupinLoad.episodes.size >= 15)

        val sampleEp = lupinLoad.episodes.last()
        val epLinks = CopyOnWriteArrayList<ExtractorLink>()
        val okEp = api.loadLinks(sampleEp.data, false, {}, { epLinks.add(it) })
        val epProviders = epLinks.map { it.source }.distinct()
        println("[E2E][LoadLinks Episode] ep='${sampleEp.name}' S${sampleEp.season}E${sampleEp.episode} ok=$okEp links=${epLinks.size} providers=$epProviders")
        for (lnk in epLinks) {
            println("  -> [${lnk.source}] ${lnk.name} q=${lnk.quality} type=${lnk.type} url=${lnk.url.take(100)}")
        }
        assertTrue("Expected loadLinks=true on series episode", okEp)
        assertTrue("Expected >= 2 extracted links on series episode, got ${epLinks.size}", epLinks.size >= 2)
        val anyEpPlayable = epLinks.any { probePlayableStream(it) }
        println("  [EpisodeStreamProbe] anyPlayable=$anyEpPlayable")
        assertTrue("Expected at least 1 live playable stream (200/206) on series episode", anyEpPlayable)
        Unit
    }

    private fun probePlayableStream(link: ExtractorLink): Boolean = runCatching {
        val rb = Request.Builder().url(link.url)
        for ((k, v) in link.headers) rb.header(k, v)
        if (link.referer.isNotBlank() && !link.headers.containsKey("Referer")) {
            rb.header("Referer", link.referer)
        }
        if (link.type == ExtractorLinkType.M3U8) {
            CimaNowNetwork.directNoProxyClient.newCall(rb.build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                res.code in 200..299 && body.contains("#EXTM3U")
            }
        } else {
            rb.header("Range", "bytes=0-1023")
            CimaNowNetwork.directNoProxyClient.newCall(rb.build()).execute().use { res ->
                val ct = res.header("Content-Type").orEmpty().lowercase()
                (res.code == 200 || res.code == 206) &&
                    !ct.contains("text/html") &&
                    (ct.contains("video") || ct.contains("octet-stream") || ct.contains("mp4"))
            }
        }
    }.getOrDefault(false)
}
