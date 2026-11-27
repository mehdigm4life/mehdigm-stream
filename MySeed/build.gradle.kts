version = 1

cloudstream {
    description = "MySeed — فهرس للبحث وتصفح بيانات الأفلام والمسلسلات والحلقات فقط."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://m.myseed.tv/wp-content/uploads/2022/09/logo333.webp"
}

android {
    namespace = "com.myseed"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val testImplementation by configurations
    testImplementation("junit:junit:4.13.2")
}
