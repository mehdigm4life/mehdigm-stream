package com.faselhd

import android.R
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object CloudflareSolver {
    private const val SOLVE_TIMEOUT_MS = 35_000L
    private const val TARGET_CSS_PATH = "html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div"

    suspend fun solve(activity: Activity?, url: String, userAgent: String): Document? {
        return suspendCancellableCoroutine { continuation ->
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var webViewRef: WebView? = null
            var pollingHandlerRef: Handler? = null
            var cancelCleanup: (() -> Unit)? = null
            continuation.invokeOnCancellation {
                mainHandler.post {
                    val cleanup = cancelCleanup
                    if (cleanup != null) {
                        cleanup()
                    } else {
                        pollingHandlerRef?.removeCallbacksAndMessages(null)
                        val view = webViewRef
                        webViewRef = null
                        runCatching { view?.stopLoading() }
                        runCatching { (view?.parent as? ViewGroup)?.removeView(view) }
                        runCatching { view?.destroy() }
                    }
                }
            }

            mainHandler.post {
                if (!continuation.isActive) return@post
                if (activity.isFinishing || activity.isDestroyed) {
                    continuation.resume(null)
                    return@post
                }
                val rootView = activity.findViewById<ViewGroup>(R.id.content) ?: run {
                    if (continuation.isActive) continuation.resume(null)
                    return@post
                }

                val webView = WebView(activity)
                webViewRef = webView
                webView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Keep Chromium in the real viewport: off-screen or fully transparent WebViews
                // can fail Cloudflare's viewport checks and make synthetic taps ineffective.
                webView.alpha = 0.01f
                webView.translationX = 0f
                webView.translationY = 0f
                webView.isFocusable = false
                webView.isFocusableInTouchMode = false
                webView.isClickable = false

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    this.userAgentString = userAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    loadsImagesAutomatically = true
                    blockNetworkImage = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                fun clearanceValue(cookieUrl: String): String? = runCatching {
                    cookieManager.getCookie(cookieUrl)
                        ?.split(';')
                        ?.firstOrNull { it.trim().startsWith("cf_clearance=", ignoreCase = true) }
                        ?.substringAfter('=')
                        ?.trim()
                }.getOrNull()

                // Avoid treating an old clearance cookie as proof that this solve succeeded.
                val initialClearance = clearanceValue(url)
                var isSolved = false
                var isProcessingClick = false
                var pollingStarted = false
                var readinessStarted = false
                var fetched = false
                var tapCount = 0
                var lastTapAt = 0L
                var lastUrl: String? = null
                var stableSince = 0L

                val pollingHandler = Handler(Looper.getMainLooper())
                pollingHandlerRef = pollingHandler
                cancelCleanup = {
                    if (!isSolved) {
                        isSolved = true
                        pollingHandler.removeCallbacksAndMessages(null)
                        runCatching { webView.stopLoading() }
                        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                        runCatching { webView.destroy() }
                        webViewRef = null
                        pollingHandlerRef = null
                    }
                }

                fun finishSuccess(html: String?, pageUrl: String = url) {
                    if (isSolved) return
                    isSolved = true
                    cookieManager.flush()
                    pollingHandler.removeCallbacksAndMessages(null)
                    runCatching { webView.stopLoading() }
                    runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                    runCatching { webView.destroy() }
                    webViewRef = null
                    pollingHandlerRef = null

                    if (!continuation.isActive) return
                    if (html == null) {
                        continuation.resume(null)
                        return
                    }
                    val decodedHtml = decodeJavascriptString(html).orEmpty()
                    continuation.resume(Jsoup.parse(decodedHtml, pageUrl))
                }

                pollingHandler.postDelayed({ finishSuccess(null) }, SOLVE_TIMEOUT_MS)

                fun finishWithCurrentPage(pageUrl: String = webView.url ?: url) {
                    if (isSolved || fetched) return
                    fetched = true
                    webView.evaluateJavascript(
                        "document.documentElement ? document.documentElement.outerHTML : (document.body ? document.body.innerHTML : '')",
                    ) { html -> finishSuccess(html, pageUrl) }
                }

                fun simulateRealTouch(view: WebView, cssX: Float, cssY: Float) {
                    val density = activity.resources.displayMetrics.density
                    val realX = cssX * density
                    val realY = cssY * density
                    val downTime = SystemClock.uptimeMillis()
                    val properties = arrayOf(MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    })
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                        x = realX
                        y = realY
                        pressure = 0.7f
                        size = 0.12f
                    })
                    fun makeEvent(action: Int, eventTime: Long) = MotionEvent.obtain(
                        downTime,
                        eventTime,
                        action,
                        1,
                        properties,
                        coordinates,
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        0,
                    )

                    val down = makeEvent(MotionEvent.ACTION_DOWN, downTime)
                    view.dispatchTouchEvent(down)
                    down.recycle()
                    view.postDelayed({
                        val up = makeEvent(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
                        view.dispatchTouchEvent(up)
                        up.recycle()
                    }, 80)
                }

                fun startPolling() {
                    if (pollingStarted || isSolved) return
                    pollingStarted = true
                    val runnable = object : Runnable {
                        override fun run() {
                            if (isSolved) return
                            if (isProcessingClick) {
                                pollingHandler.postDelayed(this, 1500)
                                return
                            }

                            val jsGetCoords = """
                                (function(){
                                    try {
                                        var html = (document.documentElement && document.documentElement.innerHTML || "").toLowerCase();
                                        var title = (document.title || "").toLowerCase();
                                        var text = title + " " + html;
                                        var explicit = /just a moment|checking your browser|checking if the site connection is secure|cf-browser-verification|__cf_chl_opt|cf-chl-widget|challenge-running|cloudflare ray id|verify you are human|attention required/.test(text);
                                        var turnstile = html.indexOf("cf-turnstile") >= 0 && (html.indexOf("_cf_chl_") >= 0 || text.indexOf("verify you are human") >= 0 || title.indexOf("just a moment") >= 0);
                                        if (!explicit && !turnstile) return "NO_CHALLENGE";

                                        var input = document.querySelector('input[name="cf-turnstile-response"], input[id^="cf-chl-widget-"]');
                                        var token = input && input.value ? input.value.length : 0;
                                        var box = null;
                                        if (input) {
                                            var parent = input.parentElement;
                                            while (parent && parent !== document.body) {
                                                var parentRect = parent.getBoundingClientRect();
                                                if (parentRect.width >= 180 && parentRect.height >= 35 && parentRect.height <= 160) {
                                                    box = parent;
                                                    break;
                                                }
                                                parent = parent.parentElement;
                                            }
                                        }
                                        if (!box) box = document.querySelector('input[type="checkbox"]')?.parentElement;
                                        if (!box) box = document.querySelector("$TARGET_CSS_PATH");
                                        if (!box) box = document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"]');
                                        if (!box) return "NO_BOX|" + token;
                                        var rect = box.getBoundingClientRect();
                                        if (rect.width < 1 || rect.height < 1) return "NO_BOX|" + token;
                                        var direction = (document.documentElement.dir || "").toLowerCase();
                                        return "STATE|" + token + "|" + rect.left + "|" + rect.right + "|" + rect.top + "|" + rect.height + "|" + direction;
                                    } catch (e) { return "ERROR"; }
                                })();
                            """.trimIndent()

                            webView.evaluateJavascript(jsGetCoords) { result ->
                                val state = decodeJavascriptString(result).orEmpty().split('|')
                                if (state.size >= 7 && state[0] == "STATE") {
                                    val tokenLength = state[1].toIntOrNull() ?: 0
                                    val left = state[2].toFloatOrNull()
                                    val right = state[3].toFloatOrNull()
                                    val top = state[4].toFloatOrNull()
                                    val height = state[5].toFloatOrNull()
                                    val direction = state[6]
                                    val now = SystemClock.uptimeMillis()
                                    if (tokenLength == 0 && left != null && right != null && top != null && height != null &&
                                        right > left && height > 0 && now - lastTapAt >= 1400
                                    ) {
                                        val preferredSide = tapCount % 2 == 0
                                        val tapRight = if (direction == "rtl") !preferredSide else preferredSide
                                        val x = if (tapRight) right - 28f else left + 28f
                                        val y = top + (height / 2f)
                                        isProcessingClick = true
                                        tapCount++
                                        lastTapAt = now
                                        simulateRealTouch(webView, x, y)
                                        pollingHandler.postDelayed({ isProcessingClick = false }, 1100)
                                    }
                                }
                                pollingHandler.postDelayed(this, 1800)
                            }
                        }
                    }
                    pollingHandler.post(runnable)
                }

                fun waitUntilReady() {
                    if (isSolved) return
                    val js = """
                        (function(){
                            try {
                                var html = (document.documentElement && document.documentElement.innerHTML || "").toLowerCase();
                                var title = (document.title || "").toLowerCase();
                                var text = title + " " + html;
                                var explicit = /just a moment|checking your browser|checking if the site connection is secure|cf-browser-verification|__cf_chl_opt|cf-chl-widget|challenge-running|cloudflare ray id|verify you are human|attention required/.test(text);
                                var turnstile = html.indexOf("cf-turnstile") >= 0 && (html.indexOf("_cf_chl_") >= 0 || text.indexOf("verify you are human") >= 0 || title.indexOf("just a moment") >= 0);
                                var active = explicit || turnstile;
                                return location.href + "|" + document.readyState + "|" + (active ? "true" : "false");
                            } catch (e) { return location.href + "|loading|true"; }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(js) { result ->
                        val parts = decodeJavascriptString(result)?.split('|')
                        if (parts == null || parts.size < 3) {
                            pollingHandler.postDelayed({ waitUntilReady() }, 200)
                            return@evaluateJavascript
                        }

                        val currentUrl = parts[0]
                        val ready = parts[1]
                        val stillCloudflare = parts[2] == "true"
                        val now = SystemClock.uptimeMillis()
                        if (currentUrl != lastUrl) {
                            lastUrl = currentUrl
                            stableSince = now
                        }
                        val stableTime = now - stableSince
                        val currentClearance = clearanceValue(currentUrl)
                        val hasFreshClearance = currentClearance != null && currentClearance != initialClearance

                        if (hasFreshClearance) {
                            finishWithCurrentPage(currentUrl)
                            return@evaluateJavascript
                        }
                        if (!stillCloudflare && ready == "complete" && stableTime > 1200) {
                            finishWithCurrentPage(currentUrl)
                            return@evaluateJavascript
                        }
                        pollingHandler.postDelayed({ waitUntilReady() }, 200)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        isProcessingClick = false
                        startPolling()
                        if (!readinessStarted) {
                            readinessStarted = true
                            waitUntilReady()
                        }
                    }
                }

                rootView.addView(webView)
                webView.loadUrl(url)
            }
        }
    }
}

private fun decodeJavascriptString(result: String?): String? {
    if (result == null) return null
    val decoded = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
    if (decoded != null) return decoded
    return result.removeSurrounding("\"")
        .replace("\\u003C", "<")
        .replace("\\u003E", ">")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
