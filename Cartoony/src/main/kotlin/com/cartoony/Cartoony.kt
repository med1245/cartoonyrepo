@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.cartoony

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
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Cartoony : MainAPI() {
    override var mainUrl = "https://cartoony.net"
    private val watchDomain = "https://carateen.tv"
    override var name = "Cartoony"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    private val apiKey = "7annaba3l_loves_crypto_safe_key!"

    private val reqHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$watchDomain/",
        "Origin" to watchDomain,
        "Accept" to "application/json"
    )

    override val mainPage = mainPageOf(
        Pair("latest", "Latest"),
        Pair("new", "New Uploads"),
        Pair("popular", "Most Watched"),
        Pair("top", "Top Rated"),
        Pair("hot", "Hot"),
        Pair("zaman", "Old Classics")
    )

    private data class ShowItem(
        val id: Int,
        val title: String,
        val plot: String?,
        val poster: String?,
        val isMovie: Boolean,
        val isNew: Boolean = false,
        val isPopular: Boolean = false,
        val isTop: Boolean = false,
        val isHot: Boolean = false,
        val isZaman: Boolean = false,
        val tags: List<String> = emptyList(),
        val rating100: Int? = null,
        val durationMin: Int? = null,
        val year: Int? = null,
        val minAge: Int? = null,
        val fromLegacy: Boolean = false
    )

    private data class LegacyEpisode(
        val id: Int,
        val title: String,
        val durationSec: Int?,
        val orderId: Int?,
        val thumbnail: String?,
        val videoId: String?
    )

    @Volatile private var cachedShows: List<ShowItem>? = null
    @Volatile private var cachedShowsAt: Long = 0L
    private val showCacheTtlMs = 5 * 60 * 1000L

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", "،").map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun parseYearFromCreatedAt(createdAt: String?): Int? {
        if (createdAt.isNullOrBlank() || createdAt.length < 4) return null
        val y = createdAt.take(4).toIntOrNull() ?: return null
        return if (y in 1900..2100) y else null
    }

    private fun parseRating100(value: Double): Int? {
        if (value <= 0.0) return null
        return (value * 20.0).toInt().coerceIn(0, 100)
    }

    private fun parseAgeRating(minAge: Int?): String? {
        val age = minAge ?: return null
        return if (age > 0) "${age}+" else null
    }

    private fun normalizeTitle(s: String): String {
        return s.lowercase().replace('|', ' ').replace('-', ' ').replace('–', ' ').replace("  ", " ").trim()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Invalid hex length: ${clean.length}" }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun decryptPayload(encryptedHex: String, ivHex: String): String? {
        return try {
            val keyBytes = apiKey.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(hexToBytes(ivHex)))
            String(cipher.doFinal(hexToBytes(encryptedHex)), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e("Cartoony", "decryptPayload failed: ${t.message}")
            null
        }
    }

    private fun decryptEnvelope(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return trimmed
        return try {
            val obj = JSONObject(trimmed)
            val enc = obj.optString("encryptedData", "")
            val iv  = obj.optString("iv", "")
            if (enc.isNotBlank() && iv.isNotBlank()) decryptPayload(enc, iv) else trimmed
        } catch (t: Throwable) {
            Log.e("Cartoony", "decryptEnvelope failed: ${t.message}")
            null
        }
    }

    private suspend fun get(url: String, base: String): String? {
        return try {
            val h = reqHeaders.toMutableMap().also { it["Origin"] = base; it["Referer"] = "$base/" }
            decryptEnvelope(app.get(url, headers = h).text)
        } catch (t: Throwable) {
            Log.e("Cartoony", "GET $url failed: ${t.message}")
            null
        }
    }

    private suspend fun apiGet(path: String): String? =
        get("$mainUrl/api/sp/$path", mainUrl) ?: get("$watchDomain/api/sp/$path", watchDomain)

    private suspend fun legacyGet(path: String): String? =
        get("$mainUrl/api/$path", mainUrl) ?: get("$watchDomain/api/$path", watchDomain)

    private suspend fun spLink(epId: Int, showId: Int?, base: String): String? {
        return try {
            val h = reqHeaders.toMutableMap().also { it["Origin"] = base; it["Referer"] = "$base/" }
            val body = mutableMapOf("episodeId" to epId.toString())
            if (showId != null) body["showId"] = showId.toString()
            decryptEnvelope(app.post("$base/api/sp/episode/link", headers = h, data = body).text)
        } catch (t: Throwable) {
            Log.e("Cartoony", "POST spLink failed: ${t.message}")
            null
        }
    }

    private fun getUrlForShow(show: ShowItem): String {
        val base = if (show.fromLegacy) "$mainUrl/watch/${show.id}" else "$mainUrl/watch/sp/${show.id}"
        return if (show.isMovie) "$base?type=movie" else "$base?type=series"
    }

    private fun buildSearchFromShow(show: ShowItem): SearchResponse {
        return newAnimeSearchResponse(show.title, getUrlForShow(show), if (show.isMovie) TvType.Movie else TvType.TvSeries) {
            this.posterUrl = show.poster
        }
    }

    private fun mapSpShow(obj: JSONObject): ShowItem? {
        val id = obj.optInt("id", -1)
        if (id <= 0) return null
        return ShowItem(
            id = id,
            title = obj.optString("name").trim().ifBlank { obj.optString("pref").trim().ifBlank { "غير معنون" } },
            plot = obj.optString("pref").ifBlank { null },
            poster = obj.optString("cover_full_path").ifBlank { obj.optString("cover").ifBlank { null } },
            isMovie = obj.optInt("is_movie", 0) == 1,
            isNew = obj.optInt("is_new", 0) == 1,
            isPopular = obj.optInt("is_popular", 0) == 1,
            isTop = obj.optInt("is_top", 0) == 1,
            isHot = obj.optInt("is_hot", 0) == 1,
            isZaman = obj.optInt("is_zaman", 0) == 1,
            tags = parseTags(obj.optString("tags")),
            rating100 = parseRating100(obj.optDouble("rating", 0.0)),
            durationMin = obj.optInt("ep_duration", 0).takeIf { it > 0 },
            year = parseYearFromCreatedAt(obj.optString("created_at")),
            minAge = obj.optInt("min_age", 0).takeIf { it > 0 },
            fromLegacy = false
        )
    }

    private fun mapLegacyShow(obj: JSONObject): ShowItem? {
        val id = obj.optInt("id", -1)
        if (id <= 0) return null
        val episodesCount = obj.optString("episodes_count")
        val category = obj.optString("category").lowercase()
        val isMovie = category.contains("فيلم") || (!episodesCount.contains("حلقة") && episodesCount.toIntOrNull() == 1)
        val durationStr = obj.optString("episodes_length")
        val durationMin = Regex("(\\d+)").find(durationStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return ShowItem(
            id = id,
            title = obj.optString("title").trim().ifBlank { "غير معنون" },
            plot = obj.optString("description").ifBlank { null },
            poster = obj.optString("poster_cover").ifBlank { null }?.let { "https://cartoony.net/assets/img/posters/$it" },
            isMovie = isMovie,
            tags = parseTags(obj.optString("category")),
            rating100 = parseRating100(obj.optDouble("rating", 0.0)),
            durationMin = durationMin,
            year = obj.optString("release_year").toIntOrNull(),
            fromLegacy = true
        )
    }

    private suspend fun getSpShows(): List<ShowItem> {
        val txt = apiGet("tvshows") ?: return emptyList()
        val arr = try { JSONArray(txt) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> mapSpShow(o) } }
    }

    private suspend fun getLegacyShows(): List<ShowItem> {
        val txt = legacyGet("tvshows") ?: return emptyList()
        val arr = try { JSONArray(txt) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> mapLegacyShow(o) } }
    }

    private suspend fun getMergedShows(): List<ShowItem> {
        val now = System.currentTimeMillis()
        cachedShows?.takeIf { (now - cachedShowsAt) < showCacheTtlMs }?.let { return it }
        val merged = LinkedHashMap<Int, ShowItem>()
        for (s in getSpShows()) merged[s.id] = s
        for (l in getLegacyShows()) {
            val cur = merged[l.id]
            if (cur == null) merged[l.id] = l
            else merged[l.id] = cur.copy(
                title = if (cur.title.isBlank() || cur.title == "غير معنون") l.title else cur.title,
                plot = cur.plot ?: l.plot, poster = cur.poster ?: l.poster,
                isMovie = cur.isMovie || l.isMovie, tags = (cur.tags + l.tags).distinct(),
                rating100 = cur.rating100 ?: l.rating100, durationMin = cur.durationMin ?: l.durationMin,
                year = cur.year ?: l.year, minAge = cur.minAge ?: l.minAge,
                fromLegacy = cur.fromLegacy || l.fromLegacy
            )
        }
        return merged.values.toList().also { cachedShows = it; cachedShowsAt = now }
    }

    private suspend fun getLegacyEpisodes(showId: Int): List<LegacyEpisode> {
        val txt = legacyGet("episodes?id=$showId") ?: return emptyList()
        val arr = try { JSONArray(txt) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LegacyEpisode(
                id = o.optInt("id", -1),
                title = o.optString("title").ifBlank { "Episode ${o.optInt("order_id", i + 1)}" },
                durationSec = o.optInt("duration", 0).takeIf { it > 0 },
                orderId = o.optInt("order_id", i + 1).takeIf { it > 0 },
                thumbnail = o.optString("thumbnail").ifBlank { null }?.let { "https://cartoony.net/assets/img/thumbnails/$it" },
                videoId = o.optString("video_id").ifBlank { null }
            )
        }.sortedBy { it.orderId ?: Int.MAX_VALUE }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        val qNorm = normalizeTitle(q)
        return getMergedShows()
            .filter { s -> normalizeTitle(listOf(s.title, s.plot ?: "", s.tags.joinToString(" ")).joinToString(" ")).contains(qNorm) }
            .distinctBy { it.id }
            .map(::buildSearchFromShow)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()
        val merged = getMergedShows()
        val mergedById = merged.associateBy { it.id }

        apiGet("recentEpisodes")?.let { txt ->
            val arr = try { JSONArray(txt) } catch (e: Exception) { null } ?: return@let
            val latest = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val showId = o.optInt("tv_series_id", -1)
                if (showId <= 0) continue
                val show = mergedById[showId]
                val title = show?.title ?: o.optString("name").ifBlank { "غير معنون" }
                val poster = show?.poster ?: o.optString("cover_full_path")
                val url = if (show != null) getUrlForShow(show) else "$mainUrl/watch/sp/$showId"
                latest.add(newAnimeSearchResponse(title, url, if (show?.isMovie == true) TvType.Movie else TvType.TvSeries) { this.posterUrl = poster })
            }
            if (latest.isNotEmpty()) sections.add(HomePageList("Latest", latest.distinctBy { it.url }))
        }

        fun section(name: String, list: List<ShowItem>) {
            if (list.isNotEmpty()) sections.add(HomePageList(name, list.distinctBy { it.id }.map(::buildSearchFromShow)))
        }

        section("New Uploads", merged.filter { it.isNew }.ifEmpty { merged.take(60) })
        section("Most Watched", merged.filter { it.isPopular })
        section("Top Rated", merged.filter { it.isTop }.ifEmpty { merged.sortedByDescending { it.rating100 ?: 0 }.take(60) })
        section("Hot", merged.filter { it.isHot })
        section("Old Classics", merged.filter { it.isZaman })

        return newHomePageResponse(sections)
    }

    override suspend fun load(url: String): LoadResponse? {
        val urlNoQuery = url.substringBefore("?")
        val watchParts = urlNoQuery.substringAfter("/watch/", "").split("/").filter { it.isNotBlank() }

        val showIdStr: String?
        val episodeIdStr: String?
        when {
            watchParts.firstOrNull() == "sp" -> { showIdStr = watchParts.getOrNull(1); episodeIdStr = watchParts.getOrNull(2) }
            watchParts.isNotEmpty() -> { showIdStr = watchParts.getOrNull(0); episodeIdStr = watchParts.getOrNull(1) }
            else -> { showIdStr = urlNoQuery.substringAfterLast("/"); episodeIdStr = null }
        }

        val id = showIdStr?.toIntOrNull() ?: return null
        val show = getMergedShows().firstOrNull { it.id == id }
        val isMovie = show?.isMovie == true || url.contains("type=movie") || url.contains("/movie/")

        // Direct episode deep-link (e.g. /watch/sp/1017/32293)
        if (episodeIdStr?.toIntOrNull() != null) {
            val epId = episodeIdStr.toInt()
            return newMovieLoadResponse(name = "Cartoony Episode", url = url, type = TvType.Anime, dataUrl = "sp:$epId|$id")
        }

        if (isMovie) {
            // Try legacy first
            val legEps = getLegacyEpisodes(id)
            if (legEps.isNotEmpty()) {
                val first = legEps.first()
                return newMovieLoadResponse(name = show?.title ?: first.title, url = url, type = TvType.Movie, dataUrl = "leg:${first.videoId ?: ""}|${first.id}|$id") {
                    this.posterUrl = show?.poster ?: first.thumbnail
                    this.plot = show?.plot; this.tags = show?.tags; this.rating = show?.rating100
                    this.duration = show?.durationMin ?: first.durationSec?.div(60)
                    this.year = show?.year; this.contentRating = parseAgeRating(show?.minAge)
                }
            }
            // SP fallback
            val spTxt = apiGet("episodes?id=$id") ?: return null
            val spArr = try { JSONArray(spTxt) } catch (e: Exception) { return null }
            val firstSp = spArr.optJSONObject(0) ?: return null
            val epId = firstSp.optInt("id", -1).takeIf { it > 0 } ?: return null
            return newMovieLoadResponse(name = show?.title ?: "Cartoony Movie", url = url, type = TvType.Movie, dataUrl = "sp:$epId|$id") {
                this.posterUrl = show?.poster ?: firstSp.optString("cover_full_path")
                this.plot = show?.plot; this.tags = show?.tags; this.rating = show?.rating100
                this.duration = show?.durationMin; this.year = show?.year; this.contentRating = parseAgeRating(show?.minAge)
            }
        }

        // Series
        // First, attempt to get legacy episodes
        val legEps = getLegacyEpisodes(id)
        Log.d("Cartoony", "Legacy episodes count: ${legEps.size} for showId=$id")
        // Then, attempt to get SP episodes as fallback
        val spTxt = apiGet("episodes?id=$id")
        val spArr = spTxt?.let { try { JSONArray(it) } catch (e: Exception) { null } }
        val spEps = spArr?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val epId = o.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val epName = o.optString("name").ifBlank { o.optString("pref").ifBlank { "Episode ${o.optInt("number", i + 1)}" } }
                val poster = o.optString("cover_full_path").ifBlank { o.optString("cover") }
                Pair(epId, Triple(epName, o.optInt("number", i + 1), poster))
            }
        } ?: emptyList()

        // Combine legacy and SP episodes, preferring legacy when available
        val combinedEps = mutableListOf<Episode>()
        // Add legacy episodes first
        legEps.forEachIndexed { idx, ep ->
            combinedEps.add(newEpisode("leg:${ep.videoId ?: ""}|${ep.id}|$id") {
                this.name = ep.title
                this.episode = ep.orderId ?: (idx + 1)
                this.posterUrl = ep.thumbnail ?: show?.poster
            })
        }
        // Add SP episodes that are not already covered (by order or title)
        // Simple approach: add all SP episodes after legacy ones
        spEps.forEach { (epId, triple) ->
            val (epName, number, poster) = triple
            combinedEps.add(newEpisode("sp:$epId|$id") {
                this.name = epName
                this.episode = number
                this.posterUrl = poster.ifBlank { show?.poster }
            })
        }

        if (combinedEps.isEmpty()) return null
        return newTvSeriesLoadResponse(show?.title ?: "Cartoony Series", url, TvType.TvSeries, combinedEps) {
            this.posterUrl = show?.poster
            this.plot = show?.plot
            this.tags = show?.tags
            this.rating = show?.rating100
            this.duration = show?.durationMin
            this.year = show?.year
            this.contentRating = parseAgeRating(show?.minAge)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Cartoony", "loadLinks: $data")

        // Parse IDs from data payload
        // Format: [prefix:](videoId|episodeId|showId)
        val raw = data.substringAfter(":")
        val parts = raw.split("|").map { it.trim() }
        
        val videoId   = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "null" }
        val episodeId = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }?.toIntOrNull()
        val showId    = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "null" }?.toIntOrNull()

        // We'll collect all candidates for potential direct HLS links
        val idCandidates = mutableSetOf<String>()
        videoId?.let { idCandidates.add(it) }
        episodeId?.let { idCandidates.add(it.toString()) }

        var linkFound = false

        // Strategy 1: SP episode/link POST (Try both domains)
        val spEpId = episodeId ?: videoId?.toIntOrNull()
        if (spEpId != null) {
            for (base in listOf(mainUrl, watchDomain)) {
                val txt = spLink(spEpId, showId, base)
                if (txt != null) {
                    val obj = try { JSONObject(txt) } catch (e: Exception) { null }
                    if (obj != null) {
                        val link = obj.optString("link", "").trim()
                        if (link.startsWith("http")) {
                            callback(ExtractorLink(name, "$name SP", link, "$base/", Qualities.Unknown.value, false))
                            linkFound = true
                        }
                        val cdn = obj.optString("cdn_stream_private_id", "").trim()
                        if (cdn.isNotBlank()) {
                            callback(ExtractorLink(name, "$name CDN", "https://vod.spacetoongo.com/asset/$cdn/play_video/index.m3u8", "$base/", Qualities.Unknown.value, false))
                            linkFound = true
                        }
                    }
                }
                if (linkFound) break
            }
        }

        // Strategy 2: Legacy episode API GET
        if (!linkFound && episodeId != null) {
            val q = if (showId != null && showId != episodeId) "&showId=$showId" else ""
            val ltx = legacyGet("episode?episodeId=$episodeId$q")
            if (ltx != null) {
                val obj = try { JSONObject(ltx) } catch (e: Exception) { null }
                val su = obj?.optString("streamUrl", "") ?: ""
                if (su.startsWith("http")) {
                    callback(ExtractorLink(name, "$name Legacy", su, "$mainUrl/", Qualities.Unknown.value, su.contains(".m3u8")))
                    linkFound = true
                }
            }
        }

        // Strategy 3: Pegasus Fallback (Try all numeric candidates)
        // If we still have no link, or even if we do (as extra mirrors), try the direct HLS endpoint
        for (id in idCandidates) {
            val pegasusUrl = "https://pegasus.5387692.xyz/api/hls/$id/playlist.m3u8"
            callback(ExtractorLink(name, "$name HLS", pegasusUrl, "$mainUrl/", Qualities.Unknown.value, true))
            linkFound = true
        }

        return linkFound
    }
}
