@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import java.util.Base64

class Shahid4uProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://shahid4u.casa"
    override var name = "Shahid4u"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // ── helpers ────────────────────────────────────────────────────────────────────

    private fun String.toAbs(): String =
        if (startsWith("http")) this
        else if (startsWith("/")) "$mainUrl$this"
        else "$mainUrl/$this"

    /** Detect movie by URL slug containing Arabic for "film" encoded */
    private fun isMovieUrl(href: String): Boolean {
        val slug = href.lowercase()
        return slug.contains("%d9%81%d9%8a%d9%84%d9%85") // "فيلم" encoded
            || slug.contains("/movies/")
            || slug.contains("فيلم")
    }

    // ── Card parsing ───────────────────────────────────────────────────────────────

    private fun Element.toCard(): SearchResponse? {
        // Cards are <a class="recent--block"> with img inside and title in div.inner--title h2
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }
        if (href.contains("#") || href.contains("javascript")) return null
        val img = selectFirst("img") ?: return null
        val poster = img.attr("src").trim().let { if (it.startsWith("http")) it else null }
        val title = (selectFirst("div.inner--title h2, h2, .title, h3")?.text()?.trim()
            ?: attr("title").trim()
            ?: img.attr("alt").trim()).ifBlank { return null }
        return if (isMovieUrl(href))
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        else
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
    }

    // ── Main page (mirrors site navigation) ───────────────────────────────────────

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
        val url = request.data + page
        val doc = app.get(url).document
        val items = doc.select("a.recent--block, article.post a, .movies-list a")
            .mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ─────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return doc.select("a.recent--block, article.post a")
            .mapNotNull { it.toCard() }.distinctBy { it.url }
    }

    // ── Load ───────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: ""

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".thumb img, .poster img, article img")?.attr("src")

        val plot = doc.selectFirst(".entry-content p, .description, .story")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()
        val tags = doc.select("a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }
        val trailer = doc.selectFirst("a[href*=youtube.com/watch]")?.attr("href")

        if (isMovieUrl(url)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
                addTrailer(trailer)
            }
        }

        // Series: collect episodes from page
        val episodes = mutableListOf<Episode>()
        // Episode links containing "حلقة" or "episode" in text or href
        doc.select("a[href]").filter { a ->
            val t = a.text().trim()
            val h = a.attr("href")
            (t.contains("الحلقة") || t.contains("حلقة") || h.contains("episode") || h.contains("ep-"))
                && a.absUrl("href").startsWith(mainUrl)
        }.forEach { ep ->
            val epHref = ep.absUrl("href")
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

        // govid.live wrapper: /play/BASE64_OF_EMBED_URL  
        // Decode Base64 to get real embed URL
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            when {
                src.contains("govid.live/play/") -> {
                    // Decode Base64 encoded inner URL
                    runCatching {
                        val b64 = src.substringAfter("/play/").split("/").first().split("?").first()
                        val decoded = String(Base64.getDecoder().decode(b64))
                        if (decoded.startsWith("http")) embedUrls.add(decoded)
                    }
                    // Also try the govid URL directly via loadExtractor
                    embedUrls.add(src)
                }
                src.startsWith("http") && !src.contains("disqus") -> embedUrls.add(src)
            }
        }

        // Server tab buttons (onclick or data attributes)
        val loadIframeRegex = Regex("""loadIframe\s*\(\s*this\s*,\s*['"]([^'"]+)['"]""")
        val govIdBaseRegex = Regex("""govid\.live/play/([A-Za-z0-9+/=]+)""")
        doc.select("[onclick]").forEach { el ->
            val onclick = el.attr("onclick")
            loadIframeRegex.find(onclick)?.groupValues?.get(1)?.let { u ->
                if (u.startsWith("http")) embedUrls.add(u)
            }
        }

        // Scan scripts for govid.live URLs and direct embed URLs
        doc.select("script").forEach { script ->
            val html = script.html()
            govIdBaseRegex.findAll(html).forEach { m ->
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(m.groupValues[1]))
                    if (decoded.startsWith("http")) embedUrls.add(decoded)
                }.onFailure { embedUrls.add("https://govid.live/play/${m.groupValues[1]}") }
            }
            loadIframeRegex.findAll(html).forEach { m -> embedUrls.add(m.groupValues[1].toAbs()) }
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(html)
                .forEach { embedUrls.add(it.groupValues[1]) }
        }

        for (embedUrl in embedUrls.distinct()) {
            if (embedUrl.isBlank()) continue
            try {
                if (embedUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, embedUrl, referer = data).forEach { callback(it); found = true }
                } else {
                    if (loadExtractor(embedUrl, referer = data, subtitleCallback, callback)) found = true
                }
            } catch (e: Exception) { /* skip */ }
        }

        // WebView fallback
        if (!found) {
            try {
                val resolved = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                ).resolveUsingWebView(requestCreator("GET", data, referer = mainUrl)).first
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
