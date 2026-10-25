import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency

version = 2

cloudstream {
    description = "تايم موفيز (TimeMovies) — التطبيق الرسمي: مشاهدة وتحميل أحدث الأفلام والمسلسلات والأنمي بجودات متعددة وترجمات مدمجة مع دعم مشغل Doom Video Player."
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

    iconUrl = "https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/TimeMovies.png"
}

android {
    namespace = "com.timemovies"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val testImplementation by configurations
    val compileOnly by configurations
    compileOnly.dependencies.withType<FileCollectionDependency>().all {
        testImplementation(files)
    }
    compileOnly.dependencies.withType<ExternalModuleDependency>().all {
        if (!(group == "com.lagradost" && name == "cloudstream3")) {
            testImplementation("$group:$name:${version.orEmpty()}")
        }
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation("dev.whyoleg.cryptography:cryptography-core:0.5.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.5.0")
}
