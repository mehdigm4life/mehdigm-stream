import org.gradle.api.artifacts.ExternalModuleDependency

version = 17

cloudstream {
    description = "إيجي ديد — أفلام ومسلسلات وكرتون وأنمي مترجم."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon",
        "AnimeMovie"
    )

    iconUrl = "https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/EgyDead.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jsoup:jsoup:1.18.3")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.json:json:20240303")
    testImplementation("dev.whyoleg.cryptography:cryptography-core:0.5.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.5.0")
}

afterEvaluate {
    val testImpl = configurations.findByName("testImplementation") ?: return@afterEvaluate
    configurations.findByName("compileOnly")?.dependencies
        ?.filterIsInstance<ExternalModuleDependency>()
        ?.filterNot { it.group == "com.lagradost" && it.name == "cloudstream3" }
        ?.forEach { dep ->
            testImpl.dependencies.add(dep.copy())
        }
}
