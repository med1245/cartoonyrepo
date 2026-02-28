import urllib.request
import json
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

API_KEY = b"7annaba3l_loves_crypto_safe_key!"

def decrypt_payload(encrypted_hex, iv_hex):
    cipher = AES.new(API_KEY, AES.MODE_CBC, bytes.fromhex(iv_hex))
    plain = unpad(cipher.decrypt(bytes.fromhex(encrypted_hex)), AES.block_size)
    return plain.decode('utf-8')

url = 'https://cartoony.net/api/sp/episodes?id=1193'
headers = {
    'User-Agent': 'Mozilla/5.0',
    'Referer': 'https://cartoony.net/',
    'Origin': 'https://cartoony.net',
    'Accept': 'application/json'
}
req = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req) as resp:
        text = resp.read().decode('utf-8')
        obj = json.loads(text)
        dec = decrypt_payload(obj['encryptedData'], obj['iv'])
        arr = json.loads(dec)
        first_ep = arr[0]['id']
        print(f"First EP ID: {first_ep}")
        
        # Test SP link extraction
        import urllib.parse
        post_url = 'https://cartoony.net/api/sp/episode/link'
        data = urllib.parse.urlencode({'episodeId': first_ep, 'showId': 1193}).encode()
        post_req = urllib.request.Request(post_url, data=data, headers=headers)
        with urllib.request.urlopen(post_req) as pr:
            ptxt = pr.read().decode('utf-8')
            pobj = json.loads(ptxt)
            if 'encryptedData' in pobj:
                pdec = decrypt_payload(pobj['encryptedData'], pobj['iv'])
                print(f"Decrypted Link: {pdec}")
            else:
                print(f"Unencrypted Link: {ptxt}")
except Exception as e:
    print(e)
