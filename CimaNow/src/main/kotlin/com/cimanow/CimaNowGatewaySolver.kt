package com.cimanow

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
 * Android real-device guarantees (`WebView 150+`, MIUI / MediaTek / PowerVR / Adreno):
 *  1. Zero `window.*` globals, zero `#cs-link-sink` / `#cs-payload` DOM mutations, and zero
 *     `Element` / `Node` / `XMLHttpRequest` / `fetch` monkey-patches (defeating Stage A `_isHijacked()`
 *     and Stage B `js-confuser` integrity checks).
 *  2. On-screen `PassThroughWebViewContainer` (`translationX = 0f, translationY = 0f` at `index = 0`
 *     behind player UI with `dispatchTouchEvent = false` for user touches) so `getGlobalVisibleRect`
 *     is `true` and Chromium's `BeginMainFrame` (`requestAnimationFrame` 10s countdown + invisible
 *     Turnstile widget) runs unthrottled at 60Hz without intercepting user input.
 *  3. Native suppression of Android WebView's `X-Requested-With: com.lagradost.cloudstream3` header via
 *     `WebSettingsCompat` + Chromium `AwSettings` reflection (preventing `freex2line.online` WAF 403).
 *  4. Detached JS-disabled `onCreateWindow` popup sink so `openads.js` receives a non-null `WindowProxy`
 *     on popunder taps #1/#2 (advancing its click counter to tap #3's main-frame navigation) with zero
 *     ad network traffic and zero extra GPU surfaces.
 *  5. Exact `(wv.width / window.innerWidth, wv.height / window.innerHeight)` CSS-to-View coordinate
 *     mapping with full touchscreen `TOOL_TYPE_FINGER` `MotionEvent` dispatch (`event.isTrusted === true`).
 */
object CimaNowGatewaySolver {

    private const val TAG = "CimaNowGW"
    private const val TOKEN_TTL_MS = 15 * 60 * 1000L
    private const val ATTEMPT_1_TIMEOUT_MS = 38_000L
    private const val ATTEMPT_2_TIMEOUT_MS = 26_000L

    private data class CachedToken(val mintedWatchingUrl: String, val expiresAtMs: Long)

    private data class CssTapTarget(
        val cssX: Float,
        val cssY: Float,
        val viewportW: Float,
        val viewportH: Float,
    )

    private class PassThroughWebViewContainer(context: Context) : FrameLayout(context) {
        @Volatile
        var allowSyntheticTouch: Boolean = false

        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            if (!allowSyntheticTouch) return false
            return super.dispatchTouchEvent(ev)
        }
    }

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()
    private val solverMutex = Mutex()

    private fun logInfo(msg: String) {
        CimaNowExtractors.debugLogger?.invoke("[GW] $msg")
        if (Build.VERSION.SDK_INT > 0) {
            runCatching { Log.i(TAG, msg) }
        }
    }

    private fun logWarn(msg: String) {
        CimaNowExtractors.debugLogger?.invoke("[GW][WARN] $msg")
        if (Build.VERSION.SDK_INT > 0) {
            runCatching { Log.w(TAG, msg) }
        }
    }

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

    internal fun cleanWebViewUserAgent(rawUa: String): String {
        return rawUa
            .replace("; wv)", ")")
            .replace(Regex("""Version/\d+\.\d+\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { CimaNowNetwork.MOBILE_UA }
    }

    internal fun unwrapHrefLi(url: String): String? {
        val clean = url.trim()
        return when {
            clean.startsWith("https://href.li/?", ignoreCase = true) -> clean.substring("https://href.li/?".length).trim()
            clean.startsWith("http://href.li/?", ignoreCase = true) -> clean.substring("http://href.li/?".length).trim()
            else -> null
        }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    internal fun normalizeMintedWatchingUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val unwrapped = unwrapHrefLi(trimmed) ?: trimmed
        return unwrapped.takeIf {
            it.startsWith("http") && it.contains("/watching/") && it.contains("token=")
        }
    }

    private fun hostOnly(url: String?): String =
        runCatching { URI(CimaNowNetwork.encodeUrl(url.orEmpty())).host?.lowercase().orEmpty() }.getOrDefault("")

    private fun isAllowedGatewayNavigation(url: String): Boolean {
        val host = hostOnly(url)
        if (host.isEmpty()) return true
        return host.contains("freex2line.") ||
            host.contains("cimanow") ||
            host.contains("cloudflare.") ||
            host.contains("href.li") ||
            host.contains("google") ||
            host.contains("gstatic.")
    }

    @Suppress("UNCHECKED_CAST")
    internal fun suppressXRequestedWithHeader(settings: WebSettings): String {
        val modes = ArrayList<String>(2)
        runCatching {
            val featureCls = Class.forName("androidx.webkit.WebViewFeature")
            val supported = featureCls
                .getMethod("isFeatureSupported", String::class.java)
                .invoke(null, "REQUESTED_WITH_HEADER_ALLOW_LIST") as? Boolean
            if (supported == true) {
                val compatCls = Class.forName("androidx.webkit.WebSettingsCompat")
                compatCls
                    .getMethod("setRequestedWithHeaderOriginAllowList", WebSettings::class.java, Set::class.java)
                    .invoke(null, settings, emptySet<String>())
                modes.add("WebSettingsCompat")
            }
        }
        runCatching {
            var awSettingsObj: Any? = null
            var cls: Class<*>? = settings.javaClass
            while (cls != null && cls != Any::class.java && awSettingsObj == null) {
                for (m in cls.declaredMethods) {
                    if (m.parameterTypes.isEmpty() && m.returnType.name.endsWith("AwSettings")) {
                        m.isAccessible = true
                        awSettingsObj = m.invoke(settings)
                        if (awSettingsObj != null) break
                    }
                }
                if (awSettingsObj == null) {
                    for (f in cls.declaredFields) {
                        if (f.type.name.endsWith("AwSettings") || f.name == "mAwSettings") {
                            f.isAccessible = true
                            awSettingsObj = f.get(settings)
                            if (awSettingsObj != null) break
                        }
                    }
                }
                cls = cls.superclass
            }
            if (awSettingsObj != null) {
                val setter = awSettingsObj.javaClass.methods.firstOrNull {
                    it.name == "setRequestedWithHeaderOriginAllowList" && it.parameterTypes.size == 1
                } ?: awSettingsObj.javaClass.declaredMethods.firstOrNull {
                    it.name == "setRequestedWithHeaderOriginAllowList" && it.parameterTypes.size == 1
                }?.also { it.isAccessible = true }
                if (setter != null) {
                    setter.invoke(awSettingsObj, emptySet<String>())
                    modes.add("AwSettings")
                }
            }
        }
        return if (modes.isEmpty()) "default" else modes.joinToString("+")
    }

    private suspend fun clearGatewayCookiesOnMain(gwOrigin: String) {
        if (Build.VERSION.SDK_INT <= 0) return
        withContext(Dispatchers.Main) {
            runCatching {
                val cm = CookieManager.getInstance()
                val existing = cm.getCookie(gwOrigin).orEmpty()
                for (part in existing.split(';')) {
                    val name = part.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(gwOrigin, "$name=; Max-Age=0; Path=/")
                    }
                }
                cm.flush()
            }
        }
    }

    suspend fun mintWatchingUrl(shineUrl: String, itemUrl: String): String? {
        getCachedWatchingUrl(itemUrl)?.let { return it }

        return solverMutex.withLock {
            getCachedWatchingUrl(itemUrl)?.let { return@withLock it }
            val cleanShine = unwrapHrefLi(shineUrl)
                ?: shineUrl.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@withLock null

            val minted = if (Build.VERSION.SDK_INT > 0 && CommonActivity.activity != null) {
                mintViaAndroidWebView(cleanShine, itemUrl, ATTEMPT_1_TIMEOUT_MS, attempt = 1)
                    ?: run {
                        val gwOrigin = runCatching {
                            val u = URI(CimaNowNetwork.encodeUrl(cleanShine))
                            "${u.scheme}://${u.host}"
                        }.getOrDefault("https://rm.freex2line.online")
                        clearGatewayCookiesOnMain(gwOrigin)
                        mintViaAndroidWebView(cleanShine, itemUrl, ATTEMPT_2_TIMEOUT_MS, attempt = 2)
                    }
            } else if (Build.VERSION.SDK_INT == 0) {
                mintViaHeadlessChromiumCli(cleanShine, itemUrl)
                    ?: mintViaHeadlessChromiumCli(cleanShine, itemUrl)
            } else {
                logWarn("CommonActivity.activity is null (SDK=${Build.VERSION.SDK_INT})")
                null
            }

            val normalized = normalizeMintedWatchingUrl(minted)
            if (normalized != null) {
                tokenCache[cacheKey(itemUrl)] = CachedToken(normalized, System.currentTimeMillis() + TOKEN_TTL_MS)
                normalized
            } else {
                null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private suspend fun mintViaAndroidWebView(
        shineUrl: String,
        itemUrl: String,
        timeoutMs: Long,
        attempt: Int,
    ): String? {
        val activity = CommonActivity.activity ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null

        val deferred = CompletableDeferred<String?>()
        var containerRef: PassThroughWebViewContainer? = null
        var webViewRef: WebView? = null
        val popupWebViews = ArrayList<WebView>(3)

        return try {
            withContext(Dispatchers.Main) {
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    ?: run {
                        logWarn("android.R.id.content not found on attempt #$attempt")
                        deferred.complete(null)
                        return@withContext
                    }

                val container = PassThroughWebViewContainer(activity).apply {
                    visibility = View.VISIBLE
                    translationX = 0f
                    translationY = 0f
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                containerRef = container

                val wv = WebView(activity).apply {
                    alpha = 0.01f
                    visibility = View.VISIBLE
                    translationX = 0f
                    translationY = 0f
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                }
                webViewRef = wv

                val rawDefaultUa = runCatching { WebSettings.getDefaultUserAgent(activity) }
                    .getOrDefault(CimaNowNetwork.MOBILE_UA)
                val cleanChromeUa = cleanWebViewUserAgent(rawDefaultUa)
                val xrwMode = suppressXRequestedWithHeader(wv.settings)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    loadsImagesAutomatically = true
                    blockNetworkImage = false
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    userAgentString = cleanChromeUa
                }

                logInfo("Attempt #$attempt init WebView (xrw=$xrwMode, ua=${cleanChromeUa.take(78)})")

                // Give openads.js a real non-null WindowProxy on popunder taps #1 & #2 while aborting
                // all ad loads immediately and capturing /watching/?token=... if opened in a new window.
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val popupWv = runCatching { WebView(activity) }.getOrNull() ?: return false
                        popupWebViews.add(popupWv)
                        popupWv.settings.apply {
                            javaScriptEnabled = false
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                        }
                        popupWv.webViewClient = object : WebViewClient() {
                            private fun interceptPopup(u: String?): Boolean {
                                val minted = normalizeMintedWatchingUrl(u)
                                if (minted != null) {
                                    logInfo("Attempt #$attempt captured watching URL via popup!")
                                    if (!deferred.isCompleted) deferred.complete(minted)
                                } else if (!u.isNullOrBlank() && u != "about:blank") {
                                    logInfo("Attempt #$attempt swallowed popunder: ${hostOnly(u)}")
                                }
                                runCatching { popupWv.stopLoading() }
                                view?.postDelayed({
                                    runCatching {
                                        popupWebViews.remove(popupWv)
                                        popupWv.destroy()
                                    }
                                }, 250L)
                                return true
                            }

                            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean =
                                interceptPopup(req?.url?.toString())

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean =
                                interceptPopup(url)

                            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (!url.isNullOrBlank() && url != "about:blank") {
                                    interceptPopup(url)
                                }
                            }
                        }
                        transport.webView = popupWv
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)
                }

                wv.webViewClient = object : WebViewClient() {
                    private fun interceptMainUrl(view: WebView?, url: String?): Boolean {
                        val target = url?.trim().orEmpty()
                        if (target.isEmpty()) return false
                        normalizeMintedWatchingUrl(target)?.let { minted ->
                            logInfo("Attempt #$attempt captured minted watching URL on main frame!")
                            if (!deferred.isCompleted) deferred.complete(minted)
                            runCatching { view?.stopLoading() }
                            return true
                        }
                        if (target.startsWith("http") && !isAllowedGatewayNavigation(target)) {
                            logInfo("Attempt #$attempt blocked external redirect: ${hostOnly(target)}")
                            return true
                        }
                        return false
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (request != null && !request.isForMainFrame) return false
                        return interceptMainUrl(view, request?.url?.toString())
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return interceptMainUrl(view, url)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        val target = url?.trim().orEmpty()
                        normalizeMintedWatchingUrl(target)?.let { minted ->
                            logInfo("Attempt #$attempt onPageStarted captured minted watching URL!")
                            if (!deferred.isCompleted) deferred.complete(minted)
                            runCatching { view?.stopLoading() }
                            return
                        }
                        if (target.startsWith("http")) {
                            logInfo("Attempt #$attempt nav -> ${target.substringBefore('?').take(85)}")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            logWarn("Attempt #$attempt HTTP ${errorResponse?.statusCode} on ${request.url.toString().take(90)}")
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            logWarn("Attempt #$attempt net error ${error?.errorCode} (${error?.description}) on ${request.url.toString().take(90)}")
                        }
                    }
                }

                val matchParent = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                container.addView(wv, matchParent)
                if (root.childCount > 0) {
                    root.addView(container, 0, matchParent)
                } else {
                    root.addView(container, matchParent)
                }

                wv.loadUrl(
                    CimaNowNetwork.encodeUrl(shineUrl),
                    mapOf("Referer" to CimaNowNetwork.encodeUrl(itemUrl)),
                )
            }

            withTimeoutOrNull(timeoutMs) {
                val startedMs = SystemClock.uptimeMillis()
                var firstBtnSeenMs = 0L
                var lastTsTapMs = 0L
                var lastBtnTapMs = 0L
                var tsTapCount = 0
                var btnTapCount = 0

                // Let initial /loadon/ -> href.li -> /redirectingfree/ -> /blog-post.html/ load cleanly
                delay(2_800L)

                while (!deferred.isCompleted) {
                    delay(550L)
                    if (deferred.isCompleted) break

                    val nowMs = SystemClock.uptimeMillis()
                    val elapsedMs = nowMs - startedMs
                    // Once blog-post.html (#downloadbtn) mounts, leave Stage B & invisible Turnstile
                    // completely undisturbed for 6.5s while the 10-second countdown runs.
                    if (firstBtnSeenMs > 0L && nowMs - firstBtnSeenMs < 6_500L) continue

                    val allowBtnForce = firstBtnSeenMs > 0L && (nowMs - firstBtnSeenMs >= 11_500L)
                    val allowTsTap = if (firstBtnSeenMs == 0L) {
                        elapsedMs >= 5_500L
                    } else {
                        nowMs - firstBtnSeenMs >= 8_500L
                    }

                    val probeResult = withContext(Dispatchers.Main) {
                        val wv = webViewRef ?: return@withContext ""
                        normalizeMintedWatchingUrl(wv.url)?.let { cur ->
                            if (!deferred.isCompleted) deferred.complete(cur)
                            return@withContext "DONE"
                        }
                        evalJsSuspend(wv, buildReadOnlyGatewayProbeJs(allowBtnForce, allowTsTap))
                    }

                    if (deferred.isCompleted || probeResult == "DONE") break

                    if (probeResult == "ERR:WAF" || probeResult == "ERR:BROWSER") {
                        logWarn("Attempt #$attempt gateway rejected browser ($probeResult) at ${elapsedMs}ms")
                        deferred.complete(null)
                        break
                    }

                    if (firstBtnSeenMs == 0L && probeResult.contains("HASBTN")) {
                        firstBtnSeenMs = SystemClock.uptimeMillis()
                        logInfo("Attempt #$attempt Stage B #downloadbtn detected at ${elapsedMs}ms (wv=${webViewRef?.width}x${webViewRef?.height})")
                    }

                    when {
                        probeResult.startsWith("URL:") -> {
                            normalizeMintedWatchingUrl(probeResult.removePrefix("URL:"))?.let { minted ->
                                logInfo("Attempt #$attempt probe extracted watching URL at ${elapsedMs}ms")
                                deferred.complete(minted)
                            }
                            if (deferred.isCompleted) break
                        }
                        probeResult.startsWith("BTN:") -> {
                            if (firstBtnSeenMs == 0L) firstBtnSeenMs = nowMs
                            if (nowMs - lastBtnTapMs >= 650L) {
                                lastBtnTapMs = nowMs
                                val target = parseCssTapTarget(probeResult.removePrefix("BTN:"))
                                if (target != null) {
                                    btnTapCount++
                                    logInfo("Attempt #$attempt tap #downloadbtn #$btnTapCount css=(${target.cssX.toInt()},${target.cssY.toInt()}) vp=${target.viewportW.toInt()}x${target.viewportH.toInt()}")
                                    dispatchTrustedTap(containerRef, webViewRef, target)
                                }
                            }
                        }
                        probeResult.startsWith("TS:") -> {
                            val payload = probeResult.removePrefix("TS:").removeSuffix(":HASBTN")
                            if (nowMs - lastTsTapMs >= 4_200L) {
                                lastTsTapMs = nowMs
                                val sides = payload.split('|')
                                val chosen = sides.getOrNull(tsTapCount % sides.size)?.let { parseCssTapTarget(it) }
                                tsTapCount++
                                if (chosen != null) {
                                    logInfo("Attempt #$attempt tap Turnstile #$tsTapCount css=(${chosen.cssX.toInt()},${chosen.cssY.toInt()}) vp=${chosen.viewportW.toInt()}x${chosen.viewportH.toInt()}")
                                    dispatchTrustedTap(containerRef, webViewRef, chosen)
                                }
                            }
                        }
                    }
                }
                deferred.await()
            }.also { res ->
                if (res == null && !deferred.isCompleted) {
                    logWarn("Attempt #$attempt timed out after ${timeoutMs}ms (lastUrl=${webViewRef?.url?.take(85)})")
                }
            }
        } catch (t: Throwable) {
            logWarn("Attempt #$attempt exception: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { CookieManager.getInstance().flush() }
                for (pwv in popupWebViews) {
                    runCatching {
                        pwv.stopLoading()
                        pwv.destroy()
                    }
                }
                popupWebViews.clear()
                webViewRef?.let { wv ->
                    runCatching {
                        wv.stopLoading()
                        wv.webChromeClient = null
                        wv.webViewClient = WebViewClient()
                        wv.loadUrl("about:blank")
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.removeAllViews()
                        wv.destroy()
                    }
                }
                containerRef?.let { c ->
                    runCatching {
                        (c.parent as? ViewGroup)?.removeView(c)
                        c.removeAllViews()
                    }
                }
                webViewRef = null
                containerRef = null
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
                    var iw = Math.round(window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0);
                    var ih = Math.round(window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0);
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
                                return 'BTN:' + Math.round(br.left + br.width / 2) + ',' + Math.round(br.top + br.height / 2) + ',' + iw + ',' + ih;
                            }
                        }
                    } else {
                        var btxt = String((document.body && document.body.innerText) || '');
                        if (btxt.indexOf('unable to access freex2line') !== -1 || btxt.indexOf('Sorry, you have been blocked') !== -1) {
                            return 'ERR:WAF';
                        }
                        if (btxt.indexOf('استخدم متصفح اخر') !== -1) {
                            return 'ERR:BROWSER';
                        }
                    }
                    if ($tsTapFlag && h !== '#watch-ready') {
                        var inp = document.querySelector('input[name="cf-turnstile-response"]');
                        var tokReady = inp && inp.value && inp.value.length > 20;
                        if (!tokReady) {
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
                            if (!box && inp) {
                                var p = inp.parentElement;
                                while (p && p !== document.body) {
                                    var pr = p.getBoundingClientRect();
                                    if (pr.width >= 180 && pr.height >= 40 && pr.height <= 140) { box = p; break; }
                                    p = p.parentElement;
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
                                    var pair = lx + ',' + cy + ',' + iw + ',' + ih + '|' + rx + ',' + cy + ',' + iw + ',' + ih;
                                    return 'TS:' + pair + (btn ? ':HASBTN' : '');
                                }
                            }
                        }
                    }
                    return btn ? 'WAIT:HASBTN' : 'WAIT';
                } catch(e) {}
                return 'WAIT';
            })();
        """.trimIndent()
    }

    private fun parseCssTapTarget(s: String): CssTapTarget? {
        val parts = s.split(',')
        if (parts.size < 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        val iw = parts.getOrNull(2)?.trim()?.toFloatOrNull() ?: 0f
        val ih = parts.getOrNull(3)?.trim()?.toFloatOrNull() ?: 0f
        return CssTapTarget(x, y, iw, ih)
    }

    private fun computeViewTapPoint(wv: WebView, target: CssTapTarget): Pair<Float, Float> {
        val density = wv.resources.displayMetrics.density.coerceAtLeast(1f)
        val scaleX = if (target.viewportW > 0f && wv.width > 0) {
            wv.width.toFloat() / target.viewportW
        } else {
            density
        }
        val scaleY = if (target.viewportH > 0f && wv.height > 0) {
            wv.height.toFloat() / target.viewportH
        } else {
            density
        }
        val maxX = (wv.width - 2).coerceAtLeast(4).toFloat()
        val maxY = (wv.height - 2).coerceAtLeast(4).toFloat()
        val realX = (target.cssX * scaleX).coerceIn(2f, maxX)
        val realY = (target.cssY * scaleY).coerceIn(2f, maxY)
        return realX to realY
    }

    private suspend fun dispatchTrustedTap(
        container: PassThroughWebViewContainer?,
        wv: WebView?,
        target: CssTapTarget,
    ) {
        if (wv == null) return
        val downAt = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        withContext(Dispatchers.Main) {
            runCatching {
                val (rx, ry) = computeViewTapPoint(wv, target)
                val coords = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = rx
                        y = ry
                        pressure = 0.68f
                        size = 0.12f
                    },
                )
                val down = MotionEvent.obtain(
                    downAt, downAt, MotionEvent.ACTION_DOWN, 1, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                container?.allowSyntheticTouch = true
                try {
                    wv.dispatchTouchEvent(down)
                } finally {
                    container?.allowSyntheticTouch = false
                    down.recycle()
                }
            }
        }
        delay(60L)
        withContext(Dispatchers.Main) {
            runCatching {
                val (rx, ry) = computeViewTapPoint(wv, target)
                val coords = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = rx
                        y = ry
                        pressure = 0.68f
                        size = 0.12f
                    },
                )
                val upAt = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(
                    downAt, upAt, MotionEvent.ACTION_UP, 1, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                container?.allowSyntheticTouch = true
                try {
                    wv.dispatchTouchEvent(up)
                } finally {
                    container?.allowSyntheticTouch = false
                    up.recycle()
                }
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
                    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
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
                        if (ms < 4500) continue;
                        if (firstBtnMs > 0 && ms - firstBtnMs < 6500) continue;
                        const allowBtnForce = firstBtnMs > 0 && (ms - firstBtnMs >= 11500);
                        const allowTsTap = firstBtnMs === 0 ? (ms >= 5500) : (ms - firstBtnMs >= 8500);
                        const probe = await send('Runtime.evaluate', {
                            expression: `(function(){
                                try {
                                    var loc = String(location.href || '');
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
                                                var tx = Math.round(tr.left + 28);
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
                        } else if (pv.startsWith('BTN:') && ms - lastBtnClick >= 650) {
                            lastBtnClick = ms;
                            const [bx, by] = pv.slice(4).split(',').map(Number);
                            if (bx > 0 && by > 0) await tap(bx, by);
                        } else if (pv.startsWith('TS:') && ms - lastTsClick >= 4200) {
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
                val minted = normalizeMintedWatchingUrl(obj?.optString("url"))
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
