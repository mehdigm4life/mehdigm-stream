import com.lagradost.cloudstream3.gradle.CloudstreamExtension

// تعريف الإضافات البرمجية للمجلد الفرعي
plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    // الـ namespace الفرعي الخاص بإضافة انمي زد
    namespace = "com.mehdigm.animezid"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }
}

// ===== Animezid extension =====
// Cloudstream plugin for https://animezid.cam
// =================================================

cloudstream {
    // تعيين الرابط تلقائياً أو استخدام الرابط المباشر
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/mehdigm4life/mehdigm-stream")
    
    language = "ar"

    // أنواع المحتوى المدعومة في الموقع
    tvTypes = listOf("Anime", "AnimeMovie", "Cartoon", "TvSeries", "Movie", "OVA")

    // المعلومات التي تظهر للمستخدمين داخل التطبيق
    description = "موقع انمي زد — مشاهدة وتحميل أفلام ومسلسلات الأنمي والكرتون المدبلج والمترجم."
    authors = listOf("mehdigm4life")

    /**
     * Status int:
     * 0 = down
     * 1 = working / ok
     * 2 = slow
     * 3 = beta only
     */
    status = 1

    iconUrl = "https://animezid.cam/uploads/custom-logo.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    
    // يمكنك إضافة مكتبات خاصة بـ Animezid هنا إذا تطلب الأمر مستقبلاً
}
