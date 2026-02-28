import urllib.request
import re
import json

urls = [
    'https://cartoony.net/watch/506',
    'https://carateen.tv/watch/506'
]

for url in urls:
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req) as response:
            html = response.read().decode('utf-8')
            print(f'-- {url} --')
            print('Length:', len(html))
            iframes = re.findall(r'<iframe[^>]*src=[\"\'\s]*([^\"\'\s>]+)', html)
            print('iframes:', iframes)
            
            # also look for video tags
            videos = re.findall(r'<video[^>]*src=[\"\'\s]*([^\"\'\s>]+)', html)
            print('videos:', videos)
            
            # look for episode id in raw html config
            ep_id = re.findall(r'episodeId\s*[:=]\s*[\'\"]?(\d+)', html)
            if not ep_id:
                ep_id = re.findall(r'id=\"episode-id\"\s*value=\"(\d+)\"', html)
            print('episode id found:', ep_id)
            
    except Exception as e:
        print(f'Error fetching {url}:', e)
