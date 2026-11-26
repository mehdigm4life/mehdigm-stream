import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask

// Keep this bump above the published v38 so CloudStream can detect the restored build.
version = 39

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
