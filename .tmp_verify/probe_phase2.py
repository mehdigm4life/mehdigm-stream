#!/usr/bin/env python3
"""
Phase 2d Surgical 6-Host JS/JSON Tail Inspection (20s):
1. vidaraa.cc/e/Be3ITdmoRQ3BZ -> full inline player script
2. bysesukior.com -> /assets/index-*.js around /embed/playback + POST test
3. tuktuk.upns.one & tuktuk.p2pstream.vip -> /assets/index-*.js around /api/v1/video & AES key
4. voe (derricknationtop.com/e/msoc3s1jjyun) -> <script type="application/json"> + decoder script
5. megatuktuk.store/iframe/lSdsCjdDS6KyO -> full Inertia JSON
6. v2.hyperwatching.com/watch/Xt9UhPK5WftV -> Inertia data-page JSON + server url test
"""
import html as htmllib
import json
import re
import ssl
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


def req(url, method="GET", headers=None, body=None, timeout=12):
    h = {"User-Agent": UA, **(headers or {})}
    r = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout, context=SSL_CTX) as resp:
            return resp.status, resp.geturl(), dict(resp.headers), resp.read(2_000_000)
    except urllib.error.HTTPError as e:
        return e.code, url, dict(e.headers or {}), e.read(300_000) if e.fp else b""
    except Exception as e:
        return -1, url, {"err": str(e)}, b""


out = {}

# 1. vidaraa
st, fu, _, raw = req("https://vidaraa.cc/e/Be3ITdmoRQ3BZ")
h = raw.decode("utf-8", errors="replace")
scripts = re.findall(r"<script[^>]*>(.*?)</script>", h, re.S | re.I)
player_sc = [s for s in scripts if "jwplayer" in s and "getFilecodeFromURL" in s]
out["vidaraa_script"] = player_sc[0][:6000] if player_sc else ""

# 2. bysesukior
st, _, _, raw = req("https://bysesukior.com/e/kpkeynaql20a")
h = raw.decode("utf-8", errors="replace")
m_js = re.search(r'src="(/assets/index-[^"]+\.js)"', h)
byse_info = {}
if m_js:
    _, _, _, jraw = req("https://bysesukior.com" + m_js.group(1))
    jtxt = jraw.decode("utf-8", errors="replace")
    hits = []
    for kw in ("playback", "embed", "/api/", "decrypt", "subtle"):
        for m in re.finditer(re.escape(kw), jtxt):
            s = max(0, m.start() - 200)
            e = min(len(jtxt), m.end() + 400)
            hits.append(jtxt[s:e])
            if len(hits) >= 10:
                break
    byse_info["js_hits"] = hits[:10]
st_p, _, _, praw = req(
    "https://bysesukior.com/api/videos/kpkeynaql20a/embed/playback",
    method="POST",
    headers={"Referer": "https://bysesukior.com/e/kpkeynaql20a", "Origin": "https://bysesukior.com", "Content-Type": "application/json"},
    body=b"{}",
)
byse_info["post_empty"] = {"status": st_p, "body": praw[:1000].decode("utf-8", errors="replace")}
st_d, _, _, draw = req(
    "https://bysesukior.com/api/videos/kpkeynaql20a/embed/details",
    headers={"Referer": "https://bysesukior.com/e/kpkeynaql20a"},
)
byse_info["get_details"] = {"status": st_d, "body": draw[:1000].decode("utf-8", errors="replace")}
out["bysesukior"] = byse_info

# 3. tuktuk.upns.one
st, _, _, raw = req("https://tuktuk.upns.one/#k8kkqt")
h = raw.decode("utf-8", errors="replace")
m_js = re.search(r'src="(/assets/index-[^"]+\.js)"', h)
upns_info = {}
if m_js:
    _, _, _, jraw = req("https://tuktuk.upns.one" + m_js.group(1))
    jtxt = jraw.decode("utf-8", errors="replace")
    hits = []
    for kw in ("/api/v1/", "AES-CBC", "decrypt", "importKey", "video?id="):
        for m in re.finditer(re.escape(kw), jtxt):
            s = max(0, m.start() - 250)
            e = min(len(jtxt), m.end() + 450)
            hits.append(jtxt[s:e])
            if len(hits) >= 8:
                break
    upns_info["js_hits"] = hits[:8]
st_v, _, _, vraw = req("https://tuktuk.upns.one/api/v1/video?id=k8kkqt&w=1920&h=1080&r=", headers={"Referer": "https://tuktuk.upns.one/"})
upns_info["api_video"] = {"status": st_v, "len": len(vraw), "sample": vraw[:300].decode("utf-8", errors="replace")}
out["tuktuk_upns"] = upns_info

# 4. voe redirect
st, _, _, raw = req("https://voe.sx/e/msoc3s1jjyun")
h = raw.decode("utf-8", errors="replace")
m_r = re.search(r"window\.location\.href\s*=\s*['\"](https?://[^'\"]+)['\"]", h)
voe_info = {}
if m_r:
    _, fu2, _, raw2 = req(m_r.group(1), headers={"Referer": "https://voe.sx/"})
    h2 = raw2.decode("utf-8", errors="replace")
    app_json = re.findall(r'<script[^>]*type=["\']application/json["\'][^>]*>(.*?)</script>', h2, re.S | re.I)
    dec_sc = [s for s in re.findall(r"<script[^>]*>(.*?)</script>", h2, re.S | re.I) if "application/json" in s or "atob" in s or "charCodeAt" in s]
    voe_info["redirect_url"] = fu2
    voe_info["app_json_count"] = len(app_json)
    voe_info["app_json_sample"] = app_json[0][:400] if app_json else ""
    voe_info["decoder_scripts"] = [s[:2000] for s in dec_sc[-2:]]
out["voe"] = voe_info

# 5. megatuktuk
st, _, _, raw = req("https://megatuktuk.store/iframe/lSdsCjdDS6KyO")
h = raw.decode("utf-8", errors="replace")
sc0 = re.findall(r"<script[^>]*>(.*?)</script>", h, re.S | re.I)
mega_json = [s.strip() for s in sc0 if '"component"' in s]
dp = re.findall(r'data-page="([^"]+)"', h)
out["megatuktuk"] = {
    "script_json": mega_json[0][:4000] if mega_json else "",
    "data_page": htmllib.unescape(dp[0])[:4000] if dp else "",
}

# 6. hyperwatching
st, _, rh, raw = req("https://v2.hyperwatching.com/watch/Xt9UhPK5WftV")
h = raw.decode("utf-8", errors="replace")
dp = re.findall(r'data-page="([^"]+)"', h)
hw_page = htmllib.unescape(dp[0]) if dp else ""
hw_info = {"data_page": hw_page[:4000]}
try:
    pj = json.loads(hw_page)
    servers = pj.get("props", {}).get("video", {}).get("links") or pj.get("props", {}).get("servers") or pj.get("props", {})
    hw_info["props_keys"] = list(pj.get("props", {}).keys())
except Exception as e:
    hw_info["parse_err"] = str(e)
# Also test /stream/proxy/Xt9UhPK5WftV
st_sp, fu_sp, rh_sp, raw_sp = req(
    "https://v2.hyperwatching.com/stream/proxy/Xt9UhPK5WftV",
    headers={"Referer": "https://v2.hyperwatching.com/watch/Xt9UhPK5WftV", "Range": "bytes=0-1023"},
)
hw_info["stream_proxy"] = {"status": st_sp, "ct": rh_sp.get("Content-Type") or rh_sp.get("content-type"), "sample": raw_sp[:200].decode("utf-8", errors="replace")}
out["hyperwatching"] = hw_info

with open(".tmp_verify/phase2_report.json", "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print("Phase 2d surgical complete!")
