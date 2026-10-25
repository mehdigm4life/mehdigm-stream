import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

version = 3

val generatedPoolDir = layout.buildDirectory.dir("generated/source/timemoviesPool/kotlin")
val poolEncFile = layout.projectDirectory.file("pool.enc")

val generateEmbeddedPool = tasks.register("generateEmbeddedPool") {
    inputs.file(poolEncFile)
    outputs.dir(generatedPoolDir)

    doLast {
        val rawFile = poolEncFile.asFile
        val lines = if (rawFile.exists()) {
            rawFile.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else {
            emptyList()
        }

        val outFile = generatedPoolDir.get().asFile.resolve("com/timemovies/TimeMoviesEmbeddedPool.kt")
        outFile.parentFile.mkdirs()

        val listLiteral = if (lines.isEmpty()) {
            "emptyList()"
        } else {
            val escaped = lines.joinToString(",\n        ") { line ->
                "\"" + line.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
            }
            "listOf(\n        $escaped\n    )"
        }

        outFile.writeText(
            """
            package com.timemovies

            internal object TimeMoviesEmbeddedPool {
                val BUNDLES: List<String> = $listLiteral
            }
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    dependsOn(generateEmbeddedPool)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateEmbeddedPool)
}

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

    sourceSets.getByName("main").java.srcDir(generatedPoolDir)

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
