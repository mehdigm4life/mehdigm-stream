package com.timemovies

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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.timemovies.TimeMoviesModels.optIntOrNull
import com.timemovies.TimeMoviesModels.optNonBlank
import java.util.concurrent.Executors

@CloudstreamPlugin
class TimeMoviesPlugin : Plugin() {

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun load(context: Context) {
        TimeMoviesPrefs.init(context)

        registerMainAPI(TimeMovies())
        registerExtractorAPI(DoomPlayerPlayExtractor())
        registerExtractorAPI(DoomPlayerDownloadExtractor())

        openSettings = { ctx ->
            showMainSettingsDialog(ctx)
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            ctx.resources.displayMetrics,
        ).toInt()

    private fun roundedBg(colorHex: String, radiusDp: Int, ctx: Context, strokeHex: String? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(ctx, 1), Color.parseColor(strokeHex))
            }
        }

    private fun buildStatusSummary(): String {
        if (!TimeMoviesPrefs.isSignedIn) {
            return if (TimeMoviesPrefs.pendingOtpToken.isNotBlank()) {
                "الحالة: بانتظار إدخال رمز التفعيل (OTP) المرسل إلى ${TimeMoviesPrefs.savedEmail}"
            } else {
                "الحالة: غير مسجل الدخول\nسجّل دخولك بحساب TimeMovies أو أنشئ حساباً مجانياً بالأسفل"
            }
        }
        val claims = TimeMoviesPrefs.decodeJwtClaims()
        val email = claims?.optNonBlank("e")
            ?: claims?.optNonBlank("email")
            ?: TimeMoviesPrefs.savedEmail.ifBlank { "مفعّل" }
        val isVip = claims?.optBoolean("iv", false) == true || claims?.optBoolean("vip", false) == true
        val fq = claims?.optIntOrNull("fq") ?: 0
        val dcr = claims?.optIntOrNull("dcr") ?: 0
        val profile = TimeMoviesPrefs.profileName ?: "تلقائي"
        val dkBadge = if (!TimeMoviesPrefs.deviceKeyId.isNullOrBlank()) "مربوط (ECDSA P-256 ✓)" else "سيتم الربط تلقائياً"
        val planBadge = if (isVip) "VIP غير محدود" else "مجاني (الرصيد: ${fq + dcr})"
        return "الحساب: $email\nالملف الشخصي: $profile • الخطة: $planBadge\nمفتاح الجهاز: $dkBadge"
    }

    private fun styledInput(
        context: Context,
        hintText: String,
        initialValue: String = "",
        isPassword: Boolean = false,
    ): EditText = EditText(context).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#6E768A"))
        setTextColor(Color.WHITE)
        textSize = 13.5f
        setSingleLine(true)
        if (isPassword) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        background = roundedBg("#1E202E", 8, context, "#2E3348")
        val pad = dp(context, 12)
        setPadding(pad, pad, pad, pad)
        if (initialValue.isNotEmpty()) setText(initialValue)
    }

    private fun styledActionButton(
        context: Context,
        label: String,
        bgHex: String = "#E85D75",
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13.5f
        setTypeface(typeface, Typeface.BOLD)
        background = roundedBg(bgHex, 8, context)
        val padV = dp(context, 10)
        setPadding(dp(context, 14), padV, dp(context, 14), padV)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(context, 8)
        }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun sectionHeader(context: Context, textValue: String): TextView = TextView(context).apply {
        text = textValue
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#FFB48A"))
        setPadding(0, dp(context, 14), 0, dp(context, 6))
    }

    private fun spacer(context: Context, heightDp: Int = 6): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp))
    }

    private fun showMainSettingsDialog(context: Context) {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#12131C"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 20)
            setPadding(p, p, p, p)
        }

        val title = TextView(context).apply {
            text = "إعدادات تايم موفيز (TimeMovies)"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 8))
        }
        root.addView(title)

        val statusBox = TextView(context).apply {
            text = buildStatusSummary()
            textSize = 12.5f
            setTextColor(Color.parseColor("#D5DCEB"))
            background = roundedBg("#1B1D2B", 10, context, "#2D324A")
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(statusBox)

        // 1. Email + Password Login
        root.addView(sectionHeader(context, "تسجيل الدخول بالبريد وكلمة المرور"))
        val emailInput = styledInput(context, "البريد الإلكتروني (يفضل Gmail)", TimeMoviesPrefs.savedEmail)
        root.addView(emailInput)
        root.addView(spacer(context, 6))
        val passInput = styledInput(context, "كلمة المرور", TimeMoviesPrefs.savedPassword, isPassword = true)
        root.addView(passInput)

        var dialogRef: AlertDialog? = null

        root.addView(
            styledActionButton(context, "تسجيل الدخول الآن", "#E05A7A") {
                val em = emailInput.text?.toString()?.trim().orEmpty()
                val pw = passInput.text?.toString().orEmpty()
                if (em.isBlank() || pw.isBlank()) {
                    Toast.makeText(context, "يرجى إدخال البريد الإلكتروني وكلمة المرور", Toast.LENGTH_SHORT).show()
                    return@styledActionButton
                }
                Toast.makeText(context, "جارٍ تسجيل الدخول وربط مفتاح الجهاز...", Toast.LENGTH_SHORT).show()
                bgExecutor.execute {
                    val res = runCatching { TimeMoviesApi.signInWithEmail(em, pw) }
                        .getOrElse { e -> TimeMoviesModels.ApiEnvelope(false, 0, e.message, null) }
                    mainHandler.post {
                        if (res.success && !res.result.isNullOrBlank()) {
                            statusBox.text = buildStatusSummary()
                            Toast.makeText(context, "تم تسجيل الدخول بنجاح ✓", Toast.LENGTH_LONG).show()
                        } else {
                            val msg = res.message ?: "فشل تسجيل الدخول (${res.status})"
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )

        // 2. Create Free Account + OTP Activation
        root.addView(
            styledActionButton(context, "إنشاء حساب مجاني جديد / تفعيل OTP", "#3B66D4") {
                dialogRef?.dismiss()
                showSignUpAndOtpDialog(context)
            }
        )

        // 2b. Live Connection & Session Health Check
        root.addView(
            styledActionButton(context, "فحص الاتصال بسيرفر TimeMovies", "#4A526E") {
                Toast.makeText(context, "جارٍ فحص الاتصال بسيرفر TimeMovies...", Toast.LENGTH_SHORT).show()
                bgExecutor.execute {
                    val astOk = runCatching { TimeMoviesApi.getAppSettings() != null }.getOrDefault(false)
                    val dkOk = if (TimeMoviesPrefs.isSignedIn) {
                        runCatching { TimeMoviesApi.ensureDeviceKeyEnrolled() }.getOrDefault(false)
                    } else false
                    mainHandler.post {
                        statusBox.text = buildStatusSummary()
                        val msg = when {
                            !astOk -> "تعذر الوصول إلى سيرفر TimeMovies (تحقق من اتصالك بالإنترنت)"
                            TimeMoviesPrefs.isSignedIn && dkOk -> "الاتصال ممتاز ✓ ومفتاح التوقيع الرقمي (ECDSA P-256) فعّال"
                            TimeMoviesPrefs.isSignedIn -> "السيرفر متصل ✓ (تحقق من صلاحية الجلسة)"
                            else -> "السيرفر متصل ويعمل بنجاح ✓ — سجّل دخولك للبدء بالمشاهدة"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )

        // 3. Profile Switcher (when signed in)
        if (TimeMoviesPrefs.isSignedIn) {
            root.addView(
                styledActionButton(context, "تبديل الملف الشخصي (Profile)", "#2E8B78") {
                    Toast.makeText(context, "جارٍ جلب الملفات الشخصية...", Toast.LENGTH_SHORT).show()
                    bgExecutor.execute {
                        val profiles = runCatching { TimeMoviesApi.fetchProfiles() }.getOrDefault(emptyList())
                        mainHandler.post {
                            if (profiles.isEmpty()) {
                                Toast.makeText(context, "لم يتم العثور على ملفات شخصية إضافية", Toast.LENGTH_SHORT).show()
                            } else {
                                showProfilePickerDialog(context, profiles) {
                                    statusBox.text = buildStatusSummary()
                                }
                            }
                        }
                    }
                }
            )
        }

        // 4. Preferences Switches
        root.addView(sectionHeader(context, "تفضيلات العرض والسيرفرات"))

        val arabicSwitch = Switch(context).apply {
            text = "تفضيل العناوين والوصف باللغة العربية"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            isChecked = TimeMoviesPrefs.preferArabic
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        root.addView(arabicSwitch)

        val downloadSwitch = Switch(context).apply {
            text = "جلب روابط التحميل المباشرة (MP4 متعددة الجودات) بجانب البث"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            isChecked = TimeMoviesPrefs.fetchDownloadGrant
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        root.addView(downloadSwitch)

        // 5. Advanced: Manual JWT Token (`x-t`)
        root.addView(sectionHeader(context, "متقدم: إدخال توكن الجلسة (JWT) يدوياً"))
        val tokenInput = styledInput(
            context = context,
            hintText = "اختياري: الصق التوكن x-t هنا إذا سجلت عبر Google في التطبيق",
            initialValue = "",
        )
        root.addView(tokenInput)

        if (TimeMoviesPrefs.isSignedIn) {
            root.addView(
                styledActionButton(context, "تسجيل الخروج ومسح الجلسة", "#7A2E3A") {
                    TimeMoviesPrefs.signOut()
                    statusBox.text = buildStatusSummary()
                    Toast.makeText(context, "تم تسجيل الخروج بنجاح", Toast.LENGTH_SHORT).show()
                }
            )
        }

        scroll.addView(root)

        dialogRef = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setPositiveButton("حفظ التفضيلات") { _, _ ->
                TimeMoviesPrefs.preferArabic = arabicSwitch.isChecked
                TimeMoviesPrefs.fetchDownloadGrant = downloadSwitch.isChecked
                val manualTk = tokenInput.text?.toString()?.trim().orEmpty()
                if (manualTk.isNotEmpty()) {
                    bgExecutor.execute {
                        val (_, msg) = TimeMoviesApi.applyManualToken(manualTk)
                        mainHandler.post {
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(context, "تم حفظ إعدادات TimeMovies", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .create()

        dialogRef.show()
    }

    private fun showSignUpAndOtpDialog(context: Context) {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#12131C"))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(context, 20)
            setPadding(p, p, p, p)
        }

        val header = TextView(context).apply {
            text = "إنشاء حساب TimeMovies وتفعيل OTP"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 10))
        }
        root.addView(header)

        val info = TextView(context).apply {
            text = "1) أدخل اسمك وبريد Gmail حقيقي وكلمة مرور (8 أحرف فأكثر) ثم اضغط «إرسال رمز التفعيل».\n2) سيصلك رمز من 6 أرقام على بريدك، أدخله بالأسفل واضغط «تفعيل الحساب»."
            textSize = 12f
            setTextColor(Color.parseColor("#B8C2D8"))
            setPadding(0, 0, 0, dp(context, 10))
        }
        root.addView(info)

        val nameInput = styledInput(context, "الاسم (مثال: Ahmed)")
        root.addView(nameInput)
        root.addView(spacer(context, 6))
        val emailInput = styledInput(context, "البريد الإلكتروني (Gmail حقيقي)", TimeMoviesPrefs.savedEmail)
        root.addView(emailInput)
        root.addView(spacer(context, 6))
        val passInput = styledInput(context, "كلمة المرور", TimeMoviesPrefs.savedPassword, isPassword = true)
        root.addView(passInput)

        root.addView(
            styledActionButton(context, "1. إنشاء الحساب وإرسال رمز OTP", "#3B66D4") {
                val nm = nameInput.text?.toString()?.trim().orEmpty()
                val em = emailInput.text?.toString()?.trim().orEmpty()
                val pw = passInput.text?.toString().orEmpty()
                if (em.isBlank() || pw.length < 6) {
                    Toast.makeText(context, "يرجى إدخال بريد صالح وكلمة مرور (6 أحرف على الأقل)", Toast.LENGTH_SHORT).show()
                    return@styledActionButton
                }
                Toast.makeText(context, "جارٍ إنشاء الحساب وإرسال رمز التفعيل...", Toast.LENGTH_SHORT).show()
                bgExecutor.execute {
                    val res = runCatching { TimeMoviesApi.signUpWithEmail(nm, em, pw) }
                        .getOrElse { e -> TimeMoviesModels.ApiEnvelope(false, 0, e.message, null) }
                    mainHandler.post {
                        if (res.success) {
                            if (TimeMoviesPrefs.isSignedIn) {
                                Toast.makeText(context, "تم إنشاء وتفعيل الحساب بنجاح ✓", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "تم إرسال رمز التفعيل من 6 أرقام إلى بريدك الإلكتروني!", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val refusalHint = if (res.emailRefusal != null) {
                                " (تأكد من أن البريد Gmail حقيقي وغير مؤقت)"
                            } else ""
                            Toast.makeText(context, "${res.message ?: "تعذر إنشاء الحساب"}$refusalHint", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )

        root.addView(sectionHeader(context, "إدخال رمز التفعيل (6 أرقام)"))
        val otpInput = styledInput(context, "رمز التفعيل OTP (مثال: 482910)")
        root.addView(otpInput)

        var otpDialog: AlertDialog? = null

        root.addView(
            styledActionButton(context, "2. تفعيل الحساب الآن", "#2E8B78") {
                val code = otpInput.text?.toString()?.trim().orEmpty()
                if (code.length < 4) {
                    Toast.makeText(context, "يرجى إدخال رمز التفعيل المرسل إلى بريدك", Toast.LENGTH_SHORT).show()
                    return@styledActionButton
                }
                Toast.makeText(context, "جارٍ التحقق من رمز التفعيل...", Toast.LENGTH_SHORT).show()
                bgExecutor.execute {
                    val res = runCatching { TimeMoviesApi.activateEmailOtp(code) }
                        .getOrElse { e -> TimeMoviesModels.ApiEnvelope(false, 0, e.message, null) }
                    mainHandler.post {
                        if (res.success && TimeMoviesPrefs.isSignedIn) {
                            Toast.makeText(context, "تم تفعيل الحساب وتسجيل الدخول بنجاح ✓", Toast.LENGTH_LONG).show()
                            otpDialog?.dismiss()
                            showMainSettingsDialog(context)
                        } else {
                            Toast.makeText(context, res.message ?: "رمز التفعيل غير صحيح", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )

        scroll.addView(root)
        otpDialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setNegativeButton("رجوع") { _, _ ->
                showMainSettingsDialog(context)
            }
            .create()
        otpDialog.show()
    }

    private fun showProfilePickerDialog(
        context: Context,
        profiles: List<TimeMoviesModels.UserProfile>,
        onPicked: () -> Unit,
    ) {
        val labels = profiles.map { p ->
            val badges = buildList {
                if (p.isMain) add("رئيسي")
                if (p.isPinSet) add("محمي بـ PIN")
                if (p.isVipLocked) add("VIP")
                add("عمر +${p.maturity}")
            }.joinToString(" • ")
            "${p.name} ($badges)"
        }.toTypedArray()

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("اختر الملف الشخصي")
            .setItems(labels) { _, which ->
                val picked = profiles[which]
                TimeMoviesPrefs.profileId = picked.id
                TimeMoviesPrefs.profileName = picked.name
                TimeMoviesPrefs.profileMaturity = picked.maturity
                Toast.makeText(context, "تم التبديل إلى: ${picked.name}", Toast.LENGTH_SHORT).show()
                onPicked()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
