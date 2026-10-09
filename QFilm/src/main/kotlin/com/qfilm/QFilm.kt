@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.qfilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder
import okhttp3.Interceptor

class QFilm : MainAPI() {
    override var mainUrl = "https://a.qfilm.tv"
    override var name = "QFilm"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller

    private fun buildHeaders(referer: String = "https://a.qfilm.tv/"): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
        "Referer" to referer
    )

    // The homepage (index.php) has multiple category carousels
    override val mainPage = mainPageOf(
        "$mainUrl/index.php" to "الصفحة الرئيسية",
        "$mainUrl/category.php?cat=2026-movies" to "أفلام 2026",
        "$mainUrl/category.php?cat=arabic-movies" to "أفلام عربي",
        "$mainUrl/category.php?cat=indian-movies" to "أفلام هندي",
        "$mainUrl/category.php?cat=foreign-movies" to "أفلام أجنبي",
        "$mainUrl/category.php?cat=arabic-series" to "مسلسلات عربي",
        "$mainUrl/category.php?cat=foreign-series" to "مسلسلات أجنبية",
    )

    private fun parseCard(el: Element): SearchResponse? {
        val linkEl = el.selectFirst("a[href*=watch.php]") ?: return null
        val href = linkEl.attr("href").let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }
        val title = linkEl.attr("title")
            .ifBlank { el.selectFirst("h3.caption")?.text() }
            ?.ifBlank { return null }
            ?: return null
        // Poster: data-echo attribute on the img (lazy-loaded)
        val poster = el.selectFirst("img[data-echo]")?.attr("data-echo")
            ?: el.selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The index page has all categories built in on page 1
        // Category pages support pagination
        if (request.data == "$mainUrl/index.php") {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val doc = app.get(
                "$mainUrl/index.php",
                referer = "$mainUrl/",
                headers = buildHeaders(),
                interceptor = cfInterceptor
            ).document

            val allSections = mutableListOf<HomePageList>()

            // Parse each carousel section: pm-section-head h3 + pm-ul-carousel-videos
            for (row in doc.select("div.row:has(div.pm-section-head)")) {
                val catName = row.selectFirst("div.pm-section-head h3 a")?.text()
                    ?: row.selectFirst("div.pm-section-head h3")?.text()
                    ?: continue
                val items = row.select("li:has(div.thumbnail)").mapNotNull { parseCard(it) }
                if (items.isNotEmpty()) {
                    allSections.add(HomePageList(catName, items, true))
                }
            }

            if (allSections.isEmpty()) {
                // Fallback: parse all thumbnail cards
                val items = doc.select("li:has(div.thumbnail)").mapNotNull { parseCard(it) }
                allSections.add(HomePageList("جديد الموقع", items))
            }

            return newHomePageResponse(allSections)
        }

        // Category pages
        val url = "${request.data}&page=$page"
        val doc = app.get(
            url,
            referer = "$mainUrl/",
            headers = buildHeaders(),
            interceptor = cfInterceptor
        ).document
        val items = doc.select("li:has(div.thumbnail), div.thumbnail").mapNotNull { parseCard(it) }
        val hasNext = doc.selectFirst("a[rel=next], .pagination li:last-child a") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, referer = "$mainUrl/", headers = buildHeaders(), interceptor = cfInterceptor).document
        return doc.select("li:has(div.thumbnail), div.thumbnail").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/", headers = buildHeaders(), interceptor = cfInterceptor).document

        // Title: strip site name from <title>
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()
                ?.replace("- كيو فيلم", "")
                ?.replace("مشاهدة فيلم", "")
                ?.trim()
            ?: "Unknown"

        // Poster: use high-res thumb from itemprop or og:image
        val poster = doc.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // The data passed to loadLinks is the watch URL; loadLinks will derive embed.php from it
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("QFilm", "loadLinks: $data")

        // Extract vid from URL: watch.php?vid=XXXX
        val vid = Regex("""vid=([a-zA-Z0-9]+)""").find(data)?.groupValues?.get(1)
            ?: return false

        val embedUrl = "$mainUrl/embed.php?vid=$vid"
        Log.d("QFilm", "Loading embed: $embedUrl")

        val embedDoc = app.get(
            embedUrl,
            referer = data,
            headers = buildHeaders(data),
            interceptor = cfInterceptor
        ).document

        var found = false

        // embed.php has <option value="SERVER_URL"> for each server
        // plus a default <iframe src="DEFAULT_URL">
        val serverUrls = linkedSetOf<String>()

        for (opt in embedDoc.select("option[value]")) {
            val v = opt.attr("value").trim()
            if (v.startsWith("http")) serverUrls.add(v)
        }
        // Also grab default iframe
        for (iframe in embedDoc.select("iframe[src]")) {
            val s = iframe.attr("src").trim()
            if (s.startsWith("http")) serverUrls.add(s)
        }

        Log.d("QFilm", "Found ${serverUrls.size} server(s): $serverUrls")

        for (serverUrl in serverUrls) {
            try {
                Log.d("QFilm", "Trying server: $serverUrl")
                if (loadExtractor(serverUrl, embedUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                Log.w("QFilm", "loadExtractor failed for $serverUrl: ${e.message}")
            }
        }

        return found
    }
}
