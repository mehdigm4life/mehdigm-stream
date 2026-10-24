package com.egydead

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
import android.widget.LinearLayout
import android.widget.TextView
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
 * Software-rendered, GPU-safe Android WebView Cloudflare solver, 1-tap interactive fallback,
 * and persistent `window.fetch()` TLS transport for EgyDead.
 *
 * Key real-device fixes verified from Android Logcat (`PowerVR Rogue GE8320`, WebView 150):
 * - Uses `View.LAYER_TYPE_SOFTWARE` on hidden WebViews to prevent exhausting the 8 hardware
 *   GPU Render Target Surfaces (`IMGSRV: Max RTS IDs (8) reached`).
 * - Never tampers with `navigator.webdriver` (Android WebView 150 already has native
 *   `Navigator.prototype.webdriver === false`; redefining an own property triggers Turnstile loops).
 * - Uses `MATCH_PARENT` + `View.VISIBLE` + `alpha = 0.01f` + `translationX = 10000f` so Turnstile's
 *   `IntersectionObserver` and `document.visibilityState` see a real full viewport, and scales CSS
 *   coordinates from Turnstile's outer light-DOM host container by `displayMetrics.density`.
 * - Includes a sleek auto-closing 1-tap interactive dialog fallback (`solveInteractiveDialog`)
 *   with a 60s cooldown if Cloudflare ever enforces human `InputTransferToken` touch verification.
 */
object CloudflareSolver {
    private const val TAG = "EgyDeadCF"
    private const val IDLE_DESTROY_MS = 120_000L
    private const val INTERACTIVE_COOLDOWN_MS = 60_000L

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
    private var lastInteractiveAttemptAtMs: Long = 0L

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
    private fun configureWebView(wv: WebView, cookieMgr: CookieManager, softwareLayer: Boolean) {
        cookieMgr.setAcceptCookie(true)
        cookieMgr.setAcceptThirdPartyCookies(wv, true)
        if (softwareLayer) {
            runCatching { wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        }
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
                .replace(" wv ", " ")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                .trim()
        }
    }

    private fun attachOffscreenFullViewportWebView(activity: Activity, wv: WebView) {
        runCatching {
            val root = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            wv.visibility = View.VISIBLE
            wv.alpha = 0.01f
            wv.translationX = 10_000f
            wv.isFocusable = false
            wv.isFocusableInTouchMode = false
            wv.isClickable = false
            root?.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun attachTinyFetchWebView(activity: Activity, wv: WebView) {
        runCatching {
            val root = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            wv.alpha = 0f
            wv.visibility = View.INVISIBLE
            wv.isFocusable = false
            root?.addView(wv, FrameLayout.LayoutParams(1, 1))
        }
    }

    private fun destroyWebViewSafe(wv: WebView?) {
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
        return pathPart.endsWith(".jpg") || pathPart.endsWith(".jpeg") ||
            pathPart.endsWith(".png") || pathPart.endsWith(".webp") ||
            pathPart.endsWith(".gif") || pathPart.endsWith(".woff") ||
            pathPart.endsWith(".woff2") || pathPart.endsWith(".ttf") ||
            pathPart.endsWith(".mp4") || pathPart.endsWith(".webm")
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

    private fun dispatchCssTap(activity: Activity, wv: WebView, cssX: Float, cssY: Float) {
        runCatching {
            val density = activity.resources.displayMetrics.density
            val realX = cssX * density
            val realY = cssY * density
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0)
            val up = MotionEvent.obtain(downTime, downTime + 55L, MotionEvent.ACTION_UP, realX, realY, 0)
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
    }

    /**
     * Silent off-screen full-viewport Cloudflare Turnstile solver (bounded to [timeoutMs] = 9s).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveAndCaptureHtml(
        targetUrl: String,
        referer: String? = null,
        timeoutMs: Long = 9_000L
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
                val act = actRef.get() ?: return
                if (deferred.isCompleted || extractingHtml) return

                runCatching {
                    // Note: Turnstile renders its iframe inside a closed ShadowRoot, so we locate
                    // the outer light-DOM host container (`div` ~300x65) instead of `iframe`.
                    val checkJs = """
                        (function() {
                            var d = document.documentElement;
                            var h = d ? d.innerHTML : '';
                            var t = document.title || '';
                            var isCfTitle = t.indexOf('Just a moment') !== -1 || t.indexOf('Attention Required') !== -1 || t.indexOf('لحظة') !== -1;
                            var hasCfScript = h.indexOf('_cf_chl_opt') !== -1 || h.indexOf('challenge-platform') !== -1;
                            var hasContent = h.indexOf('movieItem') !== -1 || h.indexOf('BottomTitle') !== -1 || h.indexOf('ServersList') !== -1 || h.indexOf('singleTitle') !== -1 || h.indexOf('EpsList') !== -1 || h.indexOf('posts-list') !== -1;
                            var lx = -1, rx = -1, cy = -1;
                            var box = document.querySelector('html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div') || document.querySelector('#turnstile-wrapper, .cf-turnstile');
                            if (!box && (isCfTitle || hasCfScript)) {
                                var divs = document.querySelectorAll('div');
                                for (var i = 0; i < divs.length; i++) {
                                    var dr = divs[i].getBoundingClientRect();
                                    if (dr.width >= 240 && dr.width <= 380 && dr.height >= 50 && dr.height <= 95) {
                                        box = divs[i];
                                        break;
                                    }
                                }
                            }
                            if (box) {
                                var r = box.getBoundingClientRect();
                                if (r.width > 0 && r.height > 0) {
                                    lx = Math.round(r.left + 28);
                                    rx = Math.round(r.right - 28);
                                    cy = Math.round(r.top + (r.height / 2));
                                }
                            }
                            return JSON.stringify({
                                ok: (!isCfTitle && hasContent),
                                cf: (isCfTitle || hasCfScript),
                                lx: lx,
                                rx: rx,
                                cy: cy
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
                                    if (!deferred.isCompleted) mainHandler.postDelayed(this, 600L)
                                }
                            }
                            return@evaluateJavascript
                        }

                        val now = SystemClock.uptimeMillis()
                        if (status?.optBoolean("cf") == true && now - startMs > 2_400L && now - lastTapMs > 2_500L) {
                            lastTapMs = now
                            val lx = status.optInt("lx", -1)
                            val rx = status.optInt("rx", -1)
                            val cy = status.optInt("cy", -1)
                            if (lx > 0 && cy > 0) {
                                dispatchCssTap(act, wv, lx.toFloat(), cy.toFloat())
                                if (rx > 0) {
                                    mainHandler.postDelayed({
                                        if (!deferred.isCompleted) {
                                            dispatchCssTap(act, wv, rx.toFloat(), cy.toFloat())
                                        }
                                    }, 220L)
                                }
                            }
                        }

                        if (!deferred.isCompleted) {
                            mainHandler.postDelayed(this, 600L)
                        }
                    }
                }.onFailure {
                    if (!deferred.isCompleted) mainHandler.postDelayed(this, 650L)
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
                configureWebView(wv, cookieMgr, softwareLayer = true)
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mainHandler.removeCallbacks(pollRunnable)
                        mainHandler.postDelayed(pollRunnable, 350L)
                    }
                }
                attachOffscreenFullViewportWebView(act, wv)
                val headers = if (!referer.isNullOrBlank()) mapOf("Referer" to referer) else emptyMap()
                wv.loadUrl(targetUrl, headers)
                mainHandler.postDelayed(pollRunnable, 1_000L)
            } catch (t: Throwable) {
                Log.w(TAG, "Silent solver WebView init failed: ${t.message}")
                deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mainHandler.removeCallbacks(pollRunnable)
            val wvToDestroy = solverWv
            solverWv = null
            mainHandler.post { destroyWebViewSafe(wvToDestroy) }
        }
    }

    /**
     * 1-tap Interactive Cloudflare Dialog Fallback: shown ONLY when direct HTTP, automatic sticky
     * proxies, AND silent off-screen WebView all fail (e.g., strict interactive Turnstile).
     * Automatically closes itself the instant `cf_clearance` / EgyDead HTML appears!
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveInteractiveDialog(
        targetUrl: String,
        referer: String? = null,
        timeoutMs: Long = 28_000L
    ): SolvedPage? {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastInteractiveAttemptAtMs < INTERACTIVE_COOLDOWN_MS) return null
        lastInteractiveAttemptAtMs = nowMs

        val mainHandler = mainHandlerRef ?: return null
        val activity = findActiveActivity() ?: return null
        val deferred = CompletableDeferred<SolvedPage?>()
        val actRef = WeakReference(activity)
        var dialogRef: AlertDialog? = null
        var dialogWv: WebView? = null
        var extracting = false

        val pollRunnable = object : Runnable {
            override fun run() {
                val wv = dialogWv ?: return
                if (deferred.isCompleted || extracting) return
                runCatching {
                    wv.evaluateJavascript(
                        "(function(){var h=document.documentElement?document.documentElement.innerHTML:'';var t=document.title||'';return (t.indexOf('Just a moment')===-1 && t.indexOf('لحظة')===-1 && (h.indexOf('movieItem')!==-1 || h.indexOf('BottomTitle')!==-1 || h.indexOf('ServersList')!==-1)) ? 'READY' : 'WAIT';})()"
                    ) { raw ->
                        if (deferred.isCompleted || extracting) return@evaluateJavascript
                        if (decodeJsString(raw) == "READY") {
                            extracting = true
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
                                    extracting = false
                                    if (!deferred.isCompleted) mainHandler.postDelayed(this, 500L)
                                }
                            }
                            return@evaluateJavascript
                        }
                        if (!deferred.isCompleted) mainHandler.postDelayed(this, 500L)
                    }
                }.onFailure {
                    if (!deferred.isCompleted) mainHandler.postDelayed(this, 600L)
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
                val dp = { v: Int ->
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        v.toFloat(),
                        act.resources.displayMetrics
                    ).toInt()
                }
                val container = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#14161F"))
                    setPadding(dp(12), dp(12), dp(12), dp(8))
                }
                val header = TextView(act).apply {
                    text = "تحقق Cloudflare السريع (اضغط على المربع مرة واحدة وستغلق النافذة تلقائياً)"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(10))
                }
                container.addView(header)

                val cookieMgr = CookieManager.getInstance()
                val wv = WebView(act)
                dialogWv = wv
                configureWebView(wv, cookieMgr, softwareLayer = false)
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        mainHandler.removeCallbacks(pollRunnable)
                        mainHandler.postDelayed(pollRunnable, 300L)
                    }
                }
                container.addView(
                    wv,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340))
                )

                val dlg = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setView(container)
                    .setNegativeButton("إلغاء") { _, _ ->
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                    .setOnCancelListener {
                        if (!deferred.isCompleted) deferred.complete(null)
                    }
                    .create()

                dialogRef = dlg
                dlg.show()

                val headers = if (!referer.isNullOrBlank()) mapOf("Referer" to referer) else emptyMap()
                wv.loadUrl(targetUrl, headers)
                mainHandler.postDelayed(pollRunnable, 800L)
            } catch (t: Throwable) {
                Log.w(TAG, "Interactive solver dialog failed: ${t.message}")
                deferred.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mainHandler.removeCallbacks(pollRunnable)
            val dlgToDismiss = dialogRef
            val wvToDestroy = dialogWv
            dialogRef = null
            dialogWv = null
            mainHandler.post {
                runCatching { dlgToDismiss?.dismiss() }
                destroyWebViewSafe(wvToDestroy)
            }
        }
    }

    /**
     * Ensures a single lightweight `1x1` software-rendered WebView anchored at `<origin>/h3/`
     * is ready to execute parallel `window.fetch(...)` calls with Chromium's TLS + cookies.
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
                    configureWebView(wv, cookieMgr, softwareLayer = true)
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
                    attachTinyFetchWebView(activity, wv)
                    val anchorUrl = "${origin.trimEnd('/')}/h3/"
                    wv.loadDataWithBaseURL(
                        anchorUrl,
                        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                        "text/html",
                        "UTF-8",
                        anchorUrl
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Persistent fetch WebView init failed: ${t.message}")
                    readyDeferred.complete(null)
                }
            }
            return withTimeoutOrNull(5_000L) { readyDeferred.await() }
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
        destroyWebViewSafe(old)
    }

    fun resetPersistentWebView() {
        lastInteractiveAttemptAtMs = 0L
        val h = mainHandlerRef ?: return
        h.removeCallbacks(idleDestroyRunnable)
        h.post { destroyPersistentOnMain() }
    }

    suspend fun fetchViaPersistentWebView(
        targetUrl: String,
        method: String = "GET",
        formFields: Map<String, String>? = null,
        referer: String? = null,
        timeoutMs: Long = 14_000L
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
                if ($jsRef) opts.referrer = $jsRef;
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
                        if (!deferred.isCompleted) mainHandler.postDelayed(this, 150L)
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
                mainHandler.postDelayed(pollRunnable, 140L)
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
