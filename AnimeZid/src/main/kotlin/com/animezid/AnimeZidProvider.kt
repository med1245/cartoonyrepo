@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
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
            .distinctBy { it.name } // Deduplicate by sanitized title
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

        // Title is in span.title
        val rawTitle = select("span.title").text().trim()
            .ifBlank { select("span").firstOrNull { it.hasClass("title") }?.text()?.trim().orEmpty() }
            .ifBlank { attr("title").trim() }
            .ifBlank { return null }

        // Determine if it's a TV series episode or a movie/special
        val isEpisode = rawTitle.contains("الحلقة") || rawTitle.contains("حلقة") || rawTitle.contains("موسم") || rawTitle.contains("الموسم")
        
        // Sanitize title for grouping ONLY if it's clearly an episode
        val title = if (isEpisode) {
            rawTitle
                .replace(Regex("""\s*الحلقة\s*\d+.*"""), "")
                .replace(Regex("""\s*حلقة\s*\d+.*"""), "")
                .replace(Regex("""\s*الموسم\s*\d+.*"""), "")
                .replace(Regex("""\s*موسم\s*\d+.*"""), "")
                .trim()
        } else {
            rawTitle.trim()
        }

        // Images are lazy-loaded: data-src confirmed
        val img   = select("img").firstOrNull()
        val poster = (img?.attr("data-src") ?: img?.attr("src") ?: "").let {
            when {
                it.startsWith("http") -> it
                it.startsWith("/")    -> "$mainUrl$it"
                it.isNotBlank()       -> "$mainUrl/$it"
                else -> null
            }
        }

        // Use Movie for things not identified as episodes to prevent unwanted grouping
        return if (isEpisode) {
            newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        }
    }

    // ── Load (Detail Page) ───────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // FIXED: Use .movie_title h1 to avoid the "Welcome" generic h1
        val titleSelector = doc.select(".movie_title h1, .movies-title, .entry-title, h2.title, div.title > span")
        val fullTitle = titleSelector.firstOrNull()?.text()?.trim()
            ?: doc.select("meta[property=og:title]").attr("content").trim()

        // Sanitize title for the series object (if it's a series)
        val title = fullTitle
            .replace(Regex("""\s*الحلقة\s*\d+.*"""), "")
            .replace(Regex("""\s*حلقة\s*\d+.*"""), "")
            .trim()

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
        // Every episode page has a list of ALL episodes in that series.
        val episodeElements = doc.select("a[href*='watch.php?vid='], a[href*='?vid=']")
            .filter { el ->
                val t = el.text()
                t.contains("حلقة") || t.contains("فيلم") || el.attr("href").contains("vid=")
            }.distinctBy { it.attr("href") }

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
            }.sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl   = poster
                this.plot        = description
                this.tags        = tags
            }
        } else {
            newMovieLoadResponse(fullTitle, url, TvType.AnimeMovie, url) {
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

        // 1. Check for already-rendered iframes
        playDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.contains("//")) iframeSrcs.add(src)
        }

        // 2. Server buttons and data-urls
        playDoc.select("[data-url], [onclick*='//']").forEach { el ->
            val src = el.attr("data-url").ifBlank {
                Regex("""https?://[^\s'"]+""").find(el.attr("onclick"))?.value ?: ""
            }
            if (src.isNotBlank()) iframeSrcs.add(src)
        }

        // 3. Download links (Fix requested by user)
        playDoc.select("a.btn.g.dl.show_dl.api").forEach { dl ->
            val link = dl.attr("href")
            val qualityText = dl.select("span").firstOrNull()?.text()?.trim() ?: "Download"
            val hostName = dl.select("span").getOrNull(1)?.text()?.trim() ?: "Link"
            
            if (link.isNotBlank() && link.startsWith("http")) {
                callback.invoke(
                    ExtractorLink(
                        "$name - $hostName",
                        "$name - $hostName ($qualityText)",
                        link,
                        playUrl,
                        Qualities.P1080.value,
                        false
                    )
                )
                found = true
            }
        }

        // 4. Regex for more hosts
        val hostRegex = Regex("""['"]?(https?://(?:zidwish|smoothpre|filemoon|dood|vidmoly|upstrea|streamwish|megamax|listeamed|upns|streamcasthub|koramaup)[^'"<>\s]+)['"]?""")
        playDoc.select("script").forEach { script ->
            hostRegex.findAll(script.html()).forEach { iframeSrcs.add(it.groupValues[1]) }
        }

        for (embedUrlRaw in iframeSrcs.distinct()) {
            val embedUrl = if (embedUrlRaw.startsWith("//")) "https:$embedUrlRaw" else embedUrlRaw
            try {
                when {
                    // Hosts that need WebView interception
                    listOf("zidwish", "smoothpre", "upns", "streamcasthub", "megamax", "listeamed").any { embedUrl.contains(it) } -> {
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
                        if (loadExtractor(embedUrl, referer = playUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // Last resort: WebView on the full play page with generic M3U8 intercept
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

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
}
