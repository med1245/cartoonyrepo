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

    private val apiBase = "$mainUrl/api/sp"
    private val apiKey = "7annaba3l_loves_crypto_safe_key!"

    private val reqHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        // Use watch domain for referer/origin as playback endpoints are tied to it
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

    @Volatile
    private var cachedShows: List<ShowItem>? = null

    @Volatile
    private var cachedShowsAt: Long = 0L
    private val showCacheTtlMs = 5 * 60 * 1000L

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", "،")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
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
            .replace('|', ' ')
            .replace('-', ' ')
            .replace('–', ' ')
            .replace("  ", " ")
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
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(hexToBytes(ivHex))
            )
            val plain = cipher.doFinal(hexToBytes(encryptedHex))
            String(plain, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e("Cartoony", "decryptPayload failed: ${t.message}", t)
            null
        }
    }

    private fun decryptEnvelope(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{\"streamUrl\"")) return trimmed
        return try {
            val obj = JSONObject(trimmed)
            val encrypted = obj.optString("encryptedData")
            val iv = obj.optString("iv")
            if (encrypted.isBlank() || iv.isBlank()) return null
            decryptPayload(encrypted, iv)
        } catch (t: Throwable) {
            Log.e("Cartoony", "decryptEnvelope failed: ${t.message}", t)
            null
        }
    }

    private suspend fun apiGetDecrypted(path: String): String? {
        val url = "$apiBase/$path"
        return try {
            val res = app.get(url, headers = reqHeaders)
            decryptEnvelope(res.text)
        } catch (t: Throwable) {
            Log.e("Cartoony", "apiGetDecrypted failed for $path: ${t.message}", t)
            null
        }
    }

    private suspend fun apiLegacyGetDecrypted(path: String): String? {
        val url = "$mainUrl/api/$path"
        return try {
            val res = app.get(url, headers = reqHeaders)
            decryptEnvelope(res.text)
        } catch (t: Throwable) {
            Log.e("Cartoony", "apiLegacyGetDecrypted failed for $path: ${t.message}", t)
            null
        }
    }

    private fun buildSearchFromShow(show: ShowItem): SearchResponse {
        val url = if (show.isMovie) "$mainUrl/movie/${show.id}" else "$mainUrl/series/${show.id}"
        return newAnimeSearchResponse(show.title, url, if (show.isMovie) TvType.Movie else TvType.TvSeries) {
            this.posterUrl = show.poster
        }
    }

    private fun mapSpShow(obj: JSONObject): ShowItem? {
        val id = obj.optInt("id", -1)
        if (id <= 0) return null
        val title = obj.optString("name").trim()
            .ifBlank { obj.optString("pref").trim() }
            .ifBlank { "غير معنون" }
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
            minAge = obj.optInt("min_age", 0).takeIf { it > 0 },
            fromLegacy = false
        )
    }

    private fun mapLegacyShow(obj: JSONObject): ShowItem? {
        val id = obj.optInt("id", -1)
        if (id <= 0) return null
        val title = obj.optString("title").trim().ifBlank { "غير معنون" }
        val category = obj.optString("category").lowercase()
        val episodesCount = obj.optString("episodes_count")
        val isMovie = category.contains("فيلم") ||
                (!episodesCount.contains("حلقة") && episodesCount.toIntOrNull() == 1)

        val durationStr = obj.optString("episodes_length")
        val durationMin = Regex("(\\d+)").find(durationStr)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return ShowItem(
            id = id,
            title = title,
            plot = obj.optString("description").ifBlank { null },
            poster = obj.optString("poster_cover").ifBlank { null }
                ?.let { "https://cartoony.net/assets/img/posters/$it" },
            isMovie = isMovie,
            tags = parseTags(obj.optString("category")),
            rating100 = parseRating100(obj.optDouble("rating", 0.0)),
            durationMin = durationMin,
            year = obj.optString("release_year").toIntOrNull(),
            minAge = null,
            fromLegacy = true
        )
    }

    private suspend fun getSpShows(): List<ShowItem> {
        val txt = apiGetDecrypted("tvshows") ?: return emptyList()
        val arr = JSONArray(txt)
        val out = mutableListOf<ShowItem>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { mapSpShow(it) }?.let(out::add)
        }
        return out
    }

    private suspend fun getLegacyShows(): List<ShowItem> {
        val txt = apiLegacyGetDecrypted("tvshows") ?: return emptyList()
        val arr = JSONArray(txt)
        val out = mutableListOf<ShowItem>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { mapLegacyShow(it) }?.let(out::add)
        }
        return out
    }

    private suspend fun getMergedShows(): List<ShowItem> {
        val now = System.currentTimeMillis()
        val cached = cachedShows
        if (cached != null && (now - cachedShowsAt) < showCacheTtlMs) {
            return cached
        }

        // Fast path: load SP first and return immediately if legacy fails/slow later in call chain
        val sp = getSpShows()
        val legacy = getLegacyShows()

        val merged = LinkedHashMap<Int, ShowItem>()
        for (s in sp) merged[s.id] = s

        for (l in legacy) {
            val current = merged[l.id]
            if (current == null) {
                merged[l.id] = l
            } else {
                merged[l.id] = current.copy(
                    title = if (current.title.isBlank() || current.title == "غير معنون") l.title else current.title,
                    plot = current.plot ?: l.plot,
                    poster = current.poster ?: l.poster,
                    isMovie = current.isMovie || l.isMovie,
                    tags = (current.tags + l.tags).distinct(),
                    rating100 = current.rating100 ?: l.rating100,
                    durationMin = current.durationMin ?: l.durationMin,
                    year = current.year ?: l.year,
                    minAge = current.minAge ?: l.minAge,
                    fromLegacy = current.fromLegacy || l.fromLegacy
                )
            }
        }

        val result = merged.values.toList()
        cachedShows = result
        cachedShowsAt = now
        return result
    }

    private suspend fun getLegacyEpisodes(showId: Int): List<LegacyEpisode> {
        val txt = apiLegacyGetDecrypted("episodes?id=$showId") ?: return emptyList()
        val arr = JSONArray(txt)
        val out = mutableListOf<LegacyEpisode>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                LegacyEpisode(
                    id = o.optInt("id", -1),
                    title = o.optString("title").ifBlank { "Episode ${o.optInt("order_id", i + 1)}" },
                    durationSec = o.optInt("duration", 0).takeIf { it > 0 },
                    orderId = o.optInt("order_id", i + 1).takeIf { it > 0 },
                    thumbnail = o.optString("thumbnail").ifBlank { null }
                        ?.let { "https://cartoony.net/assets/img/thumbnails/$it" },
                    videoId = o.optString("video_id").ifBlank { null }
                )
            )
        }
        return out.sortedBy { it.orderId ?: Int.MAX_VALUE }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val qNorm = normalizeTitle(q)
        val merged = getMergedShows()

        val results = merged.filter { s ->
            val hay = normalizeTitle(
                listOf(s.title, s.plot ?: "", s.tags.joinToString(" ")).joinToString(" ")
            )
            hay.contains(qNorm)
        }

        return results
            .distinctBy { it.id }
            .map(::buildSearchFromShow)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()
        val merged = getMergedShows()
        val mergedById = merged.associateBy { it.id }

        val recentTxt = apiGetDecrypted("recentEpisodes")
        if (recentTxt != null) {
            val arr = JSONArray(recentTxt)
            val latest = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val showId = o.optInt("tv_series_id", -1)
                val epId = o.optInt("id", -1)
                if (showId <= 0 || epId <= 0) continue

                val show = mergedById[showId]
                val title = show?.title ?: o.optString("name").ifBlank { o.optString("pref").ifBlank { "غير معنون" } }
                val poster = show?.poster ?: o.optString("cover_full_path").ifBlank { o.optString("cover") }
                val url = if (show?.isMovie == true) "$mainUrl/movie/$showId" else "$mainUrl/series/$showId"

                latest.add(
                    newAnimeSearchResponse(title, url, if (show?.isMovie == true) TvType.Movie else TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            }
            if (latest.isNotEmpty()) sections.add(HomePageList("Latest", latest.distinctBy { it.url }))
        }

        fun section(name: String, list: List<ShowItem>) {
            if (list.isNotEmpty()) sections.add(
                HomePageList(
                    name,
                    list.distinctBy { it.id }.map(::buildSearchFromShow)
                )
            )
        }

        section("New Uploads", merged.filter { it.isNew }.ifEmpty { merged.take(60) })
        section("Most Watched", merged.filter { it.isPopular })
        section(
            "Top Rated",
            merged.filter { it.isTop }.ifEmpty { merged.sortedByDescending { it.rating100 ?: 0 }.take(60) })
        section("Hot", merged.filter { it.isHot })
        section("Old Classics", merged.filter { it.isZaman })

        return newHomePageResponse(sections)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        val show = getMergedShows().firstOrNull { it.id == id }

        // Direct episode deep-link
        if (url.contains("/watch/")) {
            return newMovieLoadResponse(
                name = "Cartoony Episode",
                url = url,
                dataUrl = "spEpisode:$id",
                type = TvType.Anime
            )
        }

        // Movie path
        if (url.contains("/movie/") || show?.isMovie == true) {
            // legacy first for full catalog, fallback to sp
            val legacyEpisodes = getLegacyEpisodes(id)
            if (legacyEpisodes.isNotEmpty()) {
                val first = legacyEpisodes.first()
                return newMovieLoadResponse(
                    name = show?.title ?: first.title,
                    url = url,
                    dataUrl = "legacyVideo:${first.videoId ?: ""}|${first.id}",
                    type = TvType.Movie
                ) {
                    this.posterUrl = show?.poster ?: first.thumbnail
                    this.plot = show?.plot
                    this.tags = show?.tags
                    this.rating = show?.rating100
                    this.duration = show?.durationMin ?: first.durationSec?.div(60)
                    this.year = show?.year
                    this.contentRating = parseAgeRating(show?.minAge)
                }
            }

            // SP fallback
            val spEpisodesTxt = apiGetDecrypted("episodes?id=$id") ?: return null
            val spEpisodesArr = JSONArray(spEpisodesTxt)
            val firstSp = spEpisodesArr.optJSONObject(0) ?: return null
            val epId = firstSp.optInt("id", -1)
            if (epId <= 0) return null

            return newMovieLoadResponse(
                name = show?.title ?: firstSp.optString("name")
                    .ifBlank { firstSp.optString("pref").ifBlank { "Cartoony Movie" } },
                url = url,
                dataUrl = "spEpisode:$epId",
                type = TvType.Movie
            ) {
                this.posterUrl =
                    show?.poster ?: firstSp.optString("cover_full_path").ifBlank { firstSp.optString("cover") }
                this.plot = show?.plot ?: firstSp.optString("pref").ifBlank { firstSp.optString("name") }
                this.tags = show?.tags
                this.rating = show?.rating100
                this.duration = show?.durationMin ?: firstSp.optInt("duration", 0).takeIf { it > 0 }?.div(60)
                this.year = show?.year
                this.contentRating = parseAgeRating(show?.minAge)
            }
        }

        // Series path
        if (url.contains("/series/")) {
            val legacyEpisodes = getLegacyEpisodes(id)
            if (legacyEpisodes.isNotEmpty()) {
                val eps = legacyEpisodes.mapIndexed { idx, ep ->
                    newEpisode("legacyVideo:${ep.videoId ?: ""}|${ep.id}") {
                        this.name = ep.title
                        this.episode = ep.orderId ?: (idx + 1)
                        this.posterUrl = ep.thumbnail ?: show?.poster
                    }
                }
                return newTvSeriesLoadResponse(
                    name = show?.title ?: "Cartoony Series",
                    url = url,
                    type = TvType.TvSeries,
                    episodes = eps
                ) {
                    this.posterUrl = show?.poster
                    this.plot = show?.plot
                    this.tags = show?.tags
                    this.rating = show?.rating100
                    this.duration = show?.durationMin
                    this.year = show?.year
                    this.contentRating = parseAgeRating(show?.minAge)
                }
            }

            // SP fallback
            val spEpisodesTxt = apiGetDecrypted("episodes?id=$id") ?: return null
            val spEpisodesArr = JSONArray(spEpisodesTxt)
            val eps = mutableListOf<Episode>()
            for (i in 0 until spEpisodesArr.length()) {
                val epObj = spEpisodesArr.optJSONObject(i) ?: continue
                val epId = epObj.optInt("id", -1)
                if (epId <= 0) continue
                val epName = epObj.optString("name").ifBlank { epObj.optString("pref") }
                    .ifBlank { "Episode ${epObj.optInt("number", i + 1)}" }
                val epNum = epObj.optInt("number", i + 1)
                eps.add(
                    newEpisode("spEpisode:$epId") {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = epObj.optString("cover_full_path").ifBlank { epObj.optString("cover") }
                    }
                )
            }

            return newTvSeriesLoadResponse(
                name = show?.title ?: "Cartoony Series",
                url = url,
                type = TvType.TvSeries,
                episodes = eps
            ) {
                this.posterUrl = show?.poster
                this.plot = show?.plot
                this.tags = show?.tags
                this.rating = show?.rating100
                this.duration = show?.durationMin
                this.year = show?.year
                this.contentRating = parseAgeRating(show?.minAge)
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Legacy playback by video_id
        if (data.startsWith("legacyVideo:")) {
            val payload = data.removePrefix("legacyVideo:")
            val videoId = payload.substringBefore("|")
            if (videoId.isNotBlank()) {
                val episodeIdPart = payload.substringAfter("|", "")
                if (episodeIdPart.isNotBlank()) {
                    val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart")
                    if (legacyTxt != null) {
                        val obj = JSONObject(legacyTxt)
                        val streamUrl = obj.optString("streamUrl").trim()
                        if (streamUrl.startsWith("http")) {
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name Legacy",
                                    url = streamUrl,
                                    referer = "https://cartoony.net/",
                                    quality = Qualities.Unknown.value
                                )
                            )
                            return true
                        }
                    }
                }

                // direct by video id fallback
                val direct = "https://pegasus.5387692.xyz/api/hls/$videoId/playlist.m3u8"
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Legacy",
                        url = direct,
                        referer = "https://cartoony.net/",
                        quality = Qualities.Unknown.value
                    )
                )
                return true
            }
        }

        // SP playback
        val epId = when {
            data.startsWith("spEpisode:") -> data.removePrefix("spEpisode:").toIntOrNull()
            data.startsWith("episode:") -> data.removePrefix("episode:").toIntOrNull()
            else -> data.toIntOrNull()
        } ?: return false

        // Strategy 1: SP endpoint
        runCatching {
            // Try JSON first
            runCatching {
                app.post(
                    url = "$apiBase/episode/link",
                    headers = reqHeaders + mapOf("Content-Type" to "application/json"),
                    data = """{"episodeId":$epId}"""
                )
            }.getOrNull() ?: app.post(
                url = "$apiBase/episode/link",
                headers = reqHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("episodeId" to epId.toString())
            )
        }.getOrNull()?.let { res ->
            val decrypted = decryptEnvelope(res.text)
            if (!decrypted.isNullOrBlank()) {
                val obj = JSONObject(decrypted)

                val link = obj.optString("link").trim()
                if (link.isNotBlank()) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            referer = "$watchDomain/",
                            quality = Qualities.Unknown.value
                        )
                    )
                    return true
                }

                val cdnPrivate = obj.optString("cdn_stream_private_id").trim()
                if (cdnPrivate.isNotBlank()) {
                    val fallback = "https://vod.spacetoongo.com/asset/$cdnPrivate/play_video/index.m3u8"
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name Fallback",
                            url = fallback,
                            referer = "$watchDomain/",
                            quality = Qualities.Unknown.value
                        )
                    )
                    return true
                }
            }
        }

        // Strategy 2: legacy endpoint by numeric episode id
        val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$epId")
        if (!legacyTxt.isNullOrBlank()) {
            val obj = JSONObject(legacyTxt)
            val streamUrl = obj.optString("streamUrl").trim()
            if (streamUrl.startsWith("http")) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Legacy",
                        url = streamUrl,
                        referer = "$watchDomain/",
                        quality = Qualities.Unknown.value
                    )
                )
                return true
            }
        }

        // Strategy 3: direct HLS pattern fallback
        val direct = "https://pegasus.5387692.xyz/api/hls/$epId/playlist.m3u8"
        callback(
            ExtractorLink(
                source = name,
                name = "$name Direct",
                url = direct,
                referer = "$watchDomain/",
                quality = Qualities.Unknown.value
            )
        )
        return true
    }
}
