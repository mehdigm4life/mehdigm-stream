import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency

version = 35

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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
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
}
