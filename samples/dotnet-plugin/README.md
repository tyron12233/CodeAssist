# The C# plugin

C# on the device, shipped as its own Android app. It carries a .NET 10 runtime and the Roslyn compiler,
compiles and runs console programs, and gives the editor errors, completion, parameter help and quick fixes
that come from the compiler itself.

Originally written by aio and contributed to this repository.

## Building it

The runtime and compiler are **not in this repository**. Assemble them first:

```
tools/dotnet-toolchain/build-dotnet-android.sh       # a few minutes; downloads the NDK if none is set
./gradlew :samples:dotnet-plugin:assembleDebug       # picks up tools/dotnet-toolchain/build/out/arm64-v8a
```

`-Pdotnet.abi=x86_64` builds for the other ABI there is a runtime for (run the toolchain script with
`HOST_ABI=x86_64` first). One APK serves one ABI, because the runtime's native libraries are inside the asset
archive. Point the module somewhere else with `-Pdotnet.toolchain.dir=/path/to/out`. Building with no
toolchain present still produces an APK; it installs and reports that it has no .NET for the device.

## How it is put together

```
CSharpPlugin  (registers everything)
 ├─ sdk/        DotnetSdk (unpacks the tree, starts programs)   CsServer (the one process kept running)
 │              CsCompilerHost (compile)  CsChecker (diagnostics)  CsCompleter (completion, signatures)
 │              CsNavigator (declarations, documentation, colouring)
 ├─ project/    Csproj* (reads .csproj, follows project references)  nuget/ (package restore)  CsMains* (entry points)  templates/
 ├─ build/      CsBuildPlugin  CsCompileTask  CsRunProgramTask  CsRestoreTask  CsRunTaskProvider
 ├─ editor/     CsLanguageBackend  CsDiagnosticProvider  CsDeclarationProvider  quick fixes for usings  CsRunGutter
 ├─ completion/ CsCompletionContributor  CsSemanticCompletion  CsSymbols  CsprojCompletionContributor
 └─ ui/         CsUiPlugin (editor languages, file icons, new-file templates)
```

**Two halves.** What is executed is the launcher, `libdotnetlaunch.so`, in the package's native library
directory, the one place Android lets an app run a file from. What is only read (the runtime, Roslyn, the
reference assemblies, OpenSSL) is `assets/dotnet-sdk.zip`, unpacked on first use into the plugin's private
directory in the IDE's internal storage. tools/dotnet-toolchain/README.md explains where each comes from.

**One resident server.** Starting the compiler costs seconds, nearly all of it the runtime warming up, so
one small C# program (`CsServer`) is compiled once and kept running. It compiles a project, reports
diagnostics, and answers completion and parameter-help requests, taking requests on stdin and answering on
stdout. It is one process rather than one per job because each would load the runtime and Roslyn for itself.
It keeps the project's compilation between requests and replaces only the files that changed, so the editor's
check binds the file being edited and not the whole project. Requests wait for one another: completion asked
during a build is answered after it. The server stops after five idle minutes and starts again on the next
request. If it cannot be built or started, a build falls back to a plain `csc` process and completion to
names read from the reference assemblies.

**Navigation and colouring come from the same compilation.** Go to Declaration, quick documentation and the
colouring of names are three more requests to the server: where the symbol at the caret is declared, its
signature and doc comment, and what each name in the file resolves to. A symbol from the framework or a
package has no source to go to; its documentation still shows, from the XML beside the reference assembly.

**Project references.** A `<ProjectReference>` reaches the referenced project and everything it references
in turn. A build compiles those first, each into its own `bin/`, and copies their DLLs beside the program.
The editor does not wait for a build: the server compiles a referenced project in memory from its sources
and hands it to the referencing one as a reference, so an edit saved in one project shows in the other at the
next check, and Go to Declaration crosses from one into the other. A file belongs to the nearest project
file above it, and a project's sources leave out any folder that has a project file of its own, so a module
may hold several projects.

**No MSBuild, no NuGet client.** Neither is shipped. `Csproj` reads the subset of a project file the plugin
honours and turns it into compiler arguments; it is the single place that interprets a `.csproj`, used by the
build, the editor check and completion alike. `NuGet` talks to nuget.org's package base address directly,
picks the best target framework folder of each package, and follows dependencies with the highest requested
version winning.

**Diagnostics come from a compilation, not from `csc` output.** `csc` stops after the syntax stage, so while
any file has a syntax error it reports only those. The checker asks Roslyn for the diagnostics of the
compilation, built from the editor's unsaved buffer and the project's other files on disk.

## What is not supported

- `Import` and `Directory.Build.props`, solution files, multi-targeting, analyzers and source generators.
- NuGet beyond nuget.org and managed DLLs: no other feeds, no native assets, no package signature checks.
- Conditions in a `.csproj` other than simple comparisons on `Configuration`, evaluated for Debug.
- A debugger, a test runner, the `dotnet` command line, and workloads such as MAUI or Blazor.
- 32-bit devices: there is no Android runtime published for them.

## Older IDE versions

Two things the plugin uses were added to the plugin API in SPI 3.7.0, and it works without them:

- A run mark's action id comes from `GutterActions.RUN_TASK_PREFIX`. It is a constant, so its text is compiled
  into the plugin and an older IDE honours it just the same.
- The entry point chosen in Module Settings comes from the `RunSettings` service. An older IDE has none, and
  there `CsRunConfig` reads the value from the project's `.platform/settings.properties`.
