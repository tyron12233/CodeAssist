#!/usr/bin/env bash
# Stage a Dart SDK that RUNS ON an Android arm64 device, for the Dart plugin (samples/dart-plugin).
#
# Google publishes no Android build of the Dart SDK. Termux does: its `dart` package is the upstream SDK
# built for android-arm64 against bionic, and its executables need nothing but the system's libc, libdl,
# libm and liblog. This script downloads that package at a pinned version and checksum and splits it the
# way an app has to carry it:
#
#   out/jniLibs/arm64-v8a/   everything that is EXECUTED, or mapped executable by the Dart VM
#     libdartaotruntime.so   bin/dartaotruntime: runs the AOT snapshots below
#     libdartvm.so           bin/dartvm: the JIT VM that runs the user's program
#     libanalysis_server.so  the analysis server (the language server), an AOT snapshot
#     libgen_kernel.so       the kernel compiler Run uses, an AOT snapshot
#     libdartdev.so          the `dart` command-line tool, for `pub get`, an AOT snapshot
#     libfrontend_server.so  the incremental compiler behind Flutter's hot reload, an AOT snapshot
#   out/assets/dart-sdk/     what is only READ: the SDK's libraries, the platform dill, the version
#
# Since Android 10 an app may not exec() a file it wrote, and an AOT snapshot is an ELF the VM maps as
# code, so the first group has to arrive inside the installed APK, under lib*.so names in jniLibs. The
# second group is ordinary data the plugin unpacks on first use.
#
# Usage:  ./fetch-dart-android.sh          (re-running with the package already downloaded is quick)
set -euo pipefail

DART_VERSION="${DART_VERSION:-3.13.7}"
DEB_SHA256="${DEB_SHA256:-a351ce8f5075279971e29b9ce961fd239153547156b7a8d26b5592e1c7782605}"
DEB_URL="${DEB_URL:-https://packages.termux.dev/apt/termux-main/pool/main/d/dart/dart_${DART_VERSION}_aarch64.deb}"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/build}"
DL="$WORK/dl"
EXTRACT="$WORK/extract"
OUT="$WORK/out"
DEB="$DL/dart_${DART_VERSION}_aarch64.deb"

log() { printf '\n==> %s\n' "$*"; }

sha256() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$DL"
if [[ ! -f "$DEB" ]]; then
    log "downloading Dart $DART_VERSION for android-arm64 (Termux, ~96 MB)"
    curl -fL --retry 3 -o "$DEB.part" "$DEB_URL"
    mv "$DEB.part" "$DEB"
fi
actual="$(sha256 "$DEB")"
if [[ "$actual" != "$DEB_SHA256" ]]; then
    echo "checksum mismatch for $DEB: expected $DEB_SHA256, got $actual" >&2
    echo "A different DART_VERSION needs its own DEB_SHA256." >&2
    exit 1
fi

log "unpacking"
rm -rf "$EXTRACT"
mkdir -p "$EXTRACT"
# A .deb is an `ar` archive. BSD ar (macOS) cannot read it, bsdtar can; GNU ar can too.
if command -v bsdtar >/dev/null; then
    (cd "$EXTRACT" && bsdtar -xf "$DEB")
else
    (cd "$EXTRACT" && ar x "$DEB")
fi
tar -xf "$EXTRACT"/data.tar.* -C "$EXTRACT"
SDK="$EXTRACT/data/data/com.termux/files/usr/lib/dart-sdk"
[[ -x "$SDK/bin/dartaotruntime" ]] || { echo "no dart-sdk in the package at $SDK" >&2; exit 1; }

log "staging $OUT"
rm -rf "$OUT"
JNI="$OUT/jniLibs/arm64-v8a"
ASSETS="$OUT/assets/dart-sdk"
mkdir -p "$JNI" "$ASSETS"
cp "$SDK/bin/dartaotruntime" "$JNI/libdartaotruntime.so"
cp "$SDK/bin/dartvm" "$JNI/libdartvm.so"
cp "$SDK/bin/snapshots/analysis_server_aot.dart.snapshot" "$JNI/libanalysis_server.so"
cp "$SDK/bin/snapshots/gen_kernel_aot.dart.snapshot" "$JNI/libgen_kernel.so"
cp "$SDK/bin/snapshots/dartdev_aot.dart.snapshot" "$JNI/libdartdev.so"
cp "$SDK/bin/snapshots/frontend_server_aot.dart.snapshot" "$JNI/libfrontend_server.so"

cp -R "$SDK/lib" "$ASSETS/lib"
cp "$SDK/version" "$SDK/revision" "$SDK/sdk_packages.yaml" "$SDK/LICENSE" "$ASSETS/"
INTERNAL="$ASSETS/lib/_internal"
# The platform dills of the web and wasm compilers: nothing here compiles for the web. About 50 MB.
rm -f "$INTERNAL"/dart2js*.dill "$INTERNAL"/dart2wasm*.dill "$INTERNAL"/ddc*.dill
# `vm_platform_strong.dill` is a symlink into Termux's own prefix, which does not exist in an app. The
# kernel compiler is handed this name, so it becomes a copy of the file it pointed at.
rm -f "$INTERNAL/vm_platform_strong.dill"
cp "$INTERNAL/vm_platform.dill" "$INTERNAL/vm_platform_strong.dill"
if find "$ASSETS" -type l | grep -q .; then
    echo "symlinks left in the staged SDK (an APK cannot carry them):" >&2
    find "$ASSETS" -type l >&2
    exit 1
fi

echo "$DART_VERSION" > "$OUT/DART_VERSION"
log "done"
du -sh "$JNI" "$ASSETS"
ls -l "$JNI"
