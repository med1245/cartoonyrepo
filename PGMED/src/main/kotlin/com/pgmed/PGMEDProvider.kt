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

    /** Look up a catalog item by its URL */
    private fun findItem(url: String): MediaItem? {
        // The url from CloudStream may have been cleaned/modified, try to match flexibly
        return allItems.find { url.contains(it.url) || it.url.contains(url) || url == it.url }
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

        return newMovieLoadResponse(
            name = item?.title ?: "PGMED Video",
            url = url,
            type = item?.type ?: TvType.Movie,
            dataUrl = item?.url ?: url
        ) {
            this.posterUrl = item?.posterUrl
            this.plot = item?.title
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

        // The data is the playmogo embed URL, e.g. https://playmogo.com/e/XXXXX
        // Try converting /e/ to /d/ for Doodstream-compatible extraction
        val embedUrl = data.trim()

        // Try loadExtractor with the original URL first
        try {
            val result = loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            if (result) return true
        } catch (e: Exception) {
            Log.e(TAG, "loadExtractor on embed failed: ${e.message}")
        }

        // Try with /d/ path (Doodstream download page pattern)
        val doodUrl = embedUrl.replace("/e/", "/d/")
        if (doodUrl != embedUrl) {
            try {
                val result = loadExtractor(doodUrl, mainUrl, subtitleCallback, callback)
                if (result) return true
            } catch (e: Exception) {
                Log.e(TAG, "loadExtractor on /d/ failed: ${e.message}")
            }
        }

        // Fallback: try to scrape the embed page for a direct video URL
        try {
            val response = app.get(embedUrl, referer = mainUrl)
            val html = response.text

            // Look for common video URL patterns in the page
            val mp4Match = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""").find(html)
            val m3u8Match = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)

            val videoUrl = m3u8Match?.groupValues?.get(1) ?: mp4Match?.groupValues?.get(1)

            if (videoUrl != null) {
                val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                               else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(name, "$name Stream", videoUrl, linkType) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTML scrape failed: ${e.message}")
        }

        // Last resort: push the embed URL as-is
        callback(
            newExtractorLink(name, "$name Direct", embedUrl, ExtractorLinkType.VIDEO) {
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
