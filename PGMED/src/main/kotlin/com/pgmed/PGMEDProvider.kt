package com.pgmed

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLDecoder

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

    private fun extractDriveFileId(url: String): String? {
        return Regex("""(?:/d/|[?&]id=|/uc\?id=|/file/d/)([a-zA-Z0-9_-]{20,})""")
            .find(url)
            ?.groupValues
            ?.get(1)
    }

    private fun parseFormEncoded(data: String): Map<String, String> {
        if (data.isBlank()) return emptyMap()
        return data.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }

    private fun extractUrlFromCipher(cipher: String): String? {
        return parseFormEncoded(cipher)["url"]?.let { URLDecoder.decode(it, "UTF-8") }
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
        val id = extractDriveFileId(data)
        if (id == null) {
            Log.w(TAG, "Could not extract Drive file ID from: $data")
            return false
        }
        Log.d(TAG, "Drive file ID: $id")

        // ── Step 1: Try CloudStream's built-in extractor system with normalized Drive watch URL
        val normalizedWatchUrl = "https://drive.google.com/file/d/$id/view"
        if (loadExtractor(normalizedWatchUrl, "https://drive.google.com/", subtitleCallback, callback)) {
            Log.d(TAG, "Built-in extractor succeeded")
            return true
        }

        // ── Step 2: Prefer real streaming URLs from Drive video info (avoids HTML container parsing errors)
        try {
            val infoUrl = "https://drive.google.com/get_video_info?docid=$id"
            val infoResp = app.get(
                infoUrl,
                referer = "https://drive.google.com/"
            ).text

            val infoMap = parseFormEncoded(infoResp)
            val playerResponseRaw = infoMap["player_response"]
            val hlsvp = infoMap["hlsvp"]?.takeIf { it.startsWith("http") }

            if (!hlsvp.isNullOrBlank()) {
                Log.d(TAG, "Found hlsvp in get_video_info")
                callback(
                    newExtractorLink(name, "Google Drive HLS", hlsvp, ExtractorLinkType.M3U8) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            if (!playerResponseRaw.isNullOrBlank()) {
                val playerResponse = JSONObject(playerResponseRaw)
                val streamingData = playerResponse.optJSONObject("streamingData")
                val hlsManifestUrl = streamingData?.optString("hlsManifestUrl")?.takeIf { it.startsWith("http") }
                if (!hlsManifestUrl.isNullOrBlank()) {
                    Log.d(TAG, "Found hlsManifestUrl in player_response")
                    callback(
                        newExtractorLink(name, "Google Drive HLS", hlsManifestUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://drive.google.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }

                val formats = streamingData?.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val format = formats.optJSONObject(i) ?: continue
                        val directUrl = format.optString("url").takeIf { it.startsWith("http") }
                            ?: format.optString("signatureCipher").let { extractUrlFromCipher(it) }
                            ?: format.optString("cipher").let { extractUrlFromCipher(it) }
                        if (!directUrl.isNullOrBlank()) {
                            Log.d(TAG, "Found direct format URL in player_response")
                            callback(
                                newExtractorLink(name, "Google Drive Video", directUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = "https://drive.google.com/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "get_video_info extraction failed: ${e.message}")
        }

        // ── Step 3: Fetch the virus-scan confirmation page and extract the real download URL
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

            // Redirected directly to a non-Google URL → real file
            if (!finalUrl.contains("drive.google.com") &&
                !finalUrl.contains("accounts.google.com") &&
                finalUrl.startsWith("http")) {
                Log.d(TAG, "Direct redirect to: $finalUrl")
                callback(
                    newExtractorLink(name, "Google Drive", finalUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Strategy A: grab the full usercontent URL embedded in the confirmation page
            // (works regardless of HTML attribute order)
            val ucontentMatch = Regex(
                """(https://drive\.usercontent\.google\.com/download\?[^"'\s<>\\]+)"""
            ).find(html)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?.replace("\\u003d", "=")
                ?.replace("\\u0026", "&")

            if (ucontentMatch != null && ucontentMatch.contains("uuid=")) {
                Log.d(TAG, "Strategy A usercontent URL: $ucontentMatch")
                callback(
                    newExtractorLink(name, "Google Drive", ucontentMatch, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // Strategy B: extract UUID value from hidden input fields (both attribute orders)
            val uuid = Regex("""name=.uuid.\s+value=.([^"'>\s]+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""value=.([^"'>\s]+).\s+name=.uuid.""").find(html)?.groupValues?.get(1)
                ?: Regex("""[?&]uuid=([^&"'\s<>]+)""").find(html)?.groupValues?.get(1)

            if (uuid != null) {
                val confirmUrl = "https://drive.usercontent.google.com/download" +
                    "?id=$id&export=download&authuser=0&confirm=t&uuid=$uuid"
                Log.d(TAG, "Strategy B UUID URL: $confirmUrl")
                callback(
                    newExtractorLink(name, "Google Drive", confirmUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            Log.w(TAG, "No confirmation URL found in page. HTML snippet: ${html.take(500)}")
        } catch (e: Exception) {
            Log.w(TAG, "Confirmation page extraction failed: ${e.message}")
        }

        // ── Step 4: usercontent.google.com without UUID (sometimes works for smaller/shared files)
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
