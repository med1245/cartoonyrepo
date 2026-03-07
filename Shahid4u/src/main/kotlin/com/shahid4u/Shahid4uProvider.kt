@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import android.util.Base64 as AndroidBase64

class Shahid4uProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://shahid4u.casa"
    override var name = "Shahid4u"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    companion object {
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "8d6d91c2856440b9907db47ee286e7d2" // Free community key
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private fun String.toAbs(): String =
        if (startsWith("http")) this
        else if (startsWith("/")) "$mainUrl$this"
        else "$mainUrl/$this"

    private fun isMovieUrl(href: String): Boolean {
        val slug = href.lowercase()
        return slug.contains("%d9%81%d9%8a%d9%84%d9%85") || slug.contains("فيلم")
    }

    private fun tryDecodeBase64(input: String): String? = runCatching {
        val clean = input.trimEnd('/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        String(AndroidBase64.decode(padded, AndroidBase64.DEFAULT)).trim()
    }.getOrNull()

    private fun resolveGovidUrl(govidPlayUrl: String): List<String> {
        val results = mutableListOf<String>()
        val b64 = govidPlayUrl.substringAfter("/play/").trimEnd('/').split("?").first()
        val decoded = tryDecodeBase64(b64) ?: return results
        if (decoded.startsWith("http")) {
            results.add(decoded)
            if (decoded.contains("govid.live/e/")) results.add(govidPlayUrl)
        }
        return results
    }

    /** Extract English title from mixed Arabic/English title for TMDB lookup */
    private fun extractEnglishTitle(raw: String): String? {
        // Remove Arabic prefixes like "فيلم", "مسلسل", etc.
        val cleaned = raw
            .replace(Regex("^(فيلم|مسلسل|مشاهدة|برنامج|انمي|أنمي|كرتون)\\s+"), "")
            .replace(Regex("\\s+(مترجم|مدبلج|اون لاين|اونلاين|حصرى|كامل|مترجمة|مدبلجة).*$"), "")
            .trim()
        // Try to extract just English words
        val english = Regex("[A-Za-z][A-Za-z0-9':& .-]+").findAll(cleaned)
            .joinToString(" ") { it.value.trim() }.trim()
        return english.ifBlank { null }
    }

    /** Extract year from title */
    private fun extractYear(title: String): Int? =
        Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()

    /** Fetch TMDB poster and trailer for a title */
    private suspend fun fetchTmdbData(title: String, year: Int?, isMovie: Boolean): TmdbData? {
        val type = if (isMovie) "movie" else "tv"
        val yearParam = if (year != null) "&year=$year" else ""
        val query = java.net.URLEncoder.encode(title, "UTF-8")
        return runCatching {
            val searchUrl = "$TMDB_API/search/$type?api_key=$TMDB_KEY&query=$query$yearParam&language=ar"
            val response = app.get(searchUrl).text
            val json = parseJson<TmdbSearchResult>(response)
            val item = json.results?.firstOrNull() ?: return null
            val tmdbId = item.id ?: return null

            // Get poster
            val posterPath = item.poster_path
            val poster = if (posterPath != null) "$TMDB_IMG$posterPath" else null
            val backdrop = item.backdrop_path?.let { "$TMDB_IMG$it" }
            val overview = item.overview

            // Get trailer
            var trailerUrl: String? = null
            runCatching {
                val videosUrl = "$TMDB_API/$type/$tmdbId/videos?api_key=$TMDB_KEY"
                val videosResp = app.get(videosUrl).text
                val videos = parseJson<TmdbVideosResult>(videosResp)
                val trailer = videos.results?.firstOrNull { v ->
                    v.site == "YouTube" && (v.type == "Trailer" || v.type == "Teaser")
                }
                if (trailer != null) trailerUrl = "https://www.youtube.com/watch?v=${trailer.key}"
            }

            TmdbData(poster, backdrop, overview, trailerUrl)
        }.getOrNull()
    }

    data class TmdbSearchResult(val results: List<TmdbItem>?)
    data class TmdbItem(
        val id: Int?,
        val poster_path: String?,
        val backdrop_path: String?,
        val overview: String?
    )
    data class TmdbVideosResult(val results: List<TmdbVideo>?)
    data class TmdbVideo(val key: String?, val site: String?, val type: String?)
    data class TmdbData(val poster: String?, val backdrop: String?, val overview: String?, val trailerUrl: String?)

    // ── Card parsing ───────────────────────────────────────────────────────────────

    private fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }
        if (href.contains("#") || href.contains("javascript")) return null

        // Title: prefer the 'title' attribute on the <a> tag (contains clean title)
        val rawTitle = attr("title").trim().ifBlank {
            selectFirst("div.inner--title h2, h2, .title, h3")?.text()?.trim() ?: ""
        }.ifBlank { return null }

        // Poster: from div.Poster img
        val poster = selectFirst("div.Poster img, .Poster img, img")
            ?.attr("src")?.trim()
            ?.let { if (it.startsWith("http") && !it.contains("logo")) it else null }

        return if (isMovieUrl(href))
            newMovieSearchResponse(rawTitle, href, TvType.Movie) { posterUrl = poster }
        else
            newTvSeriesSearchResponse(rawTitle, href, TvType.TvSeries) { posterUrl = poster }
    }

    // ── Main page ──────────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/movies/?page=" to "افلام شاهد فور يو",
        "$mainUrl/series/?page=" to "مسلسلات شاهد فور يو",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/?page=" to "افلام اجنبي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%b9%d8%b1%d8%a8%d9%8a/?page=" to "افلام عربي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%87%d9%86%d8%af%d9%8a/?page=" to "افلام هندي",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/?page=" to "افلام انمي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/?page=" to "مسلسلات اجنبي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a/?page=" to "مسلسلات عربي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/?page=" to "مسلسلات تركية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%87%d9%86%d8%af%d9%8a%d8%a9/?page=" to "مسلسلات هندية",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/?page=" to "مسلسلات انمي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/?page=" to "مسلسلات اسيوية",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.select("a.recent--block")
            .mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ─────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return doc.select("a.recent--block")
            .mapNotNull { it.toCard() }.distinctBy { it.url }
    }

    // ── Load ───────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1, .entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: ""

        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.Poster img, .thumb img, article img")?.attr("src")

        val sitePlot = doc.selectFirst(".entry-content p, .story-content, .description")?.text()?.trim()
        val year = extractYear(rawTitle)
        val tags = doc.select("a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }
        val isMovie = isMovieUrl(url)

        // Enrich with TMDB data (poster, trailer, plot)
        val englishTitle = extractEnglishTitle(rawTitle)
        val tmdb = if (englishTitle != null) fetchTmdbData(englishTitle, year, isMovie) else null

        val poster = tmdb?.poster ?: sitePoster
        val backdrop = tmdb?.backdrop
        val plot = tmdb?.overview ?: sitePlot
        val trailerUrl = tmdb?.trailerUrl

        if (isMovie) {
            return newMovieLoadResponse(rawTitle, url, TvType.Movie, url) {
                posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year; this.plot = plot; this.tags = tags
                addTrailer(trailerUrl)
            }
        }

        // Series: collect episodes
        val episodes = mutableListOf<Episode>()
        doc.select("a[href]").filter { a ->
            val t = a.text().trim()
            val h = a.attr("href")
            (t.contains("الحلقة") || t.contains("حلقة") || h.contains("الحلقة"))
                && a.absUrl("href").startsWith(mainUrl) && a.absUrl("href") != url
        }.forEach { ep ->
            val epHref = ep.absUrl("href")
            val epText = ep.attr("title").trim().ifBlank { ep.text().trim() }
            val epNum = Regex("(\\d+)").findAll(epText).lastOrNull()?.value?.toIntOrNull()
            episodes.add(newEpisode(epHref) {
                name = epText.ifBlank { "الحلقة $epNum" }
                episode = epNum
            })
        }
        if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = rawTitle })

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedBy { it.episode }) {
            posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year; this.plot = plot; this.tags = tags
            addTrailer(trailerUrl)
        }
    }

    // ── LoadLinks ──────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.endsWith("/watch/") || data.endsWith("/watch")) data
                       else data.trimEnd('/') + "/watch/"
        val doc = app.get(watchUrl).document
        var found = false
        val embedUrls = mutableSetOf<String>()

        // PRIMARY: Server list from ul#watch li[data-watch]
        doc.select("ul#watch li[data-watch], .servers li[data-watch]").forEach { li ->
            val govidUrl = li.attr("data-watch").trim()
            if (govidUrl.contains("govid.live/play/")) {
                embedUrls.addAll(resolveGovidUrl(govidUrl))
            } else if (govidUrl.startsWith("http")) {
                embedUrls.add(govidUrl)
            }
        }

        // SECONDARY: iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            if (src.contains("govid.live/play/")) {
                embedUrls.addAll(resolveGovidUrl(src))
            } else if (src.startsWith("http") && !src.contains("disqus")) {
                embedUrls.add(src)
            }
        }

        // TERTIARY: Scripts
        val govidRegex = Regex("""govid\.live/play/([A-Za-z0-9+/=]+)""")
        doc.select("script").forEach { script ->
            val html = script.html()
            govidRegex.findAll(html).forEach { m ->
                embedUrls.addAll(resolveGovidUrl("https://govid.live/play/${m.groupValues[1]}"))
            }
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(html)
                .forEach { embedUrls.add(it.groupValues[1]) }
        }

        // Resolve each embed
        for (embedUrl in embedUrls.distinct()) {
            if (embedUrl.isBlank()) continue
            try {
                when {
                    embedUrl.contains(".m3u8") -> {
                        M3u8Helper.generateM3u8(name, embedUrl, referer = watchUrl)
                            .forEach { callback(it); found = true }
                    }
                    embedUrl.contains("govid.live/e/") -> {
                        runCatching {
                            val resolved = WebViewResolver(
                                interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                            ).resolveUsingWebView(requestCreator("GET", embedUrl, referer = watchUrl)).first
                            val videoUrl = resolved?.url?.toString()
                            if (!videoUrl.isNullOrBlank()) {
                                if (videoUrl.contains(".m3u8")) {
                                    M3u8Helper.generateM3u8(name, videoUrl, referer = embedUrl)
                                        .forEach { callback(it); found = true }
                                } else {
                                    callback(ExtractorLink(name, "$name - Govid", videoUrl, embedUrl, Qualities.Unknown.value, false))
                                    found = true
                                }
                            }
                        }
                    }
                    else -> {
                        if (loadExtractor(embedUrl, referer = watchUrl, subtitleCallback, callback)) found = true
                    }
                }
            } catch (e: Exception) { /* skip */ }
        }

        // Last resort WebView
        if (!found) {
            runCatching {
                val resolved = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                ).resolveUsingWebView(requestCreator("GET", watchUrl, referer = mainUrl)).first
                val videoUrl = resolved?.url?.toString()
                if (!videoUrl.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(name, videoUrl, referer = watchUrl)
                        .forEach { callback(it); found = true }
                }
            }
        }

        return found
    }
}
