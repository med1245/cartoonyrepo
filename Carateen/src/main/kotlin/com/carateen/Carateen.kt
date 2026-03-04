@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.carateen

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Carateen : MainAPI() {
    override var mainUrl = "https://carateen.tv"
    override var name = "Carateen"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    // Same encryption key as the cartoony.net/carateen.tv backend
    private val apiKey = "7annaba3l_loves_crypto_safe_key!"

    private fun buildHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json"
    )

    // ── Data classes ──────────────────────────────────────────────────────────

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
        val minAge: Int? = null
    )

    // ── Cache ─────────────────────────────────────────────────────────────────

    @Volatile private var cachedShows: List<ShowItem>? = null
    @Volatile private var cachedShowsAt: Long = 0L
    private val showCacheTtlMs = 5 * 60 * 1000L

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", "،", "\t").map { it.trim() }.filter { it.isNotBlank() }.distinct()
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

    // ── Decryption ────────────────────────────────────────────────────────────

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
            Log.e("Carateen", "decryptPayload failed: ${t.message}")
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
            Log.e("Carateen", "decryptEnvelope failed: ${t.message}")
            null
        }
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private suspend fun get(url: String): String? = withTimeoutOrNull(12000) {
        try {
            decryptEnvelope(app.get(url, headers = buildHeaders()).text)
        } catch (t: Throwable) {
            Log.e("Carateen", "GET $url failed: ${t.message}")
            null
        }
    }

    private suspend fun apiGet(path: String): String? =
        get("$mainUrl/api/sp/$path")

    private suspend fun spLink(epId: Int, showId: Int?): String? = withTimeoutOrNull(12000) {
        try {
            val body = mutableMapOf("episodeId" to epId.toString())
            if (showId != null) body["showId"] = showId.toString()
            decryptEnvelope(app.post("$mainUrl/api/sp/episode/link", headers = buildHeaders(), data = body).text)
        } catch (t: Throwable) {
            Log.e("Carateen", "POST spLink($epId) failed: ${t.message}")
            null
        }
    }

    // ── Show mapping ──────────────────────────────────────────────────────────

    private fun mapShow(obj: JSONObject): ShowItem? {
        val id = obj.optInt("id", -1)
        if (id <= 0) return null
        val title = obj.optString("name").trim().ifBlank { obj.optString("pref").trim().ifBlank { "غير معنون" } }
        return ShowItem(
            id = id,
            title = title,
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
            minAge = obj.optInt("min_age", 0).takeIf { it > 0 }
        )
    }

    private suspend fun getShows(): List<ShowItem> {
        val txt = apiGet("tvshows") ?: return emptyList()
        val arr = try { JSONArray(txt) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> mapShow(o) } }
    }

    private suspend fun getCachedShows(): List<ShowItem> {
        val now = System.currentTimeMillis()
        val cache = cachedShows
        if (cache != null && (now - cachedShowsAt) < showCacheTtlMs) return cache
        val result = getShows()
        if (result.isNotEmpty()) {
            cachedShows = result
            cachedShowsAt = now
        }
        return result.ifEmpty { cache ?: emptyList() }
    }

    private fun getUrlForShow(show: ShowItem): String {
        val base = "$mainUrl/watch/sp/${show.id}"
        return if (show.isMovie) "$base?type=movie" else "$base?type=series"
    }

    private fun buildSearchFromShow(show: ShowItem): SearchResponse {
        return newAnimeSearchResponse(show.title, getUrlForShow(show), if (show.isMovie) TvType.Movie else TvType.TvSeries) {
            this.posterUrl = show.poster
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        val qNorm = normalizeTitle(q)
        return getCachedShows()
            .filter { s -> normalizeTitle(listOf(s.title, s.plot ?: "", s.tags.joinToString(" ")).joinToString(" ")).contains(qNorm) }
            .map(::buildSearchFromShow)
    }

    // ── Main Page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()
        val shows = getCachedShows()

        // Latest episodes section
        apiGet("recentEpisodes")?.let { txt ->
            val arr = try { JSONArray(txt) } catch (e: Exception) { null } ?: return@let
            val showsById = shows.associateBy { it.id }
            val latest = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val showId = o.optInt("tv_series_id", -1)
                if (showId <= 0) continue
                val show = showsById[showId]
                val title = show?.title ?: o.optString("name").ifBlank { "غير معنون" }
                val poster = show?.poster ?: o.optString("cover_full_path")
                val url = show?.let { getUrlForShow(it) } ?: "$mainUrl/watch/sp/$showId"
                latest.add(newAnimeSearchResponse(title, url, if (show?.isMovie == true) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
            if (latest.isNotEmpty()) sections.add(HomePageList("Latest", latest.distinctBy { it.url }))
        }

        fun section(sectionName: String, list: List<ShowItem>) {
            if (list.isNotEmpty()) sections.add(HomePageList(sectionName, list.map(::buildSearchFromShow)))
        }

        section("New Uploads", shows.filter { it.isNew }.ifEmpty { shows.take(40) })
        section("Featured Movies", shows.filter { it.isMovie }.sortedByDescending { it.rating100 ?: 0 }.take(40))
        section("Trending Shows", shows.filter { !it.isMovie && (it.isPopular || it.isHot) }.take(40))
        section("Anime", shows.filter { it.tags.any { t -> t.contains("أنمي") || t.lowercase().contains("anime") } }.take(40))
        section("Most Watched", shows.filter { it.isPopular })
        section("Top Rated", shows.filter { it.isTop }.ifEmpty { shows.sortedByDescending { it.rating100 ?: 0 }.take(40) })
        section("Old Classics", shows.filter { it.isZaman })

        return newHomePageResponse(sections)
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val urlNoQuery = url.substringBefore("?")
        val watchParts = urlNoQuery.substringAfter("/watch/", "").split("/").filter { it.isNotBlank() }

        // Expect /watch/sp/<showId> or /watch/sp/<showId>/<epId>
        val isSp = watchParts.firstOrNull() == "sp"
        val showIdStr = if (isSp) watchParts.getOrNull(1) else watchParts.getOrNull(0)
        val episodeIdStr = if (isSp) watchParts.getOrNull(2) else null

        val id = showIdStr?.toIntOrNull() ?: return null
        val show = getCachedShows().firstOrNull { it.id == id }
        val isMovie = show?.isMovie == true || url.contains("type=movie")

        // Direct episode deep-link (e.g. /watch/sp/445/1234)
        if (episodeIdStr?.toIntOrNull() != null) {
            val epId = episodeIdStr.toInt()
            return newMovieLoadResponse(
                name = show?.title ?: "Carateen Episode",
                url = url,
                type = TvType.Anime,
                dataUrl = "sp:e=$epId&s=$id"
            )
        }

        if (isMovie) {
            val spTxt = apiGet("episodes?id=$id") ?: return null
            val spArr = try { JSONArray(spTxt) } catch (e: Exception) { return null }
            val firstSp = spArr.optJSONObject(0) ?: return null
            val epId = firstSp.optInt("id", -1).takeIf { it > 0 } ?: return null
            val vid = firstSp.optString("video_id", "").takeIf { it.isNotBlank() && it.lowercase() != "null" }
            return newMovieLoadResponse(
                name = show?.title ?: "Carateen Movie",
                url = url,
                type = TvType.Movie,
                dataUrl = "sp:v=${vid ?: ""}&e=$epId&s=$id"
            ) {
                this.posterUrl = show?.poster ?: firstSp.optString("cover_full_path")
                this.plot = show?.plot
                this.tags = show?.tags
                this.rating = show?.rating100
                this.duration = show?.durationMin
                this.year = show?.year
                this.contentRating = parseAgeRating(show?.minAge)
            }
        }

        // Series
        val spTxt = apiGet("episodes?id=$id") ?: return null
        val spArr = try { JSONArray(spTxt) } catch (e: Exception) { return null }

        data class SpEp(val epId: Int, val epName: String, val number: Int, val poster: String?, val vid: String?)
        val spEps = (0 until spArr.length()).mapNotNull { i ->
            val o = spArr.optJSONObject(i) ?: return@mapNotNull null
            val epId = o.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val epName = o.optString("pref").trim().ifBlank {
                o.optString("name").trim().ifBlank { "Episode ${o.optInt("number", i + 1)}" }
            }
            val poster = o.optString("cover_full_path").ifBlank { o.optString("cover").ifBlank { null } }
            val vid = o.optString("video_id", "").takeIf { it.isNotBlank() && it.lowercase() != "null" }
            SpEp(epId, epName, o.optInt("number", i + 1), poster, vid)
        }.sortedBy { it.number }

        if (spEps.isEmpty()) return null

        val episodes = spEps.map { ep ->
            newEpisode("sp:v=${ep.vid ?: ""}&e=${ep.epId}&s=$id") {
                this.name = ep.epName
                this.episode = ep.number
                this.posterUrl = ep.poster ?: show?.poster
            }
        }

        return newTvSeriesLoadResponse(show?.title ?: "Carateen", url, TvType.TvSeries, episodes) {
            this.posterUrl = show?.poster
            this.plot = show?.plot
            this.tags = show?.tags
            this.rating = show?.rating100
            this.duration = show?.durationMin
            this.year = show?.year
            this.contentRating = parseAgeRating(show?.minAge)
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Carateen", "loadLinks: $data")

        // All links are SP-based: sp:v=<vid>&e=<epId>&s=<showId>
        val raw = data.substringAfter(":")
        val map = raw.split("&").associate {
            val p = it.split("=", limit = 2)
            p.getOrElse(0) { "" } to p.getOrElse(1) { "" }
        }

        val videoId   = map["v"]?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
        val episodeId = map["e"]?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val showId    = map["s"]?.takeIf { it.isNotBlank() }?.toIntOrNull()

        Log.d("Carateen", "parsed: ep=$episodeId show=$showId vid=$videoId")

        var linkFound = false

        // Strategy 1: POST /api/sp/episode/link → get HLS link or CDN asset
        if (episodeId != null) {
            var txt: String? = null
            for (attempt in 1..3) {
                txt = spLink(episodeId, showId)
                if (txt != null) break
                if (attempt < 3) delay(700)
            }
            val obj = txt?.let { try { JSONObject(it) } catch (e: Exception) { null } }
            if (obj != null) {
                val link = obj.optString("link", "").trim().takeIf { it.startsWith("http") }
                if (link != null) {
                    Log.d("Carateen", "SP link: $link")
                    callback(ExtractorLink(name, "$name SP", link, "", Qualities.Unknown.value, link.contains(".m3u8")))
                    linkFound = true
                }
                val cdn = obj.optString("cdn_stream_private_id", "").trim()
                    .takeIf { it.isNotBlank() && it.lowercase() != "null" }
                if (cdn != null) {
                    val cdnUrl = "https://vod.spacetoongo.com/asset/$cdn/play_video/index.m3u8"
                    Log.d("Carateen", "CDN: $cdnUrl")
                    callback(ExtractorLink(name, "$name VOD", cdnUrl, "", Qualities.Unknown.value, true))
                    linkFound = true
                }
            }
        }

        // Strategy 2: Pegasus HLS fallback (older episodes with a stored video_id hash)
        if (!linkFound && videoId != null && videoId.length > 20) {
            val pegUrl = "https://pegasus.5387692.xyz/api/hls/$videoId/playlist.m3u8"
            Log.d("Carateen", "Pegasus fallback: $pegUrl")
            callback(ExtractorLink(name, "$name HLS", pegUrl, "", Qualities.Unknown.value, true))
            linkFound = true
        }

        if (!linkFound) Log.w("Carateen", "No link found for: $data")
        return linkFound
    }
}
