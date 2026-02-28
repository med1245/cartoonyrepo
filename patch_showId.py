import re

path = r'C:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\Cartoony\src\main\kotlin\com\cartoony\Cartoony.kt'

with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# Fix episode loadLinks URL encoding
code = code.replace(
    'newEpisode("legacyVideo:${ep.videoId ?: ""}|${ep.id}") {',
    'newEpisode("legacyVideo:${ep.videoId ?: ""}|${ep.id}|${id}") {'
)

code = code.replace(
    'newEpisode("spEpisode:$epId") {',
    'newEpisode("spEpisode:$epId|$id") {'
)

# And in Movie path, they usually don't need showId or id is the same
code = code.replace(
    'dataUrl = "legacyVideo:${first.videoId ?: ""}|${first.id}",',
    'dataUrl = "legacyVideo:${first.videoId ?: ""}|${first.id}|$id",'
)

code = code.replace(
    'dataUrl = "spEpisode:$epId",',
    'dataUrl = "spEpisode:$epId|$id",'
)

code = code.replace(
    'dataUrl = "spEpisode:$id",',
    'dataUrl = "spEpisode:$id|$id",'
)


# Now update loadLinks to parse showId
old_legacy_parse = '''            val videoId = payload.substringBefore("|")
            if (videoId.isNotBlank()) {
                val episodeIdPart = payload.substringAfter("|", "")
                if (episodeIdPart.isNotBlank()) {
                    val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart")'''

new_legacy_parse = '''            val videoId = payload.substringBefore("|")
            if (videoId.isNotBlank()) {
                val episodeIdPart = payload.substringAfter("|", "").substringBefore("|")
                val showIdPart = payload.substringAfterLast("|", "")
                if (episodeIdPart.isNotBlank()) {
                    val showQuery = if (showIdPart.isNotBlank() && showIdPart != episodeIdPart) "&showId=$showIdPart" else ""
                    val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart$showQuery")'''

code = code.replace(old_legacy_parse, new_legacy_parse)

old_sp_parse = '''        // SP playback
        val epId = when {
            data.startsWith("spEpisode:") -> data.removePrefix("spEpisode:").toIntOrNull()
            data.startsWith("episode:") -> data.removePrefix("episode:").toIntOrNull()
            else -> data.toIntOrNull()
        } ?: return false

        // Strategy 1: SP endpoint'''

new_sp_parse = '''        // SP playback
        var epIdStr = ""
        var showIdStr = ""
        when {
            data.startsWith("spEpisode:") -> {
                val d = data.removePrefix("spEpisode:")
                epIdStr = d.substringBefore("|")
                showIdStr = d.substringAfter("|", "")
            }
            data.startsWith("episode:") -> {
                val d = data.removePrefix("episode:")
                epIdStr = d.substringBefore("|")
                showIdStr = d.substringAfter("|", "")
            }
            else -> {
                epIdStr = data.substringBefore("|")
                showIdStr = data.substringAfter("|", "")
            }
        }
        val epId = epIdStr.toIntOrNull() ?: return false
        val showId = showIdStr.toIntOrNull()

        // Strategy 1: SP endpoint'''

code = code.replace(old_sp_parse, new_sp_parse)


old_sp_post = '''                    url = "$base/api/sp/episode/link",
                    headers = overrides + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("episodeId" to epId.toString())'''

new_sp_post = '''                    url = "$base/api/sp/episode/link",
                    headers = overrides + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mutableMapOf<String, String>().apply {
                        put("episodeId", epId.toString())
                        if (showId != null) put("showId", showId.toString())
                    }'''

code = code.replace(old_sp_post, new_sp_post)


old_legacy_strat2 = '''        // Strategy 2: legacy endpoint by numeric episode id
        val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$epId")'''

new_legacy_strat2 = '''        // Strategy 2: legacy endpoint by numeric episode id
        val showQuery = if (showId != null) "&showId=$showId" else ""
        val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$epId$showQuery")'''

code = code.replace(old_legacy_strat2, new_legacy_strat2)


with open(path, 'w', encoding='utf-8') as f:
    f.write(code)

print("Patch applied for showId parameter")
