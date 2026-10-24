package com.egydead

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Live-verified Cloudflare Turnstile (`0x4AAAAAAADnPIDROrmt1Wwj`) solver & persistent same-TLS
 * `window.fetch` transport for EgyDead v19.
 *
 * Key real-device & live-site invariants (verified live against `https://tv10.egydead.live/h3/`):
 * 1. On-screen `(0, 0)` viewport (`translationX = 0f, translationY = 0f`, `MATCH_PARENT`,
 *    `alpha = 0.01f`, inserted at index `0` behind app content): preserves `locationOnScreen = (0, 0)`,
 *    `intersectionRatio == 1.0`, and valid `0 <= rawX < screen.width` for Turnstile's worker while
 *    never intercepting user touches or flashing on screen.
 * 2. Software-rendered layer (`View.LAYER_TYPE_SOFTWARE`): avoids allocating hardware GPU
 *    RenderTarget Surfaces (`IMGSRV: Max RTS IDs (8) reached` on PowerVR Rogue GE8320).
 * 3. 100% native unmodified WebView `userAgentString` & untouched `navigator.webdriver`: guarantees
 *    zero mismatch between `User-Agent`, `Sec-CH-UA`, and `navigator.userAgentData` on WebView 150+.
 * 4. Closed-ShadowDOM outer container lookup (`input[name="cf-turnstile-response"]` parent / non-footer
 *    leaf `div` with no upper width cap for phones, tablets, and Android TV) + `dir`-aware checkbox
 *    coordinates (`r.right - 28` for RTL, `r.left + 28` for LTR) scaled by `displayMetrics.density`
 *    with natural `InputDevice.SOURCE_TOUCHSCREEN` finger `MotionEvent` dispatch.
 */
object CloudflareSolver {
    private const val TAG = "EgyDeadCF"
    private const val SILENT_SOLVE_TIMEOUT_MS = 22_000L
    private const val INTERACTIVE_SOLVE_TIMEOUT_MS = 45_000L
    private const val INTERACTIVE_COOLDOWN_MS = 60_000L
    private const val INITIAL_SETTLE_DELAY_MS = 2_200L
    private const val POLL_INTERVAL_MS = 1_500L
    private const val MIN_TAP_INTERVAL_MS = 2_800L
    private const val PERSISTENT_FETCH_TIMEOUT_MS = 10_000L

    private val uiHandler: Handler? by lazy {
        runCatching { Looper.getMainLooper()?.let { Handler(it) } }.getOrNull()
    }

    /**
     * Test-only hook used exclusively by `EgyDeadLiveE2ETest` on JVM CI (where Android `WebView` is
     * unavailable) to route through a live browser session that solved Turnstile on `tv10.egydead.live`.
     * Always `null` in production Android builds.
     */
    @Volatile
    internal var jvmTestFetchBridge: ((url: String, referer: String, method: String, formBody: Map<String, String>?) -> Pair<Int, String>?)? = null

    @Volatile
    private var persistentWebViewRef: WeakReference<WebView>? = null

    @Volatile
    private var persistentOrigin: String? = null

    @Volatile
    private var lastInteractiveAttemptMs: Long = 0L

    private val persistentMutex = Mutex()
    private val pendingFetches = ConcurrentHashMap<String, (String?) -> Unit>()

    data class SolveResult(
        val html: String,
        val finalUrl: String,
        val cookies: Map<String, String>,
        val userAgent: String
    )

    data class WebFetchResult(
        val status: Int,
        val finalUrl: String,
        val body: String,
        val cookies: Map<String, String>
    )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun currentActivity(): Activity? {
        (EgyDeadPrefs.appContext as? Activity)?.let { act ->
            if (!act.isFinishing && !act.isDestroyed) return act
        }
        runCatching { CommonActivity.activity }.getOrNull()?.let { act ->
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

    fun readCookiesFromWebView(url: String): Map<String, String> = runCatching {
        val raw = CookieManager.getInstance()?.getCookie(url).orEmpty()
        parseCookieHeader(raw)
    }.getOrDefault(emptyMap())

    fun hasValidClearance(url: String): Boolean {
        val cookies = readCookiesFromWebView(url)
        return cookies["cf_clearance"]?.isNotBlank() == true
    }

    fun parseCookieHeader(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split(";").forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    out[name] = value
                }
            }
        }
        return out
    }

    /**
     * Silent background Turnstile solver at `(0, 0)` behind app content (`alpha = 0.01f`,
     * `LAYER_TYPE_SOFTWARE`, 100% native WebView User-Agent).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveSilently(url: String): SolveResult? {
        val handler = uiHandler ?: return null
        val activity = currentActivity() ?: return null
        return withTimeoutOrNull(SILENT_SOLVE_TIMEOUT_MS + 1_500L) {
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                var webView: WebView? = null

                fun cleanupAndResume(result: SolveResult?) {
                    if (!finished.compareAndSet(false, true)) return
                    handler.post {
                        runCatching { CookieManager.getInstance()?.flush() }
                        webView?.let { wv ->
                            runCatching {
                                wv.stopLoading()
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                wv.destroy()
                            }
                        }
                        webView = null
                    }
                    if (cont.isActive) cont.resume(result)
                }

                cont.invokeOnCancellation { cleanupAndResume(null) }

                handler.post {
                    if (activity.isFinishing || activity.isDestroyed) {
                        cleanupAndResume(null)
                        return@post
                    }
                    try {
                        val wv = WebView(activity)
                        webView = wv
                        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                        // Attach ON-SCREEN at (0, 0) at index 0 (behind CloudStream's UI) so
                        // locationOnScreen == (0, 0), intersectionRatio == 1.0, and 0 <= rawX < width,
                        // while never intercepting user touches or flickering visually.
                        val params = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        wv.visibility = View.VISIBLE
                        wv.alpha = 0.01f
                        wv.translationX = 0f
                        wv.translationY = 0f
                        wv.isFocusable = false
                        wv.isFocusableInTouchMode = false
                        wv.isClickable = false

                        val decor = activity.window?.decorView as? ViewGroup
                        if (decor != null) {
                            if (decor.childCount > 0) {
                                decor.addView(wv, 0, params)
                            } else {
                                decor.addView(wv, params)
                            }
                        }

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)

                        val nativeUa = configureNativeBrowserSettings(wv)
                        val startMs = System.currentTimeMillis()
                        var lastTapMs = 0L
                        var tapAttempt = 0
                        var pageLoadedOnce = false

                        fun schedulePoll(delayMs: Long) {
                            handler.postDelayed(object : Runnable {
                                override fun run() {
                                    if (finished.get()) return
                                    val elapsed = System.currentTimeMillis() - startMs
                                    if (elapsed >= SILENT_SOLVE_TIMEOUT_MS) {
                                        Log.w(TAG, "Silent solve cap (${SILENT_SOLVE_TIMEOUT_MS}ms) reached for $url")
                                        cleanupAndResume(null)
                                        return
                                    }
                                    val activeWv = webView ?: return
                                    activeWv.evaluateJavascript(INSPECT_PAGE_AND_TURNSTILE_JS) { rawJson ->
                                        if (finished.get()) return@evaluateJavascript
                                        val parsed = decodeEvalJsonObject(rawJson)
                                        if (parsed == null) {
                                            schedulePoll(POLL_INTERVAL_MS)
                                            return@evaluateJavascript
                                        }

                                        val readyState = parsed.optString("readyState")
                                        val title = parsed.optString("title")
                                        val html = parsed.optString("html")
                                        val currentUrl = parsed.optString("url").ifBlank { activeWv.url ?: url }
                                        val dir = parsed.optString("dir").lowercase()
                                        val hasChallengeMarker = parsed.optBoolean("isChallenge", false)
                                        val hasEgyDeadDom = parsed.optBoolean("hasEgyDeadDom", false)

                                        val stillChallenge = hasChallengeMarker || isCloudflareChallengePage(title, html)
                                        if (!stillChallenge && hasEgyDeadDom &&
                                            (readyState == "complete" || readyState == "interactive")
                                        ) {
                                            val cookies = readCookiesFromWebView(currentUrl) + readCookiesFromWebView(url)
                                            Log.i(TAG, "Silent WebView solved Cloudflare in ${elapsed}ms ($currentUrl)")
                                            cleanupAndResume(
                                                SolveResult(
                                                    html = html,
                                                    finalUrl = currentUrl,
                                                    cookies = cookies,
                                                    userAgent = nativeUa
                                                )
                                            )
                                            return@evaluateJavascript
                                        }

                                        // Tap outer Turnstile host container when checkbox token is still empty
                                        val targetBox = parsed.optJSONObject("target")
                                        val valLen = targetBox?.optInt("valLen", 0) ?: 0
                                        val now = System.currentTimeMillis()
                                        if (stillChallenge && targetBox != null && valLen == 0 &&
                                            (now - lastTapMs) >= MIN_TAP_INTERVAL_MS
                                        ) {
                                            lastTapMs = now
                                            tapAttempt++
                                            val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
                                            val leftCss = targetBox.optDouble("left", 0.0).toFloat()
                                            val rightCss = targetBox.optDouble("right", 0.0).toFloat()
                                            val topCss = targetBox.optDouble("top", 0.0).toFloat()
                                            val heightCss = targetBox.optDouble("height", 65.0).toFloat()
                                            val isRtl = (dir == "rtl")

                                            // Primary: r.right - 28 (RTL) or r.left + 28 (LTR) — proven live!
                                            // Attempt #3 fallback handles mixed outer-RTL + inner-LTR (right - 272).
                                            val targetCssX = if (tapAttempt == 3 && (rightCss - leftCss) >= 300f) {
                                                if (isRtl) (rightCss - 272f).coerceAtLeast(leftCss + 24f)
                                                else (leftCss + 272f).coerceAtMost(rightCss - 24f)
                                            } else {
                                                if (isRtl) (rightCss - 28f) else (leftCss + 28f)
                                            }
                                            val targetCssY = topCss + (heightCss * 0.5f)

                                            val jitterX = (Random.nextFloat() - 0.5f) * 4f * density
                                            val jitterY = (Random.nextFloat() - 0.5f) * 4f * density
                                            val tapPxX = (targetCssX * density + jitterX).coerceAtLeast(8f)
                                            val tapPxY = (targetCssY * density + jitterY).coerceAtLeast(8f)

                                            Log.d(
                                                TAG,
                                                "Turnstile tap #$tapAttempt dir=$dir css=(${targetCssX.toInt()},${targetCssY.toInt()}) px=(${tapPxX.toInt()},${tapPxY.toInt()})"
                                            )
                                            dispatchNaturalFingerTap(activeWv, tapPxX, tapPxY)
                                        }

                                        schedulePoll(POLL_INTERVAL_MS)
                                    }
                                }
                            }, delayMs)
                        }

                        wv.webChromeClient = WebChromeClient()
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                super.onPageFinished(view, loadedUrl)
                                if (!pageLoadedOnce) {
                                    pageLoadedOnce = true
                                    schedulePoll(INITIAL_SETTLE_DELAY_MS)
                                }
                            }
                        }

                        wv.loadUrl(url)
                        // Watchdog start even if onPageFinished stalls on Turnstile subresources
                        handler.postDelayed({
                            if (!finished.get() && !pageLoadedOnce) {
                                pageLoadedOnce = true
                                schedulePoll(400L)
                            }
                        }, 3_200L)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Silent WebView init error: ${t.message}")
                        cleanupAndResume(null)
                    }
                }
            }
        }
    }

    /**
     * 1-tap interactive Turnstile fallback dialog if silent auto-tap is challenged with a hard puzzle.
     * Auto-closes immediately once real EgyDead DOM appears; rate-limited by [INTERACTIVE_COOLDOWN_MS].
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveInteractiveDialog(url: String): SolveResult? {
        val now = System.currentTimeMillis()
        if (now - lastInteractiveAttemptMs < INTERACTIVE_COOLDOWN_MS) return null
        val handler = uiHandler ?: return null
        val activity = currentActivity() ?: return null
        lastInteractiveAttemptMs = now

        return withTimeoutOrNull(INTERACTIVE_SOLVE_TIMEOUT_MS + 2_000L) {
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                var dialog: Dialog? = null
                var webView: WebView? = null

                fun cleanupAndResume(result: SolveResult?) {
                    if (!finished.compareAndSet(false, true)) return
                    handler.post {
                        runCatching { CookieManager.getInstance()?.flush() }
                        webView?.let { wv ->
                            runCatching {
                                wv.stopLoading()
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                wv.destroy()
                            }
                        }
                        webView = null
                        runCatching { dialog?.dismiss() }
                        dialog = null
                    }
                    if (cont.isActive) cont.resume(result)
                }

                cont.invokeOnCancellation { cleanupAndResume(null) }

                handler.post {
                    if (activity.isFinishing || activity.isDestroyed) {
                        cleanupAndResume(null)
                        return@post
                    }
                    try {
                        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
                        dlg.setCancelable(true)
                        dlg.setOnCancelListener { cleanupAndResume(null) }
                        dialog = dlg

                        val root = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundColor(Color.parseColor("#121212"))
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }

                        val header = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setBackgroundColor(Color.parseColor("#1E1E1E"))
                            setPadding(32, 24, 32, 24)
                        }
                        val spinner = ProgressBar(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(48, 48).apply { marginEnd = 24 }
                        }
                        val titleTv = TextView(activity).apply {
                            text = "جاري تجاوز حماية Cloudflare لـ EgyDead (اضغط مربع التحقق إن ظهر)..."
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val closeTv = TextView(activity).apply {
                            text = "إغلاق ✕"
                            setTextColor(Color.parseColor("#FF6B6B"))
                            textSize = 14f
                            setPadding(24, 8, 8, 8)
                            setOnClickListener { cleanupAndResume(null) }
                        }
                        header.addView(spinner)
                        header.addView(titleTv)
                        header.addView(closeTv)
                        root.addView(header)

                        val wv = WebView(activity).apply {
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                0,
                                1f
                            )
                        }
                        webView = wv
                        root.addView(wv)

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)
                        val nativeUa = configureNativeBrowserSettings(wv)

                        val startMs = System.currentTimeMillis()
                        fun pollInteractive() {
                            handler.postDelayed({
                                if (finished.get()) return@postDelayed
                                if (System.currentTimeMillis() - startMs >= INTERACTIVE_SOLVE_TIMEOUT_MS) {
                                    cleanupAndResume(null)
                                    return@postDelayed
                                }
                                val activeWv = webView ?: return@postDelayed
                                activeWv.evaluateJavascript(INSPECT_PAGE_AND_TURNSTILE_JS) { rawJson ->
                                    if (finished.get()) return@evaluateJavascript
                                    val parsed = decodeEvalJsonObject(rawJson)
                                    if (parsed != null) {
                                        val title = parsed.optString("title")
                                        val html = parsed.optString("html")
                                        val currentUrl = parsed.optString("url").ifBlank { activeWv.url ?: url }
                                        val stillChallenge =
                                            parsed.optBoolean("isChallenge", false) || isCloudflareChallengePage(title, html)
                                        val hasEgyDeadDom = parsed.optBoolean("hasEgyDeadDom", false)
                                        if (!stillChallenge && hasEgyDeadDom) {
                                            val cookies = readCookiesFromWebView(currentUrl) + readCookiesFromWebView(url)
                                            cleanupAndResume(
                                                SolveResult(
                                                    html = html,
                                                    finalUrl = currentUrl,
                                                    cookies = cookies,
                                                    userAgent = nativeUa
                                                )
                                            )
                                            return@evaluateJavascript
                                        }
                                    }
                                    pollInteractive()
                                }
                            }, 1_000L)
                        }

                        wv.webChromeClient = WebChromeClient()
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }

                        dlg.setContentView(root)
                        dlg.show()
                        wv.loadUrl(url)
                        pollInteractive()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Interactive dialog error: ${t.message}")
                        cleanupAndResume(null)
                    }
                }
            }
        }
    }

    /**
     * Executes an authenticated same-origin `window.fetch` inside a persistent software-rendered
     * WebView sharing Chromium's BoringSSL TLS session + `CookieManager` (`cf_clearance`).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchViaPersistentWebView(
        url: String,
        referer: String,
        method: String = "GET",
        formBody: Map<String, String>? = null
    ): WebFetchResult? {
        val handler = uiHandler ?: return null
        val activity = currentActivity() ?: return null
        val targetOrigin = extractOrigin(url) ?: return null

        val wv = ensurePersistentWebView(activity, handler, targetOrigin, referer.ifBlank { "$targetOrigin/" })
            ?: return null

        val reqId = UUID.randomUUID().toString().replace("-", "")
        val encodedBody = formBody?.entries?.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val rawJson = withTimeoutOrNull(PERSISTENT_FETCH_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                val done = AtomicBoolean(false)
                pendingFetches[reqId] = { payload ->
                    if (done.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(payload)
                    }
                }
                cont.invokeOnCancellation { pendingFetches.remove(reqId) }

                val js = buildWindowFetchJs(
                    reqId = reqId,
                    url = url,
                    method = method,
                    encodedFormBody = encodedBody
                )
                handler.post {
                    runCatching { wv.evaluateJavascript(js, null) }
                        .onFailure {
                            pendingFetches.remove(reqId)?.invoke(null)
                        }
                }

                // Poll result slot every 90ms (~10x/sec -> sub-500ms turnaround)
                fun pollSlot(attempt: Int) {
                    if (done.get() || attempt > 105) {
                        if (!done.get()) pendingFetches.remove(reqId)?.invoke(null)
                        return
                    }
                    handler.postDelayed({
                        if (done.get()) return@postDelayed
                        runCatching {
                            wv.evaluateJavascript("window.__edFetchSlot && window.__edFetchSlot['$reqId'] || ''") { raw ->
                                val decoded = decodeEvalString(raw)
                                if (decoded.isNotBlank()) {
                                    wv.evaluateJavascript(
                                        "if(window.__edFetchSlot){delete window.__edFetchSlot['$reqId'];}",
                                        null
                                    )
                                    pendingFetches.remove(reqId)?.invoke(decoded)
                                } else {
                                    pollSlot(attempt + 1)
                                }
                            }
                        }.onFailure {
                            pendingFetches.remove(reqId)?.invoke(null)
                        }
                    }, 90L)
                }
                pollSlot(0)
            }
        } ?: return null

        return runCatching {
            val obj = JSONObject(rawJson)
            if (obj.optBoolean("err", false)) return null
            val status = obj.optInt("status", 0)
            val finalUrl = obj.optString("url").ifBlank { url }
            val body = obj.optString("body")
            val cookies = readCookiesFromWebView(finalUrl)
            WebFetchResult(status = status, finalUrl = finalUrl, body = body, cookies = cookies)
        }.getOrNull()
    }

    fun resetPersistentWebView() {
        val handler = uiHandler ?: return
        val existing = persistentWebViewRef?.get()
        persistentWebViewRef = null
        persistentOrigin = null
        if (existing != null) {
            handler.post {
                runCatching {
                    existing.stopLoading()
                    (existing.parent as? ViewGroup)?.removeView(existing)
                    existing.destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensurePersistentWebView(
        activity: Activity,
        handler: Handler,
        origin: String,
        bootstrapUrl: String
    ): WebView? = persistentMutex.withLock {
        val existing = persistentWebViewRef?.get()
        if (existing != null && persistentOrigin == origin && existing.context === activity) {
            return@withLock existing
        }
        resetPersistentWebView()

        withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                handler.post {
                    if (activity.isFinishing || activity.isDestroyed) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                        return@post
                    }
                    try {
                        val wv = WebView(activity)
                        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        val params = FrameLayout.LayoutParams(1, 1)
                        wv.visibility = View.VISIBLE
                        wv.alpha = 0.01f
                        wv.translationX = 0f
                        wv.translationY = 0f
                        wv.isFocusable = false
                        wv.isClickable = false
                        val decor = activity.window?.decorView as? ViewGroup
                        if (decor != null) {
                            if (decor.childCount > 0) decor.addView(wv, 0, params) else decor.addView(wv, params)
                        }

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)
                        configureNativeBrowserSettings(wv)

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                super.onPageFinished(view, loadedUrl)
                                persistentWebViewRef = WeakReference(wv)
                                persistentOrigin = origin
                                if (done.compareAndSet(false, true) && cont.isActive) {
                                    cont.resume(wv)
                                }
                            }
                        }
                        // Load tiny same-origin shell so window.fetch executes on exact origin without network hit
                        wv.loadDataWithBaseURL(
                            bootstrapUrl,
                            "<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>",
                            "text/html",
                            "UTF-8",
                            null
                        )
                    } catch (_: Throwable) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureNativeBrowserSettings(wv: WebView): String {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // Intentionally keep WebView's genuine native userAgentString untouched so
            // User-Agent, Sec-CH-UA, and navigator.userAgentData match 100% bit-for-bit!
        }
        return runCatching { wv.settings.userAgentString.orEmpty() }.getOrDefault("")
    }

    private fun dispatchNaturalFingerTap(view: View, x: Float, y: Float) {
        runCatching {
            val downTime = SystemClock.uptimeMillis()
            val holdMs = Random.nextLong(55L, 95L)
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            val downCoords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 0.68f
                    size = 0.14f
                }
            )
            val down = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                1,
                props,
                downCoords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            view.dispatchTouchEvent(down)
            down.recycle()

            view.postDelayed({
                runCatching {
                    val upTime = SystemClock.uptimeMillis()
                    val upCoords = arrayOf(
                        MotionEvent.PointerCoords().apply {
                            this.x = x + (Random.nextFloat() - 0.5f) * 1.5f
                            this.y = y + (Random.nextFloat() - 0.5f) * 1.5f
                            pressure = 0.0f
                            size = 0.10f
                        }
                    )
                    val up = MotionEvent.obtain(
                        downTime,
                        upTime,
                        MotionEvent.ACTION_UP,
                        1,
                        props,
                        upCoords,
                        0,
                        0,
                        1.0f,
                        1.0f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        0
                    )
                    view.dispatchTouchEvent(up)
                    up.recycle()
                }
            }, holdMs)
        }
    }

    private fun buildWindowFetchJs(
        reqId: String,
        url: String,
        method: String,
        encodedFormBody: String?
    ): String {
        val safeUrl = JSONObject.quote(url)
        val safeMethod = JSONObject.quote(method.uppercase())
        val bodyClause = if (encodedFormBody != null && method.equals("POST", ignoreCase = true)) {
            "headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:${JSONObject.quote(encodedFormBody)},"
        } else {
            ""
        }
        return """
            (function(){
              window.__edFetchSlot = window.__edFetchSlot || {};
              fetch($safeUrl, {
                method: $safeMethod,
                $bodyClause
                credentials: 'include',
                redirect: 'follow'
              }).then(function(r){
                return r.text().then(function(t){
                  window.__edFetchSlot['$reqId'] = JSON.stringify({
                    status: r.status,
                    url: r.url || $safeUrl,
                    body: t
                  });
                });
              }).catch(function(e){
                window.__edFetchSlot['$reqId'] = JSON.stringify({ err: true, msg: String(e) });
              });
            })();
        """.trimIndent()
    }

    private fun extractOrigin(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        val portPart = if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""
        "$scheme://$host$portPart"
    }.getOrNull()

    fun isCloudflareChallengePage(title: String, html: String): Boolean {
        val t = title.lowercase()
        if (t.contains("just a moment") ||
            t.contains("attention required") ||
            t.contains("checking your browser") ||
            t.contains("لحظة") ||
            t.contains("يرجى الانتظار")
        ) return true
        val lower = html.lowercase()
        val hasPositiveSiteDom = lower.contains("movieitem") ||
            lower.contains("singletitle") ||
            lower.contains("serverslist") ||
            lower.contains("epslist")
        if (hasPositiveSiteDom) return false
        return lower.contains("_cf_chl_opt") ||
            lower.contains("/cdn-cgi/challenge-platform/") ||
            lower.contains("challenges.cloudflare.com/turnstile") ||
            lower.contains("cf-turnstile-response") ||
            lower.contains("cf-spinner-please-wait")
    }

    private fun decodeEvalJsonObject(raw: String?): JSONObject? {
        val str = decodeEvalString(raw)
        if (str.isBlank()) return null
        return runCatching { JSONObject(str) }.getOrNull()
    }

    internal fun decodeJsString(raw: String?): String = decodeEvalString(raw)

    internal fun decodeEvalString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            JSONObject("{\"v\":$raw}").getString("v")
        }.getOrElse {
            raw.trim().removeSurrounding("\"")
        }
    }

    private val INSPECT_PAGE_AND_TURNSTILE_JS = """
        (function() {
            try {
                var html = document.documentElement ? document.documentElement.outerHTML : '';
                var title = document.title || '';
                var dir = (document.documentElement && document.documentElement.dir) || '';
                var hasEgyDeadDom = !!(
                    document.querySelector('li.movieItem, .singleTitle, .serversList, .EpsList, .MasterSingleMeta, .search-box') ||
                    (html.length > 8000 && html.toLowerCase().indexOf('egydead') !== -1 && html.indexOf('_cf_chl_opt') === -1 && html.indexOf('challenge-platform') === -1)
                );
                var isChallenge = !hasEgyDeadDom && !!(
                    window._cf_chl_opt ||
                    document.getElementById('challenge-form') ||
                    document.querySelector('script[src*="challenge-platform"], meta[content*="challenges.cloudflare.com"], input[name="cf-turnstile-response"]') ||
                    title.toLowerCase().indexOf('just a moment') !== -1 ||
                    title.indexOf('لحظة') !== -1
                );

                var target = null;
                var inp = document.querySelector('input[name="cf-turnstile-response"], input[id^="cf-chl-widget-"]');
                if (inp && inp.parentElement) {
                    var ir = inp.parentElement.getBoundingClientRect();
                    if (ir.width >= 180 && ir.height >= 40 && ir.height <= 120 && ir.top >= 0) {
                        target = {
                            left: ir.left,
                            right: ir.right,
                            top: ir.top,
                            width: ir.width,
                            height: ir.height,
                            valLen: (inp.value || '').length
                        };
                    }
                }
                if (!target) {
                    var divs = document.querySelectorAll('div');
                    for (var i = 0; i < divs.length; i++) {
                        var d = divs[i];
                        if (d.closest && d.closest('.footer, footer, [role="contentinfo"]')) continue;
                        var r = d.getBoundingClientRect();
                        if (d.children.length === 0 && r.width >= 180 && r.height >= 45 && r.height <= 95 && r.top > 0) {
                            target = {
                                left: r.left,
                                right: r.right,
                                top: r.top,
                                width: r.width,
                                height: r.height,
                                valLen: 0
                            };
                            break;
                        }
                    }
                }

                return JSON.stringify({
                    readyState: document.readyState || '',
                    title: title,
                    url: location.href || '',
                    dir: dir,
                    isChallenge: isChallenge,
                    hasEgyDeadDom: hasEgyDeadDom,
                    target: target,
                    html: (!isChallenge && hasEgyDeadDom) ? html : html.slice(0, 4096)
                });
            } catch (e) {
                return JSON.stringify({ readyState: '', title: '', url: '', dir: '', isChallenge: false, hasEgyDeadDom: false, target: null, html: '' });
            }
        })();
    """.trimIndent()
}
