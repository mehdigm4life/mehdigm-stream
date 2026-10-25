package com.timemovies

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

@CloudstreamPlugin
class TimeMoviesPlugin : Plugin() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun load(context: Context) {
        appContextRef = WeakReference(context.applicationContext ?: context)
        TimeMoviesPrefs.init(context)

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
                topMargin = dp(ctx, 10)
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
            text = "🎬 إعدادات TimeMovies (v4.7)"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(headerTitle)

        val subHeader = TextView(ctx).apply {
            text = "إدارة الحساب، الملف الشخصي، حماية الجهاز الافتراضية، ومشغل DoomPlayer المدمج"
            setTextColor(Color.parseColor("#9AA0B4"))
            textSize = 12.5f
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 14))
        }
        root.addView(subHeader)

        val statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                fillColor = Color.parseColor("#1B1D26"),
                strokeColor = Color.parseColor("#2D3244"),
                radiusDp = 14,
                ctx = ctx,
            )
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }

        val statusBadge = TextView(ctx).apply {
            text = if (TimeMoviesPrefs.isSignedIn) "✅ متصل بحساب TimeMovies" else "⚠️ لم يتم تسجيل الدخول بعد"
            setTextColor(
                if (TimeMoviesPrefs.isSignedIn) Color.parseColor("#4ADE80")
                else Color.parseColor("#FBBF24")
            )
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusCard.addView(statusBadge)

        val statusSummary = TextView(ctx).apply {
            text = TimeMoviesPrefs.getAccountSummaryAr()
            setTextColor(Color.parseColor("#D4D8E8"))
            textSize = 13f
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        statusCard.addView(statusSummary)

        if (!TimeMoviesPrefs.isSignedIn) {
            val hintText = TextView(ctx).apply {
                text = "يتطلب خادم TimeMovies الرسمي (v4.7) حساباً مجانياً أو VIP لتصفح المكتبة وتشغيل الروابط. تتضمن الإضافة نظام جهاز افتراضي معزول لتجاوز حظر الأجهزة تلقائياً."
                setTextColor(Color.parseColor("#9AA0B4"))
                textSize = 12f
                setPadding(0, dp(ctx, 8), 0, 0)
            }
            statusCard.addView(hintText)
        }
        root.addView(statusCard)

        val langRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Color.parseColor("#1B1D26"), Color.parseColor("#2D3244"), 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(ctx, 14)
            }
        }

        val langLabel = TextView(ctx).apply {
            text = "تفضيل العناوين والملصقات العربية عند توفرها"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val langSwitch = Switch(ctx).apply {
            isChecked = TimeMoviesPrefs.preferArabic
            setOnCheckedChangeListener { _, checked ->
                TimeMoviesPrefs.preferArabic = checked
            }
        }
        langRow.addView(langLabel)
        langRow.addView(langSwitch)
        root.addView(langRow)

        lateinit var dialog: AlertDialog

        // Pending OTP Resume Button (shown whenever an unactivated registration token is saved)
        if (!TimeMoviesPrefs.pendingOtpToken.isNullOrBlank()) {
            val pendingEmail = TimeMoviesPrefs.pendingOtpEmail ?: "بريدك الإلكتروني"
            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "🔑 إدخال رمز التفعيل (OTP) لـ $pendingEmail",
                    bgColor = Color.parseColor("#D97706"),
                ) {
                    dialog.dismiss()
                    showOtpActivationDialog(ctx, pendingEmail)
                }
            )
        }

        if (!TimeMoviesPrefs.isSignedIn) {
            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "🔐 تسجيل الدخول بالبريد الإلكتروني",
                    bgColor = Color.parseColor("#2563EB"),
                ) {
                    dialog.dismiss()
                    showSignInDialog(ctx)
                }
            )

            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "✨ إنشاء حساب مجاني جديد (مع تفعيل OTP)",
                    bgColor = Color.parseColor("#059669"),
                ) {
                    dialog.dismiss()
                    showSignUpDialog(ctx)
                }
            )

            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "🛡️ توليد هوية جهاز افتراضية جديدة (تجاوز الحظر)",
                    bgColor = Color.parseColor("#4F46E5"),
                ) {
                    val fresh = TimeMoviesPrefs.rotateVirtualDeviceIdentity()
                    toast(ctx, "تم توليد هوية جهاز جديدة: ${fresh.displayLabel}")
                    dialog.dismiss()
                    showMainSettingsDialog(ctx)
                }
            )

            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "📋 لصق توكن JWT يدوياً (متقدم)",
                    bgColor = Color.parseColor("#374151"),
                ) {
                    dialog.dismiss()
                    showManualTokenDialog(ctx)
                }
            )
        } else {
            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "👤 اختيار / تحديث الملف الشخصي",
                    bgColor = Color.parseColor("#2563EB"),
                ) {
                    dialog.dismiss()
                    showProfilePickerDialog(ctx)
                }
            )

            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "🔄 مزامنة الجلسة ومفتاح التشفير (ECDSA P-256)",
                    bgColor = Color.parseColor("#374151"),
                ) {
                    toast(ctx, "جاري تحديث الجلسة ومزامنة توقيت الخادم...")
                    ioExecutor.execute {
                        TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
                        TimeMoviesApi.syncServerClockIfNeeded(force = true)
                        val tk = TimeMoviesPrefs.authToken.orEmpty()
                        TimeMoviesApi.ensureDeviceKeyEnrolled(tk)
                        val prof = TimeMoviesApi.syncAppDataAndSelectMainProfile()
                        runOnUi(ctx) {
                            toast(
                                ctx,
                                if (prof != null) "تمت المزامنة بنجاح (${prof.name})"
                                else "تم تحديث مفتاح الجهاز وتوقيت الخادم",
                            )
                            dialog.dismiss()
                            showMainSettingsDialog(ctx)
                        }
                    }
                }
            )

            root.addView(
                makeActionButton(
                    ctx = ctx,
                    label = "🚪 تسجيل الخروج",
                    bgColor = Color.parseColor("#DC2626"),
                ) {
                    TimeMoviesPrefs.signOut()
                    toast(ctx, "تم تسجيل الخروج من TimeMovies")
                    dialog.dismiss()
                }
            )
        }

        scroll.addView(root)
        dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()
    }

    private fun showSignInDialog(ctx: Context) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }

        val emailInput = EditText(ctx).apply {
            hint = "البريد الإلكتروني (Gmail / Outlook...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(TimeMoviesPrefs.userEmail.orEmpty())
        }
        val passInput = EditText(ctx).apply {
            hint = "كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(emailInput)
        container.addView(passInput)

        AlertDialog.Builder(ctx)
            .setTitle("تسجيل الدخول — TimeMovies")
            .setView(container)
            .setPositiveButton("دخول") { _, _ ->
                val email = emailInput.text?.toString()?.trim().orEmpty()
                val pass = passInput.text?.toString().orEmpty()
                if (email.isBlank() || pass.isBlank()) {
                    toast(ctx, "يرجى إدخال البريد الإلكتروني وكلمة المرور")
                    return@setPositiveButton
                }
                toast(ctx, "جاري تسجيل الدخول...")
                ioExecutor.execute {
                    val result = TimeMoviesApi.signInWithEmail(email, pass)
                    runOnUi(ctx) {
                        if (result.success) {
                            toast(ctx, "✅ تم تسجيل الدخول بنجاح! أعد تحديث الصفحة الرئيسية")
                        } else if (!TimeMoviesPrefs.pendingOtpToken.isNullOrBlank()) {
                            toast(ctx, result.message ?: "يرجى إدخال رمز التفعيل المرسل لبريدك")
                            showOtpActivationDialog(ctx, email)
                        } else {
                            toast(ctx, "❌ ${result.message ?: "فشل تسجيل الدخول"}")
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSignUpDialog(ctx: Context) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }

        val nameInput = EditText(ctx).apply {
            hint = "الاسم (مثال: Mehdi)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        val emailInput = EditText(ctx).apply {
            hint = "بريد إلكتروني حقيقي لاستلام رمز التفعيل (Gmail...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passInput = EditText(ctx).apply {
            hint = "كلمة المرور (8 أحرف أو أكثر)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(nameInput)
        container.addView(emailInput)
        container.addView(passInput)

        AlertDialog.Builder(ctx)
            .setTitle("إنشاء حساب مجاني — TimeMovies")
            .setMessage("سيتم توليد هوية جهاز افتراضية جديدة تلقائياً وإرسال رمز تفعيل (OTP) إلى بريدك الإلكتروني:")
            .setView(container)
            .setPositiveButton("إرسال الرمز") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty().ifEmpty { "Mehdi" }
                val email = emailInput.text?.toString()?.trim().orEmpty()
                val pass = passInput.text?.toString().orEmpty()
                if (email.isBlank() || pass.length < 6) {
                    toast(ctx, "يرجى إدخال بريد إلكتروني صالح وكلمة مرور لا تقل عن 6 أحرف")
                    return@setPositiveButton
                }
                toast(ctx, "جاري إنشاء الحساب عبر جهاز افتراضي نظيف...")
                ioExecutor.execute {
                    val res = TimeMoviesApi.signUpWithEmail(name, email, pass)
                    runOnUi(ctx) {
                        if (res.success && TimeMoviesPrefs.isSignedIn) {
                            toast(ctx, "🎉 تم إنشاء وتفعيل الحساب بنجاح!")
                        } else if (res.success) {
                            showOtpActivationDialog(ctx, email)
                        } else {
                            toast(ctx, "❌ ${res.message ?: "تعذر إنشاء الحساب"}")
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showOtpActivationDialog(ctx: Context, email: String) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }

        val otpInput = EditText(ctx).apply {
            hint = "رمز التفعيل (OTP)"
            inputType = InputType.TYPE_CLASS_TEXT
            gravity = Gravity.CENTER
        }
        container.addView(otpInput)

        AlertDialog.Builder(ctx)
            .setTitle("تفعيل البريد الإلكتروني")
            .setMessage("تم إرسال رمز التفعيل إلى:\n$email\nأدخل الرمز لإتمام تفعيل الحساب (وإذا غادرت لبريدك يمكنك دائماً متابعة التفعيل من إعدادات الإضافة):")
            .setView(container)
            .setPositiveButton("تفعيل") { _, _ ->
                val code = otpInput.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) {
                    toast(ctx, "يرجى إدخال رمز التفعيل")
                    showOtpActivationDialog(ctx, email)
                    return@setPositiveButton
                }
                toast(ctx, "جاري التحقق من الرمز وتفعيل الحساب...")
                ioExecutor.execute {
                    val res = TimeMoviesApi.activateEmailOtp(code)
                    runOnUi(ctx) {
                        if (res.success) {
                            toast(ctx, "🎉 تم تفعيل الحساب وتسجيل الدخول بنجاح!")
                        } else {
                            toast(ctx, "❌ ${res.message ?: "رمز التفعيل غير صحيح"}")
                            showOtpActivationDialog(ctx, email)
                        }
                    }
                }
            }
            .setNeutralButton("إعادة إرسال الرمز") { _, _ ->
                toast(ctx, "جاري طلب إعادة إرسال الرمز...")
                ioExecutor.execute {
                    val res = TimeMoviesApi.resendActivationOtp(email)
                    runOnUi(ctx) {
                        toast(
                            ctx,
                            if (res.success) "📨 ${res.message ?: "تم إرسال الرمز مجدداً"}"
                            else "❌ ${res.message ?: "تعذر إعادة الإرسال حالياً"}",
                        )
                        showOtpActivationDialog(ctx, email)
                    }
                }
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private fun showManualTokenDialog(ctx: Context) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 4))
        }
        val tokenInput = EditText(ctx).apply {
            hint = "eyJhbGciOi..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        container.addView(tokenInput)

        AlertDialog.Builder(ctx)
            .setTitle("لصق توكن JWT — TimeMovies")
            .setView(container)
            .setPositiveButton("حفظ وتفعيل") { _, _ ->
                val raw = tokenInput.text?.toString()?.trim().orEmpty()
                if (raw.isBlank()) return@setPositiveButton
                toast(ctx, "جاري التحقق وربط مفتاح الجهاز...")
                ioExecutor.execute {
                    val (ok, msg) = TimeMoviesApi.applyManualToken(raw)
                    runOnUi(ctx) {
                        toast(ctx, if (ok) "✅ $msg" else "❌ $msg")
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showProfilePickerDialog(ctx: Context) {
        toast(ctx, "جاري جلب الملفات الشخصية...")
        ioExecutor.execute {
            val profiles = TimeMoviesApi.fetchUserProfiles()
            runOnUi(ctx) {
                if (profiles.isEmpty()) {
                    toast(ctx, "تعذر العثور على ملفات شخصية")
                    return@runOnUi
                }
                val labels = profiles.map { p ->
                    val flags = buildList {
                        if (p.isMain) add("رئيسي")
                        if (p.isPinSet) add("🔒 رمز PIN")
                        if (p.isVipLocked) add("⭐ VIP")
                    }.joinToString(" • ")
                    if (flags.isNotEmpty()) "${p.name} ($flags)" else p.name
                }.toTypedArray()

                AlertDialog.Builder(ctx)
                    .setTitle("اختر الملف الشخصي")
                    .setItems(labels) { _, which ->
                        val selected = profiles[which]
                        TimeMoviesApi.saveActiveProfile(selected)
                        toast(ctx, "✅ تم اختيار الملف: ${selected.name}")
                    }
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }
    }

    private fun runOnUi(ctx: Context, block: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val act = ctx as? Activity
            if (act == null || !act.isFinishing) {
                block()
            }
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        @Volatile
        private var appContextRef: WeakReference<Context>? = null

        fun notifyUserAr(message: String) {
            runCatching {
                val ctx = appContextRef?.get() ?: return
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
