import urllib.request
import json
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
            "User-Agent": "Mozilla/5.0",
            "Referer": "https://carateen.tv/",
            "Origin": "https://carateen.tv",
            "Accept": "application/json"
        }
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as response:
            text = response.read().decode('utf-8').strip()
            print(f"URL: {url}")
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

res = get_decrypted("https://cartoony.net/api/episode?episodeId=5068&showId=506")
print(res)
