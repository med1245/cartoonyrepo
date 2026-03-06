@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.egibest

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64

class Egibest : MainAPI() {
    override var mainUrl = "https://egibest.org"
    override var name = "EgyBest"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,*/*"
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/last/", "Latest"),
        Pair("$mainUrl/movies/", "Movies"),
        Pair("$mainUrl/series/", "Series"),
        Pair("$mainUrl/category/anime/", "Anime")
    )

    private suspend fun fetchDoc(url: String): Document? =
        try { app.get(url, headers = headers()).document } catch (_: Exception) { null }

    private fun normalizeUrl(url: String): String {
        if (url.startsWith("http")) return url
        return if (url.startsWith("/")) "$mainUrl$url" else "$mainUrl/$url"
    }

    private fun inferType(title: String): TvType {
        return when {
            title.contains("فيلم") -> TvType.Movie
            title.contains("مسلسل") || title.contains("الحلقة") -> TvType.TvSeries
            title.contains("انمي") || title.contains("أنمي") -> TvType.Anime
            else -> TvType.TvSeries
        }
    }

    private fun elementToSearch(el: Element): SearchResponse? {
        val a = el.selectFirst("a[href]") ?: return null
        val href = a.absUrl("href").ifBlank { a.attr("href") }.ifBlank { return null }
        if (href.contains("/category/") || href.contains("/genre/") || href.contains("/tag/") || href.contains("/actor/")) return null
        val img = a.selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }?.trim().orEmpty().ifBlank { null }
        val title = img?.attr("alt")?.trim()
            ?.ifBlank { a.attr("title").trim() }
            ?.ifBlank { a.text().trim() }
            ?: return null
        val type = inferType(title)
        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val blocks = doc.select("article, .post, .postDiv, .movie, .film, .item, .box, .content")
        val results = blocks.mapNotNull { elementToSearch(it) }.toMutableList()
        if (results.isNotEmpty()) return results.distinctBy { it.url }
        doc.select("a[href] img").forEach { img ->
            val a = img.parent() ?: return@forEach
            val href = a.absUrl("href").ifBlank { a.attr("href") }.ifBlank { return@forEach }
            if (href.contains("/category/") || href.contains("/genre/") || href.contains("/tag/")) return@forEach
            val title = img.attr("alt").trim().ifBlank { a.attr("title").trim() }.ifBlank { return@forEach }
            val poster = img.attr("data-src").ifBlank { img.attr("src") }.trim().ifBlank { null }
            val type = inferType(title)
            results.add(newAnimeSearchResponse(title, href, type) { posterUrl = poster })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val doc = fetchDoc(url) ?: return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        if (q.isBlank()) return emptyList()
        val doc = fetchDoc("$mainUrl/?s=$q") ?: return emptyList()
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(url) ?: return null
        val title = doc.selectFirst("h1, .post-title, .entry-title, .title")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("img[src*=\"wp-content\"]")?.attr("src")
            ?.ifBlank { doc.selectFirst("img[data-src*=\"wp-content\"]")?.attr("data-src") }
        val plot = doc.selectFirst(".story, .post-content, .entry-content, .post-inner, .description")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        val episodeLinks = doc.select("a[href]").filter {
            val t = it.text().trim()
            t.contains("الحلقة") || t.contains("Episode", ignoreCase = true) || it.attr("href").contains("episode")
        }.mapNotNull {
            val epUrl = it.absUrl("href").ifBlank { it.attr("href") }
            val num = Regex("\\d+").find(it.text())?.value?.toIntOrNull()
            if (epUrl.isBlank()) null else newEpisode(epUrl) { episode = num ?: 1 }
        }.distinctBy { it.data }

        return if (episodeLinks.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeLinks) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    private fun decodeMaybeBase64(input: String): String {
        return runCatching { String(Base64.getDecoder().decode(input)) }.getOrDefault(input)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchDoc(data) ?: return false
        val candidates = mutableSetOf<String>()

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank()) candidates.add(src)
        }

        doc.select("[data-url],[data-src],[data-embed],[data-embed-url]").forEach { el ->
            listOf("data-url", "data-src", "data-embed", "data-embed-url").forEach { key ->
                val v = el.attr(key).trim()
                if (v.isNotBlank()) candidates.add(v)
            }
        }

        val scripts = doc.select("script").joinToString("\n") { it.data() }
        Regex("""https?://[^'"\s>]+""").findAll(scripts).forEach { m ->
            val url = m.value
            if (url.contains(".m3u8") || url.contains("embed") || url.contains("player") || url.contains("watch")) {
                candidates.add(url)
            }
        }

        val normalized = candidates.map { normalizeUrl(it) }.distinct()
        var found = false

        normalized.forEach { url ->
            if (url.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, url, referer = data).forEach { callback(it) }
                found = true
            } else {
                runCatching {
                    val web = WebViewResolver(interceptUrl = Regex(""".*\.(m3u8|mp4).*"""))
                        .resolveUsingWebView(requestCreator("GET", url, referer = data)).first
                    val videoUrl = web?.url?.toString()
                    if (!videoUrl.isNullOrBlank()) {
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(name, videoUrl, referer = url).forEach { callback(it) }
                        } else {
                            callback(ExtractorLink(name, name, videoUrl, url, Qualities.Unknown.value, false))
                        }
                        found = true
                    }
                }
            }
        }

        if (!found) {
            val urlRegex = Regex("""(?i)(?:file|source)\s*:\s*['"]([^'"]+)['"]""")
            urlRegex.findAll(doc.data()).forEach { m ->
                val raw = m.groupValues[1]
                val decoded = URLDecoder.decode(decodeMaybeBase64(raw), "UTF-8")
                val finalUrl = normalizeUrl(decoded)
                if (finalUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, finalUrl, referer = data).forEach { callback(it) }
                    found = true
                }
            }
        }

        return found
    }
}
