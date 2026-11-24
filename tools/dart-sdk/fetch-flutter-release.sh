#!/usr/bin/env bash
# Stage what the Dart plugin needs to build Flutter apps in RELEASE mode (ahead of time, to machine code) on the
# device. Run after fetch-flutter.sh, which stages the debug engine and the Flutter root these go beside.
#
# A release build compiles the app to kernel against Flutter's product platform, then gen_snapshot turns that into
# machine code (libapp.so), which the release engine loads. All three come from outside a Flutter SDK, by URL and
# pinned checksum, so this needs no FLUTTER_HOME:
#   jniLibs/<abi>/libflutter_release.so   the release engine, from Flutter's Maven repository, stripped (165 MB as
#                                         shipped, ~12 MB stripped, for arm64). Renamed back to libflutter.so in
#                                         the app it is packaged into.
#   jniLibs/<abi>/libgen_snapshot.so      Dart's gen_snapshot built for Android (build-gen-snapshot.sh, published
#                                         by .github/workflows/dart-gen-snapshot.yml): product mode, compressed
#                                         pointers on 64-bit, as the engine's VM is. Termux's is not, and the
#                                         engine refuses what it makes.
#   product/flutter_patched_sdk_product/  the core libraries in kernel form for product mode, which the release
#                                         kernel compiles against (12 MB).
#
# Usage:  ./fetch-flutter-release.sh
#   FLUTTER_ABIS   default: every ABI fetch-flutter.sh staged a debug engine for.
#   LLVM_OBJCOPY   as in fetch-flutter.sh: by default the NDK's, from ANDROID_NDK_HOME or tools/ndk-toolchain's archive.
set -euo pipefail

FLUTTER_VERSION="3.47.9"
DART_VERSION="3.13.7"
ENGINE="89c39edfdfae2949f6806e4a315c0c08522238b7"

MAVEN="https://storage.googleapis.com/download.flutter.io/io/flutter"
INFRA="https://storage.googleapis.com/flutter_infra_release/flutter/$ENGINE"
GEN_SNAPSHOT_RELEASE="${GEN_SNAPSHOT_RELEASE:-https://github.com/tyron12233/CodeAssist/releases/download/dart-gen-snapshot-$DART_VERSION}"

PRODUCT_SDK_SHA256="22a67cf5f4922dce11e747b91f9c504ab6f92aef5df5fa0276c30356a9e64f5a"

# Per ABI: Flutter's Maven artifact for the release engine, its checksum, and the checksum of our gen_snapshot.
abi_pins() {
    case "$1" in
        arm64-v8a)   echo "arm64_v8a_release b7acf0d2e33f4bf6eaacb335df1812d9c6e894a5342fb78e92840ece6d6d8cc4 GEN_SNAPSHOT_ARM64" ;;
        armeabi-v7a) echo "armeabi_v7a_release 36bd9abbe929328804a04795d2baa68ede8f0fd4fd6dba1f4072305ccaf874ec GEN_SNAPSHOT_ARM" ;;
        x86_64)      echo "x86_64_release 5a4a0062365c2eaec1af59378d82fa5c57bd156383fd5bb5fec51e1bdc8270f8 GEN_SNAPSHOT_X64" ;;
        *) return 1 ;;
    esac
}
GEN_SNAPSHOT_ARM64="175e79a5d685c0666ca645e758fc9a53142612f987fb37651138500472eecc50"
GEN_SNAPSHOT_ARM="34a6a0e965e141f15698eefe770fa722fd269b365764658231af54190ef5ed0e"
GEN_SNAPSHOT_X64="60d7ba7eae39f4ad6890f48dd4adcf9ac6e2ca65d2434e4e0016dbb5bbc3b32f"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/build}"
DL="$WORK/dl"
OUT="$WORK/out/flutter"
TMP="$WORK/flutter-release-tmp"

log() { printf '\n==> %s\n' "$*"; }
die() { echo "$*" >&2; exit 1; }
sha256() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Download $1 to $DL/$2 unless it is there, and check it against $3.
fetch() {
    local url="$1" file="$DL/$2" want="$3"
    if [[ ! -f "$file" ]]; then
        log "downloading $2"
        curl -fL --retry 3 -o "$file.part" "$url" || die "could not download $url"
        mv "$file.part" "$file"
    fi
    local got; got="$(sha256 "$file")"
    [[ "$got" == "$want" ]] || die "checksum mismatch for $file: expected $want, got $got"
}

[[ -f "$OUT/FLUTTER_VERSION" && "$(cat "$OUT/FLUTTER_VERSION")" == "$FLUTTER_VERSION" ]] \
    || die "Run fetch-flutter.sh (Flutter $FLUTTER_VERSION) first: the release pieces go beside its debug engine."
[[ "$(cat "$OUT/ENGINE")" == "$ENGINE" ]] || die "$OUT/ENGINE is not $ENGINE."

ABIS="${FLUTTER_ABIS:-$(cd "$OUT/jniLibs" && ls)}"
mkdir -p "$DL" "$TMP"

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

for abi in $ABIS; do
    pins="$(abi_pins "$abi")" || die "unknown ABI '$abi' (expected arm64-v8a, armeabi-v7a or x86_64)"
    read -r artifact engine_sha gen_var <<<"$pins"
    gen_sha="${!gen_var}"
    log "$abi: release engine and gen_snapshot"
    fetch "$MAVEN/$artifact/1.0.0-$ENGINE/$artifact-1.0.0-$ENGINE.jar" "$artifact-$ENGINE.jar" "$engine_sha"
    unzip -q -o -j "$DL/$artifact-$ENGINE.jar" "lib/$abi/libflutter.so" -d "$TMP"
    mkdir -p "$OUT/jniLibs/$abi"
    "$OBJCOPY" --strip-unneeded "$TMP/libflutter.so" "$OUT/jniLibs/$abi/libflutter_release.so"
    rm -f "$TMP/libflutter.so"

    fetch "$GEN_SNAPSHOT_RELEASE/gen_snapshot-$abi" "gen_snapshot-$DART_VERSION-$abi" "$gen_sha"
    cp "$DL/gen_snapshot-$DART_VERSION-$abi" "$OUT/jniLibs/$abi/libgen_snapshot.so"
    chmod +x "$OUT/jniLibs/$abi/libgen_snapshot.so"
done

log "product platform"
fetch "$INFRA/flutter_patched_sdk_product.zip" "flutter_patched_sdk_product-$ENGINE.zip" "$PRODUCT_SDK_SHA256"
rm -rf "$OUT/product"
mkdir -p "$OUT/product"
unzip -q -o "$DL/flutter_patched_sdk_product-$ENGINE.zip" "flutter_patched_sdk_product/*" -d "$OUT/product"

rm -rf "$TMP"
log "done"
du -sh "$OUT"/jniLibs/*/libflutter_release.so "$OUT"/jniLibs/*/libgen_snapshot.so "$OUT/product"
