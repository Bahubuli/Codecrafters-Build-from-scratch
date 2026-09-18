from playwright.sync_api import sync_playwright
from pathlib import Path

STATE_FILE = Path.home() / ".codecrafters" / "browser_session.json"

with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    context = browser.new_context(storage_state=str(STATE_FILE))
    page = context.new_page()
    page.goto("https://app.codecrafters.io/catalog")
    page.wait_for_timeout(4000)
    
    links = page.query_selector_all("a")
    courses = []
    for link in links:
        href = link.get_attribute("href") or ""
        if "/courses/" in href:
            text = link.inner_text().replace("\n", " | ").strip()
            if text and (href, text) not in courses:
                courses.append((href, text))
    
    for href, text in courses:
        print(f"{href} -> {text[:120]}")
    browser.close()
