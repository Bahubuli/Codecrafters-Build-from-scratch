from playwright.sync_api import sync_playwright
from pathlib import Path

STATE_FILE = Path.home() / ".codecrafters" / "browser_session.json"

with sync_playwright() as p:
    browser = p.firefox.launch(headless=True)
    context = browser.new_context(storage_state=str(STATE_FILE))
    page = context.new_page()
    page.goto("https://app.codecrafters.io/courses/dns-server/overview")
    page.wait_for_timeout(3000)
    
    # Click resume building or find active stage
    resume = page.query_selector("a:has-text('Resume Building'), button:has-text('Resume Building')")
    if resume:
        resume.click()
        page.wait_for_timeout(3000)
    
    print("Current URL:", page.url)
    print("Page title / heading:", page.title())
    content = page.query_selector("main") or page.query_selector("body")
    text = content.inner_text() if content else ""
    print("Snippet:")
    print(text[:1000])
    browser.close()
