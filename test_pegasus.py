import urllib.request
import ssl

url = 'https://pegasus.5387692.xyz/api/hls/4274/playlist.m3u8'
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
    "Referer": "https://cartoony.net/",
    "Origin": "https://cartoony.net/",
    "Accept": "*/*",
    "Accept-Language": "ar,en-US;q=0.9,en;q=0.8"
}

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

req = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req, context=ctx, timeout=15) as response:
        print("Status:", response.getcode())
        print("Headers:", response.headers)
        print("Body:", response.read().decode('utf-8')[:500])
except Exception as e:
    print("Error:", e)
