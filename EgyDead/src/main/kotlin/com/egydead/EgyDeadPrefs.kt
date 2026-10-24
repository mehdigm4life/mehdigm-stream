package com.egydead

import android.content.Context
import android.content.SharedPreferences

object EgyDeadPrefs {
    private const val FILE_NAME = "egydead_prefs_v2"
    private const val KEY_CUSTOM_DOMAIN = "custom_domain"
    private const val KEY_DISCOVERED_DOMAIN = "discovered_domain"
    private const val KEY_USE_PROXY = "use_proxy"
    private const val KEY_CUSTOM_PROXY = "custom_proxy"
    private const val KEY_CF_CLEARANCE = "cf_clearance"
    private const val KEY_CF_UA = "cf_ua"

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

    var customDomain: String?
        get() = prefs?.getString(KEY_CUSTOM_DOMAIN, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.apply {
                val clean = value?.trim()?.trimEnd('/')
                if (clean.isNullOrBlank()) remove(KEY_CUSTOM_DOMAIN)
                else putString(KEY_CUSTOM_DOMAIN, clean)
                apply()
            }
        }

    var discoveredDomain: String?
        get() = prefs?.getString(KEY_DISCOVERED_DOMAIN, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.apply {
                val clean = value?.trim()?.trimEnd('/')
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

    var savedUserAgent: String?
        get() = prefs?.getString(KEY_CF_UA, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.apply {
                if (value.isNullOrBlank()) remove(KEY_CF_UA)
                else putString(KEY_CF_UA, value)
                apply()
            }
        }

    fun clearCookies() {
        cfClearance = null
        savedUserAgent = null
    }
}
