@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.qfilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URLEncoder
import okhttp3.Interceptor

class QFilm : MainAPI() {
    override var mainUrl = "https://a.qfilm.tv"
    override var name = "QFilm"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true
    
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller

    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php?page=" to "جديد الموقع",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        val doc = app.get(
            url, 
            referer = "$mainUrl/",
            headers = headers,
            interceptor = cfInterceptor,
            timeout = 30
        ).document
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        val doc = app.get(
            url, 
            referer = "$mainUrl/",
            headers = headers,
            interceptor = cfInterceptor,
            timeout = 30
        ).document
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
        println("🔴 LOAD: $url")
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ar,en;q=0.9",
            "Cache-Control" to "no-cache"
        )
        
        val doc = app.get(
            url, 
            referer = "$mainUrl/",
            headers = headers,
            interceptor = cfInterceptor,
            timeout = 30
        ).document
        
        val title = doc.selectFirst("h1, h2, .title, [class*=title]")?.text()?.trim() ?: "Unknown"
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
        println("🟢 LOADLINKS: $data")
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val doc = app.get(
                data, 
                referer = "$mainUrl/",
                headers = headers,
                interceptor = cfInterceptor,
                timeout = 30
            ).document
            
            var foundCount = 0
            
            // Find all video/stream links
            doc.select("a, iframe, source, [data-url], [data-src], [data-link]").forEach { el ->
                val url = el.attr("href").ifBlank { 
                    el.attr("src").ifBlank { 
                        el.attr("data-url").ifBlank { 
                            el.attr("data-src").ifBlank { el.attr("data-link") }
                        }
                    }
                }
                
                if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8") || url.contains("watch") || url.contains("play"))) {
                    val title = el.attr("title").ifBlank { el.text().take(50) }
                    callback(
                        ExtractorLink(
                            "QFilm",
                            title.ifBlank { "مباشر" },
                            url,
                            data,
                            Qualities.Unknown.value,
                            false,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Referer" to data
                            )
                        )
                    )
                    foundCount++
                }
            }
            
            Log.d("QFilmProvider", "Found $foundCount links")
            println("✅ Found $foundCount links")
            return foundCount > 0
        } catch (e: Exception) {
            Log.e("QFilmProvider", "Error in loadLinks: ${e.message}", e)
            println("❌ Error: ${e.message}")
            return false
        }
    }
}
