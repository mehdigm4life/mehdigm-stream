#!/usr/bin/env python3
import json
import os
import re
import struct
import subprocess
import requests

OUT_DIR = ".tmp_verify"
os.makedirs(OUT_DIR, exist_ok=True)

SO_PATH = "CimaCloud/src/main/jniLibs/arm64-v8a/libnative-lib.so"

def dump_annotated_disasm():
    with open(SO_PATH, "rb") as f:
        b = f.read()

    def cstr(off):
        end = b.index(0, off)
        return b[off:end].decode("utf-8", "replace")

    syms = {}
    for idx in range(0x34dd0 // 24):
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", b, 0x2f8 + idx * 24)
        syms[idx] = cstr(0x48438 + st_name) if st_name else ""

    plt_to_sym = {}
    for i, off in enumerate(range(0x123f98, 0x123f98 + 0x167d0, 24)):
        r_offset, r_info, r_addend = struct.unpack_from("<QQq", b, off)
        plt_to_sym[0x42f0f0 + 32 + i * 16] = syms.get(r_info >> 32, "")

    raw = subprocess.check_output([
        "aarch64-linux-gnu-objdump",
        "-d",
        "--start-address=0x233da0",
        "--stop-address=0x23f888",
        SO_PATH,
    ], text=True)

    bl_re = re.compile(r"\b(bl|b)\s+([0-9a-fA-F]+)\b")
    annotated_lines = []
    for line in raw.splitlines():
        m = bl_re.search(line)
        if m:
            addr = int(m.group(2), 16)
            if addr in plt_to_sym:
                line = f"{line}    // PLT: {plt_to_sym[addr]}"
        annotated_lines.append(line)

    with open(os.path.join(OUT_DIR, "disasm.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(annotated_lines) + "\n")
    print(f"Wrote annotated disasm: {len(annotated_lines)} lines")


def probe_catalog_api():
    s = requests.Session()
    s.headers.update({
        "User-Agent": "okhttp/4.10.0",
        "Accept": "application/json, text/plain, */*",
        "Referer": "https://cima-cloud.com/",
    })

    cfg = s.get("https://config-backup2.animegateshorts.workers.dev/", timeout=15).json()
    base_host = cfg["data"][0]["cloudday"].rstrip("/")
    api = f"{base_host}/v1.3/api"

    report = {
        "config": cfg,
        "api_base": api,
        "search_probes": {},
        "get_probes": {},
    }

    # 1. Probe POST /search with various form parameters to discover exact filter schema!
    search_Payloads = {
        "search_star_one": {"title": "Star One", "type": "0", "sort": "1", "page": "1"},
        "search_phony": {"title": "Phony", "type": "0", "sort": "1", "page": "1"},
        "empty_type0_p1": {"title": "", "type": "0", "sort": "1", "page": "1"},
        "empty_type1_p1": {"title": "", "type": "1", "sort": "1", "page": "1"},
        "empty_type2_p1": {"title": "", "type": "2", "sort": "1", "page": "1"},
        "empty_type3_p1": {"title": "", "type": "3", "sort": "1", "page": "1"},
        "cat5_category": {"title": "", "type": "0", "category": "5", "sort": "1", "page": "1"},
        "cat5_category_id": {"title": "", "type": "0", "category_id": "5", "sort": "1", "page": "1"},
        "cat5_categories": {"title": "", "type": "0", "categories": "5", "sort": "1", "page": "1"},
        "cat6_category": {"title": "", "type": "0", "category": "6", "sort": "1", "page": "1"},
        "cat6_categories": {"title": "", "type": "0", "categories": "6", "sort": "1", "page": "1"},
        "cat1_turkish_series": {"title": "", "type": "0", "category": "1", "sort": "1", "page": "1"},
        "cat2_turkish_movies": {"title": "", "type": "0", "category": "2", "sort": "1", "page": "1"},
        "cat3_foreign_series": {"title": "", "type": "0", "category": "3", "sort": "1", "page": "1"},
        "cat4_foreign_movies": {"title": "", "type": "0", "category": "4", "sort": "1", "page": "1"},
        "cat7_korean_series": {"title": "", "type": "0", "category": "7", "sort": "1", "page": "1"},
        "cat8_korean_movies": {"title": "", "type": "0", "category": "8", "sort": "1", "page": "1"},
        "cat9_indian_series": {"title": "", "type": "0", "category": "9", "sort": "1", "page": "1"},
        "cat10_indian_movies": {"title": "", "type": "0", "category": "10", "sort": "1", "page": "1"},
        "cat11_asian_series": {"title": "", "type": "0", "category": "11", "sort": "1", "page": "1"},
        "cat12_asian_movies": {"title": "", "type": "0", "category": "12", "sort": "1", "page": "1"},
        "cat13_probe": {"title": "", "type": "0", "category": "13", "sort": "1", "page": "1"},
        "cat14_probe": {"title": "", "type": "0", "category": "14", "sort": "1", "page": "1"},
        "cat15_probe": {"title": "", "type": "0", "category": "15", "sort": "1", "page": "1"},
        "cat16_probe": {"title": "", "type": "0", "category": "16", "sort": "1", "page": "1"},
        "genre16_animation": {"title": "", "type": "0", "genre": "16", "sort": "1", "page": "1"},
        "genres16_animation": {"title": "", "type": "0", "genres": "16", "sort": "1", "page": "1"},
        "dub_1": {"title": "", "type": "0", "dub": "1", "sort": "1", "page": "1"},
        "sort_2": {"title": "", "type": "0", "sort": "2", "page": "1"},
        "sort_3": {"title": "", "type": "0", "sort": "3", "page": "1"},
        "sort_4": {"title": "", "type": "0", "sort": "4", "page": "1"},
    }

    for key, payload in search_Payloads.items():
        try:
            r = s.post(f"{api}/search", data=payload, timeout=12)
            js = r.json()
            results = js.get("results") or []
            report["search_probes"][key] = {
                "http": r.status_code,
                "total": js.get("total"),
                "total_pages": js.get("total_pages"),
                "has_more": js.get("has_more"),
                "count": len(results),
                "sample": [
                    {
                        "id": x.get("id"),
                        "type": x.get("type"),
                        "name": x.get("name"),
                        "dub": x.get("dub"),
                        "categories": x.get("categories"),
                        "genres": x.get("genres"),
                    }
                    for x in results[:3]
                ],
            }
        except Exception as e:
            report["search_probes"][key] = {"error": str(e)}

    # 2. Probe candidate GET / POST catalog routes
    candidate_paths = [
        ("GET", "/movies?page=2", None),
        ("GET", "/series?page=2", None),
        ("POST", "/movies", {"category": "6", "page": "1"}),
        ("POST", "/series", {"category": "5", "page": "1"}),
        ("GET", "/anime?page=1", None),
        ("GET", "/cartoon?page=1", None),
        ("GET", "/genres", None),
        ("GET", "/list", None),
        ("POST", "/filter", {"type": "0", "category": "5", "page": "1"}),
        ("GET", "/section/3?page=1", None),
        ("GET", "/sections/3?page=1", None),
        ("GET", "/home?page=2", None),
        ("GET", "/series/12819/episodes", None),  # Frieren anime
        ("GET", "/series/12830/episodes", None),  # Kaijudo cartoon
    ]
    for method, path, data in candidate_paths:
        try:
            url = f"{api}{path}"
            r = s.post(url, data=data, timeout=12) if method == "POST" else s.get(url, timeout=12)
            txt = r.text[:400]
            parsed = None
            try:
                js = r.json()
                parsed = {
                    "keys": list(js.keys()),
                    "total": js.get("total"),
                    "count": len(js.get("results") or js.get("seasons") or []),
                    "first": (js.get("results") or js.get("seasons") or [None])[0],
                }
            except Exception:
                pass
            report["get_probes"][f"{method} {path}"] = {
                "http": r.status_code,
                "parsed": parsed,
                "preview": txt if parsed is None else None,
            }
        except Exception as e:
            report["get_probes"][f"{method} {path}"] = {"error": str(e)}

    with open(os.path.join(OUT_DIR, "api_catalog_probe.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("Wrote api_catalog_probe.json")


if __name__ == "__main__":
    dump_annotated_disasm()
    probe_catalog_api()
