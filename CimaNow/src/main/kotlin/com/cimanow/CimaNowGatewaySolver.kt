package com.cimanow

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationHandler
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Stealth Turnstile Gateway Solver & Real-V8 Vault Fallback for CimaNow (v5).
 *
 * Permanent Android real-device architecture (`WebView 96–160+`, MIUI / HyperOS / MediaTek / PowerVR / Adreno):
 *  1. Genuine Self-Consistent Browser Identity (Attempt #1): keeps `WebSettings.userAgentString` 100%
 *     native untouched on Attempt #1 so HTTP `User-Agent`, `Sec-CH-UA` brand metadata, `navigator`,
 *     `window.chrome`, and WebGL are 100% consistent inside `challenges.cloudflare.com`'s cross-origin
 *     Turnstile widget (`0x4AAAAAAFkYRL2ky4jDsK1G`, preventing Turnstile `error-callback` ->
 *     `"من فضلك استخدم متصفح اخر"`). Attempt #2 purges `HttpOnly` cookies via `removeAllCookies` and
 *     falls back to `cleanWebViewUserAgent`.
 *  2. Multi-Layer `X-Requested-With` Suppression: uses `SupportLibReflectionUtil` boundary reflection
 *     via `WebView.getWebViewClassLoader()` (plus `WebSettingsCompat` & `AwSettings` fallbacks) to
 *     prevent `rm.freex2line.online` WAF 403 on older WebViews even without `androidx.webkit`.
 *  3. Topmost Unoccluded `PassThroughWebViewContainer` (`container.alpha = 0.01f`, `wv.alpha = 1.0f`)
 *     + Single-WebView GPU Discipline (`PowerVR Rogue GE8320` 8-RTS safe): unoccluded 60Hz frame ticks
 *     without intercepting user touches, and at most one unattached `LAYER_TYPE_NONE` popup sink torn
 *     down on the next main-looper tick.
 *  4. Pure Read-Only Gateway Probe + `#xqeqjp`/`#xqeqjp1` Overlay Dismissal + Smooth-Scroll-Safe Tap
 *     Targeting: zero premature DOM mutations during the 10s countdown, fast dismissal of full-screen
 *     `<a id="xqeqjp*">` overlays, and stationary `instant` scroll coordinates.
 *  5. Layer-2 Real-V8 `/watching/` Vault Fallback ([extractWatchingSourcesViaWebView]): if OkHttp+Rhino
 *     ever misses a future `/watching/` JS obfuscation update, loads `mintedUrl` directly in Android
 *     WebView and captures all watch embeds + direct MP4 downloads natively via V8 + `shouldInterceptRequest`.
 */
object CimaNowGatewaySolver {

    private const val TAG = "CimaNowGW"
    private const val TOKEN_TTL_MS = 15 * 60 * 1000L
    private const val ATTEMPT_1_TIMEOUT_MS = 36_000L
    private const val ATTEMPT_2_TIMEOUT_MS = 28_000L
    private const val WATCHING_WV_TIMEOUT_MS = 18_000L

    data class WebViewWatchingResult(
        val watchEmbeds: List<Pair<String, String>>,
        val downloads: List<CimaNowCrypto.DownloadEntry>,
    )

    private data class CachedToken(val mintedWatchingUrl: String, val expiresAtMs: Long)

    internal data class CssTapTarget(
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

    private fun baseDomainOf(host: String): String {
        val clean = host.trim('.')
        val parts = clean.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else clean
    }

    internal fun isAllowedGatewayNavigation(url: String, gatewayHost: String = ""): Boolean {
        val host = hostOnly(url)
        if (host.isEmpty()) return true
        val gwBase = baseDomainOf(gatewayHost)
        if (gwBase.isNotEmpty() && (host == gatewayHost || host.endsWith(".$gwBase") || host == gwBase)) {
            return true
        }
        return host.contains("freex2line.") ||
            host.contains("cimanow") ||
            host.contains("cloudflare.") ||
            host.contains("href.li") ||
            host.contains("google") ||
            host.contains("gstatic.")
    }

    private fun isWatchingPageInfraHost(host: String): Boolean {
        if (host.isEmpty()) return true
        return host.contains("cimanow") ||
            host.contains("cloudflare") ||
            host.contains("googleapis.") ||
            host.contains("gstatic.") ||
            host.contains("jquery.") ||
            host.contains("fontawesome.") ||
            host.contains("jsdelivr.") ||
            host.contains("cdnjs.") ||
            host.contains("wp.com") ||
            host.contains("googlesyndication.") ||
            host.contains("googletagmanager.") ||
            host.contains("doubleclick.")
    }

    private fun unwrapBoundaryDelegate(obj: Any?): Any? {
        if (obj == null) return null
        if (obj is InvocationHandler) {
            runCatching {
                return obj.javaClass.getMethod("getDelegate").invoke(obj)
            }
            runCatching {
                val f = obj.javaClass.declaredFields.firstOrNull()
                if (f != null) {
                    f.isAccessible = true
                    return f.get(obj)
                }
            }
        }
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    internal fun suppressXRequestedWithHeader(settings: WebSettings): String {
        val modes = ArrayList<String>(3)

        // 1. Official Chromium WebView APK support_lib_glue boundary (unobfuscated across WebView M96+)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val wvLoader = WebView.getWebViewClassLoader()
                val glueCls = Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil", false, wvLoader)
                val factoryHandler = glueCls.getDeclaredMethod("createWebViewProviderFactory")
                    .apply { isAccessible = true }
                    .invoke(null)
                val factoryTarget = unwrapBoundaryDelegate(factoryHandler)
                val convHandler = factoryTarget?.javaClass?.methods?.firstOrNull {
                    it.name == "getWebkitToCompatConverter" && it.parameterTypes.isEmpty()
                }?.invoke(factoryTarget)
                val convTarget = unwrapBoundaryDelegate(convHandler)
                val settingsHandler = convTarget?.javaClass?.methods?.firstOrNull {
                    it.name == "convertSettings" && it.parameterTypes.size == 1
                }?.invoke(convTarget, settings)
                val settingsTarget = unwrapBoundaryDelegate(settingsHandler)
                val allowSetter = settingsTarget?.javaClass?.methods?.firstOrNull {
                    it.name == "setRequestedWithHeaderOriginAllowList" && it.parameterTypes.size == 1
                }
                if (allowSetter != null) {
                    allowSetter.invoke(settingsTarget, Collections.emptySet<String>())
                    modes.add("SupportLibGlue")
                }
            }
        }

        // 2. AndroidX WebSettingsCompat (if bundled by host app)
        runCatching {
            val featureCls = Class.forName("androidx.webkit.WebViewFeature")
            val supported = featureCls
                .getMethod("isFeatureSupported", String::class.java)
                .invoke(null, "REQUESTED_WITH_HEADER_ALLOW_LIST") as? Boolean
            if (supported == true) {
                val compatCls = Class.forName("androidx.webkit.WebSettingsCompat")
                compatCls
                    .getMethod("setRequestedWithHeaderOriginAllowList", WebSettings::class.java, Set::class.java)
                    .invoke(null, settings, Collections.emptySet<String>())
                modes.add("WebSettingsCompat")
            }
        }

        // 3. Direct AwSettings reflection (AOSP / unminified system WebViews)
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
                    setter.invoke(awSettingsObj, Collections.emptySet<String>())
                    modes.add("AwSettings")
                }
            }
        }

        return if (modes.isEmpty()) "native-m130+" else modes.joinToString("+")
    }

    /**
     * Purges ALL WebView cookies (including `HttpOnly` Cloudflare `cf_clearance` / `__cf_bm` cookies
     * that `setCookie("...; Max-Age=0")` cannot delete in Chromium `CookieMonster`).
     */
    private suspend fun purgeAllWebViewCookiesOnMain() {
        if (Build.VERSION.SDK_INT <= 0) return
        withTimeoutOrNull(3_000L) {
            withContext(Dispatchers.Main) {
                runCatching {
                    val cm = CookieManager.getInstance()
                    suspendCancellableCoroutine<Boolean> { cont ->
                        cm.removeAllCookies { removed ->
                            if (cont.isActive) cont.resume(removed)
                        }
                    }
                    cm.flush()
                }
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

            val activeActivity = if (Build.VERSION.SDK_INT > 0) CimaNowNetwork.resolveActivity() else null
            val minted = if (Build.VERSION.SDK_INT > 0 && activeActivity != null) {
                // Attempt #1: 100% native untouched Android WebView identity (exact UA/Sec-CH-UA/CookieManager parity)
                mintViaAndroidWebView(
                    shineUrl = cleanShine,
                    itemUrl = itemUrl,
                    timeoutMs = ATTEMPT_1_TIMEOUT_MS,
                    attempt = 1,
                    useNativeDefaultUa = true,
                ) ?: run {
                    // Attempt #2: Full HttpOnly cookie purge + cleaned Chrome Mobile UA fallback
                    purgeAllWebViewCookiesOnMain()
                    mintViaAndroidWebView(
                        shineUrl = cleanShine,
                        itemUrl = itemUrl,
                        timeoutMs = ATTEMPT_2_TIMEOUT_MS,
                        attempt = 2,
                        useNativeDefaultUa = false,
                    )
                }
            } else if (Build.VERSION.SDK_INT == 0) {
                mintViaHeadlessChromiumCli(cleanShine, itemUrl)
                    ?: mintViaHeadlessChromiumCli(cleanShine, itemUrl)
            } else {
                logWarn("Active Activity is null (SDK=${Build.VERSION.SDK_INT}, commonAct=${CommonActivity.activity != null})")
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
        useNativeDefaultUa: Boolean,
    ): String? {
        val activity = CimaNowNetwork.resolveActivity() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null

        val gatewayHost = hostOnly(shineUrl)
        val deferred = CompletableDeferred<String?>()
        var containerRef: PassThroughWebViewContainer? = null
        var webViewRef: WebView? = null
        val popupWebViews = ArrayList<WebView>(2)
        val mainHandler = Handler(Looper.getMainLooper())

        return try {
            withContext(Dispatchers.Main) {
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    ?: run {
                        logWarn("android.R.id.content not found on attempt #$attempt")
                        deferred.complete(null)
                        return@withContext
                    }

                // Topmost unoccluded pass-through wrapper (`container.alpha = 0.01f`, `wv.alpha = 1.0f`)
                // so Chromium's BeginMainFrame / rAF / Turnstile visibility observers run at full 60Hz
                // without being occluded behind an opaque sibling View, while user touches pass through.
                val container = PassThroughWebViewContainer(activity).apply {
                    alpha = 0.01f
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
                    alpha = 1.0f
                    visibility = View.VISIBLE
                    translationX = 0f
                    translationY = 0f
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                }
                webViewRef = wv

                val rawDefaultUa = runCatching { WebSettings.getDefaultUserAgent(activity) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: wv.settings.userAgentString.orEmpty()
                val cleanedUa = cleanWebViewUserAgent(rawDefaultUa)
                val xrwMode = suppressXRequestedWithHeader(wv.settings)

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
                    javaScriptCanOpenWindowsAutomatically = true
                    if (!useNativeDefaultUa && cleanedUa.isNotBlank()) {
                        userAgentString = cleanedUa
                    }
                }

                // CRITICAL: Always synchronize CimaNowNetwork.preferredUserAgent with the EXACT byte-for-byte
                // User-Agent used by this WebView (`activeUa`), matching CloudStream's official CloudflareKiller.
                // Overwriting preferredUserAgent with `cleanedUa` while WebView used `rawDefaultUa` (`; wv`)
                // breaks `cf_clearance` and token User-Agent validation on `cimanow.cc/.../watching/?token=...`!
                val activeUa = wv.settings.userAgentString.orEmpty()
                if (activeUa.isNotBlank()) {
                    if (useNativeDefaultUa) {
                        CimaNowNetwork.nativeWebViewUserAgent = activeUa
                    }
                    CimaNowNetwork.preferredUserAgent = activeUa
                }
                logInfo(
                    "Attempt #$attempt init WebView (nativeUa=$useNativeDefaultUa, xrw=$xrwMode, root=${root.width}x${root.height}, ua=${activeUa.take(82)})",
                )

                fun clearPopupWebViews() {
                    val existing = popupWebViews.toList()
                    popupWebViews.clear()
                    for (old in existing) {
                        runCatching {
                            old.stopLoading()
                            old.webViewClient = WebViewClient()
                            old.destroy()
                        }
                    }
                }

                // Zero-leak single-surface popup sink: disposes any prior popup first so sequential
                // `#xqeqjp` -> `#xqeqjp1` -> `#downloadbtn` popups NEVER return false or leak `resultMsg`,
                // while keeping at most ONE unattached `LAYER_TYPE_NONE` WebView alive at any instant.
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        clearPopupWebViews()
                        val popupWv = runCatching { WebView(activity) }.getOrNull() ?: return false
                        popupWv.setLayerType(View.LAYER_TYPE_NONE, null)
                        popupWebViews.add(popupWv)
                        popupWv.settings.apply {
                            javaScriptEnabled = false
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                        }
                        popupWv.webViewClient = object : WebViewClient() {
                            private var handled = false
                            private fun interceptPopup(u: String?): Boolean {
                                if (handled) return true
                                handled = true
                                val minted = normalizeMintedWatchingUrl(u)
                                if (minted != null) {
                                    logInfo("Attempt #$attempt captured watching URL via popup!")
                                    if (!deferred.isCompleted) deferred.complete(minted)
                                } else if (!u.isNullOrBlank() && u != "about:blank") {
                                    logInfo("Attempt #$attempt swallowed popunder: ${hostOnly(u)}")
                                }
                                runCatching { popupWv.stopLoading() }
                                mainHandler.postDelayed({
                                    if (popupWebViews.remove(popupWv)) {
                                        runCatching { popupWv.destroy() }
                                    }
                                }, 120L)
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
                        // Guarantee teardown even if popup stays on about:blank
                        mainHandler.postDelayed({
                            if (popupWebViews.remove(popupWv)) {
                                runCatching {
                                    popupWv.stopLoading()
                                    popupWv.destroy()
                                }
                            }
                        }, 450L)
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
                        if (target.startsWith("http") && !isAllowedGatewayNavigation(target, gatewayHost)) {
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
                root.addView(container, matchParent)

                wv.loadUrl(
                    CimaNowNetwork.encodeUrl(shineUrl),
                    mapOf("Referer" to CimaNowNetwork.encodeUrl(itemUrl)),
                )
            }

            withTimeoutOrNull(timeoutMs) {
                val startedMs = SystemClock.uptimeMillis()
                var firstBtnSeenMs = 0L
                var lastTsTapMs = 0L
                var lastOvlTapMs = 0L
                var lastBtnTapMs = 0L
                var tsTapCount = 0
                var btnTapCount = 0

                // Let initial /loadon/ -> href.li -> /redirectingfree/ -> /blog-post.html/ load cleanly
                delay(2_600L)

                while (!deferred.isCompleted) {
                    delay(450L)
                    if (deferred.isCompleted) break

                    val nowMs = SystemClock.uptimeMillis()
                    val elapsedMs = nowMs - startedMs
                    // Once blog-post.html (#downloadbtn) mounts, leave Stage B & invisible Turnstile
                    // completely undisturbed for 6.2s while the 10-second countdown runs.
                    if (firstBtnSeenMs > 0L && nowMs - firstBtnSeenMs < 6_200L) continue

                    val allowBtnForce = firstBtnSeenMs > 0L && (nowMs - firstBtnSeenMs >= 12_500L)
                    val allowTsTap = if (firstBtnSeenMs == 0L) {
                        elapsedMs >= 6_500L
                    } else {
                        nowMs - firstBtnSeenMs >= 8_200L
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

                    if (probeResult == "ERR:WAF" || probeResult == "ERR:BROWSER" || probeResult == "ERR:ADBLOCK") {
                        logWarn("Attempt #$attempt gateway rejected page ($probeResult) at ${elapsedMs}ms")
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
                            if (nowMs - lastBtnTapMs >= 520L) {
                                lastBtnTapMs = nowMs
                                val target = parseCssTapTarget(probeResult.removePrefix("BTN:"))
                                if (target != null) {
                                    btnTapCount++
                                    logInfo("Attempt #$attempt tap #downloadbtn #$btnTapCount css=(${target.cssX.toInt()},${target.cssY.toInt()}) vp=${target.viewportW.toInt()}x${target.viewportH.toInt()}")
                                    dispatchTrustedTap(containerRef, webViewRef, target)
                                }
                            }
                        }
                        probeResult.startsWith("OVL:") -> {
                            // Clear full-screen #xqeqjp / #xqeqjp1 overlay quickly so Turnstile checkbox can receive taps
                            val payload = probeResult.removePrefix("OVL:").removeSuffix(":HASBTN")
                            if (nowMs - lastOvlTapMs >= 480L) {
                                lastOvlTapMs = nowMs
                                parseCssTapTarget(payload)?.let { target ->
                                    logInfo("Attempt #$attempt dismissing #xqeqjp overlay css=(${target.cssX.toInt()},${target.cssY.toInt()})")
                                    dispatchTrustedTap(containerRef, webViewRef, target)
                                }
                            }
                        }
                        probeResult.startsWith("TS:") -> {
                            val payload = probeResult.removePrefix("TS:").removeSuffix(":HASBTN")
                            if (nowMs - lastTsTapMs >= 3_200L) {
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
                mainHandler.removeCallbacksAndMessages(null)
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

    internal fun buildReadOnlyGatewayProbeJs(countdownElapsed: Boolean, allowTurnstileTap: Boolean): String {
        val forceFlag = if (countdownElapsed) "true" else "false"
        val tsTapFlag = if (allowTurnstileTap) "true" else "false"
        return """
            (function(){
                try {
                    var loc = String(location.href || '');
                    if (loc.indexOf('/watching/') !== -1 && loc.indexOf('token=') !== -1) return 'URL:' + loc;

                    var anyWatchLink = document.querySelector('a[href*="/watching/"][href*="token="]');
                    if (anyWatchLink) {
                        var awh = String(anyWatchLink.href || anyWatchLink.getAttribute('href') || '');
                        if (awh.indexOf('http') === 0 && awh.indexOf('/watching/') !== -1 && awh.indexOf('token=') !== -1) {
                            return 'URL:' + awh;
                        }
                    }

                    var vv = window.visualViewport;
                    var vw = Math.round((vv && vv.width > 0) ? vv.width : ((document.documentElement && document.documentElement.clientWidth) || window.innerWidth || 0));
                    var vh = Math.round((vv && vv.height > 0) ? vv.height : ((document.documentElement && document.documentElement.clientHeight) || window.innerHeight || 0));
                    var vox = (vv && vv.offsetLeft) ? vv.offsetLeft : 0;
                    var voy = (vv && vv.offsetTop) ? vv.offsetTop : 0;

                    function refreshVv() {
                        vv = window.visualViewport;
                        vw = Math.round((vv && vv.width > 0) ? vv.width : ((document.documentElement && document.documentElement.clientWidth) || window.innerWidth || 0));
                        vh = Math.round((vv && vv.height > 0) ? vv.height : ((document.documentElement && document.documentElement.clientHeight) || window.innerHeight || 0));
                        vox = (vv && vv.offsetLeft) ? vv.offsetLeft : 0;
                        voy = (vv && vv.offsetTop) ? vv.offsetTop : 0;
                    }

                    function scrollCenter(el) {
                        try {
                            el.scrollIntoView({behavior: 'instant', block: 'center', inline: 'center'});
                        } catch(e1) {
                            var r = el.getBoundingClientRect();
                            var curY = window.pageYOffset || (document.documentElement && document.documentElement.scrollTop) || 0;
                            window.scrollTo(0, Math.max(0, curY + r.top - (vh / 2) + (r.height / 2)));
                        }
                        refreshVv();
                    }

                    function hasActiveOverlay() {
                        var ids = ['xqeqjp', 'xqeqjp1', 'xqeqjp2'];
                        for (var oi = 0; oi < ids.length; oi++) {
                            var oel = document.getElementById(ids[oi]);
                            if (oel && oel.offsetWidth > 20 && oel.offsetHeight > 20) {
                                var ocs = getComputedStyle(oel);
                                if (ocs.display !== 'none' && ocs.visibility !== 'hidden' && ocs.pointerEvents !== 'none') return true;
                            }
                        }
                        var extra = document.querySelectorAll('a[id^="xqeqjp"]');
                        for (var ei = 0; ei < extra.length; ei++) {
                            var eel = extra[ei];
                            if (eel && eel.offsetWidth > 20 && eel.offsetHeight > 20) {
                                var ecs = getComputedStyle(eel);
                                if (ecs.display !== 'none' && ecs.visibility !== 'hidden' && ecs.pointerEvents !== 'none') return true;
                            }
                        }
                        return false;
                    }

                    var btn = document.getElementById('downloadbtn');
                    var cd = document.getElementById('countdown');
                    var hAttr = btn ? String(btn.getAttribute('href') || '') : '';
                    var hProp = btn ? String(btn.href || '') : '';
                    if (btn) {
                        if (hAttr.indexOf('http') === 0 && hAttr.indexOf('/watching/') !== -1 && hAttr.indexOf('token=') !== -1) {
                            return 'URL:' + hAttr;
                        }
                        if (hProp.indexOf('http') === 0 && hProp.indexOf('/watching/') !== -1 && hProp.indexOf('token=') !== -1) {
                            return 'URL:' + hProp;
                        }
                        var isReadyHref = (hAttr === '#watch-ready') ||
                            (hProp.slice(-12) === '#watch-ready') ||
                            ($forceFlag && hAttr && hAttr !== '#' && hAttr !== 'javascript:void(0);' && hAttr.indexOf('blog-post_75') === -1);
                        if (isReadyHref) {
                            var cs = getComputedStyle(btn);
                            if ($forceFlag && !cd && (btn.hidden || cs.display === 'none')) {
                                btn.removeAttribute('hidden');
                                btn.style.setProperty('display', 'inline-block', 'important');
                            }
                            // Dismiss full-screen popunder overlays (`#xqeqjp`, `#xqeqjp1`, z-index 99999999)
                            // BEFORE tapping `#downloadbtn`, including when Turnstile auto-passes invisibly!
                            if (hasActiveOverlay() && vw > 40 && vh > 40) {
                                return 'OVL:' + Math.round(vw / 2) + ',' + Math.round(vh / 2) + ',' + vw + ',' + vh + ':HASBTN';
                            }
                            var br = btn.getBoundingClientRect();
                            if (br.width > 4 && br.height > 4) {
                                var bvx = Math.round(br.left + br.width / 2 - vox);
                                var bvy = Math.round(br.top + br.height / 2 - voy);
                                if (bvy < 6 || bvy > vh - 6 || bvx < 4 || bvx > vw - 4) {
                                    scrollCenter(btn);
                                    br = btn.getBoundingClientRect();
                                    bvx = Math.round(br.left + br.width / 2 - vox);
                                    bvy = Math.round(br.top + br.height / 2 - voy);
                                }
                                if (bvx >= 2 && bvx <= vw - 2 && bvy >= 2 && bvy <= vh - 2) {
                                    return 'BTN:' + bvx + ',' + bvy + ',' + vw + ',' + vh;
                                }
                                return 'SCROLL:HASBTN';
                            }
                        }
                    } else {
                        var btxt = String((document.body && document.body.innerText) || '');
                        if (btxt.indexOf('unable to access freex2line') !== -1 || btxt.indexOf('Sorry, you have been blocked') !== -1) {
                            return 'ERR:WAF';
                        }
                        if (btxt.indexOf('استخدم متصفح اخر') !== -1 || btxt.indexOf('متصفح آخر') !== -1) {
                            return 'ERR:BROWSER';
                        }
                        if (btxt.indexOf('منع الإعلانات') !== -1 || btxt.indexOf('منع الاعلانات') !== -1) {
                            return 'ERR:ADBLOCK';
                        }
                    }

                    if ($tsTapFlag && hAttr !== '#watch-ready') {
                        var inp = document.querySelector('input[name="cf-turnstile-response"]');
                        var tokReady = inp && inp.value && inp.value.length > 20;
                        if (!tokReady) {
                            var box = null;
                            var ifr = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                            if (ifr) {
                                var ir = ifr.getBoundingClientRect();
                                if (ir.width >= 40 && ir.height >= 25 && ir.height <= 160) box = ifr;
                            }
                            if (!box) {
                                var hosts = document.querySelectorAll('.cf-turnstile, [data-sitekey], #challenge-stage > div, html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div');
                                for (var ci = 0; ci < hosts.length && !box; ci++) {
                                    var hEl = hosts[ci];
                                    var sub = hEl.querySelectorAll('div, iframe');
                                    for (var si = 0; si < sub.length; si++) {
                                        var sr = sub[si].getBoundingClientRect();
                                        if (sr.width >= 180 && sr.width <= 340 && sr.height >= 40 && sr.height <= 100) {
                                            box = sub[si];
                                            break;
                                        }
                                    }
                                    if (!box) {
                                        var r0 = hEl.getBoundingClientRect();
                                        if (r0.width >= 40 && r0.height >= 25 && r0.height <= 160) box = hEl;
                                    }
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
                            if (box) {
                                if (hasActiveOverlay() && vw > 40 && vh > 40) {
                                    return 'OVL:' + Math.round(vw / 2) + ',' + Math.round(vh / 2) + ',' + vw + ',' + vh + (btn ? ':HASBTN' : '');
                                }
                                var tr = box.getBoundingClientRect();
                                var tvy = Math.round(tr.top + tr.height / 2 - voy);
                                if (tvy < 6 || tvy > vh - 6) {
                                    scrollCenter(box);
                                    tr = box.getBoundingClientRect();
                                    tvy = Math.round(tr.top + tr.height / 2 - voy);
                                }
                                if (tr.width >= 40 && tr.height >= 25 && tvy >= 2 && tvy <= vh - 2) {
                                    var tl = tr.left - vox;
                                    var trRight = tr.right - vox;
                                    var pts = [];
                                    if (tr.width <= 340) {
                                        pts.push(Math.round(tl + 28) + ',' + tvy + ',' + vw + ',' + vh);
                                        pts.push(Math.round(trRight - 28) + ',' + tvy + ',' + vw + ',' + vh);
                                    } else {
                                        var mid = (tl + trRight) / 2;
                                        pts.push(Math.round(mid - 122) + ',' + tvy + ',' + vw + ',' + vh);
                                        pts.push(Math.round(mid + 122) + ',' + tvy + ',' + vw + ',' + vh);
                                        pts.push(Math.round(tl + 28) + ',' + tvy + ',' + vw + ',' + vh);
                                        pts.push(Math.round(trRight - 28) + ',' + tvy + ',' + vw + ',' + vh);
                                    }
                                    return 'TS:' + pts.join('|') + (btn ? ':HASBTN' : '');
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

    // ── Layer-2 Real-V8 `/watching/` Vault Unlocker (Future-Proof Android Fallback) ──

    internal data class ParsedWatchingDomSnapshot(
        val totalTabs: Int,
        val clickedIdx: Int,
        val tabName: String,
        val tapSpec: String,
        val iframeSrcs: List<String>,
        val downloads: List<Pair<String, String>>,
    )

    internal fun parseWatchingDomProbeOutput(rawText: String): ParsedWatchingDomSnapshot? {
        if (rawText.isBlank()) return null
        var totalTabs = 0
        var clickedIdx = -1
        var tabName = "Watch"
        var tapSpec = ""
        val iframes = ArrayList<String>()
        val downloads = ArrayList<Pair<String, String>>()
        var sawMeta = false

        for (line in rawText.split('\n')) {
            if (line.startsWith("META|")) {
                sawMeta = true
                val parts = line.split('|')
                totalTabs = parts.getOrNull(1)?.toIntOrNull() ?: 0
                clickedIdx = parts.getOrNull(2)?.toIntOrNull() ?: -1
                tabName = parts.getOrNull(3)?.trim().orEmpty().ifBlank { "Watch" }
                tapSpec = parts.getOrNull(4)?.trim().orEmpty()
            } else if (line.startsWith("IF|")) {
                val u = line.substring(3).trim()
                if (u.isNotEmpty()) iframes.add(u)
            } else if (line.startsWith("DL|")) {
                val rest = line.substring(3)
                val tabPos = rest.indexOf('\t')
                if (tabPos > 0) {
                    val u = rest.substring(0, tabPos).trim()
                    val lbl = rest.substring(tabPos + 1).trim().ifBlank { "Download" }
                    if (u.isNotEmpty()) downloads.add(u to lbl)
                } else if (rest.isNotBlank()) {
                    downloads.add(rest.trim() to "Download")
                }
            }
        }
        return if (sawMeta) {
            ParsedWatchingDomSnapshot(totalTabs, clickedIdx, tabName, tapSpec, iframes, downloads)
        } else {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    suspend fun extractWatchingSourcesViaWebView(
        mintedWatchingUrl: String,
        refererUrl: String,
        preloadedWatchHtml: String? = null,
    ): WebViewWatchingResult? {
        if (Build.VERSION.SDK_INT <= 0) return null
        val activity = CimaNowNetwork.resolveActivity() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null

        return solverMutex.withLock {
            var containerRef: PassThroughWebViewContainer? = null
            var webViewRef: WebView? = null
            val interceptedEmbeds = CopyOnWriteArraySet<String>()

            try {
                withContext(Dispatchers.Main) {
                    val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@withContext
                    val container = PassThroughWebViewContainer(activity).apply {
                        alpha = 0.01f
                        visibility = View.VISIBLE
                        isFocusable = false
                        isClickable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
                    containerRef = container

                    val wv = WebView(activity).apply {
                        alpha = 1.0f
                        visibility = View.VISIBLE
                        isFocusable = false
                        isClickable = false
                    }
                    webViewRef = wv

                    suppressXRequestedWithHeader(wv.settings)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = false
                        // Preserve exact User-Agent parity with CimaNowNetwork / CookieManager
                        val targetUa = CimaNowNetwork.preferredUserAgent
                        val defaultUa = userAgentString.orEmpty()
                        if (targetUa.isNotBlank() && targetUa != defaultUa && targetUa != CimaNowNetwork.MOBILE_UA) {
                            userAgentString = targetUa
                        }
                    }

                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean = false
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            if (request != null && !request.isForMainFrame) return false
                            val u = request?.url?.toString().orEmpty()
                            return u.startsWith("http") && !hostOnly(u).contains("cimanow")
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val u = url.orEmpty()
                            return u.startsWith("http") && !hostOnly(u).contains("cimanow")
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            if (request != null && !request.isForMainFrame) {
                                val u = request.url?.toString().orEmpty()
                                val h = hostOnly(u)
                                if (u.startsWith("http") && !isWatchingPageInfraHost(h)) {
                                    interceptedEmbeds.add(u)
                                    return WebResourceResponse(
                                        "text/html",
                                        "UTF-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    val matchParent = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    container.addView(wv, matchParent)
                    root.addView(container, matchParent)

                    val encodedWatch = CimaNowNetwork.encodeUrl(mintedWatchingUrl)
                    if (!preloadedWatchHtml.isNullOrBlank() && preloadedWatchHtml.length > 2_000) {
                        logInfo("Loading pre-fetched /watching/ HTML into V8 WebView (${preloadedWatchHtml.length} chars)")
                        wv.loadDataWithBaseURL(encodedWatch, preloadedWatchHtml, "text/html", "UTF-8", encodedWatch)
                    } else {
                        wv.loadUrl(
                            encodedWatch,
                            mapOf("Referer" to CimaNowNetwork.encodeUrl(refererUrl)),
                        )
                    }
                }

                val embeds = LinkedHashMap<String, String>()
                val downloads = LinkedHashMap<String, CimaNowCrypto.DownloadEntry>()
                var activeServerLabel = "Watch"

                withTimeoutOrNull(WATCHING_WV_TIMEOUT_MS) {
                    delay(1_500L)
                    var clickedTabUpTo = -1
                    var totalTabs = 0

                    for (step in 0 until 26) {
                        val jsOut = withContext(Dispatchers.Main) {
                            val wv = webViewRef ?: return@withContext ""
                            val nextIdx = clickedTabUpTo + 1
                            // Anti-tamper-safe DOM tree walker: avoids `querySelectorAll`, `querySelector`,
                            // `getElementsByTagName`, `getAttribute`, `innerHTML`/`outerHTML`, and `JSON.stringify`
                            // (ALL hooked by CimaNow's `cnV14Ultra+` vault!), walking C++ sibling pointers
                            // (`lastElementChild` / `previousElementSibling`) and reading `.src` / `.href` directly.
                            evalJsSuspend(
                                wv,
                                """
                                (function(){
                                    try {
                                        var vv = window.visualViewport;
                                        var vw = Math.round((vv && vv.width > 0) ? vv.width : ((document.documentElement && document.documentElement.clientWidth) || window.innerWidth || 393));
                                        var vh = Math.round((vv && vv.height > 0) ? vv.height : ((document.documentElement && document.documentElement.clientHeight) || window.innerHeight || 873));
                                        var vox = (vv && vv.offsetLeft) ? vv.offsetLeft : 0;
                                        var voy = (vv && vv.offsetTop) ? vv.offsetTop : 0;
                                        var lines = [];
                                        var ifs = [], as = [], rawLis = [];
                                        var rootNode = document.body || document.documentElement;
                                        var stack = rootNode ? [rootNode] : [];
                                        while (stack.length > 0) {
                                            var node = stack.pop();
                                            var tn = String(node.tagName || '').toUpperCase();
                                            if (tn === 'IFRAME') ifs.push(node);
                                            else if (tn === 'A') as.push(node);
                                            else if (tn === 'LI') {
                                                var wp = node.parentElement;
                                                for (var wd = 0; wd < 5 && wp; wd++, wp = wp.parentElement) {
                                                    if (String(wp.id || '') === 'watch') { rawLis.push(node); break; }
                                                }
                                            }
                                            var ch = node.lastElementChild;
                                            while (ch) { stack.push(ch); ch = ch.previousElementSibling; }
                                        }
                                        for (var i = 0; i < ifs.length; i++) {
                                            var s = String(ifs[i].src || '');
                                            if (s.indexOf('http') === 0 || s.indexOf('//') === 0) {
                                                lines.push('IF|' + s.replace(/[\r\n]+/g, ''));
                                            }
                                        }
                                        for (var j = 0; j < as.length; j++) {
                                            var hr = String(as[j].href || '');
                                            if ((hr.indexOf('http') === 0 || hr.indexOf('//') === 0) && hr.indexOf('javascript:') !== 0) {
                                                var inDl = false;
                                                var p = as[j].parentElement;
                                                for (var d = 0; d < 5 && p; d++, p = p.parentElement) {
                                                    var pid = String(p.id || '');
                                                    var pcl = String(p.className || '');
                                                    if (pid === 'download' || pcl.indexOf('download') !== -1) { inDl = true; break; }
                                                }
                                                if (inDl || hr.indexOf('cimanowtv') !== -1 || hr.indexOf('vk.com/doc') !== -1 || hr.indexOf('jetload') !== -1 || hr.indexOf('upns.') !== -1 || hr.indexOf('byse') !== -1 || hr.indexOf('filemoon') !== -1 || hr.indexOf('savefiles') !== -1 || hr.indexOf('streamhls') !== -1 || hr.indexOf('uqload') !== -1) {
                                                    var lbl = String(as[j].textContent || '').replace(/[\r\n\t|]+/g, ' ').trim().slice(0, 80);
                                                    lines.push('DL|' + hr.replace(/[\r\n\t]+/g, '') + '\t' + lbl);
                                                }
                                            }
                                        }
                                        var lis = [];
                                        for (var k = 0; k < rawLis.length; k++) {
                                            var el = rawLis[k];
                                            var cs = getComputedStyle(el);
                                            if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue;
                                            var ds = el.dataset || {};
                                            if (String(ds.id || '') === '00' || String(ds.index || '') === '00') continue;
                                            if (el.offsetWidth <= 2 && el.offsetHeight <= 2) continue;
                                            var txt = String(el.textContent || '').trim();
                                            if (!txt && (!el.children || el.children.length === 0)) continue;
                                            lis.push(el);
                                        }
                                        var clicked = -1;
                                        var tabName = '';
                                        var tap = '';
                                        if ($nextIdx < lis.length) {
                                            var li = lis[$nextIdx];
                                            tabName = String(li.textContent || ('Server ' + ($nextIdx + 1))).replace(/[\r\n|]+/g, ' ').trim().slice(0, 40);
                                            try { li.scrollIntoView({behavior: 'instant', block: 'center', inline: 'center'}); } catch(e0) {}
                                            vv = window.visualViewport;
                                            vox = (vv && vv.offsetLeft) ? vv.offsetLeft : 0;
                                            voy = (vv && vv.offsetTop) ? vv.offsetTop : 0;
                                            var r = li.getBoundingClientRect();
                                            if (r.width > 4 && r.height > 4) {
                                                tap = Math.round(r.left + r.width / 2 - vox) + ',' + Math.round(r.top + r.height / 2 - voy) + ',' + vw + ',' + vh;
                                            }
                                            clicked = $nextIdx;
                                        }
                                        lines.unshift('META|' + lis.length + '|' + clicked + '|' + tabName + '|' + tap);
                                        return lines.join('\n');
                                    } catch(e) { return ''; }
                                })()
                                """.trimIndent(),
                            )
                        }

                        val snap = parseWatchingDomProbeOutput(jsOut)
                        if (snap != null) {
                            totalTabs = snap.totalTabs
                            for (rawIf in snap.iframeSrcs) {
                                CimaNowCrypto.normalizeEmbedUrl(rawIf)?.let { norm ->
                                    if (!isWatchingPageInfraHost(hostOnly(norm))) {
                                        embeds.putIfAbsent(norm, activeServerLabel)
                                    }
                                }
                            }
                            for ((rawDl, lbl) in snap.downloads) {
                                val u = CimaNowCrypto.normalizeEmbedUrl(rawDl) ?: continue
                                val q = Regex("""(2160|1440|1080|720|480|360|240)""").find("$lbl $u")
                                    ?.groupValues?.get(1)?.toIntOrNull()
                                downloads.putIfAbsent(
                                    u,
                                    CimaNowCrypto.DownloadEntry(url = u, label = lbl, qualityHint = q),
                                )
                            }
                            if (snap.clickedIdx >= 0) {
                                clickedTabUpTo = snap.clickedIdx
                                activeServerLabel = snap.tabName.ifBlank { "Server ${snap.clickedIdx + 1}" }
                                parseCssTapTarget(snap.tapSpec)?.let { target ->
                                    dispatchTrustedTap(containerRef, webViewRef, target)
                                }
                                delay(680L)
                                continue
                            } else if (totalTabs > 0 && clickedTabUpTo >= totalTabs - 1 && (embeds.isNotEmpty() || downloads.isNotEmpty())) {
                                // Only attach intercepted URLs if their host wasn't already captured with `#fragment` via `iframe.src`
                                val capturedHosts = embeds.keys.map { hostOnly(it) }.toSet()
                                for (ic in interceptedEmbeds) {
                                    CimaNowCrypto.normalizeEmbedUrl(ic)?.let { norm ->
                                        if (hostOnly(norm) !in capturedHosts) {
                                            embeds.putIfAbsent(norm, activeServerLabel)
                                        }
                                    }
                                }
                                break
                            }
                        }
                        delay(450L)
                    }
                }

                if (embeds.isEmpty() && downloads.isEmpty()) {
                    null
                } else {
                    logInfo("WebView V8 vault fallback extracted embeds=${embeds.size} downloads=${downloads.size}")
                    WebViewWatchingResult(
                        watchEmbeds = embeds.entries.map { (u, lbl) -> lbl to u },
                        downloads = downloads.values.toList(),
                    )
                }
            } catch (t: Throwable) {
                logWarn("extractWatchingSourcesViaWebView error: ${t.message}")
                null
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
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
                }
            }
        }
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

    internal fun computeViewTapPoint(wvWidth: Int, wvHeight: Int, density: Float, target: CssTapTarget): Pair<Float, Float> {
        val safeDensity = density.coerceAtLeast(1f)
        val scaleX = if (target.viewportW > 0f && wvWidth > 0) {
            wvWidth.toFloat() / target.viewportW
        } else {
            safeDensity
        }
        val scaleY = if (target.viewportH > 0f && wvHeight > 0) {
            wvHeight.toFloat() / target.viewportH
        } else {
            safeDensity
        }
        val maxX = (wvWidth - 2).coerceAtLeast(4).toFloat()
        val maxY = (wvHeight - 2).coerceAtLeast(4).toFloat()
        val realX = (target.cssX * scaleX).coerceIn(2f, maxX)
        val realY = (target.cssY * scaleY).coerceIn(2f, maxY)
        return realX to realY
    }

    private fun computeViewTapPoint(wv: WebView, target: CssTapTarget): Pair<Float, Float> =
        computeViewTapPoint(wv.width, wv.height, wv.resources.displayMetrics.density, target)

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
        var downEvent: MotionEvent? = null
        try {
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
                    downEvent = down
                    container?.allowSyntheticTouch = true
                    try {
                        wv.dispatchTouchEvent(down)
                    } finally {
                        container?.allowSyntheticTouch = false
                    }
                }
            }
            delay(65L)
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
        } finally {
            runCatching { downEvent?.recycle() }
        }
    }

    internal fun decodeEvaluateJavascriptResult(raw: String?): String {
        val s = raw?.trim() ?: return ""
        if (s.isEmpty() || s == "null" || s == "undefined") return ""
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            runCatching {
                val parsed = JSONTokener(s).nextValue()
                if (parsed != null && parsed !== JSONObject.NULL) {
                    return parsed.toString()
                }
            }
        }
        return s
    }

    private suspend fun evalJsSuspend(wv: WebView, script: String): String =
        withTimeoutOrNull(2_000L) {
            val d = CompletableDeferred<String>()
            wv.evaluateJavascript(script) { raw ->
                if (!d.isCompleted) d.complete(decodeEvaluateJavascriptResult(raw))
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
                    ? spawn(xvfb, ['-a', '--server-args=-screen 0 1280x960x24', chromeBin, ...args], { stdio: ['ignore', 'ignore', 'pipe'] })
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
                    for (let i = 0; i < 45; i++) {
                        try { targets = await getJson('http://127.0.0.1:' + port + '/json'); if (targets && targets.length) break; } catch(e) {}
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
                    let lastTsClick = 0, lastOvlClick = 0, lastBtnClick = 0, firstBtnMs = 0;
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
                        const allowBtnForce = firstBtnMs > 0 && (ms - firstBtnMs >= 12000);
                        const allowTsTap = firstBtnMs === 0 ? (ms >= 6000) : (ms - firstBtnMs >= 8500);
                        const probe = await send('Runtime.evaluate', {
                            expression: `(function(){
                                try {
                                    var loc = String(location.href || '');
                                    if (loc.indexOf('/watching/') !== -1 && loc.indexOf('token=') !== -1) return 'URL:' + loc;
                                    var iw = Math.round(window.innerWidth || 412);
                                    var ih = Math.round(window.innerHeight || 915);
                                    var btn = document.getElementById('downloadbtn');
                                    var h = btn ? String(btn.getAttribute('href') || '') : '';
                                    if (btn) {
                                        if (h.indexOf('http') === 0 && h.indexOf('/watching/') !== -1 && h.indexOf('token=') !== -1) return 'URL:' + h;
                                        if (h === '#watch-ready' || (${'$'}{allowBtnForce} && h && h !== '#' && h !== 'javascript:void(0);' && h.indexOf('blog-post_75') === -1)) {
                                            if (${'$'}{allowBtnForce} && (btn.hidden || getComputedStyle(btn).display === 'none')) {
                                                btn.removeAttribute('hidden');
                                                btn.style.setProperty('display', 'inline-block', 'important');
                                            }
                                            try { btn.scrollIntoView({behavior:'instant', block:'center', inline:'center'}); } catch(e) {}
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
                                        if (box) {
                                            var ovl1 = document.getElementById('xqeqjp');
                                            var ovl2 = document.getElementById('xqeqjp1');
                                            var activeOvl = (ovl1 && ovl1.offsetWidth > 10 && getComputedStyle(ovl1).display !== 'none') ? ovl1
                                                : ((ovl2 && ovl2.offsetWidth > 10 && getComputedStyle(ovl2).display !== 'none') ? ovl2 : null);
                                            if (activeOvl) {
                                                return 'OVL:' + Math.round(iw / 2) + ',' + Math.round(ih / 2) + (btn ? ':HASBTN' : '');
                                            }
                                            try { box.scrollIntoView({behavior:'instant', block:'center', inline:'center'}); } catch(e) {}
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
                        } else if (pv.startsWith('OVL:') && ms - lastOvlClick >= 500) {
                            lastOvlClick = ms;
                            const [ox, oy] = pv.slice(4).replace(':HASBTN', '').split(',').map(Number);
                            if (ox > 0 && oy > 0) await tap(ox, oy);
                        } else if (pv.startsWith('BTN:') && ms - lastBtnClick >= 600) {
                            lastBtnClick = ms;
                            const [bx, by] = pv.slice(4).split(',').map(Number);
                            if (bx > 0 && by > 0) await tap(bx, by);
                        } else if (pv.startsWith('TS:') && ms - lastTsClick >= 3500) {
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
                val finished = p.waitFor(48L, TimeUnit.SECONDS)
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
