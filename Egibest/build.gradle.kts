dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.mozilla:rhino:1.7.13")
}

version = 4

cloudstream {
    description = "Movies, series and anime from egibest.org"
    authors = listOf("Mehdi Marsaman")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    requiresResources = true
    iconUrl = "https://egibest.org/wp-content/uploads/2026/02/egybest_logo2-2.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
