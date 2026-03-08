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
        private const val TMDB_KEY = "8d6d91c2856440b9907db47ee286e7d2"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_IMG_ORIG = "https://image.tmdb.org/t/p/original"
    }

    // ── TMDB data classes ─────────────────────────────────────────────────────────
    data class TmdbSearchResult(val results: List<TmdbItem>?)
    data class TmdbItem(val id: Int?, val poster_path: String?, val backdrop_path: String?, val overview: String?, val title: String?, val name: String?, val first_air_date: String?, val release_date: String?)
    data class TmdbVideosResult(val results: List<TmdbVideo>?)
    data class TmdbVideo(val key: String?, val site: String?, val type: String?)

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun String.toAbs(): String =
        if (startsWith("http")) this
        else if (startsWith("/")) "$mainUrl$this"
        else "$mainUrl/$this"

    private fun isMovieUrl(href: String): Boolean {
        val s = href.lowercase()
        return s.contains("%d9%81%d9%8a%d9%84%d9%85") || s.contains("فيلم")
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

    /** Extract English title for TMDB search from mixed Arabic/English title */
    private fun extractEnglish(raw: String): String? {
        val cleaned = raw
            .replace(Regex("^(مشاهدة|فيلم|مسلسل|برنامج|انمي|أنمي|كرتون)\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("(الحلقة|حلقة|الموسم|موسم)\\s+\\d+.*$"), "") // Strip episode/season info
            .replace(Regex("\\s+(مترجم|مدبلج|اون لاين|اونلاين|حصرى|كامل|مترجمة|مدبلجة|صدر عام).*$"), "")
            .trim()
        val eng = Regex("[A-Za-z][A-Za-z0-9':&.,! -]+").findAll(cleaned)
            .joinToString(" ") { it.value.trim() }.trim()
        return eng.ifBlank { null }
    }

    private fun extractYear(s: String): Int? = Regex("(19|20)\\d{2}").find(s)?.value?.toIntOrNull()

    /** Lookup TMDB poster, optionally by English or Arabic title */
    private suspend fun tmdbPoster(title: String, year: Int?, isMovie: Boolean, isArabic: Boolean = false): String? {
        return runCatching {
            val type = if (isMovie) "movie" else "tv"
            val q = java.net.URLEncoder.encode(title, "UTF-8")
            val yp = if (year != null) "&year=$year" else ""
            val lang = if (isArabic) "&language=ar" else ""
            val url = "$TMDB_API/search/$type?api_key=$TMDB_KEY&query=$q$yp$lang"
            val resp = app.get(url).text
            val json = parseJson<TmdbSearchResult>(resp)
            json.results?.firstOrNull()?.poster_path?.let { "$TMDB_IMG$it" }
        }.getOrNull()
    }

    /** Comprehensive TMDB data lookup (poster, backdrop, plot, trailer) */
    private suspend fun tmdbFull(title: String, year: Int?, isMovie: Boolean): TmdbFullData? {
        return runCatching {
            val type = if (isMovie) "movie" else "tv"
            val q = java.net.URLEncoder.encode(title, "UTF-8")
            val yp = if (year != null) "&year=$year" else ""
            // First search with Arabic to prioritize localized results
            val url = "$TMDB_API/search/$type?api_key=$TMDB_KEY&query=$q$yp&language=ar"
            val resp = app.get(url).text
            val json = parseJson<TmdbSearchResult>(resp)
            val item = json.results?.firstOrNull() ?: return null
            val tmdbId = item.id ?: return null
            val poster = item.poster_path?.let { "$TMDB_IMG$it" }
            val backdrop = item.backdrop_path?.let { "$TMDB_IMG_ORIG$it" }
            val overview = item.overview

            var trailerUrl: String? = null
            runCatching {
                val vResp = app.get("$TMDB_API/$type/$tmdbId/videos?api_key=$TMDB_KEY").text
                val videos = parseJson<TmdbVideosResult>(vResp)
                val trailer = videos.results?.firstOrNull { v ->
                    v.site == "YouTube" && (v.type == "Trailer" || v.type == "Teaser")
                }
                if (trailer != null) trailerUrl = "https://www.youtube.com/watch?v=${trailer.key}"
            }
            TmdbFullData(poster, backdrop, overview, trailerUrl)
        }.getOrNull()
    }

    data class TmdbFullData(val poster: String?, val backdrop: String?, val overview: String?, val trailer: String?)

    // ── Card parsing ─────────────────────────────────────────────────────────────

    private suspend fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }
        if (href.contains("#") || href.contains("javascript")) return null

        // Improved title extraction from 'title' attribute
        val rawTitle = attr("title").trim().ifBlank {
            selectFirst("div.inner--title h2, h2, .title, h3")?.text()?.trim() ?: ""
        }.replace("- shahid4u", "", ignoreCase = true).trim()
        if (rawTitle.isEmpty() || rawTitle.lowercase() == "shahid4u") return null

        val isMovie = isMovieUrl(href)
        val year = extractYear(rawTitle)

        // Try English first, then Arabic as fallback for MTDB matching
        val engTitle = extractEnglish(rawTitle)
        var poster = if (engTitle != null) tmdbPoster(engTitle, year, isMovie) else null
        if (poster == null) {
            val cleanAr = rawTitle.replace(Regex("^(مشاهدة|فيلم|مسلسل|برنامج|انمي|أنمي|كرتون)\\s+"), "").trim()
            poster = tmdbPoster(cleanAr, year, isMovie, isArabic = true)
        }

        // Final site poster fallback
        if (poster == null) {
            poster = selectFirst("div.Poster img, img")?.attr("src")?.trim()
                ?.let { if (it.startsWith("http")) it else null }
        }

        return if (isMovie)
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
        val cards = doc.select("a.recent--block")
        val items = mutableListOf<SearchResponse>()
        for (card in cards) {
            val item = card.toCard() ?: continue
            if (items.none { it.url == item.url }) {
                items.add(item)
                if (items.size >= 12) break // Reasonable balance between cards and TMDB hits
            }
        }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ─────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        val cards = doc.select("a.recent--block")
        val items = mutableListOf<SearchResponse>()
        for (card in cards) {
            val item = card.toCard() ?: continue
            if (items.none { it.url == item.url }) {
                items.add(item)
                if (items.size >= 24) break
            }
        }
        return items
    }

    // ── Load ───────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Accurate title from meta or breadcrumb
        val rawTitle = doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?.replace("- shahid4u", "", ignoreCase = true)?.trim()
            ?: doc.select(".breadcrumb a").lastOrNull()?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: ""
        
        if (rawTitle.isEmpty() || rawTitle.lowercase() == "shahid4u") return null

        val isMovie = isMovieUrl(url)
        val year = extractYear(rawTitle)
        val tags = doc.select("a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }
        val sitePlot = doc.selectFirst(".entry-content p, .story-content, .description")?.text()?.trim()

        // Improved TMDB matching
        val engTitle = extractEnglish(rawTitle)
        val tmdb = if (engTitle != null) {
            tmdbFull(engTitle, year, isMovie)
        } else {
            val cleanAr = rawTitle.replace(Regex("^(مشاهدة|فيلم|مسلسل|برنامج|انمي|أنمي|كرتون)\\s+"), "").trim()
            tmdbFull(cleanAr, year, isMovie)
        }

        val poster = tmdb?.poster
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val backdrop = tmdb?.backdrop
        val plot = tmdb?.overview ?: sitePlot
        val trailerUrl = tmdb?.trailer

        if (isMovie) {
            return newMovieLoadResponse(rawTitle, url, TvType.Movie, url) {
                posterUrl = poster; backgroundPosterUrl = backdrop
                this.year = year; this.plot = plot; this.tags = tags
                addTrailer(trailerUrl)
            }
        }

        // Series episodes - target "جميع الحلقات" section to avoid messy related content
        val episodes = mutableListOf<Episode>()
        val episodeContainers = doc.select("div.MediaGrid, .slides--single, div.container")
            .filter { it.select("h3, h2, h4").any { h -> h.text().contains("جميع الحلقات") || h.text().contains("حلقات") } }
        
        val cards = if (episodeContainers.isNotEmpty()) {
            episodeContainers.flatMap { it.select("a.recent--block") }
        } else {
            // Fallback: search results on page that look like episodes
            doc.select("a.recent--block").filter { it.text().contains("الحلقة") }
        }

        cards.forEach { ep ->
            val epHref = ep.absUrl("href")
            val epText = ep.attr("title").trim().ifBlank { ep.text().trim() }
            val epNum = Regex("(\\d+)").findAll(epText).lastOrNull()?.value?.toIntOrNull()
            if (epHref.startsWith(mainUrl) && epHref != url) {
                episodes.add(newEpisode(epHref) {
                    name = epText.ifBlank { "الحلقة $epNum" }
                    episode = epNum
                })
            }
        }
        
        if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = rawTitle })

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedBy { it.episode }) {
            posterUrl = poster; backgroundPosterUrl = backdrop
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

        doc.select("ul#watch li[data-watch], .servers li[data-watch]").forEach { li ->
            val govidUrl = li.attr("data-watch").trim()
            if (govidUrl.contains("govid.live/play/")) {
                embedUrls.addAll(resolveGovidUrl(govidUrl))
            } else if (govidUrl.startsWith("http")) {
                embedUrls.add(govidUrl)
            }
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            if (src.contains("govid.live/play/")) {
                embedUrls.addAll(resolveGovidUrl(src))
            } else if (src.startsWith("http") && !src.contains("disqus")) {
                embedUrls.add(src)
            }
        }

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
