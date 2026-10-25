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

            data class CapturedHttp(val kind: String, val method: String, val url: String, val headersJson: String, val body: String)
            val capturedRequests = mutableListOf<CapturedHttp>()
            val jsLogs = mutableListOf<String>()

            // Serialize full Jsoup DOM tree (Jsoup strips HTML comments automatically!)
            fun serializeElement(el: org.jsoup.nodes.Element): org.json.JSONObject {
                val obj = org.json.JSONObject()
                obj.put("tag", el.tagName().uppercase())
                obj.put("id", el.id())
                obj.put("cls", el.className())
                obj.put("txt", el.ownText().take(200))
                val attrsObj = org.json.JSONObject()
                el.attributes().forEach { a -> if (a.value.length < 4000) attrsObj.put(a.key, a.value) }
                obj.put("attrs", attrsObj)
                val kids = org.json.JSONArray()
                el.children().forEach { ch ->
                    if (!ch.tagName().equals("script", true) && !ch.tagName().equals("style", true)) {
                        kids.put(serializeElement(ch))
                    }
                }
                obj.put("kids", kids)
                return obj
            }
            val bodyTreeJson = serializeElement(innerDoc.body()).toString()

            val tStageB0 = System.currentTimeMillis()
            val cx2 = safeFactory.enterContext()
            try {
                cx2.putThreadLocal("deadline", System.currentTimeMillis() + 20_000L)
                cx2.setInterpretedMode(true)
                cx2.languageVersion = org.mozilla.javascript.Context.VERSION_ECMASCRIPT
                val scope2 = cx2.initSafeStandardObjects()

                val bridge2 = object {
                    fun log(msg: String) { if (jsLogs.size < 120) jsLogs.add(msg.take(300)) }
                    fun atob(b64: String): String = String(CimaNowCrypto.b64Decode(b64), Charsets.ISO_8859_1)
                    fun btoa(str: String): String = CimaNowCrypto.b64EncodeNoWrap(str.toByteArray(Charsets.ISO_8859_1))
                    fun hmacSha256B64(keyIso: String, msgIso: String): String {
                        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                        mac.init(javax.crypto.spec.SecretKeySpec(keyIso.toByteArray(Charsets.ISO_8859_1), "HmacSHA256"))
                        return CimaNowCrypto.b64EncodeNoWrap(mac.doFinal(msgIso.toByteArray(Charsets.ISO_8859_1)))
                    }
                    fun sha256Hex(msgIso: String): String {
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        return md.digest(msgIso.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
                    }
                    fun onHttp(kind: String, method: String, url: String, headersJson: String, body: String): String {
                        val h = CapturedHttp(kind, method, url, headersJson, body)
                        capturedRequests.add(h)
                        jsLogs.add("HTTP[$kind] $method $url hdr=$headersJson body=${body.take(400)}")
                        return "{\"ok\":true,\"url\":\"https://cimanow.cc/watching/?token=dummy\"}"
                    }
                }
                org.mozilla.javascript.ScriptableObject.putProperty(scope2, "__b2", org.mozilla.javascript.Context.javaToJS(bridge2, scope2))

                val shim2 = """
                    var window = this, self = this, top = this, parent = this, globalThis = this;
                    var __origFnToStr = Function.prototype.toString;
                    Function.prototype.toString = function() {
                        if (this && this.__nativeStr) return this.__nativeStr;
                        return __origFnToStr.call(this).replace(/[\r\n]+/g, ' ');
                    };

                    var __virtNow = ${System.currentTimeMillis()};
                    Date.now = function() { return __virtNow; };
                    var performance = { now: function() { return __virtNow % 1000000; }, timing: { navigationStart: __virtNow - 500 } };
                    var location = {
                        _href: '$vaultUrl', origin: '$gwOrigin', protocol: 'https:', host: '${u.host}', hostname: '${u.host}',
                        pathname: '/2020/02/blog-post.html/', search: '', hash: '',
                        assign: function(v) { this._href = String(v); __b2.log('loc.assign=' + v); },
                        replace: function(v) { this._href = String(v); __b2.log('loc.replace=' + v); }
                    };
                    Object.defineProperty(location, 'href', {
                        get: function() { return this._href; },
                        set: function(v) { this._href = String(v); __b2.log('loc.href=' + v); }
                    });
                    var navigator = {
                        userAgent: '${CimaNowNetwork.MOBILE_UA}', platform: 'Linux armv81', language: 'ar', languages: ['ar', 'en-US'],
                        webdriver: false, hardwareConcurrency: 8, deviceMemory: 8, maxTouchPoints: 5, cookieEnabled: true
                    };
                    var screen = { width: 412, height: 915, availWidth: 412, availHeight: 915, colorDepth: 24, pixelDepth: 24 };
                    var devicePixelRatio = 2.625, innerWidth = 412, innerHeight = 815, outerWidth = 412, outerHeight = 915;
                    var console = { log: function(a){ __b2.log('log:'+a); }, warn: function(a){}, error: function(a){ __b2.log('err:'+a); }, debug: function(){} };

                    function atob(s) { return String(__b2.atob(String(s))); }
                    function btoa(s) { return String(__b2.btoa(String(s))); }
                    function TextEncoder() {}
                    TextEncoder.prototype.encode = function(s) {
                        s = unescape(encodeURIComponent(String(s)));
                        var a = new Uint8Array(s.length);
                        for (var i=0;i<s.length;i++) a[i] = s.charCodeAt(i);
                        return a;
                    };
                    function TextDecoder() {}
                    TextDecoder.prototype.decode = function(u8) {
                        var arr = u8 instanceof Uint8Array ? u8 : new Uint8Array(u8);
                        var s = '';
                        for (var i=0;i<arr.length;i++) s += String.fromCharCode(arr[i]);
                        try { return decodeURIComponent(escape(s)); } catch(e) { return s; }
                    };
                    function __toIso(buf) {
                        var u8 = buf instanceof Uint8Array ? buf : (buf && buf.buffer ? new Uint8Array(buf.buffer, buf.byteOffset || 0, buf.byteLength || buf.length) : new Uint8Array(buf));
                        var s = '';
                        for (var i=0;i<u8.length;i++) s += String.fromCharCode(u8[i]);
                        return s;
                    }
                    var crypto = {
                        getRandomValues: function(arr) { for (var i=0;i<arr.length;i++) arr[i] = (Math.random()*256)|0; return arr; },
                        subtle: {
                            importKey: function(fmt, keyData, algo, ext, usages) {
                                return Promise.resolve({ __k: __toIso(keyData), __algo: algo });
                            },
                            sign: function(algo, key, data) {
                                var b64 = String(__b2.hmacSha256B64(key.__k, __toIso(data)));
                                var raw = atob(b64);
                                var out = new Uint8Array(raw.length);
                                for (var j=0;j<raw.length;j++) out[j] = raw.charCodeAt(j);
                                return Promise.resolve(out.buffer);
                            },
                            digest: function(algo, data) {
                                var hex = String(__b2.sha256Hex(__toIso(data)));
                                var out = new Uint8Array(32);
                                for (var j=0;j<32;j++) out[j] = parseInt(hex.substr(j*2, 2), 16);
                                return Promise.resolve(out.buffer);
                            }
                        }
                    };

                    var __timers = [], __nextTid = 1;
                    function setTimeout(fn, ms) { var id = __nextTid++; __timers.push({ id: id, fn: fn, due: __virtNow + (Number(ms)||0), iv: 0, active: true }); return id; }
                    function setInterval(fn, ms) { var id = __nextTid++; var st = Math.max(50, Number(ms)||50); __timers.push({ id: id, fn: fn, due: __virtNow + st, iv: st, active: true }); return id; }
                    function clearTimeout(id) { for (var i=0;i<__timers.length;i++) if (__timers[i].id===id) __timers[i].active=false; }
                    function clearInterval(id) { clearTimeout(id); }
                    function requestAnimationFrame(fn) { return setTimeout(fn, 16); }

                    function XMLHttpRequest() { this._h = {}; this._m = 'GET'; this._u = ''; this.readyState = 0; this.status = 200; this.responseText = ''; }
                    XMLHttpRequest.prototype.open = function(m, u) { this._m = String(m); this._u = String(u); this.readyState = 1; };
                    XMLHttpRequest.prototype.setRequestHeader = function(k, v) { this._h[String(k)] = String(v); };
                    XMLHttpRequest.prototype.addEventListener = function(ev, fn) { this['on' + ev] = fn; };
                    XMLHttpRequest.prototype.send = function(body) {
                        var bStr = body == null ? '' : (typeof body === 'object' && body.toString ? body.toString() : String(body));
                        var resp = String(__b2.onHttp('xhr', this._m, this._u, JSON.stringify(this._h), bStr));
                        this.status = 200; this.readyState = 4; this.responseText = resp; this.response = resp;
                        if (typeof this.onload === 'function') this.onload();
                        if (typeof this.onreadystatechange === 'function') this.onreadystatechange();
                    };
                    XMLHttpRequest.prototype.send.__nativeStr = 'function send() { [native code] }';

                    function fetch(url, opts) {
                        opts = opts || {};
                        var m = opts.method || 'GET';
                        var h = opts.headers || {};
                        var b = opts.body == null ? '' : (typeof opts.body === 'object' && opts.body.toString ? opts.body.toString() : String(opts.body));
                        var resp = String(__b2.onHttp('fetch', String(m), String(url), JSON.stringify(h), b));
                        return Promise.resolve({
                            ok: true, status: 200,
                            text: function() { return Promise.resolve(resp); },
                            json: function() { return Promise.resolve(JSON.parse(resp)); }
                        });
                    }
                    fetch.__nativeStr = 'function fetch() { [native code] }';

                    function URLSearchParams(init) {
                        this._p = [];
                        if (typeof init === 'string') {
                            var s = init.charAt(0) === '?' ? init.slice(1) : init;
                            if (s) s.split('&').forEach(function(kv){ var eq = kv.indexOf('='); if (eq>=0) this._p.push([decodeURIComponent(kv.slice(0,eq)), decodeURIComponent(kv.slice(eq+1))]); }.bind(this));
                        } else if (init && typeof init === 'object') {
                            Object.keys(init).forEach(function(k){ this._p.push([k, String(init[k])]); }.bind(this));
                        }
                    }
                    URLSearchParams.prototype.append = function(k, v) { this._p.push([String(k), String(v)]); };
                    URLSearchParams.prototype.set = function(k, v) { this._p = this._p.filter(function(x){return x[0]!==String(k);}); this._p.push([String(k), String(v)]); };
                    URLSearchParams.prototype.get = function(k) { var f = this._p.find(function(x){return x[0]===String(k);}); return f ? f[1] : null; };
                    URLSearchParams.prototype.toString = function() { return this._p.map(function(x){ return encodeURIComponent(x[0]) + '=' + encodeURIComponent(x[1]); }).join('&'); };

                    var __allEls = [], __elsById = {}, __docListeners = {};
                    function __makeEl(tag) {
                        var el = {
                            tagName: String(tag || 'DIV').toUpperCase(), nodeType: 1, id: '', className: '',
                            style: { cssText: '', display: '', visibility: 'visible', opacity: '1', setProperty: function(k,v){ this[k]=v; }, getPropertyValue: function(k){ return this[k]||''; }, removeProperty: function(k){ delete this[k]; } },
                            dataset: {}, attributes: [], childNodes: [], children: [], parentNode: null, firstChild: null,
                            textContent: '', innerText: '', _innerHTML: '', value: '', disabled: false, hidden: false, _href: '',
                            _listeners: {},
                            classList: { add: function(){}, remove: function(){}, contains: function(){ return false; }, toggle: function(){} },
                            setAttribute: function(k, v) {
                                k = String(k); v = String(v);
                                var found = false;
                                for (var i=0;i<this.attributes.length;i++) if (this.attributes[i].name===k) { this.attributes[i].value=v; found=true; break; }
                                if (!found) this.attributes.push({ name: k, value: v });
                                if (k === 'id') { this.id = v; __elsById[v] = this; }
                                if (k === 'class') this.className = v;
                                if (k === 'style') this.style.cssText = v;
                                if (k === 'hidden') this.hidden = true;
                                if (k === 'href') this._href = v;
                                if (k.indexOf('data-') === 0) {
                                    var camel = k.slice(5).replace(/-([a-z])/g, function(_, c){ return c.toUpperCase(); });
                                    this.dataset[camel] = v;
                                }
                            },
                            getAttribute: function(k) { for (var i=0;i<this.attributes.length;i++) if (this.attributes[i].name===String(k)) return this.attributes[i].value; return null; },
                            hasAttribute: function(k) { return this.getAttribute(k) !== null; },
                            removeAttribute: function(k) {
                                k = String(k);
                                this.attributes = this.attributes.filter(function(a){ return a.name !== k; });
                                if (k === 'hidden') this.hidden = false;
                                if (k === 'style') this.style.cssText = '';
                            },
                            appendChild: function(c) {
                                if (c) {
                                    c.parentNode = this; this.childNodes.push(c); this.children.push(c); this.firstChild = this.childNodes[0] || null;
                                    if (c.id) __elsById[c.id] = c;
                                    if (c.src) __b2.log('appendChild ' + c.tagName + ' src=' + c.src);
                                    __allEls.push(c);
                                }
                                return c;
                            },
                            removeChild: function(c) { var idx = this.childNodes.indexOf(c); if (idx>=0) this.childNodes.splice(idx,1); return c; },
                            remove: function() { if (this.parentNode) this.parentNode.removeChild(this); },
                            insertBefore: function(n) { return this.appendChild(n); },
                            replaceChild: function(n) { return this.appendChild(n); },
                            contains: function() { return true; },
                            matches: function(sel) { return __matches(this, sel); },
                            closest: function(sel) { var cur = this; while (cur && cur.nodeType === 1) { if (__matches(cur, sel)) return cur; cur = cur.parentNode; } return null; },
                            getElementsByTagName: function(t) { t = String(t).toUpperCase(); return __allEls.filter(function(x){ return t==='*' || x.tagName===t; }); },
                            querySelector: function(sel) { return document.querySelector(sel); },
                            querySelectorAll: function(sel) { return document.querySelectorAll(sel); },
                            addEventListener: function(ev, fn) { (this._listeners[ev] = this._listeners[ev] || []).push(fn); },
                            removeEventListener: function() {},
                            dispatchEvent: function(ev) { if (ev && ev.type === 'click') this.click(); return true; },
                            click: function() {
                                var evObj = { type: 'click', isTrusted: true, target: this, currentTarget: this, bubbles: true, cancelable: true, clientX: 120, clientY: 115, screenX: 120, screenY: 220, button: 0, buttons: 1, preventDefault: function(){}, stopPropagation: function(){}, stopImmediatePropagation: function(){} };
                                var ls = (this._listeners['click'] || []).slice();
                                for (var i=0;i<ls.length;i++) ls[i].call(this, evObj);
                                if (typeof this.onclick === 'function') this.onclick(evObj);
                                var dls = (__docListeners['click'] || []).slice();
                                for (var j=0;j<dls.length;j++) dls[j].call(document, evObj);
                            },
                            getBoundingClientRect: function() { return { width: 220, height: 48, top: 100, left: 50, right: 270, bottom: 148, x: 50, y: 100 }; }
                        };
                        Object.defineProperty(el, 'href', {
                            get: function() { return this._href; },
                            set: function(v) { this._href = String(v); __b2.log('el#' + this.id + '.href=' + v); }
                        });
                        Object.defineProperty(el, 'offsetWidth', { get: function() { return 220; } });
                        Object.defineProperty(el, 'offsetHeight', { get: function() { return 48; } });
                        Object.defineProperty(el, 'clientWidth', { get: function() { return 220; } });
                        Object.defineProperty(el, 'clientHeight', { get: function() { return 48; } });
                        Object.defineProperty(el, 'offsetParent', { get: function() { return document.body; } });
                        Object.defineProperty(el, 'innerHTML', { get: function() { return this._innerHTML; }, set: function(v) { this._innerHTML = String(v); } });
                        return el;
                    }
                    function __matches(el, sel) {
                        if (!el || !sel) return false;
                        var parts = String(sel).split(',');
                        for (var i=0;i<parts.length;i++) {
                            var s = parts[i].trim();
                            if (!s) continue;
                            if (s.charAt(0) === '#' && el.id === s.slice(1)) return true;
                            if (s.charAt(0) === '.' && (' '+el.className+' ').indexOf(' '+s.slice(1)+' ') >= 0) return true;
                            if (el.tagName === s.toUpperCase()) return true;
                            var attrM = /^([a-zA-Z0-9_-]*)\[([a-zA-Z0-9_-]+)(?:=['"]?([^'"\]]*)['"]?)?\]$/.exec(s);
                            if (attrM) {
                                if ((!attrM[1] || el.tagName === attrM[1].toUpperCase()) && el.hasAttribute(attrM[2])) {
                                    if (attrM[3] === undefined || el.getAttribute(attrM[2]) === attrM[3]) return true;
                                }
                            }
                        }
                        return false;
                    }
                    function getComputedStyle(el) {
                        return { display: (el && el.style && el.style.display) || 'block', visibility: 'visible', opacity: '1', getPropertyValue: function(p){ return p==='display' ? ((el && el.style && el.style.display) || 'block') : 'visible'; } };
                    }
                    function MutationObserver() {}
                    MutationObserver.prototype.observe = function() {};
                    MutationObserver.prototype.disconnect = function() {};

                    var document = {
                        nodeType: 9, readyState: 'complete', hidden: false, visibilityState: 'visible',
                        cookie: '', referrer: '$gwOrigin/redirectingfree/', domain: '${u.host}',
                        documentElement: __makeEl('HTML'), head: __makeEl('HEAD'), body: __makeEl('BODY'),
                        createElement: function(t) { return __makeEl(t); },
                        createTextNode: function(t) { var n = __makeEl('TEXT'); n.nodeType = 3; n.textContent = String(t); return n; },
                        createDocumentFragment: function() { return __makeEl('FRAG'); },
                        getElementById: function(id) { var r = __elsById[String(id)] || null; if (!r) __b2.log('MISS getElementById:' + id); return r; },
                        getElementsByTagName: function(t) { t = String(t).toUpperCase(); return __allEls.filter(function(x){ return t==='*' || x.tagName===t; }); },
                        getElementsByClassName: function(c) { return __allEls.filter(function(x){ return (' '+x.className+' ').indexOf(' '+c+' ')>=0; }); },
                        querySelectorAll: function(sel) {
                            var out = __allEls.filter(function(x){ return __matches(x, sel); });
                            if (!out.length) __b2.log('MISS qsa:' + sel);
                            return out;
                        },
                        querySelector: function(sel) { var a = this.querySelectorAll(sel); return a.length ? a[0] : null; },
                        addEventListener: function(ev, fn) { (__docListeners[ev] = __docListeners[ev] || []).push(fn); },
                        removeEventListener: function() {},
                        dispatchEvent: function() { return true; }
                    };
                    document.documentElement.appendChild(document.head);
                    document.documentElement.appendChild(document.body);
                    window.document = document;
                    window.addEventListener = function(ev, fn) { (__docListeners['win_'+ev] = __docListeners['win_'+ev] || []).push(fn); };
                    window.removeEventListener = function() {};

                    function __hydrate(spec, parent) {
                        var n = parent ? __makeEl(spec.tag) : document.body;
                        if (spec.txt) { n.textContent = spec.txt; n.innerText = spec.txt; }
                        var ak = Object.keys(spec.attrs || {});
                        for (var i=0;i<ak.length;i++) n.setAttribute(ak[i], spec.attrs[ak[i]]);
                        if (parent) parent.appendChild(n);
                        var ks = spec.kids || [];
                        for (var j=0;j<ks.length;j++) __hydrate(ks[j], n);
                    }
                    __hydrate($bodyTreeJson, null);

                    function $(sel) {
                        var els = typeof sel === 'string' ? document.querySelectorAll(sel) : [sel];
                        return {
                            ready: function(fn){ setTimeout(fn, 0); return this; },
                            click: function(fn){ if (fn) els.forEach(function(e){ e&&e.addEventListener&&e.addEventListener('click', fn); }); else els.forEach(function(e){ e&&e.click&&e.click(); }); return this; },
                            hide: function(){ els.forEach(function(e){ if(e&&e.style) e.style.display='none'; }); return this; },
                            show: function(){ els.forEach(function(e){ if(e){ e.hidden=false; e.removeAttribute('hidden'); if(e.style){ e.style.cssText=''; e.style.display='inline-block'; } } }); return this; },
                            remove: function(){ els.forEach(function(e){ e&&e.remove&&e.remove(); }); return this; },
                            attr: function(k, v){ if (v===undefined) return els[0]?els[0].getAttribute(k):null; els.forEach(function(e){ e&&e.setAttribute(k,v); }); return this; },
                            removeAttr: function(k){ els.forEach(function(e){ e&&e.removeAttribute(k); }); return this; },
                            text: function(v){ if (v===undefined) return els[0]?els[0].textContent:''; els.forEach(function(e){ if(e) e.textContent=String(v); }); return this; },
                            html: function(v){ if (v===undefined) return els[0]?els[0].innerHTML:''; els.forEach(function(e){ if(e) e.innerHTML=String(v); }); return this; },
                            on: function(ev, fn){ els.forEach(function(e){ e&&e.addEventListener&&e.addEventListener(ev, fn); }); return this; },
                            countdown360: function(opts){ if(opts&&opts.onComplete) setTimeout(opts.onComplete, (opts.seconds||10)*1000); return { start: function(){} }; }
                        };
                    }
                    $.ajax = function(o) {
                        var b = typeof o.data === 'string' ? o.data : new URLSearchParams(o.data || {}).toString();
                        var r = String(__b2.onHttp('ajax', o.type || 'POST', o.url || '', JSON.stringify(o.headers || {}), b));
                        if (o.success) o.success(r);
                    };
                    var jQuery = $;

                    function __stepTime(totalMs, stepMs) {
                        var end = __virtNow + totalMs;
                        while (__virtNow < end) {
                            __virtNow += stepMs;
                            var snap = __timers.slice();
                            for (var ti=0;ti<snap.length;ti++) {
                                var tm = snap[ti];
                                if (tm.active && tm.due <= __virtNow) {
                                    if (tm.iv > 0) tm.due = __virtNow + tm.iv; else tm.active = false;
                                    try { if (typeof tm.fn === 'function') tm.fn(); } catch(err) { __b2.log('tmErr:' + (err && err.message ? err.message : err)); }
                                }
                            }
                        }
                    }
                """.trimIndent()
                cx2.evaluateString(scope2, shim2, "shim2.js", 1, null)

                innerDoc.select("script").forEachIndexed { idx, sc ->
                    val code = sc.data()
                    if (code.isBlank() || idx == 9) return@forEachIndexed // idx 9 is countdown360 visual widget; simulate its 10s show() directly!
                    val tS = System.currentTimeMillis()
                    cx2.putThreadLocal("deadline", System.currentTimeMillis() + 6_000L)
                    runCatching {
                        cx2.evaluateString(scope2, code, "inner_$idx.js", 1, null)
                        println("[DIAG][$pTag] Rhino ran inner_$idx.js OK in ${System.currentTimeMillis() - tS}ms")
                    }.onFailure { e ->
                        val col = (e as? org.mozilla.javascript.RhinoException)?.columnNumber() ?: -1
                        val lineSrc = (e as? org.mozilla.javascript.RhinoException)?.lineSource().orEmpty()
                        val around = if (col > 0 && lineSrc.isNotEmpty()) {
                            val s0 = maxOf(0, col - 80)
                            val s1 = minOf(lineSrc.length, col + 80)
                            lineSrc.substring(s0, s1)
                        } else {
                            // Extract inner Function("arg", "BODY") string and compile directly to get exact column!
                            val fnBody = runCatching {
                                val q1 = code.indexOf(","")
                                val q2 = code.lastIndexOf("")")
                                if (q1 > 0 && q2 > q1) org.json.JSONTokener(code.substring(q1 + 1, q2 + 1)).nextValue() as String else ""
                            }.getOrDefault("")
                            var subAround = ""
                            if (fnBody.isNotEmpty()) {
                                runCatching { cx2.compileString(fnBody, "fn_$idx.js", 1, null) }.onFailure { ce ->
                                    val cCol = (ce as? org.mozilla.javascript.RhinoException)?.columnNumber() ?: -1
                                    if (cCol >= 0) subAround = "col=$cCol slice='${fnBody.substring(maxOf(0, cCol - 90), minOf(fnBody.length, cCol + 90))}'"
                                }
                            }
                            subAround
                        }
                        println("[DIAG][$pTag] Rhino inner_$idx.js err (${System.currentTimeMillis() - tS}ms): ${e.message?.take(140)} | $around")
                    }
                }

                // Simulate countdown360 completion after 10s + fire events + click #downloadbtn!
                cx2.putThreadLocal("deadline", System.currentTimeMillis() + 10_000L)
                val drive2 = """
                    setTimeout(function() {
                        $('.downloadbtn').show();
                        $('#countdown').remove();
                        $('.text_sco').remove();
                    }, 10000);
                    (__docListeners['DOMContentLoaded'] || []).forEach(function(f){ try { f({ type: 'DOMContentLoaded' }); } catch(e){ __b2.log('dclE:'+e); } });
                    (__docListeners['win_load'] || []).forEach(function(f){ try { f({ type: 'load' }); } catch(e){ __b2.log('ldE:'+e); } });
                    __stepTime(11500, 250);
                    var btn = document.getElementById('downloadbtn');
                    __b2.log('Before click btn attrs=' + JSON.stringify(btn ? btn.attributes : null) + ' listeners=' + Object.keys(btn ? btn._listeners : {}));
                    if (btn) btn.click();
                    __stepTime(2000, 250);
                    __b2.log('After click btn href=' + (btn ? btn.href : 'null') + ' loc=' + location.href);
                """.trimIndent()
                runCatching { cx2.evaluateString(scope2, drive2, "drive2.js", 1, null) }
                    .onFailure { println("[DIAG][$pTag] drive2 err: ${it.message?.take(220)}") }
            } finally {
                org.mozilla.javascript.Context.exit()
            }

            println("[DIAG][$pTag] StageB Rhino total=${System.currentTimeMillis() - tStageB0}ms logs=${jsLogs.size} http=${capturedRequests.size}")
            println("[DIAG][$pTag] JS_LOGS:\n${jsLogs.joinToString("\n")}")
            println("[DIAG][$pTag] CAPTURED_HTTP:\n${capturedRequests.joinToString("\n")}")

            if (capturedRequests.isEmpty()) {
                java.io.File("/tmp/inner_gw.html").writeText(innerHtml, Charsets.UTF_8)
                java.io.File("/tmp/body_tree.json").writeText(bodyTreeJson, Charsets.UTF_8)
                val v8Script = """
                    const fs = require('fs');
                    const vm = require('vm');
                    const cryptoNode = require('crypto');
                    const html = fs.readFileSync('/tmp/inner_gw.html', 'utf8');
                    const bodyTreeJson = fs.readFileSync('/tmp/body_tree.json', 'utf8');
                    const scripts = [];
                    const re = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
                    let m;
                    while ((m = re.exec(html)) !== null) scripts.push(m[1]);

                    const captured = [];
                    const logs = [];
                    const hostBridge = {
                        log: s => { if (logs.length < 100) logs.push(String(s).slice(0, 300)); },
                        onHttp: (kind, method, url, hdrs, body) => {
                            captured.push({ kind, method, url, headersJson: hdrs, body });
                            logs.push('V8_HTTP[' + kind + '] ' + method + ' ' + url + ' hdr=' + hdrs + ' body=' + String(body).slice(0, 400));
                            return '{"ok":true,"url":"https://cimanow.cc/watching/?token=dummy"}';
                        },
                        webcrypto: cryptoNode.webcrypto,
                        atob: s => Buffer.from(String(s), 'base64').toString('binary'),
                        btoa: s => Buffer.from(String(s), 'binary').toString('base64'),
                        TextEncoder, TextDecoder, URLSearchParams
                    };

                    const context = vm.createContext({ __host: hostBridge });
                    const bootInRealm = `
                        var window = globalThis, self = globalThis, top = globalThis, parent = globalThis;
                        var __virtNow = Date.now();
                        const __RealDate = Date;
                        Date.now = function() { return __virtNow; };
                        var performance = { now: function() { return __virtNow % 1000000; }, timing: { navigationStart: __virtNow - 500 } };
                        var location = {
                            _href: '${vaultUrl}', origin: '${gwOrigin}', protocol: 'https:', host: '${u.host}', hostname: '${u.host}',
                            pathname: '/2020/02/blog-post.html/', search: '', hash: '',
                            assign(v){ this._href = String(v); __host.log('loc.assign=' + v); },
                            replace(v){ this._href = String(v); __host.log('loc.replace=' + v); },
                            get href(){ return this._href; },
                            set href(v){ this._href = String(v); __host.log('loc.href=' + v); }
                        };
                        var navigator = { userAgent: '${CimaNowNetwork.MOBILE_UA}', platform: 'Linux armv81', language: 'ar', languages: ['ar','en-US'], webdriver: false, hardwareConcurrency: 8, deviceMemory: 8, maxTouchPoints: 5, cookieEnabled: true };
                        var screen = { width: 412, height: 915, availWidth: 412, availHeight: 915, colorDepth: 24, pixelDepth: 24 };
                        var devicePixelRatio = 2.625, innerWidth = 412, innerHeight = 815, outerWidth = 412, outerHeight = 915;
                        var console = { log: (...a)=>__host.log('log:'+a.join(' ')), warn(){}, error: (...a)=>__host.log('err:'+a.join(' ')), debug(){} };
                        function atob(s){ return __host.atob(s); }
                        function btoa(s){ return __host.btoa(s); }
                        var crypto = __host.webcrypto;
                        var TextEncoder = __host.TextEncoder, TextDecoder = __host.TextDecoder, URLSearchParams = __host.URLSearchParams;

                        var __timers = [], __nextTid = 1;
                        function setTimeout(fn, ms){ var id = __nextTid++; __timers.push({id, fn, due: __virtNow + (Number(ms)||0), iv: 0, active: true}); return id; }
                        function setInterval(fn, ms){ var id = __nextTid++; var st = Math.max(50, Number(ms)||50); __timers.push({id, fn, due: __virtNow + st, iv: st, active: true}); return id; }
                        function clearTimeout(id){ for (var t of __timers) if (t.id===id) t.active=false; }
                        function clearInterval(id){ clearTimeout(id); }
                        function requestAnimationFrame(fn){ return setTimeout(fn, 16); }

                        function XMLHttpRequest(){ this._h={}; this._m='GET'; this._u=''; this.readyState=0; this.status=200; this.responseText=''; }
                        XMLHttpRequest.prototype.open = function(m,u){ this._m=String(m); this._u=String(u); this.readyState=1; };
                        XMLHttpRequest.prototype.setRequestHeader = function(k,v){ this._h[String(k)]=String(v); };
                        XMLHttpRequest.prototype.addEventListener = function(ev,fn){ this['on'+ev]=fn; };
                        XMLHttpRequest.prototype.send = function(body){
                            var bStr = body==null ? '' : String(body);
                            var r = __host.onHttp('xhr', this._m, this._u, JSON.stringify(this._h), bStr);
                            this.status=200; this.readyState=4; this.responseText=r; this.response=r;
                            if (typeof this.onload==='function') this.onload();
                            if (typeof this.onreadystatechange==='function') this.onreadystatechange();
                        };
                        XMLHttpRequest.prototype.send.toString = function(){ return 'function send() { [native code] }'; };

                        function fetch(url, opts){
                            opts = opts || {};
                            var bStr = opts.body==null ? '' : String(opts.body);
                            var r = __host.onHttp('fetch', String(opts.method||'GET'), String(url), JSON.stringify(opts.headers||{}), bStr);
                            return Promise.resolve({ ok: true, status: 200, text: ()=>Promise.resolve(r), json: ()=>Promise.resolve(JSON.parse(r)) });
                        }
                        fetch.toString = function(){ return 'function fetch() { [native code] }'; };

                        var __allEls = [], __elsById = {}, __docListeners = {};
                        function __matches(el, sel) {
                            if (!el || !sel) return false;
                            for (var raw of String(sel).split(',')) {
                                var s = raw.trim();
                                if (!s) continue;
                                if (s[0]==='#' && el.id===s.slice(1)) return true;
                                if (s[0]==='.' && (' '+el.className+' ').includes(' '+s.slice(1)+' ')) return true;
                                if (el.tagName===s.toUpperCase()) return true;
                            }
                            return false;
                        }
                        function __makeEl(tag) {
                            var el = {
                                tagName: String(tag||'DIV').toUpperCase(), nodeType: 1, id: '', className: '',
                                style: { cssText: '', display: '', visibility: 'visible', opacity: '1', setProperty(k,v){this[k]=v;}, getPropertyValue(k){return this[k]||'';}, removeProperty(k){delete this[k];} },
                                dataset: {}, attributes: [], childNodes: [], children: [], parentNode: null, firstChild: null,
                                textContent: '', innerText: '', _innerHTML: '', value: '', disabled: false, hidden: false, _href: '', _listeners: {},
                                classList: { add(){}, remove(){}, contains(){return false;}, toggle(){} },
                                setAttribute(k,v){
                                    k=String(k); v=String(v);
                                    var ex = this.attributes.find(a=>a.name===k);
                                    if (ex) ex.value=v; else this.attributes.push({name:k, value:v});
                                    if (k==='id'){ this.id=v; __elsById[v]=this; }
                                    if (k==='class') this.className=v;
                                    if (k==='style') this.style.cssText=v;
                                    if (k==='hidden') this.hidden=true;
                                    if (k==='href') this._href=v;
                                    if (k.startsWith('data-')) this.dataset[k.slice(5).replace(/-([a-z])/g,(_,c)=>c.toUpperCase())] = v;
                                },
                                getAttribute(k){ var ex=this.attributes.find(a=>a.name===String(k)); return ex?ex.value:null; },
                                hasAttribute(k){ return this.getAttribute(k)!==null; },
                                removeAttribute(k){ k=String(k); this.attributes=this.attributes.filter(a=>a.name!==k); if(k==='hidden') this.hidden=false; if(k==='style') this.style.cssText=''; },
                                appendChild(c){ if(c){ c.parentNode=this; this.childNodes.push(c); this.children.push(c); this.firstChild=this.childNodes[0]||null; if(c.id) __elsById[c.id]=c; if(c.src) __host.log('append '+c.tagName+' src='+c.src); __allEls.push(c); } return c; },
                                removeChild(c){ var i=this.childNodes.indexOf(c); if(i>=0) this.childNodes.splice(i,1); return c; },
                                remove(){ if(this.parentNode) this.parentNode.removeChild(this); },
                                insertBefore(n){ return this.appendChild(n); },
                                replaceChild(n){ return this.appendChild(n); },
                                contains(){ return true; },
                                matches(s){ return __matches(this,s); },
                                closest(s){ var cur=this; while(cur&&cur.nodeType===1){ if(__matches(cur,s)) return cur; cur=cur.parentNode; } return null; },
                                getElementsByTagName(t){ t=String(t).toUpperCase(); return __allEls.filter(x=>t==='*'||x.tagName===t); },
                                querySelector(s){ return document.querySelector(s); },
                                querySelectorAll(s){ return document.querySelectorAll(s); },
                                addEventListener(ev,fn){ (this._listeners[ev]=this._listeners[ev]||[]).push(fn); },
                                removeEventListener(){},
                                click(){
                                    var evObj = { type:'click', isTrusted:true, target:this, currentTarget:this, bubbles:true, cancelable:true, clientX:120, clientY:115, button:0, buttons:1, preventDefault(){}, stopPropagation(){}, stopImmediatePropagation(){} };
                                    for (var fn of (this._listeners['click']||[]).slice()) fn.call(this, evObj);
                                    if (typeof this.onclick==='function') this.onclick(evObj);
                                    for (var dfn of (__docListeners['click']||[]).slice()) dfn.call(document, evObj);
                                },
                                getBoundingClientRect(){ return {width:220, height:48, top:100, left:50, right:270, bottom:148, x:50, y:100}; },
                                get href(){ return this._href; },
                                set href(v){ this._href=String(v); __host.log('el#'+this.id+'.href='+v); },
                                get offsetWidth(){ return 220; }, get offsetHeight(){ return 48; }, get clientWidth(){ return 220; }, get clientHeight(){ return 48; },
                                get offsetParent(){ return document.body; },
                                get innerHTML(){ return this._innerHTML; }, set innerHTML(v){ this._innerHTML=String(v); }
                            };
                            return el;
                        }
                        function getComputedStyle(el){ return { display:(el&&el.style&&el.style.display)||'block', visibility:'visible', opacity:'1', getPropertyValue:p=>p==='display'?((el&&el.style&&el.style.display)||'block'):'visible' }; }
                        class MutationObserver { observe(){} disconnect(){} }
                        var document = {
                            nodeType:9, readyState:'complete', hidden:false, visibilityState:'visible', cookie:'', referrer:'${gwOrigin}/redirectingfree/', domain:'${u.host}',
                            documentElement:__makeEl('HTML'), head:__makeEl('HEAD'), body:__makeEl('BODY'),
                            createElement:t=>__makeEl(t), createTextNode:t=>{var n=__makeEl('TEXT');n.nodeType=3;n.textContent=String(t);return n;}, createDocumentFragment:()=>__makeEl('FRAG'),
                            getElementById:id=>__elsById[String(id)]||null,
                            getElementsByTagName:t=>{t=String(t).toUpperCase();return __allEls.filter(x=>t==='*'||x.tagName===t);},
                            getElementsByClassName:c=>__allEls.filter(x=>(' '+x.className+' ').includes(' '+c+' ')),
                            querySelectorAll:sel=>__allEls.filter(x=>__matches(x,sel)),
                            querySelector:sel=>{var a=document.querySelectorAll(sel);return a.length?a[0]:null;},
                            addEventListener:(ev,fn)=>{(__docListeners[ev]=__docListeners[ev]||[]).push(fn);},
                            removeEventListener(){}
                        };
                        document.documentElement.appendChild(document.head);
                        document.documentElement.appendChild(document.body);
                        window.addEventListener = (ev,fn)=>{(__docListeners['win_'+ev]=__docListeners['win_'+ev]||[]).push(fn);};
                        window.removeEventListener = ()=>{};
                        function __hydrate(sp, parent){
                            var n = parent ? __makeEl(sp.tag) : document.body;
                            if (sp.txt){ n.textContent=sp.txt; n.innerText=sp.txt; }
                            for (var k of Object.keys(sp.attrs||{})) n.setAttribute(k, sp.attrs[k]);
                            if (parent) parent.appendChild(n);
                            for (var ch of (sp.kids||[])) __hydrate(ch, n);
                        }
                        __hydrate(${bodyTreeJson}, null);
                        function $(sel){
                            var els = typeof sel==='string' ? document.querySelectorAll(sel) : [sel];
                            return {
                                ready(fn){ setTimeout(fn,0); return this; },
                                click(fn){ if(fn) els.forEach(e=>e&&e.addEventListener&&e.addEventListener('click',fn)); else els.forEach(e=>e&&e.click&&e.click()); return this; },
                                hide(){ els.forEach(e=>{if(e&&e.style)e.style.display='none';}); return this; },
                                show(){ els.forEach(e=>{if(e){e.hidden=false;e.removeAttribute('hidden');if(e.style){e.style.cssText='';e.style.display='inline-block';}}}); return this; },
                                remove(){ els.forEach(e=>e&&e.remove&&e.remove()); return this; },
                                countdown360(o){ if(o&&o.onComplete) setTimeout(o.onComplete,(o.seconds||10)*1000); return {start(){}}; }
                            };
                        }
                        var jQuery = $;
                        delete globalThis.__host_tmp;
                    `;
                    vm.runInContext(bootInRealm, context);

                    scripts.forEach((code, idx) => {
                        if (!code.trim()) return;
                        try {
                            vm.runInContext(code, context, { filename: 's_' + idx + '.js', timeout: 5000 });
                            logs.push('V8 ran s_' + idx + ' OK');
                        } catch (e) {
                            logs.push('V8 s_' + idx + ' err: ' + String(e && e.message ? e.message : e).slice(0, 180));
                        }
                    });

                    (async () => {
                        vm.runInContext(`
                            for (var fn of (__docListeners['DOMContentLoaded']||[])) try{fn({type:'DOMContentLoaded'});}catch(e){}
                            for (var fn of (__docListeners['win_load']||[])) try{fn({type:'load'});}catch(e){}
                        `, context);
                        for (let step = 0; step < 50; step++) {
                            vm.runInContext(`
                                __virtNow += 250;
                                for (var tm of __timers.slice()) {
                                    if (tm.active && tm.due <= __virtNow) {
                                        if (tm.iv > 0) tm.due = __virtNow + tm.iv; else tm.active = false;
                                        try { if (typeof tm.fn === 'function') tm.fn(); } catch(e) { __host.log('tmE:'+e.message); }
                                    }
                                }
                            `, context, { timeout: 3000 });
                            await new Promise(r => setImmediate(r));
                        }
                        vm.runInContext(`
                            var b = document.getElementById('downloadbtn');
                            __host.log('V8 pre-click btn hidden=' + (b&&b.hidden) + ' style=' + (b&&b.style.cssText) + ' listeners=' + Object.keys(b?b._listeners:{}) + ' onclick=' + typeof(b&&b.onclick));
                            if (b) b.click();
                        `, context, { timeout: 3000 });
                        await new Promise(r => setTimeout(r, 150));
                        for (let step = 0; step < 12; step++) {
                            vm.runInContext(`
                                __virtNow += 250;
                                for (var tm of __timers.slice()) {
                                    if (tm.active && tm.due <= __virtNow) {
                                        if (tm.iv > 0) tm.due = __virtNow + tm.iv; else tm.active = false;
                                        try { if (typeof tm.fn === 'function') tm.fn(); } catch(e) {}
                                    }
                                }
                            `, context, { timeout: 3000 });
                            await new Promise(r => setImmediate(r));
                        }
                        vm.runInContext(`
                            var b = document.getElementById('downloadbtn');
                            __host.log('V8 post-click btn href=' + (b&&b.href) + ' loc=' + location.href + ' winKeys=' + Object.keys(globalThis).slice(-25).join(','));
                        `, context);
                        console.log(JSON.stringify({ logs, captured }));
                        process.exit(0);
                    })();
                """.trimIndent()
                java.io.File("/tmp/v8_clean.js").writeText(v8Script, Charsets.UTF_8)
                val p = ProcessBuilder("node", "/tmp/v8_clean.js").redirectErrorStream(true).start()
                val v8Out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                val v8Json = runCatching { org.json.JSONObject(v8Out.trim().lines().last()) }.getOrNull()
                println("[DIAG][$pTag] V8_CLEAN_LOGS:
${v8Json?.optJSONArray("logs")?.let { a -> (0 until a.length()).joinToString("
") { a.getString(it) } } ?: v8Out.take(4000)}")
                val capArr = v8Json?.optJSONArray("captured")
                if (capArr != null) {
                    for (ci in 0 until capArr.length()) {
                        val co = capArr.getJSONObject(ci)
                        capturedRequests.add(CapturedHttp(co.optString("kind"), co.optString("method"), co.optString("url"), co.optString("headersJson"), co.optString("body")))
                    }
                }
            }

            val glReq = capturedRequests.firstOrNull { it.url.contains("get-link") || it.method.equals("POST", true) }
            if (glReq != null) {
                val elapsed = System.currentTimeMillis() - tFetchDone
                if (elapsed < 10_500L) kotlinx.coroutines.delay(10_500L - elapsed)
                val targetUrl = when {
                    glReq.url.startsWith("http") -> glReq.url
                    glReq.url.startsWith("/") -> "$gwOrigin${glReq.url}"
                    else -> "${vaultUrl.trimEnd('/')}/${glReq.url.trimStart('/')}"
                }
                val hdrMap = mutableMapOf("X-Requested-With" to "XMLHttpRequest")
                runCatching {
                    val jo = org.json.JSONObject(glReq.headersJson)
                    jo.keys().forEach { k -> hdrMap[k] = jo.optString(k) }
                }
                val mRes = session.postForm(targetUrl, glReq.body, vaultUrl, gwOrigin, hdrMap)
                println("[DIAG][$pTag] LIVE get-link -> code=${mRes?.first} body='${mRes?.second?.take(500)}'")
                val mBody = mRes?.second?.trim().orEmpty()
                val mintedUrl = runCatching {
                    if (mBody.startsWith("{")) {
                        val jo = org.json.JSONObject(mBody)
                        listOf("url", "link", "redirect", "target", "data").firstNotNullOfOrNull { k -> jo.optString(k).takeIf { it.startsWith("http") } } ?: ""
                    } else mBody
                }.getOrDefault(mBody)
                if (mintedUrl.startsWith("http")) {
                    val wRes = session.getText(mintedUrl, vaultUrl)
                    val watchHtml = wRes?.second.orEmpty()
                    println("[DIAG][$pTag] watching -> code=${wRes?.first} len=${watchHtml.length} blocked=${CimaNowNetwork.isCimaBlocked(wRes?.first ?: 0, watchHtml)}")
                    val decoded = CimaNowCrypto.decodeWatchingVault(watchHtml)
                    println("[DIAG][$pTag] decodeWatchingVault -> null=${decoded == null} coreTok='${decoded?.coreToken?.take(24)}' salt='${decoded?.sigSalt}' initEmbed='${decoded?.initialEmbedUrl}' tabs=${decoded?.serverTabs} dls=${decoded?.downloads?.size}")
                    val outerDoc = org.jsoup.Jsoup.parse(watchHtml)
                    outerDoc.select("script").forEachIndexed { idx, s ->
                        val attrs = s.attributes().joinToString(",") { "${it.key}(${it.value.length})" }
                        println("[DIAG][$pTag] WATCH_SCRIPT[$idx] id='${s.id()}' attrs=[$attrs] dataLen=${s.data().length} head='${s.data().take(180).replace("\n", " ")}'")
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
