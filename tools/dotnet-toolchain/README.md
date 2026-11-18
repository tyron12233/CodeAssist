# .NET toolchain for the C# plugin

Assembles the .NET runtime, the Roslyn C# compiler and a launcher that run on an Android device. The C#
plugin (`samples/dotnet-plugin`) packages the result.

```
./build-dotnet-android.sh                   # arm64-v8a
HOST_ABI=x86_64 ./build-dotnet-android.sh
./smoke-test-device.sh                      # compile and run a C# program on the connected device
```

Output goes to `build/out/<abi>/`: `jniLibs/<abi>/libdotnetlaunch.so` and `assets/dotnet-sdk.zip`.

## Where the bytes come from

| Piece | Source |
|---|---|
| Runtime (`host/`, `shared/`) | `Microsoft.NETCore.App.Runtime.linux-bionic-<arch>` from nuget.org |
| Reference assemblies and their XML documentation (`ref/`) | `Microsoft.NETCore.App.Ref` from nuget.org |
| C# compiler (`sdk/Roslyn/`) | `Microsoft.Net.Compilers.Toolset` from nuget.org |
| OpenSSL (`lib/`) | built from the release tarball with the NDK |
| Launcher | built from `launcher/dotnetlaunch.c` with the NDK |

Every download is checked against a SHA-256 recorded in `build-dotnet-android.sh`. To move to a new version,
change the version at the top of the script, run it, and record the hash it reports for each new file after
checking the file is the one the publisher released.

Microsoft publishes the Android runtime for arm64 and x86_64 only, so there is no build for 32-bit devices.

## What a device needs that a desktop does not

- **A launcher instead of `dotnet`.** An app may only execute a file from its installed package's native
  library directory, where files are named `lib*.so`, and `dotnet` refuses to run under another name. The
  launcher loads `hostfxr` and passes it the command line.
- **Heap pointer tagging off.** Android 11 and later tag heap pointers on arm64, and the runtime crashes on a
  tagged pointer. The launcher turns tagging off before anything is allocated.
- **OpenSSL carried in the tree.** The runtime's cryptography loads OpenSSL, and the `libssl.so` on a device
  is a different library. The tree has `libssl.so.3` and `libcrypto.so.3`, and the caller sets
  `DOTNET_OPENSSL_VERSION_OVERRIDE=3` with `<tree>/lib` on `LD_LIBRARY_PATH`.
- **No ICU.** The caller sets `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1`.
