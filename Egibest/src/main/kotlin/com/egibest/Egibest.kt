@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.egibest

import android.annotation.TargetApi
import android.os.Build
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class Egibest : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egibest.org"
    override var name = "EgyBest"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private fun String.absUrlOrJoin(base: String): String {
        return if (this.startsWith("http")) this
        else if (this.startsWith("/")) "$base$this"
        else "$base/$this"
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun String.getYearFromTitle(): Int? {
        return Regex("""\(\d{4}\)""").find(this)?.groupValues?.firstOrNull()?.replace("(", "")?.replace(")", "")?.toIntOrNull()
    }

    // Card anchor = a.postBlockCol (trending/category) or a.postBlock (home)
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href") }.ifBlank { return null }
        if (href.contains("/category/") || href.contains("/genre/") || href.contains("/tag/")) return null
        val img = selectFirst("img")
        val poster = img?.attr("src")?.trim()?.ifBlank { null }
        var title = selectFirst("h3.title, span.title, .title")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: attr("title").trim().ifBlank { return null }
        if (title.isBlank()) return null
        val year = title.getYearFromTitle()
        title = title.split(" (")[0].trim()
        val quality = selectFirst("span.ribbon span, .quality")?.text()?.replace("-", "")
        val isMovie = Regex(".*/movie/.*|.*/film/.*|.*/masrahiya/.*").containsMatchIn(href)
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                this.quality = getQualityFromString(quality)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trending/?page=" to "الأكثر مشاهدة",
        "$mainUrl/movies/?page=" to "أفلام جديدة",
        "$mainUrl/tv/?page=" to "مسلسلات جديدة",
        "$mainUrl/tv/korean?page=" to "الدراما الكورية",
        "$mainUrl/animes/popular?page=" to "مسلسلات الانمي",
        "$mainUrl/movies/latest?page=" to "أحدث الإضافات",
        "$mainUrl/movies/animation?page=" to "أفلام انمي وكرتون",
        "$mainUrl/movies/romance?page=" to "أفلام رومانسية",
        "$mainUrl/movies/drama?page=" to "أفلام دراما",
        "$mainUrl/movies/horror?page=" to "أفلام رعب",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url).document
        val items = doc.select("a.postBlockCol, a.postBlock")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return doc.select("a.postBlockCol, a.postBlock")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val isMovie = Regex(".*/movie/.*|.*/film/.*|.*/masrahiya/.*").containsMatchIn(url)

        val title = doc.selectFirst("h1.postTitle, h1.title, .movie_title h1 span, .postTitle")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""

        val poster = doc.selectFirst("div.postCover img, .movie_img img, meta[property=og:image]")
            ?.let { el ->
                if (el.hasAttr("content")) el.attr("content")
                else el.attr("src").ifBlank { el.attr("data-src") }
            }

        val plot = doc.selectFirst(".postDesc, .storyLine, .entry-content p, .postContent p")
            ?.text()?.trim()

        val year = doc.selectFirst(".postYear, .year")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("(19|20)\\d{2}").find(doc.selectFirst(".postInfo, .postMeta")?.text() ?: "")?.value?.toIntOrNull()

        val tags = doc.select(".postGenres a, .genres a, a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        val trailer = doc.selectFirst("a.trailer, a[href*=youtube.com/watch]")?.attr("href")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addTrailer(trailer)
            }
        }

        // Series - collect episodes
        val episodes = mutableListOf<Episode>()

        // Season tabs like: div.seasonEpisodes or li.seasonTab
        val seasonLinks = doc.select("ul.seasonList a, .seasons-list a, #seasons .tab-pane a[href]")
        val targetPages = mutableListOf<Pair<String, Int?>>()

        if (seasonLinks.isEmpty()) {
            // Single season page, collect episodes directly
            targetPages.add(url to null)
        } else {
            seasonLinks.forEach { a ->
                val seasonHref = a.absUrl("href").ifBlank { a.attr("href").absUrlOrJoin(mainUrl) }
                val seasonNum = Regex("\\d+").find(a.text())?.value?.toIntOrNull()
                targetPages.add(seasonHref to seasonNum)
            }
        }

        for ((pageUrl, seasonNum) in targetPages) {
            val d = if (pageUrl == url) doc else app.get(pageUrl).document
            d.select("a.episodeBlock, tr.published .ep_title a, .eps a, .all-episodes a[href]").forEach { ep ->
                val epHref = ep.absUrl("href").ifBlank { ep.attr("href").absUrlOrJoin(mainUrl) }
                if (epHref.isBlank()) return@forEach
                val epName = ep.text().trim().ifBlank { ep.attr("title").trim() }
                val epNum = Regex("\\d+").find(epName)?.value?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    name = epName
                    episode = epNum
                    season = seasonNum
                })
            }
        }

        // Fallback - single episode just load the page as episode
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { name = title })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        // Collect all server buttons / iframes / data-embed attributes
        val candidates = mutableSetOf<String>()

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank() && !src.contains("disqus")) candidates.add(src)
        }

        doc.select("[data-src], [data-embed], [data-url]").forEach { el ->
            listOf("data-src", "data-embed", "data-url").forEach { attr ->
                val v = el.attr(attr).trim()
                if (v.isNotBlank()) candidates.add(v.absUrlOrJoin(mainUrl))
            }
        }

        // Scan scripts for embedded URLs
        val scriptContent = doc.select("script").joinToString("\n") { it.data() }
        Regex("""https?://[^\s"'<>]+\.(m3u8|mp4)[^\s"'<>]*""").findAll(scriptContent).forEach {
            candidates.add(it.value)
        }
        Regex("""https?://[^\s"'<>]*(embed|player|stream|watch)[^\s"'<>]*""").findAll(scriptContent).forEach {
            candidates.add(it.value)
        }

        for (candidate in candidates.distinct()) {
            val embedUrl = if (candidate.startsWith("//")) "https:$candidate" else candidate
            try {
                if (embedUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, embedUrl, referer = data).forEach { callback(it); found = true }
                } else {
                    if (loadExtractor(embedUrl, referer = data, subtitleCallback, callback)) found = true
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return found
    }
}
