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

    // ── Main Page Categories ───────────────────────────────────────────────────
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
            .distinctBy { it.name }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/search.php?keywords=${query.encodeUrl()}"
        ).document
        return doc.select("a.movie").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href  = absUrl("href").ifBlank { return null }
        val rawTitle = select("span.title").text().trim()
            .ifBlank { select("span").firstOrNull { it.hasClass("title") }?.text()?.trim().orEmpty() }
            .ifBlank { attr("title").trim() }
            .ifBlank { return null }

        val isMovie = rawTitle.startsWith("فيلم") || rawTitle.contains("فيلم")
        val isEpisode = rawTitle.contains("الحلقة") || rawTitle.contains("حلقة")
        
        val title = if (isEpisode) {
            rawTitle.replace(Regex("""\s*الحلقة\s*\d+.*"""), "")
                .replace(Regex("""\s*حلقة\s*\d+.*"""), "")
                .replace(Regex("""\s*الموسم\s*\d+.*"""), "")
                .replace(Regex("""\s*موسم\s*\d+.*"""), "")
                .trim()
        } else rawTitle.trim()

        val img = select("img").firstOrNull()
        val poster = (img?.attr("data-src") ?: img?.attr("src") ?: "").let { src ->
            when {
                src.startsWith("http") -> src
                src.startsWith("/")    -> "$mainUrl$src"
                src.isNotBlank()       -> "$mainUrl/$src"
                else -> null
            }
        }

        return if (isEpisode) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val titleSelector = doc.select(".movie_title h1, .movies-title, .entry-title, h2.title, div.title > span")
        val fullTitle = titleSelector.firstOrNull()?.text()?.trim()
            ?: doc.select("meta[property=og:title]").attr("content").trim()

        val title = fullTitle.replace(Regex("""\s*الحلقة\s*\d+.*"""), "")
            .replace(Regex("""\s*حلقة\s*\d+.*"""), "").trim()

        val poster = doc.select("meta[property=og:image]").attr("content").ifBlank {
            doc.select("img.movie-img, div.movies-img img, img[itemprop=image]")
                .firstOrNull()?.let {
                    val src = it.attr("data-src").ifBlank { it.attr("src") }
                    when {
                        src.startsWith("http") -> src
                        src.startsWith("/")    -> "$mainUrl$src"
                        src.isNotBlank()       -> "$mainUrl/$src"
                        else -> null
                    }
                }
        }

        val seasonTabs = doc.select("ul.nav.nav-tabs li a")
        val episodesList = doc.select(".pm-episode-link, a[href*='watch.php?vid=']")
            .filter { it.text().contains("الحلقة") || it.text().contains("حلقة") }
        
        val isSeries = seasonTabs.isNotEmpty() || (episodesList.isNotEmpty() && !fullTitle.startsWith("فيلم"))

        val descriptionArr = doc.select("div.pm-video-description, #video-details, .story, meta[property=og:description], .description")
        val description = descriptionArr.firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            ?: descriptionArr.attr("content").trim()

        val tags = doc.select("div.pm-video-category a, .video-tags a, a[href*='topvideos.php?filter=genre'], a[href*='cat=']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select(".pm-episode-link, a[href*='watch.php?vid=']").forEach { ep ->
                val epHref = ep.absUrl("href")
                val name = ep.text().trim()
                if (name.contains("الحلقة") || name.contains("حلقة")) {
                    val epNum = Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode(epHref) {
                        this.name = name
                        this.episode = epNum
                    })
                }
            }
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) { this.name = fullTitle })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(fullTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

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

        playDoc.select("button[data-embed], .play-server").forEach { btn ->
            val embedUrl = btn.attr("data-embed").ifBlank { btn.attr("data-url") }
            if (embedUrl.isNotBlank() && embedUrl.startsWith("http")) {
                iframeSrcs.add(embedUrl)
            }
        }

        playDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.contains("//") && !src.contains("disqus")) {
                iframeSrcs.add(src)
            }
        }

        playDoc.select("a.btn.g.dl.show_dl.api").forEach { dl ->
            val link = dl.attr("href")
            val qualityText = dl.select("span").firstOrNull()?.text()?.trim() ?: "Download"
            val hostName = dl.select("span").getOrNull(1)?.text()?.trim() ?: "Link"
            if (link.isNotBlank() && link.startsWith("http")) {
                callback.invoke(
                    ExtractorLink(
                        source = "$name Download",
                        name = "$hostName ($qualityText)",
                        url = link,
                        referer = playUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
                found = true
            }
        }

        val hostRegex = Regex("""['"]?(https?://(?:zidwish|smoothpre|filemoon|dood|vidmoly|upstrea|streamwish|megamax|listeamed|upns|streamcasthub|koramaup|vidtube)[^'"<>\s]+)['"]?""")
        playDoc.select("script").forEach { script ->
            hostRegex.findAll(script.html()).forEach { iframeSrcs.add(it.groupValues[1]) }
        }

        for (embedUrlRaw in iframeSrcs.distinct()) {
            val embedUrl = if (embedUrlRaw.startsWith("//")) "https:$embedUrlRaw" else embedUrlRaw
            try {
                when {
                    listOf("zidwish", "smoothpre", "upns", "streamcasthub", "megamax", "listeamed", "vidtube").any { embedUrl.contains(it) } -> {
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
