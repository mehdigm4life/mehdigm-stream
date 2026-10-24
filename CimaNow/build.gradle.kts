version = 1

cloudstream {
    description = "سيما ناو — مشاهدة وتحميل أحدث الأفلام والمسلسلات العربية والأجنبية والتركية والآسيوية والأنمي والبرامج بجودات متعددة."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama",
        "Cartoon"
    )

    iconUrl = "https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/CimaNow.png"
}

android {
    namespace = "com.cimanow"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val testImplementation by configurations
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.lagradost:cloudstream3:pre-release")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}
