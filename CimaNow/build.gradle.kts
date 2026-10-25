import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency

version = 4

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
    implementation("org.mozilla:rhino:1.8.1")
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
