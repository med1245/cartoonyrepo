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

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val doc = app.get(url, headers = reqHeaders).document

        val candidates = doc.select("a[href], article a, .post a, .entry-title a, h2 a, h3 a")
        val results = candidates.mapNotNull { a ->
            val href = a.attr("href")?.trim() ?: return@mapNotNull null
            if (href.isNullOrEmpty()) return@mapNotNull null

            // Filter obvious non-content links
            val isLikelyContent =
                href.startsWith("/") || href.startsWith(mainUrl) && !href.contains("#") &&
                !href.contains("/tag/") && !href.contains("/category/") && !href.contains("/page/")
            if (!isLikelyContent) return@mapNotNull null

            val title = (a.attr("title")?.trim().orEmpty())
                .ifBlank { a.text()?.trim().orEmpty() }
                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { return@mapNotNull null }

            val absolute = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith('/')) "" else "/"}$href"
            newAnimeSearchResponse(title, absolute) { }
        }.distinctBy { it.url }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url, headers = reqHeaders).document
        val items = doc.select("a[href], article a, .post a, .entry-title a, h2 a, h3 a")
            .mapNotNull { a ->
                val href = a.attr("href")?.trim() ?: return@mapNotNull null
                val title = (a.attr("title")?.trim().orEmpty())
                    .ifBlank { a.text()?.trim().orEmpty() }
                    .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                    .ifBlank { return@mapNotNull null }
                val isLikelyContent =
                    (href.startsWith("/") || href.startsWith(mainUrl)) &&
                    !href.contains("#") && !href.contains("/tag/") &&
                    !href.contains("/category/") && !href.contains("/page/")
                if (!isLikelyContent) return@mapNotNull null
                val absolute = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith('/')) "" else "/"}$href"
                newAnimeSearchResponse(title, absolute) { }
            }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }
}

