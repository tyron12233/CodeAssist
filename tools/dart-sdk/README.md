# The on-device Dart SDK

`fetch-dart-android.sh` stages a Dart SDK that **runs on an Android device**, for the Dart plugin
(`samples/dart-plugin`), one ABI per run (`ABI=arm64-v8a|armeabi-v7a|x86_64`, default arm64-v8a). Run it
before building the plugin, once for each ABI the plugin should support; the plugin build packages whatever it
left in `build/out`, and builds an APK with no SDK (which says so at runtime) when it finds nothing there.

## ABIs

The executables and the AOT snapshots are machine code, so each ABI has its own set under `jniLibs/<abi>/`; the
SDK's libraries (`assets/dart-sdk/`) are the same for all and are rewritten by each run. Runs accumulate: a run
replaces only its own ABI's directory.

```
./fetch-dart-android.sh                    # arm64-v8a
ABI=armeabi-v7a ./fetch-dart-android.sh
ABI=x86_64      ./fetch-dart-android.sh
```

Two things to know about the ABIs:

- **Termux has to publish the package.** The script downloads `dart_<version>_<arch>.deb` (`aarch64`, `arm` or
  `x86_64`). There is no x86 (32-bit) build: Termux publishes no Dart package for `i686`, so the script refuses
  `ABI=x86` (one could be built from the Dart sources with `--os=android --arch=ia32`).
- **Every checksum is pinned**, as Termux's package index (`dists/stable/main/binary-<arch>/Packages`) lists it,
  for Dart 3.13.7. Another `DART_VERSION` needs its own: give the script a `DEB_SHA256` you have verified, or pass
  `ALLOW_UNVERIFIED=1` to have it print the checksum it computed so you can pin it in the table at its top.

The plugin build makes one APK per ABI that has anything staged (see the plugin's `build.gradle.kts`).

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
  which is the read-only native library directory. That is why Run is the plugin's own compile-and-run, and
  why tests run through a generated entry point instead: it imports the test files and hands each `main` to
  test_core's `directRunTests` (the API it keeps for other runners) with the JSON reporter `dart test
  --reporter json` uses, and is compiled and run like a program. It imports `package:test_core/src/...`, so a
  test_core release that moves those files breaks it until the generator follows.

## Flutter

`fetch-flutter.sh` stages Flutter for the same plugin, from a Flutter SDK on the build machine
(`FLUTTER_HOME=/path/to/flutter ./fetch-flutter.sh`). It has to be **Flutter 3.47.9**: the kernel the IDE compiles
on the device must match the Dart VM inside the engine, and 3.47.9 is the release whose Dart is 3.13.7. The
script checks both.

| Staged as | What it is |
| --- | --- |
| `jniLibs/<abi>/libflutter.so` | the Android debug engine for each ABI that Flutter ships one for (`FLUTTER_ABIS`; arm64-v8a, armeabi-v7a, x86_64), stripped with the NDK's `llvm-objcopy` (396 MB to 39 MB for arm64) |
| `root/` | a minimal FLUTTER_ROOT: `packages/flutter` and `flutter_test` (sources only), `sky_engine` (`dart:ui`), the patched platform dill, the version files pub reads (44 MB) |
| `player/flutter_assets/` | the player's own bundle: a 4 KB `dart:ui` stub (`flutter-player/main.dart`) and the VM snapshots |
| `base-assets/` | what every app's bundle starts from: the Material icons font, the framework's compiled shaders, NOTICES |

How an app runs, which is `flutter run` without the `flutter` tool:

- The IDE compiles the app with `frontend_server` (incremental, `--target=flutter`, against the patched platform).
- The player (`FlutterPlayerActivity`, in the plugin's own APK and process) starts on its stub and sends the IDE
  its Dart VM service URI. It is not exported: its VM service runs whatever code it is sent, so the way in is
  `FlutterPlayerLauncher`, which starts it only when the caller is CodeAssist (`getLaunchedFromPackage` on
  Android 14 and later, the referrer before that, and adb for a debuggable build). The IDE uploads the kernel and the asset bundle into a DevFS over the VM service and
  calls `_flutter.runInView`: a hot restart into the app.
- A saved file is recompiled as a delta and loaded with `reloadSources` + `ext.flutter.reassemble`: hot reload,
  state kept. `reset` + `recompile` gives the full kernel a hot restart needs.

Previews: a `.dart` file with Flutter `@Preview` widgets (`package:flutter/widget_previews.dart`) gets the
editor's Preview and Split modes, like a Compose file. The pane shows that file's previews in a generated gallery
that is hot-reloaded on every save, state kept. A preview is sized and themed as Flutter's previewer does it
(`size`, `theme`, `brightness`, `textScaleFactor`, `wrapper`; not yet `localizations`), and follows the IDE's
light or dark scheme. How it gets into the pane (Android 11 and later):

- `FlutterPreviewService`, a bound service in the plugin's own `:preview` process, runs a `FlutterEngine` and a
  `FlutterView` in a `SurfaceControlViewHost` and hands the pane a `SurfacePackage`, which the pane's
  `SurfaceView` attaches. It serves CodeAssist alone, checked per message by `Message.sendingUid`.
- The pane's surface is below the IDE's window, so the IDE receives the touches. The pane forwards them to the
  service, which dispatches them to the view. This keeps the IDE's problem chip and notices drawable over the
  preview.
- The gallery's compiler and engine belong to a session per package that outlives the pane. Switching between
  Split and Preview hands the same running engine a new surface (a fresh package from the same view host)
  instead of restarting it, and a session no pane takes back within a minute is closed.

"Preview widgets" in the Run menu shows every preview in `lib/` in the player instead, which is also what Android
10 and earlier get.

Not possible here: release (AOT) builds, which need a `gen_snapshot` that runs on Android; Flutter plugins with
Android code of their own (the player has none); widget tests (`flutter test` needs Flutter's host test engine).
