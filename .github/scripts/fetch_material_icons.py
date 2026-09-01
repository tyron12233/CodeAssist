#!/usr/bin/env python3
"""Regenerate the bundled Material Symbols subset that ships with the Icon Manager.

The Icon Manager browses icons from pluggable repositories (`platform.iconRepository`). One of them is
always available offline: a curated subset of Material Symbols committed as a resource. This script
regenerates that resource so refreshing it is a reproducible step rather than a manual chore.

    python3 .github/scripts/fetch_material_icons.py [--count 300] [--cache .icon-cache]

It picks the most-used icons by Google's own popularity metadata, downloads the outlined and filled
24px SVGs for each from google/material-design-icons, and writes one tab-separated row per icon.

Every Material Symbols SVG is a single `<path>` in a 960-unit box whose viewBox starts at y=-960, with no
transform, style or fill attribute. The script asserts that rather than assuming it, and fails loudly if
upstream ever changes shape. Because the geometry is that uniform, the shared viewport and offset live in
the file header and each row carries only the path data.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

METADATA_URL = "https://fonts.google.com/metadata/icons?incomplete=1&key=material_symbols"
SVG_URL = ("https://raw.githubusercontent.com/google/material-design-icons/master"
           "/symbols/web/{name}/materialsymbolsoutlined/{name}{suffix}_24px.svg")
OUT_PATH = "android-support/src/main/resources/dev/ide/android/support/icons/material-symbols.tsv"

SYMBOLS_FAMILY = "Material Symbols Outlined"
EXPECTED_VIEWBOX = "0 -960 960 960"
MAX_KEYWORDS = 8
MAX_KEYWORD_LEN = 20


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "codeassist-icon-set-generator"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def load_metadata(cache: str) -> list[dict]:
    path = os.path.join(cache, "icons.json")
    if not os.path.exists(path):
        os.makedirs(cache, exist_ok=True)
        with open(path, "wb") as f:
            f.write(fetch(METADATA_URL))
    raw = open(path, encoding="utf-8").read()
    # The endpoint prefixes its JSON with an anti-JSON-hijacking guard, `)]}'`.
    return json.loads(raw[raw.index("{"):])["icons"]


def curate(icons: list[dict], count: int) -> list[dict]:
    available = [i for i in icons if SYMBOLS_FAMILY not in i.get("unsupported_families", [])]
    return sorted(available, key=lambda i: (-i["popularity"], i["name"]))[:count]


def svg_path_data(cache: str, name: str, filled: bool) -> str:
    suffix = "_fill1" if filled else ""
    local = os.path.join(cache, "svg", f"{name}__{'filled' if filled else 'outlined'}.svg")
    if not os.path.exists(local):
        os.makedirs(os.path.dirname(local), exist_ok=True)
        with open(local, "wb") as f:
            f.write(fetch(SVG_URL.format(name=name, suffix=suffix)))
    svg = open(local, encoding="utf-8").read()

    viewbox = re.search(r'viewBox="([^"]+)"', svg)
    if not viewbox or viewbox.group(1) != EXPECTED_VIEWBOX:
        raise SystemExit(f"{name}: expected viewBox {EXPECTED_VIEWBOX!r}, got {viewbox and viewbox.group(1)!r}. "
                         "Upstream changed shape: teach the reader about it before regenerating.")
    paths = re.findall(r"<path\b[^>]*>", svg)
    if len(paths) != 1:
        raise SystemExit(f"{name}: expected exactly one <path>, found {len(paths)}.")
    extra = [a for a in re.findall(r"\s([a-zA-Z-]+)=", paths[0]) if a != "d"]
    if extra:
        raise SystemExit(f"{name}: <path> carries unexpected attributes {extra}.")
    data = re.search(r'\sd="([^"]*)"', paths[0])
    if not data or not data.group(1):
        raise SystemExit(f"{name}: <path> has no usable d attribute.")
    return data.group(1)


def keywords(icon: dict) -> str:
    """The icon's tags, minus anything the name already says, shortest first and capped."""
    name_words = set(icon["name"].split("_"))
    picked = []
    for tag in icon.get("tags", []):
        low = tag.lower()
        if low in name_words or len(low) > MAX_KEYWORD_LEN or "\t" in low:
            continue
        if low not in picked:
            picked.append(low)
    picked.sort(key=len)
    return ",".join(picked[:MAX_KEYWORDS])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=300, help="how many icons to bundle (default 300)")
    parser.add_argument("--cache", default=".icon-cache", help="download cache directory")
    parser.add_argument("--out", default=OUT_PATH)
    args = parser.parse_args()

    curated = curate(load_metadata(args.cache), args.count)
    print(f"curated {len(curated)} icons; downloading {len(curated) * 2} SVGs...", file=sys.stderr)

    with ThreadPoolExecutor(max_workers=8) as pool:
        outlined = list(pool.map(lambda i: svg_path_data(args.cache, i["name"], False), curated))
        filled = list(pool.map(lambda i: svg_path_data(args.cache, i["name"], True), curated))

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Bundled Material Symbols subset for the Icon Manager: the repository that works offline.\n")
        f.write(f"# {len(curated)} icons, outlined + filled, ordered by Google's popularity metadata.\n")
        f.write("# Source: github.com/google/material-design-icons (Apache-2.0).\n")
        f.write("# Regenerate with .github/scripts/fetch_material_icons.py. Do not hand-edit.\n")
        f.write("!version 1\n")
        f.write("!viewport 960 960\n")
        f.write("!offset 0 -960\n")
        f.write("!columns name category keywords outlined filled\n")
        for icon, out_d, fill_d in zip(curated, outlined, filled):
            category = (icon.get("categories") or ["Other"])[0]
            f.write("\t".join([icon["name"], category, keywords(icon), out_d, fill_d]) + "\n")

    size = os.path.getsize(args.out)
    print(f"wrote {args.out} ({size // 1024} KiB, {len(curated)} icons)", file=sys.stderr)


if __name__ == "__main__":
    main()
