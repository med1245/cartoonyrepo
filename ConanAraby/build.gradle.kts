dependencies {
    implementation("com.google.android.material:material:1.12.0")
}


version = 1

cloudstream {
    description = "كونان بالعربي - Detective Conan episodes and movies in Arabic from conanaraby.com"
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

    language = "ar"

    iconUrl = "https://conanaraby.com/wp-content/uploads/2023/05/cropped-favicon-192x192.png"
}
