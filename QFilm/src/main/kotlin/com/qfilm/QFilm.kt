@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.qfilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element
import java.net.URLEncoder

class QFilm : MainAPI() {
    override var mainUrl = "https://a.qfilm.tv"
    override var name = "QFilm"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    private val logTag = "QFilmProvider"

    private val TRANSPARENT_PNG_DATA_URI =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="

    private fun posterHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private fun makeAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val p = url.trim()
        return when {
            p.startsWith("http://", true) || p.startsWith("https://", true) -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> mainUrl.trimEnd('/') + p
            else -> "$mainUrl/$p"
        }
    }

    private fun Element.attrOrAbs(attr: String): String {
        val abs = absUrl(attr)
        if (abs.isNotBlank()) return abs
        val raw = attr(attr)
        if (raw.isBlank()) return ""
        return makeAbsoluteUrl(raw) ?: raw
    }

    private fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .replace(Regex("^مشاهدة\\s+"), "")
            .replace(Regex("\\s+HD\\s+اون\\s?لاين$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+اون\\s?لاين$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*HD\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+-\\s+$name\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+-\\s+كيو فيلم\\s*$"), "")
            .trim()
    }

    private fun parseCardFromThumbLink(link: Element, posterAnchor: Element?): SearchResponse? {
        val href = posterAnchor?.attrOrAbs("href")?.ifBlank { link.attrOrAbs("href") }
            ?: link.attrOrAbs("href")
        if (href.isBlank()) return null

        val thumbImg = posterAnchor?.selectFirst("img") ?: link.selectFirst("img")
        val rawPoster = thumbImg?.attr("data-echo")?.ifBlank { thumbImg.attr("data-src") }
            ?.ifBlank { thumbImg.attr("src") }
        val poster = makeAbsoluteUrl(rawPoster)

        var title: String? = posterAnchor?.attr("title")?.ifBlank { null }
            ?: link.attr("title").ifBlank { null }
        if (title.isNullOrBlank()) {
            val caption = link.parent()?.parent()?.selectFirst("h3.caption, .caption, h2, h3")
            title = caption?.text()?.trim()
                ?: link.selectFirst("h3, h2, .caption, .title, .name")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {
            thumbImg?.attr("alt")?.let { title = it.trim() }
        }
        val clean = cleanTitle(title)
        if (clean.isBlank()) return null

        val hrefLower = href.lowercase()
        val isAnime = clean.contains("انمي") || clean.contains("أنمي") ||
            hrefLower.contains("anime", true)
        val type = if (isAnime) TvType.Anime else TvType.Movie

        return if (type == TvType.Movie)
            newMovieSearchResponse(clean, href, TvType.Movie) {
                this.posterUrl = poster ?: TRANSPARENT_PNG_DATA_URI
                this.posterHeaders = posterHeaders()
            }
        else
            newTvSeriesSearchResponse(clean, href, TvType.Anime) {
                this.posterUrl = poster ?: TRANSPARENT_PNG_DATA_URI
                this.posterHeaders = posterHeaders()
            }
    }

    private fun parseHeaderFeaturedItem(a: Element): SearchResponse? {
        val href = a.attrOrAbs("href").ifBlank { return null }
        val title = cleanTitle(a.selectFirst(".header-featured-name")?.text()?.trim()
            ?: a.attr("title"))
        if (title.isBlank()) return null
        val poster = makeAbsoluteUrl(a.selectFirst("img")?.attr("src")?.trim())

        val isAnime = title.contains("انمي") || title.contains("أنمي")
        val type = if (isAnime) TvType.Anime else TvType.Movie
        return if (type == TvType.Movie)
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster ?: TRANSPARENT_PNG_DATA_URI
                this.posterHeaders = posterHeaders()
            }
        else
            newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster ?: TRANSPARENT_PNG_DATA_URI
                this.posterHeaders = posterHeaders()
            }
    }

    private fun parseDocumentCards(doc: org.jsoup.nodes.Document, limit: Int = 40): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()

        doc.select("div.thumbnail > div.pm-video-thumb").forEach { thumbDiv ->
            val posterLink = thumbDiv.selectFirst("> a[href*=watch]") ?: return@forEach
            val item = parseCardFromThumbLink(thumbDiv, posterLink) ?: return@forEach
            out[item.url] = item
            if (out.size >= limit) return@forEach
        }

        if (out.size < limit) {
            doc.select("a.header-featured-item").forEach { a ->
                if (out.containsKey(a.attrOrAbs("href"))) return@forEach
                val item = parseHeaderFeaturedItem(a) ?: return@forEach
                out[item.url] = item
                if (out.size >= limit) return@forEach
            }
        }

        if (out.size < limit) {
            doc.select("a[href*=watch.php]").forEach { a ->
                if (out.containsKey(a.attrOrAbs("href"))) return@forEach
                val cls = a.className()
                if (!cls.contains("header-featured") && !cls.contains("pm-video-thumb")) {
                    val item = parseHeaderFeaturedItem(a) ?: return@forEach
                    out[item.url] = item
                }
                if (out.size >= limit) return@forEach
            }
        }

        return out.values.toList()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php?&page=" to "جديد الموقع",
        "$mainUrl/category.php?cat=2026-movies&page=" to "أفلام 2026",
        "$mainUrl/category.php?cat=arabic-movies&page=" to "أفلام عربي",
        "$mainUrl/category.php?cat=foreign-movies&page=" to "أفلام أجنبية",
        "$mainUrl/category.php?cat=indian-movies&page=" to "أفلام هندية",
        "$mainUrl/category.php?cat=asian-movies&page=" to "أفلام آسيوية",
        "$mainUrl/category.php?cat=turkish-movies&page=" to "أفلام تركية",
        "$mainUrl/category.php?cat=anime-movies&page=" to "أفلام أنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = parseDocumentCards(doc, limit = 30)
        val hasNext = doc.selectFirst(".pagination li.active + li a[href], .pagination li.page-item:not(.disabled):not(.active) a") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search.php?keywords=$encoded"
        return try {
            val doc = app.get(url, referer = "$mainUrl/").document
            parseDocumentCards(doc, limit = 60)
        } catch (e: Exception) {
            Log.e(logTag, "search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val h1Title = doc.selectFirst("h1, h2.title, .title, .movie-title, .entry-title, h2")?.text()?.trim()
        val pmTitle = runCatching {
            val html = doc.outerHtml()
            val idx = html.indexOf("title: '")
            if (idx >= 0) {
                val start = idx + 8
                val end = html.indexOf("'", start)
                if (end > start) html.substring(start, end) else null
            } else null
        }.getOrNull()
        val title = cleanTitle(ogTitle ?: pmTitle ?: h1Title)
            .ifBlank { "فيلم بدون عنوان" }

        val poster = run {
            val p = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            val q = p?.ifBlank { null } ?: runCatching {
                val html = doc.outerHtml()
                val idx = html.indexOf("thumb_url: \"")
                if (idx >= 0) {
                    val start = idx + 12
                    val end = html.indexOf("\"", start)
                    if (end > start) html.substring(start, end) else null
                } else null
            }.getOrNull()
            val r: String? = q ?: doc.selectFirst(".video-bibplayer-poster")
                ?.let { el ->
                    Regex("""url\(['"]?(.*?)['"]?\)""").find(el.attr("style"))?.groupValues?.get(1)
                }
            makeAbsoluteUrl(r)
        }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val tags = doc.select("a[href*=category.php]").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 30 }.distinct().take(12)

        val year = Regex("""\((19|20)\d{2}\)""").find(title)?.value
            ?.trim('(', ')')?.toIntOrNull()
            ?: doc.selectFirst("a[href*=release-year], a[href*=year]")?.text()?.trim()?.toIntOrNull()

        val duration = runCatching {
            val html = doc.outerHtml()
            val idx = html.indexOf("duration: ")
            if (idx >= 0) {
                val start = idx + 10
                val end = html.indexOfAny(charArrayOf(',', '\n', '}'), start)
                if (end > start) html.substring(start, end).trim().toIntOrNull() else null
            } else null
        }.getOrNull()

        val isAnime = title.contains("انمي") || title.contains("أنمي") ||
            url.contains("anime", true) || tags.any { it.contains("انمي") || it.contains("أنمي") }
        val type = if (isAnime) TvType.Anime else TvType.Movie

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = poster
            this.posterHeaders = posterHeaders()
            this.plot = plot
            this.tags = tags
            this.year = year
            this.duration = duration
        }
    }

    private fun extractVid(data: String): String? {
        val qp = data.substringAfter("vid=", "")
            .substringBefore("&").substringBefore("#")
        if (qp.isNotBlank()) return qp
        val pathMatch = Regex("""/(?:watch|play)[^/]*/(\d+)""").find(data)
        if (pathMatch != null) return pathMatch.groupValues[1]
        val looseMatch = Regex("""[?&/](\d{4,})(?:[&#/.]|$)""").find(data)
        if (looseMatch != null) return looseMatch.groupValues[1]
        return null
    }

    private fun isTrackedAsset(u: String): Boolean {
        val l = u.lowercase()
        return l.contains("agl.") || l.contains("xbeat.space") || l.contains("histats") ||
                l.contains("dtscdn.") || l.contains("dtscout.") || l.contains("onaudience.") ||
                l.contains("mrktmtrcs.") || l.contains("cloudflareinsights") ||
                l.contains("googletagmanager") || l.contains("google-analytics") ||
                l.contains("facebook") || l.contains("googlesyndication")
    }

    private fun scanInlinePlayerJs(html: String): Pair<List<String>, List<String>> {
        val m3u8s = linkedSetOf<String>()
        val mp4s = linkedSetOf<String>()
        val cleaned = html.replace("\\/", "/").replace("&quot;", "\"").replace("&#039;", "'")

        Regex("""https?://[^\s"'\\<>]+\.m3u8(?:\?[^\s"'\\<>]*)?""").findAll(cleaned).forEach { m ->
            if (!isTrackedAsset(m.value)) m3u8s.add(m.value)
        }
        Regex("""https?://[^\s"'\\<>]+?\.mp4(?:[?#][^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE).findAll(cleaned).forEach { m ->
            if (!isTrackedAsset(m.value)) mp4s.add(m.value)
        }

        Regex("""["']?(?:file|source|src|hls|manifest|stream)["']?\s*[:=]\s*["']((?:\\.|[^"'])+)["']""").findAll(cleaned).forEach { m ->
            var v = m.groupValues[1].replace("\\/", "/")
            if (v.isBlank() || isTrackedAsset(v)) return@forEach
            if (v.startsWith("//")) v = "https:$v"
            val lv = v.lowercase()
            if (lv.contains(".m3u8") || lv.contains("/hls/")) {
                if (v.startsWith("/")) v = mainUrl.trimEnd('/') + v
                if (v.startsWith("http")) m3u8s.add(v)
            } else if (lv.endsWith(".mp4") || lv.contains(".mp4?") || lv.endsWith(".webm") || lv.endsWith(".mkv")) {
                if (v.startsWith("/")) v = mainUrl.trimEnd('/') + v
                if (v.startsWith("http")) mp4s.add(v)
            }
        }

        val sourcesArrayM = Regex("""sources\s*:\s*\[([\s\S]*?)\]""", RegexOption.DOT_MATCHES_ALL).find(cleaned)
        if (sourcesArrayM != null) {
            val inner = sourcesArrayM.groupValues[1]
            Regex("""["']?file["']?\s*:\s*["']((?:\\.|[^"'])+)["']""").findAll(inner).forEach { m ->
                var v = m.groupValues[1].replace("\\/", "/")
                if (v.isBlank() || isTrackedAsset(v)) return@forEach
                if (v.startsWith("//")) v = "https:$v"
                val lv = v.lowercase()
                if (lv.contains(".m3u8") || lv.contains("/hls/")) {
                    if (v.startsWith("/")) v = mainUrl.trimEnd('/') + v
                    if (v.startsWith("http")) m3u8s.add(v)
                } else if (lv.contains(".mp4") || lv.endsWith(".webm")) {
                    if (v.startsWith("/")) v = mainUrl.trimEnd('/') + v
                    if (v.startsWith("http")) mp4s.add(v)
                }
            }
            Regex("""src\s*:\s*["']((?:\\.|[^"'])+)["']""").findAll(inner).forEach { m ->
                var v = m.groupValues[1].replace("\\/", "/")
                if (v.isBlank() || isTrackedAsset(v)) return@forEach
                if (v.startsWith("//")) v = "https:$v"
                val lv = v.lowercase()
                if (lv.contains(".m3u8") || lv.contains("/hls/")) {
                    if (v.startsWith("/")) v = mainUrl.trimEnd('/') + v
                    if (v.startsWith("http")) m3u8s.add(v)
                }
            }
        }

        return m3u8s.toList() to mp4s.toList()
    }

    private fun collectEmbedsFromDoc(doc: org.jsoup.nodes.Document, sink: LinkedHashSet<String>, lowPriority: LinkedHashSet<String>) {
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attrOrAbs("src").ifBlank { iframe.attr("src") }
            if (src.isBlank()) return@forEach
            val l = src.lowercase()
            if (l.startsWith("blob:")) return@forEach
            if (isTrackedAsset(src)) {
                if (!sink.contains(src)) lowPriority.add(src)
            } else {
                if (!sink.contains(src) && !lowPriority.contains(src)) sink.add(src)
            }
        }
        val html = doc.outerHtml()
        val srcRegex = Regex("""src\s*=\s*["'](https?://[^"']+)["']""")
        for (m in srcRegex.findAll(html)) {
            val s = m.groupValues[1]
            if (isTrackedAsset(s)) continue
            if (sink.contains(s) || lowPriority.contains(s)) continue
            sink.add(s)
        }
    }

    private suspend fun tryWebViewResolve(
        targetUrl: String,
        refererUrl: String,
        ua: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(logTag, "tryWebViewResolve -> $targetUrl")
        val triggerJs = """
            (function() {
                var t0 = Date.now();
                function clickAll() {
                    ['.play-button','.btn-play','.vjs-big-play-button','.jw-icon-display','button.play','#play','a.play',
                     '.bibplayer-play','.player-big-play','.btn.btn-play','[data-action="play"]',
                     '.pm-video-play-icon','.pm-play','a.bibplayer-play',
                     '.player-play','.video-play','.icon-play'].forEach(function(s){
                        try { var e = document.querySelector(s); if(e && typeof e.click === 'function') { e.click(); } } catch(_) {}
                    });
                    var vs = document.querySelectorAll('video');
                    vs.forEach(function(v){ try { v.muted = true; var p = v.play(); if (p && typeof p.catch === 'function') p.catch(function(){}); } catch(_){} });
                }
                clickAll();
                setInterval(clickAll, 600);
                setTimeout(function(){ clickAll(); }, 1200);
                setTimeout(function(){ clickAll(); }, 2500);
                setTimeout(function(){ clickAll(); }, 4500);
            })();
        """.trimIndent()
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex(""".*(\.m3u8.*|\.mp4.*|/hls/.*|/manifest/.*|master\.m3u8.*|playlist\.m3u8.*)""", RegexOption.IGNORE_CASE),
                script = triggerJs
            )
            val intercepted = resolver.resolveUsingWebView(
                requestCreator(
                    method = "GET",
                    url = targetUrl,
                    referer = refererUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
            )
            val webUrl = intercepted.first?.url?.toString()
            Log.d(logTag, "WebViewResolver intercepted URL: $webUrl")
            if (!webUrl.isNullOrBlank() && (webUrl.contains(".m3u8") || webUrl.contains("/hls/") || webUrl.contains("master.m3u8") || webUrl.contains("playlist.m3u8"))) {
                M3u8Helper.generateM3u8(
                    this.name,
                    webUrl,
                    referer = refererUrl,
                    headers = mapOf("User-Agent" to ua, "Referer" to refererUrl)
                ).forEach(callback)
                true
            } else if (!webUrl.isNullOrBlank() && (webUrl.lowercase().endsWith(".mp4") || webUrl.contains(".mp4?"))) {
                callback(
                    ExtractorLink(
                        this.name,
                        "مباشر",
                        webUrl,
                        refererUrl,
                        Qualities.Unknown.value,
                        false
                    )
                )
                true
            } else {
                Log.w(logTag, "WebViewResolver did not yield video URL. Trying catch-all regex.")
                val catchAllResolver = WebViewResolver(
                    interceptUrl = Regex(""".*"""),
                    script = triggerJs
                )
                val catchAll = catchAllResolver.resolveUsingWebView(
                    requestCreator(
                        "GET", targetUrl, referer = refererUrl,
                        headers = mapOf("User-Agent" to ua)
                    )
                )
                val caUrl = catchAll.first?.url?.toString()
                Log.d(logTag, "Catch-all intercepted: $caUrl")
                if (!caUrl.isNullOrBlank() && (caUrl.contains(".m3u8") || caUrl.contains("/hls/"))) {
                    M3u8Helper.generateM3u8(
                        this.name, caUrl, referer = refererUrl,
                        headers = mapOf("User-Agent" to ua, "Referer" to refererUrl)
                    ).forEach(callback)
                    true
                } else if (!caUrl.isNullOrBlank() && (caUrl.lowercase().endsWith(".mp4") || caUrl.contains(".mp4?"))) {
                    callback(ExtractorLink(this.name, "مباشر", caUrl, refererUrl, Qualities.Unknown.value, false))
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "WebViewResolver failed for $targetUrl: ${e.message}")
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = extractVid(data)
        val playUrl = if (!vid.isNullOrBlank()) "$mainUrl/play.php?vid=$vid" else data
        val watchUrl = data
        val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

        val embeds = linkedSetOf<String>()
        val lowerPriorityEmbeds = linkedSetOf<String>()
        val directM3u8 = linkedSetOf<String>()
        val directMp4 = linkedSetOf<String>()

        try {
            val watchResp = app.get(watchUrl, referer = "$mainUrl/", headers = mapOf("User-Agent" to ua, "Accept" to "text/html,application/xhtml+xml"))
            val watchDoc = watchResp.document
            val watchHtml = watchResp.text
            collectEmbedsFromDoc(watchDoc, embeds, lowerPriorityEmbeds)
            val (wm, wp) = scanInlinePlayerJs(watchHtml)
            wm.forEach { if (!directM3u8.contains(it)) directM3u8.add(it) }
            wp.forEach { if (!directMp4.contains(it)) directMp4.add(it) }
            watchDoc.select("a[href], button[data-ajax], .servers a, .server a, li.server, div.server-item, ul.servers-list li, .tabs--servers a, ul.nav-tabs a[data-server]").forEach { el ->
                val href = el.attrOrAbs("href").ifBlank { el.attr("data-url") }
                    .ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-embed") }
                if (href.isBlank() || href == "#" || href.lowercase().startsWith("javascript:")) return@forEach
                val abs = makeAbsoluteUrl(href) ?: return@forEach
                val lv = abs.lowercase()
                if (isTrackedAsset(abs)) return@forEach
                if (lv.contains(".m3u8") || lv.contains("/hls/")) {
                    if (!directM3u8.contains(abs)) directM3u8.add(abs)
                } else if (lv.endsWith(".mp4") || lv.contains(".mp4?")) {
                    if (!directMp4.contains(abs)) directMp4.add(abs)
                } else {
                    if (!embeds.contains(abs) && !lowerPriorityEmbeds.contains(abs)) embeds.add(abs)
                }
            }
            Log.d(logTag, "watch scan -> m3u8=${directM3u8.size}, mp4=${directMp4.size}, embeds=${embeds.size}")
        } catch (e: Exception) {
            Log.w(logTag, "watch page scan failed: ${e.message}")
        }

        if (!vid.isNullOrBlank()) {
            try {
                val playResp = app.get(
                    playUrl,
                    referer = watchUrl,
                    headers = mapOf("User-Agent" to ua, "Accept" to "text/html,application/xhtml+xml")
                )
                val playDoc = playResp.document
                val playHtml = playResp.text
                collectEmbedsFromDoc(playDoc, embeds, lowerPriorityEmbeds)
                val (pm, pp) = scanInlinePlayerJs(playHtml)
                pm.forEach { if (!directM3u8.contains(it)) directM3u8.add(it) }
                pp.forEach { if (!directMp4.contains(it)) directMp4.add(it) }
                Log.d(logTag, "play.php scan -> m3u8=$pm mp4=$pp embeds addtl=${embeds.size - (embeds.size)}")
            } catch (e: Exception) {
                Log.w(logTag, "play.php fetch failed: ${e.message}")
            }
        }

        var found = false
        for (link in directM3u8) {
            try {
                M3u8Helper.generateM3u8(
                    this.name,
                    link,
                    referer = watchUrl,
                    headers = mapOf("User-Agent" to ua, "Referer" to watchUrl)
                ).forEach(callback)
                found = true
            } catch (e: Exception) { Log.w(logTag, "m3u8 failed ($link): ${e.message}") }
        }
        for (link in directMp4) {
            try {
                callback(
                    ExtractorLink(
                        this.name,
                        "مباشر",
                        link,
                        watchUrl,
                        Qualities.Unknown.value,
                        false
                    )
                )
                found = true
            } catch (e: Exception) { Log.w(logTag, "mp4 failed ($link): ${e.message}") }
        }

        val ordered = embeds.toList() + lowerPriorityEmbeds.toList()
        for (embed in ordered) {
            try {
                val l = embed.lowercase()
                val isHls = l.contains(".m3u8") || l.contains("/hls/")
                val isMp4 = l.endsWith(".mp4") || l.contains(".mp4?")
                if (isHls) {
                    if (directM3u8.contains(embed)) continue
                    M3u8Helper.generateM3u8(this.name, embed, referer = watchUrl, headers = mapOf("User-Agent" to ua)).forEach(callback)
                    found = true
                    continue
                }
                if (isMp4) {
                    if (directMp4.contains(embed)) continue
                    callback(ExtractorLink(this.name, "مباشر", embed, watchUrl, Qualities.Unknown.value, false))
                    found = true
                    continue
                }
                if (loadExtractor(embed, referer = watchUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                Log.w(logTag, "resolve embed failed ($embed): ${e.message}")
            }
        }

        if (found) return true

        Log.w(logTag, "HTTP stages empty, falling back to WebViewResolver (watchUrl first)")
        if (tryWebViewResolve(watchUrl, watchUrl, ua, callback)) return true

        if (!vid.isNullOrBlank() && playUrl != watchUrl) {
            Log.w(logTag, "Watch URL WebView failed, trying play.php WebView: $playUrl")
            if (tryWebViewResolve(playUrl, watchUrl, ua, callback)) return true
        }

        Log.e(logTag, "All stages failed for: $data")
        return false
    }
}
