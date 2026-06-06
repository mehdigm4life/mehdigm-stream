rootProject.name = "MehdigmStream"

// يُدرج تلقائياً كل المجلدات التي بداخلها build.gradle.kts كوحدات Gradle.
// لإستثناء وحدة معينة أضِف اسمها إلى قائمة disabled.
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// لإنشاء/بناء إضافة واحدة فقط، علّق الأسطر السابقة وأبقِ rootProject.name، ثم استخدم:
// include("Animezid")
