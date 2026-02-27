import re

path = r'C:\Users\MEHDI MARSAMAN\Documents\GitHub\cartoony\Cartoony\src\main\kotlin\com\cartoony\Cartoony.kt'

with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# Replace url generation
code = code.replace(
    'val url = if (show.isMovie) "$mainUrl/movie/${show.id}" else "$mainUrl/series/${show.id}"',
    'val url = if (show.isMovie) "$watchDomain/movie/${show.id}" else "$watchDomain/series/${show.id}"'
)

code = code.replace(
    'val url = if (show?.isMovie == true) "$mainUrl/movie/$showId" else "$mainUrl/series/$showId"',
    'val url = if (show?.isMovie == true) "$watchDomain/movie/$showId" else "$watchDomain/series/$showId"'
)

# Add isM3u8 to legacyVideo direct (pegasus fallback 1)
code = code.replace(
    '''url = direct,
                        referer = "https://cartoony.net/",
                        quality = Qualities.Unknown.value
                    )''',
    '''url = direct,
                        referer = "https://cartoony.net/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )'''
)

# Add isM3u8 to legacy fallback
code = code.replace(
    '''url = streamUrl,
                                    referer = "https://cartoony.net/",
                                    quality = Qualities.Unknown.value
                                )''',
    '''url = streamUrl,
                                    referer = "https://cartoony.net/",
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = streamUrl.contains(".m3u8")
                                )'''
)

# Add isM3u8 to SP endpoint link
code = code.replace(
    '''url = link,
                            referer = "$watchDomain/",
                            quality = Qualities.Unknown.value
                        )''',
    '''url = link,
                            referer = "$watchDomain/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = link.contains(".m3u8") || link.contains("/hls/") || link.contains("playlist")
                        )'''
)

# Add isM3u8 to cdnPrivate fallback
code = code.replace(
    '''url = fallback,
                            referer = "$watchDomain/",
                            quality = Qualities.Unknown.value
                        )''',
    '''url = fallback,
                            referer = "$watchDomain/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )'''
)

# Add isM3u8 to legacy endpoint by numeric episode id fallback
code = code.replace(
    '''url = streamUrl,
                        referer = "$watchDomain/",
                        quality = Qualities.Unknown.value
                    )''',
    '''url = streamUrl,
                        referer = "$watchDomain/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = streamUrl.contains(".m3u8")
                    )'''
)

# Add isM3u8 to direct HLS pattern fallback
code = code.replace(
    '''url = direct,
                referer = "$watchDomain/",
                quality = Qualities.Unknown.value
            )''',
    '''url = direct,
                referer = "$watchDomain/",
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )'''
)

# Fix API header Origin so it matches current origin
# (already is watchDomain, that's fine)

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)

print("Patch applied")
