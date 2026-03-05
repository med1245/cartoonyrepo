import asyncio
from playwright.async_api import async_playwright
import json

async def run():
    async with async_playwright() as p:
        # User explicitly mentioned adguard fixes it. We'll use Firefox or a stealth context.
        browser = await p.firefox.launch(headless=True)
        context = await browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        )
        page = await context.new_page()

        m3u8_url = None

        page.on("request", lambda request: print(">>", request.method, request.url))
        
        async def handle_response(response):
            nonlocal m3u8_url
            if ".m3u8" in response.url:
                m3u8_url = response.url
                print("================ FOUND M3U8 ================")
                print(m3u8_url)
                print("============================================")
        
        page.on("response", handle_response)

        # we get this url from the previous dump logs
        url = "https://web340x.faselhdx.bid/video_player/?p=6&url=160_hd1080b"
        print(f"Navigating to {url}")
        
        try:
            await page.goto(url, wait_until="domcontentloaded")
            await page.wait_for_timeout(3000)

            # Try to click the play button
            await page.evaluate("""() => {
                const btn = document.querySelector('.jw-icon-display, .play-button, .jw-icon');
                if(btn) btn.click();
            }""")
            
            await page.wait_for_timeout(5000)
            
            # evaluate mainPlayer.play()
            await page.evaluate("""() => {
                if(typeof mainPlayer !== 'undefined') mainPlayer.play();
            }""")
            
            await page.wait_for_timeout(5000)
            
        except Exception as e:
            print(f"Error: {e}")

        await browser.close()
        
        if m3u8_url:
            with open("m3u8_result.txt", "w") as f:
                f.write(m3u8_url)

asyncio.run(run())
