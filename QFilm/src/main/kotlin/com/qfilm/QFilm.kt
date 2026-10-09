@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.qfilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URLEncoder

class QFilm : MainAPI() {
    override var mainUrl = "https://a.qfilm.tv"
    override var name = "QFilm"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php?page=" to "جديد الموقع",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select("a[href*=watch]").take(20).mapNotNull { el ->
            val href = el.attr("href").ifBlank { el.attr("data-url") }
            val title = el.attr("title").ifBlank { el.text() }
            if (href.isBlank() || title.isBlank()) return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { 
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, referer = "$mainUrl/").document
        return doc.select("a[href*=watch]").mapNotNull { el ->
            val href = el.attr("href").ifBlank { return@mapNotNull null }
            val title = el.attr("title").ifBlank { el.text() }.ifBlank { return@mapNotNull null }
            val poster = el.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.e("QFilmProvider", "🔴 LOAD CALLED: $url")
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1, h2, .title")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        
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
        Log.e("QFilmProvider", "🟢 LOADLINKS CALLED: $data")
        
        // Add test link
        callback(
            ExtractorLink(
                "QFilm-TEST",
                "🔴 TEST LINK - CALLBACK WORKS!",
                "https://test.com/test.mp4",
                "",
                Qualities.Unknown.value,
                false
            )
        )
        
        try {
            val doc = app.get(data, referer = "$mainUrl/").document
            
            // Try to find video links
            doc.select("a[href*=.mp4], a[href*=.m3u8]").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && (href.contains(".mp4") || href.contains(".m3u8"))) {
                    callback(
                        ExtractorLink(
                            "QFilm",
                            "مباشر",
                            href,
                            data,
                            Qualities.Unknown.value,
                            false
                        )
                    )
                }
            }
            
            // Try iframe sources
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    callback(
                        ExtractorLink(
                            "QFilm",
                            "Embedded",
                            src,
                            data,
                            Qualities.Unknown.value,
                            false,
                            headers = mapOf("User-Agent" to "Mozilla/5.0")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("QFilmProvider", "Error in loadLinks: ${e.message}")
        }
        
        return true
    }
}
