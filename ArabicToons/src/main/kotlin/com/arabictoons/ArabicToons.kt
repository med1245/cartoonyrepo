@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.arabictoons

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
import org.jsoup.Jsoup
import org.json.JSONObject

class ArabicToons : MainAPI() {
    override var mainUrl = "https://www.arabic-toons.com"
    override var name = "ArabicToons"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private fun buildHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    // ─── Main page sections ───────────────────────────────────────────────────
    // The mainPage keys are page URLs (or identifiers) used in getMainPage().
    // We use the "page" parameter to drive pagination for listing pages.
    override val mainPage = mainPageOf(
        "index"          to "الحلقات الجديدة",      // New Episodes – from homepage
        "index_series"   to "مسلسلات جديدة",         // New Series – from homepage
        "index_movies"   to "أفلام جديدة",            // New Movies – from homepage
        "cartoon.php"    to "مسلسلات كارتونية",       // All Cartoon Series (55 pages)
        "movies.php"     to "جميع الأفلام"             // All Movies listing
    )

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun absoluteUrl(path: String): String {
        return if (path.startsWith("http")) path else "$mainUrl/$path"
    }

    /** Extract the show/movie numeric ID from a URL slug like "sally-1405893806-anime-streaming.html" */
    private fun extractShowId(href: String): String? {
        // Pattern: ...-{digits}-anime-streaming.html or ...-{digits}-movies-streaming.html
        val m = Regex("""-(\d{4,12})-(?:anime|movies)-streaming""").find(href)
        return m?.groupValues?.getOrNull(1)
    }

    /** Extract the episode numeric ID from a URL slug like "sally-1405893806-20342.html" */
    private fun extractEpisodeId(href: String): String? {
        // Pattern: ...-{showId}-{episodeId}.html  (episodeId is typically 5-6 digits)
        val m = Regex("""-(\d{4,12})-(\d{3,8})\.html""").find(href)
        return m?.groupValues?.getOrNull(2)
    }

    private fun posterForShow(id: String) = "$mainUrl/images/anime/cat_$id.jpg"
    private fun posterForMovie(id: String) = "$mainUrl/images/anime/film_$id.jpg"
    private fun thumbForEpisode(epId: String) = "$mainUrl/images/anime/mqdefault_$epId.jpg"

    private fun buildSearchResponse(
        title: String,
        href: String,
        isMovie: Boolean
    ): SearchResponse {
        val absHref = absoluteUrl(href)
        val id = extractShowId(href)
        val poster = if (isMovie) id?.let { posterForMovie(it) } else id?.let { posterForShow(it) }
        return newAnimeSearchResponse(
            title,
            absHref,
            if (isMovie) TvType.Movie else TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    // ─── getMainPage ──────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data        // e.g. "index", "index_series", "cartoon.php", "movies.php"
        val items = mutableListOf<SearchResponse>()

        when {
            key == "index" -> {
                // New Episodes section from homepage – links to individual episode pages
                // We resolve episode → show so we don't duplicate show entry in history
                val doc = app.get("$mainUrl/index.php", headers = buildHeaders()).document
                val seen = mutableSetOf<String>()
                doc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    // Episode URLs: contain a numeric show id AND an episode id, no "streaming"
                    if (href.contains(".html") && !href.contains("streaming")) {
                        val epId = extractEpisodeId(href) ?: return@forEach
                        // Derive the show URL from the episode URL by keeping show ID part
                        val showId = Regex("""-(\d{4,12})-\d{3,8}\.html""").find(href)
                            ?.groupValues?.getOrNull(1) ?: return@forEach
                        if (!seen.add(showId)) return@forEach

                        val img = a.selectFirst("img")
                        val title = img?.attr("alt")?.trim()
                            ?: a.text().trim().ifBlank { "حلقة $epId" }
                        val poster = thumbForEpisode(epId)
                        items.add(
                            newAnimeSearchResponse(title, absoluteUrl(href), TvType.TvSeries) {
                                posterUrl = poster
                            }
                        )
                    }
                }
            }

            key == "index_series" -> {
                // New Series section from homepage – link to show pages
                val doc = app.get("$mainUrl/index.php", headers = buildHeaders()).document
                doc.select("a[href*=-anime-streaming]").forEach { a ->
                    val href = a.attr("href")
                    val title = (a.attr("title").ifBlank { null }
                        ?: a.selectFirst("img")?.attr("alt")
                        ?: a.text()).trim().ifBlank { return@forEach }
                    // Priority: actual img src from page > ID-built URL
                    val imgSrc = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                    val poster = imgSrc ?: extractShowId(href)?.let { posterForShow(it) }
                    val absHref = absoluteUrl(href)
                    items.add(
                        newAnimeSearchResponse(title, absHref, TvType.TvSeries) {
                            posterUrl = poster
                        }
                    )
                }
            }

            key == "index_movies" -> {
                // New Movies section from homepage
                val doc = app.get("$mainUrl/index.php", headers = buildHeaders()).document
                doc.select("a[href*=-movies-streaming]").forEach { a ->
                    val href = a.attr("href")
                    val title = (a.attr("title").ifBlank { null }
                        ?: a.selectFirst("img")?.attr("alt")
                        ?: a.text()).trim().ifBlank { return@forEach }
                    // Priority: actual img src from page > ID-built URL
                    val imgSrc = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                    val poster = imgSrc ?: extractShowId(href)?.let { posterForMovie(it) }
                    val absHref = absoluteUrl(href)
                    items.add(
                        newAnimeSearchResponse(title, absHref, TvType.Movie) {
                            posterUrl = poster
                        }
                    )
                }
            }

            key == "cartoon.php" -> {
                // Full cartoon/series listing with pagination (55 pages, ~21 per page)
                val url = if (page == 1) "$mainUrl/cartoon.php" else "$mainUrl/cartoon.php?next=$page"
                val doc = app.get(url, headers = buildHeaders()).document
                doc.select("a[href*=-anime-streaming]").forEach { a ->
                    val href = a.attr("href")
                    val title = (a.attr("title").ifBlank { null }
                        ?: a.selectFirst("img")?.attr("alt")
                        ?: a.text()).trim().ifBlank { return@forEach }
                    // Priority: actual img src from page > ID-built URL
                    val imgSrc = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                    val poster = imgSrc ?: extractShowId(href)?.let { posterForShow(it) }
                    val absHref = absoluteUrl(href)
                    items.add(
                        newAnimeSearchResponse(title, absHref, TvType.TvSeries) {
                            posterUrl = poster
                        }
                    )
                }
            }

            key == "movies.php" -> {
                // All Movies listing with pagination
                val url = if (page == 1) "$mainUrl/movies.php" else "$mainUrl/movies.php?next=$page"
                val doc = app.get(url, headers = buildHeaders()).document
                doc.select("a[href*=-movies-streaming]").forEach { a ->
                    val href = a.attr("href")
                    val title = (a.attr("title").ifBlank { null }
                        ?: a.selectFirst("img")?.attr("alt")
                        ?: a.text()).trim().ifBlank { return@forEach }
                    // Priority: actual img src from page > ID-built URL
                    val imgSrc = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                    val poster = imgSrc ?: extractShowId(href)?.let { posterForMovie(it) }
                    val absHref = absoluteUrl(href)
                    items.add(
                        newAnimeSearchResponse(title, absHref, TvType.Movie) {
                            posterUrl = poster
                        }
                    )
                }
            }
        }

        val maxPage = when (key) {
            "cartoon.php" -> 55
            "movies.php"  -> 30
            else          -> 1
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && page < maxPage)
    }

    // ─── search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        val results = mutableListOf<SearchResponse>()

        return try {
            val raw = app.get(
                "$mainUrl/search_results.php",
                params = mapOf("q" to q, "ajax" to "1"),
                headers = buildHeaders()
            ).text

            // Response is JSON: { "results": { "series": [...], "movies": [...] } }
            val root = JSONObject(raw)
            val bucket = root.optJSONObject("results") ?: JSONObject(raw)

            // Series
            val series = bucket.optJSONArray("series")
            if (series != null) {
                for (i in 0 until series.length()) {
                    val item = series.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim().ifBlank { continue }
                    val title = item.optString("title").trim().ifBlank { continue }
                    val slug = item.optString("slug", "serie").trim()
                    val href = "$mainUrl/$slug-$id-anime-streaming.html"
                    results.add(
                        newAnimeSearchResponse(title, href, TvType.TvSeries) {
                            posterUrl = posterForShow(id)
                        }
                    )
                }
            }

            // Movies
            val movies = bucket.optJSONArray("movies")
            if (movies != null) {
                for (i in 0 until movies.length()) {
                    val item = movies.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim().ifBlank { continue }
                    val title = item.optString("title").trim().ifBlank { continue }
                    val slug = item.optString("slug", "film").trim()
                    val href = "$mainUrl/$slug-$id-movies-streaming.html"
                    results.add(
                        newAnimeSearchResponse(title, href, TvType.Movie) {
                            posterUrl = posterForMovie(id)
                        }
                    )
                }
            }

            // Fallback: if JSON didn't parse as expected, try HTML search page
            if (results.isEmpty()) {
                val doc = app.get(
                    "$mainUrl/search_results.php",
                    params = mapOf("q" to q),
                    headers = buildHeaders()
                ).document
                doc.select("a[href*=-anime-streaming]").forEach { a ->
                    val href = a.attr("href")
                    val title = (a.attr("title").ifBlank { null } ?: a.text()).trim().ifBlank { return@forEach }
                    results.add(buildSearchResponse(title, href, isMovie = false))
                }
                doc.select("a[href*=-movies-streaming]").forEach { a ->
                    val href = a.attr("href")
                    val title = (a.attr("title").ifBlank { null } ?: a.text()).trim().ifBlank { return@forEach }
                    results.add(buildSearchResponse(title, href, isMovie = true))
                }
            }

            results
        } catch (t: Throwable) {
            Log.e("ArabicToons", "search failed: ${t.message}")
            emptyList()
        }
    }

    // ─── load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val isMovie = url.contains("-movies-streaming")
            val doc = app.get(url, headers = buildHeaders()).document

            val pageTitle = doc.title().trim()
            val h1 = doc.selectFirst("h1")?.text()?.trim()
            val title = (h1?.ifBlank { null } ?: pageTitle.substringBefore(" - ").trim()).ifBlank { "ArabicToons" }

            // Extract show ID from URL
            val showId = if (isMovie) {
                Regex("""-(\d{4,12})-movies-streaming""").find(url)?.groupValues?.getOrNull(1)
            } else {
                Regex("""-(\d{4,12})-anime-streaming""").find(url)?.groupValues?.getOrNull(1)
            }

            val poster = showId?.let { if (isMovie) posterForMovie(it) else posterForShow(it) }

            // Description
            val plot = doc.selectFirst("p, .description, .story, .plot, [class*=desc], [class*=story]")
                ?.text()?.trim()?.ifBlank { null }

            if (isMovie) {
                // For movies, look for a direct episode link or just use the page URL
                // The movie may have a single episode link to the actual watch page
                val epLink = doc.select("a[href]").firstOrNull { a ->
                    val h = a.attr("href")
                    h.contains(".html") && !h.contains("streaming") && extractEpisodeId(h) != null
                }

                val dataUrl = if (epLink != null) absoluteUrl(epLink.attr("href")) else url
                newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // TV Series: collect all episode links
                val seenEpIds = mutableSetOf<String>()
                val episodes = mutableListOf<Episode>()

                doc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (!href.contains(".html") || href.contains("streaming")) return@forEach
                    val epId = extractEpisodeId(href) ?: return@forEach
                    if (!seenEpIds.add(epId)) return@forEach

                    // Try to get episode number from title text or href
                    val rawText = a.text().trim().ifBlank { a.attr("title").trim() }
                    val epNum = Regex("""(\d+)""").findAll(rawText).lastOrNull()?.value?.toIntOrNull()
                        ?: Regex("""-(\d{4,8})\.html""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: seenEpIds.size

                    val epName = rawText.ifBlank { "الحلقة $epNum" }
                    val thumbnail = thumbForEpisode(epId)

                    episodes.add(newEpisode(absoluteUrl(href)) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = thumbnail
                    })
                }

                if (episodes.isEmpty()) return null

                // Sort episodes by episode number
                val sorted = episodes.sortedBy { it.episode ?: Int.MAX_VALUE }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        } catch (t: Throwable) {
            Log.e("ArabicToons", "load($url) failed: ${t.message}")
            null
        }
    }

    // ─── loadLinks ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ArabicToons", "loadLinks: $data")
        return try {
            val doc = app.get(data, headers = buildHeaders()).document
            val html = doc.html()
            var found = false

            // 1) Try <video src="..."> or <source src="...">
            val videoEls = doc.select("video[src], source[src]")
            for (el in videoEls) {
                val src = el.attr("src").trim()
                if (src.startsWith("http") && (src.contains(".mp4") || src.contains(".m3u8"))) {
                    val isM3u8 = src.contains(".m3u8")
                    Log.d("ArabicToons", "video/source tag: $src")
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name ${if (isM3u8) "HLS" else "MP4"}",
                            url = src,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = isM3u8
                        )
                    )
                    found = true
                }
            }

            // 2) Regex scan page HTML for foupix/stream MP4 or m3u8 links
            if (!found) {
                val mp4Regex = Regex("""https://[^"'\s]+\.(?:mp4|m3u8)(?:\?[^"'\s]*)?""")
                mp4Regex.findAll(html).forEach { m ->
                    val src = m.value
                    if (src.contains("foupix") || src.contains("stream") || src.contains("anime")) {
                        val isM3u8 = src.contains(".m3u8")
                        Log.d("ArabicToons", "regex match: $src")
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "$name ${if (isM3u8) "HLS" else "Stream"}",
                                url = src,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = isM3u8
                            )
                        )
                        found = true
                    }
                }
            }

            // 3) Try to find iframe src that might be a video host
            if (!found) {
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").trim()
                    if (src.startsWith("http") && src.isNotBlank()) {
                        Log.d("ArabicToons", "iframe: $src")
                        // Recurse into iframe
                        try {
                            val iDoc = app.get(src, headers = buildHeaders()).document
                            val iHtml = iDoc.html()
                            val mp4In = Regex("""https://[^"'\s]+\.(?:mp4|m3u8)(?:\?[^"'\s]*)?""")
                            mp4In.findAll(iHtml).forEach { mm ->
                                val isM3u8 = mm.value.contains(".m3u8")
                                callback(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name Iframe",
                                        url = mm.value,
                                        referer = src,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = isM3u8
                                    )
                                )
                                found = true
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }

            if (!found) Log.w("ArabicToons", "No link found for: $data")
            found
        } catch (t: Throwable) {
            Log.e("ArabicToons", "loadLinks($data) failed: ${t.message}")
            false
        }
    }
}
