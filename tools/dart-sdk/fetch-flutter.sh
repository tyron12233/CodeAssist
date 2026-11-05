#!/usr/bin/env bash
# Stage what the Dart plugin needs to run Flutter apps on the device, from a Flutter SDK on this machine.
#
# Flutter on the device is debug (JIT) mode, the way `flutter run` works: the IDE compiles the app to kernel
# with the plugin's on-device Dart, and a "player" (an Activity in the plugin's APK, built on the Flutter
# engine) runs it through the Dart VM service, which also takes hot reloads. The kernel the IDE compiles has
# to match the Dart VM inside the engine exactly, so the Flutter version is pinned to the one whose Dart is
# the plugin's (fetch-dart-android.sh): Flutter 3.47.9 is Dart 3.13.7.
#
# What it stages, in build/out/flutter:
#   jniLibs/<abi>/libflutter.so       the engine for each ABI in FLUTTER_ABIS, stripped (396 MB as shipped,
#                                     39 MB stripped, for arm64)
#   root/                             a minimal FLUTTER_ROOT: the framework packages (flutter, flutter_test),
#                                     sky_engine (dart:ui), the patched platform the compiler links against,
#                                     and the version files pub reads. 44 MB, 10 MB compressed
#   player/flutter_assets/            the player's own bundle: a dart:ui stub program and the VM snapshots
#   base-assets/                      what every app's bundle starts from: the Material icons font, the
#                                     framework's compiled shaders, the license notices
#   ENGINE                            the engine revision, which the plugin's Gradle build resolves the
#                                     engine's Java embedding by
#
# Usage:  FLUTTER_HOME=/path/to/flutter ./fetch-flutter.sh
#   FLUTTER_HOME   a Flutter 3.47.9 SDK (any host). Only read, plus two `flutter build bundle` runs.
#   FLUTTER_ABIS   the ABIs to stage an engine for, default "arm64-v8a armeabi-v7a x86_64" (the ABIs there is a
#                  Dart SDK for; see fetch-dart-android.sh). An ABI whose
#                  debug engine this Flutter does not ship is skipped with a warning, not an error.
#   LLVM_OBJCOPY   an llvm-objcopy to strip the engine with; by default the NDK's, from ANDROID_NDK_HOME or
#                  from the archive tools/ndk-toolchain downloads (build/dl/ndk.zip).
set -euo pipefail

FLUTTER_VERSION="${FLUTTER_VERSION:-3.47.9}"
DART_VERSION="${DART_VERSION:-3.13.7}"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/build}"
OUT="$WORK/out/flutter"
TMP="$WORK/flutter-tmp"

log() { printf '\n==> %s\n' "$*"; }
die() { echo "$*" >&2; exit 1; }

[[ -n "${FLUTTER_HOME:-}" && -x "$FLUTTER_HOME/bin/flutter" ]] || die "Set FLUTTER_HOME to a Flutter $FLUTTER_VERSION SDK."
VERSION_JSON="$FLUTTER_HOME/bin/cache/flutter.version.json"
[[ -f "$VERSION_JSON" ]] || "$FLUTTER_HOME/bin/flutter" --version >/dev/null
grep -q "\"frameworkVersion\": \"$FLUTTER_VERSION\"" "$VERSION_JSON" || die "FLUTTER_HOME is not Flutter $FLUTTER_VERSION ($VERSION_JSON)."
grep -q "\"dartSdkVersion\": \"$DART_VERSION\"" "$VERSION_JSON" || die "Flutter $FLUTTER_VERSION does not bundle Dart $DART_VERSION."
ENGINE="$(cat "$FLUTTER_HOME/bin/internal/engine.version")"
export PATH="$FLUTTER_HOME/bin:$PATH"

rm -rf "$OUT" "$TMP"
mkdir -p "$OUT" "$TMP"

log "precaching the Android engine artifacts"
flutter precache --android --no-ios --no-web --no-linux --no-macos --no-windows >/dev/null

log "engine $ENGINE: stripping libflutter.so"
OBJCOPY="${LLVM_OBJCOPY:-}"
if [[ -z "$OBJCOPY" ]]; then
    for host in darwin-x86_64 linux-x86_64; do
        candidate="${ANDROID_NDK_HOME:-/nonexistent}/toolchains/llvm/prebuilt/$host/bin/llvm-objcopy"
        [[ -x "$candidate" ]] && OBJCOPY="$candidate" && break
    done
fi
if [[ -z "$OBJCOPY" ]]; then
    NDK_ZIP="${NDK_ZIP:-$HERE/../ndk-toolchain/build/dl/ndk.zip}"
    [[ -f "$NDK_ZIP" ]] || die "No llvm-objcopy: set LLVM_OBJCOPY or ANDROID_NDK_HOME, or run tools/ndk-toolchain first."
    host="$([[ "$(uname)" == Darwin ]] && echo darwin-x86_64 || echo linux-x86_64)"
    unzip -q -o -j "$NDK_ZIP" "*/toolchains/llvm/prebuilt/$host/bin/llvm-objcopy" -d "$TMP"
    OBJCOPY="$TMP/llvm-objcopy"
    chmod +x "$OBJCOPY"
fi

# Flutter names its engine directories by CPU (android-arm64, android-arm, android-x64, android-x86), and the
# jar holds the library under the Android ABI name. Same engine revision for all of them.
engine_dir() {
    case "$1" in
        arm64-v8a)   echo android-arm64 ;;
        armeabi-v7a) echo android-arm ;;
        x86_64)      echo android-x64 ;;
        x86)         echo android-x86 ;;
        *) return 1 ;;
    esac
}
staged=0
for abi in ${FLUTTER_ABIS:-arm64-v8a armeabi-v7a x86_64}; do
    dir="$(engine_dir "$abi")" || die "unknown ABI '$abi' in FLUTTER_ABIS"
    jar="$FLUTTER_HOME/bin/cache/artifacts/engine/$dir/flutter.jar"
    if [[ ! -f "$jar" ]] || ! unzip -l "$jar" "lib/$abi/libflutter.so" >/dev/null 2>&1; then
        echo "WARNING: no debug engine for $abi in this Flutter ($jar); the plugin will carry no Flutter for it." >&2
        continue
    fi
    mkdir -p "$OUT/jniLibs/$abi"
    unzip -q -o -j "$jar" "lib/$abi/libflutter.so" -d "$TMP"
    "$OBJCOPY" --strip-unneeded "$TMP/libflutter.so" "$OUT/jniLibs/$abi/libflutter.so"
    rm -f "$TMP/libflutter.so"
    staged=$((staged + 1))
done
[[ $staged -gt 0 ]] || die "no engine staged for any ABI in FLUTTER_ABIS."

log "staging FLUTTER_ROOT"
R="$OUT/root"
mkdir -p "$R/packages/flutter" "$R/packages/flutter_test" "$R/bin/cache/pkg" "$R/bin/cache/artifacts/engine/common"
cp -R "$FLUTTER_HOME/packages/flutter/lib" "$FLUTTER_HOME/packages/flutter/pubspec.yaml" "$FLUTTER_HOME/packages/flutter/LICENSE" "$R/packages/flutter/"
cp -R "$FLUTTER_HOME/packages/flutter_test/lib" "$FLUTTER_HOME/packages/flutter_test/pubspec.yaml" "$R/packages/flutter_test/"
cp -R "$FLUTTER_HOME/bin/cache/pkg/sky_engine" "$R/bin/cache/pkg/"
cp -R "$FLUTTER_HOME/bin/cache/artifacts/engine/common/flutter_patched_sdk" "$R/bin/cache/artifacts/engine/common/"
cp "$VERSION_JSON" "$R/bin/cache/flutter.version.json"
echo "$FLUTTER_VERSION" > "$R/version"
cp "$FLUTTER_HOME/LICENSE" "$R/LICENSE"

bundle() { # $1 = project dir; builds its debug bundle into $1/build/flutter_assets
    (cd "$1" && flutter build bundle --debug >/dev/null)
}

log "building the player's bundle"
P="$TMP/player"
mkdir -p "$P/lib"
cp "$HERE/flutter-player/pubspec.yaml" "$P/pubspec.yaml"
cp "$HERE/flutter-player/main.dart" "$P/lib/main.dart"
bundle "$P"
mkdir -p "$OUT/player/flutter_assets"
for f in kernel_blob.bin isolate_snapshot_data vm_snapshot_data AssetManifest.bin FontManifest.json NativeAssetsManifest.json; do
    cp "$P/build/flutter_assets/$f" "$OUT/player/flutter_assets/"
done

log "collecting the base assets every app bundle starts from"
B="$TMP/base"
mkdir -p "$B/lib"
printf 'name: base\npublish_to: none\nenvironment:\n  sdk: ^3.9.0\ndependencies:\n  flutter:\n    sdk: flutter\nflutter:\n  uses-material-design: true\n' > "$B/pubspec.yaml"
printf "import 'package:flutter/material.dart';\nvoid main() => runApp(const MaterialApp(home: Text('')));\n" > "$B/lib/main.dart"
bundle "$B"
mkdir -p "$OUT/base-assets"
cp -R "$B/build/flutter_assets/fonts" "$B/build/flutter_assets/shaders" "$B/build/flutter_assets/NOTICES.Z" "$OUT/base-assets/"

echo "$ENGINE" > "$OUT/ENGINE"
echo "$FLUTTER_VERSION" > "$OUT/FLUTTER_VERSION"
rm -rf "$TMP"
log "done"
du -sh "$OUT"/*
