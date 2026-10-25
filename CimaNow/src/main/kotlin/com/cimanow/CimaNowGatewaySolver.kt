package com.cimanow

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Stealth Turnstile Gateway Solver for `rm.freex2line.online/loadon/?link=...` -> `/watching/?token=...`.
 *
 * Design guarantees:
 *  1. Zero `window.*` globals, zero `#cs-link-sink` / `#cs-payload` DOM mutations, and zero
 *     `XMLHttpRequest` / `fetch` monkey-patches (defeating `s_0` and `js-confuser` `s_10..14` traps).
 *  2. Real Android `MotionEvent` touch dispatch (`event.isTrusted === true`) with CSS-to-View density
 *     scaling after centering `#downloadbtn` / Turnstile widget inside the viewport.
 *  3. Hard 28-second timeout + guaranteed `Main`-thread `WebView` teardown so `loadLinks` can never hang.
 *  4. 15-minute in-memory token cache per canonical item URL so re-entering the player is instant (< 0.3s).
 */
object CimaNowGatewaySolver {

    private const val TOKEN_TTL_MS = 15 * 60 * 1000L
    private const val WEBVIEW_TIMEOUT_MS = 28_000L

    private data class CachedToken(val mintedWatchingUrl: String, val expiresAtMs: Long)

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()
    private val solverMutex = Mutex()

    private fun cacheKey(itemUrl: String): String =
        runCatching { URLDecoder.decode(itemUrl, "UTF-8") }
            .getOrDefault(itemUrl)
            .trim()
            .substringBefore('?')
            .trimEnd('/')
            .lowercase()

    fun getCachedWatchingUrl(itemUrl: String): String? {
        val key = cacheKey(itemUrl)
        val hit = tokenCache[key] ?: return null
        if (hit.expiresAtMs > System.currentTimeMillis()) return hit.mintedWatchingUrl
        tokenCache.remove(key)
        return null
    }

    fun invalidateWatchingUrl(itemUrl: String) {
        tokenCache.remove(cacheKey(itemUrl))
    }

    suspend fun mintWatchingUrl(shineUrl: String, itemUrl: String): String? {
        getCachedWatchingUrl(itemUrl)?.let { return it }

        return solverMutex.withLock {
            getCachedWatchingUrl(itemUrl)?.let { return@withLock it }
            val cleanShine = unwrapHrefLi(shineUrl)
                ?: shineUrl.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@withLock null

            val minted = if (Build.VERSION.SDK_INT > 0 && CommonActivity.activity != null) {
                mintViaAndroidWebView(cleanShine, itemUrl)
                    ?: mintViaAndroidWebView(cleanShine, itemUrl)
            } else if (Build.VERSION.SDK_INT == 0) {
                mintViaHeadlessChromiumCli(cleanShine, itemUrl)
                    ?: mintViaHeadlessChromiumCli(cleanShine, itemUrl)
            } else {
                null
            }

            if (!minted.isNullOrBlank() && isMintedWatchingUrl(minted)) {
                tokenCache[cacheKey(itemUrl)] = CachedToken(minted, System.currentTimeMillis() + TOKEN_TTL_MS)
                minted
            } else {
                null
            }
        }
    }

    private fun isMintedWatchingUrl(url: String?): Boolean {
        val u = url?.trim() ?: return false
        return u.startsWith("http") && u.contains("/watching/") && u.contains("token=")
    }

    private fun unwrapHrefLi(url: String): String? {
        val clean = url.trim()
        return when {
            clean.startsWith("https://href.li/?", ignoreCase = true) -> clean.substring("https://href.li/?".length).trim()
            clean.startsWith("http://href.li/?", ignoreCase = true) -> clean.substring("http://href.li/?".length).trim()
            else -> null
        }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isAllowedGatewayNavigation(url: String): Boolean {
        val host = runCatching { URI(CimaNowNetwork.encodeUrl(url)).host?.lowercase().orEmpty() }.getOrDefault("")
        if (host.isEmpty()) return true
        return host.contains("freex2line.") ||
            host.contains("cimanow") ||
            host.contains("cloudflare.") ||
            host.contains("href.li") ||
            host.contains("google") ||
            host.contains("gstatic.")
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private suspend fun mintViaAndroidWebView(shineUrl: String, itemUrl: String): String? {
        val activity = CommonActivity.activity ?: return null
        val deferred = CompletableDeferred<String?>()
        var webViewRef: WebView? = null

        return try {
            withContext(Dispatchers.Main) {
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    ?: run {
                        deferred.complete(null)
                        return@withContext
                    }

                val wv = WebView(activity)
                webViewRef = wv

                // Keep View.VISIBLE with MATCH_PARENT geometry translated off-screen so user touches
                // never hit WebView, while direct wv.dispatchTouchEvent(MotionEvent) operates in local
                // coordinates and requestAnimationFrame / layout checks run unthrottled.
                wv.alpha = 0.01f
                wv.visibility = View.VISIBLE
                wv.translationX = 10_000f
                wv.translationY = 10_000f
                wv.isFocusable = false
                wv.isFocusableInTouchMode = false
                wv.isClickable = false

                val rawDefaultUa = runCatching { WebSettings.getDefaultUserAgent(activity) }
                    .getOrDefault(CimaNowNetwork.MOBILE_UA)
                val cleanChromeUa = rawDefaultUa
                    .replace("; wv)", ")")
                    .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                    .trim()
                    .ifBlank { CimaNowNetwork.MOBILE_UA }

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    loadsImagesAutomatically = true
                    blockNetworkImage = false
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = false
                    userAgentString = cleanChromeUa
                }

                // Swallow target="_blank" popups/logos so they never hijack the main WebView
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean = false
                }

                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)
                }

                wv.webViewClient = object : WebViewClient() {
                    private fun interceptUrl(view: WebView?, url: String?): Boolean {
                        val target = url?.trim().orEmpty()
                        if (target.isEmpty()) return false
                        if (isMintedWatchingUrl(target)) {
                            if (!deferred.isCompleted) deferred.complete(target)
                            runCatching { view?.stopLoading() }
                            return true
                        }
                        if (target.startsWith("http") && !isAllowedGatewayNavigation(target)) {
                            return true
                        }
                        return false
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (request != null && !request.isForMainFrame) return false
                        return interceptUrl(view, request?.url?.toString())
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return interceptUrl(view, url)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        val target = url?.trim().orEmpty()
                        if (isMintedWatchingUrl(target)) {
                            if (!deferred.isCompleted) deferred.complete(target)
                            runCatching { view?.stopLoading() }
                        }
                    }
                }

                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                root.addView(wv, params)
                wv.loadUrl(
                    CimaNowNetwork.encodeUrl(shineUrl),
                    mapOf("Referer" to CimaNowNetwork.encodeUrl(itemUrl)),
                )
            }

            withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                val startedMs = SystemClock.uptimeMillis()
                var firstBtnSeenMs = 0L
                var lastTsTapMs = 0L
                var lastBtnTapMs = 0L
                var tsTapCount = 0

                // Let initial gateway redirects + non-interactive Turnstile run undisturbed first
                delay(3_500L)

                while (!deferred.isCompleted) {
                    delay(500L)
                    if (deferred.isCompleted) break

                    val nowMs = SystemClock.uptimeMillis()
                    val elapsedMs = nowMs - startedMs
                    // Once blog-post.html (#downloadbtn) appears, give Stage B 7.0s undisturbed so Turnstile solves cleanly
                    if (firstBtnSeenMs > 0L && nowMs - firstBtnSeenMs < 7_000L) continue
                    val allowBtnForce = firstBtnSeenMs > 0L && (nowMs - firstBtnSeenMs >= 11_000L)
                    val allowTsTap = if (firstBtnSeenMs == 0L) {
                        elapsedMs >= 4_500L
                    } else {
                        nowMs - firstBtnSeenMs >= 7_500L
                    }
                    val probeResult = withContext(Dispatchers.Main) {
                        val wv = webViewRef ?: return@withContext ""
                        wv.url?.let { cur ->
                            if (isMintedWatchingUrl(cur)) {
                                if (!deferred.isCompleted) deferred.complete(cur)
                                return@withContext "DONE"
                            }
                        }
                        evalJsSuspend(wv, buildReadOnlyGatewayProbeJs(allowBtnForce, allowTsTap))
                    }

                    if (deferred.isCompleted || probeResult == "DONE") break
                    if (firstBtnSeenMs == 0L && probeResult.contains("HASBTN")) {
                        firstBtnSeenMs = SystemClock.uptimeMillis()
                    }

                    when {
                        probeResult.startsWith("URL:") -> {
                            val u = probeResult.removePrefix("URL:").trim()
                            if (isMintedWatchingUrl(u)) {
                                deferred.complete(u)
                                break
                            }
                        }
                        probeResult.startsWith("BTN:") -> {
                            if (firstBtnSeenMs == 0L) firstBtnSeenMs = nowMs
                            if (nowMs - lastBtnTapMs >= 1_800L) {
                                lastBtnTapMs = nowMs
                                val coords = parseCssCoords(probeResult.removePrefix("BTN:"))
                                if (coords != null) {
                                    dispatchTrustedTap(webViewRef, coords.first, coords.second)
                                }
                            }
                        }
                        probeResult.startsWith("TS:") -> {
                            val payload = probeResult.removePrefix("TS:").removeSuffix(":HASBTN")
                            if (nowMs - lastTsTapMs >= 5_000L) {
                                lastTsTapMs = nowMs
                                val sides = payload.split('|')
                                val primary = sides.getOrNull(tsTapCount % sides.size)?.let { parseCssCoords(it) }
                                tsTapCount++
                                if (primary != null) {
                                    dispatchTrustedTap(webViewRef, primary.first, primary.second)
                                }
                            }
                        }
                    }
                }
                deferred.await()
            }
        } catch (_: Throwable) {
            null
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { CookieManager.getInstance().flush() }
                webViewRef?.let { wv ->
                    runCatching {
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                        wv.loadUrl("about:blank")
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.removeAllViews()
                        wv.destroy()
                    }
                }
                webViewRef = null
            }
        }
    }

    private fun buildReadOnlyGatewayProbeJs(countdownElapsed: Boolean, allowTurnstileTap: Boolean): String {
        val forceFlag = if (countdownElapsed) "true" else "false"
        val tsTapFlag = if (allowTurnstileTap) "true" else "false"
        return """
            (function(){
                try {
                    var loc = String(location.href || '');
                    if (loc.indexOf('/watching/') !== -1 && loc.indexOf('token=') !== -1) return 'URL:' + loc;
                    var btn = document.getElementById('downloadbtn');
                    var h = btn ? String(btn.getAttribute('href') || '') : '';
                    if (btn) {
                        if (h.indexOf('http') === 0 && h.indexOf('/watching/') !== -1 && h.indexOf('token=') !== -1) {
                            return 'URL:' + h;
                        }
                        if (h === '#watch-ready' || ($forceFlag && h && h !== '#' && h !== 'javascript:void(0);' && h.indexOf('blog-post_75') === -1)) {
                            if (btn.hidden || getComputedStyle(btn).display === 'none') {
                                btn.removeAttribute('hidden');
                                btn.style.setProperty('display', 'inline-block', 'important');
                            }
                            btn.scrollIntoView({block: 'center', inline: 'center'});
                            var br = btn.getBoundingClientRect();
                            if (br.width > 4 && br.height > 4) {
                                return 'BTN:' + Math.round(br.left + br.width / 2) + ',' + Math.round(br.top + br.height / 2);
                            }
                        }
                    }
                    if ($tsTapFlag && h !== '#watch-ready') {
                        var candidates = [
                            '.cf-turnstile',
                            '[data-sitekey]',
                            '#challenge-stage > div',
                            'html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div',
                            'iframe[src*="challenges.cloudflare.com"]'
                        ];
                        var box = null;
                        for (var ci = 0; ci < candidates.length; ci++) {
                            var el = document.querySelector(candidates[ci]);
                            if (el) {
                                var r0 = el.getBoundingClientRect();
                                if (r0.width >= 40 && r0.height >= 25 && r0.height <= 160) { box = el; break; }
                            }
                        }
                        if (!box) {
                            var divs = document.querySelectorAll('div');
                            for (var di = 0; di < divs.length; di++) {
                                var dr = divs[di].getBoundingClientRect();
                                if (dr.width >= 240 && dr.width <= 340 && dr.height >= 55 && dr.height <= 80) {
                                    box = divs[di];
                                    break;
                                }
                            }
                        }
                        if (box) {
                            box.scrollIntoView({block: 'center', inline: 'center'});
                            var tr = box.getBoundingClientRect();
                            if (tr.width >= 40 && tr.height >= 25 && tr.top >= 0 && tr.left >= 0) {
                                var cy = Math.round(tr.top + tr.height / 2);
                                var lx = Math.round(tr.left + 28);
                                var rx = Math.round(tr.right - 28);
                                var isRtl = (navigator.language || '').toLowerCase().indexOf('ar') === 0;
                                var pair = isRtl ? (rx + ',' + cy + '|' + lx + ',' + cy) : (lx + ',' + cy + '|' + rx + ',' + cy);
                                return 'TS:' + pair + (btn ? ':HASBTN' : '');
                            }
                        }
                    }
                    return btn ? 'WAIT:HASBTN' : 'WAIT';
                } catch(e) {}
                return 'WAIT';
            })();
        """.trimIndent()
    }

    private fun parseCssCoords(s: String): Pair<Float, Float>? {
        val parts = s.split(',')
        if (parts.size < 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        return x to y
    }

    private suspend fun dispatchTrustedTap(wv: WebView?, cssX: Float, cssY: Float) {
        if (wv == null) return
        val downAt = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            runCatching {
                val density = wv.resources.displayMetrics.density.coerceAtLeast(1f)
                val realX = (cssX * density).coerceIn(2f, (wv.width - 2).coerceAtLeast(4).toFloat())
                val realY = (cssY * density).coerceIn(2f, (wv.height - 2).coerceAtLeast(4).toFloat())
                val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, realX, realY, 0)
                wv.dispatchTouchEvent(down)
                down.recycle()
            }
        }
        delay(55L)
        withContext(Dispatchers.Main) {
            runCatching {
                val density = wv.resources.displayMetrics.density.coerceAtLeast(1f)
                val realX = (cssX * density).coerceIn(2f, (wv.width - 2).coerceAtLeast(4).toFloat())
                val realY = (cssY * density).coerceIn(2f, (wv.height - 2).coerceAtLeast(4).toFloat())
                val upAt = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downAt, upAt, MotionEvent.ACTION_UP, realX, realY, 0)
                wv.dispatchTouchEvent(up)
                up.recycle()
            }
        }
    }

    private suspend fun evalJsSuspend(wv: WebView, script: String): String =
        withTimeoutOrNull(2_000L) {
            val d = CompletableDeferred<String>()
            wv.evaluateJavascript(script) { raw ->
                val unquoted = if (raw != null && raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                } else {
                    raw.orEmpty()
                }
                if (!d.isCompleted) d.complete(unquoted)
            }
            d.await()
        }.orEmpty()

    // ── Headless JVM / CI Live Verification Minter (Unused on Android APK) ───

    private suspend fun mintViaHeadlessChromiumCli(shineUrl: String, itemUrl: String): String? =
        withContext(Dispatchers.IO) {
            val nodeBin = listOf("/usr/bin/node", "/usr/local/bin/node")
                .firstOrNull { File(it).canExecute() } ?: "node"
            val chromeBin = listOf(
                "/usr/bin/google-chrome-stable",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium-browser",
                "/usr/bin/chromium",
            ).firstOrNull { File(it).canExecute() } ?: return@withContext null

            val port = (19200..19899).random()
            val tmpJs = File.createTempFile("cimanow_gw_", ".js")
            val outFile = File.createTempFile("cimanow_gw_out_", ".json")
            val script = """
                const { spawn } = require('child_process');
                const http = require('http');
                const fs = require('fs');
                const port = $port;
                const outPath = ${JSONObject.quote(outFile.absolutePath)};
                const shineUrl = ${JSONObject.quote(CimaNowNetwork.encodeUrl(shineUrl))};
                const refererUrl = ${JSONObject.quote(CimaNowNetwork.encodeUrl(itemUrl))};
                const chromeBin = ${JSONObject.quote(chromeBin)};
                const profDir = '/tmp/cn_chr_' + process.pid + '_' + port;
                const args = [
                    '--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage',
                    '--no-first-run', '--no-default-browser-check',
                    '--user-data-dir=' + profDir,
                    '--disable-blink-features=AutomationControlled',
                    '--window-size=412,915',
                    '--remote-debugging-port=' + port,
                    'about:blank'
                ];
                const xvfb = '/usr/bin/xvfb-run';
                let procErr = '';
                const proc = fs.existsSync(xvfb)
                    ? spawn(xvfb, ['-a', chromeBin, ...args], { stdio: ['ignore', 'ignore', 'pipe'] })
                    : spawn(chromeBin, ['--headless=new', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
                if (proc.stderr) proc.stderr.on('data', c => { if (procErr.length < 500) procErr += c.toString('utf8'); });
                function cleanup(out) {
                    try { fs.writeFileSync(outPath, JSON.stringify(out), 'utf8'); } catch(e) {}
                    try { if (proc.stderr) proc.stderr.destroy(); } catch(e) {}
                    try { proc.kill('SIGKILL'); } catch(e) {}
                    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch(e) {}
                    process.exit(0);
                }
                function getJson(u) {
                    return new Promise((res, rej) => {
                        http.get(u, r => {
                            let d = ''; r.on('data', c => d += c);
                            r.on('end', () => { try { res(JSON.parse(d)); } catch(e) { rej(e); } });
                        }).on('error', rej);
                    });
                }
                (async () => {
                    let targets = null;
                    for (let i = 0; i < 35; i++) {
                        try { targets = await getJson('http://127.0.0.1:' + port + '/json'); if (targets.length) break; } catch(e) {}
                        await new Promise(r => setTimeout(r, 400));
                    }
                    if (!targets || !targets.length) return cleanup({ url: '', diag: ['no_targets', 'xvfb=' + fs.existsSync(xvfb), 'err=' + procErr] });
                    const t = targets.find(x => x.type === 'page') || targets[0];
                    const ws = new WebSocket(t.webSocketDebuggerUrl);
                    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
                    let id = 1;
                    const pending = new Map();
                    let getLinkReqId = '';
                    let getLinkDone = false;
                    let minted = '';
                    const diag = [];
                    ws.onmessage = ev => {
                        const m = JSON.parse(ev.data);
                        if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
                        if (m.method === 'Network.requestWillBeSent') {
                            const u = m.params.request.url || '';
                            if (u.includes('get-link.php')) {
                                getLinkReqId = m.params.requestId;
                                diag.push('req:get-link');
                            }
                            if (u.includes('/watching/') && u.includes('token=')) minted = u;
                        }
                        if (m.method === 'Network.loadingFinished' && getLinkReqId && m.params.requestId === getLinkReqId) {
                            getLinkDone = true;
                        }
                    };
                    const send = (method, params = {}) => new Promise(r => { const cid = id++; pending.set(cid, r); ws.send(JSON.stringify({ id: cid, method, params })); });
                    await send('Page.enable');
                    await send('Network.enable');
                    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });
                    let lastTsClick = 0, lastBtnClick = 0, firstBtnMs = 0;
                    const tap = async (x, y) => {
                        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
                        await new Promise(r => setTimeout(r, 60));
                        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
                    };
                    for (let ms = 0; ms < 34000; ms += 500) {
                        await new Promise(r => setTimeout(r, 500));
                        if (getLinkReqId && (getLinkDone || ms >= 9000) && !minted) {
                            const rb = await send('Network.getResponseBody', { requestId: getLinkReqId });
                            if (rb && rb.result && rb.result.body) {
                                const txt = rb.result.base64Encoded ? Buffer.from(rb.result.body, 'base64').toString('utf8') : rb.result.body;
                                diag.push('glBody=' + txt.slice(0, 120));
                                try { const j = JSON.parse(txt); if (j && j.url) minted = j.url; } catch(e) { if (txt.trim().startsWith('http')) minted = txt.trim(); }
                            }
                        }
                        if (minted) {
                            return cleanup({ url: minted, diag });
                        }
                        // Let initial redirect + non-interactive Turnstile run undisturbed for first 4.5s
                        if (ms < 4500) continue;
                        // Once blog-post.html (#downloadbtn) appears, give Stage B 7.0s undisturbed before probing/tapping
                        if (firstBtnMs > 0 && ms - firstBtnMs < 7000) continue;
                        const allowBtnForce = firstBtnMs > 0 && (ms - firstBtnMs >= 11000);
                        const allowTsTap = firstBtnMs === 0 ? (ms >= 4500) : (ms - firstBtnMs >= 7500);
                        const probe = await send('Runtime.evaluate', {
                            expression: `(function(){
                                try {
                                    var loc = String(location.href || '');
                                    var ttl = String(document.title || '').slice(0, 28);
                                    if (loc.indexOf('/watching/') !== -1 && loc.indexOf('token=') !== -1) return 'URL:' + loc;
                                    var btn = document.getElementById('downloadbtn');
                                    var h = btn ? String(btn.getAttribute('href') || '') : '';
                                    if (btn) {
                                        if (h.indexOf('http') === 0 && h.indexOf('/watching/') !== -1 && h.indexOf('token=') !== -1) return 'URL:' + h;
                                        if (h === '#watch-ready' || (${'$'}{allowBtnForce} && h && h !== '#' && h !== 'javascript:void(0);' && h.indexOf('blog-post_75') === -1)) {
                                            if (btn.hidden || getComputedStyle(btn).display === 'none') {
                                                btn.removeAttribute('hidden');
                                                btn.style.setProperty('display', 'inline-block', 'important');
                                            }
                                            btn.scrollIntoView({block:'center', inline:'center'});
                                            var br = btn.getBoundingClientRect();
                                            if (br.width > 4 && br.height > 4) return 'BTN:' + Math.round(br.left + br.width/2) + ',' + Math.round(br.top + br.height/2);
                                        }
                                    }
                                    if (${'$'}{allowTsTap} && h !== '#watch-ready') {
                                        var sels = [
                                            '.cf-turnstile',
                                            '[data-sitekey]',
                                            '#challenge-stage > div',
                                            'html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div',
                                            'iframe[src*="challenges.cloudflare.com"]'
                                        ];
                                        var box = null;
                                        for (var i = 0; i < sels.length; i++) {
                                            var el = document.querySelector(sels[i]);
                                            if (el) {
                                                var r0 = el.getBoundingClientRect();
                                                if (r0.width >= 40 && r0.height >= 25 && r0.height <= 160) { box = el; break; }
                                            }
                                        }
                                        if (!box) {
                                            var divs = document.querySelectorAll('div');
                                            for (var di = 0; di < divs.length; di++) {
                                                var dr = divs[di].getBoundingClientRect();
                                                if (dr.width >= 240 && dr.width <= 340 && dr.height >= 55 && dr.height <= 80) {
                                                    box = divs[di];
                                                    break;
                                                }
                                            }
                                        }
                                        if (box) {
                                            box.scrollIntoView({block:'center', inline:'center'});
                                            var tr = box.getBoundingClientRect();
                                            if (tr.width >= 40 && tr.height >= 25 && tr.top >= 0 && tr.left >= 0) {
                                                var cy = Math.round(tr.top + tr.height/2);
                                                var isRtl = (navigator.language || '').toLowerCase().indexOf('ar') === 0;
                                                var tx = isRtl ? Math.round(tr.right - 28) : Math.round(tr.left + 28);
                                                return 'TS:' + tx + ',' + cy + (btn ? ':HASBTN' : '');
                                            }
                                        }
                                    }
                                    return 'WAIT:btn=' + (!!btn) + ',h=' + h;
                                } catch(e) { return 'ERR:' + e.message; }
                            })()`,
                            returnByValue: true
                        });
                        const pv = (probe && probe.result && probe.result.result && probe.result.result.value) ? String(probe.result.result.value) : '';
                        if (!firstBtnMs && (pv.includes('btn=true') || pv.includes(':HASBTN') || pv.startsWith('BTN:'))) {
                            firstBtnMs = ms;
                        }
                        if (ms % 6000 === 0) diag.push(ms + ':' + pv);
                        if (pv.startsWith('URL:')) {
                            return cleanup({ url: pv.slice(4), diag });
                        } else if (pv.startsWith('BTN:') && ms - lastBtnClick >= 2000) {
                            lastBtnClick = ms;
                            const [bx, by] = pv.slice(4).split(',').map(Number);
                            if (bx > 0 && by > 0) await tap(bx, by);
                        } else if (pv.startsWith('TS:') && ms - lastTsClick >= 5000) {
                            lastTsClick = ms;
                            const clean = pv.slice(3).replace(':HASBTN', '');
                            const [tx, ty] = clean.split(',').map(Number);
                            if (tx > 0 && ty > 0) await tap(tx, ty);
                        }
                    }
                    cleanup({ url: minted, diag });
                })().catch(e => cleanup({ url: '', diag: ['catch:' + (e && e.message)] }));
            """.trimIndent()

            try {
                tmpJs.writeText(script, Charsets.UTF_8)
                val p = ProcessBuilder(nodeBin, tmpJs.absolutePath)
                    .redirectErrorStream(true)
                    .redirectOutput(File("/dev/null"))
                    .start()
                val finished = p.waitFor(46L, TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                }
                val jsonText = if (outFile.exists()) outFile.readText(Charsets.UTF_8).trim() else ""
                val obj = CimaNowCrypto.safeJsonObject(jsonText)
                val minted = obj?.optString("url")?.trim()?.takeIf { isMintedWatchingUrl(it) }
                if (minted == null) {
                    CimaNowExtractors.debugLogger?.invoke("gwCli miss json=${jsonText.take(260)}")
                }
                minted
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { tmpJs.delete() }
                runCatching { outFile.delete() }
            }
        }
}
