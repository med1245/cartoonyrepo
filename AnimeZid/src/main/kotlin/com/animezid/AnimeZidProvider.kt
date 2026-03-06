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

    // ── Main Page Categories ──────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php"                     to "الأحدث",
        "$mainUrl/category.php?cat=anime"            to "أنمي",
        "$mainUrl/category.php?cat=movies"           to "أفلام",
        "$mainUrl/category.php?cat=series"           to "مسلسلات",
        "$mainUrl/category.php?cat=disney-masr"      to "ديزني مصر",
        "$mainUrl/topvideos.php?filter=views"        to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val doc = app.get(url).document
        val items = doc.select("a.movie").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search.php?keywords=${query.encodeUrl()}").document
        return doc.select("a.movie").mapNotNull { it.toSearchResult() }
    }

    // ── Search Result helper ──────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val href  = absUrl("href").ifBlank { return null }
        val title = select("span").firstOrNull()?.text()?.trim() ?: return null
        val poster = select("img").attr("src").let {
            if (it.startsWith("http")) it else "$mainUrl/$it"
        }
        // Determine type by URL or text
        val type = when {
            href.contains("?vid=") -> TvType.TvSeries
            else -> TvType.Movie
        }
        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ── Load (Detail Page) ───────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.select("h1, .entry-title, div.movies-title, span.title-name")
            .firstOrNull()?.text()?.trim()
            ?: doc.title().substringBefore(" - AniméZid").trim()

        val poster = doc.select("img.movie-img, div.movies-img img, img[src*=thumb]")
            .firstOrNull()?.attr("src")?.let {
                if (it.startsWith("http")) it else "$mainUrl/$it"
            }

        val description = doc.select("div.movies-story, div.description, p.story")
            .firstOrNull()?.text()?.trim()

        val tags = doc.select("a[href*='topvideos.php?filter=genre']")
            .map { it.text().trim() }

        // ── Episode list ──────────────────────────────────────────────────────
        // Episodes are links with ?vid= in href and contain "الحلقة"
        val episodeElements = doc.select("a[href*='watch.php?vid='], a[href*='?vid=']")
            .filter { it.text().contains("حلقة") || it.text().contains("فيلم") || it.attr("href").contains("vid=") }

        return if (episodeElements.isNotEmpty()) {
            // TV Series / Anime
            val episodes = episodeElements.mapIndexed { index, el ->
                val epHref = el.absUrl("href").ifBlank { "$mainUrl/${el.attr("href")}" }
                val epName = el.text().trim().ifBlank { "حلقة ${index + 1}" }

                // Try to extract season/episode numbers from text
                val seasonMatch  = Regex("""(?:الموسم|موسم)\s*(\d+)""").find(epName)
                val episodeMatch = Regex("""(?:الحلقة|حلقة)\s*(\d+)""").find(epName)

                val seasonNum  = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val episodeNum = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)

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
        // The episode page is watch.php?vid=HEX
        // The player page is play.php?vid=HEX (same hex ID)
        val vid = Regex("""[?&]vid=([a-fA-F0-9]+)""").find(data)?.groupValues?.getOrNull(1)
            ?: return false

        val playUrl = "$mainUrl/play.php?vid=$vid"
        val playDoc = app.get(playUrl, referer = data).document

        var found = false

        // Server buttons: each has data-url or onclick containing the embed iframe src
        // They can also be direct iframes already rendered
        val iframeSrcs = mutableListOf<String>()

        // Check for already-embedded iframes
        playDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) iframeSrcs.add(src)
        }

        // Check server buttons for data-url attributes
        playDoc.select("[data-url], [onclick*='//']").forEach { el ->
            val src = el.attr("data-url").ifBlank {
                Regex("""https?://[^\s'"]+""").find(el.attr("onclick"))?.value ?: ""
            }
            if (src.isNotBlank()) iframeSrcs.add(src)
        }

        // Also look for JS-embedded URLs in script tags
        playDoc.select("script").forEach { script ->
            val text = script.html()
            Regex("""['"](https?://(?:zidwish\.site|smoothpre\.com|dood\.|vidmoly\.to|filemoon\.)[^'"]+)['"]""")
                .findAll(text).forEach { iframeSrcs.add(it.groupValues[1]) }
        }

        // De-duplicate
        val uniqueSrcs = iframeSrcs.distinct()

        for (embedUrl in uniqueSrcs) {
            try {
                when {
                    // ── Zidwish / Smoothpre: use WebViewResolver to intercept M3U8 ──
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

                    // ── Generic: try loadExtractor for known hosts ─────────────────
                    else -> {
                        loadExtractor(embedUrl, referer = playUrl, subtitleCallback, callback)
                        found = true
                    }
                }
            } catch (e: Exception) { /* ignore per-server errors */ }
        }

        // If nothing found from play page, try WebView on the watch page itself
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
