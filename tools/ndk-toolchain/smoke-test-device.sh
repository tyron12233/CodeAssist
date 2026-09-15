#!/usr/bin/env bash
# Prove the cross-built toolchain on a real arm64 device: push it, compile a C and a C++ file to a shared
# library, and check the result is an AArch64 Android ELF.
#
# It runs out of /data/local/tmp, which the shell user may execute from. That is NOT where the plugin will
# run it (an app gets its own unpacked lib directory instead, and may not execute from its data dir at all),
# but it is the cheapest place to find out whether the binaries work at all, with no APK in the way.
#
# Usage: ./smoke-test-device.sh [out-dir]
set -euo pipefail

OUT="${1:-build/out}"
ADB="${ADB:-adb}"
DEV=/data/local/tmp/ca-toolchain
API="${ANDROID_API:-26}"
LLVM_MAJOR="${LLVM_MAJOR:-18}"

log() { printf '\n==> %s\n' "$*"; }

[ -d "$OUT/jniLibs/arm64-v8a" ] || { echo "no build output at $OUT; run build-llvm-android.sh package" >&2; exit 1; }

abi=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
[ "$abi" = "arm64-v8a" ] || { echo "device is $abi; this toolchain is arm64-v8a only" >&2; exit 1; }

log "pushing the toolchain to $DEV"
$ADB shell "rm -rf $DEV && mkdir -p $DEV/bin"
$ADB push "$OUT/jniLibs/arm64-v8a/." "$DEV/bin/" >/dev/null
$ADB push "$OUT/assets/toolchain/." "$DEV/" >/dev/null
$ADB shell "chmod 755 $DEV/bin/*"

cat > /tmp/ca-smoke.c <<'EOF'
#include <stdio.h>
#include <android/log.h>
int ca_smoke(int x) { __android_log_print(4, "ca", "%d", x); return x * 2; }
EOF

cat > /tmp/ca-smoke.cpp <<'EOF'
#include <string>
#include <vector>
#include <memory>
extern "C" int ca_smoke_cpp() {
    auto v = std::make_unique<std::vector<std::string>>();
    v->push_back("hello");
    return static_cast<int>(v->at(0).size());
}
EOF

$ADB push /tmp/ca-smoke.c /tmp/ca-smoke.cpp "$DEV/" >/dev/null

# The two flags the plugin will always pass. clang derives its resource directory from the path of the
# running executable, which is wrong once it lives in a flat directory of lib*.so files, so both the resource
# headers and the sysroot are named explicitly.
COMMON="--target=aarch64-linux-android$API \
  -resource-dir $DEV/lib-clang/$LLVM_MAJOR \
  --sysroot $DEV/sysroot \
  -fPIC -O2"

log "clang --version"
$ADB shell "LD_LIBRARY_PATH=$DEV/bin $DEV/bin/libllvmtools.so clang --version"

log "compiling C to an object"
$ADB shell "LD_LIBRARY_PATH=$DEV/bin $DEV/bin/libllvmtools.so clang $COMMON -c $DEV/ca-smoke.c -o $DEV/ca-smoke.o"

log "compiling C++ (--driver-mode=g++, since an APK carries no clang++ symlink)"
$ADB shell "LD_LIBRARY_PATH=$DEV/bin $DEV/bin/libllvmtools.so clang --driver-mode=g++ $COMMON -std=c++17 -c $DEV/ca-smoke.cpp -o $DEV/ca-smoke-cpp.o"

# --ld-path, not -fuse-ld=lld: clang would otherwise look for an `ld.lld` on PATH that does not exist here.
# The linker's file name is what tells lld to be the GNU flavor -- see stage_package in build-llvm-android.sh.
log "linking a shared library with lld"
$ADB shell "LD_LIBRARY_PATH=$DEV/bin $DEV/bin/libllvmtools.so clang $COMMON -shared \
  --ld-path=$DEV/bin/libld-gnu-lld.so \
  $DEV/ca-smoke.o $DEV/ca-smoke-cpp.o -llog -o $DEV/libcasmoke.so"

log "what came out"
$ADB shell "LD_LIBRARY_PATH=$DEV/bin $DEV/bin/libllvmtools.so readelf -h $DEV/libcasmoke.so" \
    | grep -E "Class|Machine|Type"
$ADB shell "LD_LIBRARY_PATH=$DEV/bin $DEV/bin/libllvmtools.so nm -D --defined-only $DEV/libcasmoke.so" \
    | grep -E "ca_smoke"

log "PASS: the device compiled and linked its own arm64 shared library"
