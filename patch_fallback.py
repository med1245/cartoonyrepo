import re

path = r'C:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\Cartoony\src\main\kotlin\com\cartoony\Cartoony.kt'

with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

old_api_methods = '''    private suspend fun apiGetDecrypted(path: String): String? {
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
    }'''

new_api_methods = '''    private suspend fun fetchApiWithFallback(path: String, isLegacy: Boolean): String? {
        val bases = listOf(mainUrl, watchDomain)
        for (base in bases) {
            val endpoint = if (isLegacy) "$base/api/$path" else "$base/api/sp/$path"
            try {
                val overrides = reqHeaders.toMutableMap()
                overrides["Origin"] = base
                overrides["Referer"] = "$base/"
                
                val res = app.get(endpoint, headers = overrides)
                val decrypted = decryptEnvelope(res.text)
                if (!decrypted.isNullOrBlank()) {
                    return decrypted
                }
            } catch (t: Throwable) {
                Log.e("Cartoony", "API fallback $endpoint failed: ${t.message}")
            }
        }
        return null
    }

    private suspend fun apiGetDecrypted(path: String): String? {
        return fetchApiWithFallback(path, isLegacy = false)
    }

    private suspend fun apiLegacyGetDecrypted(path: String): String? {
        return fetchApiWithFallback(path, isLegacy = true)
    }'''

code = code.replace(old_api_methods, new_api_methods)

old_post_block = '''        // Strategy 1: SP endpoint
        runCatching {
            app.post(
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
                            quality = Qualities.Unknown.value,
                            isM3u8 = link.contains(".m3u8") || link.contains("/hls/") || link.contains("playlist")
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
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }
        }'''

new_post_block = '''        // Strategy 1: SP endpoint
        val bases = listOf(mainUrl, watchDomain)
        for (base in bases) {
            runCatching {
                val overrides = reqHeaders.toMutableMap()
                overrides["Origin"] = base
                overrides["Referer"] = "$base/"
                
                app.post(
                    url = "$base/api/sp/episode/link",
                    headers = overrides + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("episodeId" to epId.toString())
                )
            }.getOrNull()?.let { res ->
                val decrypted = decryptEnvelope(res.text)
                if (!decrypted.isNullOrBlank()) {
                    val obj = try { JSONObject(decrypted) } catch (e: Exception) { null }
                    if (obj != null) {
                        val link = obj.optString("link").trim()
                        if (link.isNotBlank()) {
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = name,
                                    url = link,
                                    referer = "$base/",
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = link.contains(".m3u8") || link.contains("/hls/") || link.contains("playlist")
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
                                    referer = "$base/",
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = true
                                )
                            )
                            return true
                        }
                    }
                }
            }
        }'''

code = code.replace(old_post_block, new_post_block)

# Fix possible JSON Object exception in Strategy 2 Legacy playback
old_strat_2 = '''                    val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart")
                    if (legacyTxt != null) {
                        val obj = JSONObject(legacyTxt)
                        val streamUrl = obj.optString("streamUrl").trim()'''

new_strat_2 = '''                    val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart")
                    if (legacyTxt != null) {
                        val obj = try { JSONObject(legacyTxt) } catch (e: Exception) { null }
                        if (obj != null) {
                            val streamUrl = obj.optString("streamUrl").trim()'''

code = code.replace(old_strat_2, new_strat_2)

# One more closing brace for the added if
old_strat_2_end = '''                                )
                            )
                            return true
                        }
                    }
                }
                
                // direct by video id fallback'''
new_strat_2_end = '''                                )
                            )
                            return true
                        }
                        }
                    }
                }
                
                // direct by video id fallback'''

code = code.replace(old_strat_2_end, new_strat_2_end)


old_strat_2_loose = '''        // Strategy 2: legacy endpoint by numeric episode id
        val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$epId")
        if (!legacyTxt.isNullOrBlank()) {
            val obj = JSONObject(legacyTxt)
            val streamUrl = obj.optString("streamUrl").trim()'''

new_strat_2_loose = '''        // Strategy 2: legacy endpoint by numeric episode id
        val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$epId")
        if (!legacyTxt.isNullOrBlank()) {
            val obj = try { JSONObject(legacyTxt) } catch (e: Exception) { null }
            if (obj != null) {
                val streamUrl = obj.optString("streamUrl").trim()'''
code = code.replace(old_strat_2_loose, new_strat_2_loose)

old_strat_2_loose_end = '''                )
                return true
            }
        }

        // Strategy 3: direct HLS pattern fallback'''

new_strat_2_loose_end = '''                )
                return true
            }
            }
        }

        // Strategy 3: direct HLS pattern fallback'''

code = code.replace(old_strat_2_loose_end, new_strat_2_loose_end)

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)

print("Patch applied fully.")
