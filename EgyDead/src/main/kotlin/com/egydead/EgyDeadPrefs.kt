package com.egydead

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object EgyDeadPrefs {
    private const val FILE_NAME = "egydead_prefs_v2"
    private const val KEY_CUSTOM_DOMAIN = "custom_domain"
    private const val KEY_DISCOVERED_DOMAIN = "discovered_domain"
    private const val KEY_USE_PROXY = "use_proxy"
    private const val KEY_CUSTOM_PROXY = "custom_proxy"
    private const val KEY_CF_CLEARANCE = "cf_clearance"
    private const val KEY_SAVED_COOKIE_HEADER = "saved_cookie_header"
    private const val KEY_CF_UA = "cf_ua"
    private const val KEY_WORKING_PROXIES = "working_proxies_v1"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = appContext?.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Normalizes user-entered or redirected URLs such as `https://tv10.egydead.live/h3/`
     * or `tv10.egydead.live/h3` into a clean scheme+host origin (`https://tv10.egydead.live`).
     */
    fun normalizeDomainOrigin(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return trimmed.trimEnd('/')
        val portSuffix = if ((parsed.scheme == "https" && parsed.port == 443) ||
            (parsed.scheme == "http" && parsed.port == 80)
        ) {
            ""
        } else {
            ":${parsed.port}"
        }
        return "${parsed.scheme}://${parsed.host}$portSuffix"
    }

    var customDomain: String?
        get() = normalizeDomainOrigin(prefs?.getString(KEY_CUSTOM_DOMAIN, null))
        set(value) {
            prefs?.edit()?.apply {
                val clean = normalizeDomainOrigin(value)
                if (clean.isNullOrBlank()) remove(KEY_CUSTOM_DOMAIN)
                else putString(KEY_CUSTOM_DOMAIN, clean)
                apply()
            }
        }

    var discoveredDomain: String?
        get() = normalizeDomainOrigin(prefs?.getString(KEY_DISCOVERED_DOMAIN, null))
        set(value) {
            prefs?.edit()?.apply {
                val clean = normalizeDomainOrigin(value)
                if (clean.isNullOrBlank()) remove(KEY_DISCOVERED_DOMAIN)
                else putString(KEY_DISCOVERED_DOMAIN, clean)
                apply()
            }
        }

    var useProxy: Boolean
        get() = prefs?.getBoolean(KEY_USE_PROXY, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_USE_PROXY, value)?.apply()
        }

    var customProxy: String?
        get() = prefs?.getString(KEY_CUSTOM_PROXY, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.apply {
                val clean = value?.trim()
                if (clean.isNullOrBlank()) remove(KEY_CUSTOM_PROXY)
                else putString(KEY_CUSTOM_PROXY, clean)
                apply()
            }
        }

    var cfClearance: String?
        get() = prefs?.getString(KEY_CF_CLEARANCE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.apply {
                if (value.isNullOrBlank()) remove(KEY_CF_CLEARANCE)
                else putString(KEY_CF_CLEARANCE, value)
                apply()
            }
        }

    var savedCookieHeader: String?
        get() = prefs?.getString(KEY_SAVED_COOKIE_HEADER, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.apply {
                if (value.isNullOrBlank()) remove(KEY_SAVED_COOKIE_HEADER)
                else putString(KEY_SAVED_COOKIE_HEADER, value)
                apply()
            }
        }

    var savedUserAgent: String?
        get() = prefs?.getString(KEY_CF_UA, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.apply {
                if (value.isNullOrBlank()) remove(KEY_CF_UA)
                else putString(KEY_CF_UA, value)
                apply()
            }
        }

    var savedWorkingProxies: List<String>
        get() = prefs?.getString(KEY_WORKING_PROXIES, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.contains("://") && it.contains(':') }
            .orEmpty()
        set(value) {
            prefs?.edit()?.apply {
                val clean = value.map { it.trim() }.filter { it.contains("://") }.distinct().take(6)
                if (clean.isEmpty()) remove(KEY_WORKING_PROXIES)
                else putString(KEY_WORKING_PROXIES, clean.joinToString(","))
                apply()
            }
        }

    fun clearCookies() {
        cfClearance = null
        savedCookieHeader = null
        savedUserAgent = null
        savedWorkingProxies = emptyList()
        EgyDeadPrefsEncoder.clear()
    }
}
