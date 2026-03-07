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
    data class TmdbItem(val id: Int?, val poster_path: String?, val backdrop_path: String?, val overview: String?, val title: String?, val name: String?)
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

    /** Extract English part of a mixed Arabic/English title */
    private fun extractEnglish(raw: String): String? {
        val cleaned = raw
            .replace(Regex("^(مشاهدة|فيلم|مسلسل|برنامج|انمي|أنمي|كرتون)\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+(مترجم|مدبلج|اون لاين|اونلاين|حصرى|كامل|مترجمة|مدبلجة|صدر عام).*$"), "")
            .trim()
        val eng = Regex("[A-Za-z][A-Za-z0-9':&.,! -]+").findAll(cleaned)
            .joinToString(" ") { it.value.trim() }.trim()
        return eng.ifBlank { null }
    }

    private fun extractYear(s: String): Int? = Regex("(19|20)\\d{2}").find(s)?.value?.toIntOrNull()

    /** Quick TMDB poster lookup — just gets poster URL, nothing else */
    private suspend fun tmdbPoster(title: String, year: Int?, isMovie: Boolean): String? {
        return runCatching {
            val type = if (isMovie) "movie" else "tv"
            val q = java.net.URLEncoder.encode(title, "UTF-8")
            val yp = if (year != null) "&year=$year" else ""
            val url = "$TMDB_API/search/$type?api_key=$TMDB_KEY&query=$q$yp"
            val resp = app.get(url).text
            val json = parseJson<TmdbSearchResult>(resp)
            json.results?.firstOrNull()?.poster_path?.let { "$TMDB_IMG$it" }
        }.getOrNull()
    }

    /** Full TMDB data for detail page */
    private suspend fun tmdbFull(title: String, year: Int?, isMovie: Boolean): TmdbFullData? {
        return runCatching {
            val type = if (isMovie) "movie" else "tv"
            val q = java.net.URLEncoder.encode(title, "UTF-8")
            val yp = if (year != null) "&year=$year" else ""
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

    // ── Card parsing with TMDB poster ─────────────────────────────────────────────

    private suspend fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }
        if (href.contains("#") || href.contains("javascript")) return null

        // Title from a[title] attribute (clean, full title)
        val rawTitle = attr("title").trim().ifBlank {
            selectFirst("div.inner--title h2, h2, .title, h3")?.text()?.trim() ?: ""
        }.ifBlank { return null }

        val isMovie = isMovieUrl(href)
        val year = extractYear(rawTitle)

        // Get TMDB poster (since site images return 403 to external loaders)
        val engTitle = extractEnglish(rawTitle)
        val tmdbPosterUrl = if (engTitle != null) tmdbPoster(engTitle, year, isMovie) else null

        // Fallback to site poster (might work for some items)
        val sitePoster = selectFirst("div.Poster img, img")?.attr("src")?.trim()
            ?.let { if (it.startsWith("http")) it else null }

        val poster = tmdbPosterUrl ?: sitePoster

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
            if (items.none { it.url == item.url }) items.add(item)
            if (items.size >= 15) break // Limit to keep TMDB calls reasonable
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
            if (items.none { it.url == item.url }) items.add(item)
            if (items.size >= 20) break
        }
        return items
    }

    // ── Load ───────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1, .entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: ""
        val isMovie = isMovieUrl(url)
        val year = extractYear(rawTitle)
        val tags = doc.select("a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }
        val sitePlot = doc.selectFirst(".entry-content p, .story-content, .description")?.text()?.trim()

        // TMDB enrichment
        val engTitle = extractEnglish(rawTitle)
        val tmdb = if (engTitle != null) tmdbFull(engTitle, year, isMovie) else null

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

        // Series episodes
        val episodes = mutableListOf<Episode>()
        doc.select("a.recent--block, a[href]").filter { a ->
            val t = a.attr("title").trim() + a.text().trim()
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
