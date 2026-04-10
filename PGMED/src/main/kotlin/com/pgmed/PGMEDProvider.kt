package com.pgmed

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class PGMEDProvider : MainAPI() {

    // ── Provider identity ────────────────────────────────────────────────────
    override var mainUrl = "https://playmogo.com"
    override var name = "PGMED"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ── Data helpers ─────────────────────────────────────────────────────────
    private fun encodeData(title: String, url: String) = "pgmed|$title|$url"
    private fun decodeTitle(data: String) = data.removePrefix("pgmed|").substringBefore("|")
    private fun decodeUrl(data: String) = data.removePrefix("pgmed|").substringAfter("|")

    // ── Hardcoded content catalog ────────────────────────────────────────────
    data class MediaItem(
        val title: String,
        val url: String,
        val posterUrl: String,
        val type: TvType
    )

    private val catalog = mapOf(
        "Latest Movies" to listOf(
            MediaItem(
                title = "Avatar: Fire and Ash (2025)",
                url = "https://playmogo.com/e/1yhz2awpod5z",
                posterUrl = "https://m.media-amazon.com/images/M/MV5BZDYxY2I1OGMtN2Y4MS00ZmU1LTgyNDAtODA0MzAyYjI0N2Y2XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg",
                type = TvType.Movie
            )
        ),
        "Trending Series" to listOf(
            MediaItem(
                title = "hack",
                url = "https://playmogo.com/e/1yhz2awpod5z",
                posterUrl = "https://upload.wikimedia.org/wikipedia/en/thumb/5/58/Blackhat_poster.jpg/250px-Blackhat_poster.jpg",
                type = TvType.TvSeries
            )
        )
    )

    // Flat list for search / load lookups
    private val allItems get() = catalog.values.flatten()

    // ── Homepage ─────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = catalog.map { (category, items) ->
            HomePageList(
                category,
                items.map { item ->
                    if (item.type == TvType.TvSeries) {
                        newTvSeriesSearchResponse(item.title, encodeData(item.title, item.url)) {
                            this.posterUrl = item.posterUrl
                        }
                    } else {
                        newMovieSearchResponse(item.title, encodeData(item.title, item.url)) {
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
                    newTvSeriesSearchResponse(item.title, encodeData(item.title, item.url)) {
                        this.posterUrl = item.posterUrl
                    }
                } else {
                    newMovieSearchResponse(item.title, encodeData(item.title, item.url)) {
                        this.posterUrl = item.posterUrl
                    }
                }
            }
    }

    // ── Load (detail page) ───────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val title = decodeTitle(url)
        val streamUrl = decodeUrl(url)
        val item = allItems.find { it.title == title }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = item?.type ?: TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = item?.posterUrl
        }
    }

    // ── Load links (video extraction) ────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = decodeUrl(data)
        Log.d(TAG, "loadLinks url=$streamUrl")

        return try {
            loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadExtractor failed: ${e.message}")
            // Fallback: push raw URL
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
        private const val TAG = "PGMEDProvider"
    }
}
