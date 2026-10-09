import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask

version = 16

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

    iconUrl = "https://cima-cloud.com/favicon.ico"
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
