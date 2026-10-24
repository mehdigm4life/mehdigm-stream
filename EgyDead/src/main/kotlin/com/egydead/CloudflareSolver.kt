package com.egydead

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-lag Android WebView Cloudflare solver & TLS-bound `window.fetch()` transport for EgyDead.
 *
 * Why two WebView engines?
 * 1. [solveAndCaptureHtml]: Used ONCE (serialized behind `EgyDeadNetwork.cfMutex`) when Cloudflare
 *    presents a JS/Turnstile Managed Challenge (`403 Just a moment...`). Runs in a `1x1` invisible
 *    WebView with images, fonts, and ad networks blocked (`shouldInterceptRequest`) so there is
 *    zero UI-thread lag, captures the solved HTML + full Cookie header + native WebView User-Agent,
 *    and immediately destroys the solver WebView.
 * 2. [fetchViaPersistentWebView]: When Cloudflare binds `cf_clearance` to Android Chromium's
 *    BoringSSL JA3/JA4 TLS fingerprint (causing OkHttp retries to still get `403`), this reuses
 *    ONE single `1x1` blank-origin WebView (`<html><body></body></html>` anchored at `<origin>/h3/`)
 *    and executes `window.fetch(url, { credentials: 'include' })` in parallel. Because no DOM, CSS,
 *    images, or third-party scripts are ever rendered, requests finish in ~200-350ms with zero lag.
 */
object CloudflareSolver {
    private const val TAG = "EgyDeadCF"
    private const val IDLE_DESTROY_MS = 120_000L

    data class SolvedPage(
        val finalUrl: String,
        val html: String,
        val cookies: String?,
        val userAgent: String?
    )

    private val mainHandlerRef: Handler? by lazy {
        runCatching { Looper.getMainLooper()?.let { Handler(it) } }.getOrNull()
    }

    private val persistentInitMutex = Mutex()
    private val nextReqId = AtomicInteger(1)

    @Volatile
    private var persistentWebView: WebView? = null

    @Volatile
    private var persistentOrigin: String? = null

    @Volatile
    private var persistentActRef: WeakReference<Activity>? = null

    @Volatile
    private var persistentReady: Boolean = false

    private val idleDestroyRunnable = Runnable {
        destroyPersistentOnMain()
    }

    private val BLOCKED_AD_SNIPPETS = listOf(
        "adfox.",
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "popads.",
        "popcash.",
        "exoclick.",
        "onclick",
        "propellerads",
        "adsterra",
        "acscdn.com",
        "histats.com",
        "yandex.ru/metrika"
    )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun findActiveActivity(): Activity? {
        (EgyDeadPrefs.appContext as? Activity)?.let { act ->
            if (!act.isFinishing && !act.isDestroyed) return act
        }
        CommonActivity.activity?.let { act ->
            if (!act.isFinishing && !act.isDestroyed) return act
        }
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (record in activities.values) {
                if (record == null) continue
                val recordClass = record.javaClass
                val pausedField = recordClass.getDeclaredField("paused").apply { isAccessible = true }
                if (!pausedField.getBoolean(record)) {
                    val activityField = recordClass.getDeclaredField("activity").apply { isAccessible = true }
                    val act = activityField.get(record) as? Activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) return act
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureStealthWebView(wv: WebView, cookieMgr: CookieManager) {
        cookieMgr.setAcceptCookie(true)
        cookieMgr.setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace("Version/4.0 ", "")
                .trim()
        }
    }

    private fun attachHiddenWebView(activity: Activity, wv: WebView) {
        runCatching {
            val root = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            wv.alpha = 0f
            wv.visibility = View.INVISIBLE
            root?.addView(wv, FrameLayout.LayoutParams(1, 1))
        }
    }

    private fun destroyHiddenWebView(wv: WebView?) {
        if (wv == null) return
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.removeAllViews()
            wv.destroy()
        }
    }

    private fun shouldBlockSolverSubresource(reqUrl: String): Boolean {
        val lower = reqUrl.lowercase()
        if (lower.contains("cloudflare.com") || lower.contains("/cdn-cgi/")) return false
        for (ad in BLOCKED_AD_SNIPPETS) {
            if (lower.contains(ad)) return true
        }
        val pathPart = lower.substringBefore('?')
        if (pathPart.endsWith(".jpg") || pathPart.endsWith(".jpeg") ||
            pathPart.endsWith(".png") || pathPart.endsWith(".webp") ||
            pathPart.endsWith(".gif") || pathPart.endsWith(".svg") ||
            pathPart.endsWith(".woff") || pathPart.endsWith(".woff2") ||
            pathPart.endsWith(".ttf") || pathPart.endsWith(".mp4") ||
            pathPart.endsWith(".webm")
        ) {
            return true
        }
        return false
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    internal fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
        return runCatching {
            val arr = JSONArray("[$raw]")
            if (arr.isNull(0)) null else arr.getString(0)
        }.getOrNull()
    }

    private fun dispatchTap(wv: WebView, x: Float, y: Float) {
        runCatching {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 45L, MotionEvent.ACTION_UP, x, y, 0)
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
    }

    /**
     * Solves Cloudflare JS / Turnstile challenge on [targetUrl] inside a `1x1` stealth WebView
     * and returns the already-rendered HTML + full cookie header + WebView User-Agent.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveAndCaptureHtml(
        targetUrl: String,
        referer: String? = null,
        timeoutMs: Long = 22_000L
    ): SolvedPage? {
        val mainHandler = mainHandlerRef ?: return null
        val activity = findActiveActivity() ?: return null
        val deferred = CompletableDeferred<SolvedPage?>()
        val actRef = WeakReference(activity)
        var solverWv: WebView? = null
        val startMs = SystemClock.uptimeMillis()
        var lastTapMs = 0L
        var extractingHtml = false

        val pollRunnable = object : Runnable {
            override fun run() {
                val wv = solverWv ?: return
                if (deferred.isCompleted || extractingHtml) return

                runCatching {
                    val checkJs = """
                        (function() {
                            var d = document.documentElement;
                            var h = d ? d.innerHTML : '';
                            var t = document.title || '';
                            var cfEl = document.querySelector('#challenge-running, #challenge-stage, #cf-please-wait, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]');
                            var isCfTitle = t.indexOf('Just a moment') !== -1 || t.indexOf('Attention Required') !== -1 || t.indexOf('لحظة') !== -1;
                            var hasContent = h.indexOf('movieItem') !== -1 || h.indexOf('BottomTitle') !== -1 || h.indexOf('ServersList') !== -1 || h.indexOf('singleTitle') !== -1 || h.indexOf('EpsList') !== -1 || h.indexOf('posts-list') !== -1;
                            var tx = -1, ty = -1;
                            var ifr = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                            if (ifr) {
                                var r = ifr.getBoundingClientRect();
                                if (r.width > 0 && r.height > 0) {
                                    tx = Math.round(r.left + 28);
                                    ty = Math.round(r.top + (r.height / 2));
                                }
                            }
                            return JSON.stringify({
                                ok: (!isCfTitle && !cfEl && (hasContent || h.length > 8000)),
                                cf: (isCfTitle || !!cfEl),
                                tx: tx,
                                ty: ty
                            });
                        })();
                    """.trimIndent()

                    wv.evaluateJavascript(checkJs) { raw ->
                        if (deferred.isCompleted || extractingHtml) return@evaluateJavascript
                        val decoded = decodeJsString(raw)
                        val status = decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
                        if (status?.optBoolean("ok") == true) {
                            extractingHtml = true
                            val landedUrl = wv.url ?: targetUrl
                            val uaNow = wv.settings.userAgentString
                            val cookieMgr = CookieManager.getInstance()
                            wv.evaluateJavascript(
                                "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()"
                            ) { rawHtml ->
                                val html = decodeJsString(rawHtml).orEmpty()
                                if (EgyDeadNetwork.isValidEgyDeadHtml(200, html)) {
                                    runCatching { cookieMgr.flush() }
                                    val cookies = cookieMgr.getCookie(landedUrl)
                                        ?: EgyDeadNetwork.originOf(landedUrl)?.let { cookieMgr.getCookie(it) }
                                    deferred.complete(SolvedPage(landedUrl, html, cookies, uaNow))
                                } else {
                                    extractingHtml = false
                                    if (!deferred.isCompleted) {
                                        mainHandler.postDelayed(this, 650L)
                                    }
                                }
                            }
                            return@evaluateJavascript
                        }

                        // Gentle Turnstile tap only if challenge iframe is present after 2.8s
                        val now = SystemClock.uptimeMillis()
                        if (status?.optBoolean("cf") == true && now - startMs > 2_800L && now - lastTapMs > 3_200L) {
                            lastTapMs = now
                            val tx = status.optInt("tx", -1)
                            val ty = status.optInt("ty", -1)
                            if (tx > 0 && ty > 0) {
                                dispatchTap(wv, tx.toFloat(), ty.toFloat())
                            }
                        }

                        if (!deferred.isCompleted) {
                            mainHandler.postDelayed(this, 650L)
                        }
                    }
                }.onFailure {
                    if (!deferred.isCompleted) mainHandler.postDelayed(this, 700L)
                }
            }
        }

        mainHandler.post {
            val act = actRef.get()
            if (act == null || act.isFinishing || act.isDestroyed) {
                deferred.complete(null)
                return@post
            }
            try {
                val cookieMgr = CookieManager.getInstance()
                val wv = WebView(act)
                solverWv = wv
                configureStealthWebView(wv, cookieMgr)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (!request.isForMainFrame && shouldBlockSolverSubresource(u)) {
                            return emptyResponse()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(
                            "try{Object.defineProperty(navigator,'webdriver',{get:()=>false});}catch(e){}",
                            null
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mainHandler.removeCallbacks(pollRunnable)
                        mainHandler.postDelayed(pollRunnable, 350L)
                    }
                }
                attachHiddenWebView(act, wv)
                val headers = if (!referer.isNullOrBlank()) mapOf("Referer" to referer) else emptyMap()
                wv.loadUrl(targetUrl, headers)
                mainHandler.postDelayed(pollRunnable, 1_100L)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to init solver WebView: ${t.message}")
                deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mainHandler.removeCallbacks(pollRunnable)
            val wvToDestroy = solverWv
            solverWv = null
            mainHandler.post { destroyHiddenWebView(wvToDestroy) }
        }
    }

    /**
     * Ensures a single lightweight `1x1` persistent WebView anchored at `<origin>/h3/` is ready
     * to execute fast parallel `window.fetch(...)` calls with Chromium's TLS + cookies.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensurePersistentFetchWebView(origin: String): WebView? {
        val mainHandler = mainHandlerRef ?: return null
        persistentInitMutex.withLock {
            val activity = findActiveActivity() ?: return null
            val existing = persistentWebView
            val existingAct = persistentActRef?.get()
            if (existing != null && persistentReady && persistentOrigin == origin &&
                existingAct === activity && !activity.isFinishing && !activity.isDestroyed
            ) {
                scheduleIdleDestroy()
                return existing
            }

            val readyDeferred = CompletableDeferred<WebView?>()
            mainHandler.post {
                destroyPersistentOnMain()
                if (activity.isFinishing || activity.isDestroyed) {
                    readyDeferred.complete(null)
                    return@post
                }
                try {
                    val cookieMgr = CookieManager.getInstance()
                    val wv = WebView(activity)
                    configureStealthWebView(wv, cookieMgr)
                    var completed = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!completed && view != null) {
                                completed = true
                                persistentWebView = view
                                persistentOrigin = origin
                                persistentActRef = WeakReference(activity)
                                persistentReady = true
                                scheduleIdleDestroy()
                                readyDeferred.complete(view)
                            }
                        }
                    }
                    attachHiddenWebView(activity, wv)
                    val anchorUrl = "${origin.trimEnd('/')}/h3/"
                    wv.loadDataWithBaseURL(
                        anchorUrl,
                        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                        "text/html",
                        "UTF-8",
                        anchorUrl
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to init persistent fetch WebView: ${t.message}")
                    readyDeferred.complete(null)
                }
            }
            return withTimeoutOrNull(6_000L) { readyDeferred.await() }
        }
    }

    private fun scheduleIdleDestroy() {
        val h = mainHandlerRef ?: return
        h.removeCallbacks(idleDestroyRunnable)
        h.postDelayed(idleDestroyRunnable, IDLE_DESTROY_MS)
    }

    private fun destroyPersistentOnMain() {
        val old = persistentWebView
        persistentWebView = null
        persistentOrigin = null
        persistentActRef = null
        persistentReady = false
        destroyHiddenWebView(old)
    }

    fun resetPersistentWebView() {
        val h = mainHandlerRef ?: return
        h.removeCallbacks(idleDestroyRunnable)
        h.post { destroyPersistentOnMain() }
    }

    /**
     * Executes an HTTP `GET` or `POST` via `window.fetch` inside the single persistent blank-origin
     * WebView — zero DOM rendering, zero ads/images, genuine Chromium TLS JA4 + HttpOnly cookies.
     */
    suspend fun fetchViaPersistentWebView(
        targetUrl: String,
        method: String = "GET",
        formFields: Map<String, String>? = null,
        referer: String? = null,
        timeoutMs: Long = 16_000L
    ): EgyDeadNetwork.RawResponse? {
        val mainHandler = mainHandlerRef ?: return null
        val origin = EgyDeadNetwork.originOf(targetUrl) ?: EgyDeadNetwork.DEFAULT_BASE_URL
        val wv = ensurePersistentFetchWebView(origin) ?: return null
        scheduleIdleDestroy()

        val reqId = "r${nextReqId.incrementAndGet()}"
        val deferred = CompletableDeferred<EgyDeadNetwork.RawResponse?>()

        val encodedForm = if (!formFields.isNullOrEmpty()) {
            formFields.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
        } else {
            null
        }

        val jsUrl = JSONObject.quote(targetUrl)
        val jsMethod = JSONObject.quote(method.uppercase())
        val jsId = JSONObject.quote(reqId)
        val jsBody = if (encodedForm != null) JSONObject.quote(encodedForm) else "null"
        val jsRef = if (!referer.isNullOrBlank()) JSONObject.quote(referer) else "null"

        val launchScript = """
            (function() {
                window.__edPool = window.__edPool || {};
                var opts = {
                    method: $jsMethod,
                    credentials: 'include',
                    redirect: 'follow',
                    headers: { 'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' }
                };
                if ($jsRef) {
                    opts.referrer = $jsRef;
                }
                if ($jsBody !== null) {
                    opts.headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
                    opts.headers['X-Requested-With'] = 'XMLHttpRequest';
                    opts.body = $jsBody;
                }
                fetch($jsUrl, opts).then(function(r) {
                    return r.text().then(function(txt) {
                        window.__edPool[$jsId] = JSON.stringify({ s: r.status, u: r.url || $jsUrl, b: txt });
                    });
                }).catch(function(e) {
                    window.__edPool[$jsId] = JSON.stringify({ s: 0, u: $jsUrl, b: '' });
                });
            })();
        """.trimIndent()

        val pollScript = """
            (function() {
                if (window.__edPool && Object.prototype.hasOwnProperty.call(window.__edPool, $jsId)) {
                    var v = window.__edPool[$jsId];
                    delete window.__edPool[$jsId];
                    return v;
                }
                return '';
            })();
        """.trimIndent()

        val pollRunnable = object : Runnable {
            override fun run() {
                if (deferred.isCompleted) return
                val activeWv = persistentWebView
                if (activeWv == null) {
                    deferred.complete(null)
                    return
                }
                runCatching {
                    activeWv.evaluateJavascript(pollScript) { raw ->
                        if (deferred.isCompleted) return@evaluateJavascript
                        val decoded = decodeJsString(raw)
                        if (!decoded.isNullOrBlank()) {
                            val obj = runCatching { JSONObject(decoded) }.getOrNull()
                            if (obj != null) {
                                val status = obj.optInt("s", 0)
                                val finalU = obj.optString("u", targetUrl).ifBlank { targetUrl }
                                val body = obj.optString("b", "")
                                if (status == 0 && body.isEmpty()) {
                                    deferred.complete(null)
                                } else {
                                    deferred.complete(
                                        EgyDeadNetwork.RawResponse(
                                            code = status,
                                            body = body,
                                            finalUrl = finalU,
                                            headers = okhttp3.Headers.headersOf()
                                        )
                                    )
                                }
                                return@evaluateJavascript
                            }
                        }
                        if (!deferred.isCompleted) {
                            mainHandler.postDelayed(this, 160L)
                        }
                    }
                }.onFailure {
                    deferred.complete(null)
                }
            }
        }

        mainHandler.post {
            val activeWv = persistentWebView
            if (activeWv == null) {
                deferred.complete(null)
                return@post
            }
            runCatching {
                activeWv.evaluateJavascript(launchScript, null)
                mainHandler.postDelayed(pollRunnable, 150L)
            }.onFailure {
                deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post {
                persistentWebView?.evaluateJavascript(
                    "if(window.__edPool){delete window.__edPool[$jsId];}",
                    null
                )
            }
        }
    }
}
