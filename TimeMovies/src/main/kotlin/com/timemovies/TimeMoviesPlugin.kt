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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
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

        // Configure interactive Settings BottomSheet in CloudStream plugin manager
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity ?: ctx as? Activity
            if (activity != null && !activity.isFinishing) {
                showSettingsBottomSheet(activity)
            } else {
                Toast.makeText(
                    ctx,
                    "إعدادات TimeMovies متاحة من داخل شاشة الإضافات",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun dp(ctx: Context, value: Int): Int {
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

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
            textSize = 15f
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

    private fun showSettingsBottomSheet(activity: Activity) {
        val dialog = BottomSheetDialog(activity)
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor("#121318"))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 28))
        }

        // Header Title
        val headerTitle = TextView(activity).apply {
            text = "🎬 إعدادات TimeMovies (v4.7)"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(headerTitle)

        val subHeader = TextView(activity).apply {
            text = "إدارة الحساب، الملف الشخصي، حماية الجهاز الافتراضية، ومشغل DoomPlayer المدمج"
            setTextColor(Color.parseColor("#9AA0B4"))
            textSize = 13f
            setPadding(0, dp(activity, 4), 0, dp(activity, 14))
        }
        root.addView(subHeader)

        // Account Status Card
        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                fillColor = Color.parseColor("#1B1D26"),
                strokeColor = Color.parseColor("#2D3244"),
                radiusDp = 14,
                ctx = activity,
            )
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
        }

        val statusBadge = TextView(activity).apply {
            text = if (TimeMoviesPrefs.isSignedIn) "✅ متصل بحساب TimeMovies" else "⚠️ لم يتم تسجيل الدخول بعد"
            setTextColor(
                if (TimeMoviesPrefs.isSignedIn) Color.parseColor("#4ADE80")
                else Color.parseColor("#FBBF24")
            )
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusCard.addView(statusBadge)

        val statusSummary = TextView(activity).apply {
            text = TimeMoviesPrefs.getAccountSummaryAr()
            setTextColor(Color.parseColor("#D4D8E8"))
            textSize = 13f
            setPadding(0, dp(activity, 6), 0, 0)
        }
        statusCard.addView(statusSummary)

        if (!TimeMoviesPrefs.isSignedIn) {
            val hintText = TextView(activity).apply {
                text = "يتطلب خادم TimeMovies الرسمي (v4.7) حساباً مجانياً أو VIP لتصفح المكتبة وتشغيل الروابط. تتضمن الإضافة نظام جهاز افتراضي معزول لتجاوز حظر الأجهزة تلقائياً."
                setTextColor(Color.parseColor("#9AA0B4"))
                textSize = 12f
                setPadding(0, dp(activity, 8), 0, 0)
            }
            statusCard.addView(hintText)
        }
        root.addView(statusCard)

        // Language Preference Toggle
        val langRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Color.parseColor("#1B1D26"), Color.parseColor("#2D3244"), 12, activity)
            setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(activity, 14)
            }
        }

        val langLabel = TextView(activity).apply {
            text = "تفضيل العناوين والملصقات العربية عند توفرها"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val langSwitch = Switch(activity).apply {
            isChecked = TimeMoviesPrefs.preferArabic
            setOnCheckedChangeListener { _, checked ->
                TimeMoviesPrefs.preferArabic = checked
            }
        }
        langRow.addView(langLabel)
        langRow.addView(langSwitch)
        root.addView(langRow)

        // Pending OTP Resume Button (shown whenever an unactivated registration token is saved)
        if (!TimeMoviesPrefs.pendingOtpToken.isNullOrBlank()) {
            val pendingEmail = TimeMoviesPrefs.pendingOtpEmail ?: "بريدك الإلكتروني"
            root.addView(
                makeActionButton(
                    ctx = activity,
                    label = "🔑 إدخال رمز التفعيل (OTP) لـ $pendingEmail",
                    bgColor = Color.parseColor("#D97706"),
                ) {
                    dialog.dismiss()
                    showOtpActivationDialog(activity, pendingEmail)
                }
            )
        }

        // Auth & Profile Buttons
        if (!TimeMoviesPrefs.isSignedIn) {
            root.addView(
                makeActionButton(
                    ctx = activity,
                    label = "🔐 تسجيل الدخول بالبريد الإلكتروني",
                    bgColor = Color.parseColor("#2563EB"),
                ) {
                    dialog.dismiss()
                    showSignInDialog(activity)
                }
            )

            root.addView(
                makeActionButton(
                    ctx = activity,
                    label = "✨ إنشاء حساب مجاني جديد (مع تفعيل OTP)",
                    bgColor = Color.parseColor("#059669"),
                ) {
                    dialog.dismiss()
                    showSignUpDialog(activity)
                }
            )

            root.addView(
                makeActionButton(
                    ctx = activity,
                    label = "🛡️ توليد هوية جهاز افتراضية جديدة (تجاوز الحظر)",
                    bgColor = Color.parseColor("#4F46E5"),
                ) {
                    val fresh = TimeMoviesPrefs.rotateVirtualDeviceIdentity()
                    toast(activity, "تم توليد هوية جهاز جديدة: ${fresh.displayLabel}")
                    dialog.dismiss()
                    showSettingsBottomSheet(activity)
                }
            )
        } else {
            root.addView(
                makeActionButton(
                    ctx = activity,
                    label = "👤 اختيار / تحديث الملف الشخصي",
                    bgColor = Color.parseColor("#2563EB"),
                ) {
                    dialog.dismiss()
                    showProfilePickerDialog(activity)
                }
            )

            root.addView(
                makeActionButton(
                    ctx = activity,
                    label = "🔄 مزامنة الجلسة ومفتاح التشفير (ECDSA P-256)",
                    bgColor = Color.parseColor("#374151"),
                ) {
                    toast(activity, "جاري تحديث الجلسة ومزامنة توقيت الخادم...")
                    ioExecutor.execute {
                        TimeMoviesPrefs.clearDeviceKey(regenerateKeyPair = false)
                        TimeMoviesApi.syncServerClockIfNeeded(force = true)
                        val tk = TimeMoviesPrefs.authToken.orEmpty()
                        TimeMoviesApi.ensureDeviceKeyEnrolled(tk)
                        val prof = TimeMoviesApi.syncAppDataAndSelectMainProfile()
                        runOnUi(activity) {
                            toast(
                                activity,
                                if (prof != null) "تمت المزامنة بنجاح (${prof.name})"
                                else "تم تحديث مفتاح الجهاز وتوقيت الخادم",
                            )
                            dialog.dismiss()
                            showSettingsBottomSheet(activity)
                        }
                    }
                }
            )

            root.addView(
                makeActionButton(
                    ctx = activity,
                    label = "🚪 تسجيل الخروج",
                    bgColor = Color.parseColor("#DC2626"),
                ) {
                    TimeMoviesPrefs.signOut()
                    toast(activity, "تم تسجيل الخروج من TimeMovies")
                    dialog.dismiss()
                }
            )
        }

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun showSignInDialog(activity: Activity) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 4))
        }

        val emailInput = EditText(activity).apply {
            hint = "البريد الإلكتروني (Gmail / Outlook...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(TimeMoviesPrefs.userEmail.orEmpty())
        }
        val passInput = EditText(activity).apply {
            hint = "كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(emailInput)
        container.addView(passInput)

        AlertDialog.Builder(activity)
            .setTitle("تسجيل الدخول — TimeMovies")
            .setView(container)
            .setPositiveButton("دخول") { _, _ ->
                val email = emailInput.text?.toString()?.trim().orEmpty()
                val pass = passInput.text?.toString().orEmpty()
                if (email.isBlank() || pass.isBlank()) {
                    toast(activity, "يرجى إدخال البريد الإلكتروني وكلمة المرور")
                    return@setPositiveButton
                }
                toast(activity, "جاري تسجيل الدخول...")
                ioExecutor.execute {
                    val result = TimeMoviesApi.signInWithEmail(email, pass)
                    runOnUi(activity) {
                        if (result.success) {
                            toast(activity, "✅ تم تسجيل الدخول بنجاح! أعد تحديث الصفحة الرئيسية")
                        } else if (!TimeMoviesPrefs.pendingOtpToken.isNullOrBlank()) {
                            toast(activity, result.message ?: "يرجى إدخال رمز التفعيل المرسل لبريدك")
                            showOtpActivationDialog(activity, email)
                        } else {
                            toast(activity, "❌ ${result.message ?: "فشل تسجيل الدخول"}")
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSignUpDialog(activity: Activity) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 4))
        }

        val nameInput = EditText(activity).apply {
            hint = "الاسم (مثال: Mehdi)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        val emailInput = EditText(activity).apply {
            hint = "بريد إلكتروني حقيقي لاستلام رمز التفعيل (Gmail...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passInput = EditText(activity).apply {
            hint = "كلمة المرور (8 أحرف أو أكثر)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(nameInput)
        container.addView(emailInput)
        container.addView(passInput)

        AlertDialog.Builder(activity)
            .setTitle("إنشاء حساب مجاني — TimeMovies")
            .setMessage("سيتم توليد هوية جهاز افتراضية جديدة تلقائياً وإرسال رمز تفعيل (OTP) إلى بريدك الإلكتروني:")
            .setView(container)
            .setPositiveButton("إرسال الرمز") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty().ifEmpty { "Mehdi" }
                val email = emailInput.text?.toString()?.trim().orEmpty()
                val pass = passInput.text?.toString().orEmpty()
                if (email.isBlank() || pass.length < 6) {
                    toast(activity, "يرجى إدخال بريد إلكتروني صالح وكلمة مرور لا تقل عن 6 أحرف")
                    return@setPositiveButton
                }
                toast(activity, "جاري إنشاء الحساب عبر جهاز افتراضي نظيف...")
                ioExecutor.execute {
                    val res = TimeMoviesApi.signUpWithEmail(name, email, pass)
                    runOnUi(activity) {
                        if (res.success) {
                            showOtpActivationDialog(activity, email)
                        } else {
                            toast(activity, "❌ ${res.message ?: "تعذر إنشاء الحساب"}")
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showOtpActivationDialog(activity: Activity, email: String) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 4))
        }

        val otpInput = EditText(activity).apply {
            hint = "رمز التفعيل (OTP)"
            inputType = InputType.TYPE_CLASS_TEXT
            gravity = Gravity.CENTER
        }
        container.addView(otpInput)

        AlertDialog.Builder(activity)
            .setTitle("تفعيل البريد الإلكتروني")
            .setMessage("تم إرسال رمز التفعيل إلى:\n$email\nأدخل الرمز لإتمام تفعيل الحساب (وإذا غادرت لبريدك يمكنك دائماً متابعة التفعيل من إعدادات الإضافة):")
            .setView(container)
            .setPositiveButton("تفعيل") { _, _ ->
                val code = otpInput.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) {
                    toast(activity, "يرجى إدخال رمز التفعيل")
                    showOtpActivationDialog(activity, email)
                    return@setPositiveButton
                }
                toast(activity, "جاري التحقق من الرمز وتفعيل الحساب...")
                ioExecutor.execute {
                    val res = TimeMoviesApi.activateEmailOtp(code)
                    runOnUi(activity) {
                        if (res.success) {
                            toast(activity, "🎉 تم تفعيل الحساب وتسجيل الدخول بنجاح!")
                        } else {
                            toast(activity, "❌ ${res.message ?: "رمز التفعيل غير صحيح"}")
                            showOtpActivationDialog(activity, email)
                        }
                    }
                }
            }
            .setNeutralButton("إعادة إرسال الرمز") { _, _ ->
                toast(activity, "جاري طلب إعادة إرسال الرمز...")
                ioExecutor.execute {
                    val res = TimeMoviesApi.resendActivationOtp(email)
                    runOnUi(activity) {
                        toast(
                            activity,
                            if (res.success) "📨 ${res.message ?: "تم إرسال الرمز مجدداً"}"
                            else "❌ ${res.message ?: "تعذر إعادة الإرسال حالياً"}",
                        )
                        showOtpActivationDialog(activity, email)
                    }
                }
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private fun showProfilePickerDialog(activity: Activity) {
        toast(activity, "جاري جلب الملفات الشخصية...")
        ioExecutor.execute {
            val profiles = TimeMoviesApi.fetchUserProfiles()
            runOnUi(activity) {
                if (profiles.isEmpty()) {
                    toast(activity, "تعذر العثور على ملفات شخصية")
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

                AlertDialog.Builder(activity)
                    .setTitle("اختر الملف الشخصي")
                    .setItems(labels) { _, which ->
                        val selected = profiles[which]
                        TimeMoviesApi.saveActiveProfile(selected)
                        toast(activity, "✅ تم اختيار الملف: ${selected.name}")
                    }
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }
    }

    private fun runOnUi(activity: Activity, block: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            if (!activity.isFinishing) {
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
