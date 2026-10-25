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

        // 2. FNV-1a base36 `core.php` signature
        val sig = CimaNowCrypto.calcCoreSig(
            "d224f3b047d641a53111e7a19b7091c688038510dbfeca943dbe964114b13c25",
            "139241",
            "66",
            "cnSig12",
        )
        assertTrue(sig.isNotBlank())

        // 3. `__cnBootFrame` (`data-cnk` + `data-cna`) iframe decryption
        val sampleIframe =
            """<iframe id="cn_fr_02bd5628" src="about:blank" data-cnk="633637376630323664653663303836346663363863613432" data-cna="12.86.98.105.46.66.72.123.104.112.210.148.253.231.252.208.196.247.169.181.70.22.127.108.53.20.11.126.54.223.203.194.170.226.156"></iframe>"""
        assertEquals("https://cimanow.upns.online/#otni3k", CimaNowCrypto.decodeCnBootFrame(sampleIframe))

        // 4. `solveGatewayPostBody` repeating-XOR + `window["..."] = [...]` unpacker
        val innerScript =
            "<script>window.d={'k1':'chVal','k2':'riVal','k3':'AAECAw==','k4':'abcd'};" +
                "window.map_a1={ch:'k1',ri:'k2',ke:'k3',se:'k4'};</script>"
        val xorKey = byteArrayOf(0x5A, 0x33, 0x71)
        val xored = ByteArray(innerScript.length) { i ->
            (innerScript[i].code xor (xorKey[i % xorKey.size].toInt() and 0xFF)).toByte()
        }
        val b64Full = java.util.Base64.getEncoder().encodeToString(xored)
        val chunkedJs = b64Full.chunked(17).joinToString(",") { "\"$it\"" }
        val mockVault = """<html><body><script>window["_0x9f2a"] = [$chunkedJs];</script></body></html>"""
        val solvedPost = CimaNowCrypto.solveGatewayPostBody(mockVault)
        assertNotNull("Expected solveGatewayPostBody to unpack window[\"_0x9f2a\"] array", solvedPost)
        assertTrue(solvedPost!!.contains("request_id=riVal") && solvedPost.contains("ch=chVal") && solvedPost.contains("hmac_token="))

        // 5. `#related` recommendation card parsing (must skip `<img alt="logo" src="...-لوجو.png">` for both title & poster)
        val relatedHtml = """
            <section id="related">
              <a href="https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-tenzing-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/">
                <img alt="logo" src="https://media6.cimanow.cc/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Tenzing-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D9%84%D9%88%D8%AC%D9%88.png?width=150">
                <img alt="فيلم Tenzing 2026 مترجم" src="https://media6.cimanow.cc/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Tenzing-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D9%83%D9%88%D9%81%D8%B1-768x432.jpg">
              </a>
              <a href="https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-the-terminal-list-%d8%ac2-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/">
                <img alt="logo" src="https://media6.cimanow.cc/wp-content/uploads/2026/10/%D9%85%D8%B3%D9%84%D8%B3%D9%84-The-Terminal-List-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%AB%D8%A7%D9%86%D9%8A-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D9%84%D9%88%D8%AC%D9%88.png?width=150">
                <img alt="مسلسل The Terminal List الموسم الثاني مترجم" src="https://media6.cimanow.cc/wp-content/uploads/2026/10/%D9%85%D8%B3%D9%84%D8%B3%D9%84-The-Terminal-List-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%AB%D8%A7%D9%86%D9%8A-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D9%83%D9%88%D9%81%D8%B1-768x432.jpg">
              </a>
              <a href="https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-1917-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/">
                <img alt="logo" src="https://media6.cimanow.cc/wp-content/uploads/2020/01/1917-%D9%84%D9%88%D8%AC%D9%88.png">
                <img alt="فيلم 1917 2019 مترجم" src="https://media6.cimanow.cc/wp-content/uploads/2020/01/1917-%D9%83%D9%88%D9%81%D8%B1.jpg">
              </a>
              <a href="https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-inception-2010-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/">
                <img alt="logo" src="https://media6.cimanow.cc/wp-content/uploads/2020/01/inception-%D9%84%D9%88%D8%AC%D9%88.png">
                <img alt="" src="https://media6.cimanow.cc/wp-content/uploads/2020/01/inception-%D9%83%D9%88%D9%81%D8%B1.jpg">
              </a>
            </section>
        """.trimIndent()
        val api = CimaNow()
        val relDoc = org.jsoup.Jsoup.parse(relatedHtml)
        val parsedRecs = relDoc.select("#related a[href]").mapNotNull { api.parseCardAnchor(it) }
        assertEquals(4, parsedRecs.size)
        assertEquals("Tenzing", parsedRecs[0].name)
        assertTrue(parsedRecs[0].posterUrl!!.contains("%D9%83%D9%88%D9%81%D8%B1"))
        assertFalse(parsedRecs[0].posterUrl!!.contains("%D9%84%D9%88%D8%AC%D9%88"))
        assertEquals("The Terminal List الموسم الثاني", parsedRecs[1].name)
        assertTrue(parsedRecs[1].posterUrl!!.contains("%D9%83%D9%88%D9%81%D8%B1"))
        assertEquals("1917", parsedRecs[2].name)
        assertEquals("inception", parsedRecs[3].name.lowercase())
        assertTrue(parsedRecs.none { it.name.equals("logo", ignoreCase = true) })
    }

    @Test
    fun testLiveDiagnosticDump(): Unit = runBlocking {
        System.setProperty("kotlinx.coroutines.debug", "off")
        val api = CimaNow()
        val fm = api.getMainPage(1, MainPageRequest("أفلام أجنبية", "category/افلام-اجنبية", false))
        val firstCard = fm.items.firstOrNull()?.list?.firstOrNull() ?: return@runBlocking
        val detailPage = CimaNowNetwork.getPage(firstCard.url, "${CimaNowNetwork.mainUrl}/home/") ?: return@runBlocking
        val shine = CimaNowExtractors.extractShineUrlFromDetailHtml(detailPage.html) ?: return@runBlocking
        println("[DIAG] Movie=${firstCard.name} url=${detailPage.finalUrl} shine=$shine")

        run {
            val proxySpec = "direct"
            println("[DIAG] Running direct 10Gbps gateway mint")

            // Use real Chromium via CDP under xvfb-run (mirrors Android WebView + MotionEvent!)
            val cdpScript = """
                const { spawn } = require('child_process');
                const http = require('http');

                const proxyArg = '${proxySpec}';
                const shineUrl = '${shine}';
                const refererUrl = '${detailPage.finalUrl}';

                const fs = require('fs');
                const chromeArgs = [
                    '--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage',
                    '--no-first-run', '--no-default-browser-check',
                    '--user-data-dir=/tmp/chrome_prof_' + process.pid,
                    '--disable-blink-features=AutomationControlled',
                    '--window-size=412,915',
                    // direct 10Gbps first!
                    '--remote-debugging-port=9229',
                    'about:blank'
                ];
                const useXvfb = fs.existsSync('/usr/bin/xvfb-run');
                const chrome = useXvfb
                    ? spawn('/usr/bin/xvfb-run', ['-a', 'google-chrome-stable', ...chromeArgs], { stdio: 'ignore' })
                    : spawn('google-chrome-stable', ['--headless=new', ...chromeArgs], { stdio: 'ignore' });

                function httpGetJson(url) {
                    return new Promise((resolve, reject) => {
                        http.get(url, res => {
                            let d = ''; res.on('data', c => d += c);
                            res.on('end', () => { try { resolve(JSON.parse(d)); } catch(e) { reject(e); } });
                        }).on('error', reject);
                    });
                }

                (async () => {
                    let targets = null;
                    for (let i = 0; i < 30; i++) {
                        try { targets = await httpGetJson('http://127.0.0.1:9229/json'); if (targets.length) break; } catch(e) {}
                        await new Promise(r => setTimeout(r, 400));
                    }
                    if (!targets || !targets.length) { console.log(JSON.stringify({ err: 'no_cdp_target' })); chrome.kill('SIGKILL'); process.exit(0); }
                    const pageTarget = targets.find(t => t.type === 'page') || targets[0];
                    const ws = new WebSocket(pageTarget.webSocketDebuggerUrl);
                    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

                    let msgId = 1;
                    const pending = new Map();
                    const logs = [];
                    let mintedUrl = '';
                    let getLinkReqId = '';
                    let getLinkBody = '';
                    let watchReqId = '';
                    let rawWatchNetworkBody = '';
                    let watchHtml = '';

                    ws.onmessage = ev => {
                        const m = JSON.parse(ev.data);
                        if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
                        if (m.method === 'Network.requestWillBeSent') {
                            const u = m.params.request.url;
                            if (u.includes('get-link') || u.includes('/watching/')) {
                                logs.push('NET_REQ ' + m.params.request.method + ' ' + u + ' post=' + (m.params.request.postData || '').slice(0, 180));
                            }
                            if (u.includes('get-link.php')) getLinkReqId = m.params.requestId;
                            if (u.includes('/watching/') && u.includes('token=')) {
                                mintedUrl = u;
                                watchReqId = m.params.requestId;
                            }
                        }
                        if (m.method === 'Network.responseReceived') {
                            const u = m.params.response.url;
                            if (u.includes('get-link') || u.includes('/watching/')) {
                                logs.push('NET_RES ' + m.params.response.status + ' ' + u);
                            }
                        }
                    };
                    function send(method, params = {}) {
                        return new Promise(resolve => {
                            const id = msgId++;
                            pending.set(id, resolve);
                            ws.send(JSON.stringify({ id, method, params }));
                        });
                    }
                    async function evalJs(expression) {
                        const r = await send('Runtime.evaluate', { expression, returnByValue: true });
                        return r && r.result && r.result.result ? r.result.result.value : null;
                    }
                    async function realTap(x, y) {
                        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
                        await new Promise(r => setTimeout(r, 60));
                        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
                    }

                    await send('Page.enable');
                    await send('Network.enable');
                    await send('Page.addScriptToEvaluateOnNewDocument', {
                        source: "Object.defineProperty(navigator, 'webdriver', { get: () => false });"
                    });
                                        await send('Page.navigate', { url: shineUrl, referrer: refererUrl });

                    let mintedAtSec = -1;
                    for (let sec = 0; sec < 55; sec++) {
                        await new Promise(r => setTimeout(r, 1000));
                        const curUrl = await evalJs('location.href');
                        if (sec % 4 === 0) logs.push('t=' + sec + 's url=' + curUrl);

                        if (curUrl && curUrl.includes('/watching/')) {
                            mintedUrl = curUrl;
                            // Wait 2.5s for /watching/ vault JS to finish decrypting DOM too!
                            await new Promise(r => setTimeout(r, 3000));
                            if (watchReqId) {
                                const rb = await send('Network.getResponseBody', { requestId: watchReqId });
                                if (rb && rb.result && rb.result.body) {
                                    rawWatchNetworkBody = rb.result.base64Encoded ? Buffer.from(rb.result.body, 'base64').toString('utf8') : rb.result.body;
                                }
                            }
                            const idlDump = await evalJs('JSON.stringify({ tk: window.tk || "", iframes: Array.from(document.querySelectorAll("iframe")).map(function(f){ return { srcProp: f.src, cnb: f.getAttribute("data-cnb") }; }), dls: Array.from(document.querySelectorAll("a")).map(function(a){ return { txt: (a.textContent || "").trim().slice(0, 40), hrefProp: a.href }; }).filter(function(x){ return x.hrefProp && x.hrefProp.indexOf("http") === 0 && x.hrefProp.indexOf("cimanow.cc") === -1 && x.hrefProp.indexOf("facebook") === -1 && x.hrefProp.indexOf("twitter") === -1 && x.hrefProp.indexOf("telegram") === -1; }).slice(0, 25) })');
                            logs.push('IDL_DUMP=' + (idlDump || '').slice(0, 4000));
                            const domHtml = await evalJs('document.documentElement.outerHTML');
                            watchHtml = domHtml || '';
                            require('fs').writeFileSync('/tmp/raw_watch.html', rawWatchNetworkBody || '', 'utf8');
                            require('fs').writeFileSync('/tmp/dom_watch.html', watchHtml || '', 'utf8');
                            break;
                        }

                        if (getLinkReqId && !getLinkBody) {
                            const glb = await send('Network.getResponseBody', { requestId: getLinkReqId });
                            if (glb && glb.result && glb.result.body) {
                                getLinkBody = glb.result.base64Encoded ? Buffer.from(glb.result.body, 'base64').toString('utf8') : glb.result.body;
                                logs.push('GET_LINK_BODY=' + getLinkBody.slice(0, 300));
                                try {
                                    const parsed = JSON.parse(getLinkBody);
                                    if (parsed && parsed.url) {
                                        mintedUrl = parsed.url;
                                        if (mintedAtSec < 0) mintedAtSec = sec;
                                    }
                                } catch(e) {}
                            }
                        }

                        // Scroll into viewport first (and reveal if #watch-ready), then read centered coordinates for real trusted tap!
                        const state = await evalJs(`(function(){
                            var ts = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                            if (ts && ts.getBoundingClientRect().width > 20) ts.scrollIntoView({block: 'center'});
                            var tsRect = ts ? ts.getBoundingClientRect() : null;
                            var btn = document.getElementById('downloadbtn');
                            var btnHref = btn ? (btn.getAttribute('href') || '') : '';
                            if (btn && btnHref === '#watch-ready') {
                                btn.removeAttribute('hidden');
                                btn.style.setProperty('display', 'inline-block', 'important');
                            }
                            var btnVis = btn && !btn.hidden && getComputedStyle(btn).display !== 'none';
                            if (btnVis) btn.scrollIntoView({block: 'center'});
                            var btnRect = btnVis ? btn.getBoundingClientRect() : null;
                            return {
                                btnHref: btnHref,
                                btnVis: !!btnVis,
                                ts: tsRect && tsRect.width > 20 && tsRect.top >= 0 ? { x: Math.round(tsRect.left + 28), y: Math.round(tsRect.top + tsRect.height / 2) } : null,
                                btn: btnRect && btnRect.width > 10 && btnRect.top >= 0 ? { x: Math.round(btnRect.left + btnRect.width / 2), y: Math.round(btnRect.top + btnRect.height / 2) } : null
                            };
                        })()`);
                        if (sec >= 9) logs.push('t=' + sec + ' state=' + JSON.stringify(state));
                        if (state && state.ts) {
                            await realTap(state.ts.x, state.ts.y);
                        }
                        if (state && state.btn && (state.btnHref === '#watch-ready' || sec >= 11)) {
                            logs.push('Tapping #downloadbtn at ' + JSON.stringify(state.btn));
                            await realTap(state.btn.x, state.btn.y);
                        }
                        if (mintedUrl) {
                            logs.push('Minted URL captured from get-link.php, breaking immediately!');
                            break;
                        }
                    }

                    const cookiesRes = await send('Network.getAllCookies');
                    const cookies = (cookiesRes.result && cookiesRes.result.cookies) || [];
                    chrome.kill('SIGKILL');
                    console.log(JSON.stringify({ logs, mintedUrl, renderedDomLen: watchHtml.length, renderedDomSample: watchHtml.slice(0, 3000), cookies: cookies.map(c => ({ name: c.name, value: c.value, domain: c.domain })), getLinkBody }));
                    process.exit(0);
                })().catch(e => { console.log(JSON.stringify({ err: String(e) })); chrome.kill('SIGKILL'); process.exit(0); });
            """.trimIndent()

            java.io.File("/tmp/cdp_gw.js").writeText(cdpScript, Charsets.UTF_8)
            val proc = ProcessBuilder("node", "/tmp/cdp_gw.js").redirectErrorStream(true).start()
            val cdpOut = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val cdpJson = runCatching { org.json.JSONObject(cdpOut.trim().lines().last()) }.getOrNull()
            if (cdpJson == null) {
                println("[DIAG] Raw cdpOut:\n${cdpOut.take(4000)}")
                return@run "cdp_parse_err"
            }
            println("[DIAG] CDP logs:\n" + (cdpJson.optJSONArray("logs")?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } } ?: ""))
            val mintedUrl = cdpJson.optString("mintedUrl")
            val renderedDomLen = cdpJson.optInt("renderedDomLen")
            println("[DIAG] CDP mintedUrl=$mintedUrl renderedDomLen=$renderedDomLen cookies=${cdpJson.optJSONArray("cookies")}")
            var rawFileHtml = java.io.File("/tmp/raw_watch.html").takeIf { it.exists() }?.readText().orEmpty()
            val domFileHtml = java.io.File("/tmp/dom_watch.html").takeIf { it.exists() }?.readText().orEmpty()
            if (rawFileHtml.length < 1000 || CimaNowNetwork.isCimaBlocked(200, rawFileHtml)) {
                val fallbackPage = CimaNowNetwork.getPage(mintedUrl, "https://rm.freex2line.online/2020/02/blog-post.html/")
                if (fallbackPage != null) rawFileHtml = fallbackPage.html
                println("[DIAG] Fetched mintedUrl via CimaNowNetwork.getPage -> len=${rawFileHtml.length} finalUrl=${fallbackPage?.finalUrl}")
            }
            println("[DIAG] Saved raw_watch.html len=${rawFileHtml.length}, dom_watch.html len=${domFileHtml.length}")

            if (rawFileHtml.length > 500) {
                val rawDoc = org.jsoup.Jsoup.parse(rawFileHtml)
                rawDoc.select("script").forEachIndexed { idx, s ->
                    val attrs = s.attributes().joinToString(",") { "${it.key}(${it.value.length})" }
                    println("[DIAG] RAW_WATCH_SCRIPT[$idx] id='${s.id()}' attrs=[$attrs] dataLen=${s.data().length} FULL_DATA='${s.data().replace('\n', ' ')}'")
                }
                // Run the bootstrap script (RAW_WATCH_SCRIPT[1]) in Rhino to capture unpacked s2 cleanly!
                val bootScriptEl = rawDoc.select("script").firstOrNull { s -> s.data().length in 100..5000 && s.attributes().any { a -> a.value.length > 1000 } }
                if (bootScriptEl != null) {
                    val bootId = bootScriptEl.id()
                    val attrEntry = bootScriptEl.attributes().first { it.value.length > 1000 }
                    val capturedScripts = mutableListOf<String>()
                    val cx = org.mozilla.javascript.Context.enter()
                    try {
                        cx.languageVersion = org.mozilla.javascript.Context.VERSION_ECMASCRIPT
                        cx.isInterpretedMode = true
                        val scope = cx.initStandardObjects()
                        val bridge = object {
                            fun getAttr(id: String, name: String): String =
                                if (id == bootId && name.equals(attrEntry.key, ignoreCase = true)) attrEntry.value else ""
                            fun b64(s: String): String = String(CimaNowCrypto.b64Decode(s), Charsets.UTF_8)
                            fun capture(code: String) { capturedScripts.add(code) }
                        }
                        org.mozilla.javascript.ScriptableObject.putProperty(scope, "__b", org.mozilla.javascript.Context.javaToJS(bridge, scope))
                        val shim = """
                            var window = this, self = this;
                            function atob(s) { return String(__b.b64(String(s))); }
                            var document = {
                                getElementById: function(id) {
                                    return {
                                        getAttribute: function(n) { return String(__b.getAttr(String(id), String(n))); },
                                        remove: function() {},
                                        parentNode: { removeChild: function() {} }
                                    };
                                },
                                createElement: function(tag) {
                                    return { _t: '', set text(v) { this._t = String(v); __b.capture(this._t); }, set textContent(v) { this._t = String(v); __b.capture(this._t); }, set innerHTML(v) { this._t = String(v); __b.capture(this._t); } };
                                },
                                head: { appendChild: function(el) { if (el && el._t) __b.capture(el._t); } },
                                body: { appendChild: function(el) { if (el && el._t) __b.capture(el._t); } },
                                documentElement: { appendChild: function(el) { if (el && el._t) __b.capture(el._t); } }
                            };
                            var FunctionOrig = Function;
                            Function = function() {
                                var body = arguments[arguments.length - 1];
                                __b.capture(String(body));
                                return function() {};
                            };
                            var evalOrig = eval;
                        """.trimIndent()
                        cx.evaluateString(scope, shim, "shim.js", 1, null)
                        runCatching { cx.evaluateString(scope, bootScriptEl.data(), "boot.js", 1, null) }
                            .onFailure { println("[DIAG] Rhino boot err: ${it.message}") }
                    } finally {
                        org.mozilla.javascript.Context.exit()
                    }
                    println("[DIAG] Rhino boot captured count=${capturedScripts.size}")
                    capturedScripts.distinct().forEachIndexed { ci, cs ->
                        val gridEnd = cs.indexOf("</x-grid>")
                        if (gridEnd > 0) {
                            println("[DIAG] UNPACKED_S2 AFTER_GRID:\n${cs.substring(gridEnd, minOf(cs.length, gridEnd + 2500))}")
                        }
                        // Now test executing full UNPACKED_S2 in Rhino with generalized computeFlexHeight!
                        val tRhino0 = System.currentTimeMillis()
                        val cx2 = org.mozilla.javascript.Context.enter()
                        try {
                            cx2.languageVersion = org.mozilla.javascript.Context.VERSION_ECMASCRIPT
                            cx2.isInterpretedMode = true
                            val scope2 = cx2.initStandardObjects()
                            var decryptedDoc: org.jsoup.nodes.Document? = null
                            val bridge2 = object {
                                fun getOuterAttr(id: String, attr: String): String =
                                    rawDoc.getElementById(id)?.let { el ->
                                        el.attributes().firstOrNull { it.key.equals(attr, true) }?.value
                                    } ?: ""
                                fun hasOuterEl(id: String): Boolean = rawDoc.getElementById(id) != null
                                fun b64(s: String): String = String(CimaNowCrypto.b64Decode(s), Charsets.ISO_8859_1)
                                fun utf8FromLatin1(s: String): String = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                                fun flexHeight(html: String): Int {
                                    val cStyle = Regex("""<[A-Za-z0-9-]+\s+style=['"]([^'"]*display\s*:\s*flex[^'"]*)['"]""").find(html)?.groupValues?.get(1) ?: return 0
                                    fun px(st: String, p: String): Int = Regex("""(?:^|;)\s*${Regex.escape(p)}:(-?\d+)px""").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                    fun pairPx(st: String, p: String): Pair<Int, Int> {
                                        val m = Regex("""(?:^|;)\s*${Regex.escape(p)}:(\d+)px(?:\s+(\d+)px)?""").find(st) ?: return 0 to 0
                                        val v = m.groupValues[1].toIntOrNull() ?: 0
                                        val h = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: v
                                        return v to h
                                    }
                                    val fxW = px(cStyle, "width")
                                    val (fxPadV, fxPadH) = pairPx(cStyle, "padding")
                                    val colGap = px(cStyle, "column-gap")
                                    val rowGap = px(cStyle, "row-gap")
                                    val availW = fxW - 2 * fxPadH
                                    data class FItem(val order: Int, val idx: Int, val w: Int, val h: Int)
                                    val items = ArrayList<FItem>()
                                    val afterContainer = html.substringAfter('>')
                                    var dIdx = 0
                                    for (m in Regex("""<[A-Za-z0-9-]+\s+style=['"]([^'"]+)['"]""").findAll(afterContainer)) {
                                        val st = m.groupValues[1]
                                        dIdx++
                                        if (st.contains("display:none")) continue
                                        val ord = Regex("""order:(-?\d+)""").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                        val bb = st.contains("box-sizing:border-box")
                                        val w = px(st, "width")
                                        val h = px(st, "height")
                                        val (pv, ph) = pairPx(st, "padding")
                                        val (mv, mh) = pairPx(st, "margin")
                                        val bw = if (bb) w else w + 2 * ph
                                        val bh = if (bb) h else h + 2 * pv
                                        items.add(FItem(ord, dIdx, bw + 2 * mh, bh + 2 * mv))
                                    }
                                    items.sortWith(compareBy<FItem> { it.order }.thenBy { it.idx })
                                    val rows = ArrayList<Int>()
                                    var cw = 0; var ch = 0; var cnt = 0
                                    for (it in items) {
                                        val need = if (cnt == 0) it.w else cw + colGap + it.w
                                        if (cnt > 0 && need > availW) { rows.add(ch); cw = it.w; ch = it.h; cnt = 1 }
                                        else { cw = need; if (it.h > ch) ch = it.h; cnt++ }
                                    }
                                    if (cnt > 0) rows.add(ch)
                                    return 2 * fxPadV + rows.sum() + maxOf(0, rows.size - 1) * rowGap
                                }
                                fun onDocWrite(html: String) {
                                    decryptedDoc = org.jsoup.Jsoup.parse(html)
                                }
                                fun qsaCount(sel: String): Int = decryptedDoc?.select(sel)?.size ?: 0
                                fun qsaGetAttr(sel: String, idx: Int, attr: String): String =
                                    decryptedDoc?.select(sel)?.getOrNull(idx)?.let { el ->
                                        el.attributes().firstOrNull { it.key.equals(attr, true) }?.value
                                    } ?: ""
                                fun qsaSetAttr(sel: String, idx: Int, attr: String, v: String) {
                                    decryptedDoc?.select(sel)?.getOrNull(idx)?.attr(attr, v)
                                }
                                fun qsaRemoveAttr(sel: String, idx: Int, attr: String) {
                                    decryptedDoc?.select(sel)?.getOrNull(idx)?.let { el ->
                                        val realKey = el.attributes().firstOrNull { it.key.equals(attr, true) }?.key ?: attr
                                        el.removeAttr(realKey)
                                    }
                                }
                            }
                            org.mozilla.javascript.ScriptableObject.putProperty(scope2, "__h", org.mozilla.javascript.Context.javaToJS(bridge2, scope2))
                            val s2Shim = """
                                var window = this, self = this, globalThis = this;
                                function setTimeout() { return 1; }
                                function setInterval() { return 1; }
                                function clearTimeout() {}
                                function clearInterval() {}
                                var navigator = { webdriver: false, userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36' };
                                function atob(s) { return String(__h.b64(String(s))); }
                                function TextDecoder() {}
                                TextDecoder.prototype.decode = function(u8) {
                                    var CHUNK = 8192, parts = [];
                                    for (var i = 0; i < u8.length; i += CHUNK) {
                                        var sub = u8.subarray ? u8.subarray(i, Math.min(i + CHUNK, u8.length)) : Array.prototype.slice.call(u8, i, i + CHUNK);
                                        parts.push(String.fromCharCode.apply(null, sub));
                                    }
                                    return String(__h.utf8FromLatin1(parts.join('')));
                                };
                                window.TextDecoder = TextDecoder;
                                function XMLHttpRequest() { this._url = ''; }
                                XMLHttpRequest.prototype.open = function(m, u) { this._url = String(u); };
                                XMLHttpRequest.prototype.send = function() {};
                                window.XMLHttpRequest = XMLHttpRequest;
                                function makeStyleObj() {
                                    return {
                                        _css: '',
                                        set cssText(v) { this._css = String(v); },
                                        get cssText() { return this._css; },
                                        setProperty: function() {}
                                    };
                                }
                                function makeFlexChild(h) {
                                    return {
                                        offsetHeight: h, clientHeight: h, scrollHeight: h, offsetWidth: 403,
                                        style: makeStyleObj(),
                                        getBoundingClientRect: function() { return { height: h, width: 403, top: 0, bottom: h, left: 0, right: 403 }; }
                                    };
                                }
                                function makeMockEl(tag, id) {
                                    return {
                                        tagName: String(tag || 'DIV').toUpperCase(),
                                        id: String(id || ''),
                                        style: makeStyleObj(),
                                        _ih: '',
                                        _fh: 0,
                                        parentNode: { removeChild: function() {}, insertBefore: function() {} },
                                        appendChild: function(c) { return c; },
                                        removeChild: function(c) { return c; },
                                        set innerHTML(v) {
                                            this._ih = String(v);
                                            if (this._ih.indexOf('display:flex') !== -1) {
                                                this._fh = Number(__h.flexHeight(this._ih));
                                            }
                                        },
                                        get innerHTML() { return this._ih; },
                                        get offsetHeight() { return this._fh; },
                                        get clientHeight() { return this._fh; },
                                        get firstChild() { return makeFlexChild(this._fh); },
                                        get firstElementChild() { return makeFlexChild(this._fh); },
                                        querySelector: function() { return makeFlexChild(this._fh); },
                                        getBoundingClientRect: function() { return { height: this._fh, width: 403, top: 0, bottom: this._fh, left: 0, right: 403 }; },
                                        getAttribute: function(n) { return String(__h.getOuterAttr(this.id, String(n))); },
                                        setAttribute: function() {},
                                        removeAttribute: function() {}
                                    };
                                }
                                window.getComputedStyle = function(el) {
                                    var css = (el && el.style && el.style._css) ? el.style._css : '';
                                    var res = { height: (el && el._fh ? el._fh : 0) + 'px' };
                                    var re = /([a-z-]+)\s*:\s*([^;!]+)/gi, m;
                                    while ((m = re.exec(css)) !== null) { res[m[1].trim()] = m[2].trim(); }
                                    return res;
                                };
                                function Document() {}
                                Document.prototype.write = function(html) { __h.onDocWrite(String(html)); };
                                var document = new Document();
                                document.documentElement = makeMockEl('HTML', '');
                                document.body = makeMockEl('BODY', '');
                                document.addEventListener = function() {};
                                document.createDocumentFragment = function() { return makeMockEl('FRAG', ''); };
                                document.createElement = function(tag) { return makeMockEl(tag, ''); };
                                document.getElementById = function(id) {
                                    var sid = String(id);
                                    if (__h.hasOuterEl(sid)) return makeMockEl('SCRIPT', sid);
                                    return null;
                                };
                                document.querySelectorAll = function(sel) {
                                    var s = String(sel);
                                    var n = Number(__h.qsaCount(s));
                                    var arr = [];
                                    for (var i = 0; i < n; i++) {
                                        (function(idx) {
                                            arr.push({
                                                getAttribute: function(a) { return String(__h.qsaGetAttr(s, idx, String(a))); },
                                                setAttribute: function(a, v) { __h.qsaSetAttr(s, idx, String(a), String(v)); },
                                                removeAttribute: function(a) { __h.qsaRemoveAttr(s, idx, String(a)); }
                                            });
                                        })(i);
                                    }
                                    return arr;
                                };
                            """.trimIndent()
                            cx2.evaluateString(scope2, s2Shim, "s2shim.js", 1, null)
                            cx2.evaluateString(scope2, cs, "unpacked_s2.js", 1, null)
                            val rhinoMs = System.currentTimeMillis() - tRhino0
                            println("[DIAG] Rhino S2 executed in ${rhinoMs}ms! decryptedDocNull=${decryptedDoc == null}")
                            val dDoc = decryptedDoc
                            if (dDoc != null) {
                                // Print scripts inside decryptedDoc (like #vault_script and cimanowtv rewriter!)
                                dDoc.select("script").forEachIndexed { si, sc ->
                                    val data = sc.data().trim()
                                    if (data.isNotEmpty() && data.length < 4000) {
                                        println("[DIAG] DECRYPTED_SCRIPT[$si] id='${sc.id()}' len=${data.length}: ${data.replace('\n', ' ').take(600)}")
                                    }
                                }
                                // Execute #vault_script (or any small script calling window['_...']) in scope2!
                                for (sc in dDoc.select("script")) {
                                    val code = sc.data()
                                    if (sc.id() == "vault_script" || (code.length in 10..2000 && code.contains("window["))) {
                                        runCatching { cx2.evaluateString(scope2, code, "vault_inline.js", 1, null) }
                                    }
                                }
                                val tkVal = cx2.evaluateString(scope2, "String(window.tk || '')", "tk.js", 1, null)?.toString().orEmpty()
                                val tabs = dDoc.select("li[data-id][data-index]").map { "${it.text().trim()}:${it.attr("data-id")}@${it.attr("data-index")}" }
                                val dls = dDoc.select("#download a[href], li[aria-label=download] a[href], li[aria-label=quality] a[href], a[href*=.mp4]").map { "${it.text().trim().take(35)} -> ${it.attr("href")}" }
                                println("[DIAG] UNLOCKED tkLen=${tkVal.length} tk='${tkVal.take(32)}' tabs=$tabs")
                                println("[DIAG] UNLOCKED downloads (${dls.size}):\n" + dls.joinToString("\n"))

                                // Test signing & switching ALL server tabs via scope2 XMLHttpRequest + __cnBootFrame*!!
                                val watchOrigin = java.net.URI(mintedUrl).let { "${it.scheme}://${it.host}" }
                                for (li in dDoc.select("li[data-id][data-index]")) {
                                    val tName = li.text().trim()
                                    val dId = li.attr("data-id")
                                    val dIdx = li.attr("data-index")
                                    val rawCore = "$watchOrigin/wp-content/themes/Cima%20Now%20New/core.php?token=${java.net.URLEncoder.encode(tkVal, "UTF-8")}&action=switch&index=${java.net.URLEncoder.encode(dIdx, "UTF-8")}&id=${java.net.URLEncoder.encode(dId, "UTF-8")}"
                                    org.mozilla.javascript.ScriptableObject.putProperty(scope2, "__rawCore", rawCore)
                                    val signedCore = cx2.evaluateString(scope2, "(function(){ var x = new XMLHttpRequest(); x.open('GET', String(__rawCore)); return x._url; })()", "sign.js", 1, null).toString()
                                    val cResp = CimaNowNetwork.getPage(signedCore, mintedUrl)
                                    // Decrypt iframe via window.__cnBootFrame* in scope2!
                                    val ifDoc = org.jsoup.Jsoup.parse(cResp?.html.orEmpty())
                                    val ifEl = ifDoc.selectFirst("iframe")
                                    var resolvedSrc = ifEl?.attr("src").orEmpty()
                                    if (ifEl != null && (resolvedSrc.isBlank() || resolvedSrc.startsWith("javascript:"))) {
                                        val ifBridge = object {
                                            var outSrc: String = ""
                                            fun getAttr(k: String): String? = ifEl.attributes().firstOrNull { it.key.equals(k, true) }?.value
                                        }
                                        org.mozilla.javascript.ScriptableObject.putProperty(scope2, "__ifb", org.mozilla.javascript.Context.javaToJS(ifBridge, scope2))
                                        cx2.evaluateString(scope2, """
                                            (function(){
                                                var fakeF = {
                                                    src: '',
                                                    getAttribute: function(k) { var v = __ifb.getAttr(String(k)); return v === null ? null : String(v); },
                                                    removeAttribute: function() {}
                                                };
                                                for (var k in window) {
                                                    if (k.indexOf('__cnBootFrame') === 0 && typeof window[k] === 'function') {
                                                        try { window[k](fakeF); } catch(e) {}
                                                    }
                                                }
                                                __ifb.outSrc = String(fakeF.src || '');
                                            })();
                                        """.trimIndent(), "bootf.js", 1, null)
                                        resolvedSrc = ifBridge.outSrc
                                    }
                                    println("[DIAG] LIVE TAB '$tName' (id=$dId, idx=$dIdx) -> embed='$resolvedSrc' (signed='${signedCore.takeLast(35)}')")
                                }
                            }
                        } catch (e: Throwable) {
                            println("[DIAG] Rhino S2 ERR: ${e.javaClass.simpleName}: ${e.message}")
                        } finally {
                            org.mozilla.javascript.Context.exit()
                        }
                    }
                }
            }

            if (domFileHtml.length > 500) {
                val domDoc = org.jsoup.Jsoup.parse(domFileHtml)
                val iframes = domDoc.select("iframe").map { "id='${it.id()}' src='${it.attr("src")}' cnk='${it.attr("data-cnk").take(24)}' cna='${it.attr("data-cna").take(40)}' html='${it.outerHtml().take(220)}'" }
                val tabs = domDoc.select("li[data-id], #watch li, ul.tabs li").map { "txt='${it.text()}' id='${it.attr("data-id")}' idx='${it.attr("data-index")}' cls='${it.className()}'" }
                val dls = domDoc.select("#download a[href], a[href*=.mp4], a[href*=down]").take(20).map { "txt='${it.text().take(40)}' href='${it.attr("href").take(140)}'" }
                val scriptsInDom = domDoc.select("script").mapIndexed { idx, s -> "[$idx] id='${s.id()}' len=${s.data().length} head='${s.data().take(220).replace('\n', ' ')}'" }
                println("[DIAG] DOM_WATCH iframes (${iframes.size}):\n" + iframes.joinToString("\n"))
                println("[DIAG] DOM_WATCH tabs (${tabs.size}):\n" + tabs.joinToString("\n"))
                println("[DIAG] DOM_WATCH dls (${dls.size}):\n" + dls.joinToString("\n"))
                println("[DIAG] DOM_WATCH scripts (${scriptsInDom.size}):\n" + scriptsInDom.joinToString("\n"))
                val vaultScript = domDoc.getElementById("vault_script")?.data().orEmpty()
                if (vaultScript.isNotEmpty()) {
                    println("[DIAG] DOM_WATCH #vault_script (len=${vaultScript.length}):\n${vaultScript.take(8000)}")
                }
            }

            if (mintedUrl.startsWith("http")) "done" else null
        }
        Unit
    }

    @Test
    fun testFullLiveEndToEndSuite() = runBlocking {
        assumeTrue("Skip full suite during quick diag", System.getenv("CIMANOW_FULL_E2E") == "1")

        val api = CimaNow()

        // ── 1. Verify Catalogs & Pagination ──────────────────────────────────
        val shelvesToCheck = listOf(
            MainPageRequest("أحدث الإضافات", "الاحدث", false),
            MainPageRequest("الأكثر مشاهدة", "الاكثر-مشاهدة", false),
            MainPageRequest("أفلام أجنبية", "category/افلام-اجنبية", false),
            MainPageRequest("أفلام عربية", "category/افلام-عربية", false),
            MainPageRequest("مسلسلات أجنبية", "category/مسلسلات-اجنبية", false),
            MainPageRequest("مسلسلات رمضان 2026", "category/رمضان-2026", false),
            MainPageRequest("أرشيف مسلسلات رمضان", "category/رمضان", false),
            MainPageRequest("مسلسلات نتفليكس", "category/مسلسلات-نتفليكس", false),
            MainPageRequest("مسلسلات أنيميشن", "category/مسلسلات-انيميشن", false),
            MainPageRequest("أطفال", "category/اطفال", false),
        )

        for (req in shelvesToCheck) {
            val p1 = api.getMainPage(1, req)
            val p1Items = p1.items.firstOrNull()?.list.orEmpty()
            println("[E2E][Catalog] ${req.name} p1 -> count=${p1Items.size} hasNext=${p1.hasNext}")
            assertTrue("Expected items in shelf ${req.name}", p1Items.isNotEmpty())
            assertTrue("Expected valid posters in shelf ${req.name}", p1Items.any { !it.posterUrl.isNullOrBlank() })
        }

        // Verify page 1 vs page 2 pagination produces distinct items
        val foreignMoviesReq = MainPageRequest("أفلام أجنبية", "category/افلام-اجنبية", false)
        val fmP1 = api.getMainPage(1, foreignMoviesReq).items.first().list
        val fmP2 = api.getMainPage(2, foreignMoviesReq).items.first().list
        println("[E2E][Pagination] Foreign Movies p1=${fmP1.size}, p2=${fmP2.size}")
        assertTrue("Expected page 2 items", fmP2.isNotEmpty())
        assertFalse("Expected distinct page 2 URLs", fmP1.first().url == fmP2.first().url)

        // ── 2. Verify Search & Paginated Search ──────────────────────────────
        val lupinSearch = api.search("Lupin", 1)
        assertNotNull(lupinSearch)
        println("[E2E][Search] 'Lupin' -> count=${lupinSearch!!.items.size}")
        assertTrue("Expected Lupin search results", lupinSearch.items.isNotEmpty())

        val movieSearchP1 = api.search("فيلم", 1)!!
        val movieSearchP2 = api.search("فيلم", 2)!!
        println("[E2E][SearchPagination] 'فيلم' p1=${movieSearchP1.items.size} (hasNext=${movieSearchP1.hasNext}), p2=${movieSearchP2.items.size}")
        assertTrue(movieSearchP1.items.isNotEmpty() && movieSearchP2.items.isNotEmpty())

        // ── 3. Verify `load()` on Movies & Multi-Season Series ───────────────
        val fall2Url = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/"
        val fall2Load = api.load(fall2Url) as? MovieLoadResponse
        assertNotNull("Expected MovieLoadResponse for Fall 2", fall2Load)
        println(
            "[E2E][Load Movie] title='${fall2Load!!.name}' year=${fall2Load.year} dur=${fall2Load.duration}m " +
                "tags=${fall2Load.tags?.size ?: 0} actors=${fall2Load.actors?.size ?: 0} recs=${fall2Load.recommendations?.size ?: 0} poster=${fall2Load.posterUrl}",
        )
        assertFalse(fall2Load.posterUrl.isNullOrBlank())
        assertFalse(fall2Load.plot.isNullOrBlank())
        assertEquals(2026, fall2Load.year)
        val movieRecs = fall2Load.recommendations.orEmpty()
        assertTrue("Expected movie recommendations on Fall 2", movieRecs.isNotEmpty())
        assertTrue("Expected zero 'logo' titles in movie recommendations: ${movieRecs.map { it.name }}", movieRecs.none { it.name.equals("logo", true) || it.name.isBlank() })

        val lupinSelaryUrl = "https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin/"
        val lupinLoad = api.load(lupinSelaryUrl) as? TvSeriesLoadResponse
        assertNotNull("Expected TvSeriesLoadResponse for Lupin /selary/", lupinLoad)
        val seasonsFound = lupinLoad!!.episodes.mapNotNull { it.season }.distinct().sorted()
        println(
            "[E2E][Load Series Root] title='${lupinLoad.name}' seasons=$seasonsFound totalEpisodes=${lupinLoad.episodes.size} " +
                "plotLen=${lupinLoad.plot?.length ?: 0} actors=${lupinLoad.actors?.size ?: 0} recs=${lupinLoad.recommendations?.map { it.name }}",
        )
        assertEquals("Expected all 4 seasons [1, 2, 3, 4] on root /selary/, got $seasonsFound", listOf(1, 2, 3, 4), seasonsFound)
        assertTrue("Expected >= 15 total episodes across Lupin seasons", lupinLoad.episodes.size >= 15)
        val seriesRecs = lupinLoad.recommendations.orEmpty()
        assertTrue("Expected series recommendations on Lupin", seriesRecs.isNotEmpty())
        assertTrue("Expected zero 'logo' titles in series recommendations: ${seriesRecs.map { it.name }}", seriesRecs.none { it.name.equals("logo", true) || it.name.isBlank() })

        val lupinS4SelaryUrl = "https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/"
        val lupinS4Load = api.load(lupinS4SelaryUrl) as? TvSeriesLoadResponse
        assertNotNull("Expected TvSeriesLoadResponse for Lupin S4 /selary/", lupinS4Load)
        val s4SeasonsFound = lupinS4Load!!.episodes.mapNotNull { it.season }.distinct().sorted()
        println(
            "[E2E][Load Series S4] title='${lupinS4Load.name}' seasons=$s4SeasonsFound totalEpisodes=${lupinS4Load.episodes.size}",
        )
        assertEquals("Expected all 4 seasons [1, 2, 3, 4] on S4 /selary/, got $s4SeasonsFound", listOf(1, 2, 3, 4), s4SeasonsFound)

        // ── 4. Verify `loadLinks()` + Live Stream HTTP 200/206 Probing ───────
        CimaNowExtractors.debugLogger = { println("    [E2E][Trace] $it") }
        val movieLinks = CopyOnWriteArrayList<ExtractorLink>()
        val movieSubs = CopyOnWriteArrayList<SubtitleFile>()
        val okMovie = api.loadLinks(fall2Load.dataUrl, false, { movieSubs.add(it) }, { movieLinks.add(it) })
        val movieProviders = movieLinks.map { it.source }.distinct()
        println("[E2E][LoadLinks Movie] ok=$okMovie links=${movieLinks.size} subs=${movieSubs.size} providers=$movieProviders")
        for (lnk in movieLinks) {
            println("  -> [${lnk.source}] ${lnk.name} q=${lnk.quality} type=${lnk.type} url=${lnk.url.take(100)}")
        }
        assertTrue("Expected loadLinks=true on Fall 2", okMovie)
        assertTrue("Expected >= 5 extracted links on Fall 2, got ${movieLinks.size}", movieLinks.size >= 5)
        assertTrue("Expected >= 3 distinct providers on Fall 2, got $movieProviders", movieProviders.size >= 3)

        // Probe HTTP 200/206 reachability on at least 3 distinct providers!
        var verifiedPlayableProviders = 0
        for ((prov, group) in movieLinks.groupBy { it.source }) {
            val sample = group.first()
            val playable = probePlayableStream(sample)
            println("  [StreamProbe] provider=$prov playable=$playable url=${sample.url.take(90)}")
            if (playable) verifiedPlayableProviders++
        }
        assertTrue("Expected >= 3 live playable providers returning 200/206 media, got $verifiedPlayableProviders", verifiedPlayableProviders >= 3)

        // Also verify `loadLinks()` on a Series Episode (Lupin S4E1 / latest episode)
        val sampleEp = lupinLoad.episodes.last()
        val epLinks = CopyOnWriteArrayList<ExtractorLink>()
        val okEp = api.loadLinks(sampleEp.data, false, {}, { epLinks.add(it) })
        println("[E2E][LoadLinks Episode] ep='${sampleEp.name}' S${sampleEp.season}E${sampleEp.episode} ok=$okEp links=${epLinks.size} providers=${epLinks.map { it.source }.distinct()}")
        assertTrue("Expected loadLinks=true on series episode", okEp && epLinks.isNotEmpty())
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
