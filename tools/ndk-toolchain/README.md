# The on-device NDK toolchain

`build-llvm-android.sh` builds a clang, an lld and the llvm binutils that **run on an Android device**, and
packages the sysroot they compile against for **all four ABIs** (arm64-v8a, armeabi-v7a, x86, x86_64), for the
NDK plugin to compile the user's C and C++ with. They come out as a single multicall executable; see
the dispatch section below for why that matters here more than it usually would.

## Why this is a build and not a download

**Google's NDK has no Android host build.** Every `clang` in the NDK is a linux-x86_64, darwin or windows
binary; none of them can run on a phone. The NDK is still needed here, but only for two things: its host
clang, which does the cross-compiling, and its **sysroot**, which is host-independent and is what the
finished compiler reads headers and link libraries from.

**A toolchain cannot be downloaded at runtime either.** Since Android 10 an app may not `exec()` or
`dlopen()` a file it wrote into its own storage, so anything the plugin means to *run* has to arrive inside
an installed APK, where the installer unpacks it into a directory that is executable. Everything the
compiler only *reads* (headers, link libraries) has no such restriction and ships as assets instead.

Those two facts are what set the whole shape: build it ourselves, keep it small enough to ship, and split it
between `jniLibs` (things we run) and `assets` (things we read).

## Size, and what controls it

The levers in the script are load-bearing, not tidiness:

| Lever | Why |
| --- | --- |
| `LLVM_TARGETS_TO_BUILD=AArch64;ARM;X86` | The single biggest one. Each backend is roughly 8-15 MB, and the default now carries all three so every ABI can be built. Set `LLVM_TARGETS=AArch64` and `TARGET_ABIS=arm64-v8a` for the small arm64-only toolchain |
| `LLVM_LINK_LLVM_DYLIB=ON` | One shared `libLLVM.so` instead of statically linking the whole of LLVM into each of clang and lld |
| `LLVM_TOOL_LLVM_DRIVER_BUILD=ON` | One multicall binary for `ar`/`nm`/`strip`/`objcopy`/`ranlib`/`readelf`. Worth more here than usual: an APK cannot carry symlinks, so without it each tool ships as its own full copy |
| `CMAKE_BUILD_TYPE=MinSizeRel` | `-Os`, no debug info |
| static analyzer / ARCMT / tests / examples / docs / bindings off | None of it is on the path from a `.cpp` to a `.so` |
| one API level of sysroot libs per ABI | The NDK carries every ABI at API 21 through 35. One level's stubs and CRT objects are 852 KB, so shipping just the one saves about 12 MB per ABI |

Measured on the NDK r27c sysroot, for the asset half: `usr/include` is 22 MB (it compresses well, being all
text) and the arm64 link archives are 14 MB, of which `libc.a` is 6.5 MB and `libc++_static.a` is 2.4 MB.
Every `.a` is shipped rather than a curated subset: guessing wrong there surfaces as a confusing link failure
in someone's project rather than as a build error here, and the whole set is a few MB. Dropping
`libc++_static.a` is a real 2.4 MB lever for a plugin that only ever links `c++_shared`.

## The no-symlinks rule, and how tools are selected

The build produces exactly **one executable**. clang and lld are both built with `GENERATE_DRIVER`, so they
are folded into `bin/llvm` together with ar/nm/objcopy/strip/ranlib/readelf, and are normally reached through
symlinks named after each tool.

An APK carries no symlinks, and `ProcessBuilder` cannot set `argv[0]` independently of the path it executes.
So the same binary is packaged under three names instead, each picked so the driver's own dispatch resolves
it. Two dispatchers are involved and they work differently, which is why the linker's name looks odd:

- **The llvm driver** takes the stem of `argv[0]` and looks for a registered tool name at the end of it
  (`llvm-driver.cpp`, `findTool`), accepting a match that ends the stem or is followed by a non-alphanumeric.
  A name matching nothing falls through to treating `argv[1]` as the tool, which is how `libllvmtools.so ar …`
  and `libllvmtools.so clang …` work. **`lib` is itself a registered tool** (the COFF librarian), so a name
  like `lib-gnu-lld.so` matches *that* — "lib" at index 0 followed by `-` — and the linker runs as a
  librarian. The character after `lib` must be alphanumeric; that is what the `ld` in `libld` is for.
- **lld** then splits the file name on `-` and accepts any component that is `ld`, `gnu` or `ld.lld`
  (`DriverDispatcher.cpp`, `parseProgname`). `libld-gnu-lld.so` satisfies both: the stem ends with `lld` for the
  driver, and the middle component is `gnu` for lld.

That second one is not decoration. The linker is the one tool *we* do not invoke: the clang driver does,
through `--ld-path`, and there is no way to make clang pass it a `-flavor gnu` first argument. The flavor has
to come from the file name or lld exits with "lld is a generic driver".

| Normally | Here |
| --- | --- |
| `clang …` | `libllvmtools.so clang …` |
| `clang++ …` | `libllvmtools.so clang --driver-mode=g++ …` |
| `ld.lld` (invoked by clang) | `--ld-path=…/libld-gnu-lld.so` |
| `llvm-ar …`, `llvm-strip …` | `libllvmtools.so ar …`, `libllvmtools.so strip …` |

## What the plugin ships, and where

```
jniLibs/arm64-v8a/          unpacked by the installer into an executable directory
  libllvmtools.so             |  two copies of ONE driver binary; the name selects the tool
  libld-gnu-lld.so            |
  libLLVM.so                  where the code actually lives
  libclang-cpp.so             likewise, for clang
  libc++_shared.so            the STL they were linked against
  libclang.so                 clang's C API (~0.7 MB), linked against libclang-cpp.so (see below)
  libndkclangd.so             the resident daemon (~15 KB), an executable despite the name

assets/toolchain/           unpacked by the plugin into its own dataDir on first use
  lib-clang/<ver>/include/    clang's resource headers (stddef.h, stdarg.h, arm_neon.h, …)
  sysroot/usr/include/        bionic + libc++ headers
  sysroot/usr/lib/<triple>/   crt*.o and the link libraries for each target ABI, at one API level
                              (aarch64-linux-android, arm-linux-androideabi, i686-linux-android,
                              x86_64-linux-android)
  lib-clang/<ver>/lib/linux/  libclang_rt.builtins-<arch>-android.a and <dir>/libunwind.a per target ABI
```

The executables live under `jniLibs/<host abi>/` (one directory per host ABI built, see "Host and target
ABIs"); the asset tree above is the same whichever host produced it.

## The resident daemon

`daemon/ndkclangd.c` is a small C program over libclang that keeps each open file's translation unit, and
the preamble libclang precompiles for it, in memory between requests. The plugin talks to it over
stdin/stdout (`ClangDaemon.kt`, protocol in the C file's header) for editor completion and error checks;
builds still run the driver. `./build-llvm-android.sh daemon` rebuilds just the C in seconds against an
existing package.

**Trap:** LLVM 18's libclang ignores `CLANG_LINK_CLANG_DYLIB` and statically links its own copy of the
frontend: 28 MB stripped, beside a `libclang-cpp.so` holding the same code. `patch_libclang_dylib` points it
at clang-cpp, which takes it to 0.7 MB. CMake's Android platform sets `CMAKE_PLATFORM_NO_VERSIONED_SONAME`,
so its SONAME is a plain `libclang.so` that the installer packages and the loader finds.

## Two flags the plugin must always pass

clang works out where its own headers live from the path of the running executable. That is wrong here, since
the executable lives in a flat `jniLibs` directory rather than in a `bin/` next to a `lib/`. So the plugin
passes both locations explicitly, every time:

```
-resource-dir <dataDir>/toolchain/lib-clang/<ver>
--sysroot     <dataDir>/toolchain/sysroot
```

It also puts the library directory on the child process's `LD_LIBRARY_PATH`, because the child is a child of
the *IDE's* process and does not inherit the plugin package's own library search path.

## Host and target ABIs

These are independent, and the script keeps them apart.

- **Target** is what the compiler emits code for. A single LLVM build carries the AArch64, ARM and X86
  backends, so it can target `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. What each target needs is in the
  sysroot half: headers, the CRT objects and link libraries from `usr/lib/<triple>/`, plus
  `libclang_rt.builtins-<arch>-android.a` and `libunwind.a`. `TARGET_ABIS` (default all four) chooses which are
  packaged, and the script refuses one whose backend is not in `LLVM_TARGETS`.

  The names differ per ABI, which is where a hand-edit goes wrong: 32-bit ARM is `armv7a-linux-androideabi`
  to clang but `arm-linux-androideabi` as the sysroot directory, and 32-bit x86 is `i686` for compiler-rt but
  `i386` for libunwind's directory. They are in one table, `abi_info()`.
- **Host** is what the compiler itself runs on, i.e. the device with the plugin installed. `HOST_ABI` (default
  `arm64-v8a`) picks it, and each host needs its own LLVM build, because an arm64 clang cannot run on an x86
  emulator. Build as many as the plugin should support, one run each, into the same output:

  ```
  ./build-llvm-android.sh                          # arm64-v8a host (the default)
  HOST_ABI=armeabi-v7a ./build-llvm-android.sh cross package
  HOST_ABI=x86_64      ./build-llvm-android.sh cross package
  HOST_ABI=x86         ./build-llvm-android.sh cross package
  ```

  The `deps` and `native` stages are shared. Each `cross` run uses its own tree (`build/build-android-<abi>`;
  arm64 keeps `build/build-android`), and each `package` run writes only `out/jniLibs/<abi>/`, so the hosts
  accumulate. A device whose ABI has no directory there installs the plugin and reports that it ships no
  compiler for it.

  Each host build is a full LLVM build (about an hour on ten cores) and adds roughly 100 MB of executables to
  the APK. The 32-bit hosts (`armeabi-v7a`, `x86`) are the least exercised: a 32-bit process has a 4 GB address
  space at best and a large translation unit can exhaust it. Check them on a device before shipping.

## Built in CI, released with the app

Two GitHub workflows do the release, so nothing here has to be built on a desktop:

- **NDK toolchain** (`.github/workflows/ndk-toolchain.yml`) runs this script (`deps native cross package daemon`)
  once per host ABI and publishes the results as the prerelease `ndk-toolchain-<key>`, where `<key>` is
  `./toolchain-key.sh`'s hash of this script and the daemon sources. It runs when those change on `main`, or by
  hand; a key that already has a complete build is skipped. Each host takes one to two hours.
- **NDK plugin release** (`.github/workflows/ndk-plugin.yml`) runs when a CodeAssist release is published:
  it downloads the toolchain for the release commit's key, builds `:samples:ndk-plugin:assembleRelease` signed
  with the upload key (repository secrets), and attaches `CodeAssist-NDK-Plugin-<version>-<abi>.apk` for every
  ABI. Its summary prints the `plugin_catalog` update for the Get plugins page. Run it by hand with a tag to
  redo a release, or with no tag to only build.

A release whose commit changed the toolchain's sources has no toolchain yet: the plugin job stops and says so.
Let the NDK toolchain run finish, then run NDK plugin release by hand for the tag.

## Running it

```
./build-llvm-android.sh           # deps, native, cross, package
./build-llvm-android.sh cross     # just re-run one stage
```

Needs cmake, ninja, curl and about 13 GB of free disk. The `native` stage builds `llvm-tblgen` and
`clang-tblgen` for the build machine, because a cross build cannot run the Android ones it would otherwise
produce; it builds only those two targets, not a whole native LLVM.
