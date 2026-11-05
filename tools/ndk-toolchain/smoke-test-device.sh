#!/usr/bin/env bash
# Prove the cross-built toolchain on a real device: push it, compile a C and a C++ file to a shared library
# for EACH target ABI in TARGET_ABIS (default: all four), and check each result is an ELF of the right
# machine. The toolchain pushed is the one built for the device's own ABI (the host half), so a device
# whose ABI was never built for is reported rather than tried.
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

TARGET_ABIS="${TARGET_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"

abi=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
[ -d "$OUT/jniLibs/$abi" ] || { echo "no toolchain for this device's ABI ($abi) under $OUT/jniLibs; run build-llvm-android.sh with HOST_ABI=$abi" >&2; exit 1; }

log "pushing the toolchain to $DEV"
$ADB shell "rm -rf $DEV && mkdir -p $DEV/bin"
$ADB push "$OUT/jniLibs/$abi/." "$DEV/bin/" >/dev/null
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

# <clang target> and the ELF `Machine` line readelf should report, per ABI.
target_of() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android$API AArch64" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi$API ARM" ;;
        x86)         echo "i686-linux-android$API Intel 80386" ;;
        x86_64)      echo "x86_64-linux-android$API Advanced Micro Devices X86-64" ;;
        *) echo "unknown ABI $1" >&2; return 1 ;;
    esac
}

TOOL="LD_LIBRARY_PATH=$DEV/bin $DEV/bin/libllvmtools.so"
log "clang --version"
$ADB shell "$TOOL clang --version"

for t in $TARGET_ABIS; do
    spec="$(target_of "$t")"
    triple="${spec%% *}"; machine="${spec#* }"
    extra=""; [ "$t" = x86 ] && extra="-mstackrealign"

    # The two flags the plugin will always pass. clang derives its resource directory from the path of the
    # running executable, which is wrong once it lives in a flat directory of lib*.so files, so both the
    # resource headers and the sysroot are named explicitly.
    COMMON="--target=$triple -resource-dir $DEV/lib-clang/$LLVM_MAJOR --sysroot $DEV/sysroot $extra -fPIC -O2"
    obj="$DEV/ca-$t"

    log "[$t] compiling C and C++ (--driver-mode=g++, since an APK carries no clang++ symlink)"
    $ADB shell "$TOOL clang $COMMON -c $DEV/ca-smoke.c -o $obj.o"
    $ADB shell "$TOOL clang --driver-mode=g++ $COMMON -std=c++17 -c $DEV/ca-smoke.cpp -o $obj-cpp.o"

    # --ld-path, not -fuse-ld=lld: clang would otherwise look for an `ld.lld` on PATH that does not exist
    # here. The linker's file name is what tells lld to be the GNU flavor -- see stage_package in
    # build-llvm-android.sh.
    log "[$t] linking a shared library with lld"
    $ADB shell "$TOOL clang --driver-mode=g++ $COMMON -shared --ld-path=$DEV/bin/libld-gnu-lld.so \
      $obj.o $obj-cpp.o -llog -o $DEV/libcasmoke-$t.so"

    log "[$t] what came out"
    out="$($ADB shell "$TOOL readelf -h $DEV/libcasmoke-$t.so")"
    echo "$out" | grep -E "Class|Machine|Type"
    echo "$out" | grep -q "Machine:.*$machine" || { echo "FAIL: $t library is not $machine" >&2; exit 1; }
    $ADB shell "$TOOL nm -D --defined-only $DEV/libcasmoke-$t.so" | grep -E "ca_smoke"
done

log "PASS: the $abi device compiled and linked its own shared library for: $TARGET_ABIS"
