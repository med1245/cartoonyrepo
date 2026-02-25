package com.cartoony

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.newAnimeSearchResponse

class Cartoony : MainAPI() {
    override var mainUrl = "https://cartoony.net"
    override var name = "Cartoony"
    override val hasMainPage = false
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim().replace(' ', '+')}"
        val doc = Jsoup.connect(url).get()
        val anchors = doc.select("a[href*=\"/watch/\"]")
        return anchors.mapNotNull { a ->
            val href = a.attr("href")
            if (!href.contains("/watch/")) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text().trim() }.ifBlank { "بدون عنوان" }
            newAnimeSearchResponse(title, if (href.startsWith("http")) href else mainUrl + href) { }
        }.distinctBy { it.url }
    }
}

