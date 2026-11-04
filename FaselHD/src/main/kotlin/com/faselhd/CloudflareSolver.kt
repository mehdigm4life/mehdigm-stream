package com.faselhd

import android.R
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object CloudflareSolver {
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
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webView.alpha = 0f
                webView.translationX = 10000f
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
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isSolved = false
                var isProcessingClick = false
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
                    if (!isSolved) {
                        isSolved = true
                        cookieManager.flush()
                        try {
                            pollingHandler.removeCallbacksAndMessages(null)
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            webView.destroy()
                            webViewRef = null
                            pollingHandlerRef = null
                        } catch (e: Exception) {}

                        if (html == null) {
                            if (continuation.isActive) continuation.resume(null)
                            return
                        }

                        val cleanHtml = html.removeSurrounding("\"")
                            .replace("\\u003C", "<")
                            .replace("\\u003E", ">")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")

                        if (continuation.isActive) continuation.resume(Jsoup.parse(cleanHtml, pageUrl))
                    }
                }

                pollingHandler.postDelayed({ finishSuccess(null) }, 60000)

                fun simulateRealTouch(view: WebView, cssX: Float, cssY: Float) {
                    val density = activity.resources.displayMetrics.density
                    val realX = cssX * density
                    val realY = cssY * density
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis() + 50
                    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0)
                    view.dispatchTouchEvent(downEvent)
                    view.postDelayed({
                        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, realX, realY, 0)
                        view.dispatchTouchEvent(upEvent)
                        downEvent.recycle()
                        upEvent.recycle()
                    }, 50)
                }

                val targetCssPath = "html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div"

                fun startPolling() {
                    val runnable = object : Runnable {
                        override fun run() {
                            if (isSolved) return
                            if (isProcessingClick) {
                                pollingHandler.postDelayed(this, 2000)
                                return
                            }

                            val jsGetCoords = """
                                (function(){
                                    try{
                                        var html = document.documentElement.innerHTML || "";
                                        var challengeText = (document.title + " " + html).toLowerCase();
                                        var active = /just a moment|checking your browser|cf-browser-verification|__cf_chl_opt|cf-chl-widget|cloudflare ray id/.test(challengeText);
                                        if (!active) return "NO_CHALLENGE";
                                        var box = document.querySelector('input[type="checkbox"]')?.parentElement
                                            || document.querySelector("$targetCssPath")
                                            || document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                                        if (!box) return "NO_BOX";
                                        var r = box.getBoundingClientRect();
                                        if (r.width < 1 || r.height < 1) return "NO_BOX";
                                        return (r.left + r.width / 2) + "," + (r.top + r.height / 2);
                                    }catch(e){ return "ERROR"; }
                                })();
                            """.trimIndent()

                            webView.evaluateJavascript(jsGetCoords) { res ->
                                try {
                                    val clean = res?.removeSurrounding("\"")
                                    val coords = clean?.split(",")
                                    val x = coords?.getOrNull(0)?.toFloatOrNull()
                                    val y = coords?.getOrNull(1)?.toFloatOrNull()
                                    if (x != null && y != null) {
                                        isProcessingClick = true
                                        simulateRealTouch(webView, x, y)
                                        pollingHandler.postDelayed({ isProcessingClick = false }, 1800)
                                    }
                                } catch (_: Exception) {
                                    isProcessingClick = false
                                }
                            }
                            pollingHandler.postDelayed(this, 2000)
                        }
                    }
                    pollingHandler.post(runnable)
                }

                var lastUrl: String? = null
                var stableSince = 0L
                var fetched = false

                fun waitUntilReady() {
                    if (isSolved) return
                    val js = """
                        (function(){
                            try{
                                var html = document.documentElement.innerHTML || "";
                                var challengeText = (document.title + " " + html).toLowerCase();
                                var stillCloudflare = /just a moment|checking your browser|cf-browser-verification|__cf_chl_opt|challenge-running|cf-chl-widget|cloudflare ray id/.test(challengeText);
                                return location.href + "|" + document.readyState + "|" + stillCloudflare;
                            }catch(e){ return location.href + "|loading|true"; }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(js) { res ->
                        if (res == null) {
                            pollingHandler.postDelayed({ waitUntilReady() }, 200)
                            return@evaluateJavascript
                        }

                        val parts = res.replace("\"", "").split("|")
                        if (parts.size < 3) {
                            pollingHandler.postDelayed({ waitUntilReady() }, 200)
                            return@evaluateJavascript
                        }

                        val currentUrl = parts[0]
                        val ready = parts[1]
                        val stillCloudflare = parts[2]
                        val now = SystemClock.uptimeMillis()
                        if (currentUrl != lastUrl) {
                            lastUrl = currentUrl
                            stableSince = now
                        }
                        val stableTime = now - stableSince

                        if (stillCloudflare == "false" && ready == "complete" && stableTime > 1500 && !fetched) {
                            fetched = true
                            pollingHandler.postDelayed({
                                webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                    finishSuccess(html, currentUrl)
                                }
                            }, 500)
                            return@evaluateJavascript
                        }
                        pollingHandler.postDelayed({ waitUntilReady() }, 200)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isProcessingClick = false
                        startPolling()
                        waitUntilReady()
                    }
                }

                rootView.addView(webView)
                webView.loadUrl(url)
            }
        }
    }
}
