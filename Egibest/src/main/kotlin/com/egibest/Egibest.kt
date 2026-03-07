@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.egibest

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element

class Egibest : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://egibest.org"
    override var name = "EgyBest"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ── helpers ────────────────────────────────────────────────────────────────────

    private fun String.toAbsUrl(): String =
        if (startsWith("http")) this
        else if (startsWith("/")) "$mainUrl$this"
        else "$mainUrl/$this"

    /** Movie if title contains "فيلم" OR slug doesn't look like an episode/season page */
    private fun isMovieUrl(href: String, title: String): Boolean {
        val slug = href.lowercase()
        if (title.contains("فيلم") || title.contains("film", ignoreCase = true)) return true
        if (slug.contains("/movie/") || slug.contains("مشاهدة-فيلم") || slug.contains("%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85")) return true
        return false
    }

    // ── Card parsing ───────────────────────────────────────────────────────────────

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbsUrl() }.ifBlank { return null }
        val img = selectFirst("img") ?: return null
        // poster: site uses direct src, not data-src
        val poster = img.attr("src").trim().ifBlank { null }
        // title: from h3 inside the card OR alt of img
        val rawTitle = (selectFirst("h3.title, h3, span.title")?.text()?.trim()
            ?: img.attr("alt").trim())
        val title = rawTitle.ifBlank { attr("title").trim().ifBlank { return null } }
        // clean title - strip "مشاهدة فيلم/مسلسل ... مترجم ..." prefix if needed
        val cleanTitle = title
            .replace(Regex("^مشاهدة (فيلم|مسلسل|انمي|كرتون|برنامج)\\s+"), "")
            .replace(Regex("\\s+(مترجم|مترجمة|مدبلج|مدبلجة|اون لاين|اونلاين|حصرى|على أكثر من سيرفر).*$"), "")
            .trim().ifBlank { title }
        val isMovie = isMovieUrl(href, rawTitle)
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) { posterUrl = poster }
        }
    }

    // ── Main page ──────────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/trending/?page=" to "الأكثر مشاهدة",
        "$mainUrl/movies/?page=" to "أفلام جديدة",
        "$mainUrl/movies/latest?page=" to "أحدث الإضافات",
        "$mainUrl/tv/?page=" to "مسلسلات جديدة",
        "$mainUrl/animes/popular?page=" to "الانمي",
        "$mainUrl/tv/korean?page=" to "الدراما الكورية",
        "$mainUrl/movies/animation?page=" to "أفلام انمي وكرتون",
        "$mainUrl/movies/horror?page=" to "أفلام رعب",
        "$mainUrl/movies/drama?page=" to "أفلام دراما",
        "$mainUrl/movies/romance?page=" to "رومانسية",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url).document
        val items = doc.select("a.postBlockCol, a.postBlock")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ─────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return doc.select("a.postBlockCol, a.postBlock")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    // ── Load ───────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1, .postTitle, .entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""

        val isMovie = isMovieUrl(url, rawTitle)

        val cleanTitle = rawTitle
            .replace(Regex("^مشاهدة (فيلم|مسلسل|انمي|كرتون)\\s+"), "")
            .replace(Regex("\\s+(مترجم|مدبلج|اون لاين|اونلاين|حصرى|كامل|على أكثر من سيرفر).*$"), "")
            .trim().ifBlank { rawTitle }

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".postCover img, .post-thumbnail img, article .aligncenter img")?.attr("src")

        val plot = doc.selectFirst(".postDesc, .story, .entry-summary, .storyLine")?.text()?.trim()

        val year = Regex("(19|20)\\d{2}").find(rawTitle)?.value?.toIntOrNull()

        val tags = doc.select(".postGenres a, .genres a, a[href*=/genre/], a[href*=/category/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val trailer = doc.selectFirst("a[href*=youtube.com/watch]")?.attr("href")

        if (isMovie) {
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addTrailer(trailer)
            }
        }

        // ── Series: collect all episode links ─────────────────────────────────────
        val episodes = mutableListOf<Episode>()

        // Try season tabs (li with data-season or similar)
        val seasonDivs = doc.select("ul.seasonList li a, .seasons-tabs a, #seasons-list a")
        val pagesWithSeason = if (seasonDivs.isNotEmpty()) {
            seasonDivs.mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { null } ?: return@mapNotNull null
                val sNum = Regex("\\d+").find(a.text())?.value?.toIntOrNull()
                href to sNum
            }
        } else {
            listOf(url to null)
        }

        for ((pageUrl, seasonNum) in pagesWithSeason) {
            val d = if (pageUrl == url) doc else app.get(pageUrl).document
            // find episode links
            d.select("a.postBlock, a[href*=الحلقة], a[href*=ep-], a[href*=episode]").forEach { ep ->
                val epHref = ep.absUrl("href").ifBlank { return@forEach }
                val epText = ep.text().trim()
                // Skip if it's a poster for another series (non-episode link)
                if (epHref == pageUrl) return@forEach
                val epNum = Regex("(\\d+)").find(epText)?.value?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    name = epText.ifBlank { "الحلقة $epNum" }
                    episode = epNum
                    season = seasonNum
                    this.posterUrl = ep.selectFirst("img")?.attr("src")?.ifBlank { null }
                })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { name = cleanTitle })
        }

        return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedBy { it.episode }) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addTrailer(trailer)
        }
    }

    // ── LoadLinks ──────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false
        val embedUrls = mutableSetOf<String>()

        // 1. Server buttons using onclick="loadIframe(this, 'URL')"
        val onclickRegex = Regex("""loadIframe\s*\(\s*this\s*,\s*['"]([^'"]+)['"]""")
        doc.select("ul#watch-servers-list li, .servers-list li, li[onclick]").forEach { li ->
            val onclick = li.attr("onclick")
            onclickRegex.find(onclick)?.groupValues?.get(1)?.let { embedUrls.add(it.toAbsUrl()) }
        }

        // 2. Anchor tags with onclick
        doc.select("a[onclick]").forEach { a ->
            onclickRegex.find(a.attr("onclick"))?.groupValues?.get(1)?.let { embedUrls.add(it.toAbsUrl()) }
        }

        // 3. Iframes with src
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src").toAbsUrl() }
            if (!src.contains("disqus") && !src.contains("facebook") && src.startsWith("http")) {
                embedUrls.add(src)
            }
        }

        // 4. data-* attributes on any element
        doc.select("[data-src],[data-embed],[data-url],[data-link]").forEach { el ->
            for (attr in listOf("data-src", "data-embed", "data-url", "data-link")) {
                val v = el.attr(attr).trim()
                if (v.startsWith("http")) embedUrls.add(v)
                else if (v.startsWith("/")) embedUrls.add("$mainUrl$v")
            }
        }

        // 5. Scan scripts for embed/stream/m3u8 URLs
        doc.select("script").forEach { script ->
            val html = script.html()
            onclickRegex.findAll(html).forEach { embedUrls.add(it.groupValues[1].toAbsUrl()) }
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html).forEach { embedUrls.add(it.value) }
            Regex("""["'](https?://(?:vidtube|doodstream|updown|lulustream|streamwish|cybervynx|vidsrc|streameast|filemoon|mixdrop)[^"']+)["']""")
                .findAll(html).forEach { embedUrls.add(it.groupValues[1]) }
        }

        // 6. Try to resolve each candidate embed
        for (embedUrl in embedUrls.distinct()) {
            try {
                if (embedUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, embedUrl, referer = data).forEach { callback(it); found = true }
                } else {
                    val result = loadExtractor(embedUrl, referer = data, subtitleCallback, callback)
                    if (result) found = true
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // 7. Last resort WebView for anything that loads dynamically
        if (!found) {
            try {
                val resolved = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                ).resolveUsingWebView(requestCreator("GET", data, referer = mainUrl)).first
                val videoUrl = resolved?.url?.toString()
                if (!videoUrl.isNullOrBlank()) {
                    if (videoUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, videoUrl, referer = data).forEach { callback(it); found = true }
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return found
    }
}
