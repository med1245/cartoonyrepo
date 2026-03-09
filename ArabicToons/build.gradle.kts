// Use an integer for version numbers
version = 1

cloudstream {
    description = "Arabic cartoons, anime, series, and movies from arabic-toons.com"
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

    iconUrl = "https://www.arabic-toons.com/images/logo.png"
}
