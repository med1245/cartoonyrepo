import json

# Look at the first HAR in detail
harfile = r'C:\Users\MEHDI MARSAMAN\Desktop\har files\www.stardima.com.har'

with open(harfile, encoding='utf-8') as f:
    data = json.load(f)

entries = data['log']['entries']

for e in entries:
    url = e['request']['url']
    method = e['request']['method']
    status = e['response']['status']
    body = e['response']['content'].get('text', '')
    
    # Show responses with stardima
    if 'stardima.com' in url:
        print(f"[{method}] {status} {url}")
        if body and len(body) < 5000:
            print(f"  RESPONSE: {body[:2000]}")
        elif body:
            print(f"  RESPONSE (truncated): {body[:2000]}")
