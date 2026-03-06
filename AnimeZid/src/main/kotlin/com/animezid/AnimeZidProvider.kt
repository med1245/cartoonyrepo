package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element

class AnimeZidProvider : MainAPI() {

    override var mainUrl    = "https://animezid.cam"
    override var name       = "AnimeZid"
    override var lang       = "ar"
    override val hasMainPage    = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Movie
    )

    // ── Main Page Categories (mirror the site's navigation) ───────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php"                          to "الأحدث إضافة",
        "$mainUrl/category.php?cat=anime"                 to "أنمي",
        "$mainUrl/category.php?cat=movies"                to "أفلام أنمي",
        "$mainUrl/category.php?cat=series"                to "مسلسلات",
        "$mainUrl/category.php?cat=disney-masr"           to "ديزني مصر",
        "$mainUrl/category.php?cat=spacetoon"             to "سبيستون",
        "$mainUrl/topvideos.php?filter=views"             to "الأكثر مشاهدة",
        "$mainUrl/topvideos.php?filter=rating"            to "الأعلى تقييماً",
        "$mainUrl/topvideos.php?filter=year&year=2024"    to "إصدار 2024",
        "$mainUrl/topvideos.php?filter=genre&genre=action" to "أكشن"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val doc = app.get(url).document
        val items = doc.select("a.movie").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search.php?keywords=${query.encodeUrl()}"
        ).document
        return doc.select("a.movie").mapNotNull { it.toSearchResult() }
    }

    // ── Card helper ───────────────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val href  = absUrl("href").ifBlank { return null }

        // Title is in span.title (confirmed by HTML probe)
        val title = select("span.title").text().trim()
            .ifBlank { select("span").firstOrNull { it.hasClass("title") }?.text()?.trim().orEmpty() }
            .ifBlank { attr("title").trim() }
            .ifBlank { return null }

        // Images are lazy-loaded: class="lazy" with data-src attribute (NOT src)
        val img   = select("img").firstOrNull()
        val poster = (img?.attr("data-src") ?: img?.attr("src") ?: "").let {
            when {
                it.startsWith("http") -> it
                it.startsWith("/")    -> "$mainUrl$it"
                it.isNotBlank()       -> "$mainUrl/$it"
                else -> null
            }
        }

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ── Load (Detail Page) ───────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // Title: try several selectors
        val title = doc.select("h1, .movies-title, .entry-title, h2.title, div.title > span")
            .firstOrNull()?.text()?.trim()
            ?: doc.select("meta[property=og:title]").attr("content").trim()
            .ifBlank { doc.title().substringBefore(" - ") }

        // Poster: og:image is most reliable on detail pages
        val poster = doc.select("meta[property=og:image]").attr("content").ifBlank {
            doc.select("img.movie-img, div.movies-img img, img[itemprop=image]")
                .firstOrNull()?.let {
                    (it.attr("data-src").ifBlank { it.attr("src") }).let { src ->
                        when {
                            src.startsWith("http") -> src
                            src.startsWith("/")    -> "$mainUrl$src"
                            src.isNotBlank()       -> "$mainUrl/$src"
                            else -> null
                        }
                    }
                }
        }

        val description = doc.select("meta[property=og:description]").attr("content").ifBlank {
            doc.select("div.movies-story, div.description, .story").firstOrNull()?.text()?.trim()
        }

        val tags = doc.select("a[href*='topvideos.php?filter=genre'], a[href*='cat=']")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        // ── Episode links ────────────────────────────────────────────────────
        // Episodes link to watch.php?vid=HEX
        val episodeElements = doc.select("a[href*='watch.php?vid='], a[href*='?vid=']")
            .filter { el ->
                val t = el.text()
                t.contains("حلقة") || t.contains("فيلم") || el.attr("href").contains("vid=")
            }

        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapIndexed { index, el ->
                val epHref = el.absUrl("href").ifBlank { "$mainUrl/${el.attr("href")}" }
                val epName = el.text().trim().ifBlank { "حلقة ${index + 1}" }

                val seasonNum  = Regex("""(?:الموسم|موسم)\s*(\d+)""").find(epName)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val episodeNum = Regex("""(?:الحلقة|حلقة)\s*(\d+)""").find(epName)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = seasonNum
                    this.episode = episodeNum
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl   = poster
                this.plot        = description
                this.tags        = tags
            }
        } else {
            // Single movie
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl   = poster
                this.plot        = description
                this.tags        = tags
            }
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = Regex("""[?&]vid=([a-fA-F0-9]+)""").find(data)?.groupValues?.getOrNull(1)
            ?: return false

        val playUrl = "$mainUrl/play.php?vid=$vid"
        val playDoc = app.get(playUrl, referer = data).document

        var found = false
        val iframeSrcs = mutableListOf<String>()

        // Already-rendered iframes
        playDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) iframeSrcs.add(src)
        }

        // Server buttons
        playDoc.select("[data-url]").forEach { el ->
            val src = el.attr("data-url")
            if (src.isNotBlank()) iframeSrcs.add(src)
        }

        // JS-embedded URLs in scripts
        playDoc.select("script").forEach { script ->
            Regex("""['"]?(https?://(?:zidwish\.site|smoothpre\.com|filemoon\.|dood\.|vidmoly\.to|upstrea\.me|streamwish\.)[^'"<>\s]+)['"]?""")
                .findAll(script.html()).forEach { iframeSrcs.add(it.groupValues[1]) }
        }

        for (embedUrl in iframeSrcs.distinct()) {
            try {
                when {
                    embedUrl.contains("zidwish.site") || embedUrl.contains("smoothpre.com") -> {
                        val m3u8 = WebViewResolver(
                            interceptUrl = Regex(""".*\.m3u8.*""")
                        ).resolveUsingWebView(
                            requestCreator("GET", embedUrl, referer = playUrl)
                        ).first?.url?.toString()

                        if (!m3u8.isNullOrBlank()) {
                            M3u8Helper.generateM3u8(name, m3u8, referer = embedUrl)
                                .toList().forEach { callback.invoke(it) }
                            found = true
                        }
                    }
                    else -> {
                        loadExtractor(embedUrl, referer = playUrl, subtitleCallback, callback)
                        found = true
                    }
                }
            } catch (e: Exception) { /* ignore per-server errors */ }
        }

        // Last resort: WebView on the full play page
        if (!found) {
            try {
                val webView = WebViewResolver(
                    interceptUrl = Regex(""".*\.m3u8.*""")
                ).resolveUsingWebView(
                    requestCreator("GET", playUrl, referer = data)
                ).first

                val videoUrl = webView?.url?.toString()
                if (!videoUrl.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(name, videoUrl, referer = playUrl)
                        .toList().forEach { callback.invoke(it) }
                    found = true
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return found
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
}
