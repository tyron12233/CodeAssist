# The on-device Dart SDK

`fetch-dart-android.sh` stages a Dart SDK that **runs on an Android arm64 device**, for the Dart plugin
(`samples/dart-plugin`). Run it once before building the plugin; the plugin build packages whatever it left in
`build/out`, and builds an APK with no SDK (which says so at runtime) when it finds nothing there.

## Where the SDK comes from

Google publishes no Android build of the Dart SDK. [Termux](https://termux.dev) does: its `dart` package is
the upstream SDK built for `android-arm64` against bionic. Its executables link only the system's `libc`,
`libdl`, `libm` and `liblog`, use `/system/bin/linker64`, and run on 16 KB-page devices. The script downloads
that package at a pinned version and checksum (`DART_VERSION`, `DEB_SHA256`); a new version needs both.

The Dart SDK is BSD-licensed (its `LICENSE` ships in the staged assets). Before a public release, decide whether
to keep using Termux's build or build the SDK ourselves (`--os=android --arch=arm64`); nothing in the plugin
depends on which.

## How it is split

An app may not execute a file it wrote (Android 10 and later), and three of the SDK's tools are AOT
snapshots: ELF files the Dart VM maps as code. So everything that runs ships in `jniLibs`, under `lib*.so`
names, and the rest is data:

| Staged as | From the SDK | Used for |
| --- | --- | --- |
| `libdartaotruntime.so` | `bin/dartaotruntime` | running the snapshots below |
| `libdartvm.so` | `bin/dartvm` | running the user's program (JIT) |
| `libanalysis_server.so` | `analysis_server_aot.dart.snapshot` | the language server |
| `libgen_kernel.so` | `gen_kernel_aot.dart.snapshot` | compiling a program for Run |
| `libdartdev.so` | `dartdev_aot.dart.snapshot` | `dart pub` |
| `assets/dart-sdk/` | `lib/`, `version`, ... | the SDK libraries, unpacked on first use |

The web and wasm compilers' platform files are dropped (about 50 MB), and `vm_platform_strong.dill`, a symlink
into Termux's prefix, becomes a copy. About 90 MB of native code and 34 MB of libraries; the plugin APK is
about 53 MB.

## What works, and what does not

- **The analysis server** runs straight from its snapshot (`dartaotruntime analysis_server --protocol=lsp`).
- **Run** compiles with `gen_kernel` (`--no-link-platform`, so the output is the program alone) and runs the
  result on `dartvm`. The JIT is allowed in an app's own process.
- **`dart pub`** works when started through a symlink at `<sdk>/bin/dart`: it finds its SDK two directories
  above the path it was started by. The plugin unpacks the SDK in internal storage, because shared storage
  cannot hold a symlink.
- **`dart run` and `dart test` do not**: they look for their compilers beside the runtime's *resolved* path,
  which is the read-only native library directory. That is why Run is the plugin's own compile-and-run.
