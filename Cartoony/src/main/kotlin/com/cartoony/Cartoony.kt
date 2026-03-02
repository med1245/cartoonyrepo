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
import kotlinx.coroutines.*
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
        return s.lowercase()
            .replace(Regex("[أإآ]"), "ا")
            .replace(Regex("ة"), "ه")
            .replace(Regex("ى"), "ي")
            .replace(Regex("[^a-z0-9\u0621-\u064A\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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

    private suspend fun get(url: String, base: String): String? = withTimeoutOrNull(10000) {
        try {
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

    // Fetch episode video URL via unified /api/episode endpoint (both SP and Legacy)
    private suspend fun fetchEpisodeLink(episodeId: Int, showId: Int): String? {
        // The get() function already decrypts, so txt is the decrypted JSON string
        val txt = legacyGet("episode?episodeId=$episodeId&showId=$showId") ?: return null
        val obj = try { JSONObject(txt) } catch (e: Exception) { return null }
        // streamUrl is the Pegasus HLS URL directly
        return obj.optString("streamUrl", "").trim().ifBlank {
            obj.optString("link", "").trim().ifBlank { null }
        }
    }

    private suspend fun spLink(epId: Int, showId: Int?, base: String): String? = withTimeoutOrNull(10000) {
        try {
            val h = reqHeaders.toMutableMap().also { it["Origin"] = base; it["Referer"] = "$base/" }
            val body = mutableMapOf(
                "episodeId" to epId.toString(),
                "userId" to "anon_${System.currentTimeMillis()}_${(1000..9999).random()}"
            )
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

    private suspend fun getMergedShows(): List<ShowItem> = coroutineScope {
        val now = System.currentTimeMillis()
        val cache = cachedShows
        if (cache != null && (now - cachedShowsAt) < showCacheTtlMs) {
            return@coroutineScope cache
        }
        
        // Parallel fetch SP and Legacy lists to improve speed
        val spJob = async { try { getSpShows() } catch (e: Exception) { emptyList<ShowItem>() } }
        val legJob = async { try { getLegacyShows() } catch (e: Exception) { emptyList<ShowItem>() } }
        
        val resSp = spJob.await()
        val resLeg = legJob.await()
        
        val all = resSp + resLeg
        
        // If both failed/empty, and we have ANY cache (even stale), return it
        if (all.isEmpty() && cache != null) {
            return@coroutineScope cache
        }
        
        val result = all.distinctBy { (if (it.fromLegacy) "leg_" else "sp_") + it.id }
        if (result.isNotEmpty()) {
            cachedShows = result
            cachedShowsAt = now
        } else if (cache != null) {
            return@coroutineScope cache
        }
        
        result
    }

    private suspend fun getLegacyEpisodes(showId: Int): List<LegacyEpisode> {
        val txt = legacyGet("episodes?id=$showId") ?: return emptyList()
        val arr = try { JSONArray(txt) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val vid = o.optString("video_id").trim().lowercase()
            LegacyEpisode(
                id = o.optInt("id", -1),
                title = o.optString("title").ifBlank { "Episode ${o.optInt("order_id", i + 1)}" },
                durationSec = o.optInt("duration", 0).takeIf { it > 0 },
                orderId = o.optInt("order_id", i + 1).takeIf { it > 0 },
                thumbnail = o.optString("thumbnail").ifBlank { null }?.let { "https://cartoony.net/assets/img/thumbnails/$it" },
                videoId = if (vid == "null" || vid.isBlank()) null else vid
            )
        }.sortedBy { it.orderId ?: Int.MAX_VALUE }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        val qNorm = normalizeTitle(q)
        return getMergedShows()
            .filter { s -> normalizeTitle(listOf(s.title, s.plot ?: "", s.tags.joinToString(" ")).joinToString(" ")).contains(qNorm) }
            .map(::buildSearchFromShow)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        val sections = mutableListOf<HomePageList>()
        
        // Fetch shows and recent episodes in parallel
        val mergedJob = async { getMergedShows() }
        val recentJob = async { try { apiGet("recentEpisodes") } catch (e: Exception) { null } }
        
        val merged = mergedJob.await()
        val recentTxt = recentJob.await()
        
        val mergedBySpId = merged.filter { !it.fromLegacy }.associateBy { it.id }

        recentTxt?.let { txt ->
            val arr = try { JSONArray(txt) } catch (e: Exception) { null } ?: return@let
            val latest = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val showId = o.optInt("tv_series_id", -1)
                if (showId <= 0) continue
                val show = mergedBySpId[showId]
                val title = show?.title ?: o.optString("name").ifBlank { "غير معنون" }
                val poster = show?.poster ?: o.optString("cover_full_path")
                val url = if (show != null) getUrlForShow(show) else "$mainUrl/watch/sp/$showId"
                latest.add(newAnimeSearchResponse(title, url, if (show?.isMovie == true) TvType.Movie else TvType.TvSeries) { this.posterUrl = poster })
            }
            if (latest.isNotEmpty()) sections.add(HomePageList("Latest", latest.distinctBy { it.url }))
        }

        fun section(name: String, list: List<ShowItem>) {
            if (list.isNotEmpty()) sections.add(HomePageList(name, list.map(::buildSearchFromShow)))
        }

        section("New Uploads", merged.filter { it.isNew }.ifEmpty { merged.take(40) })
        section("Featured Movies", merged.filter { it.isMovie }.sortedByDescending { it.rating100 ?: 0 }.take(40))
        section("Trending TV shows", merged.filter { !it.isMovie && (it.isPopular || it.isHot) }.take(40))
        section("Anime", merged.filter { it.tags.any { t -> t.lowercase().contains("أنمي") || t.lowercase().contains("anime") } }.take(40))
        section("Most Watched", merged.filter { it.isPopular })
        section("Top Rated", merged.filter { it.isTop }.ifEmpty { merged.sortedByDescending { it.rating100 ?: 0 }.take(40) })
        section("Old Classics", merged.filter { it.isZaman })

        newHomePageResponse(sections)
    }

    override suspend fun load(url: String): LoadResponse? {
        val urlNoQuery = url.substringBefore("?")
        val watchParts = urlNoQuery.substringAfter("/watch/", "").split("/").filter { it.isNotBlank() }
        
        val isSp = watchParts.firstOrNull() == "sp"
        val showIdStr = if (isSp) watchParts.getOrNull(1) else watchParts.getOrNull(0)
        val episodeIdStr = if (isSp) watchParts.getOrNull(2) else watchParts.getOrNull(1)

        val id = showIdStr?.toIntOrNull() ?: return null
        val show = getMergedShows().firstOrNull { it.id == id && it.fromLegacy == !isSp }
        val isMovie = show?.isMovie == true || url.contains("type=movie") || url.contains("/movie/")

        // Direct episode deep-link
        if (episodeIdStr?.toIntOrNull() != null) {
            val epId = episodeIdStr.toInt()
            val data = if (isSp) "sp:e=$epId&s=$id" else "leg:e=$epId&s=$id"
            return newMovieLoadResponse(name = "Cartoony Episode", url = url, type = TvType.Anime, dataUrl = data)
        }

        if (isMovie) {
            if (isSp) {
                val spTxt = apiGet("episodes?id=$id") ?: return null
                val spArr = try { JSONArray(spTxt) } catch (e: Exception) { return null }
                val firstSp = spArr.optJSONObject(0) ?: return null
                val epId = firstSp.optInt("id", -1).takeIf { it > 0 } ?: return null
                val vid = firstSp.optString("video_id", "").takeIf { it.isNotBlank() && it.lowercase() != "null" }
                val data = "sp:v=${vid ?: ""}&e=$epId&s=$id"
                return newMovieLoadResponse(name = show?.title ?: "Cartoony Movie", url = url, type = TvType.Movie, dataUrl = data) {
                    this.posterUrl = show?.poster ?: firstSp.optString("cover_full_path")
                    this.plot = show?.plot; this.tags = show?.tags; this.rating = show?.rating100
                    this.duration = show?.durationMin; this.year = show?.year; this.contentRating = parseAgeRating(show?.minAge)
                }
            } else {
                val legEps = getLegacyEpisodes(id)
                if (legEps.isNotEmpty()) {
                    val first = legEps.first()
                    val data = "leg:v=${first.videoId ?: ""}&e=${first.id}&s=$id"
                    return newMovieLoadResponse(name = show?.title ?: first.title, url = url, type = TvType.Movie, dataUrl = data) {
                        this.posterUrl = show?.poster ?: first.thumbnail
                        this.plot = show?.plot; this.tags = show?.tags; this.rating = show?.rating100
                        this.duration = show?.durationMin ?: first.durationSec?.div(60)
                        this.year = show?.year; this.contentRating = parseAgeRating(show?.minAge)
                    }
                }
            }
        }

        // Series
        val episodes = mutableListOf<Episode>()
        if (isSp) {
            val spTxt = apiGet("episodes?id=$id")
            val spArr = spTxt?.let { try { JSONArray(it) } catch (e: Exception) { null } }
            spArr?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val epId = o.optInt("id", -1).takeIf { it > 0 } ?: continue
                    val epName = o.optString("name").ifBlank { o.optString("pref").ifBlank { "Episode ${o.optInt("number", i + 1)}" } }
                    val poster = o.optString("cover_full_path").ifBlank { o.optString("cover") }
                    val vid = o.optString("video_id", "").takeIf { it.isNotBlank() && it.lowercase() != "null" }
                    val data = "sp:v=${vid ?: ""}&e=$epId&s=$id"
                    episodes.add(newEpisode(data) {
                        this.name = epName
                        this.episode = o.optInt("number", i + 1)
                        this.posterUrl = poster.ifBlank { show?.poster }
                    })
                }
            }
        } else {
            val legEps = getLegacyEpisodes(id)
            legEps.forEachIndexed { i, ep ->
                val data = "leg:v=${ep.videoId ?: ""}&e=${ep.id}&s=$id"
                episodes.add(newEpisode(data) {
                    this.name = ep.title
                    this.episode = ep.orderId ?: (i + 1)
                    this.posterUrl = ep.thumbnail ?: show?.poster
                })
            }
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(show?.title ?: "Cartoony", url, TvType.TvSeries, episodes) {
            this.posterUrl = show?.poster
            this.plot = show?.plot; this.tags = show?.tags; this.rating = show?.rating100
            this.duration = show?.durationMin; this.year = show?.year; this.contentRating = parseAgeRating(show?.minAge)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Cartoony", "loadLinks: $data")
        val isSp = data.startsWith("sp:")
        val raw = data.substringAfter(":")

        // Robust key=value parsing  (format: sp:v=<vid>&e=<epId>&s=<showId>)
        val map = raw.split("&").associate {
            val p = it.split("=", limit = 2)
            p.getOrElse(0) { "" } to p.getOrElse(1) { "" }
        }

        val videoId   = map["v"]?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
        val episodeId = map["e"]?.takeIf { it.isNotBlank() && it.lowercase() != "null" }?.toIntOrNull()
        val showId    = map["s"]?.takeIf { it.isNotBlank() && it.lowercase() != "null" }?.toIntOrNull()

        Log.d("Cartoony", "parsed: isSp=$isSp ep=$episodeId show=$showId vid=$videoId")

        var linkFound = false

        // === STAGE 1: Unified encrypted API (works for both SP and Legacy) ===
        if (episodeId != null && showId != null) {
            val streamUrl = fetchEpisodeLink(episodeId, showId)
            if (streamUrl != null && streamUrl.startsWith("http")) {
                Log.d("Cartoony", "Stage1 link: $streamUrl")
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Stream",
                        url = streamUrl,
                        referer = "",
                        quality = Qualities.Unknown.value,
                        isM3u8 = streamUrl.contains(".m3u8")
                    )
                )
                linkFound = true
            }
        }

        // === STAGE 2: SP POST fallback ===
        if (!linkFound && isSp) {
            val spEpId = episodeId ?: return false
            for (base in listOf(mainUrl, watchDomain)) {
                val txt = spLink(spEpId, showId, base) ?: continue
                val obj = try { JSONObject(txt) } catch (e: Exception) { null } ?: continue
                val link = obj.optString("link", "").trim().takeIf { it.startsWith("http") }
                if (link != null) {
                    callback(ExtractorLink(name, "$name SP", link, "", Qualities.Unknown.value, link.contains(".m3u8")))
                    linkFound = true
                }
                val cdn = obj.optString("cdn_stream_private_id", "").trim().takeIf { it.isNotBlank() }
                if (cdn != null) {
                    callback(ExtractorLink(name, "$name CDN", "https://vod.spacetoongo.com/asset/$cdn/play_video/index.m3u8", "", Qualities.Unknown.value, true))
                    linkFound = true
                }
                if (linkFound) break
            }
        }

        // === STAGE 3: Pegasus fallback using known asset/video ID ===
        if (!linkFound && videoId != null && videoId.length > 20) {
            val pegUrl = "https://pegasus.5387692.xyz/api/hls/$videoId/playlist.m3u8"
            Log.d("Cartoony", "Stage3 Pegasus: $pegUrl")
            callback(ExtractorLink(name, "$name HLS", pegUrl, "", Qualities.Unknown.value, true))
            linkFound = true
        }

        if (!linkFound) Log.w("Cartoony", "No link found for: $data")
        return linkFound
    }
}
