#!/bin/bash
# Re-syncs the platform-free ZipCLI sources into this module from a ZipCLI checkout (or the unpacked zip).
#
#   services/zipcli-core/sync-from-zipcli.sh /path/to/zipcli/app
#
# The argument is ZipCLI's `app` directory (the one holding src/, tests/, docs/, CHANGELOG.md). Only the files
# that build on a plain JVM are copied; the Android pieces (MainActivity, ui/, mcp/, SafFileSystem, SafRootStore,
# RoutingFileSystem) and the two commands that need them (SafCommands, McpCommands) are left behind. The
# CodePrimer glue in src/main/kotlin/dev/ide/zipcli is never touched.
set -euo pipefail
SRC="${1:?usage: sync-from-zipcli.sh <zipcli app dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
P=src/main/kotlin/com/zipcli/ke
[ -d "$SRC/$P" ] || { echo "not a ZipCLI app dir: $SRC/$P is missing" >&2; exit 2; }

rm -rf "$HERE/$P/domain" "$HERE/$P/zip" "$HERE/$P/fs" "$HERE/tests" "$HERE/docs" "$HERE/examples"
mkdir -p "$HERE/$P/fs"
cp -r "$SRC/$P/domain" "$HERE/$P/domain"
cp -r "$SRC/$P/zip" "$HERE/$P/zip"
rm -f "$HERE/$P/domain/commands/SafCommands.kt" "$HERE/$P/domain/commands/McpCommands.kt"
for f in FileEntry NioFileSystem VirtualPath TerminalFileSystem; do cp "$SRC/$P/fs/$f.kt" "$HERE/$P/fs/"; done
cp -r "$SRC/tests" "$HERE/tests"
cp -r "$SRC/docs" "$HERE/docs"
cp -r "$SRC/examples" "$HERE/examples"
cp "$SRC/CHANGELOG.md" "$HERE/CHANGELOG.md"

# A new ZipCLI file that reaches for Android, or for a type we left behind, would break the JVM build: say so now.
if grep -rnE "^import (android|androidx)\.|SafRootStore|McpController|McpService|buildSafCommands|buildMcpCommands" "$HERE/$P"; then
  echo "WARNING: the lines above need an Android type; extend the exclusions in this script." >&2
  exit 1
fi
echo "synced. Review with: git -C \"$HERE\" status --short ."
echo "Then run the engine tests: $HERE/tests/run.sh (needs kotlinc)"
