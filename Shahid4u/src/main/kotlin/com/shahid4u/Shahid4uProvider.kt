@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
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

    // ── helpers ────────────────────────────────────────────────────────────────────

    private fun String.toAbs(): String =
        if (startsWith("http")) this
        else if (startsWith("/")) "$mainUrl$this"
        else "$mainUrl/$this"

    private fun isMovieUrl(href: String): Boolean {
        val slug = href.lowercase()
        return slug.contains("%d9%81%d9%8a%d9%84%d9%85") || slug.contains("فيلم")
    }

    /** Decode Base64 string safely, returns null on failure */
    private fun tryDecodeBase64(input: String): String? {
        return runCatching {
            val padded = input.trimEnd('/') + "=".repeat((4 - input.trimEnd('/').length % 4) % 4)
            String(AndroidBase64.decode(padded, AndroidBase64.DEFAULT)).trim()
        }.getOrNull()
    }

    /**
     * govid.live URLs format: https://govid.live/play/BASE64/
     * The BASE64 decodes to a real embed URL like:
     *   https://dingtezuni.com/embed/d23jaavabhpv
     *   https://fsdcmo.sbs/e//hywgw8a7fape 
     *   https://govid.live/e/34/?pic=BASE64_OF_POSTER
     * For govid.live/e/ URLs, the HLS m3u8 is served at govid.live/video-{id}.m3u8
     */
    private fun resolveGovidUrl(govidPlayUrl: String): List<String> {
        val results = mutableListOf<String>()
        val b64 = govidPlayUrl
            .substringAfter("/play/")
            .trimEnd('/')
            .split("?").first()
        val decoded = tryDecodeBase64(b64) ?: return results
        if (decoded.startsWith("http")) {
            results.add(decoded)
            // If it's a govid.live/e/ URL, also add the govidPlayUrl itself for WebView
            if (decoded.contains("govid.live/e/")) {
                results.add(govidPlayUrl)
            }
        }
        return results
    }

    // ── Card parsing ───────────────────────────────────────────────────────────────

    private fun Element.toCard(): SearchResponse? {
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
        // The watch page is at {contentUrl}/watch/
        val watchUrl = if (data.endsWith("/watch/") || data.endsWith("/watch")) data
                       else data.trimEnd('/') + "/watch/"
        val doc = app.get(watchUrl).document

        var found = false
        val embedUrls = mutableSetOf<String>()

        // ── PRIMARY: Read server list from ul#watch li[data-watch] ─────────────────
        // Each li has data-watch="https://govid.live/play/BASE64_ENCODED_URL"
        doc.select("ul#watch li[data-watch], .servers li[data-watch]").forEach { li ->
            val govidUrl = li.attr("data-watch").trim()
            if (govidUrl.contains("govid.live/play/")) {
                val resolved = resolveGovidUrl(govidUrl)
                embedUrls.addAll(resolved)
            } else if (govidUrl.startsWith("http")) {
                embedUrls.add(govidUrl)
            }
        }

        // ── SECONDARY: Read iframes already rendered ───────────────────────────────
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            if (src.contains("govid.live/play/")) {
                embedUrls.addAll(resolveGovidUrl(src))
            } else if (src.startsWith("http") && !src.contains("disqus")) {
                embedUrls.add(src)
            }
        }

        // ── TERTIARY: Scan scripts for additional govid URLs ───────────────────────
        val govidRegex = Regex("""govid\.live/play/([A-Za-z0-9+/=]+)""")
        doc.select("script").forEach { script ->
            val html = script.html()
            govidRegex.findAll(html).forEach { m ->
                val full = "https://govid.live/play/${m.groupValues[1]}"
                embedUrls.addAll(resolveGovidUrl(full))
            }
            // Also look for direct m3u8 URLs
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(html)
                .forEach { embedUrls.add(it.groupValues[1]) }
        }

        // ── Resolve each embed URL ─────────────────────────────────────────────────
        for (embedUrl in embedUrls.distinct()) {
            if (embedUrl.isBlank()) continue
            try {
                when {
                    embedUrl.contains(".m3u8") -> {
                        M3u8Helper.generateM3u8(name, embedUrl, referer = watchUrl)
                            .forEach { callback(it); found = true }
                    }
                    embedUrl.contains("govid.live/e/") -> {
                        // govid.live's own player: try WebView to intercept the m3u8
                        runCatching {
                            val resolved = WebViewResolver(
                                interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                            ).resolveUsingWebView(
                                requestCreator("GET", embedUrl, referer = watchUrl)
                            ).first
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
                        // Regular embed (dingtezuni, fsdcmo, uqload, vinovo, doodstream, etc.)
                        if (loadExtractor(embedUrl, referer = watchUrl, subtitleCallback, callback)) found = true
                    }
                }
            } catch (e: Exception) { /* skip failed extractors */ }
        }

        // ── LAST RESORT: WebView on the watch page itself ──────────────────────────
        if (!found) {
            try {
                val resolved = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                ).resolveUsingWebView(
                    requestCreator("GET", watchUrl, referer = mainUrl)
                ).first
                val videoUrl = resolved?.url?.toString()
                if (!videoUrl.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(name, videoUrl, referer = watchUrl)
                        .forEach { callback(it); found = true }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return found
    }
}
