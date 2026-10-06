version = 4

cloudstream {
    description = "سينمانا — لا يعمل خارج العراق أو على أي شبكة غير Earthlink."
    authors = listOf("mehdigm4life")
    language = "ar"

    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTZlAGH56cEnNEL93W3QqZWpUe8XR8i90olTA&s"
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
