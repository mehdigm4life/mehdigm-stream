#!/usr/bin/env python3
"""Compare a source CimaCloud APK with rebuilt variants for launch regressions.

The report is intentionally static: it identifies changes to signing, manifests,
DEX/smali, and native libraries, but cannot prove why a particular device exits.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import BadZipFile, ZipFile

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
DEX_NAME = re.compile(r"^classes(?:[2-9][0-9]*)?\.dex$")

# Broad indicators only; a hit means "inspect this code", not that the app is
# necessarily enforcing an integrity rule or terminating because of it.
SCAN_PATTERNS: dict[str, re.Pattern[str]] = {
    "signing_or_package_identity": re.compile(
        r"getPackageInfo|getPackageArchiveInfo|getSigningCertificateHistory|"
        r"GET_SIGNATURES|GET_SIGNING_CERTIFICATES|SigningInfo|apkContentsSigners|"
        r"checkSignatures|PackageManager|installerPackageName|sourceDir|base\.apk|"
        r"certificate|cert(?:ificate)?[_ -]?(?:sha|hash|fingerprint)|signing.?key",
        re.IGNORECASE,
    ),
    "integrity_or_attestation": re.compile(
        r"integrity|attest|SafetyNet|Play.?Integrity|App.?Check|tamper|checksum|"
        r"digest|MessageDigest|HMAC|SHA-?256|SHA-?1|CRC32|signature.?check",
        re.IGNORECASE,
    ),
    "process_exit_or_crash": re.compile(
        r"System;->exit|Runtime;->halt|Process;->killProcess|killProcess|"
        r"SIGKILL|SIGABRT|\babort\b|\bexit\b|Fatal signal|uncaughtException",
        re.IGNORECASE,
    ),
    "anti_debug_or_instrumentation": re.compile(
        r"isDebuggerConnected|waitingForDebugger|TracerPid|ptrace|frida|xposed|"
        r"substrate|zygisk|gum-js-loop|anti.?debug|hooking|instrumentation",
        re.IGNORECASE,
    ),
    "root_or_emulator_checks": re.compile(
        r"test-keys|ro\.debuggable|magisk|supersu|/system/xbin/su|/system/bin/su|"
        r"goldfish|ranchu|qemu|Build\.FINGERPRINT|Build\.MODEL|isEmulator|rooted",
        re.IGNORECASE,
    ),
    "tls_or_certificate_pinning": re.compile(
        r"CertificatePinner|X509TrustManager|checkServerTrusted|checkClientTrusted|"
        r"HostnameVerifier|SSLPeerUnverifiedException|pinning|network_security_config|"
        r"trust-anchors|certificates src=",
        re.IGNORECASE,
    ),
    "jni_or_native_loading": re.compile(
        r"System;->loadLibrary|loadLibrary|JNI_OnLoad|Java_[A-Za-z0-9_]+|\bnative\b",
        re.IGNORECASE,
    ),
}

ELF_MACHINES = {
    3: "x86",
    40: "ARM",
    62: "x86_64",
    183: "AArch64",
    243: "RISC-V",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_pairs(values: list[str], option: str) -> dict[str, Path]:
    parsed: dict[str, Path] = {}
    for value in values:
        label, separator, path = value.partition("=")
        if not separator or not label or not path:
            raise ValueError(f"{option} must use LABEL=PATH; got {value!r}")
        if label in parsed:
            raise ValueError(f"duplicate {option} label: {label}")
        parsed[label] = Path(path)
    return parsed


def read_signature_report(path: Path | None) -> dict[str, object]:
    if path is None or not path.is_file():
        return {"available": False, "verified": None, "cert_sha256": [], "schemes": {}, "text": ""}
    text = path.read_text(encoding="utf-8", errors="replace").strip()
    certs = re.findall(
        r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", text, re.IGNORECASE
    )
    schemes = {
        name: value.lower() == "true"
        for name, value in re.findall(
            r"Verified using (v[1-4]) scheme \([^)]*\):\s*(true|false)",
            text,
            re.IGNORECASE,
        )
    }
    result_marker = re.search(r"^RESULT=(PASS|FAIL)$", text, re.MULTILINE)
    if result_marker:
        verified: bool | None = result_marker.group(1) == "PASS"
    else:
        first_line = text.splitlines()[0].strip().lower() if text else ""
        verified = first_line in {"verifies", "verified"} and "does not verify" not in text.lower()
    return {
        "available": True,
        "verified": verified,
        "cert_sha256": [value.replace(":", "").lower() for value in certs],
        "schemes": schemes,
        "text": text,
    }


def read_elf_info(data: bytes) -> dict[str, object]:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return {"is_elf": False}
    byte_order = "little" if data[5] == 1 else "big" if data[5] == 2 else None
    if byte_order is None:
        return {"is_elf": True, "class": data[4], "machine": "unknown"}
    machine_id = int.from_bytes(data[18:20], byte_order)
    elf_class = {1: "ELF32", 2: "ELF64"}.get(data[4], f"class-{data[4]}")
    return {
        "is_elf": True,
        "class": elf_class,
        "machine": ELF_MACHINES.get(machine_id, f"e_machine-{machine_id}"),
        "endianness": byte_order,
    }


def apk_inventory(path: Path) -> dict[str, object]:
    if not path.is_file():
        raise FileNotFoundError(path)
    result: dict[str, object] = {
        "path_name": path.name,
        "size": path.stat().st_size,
        "sha256": sha256(path.read_bytes()),
        "entries": {},
        "native_libraries": {},
        "dex": {},
        "manifest_binary_sha256": None,
    }
    with ZipFile(path) as archive:
        entries: dict[str, dict[str, object]] = {}
        native: dict[str, dict[str, object]] = {}
        dex: dict[str, dict[str, object]] = {}
        for info in archive.infolist():
            if info.is_dir():
                continue
            data = archive.read(info.filename)
            entry = {"size": len(data), "sha256": sha256(data)}
            entries[info.filename] = entry
            if info.filename == "AndroidManifest.xml":
                result["manifest_binary_sha256"] = entry["sha256"]
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                native[info.filename] = {**entry, **read_elf_info(data)}
            if DEX_NAME.fullmatch(Path(info.filename).name):
                dex[info.filename] = entry
        result["entries"] = entries
        result["native_libraries"] = native
        result["dex"] = dex
    return result


def manifest_summary(decoded_dir: Path | None) -> dict[str, object] | None:
    if decoded_dir is None:
        return None
    manifest_path = decoded_dir / "AndroidManifest.xml"
    if not manifest_path.is_file():
        return {"available": False, "decoded_dir": str(decoded_dir)}
    root = ET.parse(manifest_path).getroot()
    uses_sdk = root.find("uses-sdk")
    application = root.find("application")
    components: list[dict[str, object]] = []
    if application is not None:
        for component in list(application):
            if component.tag not in {
                "activity",
                "activity-alias",
                "service",
                "receiver",
                "provider",
            }:
                continue
            components.append(
                {
                    "type": component.tag,
                    "name": component.get(ANDROID_NS + "name"),
                    "exported": component.get(ANDROID_NS + "exported"),
                    "enabled": component.get(ANDROID_NS + "enabled"),
                    "process": component.get(ANDROID_NS + "process"),
                }
            )
    permissions = sorted(
        child.get(ANDROID_NS + "name", "")
        for child in root
        if child.tag in {"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"}
    )
    app_summary: dict[str, object] = {}
    if application is not None:
        for name in (
            "name",
            "label",
            "enabled",
            "debuggable",
            "allowBackup",
            "usesCleartextTraffic",
            "networkSecurityConfig",
            "extractNativeLibs",
        ):
            app_summary[name] = application.get(ANDROID_NS + name)
    network_config_ref = app_summary.get("networkSecurityConfig")
    network_config: dict[str, object] | None = None
    if isinstance(network_config_ref, str) and network_config_ref.startswith("@xml/"):
        config_name = network_config_ref.split("/", 1)[1]
        for candidate in sorted((decoded_dir / "res").glob(f"xml*/{config_name}.xml")):
            content = candidate.read_bytes()
            network_config = {
                "path": str(candidate.relative_to(decoded_dir)),
                "sha256": sha256(content),
                "text": content.decode("utf-8", errors="replace")[:20_000],
            }
            break
    return {
        "available": True,
        "package": root.get("package"),
        "version_code": root.get(ANDROID_NS + "versionCode"),
        "version_name": root.get(ANDROID_NS + "versionName"),
        "platform_build_version_code": root.get("platformBuildVersionCode"),
        "platform_build_version_name": root.get("platformBuildVersionName"),
        "min_sdk": uses_sdk.get(ANDROID_NS + "minSdkVersion") if uses_sdk is not None else None,
        "target_sdk": uses_sdk.get(ANDROID_NS + "targetSdkVersion") if uses_sdk is not None else None,
        "application": app_summary,
        "permissions": permissions,
        "components": components,
        "network_security_config": network_config,
    }


def smali_inventory(decoded_dir: Path | None) -> dict[str, object]:
    if decoded_dir is None or not decoded_dir.is_dir():
        return {"available": False, "file_count": 0, "tree_sha256": None, "scan": {}}
    files = sorted(decoded_dir.glob("smali*/**/*.smali"))
    tree_hash = hashlib.sha256()
    scan: dict[str, dict[str, object]] = {
        name: {"count": 0, "samples": []} for name in SCAN_PATTERNS
    }
    for file_path in files:
        relative = file_path.relative_to(decoded_dir).as_posix()
        content = file_path.read_bytes()
        tree_hash.update(relative.encode("utf-8"))
        tree_hash.update(b"\0")
        tree_hash.update(hashlib.sha256(content).digest())
        for line_number, line in enumerate(content.decode("utf-8", errors="replace").splitlines(), 1):
            text = line.strip()
            for category, pattern in SCAN_PATTERNS.items():
                if not pattern.search(text):
                    continue
                item = scan[category]
                item["count"] = int(item["count"]) + 1
                samples = item["samples"]
                if len(samples) < 8:
                    samples.append(
                        {
                            "file": relative,
                            "line": line_number,
                            "text": text[:280],
                        }
                    )
    return {
        "available": True,
        "file_count": len(files),
        "tree_sha256": tree_hash.hexdigest(),
        "scan": scan,
    }


def native_string_scan(path: Path, limit: int = 10) -> dict[str, dict[str, object]]:
    data = path.read_bytes()
    strings = [match.group().decode("ascii", errors="replace") for match in re.finditer(rb"[\x20-\x7e]{4,}", data)]
    result: dict[str, dict[str, object]] = {}
    for category, pattern in SCAN_PATTERNS.items():
        matches: list[str] = []
        count = 0
        for value in strings:
            if not pattern.search(value):
                continue
            count += 1
            if len(matches) < limit:
                matches.append(value[:280])
        if count:
            result[category] = {"count": count, "samples": matches}
    return result


def decoded_native_scan(decoded_dir: Path | None) -> dict[str, object]:
    if decoded_dir is None or not decoded_dir.is_dir():
        return {"available": False, "libraries": {}}
    libraries: dict[str, object] = {}
    for path in sorted((decoded_dir / "lib").rglob("*.so")) if (decoded_dir / "lib").is_dir() else []:
        libraries[path.relative_to(decoded_dir).as_posix()] = native_string_scan(path)
    return {"available": True, "libraries": libraries}


def content_changes(before: dict[str, dict[str, object]], after: dict[str, dict[str, object]]) -> dict[str, object]:
    before_names, after_names = set(before), set(after)
    common = before_names & after_names
    changed = sorted(name for name in common if before[name]["sha256"] != after[name]["sha256"])
    return {
        "added": sorted(after_names - before_names),
        "removed": sorted(before_names - after_names),
        "changed": changed,
        "unchanged_count": sum(before[name]["sha256"] == after[name]["sha256"] for name in common),
    }


def manifest_diff(left: dict[str, object] | None, right: dict[str, object] | None) -> list[str]:
    if left is None or right is None or not left.get("available") or not right.get("available"):
        return ["Manifest comparison unavailable because apktool did not decode both APKs."]
    left_lines = json.dumps(left, indent=2, sort_keys=True, ensure_ascii=False).splitlines()
    right_lines = json.dumps(right, indent=2, sort_keys=True, ensure_ascii=False).splitlines()
    return list(
        difflib.unified_diff(
            left_lines,
            right_lines,
            fromfile="source manifest/config",
            tofile="rebuilt manifest/config",
            lineterm="",
        )
    )[:500]


def shorten(values: list[str], limit: int = 30) -> list[str]:
    if len(values) <= limit:
        return values
    return values[:limit] + [f"… plus {len(values) - limit} more"]


def build_report(
    apks: dict[str, Path], decoded: dict[str, Path], signatures: dict[str, Path]
) -> dict[str, object]:
    if "source" not in apks:
        raise ValueError("--apk source=PATH is required")
    apk_reports: dict[str, dict[str, object]] = {}
    for label, path in apks.items():
        inventory = apk_inventory(path)
        decoded_dir = decoded.get(label)
        manifest = manifest_summary(decoded_dir)
        smali = smali_inventory(decoded_dir)
        native_scan = decoded_native_scan(decoded_dir)
        signature = read_signature_report(signatures.get(label))
        inventory.update(
            {
                "manifest": manifest,
                "smali": smali,
                "native_string_scan": native_scan,
                "signature": signature,
            }
        )
        apk_reports[label] = inventory

    source = apk_reports["source"]
    source_native = source["native_libraries"]
    source_dex = source["dex"]
    source_entries = source["entries"]
    comparisons: dict[str, object] = {}
    for label, report in apk_reports.items():
        if label == "source":
            continue
        comparisons[label] = {
            "native_libraries": content_changes(source_native, report["native_libraries"]),
            "dex": content_changes(source_dex, report["dex"]),
            "zip_entries": content_changes(source_entries, report["entries"]),
            "smali_tree_sha256_equal": (
                source["smali"].get("tree_sha256") is not None
                and source["smali"].get("tree_sha256") == report["smali"].get("tree_sha256")
            ),
            "manifest_diff": manifest_diff(source.get("manifest"), report.get("manifest")),
            "signer_sha256_matches_source": (
                source["signature"].get("cert_sha256") == report["signature"].get("cert_sha256")
                and bool(source["signature"].get("cert_sha256"))
            ),
        }
    return {
        "schema_version": 1,
        "scope_note": (
            "Static APK comparison only. A matching native-library hash proves the library bytes "
            "were preserved, not that the library accepts a changed APK signer. A device log is "
            "still needed to identify the exact runtime exit path."
        ),
        "apks": apk_reports,
        "comparisons_against_source": comparisons,
    }


def markdown_report(report: dict[str, object]) -> str:
    apks = report["apks"]
    comparisons = report["comparisons_against_source"]
    lines = [
        "# CimaCloud APK forensic comparison",
        "",
        str(report["scope_note"]),
        "",
        "## APK identity and signing",
        "",
        "| Variant | Size | SHA-256 | Package | Version | Signer SHA-256 | Verified |",
        "| --- | ---: | --- | --- | --- | --- | --- |",
    ]
    for label, item in apks.items():
        manifest = item.get("manifest") or {}
        signature = item["signature"]
        certs = signature.get("cert_sha256") or []
        lines.append(
            "| {label} | {size} | `{sha}` | `{package}` | `{version}` | `{cert}` | {verified} |".format(
                label=label,
                size=item["size"],
                sha=item["sha256"],
                package=manifest.get("package", "unknown") if manifest else "unknown",
                version=manifest.get("version_name", "unknown") if manifest else "unknown",
                cert=", ".join(certs) if certs else "unavailable",
                verified=signature.get("verified", "unknown"),
            )
        )
    lines.extend(["", "## High-signal comparisons", ""])
    for label, comparison in comparisons.items():
        native = comparison["native_libraries"]
        dex = comparison["dex"]
        signature = apks[label]["signature"]
        lines.extend(
            [
                f"### source → {label}",
                f"- Signing certificate matches source: **{comparison['signer_sha256_matches_source']}**",
                f"- Native libraries: {native['unchanged_count']} unchanged; "
                f"{len(native['changed'])} changed; {len(native['added'])} added; {len(native['removed'])} removed.",
                f"- DEX files: {dex['unchanged_count']} unchanged; "
                f"{len(dex['changed'])} changed; {len(dex['added'])} added; {len(dex['removed'])} removed.",
                f"- Decompiled smali tree identical: **{comparison['smali_tree_sha256_equal']}**",
            ]
        )
        if native["changed"] or native["added"] or native["removed"]:
            lines.append(f"- Native file differences: `{', '.join(shorten(native['changed'] + native['added'] + native['removed']))}`")
        if dex["changed"] or dex["added"] or dex["removed"]:
            lines.append(f"- DEX file differences: `{', '.join(shorten(dex['changed'] + dex['added'] + dex['removed']))}`")
        if signature.get("schemes"):
            lines.append(f"- APK signature schemes: `{json.dumps(signature['schemes'], sort_keys=True)}`")
        lines.append("")

    lines.extend(["## Manifest and network-security differences", ""])
    for label, comparison in comparisons.items():
        lines.extend([f"### source → {label}", "", "```diff"])
        diff = comparison["manifest_diff"]
        lines.extend(diff if diff else ["(no decoded manifest/config differences)"])
        lines.extend(["```", ""])

    lines.extend(["## Static code indicators", ""])
    for label, item in apks.items():
        lines.append(f"### {label}")
        smali_scan = item["smali"].get("scan", {})
        native_scan = item["native_string_scan"].get("libraries", {})
        any_hits = False
        for category, findings in smali_scan.items():
            if findings.get("count", 0):
                any_hits = True
                lines.append(f"- Smali `{category}`: {findings['count']} matching lines")
                for sample in findings.get("samples", [])[:3]:
                    sample_text = sample["text"].replace("`", "'")
                    lines.append(f"  - `{sample['file']}:{sample['line']}` — `{sample_text}`")
        for library, categories in native_scan.items():
            for category, findings in categories.items():
                if findings.get("count", 0):
                    any_hits = True
                    lines.append(f"- Native `{library}` / `{category}`: {findings['count']} printable-string hits")
                    for sample in findings.get("samples", [])[:3]:
                        sample_text = sample.replace("`", "'")
                        lines.append(f"  - `{sample_text}`")
        if not any_hits:
            lines.append("- No indicators matched the heuristic scan.")
        lines.append("")

    lines.extend(
        [
            "## Reading this report",
            "",
            "- If the source and rebuilt signer fingerprints differ, the APK was re-signed. This is expected after rebuilding; a local or native certificate check may reject it.",
            "- If native-library hashes are identical, apktool/apk-mitm did not alter those `.so` payloads. The original native code can still detect the changed signer or build.",
            "- Static string matches are leads, not proof. The exact reason for a post-login exit requires the Android crash/exit log from the affected device or an equivalent reproducer.",
            "",
        ]
    )
    return "\n".join(lines)


def emit_github_notices(report: dict[str, object]) -> None:
    """Expose compact findings through the Checks API as well as the artifact."""
    if os.environ.get("GITHUB_ACTIONS", "").lower() != "true":
        return

    apks = report["apks"]
    source_manifest = (apks["source"].get("manifest") or {})
    comparisons = report["comparisons_against_source"]
    for label, item in apks.items():
        signature = item["signature"]
        manifest = item.get("manifest") or {}
        smali_scan = item["smali"].get("scan", {})
        smali_hits = {
            category: finding["count"]
            for category, finding in smali_scan.items()
            if finding.get("count", 0)
        }
        native_hits: dict[str, dict[str, object]] = {}
        for library, categories in item["native_string_scan"].get("libraries", {}).items():
            for category, finding in categories.items():
                if not finding.get("count", 0):
                    continue
                aggregate = native_hits.setdefault(category, {"count": 0, "libraries": []})
                aggregate["count"] = int(aggregate["count"]) + int(finding["count"])
                libraries = aggregate["libraries"]
                if library not in libraries and len(libraries) < 3:
                    libraries.append(library)
        comparison = comparisons.get(label, {})
        native_diff = comparison.get("native_libraries", {})
        dex_diff = comparison.get("dex", {})
        report_summary = {
            "variant": label,
            "sha256": item["sha256"],
            "package": manifest.get("package"),
            "version": manifest.get("version_name"),
            "signer_sha256": signature.get("cert_sha256", []),
            "signature_verified": signature.get("verified"),
            "signature_schemes": signature.get("schemes", {}),
            "signer_matches_source": comparison.get("signer_sha256_matches_source"),
            "native_libraries": {
                "unchanged": native_diff.get("unchanged_count"),
                "changed": native_diff.get("changed", [])[:12],
                "added": native_diff.get("added", [])[:12],
                "removed": native_diff.get("removed", [])[:12],
            },
            "dex": {
                "unchanged": dex_diff.get("unchanged_count"),
                "changed": dex_diff.get("changed", [])[:12],
                "added": dex_diff.get("added", [])[:12],
                "removed": dex_diff.get("removed", [])[:12],
            },
            "smali_tree_identical": comparison.get("smali_tree_sha256_equal"),
            "manifest_changed_fields": sorted(
                key
                for key in set(source_manifest) | set(manifest)
                if source_manifest.get(key) != manifest.get(key)
            ) if label != "source" else [],
            "smali_indicators": smali_hits,
            "native_string_indicators": native_hits,
        }
        encoded = json.dumps(report_summary, separators=(",", ":"), ensure_ascii=True)
        encoded = encoded.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title=CimaCloud {label} forensic result::{encoded}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", action="append", default=[], metavar="LABEL=PATH", help="APK to compare (repeatable; source is required)")
    parser.add_argument("--decoded", action="append", default=[], metavar="LABEL=DIR", help="apktool output directory corresponding to a variant")
    parser.add_argument("--signature", action="append", default=[], metavar="LABEL=PATH", help="apksigner output file corresponding to a variant")
    parser.add_argument("--output-dir", type=Path, help="directory for report.json and summary.md")
    parser.add_argument("--report-json", type=Path, help="re-emit notices from an existing diagnostic report")
    args = parser.parse_args()

    try:
        if args.report_json:
            report = json.loads(args.report_json.read_text(encoding="utf-8"))
            if not isinstance(report, dict) or "apks" not in report:
                raise ValueError("the JSON file is not a CimaCloud APK diagnostic report")
            print(markdown_report(report))
            emit_github_notices(report)
            return 0

        if args.output_dir is None:
            parser.error("--output-dir is required unless --report-json is used")
        apks = parse_pairs(args.apk, "--apk")
        decoded = parse_pairs(args.decoded, "--decoded")
        signatures = parse_pairs(args.signature, "--signature")
        report = build_report(apks, decoded, signatures)
    except (BadZipFile, FileNotFoundError, OSError, ValueError, ET.ParseError, json.JSONDecodeError) as error:
        print(f"APK diagnostic failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 1

    args.output_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.output_dir / "report.json"
    markdown_path = args.output_dir / "summary.md"
    json_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    markdown_path.write_text(markdown_report(report), encoding="utf-8")
    print(markdown_path.read_text(encoding="utf-8"))
    emit_github_notices(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
