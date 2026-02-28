import re

path = r'C:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\Cartoony\src\main\kotlin\com\cartoony\Cartoony.kt'

with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Update `getUrlForShow` to append `?type=movie` or `?type=series`
old_geturl = '''    private fun getUrlForShow(show: ShowItem): String {
        return if (show.fromLegacy) "$mainUrl/watch/${show.id}" else "$mainUrl/watch/sp/${show.id}"
    }'''

new_geturl = '''    private fun getUrlForShow(show: ShowItem): String {
        val base = if (show.fromLegacy) "$mainUrl/watch/${show.id}" else "$mainUrl/watch/sp/${show.id}"
        return if (show.isMovie) "$base?type=movie" else "$base?type=series"
    }'''

code = code.replace(old_geturl, new_geturl)

# 2. Rewrite the top part of `load(url)`
old_load_top = '''    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        val show = getMergedShows().firstOrNull { it.id == id }

        // Direct episode deep-link parsing (differentiate from whole series /watch/ id)
        val isDeepLink = url.substringAfter("/watch/", "").split("/").filter { it.isNotBlank() }.size > 1
        if (isDeepLink || (url.contains("/watch/") && show == null)) {
            return newMovieLoadResponse(
                name = "Cartoony Episode",
                url = url,
                dataUrl = "spEpisode:$id|$id",
                type = TvType.Anime
            )
        }

        // Movie path
        if (show?.isMovie == true || url.contains("/movie/")) {'''

new_load_top = '''    override suspend fun load(url: String): LoadResponse? {
        val urlWithoutQuery = url.substringBefore("?")
        val watchParts = urlWithoutQuery.substringAfter("/watch/", "").split("/").filter { it.isNotBlank() }
        
        // Parse showId and episodeId (if any)
        val showIdStr: String?
        val episodeIdStr: String?
        if (watchParts.isNotEmpty() && watchParts[0] == "sp") {
            showIdStr = watchParts.getOrNull(1)
            episodeIdStr = watchParts.getOrNull(2)
        } else if (watchParts.isNotEmpty()) {
            showIdStr = watchParts.getOrNull(0)
            episodeIdStr = watchParts.getOrNull(1)
        } else {
            showIdStr = urlWithoutQuery.substringAfterLast("/")
            episodeIdStr = null
        }
        
        val id = showIdStr?.toIntOrNull() ?: return null
        val show = getMergedShows().firstOrNull { it.id == id }
        val isMovie = show?.isMovie == true || url.contains("type=movie") || url.contains("/movie/")

        // If an episode deep link was pasted (e.g. watch/sp/1017/32293)
        if (episodeIdStr != null && episodeIdStr.toIntOrNull() != null) {
            val epId = episodeIdStr.toInt()
            return newMovieLoadResponse(
                name = "Cartoony Episode",
                url = url,
                dataUrl = "spEpisode:$epId|$id",
                type = TvType.Anime
            )
        }

        // Movie path
        if (isMovie) {'''

code = code.replace(old_load_top, new_load_top)

# 3. Replace Series path check
old_series_path = '''        // Series path
        if (show?.isMovie == false || url.contains("/watch/")) {'''
new_series_path = '''        // Series path
        if (!isMovie) {'''
code = code.replace(old_series_path, new_series_path)

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)

print("Patch load routing applied")
