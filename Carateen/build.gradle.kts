dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Anime, series and movies from carateen.tv — large Arabic anime library"
    authors = listOf("Mehdi Marsaman")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("TvSeries", "Anime", "Movie")

    requiresResources = true
    language = "ar"

    iconUrl = "https://carateen.tv/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
