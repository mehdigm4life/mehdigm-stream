package com.egydead

import android.content.Context

object EgyDeadPrefsEncoder {
    private const val PREFS_NAME = "egydead_cf_prefs"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_TS = "last_ts"

    fun saveCookies(cookies: String?) {
        if (cookies.isNullOrBlank()) return
        EgyDeadPrefs.savedCookieHeader = cookies
        val ctx = getContext() ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIES, cookies)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }

    fun loadCookies(): String? {
        val ctx = getContext()
        if (ctx != null) {
            val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val c = p.getString(KEY_COOKIES, null)
            val ts = p.getLong(KEY_TS, 0)
            if (!c.isNullOrBlank() && (ts == 0L || System.currentTimeMillis() - ts <= 7L * 24 * 60 * 60 * 1000)) {
                return c
            }
        }
        return EgyDeadPrefs.savedCookieHeader?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        val ctx = getContext() ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_COOKIES)
            .remove(KEY_TS)
            .apply()
    }

    private fun getContext(): Context? {
        EgyDeadPrefs.appContext?.let { return it }
        return try {
            Class.forName("com.lagradost.cloudstream3.CloudStreamApp")
                .getMethod("getContext")
                .invoke(null) as? Context
        } catch (_: Throwable) {
            try {
                Class.forName("com.lagradost.cloudstream3.AcraApplication")
                    .getMethod("getApplicationContext")
                    .invoke(null) as? Context
            } catch (_: Throwable) {
                null
            }
        }
    }
}
