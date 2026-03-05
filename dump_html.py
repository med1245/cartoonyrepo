import urllib.request
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

import urllib.request
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

url = 'https://web350x.faselhdx.bid/video_player?player_token=RisvSFZkUFlYTUtWR2lnY3pKRk1VWUNhM3UyTlllNktFb0p3ck1kbTZnYTdjWlhDaGxQOEJlbDZnWDNQNXNwWjFjS1phV002SWdyS1ludDlMZ1daWlZiUGdNbUNyb2xNT0ZpcXNLT3ZqSHdqaEFVL04rc3Nydm5Pem5vaFFTRG5COWpnZjg3MGEvTW9zM3R0Q2dxblQxeUN4SlBTWHdScTNhOUhkYVYwSUFFNU9mV3VVend4bm5CcTBEUVBFdkV2bUdjb0x2aGt4THNqSXYwdEtWQkIxNW5QVmNYdENkOC9YQkpCNlVSNUhyQT06Oj6tIOyFYc0aHKOJXgqnyRU%3D'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req, context=ctx) as response:
        html = response.read().decode('utf-8')
        with open('dump.html', 'w', encoding='utf-8') as f:
            f.write(html)
        print("dumped video_player")
except Exception as e:
    print(f'Error:', e)
