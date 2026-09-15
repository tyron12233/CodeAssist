# The NDK plugin

C and C++ on the device, shipped as its own Android app. It carries a clang and an lld built to run on
Android arm64, colours C/C++ in the editor, and compiles and links native code without a desktop anywhere in
the loop.

## Building it

The compiler is **not in this repository**. Build it first:

```
tools/ndk-toolchain/build-llvm-android.sh          # ~35 min, needs cmake + ninja
./gradlew :samples:ndk-plugin:assembleDebug        # picks up tools/ndk-toolchain/build/out
```

Point the module somewhere else with `-Pndk.toolchain.dir=/path/to/out`. Building with no toolchain present
still produces a working APK; it installs and reports "this plugin ships no compiler for this device's ABI",
which is exactly what a user on armeabi-v7a or x86 sees today.

## How the two halves are packaged, and why

The toolchain is split according to a rule that is not ours to choose: **since Android 10 an app may not
`exec()` a file it wrote into its own storage.**

| Half | Where | Why there |
| --- | --- | --- |
| The executables (~103 MB) | `jniLibs`, so the installer unpacks them into the package's own `lib/` directory | It is the only directory a plugin can execute from |
| Headers and link libraries (~52 MB) | `assets/toolchain.zip`, unpacked into `PluginRegistration.dataDir` on first use | Only ever read, so the rule does not apply, and 2800 files do not belong in `jniLibs` |

The read-only half is **one zip, not a directory**, because the plugin reads it back through its classloader
over the installed APK, and a classloader can open a named entry but cannot list a directory. As loose files
there would be no way to discover what to unpack. It also compresses 52 MB down to about 10 MB.

`jniLibs.useLegacyPackaging = true` is required: left compressed, a native library is mapped straight out of
the APK, which serves `System.loadLibrary` and is useless for `exec` because there is no file on disk to hand
a child process.

## Two flags on every invocation

clang works out where its own headers live from the path of the running executable. That is wrong here, since
the executable sits in a flat directory of `lib*.so` files rather than in a `bin/` beside a `lib/`. Both
locations are named explicitly instead (`NdkToolchain.commonFlags`):

```
-resource-dir <dataDir>/toolchain/lib-clang/18
--sysroot     <dataDir>/toolchain/sysroot
```

The child process also gets `LD_LIBRARY_PATH` set to the library directory, because it is a child of the
*IDE's* process and does not inherit the plugin package's own search path.

## The tools, and their odd names

One binary holds clang, lld and the llvm binutils, normally reached through symlinks. An APK carries no
symlinks, so it ships under two names and the name selects the tool. See
[tools/ndk-toolchain/README.md](../../tools/ndk-toolchain/README.md) for the dispatch rules.

| | |
| --- | --- |
| `libllvmtools.so clang …` | the C compiler |
| `libllvmtools.so clang --driver-mode=g++ …` | the C++ compiler |
| `--ld-path=…/libld-gnu-lld.so` | the linker, which clang invokes for us |

## What it contributes

**Engine facet** (`NdkPlugin`): file types for `.c` and the C++ suffixes, and a palette command that prepares
the toolchain and then compiles and links a throwaway file, so the answer is "it works" rather than "the
binary exists". It deliberately does **not** touch the toolchain during `register`: unpacking 2800 files does
not belong on the path to the first frame.

**UI facet** (`NdkUiPlugin`): `EditorLanguage` profiles for C and C++, with `directivePrefix = "#"` so
`#include <stdio.h>` reads as a directive and a header name rather than a run of operators. This is the layer
that runs on every keystroke; without it a `.cpp` opens grey and stays grey between keystrokes, because
anything compiler-driven is asynchronous.

## Configuration

A module's native build is the `[ndk]` table of its `module.toml`, decoded into an `NdkFacet`: source
directories, library name, ABIs, min SDK, C++ standard, STL, optimization, extra flags, and libraries to
link. Declarative on purpose — there is no CMakeLists to parse and no second build system to ship, which is
the only reason a C++ build fits on a phone beside the compiler.

```toml
[ndk]
sourceDirs = ["src/main/cpp"]
libraryName = "native-lib"
abis = ["arm64-v8a"]
minSdk = 26
cppStandard = "c++17"
stl = "c++_shared"
optimization = "-O2"
compilerFlags = []
linkLibraries = ["log"]
```

**It is also editable in Module Settings, with no UI code in this plugin.** The Settings tab renders any
codec-backed facet generically: `getModuleConfig` encodes each facet through its codec and types each value,
so strings become text fields, `minSdk` a number, `abis` a chip list and `nativeActivity` a toggle. The
closed `ModulesTab` enum is only the set of *bespoke* tabs (Build Features, Packaging, Signing); facet
configuration lives inside Settings and needs no extension point at all.

Decoding is deliberately forgiving: a missing key takes its default, a bare string is accepted where a list
was meant, a quoted number is read as a number, and a value of the wrong shape falls back instead of
throwing, because a project that will not open is a worse answer than one that builds with a default. The
one thing it will not do is confuse *absent* with *empty*: `linkLibraries = []` means link nothing.

## The project templates

Two of them, in two categories of the gallery, because the projects are not variants of each other:

- **C/C++ Library** (Other) — a `java-lib` module with a header and an implementation, in C or C++ as the
  user picks, and nothing Android in it: no manifest, no resources, no `android/log.h`. The header is
  generated alongside the source rather than left as an exercise, because a one-file starter teaches the
  wrong shape for a language whose whole compilation model is headers.
- **Native C++ Activity** (Android) — an Android app with no Java at all. The manifest names
  `android.app.NativeActivity` with `hasCode="false"`, and `android.app.lib_name` points at the same library
  the `[ndk]` table builds. The source is an `android_main` driving the glue's event loop, handling
  window-created and window-destroyed rather than returning immediately, because an empty `android_main`
  gives an app that looks broken rather than empty.

Each **builds the project model**, not just files on disk: the module, its type, `src/main/cpp` as a
declared content root, and the facet tables. The `[ndk]` table is written through this plugin's own codec;
the `[android]` table is written by NAME (`putFacetData`), which is how a template scaffolds a module whose
type belongs to a plugin it does not link against. The generated `[ndk]` table spells out every default
rather than omitting it, so the file doubles as the documentation for what can be changed.

`android_native_app_glue` is **source, not a library** — the NDK ships it to be compiled into each app — so
the toolchain packages its 40 KB and the build compiles it alongside the module's own sources when
`nativeActivity = true`. It is also a `.c` in a C++ project, which is why the facet carries `cStandard`
beside `cppStandard`: clang refuses `-std=c++17` on a C file outright, so one standard for everything is not
a stricter policy but a build that fails on a file the user never wrote.

## File type icons

`.c`, `.cpp` and headers get their own badges instead of the generic grey document — the one an
unrecognised binary gets, which reads as "the IDE does not know what this is".

Two registrations, and they are not redundant. The project tree asks the ENGINE which icon a file gets
(`platform.fileIcon`, contributed by `NdkFileIconProvider` at priority 50 — above the built-in fallback,
below android-support, so nothing about a C file outranks the manifest); a tab or a breadcrumb resolves the
name in the UI layer itself, because a file opens from places that have no tree node, which is what
`ui.fileIcon` is for. `NdkFileIcons.SUFFIXES` is the one table both read.

## Building native code

`NdkBuildPlugin` registers `:<module>:compileNative` for every module carrying an `[ndk]` facet and hangs it
off `mergeNativeLibs<Variant>` (the Android packaging merge) and the module's `assemble` aggregates.
Configuring a task no build system registered is silently ignored, which is how one plugin serves both
pipelines without probing which is running.

The `.so` is written into the module's own `src/main/jniLibs/<abi>/`. That is not where generated output
would ideally live, but it is the only place the packaging merge looks: it collects the module's *declared*
`jniLibs` content roots, and those come from the module type rather than from anything a plugin can add. A
build directory would be tidier and would simply never be packaged.

Compiler output is parsed by the same `ClangDiagnostics` the editor uses, so a build error and the squiggle
on the same line say the same thing in the same words. An ABI the bundled toolchain cannot target is reported
as a warning rather than silently producing nothing, and a device with no toolchain leaves the build
succeeding with a warning — a missing compiler is not a broken build, it is a build with no native part.

## Completion

Answered by the compiler: `clang -Xclang -code-completion-at=-:line:col` over the live buffer on stdin, the
same flags and include path as the build. That is why this plugin ships no language server - a second
frontend would be another 25 MB and another thing to disagree with the first.

clang's candidate format is chunked (`[#void#]push_back(<#const value_type &__x#>)`) and the markers have to
be stripped rather than shown. It carries no *kind*, so the shape implies it: a parameter list means
something callable, `Pattern` is a snippet, a bare name is a macro in C and a type in C++.

Asked once per word, not per keystroke: the cache is keyed on the buffer and the **start** of the word being
typed, so narrowing a prefix re-filters what clang already said instead of re-parsing.

## Verified

On an arm64 emulator (API 37), from inside the IDE: the plugin unpacked its toolchain, ran
`clang version 18.1.8`, compiled a C++ file and linked `libcaprobe.so` — an AArch64 `ELF64 DYN` exporting
`ca_probe` — and reported through the notification centre. The C++ editor colouring is in the same pass.

**Diagnostics**, also on device: a `.cpp` with `return qux + ok;` gets a red squiggle on `qux`, a gutter
marker, and an inline "use of undeclared identifier" chip, from the real clang. `#include <vector>` resolves,
so the sysroot and resource headers are being found.

**The build**, also on device: an ordinary Build of a module with an `[ndk]` facet produced
`src/main/jniLibs/arm64-v8a/libnative-lib.so` — AArch64 `ELF64 DYN` exporting `ndk_sum` — plus its object
file under `build/ndk/`. The `[ndk]` panel renders and is editable in Module Settings.

**Completion**, on device: typing `values.s` on a `std::vector<int>` offers `push_back(const_reference x)`,
`resize(size_type sz)`, `erase(const_iterator position)` and the rest, read out of the real libc++ headers,
each with its result type and signature.

**Both templates**, on device, created from the gallery and built: the C/C++ Library one produced
`libnative-lib.so` from a header and a `.cpp` in a `java-lib` module; the Native C++ Activity one produced an
APK that installs and RUNS — `android_main started` / `window ready: 1080 x 2400` in logcat, from an app with
no Java in it. The tabs, the breadcrumbs and the project tree all draw the C++ and H badges.

72 unit tests cover the pieces that are pure functions and where the mistakes hide: reading clang's
diagnostic and completion output, decoding the `[ndk]` table, the icon mapping, and what each template
writes AND what it puts in the model.

### One thing those tests cannot catch

**Android's ICU regex engine rejects `}` and `]` as unescaped literals; the desktop JVM accepts them.** Four
patterns here had a bare one. On the JVM every test passed; on the device the pattern threw
`PatternSyntaxException` at class-init, so the completion parser died and the popup silently showed nothing.
Escape both characters in every pattern, and treat a JVM-green regex as unverified until it has run on ART.

### Three more the device found and the JVM could not

- **A stamped unpack that never re-stamps.** The toolchain archive is unpacked once and guarded by a
  `.version` file holding a hand-bumped constant. The archive gained `android_native_app_glue`; the constant
  did not change; every device that had already unpacked kept a tree without it, and a correct project
  reported `'android_native_app_glue.h' file not found`. The stamp now carries the installed driver's size
  and timestamp, so an updated plugin re-unpacks by construction rather than by remembering to.
- **Flags the editor did not have.** The build passed the glue's `-I` and the module's standard; the editor
  passed neither, so a generated NativeActivity project opened with a red error on line 2 of a file that
  built perfectly. `NdkFlags` is now the one place both read, and the diagnostics provider hands the facet to
  it (through `AnalysisTarget.module`) for completion to find — `CompletionParams` carries no `Module`.
- **4 KB-aligned output.** Nothing passed `-z max-page-size=16384`, so every library this plugin linked made
  Android 15 run the app in page-size compatibility mode and say so in a dialog. The NDK's own toolchain
  passes it by default; ours has no default to inherit.

## Not built yet

- Incremental compilation: the task rebuilds every source when any input changes. Per-file up-to-date checks
  would need a header dependency scan (`clang -MD`), which is the next real piece of work here.
- More than one ABI, which needs a toolchain built with more than one backend.
