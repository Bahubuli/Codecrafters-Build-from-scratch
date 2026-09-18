from playwright.sync_api import sync_playwright
from pathlib import Path
import time

STATE_FILE = Path.home() / '.codecrafters' / 'browser_session.json'

with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    context = browser.new_context(storage_state=str(STATE_FILE))
    page = context.new_page()
    page.goto('https://app.codecrafters.io/courses/bittorrent/introduction')
    page.wait_for_timeout(3000)

    # Click intermediate or advanced
    opt = page.query_selector('text="Advanced"') or page.query_selector('text="Intermediate"')
    if opt:
        print("Found proficiency option, clicking...")
        opt.click()
        time.sleep(2)

    # Look for Continue / Submit / Finish buttons
    for txt in ["Continue", "Submit", "Finish", "Start Challenge"]:
        btn = page.query_selector(f'button:has-text("{txt}")')
        if btn and btn.is_visible():
            print(f"Clicking {txt}...")
            btn.click()
            time.sleep(2)

    context.storage_state(path=str(STATE_FILE))
    print("Page URL:", page.url)
    browser.close()
