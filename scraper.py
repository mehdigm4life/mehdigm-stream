#!/usr/bin/env python3
import sys, os, subprocess, shutil, re

def fetch(url, output=None):
    out = output or (url.strip("/").split("/")[-1] or "index") + ".html"

    cmd = [
        "curl", "-sL",
        "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "-H", "Accept-Language: en-US,en;q=0.9,ar;q=0.8",
        "--compressed", "-o", out,
        "-w", "HTTP %{http_code} | نوع: %{content_type} | حجم: %{size_download} بايت\n",
        url
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    meta = r.stderr if r.stderr else r.stdout
    for l in meta.strip().split("\n"):
        if l.strip(): print(l, file=sys.stderr)

    if os.path.exists(out) and os.path.getsize(out) > 0:
        with open(out, "rb") as f:
            c = f.read(200)
        is_good = False
        if b"<!DOCTYPE html" in c or b"<html" in c or b"<HEAD" in c or b"<head" in c:
            print("HTML صحيح ✓", file=sys.stderr)
            is_good = True
        else:
            nulls = sum(1 for b in c[:500] if b == 0)
            if nulls > 20:
                print("⚠️  محتوى ثنائي! جارٍ فك الضغط...", file=sys.stderr)
                decompressed = False
                try:
                    import brotli
                    with open(out, "rb") as f:
                        compressed = f.read()
                    d = brotli.decompress(compressed)
                    with open(out, "wb") as f:
                        f.write(d)
                    print(f"تم فك Brotli: {len(compressed)} → {len(d)} بايت ✓", file=sys.stderr)
                    decompressed = True
                    is_good = True
                except:
                    pass
                if not decompressed:
                    try:
                        import gzip
                        with open(out, "rb") as f:
                            compressed = f.read()
                        d = gzip.decompress(compressed)
                        with open(out, "wb") as f:
                            f.write(d)
                        print(f"تم فك Gzip: {len(compressed)} → {len(d)} بايت ✓", file=sys.stderr)
                        decompressed = True
                        is_good = True
                    except:
                        pass
                if not decompressed:
                    print("❌ لم نتمكن من فك الضغط - الملف قد يكون تالفًا", file=sys.stderr)
            elif all(b < 128 for b in c[:100]):
                print("نص عادي", file=sys.stderr)
                is_good = True
            else:
                print("⚠️  محتوى غير معروف", file=sys.stderr)
        print(f"محفوظ في: {out}", file=sys.stderr)
        return True
    return False

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("استعمال: python scraper.py <رابط> [رابط2 ...] [-o ملف.html]")
        sys.exit(1)

    urls, out_file = [], None
    i = 0
    while i < len(sys.argv[1:]):
        a = sys.argv[1:][i]
        if a == "-o" and i + 1 < len(sys.argv[1:]):
            out_file = sys.argv[1:][i+1]; i += 2
        else:
            urls.append(a); i += 1

    for url in urls:
        fetch(url, out_file if len(urls) == 1 else None)
