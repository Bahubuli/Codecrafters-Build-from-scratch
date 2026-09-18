import sys
import os
import shutil
import sqlite3
import time
from pathlib import Path
from playwright.sync_api import sync_playwright

sys.stdout.reconfigure(encoding="utf-8")

SESSION_DIR = Path.home() / ".codecrafters"
SESSION_DIR.mkdir(parents=True, exist_ok=True)
STATE_FILE = SESSION_DIR / "browser_session.json"

def get_firefox_cookies():
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return []
    profiles_dir = Path(appdata) / "Mozilla" / "Firefox" / "Profiles"
    if not profiles_dir.exists():
        return []

    # Find the release profile or default profile
    profiles = list(profiles_dir.glob("*.default-release")) or list(profiles_dir.glob("*.default"))
    if not profiles:
        return []

    profile = profiles[0]
    temp_dir = SESSION_DIR / "temp_cookies"
    temp_dir.mkdir(parents=True, exist_ok=True)

    for f in ["cookies.sqlite", "cookies.sqlite-wal", "cookies.sqlite-shm"]:
        src = profile / f
        if src.exists():
            shutil.copy2(src, temp_dir / f)

    db_path = temp_dir / "cookies.sqlite"
    if not db_path.exists():
        return []

    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute("SELECT host, name, value, path, isSecure, isHttpOnly FROM moz_cookies WHERE host LIKE '%github.com%' OR host LIKE '%codecrafters.io%'")
    cookie_rows = cursor.fetchall()
    conn.close()

    cookies = []
    for host, name, val, path, is_sec, is_http in cookie_rows:
        domain = host if host.startswith(".") else "." + host
        cookies.append({
            "name": name,
            "value": val,
            "domain": domain,
            "path": path or "/",
            "secure": bool(is_sec),
            "httpOnly": bool(is_http)
        })
    return cookies

def main():
    ff_cookies = get_firefox_cookies()
    print(f"Loaded {len(ff_cookies)} cookies from Firefox profile.")

    with sync_playwright() as p:
        browser = p.firefox.launch(headless=True)
        
        # Try loading existing session state if available, else new context with cookies
        if STATE_FILE.exists():
            context = browser.new_context(storage_state=str(STATE_FILE), viewport={"width": 1400, "height": 900})
        else:
            context = browser.new_context(viewport={"width": 1400, "height": 900})
            if ff_cookies:
                context.add_cookies(ff_cookies)

        page = context.new_page()

        print("Navigating to https://app.codecrafters.io/courses/redis/overview ...")
        page.goto("https://app.codecrafters.io/courses/redis/overview", wait_until="domcontentloaded")
        time.sleep(3)

        # Check if login is needed
        if "login" in page.url or "Sign in" in page.evaluate("() => document.body.innerText"):
            print("Session not logged in, authenticating via GitHub...")
            page.goto("https://app.codecrafters.io/login", wait_until="domcontentloaded")
            time.sleep(2)

            btn = page.query_selector("form[action*='authorize_app'] button, form[action*='authorize_app'] input[type='submit'], button:has-text('Continue')")
            if btn:
                btn.click()
                page.wait_for_load_state("networkidle")
                time.sleep(4)

            page.goto("https://app.codecrafters.io/courses/redis/overview", wait_until="domcontentloaded")
            time.sleep(3)

        context.storage_state(path=str(STATE_FILE))

        # Check for Resume Building button
        resume_btn = page.query_selector("button:has-text('Resume Building'), a:has-text('Resume Building')")
        if resume_btn:
            print("Clicking Resume Building...")
            resume_btn.click()
            time.sleep(3)

        # Look for "Mark stage as complete" button
        mark_btn = page.query_selector("button:has-text('Mark stage as complete'), a:has-text('Mark stage as complete')")
        if mark_btn:
            print("Found 'Mark stage as complete' button. Clicking...")
            mark_btn.click()
            time.sleep(4)
            print("Successfully clicked 'Mark stage as complete'!")
        else:
            print("No 'Mark stage as complete' button found (stage may already be completed or pending test run).")

        # Check for confirmation dialogs
        confirm_btn = page.query_selector("button:has-text('Confirm'), button:has-text('Proceed'), button:has-text('Yes'), button:has-text('Continue')")
        if confirm_btn and confirm_btn.is_visible():
            print("Confirming dialog...")
            confirm_btn.click()
            time.sleep(3)

        context.storage_state(path=str(STATE_FILE))
        print("Final URL:", page.url)
        browser.close()

if __name__ == "__main__":
    main()
