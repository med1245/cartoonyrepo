@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class FaselHDX : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://web340x.faselhdx.bid"
    override var name = "FaselHD"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val cfKiller = CloudflareKiller()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.getIntFromText(): Int? =
        Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = select("div.postDiv a").firstOrNull() ?: return null
        val url = a.attr("abs:href").ifBlank { a.attr("href") }.ifBlank { return null }
        val img = select("div.postDiv a div img")
        val posterUrl = img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
        val title = img.attr("alt").ifBlank { a.text() }
        val quality = select(".quality").firstOrNull()?.text()?.replace(Regex("1080p |-"), "")
        val type = if (title.contains("فيلم", ignoreCase = true)) TvType.Movie else TvType.TvSeries
        return MovieSearchResponse(
            name = title.replace(Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي"), "").trim(),
            url = url,
            apiName = this@FaselHDX.name,
            type = type,
            posterUrl = posterUrl,
            year = null,
            quality = getQualityFromString(quality)
        )
    }

    private suspend fun fetchDocument(url: String) =
        try {
            val doc = app.get(url, timeout = 30).document
            if (doc.select("title").text().contains("Just a moment", ignoreCase = true)) {
                app.get(url, interceptor = cfKiller, timeout = 120).document
            } else doc
        } catch (e: Exception) {
            app.get(url, interceptor = cfKiller, timeout = 120).document
        }

    private fun postListSelector() =
        "div[id=postList] div[class=col-xl-2 col-lg-2 col-md-3 col-sm-3]"

    // ── Main Page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/all-movies/page/" to "جميع الافلام",
        "$mainUrl/movies_top_views/page/" to "الافلام الاعلي مشاهدة",
        "$mainUrl/dubbed-movies/page/" to "الأفلام المدبلجة",
        "$mainUrl/movies_top_imdb/page/" to "الافلام الاعلي تقييما IMDB",
        "$mainUrl/series/page/" to "مسلسلات أجنبي",
        "$mainUrl/recent_series/page/" to "المضاف حديثاً (مسلسلات)",
        "$mainUrl/anime/page/" to "الأنمي",
        "$mainUrl/recent_anime/page/" to "المضاف حديثاً (أنمي)",
        "$mainUrl/asian-series/page/" to "مسلسلات آسيوي",
        "$mainUrl/most_recent/page/" to "المضاف حديثاً (الكل)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetchDocument(request.data + page)
        val list = doc.select(postListSelector()).mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, list)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val doc = fetchDocument("$mainUrl/?s=$q")
        return doc.select(postListSelector()).mapNotNull { it.toSearchResponse() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url)

        val isMovie = doc.select("div.epAll").isEmpty()

        val posterUrl = doc.select("div.posterImg img").attr("src")
            .ifBlank { doc.select("div.seasonDiv.active img").attr("data-src") }
            .ifBlank { null }

        val infoItems = doc.select("div[id=singleList] div[class=col-xl-6 col-lg-6 col-md-6 col-sm-6]")

        val year = infoItems.firstOrNull { it.text().contains(Regex("سنة|موعد")) }
            ?.text()?.getIntFromText()

        val duration = infoItems.firstOrNull { it.text().contains(Regex("مدة|توقيت")) }
            ?.text()?.getIntFromText()

        val title = doc.select("title").text()
            .replace(" - فاصل إعلاني", "")
            .replace(" - فاصل اعلاني", "")
            .replace(Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year"), "")
            .trim()

        val tags = doc.select("div[id=singleList] div[class=col-xl-6 col-lg-6 col-md-6 col-sm-6]:contains(تصنيف) a")
            .map { it.text() }

        val synopsis = doc.select("div.singleDesc p").text().ifBlank {
            doc.select("div.singleDesc").text()
        }.ifBlank { null }

        val recommendations = doc.select("div#postList div.postDiv").mapNotNull {
            it.toSearchResponse()
        }

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()

            // Active season episodes
            doc.select("div.epAll a").forEach { a ->
                episodes.add(
                    Episode(
                        data = a.attr("abs:href").ifBlank { a.attr("href") },
                        name = a.text(),
                        season = doc.select("div.seasonDiv.active div.title").text().getIntFromText() ?: 1,
                        episode = a.text().getIntFromText()
                    )
                )
            }

            // Other seasons — fetch each season page
            doc.select("div[id=seasonList] div[class=col-xl-2 col-lg-3 col-md-6] div.seasonDiv")
                .not(".active")
                .apmap { seasonDiv ->
                    val id = seasonDiv.attr("onclick").replace(Regex(".*/\\?p=|'"), "")
                    if (id.isBlank()) return@apmap
                    val seasonDoc = fetchDocument("$mainUrl/?p=$id")
                    val seasonNum = seasonDoc.select("div.seasonDiv.active div.title").text().getIntFromText()
                    seasonDoc.select("div.epAll a").forEach { a ->
                        episodes.add(
                            Episode(
                                data = a.attr("abs:href").ifBlank { a.attr("href") },
                                name = a.text(),
                                season = seasonNum,
                                episode = a.text().getIntFromText()
                            )
                        )
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
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
        var doc = app.get(data).document
        if(doc.select("title").text() == "Just a moment...") {
            doc = app.get(data, interceptor = cfKiller).document
        }

        var found = false

        // 1. Get Download Link (Ported from old Arabico provider)
        val downloadUrl = doc.select(".downloadLinks a, .dl-links a").attr("href")
        if (downloadUrl.isNotBlank()) {
            try {
                // The old provider does a POST request to the download page to get the direct link
                val playerDoc = app.post(downloadUrl, interceptor = cfKiller, referer = mainUrl, timeout = 120).document
                val directLink = playerDoc.select("div.dl-link a").attr("href")
                if (directLink.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name + " Download Source",
                            directLink,
                            this.mainUrl,
                            Qualities.Unknown.value,
                            directLink.contains(".m3u8")
                        )
                    )
                    found = true
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // 2. Get Iframe Link
        // The new domain hides the iframe src in data-src or in the li onclick
        val iframeSrc = doc.select("iframe[name=player_iframe]").let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }.ifBlank {
            val lis = doc.select("li[onclick*=player_iframe], ul.tabs-ul li[onclick]")
            val firstLi = lis.firstOrNull()
            if (firstLi != null) {
                val match = Regex("""href\s*=\s*'([^']+)'""").find(firstLi.attr("onclick"))
                match?.groupValues?.getOrNull(1) ?: ""
            } else ""
        }.ifBlank { null }

        if (iframeSrc != null) {
            try {
                // Ported from old Arabico provider: direct WebView on the iframe URL
                val webView = WebViewResolver(
                    Regex("""master\.m3u8""")
                ).resolveUsingWebView(
                    iframeSrc, referer = mainUrl
                ).first
                
                val videoUrl = webView?.url?.toString()
                if (!videoUrl.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        this.name,
                        videoUrl,
                        referer = mainUrl
                    ).toList().forEach { callback.invoke(it) }
                    found = true
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return found
    }
}


