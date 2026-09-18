import sys
from playwright.sync_api import sync_playwright
from pathlib import Path

STATE_FILE = Path.home() / ".codecrafters" / "browser_session.json"

def get_stage_instructions(stage_slug=None):
    with sync_playwright() as p:
        browser = p.firefox.launch(headless=True)
        context = browser.new_context(storage_state=str(STATE_FILE))
        page = context.new_page()
        if stage_slug:
            url = f"https://app.codecrafters.io/courses/dns-server/stages/{stage_slug}"
        else:
            url = "https://app.codecrafters.io/courses/dns-server/overview"
            page.goto(url)
            page.wait_for_timeout(2000)
            resume = page.query_selector("a:has-text('Resume Building'), button:has-text('Resume Building')")
            if resume:
                resume.click()
                page.wait_for_timeout(3000)
            url = page.url

        if not stage_slug:
            pass
        else:
            page.goto(url)
            page.wait_for_timeout(3000)

        print("STAGE_URL:", page.url)
        # Try finding the instructions container
        container = page.query_selector("div[data-test-instructions-container]") or page.query_selector("main")
        if container:
            print("--- INSTRUCTIONS START ---")
            print(container.inner_text())
            print("--- INSTRUCTIONS END ---")
        else:
            print("--- FULL BODY START ---")
            print(page.inner_text("body"))
            print("--- FULL BODY END ---")
        browser.close()

if __name__ == "__main__":
    slug = sys.argv[1] if len(sys.argv) > 1 else None
    get_stage_instructions(slug)
