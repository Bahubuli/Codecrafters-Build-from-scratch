from playwright.sync_api import sync_playwright
from pathlib import Path

STATE_FILE = Path.home() / ".codecrafters" / "browser_session.json"

with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    context = browser.new_context(storage_state=str(STATE_FILE))
    page = context.new_page()
    page.goto("https://app.codecrafters.io/courses/dns-server/overview")
    page.wait_for_timeout(3000)
    
    links = page.query_selector_all("a")
    stages = []
    for link in links:
        href = link.get_attribute("href") or ""
        if "/stages/" in href:
            text = link.inner_text().replace("\n", " -- ")
            stages.append((href, text))
            
    print(f"Found {len(stages)} stages:")
    for h, t in stages:
        print(f"{h}: {t}")
    browser.close()
