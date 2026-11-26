#!/usr/bin/env python3
"""Print concise identity/signing/native-method metadata for the downloaded APK."""

from __future__ import annotations

import hashlib
import json
import os
import struct
import sys
from pathlib import Path
from zipfile import ZipFile

from androguard.core.apk import APK
from androguard.core.dex import DEX


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def apk_signing_block_sha256(path: Path) -> str | None:
    data = path.read_bytes()
    tail = data[-min(len(data), 0x10015) :]
    eocd_offset = tail.rfind(bytes((0x50, 0x4B, 0x05, 0x06)))
    if eocd_offset < 0 or eocd_offset + 22 > len(tail):
        return None

    central_directory_offset = struct.unpack_from("<I", tail, eocd_offset + 16)[0]
    if central_directory_offset < 24 or central_directory_offset > len(data):
        return None
    footer = data[central_directory_offset - 24 : central_directory_offset]
    block_size, magic = struct.unpack("<Q16s", footer)
    if magic != b"APK Sig Block 42":
        return None

    block_start = central_directory_offset - (block_size + 8)
    if block_start < 0:
        return None
    header_size = struct.unpack_from("<Q", data, block_start)[0]
    if header_size != block_size:
        return None
    return sha256(data[block_start:central_directory_offset])


def analyze(apk_path: str) -> dict:
    path = Path(apk_path)
    apk = APK(str(path))
    certificates = [sha256(cert.dump()) for cert in apk.get_certificates()]
    native_libraries = []
    native_methods = []

    with ZipFile(path) as archive:
        for name in archive.namelist():
            if name.startswith("lib/") and name.endswith("/libnative-lib.so"):
                data = archive.read(name)
                native_libraries.append({"path": name, "size": len(data), "sha256": sha256(data)})

        dex_names = sorted(
            name
            for name in archive.namelist()
            if name == "classes.dex"
            or (name.startswith("classes") and name.endswith(".dex") and name[7:-4].isdigit())
        )
        for dex_name in dex_names:
            dex = DEX(archive.read(dex_name))
            for clazz in dex.get_classes():
                class_name = clazz.get_name()
                for method in clazz.get_methods():
                    if method.get_name() not in {"buildSecure", "decryptResponse"}:
                        continue
                    access_flags = method.get_access_flags()
                    native_methods.append(
                        {
                            "dex": dex_name,
                            "class": class_name,
                            "name": method.get_name(),
                            "descriptor": method.get_descriptor(),
                            "native": bool(access_flags & 0x100),
                            "has_bytecode": method.get_code() is not None,
                        }
                    )

    return {
        "package": apk.get_package(),
        "version_name": apk.get_androidversion_name(),
        "version_code": apk.get_androidversion_code(),
        "apk_size": path.stat().st_size,
        "apk_sha256": sha256(path.read_bytes()),
        "apk_sig_block_sha256": apk_signing_block_sha256(path),
        "signing_cert_sha256": certificates,
        "native_libraries": sorted(native_libraries, key=lambda item: item["path"]),
        "native_methods": native_methods,
    }


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} APK_PATH", file=sys.stderr)
        return 2
    try:
        report = analyze(sys.argv[1])
    except Exception as error:  # Keep the workflow failure diagnostic, but avoid swallowing it.
        print(f"APK analysis failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 1

    encoded = json.dumps(report, separators=(",", ":"), ensure_ascii=True)
    command_value = encoded.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::notice title=Official CimaCloud APK identity and native metadata::{command_value}")
    print(json.dumps(report, indent=2, ensure_ascii=True))

    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        arm64 = next(
            (item["sha256"] for item in report["native_libraries"] if item["path"] == "lib/arm64-v8a/libnative-lib.so"),
            "",
        )
        outputs = {
            "apk_size": report["apk_size"],
            "apk_sig_block_sha256": report["apk_sig_block_sha256"] or "",
            "cert_sha256": report["signing_cert_sha256"][0] if report["signing_cert_sha256"] else "",
            "native_lib_sha256": arm64,
        }
        with open(output_path, "a", encoding="utf-8") as output:
            for name, value in outputs.items():
                output.write(f"{name}={value}" + chr(10))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
