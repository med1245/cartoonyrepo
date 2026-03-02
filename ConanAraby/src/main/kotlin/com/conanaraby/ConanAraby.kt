@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.conanaraby

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.Base64

class ConanAraby : MainAPI() {
    override var mainUrl = "https://conanaraby.com"
    override var name = "كونان بالعربي"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private fun buildHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,*/*"
    )

    override val mainPage = mainPageOf(
        // Latest dubbed episodes (الحديثة)
        "$mainUrl/anime/%d8%a7%d9%84%d9%85%d8%ad%d9%82%d9%82-%d9%83%d9%88%d9%86%d8%a7%d9%86-%d9%85%d8%af%d8%a8%d9%84%d8%ac-%d8%a8%d8%a7%d9%84%d8%b9%d8%b1%d8%a8%d9%8a%d8%a9/" to "كونان مدبلج (الحلقات الحديثة)",
        // Part 4 classic dubbed
        "$mainUrl/anime/meitantei-conan/" to "الجزء الرابع مدبلج",
        // Part 11 dubbed
        "$mainUrl/anime/%d8%a7%d9%84%d9%85%d8%ad%d9%82%d9%82-%d9%83%d9%88%d9%86%d8%a7%d9%86-%d8%a7%d9%84%d8%ac%d8%b2%d8%a1-%d8%a7%d9%84%d8%ad%d8%a7%d8%af%d9%8a-%d8%b9%d8%b4%d8%b1-%d9%85%d8%af%d8%a8%d9%84%d8%ac/" to "الجزء الحادي عشر مدبلج",
        // Movie 28
        "$mainUrl/anime/%d9%81%d9%8a%d9%84%d9%85-%d8%a7%d9%84%d9%85%d8%ad%d9%82%d9%82-%d9%83%d9%88%d9%86%d8%a7%d9%86-28-%d9%88%d9%85%d8%b6%d8%a7%d8%aa-%d9%85%d9%86-%d8%a7%d9%84%d9%85%d8%a7%d8%b6%d9%8a-%d9%85%d8%af%d8%a8/" to "فيلم كونان 28"
    )

    /** Fetch URL and parse as Jsoup Document */
    private suspend fun fetchDoc(url: String): Document? {
        return try {
            withTimeoutOrNull(12000) {
                val resp = app.get(url, headers = buildHeaders())
                Jsoup.parse(resp.text)
            }
        } catch (t: Throwable) {
            Log.e("ConanAraby", "fetchDoc $url failed: ${t.message}")
            null
        }
    }

    // ── Homepage ───────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data
        val doc = fetchDoc(url) ?: return null
        val results = mutableListOf<SearchResponse>()
        parseEpisodeLinks(doc, results)
        if (results.isEmpty()) return null
        return newHomePageResponse(request.name, results.distinctBy { it.url })
    }

    /** Parse all /watch/ links from a document into SearchResponse list */
    private fun parseEpisodeLinks(doc: Document, out: MutableList<SearchResponse>) {
        doc.select("a[href*='/watch/']").forEach { a ->
            val href = a.absUrl("href").ifBlank { return@forEach }
            val rawText = a.text().replace("play_circle_filled", "").trim()
            val title = rawText.ifBlank { a.attr("title").trim().ifBlank { return@forEach } }
            val poster = a.selectFirst("img")?.attr("src")?.ifBlank { null }
            val isMovie = title.contains("فيلم", ignoreCase = true)
            out.add(
                newAnimeSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = fetchDoc(searchUrl) ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        // WordPress search results are in <article> tags or .anime-card
        doc.select("article, .anime-card, .search-result").forEach { article ->
            val a = article.selectFirst("a") ?: return@forEach
            val href = a.absUrl("href").ifBlank { return@forEach }
            if (!href.contains("/anime/") && !href.contains("/watch/")) return@forEach
            val title = article.selectFirst("h2, h3, .entry-title, .title")?.text()?.trim()
                ?: a.attr("title").trim().ifBlank { a.text().trim().ifBlank { return@forEach } }
            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }
            val isMovie = title.contains("فيلم", ignoreCase = true)
            results.add(
                newAnimeSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }
        return results.distinctBy { it.url }
    }

    // ── Load ───────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(url) ?: return null

        val title = doc.selectFirst("h1.anime-title, h1.entry-title, h1, .show-title")
            ?.text()?.trim() ?: "كونان بالعربي"
        val poster = doc.selectFirst("img.anime-poster, .anime-cover img, .wp-post-image")
            ?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst(".anime-synopsis p, .entry-content p, .description")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[name='description'], meta[property='og:description']")?.attr("content")

        // /watch/ URL = single episode page
        if (url.contains("/watch/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // /anime/ URL = season/show page — collect all episodes across pages
        val episodes = mutableListOf<Episode>()
        collectEpisodes(doc, url, episodes)

        if (episodes.isEmpty()) return null

        // Sort by episode number ascending
        val sorted = episodes.sortedBy { it.episode ?: Int.MAX_VALUE }

        val isMovie = title.contains("فيلم", ignoreCase = true) && sorted.size == 1
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = sorted[0].data) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /** Recursively collect episodes from all pages of a season */
    private suspend fun collectEpisodes(
        firstDoc: Document,
        baseUrl: String,
        out: MutableList<Episode>
    ) {
        var doc = firstDoc
        var pageNum = 1
        var seen = 0

        while (true) {
            val links = doc.select("a[href*='/watch/']")
            if (links.isEmpty()) break

            links.forEach { a ->
                val href = a.absUrl("href").ifBlank { return@forEach }
                val rawText = a.text().replace("play_circle_filled", "").trim()
                val epTitle = rawText.ifBlank { a.attr("title").trim() }
                val epNum = Regex("""(\d{1,5})""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                out.add(newEpisode(href) {
                    this.name = epTitle.ifBlank { "حلقة ${out.size + 1}" }
                    this.episode = epNum
                })
            }

            if (out.size == seen) break // no new episodes added
            seen = out.size

            // Check for next page
            val nextLink = doc.selectFirst("a.next, .nav-links a[rel='next'], a:contains(التالي)")
                ?.absUrl("href")?.ifBlank { null }
            if (nextLink == null) break

            pageNum++
            if (pageNum > 60) break // safety limit
            doc = fetchDoc(nextLink) ?: break
        }
    }

    // ── loadLinks ──────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ConanAraby", "loadLinks: $data")
        val doc = fetchDoc(data) ?: return false
        var found = false

        // Method 1: iframe with video_url param (primary method)
        doc.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.absUrl("src").ifBlank { iframe.attr("src") }.ifBlank { return@forEach }
            val videoUrl = extractVideoUrlParam(iframeSrc)
            if (videoUrl != null) {
                Log.d("ConanAraby", "iframe video URL: $videoUrl")
                addLink(videoUrl, "مشاهدة مباشرة", callback)
                found = true
            }
        }

        // Method 2: data-embed-id (Base64 server buttons)
        doc.select("[data-embed-id]").forEachIndexed { index, el ->
            val raw = el.attr("data-embed-id")
            // Format: "ServerName:base64encodedIframeHtml" or just base64
            val encoded = if (raw.contains(":")) raw.substringAfter(":") else raw
            try {
                val decoded = String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
                Log.d("ConanAraby", "embed[$index] decoded: ${decoded.take(150)}")

                // Decode may be an iframe HTML string or raw URL
                val srcUrl = when {
                    decoded.contains("src=") ->
                        Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
                    decoded.startsWith("http") -> decoded.trim()
                    else -> null
                } ?: return@forEachIndexed

                // Try extracting video_url from iframe src
                val videoUrl = extractVideoUrlParam(srcUrl)
                val serverName = if (raw.contains(":")) raw.substringBefore(":") else "خادم ${index + 1}"
                if (videoUrl != null) {
                    addLink(videoUrl, serverName, callback)
                    found = true
                } else if (srcUrl.startsWith("http")) {
                    // Visit the embed page to find video source
                    val embedDoc = fetchDoc(srcUrl)
                    embedDoc?.select("source[src], video[src]")?.forEach { el2 ->
                        val src = el2.attr("src").ifBlank { el2.attr("data-src") }
                        if (src.startsWith("http")) {
                            addLink(src, serverName, callback)
                            found = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ConanAraby", "embed[$index] decode error: ${e.message}")
            }
        }

        // Method 3: Regex scan for video URLs in page HTML
        if (!found) {
            val html = doc.html()
            listOf(
                Regex("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']"""),
                Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']"""),
                Regex("""file["']?\s*:\s*["'](https?://[^"']+)["']"""),
                Regex("""video_url=(https?[^&"'\s]+)""")
            ).forEachIndexed { i, rx ->
                rx.findAll(html).take(3).forEach { m ->
                    var u = m.groupValues[1]
                    try { u = URLDecoder.decode(u, "UTF-8") } catch (_: Exception) {}
                    if (u.startsWith("http")) {
                        addLink(u, "رابط ${i + 1}", callback)
                        found = true
                    }
                }
            }
        }

        if (!found) Log.w("ConanAraby", "No video found for: $data")
        return found
    }

    /** Extract the video_url query parameter from an iframe src URL */
    private fun extractVideoUrlParam(iframeSrc: String): String? {
        return try {
            val after = iframeSrc.substringAfter("video_url=", "")
            if (after.isBlank()) return null
            val raw = after.substringBefore("&")
            val decoded = URLDecoder.decode(raw, "UTF-8")
            if (decoded.startsWith("http")) decoded else null
        } catch (_: Exception) { null }
    }

    private fun addLink(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.contains(".m3u8")
        @Suppress("DEPRECATION")
        callback(
            ExtractorLink(
                source = this.name,
                name = label,
                url = url,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = isM3u8
            )
        )
    }
}
