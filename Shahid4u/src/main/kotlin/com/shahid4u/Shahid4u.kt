@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Element
import android.util.Log
import java.net.URI
import java.net.URLEncoder

class Shahid4u : MainAPI() {
    override var mainUrl = "https://shaheid4u.name/"
    override var name = "Shahid4u"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val logTag = "Shahid4uProvider"
    private var resolvedReferer: String? = null

    private val TRANSPARENT_PNG_DATA_URI =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller
    private fun encodeUri(url: String): String {
        return try {
            url.toCharArray().joinToString("") { char ->
                if (char.code <= 127) char.toString() else URLEncoder.encode(
                    char.toString(),
                    "UTF-8"
                )
            }
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun buildBrowserHeaders(referer: String? = null): Map<String, String> {
        val ref = referer ?: resolvedReferer ?: mainUrl
        val safeRef = encodeUri(ref)

        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to safeRef,
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun posterheader(referer: String? = null): Map<String, String> {
        val ref = referer ?: resolvedReferer ?: mainUrl
        val safeRef = encodeUri(ref)

        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to safeRef,
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun buildMergedHeaders(url: String, referer: String? = null): Map<String, String> {
        val base = buildBrowserHeaders(referer).toMutableMap()

        return try {
            val cloudHeaders = cloudflareKiller.getCookieHeaders(url).toMultimap()
                .mapValues { entry -> entry.value.joinToString("; ") }
            base.putAll(cloudHeaders)
            base
        } catch (e: Exception) {
            Log.w(logTag, "buildMergedHeaders -> failed to get cloudflare headers: ${e.message}")
            base
        }
    }

    private fun makeAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val p = url.trim()
        return when {
            p.startsWith("http://", true) || p.startsWith("https://", true) -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> mainUrl.trimEnd('/') + p
            else -> {
                mainUrl.trimEnd('/') + "/" + p.trimStart('/')
            }
        }
    }

    private fun detectCloudflareBlock(doc: org.jsoup.nodes.Document, html: String?): Boolean {
        if (!html.isNullOrBlank()) {
            val l = html.lowercase()
            if (l.contains("just a moment") || l.contains("enable javascript and cookies") ||
                l.contains("ray id:") || l.contains("checking your browser") ||
                l.contains("attention required") || l.contains("cloudflare") && l.contains("captcha")) return true
        }
        val title = doc.selectFirst("title")?.text().orEmpty().lowercase()
        return title.contains("just a moment") || title.contains("attention required") ||
                title.contains("cloudflare") || title.contains("403 forbidden") ||
                title.contains("429 too many") || title.contains("503 service")
    }

    private suspend fun httpGet(url: String, referer: String? = null): org.jsoup.nodes.Document {
        val headers = buildMergedHeaders(url, referer)
        val safeRef = encodeUri(referer ?: mainUrl)

        val response = app.get(
            url,
            referer = safeRef,
            headers = headers,
            interceptor = cfInterceptor
        )
        if (resolvedReferer == null) {
            val finalUrl = response.url
            val match = Regex("^(https?://[^/]+/)").find(finalUrl)
            resolvedReferer = match?.value ?: mainUrl
            Log.d(logTag, "Resolved final referer: $resolvedReferer")
        }

        val doc = response.document
        val htmlText = runCatching { response.text }.getOrNull()
        if (detectCloudflareBlock(doc, htmlText)) {
            Log.w(logTag, "Cloudflare block detected for $url, retrying with interceptor-only...")
            try {
                val retryResp = app.get(
                    url,
                    referer = safeRef,
                    headers = buildBrowserHeaders(referer),
                    interceptor = cfInterceptor
                )
                val retryDoc = retryResp.document
                val retryHtml = runCatching { retryResp.text }.getOrNull()
                if (!detectCloudflareBlock(retryDoc, retryHtml)) return retryDoc
            } catch (t: Throwable) {
                Log.w(logTag, "Retry also failed for $url: ${t.message}")
            }
        }
        return doc
    }

    private fun parseCard(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.show.card, a.glide_post, a")
        if (linkElement == null) return null

        val href = linkElement.attr("href").ifBlank { linkElement.absUrl("href") }
        val absHref = makeAbsoluteUrl(href) ?: href

        val mainTitle = element.selectFirst("p.title")?.text()?.trim()
        val description = element.selectFirst("p.description")?.text()?.trim()
        val title = if (!mainTitle.isNullOrBlank()) {
            if (!description.isNullOrBlank()) "$mainTitle - $description" else mainTitle
        } else {
            element.selectFirst("div.card-content")?.text()?.trim()
                ?: element.selectFirst("h3")?.text()?.trim()
                ?: linkElement.attr("title").trim()
        }
        if (title.isNullOrBlank()) return null

        val posterStyle = linkElement.attr("style")
        var posterUrl = Regex("""url\(['"]?(.*?)['"]?\)""").find(posterStyle)?.groupValues?.get(1)
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("data-src")?.trim()
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("src")?.trim()
        posterUrl = makeAbsoluteUrl(posterUrl) ?: TRANSPARENT_PNG_DATA_URI

        val isTvSeries =
            element.selectFirst(".ep_num, .الحلقة, .ep") != null || absHref.contains("/episode/") ||
                    href.contains("/episode/") || element.select(".ep").isNotEmpty()

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, absHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheader()
            }
        } else {
            newMovieSearchResponse(title, absHref, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheader()
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isNotEmpty()) {
            val categoryUrl = "${request.data}?page=$page"
            val document = httpGet(categoryUrl, referer = mainUrl)
            val items = document.select("div.shows-container.row div[class*=col-]").mapNotNull { col ->
                col.selectFirst("a.show.card")?.let { parseCard(it) } ?: parseCard(col)
            }
            val hasNext =
                document.selectFirst("ul.pagination li.page-item.active + li.page-item a") != null
            return newHomePageResponse(request.name, items, hasNext)
        }

        if (page > 1) return newHomePageResponse(emptyList())

        val homePageList = mutableListOf<HomePageList>()
        val document = httpGet(mainUrl, referer = mainUrl)

        try {
            val sliderItems =
                document.select("div.glide li.glide__slide:not(.glide__slide--clone)").mapNotNull { slide ->
                    slide.selectFirst("a.show.card")?.let { parseCard(it) } ?: parseCard(slide)
                }
            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("أبرز العروض", sliderItems))
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error parsing slider items: ${e.message}")
        }

        val categories = listOf(
            "افلام اجنبي" to "${mainUrl}category/افلام-اجنبي",
            "افلام عربي" to "${mainUrl}category/افلام-عربي",
            "افلام هندي" to "${mainUrl}category/افلام-هندي",
            "افلام انمي" to "${mainUrl}category/افلام-انمي",
            "مسلسلات أجنبي" to "${mainUrl}category/مسلسلات-اجنبي",
            "مسلسلات عربي" to "${mainUrl}category/مسلسلات-عربي",
            "مسلسلات تركية" to "${mainUrl}category/مسلسلات-تركية",
            "مسلسلات انمي" to "${mainUrl}category/مسلسلات-انمي",
        )

        for ((title, url) in categories) {
            try {
                val doc = httpGet(url, referer = mainUrl)
                val items =
                    doc.select("div.shows-container.row div[class*=col-]").take(40).mapNotNull { col ->
                        col.selectFirst("a.show.card")?.let { parseCard(it) } ?: parseCard(col)
                    }
                if (items.isNotEmpty()) homePageList.add(HomePageList(title, items, true))
            } catch (e: Exception) {
                Log.e(logTag, "Failed to load category '$title': ${e.message}")
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "${mainUrl}search?s=$encoded"

        return try {
            val document = httpGet(searchUrl, referer = mainUrl)
            val resultItems = document.select("div.shows-container.row div[class*=col-]")

            if (resultItems.isEmpty()) return emptyList()

            resultItems.mapIndexedNotNull { _, element ->
                try {
                    element.selectFirst("a.show.card")?.let { parseCard(it) } ?: parseCard(element)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = httpGet(url)

        val title = document.selectFirst("span.title, h1.title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                ?.replace("- Shahid4u", "", ignoreCase = true)
                ?.replace("- شاهد فور يو", "", ignoreCase = true)?.trim()
            ?: document.selectFirst("title")?.text()
                ?.replace("- Shahid4u", "", ignoreCase = true)
                ?.replace("- شاهد فور يو", "", ignoreCase = true)?.trim()
            ?: "غير متوفر"

        val posterStyle = document.selectFirst("div.poster-side div.poster, div.poster")?.attr("style").orEmpty()
        val posterRaw = document.selectFirst("div.poster-side img")?.attr("src")
            ?: document.selectFirst("div.poster img")?.attr("src")
            ?: Regex("""--background-image-url:\s*url\(['"]?(.*?)['"]?\)""")
                .find(posterStyle)?.groupValues?.get(1)
            ?: Regex("""url\(['"]?(.*?)['"]?\)""").find(posterStyle)?.groupValues?.get(1)
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
        val poster = makeAbsoluteUrl(posterRaw)

        val plot = document.selectFirst("span.description, .description, .entry-content p, .story-content, div.story, .movie-story p, p.plot, .summary")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val tags = document.select("div.qualities span.q-tag a, a[href*=/category/], a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        val isAnime = url.contains("انمي") || title.contains("انمي") ||
                url.contains("anime", ignoreCase = true)

        val seasons = document.select("div.w-100.bg-main.rounded.my-4 a.epss[href*='/season/']")
        val episodes = ArrayList<Episode>()

        if (seasons.isNotEmpty()) {
            seasons.amap { seasonElement ->
                val seasonUrl = seasonElement.attr("href")
                val seasonDoc = httpGet(seasonUrl, referer = url)

                seasonDoc.select("div.w-100.bg-main.rounded.my-4 a.epss:not([href*='/season/'])")
                    .forEach { episodeElement ->
                        val epName = episodeElement.text().trim()
                        val epUrl = episodeElement.attr("href")
                        val episodeNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                        val seasonNumber =
                            Regex("""الموسم\s*(\d+)""").find(seasonElement.text())?.groupValues?.get(
                                1
                            )?.toIntOrNull()

                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            episode = episodeNumber
                            season = seasonNumber
                            posterUrl = poster
                        })
                    }
            }
        } else {
            document.select("div.w-100.bg-main.rounded.my-4 a.epss:not([href*='/season/'])")
                .forEach { episodeElement ->
                    val epName = episodeElement.text().trim()
                    val epUrl = episodeElement.attr("href")
                    val episodeNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    })
                }
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (sortedEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = posterheader()
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = posterheader()
                this.plot = plot
                this.tags = tags
            }
        }
    }

    private fun normalizeToWatch(data: String): String {
        if (data.contains("/watch")) return data
        val listOfPatterns = listOf("/film/", "/movie/", "/episode/", "/series/", "/download/", "/season/", "/movies/", "/episodes/", "/tv/")
        var out = data
        for (p in listOfPatterns) {
            if (out.contains(p)) {
                out = out.replace(p, "/watch/")
                return out
            }
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = normalizeToWatch(data)

        val embedUrls = linkedSetOf<String>()
        val directHls = linkedSetOf<String>()
        val directMp4 = linkedSetOf<String>()
        val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        try {
            val rawWatchResp = runCatching {
                app.get(watchUrl, referer = data, headers = buildMergedHeaders(watchUrl, data), interceptor = cfInterceptor)
            }.getOrNull()
            val watchDoc = if (rawWatchResp != null) {
                val wdoc = rawWatchResp.document
                val whtml = runCatching { rawWatchResp.text }.getOrNull()
                if (detectCloudflareBlock(wdoc, whtml)) {
                    Log.w(logTag, "loadLinks: CF block on watch page, retrying via httpGet...")
                    httpGet(watchUrl, referer = data)
                } else wdoc
            } else httpGet(watchUrl, referer = data)

            val htmlContent = runCatching { rawWatchResp?.text }.getOrNull() ?: watchDoc.outerHtml()
            embedUrls.addAll(parseEmbedUrls(htmlContent))
            watchDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                val absSrc = makeAbsoluteUrl(src)
                if (!absSrc.isNullOrBlank()) embedUrls.add(absSrc)
            }
            Regex("""https?://[^\s"'\\<>]+\.(?:m3u8|mp4|webm|mkv)(?:\?[^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE).findAll(htmlContent).forEach { m ->
                val v = m.value
                if (v.contains(".m3u8", ignoreCase = true) || v.contains("/hls/")) directHls.add(v)
                else directMp4.add(v)
            }
        } catch (e: Exception) {
            Log.e(logTag, "loadLinks -> failed to fetch watch page $watchUrl: ${e.message}")
        }

        try {
            val downloadUrl = watchUrl.replace("/watch/", "/download/")
            if (downloadUrl != watchUrl) {
                val dlDoc = httpGet(downloadUrl, referer = watchUrl)
                dlDoc.select("a.btn-down[href], a[href*='/d/']").forEach { a ->
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    val link = makeAbsoluteUrl(href)
                    if (!link.isNullOrBlank()) embedUrls.add(link)
                }
            }
        } catch (e: Exception) {
            Log.w(logTag, "loadLinks -> download page failed: ${e.message}")
        }

        for (link in directHls) {
            try {
                M3u8Helper.generateM3u8(this.name, link, referer = watchUrl, headers = mapOf("User-Agent" to ua, "Referer" to watchUrl)).forEach(callback)
            } catch (e: Exception) { Log.w(logTag, "direct HLS failed: ${e.message}") }
        }
        for (link in directMp4) {
            try { callback(ExtractorLink(this.name, "مباشر", link, watchUrl, Qualities.Unknown.value, false)) } catch (_: Exception) {}
        }
        val alreadyFound = directHls.isNotEmpty() || directMp4.isNotEmpty()

        var results: List<Boolean> = emptyList()
        if (embedUrls.isNotEmpty()) {
            results = embedUrls.toList().amap { embedUrl ->
                try {
                    resolveEmbedUrl(embedUrl, watchUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.w(logTag, "resolveEmbedUrl failed ($embedUrl): ${e.message}")
                    false
                }
            }
        }

        val resolvedOk = results.any { it }
        if (alreadyFound || resolvedOk) return true

        Log.w(logTag, "HTTP embed stages empty for $watchUrl. Falling back to WebViewResolver.")
        val triggerJs = """
            (function() {
                function clickAll() {
                    ['.play-button','.btn-play','.vjs-big-play-button','.jw-icon-display','button.play','#play','a.play',
                     '.servers-list li','.server-list li','ul.servers li','.tab-server','.epss','button.watch',
                     '[data-server]','a[href*="/watch"]','.watch-server','.switch-server','.player-big-play',
                     '.btn-watch','.btn-primary','.btn.success'].forEach(function(s){
                        try { var e = document.querySelector(s); if(e && typeof e.click === 'function') { e.click(); } } catch(_) {}
                    });
                    var vs = document.querySelectorAll('video');
                    vs.forEach(function(v){ try { v.muted = true; var p = v.play(); if (p && typeof p.catch === 'function') p.catch(function(){}); } catch(_){} });
                    var ifs = document.querySelectorAll('iframe');
                    ifs.forEach(function(f){ try { f.contentWindow && f.contentWindow.postMessage && f.contentWindow.postMessage('play','*'); } catch(_){} });
                }
                clickAll();
                setInterval(clickAll, 500);
                setTimeout(function(){ clickAll(); }, 1000);
                setTimeout(function(){ clickAll(); }, 2000);
                setTimeout(function(){ clickAll(); }, 3500);
            })();
        """.trimIndent()
        val targetsToTry = listOfNotNull(
            watchUrl,
            watchUrl.takeIf { it != data },
            data.takeIf { data != watchUrl }
        ).distinct()
        for (t in targetsToTry) {
            try {
                Log.d(logTag, "WebViewResolver trying: $t")
                val resolver = WebViewResolver(
                    interceptUrl = Regex(""".*(\.m3u8.*|\.mp4.*|/hls/.*|master\.m3u8.*|playlist\.m3u8.*|fastvid|fastved|earnvids|d0000d|ok\.ru|mail\.ru|videa\.hu|uptobox|mixdrop|dood)""", RegexOption.IGNORE_CASE),
                    script = triggerJs
                )
                val intercepted = resolver.resolveUsingWebView(
                    requestCreator("GET", t, referer = t, headers = buildMergedHeaders(t, data).plus("User-Agent" to ua))
                )
                val webUrl = intercepted.first?.url?.toString()
                Log.d(logTag, "WebViewResolver($t) got: $webUrl")
                if (!webUrl.isNullOrBlank()) {
                    val lurl = webUrl.lowercase()
                    if (lurl.contains(".m3u8") || lurl.contains("/hls/")) {
                        M3u8Helper.generateM3u8(
                            this.name, webUrl, referer = watchUrl,
                            headers = mapOf("User-Agent" to ua, "Referer" to watchUrl)
                        ).forEach(callback)
                        return true
                    } else if (lurl.endsWith(".mp4") || lurl.contains(".mp4?")) {
                        callback(ExtractorLink(this.name, "مباشر", webUrl, watchUrl, Qualities.Unknown.value, false))
                        return true
                    } else if (loadExtractor(webUrl, referer = watchUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(logTag, "WebViewResolver failed($t): ${e.message}")
            }
        }
        return false
    }

    private fun isCanaryServer(name: String?, rank: Int?, url: String?): Boolean {
        if (rank != null && rank >= 900000) return true
        val n = (name ?: "").lowercase()
        if (n.contains("backup") || n.contains("mirror") || n.contains("cdn player")) return true
        val u = (url ?: "").lowercase()
        return u.contains("/media/watch/") || u.contains("/media/api/") || u.contains("/media/page/")
    }

    private fun relaxedJsObjectToUrlList(rawBlock: String): List<Triple<String, String?, Int?>> {
        val out = mutableListOf<Triple<String, String?, Int?>>()
        if (rawBlock.isBlank()) return out
        try {
            val arr = JSONArray(rawBlock)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").ifBlank { o.optString("src") }
                if (url.isBlank()) continue
                val name = o.optString("name").ifBlank { null }
                val rank = if (o.has("rank")) o.optInt("rank", -1) else null
                out.add(Triple(url, name, rank))
            }
            return out
        } catch (_: JSONException) { }
        val objRanges = mutableListOf<Pair<Int, Int>>()
        var depth = 0; var start = -1
        for (i in rawBlock.indices) {
            val c = rawBlock[i]
            if (c == '{') { if (depth == 0) start = i; depth++ }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) objRanges.add(start to i); start = -1 }
        }
        val urlRe = Regex("""(?:url|src|link|embed)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val nameRe = Regex("""(?:name|title|label)\s*[:=]\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
        val rankRe = Regex("""(?:rank|priority)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        for (r in objRanges) {
            val objSlice = runCatching { rawBlock.substring(r.first, r.second + 1) }.getOrNull() ?: continue
            val u = urlRe.find(objSlice)?.groupValues?.get(1) ?: continue
            val n = nameRe.find(objSlice)?.groupValues?.get(1)
            val rnk = rankRe.find(objSlice)?.groupValues?.get(1)?.toIntOrNull()
            out.add(Triple(u, n, rnk))
        }
        return out
    }

    private fun parseServersArrayFromHtml(html: String): List<String> {
        val out = linkedSetOf<String>()
        val cleaned = html
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")

        val varRegex = Regex("""(?:let|const|var)\s+(?:servers|player_servers|list_servers|hosts|watches|items)\s*=\s*(\[[\s\S]*?\])\s*[;\n]""")
        for (match in varRegex.findAll(cleaned)) {
            val block = match.groupValues[1]
            relaxedJsObjectToUrlList(block).forEach { (u, n, r) ->
                if (u.isNotBlank() && !isCanaryServer(n, r, u)) {
                    val absUrl = makeAbsoluteUrl(u)
                    if (absUrl != null) out.add(absUrl)
                }
            }
        }

        val windowRegex = Regex("""(?:window|document)\.?(?:servers|player_servers|list_servers)\s*=\s*(\[[\s\S]*?\])\s*[;\n]""")
        for (match in windowRegex.findAll(cleaned)) {
            val block = match.groupValues[1]
            relaxedJsObjectToUrlList(block).forEach { (u, n, r) ->
                if (u.isNotBlank() && !isCanaryServer(n, r, u)) {
                    val absUrl = makeAbsoluteUrl(u)
                    if (absUrl != null) out.add(absUrl)
                }
            }
        }

        return out.toList()
    }

    private fun parseEmbedUrls(html: String): List<String> {
        val out = linkedSetOf<String>()

        out.addAll(parseServersArrayFromHtml(html))

        val cleaned = html
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")

        val jsonParseRegex = Regex("""JSON\.parse\(\s*['"]([\s\S]*?)['"]\s*\)""")
        for (match in jsonParseRegex.findAll(cleaned)) {
            val raw = match.groupValues[1].replace("\\/", "/")
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").ifBlank { obj.optString("src") }
                    val name = obj.optString("name")
                    val rank = obj.optInt("rank", 0)
                    if (url.isNotBlank() && !isCanaryServer(name, rank, url)) {
                        val absUrl = makeAbsoluteUrl(url)
                        if (absUrl != null) out.add(absUrl)
                    }
                }
            } catch (e1: JSONException) {
                try {
                    val obj = JSONObject(raw)
                    val url = obj.optString("url").ifBlank { obj.optString("src") }
                    val name = obj.optString("name")
                    val rank = obj.optInt("rank", 0)
                    if (url.isNotBlank() && !isCanaryServer(name, rank, url)) {
                        val absUrl = makeAbsoluteUrl(url)
                        if (absUrl != null) out.add(absUrl)
                    }
                } catch (e2: JSONException) {
                    Log.w(logTag, "parseEmbedUrls -> could not parse JSON block: ${e2.message}")
                }
            }
        }

        if (out.isEmpty()) {
            Regex("""["']url["']\s*:\s*["']((?:\\.|[^"'])+)["']""")
                .findAll(cleaned)
                .forEach { m ->
                    val u = m.groupValues[1].replace("\\/", "/")
                    if (u.startsWith("http")) out.add(u)
                    else {
                        val absUrl = makeAbsoluteUrl(u)
                        if (absUrl != null) out.add(absUrl)
                    }
                }
        }

        return out.toList()
    }

    private suspend fun resolveEmbedUrl(
        embedUrl: String,
        watchUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = makeAbsoluteUrl(embedUrl) ?: return false
        val targetHost = runCatching { URI(target).host }.getOrNull()
        val mainHost = runCatching { URI(mainUrl).host }.getOrNull()

        if (targetHost != null && mainHost != null && targetHost.equals(mainHost, ignoreCase = true)) {
            return runCatching {
                val page = httpGet(target, referer = watchUrl)
                val iframe = page.selectFirst("iframe[src]")
                if (iframe != null) {
                    val srcRaw = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                    val src = makeAbsoluteUrl(srcRaw)
                    if (!src.isNullOrBlank()) {
                        return@runCatching resolveEmbedUrl(src, watchUrl, subtitleCallback, callback)
                    }
                }
                val innerLinks = parseEmbedUrls(page.outerHtml())
                for (inner in innerLinks) {
                    if (resolveEmbedUrl(inner, watchUrl, subtitleCallback, callback)) return@runCatching true
                }
                false
            }.getOrDefault(false)
        }

        val lower = target.lowercase()
        val isM3u8 = lower.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("/hls/")
        val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
        if (isM3u8 || isVideo) {
            callback(
                ExtractorLink(
                    this.name,
                    "مباشر",
                    target,
                    watchUrl,
                    Qualities.Unknown.value,
                    isM3u8
                )
            )
            return true
        }

        return loadExtractor(target, watchUrl, subtitleCallback, callback)
    }
}
