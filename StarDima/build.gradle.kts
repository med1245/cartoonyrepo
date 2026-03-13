android {
    lint {
        lintConfig = file("lint.xml")
        abortOnError = false
    }
}

dependencies {
    // material is included for completeness; not directly used by plugin logic
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Arabic cartoons, anime, series, and movies from stardima.com"
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

    iconUrl = "https://www.stardima.com/favicon.ico"
}
