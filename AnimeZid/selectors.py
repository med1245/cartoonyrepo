from bs4 import BeautifulSoup

with open('pollyanna_ep.html', encoding='utf-8') as f:
    soup = BeautifulSoup(f, 'html.parser')

links = soup.select("a[href*='watch.php?vid=']")
print(f"Found {len(links)} links")
for i, link in enumerate(links[:20]):
    print(f"[{i}] {link.text.strip()} - Parent classes: {link.parent.get('class', [])}")
    print(f"     Grandparent classes: {link.parent.parent.get('class', [])}")
    print("---")
