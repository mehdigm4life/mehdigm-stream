package com.timemovies

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.concurrent.Executors

@CloudstreamPlugin
class TimeMoviesPlugin : Plugin() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun load(context: Context) {
        appContext = context.applicationContext ?: context
        TimeMoviesPrefs.init(context)
        TimeMoviesPrefs.activateZeroAccountModeOnStartup()

        // Background session & OTA warm-up so TimeMovies stays permanently ready without any user setup
        ioExecutor.execute {
            TimeMoviesApi.warmUpAndRefreshPoolIfNeeded()
        }

        // Register main TimeMovies v4.7 provider
        registerMainAPI(TimeMovies())

        // Register DoomVideoPlayer + MediaV2 + all supported external hoster extractors
        registerExtractorAPI(DoomPlayerPlayExtractor())
        registerExtractorAPI(DoomPlayerHttpsPlayExtractor())
        registerExtractorAPI(DoomPlayerDownloadExtractor())
        registerExtractorAPI(DoomPlayerHttpsDownloadExtractor())
        registerExtractorAPI(TimeMoviesMediaV2Extractor())

        registerExtractorAPI(TmStreamHgExtractor())
        registerExtractorAPI(TmCavanhabgExtractor())
        registerExtractorAPI(TmDumbalagExtractor())
        registerExtractorAPI(TmUasoptExtractor())
        registerExtractorAPI(TmDhcplayExtractor())
        registerExtractorAPI(TmEarnVidsExtractor())

        registerExtractorAPI(TmVidaraExtractor())
        registerExtractorAPI(TmVidaraSoExtractor())
        registerExtractorAPI(TmVidrobaExtractor())

        registerExtractorAPI(TmByseExtractor())
        registerExtractorAPI(TmByseVepoinExtractor())
        registerExtractorAPI(TmByseDikamoumExtractor())
        registerExtractorAPI(TmByseKozeExtractor())

        openSettings = { ctx ->
            val activity = ctx as? Activity
            if (activity != null && !activity.isFinishing) {
                showMainSettingsDialog(ctx)
            } else {
                showMainSettingsDialog(ctx)
            }
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            ctx.resources.displayMetrics,
        ).toInt()

    private fun roundedBg(fillColor: Int, strokeColor: Int? = null, radiusDp: Int = 14, ctx: Context): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke(dp(ctx, 1), strokeColor)
            }
        }
    }

    private fun makeActionButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        textColor: Int = Color.WHITE,
        onClick: () -> Unit,
    ): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(textColor)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg(bgColor, null, 12, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(ctx, 12)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showMainSettingsDialog(ctx: Context) {
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#121318"))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 24))
        }

        val headerTitle = TextView(ctx).apply {
            text = "🎬 إعدادات TimeMovies"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(headerTitle)

        val subHeader = TextView(ctx).apply {
            text = "تفضيلات العرض والمشاهدة والتحميل المباشر"
            setTextColor(Color.parseColor("#9AA0B4"))
            textSize = 12.5f
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 14))
        }
        root.addView(subHeader)

        fun makeSwitchRow(titleText: String, initialChecked: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.parseColor("#1B1D26"), Color.parseColor("#2D3244"), 12, ctx)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(ctx, 10)
                }
                val lbl = TextView(ctx).apply {
                    text = titleText
                    setTextColor(Color.WHITE)
                    textSize = 13.5f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val sw = Switch(ctx).apply {
                    isChecked = initialChecked
                    setOnCheckedChangeListener { _, checked -> onToggle(checked) }
                }
                addView(lbl)
                addView(sw)
            }
        }

        root.addView(
            makeSwitchRow(
                titleText = "تفضيل العناوين والملصقات العربية عند توفرها",
                initialChecked = TimeMoviesPrefs.preferArabic,
            ) { checked ->
                TimeMoviesPrefs.preferArabic = checked
            }
        )

        root.addView(
            makeSwitchRow(
                titleText = "إظهار جميع سيرفرات المشاهدة والتحميل المتعددة (FHD / HD / SD)",
                initialChecked = TimeMoviesPrefs.alwaysIncludeUnlimitedServers,
            ) { checked ->
                TimeMoviesPrefs.alwaysIncludeUnlimitedServers = checked
            }
        )

        lateinit var dialog: AlertDialog

        root.addView(
            makeActionButton(
                ctx = ctx,
                label = "🗑️ مسح الذاكرة المؤقتة للروابط",
                bgColor = Color.parseColor("#1E293B"),
            ) {
                TimeMoviesGrantCache.clearAll()
                toast(ctx, "✅ تم مسح الذاكرة المؤقتة للروابط بنجاح")
                dialog.dismiss()
            }
        )

        scroll.addView(root)
        dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        @Volatile
        private var appContext: Context? = null

        fun notifyUserAr(message: String) {
            runCatching {
                val ctx = appContext ?: return
                val mainLooper = Looper.getMainLooper() ?: return
                Handler(mainLooper).post {
                    runCatching {
                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
