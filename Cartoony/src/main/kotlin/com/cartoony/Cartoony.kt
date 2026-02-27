package com.cartoony

import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

import org.json.JSONArray
import org.json.JSONObject

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Cartoony : MainAPI() {
    override var mainUrl = "https://cartoony.net"
    override var name = "Cartoony"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries)

    private val apiBase = "$mainUrl/api/sp"
    private val apiKey = "7annaba3l_loves_crypto_safe_key!"

    private val reqHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "https://cartoony.net/",
        "Origin" to "https://cartoony.net",
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
            if (keyBytes.size != 32) {
                Log.w("Cartoony", "Unexpected API key length=${keyBytes.size}")
            }
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
        if (trimmed.startsWith("[")) return trimmed
        return try {
            val obj = JSONObject(trimmed)
            val encrypted = obj.optString("encryptedData")
            val iv = obj.optString("iv")
            if (encrypted.isBlank() || iv.isBlank()) {
                Log.w("Cartoony", "Missing encryptedData/iv in response")
                return null
            }
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
            val decrypted = decryptEnvelope(res.text)
            if (decrypted == null) {
                Log.w("Cartoony", "Decryption returned null for $path")
            }
            decrypted
        } catch (t: Throwable) {
            Log.e("Cartoony", "apiGetDecrypted failed for $path: ${t.message}", t)
            null
        }
    }

    private fun safeLower(text: String): String = text.lowercase()

    private fun buildShowResponse(obj: JSONObject): SearchResponse? {
        val id = obj.optInt("id", -1)
        if (id <= 0) return null

        val title = obj.optString("name").trim()
            .ifBlank { obj.optString("pref").trim() }
            .ifBlank { "غير معنون" }

        val poster = obj.optString("cover_full_path").ifBlank { obj.optString("cover") }
        val url = "$mainUrl/series/$id"

        return newAnimeSearchResponse(title, url) {
            this.posterUrl = poster
        }
    }

    private fun buildSection(items: List<SearchResponse>, name: String): HomePageList {
        return HomePageList(name, items)
    }

    private fun filterShowsByFlag(arr: JSONArray, flagKey: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optInt(flagKey, 0) != 1) continue
            buildShowResponse(obj)?.let { items.add(it) }
        }
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val decrypted = apiGetDecrypted("tvshows") ?: return emptyList()
        val arr = JSONArray(decrypted)

        val results = mutableListOf<SearchResponse>()
        val qLower = safeLower(q)

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val pref = obj.optString("pref").trim()
            val tags = obj.optString("tags").trim()
            val hay = safeLower(listOf(name, pref, tags).joinToString(" "))
            if (!hay.contains(qLower)) continue

            val id = obj.optInt("id", -1)
            if (id <= 0) continue

            val title = name.ifBlank { pref }.ifBlank { "غير معنون" }
            val poster = obj.optString("cover_full_path").ifBlank { obj.optString("cover") }
            val url = "$mainUrl/series/$id"

            results.add(
                newAnimeSearchResponse(title, url) {
                    this.posterUrl = poster
                }
            )
        }

        Log.d("Cartoony", "search '$query' results=${results.size}")
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = mutableListOf<HomePageList>()

        val recentDecrypted = apiGetDecrypted("recentEpisodes")
        if (recentDecrypted != null) {
            val recentArr = JSONArray(recentDecrypted)
            val recentItems = mutableListOf<SearchResponse>()
            for (i in 0 until recentArr.length()) {
                val obj = recentArr.optJSONObject(i) ?: continue
                val id = obj.optInt("id", -1)
                if (id <= 0) continue

                val title = obj.optString("name").trim()
                    .ifBlank { obj.optString("pref").trim() }
                    .ifBlank { obj.optString("slug").trim() }
                    .ifBlank { "غير معنون" }

                val poster = obj.optString("cover_full_path").ifBlank { obj.optString("cover") }
                val url = "$mainUrl/watch/$id"

                recentItems.add(
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = poster
                    }
                )
            }
            if (recentItems.isNotEmpty()) {
                sections.add(buildSection(recentItems, "Latest"))
            }
        } else {
            Log.w("Cartoony", "Main page empty: decrypted recentEpisodes null")
        }

        val showsDecrypted = apiGetDecrypted("tvshows")
        if (showsDecrypted != null) {
            val showsArr = JSONArray(showsDecrypted)

            val newItems = filterShowsByFlag(showsArr, "is_new")
            if (newItems.isNotEmpty()) sections.add(buildSection(newItems, "New Uploads"))

            val popularItems = filterShowsByFlag(showsArr, "is_popular")
            if (popularItems.isNotEmpty()) sections.add(buildSection(popularItems, "Most Watched"))

            val topItems = filterShowsByFlag(showsArr, "is_top")
            if (topItems.isNotEmpty()) sections.add(buildSection(topItems, "Top Rated"))

            val hotItems = filterShowsByFlag(showsArr, "is_hot")
            if (hotItems.isNotEmpty()) sections.add(buildSection(hotItems, "Hot"))

            val zamanItems = filterShowsByFlag(showsArr, "is_zaman")
            if (zamanItems.isNotEmpty()) sections.add(buildSection(zamanItems, "Old Classics"))
        } else {
            Log.w("Cartoony", "Main page empty: decrypted tvshows null")
        }

        return newHomePageResponse(sections)
    }

    override suspend fun load(url: String): LoadResponse? {
        val episodeId = url.substringAfterLast("/").toIntOrNull()
        if (episodeId != null && url.contains("/watch/")) {
            return newMovieLoadResponse(
                name = "Cartoony Episode",
                url = url,
                dataUrl = episodeId.toString(),
                type = TvType.Anime
            )
        }

        if (url.contains("/series/")) {
            val seriesId = url.substringAfterLast("/").toIntOrNull()
            if (seriesId != null) {
                return newMovieLoadResponse(
                    name = "Cartoony Series",
                    url = url,
                    dataUrl = "series:$seriesId",
                    type = TvType.TvSeries
                )
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var episodeId = data.toIntOrNull()

        if (episodeId == null && data.startsWith("series:")) {
            val seriesId = data.removePrefix("series:").toIntOrNull()
            if (seriesId != null) {
                val recentDecrypted = apiGetDecrypted("recentEpisodes")
                if (recentDecrypted != null) {
                    val recentArr = JSONArray(recentDecrypted)
                    for (i in 0 until recentArr.length()) {
                        val obj = recentArr.optJSONObject(i) ?: continue
                        if (obj.optInt("tv_series_id", -1) == seriesId) {
                            val candidate = obj.optInt("id", -1)
                            if (candidate > 0) {
                                episodeId = candidate
                                break
                            }
                        }
                    }
                }
            }
        }

        episodeId ?: return false

        val endpoint = "$apiBase/episode/link"
        val res = app.post(
            url = endpoint,
            headers = reqHeaders + mapOf("Content-Type" to "application/json"),
            data = mapOf("episodeId" to episodeId.toString())
        )

        val decrypted = decryptEnvelope(res.text) ?: return false
        val obj = JSONObject(decrypted)
        val link = obj.optString("link").trim()
        if (link.isBlank()) return false

        @Suppress("DEPRECATION")
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = link,
                referer = mainUrl,
                quality = Qualities.Unknown.value
            )
        )
        return true
    }
}
