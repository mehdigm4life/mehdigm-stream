#!/usr/bin/env python3
"""Derive a stable MediaFire file page and refresh its temporary download URL."""

from __future__ import annotations

import argparse
import html
import re
import sys
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

DOWNLOAD_HOST = re.compile(r"^download[0-9]+\.mediafire\.com$", re.IGNORECASE)
URL_PATTERN = re.compile(r"https?://download[0-9]+\.mediafire\.com/[^\s\"'<>\\]+", re.IGNORECASE)


def page_info(direct_url: str) -> tuple[str, str]:
    parsed = urlsplit(direct_url)
    if parsed.scheme != "https" or not DOWNLOAD_HOST.fullmatch(parsed.hostname or ""):
        raise ValueError("Expected an HTTPS URL on a download-numbered MediaFire host")
    segments = [segment for segment in parsed.path.split("/") if segment]
    if len(segments) < 3:
        raise ValueError("The direct MediaFire path does not contain a file key and filename")
    file_key = unquote(segments[-2])
    filename = unquote(segments[-1])
    if not re.fullmatch(r"[A-Za-z0-9_-]{8,32}", file_key):
        raise ValueError(f"Unexpected MediaFire file key: {file_key!r}")
    page_url = (
        "https://www.mediafire.com/file/"
        f"{quote(file_key, safe='')}/{quote(filename, safe='+')}/file"
    )
    return page_url, file_key


def extract_download_url(page_html: str, expected_file_key: str) -> str:
    normalized = html.unescape(page_html)
    normalized = (
        normalized.replace(r"\/", "/")
        .replace(r"\u0026", "&")
        .replace(r"\u003d", "=")
    )
    candidates: list[str] = []
    for match in URL_PATTERN.finditer(normalized):
        candidate = match.group().rstrip(".,;)")
        parsed = urlsplit(candidate)
        segments = [unquote(segment) for segment in parsed.path.split("/") if segment]
        if DOWNLOAD_HOST.fullmatch(parsed.hostname or "") and expected_file_key in segments:
            if candidate not in candidates:
                candidates.append(candidate)
    if not candidates:
        raise ValueError(
            "The MediaFire file page did not expose a fresh download URL for "
            f"file key {expected_file_key!r}"
        )
    return candidates[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    page_parser = subparsers.add_parser("page-info", help="derive the stable file page from a direct URL")
    page_parser.add_argument("direct_url")
    extract_parser = subparsers.add_parser("extract", help="extract a refreshed direct URL from saved page HTML")
    extract_parser.add_argument("page_html", type=Path)
    extract_parser.add_argument("file_key")
    args = parser.parse_args()

    try:
        if args.command == "page-info":
            page_url, file_key = page_info(args.direct_url)
            print(page_url)
            print(file_key)
        else:
            print(extract_download_url(args.page_html.read_text(encoding="utf-8", errors="replace"), args.file_key))
    except (OSError, ValueError) as error:
        print(f"MediaFire URL resolution failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
