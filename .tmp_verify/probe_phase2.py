#!/usr/bin/env python3
import base64
import concurrent.futures
import hashlib
import json
import os
import random
import re
import string
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

API_BASE = "https://1654865.xyz/v1.3/api"
CONFIG_URL = "https://config-backup2.animegateshorts.workers.dev/"
UA = "okhttp/4.10.0"
BROWSER_UA = (
    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
)

NODE_CRYPTO_JS = r"""
const crypto = require('crypto');
const mode = process.argv[1];
const arg1 = process.argv[2] || '';
const arg2 = process.argv[3] || '';

const CERT_SHA256 = '021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7';
const PKG = 'com.app.cimacloud';
const LIB_SHA256 = 'edac3036c45d18168ab43fd95d1d5ac9d0687547efe6414800bd0457caae43fb';
const HMAC_KEY = '97a54e5dec15b4f1754abc5939643bbe57a0d8ae8db88529e268289457728002';
const PUBKEY_B64 = 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6+BtHI/Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3QCh65Nbi4mKRFFJyDkJVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9FtgJkuz17n265rLB6ruUUrHQVeEDl8ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvsq4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IEYV47OyddG32qbriJbxdvW+n5OzowiYQrsC8hG65GPBGCuj3zO7wIDAQAB';
const RESP_SALT = 'Gm4il@G00Gl3.Com';

if (mode === 'build') {
  const apkSize = arg1 || '28549120';
  const dField = apkSize === 'err' ? 'empty' : 'cache,code_cache,files,shared_prefs';
  const hmacMsg = `${apkSize}|${CERT_SHA256}|000|${LIB_SHA256}`;
  const m = crypto.createHmac('sha256', HMAC_KEY).update(hmacMsg).digest('hex');
  const ts = Math.floor(Date.now() / 1000);
  const rand16 = crypto.randomBytes(16);
  let nonce = '';
  for (let i = 0; i < 16; i++) nonce += (rand16[i] & 0x0f).toString(16);
  const bField = `${CERT_SHA256}|${PKG}`;
  const payload = `{"a":"${apkSize}","b":"${bField}","c":"false","d":"${dField}","e":"${dField}","f":"false","g":"false","h":"false","i":"false","j":"false","r":"false","s":"${LIB_SHA256}","m":"${m}","t":${ts},"n":"${nonce}"}`;
  const aesKey = crypto.randomBytes(32);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', aesKey, iv);
  const ct = Buffer.concat([cipher.update(payload, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  const pubKey = crypto.createPublicKey({ key: Buffer.from(PUBKEY_B64, 'base64'), format: 'der', type: 'spki' });
  const encKey = crypto.publicEncrypt({ key: pubKey, padding: crypto.constants.RSA_PKCS1_PADDING }, aesKey);
  process.stdout.write(`${encKey.toString('base64')}.${iv.toString('base64')}.${ct.toString('base64')}`);
} else if (mode === 'decrypt') {
  const ct = Buffer.from(arg1.trim(), 'base64');
  const aesKey = Buffer.from(crypto.createHash('md5').update(RESP_SALT + arg2).digest('hex'), 'ascii');
  const iv = Buffer.from(crypto.createHash('md5').update(arg2 + RESP_SALT).digest('hex').slice(0, 16), 'ascii');
  const decipher = crypto.createDecipheriv('aes-256-cbc', aesKey, iv);
  const pt = Buffer.concat([decipher.update(ct), decipher.final()]);
  process.stdout.write(pt.toString('utf8'));
}
"""


def build_secure(apk_size="28549120"):
    return subprocess.check_output(["node", "-e", NODE_CRYPTO_JS, "build", apk_size], timeout=10).decode()


def decrypt_response(b64_data, key_arg):
    return subprocess.check_output(["node", "-e", NODE_CRYPTO_JS, "decrypt", b64_data, key_arg], timeout=10).decode()


def rand_alnum(n):
    chars = string.ascii_letters + string.digits
    return "".join(random.choice(chars) for _ in range(n))


def rand_hex(n):
    return "".join(random.choice("0123456789abcdef") for _ in range(n))


def make_auth_headers(apk_size="28549120"):
    android_id = rand_hex(16)
    cf = rand_alnum(15) + android_id
    fb = build_secure(apk_size)
    return {
        "User-Agent": UA,
        "Accept": "application/json, text/plain, */*",
        "cloudflare-id": cf,
        "firebase_id": fb,
    }, android_id


def http_req(url, method="GET", headers=None, data=None, timeout=15, max_bytes=262144):
    hdrs = dict(headers or {"User-Agent": UA, "Accept": "application/json, text/plain, */*"})
    body = None
    if data is not None:
        if isinstance(data, dict):
            body = urllib.parse.urlencode(data).encode()
            hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
        elif isinstance(data, bytes):
            body = data
        else:
            body = str(data).encode()
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(max_bytes)
            return {
                "ok": True,
                "status": resp.status,
                "final_url": resp.geturl(),
                "content_type": resp.headers.get("Content-Type", ""),
                "content_length": resp.headers.get("Content-Length", ""),
                "text": raw.decode("utf-8", "replace"),
                "raw_bytes": raw,
            }
    except urllib.error.HTTPError as e:
        raw = b""
        try:
            raw = e.read(65536)
        except Exception:
            pass
        return {
            "ok": False,
            "status": e.code,
            "final_url": url,
            "content_type": e.headers.get("Content-Type", "") if e.headers else "",
            "text": raw.decode("utf-8", "replace"),
            "raw_bytes": raw,
        }
    except Exception as e:
        return {"ok": False, "status": -1, "final_url": url, "error": str(e), "text": "", "raw_bytes": b""}


def fetch_secure(path, apk_size="28549120"):
    hdrs, key_arg = make_auth_headers(apk_size)
    r = http_req(f"{API_BASE}{path}", headers=hdrs, timeout=15)
    txt = r["text"].strip()
    decrypted = None
    dec_err = None
    if r["status"] == 200 and txt:
        if txt.startswith("{") or txt.startswith("["):
            decrypted = txt
        else:
            try:
                decrypted = decrypt_response(txt, key_arg)
            except Exception as e:
                dec_err = str(e)
    parsed = None
    if decrypted:
        try:
            parsed = json.loads(decrypted)
        except Exception as e:
            dec_err = f"json_err: {e}"
    return {
        "status": r["status"],
        "encrypted_len": len(txt),
        "raw_preview": txt[:160],
        "decrypt_error": dec_err,
        "json": parsed,
    }


def probe_single_server(srv, php_endpoints):
    link = (srv.get("link") or srv.get("url") or "").strip()
    referer = (srv.get("referer") or srv.get("origin") or "https://cima-cloud.com/").strip()
    info = {
        "server_meta": srv,
        "link": link,
        "direct_probe": None,
        "php_results": {},
    }
    if not link:
        return info

    # 1. Direct probe (Range 0-32767 with browser UA + Referer)
    is_gp = ("googleusercontent.com" in link) or ("photos.google.com" in link) or ("photos.app.goo.gl" in link)
    probe_hdrs = {
        "User-Agent": BROWSER_UA,
        "Accept": "*/*",
        "Referer": "https://photos.google.com/" if is_gp else referer,
    }
    if is_gp:
        probe_hdrs["Origin"] = "https://photos.google.com"
    probe_hdrs["Range"] = "bytes=0-32767"
    dr = http_req(link, headers=probe_hdrs, timeout=12, max_bytes=32768)
    info["direct_probe"] = {
        "status": dr["status"],
        "final_url": dr.get("final_url"),
        "content_type": dr.get("content_type"),
        "content_length": dr.get("content_length"),
        "head_text": dr["text"][:600] if ("html" in dr.get("content_type", "").lower() or "mpegurl" in dr.get("content_type", "").lower() or "json" in dr.get("content_type", "").lower() or dr["text"].lstrip().startswith(("<", "{", "#EXTM3U"))) else f"<binary {len(dr['raw_bytes'])} bytes>",
    }

    # If HTML embed/download page, fetch full HTML (up to 160 KB) without Range header to inspect iframes/scripts/forms!
    ct_low = dr.get("content_type", "").lower()
    if "html" in ct_low or dr["text"].lstrip().startswith("<"):
        full_r = http_req(
            link,
            headers={
                "User-Agent": BROWSER_UA,
                "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
                "Accept-Language": "ar,en;q=0.9",
                "Referer": referer,
            },
            timeout=12,
            max_bytes=163840,
        )
        html = full_r["text"]
        info["direct_probe"]["full_html_len"] = len(html)
        info["direct_probe"]["iframes"] = re.findall(r'(?i)<iframe[^>]+(?:src|data-src)=["\']([^"\']+)["\']', html)[:10]
        info["direct_probe"]["anchors_dl"] = re.findall(r'(?i)<a[^>]+href=["\']([^"\']+)["\']', html)[:20]
        info["direct_probe"]["m3u8_mp4_matches"] = re.findall(r'https?://[^"\'\s<>\\]+(?:\.m3u8|\.mp4|\.mkv|master\.txt|/pw/)[^"\'\s<>\\]*', html)[:15]
        info["direct_probe"]["has_packer"] = "eval(function(p,a,c,k,e," in html
        info["direct_probe"]["html_snippet"] = html[:1200]

    # If Google Photos, also test =m37, =m22, =m18 and DASH
    pw_m = re.search(r"https://lh3\.googleusercontent\.com/pw/[^=?\"'\s\\]+", link)
    if not pw_m and "html_snippet" in info["direct_probe"]:
        pw_m = re.search(r"https://lh3\.googleusercontent\.com/pw/[^=?\"'\s\\]+", full_r["text"])
    if pw_m:
        gp_base = pw_m.group(0)
        gp_variants = {}
        for suf in ("=m37", "=m22", "=m18", "=mm,dash-vm-vf,dr.sdr,sdrCodec.vp9.h264?alr=true&mpd_version=5&pacing=0"):
            gr = http_req(
                gp_base + suf,
                headers={
                    "User-Agent": BROWSER_UA,
                    "Referer": "https://photos.google.com/",
                    "Origin": "https://photos.google.com",
                    "Range": "bytes=0-65535",
                },
                timeout=10,
                max_bytes=65536,
            )
            gp_variants[suf[:12]] = {
                "status": gr["status"],
                "content_type": gr.get("content_type"),
                "content_length": gr.get("content_length"),
                "final_url_host": urllib.parse.urlparse(gr.get("final_url", "")).netloc,
                "bytes_read": len(gr["raw_bytes"]),
            }
        info["gphotos_variants"] = {"base": gp_base[:80] + "...", "variants": gp_variants}

    # 2. Test all 3 PHP extractor endpoints
    for name, ep in php_endpoints.items():
        qurl = f"{ep}?url={urllib.parse.quote(link, safe='')}"
        pr = http_req(
            qurl,
            headers={"User-Agent": UA, "Accept": "application/json, text/plain, */*", "Referer": referer},
            timeout=12,
            max_bytes=65536,
        )
        ptxt = pr["text"].strip()
        pjson = None
        try:
            if ptxt.startswith("{"):
                pjson = json.loads(ptxt)
        except Exception:
            pass
        info["php_results"][name] = {
            "status": pr["status"],
            "json": pjson if pjson is not None else ptxt[:300],
        }
    return info


def main():
    report = {"timestamp": int(time.time())}

    # 0. Config check
    cfg_r = http_req(CONFIG_URL, timeout=10)
    cfg_json = json.loads(cfg_r["text"]) if cfg_r["ok"] else {}
    report["config"] = cfg_json
    cfg_item = (cfg_json.get("data") or [{}])[0] if isinstance(cfg_json.get("data"), list) else {}
    php_endpoints = {
        "extractor": cfg_item.get("extractorUrl", "https://cloud-day.online/cimacloud/extractor.php"),
        "html_extractor": cfg_item.get("htmlExtractorUrl", "https://cloud-day.online/cimacloud/html_extractor.php"),
        "advanced_extractor": cfg_item.get("advancedExtractorUrl", "https://cloud-day.online/cimacloud/advanced_extractor.php"),
    }

    # 1. Verify buildSecure with clean apk_size="28549120" vs "err"
    report["auth_variant_check"] = {
        "clean_28549120": fetch_secure("/movie/27295", apk_size="28549120")["status"],
        "legacy_err": fetch_secure("/movie/27295", apk_size="err")["status"],
    }

    # 2. Fetch Home Page & verify Phony in "احدث المسلسلات" and Star One in "احدث الأفلام"
    home_r = http_req(f"{API_BASE}/home", timeout=15)
    home_json = json.loads(home_r["text"])
    home_summary = []
    phony_in_home = None
    star_one_in_home = None
    extra_samples = []
    for sec in home_json.get("sections", []):
        sname = sec.get("section_name", "")
        items = sec.get("section_items", [])
        home_summary.append({
            "section_id": sec.get("section_id"),
            "section_name": sname,
            "section_type": sec.get("section_type"),
            "count": len(items),
            "sample_types": list({it.get("type") for it in items}),
        })
        for it in items:
            nm = it.get("name", "")
            if "Phony" in nm and "المسلسلات" in sname:
                phony_in_home = {"section": sname, "item": it}
            if "Star One" in nm and "الأفلام" in sname:
                star_one_in_home = {"section": sname, "item": it}
        if any(k in sname for k in ("التركية", "الكورية", "الهندية", "الآسيوية")) and items:
            it0 = items[0]
            extra_samples.append({"section": sname, "id": it0.get("id"), "type": it0.get("type"), "name": it0.get("name")})

    report["home_summary"] = home_summary
    report["phony_in_home"] = phony_in_home
    report["star_one_in_home"] = star_one_in_home

    # 3. Target details + decrypted /servers
    targets = {
        "star_one_movie_27295": {
            "detail": fetch_secure("/movie/27295"),
            "detail_no_token": http_req(f"{API_BASE}/movie/27295")["status"],
            "servers": fetch_secure("/movie/27295/servers"),
        },
        "phony_series_12827": {
            "detail": fetch_secure("/series/12827"),
            "detail_no_token": http_req(f"{API_BASE}/series/12827")["status"],
            "episodes": json.loads(http_req(f"{API_BASE}/series/12827/episodes")["text"]),
            "ep1_370096_detail": fetch_secure("/episode/370096"),
            "ep1_370096_servers": fetch_secure("/episode/370096/servers"),
            "ep2_370097_servers": fetch_secure("/episode/370097/servers"),
        },
        "anime_series_frieren_12819": {
            "detail": fetch_secure("/series/12819"),
            "ep1_369392_servers": fetch_secure("/episode/369392/servers"),
        },
        "cartoon_series_kaijudo_12830": {
            "detail": fetch_secure("/series/12830"),
            "ep1_370424_servers": fetch_secure("/episode/370424/servers"),
        },
        "anime_movie_hxh_27291": {
            "detail": fetch_secure("/movie/27291"),
            "servers": fetch_secure("/movie/27291/servers"),
        },
        "anime_movie_bluelock_27206": {
            "detail": fetch_secure("/movie/27206"),
            "servers": fetch_secure("/movie/27206/servers"),
        },
        "cartoon_movie_toystory5_27040": {
            "detail": fetch_secure("/movie/27040"),
            "servers": fetch_secure("/movie/27040/servers"),
        },
    }
    for ex in extra_samples[:3]:
        tid = ex["id"]
        ttype = ex["type"]
        key = f"extra_{ttype}_{tid}"
        if ttype == "movie":
            targets[key] = {"name": ex["name"], "servers": fetch_secure(f"/movie/{tid}/servers")}
        else:
            eps_j = json.loads(http_req(f"{API_BASE}/series/{tid}/episodes")["text"])
            seasons = eps_j.get("seasons") or []
            ep_id = seasons[0]["episodes"][0]["id"] if seasons and seasons[0].get("episodes") else None
            targets[key] = {
                "name": ex["name"],
                "ep_id": ep_id,
                "servers": fetch_secure(f"/episode/{ep_id}/servers") if ep_id else None,
            }
    report["targets"] = targets

    # 4. Catalog & section pagination probe
    catalog_probe = {"movies_categories": {}, "series_categories": {}, "sections_auth": {}}
    for cid in range(1, 16):
        rm = http_req(f"{API_BASE}/movies", method="POST", data={"category": str(cid), "page": "1"})
        if rm["status"] == 200:
            jm = json.loads(rm["text"])
            if jm.get("total", 0):
                catalog_probe["movies_categories"][str(cid)] = {
                    "total": jm.get("total"),
                    "total_pages": jm.get("total_pages"),
                    "sample_cats": (jm.get("data") or [{}])[0].get("categories"),
                    "sample_name": (jm.get("data") or [{}])[0].get("name"),
                }
        rs = http_req(f"{API_BASE}/series", method="POST", data={"category": str(cid), "page": "1"})
        if rs["status"] == 200:
            js = json.loads(rs["text"])
            if js.get("total", 0):
                catalog_probe["series_categories"][str(cid)] = {
                    "total": js.get("total"),
                    "total_pages": js.get("total_pages"),
                    "sample_cats": (js.get("data") or [{}])[0].get("categories"),
                    "sample_name": (js.get("data") or [{}])[0].get("name"),
                }

    # Verify page 1 vs page 2 on POST /movies (category=6) and POST /series (category=5) and POST /search
    m_p1 = json.loads(http_req(f"{API_BASE}/movies", method="POST", data={"category": "6", "page": "1"})["text"])
    m_p2 = json.loads(http_req(f"{API_BASE}/movies", method="POST", data={"category": "6", "page": "2"})["text"])
    s_p1 = json.loads(http_req(f"{API_BASE}/series", method="POST", data={"category": "5", "page": "1"})["text"])
    s_p2 = json.loads(http_req(f"{API_BASE}/series", method="POST", data={"category": "5", "page": "2"})["text"])
    catalog_probe["pagination_verify"] = {
        "movies_cat6_p1_ids": [x.get("id") for x in (m_p1.get("data") or [])],
        "movies_cat6_p2_ids": [x.get("id") for x in (m_p2.get("data") or [])],
        "series_cat5_p1_ids": [x.get("id") for x in (s_p1.get("data") or [])],
        "series_cat5_p2_ids": [x.get("id") for x in (s_p2.get("data") or [])],
    }

    # Check GET /section/{1..10}?page=1 with auth headers & POST /section/{1..10}
    for sid in range(1, 11):
        sec_auth = fetch_secure(f"/section/{sid}?page=1")
        sec_j = sec_auth.get("json") or {}
        catalog_probe["sections_auth"][str(sid)] = {
            "status": sec_auth["status"],
            "section_name": sec_j.get("section_name"),
            "section_type": sec_j.get("section_type"),
            "has_more": sec_j.get("has_more"),
            "items_count": len(sec_j.get("section_items") or []),
        }
    # Check latest episodes endpoint possibilities
    latest_ep_probes = {}
    for ep_path in ("/episodes?page=1", "/latest-episodes?page=1", "/section/2?page=1", "/section/2?page=2"):
        r_ep = fetch_secure(ep_path)
        latest_ep_probes[ep_path] = {
            "status": r_ep["status"],
            "keys": list((r_ep["json"] or {}).keys()) if isinstance(r_ep.get("json"), dict) else None,
            "count": len((r_ep.get("json") or {}).get("section_items") or (r_ep.get("json") or {}).get("data") or []),
        }
    catalog_probe["latest_ep_probes"] = latest_ep_probes
    report["catalog_probe"] = catalog_probe

    # 5. Live-probe EVERY server returned by Star One (27295), Phony Ep 1 & Ep 2 (370096, 370097), plus unique hosts from Anime/Cartoon!
    servers_to_probe = []
    seen_links = set()

    def collect_servers(label, srv_resp):
        j = (srv_resp or {}).get("json") or {}
        for k, v in j.items():
            if isinstance(v, list):
                for item in v:
                    if isinstance(item, dict):
                        u = (item.get("link") or item.get("url") or "").strip()
                        if u and u not in seen_links:
                            seen_links.add(u)
                            servers_to_probe.append((label, k, item))

    collect_servers("StarOne_27295", targets["star_one_movie_27295"]["servers"])
    collect_servers("Phony_Ep1_370096", targets["phony_series_12827"]["ep1_370096_servers"])
    collect_servers("Phony_Ep2_370097", targets["phony_series_12827"]["ep2_370097_servers"])

    # For other targets, collect up to 2 servers per unique hostname so we cover every host without probing 100 identical links
    seen_hosts = {urllib.parse.urlparse(x[2].get("link") or x[2].get("url") or "").netloc for x in servers_to_probe}
    host_counts = {}
    for tname, tval in targets.items():
        if tname in ("star_one_movie_27295", "phony_series_12827"):
            continue
        for subk, subv in tval.items():
            if isinstance(subv, dict) and "json" in subv and isinstance(subv["json"], dict):
                for arrk, arrv in subv["json"].items():
                    if isinstance(arrv, list):
                        for item in arrv:
                            if isinstance(item, dict):
                                u = (item.get("link") or item.get("url") or "").strip()
                                h = urllib.parse.urlparse(u).netloc
                                if u and u not in seen_links and host_counts.get(h, 0) < 2:
                                    seen_links.add(u)
                                    host_counts[h] = host_counts.get(h, 0) + 1
                                    servers_to_probe.append((f"{tname}:{subk}", arrk, item))

    probed_servers = []
    ex = concurrent.futures.ThreadPoolExecutor(max_workers=10)
    try:
        futs = {
            ex.submit(probe_single_server, item, php_endpoints): (label, arrk)
            for (label, arrk, item) in servers_to_probe
        }
        done, not_done = concurrent.futures.wait(futs.keys(), timeout=120)
        for fut in done:
            label, arrk = futs[fut]
            try:
                res = fut.result()
                res["source_target"] = label
                res["source_array"] = arrk
                probed_servers.append(res)
            except Exception as e:
                probed_servers.append({"source_target": label, "error": str(e)})
        for fut in not_done:
            fut.cancel()
    finally:
        ex.shutdown(wait=False, cancel_futures=True)

    report["probed_servers_count"] = len(probed_servers)
    report["probed_servers"] = probed_servers

    os.makedirs(".tmp_verify", exist_ok=True)
    with open(".tmp_verify/phase2_report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"Saved .tmp_verify/phase2_report.json ({os.path.getsize('.tmp_verify/phase2_report.json')} bytes), probed {len(probed_servers)} servers.")


if __name__ == "__main__":
    main()
