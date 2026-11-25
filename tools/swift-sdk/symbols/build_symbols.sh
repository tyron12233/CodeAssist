#!/usr/bin/env bash
# The icons SwiftUI previews draw for SF Symbols. SF Symbols may not be shipped or drawn off Apple's devices, so each
# SF name in sf-to-material.tsv is drawn with the closest icon of Google's Material Symbols Rounded (Apache License
# 2.0), a variable font whose FILL axis gives the `.fill` forms and whose wght axis follows the symbol's weight.
#
# Downloads the font at a pinned commit (checksummed), subsets it to the mapped icons (pinning the GRAD axis, which iOS
# has no equivalent of) and writes, into the Swift plugin's assets:
#   symbols/MaterialSymbolsRounded-subset.ttf   ~330 KB
#   symbols/sf-symbols.map                      <sf name> TAB <codepoint hex>
#   symbols/MaterialSymbols-LICENSE.txt
#
# Needs Python with fontTools (`pip install fonttools`); FONTTOOLS_PYTHON names that Python.
#
# Usage: ./build_symbols.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../../samples/swift-plugin/src/main/assets/symbols"
PY="${FONTTOOLS_PYTHON:-python3}"
COMMIT=10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7
BASE="https://raw.githubusercontent.com/google/material-design-icons/$COMMIT"
FONT_SHA=7e74e36533ccee12c2b81e75bfcf3ccf123d3bb7e7e46d65d94445f23db0c97c
CODEPOINTS_SHA=a7404d9e5a1b63f496cf05ace45d386075886b5a7f123fba9f29527cb6006fde

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

curl -fsSL "$BASE/variablefont/MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf" -o "$WORK/font.ttf"
curl -fsSL "$BASE/variablefont/MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.codepoints" -o "$WORK/codepoints"
curl -fsSL "$BASE/LICENSE" -o "$WORK/LICENSE"
[[ "$(sha "$WORK/font.ttf")" == "$FONT_SHA" ]] || { echo "font checksum mismatch" >&2; exit 1; }
[[ "$(sha "$WORK/codepoints")" == "$CODEPOINTS_SHA" ]] || { echo "codepoints checksum mismatch" >&2; exit 1; }

mkdir -p "$OUT"
"$PY" - "$HERE/sf-to-material.tsv" "$WORK/codepoints" "$OUT/sf-symbols.map" "$WORK/unicodes" <<'EOF'
import sys
table, codepoints, out, unicodes = sys.argv[1:]
cp = dict(line.split() for line in open(codepoints) if line.strip())
rows = [l.rstrip("\n").split("\t") for l in open(table) if l.strip() and not l.startswith("#")]
missing = [m for _, m in rows if m not in cp]
if missing:
    sys.exit(f"not in Material Symbols: {missing}")
with open(out, "w") as f:
    f.write("# SF Symbol name -> Material Symbols Rounded codepoint (tools/swift-sdk/symbols/build_symbols.sh)\n")
    for sf, m in rows:
        f.write(f"{sf}\t{cp[m]}\n")
open(unicodes, "w").write(",".join(sorted({"U+" + cp[m] for _, m in rows})))
EOF
"$PY" -m fontTools.subset "$WORK/font.ttf" --unicodes="$(cat "$WORK/unicodes")" --layout-features='' --no-hinting \
    --output-file="$WORK/subset.ttf"
"$PY" -m fontTools.varLib.instancer "$WORK/subset.ttf" GRAD=0 -o "$OUT/MaterialSymbolsRounded-subset.ttf"
cp "$WORK/LICENSE" "$OUT/MaterialSymbols-LICENSE.txt"
ls -la "$OUT"
