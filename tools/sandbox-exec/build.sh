#!/usr/bin/env bash
# Builds sandboxexec.c into app/ide-android/src/main/jniLibs/<abi>/libsandboxexec.so for the four Android ABIs.
#
# Freestanding and statically linked, so it needs only clang and lld, not the NDK: by default they come from an
# Alpine container (docker); set CLANG=clang to use a local LLVM that has lld. The output is committed, like
# the bundled aapt2.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
out="$repo/app/ide-android/src/main/jniLibs"

build_all() {
    local src="$1" dest="$2"
    for spec in "arm64-v8a aarch64-linux-android" "armeabi-v7a armv7a-linux-androideabi" \
                "x86 i686-linux-android" "x86_64 x86_64-linux-android"; do
        set -- $spec
        local abi="$1" triple="$2" extra=""
        [ "$abi" = "armeabi-v7a" ] && extra="-marm"
        mkdir -p "$dest/$abi"
        # -fno-pic -static: a static program at a fixed address needs no relocation, so no loader is involved.
        # max-page-size 16384: runs on 16 KB-page devices (and passes Play's 16 KB alignment check).
        ${CLANG:-clang} --target="$triple" $extra -Os -Wall -Werror -ffreestanding -fno-builtin -fno-stack-protector \
            -fno-pic -static -nostdlib -fno-asynchronous-unwind-tables -fno-unwind-tables \
            -fuse-ld=lld -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,-s \
            -o "$dest/$abi/libsandboxexec.so" "$src"
        echo "built $abi"
    done
}

if [ "${CLANG:-}" != "" ]; then
    build_all "$here/sandboxexec.c" "$out"
else
    docker run --rm -v "$here:/src:ro" -v "$out:/out" alpine:3.20 sh -c '
        apk add --no-cache bash clang lld >/dev/null &&
        bash -ec "$(cat /src/build.sh | sed -n "/^build_all()/,/^}/p"); build_all /src/sandboxexec.c /out"'
fi
