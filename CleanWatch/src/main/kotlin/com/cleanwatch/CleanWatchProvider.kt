package com.cleanwatch

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * CleanWatchProvider
 *
 * Fetches an M3U playlist from Google Drive, parses Doodstream links,
 * and scrapes TMDB metadata based on media titles.
 *
 * ─────────────────────────────────────────────────────────
 *  CONFIGURATION:
 *  1. Set M3U_DRIVE_FILE_ID → your Google Drive .m3u file ID
 *     (from: https://drive.google.com/file/d/FILE_ID_HERE/view)
 *  2. Set TMDB_API_KEY      → your free TMDB v3 key
 *     (from: https://www.themoviedb.org/settings/api)
 * ─────────────────────────────────────────────────────────
 */
class CleanWatchProvider : MainAPI() {

    // ── ⚙️  CONFIGURE THESE TWO VALUES ───────────────────────────────────────
    private val M3U_DRIVE_FILE_ID = "YOUR_GOOGLE_DRIVE_FILE_ID_HERE"
    private val TMDB_API_KEY      = "YOUR_TMDB_API_KEY_HERE"
    // ─────────────────────────────────────────────────────────────────────────

    override var mainUrl        = "https://drive.google.com"
    override var name           = "CleanWatch"
    override var lang           = "ar"
    override val hasMainPage    = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun driveDownloadUrl(fileId: String) =
        "https://drive.google.com/uc?export=download&id=$fileId"

    private fun tmdbPoster(path: String?) =
        if (path.isNullOrBlank()) null else "https://image.tmdb.org/t/p/w500$path"

    private fun tmdbBackdrop(path: String?) =
        if (path.isNullOrBlank()) null else "https://image.tmdb.org/t/p/w1280$path"

    // ── M3U parsing ───────────────────────────────────────────────────────────

    data class M3UEntry(
        val title: String,
        val url: String,
        val group: String  = "",
        val logo: String?  = null
    )

    private suspend fun fetchM3U(): List<M3UEntry> {
        return try {
            val text = app.get(
                driveDownloadUrl(M3U_DRIVE_FILE_ID),
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            ).text
            parseM3U(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch M3U: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses standard IPTV M3U format:
     *   #EXTINF:-1 tvg-logo="URL" group-title="GROUP",Title
     *   https://dood.la/e/XXXXXXXXXX
     */
    private fun parseM3U(text: String): List<M3UEntry> {
        val entries = mutableListOf<M3UEntry>()
        val lines   = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                val logo  = Regex("""tvg-logo="([^"]*)"""").find(line)
                    ?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
                val group = Regex("""group-title="([^"]*)"""").find(line)
                    ?.groupValues?.getOrNull(1)?.trim() ?: ""
                val title = line.substringAfterLast(",").trim()

                var urlLine = ""
                var j = i + 1
                while (j < lines.size) {
                    val c = lines[j].trim()
                    if (c.isNotBlank() && !c.startsWith("#")) { urlLine = c; break }
                    j++
                }
                i = j

                if (urlLine.isNotBlank() && title.isNotBlank()) {
                    entries.add(M3UEntry(title, urlLine, group, logo))
                }
            }
            i++
        }
        Log.d(TAG, "Parsed ${entries.size} M3U entries")
        return entries
    }

    // ── TMDB scraper ──────────────────────────────────────────────────────────

    data class TmdbMeta(
        val title: String,
        val overview: String?,
        val posterPath: String?,
        val backdropPath: String?,
        val year: Int?,
        val type: TvType
    )

    private suspend fun tmdbSearch(query: String): TmdbMeta? {
        if (TMDB_API_KEY == "YOUR_TMDB_API_KEY_HERE") return null
        return try {
            val q    = query.trim().replace(" ", "+")
            val url  = "https://api.themoviedb.org/3/search/multi" +
                       "?api_key=$TMDB_API_KEY&query=$q&language=ar-SA"
            val json = JSONObject(app.get(url).text)
            val arr  = json.optJSONArray("results") ?: return null
            if (arr.length() == 0) return null
            val top  = arr.getJSONObject(0)
            val type = if (top.optString("media_type") == "tv") TvType.TvSeries else TvType.Movie
            val date = top.optString("release_date", "")
                .ifBlank { top.optString("first_air_date", "") }
            TmdbMeta(
                title        = top.optString("title", "").ifBlank { top.optString("name", query) },
                overview     = top.optString("overview", "").ifBlank { null },
                posterPath   = top.optString("poster_path", "").ifBlank { null },
                backdropPath = top.optString("backdrop_path", "").ifBlank { null },
                year         = date.take(4).toIntOrNull(),
                type         = type
            )
        } catch (e: Exception) {
            Log.e(TAG, "TMDB search failed: ${e.message}")
            null
        }
    }

    // ── Data encoding ─────────────────────────────────────────────────────────
    // Format:  cw|<title>|<dood_url>

    private fun encode(title: String, url: String) = "cw|$title|$url"
    private fun decodeTitle(data: String) = data.removePrefix("cw|").substringBefore("|")
    private fun decodeUrl(data: String)   = data.removePrefix("cw|").substringAfter("|")

    // ── Cloudstream API ───────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val entries = fetchM3U()
        val grouped = entries.groupBy { it.group.ifBlank { "All" } }
        val sections = grouped.map { (group, items) ->
            HomePageList(group, items.map { e ->
                newMovieSearchResponse(e.title, encode(e.title, e.url), TvType.Movie) {
                    this.posterUrl = e.logo
                }
            })
        }
        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        return fetchM3U()
            .filter { it.title.lowercase().contains(q) }
            .map { e ->
                newMovieSearchResponse(e.title, encode(e.title, e.url), TvType.Movie) {
                    this.posterUrl = e.logo
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val title   = decodeTitle(url)
        val doodUrl = decodeUrl(url)
        val meta    = tmdbSearch(title)

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
        val doodUrl = decodeUrl(data)
        Log.d(TAG, "loadLinks: $doodUrl")
        return try {
            loadExtractor(doodUrl, mainUrl, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Extractor failed: ${e.message}")
            callback(
                newExtractorLink(name, "$name Direct", doodUrl, ExtractorLinkType.VIDEO) {
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
