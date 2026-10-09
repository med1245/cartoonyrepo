import re

fpath = r'c:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\StarDima\src\main\kotlin\com\stardima\StarDima.kt'
with open(fpath, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: Fix the COMMENTS regex - replace the entire Pattern 2 block
old = '''        // Pattern 2: JSON key-value
        if (!found) {
            val jsonRegex = Regex(
                """[\'"](?:file|url|src|stream|link|source|hls|video)[\'"]\\s*:\\s*[\'"]( https?://[^\'\"]+\\.(?:mp4|m3u8)[^\'\"]*)[\'"]\"\"\",
                setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
            )
            for (m in jsonRegex.findAll(html)) {
                val src   = m.groupValues[1].trim()
                val isHls = src.contains(".m3u8")
                callback(ExtractorLink(name, "$name Stream", src, referer, Qualities.Unknown.value, isHls))
                found = true
            }
        }'''

new = '''        // Pattern 2: JSON key-value
        if (!found) {
            val jsonRegex = Regex(
                """["\'](file|url|src|stream|link|source|hls|video)["\']\\s*:\\s*["\'](https?://[^"\\']+\\.(?:mp4|m3u8)[^"\\']*)["\']""",
                RegexOption.IGNORE_CASE
            )
            for (m in jsonRegex.findAll(html)) {
                val src = m.groupValues[2].trim()
                if (src.isBlank()) continue
                val isHls = src.contains(".m3u8")
                callback(ExtractorLink(name, "$name Stream", src, referer, Qualities.Unknown.value, isHls))
                found = true
            }
        }'''

if old in content:
    content = content.replace(old, new, 1)
    print("Fixed Pattern 2 block")
else:
    # Try finding it differently 
    print("OLD NOT FOUND - searching manually")
    idx = content.find("// Pattern 2: JSON key-value")
    print(repr(content[idx:idx+600]))

with open(fpath, 'w', encoding='utf-8') as f:
    f.write(content)
print("Done")
