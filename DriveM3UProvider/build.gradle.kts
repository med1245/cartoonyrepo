// Use an integer for version numbers
version = 1

cloudstream {
    description = "Plays Doodstream videos from an M3U playlist hosted on Google Drive, with TMDB metadata"
    authors = listOf("Mehdi Marsaman")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")

    language = "ar"

    iconUrl = "https://www.gstatic.com/images/branding/product/1x/drive_2020q4_48dp.png"
}
