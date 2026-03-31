package com.cleanwatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * CleanWatchProvider
 *
 * A Cloudstream plugin that:
 *  1. Fetches an M3U playlist from raw GitHub.
 *  2. Parses each #EXTINF entry to get the media title and stream URL.
 *  3. Scrapes TMDB for metadata (poster, overview, year) based on the title.
 *  4. Presents all media as playable Movie / TvSeries / Live entries in Cloudstream.
 */
class CleanWatchProvider : MainAPI() {

    // ── Configuration ─────────────────────────────────────────────────────────
    // Raw GitHub URL of the M3U playlist — update the file on GitHub to refresh content.
    private val M3U_URL =
        "https://raw.githubusercontent.com/med1245/cartoonyrepo/refs/heads/master/cleanwatch_playlist.m3u"

    // Replace with your TMDB API key. Free registration: https://www.themoviedb.org/settings/api
    // Leave as-is to skip metadata scraping (titles + tvg-logo from M3U will be used).
    private val TMDB_API_KEY = "YOUR_TMDB_API_KEY_HERE"

    // ── Provider identity ─────────────────────────────────────────────────────
    override var mainUrl = "https://raw.githubusercontent.com"
    override var name = "CleanWatch"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Live
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build a TMDB poster URL from a path returned by the API. */
    private fun tmdbPoster(path: String?) =
        if (path.isNullOrBlank()) null else "https://image.tmdb.org/t/p/w500$path"

    /** Build a TMDB backdrop URL from a path returned by the API. */
    private fun tmdbBackdrop(path: String?) =
        if (path.isNullOrBlank()) null else "https://image.tmdb.org/t/p/w1280$path"

    /**
     * A single entry parsed from the M3U file.
     */
    data class M3UEntry(
        val title: String,
        val streamUrl: String,
        val groupTitle: String = "",
        val logoUrl: String? = null
    )

    /** Fetch the M3U file from raw GitHub and parse it. */
    private suspend fun fetchAndParseM3U(): List<M3UEntry> {
        return try {
            val text = app.get(
                M3U_URL,
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            ).text
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
     *   #EXTINF:-1 tvg-logo="https://..." group-title="Movies",Movie Title
     *   https://myvidplay.com/e/XXXXXXXXXX
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
     * Encode title + stream URL into a single data string.
     * Format: cw|<title>|<url>
     */
    private fun encodeData(title: String, streamUrl: String) = "cw|$title|$streamUrl"

    private fun decodeTitle(data: String): String =
        data.removePrefix("cw|").substringBefore("|")

    private fun decodeStreamUrl(data: String): String =
        data.removePrefix("cw|").substringAfter("|")

    // ── Cloudstream API ───────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val entries = fetchAndParseM3U()

        // Group by group-title tag; fall back to "All Media"
        val grouped = entries.groupBy { it.groupTitle.ifBlank { "All Media" } }

        val sections = grouped.map { (group, items) ->
            val isLive = group.equals("Channels", ignoreCase = true)
            val cards = items.map { entry ->
                newMovieSearchResponse(
                    name = entry.title,
                    url  = encodeData(entry.title, entry.streamUrl),
                    type = if (isLive) TvType.Live else TvType.Movie
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
                    url  = encodeData(entry.title, entry.streamUrl),
                    type = TvType.Movie
                ) {
                    this.posterUrl = entry.logoUrl
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val title     = decodeTitle(url)
        val streamUrl = decodeStreamUrl(url)

        // Scrape TMDB using the actual title (from M3U #EXTINF)
        val meta = tmdbSearch(title)

        // Detect live streams for correct type
        val isLive = streamUrl.endsWith(".m3u8")

        return newMovieLoadResponse(
            name    = meta?.title ?: title,
            url     = url,
            type    = if (isLive) TvType.Live else (meta?.type ?: TvType.Movie),
            dataUrl = url
        ) {
            this.posterUrl           = meta?.posterPath?.let { tmdbPoster(it) }
                ?: run {
                    // Fall back to the tvg-logo from M3U (stored in logoUrl via search)
                    null
                }
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
        val streamUrl = decodeStreamUrl(data)
        Log.d(TAG, "loadLinks url=$streamUrl")

        // ── HLS (.m3u8) / plain MP4 pass-through ─────────────────────────────
        if (streamUrl.endsWith(".m3u8") || streamUrl.contains(".m3u8?") ||
            streamUrl.endsWith(".mp4")  || streamUrl.contains(".mp4?")) {
            val type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                       else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(name, "$name Direct", streamUrl, type) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // ── myvidplay.com / Doodstream embed ──────────────────────────────────
        // Both use the same Doodstream infrastructure; loadExtractor handles them.
        return try {
            loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadExtractor failed: ${e.message}")
            // Last-resort: push raw URL
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
        private const val TAG = "CleanWatchProvider"
    }
}
