#!/usr/bin/env bash
# Build the Dart SDK's gen_snapshot as an Android executable, for release (AOT) builds of Flutter apps on the device.
#
# A Flutter release APK carries the app compiled to machine code (libapp.so), made by gen_snapshot from the app's
# kernel. The engine only loads a snapshot made by a gen_snapshot built like its own Dart VM: the same Dart
# revision (Flutter 3.47.9 is Dart 3.13.7), product mode, and, on 64-bit targets, compressed pointers. Flutter
# publishes gen_snapshot only for x64 desktop hosts, and Termux's Dart has one built without compressed pointers,
# so the engine would refuse what it makes. Dart's own build makes the right one: `--os android` builds an Android
# executable, and the `c` architectures (arm64c, x64c) are compressed pointers.
#
# Usage:  ARCH=arm64c ./build-gen-snapshot.sh
#   ARCH          arm64c (arm64-v8a devices), x64c (x86_64), arm (armeabi-v7a). Default arm64c.
#   DART_VERSION  the Dart tag, default 3.13.7 (the Dart of the Flutter the plugin pins).
#   WORK          where the checkout goes, default build/gen-snapshot. About 6 GB with the NDK and toolchains.
#
# Output: build/out/gen-snapshot/<abi>/gen_snapshot. Runs on Linux or macOS (Dart cross-builds Android from both).
set -euo pipefail

DART_VERSION="${DART_VERSION:-3.13.7}"
ARCH="${ARCH:-arm64c}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/build/gen-snapshot}"
OUT="${OUT:-$HERE/build/out/gen-snapshot}"
SDK_URL="https://dart.googlesource.com/sdk.git"

log() { printf '\n==> %s\n' "$*"; }
die() { echo "$*" >&2; exit 1; }

case "$ARCH" in
    arm64c) ABI=arm64-v8a; ELF_MACHINE="aarch64"; POINTERS="compressed-pointers" ;;
    x64c)   ABI=x86_64;    ELF_MACHINE="x86-64";  POINTERS="compressed-pointers" ;;
    arm)    ABI=armeabi-v7a; ELF_MACHINE="ARM";   POINTERS="" ;;  # 32-bit: no pointers to compress
    *) die "ARCH must be arm64c, x64c or arm (got '$ARCH')." ;;
esac

mkdir -p "$WORK" "$OUT/$ABI"
cd "$WORK"

if [[ ! -d depot_tools ]]; then
    log "fetching depot_tools"
    git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
export PATH="$WORK/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0

if [[ ! -d sdk ]]; then
    log "fetching Dart $DART_VERSION"
    git clone --branch "$DART_VERSION" --depth 1 --no-tags "$SDK_URL" sdk
fi
[[ "$(cat sdk/tools/VERSION | awk '/^MAJOR/{a=$2}/^MINOR/{b=$2}/^PATCH/{c=$2}END{print a"."b"."c}')" == "$DART_VERSION" ]] \
    || die "sdk/ is not Dart $DART_VERSION; remove $WORK/sdk and run again."

log "syncing dependencies (with the Android NDK)"
gclient config --name sdk --custom-var download_android_deps=True --unmanaged "$SDK_URL"
for attempt in 1 2 3; do
    gclient sync --no-history --jobs 8 && break
    [[ $attempt == 3 ]] && die "gclient sync failed three times."
    sleep $((attempt * 30))
done

log "building gen_snapshot: product, android, $ARCH"
cd sdk
./tools/build.py --no-rbe --mode product --arch "$ARCH" --os android gen_snapshot

BUILD_DIR="out/ProductAndroid$(echo "$ARCH" | tr '[:lower:]' '[:upper:]')"
# The Android executable, not a host tool built along the way: the one `file` calls an ELF for the target.
BIN=""
for candidate in "$BUILD_DIR/exe.stripped/gen_snapshot" "$BUILD_DIR/gen_snapshot"; do
    if [[ -f "$candidate" ]] && file "$candidate" | grep -q "$ELF_MACHINE"; then BIN="$candidate"; break; fi
done
[[ -n "$BIN" ]] || { find "$BUILD_DIR" -maxdepth 3 -name 'gen_snapshot*' -exec file {} \; >&2; die "no $ELF_MACHINE gen_snapshot in $BUILD_DIR"; }
cp "$BIN" "$OUT/$ABI/gen_snapshot"

log "checking it"
file "$OUT/$ABI/gen_snapshot"
# What the engine compares a snapshot against: the snapshot hash of the Dart revision, and the build's features.
echo "snapshot hash: $(strings -n 32 "$OUT/$ABI/gen_snapshot" | grep -E '^[0-9a-f]{32}$' | sort -u | tr '\n' ' ')"
if [[ -n "$POINTERS" ]]; then
    strings "$OUT/$ABI/gen_snapshot" | grep -q -x " $POINTERS" \
        || die "gen_snapshot was not built with '$POINTERS', so the Flutter engine would refuse its snapshots."
    echo "pointers: $POINTERS"
fi
echo "$DART_VERSION" > "$OUT/DART_VERSION"
log "done: $OUT/$ABI/gen_snapshot ($(du -h "$OUT/$ABI/gen_snapshot" | cut -f1))"
