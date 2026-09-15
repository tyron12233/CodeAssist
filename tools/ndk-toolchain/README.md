# The on-device NDK toolchain

`build-llvm-android.sh` builds a clang, an lld and the llvm binutils that **run on an Android arm64 device**,
for the NDK plugin to compile the user's C and C++ with. They come out as a single multicall executable; see
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
| `LLVM_TARGETS_TO_BUILD=AArch64` | The single biggest one. Each extra backend is roughly 8-15 MB. Add `ARM;X86` only when cross-ABI release builds are wanted |
| `LLVM_LINK_LLVM_DYLIB=ON` | One shared `libLLVM.so` instead of statically linking the whole of LLVM into each of clang and lld |
| `LLVM_TOOL_LLVM_DRIVER_BUILD=ON` | One multicall binary for `ar`/`nm`/`strip`/`objcopy`/`ranlib`/`readelf`. Worth more here than usual: an APK cannot carry symlinks, so without it each tool ships as its own full copy |
| `CMAKE_BUILD_TYPE=MinSizeRel` | `-Os`, no debug info |
| static analyzer / ARCMT / tests / examples / docs / bindings off | None of it is on the path from a `.cpp` to a `.so` |
| one ABI, one API level of sysroot libs | The NDK carries every ABI at API 21 through 35. One level's stubs and CRT objects are 852 KB, so shipping just the one saves about 12 MB per ABI |

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

assets/toolchain/           unpacked by the plugin into its own dataDir on first use
  lib-clang/<ver>/include/    clang's resource headers (stddef.h, stdarg.h, arm_neon.h, …)
  sysroot/usr/include/        bionic + libc++ headers
  sysroot/usr/lib/            crt*.o and the link libraries for one ABI at one API level
```

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

## Running it

```
./build-llvm-android.sh           # deps, native, cross, package
./build-llvm-android.sh cross     # just re-run one stage
```

Needs cmake, ninja, curl and about 13 GB of free disk. The `native` stage builds `llvm-tblgen` and
`clang-tblgen` for the build machine, because a cross build cannot run the Android ones it would otherwise
produce; it builds only those two targets, not a whole native LLVM.
