#!/usr/bin/env bash
# Assemble the .NET runtime and C# compiler that RUN ON an Android device, for the C# plugin.
#
# Nothing here compiles .NET. Microsoft publishes a runtime built against Android's libc (the
# `linux-bionic-*` runtime packs), the reference assemblies, and the Roslyn compiler as signed NuGet
# packages; this script downloads the pinned versions, checks each against the SHA-256 recorded below,
# and lays the files out as a DOTNET_ROOT. Two pieces are built from source with the NDK:
#
#   libdotnetlaunch.so   launcher/dotnetlaunch.c, which starts a program through hostfxr
#   libssl / libcrypto   OpenSSL, which the runtime's cryptography loads and a device does not offer to apps
#
# Output, under build/out/<abi>/:
#   jniLibs/<abi>/libdotnetlaunch.so   the one file that is executed (an APK's native library directory)
#   assets/dotnet-sdk.zip              the tree that is only read, unpacked by the plugin on first use:
#       host/fxr/<version>/libhostfxr.so
#       shared/Microsoft.NETCore.App/<version>/   the runtime and its managed libraries
#       sdk/Roslyn/                               csc.dll and the compiler assemblies
#       ref/                                      reference assemblies and their XML documentation
#       lib/                                      libssl.so.3, libcrypto.so.3
#
# Usage: ./build-dotnet-android.sh [stage...]     stages: fetch tree openssl launcher package (default: all)
#   HOST_ABI=arm64-v8a|x86_64   the device ABI to build for (default arm64-v8a)
#   ANDROID_NDK_HOME=<dir>      an NDK to build with; downloaded into build/ when unset
set -euo pipefail
cd "$(dirname "$0")"

DOTNET_VERSION="${DOTNET_VERSION:-10.0.15}"
ROSLYN_VERSION="${ROSLYN_VERSION:-5.0.0}"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.9}"
NDK_VERSION="${NDK_VERSION:-r27c}"
ANDROID_API="${ANDROID_API:-26}"
HOST_ABI="${HOST_ABI:-arm64-v8a}"

case "$HOST_ABI" in
    arm64-v8a) RID=linux-bionic-arm64; OPENSSL_TARGET=android-arm64; TRIPLE=aarch64-linux-android ;;
    x86_64)    RID=linux-bionic-x64;   OPENSSL_TARGET=android-x86_64; TRIPLE=x86_64-linux-android ;;
    *) echo "no .NET runtime is published for $HOST_ABI (arm64-v8a and x86_64 only)" >&2; exit 1 ;;
esac

# SHA-256 of each download. A new version needs its hash recorded here; a mismatch stops the build.
sha_of() {
    case "$1" in
        microsoft.netcore.app.runtime.linux-bionic-arm64.10.0.15.nupkg) echo 3e4c27df1cb174f98b294621cfe710f9838d8b4b7bc9929c82507bc085c8f841 ;;
        microsoft.netcore.app.runtime.linux-bionic-x64.10.0.15.nupkg)   echo 4b254d514c7258862329628a46e404acc691990bcc684b1db1e63af637632fb2 ;;
        microsoft.netcore.app.ref.10.0.15.nupkg)                        echo 71d5c46b014531d3226cb716e82b1a6e5434ac4a151a941d37ea34e1eb5e1c8e ;;
        microsoft.net.compilers.toolset.5.0.0.nupkg)                    echo e3f0207e7a8caf2cf79ad8f276eb20b2b880b3a7bb0e8ad27b4d3183d0a81627 ;;
        openssl-3.5.9.tar.gz)                                           echo 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a ;;
        *) echo "" ;;
    esac
}

WORK="$PWD/build"
DL="$WORK/dl"
SRC="$WORK/src"
OUT="$WORK/out/$HOST_ABI"
TREE="$OUT/tree"

log() { printf '\n==> %s\n' "$*"; }
sha256() { { sha256sum "$1" 2>/dev/null || shasum -a 256 "$1"; } | awk '{print $1}'; }

# Downloads <url> to build/dl/<name> unless it is there, then checks it against sha_of.
fetch() {
    local name="$1" url="$2" want have
    mkdir -p "$DL"
    [ -f "$DL/$name" ] || { log "fetching $name"; curl -fL --retry 3 -o "$DL/$name.part" "$url" && mv "$DL/$name.part" "$DL/$name"; }
    want="$(sha_of "$name")"
    have="$(sha256 "$DL/$name")"
    if [ -z "$want" ]; then
        echo "no SHA-256 is recorded for $name (it is $have); add it to sha_of" >&2
        exit 1
    fi
    [ "$want" = "$have" ] || { echo "$name: SHA-256 is $have, expected $want" >&2; exit 1; }
}

nupkg() { # <package id> <version>
    local id="$1" version="$2"
    fetch "$id.$version.nupkg" "https://api.nuget.org/v3-flatcontainer/$id/$version/$id.$version.nupkg"
}

stage_fetch() {
    nupkg "microsoft.netcore.app.runtime.$RID" "$DOTNET_VERSION"
    nupkg microsoft.netcore.app.ref "$DOTNET_VERSION"
    nupkg microsoft.net.compilers.toolset "$ROSLYN_VERSION"
    fetch "openssl-$OPENSSL_VERSION.tar.gz" \
        "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
}

# Lays the packages out as a DOTNET_ROOT. Static archives, the VB compiler and the compiler server are
# left out: nothing on a device links against the first, and the plugin runs csc.dll directly.
stage_tree() {
    log "assembling the tree for $HOST_ABI"
    local unpack="$WORK/unpack/$HOST_ABI"
    rm -rf "$TREE" "$unpack"
    mkdir -p "$unpack/runtime" "$unpack/ref" "$unpack/roslyn"
    unzip -q "$DL/microsoft.netcore.app.runtime.$RID.$DOTNET_VERSION.nupkg" -d "$unpack/runtime"
    unzip -q "$DL/microsoft.netcore.app.ref.$DOTNET_VERSION.nupkg" -d "$unpack/ref"
    unzip -q "$DL/microsoft.net.compilers.toolset.$ROSLYN_VERSION.nupkg" -d "$unpack/roslyn"

    local runtime="$unpack/runtime/runtimes/$RID"
    local shared="$TREE/shared/Microsoft.NETCore.App/$DOTNET_VERSION"
    mkdir -p "$TREE/host/fxr/$DOTNET_VERSION" "$shared" "$TREE/sdk/Roslyn" "$TREE/ref" "$TREE/lib"

    mv "$runtime/native/libhostfxr.so" "$TREE/host/fxr/$DOTNET_VERSION/"
    cp "$runtime"/lib/net*/* "$shared/"
    # System.Private.CoreLib is built per runtime, so the package keeps it with the native libraries.
    cp "$runtime"/native/*.so "$runtime"/native/*.dll "$shared/"

    cp "$unpack"/ref/ref/net*/*.dll "$unpack"/ref/ref/net*/*.xml "$TREE/ref/"

    local bincore="$unpack/roslyn/tasks/netcore/bincore"
    cp "$bincore"/csc.dll "$bincore"/csc.deps.json "$bincore"/csc.runtimeconfig.json \
        "$bincore"/Microsoft.CodeAnalysis.dll "$bincore"/Microsoft.CodeAnalysis.CSharp.dll "$TREE/sdk/Roslyn/"

    # The licenses the packages ship under, carried with the files they cover.
    mkdir -p "$TREE/licenses"
    cp "$unpack/runtime/LICENSE.TXT" "$TREE/licenses/dotnet-LICENSE.txt"
    cp "$unpack/runtime/THIRD-PARTY-NOTICES.TXT" "$TREE/licenses/dotnet-THIRD-PARTY-NOTICES.txt"
    cp "$unpack/roslyn/ThirdPartyNotices.rtf" "$TREE/licenses/roslyn-ThirdPartyNotices.rtf" 2>/dev/null || true
}

ndk() {
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then echo "$ANDROID_NDK_HOME"; return; fi
    local dir="$WORK/android-ndk-$NDK_VERSION" host
    if [ ! -d "$dir" ]; then
        case "$(uname -s)" in Darwin) host=darwin ;; *) host=linux ;; esac
        log "fetching NDK $NDK_VERSION" >&2
        mkdir -p "$DL"
        [ -f "$DL/ndk.zip" ] || curl -fL -o "$DL/ndk.zip" \
            "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-$host.zip"
        unzip -q "$DL/ndk.zip" -d "$WORK"
    fi
    echo "$dir"
}

ndk_bin() { echo "$(ndk)/toolchains/llvm/prebuilt/$(ls "$(ndk)/toolchains/llvm/prebuilt" | head -1)/bin"; }

# Builds libssl.so.3 and libcrypto.so.3. OpenSSL's Android targets name their libraries libssl.so and
# libcrypto.so, the names of the device's own (different) libraries; the versioned names keep the two
# apart whatever the library search order is, and are the first the runtime looks for.
stage_openssl() {
    log "building OpenSSL $OPENSSL_VERSION for $HOST_ABI"
    local dir="$SRC/openssl-$OPENSSL_VERSION-$HOST_ABI"
    rm -rf "$dir"
    mkdir -p "$dir"
    tar -xzf "$DL/openssl-$OPENSSL_VERSION.tar.gz" -C "$dir" --strip-components=1

    cat > "$dir/Configurations/99-codeassist.conf" <<EOF
my %targets = (
    "codeassist-$OPENSSL_TARGET" => {
        inherit_from     => [ "$OPENSSL_TARGET" ],
        shared_extension => ".so.\\\$(SHLIB_VERSION_NUMBER)",
    },
);
EOF

    (
        cd "$dir"
        export ANDROID_NDK_ROOT="$(ndk)"
        export PATH="$(ndk_bin):$PATH"
        ./Configure "codeassist-$OPENSSL_TARGET" "-U__ANDROID_API__" "-D__ANDROID_API__=$ANDROID_API" \
            shared no-tests no-apps no-docs no-engine no-legacy -Wl,-z,max-page-size=16384 >/dev/null
        make -j"$(getconf _NPROCESSORS_ONLN)" build_libs >/dev/null
    )

    mkdir -p "$TREE/lib" "$TREE/licenses"
    cp "$dir/libssl.so.3" "$dir/libcrypto.so.3" "$TREE/lib/"
    "$(ndk_bin)/llvm-strip" --strip-unneeded "$TREE/lib/libssl.so.3" "$TREE/lib/libcrypto.so.3"
    cp "$dir/LICENSE.txt" "$TREE/licenses/openssl-LICENSE.txt"
}

stage_launcher() {
    log "building the launcher for $HOST_ABI"
    mkdir -p "$OUT/jniLibs/$HOST_ABI"
    "$(ndk_bin)/clang" --target="$TRIPLE$ANDROID_API" -O2 -Wall -Wextra -Werror -fPIE -pie \
        -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
        -o "$OUT/jniLibs/$HOST_ABI/libdotnetlaunch.so" launcher/dotnetlaunch.c
    "$(ndk_bin)/llvm-strip" --strip-unneeded "$OUT/jniLibs/$HOST_ABI/libdotnetlaunch.so"
}

# Zips the tree with fixed timestamps and a sorted file list, so the same inputs make the same archive.
stage_package() {
    log "packaging"
    mkdir -p "$OUT/assets"
    rm -f "$OUT/assets/dotnet-sdk.zip"
    find "$TREE" -exec touch -t 202001010000 {} +
    ( cd "$TREE" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | zip -q -X -9 "$OUT/assets/dotnet-sdk.zip" -@ )
    du -sh "$TREE" "$OUT/assets/dotnet-sdk.zip" "$OUT/jniLibs/$HOST_ABI/libdotnetlaunch.so"
}

stages=("$@")
[ ${#stages[@]} -gt 0 ] || stages=(fetch tree openssl launcher package)
for stage in "${stages[@]}"; do "stage_$stage"; done
