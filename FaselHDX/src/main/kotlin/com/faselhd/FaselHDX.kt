package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FaselHDX : MainAPI() {
    override var mainUrl = "https://web31012x.faselhdx.bid"
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val cfKiller = CloudflareKiller()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

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

    /**
     * GET with automatic Cloudflare bypass.
     * Tries plain HTTP first; if Cloudflare challenge is detected, retries with cfKiller.
     */
    private suspend fun cfGet(url: String, referer: String? = null): Document {
        val headers = mapOf("User-Agent" to USER_AGENT)
        var doc = app.get(url, referer = referer, headers = headers).document
        if (doc.selectFirst("title")?.text()?.contains("Just a moment", ignoreCase = true) == true) {
            doc = app.get(url, referer = referer, interceptor = cfKiller, headers = headers).document
        }
        return doc
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main Page
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = cfGet(url)
        val list = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
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
        val doc = cfGet("$mainUrl/?s=$query")
        return doc.select("div.postDiv").mapNotNull { it.toSearchResult() }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Load (detail page)
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = cfGet(url)
        val title = doc.selectFirst(".singleInfo .title, div.title")?.ownText()?.trim()
            ?: doc.selectFirst("title")?.text()?.replace(" - فاصل إعلاني", "")?.trim()
            ?: ""
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
            val epNum = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) { name = epTitle; episode = epNum }
        }

        val recommendations = doc.select("div.postDiv").mapNotNull { it.toSearchResult() }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = desc
                this.duration        = duration
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = desc
                this.recommendations = recommendations
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Load Links (video extraction)  – three-stage approach
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = cfGet(data)

        // ── Stage 1: Direct download link (fastest, no WebView) ─────────────
        val downloadHref = doc.select(".downloadLinks a").attr("href")
        if (downloadHref.isNotBlank()) {
            try {
                val playerDoc = app.post(downloadHref, referer = mainUrl, timeout = 120).document
                val dlLink    = playerDoc.select("div.dl-link a").attr("href")
                if (dlLink.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(name, "$name Direct", dlLink, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (_: Exception) {}
        }

        // ── Locate player iframe URL ─────────────────────────────────────────
        val iframeSrc = doc.select(
            "iframe[name=\"player_iframe\"], iframe[src*=\"video_player\"]"
        ).attr("src").ifEmpty {
            // fallback: onclick tab
            val onclick = doc.selectFirst("ul.tabs-ul li[onclick], li.active[onclick]")
                ?.attr("onclick")
            Regex("""'([^']+)'""").find(onclick ?: "")
                ?.groupValues?.get(1)?.let { fixUrl(it) } ?: ""
        }

        if (iframeSrc.isBlank()) return false

        // ── Stage 2: Fetch player HTML via HTTP + cfKiller, scan for m3u8 ───
        // This avoids the WebView entirely and bypasses CF via HTTP.
        try {
            val playerText = app.get(
                iframeSrc,
                referer = data,
                interceptor = cfKiller,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text

            // Remove obfuscation (concatenated strings)
            val cleaned = playerText.replace(Regex("""['"]\s*\+\s*['"]"""), "")

            val videoUrl = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""").find(cleaned)?.value
            if (!videoUrl.isNullOrBlank()) {
                M3u8Helper.generateM3u8(name, videoUrl, referer = mainUrl).forEach(callback)
                return true
            }
            // Also try mp4
            val mp4Url = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""").find(cleaned)?.value
            if (!mp4Url.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(name, "$name MP4", mp4Url, ExtractorLinkType.VIDEO) {
                        this.referer = iframeSrc
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) {}

        // ── Stage 3: WebView with CF cookies passed in headers ───────────────
        // Pass the Cloudflare cookies we already have so the WebView doesn't
        // hit the challenge page again.
        return try {
            val cfHeaders = cfKiller.getCookieHeaders(mainUrl).toMap()
            val resolver  = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                script = """
                    (function() {
                        var sels = ['.play-button','.btn-play','button.play',
                                    '.play-btn','.vjs-big-play-button','.jw-icon-display'];
                        for (var i = 0; i < sels.length; i++) {
                            var el = document.querySelector(sels[i]);
                            if (el) { el.click(); break; }
                        }
                    })();
                """.trimIndent()
            )
            val result = resolver.resolveUsingWebView(
                requestCreator(
                    "GET", iframeSrc,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data) + cfHeaders
                )
            )
            val videoUrl = result.first?.url?.toString()
            if (!videoUrl.isNullOrBlank() && videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, videoUrl, referer = mainUrl).forEach(callback)
                true
            } else false
        } catch (_: Exception) { false }
    }
}
