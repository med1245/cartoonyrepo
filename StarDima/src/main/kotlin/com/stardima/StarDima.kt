@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "NewApi")

package com.stardima

import android.annotation.SuppressLint
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray

/** Intermediate episode data extracted from the StarDima API before building CloudStream Episode objects. */
private data class RawEpisode(
    val data: String,
    val name: String?,
    val number: Int,
    val posterUrl: String?
)

class StarDima : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "StarDima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private fun buildHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private fun buildApiHeaders(): Map<String, String> = buildHeaders() + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    // ─── Main page sections ───────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        ""              to "الرئيسية",          // Homepage (trending/featured)
        "newrelases"    to "المضاف حديثاً",      // New Releases
        "mosalsalat"    to "مسلسلات",            // Series
        "aflam"         to "أفلام"              // Movies
    )

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isMovie(url: String) = url.contains("/movie/")
    private fun isTvShow(url: String) = url.contains("/tvshow/")

    /**
     * Determine TvType from URL.
     * Shows: /tvshow/...  → TvSeries (treated as Anime since it's cartoon/anime site)
     * Movies: /movie/...  → Movie
     */
    private fun typeFrom(url: String): TvType =
        if (isMovie(url)) TvType.Movie else TvType.Anime

    /**
     * Extract all show/movie cards from a document.
     * Cards are <a href="/tvshow/..."> or <a href="/movie/..."> links.
     */
    private fun parseCards(doc: Document, baseUrl: String = mainUrl): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").trim()
            if (href.isBlank()) return@forEach
            // Only show/movie links from this domain
            if (!href.startsWith(mainUrl)) return@forEach
            if (!isTvShow(href) && !isMovie(href)) return@forEach
            // Skip episode play links like /tvshow/.../play/...
            if (href.contains("/play/")) return@forEach
            if (!seen.add(href)) return@forEach

            // Title: from title attr, img alt, or link text (strip UI decoration)
            val img = a.selectFirst("img")
            val rawTitle = (a.attr("title").ifBlank { null }
                ?: img?.attr("alt")?.ifBlank { null }
                ?: a.text()).trim()
            // Clean up — remove newlines and excessive whitespace
            val title = rawTitle.replace(Regex("\\s+"), " ").trim().ifBlank { return@forEach }

            // Poster: from <img> inside the anchor
            val poster = img?.attr("abs:src")?.ifBlank { null }
                ?: img?.attr("data-src")?.ifBlank { null }

            results.add(
                newAnimeSearchResponse(title, href, typeFrom(href)) {
                    posterUrl = poster
                }
            )
        }
        return results
    }

    // ─── getMainPage ──────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data  // "", "newrelases", "mosalsalat", "aflam"

        val url = when {
            key.isBlank() -> mainUrl  // homepage
            page == 1 -> "$mainUrl/$key"
            else -> "$mainUrl/$key?page=$page"
        }

        val doc = try {
            app.get(url, headers = buildHeaders()).document
        } catch (t: Throwable) {
            Log.e("StarDima", "getMainPage($url) failed: ${t.message}")
            return newHomePageResponse(request.name, emptyList())
        }

        val items = parseCards(doc)
        // Homepage doesn't paginate; other pages do
        val hasMore = key.isNotBlank() && items.isNotEmpty() && page < 50
        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    // ─── search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        return try {
            val doc = app.get(
                "$mainUrl/search",
                params = mapOf("q" to q),
                headers = buildHeaders()
            ).document
            parseCards(doc)
        } catch (t: Throwable) {
            Log.e("StarDima", "search($q) failed: ${t.message}")
            emptyList()
        }
    }

    // ─── load ─────────────────────────────────────────────────────────────────

    /**
     * Fetch episodes for a season from the JSON API.
     * Returns a list of RawEpisode objects with watch_url as data.
     */
    private suspend fun fetchSeasonEpisodes(seasonId: String, showPoster: String?): List<RawEpisode> {
        return try {
            val apiUrl = "$mainUrl/series/season/$seasonId"
            val response = app.get(
                apiUrl,
                headers = buildApiHeaders()
            ).text

            val json = JSONObject(response)
            val episodesArr: JSONArray = json.optJSONArray("episodes") ?: return emptyList()

            val episodes = mutableListOf<RawEpisode>()
            for (i in 0 until episodesArr.length()) {
                val ep = episodesArr.getJSONObject(i)
                val epId = ep.optInt("id", -1)
                if (epId < 0) continue
                val epNum = ep.optInt("episode_number", i + 1)
                val epTitle = ep.optString("title", "الحلقة $epNum")
                val watchUrl = ep.optString("watch_url", "")

                if (watchUrl.isBlank()) continue

                episodes.add(RawEpisode(
                    data = watchUrl,
                    name = epTitle.ifBlank { null },
                    number = epNum,
                    posterUrl = showPoster
                ))
            }
            episodes
        } catch (t: Throwable) {
            Log.e("StarDima", "fetchSeasonEpisodes($seasonId) failed: ${t.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = buildHeaders()).document

            // Title: prefer <h1>, fallback to <title> (strip site name suffix)
            val h1 = doc.selectFirst("h1")?.text()?.trim()
            val titleFallback = doc.title()
                .trim()
                .substringBefore(" - ")
                .substringBefore(" | ")
                .trim()
            val title = (h1?.ifBlank { null } ?: titleFallback).ifBlank { "StarDima" }

            // Plot: meta description
            val metaDesc = doc.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")?.trim()
            val bodyPlot = doc.selectFirst("p")?.text()?.trim()
            val plot = (metaDesc?.ifBlank { null } ?: bodyPlot?.ifBlank { null })

            // Poster: from og:image meta or first large <img>
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: doc.selectFirst("img[src]")?.attr("abs:src")?.ifBlank { null }

            if (isMovie(url)) {
                // Movie: find the single play link or use a direct href
                val playLink = doc.select("a[href*=/play/]").firstOrNull()?.attr("abs:href")
                    ?: url
                newMovieLoadResponse(title, url, TvType.Movie, playLink) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // TV Show: use the season API to get all episodes
                // 1. Find the episodes list container with data-initial-season-id
                val episodesContainer = doc.selectFirst("#episodes-list-container")
                val initialSeasonId = episodesContainer?.attr("data-initial-season-id")?.trim()

                // 2. Find all season items with data-season-id to handle multi-season shows
                val seasonItems = doc.select("[data-season-id]")
                val allSeasonIds = if (seasonItems.isNotEmpty()) {
                    seasonItems.map { it.attr("data-season-id") }.filter { it.isNotBlank() }.distinct()
                } else if (!initialSeasonId.isNullOrBlank()) {
                    listOf(initialSeasonId)
                } else {
                    // Fallback: look for first play link and use its URL to find episode list
                    val firstPlayHref = doc.select("a[href*=/play/]").firstOrNull()?.attr("abs:href")
                    if (firstPlayHref != null) {
                        // Try to get the season ID from the player page
                        try {
                            val playerDoc = app.get(firstPlayHref, headers = buildHeaders()).document
                            val cont = playerDoc.selectFirst("#episodes-list-container")
                            val sid = cont?.attr("data-initial-season-id")?.trim()
                            if (!sid.isNullOrBlank()) listOf(sid) else emptyList()
                        } catch (_: Throwable) { emptyList() }
                    } else emptyList()
                }

                if (allSeasonIds.isEmpty()) {
                    Log.w("StarDima", "No season IDs found for $url")
                    return null
                }

                // 3. Fetch episodes for each season
                val allEpisodes = mutableListOf<Episode>()
                for ((seasonIndex, seasonId) in allSeasonIds.withIndex()) {
                    val rawEps = fetchSeasonEpisodes(seasonId, poster)
                    // Tag each episode with the season number
                    rawEps.forEach { ep ->
                        allEpisodes.add(newEpisode(ep.data) {
                            this.name = ep.name
                            this.episode = ep.number
                            this.season = seasonIndex + 1
                            this.posterUrl = ep.posterUrl
                        })
                    }
                }

                if (allEpisodes.isEmpty()) {
                    Log.w("StarDima", "No episodes found for $url")
                    return null
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        } catch (t: Throwable) {
            Log.e("StarDima", "load($url) failed: ${t.message}")
            null
        }
    }

    // ─── loadLinks ────────────────────────────────────────────────────────────

    /**
     * The episode data is a hyperwatching.com iframe URL like:
     *   https://hyperwatching.com/iframe/JgiZyLNAmI5I
     * We need to fetch that iframe page and extract the stream URL.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StarDima", "loadLinks: $data")
        var found = false

        // data could be a hyperwatching.com/iframe/ URL or a stardima player page URL
        val urlsToTry = mutableListOf(data)

        // If it's a stardima.com URL (movie play page), also try fetching it to find the iframe
        if (data.startsWith(mainUrl)) {
            try {
                val doc = app.get(data, headers = buildHeaders()).document
                val iframe = doc.selectFirst("iframe[src]")?.attr("abs:src")
                if (!iframe.isNullOrBlank() && iframe.startsWith("http")) {
                    urlsToTry.add(0, iframe)
                }
            } catch (_: Throwable) {}
        }

        for (targetUrl in urlsToTry) {
            try {
                val resp = app.get(
                    targetUrl,
                    headers = buildHeaders() + mapOf("Referer" to mainUrl)
                )
                val html = resp.text
                val doc = resp.document

                // Strategy 1: <video src> or <source src>
                doc.select("video[src], source[src]").forEach { el ->
                    val src = el.attr("abs:src").trim()
                    if (src.startsWith("http") && (src.contains(".mp4") || src.contains(".m3u8"))) {
                        val isM3u8 = src.contains(".m3u8")
                        Log.d("StarDima", "video/source tag: $src")
                        callback(ExtractorLink(
                            source = name,
                            name = "$name ${if (isM3u8) "HLS" else "MP4"}",
                            url = src,
                            referer = targetUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = isM3u8
                        ))
                        found = true
                    }
                }

                // Strategy 2: Regex scan for m3u8/mp4 in page HTML
                if (!found) {
                    val streamRegex = Regex("""https://[^\s"'\\]+\.(?:mp4|m3u8)(?:\?[^\s"'\\]*)?""")
                    streamRegex.findAll(html).forEach { m ->
                        val src = m.value
                        val isM3u8 = src.contains(".m3u8")
                        Log.d("StarDima", "regex stream: $src")
                        callback(ExtractorLink(
                            source = name,
                            name = "$name ${if (isM3u8) "HLS" else "Stream"}",
                            url = src,
                            referer = targetUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = isM3u8
                        ))
                        found = true
                    }
                }

                // Strategy 3: script tag JSON with file/url/src/stream etc.
                if (!found) {
                    val scriptRegex = Regex("""["'](?:file|url|src|stream|link|source|hls|video)["']\s*:\s*["'](https://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    scriptRegex.findAll(html).forEach { m ->
                        val src = m.groupValues[1]
                        val isM3u8 = src.contains(".m3u8")
                        Log.d("StarDima", "script JSON stream: $src")
                        callback(ExtractorLink(
                            source = name,
                            name = "$name Script",
                            url = src,
                            referer = targetUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = isM3u8
                        ))
                        found = true
                    }
                }

                // Strategy 4: Follow nested iframe
                if (!found) {
                    doc.select("iframe[src]").forEach { iframe ->
                        val iframeSrc = iframe.attr("abs:src").trim()
                        if (iframeSrc.isBlank() || !iframeSrc.startsWith("http")) return@forEach
                        if (iframeSrc == targetUrl) return@forEach  // avoid loop
                        Log.d("StarDima", "iframe: $iframeSrc")
                        try {
                            val iDoc = app.get(
                                iframeSrc,
                                headers = buildHeaders() + mapOf("Referer" to targetUrl)
                            )
                            val iHtml = iDoc.text

                            iDoc.document.select("video[src], source[src]").forEach { el ->
                                val src = el.attr("abs:src").trim()
                                if (src.startsWith("http") && (src.contains(".mp4") || src.contains(".m3u8"))) {
                                    val isM3u8 = src.contains(".m3u8")
                                    callback(ExtractorLink(
                                        source = name,
                                        name = "$name Iframe",
                                        url = src,
                                        referer = iframeSrc,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = isM3u8
                                    ))
                                    found = true
                                }
                            }

                            if (!found) {
                                val streamRegex2 = Regex("""https://[^\s"'\\]+\.(?:mp4|m3u8)(?:\?[^\s"'\\]*)?""")
                                streamRegex2.findAll(iHtml).forEach { m ->
                                    val src = m.value
                                    val isM3u8 = src.contains(".m3u8")
                                    callback(ExtractorLink(
                                        source = name,
                                        name = "$name Iframe Stream",
                                        url = src,
                                        referer = iframeSrc,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = isM3u8
                                    ))
                                    found = true
                                }
                            }

                            if (!found) {
                                val scriptRegex2 = Regex("""["'](?:file|url|src|stream|link|source|hls|video)["']\s*:\s*["'](https://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
                                scriptRegex2.findAll(iHtml).forEach { m ->
                                    val src = m.groupValues[1]
                                    val isM3u8 = src.contains(".m3u8")
                                    callback(ExtractorLink(
                                        source = name,
                                        name = "$name Iframe Script",
                                        url = src,
                                        referer = iframeSrc,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = isM3u8
                                    ))
                                    found = true
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                if (found) break
            } catch (t: Throwable) {
                Log.e("StarDima", "loadLinks trying $targetUrl failed: ${t.message}")
            }
        }

        if (!found) Log.w("StarDima", "No link found for: $data")
        return found
    }
}
