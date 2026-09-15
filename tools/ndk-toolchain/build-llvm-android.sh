#!/usr/bin/env bash
# Build clang/lld/llvm-tools that RUN ON an Android arm64 device, for the NDK plugin.
#
# Why this exists: Google's NDK has no Android host build. Every `clang` in it is a
# linux-x86_64/darwin/windows binary, so none of them can run on a phone. A CodeAssist plugin that
# compiles C and C++ on the device has to bring an LLVM built *for* Android, and this is that build.
#
# It also has to be SMALL, because of a second constraint: since Android 10 an app may not exec() or
# dlopen() a file it wrote into its own storage, so a toolchain cannot be downloaded at runtime. It has to
# ship inside the plugin APK, where the installer unpacks it into a directory that is executable. The size
# levers below (one target backend, one shared libLLVM, MinSizeRel, the multicall llvm driver) are
# therefore load-bearing, not tidiness.
#
# Usage:  ./build-llvm-android.sh [stage]
#   stage: deps | native | cross | package | all   (default: all)
#
# Needs ~13 GB of free disk and around an hour on ten cores.
set -euo pipefail

LLVM_VERSION="${LLVM_VERSION:-18.1.8}"     # matches NDK r27's clang, so its libc++ headers pair exactly
NDK_VERSION="${NDK_VERSION:-r27c}"
ANDROID_API="${ANDROID_API:-26}"           # the IDE's own minSdk floor
# Only the backend we emit code for. Each extra target is roughly 8-15 MB of the final binary, so this is
# the single biggest size lever. Add "ARM;X86" here to cross-compile for the other Android ABIs.
LLVM_TARGETS="${LLVM_TARGETS:-AArch64}"

WORK="${WORK:-$(pwd)/build}"
DL="$WORK/dl"
SRC="$WORK/llvm-project-$LLVM_VERSION.src"
NDK="$WORK/android-ndk-$NDK_VERSION"
NATIVE_BUILD="$WORK/build-native"
CROSS_BUILD="$WORK/build-android"
OUT="$WORK/out"

CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-ninja}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

log() { printf '\n==> %s\n' "$*"; }

# Stop before filling the disk rather than after. A half-finished LLVM build that died on ENOSPC leaves
# several GB of objects behind and no way to tell which are complete.
require_disk() {
    local need_gb="$1" avail_gb
    avail_gb=$(df -g "$WORK" | awk 'NR==2 {print $4}')
    if [ "$avail_gb" -lt "$need_gb" ]; then
        echo "Only ${avail_gb} GB free under $WORK; this stage needs ${need_gb} GB. Stopping." >&2
        exit 1
    fi
    log "disk: ${avail_gb} GB free"
}

# LLVM 18 mixes the two forms of target_link_libraries on one target, which CMake refuses.
#
# Both clang and lld wrap their linking in a `<project>_target_link_libraries(target type ...)` helper that
# links the `obj.<target>` object library in the PLAIN form (no PRIVATE/PUBLIC keyword), while
# `LLVM-Config.cmake` links that same target in the KEYWORD form. CMake requires every call on one target to
# agree, so this is an error.
#
# A default build never hits it: LLVM-Config only touches those `obj.` targets when LLVM is a shared library.
# It appears exactly when LLVM_LINK_LLVM_DYLIB / CLANG_LINK_CLANG_DYLIB are on, which are among the size
# levers this build cannot do without (they are what stops the whole of LLVM being statically linked into
# each of clang and lld).
#
# The fix is upstream's: pass the caller's own keyword through to the object library. Guarded, so re-running
# a stage is harmless.
apply_patches() {
    local f
    for f in "$SRC/lld/tools/lld/CMakeLists.txt" "$SRC/clang/cmake/modules/AddClang.cmake"; do
        [ -f "$f" ] || continue
        grep -q 'target_link_libraries(obj.${target} ${ARGN})' "$f" || continue
        log "patching ${f#$SRC/}: link obj.\${target} with the caller's keyword"
        perl -0pi -e 's/target_link_libraries\(obj\.\$\{target\} \$\{ARGN\}\)/target_link_libraries(obj.\${target} \${type} \${ARGN})/' "$f"
    done
    patch_lld_elf_only
}

# Build lld with the ELF flavor alone.
#
# Necessary, not merely smaller: lld/MachO includes <mach-o/compact_unwind_encoding.h>, an Apple SDK header.
# It is there when you build lld to RUN on macOS, and absent when you build it to run on Android, so the
# MachO flavor cannot be compiled for this target at all.
#
# It is also what we want. Android links ELF; COFF, MachO, MinGW and Wasm are four linkers' worth of code in
# a binary that ships inside an APK.
#
# This is a supported configuration rather than a hack: lld's own header says "library users must specify
# which drivers they use, provide that information to lldMain() in the `drivers` param, and link the
# corresponding driver library", and whichDriver() already answers "not available in this build" for a flavor
# that is absent. Three edits follow from that: stop building the other flavors, stop linking them, and
# declare only the one in the driver table.
patch_lld_elf_only() {
    local cm="$SRC/lld/CMakeLists.txt" tool="$SRC/lld/tools/lld/CMakeLists.txt" src="$SRC/lld/tools/lld/lld.cpp"
    [ -f "$cm" ] || return 0
    grep -q '^add_subdirectory(MachO)' "$cm" || return 0
    log "patching lld: ELF flavor only (MachO needs an Apple SDK header; COFF/MinGW/Wasm are dead weight)"

    perl -pi -e 's/^add_subdirectory\((COFF|MachO|MinGW|wasm)\)/# $& -- removed: Android links ELF only/' "$cm"

    # The driver links only what still exists.
    perl -0pi -e 's/lld_target_link_libraries\(lld\s+PRIVATE\s+lldCommon\s+lldCOFF\s+lldELF\s+lldMachO\s+lldMinGW\s+lldWasm\s+\)/lld_target_link_libraries(lld\n  PRIVATE\n  lldCommon\n  lldELF\n  )/s' "$tool"

    # And declares only the driver it links. Overriding the macro rather than editing its two use sites keeps
    # this to one hunk, and leaves an obvious marker for anyone re-reading it.
    perl -0pi -e 's/LLD_HAS_DRIVER\(coff\)\nLLD_HAS_DRIVER\(elf\)\nLLD_HAS_DRIVER\(mingw\)\nLLD_HAS_DRIVER\(macho\)\nLLD_HAS_DRIVER\(wasm\)/LLD_HAS_DRIVER(elf)\n\n\/\/ CodeAssist: ELF is the only flavor built here, so the driver table holds only that one.\n#undef LLD_ALL_DRIVERS\n#define LLD_ALL_DRIVERS {{lld::Gnu, \&lld::elf::link}}/' "$src"
}

stage_deps() {
    mkdir -p "$DL" "$WORK"
    require_disk 12

    if [ ! -d "$SRC" ]; then
        log "fetching LLVM $LLVM_VERSION source"
        [ -f "$DL/llvm.tar.xz" ] || curl -fL -o "$DL/llvm.tar.xz" \
            "https://github.com/llvm/llvm-project/releases/download/llvmorg-$LLVM_VERSION/llvm-project-$LLVM_VERSION.src.tar.xz"
        # Only the projects this build needs. The monorepo tarball also carries flang, mlir, lldb, libcxx,
        # compiler-rt, openmp, polly and bolt, which together are most of its size and none of which a
        # cross-built clang driver uses. compiler-rt in particular is a RUNTIME for the code we compile, and
        # the NDK already ships Android's.
        log "extracting llvm, clang, lld, cmake, third-party"
        tar -C "$WORK" -xf "$DL/llvm.tar.xz" \
            "llvm-project-$LLVM_VERSION.src/llvm" \
            "llvm-project-$LLVM_VERSION.src/clang" \
            "llvm-project-$LLVM_VERSION.src/lld" \
            "llvm-project-$LLVM_VERSION.src/cmake" \
            "llvm-project-$LLVM_VERSION.src/third-party"
    fi

    apply_patches

    if [ ! -d "$NDK" ]; then
        log "fetching NDK $NDK_VERSION"
        local host; host=$([ "$(uname)" = "Darwin" ] && echo darwin || echo linux)
        [ -f "$DL/ndk.zip" ] || curl -fL -o "$DL/ndk.zip" \
            "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-$host.zip"
        log "extracting the NDK"
        unzip -q "$DL/ndk.zip" -d "$WORK"
    fi
}

# The cross build cannot run the tablegen binaries it needs (they would be Android ELF), so they are built
# for THIS machine first. Only those two targets: a full native LLVM would cost an extra half hour and
# several GB for two executables.
stage_native() {
    require_disk 8
    log "configuring the native tablegen build"
    "$CMAKE" -G Ninja -S "$SRC/llvm" -B "$NATIVE_BUILD" \
        -DCMAKE_BUILD_TYPE=Release \
        -DLLVM_ENABLE_PROJECTS="clang" \
        -DLLVM_TARGETS_TO_BUILD="$LLVM_TARGETS" \
        -DLLVM_ENABLE_ASSERTIONS=OFF \
        -DLLVM_INCLUDE_TESTS=OFF \
        -DLLVM_INCLUDE_EXAMPLES=OFF \
        -DLLVM_INCLUDE_BENCHMARKS=OFF \
        -DLLVM_ENABLE_ZSTD=OFF \
        -DLLVM_ENABLE_LIBXML2=OFF \
        -DLLVM_ENABLE_TERMINFO=OFF

    log "building llvm-tblgen and clang-tblgen"
    "$NINJA" -C "$NATIVE_BUILD" -j "$JOBS" llvm-tblgen clang-tblgen llvm-config
}

stage_cross() {
    require_disk 8
    local toolchain="$NDK/toolchains/llvm/prebuilt"
    toolchain="$toolchain/$(ls "$toolchain" | head -1)"
    log "configuring the Android build (targets: $LLVM_TARGETS, api $ANDROID_API)"

    # CMake's own Android support rather than the NDK's android.toolchain.cmake: fewer moving parts, and the
    # NDK's shim has not kept up with CMake 4.
    "$CMAKE" -G Ninja -S "$SRC/llvm" -B "$CROSS_BUILD" \
        -DCMAKE_SYSTEM_NAME=Android \
        -DCMAKE_SYSTEM_VERSION="$ANDROID_API" \
        -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
        -DCMAKE_ANDROID_NDK="$NDK" \
        -DCMAKE_ANDROID_STL_TYPE=c++_shared \
        \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DLLVM_ENABLE_PROJECTS="clang;lld" \
        -DLLVM_TARGETS_TO_BUILD="$LLVM_TARGETS" \
        \
        `# 16 KB pages. Android 15 and up run a 16 KB-page kernel on 64-bit devices, and a binary whose LOAD` \
        `# segments are aligned to the old 4 KB simply cannot be mapped: it dies with SIGSEGV before main,` \
        `# with no linker message to say why. The NDK's own android.toolchain.cmake passes this; CMake's` \
        `# built-in Android support, which this build uses instead, does not, so it has to be passed here.` \
        -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
        -DCMAKE_MODULE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
        \
        `# What it runs on, and what it emits for when told nothing else.` \
        -DLLVM_HOST_TRIPLE="aarch64-linux-android$ANDROID_API" \
        -DLLVM_DEFAULT_TARGET_TRIPLE="aarch64-linux-android$ANDROID_API" \
        \
        `# The native tools from the stage above; without this the build tries to RUN the Android tablegen.` \
        -DLLVM_NATIVE_TOOL_DIR="$NATIVE_BUILD/bin" \
        -DLLVM_TABLEGEN="$NATIVE_BUILD/bin/llvm-tblgen" \
        -DCLANG_TABLEGEN="$NATIVE_BUILD/bin/clang-tblgen" \
        \
        `# One shared libLLVM instead of statically linking the whole of LLVM into each of clang and lld.` \
        -DLLVM_BUILD_LLVM_DYLIB=ON \
        -DLLVM_LINK_LLVM_DYLIB=ON \
        -DCLANG_LINK_CLANG_DYLIB=ON \
        \
        `# One multicall binary for llvm-ar / llvm-nm / llvm-strip / llvm-objcopy / llvm-ranlib / llvm-readelf,` \
        `# which matters more than usual here: an APK cannot carry symlinks, so without this each tool would` \
        `# have to ship as its own full copy.` \
        -DLLVM_TOOL_LLVM_DRIVER_BUILD=ON \
        \
        `# Everything a compile-and-link toolchain does not need.` \
        -DLLVM_ENABLE_ASSERTIONS=OFF \
        -DLLVM_INCLUDE_TESTS=OFF \
        -DLLVM_INCLUDE_EXAMPLES=OFF \
        -DLLVM_INCLUDE_BENCHMARKS=OFF \
        -DLLVM_INCLUDE_DOCS=OFF \
        `# NOT LLVM_INCLUDE_UTILS=OFF: llvm-tblgen lives under utils/, and the cross configure still wants` \
        `# those targets to exist even though it runs the native ones from LLVM_NATIVE_TOOL_DIR.` \
        -DLLVM_ENABLE_BINDINGS=OFF \
        -DLLVM_ENABLE_PLUGINS=OFF \
        -DLLVM_ENABLE_ZLIB=OFF \
        -DLLVM_ENABLE_ZSTD=OFF \
        -DLLVM_ENABLE_LIBXML2=OFF \
        -DLLVM_ENABLE_TERMINFO=OFF \
        -DLLVM_ENABLE_LIBEDIT=OFF \
        -DLLVM_ENABLE_LIBPFM=OFF \
        -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
        -DCLANG_ENABLE_ARCMT=OFF \
        -DCLANG_PLUGIN_SUPPORT=OFF \
        -DCLANG_TOOL_CLANG_IMPORT_TEST_BUILD=OFF \
        \
        `# Android has no /usr/lib to probe, and letting it probe the BUILD host is how a cross build ends up` \
        `# with the wrong answers baked in.` \
        -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY \
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY

    log "building clang, lld and the llvm driver (this is the long one)"
    "$NINJA" -C "$CROSS_BUILD" -j "$JOBS" clang lld llvm-driver
}

# Lay the result out the way the plugin packages it: the three exec-ables and the two shared libraries as
# `lib*.so` for jniLibs, everything else as an asset tree the plugin unpacks into its dataDir.
#
# Three exec-ables, not a dozen, because an APK cannot carry symlinks and ProcessBuilder cannot set argv[0]
# independently of the path it executes. Every multicall tool here is therefore selected by a FLAG instead:
#   clang++      ->  libclang.so --driver-mode=g++
#   llvm-ar etc. ->  libllvmtools.so ar ...
#
# The linker is the exception, and the reason its file has an odd name. lld also dispatches on argv[0], but
# unlike the other two it is not US who invokes it: the clang driver does, through --ld-path, and there is no
# way to make clang pass lld a `-flavor gnu` first argument. So the flavor has to come from the file name.
# lld takes the filename of argv[0], splits it on '-', and accepts any component that is "ld", "gnu" or
# "ld.lld" (lld/Common/DriverDispatcher.cpp, parseProgname). `libld-gnu-lld.so` splits to
# ["lib", "gnu", "lld.so"], so the middle component selects the GNU flavor, and the name still has the
# lib-prefix and .so-suffix the installer expects. `liblld.so` would split to one component that matches
# nothing, and lld would exit with "lld is a generic driver".
stage_package() {
    local strip="$NDK/toolchains/llvm/prebuilt"
    strip="$strip/$(ls "$strip" | head -1)/bin/llvm-strip"
    rm -rf "$OUT"
    mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/assets/toolchain"

    # There is only ONE executable. Both clang and lld are built with GENERATE_DRIVER, so they are folded
    # into `bin/llvm` along with ar/nm/objcopy/strip/ranlib/readelf, and normally reached through symlinks.
    # An APK carries no symlinks, so the same binary is packaged under TWO names:
    #
    #   libllvmtools.so  everything we invoke ourselves. An unrecognised name falls through to argv[1], so
    #                    `libllvmtools.so clang …`, `libllvmtools.so ar …` and so on all work.
    #   libld-gnu-lld.so the linker, and the reason it needs a name this odd: it is the one tool we do NOT
    #                    invoke. clang runs it for us through --ld-path, and there is no way to make clang
    #                    pass it a `-flavor gnu` first argument, so the flavor has to come from the file name,
    #                    and it has to satisfy TWO dispatchers at once:
    #                      * the llvm driver matches a registered tool name anywhere in the stem, provided the
    #                        match ends the stem or is followed by a non-alphanumeric. "lld" at the end
    #                        satisfies that. The `lib` prefix is a trap here: `lib` is itself a tool (the COFF
    #                        librarian), so `lib-gnu-lld` matched IT -- "lib" at index 0 followed by '-' --
    #                        and the linker ran as a librarian. The character after "lib" must be
    #                        alphanumeric, which is what the "ld" in "libld" is for.
    #                      * lld then splits the file name on '-' and takes any component that is "ld", "gnu"
    #                        or "ld.lld" as its flavor, which the middle "gnu" supplies.
    #
    # Two copies of one binary, not two binaries: the code lives in libLLVM.so / libclang-cpp.so, so each
    # copy costs only the driver stub.
    #
    # None of these may differ from another packaged file by CASE ALONE. This build runs on macOS, whose file
    # system is case-insensitive, so naming a copy `libllvm.so` silently made it the same file as
    # `libLLVM.so` and the shared library overwrote the driver -- which would then have failed on the device,
    # where the file system is case-sensitive and the name is simply missing.
    log "collecting the executables and shared libraries"
    local driver="$CROSS_BUILD/bin/llvm"
    [ -f "$driver" ] || { echo "no $driver; did the cross stage finish?" >&2; exit 1; }
    cp "$driver" "$OUT/jniLibs/arm64-v8a/libllvmtools.so"
    cp "$driver" "$OUT/jniLibs/arm64-v8a/libld-gnu-lld.so"
    cp "$CROSS_BUILD/lib/libLLVM.so."*              "$OUT/jniLibs/arm64-v8a/libLLVM.so" 2>/dev/null \
        || cp "$CROSS_BUILD/lib/libLLVM.so"         "$OUT/jniLibs/arm64-v8a/libLLVM.so"
    cp "$CROSS_BUILD/lib/libclang-cpp.so"*          "$OUT/jniLibs/arm64-v8a/libclang-cpp.so" 2>/dev/null || true
    # The STL the toolchain itself was linked against, from the NDK rather than from our build.
    find "$NDK" -name libc++_shared.so -path '*aarch64*' -print -quit \
        | xargs -I{} cp {} "$OUT/jniLibs/arm64-v8a/libc++_shared.so"

    log "stripping"
    for f in "$OUT"/jniLibs/arm64-v8a/*.so; do "$strip" --strip-all "$f" || true; done

    log "collecting the headers and link libraries the compiler needs at runtime"
    # clang computes its resource directory from its own path, which is wrong once it lives in a flat
    # jniLibs directory, so the plugin passes -resource-dir at this location instead.
    cp -R "$CROSS_BUILD/lib/clang" "$OUT/assets/toolchain/lib-clang"

    # The resource directory needs two RUNTIME archives our build does not produce, because compiler-rt is
    # deliberately not built here: they are libraries for the code being compiled, not part of the compiler,
    # and the NDK already ships them built for the target. Without them every link fails on
    # `libclang_rt.builtins-aarch64-android.a` and `-l:libunwind.a`.
    #
    # Taken from the NDK's own clang resource directory, which is the same clang version as ours. The
    # sanitizer runtimes sitting beside them are deliberately left out: several MB each, and nothing on the
    # path from a .cpp to a .so needs one.
    local ndk_res="$NDK/toolchains/llvm/prebuilt"
    ndk_res="$ndk_res/$(ls "$ndk_res" | head -1)/lib/clang/${LLVM_VERSION%%.*}/lib/linux"
    local out_res="$OUT/assets/toolchain/lib-clang/${LLVM_VERSION%%.*}/lib/linux"
    mkdir -p "$out_res/aarch64"
    cp "$ndk_res/libclang_rt.builtins-aarch64-android.a" "$out_res/"
    cp "$ndk_res/aarch64/libunwind.a" "$out_res/aarch64/"
    local sysroot="$NDK/toolchains/llvm/prebuilt"
    sysroot="$sysroot/$(ls "$sysroot" | head -1)/sysroot"
    mkdir -p "$OUT/assets/toolchain/sysroot/usr"
    cp -R "$sysroot/usr/include" "$OUT/assets/toolchain/sysroot/usr/include"
    # Only this ABI's link libraries, and only at the API level we target: the NDK carries every ABI at
    # every level from 21 up, which is most of its size and all but one slice of it is dead weight here.
    #
    # The LAYOUT is copied exactly, not flattened or renamed. clang derives these paths itself
    # (<sysroot>/usr/lib/<triple>/<api> for the CRT objects and the versioned stubs, <sysroot>/usr/lib/<triple>
    # for the static libraries beside them), so a tidier arrangement is simply one it cannot find.
    local libdir="$OUT/assets/toolchain/sysroot/usr/lib/aarch64-linux-android"
    mkdir -p "$libdir/$ANDROID_API"
    cp -R "$sysroot/usr/lib/aarch64-linux-android/$ANDROID_API/." "$libdir/$ANDROID_API/"
    find "$sysroot/usr/lib/aarch64-linux-android" -maxdepth 1 -type f \( -name '*.a' -o -name '*.so' \) \
        -exec cp {} "$libdir/" \;

    # Guard the case-collision above rather than trusting the reader to remember it.
    local lowered
    lowered=$(ls "$OUT/jniLibs/arm64-v8a" | tr 'A-Z' 'a-z' | sort)
    if [ "$(echo "$lowered" | wc -l)" != "$(echo "$lowered" | uniq | wc -l)" ]; then
        echo "two packaged files differ only by case; on this case-insensitive file system one has" >&2
        echo "overwritten the other: $(echo "$lowered" | uniq -d | tr '\n' ' ')" >&2
        exit 1
    fi

    # android_native_app_glue: the event loop a NativeActivity app is written against. It is SOURCE, not a
    # library -- the NDK ships it to be compiled into each app rather than linked -- so it is 40 KB of .c and
    # .h that has to travel with the toolchain for a native-activity project to build at all.
    local glue="$NDK/sources/android/native_app_glue"
    if [ -d "$glue" ]; then
        mkdir -p "$OUT/assets/toolchain/native_app_glue"
        cp "$glue/android_native_app_glue.c" "$glue/android_native_app_glue.h" "$glue/NOTICE" \
            "$OUT/assets/toolchain/native_app_glue/"
    fi

    log "sizes"
    du -sh "$OUT/jniLibs" "$OUT/assets" 2>/dev/null
    ls -la "$OUT/jniLibs/arm64-v8a"
}

case "${1:-all}" in
    deps) stage_deps ;;
    native) stage_native ;;
    cross) stage_cross ;;
    package) stage_package ;;
    all) stage_deps; stage_native; stage_cross; stage_package ;;
    *) echo "unknown stage: $1" >&2; exit 2 ;;
esac

log "done"
