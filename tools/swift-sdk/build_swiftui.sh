#!/usr/bin/env bash
# Build CodeAssist's SwiftUI engine (samples/swift-plugin/swiftui) as a library for the device: libSwiftUI.so and the
# module `SwiftUI`, so a preview compiles only the user's code against it (about 0.1 s on the emulator instead of
# 2.9 s with the engine's source) and the user's `import SwiftUI` finds it.
#
# It has to be built by exactly the compiler the device runs (Termux's Swift 6.4.3), so it is built in the official
# swift:6.4.3 Linux image, whose compiler is the same release and which carries ld.lld, against the Android SDK that
# stage_swift.py staged for the ABI: the device's own modules, headers and runtime libraries.
#
#   ./build_swiftui.sh <abi>        after `python3 -I stage_swift.py <abi>`
#
# Locally it runs the image with Docker; in CI (already inside swift:6.4.3, SWIFTUI_IN_CONTAINER=1) it runs directly.
# Output, beside what stage_swift.py staged:
#   build/out/<abi>/jniLibs/<abi>/libSwiftUI.so
#   build/out/<abi>/assets/swift/swiftui/SwiftUI.{swiftmodule,swiftinterface,swiftdoc}, symbol-metrics.tsv
set -euo pipefail

ABI="${1:?usage: build_swiftui.sh <arm64-v8a|armeabi-v7a|x86_64>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
IMAGE="${SWIFTUI_IMAGE:-swift:6.4.3}"

case "$ABI" in
    arm64-v8a)   ARCH=aarch64; TARGET=aarch64-unknown-linux-android26; CLANG_TARGET=aarch64-linux-android26 ;;
    armeabi-v7a) ARCH=arm;     TARGET=armv7-unknown-linux-androideabi26; CLANG_TARGET=armv7a-linux-androideabi26 ;;
    x86_64)      ARCH=x86_64;  TARGET=x86_64-unknown-linux-android26; CLANG_TARGET=x86_64-linux-android26 ;;
    *) echo "unknown ABI '$ABI'" >&2; exit 2 ;;
esac

STAGED="$HERE/build/out/$ABI"
EXTRACT="$HERE/build/extract-$ARCH"
[[ -d "$STAGED/assets/swift" && -d "$EXTRACT/lib" ]] || { echo "Stage the toolchain first: python3 -I stage_swift.py $ABI" >&2; exit 1; }

if [[ "${SWIFTUI_IN_CONTAINER:-}" != 1 ]]; then
    exec docker run --rm \
        -e SWIFTUI_IN_CONTAINER=1 \
        -v "$REPO:/repo" -w /repo \
        "$IMAGE" bash tools/swift-sdk/build_swiftui.sh "$ABI"
fi

swift --version 2>&1 | grep -q "swift-6.4.3-RELEASE" || { echo "needs Swift 6.4.3 (the device's compiler), got: $(swift --version 2>&1 | head -1)" >&2; exit 1; }

SDK="$STAGED/assets/swift"
JNI="$STAGED/jniLibs/$ABI"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Bionic's libraries, as empty stand-ins that only carry their names: the device provides the real ones, and Termux's
# sysroot has no link stubs (Termux links against the device's own).
mkdir -p "$WORK/stubs"
for lib in c m dl log; do
    clang --target="$CLANG_TARGET" -shared -nostdlib -Wl,-soname,"lib$lib.so" -o "$WORK/stubs/lib$lib.so" -x c /dev/null
done
cp "$EXTRACT/lib/crtbegin_so.o" "$EXTRACT/lib/crtend_so.o" "$WORK/stubs/"

echo "==> libSwiftUI.so for $ABI ($TARGET)"
cd "$WORK"
swiftc -target "$TARGET" -sdk "$SDK" -resource-dir "$SDK/usr/lib/swift" \
    -module-name SwiftUI -module-link-name SwiftUI \
    -language-mode 5 -enable-library-evolution -emit-module -emit-module-interface -Xfrontend -no-verify-emitted-module-interface \
    -emit-library -parse-as-library -O \
    -use-ld=lld -L "$JNI" -L "$WORK/stubs" -Xclang-linker -nostdlib \
    -Xlinker -soname -Xlinker libSwiftUI.so -Xlinker -rpath -Xlinker '$ORIGIN' \
    "$REPO"/samples/swift-plugin/swiftui/Sources/*.swift -o libSwiftUI.so 2>&1 | grep -v 'warning:' | grep -E 'error' && exit 1 || true
[[ -f libSwiftUI.so ]] || { echo "the build produced no libSwiftUI.so" >&2; exit 1; }

cp libSwiftUI.so "$JNI/"
mkdir -p "$SDK/swiftui"
cp SwiftUI.swiftmodule SwiftUI.swiftinterface SwiftUI.swiftdoc "$SDK/swiftui/"
# SF Symbol frames per point size, measured from iOS (tools/swiftui-parity/SymbolProbe.swift), which the engine reads.
cp "$REPO/samples/swift-plugin/swiftui/symbol-metrics.tsv" "$SDK/swiftui/"
echo "==> done: $JNI/libSwiftUI.so ($(du -h libSwiftUI.so | cut -f1)), $SDK/swiftui/"
