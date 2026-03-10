package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.requestCreator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FaselHDX : MainAPI() {
    // Hardcoded fallback – updated to latest known domain
    override var mainUrl = "https://web31018x.faselhdx.bid"
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
    )

    private val cfKiller = CloudflareKiller()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

        /** The base domain that never changes – only the subdomain does. */
        private const val BASE_DOMAIN = "faselhdx.bid"

        /** Entry-points to try for auto-discovery (in order). */
        private val DISCOVERY_URLS = listOf(
            "https://$BASE_DOMAIN",
            "https://www.$BASE_DOMAIN",
        )

        // Session-wide cache so we only resolve once per app session.
        @Volatile private var resolvedUrl: String? = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Domain auto-discovery
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Discovers the currently active FaselHD domain by following HTTP redirects
     * from the stable root domain. Falls back to [mainUrl] if resolution fails.
     * The result is cached for the entire app session.
     */
    private suspend fun activeUrl(): String {
        resolvedUrl?.let { return it }

        for (entry in DISCOVERY_URLS) {
            try {
                // Follow redirects – the final URL should be the active mirror
                val resp = app.get(entry, allowRedirects = true,
                    headers = mapOf("User-Agent" to USER_AGENT), timeout = 10)
                val finalHost = resp.url.toHttpUrlOrNull()?.host ?: continue
                if (finalHost.contains(BASE_DOMAIN, ignoreCase = true)) {
                    val discovered = "https://$finalHost"
                    resolvedUrl = discovered
                    mainUrl = discovered          // keep mainUrl in sync
                    return discovered
                }
            } catch (_: Exception) {}
        }

        // Nothing resolved – use the hardcoded fallback
        resolvedUrl = mainUrl
        return mainUrl
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cloudflare-aware GET
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun cfGet(url: String, referer: String? = null): Document {
        val h = mapOf("User-Agent" to USER_AGENT)
        var doc = app.get(url, referer = referer, headers = h).document
        if (doc.selectFirst("title")?.text()
                ?.contains("Just a moment", ignoreCase = true) == true) {
            doc = app.get(url, referer = referer, interceptor = cfKiller, headers = h).document
        }
        return doc
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main Page
    // ──────────────────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/most_recent"   to "المضاف حديثاَ",
        "$mainUrl/series"        to "مسلسلات",
        "$mainUrl/movies"        to "أفلام",
        "$mainUrl/asian-series"  to "مسلسلات آسيوية",
        "$mainUrl/anime"         to "الأنمي",
        "$mainUrl/tvshows"       to "البرامج التلفزيونية",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi"         to "أفلام هندية",
        "$mainUrl/asian-movies"  to "أفلام آسيوية",
        "$mainUrl/anime-movies"  to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base   = activeUrl()
        // Replace whatever domain is in request.data with the live one
        val rawUrl = request.data.replace(mainUrl, base)
        val url    = if (page == 1) rawUrl else "${rawUrl.trimEnd('/')}/page/$page"
        val doc    = cfGet(url)
        val list   = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a")?.attr("href")?.trim() ?: return null
        val title = selectFirst(".h1, .h4, div.h1, div.h4, div.postInner div.h1")
            ?.text()?.trim() ?: return null
        val img = selectFirst("div.imgdiv-class img") ?: selectFirst("img")
        var posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") }
        if (!posterUrl.isNullOrEmpty() && posterUrl.startsWith("//"))
            posterUrl = "https:$posterUrl"
        val quality = selectFirst("span.quality")?.text()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality   = getQualityFromString(quality)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val base = activeUrl()
        val doc  = cfGet("$base/?s=$query")
        return doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Load (detail page)
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc   = cfGet(url)
        val base  = activeUrl()

        val title = doc.selectFirst(".singleInfo .title, div.title")?.ownText()?.trim()
            ?: doc.selectFirst("title")?.text()
                ?.replace(" - فاصل إعلاني", "")?.trim() ?: ""
        val poster = doc.selectFirst("div.posterImg img")?.attr("src")
        val desc   = doc.selectFirst("div.singleDesc p")?.text()

        val infoRows = doc.select("div#singleList .col-xl-6, div#singleList div[class*=col-]")
        val year = infoRows.find { it.text().contains("سنة الإنتاج") }
            ?.text()?.substringAfter(":")?.trim()?.toIntOrNull()
        val duration = infoRows.find { it.text().contains("مدة") }
            ?.text()?.substringAfter(":")?.trim()?.filter(Char::isDigit)?.toIntOrNull()

        val episodes = doc.select("div#epAll a").mapNotNull { el ->
            val epUrl = el.attr("href").trim().ifEmpty { return@mapNotNull null }
            val epTitle = el.text().trim()
            val epNum = Regex("""الحلقة\s*(\d+)""").find(epTitle)
                ?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) { name = epTitle; episode = epNum }
        }

        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year
                this.plot = desc; this.duration = duration
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year
                this.plot = desc; this.recommendations = recommendations
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Load Links  – four-stage video extraction
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = activeUrl()
        val doc  = cfGet(data)

        // ── Stage 1: Direct download link ───────────────────────────────────
        val downloadHref = doc.select(".downloadLinks a").attr("href")
        if (downloadHref.isNotBlank()) {
            try {
                val playerDoc = app.post(downloadHref, referer = base, timeout = 120).document
                val dlLink    = playerDoc.select("div.dl-link a").attr("href")
                if (dlLink.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(name, "$name Direct", dlLink, ExtractorLinkType.VIDEO) {
                            this.referer = base
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (_: Exception) {}
        }

        // ── Locate player iframe ─────────────────────────────────────────────
        val iframeSrc = doc.select(
            "iframe[name=\"player_iframe\"], iframe[src*=\"video_player\"]"
        ).attr("src").ifEmpty {
            val onclick = doc.selectFirst("ul.tabs-ul li[onclick], li.active[onclick]")
                ?.attr("onclick")
            Regex("""'([^']+)'""").find(onclick ?: "")
                ?.groupValues?.get(1)?.let { fixUrl(it) } ?: ""
        }

        if (iframeSrc.isBlank()) return false

        // ── Stage 2: loadExtractor (handles 100s of known video hosts) ───────
        // If the iframe points to a recognised CDN/host this resolves it fully.
        try {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        } catch (_: Exception) {}

        // ── Stage 3: Fetch player HTML via HTTP + cfKiller, regex for URL ────
        // Avoids WebView entirely; works when the URL is readable in the JS.
        try {
            val playerText = app.get(
                iframeSrc, referer = data, interceptor = cfKiller,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
            val cleaned = playerText.replace(Regex("""['"]\s*\+\s*['"]"""), "")

            val m3u8 = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""").find(cleaned)?.value
            if (!m3u8.isNullOrBlank()) {
                M3u8Helper.generateM3u8(name, m3u8, referer = base).forEach(callback)
                return true
            }
            val mp4 = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""").find(cleaned)?.value
            if (!mp4.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(name, "$name MP4", mp4, ExtractorLinkType.VIDEO) {
                        this.referer = iframeSrc; this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) {}

        // ── Stage 4: WebView with CF cookies in request headers ──────────────
        return try {
            val cfHeaders = cfKiller.getCookieHeaders(base).toMap()
            val resolver  = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                script = """
                    (function() {
                        ['.play-button','.btn-play','.vjs-big-play-button','.jw-icon-display']
                            .forEach(function(s){var e=document.querySelector(s);if(e)e.click();});
                    })();
                """.trimIndent()
            )
            val videoUrl = resolver.resolveUsingWebView(
                requestCreator("GET", iframeSrc,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data) + cfHeaders)
            ).first?.url?.toString()

            if (!videoUrl.isNullOrBlank() && videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, videoUrl, referer = base).forEach(callback)
                true
            } else false
        } catch (_: Exception) { false }
    }
}
