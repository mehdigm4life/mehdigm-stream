import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask

version = 32

cloudstream {
    description = "AnimeDay — أنمي وكرتون مدبلج ومترجم"
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "Anime",
        "TvSeries",
        "Movie",
        "Cartoon"
    )

    iconUrl = "https://github.com/mehdigm4life/mehdigm-stream/raw/refs/heads/main/build/AnimeDay.jpg"
}

android {
    namespace = "com.animeday"
}

tasks.matching { it.name == "make" }.configureEach {
    (this as org.gradle.api.tasks.bundling.Zip).from("src/main/jniLibs") {
        into("lib")
    }
}

tasks.withType<CompileDexTask>().configureEach {
    val javaCompile = project.tasks.named("compileDebugJavaWithJavac")
    dependsOn(javaCompile)
    input.from(javaCompile)
}
