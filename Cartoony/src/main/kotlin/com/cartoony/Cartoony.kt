package com.cartoony

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import kotlin.math.min

class Cartoony : MainAPI() {
    override var mainUrl = "https://cartoony.net"
    override var name = "Cartoony"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries)

    private val reqHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "https://cartoony.net/"
    )

    override val mainPage = mainPageOf(
        Pair("latest", "Latest")
    )

    private fun parseProxyMarkdown(text: String): List<SearchResponse> {
        // Matches markdown links: [ ...title... ](https://cartoony.net/watch/123)
        val linkRegex = Regex("""\[[^\]]+]\((https?://cartoony\.net/watch/\d+)\)""")
        val imgRegex = Regex("""!\[[^\]]*]\([^)]+\)\s*""") // remove leading image tags
        return linkRegex.findAll(text).mapNotNull { m ->
            val full = m.value
            val url = m.groupValues[1]
            var inside = full.substring(1, full.indexOf(']')) // contents between first [ and ]
            inside = imgRegex.replace(inside, "").trim()
            // Heuristic: keep last 40 chars to avoid long badges "24 حلقة ... "
            val title = inside.split('\n', '•').lastOrNull()?.trim().orEmpty()
                .takeIf { it.isNotEmpty() } ?: "غير معنون"
            newAnimeSearchResponse(title, url) { }
        }.distinctBy { it.url }.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val res = app.get(url, headers = reqHeaders)
        val doc = res.document

        // Cartoony uses /watch/{id} for content pages
        val candidates = doc.select("a[href*=/watch/]")
        val results = candidates.mapNotNull { a ->
            val href = a.attr("href")?.trim() ?: return@mapNotNull null
            if (href.isNullOrEmpty()) return@mapNotNull null
            if (!href.contains("/watch/")) return@mapNotNull null

            val title = (a.attr("title")?.trim().orEmpty())
                .ifBlank { a.text()?.trim().orEmpty() }
                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { return@mapNotNull null }

            val absolute = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith('/')) "" else "/"}$href"
            newAnimeSearchResponse(title, absolute) { }
        }.distinctBy { it.url }

        // Fallback: if nothing parsed (likely CF/JS page), use text proxy
        if (results.isEmpty()) {
            val proxyUrl = "https://r.jina.ai/http://cartoony.net/?s=$encoded"
            val proxyText = app.get(proxyUrl, headers = reqHeaders).text
            val proxied = parseProxyMarkdown(proxyText)
            if (proxied.isNotEmpty()) return proxied
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val res = app.get(url, headers = reqHeaders)
        val doc = res.document
        val items = doc.select("a[href*=/watch/]")
            .mapNotNull { a ->
                val href = a.attr("href")?.trim() ?: return@mapNotNull null
                if (!href.contains("/watch/")) return@mapNotNull null
                val title = (a.attr("title")?.trim().orEmpty())
                    .ifBlank { a.text()?.trim().orEmpty() }
                    .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                    .ifBlank { return@mapNotNull null }
                val absolute = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith('/')) "" else "/"}$href"
                newAnimeSearchResponse(title, absolute) { }
            }.distinctBy { it.url }
        if (items.isNotEmpty()) return newHomePageResponse(request.name, items)

        // Fallback via proxy if empty
        val proxyUrl = if (page <= 1) "https://r.jina.ai/http://cartoony.net/" else "https://r.jina.ai/http://cartoony.net/page/$page/"
        val proxyText = app.get(proxyUrl, headers = reqHeaders).text
        val proxied = parseProxyMarkdown(proxyText)
        return newHomePageResponse(request.name, proxied)
    }
}

