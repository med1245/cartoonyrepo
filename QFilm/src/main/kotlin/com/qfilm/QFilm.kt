@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.qfilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = data.substringAfter("vid=").substringBefore("&").substringBefore("#")
        val playUrl = "$mainUrl/play.php?vid=$vid"
        val watchUrl = data

        val embeds = linkedSetOf<String>()
        val lowerPriorityEmbeds = linkedSetOf<String>()

        try {
            val playDoc = app.get(
                playUrl,
                referer = watchUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )
            ).document

            playDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attrOrAbs("src").ifBlank { iframe.attr("src") }
                if (src.isBlank()) return@forEach
                val l = src.lowercase()
                if (l.startsWith("blob:")) return@forEach
                if (l.contains("agl") || l.contains("xbeat.space") || l.contains("cdn-cgi")) {
                    lowerPriorityEmbeds.add(src)
                } else {
                    embeds.add(src)
                }
            }

            val html = playDoc.outerHtml()
            val srcRegex = Regex("""src\s*=\s*["'](https?://[^"']+)["']""")
            for (m in srcRegex.findAll(html)) {
                val s = m.groupValues[1]
                val l = s.lowercase()
                if (l.contains("agl") || l.contains("xbeat.space") || l.contains("histats") ||
                    l.contains("dtscdn") || l.contains("dtscout") || l.contains("onaudience") ||
                    l.contains("mrktmtrcs") || l.contains("cloudflareinsights")) continue
                if (embeds.contains(s) || lowerPriorityEmbeds.contains(s)) continue
                embeds.add(s)
            }
        } catch (e: Exception) {
            Log.w(logTag, "play.php fetch failed: ${e.message}")
        }

        try {
            val watchDoc = app.get(watchUrl, referer = "$mainUrl/").document
            watchDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attrOrAbs("src").ifBlank { iframe.attr("src") }
                if (src.isBlank() || src.lowercase().startsWith("blob:")) return@forEach
                val l = src.lowercase()
                if (l.contains("agl") || l.contains("xbeat.space") || l.contains("cdn-cgi")) {
                    if (!embeds.contains(src)) lowerPriorityEmbeds.add(src)
                } else {
                    if (!embeds.contains(src)) embeds.add(src)
                }
            }
        } catch (e: Exception) {
            Log.w(logTag, "watch.php iframe scan failed: ${e.message}")
        }

        val ordered = embeds.toList() + lowerPriorityEmbeds.toList()

        if (ordered.isEmpty()) {
            Log.e(logTag, "loadLinks -> no embed URLs found for $data")
            return false
        }

        var found = false
        for (embed in ordered) {
            try {
                val l = embed.lowercase()
                val isHls = l.contains(".m3u8") || l.contains("/hls/")
                val isMp4 = l.endsWith(".mp4") || l.contains(".mp4?")
                if (isHls || isMp4) {
                    callback(
                        ExtractorLink(
                            this.name,
                            "مباشر",
                            embed,
                            playUrl,
                            Qualities.Unknown.value,
                            isHls
                        )
                    )
                    found = true
                    continue
                }
                if (loadExtractor(embed, referer = playUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                Log.w(logTag, "resolve embed failed ($embed): ${e.message}")
            }
        }

        return found
    }
}
