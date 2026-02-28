import re

path = r'C:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\Cartoony\src\main\kotlin\com\cartoony\Cartoony.kt'

with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# Revert search / homepage urls to mainUrl
code = code.replace(
    'val url = if (show.isMovie) "$watchDomain/movie/${show.id}" else "$watchDomain/series/${show.id}"',
    'val url = if (show.isMovie) "$mainUrl/movie/${show.id}" else "$mainUrl/series/${show.id}"'
)

code = code.replace(
    'val url = if (show?.isMovie == true) "$watchDomain/movie/$showId" else "$watchDomain/series/$showId"',
    'val url = if (show?.isMovie == true) "$mainUrl/movie/$showId" else "$mainUrl/series/$showId"'
)

# Fix deep link bug
old_deep_link = '''        // Direct episode deep-link
        if (url.contains("/watch/")) {
            return newMovieLoadResponse(
                name = "Cartoony Episode",
                url = url,
                dataUrl = "spEpisode:$id",
                type = TvType.Anime
            )
        }'''

new_deep_link = '''        // Direct episode deep-link (only if it's an explicit episode path or we can't find the show)
        if ((url.contains("/watch/sp/") || url.contains("/watch/legacy/")) || (url.contains("/watch/") && show == null)) {
            return newMovieLoadResponse(
                name = "Cartoony Episode",
                url = url,
                dataUrl = "spEpisode:$id",
                type = TvType.Anime
            )
        }'''

code = code.replace(old_deep_link, new_deep_link)

# Fix isSeries block condition
old_series = '''        // Series path
        if (url.contains("/series/")) {'''
new_series = '''        // Series path
        if (url.contains("/series/") || url.contains("/watch/") || show?.isMovie == false) {'''

code = code.replace(old_series, new_series)


with open(path, 'w', encoding='utf-8') as f:
    f.write(code)

print("Patch applied")
