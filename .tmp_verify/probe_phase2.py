#!/usr/bin/env python3
"""
Phase 2c Deep Extractor Inspection:
1. Inspects the exact HTML / JS / API of all remaining embed hosts from Star One, Phony,
   Frieren, Kaijudo, Hunter x Hunter Pilot, Blue Lock, Toy Story 5, plus regional samples.
2. Tests POST advanced_extractor.php & POST html_extractor.php with (url, html).
3. Verifies actual HTTP 200/206 playback (with Referer/Origin headers) on unpacked HLS/MP4 URLs
   from Star One and Phony so we know exact Referer requirements for ExoPlayer!
"""
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
APP_UA = "okhttp/4.10.0"

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


def http_req(url: str, method: str = "GET", headers: dict = None, data: dict = None, timeout: int = 14, max_bytes: int = 1_500_000):
    hdrs = dict(headers or {})
    body = None
    if data is not None:
        body = urllib.parse.urlencode(data).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as resp:
            raw = resp.read(max_bytes)
            return resp.status, resp.geturl(), dict(resp.headers), raw
    except urllib.error.HTTPError as e:
        raw = e.read(250_000) if e.fp else b""
        return e.code, url, dict(e.headers) if e.headers else {}, raw
    except Exception as e:
        return -1, url, {"error": str(e)}, b""


def unpack_packer(html: str):
    results = []
    pat = re.compile(
        r"eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)",
        re.S,
    )
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    def to_base(num: int, radix: int) -> str:
        if num == 0:
            return "0"
        out = []
        while num > 0:
            num, rem = divmod(num, radix)
            out.append(alphabet[rem] if rem < len(alphabet) else chr(rem + 29))
        return "".join(reversed(out))

    for m in pat.finditer(html):
        p, a, c, k_raw = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
        k = k_raw.split("|")
        for idx in range(c - 1, -1, -1):
            if idx < len(k) and k[idx]:
                token = to_base(idx, a)
                p = re.sub(r"\b" + re.escape(token) + r"\b", lambda _, rep=k[idx]: rep, p)
        results.append(p)
    return results


HOSTS_TO_INSPECT = [
    ("vidaraa", "https://vidaraa.cc/e/Be3ITdmoRQ3BZ"),
    ("bysesukior", "https://bysesukior.com/e/kpkeynaql20a"),
    ("bysezejataos", "https://bysezejataos.com/e/fv0eqw5evxxr"),
    ("playmogo", "https://playmogo.com/e/vzhp9e9ds8fn"),
    ("voe", "https://voe.sx/e/msoc3s1jjyun"),
    ("mixdrop_top", "https://mixdrop.top/e/366p6ze7f7078e"),
    ("hgcloud", "https://hgcloud.to/e/lfdg89s3aq1d"),
    ("hgplaycdn", "https://hgplaycdn.com/e/of4b3jumbpoz"),
    ("vk_ru", "https://vk.ru/video_ext.php?oid=-242517679&id=456239111"),
    ("mp4upload", "https://www.mp4upload.com/embed-4nnn54jxdheb.html"),
    ("test_stream_worker", "https://test-stream.developer-pro.workers.dev/video?url=17232018016895"),
    ("google_photos_frieren", "https://photos.google.com/share/AF1QipNFgy_b8yYEe61yXcMlaMYTag0wAUGxaPwnVP0VCc2EVO0mnk3JL8baapuiH9zVFA/photo/AF1QipMnDLVMjEFHUICsCtqKFtKwOtaoeTTW2bzbZd2M?key=d2xTWlc5UXQ2N0hQSXZMQmJjcFBTV0dEU2IwT2x3"),
    ("hyperwatching", "https://v2.hyperwatching.com/watch/Xt9UhPK5WftV"),
    ("lulustream", "https://lulustream.com/e/qgi8iyjeub4h"),
    ("savefiles", "https://savefiles.com/e/7q3kihype5kp"),
    ("stardima_mp4", "https://stardima.worldnow.top/Kaijudo%3A%20Rise%20of%20the%20Duel%20Masters%20%282012%29%20sub/Kaijudo%20Rise%20of%20the%20Duel%20Masters%20S01/S01e01.mp4"),
    ("anime3rb", "https://anime3rb.com/episode/hunter-x-hunter-pilot/1"),
    ("pixeldrain", "https://pixeldrain.com/u/owDszUEX"),
    ("mediafire", "https://www.mediafire.com/file/cgf69g5r3f0vf4e/Hunter+x+Hunter+Pilot+480p.mp4/file"),
    ("tuktuk_upns", "https://tuktuk.upns.one/#k8kkqt"),
    ("tuktuk_p2p", "https://tuktuk.p2pstream.vip/#ebj1pu"),
    ("megatuktuk", "https://megatuktuk.store/iframe/lSdsCjdDS6KyO"),
    ("goodstream", "https://goodstream.one/embed-nj4f1tlufl5c.html"),
    ("minochinos", "https://minochinos.com/embed/ktug9jaul7e4"),
]


def inspect_one(item):
    label, url = item
    out = {"label": label, "url": url}
    if label == "stardima_mp4":
        st, fu, rh, raw = http_req(url, headers={"User-Agent": BROWSER_UA, "Range": "bytes=0-1023"}, timeout=12, max_bytes=2048)
        out["status"] = st
        out["ct"] = rh.get("Content-Type") or rh.get("content-type")
        out["ftyp"] = len(raw) >= 8 and raw[4:8] == b"ftyp"
        return out
    if label == "pixeldrain":
        api_u = "https://pixeldrain.com/api/file/owDszUEX"
        st, fu, rh, raw = http_req(api_u, headers={"User-Agent": BROWSER_UA, "Range": "bytes=0-1023"}, timeout=12, max_bytes=2048)
        out["api_url"] = api_u
        out["status"] = st
        out["ct"] = rh.get("Content-Type") or rh.get("content-type")
        out["ftyp"] = len(raw) >= 8 and raw[4:8] == b"ftyp"
        return out

    st, final_url, rh, raw = http_req(url, headers={"User-Agent": BROWSER_UA, "Accept": "text/html,*/*"}, timeout=14)
    ct = rh.get("Content-Type") or rh.get("content-type") or ""
    html = raw.decode("utf-8", errors="replace")
    out["status"] = st
    out["final_url"] = final_url
    out["ct"] = ct
    out["len"] = len(html)

    if label == "google_photos_frieren":
        clean = html.replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/")
        gurls = list(dict.fromkeys(re.findall(r"https://[a-zA-Z0-9._-]*googlevideo\.com/videoplayback[^\s\"'\\<>)}\]]*", clean)))
        lh3 = list(dict.fromkeys(re.findall(r"https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]{20,}", clean)))
        out["googlevideo_count"] = len(gurls)
        out["lh3_count"] = len(lh3)
        out["lh3_sample"] = lh3[:2]
        # Test lh3 =m37 / =m22 / =m18 (like AnimeDay/CimaCloud!) AND googlevideo =m37 / =m22 / =m18
        tests = []
        for base in lh3[:1] + [re.sub(r"=[^=?&\s\"'\\]+(?:\?[^\s\"'\\]*)?$", "", g) for g in gurls[:1]]:
            for sfx in ("=m37", "=m22", "=m18"):
                pst, pfu, prh, praw = http_req(
                    base + sfx,
                    headers={"User-Agent": BROWSER_UA, "Referer": "https://photos.google.com/", "Origin": "https://photos.google.com", "Range": "bytes=0-1023"},
                    timeout=10,
                    max_bytes=2048,
                )
                tests.append({"base_kind": "lh3" if "lh3." in base else "gv", "sfx": sfx, "status": pst, "ct": prh.get("Content-Type") or prh.get("content-type"), "ftyp": len(praw) >= 8 and praw[4:8] == b"ftyp"})
        out["stream_tests"] = tests
        return out

    # Test PHP extractors: GET extractor.php AND POST advanced_extractor.php + html_extractor.php with (url, html)
    st_ex, _, _, raw_ex = http_req(
        f"https://cloud-day.online/cimacloud/extractor.php?url={urllib.parse.quote(url, safe='')}",
        headers={"User-Agent": APP_UA},
        timeout=12,
    )
    st_adv, _, _, raw_adv = http_req(
        "https://cloud-day.online/cimacloud/advanced_extractor.php",
        method="POST",
        headers={"User-Agent": APP_UA},
        data={"url": final_url, "html": html[:250_000]},
        timeout=12,
    )
    st_htm, _, _, raw_htm = http_req(
        "https://cloud-day.online/cimacloud/html_extractor.php",
        method="POST",
        headers={"User-Agent": APP_UA},
        data={"url": final_url, "html": html[:250_000]},
        timeout=12,
    )
    for kname, rbytes in (("php_get", raw_ex), ("php_adv_post", raw_adv), ("php_html_post", raw_htm)):
        try:
            out[kname] = json.loads(rbytes.decode("utf-8", errors="replace"))
        except Exception:
            out[kname] = rbytes[:200].decode("utf-8", errors="replace")

    unpacked = unpack_packer(html)
    out["packer_count"] = len(unpacked)
    out["unpacked_snippets"] = [u[:500] for u in unpacked[:2]]

    # Interesting patterns in html
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", html, re.S | re.I)
    out["scripts_preview"] = [s.strip()[:600] for s in scripts if s.strip() and "jquery" not in s[:80].lower()][:5]
    out["script_srcs"] = re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', html, re.I)[:10]
    out["iframes"] = re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', html, re.I)[:8]
    out["urls_found"] = list(dict.fromkeys(re.findall(r'(?:https?:)?//[a-zA-Z0-9._/-]+(?:\.m3u8|\.mp4|\.mkv|master\.txt|/api/[^\s"\'<>]+|videoplayback[^\s"\'<>]*)[^\s"\'<>]*', html + "\n" + "\n".join(unpacked))))[:15]
    out["html_head"] = html[:900]

    # Follow VOE / HGCloud / Byse / Vidara / Playmogo / Hyperwatching sub-requests!
    if label == "voe":
        m = re.search(r"window\.location\.href\s*=\s*['\"](https?://[^'\"]+)['\"]", html)
        if m:
            st2, fu2, _, raw2 = http_req(m.group(1), headers={"User-Agent": BROWSER_UA, "Referer": url}, timeout=12)
            h2 = raw2.decode("utf-8", errors="replace")
            out["voe_redirect"] = {"url": fu2, "status": st2, "len": len(h2), "scripts": [s.strip()[:500] for s in re.findall(r"<script[^>]*>(.*?)</script>", h2, re.S | re.I) if s.strip()][:5]}
    if label in ("bysesukior", "bysezejataos"):
        code = url.rstrip("/").split("/")[-1]
        origin = f"{urllib.parse.urlparse(final_url).scheme}://{urllib.parse.urlparse(final_url).netloc}"
        api_u = f"{origin}/api/videos/{code}/embed/playback"
        st2, _, _, raw2 = http_req(api_u, headers={"User-Agent": BROWSER_UA, "Referer": final_url, "X-Requested-With": "XMLHttpRequest"}, timeout=12)
        out["byse_playback_api"] = {"url": api_u, "status": st2, "body": raw2[:800].decode("utf-8", errors="replace")}
    if label in ("hgcloud", "hgplaycdn"):
        code = url.rstrip("/").split("/")[-1]
        mirror = f"https://vibuxer.com/e/{code}"
        st2, fu2, _, raw2 = http_req(mirror, headers={"User-Agent": BROWSER_UA, "Referer": url}, timeout=12)
        h2 = raw2.decode("utf-8", errors="replace")
        up2 = unpack_packer(h2)
        out["vibuxer_mirror"] = {"status": st2, "len": len(h2), "packer": len(up2), "media": re.findall(r'https?://[^\s"\'<>]+(?:\.m3u8|master\.txt)[^\s"\'<>]*', "\n".join(up2))[:5]}
    return out


def verify_star_one_and_phony_streams():
    """Verify actual HTTP 200/206 media response on extracted Star One & Phony streams!"""
    # Re-fetch fresh tokens on 5 key embeds of Star One + Phony and check HTTP status of their .m3u8 / .mp4
    embeds = [
        ("star_one_stmruby", "https://stmruby.com/embed-b23ba4emjx99.html"),
        ("star_one_morencius", "https://morencius.com/v/u7d7v0b9ir88"),
        ("star_one_luluvdo", "https://luluvdo.com/e/c2pobfuk2dxi"),
        ("star_one_1vid", "https://1vid.xyz/embed-okjhdcy174kl.html"),
        ("star_one_vidmoly", "https://vidmoly.org/embed-6e34jsfg8va4.html"),
        ("star_one_vidspeed", "https://vidspeed.org/embed-7atido9w9sc7.html"),
        ("star_one_mp4plus", "https://mp4plus.org/embed-8i1mv9u55p9r.html"),
        ("star_one_uqload", "https://uqload.vc/embed-etlw0y8s7eu2.html"),
        ("phony_hanerix", "https://hanerix.com/e/ybnrleflifjt"),
    ]
    results = []
    for name, eurl in embeds:
        st, fu, _, raw = http_req(eurl, headers={"User-Agent": BROWSER_UA}, timeout=12)
        html = raw.decode("utf-8", errors="replace")
        blob = html + "\n" + "\n".join(unpack_packer(html))
        m3u8s = re.findall(r'https?://[^\s"\'<>\\]+(?:\.m3u8|\.mp4)[^\s"\'<>\\]*', blob)
        m3u8s = [u for u in m3u8s if not u.endswith((".js", ".css"))]
        if not m3u8s:
            results.append({"name": name, "found": False})
            continue
        target_media = m3u8s[0]
        origin = f"{urllib.parse.urlparse(fu).scheme}://{urllib.parse.urlparse(fu).netloc}"
        mst, _, mrh, mraw = http_req(
            target_media,
            headers={"User-Agent": BROWSER_UA, "Referer": origin + "/", "Origin": origin, "Range": "bytes=0-2047"},
            timeout=12,
            max_bytes=4096,
        )
        mhead = mraw[:200].decode("utf-8", errors="replace")
        results.append({
            "name": name,
            "media_url": target_media[:120],
            "status_with_origin_ref": mst,
            "ct": mrh.get("Content-Type") or mrh.get("content-type"),
            "valid": "#EXTM3U" in mhead or (len(mraw) >= 8 and mraw[4:8] == b"ftyp"),
        })
    return results


def main():
    out = {}
    ex = ThreadPoolExecutor(max_workers=8)
    try:
        futs = {ex.submit(inspect_one, item): item[0] for item in HOSTS_TO_INSPECT}
        hosts_res = {}
        for fut in as_completed(futs, timeout=120):
            lbl = futs[fut]
            try:
                hosts_res[lbl] = fut.result()
            except Exception as e:
                hosts_res[lbl] = {"error": str(e)}
        out["hosts"] = hosts_res
    finally:
        ex.shutdown(wait=False, cancel_futures=True)

    out["playback_verify"] = verify_star_one_and_phony_streams()
    with open(".tmp_verify/phase2_report.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"Phase 2c complete! Inspected {len(out['hosts'])} hosts + {len(out['playback_verify'])} playback checks.")


if __name__ == "__main__":
    main()
