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
import org.junit.Assert.assertNull
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

        // 2. Generalized Flexbox `<x-fx>` & `<x-grid>` height solver (both inline styles & V14Ultra `<style>` class rules)
        val gridHtml =
            "<x-grid style='display:flex!important;width:403px!important;box-sizing:border-box!important;padding:10px 12px!important;column-gap:12px!important;row-gap:6px!important;'>" +
                "<x-n style='order:20!important;display:block!important;box-sizing:border-box!important;width:200px!important;height:40px!important;padding:4px 4px!important;margin:0px 2px!important;'></x-n>" +
                "<x-n style='order:10!important;display:block!important;box-sizing:content-box!important;width:200px!important;height:30px!important;padding:5px 6px!important;margin:0px 4px!important;'></x-n>" +
                "<x-n style='order:30!important;display:none!important;width:300px!important;height:90px!important;'></x-n>" +
                "</x-grid>"
        assertEquals(106, CimaNowCrypto.computeFlexHeight(gridHtml))

        val v14ClassGridHtml =
            "<style>" +
                ".cg_1 { display:flex!important;width:403px!important;box-sizing:border-box!important;padding:10px 12px!important;column-gap:12px!important;row-gap:6px!important; }" +
                ".cs_a { order:20!important;display:block!important;box-sizing:border-box!important;width:200px!important;height:40px!important;padding:4px 4px!important;margin:0px 2px!important; }" +
                ".cs_b { order:10!important;display:block!important;box-sizing:content-box!important;width:200px!important;height:30px!important;padding:5px 6px!important;margin:0px 4px!important; }" +
                ".cs_c { order:30!important;display:none!important;width:300px!important;height:90px!important; }" +
                "</style><x-grid class='cg_1'><x-n class='cs_a'></x-n><x-n class='cs_b'></x-n><x-n class='cs_c'></x-n></x-grid>"
        assertEquals(106, CimaNowCrypto.computeFlexHeight(v14ClassGridHtml))

        // 3. FNV-1a base36 `core.php` signatures (V12, V13, & V14Ultra)
        val sigV12 = CimaNowCrypto.calcCoreSig(
            "d224f3b047d641a53111e7a19b7091c688038510dbfeca943dbe964114b13c25",
            "139241",
            "66",
            "cnSig12",
        )
        assertTrue(sigV12.isNotBlank())
        val sigV13 = CimaNowCrypto.calcCoreSigV13("tok123", "139950", "66", "cnV13Sig")
        assertTrue(sigV13.isNotBlank())
        val sigV14 = CimaNowCrypto.calcCoreSigV14("tok123", "139950", "66", "cnV14Ultra")
        assertTrue(sigV14.isNotBlank())

        // 4. `__cnBootFrame` V12 (`data-cnk`/`data-cna`), V13 (`data-cnb`/`data-cni`), & V14Ultra (`data-vx`) iframe decryption
        val v12Fixed =
            """<iframe id="cn_fr_02bd5628" src="about:blank" data-cnk="633637376630323664653663303836346663363863613432" data-cna="12.86.98.105.46.66.72.123.104.112.210.148.253.231.252.208.196.247.169.181.70.22.127.108.53.20.11.126.54.223.203.194.170.226.156"></iframe>"""
        assertEquals("https://cimanow.upns.online/#otni3k", CimaNowCrypto.decodeCnBootFrame(v12Fixed))

        val coreTok = "myCoreSecretToken64HexValue"
        val infoAttr = "f91a"
        val targetEmbed = "https://cimanow.upns.online/#cfv8l1"
        val ks = "$coreTok:$infoAttr"
        val cnbEncoded = targetEmbed.indices.joinToString("_") { j ->
            (targetEmbed[j].code xor ks[j % ks.length].code xor ((j * 19 + 11) and 255)).toString()
        }
        val sampleIframeV13 = """<iframe src="javascript:void(0);" data-cnb="$cnbEncoded" data-cni="$infoAttr"></iframe>"""
        assertEquals(targetEmbed, CimaNowCrypto.decodeCnBootFrame(sampleIframeV13, coreTok))

        val v14Payload = "cn::v14::$targetEmbed"
        val ksV14 = "$coreTok~cnV14Ultra"
        val vxEncoded = v14Payload.indices.joinToString("-") { j ->
            (v14Payload[j].code xor ksV14[j % ksV14.length].code xor ((j * 23 + 7) and 255)).toString()
        }
        val sampleIframeV14 = """<iframe src="javascript:void(0);" data-vx="$vxEncoded"></iframe>"""
        assertEquals(targetEmbed, CimaNowCrypto.decodeCnBootFrame(sampleIframeV14, coreTok))

        // 5. Full Rhino V14+ `/watching/` sandbox (`unlockWatchingVault` + `Document.prototype`/`Element.prototype` `_isBadQS` hooks + XHR signer + `__cnBootFrame*`)
        val mockS2 = """
            (function(){
                var _nWrite = Document.prototype.write;
                var _emptyQSA = document.createDocumentFragment().querySelectorAll('x');
                var _origQSA = Document.prototype.querySelectorAll;
                var _origQS = Document.prototype.querySelector;
                var _origGTN = Document.prototype.getElementsByTagName;
                var _origEQSA = Element.prototype.querySelectorAll;
                var _origEQS = Element.prototype.querySelector;
                Document.prototype.querySelectorAll = function(a) {
                    if (typeof a === 'string' && (a.indexOf('a[href]') !== -1 || a.indexOf('#download') !== -1)) return _emptyQSA;
                    return _origQSA.apply(this, arguments);
                };
                Document.prototype.getElementsByTagName = function(a) {
                    if (a && a.toLowerCase() === 'iframe') return _emptyQSA;
                    return _origGTN.apply(this, arguments);
                };
                var _nOPD = Object.getOwnPropertyDescriptor;
                if (window.Object && _nOPD) { _nOPD(window.Element.prototype, 'outerHTML'); }
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
        val realS2B64 = CimaNowCrypto.b64EncodeNoWrap((mockS2 + "\n//" + "x".repeat(600)).toByteArray(Charsets.UTF_8))
        val mockRawWatchHtml = """
            <html><head>
            <script id="k_test99" data-payload="$realS2B64">
                (function(){ var s=document.getElementById('k_test99'); (new Function(window.atob(s.getAttribute('data-payload'))))(); })();
            </script>
            </head><body></body></html>
        """.trimIndent()
        val handle = CimaNowCrypto.unlockWatchingVault(mockRawWatchHtml)
        assertNotNull("Expected Rhino unlockWatchingVault to succeed", handle)
        assertEquals("tok123", handle!!.decoded.coreToken)
        assertEquals("https://ok.ru/videoembed/999?t=tok123", handle.decoded.initialEmbedUrl)
        assertEquals(listOf("Upnshare", "Filemoon", "VK"), handle.decoded.serverTabs.map { it.name })
        assertEquals(
            listOf("https://vk.com/doc1_1", "https://cimanow.upns.online/#abc", "https://bysetayico.com/download/xyz"),
            handle.decoded.downloads.map { it.url },
        )
        assertTrue(handle.signCoreSwitchUrl("https://cimanow.cc/core.php?action=switch", handle.decoded.serverTabs[0]).endsWith("&_v=sig99"))
        assertEquals(
            "https://vid.example/e/1?t=tok123",
            handle.decodeCoreIframe("""<iframe src="javascript:void(0);" data-cnb="https://vid.example/e/1"></iframe>"""),
        )

        // 6. Android WebView UA cleaning (`; wv)` and `Version/4.0` stripped cleanly) + gateway URL helpers
        val xiaomiWvUa =
            "Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/150.0.7871.63 Mobile Safari/537.36"
        val cleanedUa = CimaNowGatewaySolver.cleanWebViewUserAgent(xiaomiWvUa)
        assertFalse(cleanedUa.contains("; wv"))
        assertFalse(cleanedUa.contains("Version/"))
        assertTrue(cleanedUa.contains("M2006C3MG") && cleanedUa.contains("Chrome/150.0.7871.63 Mobile"))

        assertEquals(
            "https://cimanow.cc/movie/watching/?token=abc123",
            CimaNowGatewaySolver.normalizeMintedWatchingUrl("https://href.li/?https://cimanow.cc/movie/watching/?token=abc123"),
        )
        assertNull(CimaNowGatewaySolver.normalizeMintedWatchingUrl("https://fugitivequilt.com/popunder"))
        assertTrue(
            CimaNowNetwork.isSoftRedirectedToHome(
                "https://cimanow.cc/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9/",
                "https://cimanow.cc/home/",
            ),
        )
        assertFalse(CimaNowNetwork.isSoftRedirectedToHome("https://cimanow.cc/home/", "https://cimanow.cc/home/"))

        // 7. VidGuard AAEncode Rhino decryption (`ﾟωﾟﾉ=`)
        val vgHtml = """
            <html><body><script>
            var ﾟωﾟﾉ = 'svg';
            window[ﾟωﾟﾉ] = {"stream":"https:\/\/embedv.net\/hls2\/01\/0001\/master.m3u8?t=abc&sig=deadbeef"};
            </script></body></html>
        """.trimIndent()
        val svgJson = CimaNowExtractors.evalVidGuardSvgJson(vgHtml)
        assertNotNull(svgJson)
        assertEquals("https://embedv.net/hls2/01/0001/master.m3u8?t=abc&sig=deadbeef", svgJson!!.optString("stream"))

        // 8. `#related` recommendation card parsing (must skip `<img alt="logo" src="...-لوجو.png">`)
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

        // 9. Read-only Android WebView gateway probe invariants
        val probeDuringCd = CimaNowGatewaySolver.buildReadOnlyGatewayProbeJs(countdownElapsed = false, allowTurnstileTap = true)
        assertTrue(probeDuringCd.contains("xqeqjp") && probeDuringCd.contains("OVL:"))
        assertTrue(probeDuringCd.contains("visualViewport"))
        assertTrue(probeDuringCd.contains("ERR:BROWSER") && probeDuringCd.contains("ERR:WAF"))
        assertTrue(probeDuringCd.contains("behavior: 'instant'"))
        assertTrue(probeDuringCd.contains("if (false && !cd"))
    }

    @Test
    fun testCloudStreamRuntimeInvariants() {
        // 1. `visualViewport` -> physical WebView px mapping across Portrait, Landscape (`GeneratorPlayer` 800x360), and 980px wide viewport
        val portraitPt = CimaNowGatewaySolver.computeViewTapPoint(
            wvWidth = 1080,
            wvHeight = 2400,
            density = 2.75f,
            target = CimaNowGatewaySolver.CssTapTarget(196f, 567f, 393f, 873f),
        )
        assertEquals(538.6f, portraitPt.first, 1.5f)
        assertEquals(1558.8f, portraitPt.second, 1.5f)

        val landscapePt = CimaNowGatewaySolver.computeViewTapPoint(
            wvWidth = 2200,
            wvHeight = 990,
            density = 2.75f,
            target = CimaNowGatewaySolver.CssTapTarget(400f, 180f, 800f, 360f),
        )
        assertEquals(1100f, landscapePt.first, 0.5f)
        assertEquals(495f, landscapePt.second, 0.5f)

        val wideViewportPt = CimaNowGatewaySolver.computeViewTapPoint(
            wvWidth = 1080,
            wvHeight = 2400,
            density = 2.75f,
            target = CimaNowGatewaySolver.CssTapTarget(490f, 1100f, 980f, 2180f),
        )
        assertEquals(540f, wideViewportPt.first, 0.5f)
        assertEquals(1211f, wideViewportPt.second, 1.5f)

        // 2. Lossless `evaluateJavascript` JSON string decoding (`JSONTokener`)
        val rawJsStr = org.json.JSONObject.quote(
            "META|4|0|UpnShare|196,420,800,360\nIF|https://cimanow.upns.online/#cfv8l1\nDL|https://reva.cimanowtv.com/f.mp4?dl=1\t1080p \\ \"HD\"",
        )
        val decoded = CimaNowGatewaySolver.decodeEvaluateJavascriptResult(rawJsStr)
        val snap = CimaNowGatewaySolver.parseWatchingDomProbeOutput(decoded)
        assertNotNull(snap)
        assertEquals(4, snap!!.totalTabs)
        assertEquals(0, snap.clickedIdx)
        assertEquals("UpnShare", snap.tabName)
        assertEquals("196,420,800,360", snap.tapSpec)
        assertEquals(listOf("https://cimanow.upns.online/#cfv8l1"), snap.iframeSrcs)
        assertEquals(1, snap.downloads.size)
        assertEquals("https://reva.cimanowtv.com/f.mp4?dl=1", snap.downloads[0].first)
        assertEquals("1080p \\ \"HD\"", snap.downloads[0].second)
        assertEquals("", CimaNowGatewaySolver.decodeEvaluateJavascriptResult("null"))
        assertEquals("", CimaNowGatewaySolver.decodeEvaluateJavascriptResult(null))

        // 3. Dynamic gateway host navigation allowlist
        assertTrue(CimaNowGatewaySolver.isAllowedGatewayNavigation("https://rm.newgateway2027.online/2020/02/blog-post.html/", "rm.newgateway2027.online"))
        assertTrue(CimaNowGatewaySolver.isAllowedGatewayNavigation("https://sub.newgateway2027.online/get-link.php", "rm.newgateway2027.online"))
        assertFalse(CimaNowGatewaySolver.isAllowedGatewayNavigation("https://fugitivequilt.com/ad", "rm.newgateway2027.online"))

        // 4. `CS3IPlayer` Cronet HTTP/2 duplicate `referer`/`Referer` prevention (`mapOf("referer" to link.referer) + link.headers`)
        val normalized = CimaNowExtractors.playerHeaders(
            mapOf("User-Agent" to CimaNowNetwork.DESKTOP_UA, "Referer" to "https://cimanow.upns.online/"),
        )
        val cs3Merged = mapOf("referer" to "https://cimanow.upns.online/") + normalized
        val refererKeys = cs3Merged.keys.filter { it.equals("referer", ignoreCase = true) }
        assertEquals("CS3IPlayer merged headers must contain exactly 1 referer key", listOf("referer"), refererKeys)
    }

    @Test
    fun testFullLiveEndToEndSuite(): Unit = runBlocking {
        assumeTrue("Skipping live E2E test unless CIMANOW_LIVE_E2E=1", System.getenv("CIMANOW_LIVE_E2E") == "1")

        val api = CimaNow()
        CimaNowExtractors.debugLogger = { println("    [E2E][Trace] $it") }
        CimaNowCrypto.vaultDebugLogger = { println("    [E2E][VaultDbg] $it") }

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

        // ── 3. Verify User-Reported `The Beekeeper (2024)` Movie End-to-End ──
        val beekeeperUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-the-beekeeper-2024-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/"
        val bkLoad = api.load(beekeeperUrl) as? MovieLoadResponse
        assertNotNull("Expected MovieLoadResponse for The Beekeeper (2024)", bkLoad)
        val bkLinks = CopyOnWriteArrayList<ExtractorLink>()
        val okBk = api.loadLinks(bkLoad!!.dataUrl, false, {}, { bkLinks.add(it) })
        val bkProviders = bkLinks.map { it.source }.distinct()
        println("[E2E][Beekeeper 2024] ok=$okBk links=${bkLinks.size} providers=$bkProviders")
        for (lnk in bkLinks) {
            println("  -> [${lnk.source}] ${lnk.name} q=${lnk.quality} type=${lnk.type} url=${lnk.url.take(100)}")
        }
        assertTrue("Expected loadLinks=true on The Beekeeper (2024)", okBk)
        assertTrue("Expected >= 4 extracted links on The Beekeeper (2024), got ${bkLinks.size}", bkLinks.size >= 4)
        assertTrue("Expected >= 1 distinct provider on The Beekeeper (2024), got $bkProviders", bkProviders.size >= 1)
        val bkPlayable = bkLinks.any { probePlayableStream(it) }
        println("  [BeekeeperStreamProbe] anyPlayable=$bkPlayable")
        assertTrue("Expected live playable stream (200/206) on The Beekeeper (2024)", bkPlayable)

        // ── 4. Verify Latest Live Movie + Instant Cache Reload (< 9s) ────────
        val liveMovieCard = fmP1.first()
        val movieLoad = api.load(liveMovieCard.url) as? MovieLoadResponse
        assertNotNull("Expected MovieLoadResponse for ${liveMovieCard.name}", movieLoad)
        println(
            "[E2E][Load Movie] title='${movieLoad!!.name}' year=${movieLoad.year} dur=${movieLoad.duration}m " +
                "recs=${movieLoad.recommendations?.size ?: 0}",
        )
        assertFalse(movieLoad.posterUrl.isNullOrBlank())

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

        val tCache0 = System.currentTimeMillis()
        val cachedLinks = CopyOnWriteArrayList<ExtractorLink>()
        val okCached = api.loadLinks(movieLoad.dataUrl, false, {}, { cachedLinks.add(it) })
        val cacheReloadMs = System.currentTimeMillis() - tCache0
        println("[E2E][CacheReload Movie] ok=$okCached links=${cachedLinks.size} reloadMs=${cacheReloadMs}ms")
        assertTrue("Expected cached loadLinks=true", okCached && cachedLinks.size >= 4)
        assertTrue("Expected cached reload under 9000ms (no gateway countdown), got ${cacheReloadMs}ms", cacheReloadMs < 9_000L)

        // ── 5. Verify `load()` + `loadLinks()` on Live Series Episode ────────
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

        // Verify CS3IPlayer Cronet HTTP/2 header cleanliness across all emitted links
        for (lnk in bkLinks + movieLinks + epLinks) {
            val cs3Merged = mapOf("referer" to lnk.referer) + lnk.headers
            val refKeyCount = cs3Merged.keys.count { it.equals("referer", ignoreCase = true) }
            assertTrue("Expected <= 1 referer key in CS3IPlayer merged headers for ${lnk.name}, got ${cs3Merged.keys}", refKeyCount <= 1)
        }

        // ── 6. Verify Exact Android WebView (`; wv`) in BOTH Portrait (393x873) AND `GeneratorPlayer` Landscape (800x360) Live ──
        val bkData = CimaNowCrypto.safeJsonObject(bkLoad.dataUrl)
        val bkItemUrl = bkData?.optString("itemUrl") ?: beekeeperUrl
        val bkShineUrl = bkData?.optString("shineUrl").orEmpty()
        assertTrue("Expected shineUrl on The Beekeeper", bkShineUrl.isNotBlank())

        val portraitMinted = verifyAndroidWebViewProbeLive(bkShineUrl, bkItemUrl, viewportW = 393, viewportH = 873)
        println("[E2E][AndroidWebViewSim-Portrait-393x873] minted=${portraitMinted?.take(95)}")
        assertNotNull("Expected Portrait Android WebView `; wv` simulation to mint watching URL", portraitMinted)
        assertTrue(portraitMinted!!.contains("/watching/") && portraitMinted.contains("token="))

        val landscapeMinted = verifyAndroidWebViewProbeLive(bkShineUrl, bkItemUrl, viewportW = 800, viewportH = 360)
        println("[E2E][AndroidWebViewSim-Landscape-800x360] minted=${landscapeMinted?.take(95)}")
        assertNotNull("Expected GeneratorPlayer Landscape (800x360) Android WebView `; wv` simulation to mint watching URL", landscapeMinted)
        assertTrue(landscapeMinted!!.contains("/watching/") && landscapeMinted.contains("token="))
        Unit
    }

    private fun verifyAndroidWebViewProbeLive(
        shineUrl: String,
        itemUrl: String,
        viewportW: Int,
        viewportH: Int,
    ): String? {
        val probeWait = CimaNowGatewaySolver.buildReadOnlyGatewayProbeJs(countdownElapsed = false, allowTurnstileTap = false)
        val probeTs = CimaNowGatewaySolver.buildReadOnlyGatewayProbeJs(countdownElapsed = false, allowTurnstileTap = true)
        val probeForce = CimaNowGatewaySolver.buildReadOnlyGatewayProbeJs(countdownElapsed = true, allowTurnstileTap = true)
        val tmpJs = java.io.File.createTempFile("cn_wv_sim_", ".js")
        val tmpOut = java.io.File.createTempFile("cn_wv_sim_out_", ".json")
        val script = """
            const { spawn } = require('child_process');
            const http = require('http');
            const fs = require('fs');
            const port = 19910 + Math.floor(Math.random() * 60);
            const prof = '/tmp/cn_wv_sim_' + process.pid + '_' + port;
            const chrome = ['/usr/bin/google-chrome-stable','/usr/bin/google-chrome','/usr/bin/chromium-browser','/usr/bin/chromium'].find(p => fs.existsSync(p));
            const args = ['--no-sandbox','--disable-setuid-sandbox','--disable-dev-shm-usage','--no-first-run','--disable-gpu','--user-data-dir='+prof,'--disable-blink-features=AutomationControlled','--window-size=${viewportW},${viewportH}','--remote-debugging-port='+port,'about:blank'];
            const xvfb = '/usr/bin/xvfb-run';
            const proc = fs.existsSync(xvfb) ? spawn(xvfb, ['-a','--server-args=-screen 0 1280x960x24', chrome, ...args], { stdio: 'ignore' }) : spawn(chrome, ['--headless=new', ...args], { stdio: 'ignore' });
            function done(obj) {
                try { fs.writeFileSync(${org.json.JSONObject.quote(tmpOut.absolutePath)}, JSON.stringify(obj), 'utf8'); } catch(e) {}
                try { proc.kill('SIGKILL'); } catch(e) {}
                try { fs.rmSync(prof, { recursive: true, force: true }); } catch(e) {}
                process.exit(0);
            }
            function getJson(u) { return new Promise((res, rej) => { http.get(u, r => { let d=''; r.on('data',c=>d+=c); r.on('end',()=>{ try{res(JSON.parse(d));}catch(e){rej(e);} }); }).on('error', rej); }); }
            (async () => {
                let targets = null;
                for (let i = 0; i < 45; i++) {
                    try { targets = await getJson('http://127.0.0.1:'+port+'/json'); if (targets && targets.length) break; } catch(e) {}
                    await new Promise(r => setTimeout(r, 400));
                }
                if (!targets || !targets.length) return done({ url: '', err: 'no_targets' });
                const t = targets.find(x => x.type === 'page') || targets[0];
                const ws = new WebSocket(t.webSocketDebuggerUrl);
                await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
                let id = 1; const pending = new Map();
                let minted = ''; const trace = [];
                ws.onmessage = ev => {
                    const m = JSON.parse(ev.data);
                    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
                    if (m.method === 'Target.attachedToTarget' && m.params && m.params.sessionId) {
                        const sid = m.params.sessionId;
                        const turl = (m.params.targetInfo && m.params.targetInfo.url) || '';
                        if (turl.includes('/watching/') && turl.includes('token=')) minted = turl;
                        else if (turl.startsWith('http') && !turl.includes('freex2line') && !turl.includes('cimanow') && !turl.includes('href.li')) {
                            ws.send(JSON.stringify({ id: id++, method: 'Target.closeTarget', params: { targetId: m.params.targetInfo.targetId } }));
                        } else {
                            ws.send(JSON.stringify({ id: id++, sessionId: sid, method: 'Network.enable', params: {} }));
                        }
                    }
                    if (m.method === 'Target.targetInfoChanged' && m.params && m.params.targetInfo) {
                        const tu = m.params.targetInfo.url || '';
                        if (tu.includes('/watching/') && tu.includes('token=')) minted = tu;
                    }
                    if (m.method === 'Network.requestWillBeSent') {
                        const u = m.params.request.url || '';
                        if (u.includes('/watching/') && u.includes('token=')) minted = u;
                    }
                };
                const send = (method, params = {}) => new Promise(r => { const cid = id++; pending.set(cid, r); ws.send(JSON.stringify({ id: cid, method, params })); });
                await send('Page.enable');
                await send('Network.enable');
                await send('Target.setAutoAttach', { autoAttach: true, waitForDebuggerOnStart: false, flatten: true });
                await send('Emulation.setDeviceMetricsOverride', { width: ${viewportW}, height: ${viewportH}, deviceScaleFactor: 2.75, mobile: true });
                await send('Page.addScriptToEvaluateOnNewDocument', {
                    source: "try { Object.defineProperty(navigator, 'userAgent', { get: () => 'Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/150.0.7871.63 Mobile Safari/537.36', configurable: true }); Object.defineProperty(navigator, 'platform', { get: () => 'Linux armv8l', configurable: true }); } catch(e) {}"
                });
                await send('Page.navigate', { url: ${org.json.JSONObject.quote(CimaNowNetwork.encodeUrl(shineUrl))}, referrer: ${org.json.JSONObject.quote(CimaNowNetwork.encodeUrl(itemUrl))} });
                const tap = async (x, y) => {
                    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
                    await new Promise(r => setTimeout(r, 65));
                    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
                };
                let firstBtnMs = 0, lastTsMs = 0, lastOvlMs = 0, lastBtnMs = 0, tsTry = 0;
                const jsWait = ${org.json.JSONObject.quote(probeWait)};
                const jsTs = ${org.json.JSONObject.quote(probeTs)};
                const jsForce = ${org.json.JSONObject.quote(probeForce)};
                for (let ms = 0; ms < 34000; ms += 450) {
                    await new Promise(r => setTimeout(r, 450));
                    if (minted) return done({ url: minted, trace });
                    if (ms < 2600) continue;
                    if (firstBtnMs > 0 && ms - firstBtnMs < 6200) continue;
                    const expr = (firstBtnMs > 0 && ms - firstBtnMs >= 12500) ? jsForce : ((firstBtnMs === 0 ? ms >= 6500 : ms - firstBtnMs >= 8200) ? jsTs : jsWait);
                    const ev = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
                    const pv = (ev && ev.result && ev.result.result && ev.result.result.value) ? String(ev.result.result.value) : '';
                    if (!firstBtnMs && pv.includes('HASBTN')) firstBtnMs = ms;
                    if (pv.startsWith('URL:')) return done({ url: pv.slice(4), trace });
                    if (pv.startsWith('ERR:')) return done({ url: '', err: pv, trace });
                    if (pv.startsWith('SCROLL:')) trace.push(ms + ':' + pv);
                    if (pv.startsWith('OVL:') && ms - lastOvlMs >= 480) {
                        lastOvlMs = ms;
                        const [ox, oy] = pv.slice(4).replace(':HASBTN','').split(',').map(Number);
                        trace.push(ms + ':OVL:' + ox + ',' + oy);
                        await tap(ox, oy);
                    } else if (pv.startsWith('BTN:') && ms - lastBtnMs >= 520) {
                        lastBtnMs = ms;
                        const [bx, by] = pv.slice(4).split(',').map(Number);
                        trace.push(ms + ':BTN:' + bx + ',' + by);
                        await tap(bx, by);
                    } else if (pv.startsWith('TS:') && ms - lastTsMs >= 3200) {
                        lastTsMs = ms;
                        const pts = pv.slice(3).replace(':HASBTN','').split('|');
                        const pick = pts[tsTry % pts.length] || pts[0];
                        tsTry++;
                        const [tx, ty] = pick.split(',').map(Number);
                        trace.push(ms + ':TS:' + tx + ',' + ty);
                        await tap(tx, ty);
                    }
                }
                done({ url: minted, trace });
            })().catch(e => done({ url: '', err: String(e) }));
        """.trimIndent()
        return try {
            tmpJs.writeText(script, Charsets.UTF_8)
            val proc = ProcessBuilder("node", tmpJs.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(java.io.File("/dev/null"))
                .start()
            val finished = proc.waitFor(55L, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            val raw = if (tmpOut.exists()) tmpOut.readText(Charsets.UTF_8) else "{}"
            println("  [AndroidWebViewSimRaw ${viewportW}x${viewportH}] $raw")
            val obj = CimaNowCrypto.safeJsonObject(raw)
            val traceStr = obj?.optJSONArray("trace")?.toString().orEmpty()
            assertFalse("Unexpected SCROLL:HASBTN deadlock in ${viewportW}x${viewportH}: $traceStr", traceStr.contains("SCROLL:HASBTN"))
            CimaNowGatewaySolver.normalizeMintedWatchingUrl(obj?.optString("url"))
        } finally {
            tmpJs.delete()
            tmpOut.delete()
        }
    }

    private fun probePlayableStream(link: ExtractorLink): Boolean = runCatching {
        val rb = Request.Builder().url(link.url)
        for ((k, v) in link.headers) rb.header(k, v)
        if (link.referer.isNotBlank() && link.headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
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
