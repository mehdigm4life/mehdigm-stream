version = 2

cloudstream {
    description = "MySeed — بحث وتصفح ومشاهدة وتحميل الأفلام والمسلسلات والحلقات."
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
