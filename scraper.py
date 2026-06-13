#!/usr/bin/env python3
import sys
import os

try:
    import cloudscraper
except ImportError:
    print("cloudscraper not found. Installing...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "cloudscraper"])
    import cloudscraper

def fetch_url(url, output_file=None):
    scraper = cloudscraper.create_scraper(
        browser={
            'browser': 'chrome',
            'platform': 'windows',
            'desktop': True,
        }
    )

    headers = {
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
        "Accept-Encoding": "gzip, deflate, br",
    }

    try:
        resp = scraper.get(url, timeout=30, headers=headers)
        content_type = resp.headers.get("Content-Type", "")
        print(f"Status: {resp.status_code}", file=sys.stderr)
        print(f"Content-Type: {content_type}", file=sys.stderr)
        print(f"Final URL: {resp.url}", file=sys.stderr)
        print(f"Size: {len(resp.content)} bytes", file=sys.stderr)

        if not output_file:
            output_file = url.strip("/").split("/")[-1] or "index"
            if not output_file.endswith(".html"):
                output_file += ".html"

        with open(output_file, "wb") as f:
            f.write(resp.content)

        print(f"Saved to: {output_file}", file=sys.stderr)

        if b"<!DOCTYPE html" in resp.content[:50] or b"<html" in resp.content[:100]:
            print("Result: Valid HTML ✓", file=sys.stderr)
        elif all(b < 128 for b in resp.content[:200]):
            print("Result: Text content", file=sys.stderr)
        else:
            print("WARNING: Content may still be binary!", file=sys.stderr)
            print("First 40 bytes hex:", resp.content[:40].hex(), file=sys.stderr)

        return True
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return False

if __name__ == "__main__":
    urls = sys.argv[1:]
    if not urls:
        print("Usage: python scraper.py <url> [url2 url3 ...]")
        print("       python scraper.py <url> -o filename.html")
        sys.exit(1)

    output_file = None
    if "-o" in urls:
        idx = urls.index("-o")
        output_file = urls[idx + 1] if len(urls) > idx + 1 else None
        urls = [u for i, u in enumerate(urls) if i != idx and (i != idx + 1 or not u.startswith("-"))]

    for url in urls:
        fetch_url(url, output_file if len(urls) == 1 else None)
