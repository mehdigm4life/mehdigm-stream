#!/usr/bin/env python3
"""
Phase 2b Live Verification for CimaCloud (with exact sourceDir + APK signing block fallback):
1. Tests buildSecure() with exact FakeContext values (`b = cert|/data/app/com.app.cimacloud/base.apk`,
   `a = "err"`, `s = "err_open"`, `d = "empty"`, `e = "empty"`) AND `d/e = cache,code_cache,files,shared_prefs`.
2. If the server rejects `err` / `err_open`, automatically fetches `https://www.cima-cloud.com`,
   locates the official APK download URL, extracts exact `apk_size` (`a`), `apk_sig_block_sha256` (`s`),
   and certificate SHA-256 (`cert`), and re-tests!
3. Using the winning attestation variant, probes:
   - Star One (`movie_id = 27295`)
   - Phony (`series_id = 12827`, S1 Ep 1 `370096` & Ep 2 `370097`)
   - Anime & Cartoon series/movies (`12819` Ep 1 `369392`, `12830` Ep 1 `370424`, `27291`, `27206`, `27040`)
   - All 3 PHP extractors + direct host URLs + Google Photos renditions + FaselHD / Packer embeds.
"""
import base64
import hashlib
import json
import os
import random
import re
import ssl
import string
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

CONFIG_URL = "https://config-backup2.animegateshorts.workers.dev/"
DEFAULT_API_BASE = "https://1654865.xyz/v1.3/api"
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
APP_UA = "okhttp/4.10.0"

CERT_HEX = "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7"
HMAC_KEY = "97a54e5dec15b4f1754abc5939643bbe57a0d8ae8db88529e268289457728002"
RSA_SPKI_B64 = (
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+"
    "6+BtHI/Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3QCh"
    "65Nbi4mKRFFJyDkJVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9FtgJkuz"
    "17n265rLB6ruUUrHQVeEDl8ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvsq4Lhuthct"
    "s6gtVq3uPExSKdLBpbqbwQNvhRgB3IEYV47OyddG32qbriJbxdvW+n5OzowiYQrsC8hG65GPBGCu"
    "j3zO7wIDAQAB"
)

NODE_CRYPTO_JS = r"""
const crypto = require('crypto');
const fs = require('fs');

const req = JSON.parse(fs.readFileSync(0, 'utf8'));
const cmd = req.cmd;

if (cmd === 'token') {
  const cert = req.cert || '__CERT__';
  const hmacKey = '__HMAC__';
  const pubPem = '-----BEGIN PUBLIC KEY-----\n' +
    '__RSA__'.match(/.{1,64}/g).join('\n') +
    '\n-----END PUBLIC KEY-----\n';
  const a = req.a;
  const b = req.b;
  const d = req.d;
  const e = req.e;
  const s = req.s;
  const flags = req.flags || '000';
  const msg = `${a}|${cert}|${flags}|${s}`;
  const m = crypto.createHmac('sha256', Buffer.from(hmacKey, 'utf8')).update(msg, 'utf8').digest('hex');
  const t = req.ts || Math.floor(Date.now() / 1000);
  const n = crypto.randomBytes(8).toString('hex');
  const plain = JSON.stringify({
    a, b, c: 'false', d, e,
    f: 'false', g: 'false', h: 'false', i: 'false', j: 'false', r: 'false',
    s, m, t, n
  });
  const aesKey = crypto.randomBytes(32);
  const iv = crypto.randomBytes(12);
  const gcm = crypto.createCipheriv('aes-256-gcm', aesKey, iv);
  const ct = Buffer.concat([gcm.update(plain, 'utf8'), gcm.final()]);
  const tag = gcm.getAuthTag();
  const sealed = Buffer.concat([ct, tag]);
  const encKey = crypto.publicEncrypt(
    { key: pubPem, padding: crypto.constants.RSA_PKCS1_PADDING },
    aesKey
  );
  process.stdout.write(`${encKey.toString('base64')}.${iv.toString('base64')}.${sealed.toString('base64')}`);
} else if (cmd === 'decrypt') {
  const data = req.data.trim();
  const key = req.key;
  const salt = 'Gm4il@G00Gl3.Com';
  const aesKey = Buffer.from(crypto.createHash('md5').update(salt + key, 'utf8').digest('hex').toLowerCase(), 'utf8');
  const iv = Buffer.from(crypto.createHash('md5').update(key + salt, 'utf8').digest('hex').toLowerCase().slice(0, 16), 'utf8');
  const raw = Buffer.from(data.replace(/\s+/g, ''), 'base64');
  const dec = crypto.createDecipheriv('aes-256-cbc', aesKey, iv);
  const out = Buffer.concat([dec.update(raw), dec.final()]);
  process.stdout.write(out.toString('utf8'));
}
""".replace("__CERT__", CERT_HEX).replace("__HMAC__", HMAC_KEY).replace("__RSA__", RSA_SPKI_B64)

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


def build_secure(params: dict) -> str:
    payload = {"cmd": "token", **params}
    p = subprocess.run(
        ["node", "-e", NODE_CRYPTO_JS],
        input=json.dumps(payload).encode(),
        capture_output=True,
        check=True,
        timeout=10,
    )
    return p.stdout.decode()


def decrypt_response(data: str, key: str) -> str:
    payload = {"cmd": "decrypt", "data": data, "key": key}
    p = subprocess.run(
        ["node", "-e", NODE_CRYPTO_JS],
        input=json.dumps(payload).encode(),
        capture_output=True,
        check=True,
        timeout=10,
    )
    return p.stdout.decode("utf-8", errors="replace")


def rand_alnum(n: int) -> str:
    chars = string.ascii_letters + string.digits
    return "".join(random.choice(chars) for _ in range(n))


def make_auth_headers(token_params: dict):
    android_id = "".join(random.choice("0123456789abcdef") for _ in range(16))
    cf = rand_alnum(15) + android_id
    fb = build_secure(token_params)
    headers = {
        "User-Agent": APP_UA,
        "Accept": "application/json, text/plain, */*",
        "cloudflare-id": cf,
        "firebase_id": fb,
    }
    return headers, android_id


def http_req(url: str, method: str = "GET", headers: dict = None, data: dict = None, timeout: int = 15, max_bytes: int = 2_000_000):
    hdrs = dict(headers or {})
    body_bytes = None
    if data is not None:
        body_bytes = urllib.parse.urlencode(data).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
    req = urllib.request.Request(url, data=body_bytes, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as resp:
            raw = resp.read(max_bytes)
            return resp.status, dict(resp.headers), raw
    except urllib.error.HTTPError as e:
        raw = e.read(300_000) if e.fp else b""
        return e.code, dict(e.headers) if e.headers else {}, raw
    except Exception as e:
        return -1, {"error": str(e)}, b""


def api_get_decrypted(api_base: str, path: str, token_params: dict):
    hdrs, key_arg = make_auth_headers(token_params)
    status, resp_hdrs, raw = http_req(f"{api_base}{path}", headers=hdrs, timeout=15)
    txt = raw.decode("utf-8", errors="replace").strip()
    result = {
        "status": status,
        "encrypted_len": len(txt),
        "raw_preview": txt[:160],
        "decrypt_error": None,
        "json": None,
    }
    if not txt:
        return result
    if txt.startswith("{") or txt.startswith("["):
        try:
            result["json"] = json.loads(txt)
            return result
        except Exception:
            pass
    try:
        dec = decrypt_response(txt, key_arg)
        result["decrypted_preview"] = dec[:300]
        result["json"] = json.loads(dec)
    except Exception as e:
        result["decrypt_error"] = str(e)
    return result


def parse_apk_sig_block(apk_bytes: bytes):
    """Exact replica of libnative-lib.so 0x2375bc..0x23c838 APK Signing Block v2/v3 SHA-256."""
    n = len(apk_bytes)
    tail_len = min(n, 0x10015)
    tail = apk_bytes[n - tail_len:]
    eocd_pos = tail.rfind(b"PK\x05\x06")
    if eocd_pos < 0:
        return None
    eocd = tail[eocd_pos:eocd_pos + 22]
    cd_offset = struct.unpack_from("<I", eocd, 16)[0]
    if cd_offset < 24 or cd_offset > n:
        return None
    footer = apk_bytes[cd_offset - 24:cd_offset]
    size_in_footer, magic = struct.unpack("<Q16s", footer)
    if magic != b"APK Sig Block 42":
        return None
    total_block_len = size_in_footer + 8
    block_start = cd_offset - total_block_len
    if block_start < 0:
        return None
    size_in_header = struct.unpack_from("<Q", apk_bytes, block_start)[0]
    if size_in_header != size_in_footer:
        return None
    sig_block = apk_bytes[block_start:cd_offset]
    sig_sha256 = hashlib.sha256(sig_block).hexdigest()
    # Also scan pairs inside sig_block[8:-24] to extract X.509 cert SHA-256!
    certs = []
    pos = 8
    limit = len(sig_block) - 24
    while pos + 12 <= limit:
        pair_len = struct.unpack_from("<Q", sig_block, pos)[0]
        pos += 8
        if pair_len < 4 or pos + pair_len > limit:
            break
        pair_id = struct.unpack_from("<I", sig_block, pos)[0]
        pair_val = sig_block[pos + 4:pos + pair_len]
        pos += pair_len
        # APK Sig v2 = 0x7109871a, v3 = 0xf05368c0
        if pair_id in (0x7109871A, 0xF05368C0):
            # Walk length-prefixed signers -> signedData -> certificates
            try:
                signers_len = struct.unpack_from("<I", pair_val, 0)[0]
                signer_len = struct.unpack_from("<I", pair_val, 4)[0]
                signed_data_len = struct.unpack_from("<I", pair_val, 8)[0]
                digests_len = struct.unpack_from("<I", pair_val, 12)[0]
                certs_off = 16 + digests_len
                certs_len = struct.unpack_from("<I", pair_val, certs_off)[0]
                cert_len = struct.unpack_from("<I", pair_val, certs_off + 4)[0]
                cert_der = pair_val[certs_off + 8:certs_off + 8 + cert_len]
                certs.append(hashlib.sha256(cert_der).hexdigest())
            except Exception:
                pass
    return {
        "apk_size": str(n),
        "sig_block_sha256": sig_sha256,
        "certs_sha256": certs,
    }


def try_fetch_official_apk(site_url: str):
    info = {"site_url": site_url, "apk_urls": [], "parsed": None}
    st, hdrs, raw = http_req(site_url, headers={"User-Agent": BROWSER_UA}, timeout=15)
    html = raw.decode("utf-8", errors="replace")
    info["site_status"] = st
    info["site_preview"] = html[:500]
    urls = re.findall(r'https?://[^\s"\'<>]+\.apk(?:\?[^\s"\'<>]*)?', html, re.I)
    rel_urls = re.findall(r'href=["\']([^"\']+\.apk(?:\?[^"\']*)?)["\']', html, re.I)
    for u in rel_urls:
        full = urllib.parse.urljoin(site_url, u)
        if full not in urls:
            urls.append(full)
    # Also check any download links on the page
    dl_links = re.findall(r'href=["\']([^"\']*(?:download|app|cima|apk)[^"\']*)["\']', html, re.I)
    info["dl_links"] = dl_links[:20]
    info["apk_urls"] = urls
    candidates = list(urls)
    for dl in dl_links:
        full = urllib.parse.urljoin(site_url, dl)
        if full not in candidates and not full.endswith((".css", ".js", ".png", ".jpg", ".ico")):
            candidates.append(full)
    for cand in candidates[:8]:
        try:
            req = urllib.request.Request(cand, headers={"User-Agent": BROWSER_UA})
            with urllib.request.urlopen(req, timeout=25, context=SSL_CTX) as resp:
                ct = resp.headers.get("Content-Type", "")
                final_u = resp.geturl()
                head4 = resp.read(4)
                if head4 == b"PK\x03\x04":
                    rest = resp.read(80_000_000)
                    apk_bytes = head4 + rest
                    parsed = parse_apk_sig_block(apk_bytes)
                    if parsed:
                        parsed["downloaded_from"] = final_u
                        info["parsed"] = parsed
                        return info
        except Exception as e:
            info.setdefault("errors", []).append(f"{cand}: {e}")
    return info


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


def probe_php_chain(php_urls: list, target_url: str):
    chain_out = []
    current_urls = [target_url]
    for php_url in php_urls:
        if not php_url:
            continue
        step_results = []
        next_urls = []
        for u in current_urls[:4]:
            full = f"{php_url}?url={urllib.parse.quote(u, safe='')}"
            st, hdrs, raw = http_req(full, headers={"User-Agent": APP_UA, "Accept": "application/json"}, timeout=15)
            txt = raw.decode("utf-8", errors="replace").strip()
            parsed = None
            try:
                parsed = json.loads(txt)
            except Exception:
                pass
            step_results.append({
                "php": php_url.split("/")[-1],
                "input": u,
                "status": st,
                "json": parsed,
                "raw_preview": txt[:200] if parsed is None else None,
            })
            if isinstance(parsed, dict) and isinstance(parsed.get("servers"), list):
                for srv in parsed["servers"]:
                    su = srv.get("url") or ""
                    if su and not any(x in su.lower() for x in (".m3u8", ".mp4", "googlevideo.com")):
                        next_urls.append(su)
        chain_out.append(step_results)
        if next_urls:
            current_urls = next_urls
    return chain_out


def probe_media_or_page(url: str, referer: str = ""):
    hdrs = {"User-Agent": BROWSER_UA}
    if referer:
        hdrs["Referer"] = referer
    if "googlevideo.com" in url:
        hdrs["Referer"] = "https://photos.google.com/"
        hdrs["Origin"] = "https://photos.google.com"
    hdrs["Range"] = "bytes=0-4095"
    st, rh, raw = http_req(url, headers=hdrs, timeout=12, max_bytes=65536)
    ct = rh.get("Content-Type") or rh.get("content-type") or ""
    head_txt = raw[:512].decode("utf-8", errors="replace")
    is_hls = "#EXTM3U" in head_txt or "mpegurl" in ct.lower()
    is_mp4 = (len(raw) >= 8 and raw[4:8] == b"ftyp") or "video/" in ct.lower() or "octet-stream" in ct.lower()
    info = {
        "url": url[:180],
        "status": st,
        "content_type": ct,
        "is_hls": is_hls,
        "is_mp4": is_mp4,
        "head_preview": head_txt[:160] if not is_mp4 else f"<binary {len(raw)}B magic={raw[:12]!r}>",
    }
    if not is_hls and not is_mp4 and ("text/html" in ct.lower() or "<html" in head_txt.lower() or "<script" in head_txt.lower()):
        st2, _, raw2 = http_req(url, headers={"User-Agent": BROWSER_UA, **({"Referer": referer} if referer else {})}, timeout=12, max_bytes=400_000)
        full_html = raw2.decode("utf-8", errors="replace")
        unpacked = unpack_packer(full_html)
        media_hits = re.findall(r'https?://[^\s"\'<>]+(?:\.m3u8|\.mp4|master\.txt)[^\s"\'<>]*', full_html + "\n" + "\n".join(unpacked))
        iframes = re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', full_html, re.I)
        player_iframes = re.findall(r"player_iframe\.location\.href\s*=\s*['\"]([^'\"]+)['\"]", full_html)
        info["html_analysis"] = {
            "html_len": len(full_html),
            "packer_blocks": len(unpacked),
            "media_hits": media_hits[:10],
            "iframes": iframes[:8],
            "player_iframes": player_iframes[:8],
        }
    return info


def probe_google_photos(share_url: str):
    st, rh, raw = http_req(share_url, headers={"User-Agent": BROWSER_UA}, timeout=15, max_bytes=1_500_000)
    html = raw.decode("utf-8", errors="replace")
    clean = html.replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/")
    gurls = list(dict.fromkeys(re.findall(r"https://[a-zA-Z0-9._-]*googlevideo\.com/videoplayback[^\s\"'\\<>)}\]]*", clean)))
    lh3 = list(dict.fromkeys(re.findall(r"https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]{20,}", clean)))
    out = {"status": st, "googlevideo_count": len(gurls), "lh3_count": len(lh3), "streams": []}
    for base in [re.sub(r"=[^=?&\s\"'\\]+(?:\?[^\s\"'\\]*)?$", "", g) for g in gurls[:1]]:
        for suffix in ("=m37", "=m22", "=m18"):
            cand = base + suffix
            pst, prh, praw = http_req(
                cand,
                headers={
                    "User-Agent": BROWSER_UA,
                    "Referer": "https://photos.google.com/",
                    "Origin": "https://photos.google.com",
                    "Range": "bytes=0-1023",
                },
                timeout=10,
                max_bytes=2048,
            )
            out["streams"].append({
                "suffix": suffix,
                "status": pst,
                "ct": prh.get("Content-Type") or prh.get("content-type"),
                "ftyp": len(praw) >= 8 and praw[4:8] == b"ftyp",
            })
    return out


def main():
    report = {"timestamp": int(time.time())}

    st, _, raw = http_req(CONFIG_URL, headers={"User-Agent": APP_UA}, timeout=15)
    cfg_json = json.loads(raw.decode("utf-8"))
    cfg = cfg_json["data"][0]
    api_base = cfg["cloudday"].rstrip("/") + "/v1.3/api"
    php_chain = [
        cfg.get("extractorUrl", ""),
        cfg.get("htmlExtractorUrl", ""),
        cfg.get("advancedExtractorUrl", ""),
    ]
    report["api_base"] = api_base
    report["php_chain"] = php_chain

    # Test token variants!
    b_sourcedir = f"{CERT_HEX}|/data/app/com.app.cimacloud/base.apk"
    variants = {
        "v1_fakecontext_exact": {
            "a": "err",
            "b": b_sourcedir,
            "d": "empty",
            "e": "empty",
            "s": "err_open",
        },
        "v2_fakecontext_dirs": {
            "a": "err",
            "b": b_sourcedir,
            "d": "cache,code_cache,files,shared_prefs",
            "e": "cache,code_cache,files,shared_prefs",
            "s": "err_open",
        },
        "v3_android11_path_err": {
            "a": "err",
            "b": f"{CERT_HEX}|/data/app/~~a1b2c3d4e5f6g7h8==/com.app.cimacloud-x9y8z7w6v5u4==/base.apk",
            "d": "cache,code_cache,files,shared_prefs",
            "e": "cache,code_cache,files,shared_prefs",
            "s": "err_open",
        },
    }

    variant_results = {}
    winning_params = None
    winning_name = None

    for vname, vparams in variants.items():
        res = api_get_decrypted(api_base, "/movie/27295/servers", vparams)
        variant_results[vname] = res
        j = res.get("json") or {}
        if j.get("status") is True and not j.get("blocked"):
            winning_params = vparams
            winning_name = vname
            break

    # Also test official APK if none of v1..v3 unlocked OR even just to inspect!
    if winning_params is None:
        apk_probe = try_fetch_official_apk(cfg.get("cimacloud", "https://www.cima-cloud.com"))
        report["official_apk_probe"] = apk_probe
        if apk_probe.get("parsed"):
            ap = apk_probe["parsed"]
            cert_use = ap["certs_sha256"][0] if ap.get("certs_sha256") else CERT_HEX
            real_params = {
                "cert": cert_use,
                "a": ap["apk_size"],
                "b": f"{cert_use}|/data/app/~~a1b2c3d4e5f6g7h8==/com.app.cimacloud-x9y8z7w6v5u4==/base.apk",
                "d": "cache,code_cache,files,shared_prefs",
                "e": "cache,code_cache,files,shared_prefs",
                "s": ap["sig_block_sha256"],
            }
            res_real = api_get_decrypted(api_base, "/movie/27295/servers", real_params)
            variant_results["v4_official_apk"] = res_real
            j = res_real.get("json") or {}
            if j.get("status") is True and not j.get("blocked"):
                winning_params = real_params
                winning_name = "v4_official_apk"

    report["variant_results"] = variant_results
    report["winning_variant"] = winning_name
    report["winning_params"] = winning_params

    if winning_params is None:
        # Write report immediately so we can inspect
        with open(".tmp_verify/phase2_report.json", "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print("No winning variant yet; see phase2_report.json")
        return

    # Full target verification with winning_params!
    targets = {
        "star_one_movie_27295": {
            "detail": api_get_decrypted(api_base, "/movie/27295", winning_params),
            "servers": api_get_decrypted(api_base, "/movie/27295/servers", winning_params),
        },
        "phony_series_12827": {
            "detail": api_get_decrypted(api_base, "/series/12827", winning_params),
            "ep1_370096_servers": api_get_decrypted(api_base, "/episode/370096/servers", winning_params),
            "ep2_370097_servers": api_get_decrypted(api_base, "/episode/370097/servers", winning_params),
        },
        "anime_series_frieren_12819": {
            "detail": api_get_decrypted(api_base, "/series/12819", winning_params),
            "ep1_369392_servers": api_get_decrypted(api_base, "/episode/369392/servers", winning_params),
        },
        "cartoon_series_kaijudo_12830": {
            "detail": api_get_decrypted(api_base, "/series/12830", winning_params),
            "ep1_370424_servers": api_get_decrypted(api_base, "/episode/370424/servers", winning_params),
        },
        "anime_movie_hxh_27291": {
            "detail": api_get_decrypted(api_base, "/movie/27291", winning_params),
            "servers": api_get_decrypted(api_base, "/movie/27291/servers", winning_params),
        },
        "anime_movie_bluelock_27206": {
            "servers": api_get_decrypted(api_base, "/movie/27206/servers", winning_params),
        },
        "cartoon_movie_toystory5_27040": {
            "servers": api_get_decrypted(api_base, "/movie/27040/servers", winning_params),
        },
    }
    report["targets"] = targets

    # Collect all server entries across Star One, Phony Ep 1 & Ep 2, Frieren, Kaijudo, HxH, Blue Lock, Toy Story 5
    all_servers = []
    for tname, tval in targets.items():
        for k, v in tval.items():
            if "servers" in k and isinstance(v, dict) and isinstance(v.get("json"), dict):
                srvs = v["json"].get("servers") or v["json"].get("data") or []
                if isinstance(srvs, list):
                    for s in srvs:
                        if isinstance(s, dict):
                            all_servers.append({"target": f"{tname}:{k}", **s})

    # Deduplicate by host+pattern, always keeping ALL servers of Star One and Phony!
    unique_to_probe = []
    seen_hosts = set()
    for srv in all_servers:
        link = (srv.get("link") or srv.get("url") or "").strip()
        if not link:
            continue
        host = urllib.parse.urlparse(link).netloc.lower()
        is_priority = "star_one" in srv["target"] or "phony" in srv["target"]
        if is_priority or host not in seen_hosts:
            seen_hosts.add(host)
            unique_to_probe.append(srv)

    def inspect_server(srv):
        link = (srv.get("link") or srv.get("url") or "").strip()
        ref = (srv.get("referer") or srv.get("origin") or "").strip()
        entry = {
            "target": srv["target"],
            "raw_server": srv,
            "direct_probe": probe_media_or_page(link, ref),
        }
        if "photos.app.goo.gl" in link or "photos.google.com" in link:
            entry["google_photos"] = probe_google_photos(link)
        else:
            chain = probe_php_chain(php_chain, link)
            entry["php_chain"] = chain
            resolved_probes = []
            for step in chain:
                for sr in step:
                    j = sr.get("json")
                    if isinstance(j, dict) and isinstance(j.get("servers"), list):
                        for rs in j["servers"][:4]:
                            ru = (rs.get("url") or "").strip()
                            rref = (rs.get("referer") or rs.get("origin") or ref).strip()
                            if ru:
                                resolved_probes.append({
                                    "from_php": sr["php"],
                                    "server_obj": rs,
                                    "probe": probe_media_or_page(ru, rref),
                                })
                            dl = (rs.get("url_download") or "").strip()
                            if dl and dl != ru:
                                resolved_probes.append({
                                    "from_php": sr["php"] + ":url_download",
                                    "url_download": dl,
                                    "probe": probe_media_or_page(dl, rref),
                                })
            entry["resolved_probes"] = resolved_probes
        return entry

    probed_servers = []
    ex = ThreadPoolExecutor(max_workers=8)
    try:
        futs = [ex.submit(inspect_server, s) for s in unique_to_probe[:35]]
        for fut in as_completed(futs, timeout=150):
            try:
                probed_servers.append(fut.result())
            except Exception as e:
                probed_servers.append({"error": str(e)})
    finally:
        ex.shutdown(wait=False, cancel_futures=True)

    report["all_servers_count"] = len(all_servers)
    report["probed_servers_count"] = len(probed_servers)
    report["probed_servers"] = probed_servers

    with open(".tmp_verify/phase2_report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"Phase 2b complete! Winning={winning_name}, total_servers={len(all_servers)}, probed={len(probed_servers)}")


if __name__ == "__main__":
    main()
