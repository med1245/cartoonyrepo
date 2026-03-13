@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "NewApi")

package com.stardima

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class StarDima : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "StarDima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private val ua = "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

    private fun hdrs() = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        ""           to "الرئيسية",
        "newrelases" to "المضاف حديثاً",
        "mosalsalat" to "مسلسلات",
        "aflam"      to "أفلام"
    )

    private fun isMovie(url: String) = url.contains("/movie/")
    private fun isTvShow(url: String) = url.contains("/tvshow/")

    /** Get og:title or fallback, never the login-modal h1 */
    private fun pageTitle(doc: Document): String {
        // og:title is always set to the real content title
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (!og.isNullOrBlank() && !og.contains("تسجيل") && !og.contains("ستارديما | STARDIMA")) return og
        // Try the browser <title> minus site name suffix
        val t = doc.title().trim()
            .substringBefore(" - ستارديما")
            .substringBefore(" | ستارديما")
            .substringBefore(" - STARDIMA")
            .substringBefore(" | STARDIMA")
            .trim()
        return t.ifBlank { "StarDima" }
    }

    /**
     * Parse show/movie cards from a page.
     *
     * The site renders cards where the title is in h2/h3 and the watch link
     * says "شاهد الآن". Strategy: find all tvshow/movie links, then walk UP
     * the DOM to find the nearest h2/h3 title and img poster.
     */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").trim()
            if (href.isBlank() || !href.startsWith(mainUrl)) continue
            if (!isTvShow(href) && !isMovie(href)) continue
            if (href.contains("/play/")) continue
            if (!seen.add(href)) continue

            var title: String? = a.attr("title").trim().ifBlank { null }
            var poster: String? = null

            // Check img inside anchor
            if (title == null) {
                val imgInside = a.selectFirst("img")
                if (imgInside != null) {
                    title = imgInside.attr("alt").trim().ifBlank { null }
                    poster = imgInside.attr("abs:src").ifBlank { null }
                        ?: imgInside.attr("data-src").ifBlank { null }
                }
            }

            // Walk up DOM looking for h2/h3 heading
            if (title == null) {
                var el: Element? = a.parent()
                var depth = 0
                while (el != null && depth < 6) {
                    val heading = el.selectFirst("h2, h3")
                    if (heading != null && heading.text().isNotBlank()) {
                        title = heading.text().trim()
                    }
                    if (poster == null) {
                        val img = el.selectFirst("img[src]")
                        if (img != null) poster = img.attr("abs:src").ifBlank { null }
                    }
                    if (title != null) break
                    el = el.parent()
                    depth++
                }
            }

            // Look at previous siblings for h2/h3
            if (title == null) {
                var sibling = a.previousElementSibling()
                while (sibling != null) {
                    val tag = sibling.tagName()
                    if ((tag == "h2" || tag == "h3") && sibling.text().isNotBlank()) {
                        title = sibling.text().trim()
                        break
                    }
                    sibling = sibling.previousElementSibling()
                }
            }

            // Fallback to link text only if it doesn't contain "شاهد"
            if (title == null) {
                val txt = a.text().trim()
                if (txt.isNotBlank() && !txt.contains("شاهد")) title = txt
            }

            val cleanTitle = title?.replace(Regex("\\s+"), " ")?.trim()
            if (cleanTitle.isNullOrBlank()) continue
            
            val type = if (isMovie(href)) TvType.Movie else if (isTvShow(href)) TvType.TvSeries else TvType.Anime
            results.add(newAnimeSearchResponse(cleanTitle, href, type) {
                posterUrl = poster
            })
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data
        val url = when {
            key.isBlank() -> mainUrl
            page == 1    -> "$mainUrl/$key"
            else         -> "$mainUrl/$key?page=$page"
        }
        val doc = try {
            app.get(url, headers = hdrs()).document
        } catch (t: Throwable) {
            Log.e("StarDima", "getMainPage failed: ${t.message}")
            return newHomePageResponse(request.name, emptyList())
        }
        val items = parseCards(doc)
        val hasMore = key.isNotBlank() && items.isNotEmpty() && page < 50
        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        return try {
            val doc = app.get(
                "$mainUrl/search",
                params = mapOf("q" to q),
                headers = hdrs()
            ).document
            parseCards(doc)
        } catch (t: Throwable) {
            Log.e("StarDima", "search failed: ${t.message}")
            emptyList()
        }
    }

    // ─── load ─────────────────────────────────────────────────────────────────

    private suspend fun fetchSeasonEpisodes(seasonId: String, showPoster: String?): List<Episode> {
        return try {
            val text = app.get(
                "$mainUrl/series/season/$seasonId",
                headers = hdrs() + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, */*; q=0.01"
                )
            ).text

            if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) {
                return emptyList()
            }

            val json = org.json.JSONObject(text)
            val arr = json.optJSONArray("episodes") ?: return emptyList()
            val episodes = mutableListOf<Episode>()
            for (i in 0 until arr.length()) {
                val ep = arr.getJSONObject(i)
                val num = ep.optInt("episode_number", i + 1)
                val epTitle = ep.optString("title", "الحلقة $num")
                val watchUrl = ep.optString("watch_url", "")
                if (watchUrl.isBlank()) continue
                episodes.add(newEpisode(watchUrl) {
                    name = epTitle.ifBlank { null }
                    episode = num
                    posterUrl = showPoster
                })
            }
            episodes
        } catch (t: Throwable) {
            Log.e("StarDima", "fetchSeasonEpisodes($seasonId) failed: ${t.message}")
            emptyList()
        }
    }

    private fun extractEpisodesFromHtml(doc: Document, poster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        var counter = 1
        
        // Try multiple selectors for episode links
        val episodeLinks = mutableListOf<Element>()
        episodeLinks.addAll(doc.select("a[href*=/play/]"))
        episodeLinks.addAll(doc.select("a[href*=/episode/]"))
        episodeLinks.addAll(doc.select("a:containsOwn(حلقة)")) // Arabic for "Episode"
        
        for (a in episodeLinks.distinct()) {
            val href = a.attr("abs:href").trim()
            if (href.isBlank() || !seen.add(href)) continue
            
            // Skip if it's a modal or non-episode link
            if (href.contains("modal") || href.contains("login") || href.contains("signup")) continue
            
            // Never use the DB id (long number) as episode number - use sequential counter
            val rawText = a.text().trim()
            
            // Filter out pure play-button links that have no real title
            val isMeaningfulText = rawText.isNotBlank()
                && !rawText.contains("تشغيل")
                && !rawText.contains("play", ignoreCase = true)
                && !rawText.contains("Watch")
                && rawText.length > 2
            
            val epName = if (isMeaningfulText) rawText else "الحلقة $counter"
            val epPoster = a.selectFirst("img")?.attr("abs:src")?.ifBlank { null }
                ?: a.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            
            episodes.add(newEpisode(href) {
                name = epName
                episode = counter
                posterUrl = epPoster ?: poster
            })
            counter++
        }
        
        // If we still haven't found episodes, try looking for any links that might be episodes
        if (episodes.isEmpty()) {
            val allLinks = doc.select("a[href]")
            for (a in allLinks) {
                val href = a.attr("abs:href").trim()
                if (href.isBlank() || !seen.add(href)) continue
                
                // Look for patterns that might indicate episode links
                if (href.contains("/play/") || href.contains("/episode/") || 
                    a.text().contains("حلقة") || a.text().contains("Episode")) {
                    
                    val rawText = a.text().trim()
                    val isMeaningfulText = rawText.isNotBlank()
                        && !rawText.contains("تشغيل")
                        && !rawText.contains("play", ignoreCase = true)
                        && !rawText.contains("Watch")
                        && rawText.length > 2
                    
                    val epName = if (isMeaningfulText) rawText else "الحلقة $counter"
                    val epPoster = a.selectFirst("img")?.attr("abs:src")?.ifBlank { null }
                        ?: a.selectFirst("img")?.attr("data-src")?.ifBlank { null }
                    
                    episodes.add(newEpisode(href) {
                        name = epName
                        episode = counter
                        posterUrl = epPoster ?: poster
                    })
                    counter++
                }
            }
        }
        
        return episodes
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = hdrs()).document

            // Always prefer og:title — h1 on stardima.com shows the login modal text
            val title = pageTitle(doc)

            val plot = doc.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")?.trim()

            val poster = doc.selectFirst("meta[property=og:image]")
                ?.attr("content")?.ifBlank { null }

            if (isMovie(url)) {
                // For movies, the data is either a direct play URL or the page itself
                val playLink = doc.select("a[href*=/play/]").firstOrNull()?.attr("abs:href") ?: url
                return newMovieLoadResponse(title, url, TvType.Movie, playLink) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else if (isTvShow(url)) {
                // TV Show: gather episodes
                val allEpisodes = mutableListOf<Episode>()
                
                // Try JSON season API
                val episodesContainer = doc.selectFirst("[data-initial-season-id]")
                val initialSeasonId = episodesContainer?.attr("data-initial-season-id")?.trim()
                val seasonItems = doc.select("[data-season-id]")
                val seasonIds = when {
                    seasonItems.isNotEmpty() ->
                        seasonItems.map { it.attr("data-season-id") }.filter { it.isNotBlank() }.distinct()
                    !initialSeasonId.isNullOrBlank() -> listOf(initialSeasonId)
                    else -> emptyList()
                }
                
                for ((idx, sid) in seasonIds.withIndex()) {
                    val eps = fetchSeasonEpisodes(sid, poster)
                    for (ep in eps) {
                        allEpisodes.add(newEpisode(ep.data) {
                            name = ep.name
                            episode = ep.episode
                            season = idx + 1
                            posterUrl = ep.posterUrl
                        })
                    }
                }
                
                // HTML fallback
                if (allEpisodes.isEmpty()) {
                    allEpisodes.addAll(extractEpisodesFromHtml(doc, poster))
                }
                
                // Fetch from first episode page as last resort
                if (allEpisodes.isEmpty()) {
                    val firstPlay = doc.select("a[href*=/play/]").firstOrNull()?.attr("abs:href")
                    if (firstPlay != null) {
                        try {
                            val pDoc = app.get(firstPlay, headers = hdrs()).document
                            val sid = pDoc.selectFirst("[data-initial-season-id]")
                                ?.attr("data-initial-season-id")?.trim()
                            if (!sid.isNullOrBlank()) {
                                allEpisodes.addAll(fetchSeasonEpisodes(sid, poster))
                            }
                            if (allEpisodes.isEmpty()) {
                                allEpisodes.addAll(extractEpisodesFromHtml(pDoc, poster))
                            }
                        } catch (_: Throwable) {}
                    }
                }
                
                if (allEpisodes.isEmpty()) return null
                
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // Unsupported URL type
                return null
            }
        } catch (t: Throwable) {
            Log.e("StarDima", "load($url) failed: ${t.message}")
            null
        }
    }

    // ─── loadLinks ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StarDima", "loadLinks: $data")
        var found = false

        // Collect all URLs to try (start with the stardima play page iframes, then the data URL itself)
        val urlsToTry = mutableListOf<String>()

        if (data.startsWith(mainUrl)) {
            try {
                // Fetch the episode play page — this may have hyperwatching.com iframes
                val resp = app.get(
                    data,
                    headers = hdrs() + mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )
                val doc = resp.document
                val html = resp.text

                // Look for iframe embeds
                for (iframe in doc.select("iframe[src]")) {
                    val src = iframe.attr("abs:src").trim()
                    if (src.isNotBlank() && src.startsWith("http") && src != data) {
                        urlsToTry.add(src)
                    }
                }

                // Try to extract streams directly from the page HTML (regex for m3u8/mp4)
                found = found || tryExtractStreams(html, data, callback)

                // Try to extract player server links from server-select buttons
                // e.g. data-url attributes on watch buttons
                for (el in doc.select("[data-url]")) {
                    val watchUrl = el.attr("data-url").trim()
                    if (watchUrl.startsWith("http")) urlsToTry.add(watchUrl)
                }

                // Look for player-watch-server links
                for (a in doc.select("a[href*=hyperwatching], a[href*=embed], a[href*=player]")) {
                    val href = a.attr("abs:href").trim()
                    if (href.startsWith("http") && href != data) urlsToTry.add(href)
                }
            } catch (_: Throwable) {}
        } else {
            urlsToTry.add(data)
        }

        for (targetUrl in urlsToTry) {
            if (found) break
            try {
                // First try CloudStream's known extractors
                if (loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }

                val resp = app.get(targetUrl, headers = hdrs() + mapOf("Referer" to mainUrl))
                val html = resp.text
                val doc = resp.document

            // Try video/source tags
            for (el in doc.select("video[src], source[src]")) {
                val src = el.attr("abs:src").trim()
                if (!src.startsWith("http")) continue
                if (src.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, src, targetUrl).forEach { callback(it); found = true }
                } else if (src.contains(".mp4")) {
                    callback(ExtractorLink(name, "$name MP4", src, targetUrl, Qualities.Unknown.value, false))
                    found = true
                }
            }

            // Try HTML grep
            if (!found) {
                found = found || tryExtractStreams(html, targetUrl, callback)
            }

            // Follow nested iframes
            if (!found) {
                for (iframe in doc.select("iframe[src]")) {
                    if (found) break
                    val iSrc = iframe.attr("abs:src").trim()
                    if (iSrc.isBlank() || !iSrc.startsWith("http") || iSrc == targetUrl) continue

                    // Try known extractors
                    if (loadExtractor(iSrc, targetUrl, subtitleCallback, callback)) {
                        found = true
                        continue
                    }

                    try {
                        val iResp = app.get(iSrc, headers = hdrs() + mapOf("Referer" to targetUrl))
                        val iHtml = iResp.text
                        val iDoc = iResp.document

                        for (el in iDoc.select("video[src], source[src]")) {
                            val src = el.attr("abs:src").trim()
                            if (!src.startsWith("http")) continue
                            if (src.contains(".m3u8")) {
                                M3u8Helper.generateM3u8(name, src, iSrc).forEach { callback(it); found = true }
                            } else if (src.contains(".mp4")) {
                                callback(ExtractorLink(name, "$name Iframe", src, iSrc, Qualities.Unknown.value, false))
                                found = true
                            }
                        }

                        if (!found) found = found || tryExtractStreams(iHtml, iSrc, callback)
                    } catch (_: Throwable) {}
                }
            }
            
            // Additional fallback: try to extract any video URL from the page
            if (!found) {
                val videoUrls = mutableListOf<String>()
                // Look for any URLs that might be video files
                val urlPatterns = listOf(
                    Regex("""https?://[^\s"'\\]+\.(?:mp4|m3u8|m3u)(?:\?[^\s"'\\]*)?"""),
                    Regex("""["'](?:file|url|src|stream|link|source|hls|video)["']\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8|m3u)[^"']*)["']""", RegexOption.IGNORE_CASE)
                )
                
                for (pattern in urlPatterns) {
                    for (match in pattern.findAll(html)) {
                        val url = if (pattern.pattern.startsWith("[")) match.groupValues[1] else match.value
                        if (url.startsWith("http")) {
                            videoUrls.add(url)
                        }
                    }
                }
                
                // Try each found URL
                for (videoUrl in videoUrls.distinct()) {
                    if (found) break
                    try {
                        // Try known extractors first
                        if (loadExtractor(videoUrl, targetUrl, subtitleCallback, callback)) {
                            found = true
                            continue
                        }
                        
                        // Try direct video URL
                        val resp = app.get(videoUrl, headers = hdrs() + mapOf("Referer" to targetUrl))
                        if (resp.code == 200) {
                            val contentType = resp.headers["Content-Type"] ?: ""
                            if (contentType.contains("video/") || videoUrl.contains(".mp4")) {
                                callback(ExtractorLink(name, "$name Direct", videoUrl, targetUrl, Qualities.Unknown.value, false))
                                found = true
                            } else if (videoUrl.contains(".m3u8") || contentType.contains("mpegurl")) {
                                M3u8Helper.generateM3u8(name, videoUrl, targetUrl).forEach { callback(it); found = true }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("StarDima", "Failed to process video URL $videoUrl: ${e.message}")
                    }
                }
            }
            } catch (t: Throwable) {
                Log.e("StarDima", "loadLinks $targetUrl: ${t.message}")
            }
        }

        if (!found) Log.w("StarDima", "No stream: $data")
        return found
    }

    /**
     * Extract m3u8/mp4 URLs from raw HTML using regex patterns.
     * Returns true if any link was found and dispatched to callback.
     */
    private fun tryExtractStreams(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false

        // Pattern 1: bare m3u8/mp4 URLs
        val urlRegex = Regex("""https?://[^\s"'\\]+\.(?:mp4|m3u8)(?:\?[^\s"'\\]*)?""")
        for (m in urlRegex.findAll(html)) {
            val src = m.value
            val isM3u8 = src.contains(".m3u8")
            if (isM3u8) {
                // Note: can't call suspend M3u8Helper here, just pass the URL directly
                callback(ExtractorLink(name, "$name HLS", src, referer, Qualities.Unknown.value, true))
            } else {
                callback(ExtractorLink(name, "$name MP4", src, referer, Qualities.Unknown.value, false))
            }
            found = true
        }

        // Pattern 2: JSON key-value with stream URLs
        if (!found) {
            val jsonRegex = Regex(
                """["'](?:file|url|src|stream|link|source|hls|video)["']\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )
            for (m in jsonRegex.findAll(html)) {
                val src = m.groupValues[1]
                val isM3u8 = src.contains(".m3u8")
                callback(ExtractorLink(name, "$name Stream", src, referer, Qualities.Unknown.value, isM3u8))
                found = true
            }
        }

        return found
    }
}
