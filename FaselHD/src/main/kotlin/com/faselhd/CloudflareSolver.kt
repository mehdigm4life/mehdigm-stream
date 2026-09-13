package com.faselhd

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine

object CloudflareSolver {

    // يعرض نافذة منبثقة تحمّل صفحة التحدي، ويبقى فيها حتى يتمكن الحل (يدوياً أو تلقائياً)
    // ويعيد true فقط عند حصولنا على كوكي cf_clearance المثبّتة في CookieManager.
    suspend fun solve(activity: Activity?, url: String, userAgent: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            if (activity == null || activity.isFinishing) {
                cont.resumeWith(Result.success(false))
                return@suspendCancellableCoroutine
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var finished = false
            var dialogRef: Dialog? = null
            var webViewRef: WebView? = null
            var statusRef: TextView? = null

            fun finishWith(solved: Boolean) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                runCatching { dialogRef?.dismiss() }
                runCatching { webViewRef?.stopLoading() }
                runCatching { webViewRef?.destroy() }
                runCatching { CookieManager.getInstance().flush() }
                if (cont.isActive) cont.resumeWith(Result.success(solved))
            }

            // النجاح الحقيقي: وجود كوكي cf_clearance للدومين المطلوب.
            // التحدي عند فاصل غالباً "managed" يُحل تلقائياً بلا مربع، لذا هذا أفضل من فحص الـ HTML.
            fun waitForClearance(url: String) {
                if (finished) return
                val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: ""
                if (cookies.contains("cf_clearance")) {
                    statusRef?.post { statusRef?.text = "تم الحل ✓ جارٍ التحميل..." }
                    mainHandler.postDelayed({ finishWith(true) }, 300)
                } else {
                    mainHandler.postDelayed({ waitForClearance(url) }, 500)
                }
            }

            activity.runOnUiThread {
                val dialog = Dialog(activity)
                dialogRef = dialog
                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.window?.let { w ->
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                    w.setGravity(Gravity.CENTER)
                }

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
                    text = "في انتظار حل تحدي Cloudflare...\n(سواء ظهر مربع أم لا، سيُحل تلقائياً)"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(24, 20, 24, 20)
                }
                statusRef = statusText

                val cancelBtn = TextView(activity).apply {
                    text = "إلغاء"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(24, 20, 24, 20)
                    setBackgroundColor(Color.parseColor("#8E0000"))
                }
                cancelBtn.setOnClickListener { finishWith(false) }

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
                        mainHandler.post { waitForClearance(url) }
                    }
                }

                dialog.show()

                // مهلة أمان: في حال تعثّر الحل تماماً
                mainHandler.postDelayed({ finishWith(false) }, 60_000L)

                webView.loadUrl(url)
            }

            cont.invokeOnCancellation {
                mainHandler.post { finishWith(false) }
            }
        }
    }
}