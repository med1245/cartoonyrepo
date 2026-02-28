path = r'C:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\Cartoony\src\main\kotlin\com\cartoony\Cartoony.kt'

with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# Debug: Is `videoId` extraction messing up?
# Let's completely rework the parameters passing via dataUrl using delimited string to avoid bugs!
# In `load()`, the dataUrl for Legacy Episodes is:
# "legacyVideo:${ep.videoId ?: ""}|${ep.id}|${id}"
# e.g., "legacyVideo:4274|5068|506"

# In `loadLinks`:
# val payload = data.removePrefix("legacyVideo:") // "4274|5068|506"
# val parts = payload.split("|")
# val videoId = parts.getOrNull(0) ?: ""
# val episodeIdPart = parts.getOrNull(1) ?: ""
# val showIdPart = parts.getOrNull(2) ?: ""

new_legacy_link_code = '''        // Legacy playback
        if (data.startsWith("legacyVideo:")) {
            val payload = data.removePrefix("legacyVideo:")
            val parts = payload.split("|")
            val videoId = parts.getOrNull(0)?.trim() ?: ""
            val episodeIdPart = parts.getOrNull(1)?.trim() ?: ""
            val showIdPart = parts.getOrNull(2)?.trim() ?: ""
            
            if (episodeIdPart.isNotBlank()) {
                val showQuery = if (showIdPart.isNotBlank() && showIdPart != episodeIdPart) "&showId=$showIdPart" else ""
                val legacyTxt = apiLegacyGetDecrypted("episode?episodeId=$episodeIdPart$showQuery")
                if (legacyTxt != null) {
                    val obj = try { JSONObject(legacyTxt) } catch (e: Exception) { null }
                    if (obj != null) {
                        val streamUrl = obj.optString("streamUrl").trim()
                        if (streamUrl.startsWith("http")) {
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "$name Legacy",
                                    url = streamUrl,
                                    referer = "https://cartoony.net/",
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = streamUrl.contains(".m3u8")
                                )
                            )
                            return true
                        }
                    }
                }
            }

            // direct by video id fallback
            if (videoId.isNotBlank() && videoId != "null") {
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
            return false
        }'''

# Extract the old block properly
import re
# We'll use a regex to replace everything from `// Legacy playback` to the `return false \n        }`
pattern = re.compile(r'        // Legacy playback.*?return false\s*?\n\s*?}', re.DOTALL)
if pattern.search(code):
    code = pattern.sub(new_legacy_link_code, code)
    print("Found and replaced Legacy playback")
else:
    print("Could not find Legacy playback block")

from pathlib import Path

# Same for SP playback
sp_link_code = '''        // SP playback
        var epIdStr = ""
        var showIdStr = ""
        val payload = when {
            data.startsWith("spEpisode:") -> data.removePrefix("spEpisode:")
            data.startsWith("episode:") -> data.removePrefix("episode:")
            else -> data
        }
        val spParts = payload.split("|")
        epIdStr = spParts.getOrNull(0)?.trim() ?: ""
        showIdStr = spParts.getOrNull(1)?.trim() ?: ""
        
        val epId = epIdStr.toIntOrNull() ?: return false
        val showId = showIdStr.toIntOrNull()'''

pattern_sp = re.compile(r'        // SP playback.*?val showId = showIdStr\.toIntOrNull\(\)', re.DOTALL)
if pattern_sp.search(code):
    code = pattern_sp.sub(sp_link_code, code)
    print("Found and replaced SP playback")
else:
    print("Could not find SP playback block")


with open(path, 'w', encoding='utf-8') as f:
    f.write(code)

print("Patch loadLinks routing applied")
