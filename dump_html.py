import urllib.request

urls = [
    'https://cartoony.net/watch/506'
]

for url in urls:
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req) as response:
            html = response.read().decode('utf-8')
            with open('dump.html', 'w', encoding='utf-8') as f:
                f.write(html)
            print("dumped")
    except Exception as e:
        print(f'Error fetching {url}:', e)
