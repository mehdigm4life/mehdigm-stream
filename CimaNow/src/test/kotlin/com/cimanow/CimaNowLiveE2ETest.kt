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

        val safeFactory = object : org.mozilla.javascript.ContextFactory() {
            override fun makeContext(): org.mozilla.javascript.Context {
                val cx = super.makeContext()
                cx.instructionObserverThreshold = 500_000
                return cx
            }
            override fun observeInstructionCount(cx: org.mozilla.javascript.Context, instructionCount: Int) {
                val deadline = cx.getThreadLocal("deadline") as? Long ?: return
                if (System.currentTimeMillis() > deadline) {
                    throw RuntimeException("JS_DEADLINE_EXCEEDED")
                }
            }
        }

        CimaNowNetwork.withStickySession { session ->
            val pTag = session.proxySpec ?: "direct"
            val u = java.net.URI(CimaNowNetwork.encodeUrl(shine))
            val gwOrigin = "${u.scheme}://${u.host}"

            val hop1 = session.getNoRedirect(shine, detailPage.finalUrl)
            if (hop1 == null || hop1.code !in 200..399) return@withStickySession null
            val hop2 = session.getNoRedirect("$gwOrigin/redirectingfree/", shine)
            if (hop2 == null || hop2.code !in 200..399) return@withStickySession null

            val vaultUrl = "$gwOrigin/2020/02/blog-post.html/"
            val tFetchDone = System.currentTimeMillis()
            val hop3 = session.getText(vaultUrl, "$gwOrigin/redirectingfree/")
            val vaultHtml = hop3?.second.orEmpty()
            if (vaultHtml.length < 5000 || vaultHtml.contains("redirectingfree")) return@withStickySession null

            val vDoc = org.jsoup.Jsoup.parse(vaultHtml)
            val mainScriptEl = vDoc.selectFirst("script[id^=script_]") ?: vDoc.select("script").firstOrNull { it.data().length > 5000 }
                ?: return@withStickySession null
            val scriptId = mainScriptEl.id().ifEmpty { "script_main" }
            val scriptJs = mainScriptEl.data()

            // Stage A: Unpack outer vaultHtml -> innerHtml via Rhino
            var capturedDocWrite = ""
            var capturedFragHtml = ""
            val tRhino0 = System.currentTimeMillis()
            val cx1 = safeFactory.enterContext()
            try {
                cx1.putThreadLocal("deadline", System.currentTimeMillis() + 15_000L)
                cx1.setInterpretedMode(true)
                cx1.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
                val scope1 = cx1.initSafeStandardObjects()
                val bridge1 = object {
                    fun atob(b64: String): String = String(CimaNowCrypto.b64Decode(b64), Charsets.ISO_8859_1)
                    fun btoa(str: String): String = CimaNowCrypto.b64EncodeNoWrap(str.toByteArray(Charsets.ISO_8859_1))
                    fun utf8Decode(arr: Any?): String {
                        if (arr is org.mozilla.javascript.typedarrays.NativeUint8Array) {
                            val out = ByteArray(arr.byteLength)
                            for (idx in 0 until arr.byteLength) out[idx] = (arr.get(idx) as Number).toInt().toByte()
                            return String(out, Charsets.UTF_8)
                        }
                        return ""
                    }
                    fun onWrite(s: String) { capturedDocWrite += s }
                    fun onFrag(s: String) { capturedFragHtml += s }
                }
                org.mozilla.javascript.ScriptableObject.putProperty(scope1, "__bridge", org.mozilla.javascript.Context.javaToJS(bridge1, scope1))
                val shim1 = """
                    var window = this, self = this, globalThis = this;
                    var navigator = { userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36', webdriver: false };
                    function atob(s) { return String(__bridge.atob(String(s))); }
                    function btoa(s) { return String(__bridge.btoa(String(s))); }
                    function TextDecoder() {}
                    TextDecoder.prototype.decode = function(u8) { return String(__bridge.utf8Decode(u8)); };
                    function XMLHttpRequest() {}
                    XMLHttpRequest.prototype.send = function() {};
                    XMLHttpRequest.prototype.send.toString = function() { return 'function send() { [native code] }'; };
                    function MutationObserver() {}
                    MutationObserver.prototype.observe = function() {};
                    function setTimeout() { return 1; }
                    function setInterval() { return 1; }
                    var __elsById = {};
                    function __parsePx(css, prop) {
                        var m = new RegExp('(?:^|;|\\s)' + prop + '\\s*:\\s*([0-9.]+)px', 'i').exec(String(css || ''));
                        return m ? parseFloat(m[1]) : 0;
                    }
                    function __makeEl(tag) {
                        var el = {
                            tagName: String(tag || 'DIV').toUpperCase(), nodeType: 1, id: '', style: { cssText: '' },
                            attributes: [], childNodes: [], parentNode: null, firstChild: null, _innerHTML: '',
                            setAttribute: function(k, v) { this.attributes.push({ name: k, value: String(v) }); if (k === 'id') { this.id = String(v); __elsById[this.id] = this; } },
                            getAttribute: function(k) { for (var i=0;i<this.attributes.length;i++) if (this.attributes[i].name===k) return this.attributes[i].value; return null; },
                            hasAttribute: function(k) { return this.getAttribute(k) !== null; },
                            appendChild: function(c) { if (c) { c.parentNode = this; this.childNodes.push(c); this.firstChild = this.childNodes[0] || null; if (c.id) __elsById[c.id] = c; } return c; },
                            removeChild: function(c) { var idx = this.childNodes.indexOf(c); if (idx >= 0) this.childNodes.splice(idx, 1); this.firstChild = this.childNodes[0] || null; if (c) c.parentNode = null; return c; },
                            insertBefore: function(n) { return this.appendChild(n); },
                            replaceChild: function(n) { return this.appendChild(n); },
                            getElementsByTagName: function() { return []; },
                            addEventListener: function() {},
                            getBoundingClientRect: function() { var w = __parsePx(this.style.cssText, 'width'), h = __parsePx(this.style.cssText, 'height'); return { width: w, height: h }; }
                        };
                        Object.defineProperty(el, 'offsetWidth', { get: function() { return Math.round(__parsePx(this.style.cssText, 'width')); } });
                        Object.defineProperty(el, 'offsetHeight', { get: function() { return Math.round(__parsePx(this.style.cssText, 'height')); } });
                        Object.defineProperty(el, 'innerHTML', { get: function() { return this._innerHTML; }, set: function(v) { this._innerHTML = String(v); __bridge.onFrag(String(v)); } });
                        return el;
                    }
                    function Document() {}
                    Document.prototype.write = function(h) { __bridge.onWrite(String(h)); };
                    var document = new Document();
                    document.nodeType = 9; document.readyState = 'complete';
                    document.documentElement = __makeEl('html'); document.body = __makeEl('body');
                    document.createElement = function(t) { return __makeEl(t); };
                    document.createDocumentFragment = function() { return __makeEl('frag'); };
                    document.getElementById = function(id) { return __elsById[id] || null; };
                    document.getElementsByTagName = function() { return [document.body]; };
                    document.addEventListener = function() {};
                    var __initScript = __makeEl('script'); __initScript.id = '$scriptId'; document.body.appendChild(__initScript);
                """.trimIndent()
                cx1.evaluateString(scope1, shim1, "shim1.js", 1, null)
                cx1.evaluateString(scope1, scriptJs, "vault.js", 1, null)
            } finally {
                org.mozilla.javascript.Context.exit()
            }
            val innerHtml = capturedDocWrite.ifEmpty { capturedFragHtml }
            println("[DIAG][$pTag] StageA unpacked in ${System.currentTimeMillis() - tRhino0}ms innerLen=${innerHtml.length}")

            val innerDoc = org.jsoup.Jsoup.parse(innerHtml)
            val innerDocNoScripts = org.jsoup.Jsoup.parse(innerHtml).apply { select("script, style").remove() }
            println("[DIAG][$pTag] INNER_DOM_NO_SCRIPTS:\n${innerDocNoScripts.body().html().take(4000)}")
            innerDoc.select("script").forEachIndexed { idx, sc ->
                val d = sc.data()
                println("[DIAG][$pTag] INNER_SCRIPT_META[$idx] id='${sc.id()}' src='${sc.attr("src")}' len=${d.length} head='${d.take(260).replace("\n", " ")}'")
            }

            // Print inner_0, inner_6, inner_9 in full (they are tiny: 1254B, 287B, 5613B!)
            for (idx in listOf(0, 6, 9)) {
                val sc = innerDoc.select("script").getOrNull(idx)
                if (sc != null) println("[DIAG][$pTag] FULL_INNER_SCRIPT[$idx]:\n${sc.data()}")
            }

            // Also check Rhino VERSION_ECMASCRIPT (250) syntax error details
            val cxCheck = safeFactory.enterContext()
            try {
                cxCheck.setInterpretedMode(true)
                cxCheck.languageVersion = org.mozilla.javascript.Context.VERSION_ECMASCRIPT
                val scCheck = cxCheck.initSafeStandardObjects()
                for (idx in 7 until innerDoc.select("script").size) {
                    val code = innerDoc.select("script")[idx].data()
                    runCatching { cxCheck.compileString(code, "s_$idx.js", 1, null) }
                        .onSuccess { println("[DIAG] Rhino VERSION_ECMASCRIPT compiled s_$idx.js OK!") }
                        .onFailure { println("[DIAG] Rhino VERSION_ECMASCRIPT compile err s_$idx.js: $it") }
                }
            } finally {
                org.mozilla.javascript.Context.exit()
            }

            // Stage B via Node.js (real V8) on CI to capture exact XHR/fetch/crypto/Turnstile behavior of inner_0..14!
            java.io.File("/tmp/inner_gw.html").writeText(innerHtml, Charsets.UTF_8)
            val nodeDriver = """
                const fs = require('fs');
                const vm = require('vm');
                const cryptoNode = require('crypto');
                const html = fs.readFileSync('/tmp/inner_gw.html', 'utf8');

                // Extract scripts & DOM elements simply
                const scripts = [];
                const scriptRe = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
                let m;
                while ((m = scriptRe.exec(html)) !== null) {
                    scripts.push({ attrs: m[1], code: m[2] });
                }

                const capturedHttp = [];
                const logs = [];
                let virtNow = Date.now();

                const elsById = {};
                const allEls = [];
                const docListeners = {};

                function makeEl(tag) {
                    const el = {
                        tagName: String(tag || 'DIV').toUpperCase(), nodeType: 1, id: '', className: '',
                        style: { cssText: '', display: 'block', visibility: 'visible', opacity: '1', setProperty(k,v){this[k]=v;}, getPropertyValue(k){return this[k]||'';} },
                        dataset: {}, attributes: [], childNodes: [], children: [], parentNode: null, firstChild: null,
                        textContent: '', innerText: '', _innerHTML: '', value: '', disabled: false, hidden: false, href: '',
                        _listeners: {},
                        classList: { add(){}, remove(){}, contains(){return false;}, toggle(){} },
                        setAttribute(k, v) {
                            k = String(k); v = String(v);
                            const ex = this.attributes.find(a => a.name === k);
                            if (ex) ex.value = v; else this.attributes.push({ name: k, value: v });
                            if (k === 'id') { this.id = v; elsById[v] = this; }
                            if (k === 'class') this.className = v;
                            if (k === 'href') { this.href = v; logs.push('setAttr href #' + this.id + '=' + v); }
                            if (k.startsWith('data-')) {
                                const camel = k.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
                                this.dataset[camel] = v;
                            }
                        },
                        getAttribute(k) { const ex = this.attributes.find(a => a.name === k); return ex ? ex.value : null; },
                        hasAttribute(k) { return this.getAttribute(k) !== null; },
                        removeAttribute(k) { this.attributes = this.attributes.filter(a => a.name !== k); if (k==='hidden') this.hidden=false; },
                        appendChild(c) {
                            if (c) {
                                c.parentNode = this; this.childNodes.push(c); this.children.push(c); this.firstChild = this.childNodes[0] || null;
                                if (c.id) elsById[c.id] = c;
                                if (c.src) logs.push('appendChild script/iframe src=' + c.src + ' id=' + c.id);
                                allEls.push(c);
                            }
                            return c;
                        },
                        removeChild(c) { const i = this.childNodes.indexOf(c); if (i>=0) this.childNodes.splice(i,1); return c; },
                        insertBefore(n) { return this.appendChild(n); },
                        replaceChild(n) { return this.appendChild(n); },
                        contains() { return true; },
                        closest() { return this; },
                        getElementsByTagName(t) { t = String(t).toUpperCase(); return allEls.filter(x => t==='*' || x.tagName===t); },
                        querySelector(s) { return document.querySelector(s); },
                        querySelectorAll(s) { return document.querySelectorAll(s); },
                        addEventListener(ev, fn) { (this._listeners[ev] = this._listeners[ev] || []).push(fn); },
                        removeEventListener() {},
                        click() {
                            const evObj = { isTrusted: true, target: this, currentTarget: this, preventDefault(){}, stopPropagation(){}, stopImmediatePropagation(){} };
                            for (const fn of (this._listeners['click'] || [])) { try { fn.call(this, evObj); } catch(e) { logs.push('clickFnErr:'+e.message); } }
                            if (typeof this.onclick === 'function') { try { this.onclick(evObj); } catch(e) { logs.push('onclickErr:'+e.message); } }
                        },
                        getBoundingClientRect() { return { width: 220, height: 48, top: 100, left: 50, right: 270, bottom: 148 }; }
                    };
                    Object.defineProperty(el, 'offsetWidth', { get: () => 220 });
                    Object.defineProperty(el, 'offsetHeight', { get: () => 48 });
                    Object.defineProperty(el, 'clientWidth', { get: () => 220 });
                    Object.defineProperty(el, 'clientHeight', { get: () => 48 });
                    Object.defineProperty(el, 'offsetParent', { get: () => document.body });
                    Object.defineProperty(el, 'innerHTML', { get() { return this._innerHTML; }, set(v) { this._innerHTML = String(v); } });
                    return el;
                }

                const document = {
                    nodeType: 9, readyState: 'complete', hidden: false, visibilityState: 'visible',
                    cookie: '', referrer: '${gwOrigin}/redirectingfree/', domain: '${u.host}',
                    documentElement: makeEl('HTML'), head: makeEl('HEAD'), body: makeEl('BODY'),
                    createElement: t => makeEl(t),
                    createTextNode: t => { const n = makeEl('TEXT'); n.nodeType = 3; n.textContent = String(t); return n; },
                    createDocumentFragment: () => makeEl('FRAG'),
                    getElementById: id => elsById[String(id)] || null,
                    getElementsByTagName: t => { t = String(t).toUpperCase(); return allEls.filter(x => t==='*' || x.tagName===t); },
                    getElementsByClassName: c => allEls.filter(x => (' '+x.className+' ').includes(' '+c+' ')),
                    querySelectorAll(sel) {
                        sel = String(sel).trim();
                        if (sel.startsWith('#') && !sel.includes(' ') && !sel.includes(',')) { const e = elsById[sel.slice(1)]; return e ? [e] : []; }
                        if (sel.startsWith('.') && !sel.includes(' ')) return document.getElementsByClassName(sel.slice(1));
                        return document.getElementsByTagName(sel);
                    },
                    querySelector(sel) { const a = this.querySelectorAll(sel); return a.length ? a[0] : null; },
                    addEventListener(ev, fn) { (docListeners[ev] = docListeners[ev] || []).push(fn); },
                    removeEventListener() {}
                };

                // Populate key elements from innerHtml
                const dlMatch = html.match(/<a\s+id="downloadbtn"[^>]*>/i);
                const dlBtn = makeEl('A');
                dlBtn.setAttribute('id', 'downloadbtn');
                dlBtn.setAttribute('class', 'downloadbtn');
                dlBtn.setAttribute('href', 'javascript:void(0);');
                if (dlMatch) {
                    const attrRe = /([a-zA-Z0-9_-]+)="([^"]*)"/g;
                    let am;
                    while ((am = attrRe.exec(dlMatch[0])) !== null) dlBtn.setAttribute(am[1], am[2]);
                }
                document.body.appendChild(dlBtn);
                const cdDiv = makeEl('DIV'); cdDiv.setAttribute('id', 'countdown'); document.body.appendChild(cdDiv);
                const h3 = makeEl('H3'); h3.setAttribute('class', 'text_sco'); document.body.appendChild(h3);
                const ad1 = makeEl('A'); ad1.setAttribute('id', 'xqeqjp'); document.body.appendChild(ad1);
                const ad2 = makeEl('A'); ad2.setAttribute('id', 'xqeqjp1'); document.body.appendChild(ad2);

                const timers = [];
                let nextTid = 1;
                function jq(sel) {
                    const els = typeof sel === 'string' ? document.querySelectorAll(sel) : [sel];
                    return {
                        ready(fn) { setTimeout(fn, 0); return this; },
                        click(fn) { if (fn) els.forEach(e => e && e.addEventListener && e.addEventListener('click', fn)); else els.forEach(e => e && e.click && e.click()); return this; },
                        hide() { return this; }, show() { return this; }, fadeIn() { return this; }, fadeOut() { return this; },
                        attr(k, v) { if (v === undefined) return els[0] ? els[0].getAttribute(k) : null; els.forEach(e => e && e.setAttribute(k, v)); return this; },
                        removeAttr(k) { els.forEach(e => e && e.removeAttribute(k)); return this; },
                        text() { return this; }, html() { return this; }, css() { return this; }, on(ev, fn) { els.forEach(e => e && e.addEventListener && e.addEventListener(ev, fn)); return this; },
                        countdown360(opts) { if (opts && typeof opts.onComplete === 'function') setTimeout(opts.onComplete, (opts.seconds || 10) * 1000); return { start(){} }; }
                    };
                }
                jq.ajax = function(opts) {
                    logs.push('jq.ajax url=' + opts.url + ' type=' + opts.type + ' data=' + JSON.stringify(opts.data));
                    capturedHttp.push({ kind: 'ajax', method: opts.type || 'POST', url: opts.url || '', headers: opts.headers || {}, body: typeof opts.data === 'string' ? opts.data : new URLSearchParams(opts.data || {}).toString() });
                };
                jq.post = function(url, data) { jq.ajax({ url, type: 'POST', data }); };

                function XHR() { this._h = {}; this._m = 'GET'; this._u = ''; this.readyState = 0; this.status = 200; this.responseText = '{"ok":true,"url":"https://cimanow.cc/watching/?token=test"}'; }
                XHR.prototype.open = function(m, u) { this._m = String(m); this._u = String(u); this.readyState = 1; };
                XHR.prototype.setRequestHeader = function(k, v) { this._h[String(k)] = String(v); };
                XHR.prototype.addEventListener = function(ev, fn) { this['on' + ev] = fn; };
                XHR.prototype.send = function(body) {
                    const bStr = body == null ? '' : (body instanceof URLSearchParams ? body.toString() : String(body));
                    logs.push('XHR ' + this._m + ' ' + this._u + ' hdr=' + JSON.stringify(this._h) + ' body=' + bStr);
                    capturedHttp.push({ kind: 'xhr', method: this._m, url: this._u, headers: this._h, body: bStr });
                    this.readyState = 4;
                    if (typeof this.onload === 'function') this.onload();
                    if (typeof this.onreadystatechange === 'function') this.onreadystatechange();
                };
                XHR.prototype.send.toString = () => 'function send() { [native code] }';

                function fakeFetch(url, opts) {
                    opts = opts || {};
                    const bStr = opts.body == null ? '' : (opts.body instanceof URLSearchParams ? opts.body.toString() : String(opts.body));
                    logs.push('FETCH ' + (opts.method||'GET') + ' ' + url + ' hdr=' + JSON.stringify(opts.headers||{}) + ' body=' + bStr);
                    capturedHttp.push({ kind: 'fetch', method: opts.method || 'GET', url: String(url), headers: opts.headers || {}, body: bStr });
                    const rTxt = '{"ok":true,"url":"https://cimanow.cc/watching/?token=test"}';
                    return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(rTxt), json: () => Promise.resolve(JSON.parse(rTxt)) });
                }
                fakeFetch.toString = () => 'function fetch() { [native code] }';

                const ctxObj = {
                    console: { log: (...a) => logs.push('log:' + a.join(' ')), warn: (...a) => logs.push('warn:' + a.join(' ')), error: (...a) => logs.push('err:' + a.join(' ')) },
                    Date: new Proxy(Date, {
                        apply: () => new Date(virtNow).toString(),
                        construct: (t, args) => args.length ? new t(...args) : new t(virtNow),
                        get: (t, p) => p === 'now' ? () => virtNow : Reflect.get(t, p)
                    }),
                    performance: { now: () => virtNow % 1000000, timing: { navigationStart: virtNow - 500 } },
                    location: { href: '${vaultUrl}', origin: '${gwOrigin}', protocol: 'https:', host: '${u.host}', hostname: '${u.host}', pathname: '/2020/02/blog-post.html/', search: '', hash: '', assign(v){logs.push('loc.assign:'+v);}, replace(v){logs.push('loc.replace:'+v);} },
                    navigator: { userAgent: '${CimaNowNetwork.MOBILE_UA}', platform: 'Linux armv81', language: 'ar', languages: ['ar', 'en-US'], webdriver: false, hardwareConcurrency: 8, deviceMemory: 8, maxTouchPoints: 5 },
                    screen: { width: 412, height: 915, availWidth: 412, availHeight: 915, colorDepth: 24, pixelDepth: 24 },
                    devicePixelRatio: 2.625, innerWidth: 412, innerHeight: 815, outerWidth: 412, outerHeight: 915,
                    document,
                    $: jq, jQuery: jq,
                    XMLHttpRequest: XHR,
                    fetch: fakeFetch,
                    URLSearchParams,
                    TextEncoder, TextDecoder, Uint8Array, ArrayBuffer, DataView, Promise, Map, Set, WeakMap, WeakSet, Symbol, Proxy, Reflect, JSON, Math, RegExp, Error, TypeError, Number, String, Boolean, Object, Array, Function, parseInt, parseFloat, isNaN, isFinite, encodeURIComponent, decodeURIComponent, encodeURI, decodeURI, escape, unescape,
                    atob: s => Buffer.from(String(s), 'base64').toString('binary'),
                    btoa: s => Buffer.from(String(s), 'binary').toString('base64'),
                    crypto: cryptoNode.webcrypto,
                    MutationObserver: class { observe(){} disconnect(){} },
                    getComputedStyle: el => ({ display: (el&&el.style&&el.style.display)||'block', visibility: 'visible', opacity: '1', getPropertyValue: p => p==='display'?'block':'visible' }),
                    setTimeout(fn, ms) { const id = nextTid++; timers.push({ id, fn, due: virtNow + (ms||0), iv: 0, active: true }); return id; },
                    setInterval(fn, ms) { const id = nextTid++; const st = Math.max(50, ms||50); timers.push({ id, fn, due: virtNow + st, iv: st, active: true }); return id; },
                    clearTimeout(id) { const t = timers.find(x => x.id === id); if (t) t.active = false; },
                    clearInterval(id) { const t = timers.find(x => x.id === id); if (t) t.active = false; },
                    requestAnimationFrame(fn) { const id = nextTid++; timers.push({ id, fn, due: virtNow + 16, iv: 0, active: true }); return id; }
                };
                ctxObj.window = ctxObj;
                ctxObj.self = ctxObj;
                ctxObj.top = ctxObj;
                ctxObj.parent = ctxObj;
                ctxObj.globalThis = ctxObj;

                const context = vm.createContext(ctxObj);
                scripts.forEach((s, idx) => {
                    if (!s.code.trim()) return;
                    try {
                        vm.runInContext(s.code, context, { filename: 'inner_' + idx + '.js', timeout: 4000 });
                        logs.push('ran inner_' + idx + ' OK');
                    } catch (e) {
                        logs.push('err inner_' + idx + ': ' + e.stack.split('\n').slice(0, 2).join(' | '));
                    }
                });

                (async () => {
                    for (const fn of (docListeners['DOMContentLoaded'] || [])) { try { fn({ type: 'DOMContentLoaded' }); } catch(e){} }
                    const end = virtNow + 13000;
                    while (virtNow < end) {
                        virtNow += 250;
                        for (const tm of timers.slice()) {
                            if (tm.active && tm.due <= virtNow) {
                                if (tm.iv > 0) tm.due = virtNow + tm.iv; else tm.active = false;
                                try { if (typeof tm.fn === 'function') tm.fn(); } catch(e) { logs.push('tmErr:' + e.message); }
                            }
                        }
                        await new Promise(r => setImmediate(r));
                    }
                    logs.push('before click dlBtn href=' + dlBtn.href + ' attrs=' + JSON.stringify(dlBtn.attributes));
                    dlBtn.click();
                    await new Promise(r => setTimeout(r, 150));
                    for (let k=0; k<8; k++) {
                        virtNow += 250;
                        for (const tm of timers.slice()) {
                            if (tm.active && tm.due <= virtNow) {
                                if (tm.iv > 0) tm.due = virtNow + tm.iv; else tm.active = false;
                                try { if (typeof tm.fn === 'function') tm.fn(); } catch(e) {}
                            }
                        }
                        await new Promise(r => setImmediate(r));
                    }
                    logs.push('after click dlBtn href=' + dlBtn.href + ' windowKeys=' + Object.keys(ctxObj).filter(k => !['console','Date','performance','location','navigator','screen','document','$','jQuery','XMLHttpRequest','fetch','window','self','top','parent','globalThis'].includes(k)).join(','));
                    console.log(JSON.stringify({ logs, capturedHttp, dlHref: dlBtn.href }));
                    process.exit(0);
                })();
            """.trimIndent()
            java.io.File("/tmp/run_gw.js").writeText(nodeDriver, Charsets.UTF_8)
            val proc = ProcessBuilder("node", "/tmp/run_gw.js").redirectErrorStream(true).start()
            val nodeOut = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            println("[DIAG][$pTag] NODE_V8_OUT:\n${nodeOut.take(15000)}")

            val nodeJson = runCatching { org.json.JSONObject(nodeOut.trim().lines().last()) }.getOrNull()
            val httpArr = nodeJson?.optJSONArray("capturedHttp")
            if (httpArr != null && httpArr.length() > 0) {
                for (hi in 0 until httpArr.length()) {
                    val hObj = httpArr.getJSONObject(hi)
                    val hUrl = hObj.optString("url")
                    val hMethod = hObj.optString("method")
                    val hBody = hObj.optString("body")
                    val hHdr = hObj.optJSONObject("headers")
                    println("[DIAG][$pTag] V8_CAPTURED[$hi]: $hMethod $hUrl hdr=$hHdr body=$hBody")
                    if (hMethod.equals("POST", true) || hUrl.contains("get-link")) {
                        val elapsed = System.currentTimeMillis() - tFetchDone
                        if (elapsed < 10_500L) kotlinx.coroutines.delay(10_500L - elapsed)
                        val fullUrl = when {
                            hUrl.startsWith("http") -> hUrl
                            hUrl.startsWith("/") -> "$gwOrigin$hUrl"
                            else -> "${vaultUrl.trimEnd('/')}/${hUrl.trimStart('/')}"
                        }
                        val extraH = mutableMapOf("X-Requested-With" to "XMLHttpRequest")
                        hHdr?.keys()?.forEach { k -> extraH[k] = hHdr.optString(k) }
                        val mRes = session.postForm(fullUrl, hBody, vaultUrl, gwOrigin, extraH)
                        println("[DIAG][$pTag] LIVE POST -> code=${mRes?.first} body='${mRes?.second?.take(500)}'")
                    }
                }
            }
            "done"
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
