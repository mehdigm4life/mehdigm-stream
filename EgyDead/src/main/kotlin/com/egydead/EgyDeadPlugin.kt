package com.egydead

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EgyDeadPlugin : Plugin() {
    override fun load(context: Context) {
        EgyDeadPrefs.init(context)

        // Register main provider
        registerMainAPI(EgyDead())

        // Register custom pure-Kotlin multi-quality video & download extractors
        registerExtractorAPI(EgyDeadMegaMaxExtractor())
        registerExtractorAPI(EgyDeadShare4MaxExtractor())
        registerExtractorAPI(EgyDeadMegaMaxRotatingExtractor())
        registerExtractorAPI(EgyDeadByseExtractor())
        registerExtractorAPI(EgyDeadUpnShareExtractor())
        registerExtractorAPI(EgyDeadStreamP2PExtractor())
        registerExtractorAPI(EgyDeadRpmShareExtractor())
        registerExtractorAPI(EgyDeadFireStreamExtractor())
        registerExtractorAPI(EgyDeadVidaraExtractor())
        registerExtractorAPI(EgyDeadStreamHGExtractor())
        registerExtractorAPI(EgyDeadHanerixExtractor())
        registerExtractorAPI(EgyDeadHgCloudExtractor())
        registerExtractorAPI(EgyDeadEarnVidsExtractor())
        registerExtractorAPI(EgyDeadMixdropExtractor())
        registerExtractorAPI(EgyDeadDoodExtractor())
        registerExtractorAPI(EgyDeadVoeExtractor())
        registerExtractorAPI(EgyDeadFileMoonExtractor())
        registerExtractorAPI(EgyDeadMediaFireExtractor())
        registerExtractorAPI(EgyDeadMp4UploadExtractor())
        registerExtractorAPI(EgyDeadUqloadExtractor())
        registerExtractorAPI(EgyDeadVkExtractor())
        registerExtractorAPI(EgyDeadVidozaExtractor())
        registerExtractorAPI(EgyDeadKoramaUpExtractor())
        registerExtractorAPI(EgyDeadUpvidExtractor())
        registerExtractorAPI(EgyDeadSaveFilesExtractor())
        registerExtractorAPI(EgyDeadLuluStreamExtractor())
        registerExtractorAPI(EgyDeadVidmolyExtractor())

        openSettings = { ctx ->
            showSettingsDialog(ctx)
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            ctx.resources.displayMetrics
        ).toInt()

    private fun roundedBg(colorHex: String, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    private fun showSettingsDialog(context: Context) {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#121218"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 20)
            setPadding(p, p, p, p)
        }

        val title = TextView(context).apply {
            text = "إعدادات إيجي ديد (EgyDead)"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 8))
        }
        root.addView(title)

        val activeDomainLabel = TextView(context).apply {
            val active = EgyDeadPrefs.customDomain ?: EgyDeadPrefs.discoveredDomain ?: EgyDeadNetwork.DEFAULT_BASE_URL
            text = "الدومين النشط حالياً: $active/h3/\n(يدعم تجاوز Cloudflare تلقائياً + DoH)"
            textSize = 12f
            setTextColor(Color.parseColor("#A0A8B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 16))
        }
        root.addView(activeDomainLabel)

        val domainInput = EditText(context).apply {
            hint = "اتركه فارغاً للاكتشاف التلقائي (مثال: https://tv10.egydead.live)"
            setHintTextColor(Color.parseColor("#687080"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            background = roundedBg("#1E202B", 8, context)
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
            setText(EgyDeadPrefs.customDomain.orEmpty())
        }
        root.addView(domainInput)

        val proxySwitch = Switch(context).apply {
            text = "تفعيل البروكسي المخصص"
            setTextColor(Color.WHITE)
            textSize = 14f
            isChecked = EgyDeadPrefs.useProxy
            val pad = dp(context, 12)
            setPadding(0, pad, 0, pad)
        }
        root.addView(proxySwitch)

        val proxyInput = EditText(context).apply {
            hint = "بروكسي اختياري (مثال: socks5://host:1080 أو host:8080)"
            setHintTextColor(Color.parseColor("#687080"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            background = roundedBg("#1E202B", 8, context)
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
            setText(EgyDeadPrefs.customProxy.orEmpty())
        }
        root.addView(proxyInput)

        scroll.addView(root)

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setPositiveButton("حفظ") { _, _ ->
                EgyDeadPrefs.customDomain = domainInput.text?.toString()
                EgyDeadPrefs.useProxy = proxySwitch.isChecked
                EgyDeadPrefs.customProxy = proxyInput.text?.toString()
                EgyDeadPrefs.clearCookies()
                EgyDeadNetwork.invalidateDomainCache()
                Toast.makeText(context, "تم حفظ إعدادات EgyDead بنجاح", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("إعادة الضبط التلقائي") { _, _ ->
                EgyDeadPrefs.customDomain = null
                EgyDeadPrefs.discoveredDomain = null
                EgyDeadPrefs.useProxy = false
                EgyDeadPrefs.customProxy = null
                EgyDeadPrefs.clearCookies()
                EgyDeadNetwork.invalidateDomainCache()
                Toast.makeText(context, "تم تفعيل الاكتشاف التلقائي للدومين", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
