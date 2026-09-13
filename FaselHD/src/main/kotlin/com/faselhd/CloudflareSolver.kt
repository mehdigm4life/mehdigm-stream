package com.faselhd

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object CloudflareSolver {

    suspend fun solve(activity: Activity?, url: String, userAgent: String): Document? {
        return suspendCancellableCoroutine { cont ->
            if (activity == null || activity.isFinishing) {
                cont.resumeWith(Result.success(null))
                return@suspendCancellableCoroutine
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var finished = false
            var dialogRef: Dialog? = null
            var webViewRef: WebView? = null

            fun finishWith(html: String?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                runCatching { dialogRef?.dismiss() }
                runCatching { webViewRef?.stopLoading() }
                runCatching { webViewRef?.destroy() }
                runCatching { CookieManager.getInstance().flush() }

                if (html == null) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                    return
                }

                val cleanHtml = html.removeSurrounding("\"")
                    .replace("\\u003C", "<")
                    .replace("\\u003E", ">")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                if (cont.isActive) cont.resumeWith(Result.success(Jsoup.parse(cleanHtml)))
            }

            // مراقبة تقدم الصفحة داخل النافذة المنبثقة: ننتظر حتى تكتمل الصفحة
            // ويختفي تحدي Cloudflare، ثم نلتقط HTML النهائي ونغلق النافذة.
            fun waitForReady(webView: WebView) {
                if (finished) return
                val js = """
                    (function(){
                        try{
                            var hasChallenge = document.querySelector('#challenge-form, #challenge-running, .cf-turnstile, #cf-chl-widget') != null;
                            var html = document.documentElement.innerHTML || '';
                            html = html.toLowerCase();
                            var stillCloudflare = html.indexOf('just a moment') !== -1 || html.indexOf('checking your browser') !== -1 || html.indexOf('cf_error') !== -1;
                            return location.href + '|' + document.readyState + '|' + (hasChallenge ? '1' : '0') + '|' + (stillCloudflare ? '1' : '0');
                        }catch(e){ return location.href + '|loading|1|1'; }
                    })();
                """.trimIndent()

                webView.evaluateJavascript(js) { res ->
                    if (finished) return@evaluateJavascript
                    if (res == null) {
                        mainHandler.postDelayed({ waitForReady(webView) }, 400)
                        return@evaluateJavascript
                    }

                    val parts = res.replace("\"", "").split("|")
                    if (parts.size < 4) {
                        mainHandler.postDelayed({ waitForReady(webView) }, 400)
                        return@evaluateJavascript
                    }

                    val (pageUrl, ready, hasChallenge, stillCloudflare) = parts

                    // الصفحة الحقيقية ظهرت: اكتمل التحميل، لا يوجد تحدي، ولا أي أثر لـ Cloudflare
                    if (ready == "complete" && hasChallenge == "0" && stillCloudflare == "0") {
                        // سكون قصير حتى تستقر الصفحة
                        mainHandler.postDelayed({
                            webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                finishWith(html)
                            }
                        }, 400)
                    } else {
                        mainHandler.postDelayed({ waitForReady(webView) }, 400)
                    }
                }
            }

            activity.runOnUiThread {
                val dialog = Dialog(activity)
                dialogRef = dialog
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

                val webView = WebView(activity)
                webViewRef = webView
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = userAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }

                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(webView, true)
                    cm.flush()
                }

                val statusText = TextView(activity).apply {
                    text = "حل تحدي Cloudflare: اضغط على المربع ثم انتظر ثوانٍ"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(24, 20, 24, 20)
                }

                val cancelBtn = TextView(activity).apply {
                    text = "إلغاء"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(24, 20, 24, 20)
                    setBackgroundColor(Color.parseColor("#8E0000"))
                }
                cancelBtn.setOnClickListener { finishWith(null) }

                val topBar = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#1B1B1B"))
                }
                topBar.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                topBar.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                }
                root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

                dialog.setContentView(root)

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        mainHandler.post { waitForReady(webView) }
                    }
                }

                dialog.show()

                // مهلة أمان: في حال لم يعمل المستخدم أي شيء
                mainHandler.postDelayed({ finishWith(null) }, 120_000L)

                webView.loadUrl(url)
            }

            cont.invokeOnCancellation {
                mainHandler.post { finishWith(null) }
            }
        }
    }
}