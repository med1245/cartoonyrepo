import re

path = r'C:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\Cartoony\src\main\kotlin\com\cartoony\Cartoony.kt'

with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Fix URL Generation
new_url_func = '''    private fun getUrlForShow(show: ShowItem): String {
        return if (show.fromLegacy) "$mainUrl/watch/${show.id}" else "$mainUrl/watch/sp/${show.id}"
    }'''

if "private fun getUrlForShow" not in code:
    code = code.replace(
        '''    private suspend fun apiLegacyGetDecrypted(path: String): String? {''',
        new_url_func + '\n\n' + '''    private suspend fun apiLegacyGetDecrypted(path: String): String? {'''
    )

old_search_url = '''        val url = if (show.isMovie) "$mainUrl/movie/${show.id}" else "$mainUrl/series/${show.id}"'''
new_search_url = '''        val url = getUrlForShow(show)'''
code = code.replace(old_search_url, new_search_url)

# Replace the homepage url line
old_home_url = '''                val url = if (show?.isMovie == true) "$mainUrl/movie/$showId" else "$mainUrl/series/$showId"'''
new_home_url = '''                val url = if (show != null) getUrlForShow(show) else "$mainUrl/watch/sp/$showId"'''
code = code.replace(old_home_url, new_home_url)

old_load_deep = '''        // Direct episode deep-link (only if it's an explicit episode path or we can't find the show)
        if ((url.contains("/watch/sp/") || url.contains("/watch/legacy/")) || (url.contains("/watch/") && show == null)) {
            return newMovieLoadResponse('''

new_load_deep = '''        // Direct episode deep-link parsing (differentiate from whole series /watch/ id)
        val isDeepLink = url.substringAfter("/watch/", "").split("/").filter { it.isNotBlank() }.size > 1
        if (isDeepLink || (url.contains("/watch/") && show == null)) {
            return newMovieLoadResponse('''

code = code.replace(old_load_deep, new_load_deep)

old_movie_path = '''        // Movie path
        if (url.contains("/movie/") || show?.isMovie == true) {'''
new_movie_path = '''        // Movie path
        if (show?.isMovie == true || url.contains("/movie/")) {'''
code = code.replace(old_movie_path, new_movie_path)

old_series_path = '''        // Series path
        if (url.contains("/series/") || url.contains("/watch/") || show?.isMovie == false) {'''
new_series_path = '''        // Series path
        if (show?.isMovie == false || url.contains("/watch/")) {'''
code = code.replace(old_series_path, new_series_path)

# 2. Fix decryptEnvelope for unencrypted APIs
old_decrypt = '''    private fun decryptEnvelope(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{\\"streamUrl\\"")) return trimmed
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
    }'''

new_decrypt = '''    private fun decryptEnvelope(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return trimmed
        return try {
            val obj = JSONObject(trimmed)
            val encrypted = obj.optString("encryptedData", "")
            val iv = obj.optString("iv", "")
            if (encrypted.isNotBlank() && iv.isNotBlank()) {
                decryptPayload(encrypted, iv)
            } else {
                trimmed // Pass-through unencrypted/decrypted JSON objects
            }
        } catch (t: Throwable) {
            Log.e("Cartoony", "decryptEnvelope failed: ${t.message}", t)
            null
        }
    }'''

code = code.replace(old_decrypt, new_decrypt)

# 3. Fix loadLinks logic bypassing legacy video id requirement
old_legacy_link = '''        // Legacy playback by video_id
        if (data.startsWith("legacyVideo:")) {
            val payload = data.removePrefix("legacyVideo:")
            val videoId = payload.substringBefore("|")
            if (videoId.isNotBlank()) {
                val episodeIdPart = payload.substringAfter("|", "").substringBefore("|")
                val showIdPart = payload.substringAfterLast("|", "")
                if (episodeIdPart.isNotBlank()) {
                    val showQuery = if (showIdPart.isNotBlank() && showIdPart != episodeIdPart) "&showId=$showIdPart" else ""
                    val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart$showQuery")'''

new_legacy_link = '''        // Legacy playback
        if (data.startsWith("legacyVideo:")) {
            val payload = data.removePrefix("legacyVideo:")
            val videoId = payload.substringBefore("|")
            val episodeIdPart = payload.substringAfter("|", "").substringBefore("|")
            val showIdPart = payload.substringAfterLast("|", "")
            
            if (episodeIdPart.isNotBlank()) {
                val showQuery = if (showIdPart.isNotBlank() && showIdPart != episodeIdPart) "&showId=$showIdPart" else ""
                val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart$showQuery")'''

code = code.replace(old_legacy_link, new_legacy_link)

old_fallback = '''                // direct by video id fallback
                val direct = "https://pegasus.5387692.xyz/api/hls/$videoId/playlist.m3u8"
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Legacy",
                        url = direct,
                        referer = "https://cartoony.net/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }
        }'''

new_fallback = '''                // direct by video id fallback
                if (videoId.isNotBlank()) {
                    val direct = "https://pegasus.5387692.xyz/api/hls/$videoId/playlist.m3u8"
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name Legacy",
                            url = direct,
                            referer = "https://cartoony.net/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }
            return false
        }'''
code = code.replace(old_fallback, new_fallback)

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)

print("Patch applied for URLs and extraction fix.")
