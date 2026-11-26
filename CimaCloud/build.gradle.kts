version = 37

cloudstream {
    description = "CimaCloud — مباشر، مدبلج، مترجم"
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Anime",
        "TvSeries",
        "Movie",
        "Cartoon"
    )

    iconUrl = "https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/CimaCloud.png"
}

android {
    namespace = "com.cimacloud"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
