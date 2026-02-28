import urllib.request
import json

url = 'https://cartoony.net/api/sp/tvshows'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as response:
        # We know it's encrypted so this won't be pure JSON unless it decrypted?
        # Oh wait, the plugin says it must be decrypted using AES/CBC.
        html = response.read()
        print('Cartoony.net tvshows length:', len(html))
except Exception as e:
    print('Error cartoony:', e)
