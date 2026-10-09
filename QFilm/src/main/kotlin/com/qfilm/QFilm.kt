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

    private val tag = "QFilm"
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller

    private fun buildHeaders(referer: String = "https://a.qfilm.tv/"): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
        "Referer" to referer
    )

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
        val poster = el.selectFirst("img[data-echo]")?.attr("data-echo")
            ?: el.selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "$mainUrl/index.php") {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val doc = app.get(
                "$mainUrl/index.php",
                referer = "$mainUrl/",
                headers = buildHeaders(),
                interceptor = cfInterceptor
            ).document
            val allSections = mutableListOf<HomePageList>()
            for (row in doc.select("div.row:has(div.pm-section-head)")) {
                val catName = row.selectFirst("div.pm-section-head h3 a")?.text()
                    ?: row.selectFirst("div.pm-section-head h3")?.text()
                    ?: continue
                val items = row.select("li:has(div.thumbnail)").mapNotNull { parseCard(it) }
                if (items.isNotEmpty()) allSections.add(HomePageList(catName, items, true))
            }
            if (allSections.isEmpty()) {
                val items = doc.select("li:has(div.thumbnail)").mapNotNull { parseCard(it) }
                allSections.add(HomePageList("جديد الموقع", items))
            }
            return newHomePageResponse(allSections)
        }
        val url = "${request.data}&page=$page"
        val doc = app.get(url, referer = "$mainUrl/", headers = buildHeaders(), interceptor = cfInterceptor).document
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
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()
                ?.replace("- كيو فيلم", "")
                ?.replace("مشاهدة فيلم", "")
                ?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
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
        Log.d(tag, "loadLinks: $data")

        val vid = Regex("""vid=([a-zA-Z0-9]+)""").find(data)?.groupValues?.get(1)
            ?: run {
                Log.e(tag, "No vid parameter found in: $data")
                return false
            }

        val embedUrl = "$mainUrl/embed.php?vid=$vid"
        Log.d(tag, "Fetching embed page: $embedUrl")

        val embedResp = try {
            app.get(embedUrl, referer = data, headers = buildHeaders(data), interceptor = cfInterceptor)
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch embed page: ${e.message}")
            return false
        }

        Log.d(tag, "Embed page status: ${embedResp.code}, url: ${embedResp.url}")
        val embedDoc = embedResp.document

        // Collect all server URLs from <option value="..."> elements
        val serverUrls = linkedSetOf<String>()
        for (opt in embedDoc.select("option[value]")) {
            val v = opt.attr("value").trim()
            if (v.startsWith("http")) serverUrls.add(v)
        }
        // Also grab default iframe src
        for (iframe in embedDoc.select("iframe[src]")) {
            val s = iframe.attr("src").trim()
            if (s.startsWith("http")) serverUrls.add(s)
        }

        Log.d(tag, "Found ${serverUrls.size} server URLs: $serverUrls")

        if (serverUrls.isEmpty()) {
            Log.e(tag, "No server URLs found in embed page HTML")
            return false
        }

        var found = false

        for (serverUrl in serverUrls) {
            Log.d(tag, "Processing server: $serverUrl")
            try {
                val result = resolveServer(serverUrl, embedUrl, subtitleCallback, callback)
                if (result) {
                    found = true
                    Log.d(tag, "Got links from: $serverUrl")
                }
            } catch (e: Exception) {
                Log.w(tag, "Server $serverUrl failed: ${e.message}")
            }
        }

        Log.d(tag, "loadLinks result: found=$found")
        return found
    }

    /**
     * Resolve a single embed server URL to playable links.
     *
     * Strategy:
     *  1. For vidmoly.* domains: the m3u8 URL is in the initial HTML response
     *     inside `sources: [{ file: '...' }]` — extract it directly with a regex.
     *  2. For all other domains: try CloudStream's loadExtractor first.
     *     If that returns false, fall back to fetching the page and scanning
     *     the HTML for m3u8/mp4 URLs.
     */
    private suspend fun resolveServer(
        serverUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // --- Strategy 1: vidmoly.* — m3u8 is plaintext in the page HTML ---
        if (serverUrl.contains("vidmoly", ignoreCase = true)) {
            return resolveVidmoly(serverUrl, referer, callback)
        }

        // --- Strategy 2: try CloudStream's built-in extractor registry ---
        val extractorResult = try {
            loadExtractor(serverUrl, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.w(tag, "loadExtractor threw for $serverUrl: ${e.message}")
            false
        }
        if (extractorResult) {
            Log.d(tag, "loadExtractor succeeded for: $serverUrl")
            return true
        }

        // --- Strategy 3: fallback — fetch the page and scan for raw video URLs ---
        Log.d(tag, "loadExtractor failed for $serverUrl, trying direct page scan")
        return resolveByPageScan(serverUrl, referer, callback)
    }

    /**
     * vidmoly.biz (and other vidmoly.* domains) include the HLS master URL
     * directly in plaintext JS inside the initial HTTP response:
     *   sources: [{ file: 'https://....master.m3u8?...' }],
     * No JS execution needed — just fetch and regex.
     */
    private suspend fun resolveVidmoly(
        serverUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = try {
            app.get(serverUrl, referer = referer, headers = buildHeaders(referer))
        } catch (e: Exception) {
            Log.w(tag, "vidmoly fetch failed: ${e.message}")
            return false
        }

        val html = resp.text
        // The m3u8 is in: sources: [{ file: 'URL' }]
        val m3u8 = Regex("""file:\s*'(https?://[^']+\.m3u8[^']*)'""")
            .find(html)?.groupValues?.get(1)

        if (m3u8.isNullOrBlank()) {
            Log.w(tag, "No m3u8 found in vidmoly page: $serverUrl")
            return false
        }

        Log.d(tag, "vidmoly m3u8 extracted: ${m3u8.take(80)}...")
        val links = M3u8Helper.generateM3u8(name, m3u8, serverUrl)
        for (link in links) {
            callback(link)
        }
        return links.isNotEmpty()
    }

    /**
     * Generic fallback: fetch the embed page and look for any m3u8 or mp4 URL
     * in the HTML source.
     */
    private suspend fun resolveByPageScan(
        serverUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = try {
            app.get(serverUrl, referer = referer, headers = buildHeaders(referer))
        } catch (e: Exception) {
            Log.w(tag, "Page scan fetch failed for $serverUrl: ${e.message}")
            return false
        }

        val html = resp.text
        var found = false

        // Look for m3u8
        val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
        for (match in m3u8Regex.findAll(html)) {
            val url = match.groupValues[1]
            if (url.isNotBlank()) {
                Log.d(tag, "Page scan found m3u8: ${url.take(80)}")
                val links = M3u8Helper.generateM3u8(name, url, serverUrl)
                for (link in links) { callback(link) }
                if (links.isNotEmpty()) found = true
            }
        }

        // Look for mp4 if no m3u8 found
        if (!found) {
            val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
            for (match in mp4Regex.findAll(html)) {
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    Log.d(tag, "Page scan found mp4: ${url.take(80)}")
                    callback(
                        ExtractorLink(
                            name, name, url, serverUrl,
                            Qualities.Unknown.value, false,
                            headers = mapOf("Referer" to serverUrl)
                        )
                    )
                    found = true
                }
            }
        }

        return found
    }
}
