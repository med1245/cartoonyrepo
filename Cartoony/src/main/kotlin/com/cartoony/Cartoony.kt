package com.cartoony

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import java.net.URLEncoder

class Cartoony : MainAPI() {
    override var mainUrl = "https://cartoony.net"
    override var name = "Cartoony"
    override val hasMainPage = false
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val doc = app.get(url).document

        // Collect candidate links and filter to likely titles
        val candidates = doc.select("a[href]")
        val results = candidates.mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isNullOrEmpty()) return@mapNotNull null

            val isContent =
                href.contains("/watch", ignoreCase = true) ||
                href.contains("/series", ignoreCase = true) ||
                href.contains("/movie", ignoreCase = true) ||
                href.contains("/cartoon", ignoreCase = true) ||
                href.contains("%d9%85%d8%b3%d9%84%d8%b3%d9%84", ignoreCase = true) || // مسلسل
                href.contains("%d9%81%d9%8a%d9%84%d9%85", ignoreCase = true)         // فيلم

            if (!isContent) return@mapNotNull null

            val title = a.attr("title")
                .ifBlank { a.text().trim() }
                .ifBlank { a.selectFirst("img")?.attr("alt") ?: "" }
                .ifBlank { return@mapNotNull null }

            val absolute = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith('/')) "" else "/"}$href"
            newAnimeSearchResponse(title, absolute) { }
        }.distinctBy { it.url }

        return results
    }
}

