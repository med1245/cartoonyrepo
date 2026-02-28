import urllib.request
import json
import uuid
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

API_KEY = b"7annaba3l_loves_crypto_safe_key!"

def decrypt_payload(encrypted_hex, iv_hex):
    cipher = AES.new(API_KEY, AES.MODE_CBC, bytes.fromhex(iv_hex))
    plain = unpad(cipher.decrypt(bytes.fromhex(encrypted_hex)), AES.block_size)
    return plain.decode('utf-8')

def get_decrypted(url, headers=None):
    if headers is None:
        headers = {
            "User-Agent": "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            "Accept-Language": "ar,en-US;q=0.9,en;q=0.8",
            "Referer": "https://carateen.tv/",
            "Origin": "https://carateen.tv",
            "Accept": "application/json"
        }
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as response:
            text = response.read().decode('utf-8').strip()
            print(f"URL: {url}")
            print(f"Raw decrypted: ", text[:200])
            if text.startswith('[') or text.startswith('{'):
                try:
                    obj = json.loads(text)
                    if 'encryptedData' in obj and 'iv' in obj:
                        return decrypt_payload(obj['encryptedData'], obj['iv'])
                    return text
                except Exception:
                    return text
            return text
    except Exception as e:
        print(f"Error for {url}: {e}")
        return None

# Find Pollyanna
shows_json = get_decrypted("https://cartoony.net/api/sp/tvshows")
if shows_json:
    shows = json.loads(shows_json)
    poly = next((s for s in shows if s.get('id') == 506), None)
    if poly:
        print("Found Pollyanna in sp API:", poly.get('name'))
    else:
        print("Pollyanna not found in sp API, ID 506 might be from Legacy API")
        
    legacy_json = get_decrypted("https://cartoony.net/api/tvshows")
    if legacy_json:
        legacy = json.loads(legacy_json)
        poly_legacy = next((s for s in legacy if s.get('id') == 506), None)
        if poly_legacy:
            print("Found Pollyanna in Legacy API:", poly_legacy.get('title'))
            
ep_txt = get_decrypted("https://cartoony.net/api/sp/episodes?id=506")
if ep_txt:
    print("SP EPISODES:", ep_txt[:200])

ep_legacy_txt = get_decrypted("https://cartoony.net/api/episodes?id=506")
if ep_legacy_txt:
    print("LEGACY EPISODES:", ep_legacy_txt[:200])
