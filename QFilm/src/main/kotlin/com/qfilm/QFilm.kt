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
            val html = doc.outerHtml()
            
            // Debug: Log HTML to find video links
            Log.d("QFilmProvider", "Page HTML length: ${html.length}")
            
            // Strategy 1: Look for direct video URLs in JavaScript
            val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            
            mp4Regex.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    Log.d("QFilmProvider", "Found MP4: $url")
                    callback(
                        ExtractorLink(
                            "QFilm",
                            "مباشر (MP4)",
                            url,
                            data,
                            Qualities.Unknown.value,
                            false,
                            headers = mapOf("Referer" to data)
                        )
                    )
                    foundCount++
                }
            }
            
            m3u8Regex.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    Log.d("QFilmProvider", "Found M3U8: $url")
                    callback(
                        ExtractorLink(
                            "QFilm",
                            "مباشر (HLS)",
                            url,
                            data,
                            Qualities.Unknown.value,
                            false,
                            headers = mapOf("Referer" to data)
                        )
                    )
                    foundCount++
                }
            }
            
            // Strategy 2: Look in iframe sources
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    Log.d("QFilmProvider", "Found iframe: $src")
                    callback(
                        ExtractorLink(
                            "QFilm",
                            "Embedded Player",
                            src,
                            data,
                            Qualities.Unknown.value,
                            false,
                            headers = mapOf("Referer" to data)
                        )
                    )
                    foundCount++
                }
            }
            
            // Strategy 3: Look for any link with common streaming domains
            val streamingDomains = listOf(
                "ok.ru", "mail.ru", "vimeo", "youtube", "dailymotion",
                "dood", "mixdrop", "uptobox", "mega", "google"
            )
            
            doc.select("a[href]").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    streamingDomains.forEach { domain ->
                        if (href.contains(domain, ignoreCase = true)) {
                            Log.d("QFilmProvider", "Found streaming link: $href")
                            callback(
                        ExtractorLink(
                                    "QFilm",
                                    "Player",
                                    href,
                                    data,
                                    Qualities.Unknown.value,
                                    false,
                                    headers = mapOf("Referer" to data)
                                )
                            )
                            foundCount++
                        }
                    }
                }
            }
            
            Log.d("QFilmProvider", "Found $foundCount links total")
            println("✅ Found $foundCount links")
            
            return foundCount > 0
        } catch (e: Exception) {
            Log.e("QFilmProvider", "Error in loadLinks: ${e.message}", e)
            println("❌ Error: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
