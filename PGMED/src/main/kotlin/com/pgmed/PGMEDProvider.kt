package com.pgmed

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class PGMEDProvider : MainAPI() {

    // ── Provider identity ────────────────────────────────────────────────────
    override var mainUrl = "https://drive.google.com" // Set to Google Drive
    override var name = "PGMED"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ── Hardcoded content catalog ────────────────────────────────────────────
    data class MediaItem(
        val title: String,
        val url: String,
        val posterUrl: String,
        val type: TvType,
        val plot: String? = null
    )

    private val catalog = mapOf(
        "Latest Movies" to listOf(
            MediaItem(
                title = "Avatar: Fire and Ash (2025)",
                url = "https://drive.google.com/file/d/1fhT2IeDtKH-Q7DETP2k_sQtHAXybOFFG/view?usp=drive_link",
                posterUrl = "https://m.media-amazon.com/images/M/MV5BZDYxY2I1OGMtN2Y4MS00ZmU1LTgyNDAtODA0MzAyYjI0N2Y2XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg",
                type = TvType.Movie,
                plot = "Jake Sully and Neytiri encounter the Ash People, a hostile clan of Na'vi, as they explore deeper into the unknown regions of Pandora."
            )
        ),
        "Trending Series" to emptyList()
    )

    private val allItems get() = catalog.values.flatten()

    private fun findItem(url: String): MediaItem? {
        return allItems.find { url.contains(it.url) || it.url.contains(url) || url == it.url }
    }

    // ── IMDb / Cinemeta Metadata ─────────────────────────────────────────────
    data class MetaData(
        val title: String,
        val posterPath: String?,
        val backgroundPath: String?,
        val plot: String?,
        val year: Int?
    )

    private suspend fun fetchCinemeta(query: String, type: TvType): MetaData? {
        return try {
            val typeStr = if (type == TvType.TvSeries) "series" else "movie"
            // Clean the query slightly
            val cleanQuery = query.replace("\\(.*?\\)".toRegex(), "").trim()
            val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "https://v3-cinemeta.strem.io/catalog/$typeStr/top/search=$encodedQuery.json"
            
            val response = app.get(url).text
            val json = JSONObject(response)
            val metas = json.optJSONArray("metas")
            if (metas != null && metas.length() > 0) {
                val top = metas.getJSONObject(0)
                MetaData(
                    title = top.optString("name", cleanQuery),
                    posterPath = top.optString("poster", "").ifBlank { null },
                    backgroundPath = top.optString("background", "").ifBlank { null },
                    plot = top.optString("description", "").ifBlank { null },
                    year = top.optString("releaseInfo", top.optString("year", "")).take(4).toIntOrNull()
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Cinemeta search failed: ${e.message}")
            null
        }
    }

    // ── Homepage ─────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = catalog.map { (category, items) ->
            HomePageList(
                category,
                items.map { item ->
                    if (item.type == TvType.TvSeries) {
                        newTvSeriesSearchResponse(item.title, item.url) {
                            this.posterUrl = item.posterUrl
                        }
                    } else {
                        newMovieSearchResponse(item.title, item.url) {
                            this.posterUrl = item.posterUrl
                        }
                    }
                }
            )
        }
        return newHomePageResponse(sections)
    }

    // ── Search ───────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        return allItems
            .filter { it.title.lowercase().contains(q) }
            .map { item ->
                if (item.type == TvType.TvSeries) {
                    newTvSeriesSearchResponse(item.title, item.url) {
                        this.posterUrl = item.posterUrl
                    }
                } else {
                    newMovieSearchResponse(item.title, item.url) {
                        this.posterUrl = item.posterUrl
                    }
                }
            }
    }

    // ── Load (detail page) ───────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load url=$url")
        val item = findItem(url)
        val actualTitle = item?.title ?: "PGMED Video"
        val actualType = item?.type ?: TvType.Movie
        
        // Fetch metadata from IMDb via Stremio Cinemeta
        val meta = fetchCinemeta(actualTitle, actualType)

        if (actualType == TvType.TvSeries) {
            return newTvSeriesLoadResponse(
                name = meta?.title ?: actualTitle,
                url = url,
                type = actualType,
                episodes = listOf(
                    newEpisode(item?.url ?: url) {
                        this.name = "Episode 1"
                        this.posterUrl = meta?.posterPath ?: item?.posterUrl
                        this.description = meta?.plot ?: item?.plot
                    }
                )
            ) {
                this.posterUrl = meta?.posterPath ?: item?.posterUrl
                this.backgroundPosterUrl = meta?.backgroundPath
                this.plot = meta?.plot ?: item?.plot
                this.year = meta?.year
            }
        } else {
            return newMovieLoadResponse(
                name = meta?.title ?: actualTitle,
                url = url,
                type = actualType,
                dataUrl = item?.url ?: url
            ) {
                this.posterUrl = meta?.posterPath ?: item?.posterUrl
                this.backgroundPosterUrl = meta?.backgroundPath
                this.plot = meta?.plot ?: item?.plot
                this.year = meta?.year
            }
        }
    }

    // ── Load links (video extraction) ────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks data=$data")

        if (!data.contains("drive.google.com")) {
            callback(
                newExtractorLink(name, "$name Link", data, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // Extract the file ID
        val id = Regex("""(?:/d/|[?&]id=)([a-zA-Z0-9_-]{20,})""").find(data)?.groupValues?.get(1)
        if (id == null) {
            Log.w(TAG, "Could not extract Drive file ID from: $data")
            return false
        }
        Log.d(TAG, "Drive file ID: $id")

        // ── Step 1: Try CloudStream's built-in extractor system
        if (loadExtractor(data, "https://drive.google.com/", subtitleCallback, callback)) {
            Log.d(TAG, "Built-in extractor succeeded")
            return true
        }

        // ── Step 2: Fetch the download page and extract UUID confirmation token
        // For large files, Google returns an HTML warning page containing a UUID token.
        // We must parse that UUID and include it in the final download URL.
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val pageResp = app.get(
                "https://drive.google.com/uc?export=download&id=$id",
                referer  = "https://drive.google.com/",
                headers  = headers
            )
            val finalUrl = pageResp.url
            val html     = pageResp.text

            // If the final URL is already a direct file (no drive.google.com), use it
            if (!finalUrl.contains("drive.google.com") &&
                !finalUrl.contains("accounts.google.com") &&
                finalUrl.startsWith("http")) {
                Log.d(TAG, "Direct redirect: $finalUrl")
                callback(
                    newExtractorLink(name, "Google Drive", finalUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Parse UUID from the confirmation form / link
            //   <input type="hidden" name="uuid" value="XXXX">
            //   OR uuid=XXXX in anchor href
            val uuid = Regex("""name=.uuid.\s+value=.([^"'>\s]+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""[?&]uuid=([^&"'\s<>]+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""[\\"']uuid[\\"']\s*:\s*[\\"']([^"'\\]+)""").find(html)?.groupValues?.get(1)

            if (uuid != null) {
                val confirmUrl = "https://drive.usercontent.google.com/download" +
                    "?id=$id&export=download&authuser=0&confirm=t&uuid=$uuid"
                Log.d(TAG, "UUID confirmation URL: $confirmUrl")
                callback(
                    newExtractorLink(name, "Google Drive", confirmUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "UUID extraction failed: ${e.message}")
        }

        // ── Step 3: usercontent.google.com without UUID (sometimes works for smaller/shared files)
        val ucontentUrl = "https://drive.usercontent.google.com/download" +
            "?id=$id&export=download&authuser=0&confirm=t"
        Log.d(TAG, "Fallback usercontent URL: $ucontentUrl")
        callback(
            newExtractorLink(name, "Google Drive (Direct)", ucontentUrl, ExtractorLinkType.VIDEO) {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        private const val TAG = "PGMEDProvider"
    }
}
