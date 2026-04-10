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
        
        // Check if it's a Google Drive URL
        if (data.contains("drive.google.com")) {
            // Extract the ID from either /d/XXXX/view or id=XXXX
            val idMatch = Regex("d/([^/]+)|id=([^&]+)").find(data)
            val id = idMatch?.groupValues?.get(1)?.ifBlank { null } 
                  ?: idMatch?.groupValues?.get(2)
                  
            if (id != null) {
                // The /uc?export=download endpoint forces a direct file download 
                val directUrl = "https://drive.google.com/uc?export=download&id=$id"
                
                callback(
                    newExtractorLink(name, "Google Drive (MP4)", directUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }
        
        // Fallback: provide raw URL to webview if needed
        callback(
            newExtractorLink(name, "$name Raw Link", data, ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        private const val TAG = "PGMEDProvider"
    }
}
