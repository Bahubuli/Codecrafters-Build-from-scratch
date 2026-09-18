import sys
from playwright.sync_api import sync_playwright
from pathlib import Path

STATE_FILE = Path.home() / ".codecrafters" / "browser_session.json"

course = sys.argv[1] if len(sys.argv) > 1 else "bittorrent"

with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    context = browser.new_context(storage_state=str(STATE_FILE))
    page = context.new_page()
    page.goto(f"https://app.codecrafters.io/courses/{course}/overview")
    page.wait_for_timeout(3000)
    
    links = page.query_selector_all("a")
    stages = []
    seen = set()
    for link in links:
        href = link.get_attribute("href") or ""
        if "/stages/" in href and href not in seen:
            seen.add(href)
            text = link.inner_text().replace("\n", " -- ")
            stages.append((href, text))
            
    print(f"Found {len(stages)} stages for {course}:")
    for h, t in stages:
        print(f"{h}: {t}")
    browser.close()
