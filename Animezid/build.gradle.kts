// ===== Animezid extension =====
// Cloudstream plugin for https://animezid.cam
// =================================================

cloudstream {
    language = "ar"

    // أنواع المحتوى المدعومة
    tvTypes = listOf("Anime", "AnimeMovie", "Cartoon", "TvSeries", "Movie", "OVA")

    // ميتاداتا تظهر داخل تطبيق Cloudstream
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
