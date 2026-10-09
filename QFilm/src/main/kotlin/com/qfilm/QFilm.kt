@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.qfilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
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

    private fun buildHeaders(referer: String = "$mainUrl/"): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ar,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Referer" to referer
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(
            url, 
            referer = "$mainUrl/",
            headers = buildHeaders(),
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
        val doc = app.get(
            url, 
            referer = "$mainUrl/",
            headers = buildHeaders(),
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
        val doc = app.get(
            url, 
            referer = "$mainUrl/",
            headers = buildHeaders(),
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
        Log.d("QFilm", "loadLinks: $data")
        
        try {
            // Try direct HTTP parsing first for embedded players
            val doc = app.get(
                data,
                referer = "$mainUrl/",
                headers = buildHeaders(),
                interceptor = cfInterceptor,
                timeout = 30
            ).document
            
            // Look for direct video URLs in page HTML
            val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            val html = doc.outerHtml()
            
            var foundCount = 0
            
            mp4Regex.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank() && !url.contains("data:")) {
                    Log.d("QFilm", "Found direct MP4: $url")
                    callback(
                        ExtractorLink(
                            "QFilm",
                            "مباشر",
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
            
            for (match in m3u8Regex.findAll(html)) {
                val url = match.groupValues[1]
                if (url.isNotBlank() && !url.contains("data:")) {
                    Log.d("QFilm", "Found direct M3U8: $url")
                    val links = M3u8Helper.generateM3u8("QFilm", url, data)
                    for (link in links) {
                        callback(link)
                    }
                    foundCount++
                }
            }
            
            // Look for iframe embed sources
            for (iframe in doc.select("iframe[src]")) {
                val src = iframe.attr("src").trim()
                if (src.isNotBlank() && src.startsWith("http")) {
                    Log.d("QFilm", "Found iframe: $src")
                    callback(
                        ExtractorLink(
                            "QFilm",
                            "Embedded",
                            src,
                            data,
                            Qualities.Unknown.value,
                            false
                        )
                    )
                    foundCount++
                }
            }
            
            if (foundCount > 0) return true
            
        } catch (e: Exception) {
            Log.w("QFilm", "HTTP parsing failed: ${e.message}")
        }
        
        // Fallback to WebViewResolver for JavaScript-rendered content
        return try {
            Log.d("QFilm", "Using WebViewResolver for $data")
            
            val triggerJs = """
                (function() {
                    function autoClick() {
                        ['.play-button','.btn-play','.vjs-big-play-button','.jw-icon-display',
                         'button.play','button[class*=play]','#play','a.play','.epss','.watch-btn',
                         'button.watch','button.btn-primary','button.btn','.player-big-play']
                            .forEach(function(sel){
                                try {
                                    var el = document.querySelector(sel);
                                    if(el && typeof el.click === 'function') { el.click(); }
                                } catch(_) {}
                            });
                        
                        var videos = document.querySelectorAll('video');
                        videos.forEach(function(v){
                            try { 
                                v.muted = true; 
                                var p = v.play(); 
                                if(p && typeof p.catch === 'function') p.catch(function(){}); 
                            } catch(_) {}
                        });
                        
                        var iframes = document.querySelectorAll('iframe');
                        iframes.forEach(function(f){
                            try { 
                                if(f.contentWindow && f.contentWindow.postMessage) {
                                    f.contentWindow.postMessage('play','*');
                                    f.contentWindow.postMessage({action:'play'},'*');
                                }
                            } catch(_) {}
                        });
                    }
                    autoClick();
                    setInterval(autoClick, 800);
                    setTimeout(autoClick, 1000);
                    setTimeout(autoClick, 2500);
                })();
            """.trimIndent()
            
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8|\.mp4|/hls/|/playlist|master\.m3u8""", RegexOption.IGNORE_CASE),
                script = triggerJs
            )
            
            val (interceptedRequest, _) = resolver.resolveUsingWebView(
                requestCreator(
                    "GET",
                    data,
                    referer = data,
                    headers = buildHeaders(data)
                )
            )
            
            val videoUrl = interceptedRequest?.url?.toString()
            Log.d("QFilm", "WebViewResolver intercepted: $videoUrl")
            
            if (!videoUrl.isNullOrBlank()) {
                val lurl = videoUrl.lowercase()
                return when {
                    lurl.contains(".m3u8") || lurl.contains("/hls/") -> {
                        Log.d("QFilm", "Processing HLS playlist: $videoUrl")
                        val links = M3u8Helper.generateM3u8("QFilm", videoUrl, data)
                        for (link in links) {
                            callback(link)
                        }
                        true
                    }
                    lurl.endsWith(".mp4") || lurl.contains(".mp4?") -> {
                        Log.d("QFilm", "Processing direct MP4: $videoUrl")
                        callback(
                            ExtractorLink(
                                "QFilm",
                                "WebView MP4",
                                videoUrl,
                                data,
                                Qualities.Unknown.value,
                                false,
                                headers = mapOf("Referer" to data)
                            )
                        )
                        true
                    }
                    else -> {
                        Log.d("QFilm", "Trying to load extractor from: $videoUrl")
                        loadExtractor(videoUrl, data, subtitleCallback, callback)
                    }
                }
            } else {
                Log.w("QFilm", "WebViewResolver returned null URL")
                false
            }
        } catch (e: Exception) {
            Log.e("QFilm", "WebViewResolver error: ${e.message}", e)
            false
        }
    }
}
