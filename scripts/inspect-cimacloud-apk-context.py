#!/usr/bin/env python3
"""Extract focused Smali/native-code leads from an apktool decode directory."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from zipfile import BadZipFile, ZipFile

PATTERNS: dict[str, re.Pattern[str]] = {
    "signature_api": re.compile(
        r"GET_SIGNATURES|GET_SIGNING_CERTIFICATES|SigningInfo|apkContentsSigners|"
        r"getSigningCertificateHistory|checkSignatures|PackageInfo;->signatures|"
        r"getPackageInfo",
        re.IGNORECASE,
    ),
    "secure_attestation": re.compile(
        r"buildSecure|decryptResponse|attest_failed|attest_no_token|"
        r"MessageDigest|SHA-?256|HmacSHA256|CertificateFactory",
        re.IGNORECASE,
    ),
    "process_termination": re.compile(
        r"System;->exit|Runtime;->halt|Process;->killProcess|killProcess|"
        r"SIGKILL|SIGABRT|uncaughtException",
        re.IGNORECASE,
    ),
    "native_method_declaration": re.compile(
        r"^\s*\.method\b.*\bnative\b.*(?:buildSecure|decryptResponse)|"
        r"System;->loadLibrary",
        re.IGNORECASE,
    ),
}


def context(lines: list[str], index: int, radius: int = 12) -> str:
    start = max(0, index - radius)
    end = min(len(lines), index + radius + 1)
    return "\n".join(f"{line_number + 1}: {lines[line_number][:120]}" for line_number in range(start, end))


def scan_smali(decoded_dir: Path) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    category_counts = {category: 0 for category in PATTERNS}
    files = sorted(decoded_dir.glob("smali*/**/*.smali"))
    for path in files:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        relative = path.relative_to(decoded_dir).as_posix()
        for index, line in enumerate(lines):
            for category, pattern in PATTERNS.items():
                if not pattern.search(line):
                    continue
                category_counts[category] += 1
                # Keep matches in the app's own obfuscated packages, plus a
                # small sample of native/attestation declarations elsewhere.
                own_code = relative.startswith(("smali/C1/", "smali/Q0/"))
                important_term = bool(
                    re.search(
                        r"GET_SIGNATURES|GET_SIGNING_CERTIFICATES|SigningInfo|apkContentsSigners|"
                        r"getSigningCertificateHistory|buildSecure|decryptResponse|attest_failed|"
                        r"attest_no_token|System;->exit|killProcess|\.method.*\bnative\b",
                        line,
                        re.IGNORECASE,
                    )
                )
                if (own_code or important_term) and sum(1 for hit in results if hit["category"] == category) < 5:
                    results.append(
                        {
                            "category": category,
                            "file": relative,
                            "line": index + 1,
                            "context": context(lines, index),
                        }
                    )
    return [
        {"category_counts": category_counts},
        *results,
    ]


def scan_native(decoded_dir: Path, apk_path: Path | None = None) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    libraries: list[tuple[str, bytes]] = []
    is_app_library = lambda name: name.lower().endswith(".so") and any(
        marker in name.lower() for marker in ("bnhe", "cima", "cloud", "native-lib")
    )
    if apk_path is not None:
        with ZipFile(apk_path) as archive:
            libraries.extend(
                (name, archive.read(name))
                for name in sorted(archive.namelist())
                if name.startswith("lib/") and is_app_library(name)
            )
    if not libraries and (decoded_dir / "lib").is_dir():
        libraries.extend(
            (library.relative_to(decoded_dir).as_posix(), library.read_bytes())
            for library in sorted((decoded_dir / "lib").rglob("*.so"))
            if is_app_library(library.name)
        )

    for path, data in libraries:
        strings = [match.group().decode("ascii", errors="replace") for match in re.finditer(rb"[\x20-\x7e]{4,}", data)]
        interesting = [
            value[:160]
            for value in strings
            if re.search(
                r"getPackageInfo|getPackageManager|certificate|signature|digest|SHA-?256|"
                r"MessageDigest|Hmac|JNI_OnLoad|abort|uncaughtException|buildSecure",
                value,
                re.IGNORECASE,
            )
        ][:10]
        results.append(
            {
                "file": path,
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "interesting_strings": interesting,
            }
        )
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decoded_dir", type=Path, help="apktool output directory")
    parser.add_argument("--apk", type=Path, help="original APK for direct native-library string scanning")
    args = parser.parse_args()
    decoded_dir = args.decoded_dir
    if not decoded_dir.is_dir():
        print(f"Decode directory not found: {decoded_dir}", file=sys.stderr)
        return 1

    try:
        native = scan_native(decoded_dir, args.apk)
    except (BadZipFile, OSError) as error:
        print(f"Native library inspection failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 1
    report = {
        "smali": scan_smali(decoded_dir),
        "native": native,
    }
    print(json.dumps(report, indent=2, ensure_ascii=True))
    counts = report["smali"][0]["category_counts"]
    encoded_counts = json.dumps(counts, separators=(",", ":"), ensure_ascii=True)
    print(f"::notice title=CimaCloud focused Smali match counts::{encoded_counts}")
    for finding in report["smali"][1:]:
        encoded = json.dumps(finding, separators=(",", ":"), ensure_ascii=True)
        encoded = encoded.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title=CimaCloud {finding['category']} in {finding['file']}::{encoded}")
    for finding in report["native"]:
        encoded = json.dumps(finding, separators=(",", ":"), ensure_ascii=True)
        encoded = encoded.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title=CimaCloud native library scan::{encoded}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
