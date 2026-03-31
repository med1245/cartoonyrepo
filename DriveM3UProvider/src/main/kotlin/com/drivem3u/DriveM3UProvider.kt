package com.drivem3u

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * DriveM3UProvider
 *
 * A Cloudstream plugin that:
 *  1. Fetches an M3U playlist from Google Drive (public shared file).
 *  2. Parses each #EXTINF entry to get the media title and Doodstream URL.
 *  3. Scrapes TMDB for metadata (poster, overview, year) based on the title.
 *  4. Presents all media as playable Movie / TvSeries entries in Cloudstream.
 *
 * ──────────────────────────────────────────────────────────────────
 *  HOW TO CONFIGURE:
 *  • Set M3U_DRIVE_FILE_ID  → the Google Drive file ID of your .m3u file
 *    (the part after "/d/" in the share link, e.g. "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74Ib")
 *  • Set TMDB_API_KEY        → your TMDB v3 API key (free at themoviedb.org/settings/api)
 * ──────────────────────────────────────────────────────────────────
 */
class DriveM3UProvider : MainAPI() {

    // ── Configuration ────────────────────────────────────────────────────────
    // Replace with your own Google Drive file ID.
    // Get it from your share link: https://drive.google.com/file/d/FILE_ID_HERE/view
    private val M3U_DRIVE_FILE_ID = "1oMRF1td4rCu1aGfN-LPxp6qT6Y88Qa30"

    // Replace with your TMDB API key. Free registration: https://www.themoviedb.org/settings/api
    // Leave as-is to skip metadata scraping (titles + tvg-logo from M3U will be used).
    private val TMDB_API_KEY = "YOUR_TMDB_API_KEY_HERE"

    // ── Provider identity ─────────────────────────────────────────────────────
    override var mainUrl = "https://drive.google.com"
    override var name = "DriveM3U"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build the direct-download URL for a public Google Drive file. */
    private fun driveDownloadUrl(fileId: String) =
        "https://drive.google.com/uc?export=download&id=$fileId"

    /** Build a TMDB poster URL from a path returned by the API. */
    private fun tmdbPoster(path: String?) =
        if (path.isNullOrBlank()) null else "https://image.tmdb.org/t/p/w500$path"

    /** Build a TMDB backdrop URL from a path returned by the API. */
    private fun tmdbBackdrop(path: String?) =
        if (path.isNullOrBlank()) null else "https://image.tmdb.org/t/p/w1280$path"

    /**
     * A single entry parsed from the M3U file.
     * [doodUrl] is the Doodstream link from the M3U.
     */
    data class M3UEntry(
        val title: String,
        val doodUrl: String,
        val groupTitle: String = "",
        val logoUrl: String? = null
    )

    /** Fetch the M3U file from Google Drive and parse it. */
    private suspend fun fetchAndParseM3U(): List<M3UEntry> {
        val downloadUrl = driveDownloadUrl(M3U_DRIVE_FILE_ID)
        return try {
            val text = app.get(downloadUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            parseM3U(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch M3U: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse raw M3U text.
     *
     * Handles the standard IPTV format:
     *   #EXTINF:-1 tvg-logo="https://..." group-title="Kids",Show Title
     *   https://dood.la/e/XXXXXXXXXX
     */
    private fun parseM3U(text: String): List<M3UEntry> {
        val entries = mutableListOf<M3UEntry>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                val logoUrl    = Regex("""tvg-logo="([^"]*)"""").find(line)
                    ?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
                val groupTitle = Regex("""group-title="([^"]*)"""").find(line)
                    ?.groupValues?.getOrNull(1)?.trim() ?: ""
                val titlePart  = line.substringAfterLast(",").trim()

                // Find the next non-blank, non-comment line for the URL
                var urlLine = ""
                var j = i + 1
                while (j < lines.size) {
                    val c = lines[j].trim()
                    if (c.isNotBlank() && !c.startsWith("#")) { urlLine = c; break }
                    j++
                }
                i = j

                if (urlLine.isNotBlank() && titlePart.isNotBlank()) {
                    entries.add(M3UEntry(titlePart, urlLine, groupTitle, logoUrl))
                }
            }
            i++
        }
        Log.d(TAG, "Parsed ${entries.size} M3U entries")
        return entries
    }

    // ── TMDB metadata scraper ─────────────────────────────────────────────────

    data class TmdbMeta(
        val title: String,
        val overview: String?,
        val posterPath: String?,
        val backdropPath: String?,
        val year: Int?,
        val type: TvType
    )

    /** Search TMDB for a title and return the top match's metadata. */
    private suspend fun tmdbSearch(query: String): TmdbMeta? {
        if (TMDB_API_KEY == "YOUR_TMDB_API_KEY_HERE") return null
        return try {
            val encodedQuery = query.trim().replace(" ", "+")
            val url = "https://api.themoviedb.org/3/search/multi" +
                    "?api_key=$TMDB_API_KEY&query=$encodedQuery&language=ar-SA"
            val json = JSONObject(app.get(url).text)
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val top = results.getJSONObject(0)
            val mediaType = top.optString("media_type", "movie")
            val tmdbType  = if (mediaType == "tv") TvType.TvSeries else TvType.Movie
            val releaseDate = top.optString("release_date", "")
                .ifBlank { top.optString("first_air_date", "") }

            TmdbMeta(
                title        = top.optString("title", "").ifBlank { top.optString("name", query) },
                overview     = top.optString("overview", "").ifBlank { null },
                posterPath   = top.optString("poster_path", "").ifBlank { null },
                backdropPath = top.optString("backdrop_path", "").ifBlank { null },
                year         = releaseDate.take(4).toIntOrNull(),
                type         = tmdbType
            )
        } catch (e: Exception) {
            Log.e(TAG, "TMDB search failed for '$query': ${e.message}")
            null
        }
    }

    // ── Data-encoding helpers ─────────────────────────────────────────────────

    /**
     * We store the Doodstream URL prefixed with the title so we can recover
     * both the title (for TMDB lookup) and the URL in load().
     *
     * Format:  dood|<title>|<url>
     */
    private fun encodeData(title: String, doodUrl: String) = "dood|$title|$doodUrl"

    private fun decodeTitle(data: String): String =
        data.removePrefix("dood|").substringBefore("|")

    private fun decodeDoodUrl(data: String): String =
        data.removePrefix("dood|").substringAfter("|")

    // ── Cloudstream API ───────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val entries = fetchAndParseM3U()

        // Group by group-title tag; fall back to "All Media"
        val grouped = entries.groupBy { it.groupTitle.ifBlank { "All Media" } }

        val sections = grouped.map { (group, items) ->
            val cards = items.map { entry ->
                newMovieSearchResponse(
                    name = entry.title,
                    url  = encodeData(entry.title, entry.doodUrl),
                    type = TvType.Movie
                ) {
                    this.posterUrl = entry.logoUrl
                }
            }
            HomePageList(group, cards)
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        return fetchAndParseM3U()
            .filter { it.title.lowercase().contains(q) }
            .map { entry ->
                newMovieSearchResponse(
                    name = entry.title,
                    url  = encodeData(entry.title, entry.doodUrl),
                    type = TvType.Movie
                ) {
                    this.posterUrl = entry.logoUrl
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val title   = decodeTitle(url)
        val doodUrl = decodeDoodUrl(url)

        // Scrape TMDB using the actual title (from M3U #EXTINF)
        val meta = tmdbSearch(title)

        return newMovieLoadResponse(
            name    = meta?.title ?: title,
            url     = url,
            type    = meta?.type ?: TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl           = tmdbPoster(meta?.posterPath)
            this.backgroundPosterUrl = tmdbBackdrop(meta?.backdropPath)
            this.plot                = meta?.overview
            this.year                = meta?.year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = decodeDoodUrl(data)
        Log.d(TAG, "loadLinks url=$streamUrl")

        // ── myvidplay.com extractor ───────────────────────────────────────────
        if (streamUrl.contains("myvidplay.com")) {
            return try {
                val html = app.get(
                    streamUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0")
                ).text

                // 1. Try og:video meta tag
                var directUrl = Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']""").find(html)
                    ?.groupValues?.getOrNull(1)?.trim()

                // 2. Try jwplayer / file: "..." pattern
                if (directUrl.isNullOrBlank()) {
                    directUrl = Regex("""["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(html)
                        ?.groupValues?.getOrNull(1)?.trim()
                }

                // 3. Try sources:[{file:"..."}] pattern
                if (directUrl.isNullOrBlank()) {
                    directUrl = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""").find(html)
                        ?.groupValues?.getOrNull(1)?.trim()
                }

                if (!directUrl.isNullOrBlank()) {
                    Log.d(TAG, "myvidplay direct url=$directUrl")
                    callback(
                        newExtractorLink(name, "$name [myvidplay]", directUrl, ExtractorLinkType.VIDEO) {
                            this.referer = streamUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    true
                } else {
                    Log.w(TAG, "myvidplay: could not extract direct URL from page")
                    // Last resort: push the page URL and hope the player handles it
                    callback(
                        newExtractorLink(name, "$name Direct", streamUrl, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "myvidplay extraction failed: ${e.message}")
                false
            }
        }

        // ── HLS / plain stream pass-through ──────────────────────────────────
        if (streamUrl.endsWith(".m3u8") || streamUrl.endsWith(".mp4")) {
            callback(
                newExtractorLink(
                    name, "$name Direct", streamUrl,
                    if (streamUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // ── Default: Cloudstream built-in extractor (Doodstream etc.) ─────────
        return try {
            loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadExtractor failed: ${e.message}")
            callback(
                newExtractorLink(name, "$name Direct", streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            false
        }
    }

    companion object {
        private const val TAG = "DriveM3UProvider"
    }
}
