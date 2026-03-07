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

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun String.toAbs(): String =
        if (startsWith("http")) this
        else if (startsWith("/")) "$mainUrl$this"
        else "$mainUrl/$this"

    private fun isMovie(href: String, rawTitle: String): Boolean {
        val t = rawTitle
        return t.contains("فيلم") || t.contains("film", ignoreCase = true)
                || href.contains("مشاهدة-فيلم") || href.contains("%d9%81%d9%8a%d9%84%d9%85")
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("^مشاهدة (فيلم|مسلسل|انمي|أنمي|كرتون|برنامج)\\s+"), "")
        .replace(Regex("\\s+(مترجم|مدبلج|حصرى|حصريا|اون لاين|اونلاين|كامل|على أكثر من سيرفر|كاملة).*$"), "")
        .trim()

    // ── Card ───────────────────────────────────────────────────────────────────────

    private fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }
        val img = selectFirst("img") ?: return null
        val poster = img.attr("src").trim().let { if (it.startsWith("http")) it else null }
        val rawTitle = (selectFirst("h3.title, h3, span.title")?.text()?.trim()
            ?: img.attr("alt").trim()).ifBlank { return null }
        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        return if (isMovie(href, rawTitle))
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        else
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
    }

    // ── Main page – mirrors site nav ───────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/trends/?page=" to "التريند",
        "$mainUrl/last/?page=" to "المضاف حديثاً",
        "$mainUrl/movies/?page=" to "أحدث الأفلام",
        "$mainUrl/series/?page=" to "أحدث الحلقات",
        "$mainUrl/category/anime/?page=" to "أحدث الكرتون",
        "$mainUrl/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%B1%D9%85%D8%B6%D8%A7%D9%86-2026/?page=" to "مسلسلات رمضان",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url).document
        val items = doc.select("a.postBlockCol, a.postBlock")
            .mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ─────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return doc.select("a.postBlockCol, a.postBlock")
            .mapNotNull { it.toCard() }.distinctBy { it.url }
    }

    // ── Load ───────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1, .postTitle, .entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: ""
        val title = cleanTitle(rawTitle).ifBlank { rawTitle }

        // Poster from og:image (most stable)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img.postCoverImg, .postCover img, article img")?.attr("src")

        val plot = doc.selectFirst(".postDesc, .entry-content p, .storyLine")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(rawTitle)?.value?.toIntOrNull()
        val tags = doc.select("a[href*=/category/], a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }
        val trailer = doc.selectFirst("a[href*=youtube.com/watch]")?.attr("href")

        if (isMovie(url, rawTitle)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
                addTrailer(trailer)
            }
        }

        // Series – collect episode cards
        val episodes = mutableListOf<Episode>()
        doc.select("a.postBlock, div.postBlock a").forEach { ep ->
            val epHref = ep.absUrl("href").ifBlank { return@forEach }
            if (epHref == url) return@forEach
            val epText = ep.text().trim()
            val epNum = Regex("(\\d+)").find(epText)?.value?.toIntOrNull()
            val epPoster = ep.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else null }
            episodes.add(newEpisode(epHref) {
                name = epText.ifBlank { "الحلقة $epNum" }
                episode = epNum
                posterUrl = epPoster
            })
        }
        if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = title })

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedBy { it.episode }) {
            posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
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

        // Primary: onclick="loadIframe(this, 'URL')" on <li> server buttons
        val loadIframeRegex = Regex("""loadIframe\s*\(\s*this\s*,\s*['"]([^'"]+)['"]""")
        doc.select("[onclick]").forEach { el ->
            loadIframeRegex.find(el.attr("onclick"))
                ?.groupValues?.get(1)?.let { embedUrls.add(it.toAbs()) }
        }

        // Secondary: onclick="window.open('URL',...)" for download/direct links
        val windowOpenRegex = Regex("""window\.open\s*\(\s*['"]([^'"]+)['"]""")
        doc.select("[onclick]").forEach { el ->
            windowOpenRegex.find(el.attr("onclick"))
                ?.groupValues?.get(1)?.let { url ->
                    if (url.startsWith("http")) embedUrls.add(url)
                }
        }

        // Fallback: iframes already rendered
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src")
            if (src.isNotBlank() && !src.contains("disqus") && !src.contains("facebook")) embedUrls.add(src)
        }

        // Scan scripts for loadIframe calls and raw embed URLs
        doc.select("script").forEach { script ->
            val html = script.html()
            loadIframeRegex.findAll(html).forEach { embedUrls.add(it.groupValues[1].toAbs()) }
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(html)
                .forEach { embedUrls.add(it.groupValues[1]) }
        }

        for (embedUrl in embedUrls.distinct()) {
            if (embedUrl.isBlank()) continue
            try {
                when {
                    embedUrl.contains(".m3u8") -> {
                        M3u8Helper.generateM3u8(name, embedUrl, referer = data).forEach { callback(it); found = true }
                    }
                    else -> {
                        if (loadExtractor(embedUrl, referer = data, subtitleCallback, callback)) found = true
                    }
                }
            } catch (e: Exception) { /* skip failed extractors */ }
        }

        // Last resort: WebView intercept on the page itself
        if (!found) {
            try {
                val resolved = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                ).resolveUsingWebView(
                    requestCreator("GET", data, referer = mainUrl)
                ).first
                val videoUrl = resolved?.url?.toString()
                if (!videoUrl.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(name, videoUrl, referer = data)
                        .toList().forEach { callback(it); found = true }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return found
    }
}
