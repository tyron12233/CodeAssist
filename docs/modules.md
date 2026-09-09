# Module map

## Where the modules live

The sixty modules are grouped on disk by layer. The directory says what kind of thing a module is;
the dependency graph further down says what it is allowed to know about.

```
platform/      platform-core, vfs-api, project-model-{api,impl}
lang/          language-api, index-{api,impl}, analysis-{api,impl}, lang-{java,jdt,kotlin,ksp,xml},
               lang-kotlin-index, kotlin-compiler-deps, intellij-psi-host, decompiler,
               block-{api,impl}
build-system/  build-api, build-engine, jvm-build
run/           interp-{api,core,impl,compose}, jvm-interp, awt-toolkit
android/       android-support, android-sdk-metadata, art-compat, layout-preview-{api,impl},
               applog-runtime
services/      deps-{api,impl}, vcs-{api,impl,ui}, store-{api,impl}, analytics-{api,impl},
               agent-{api,impl,mcp,ui}
plugins/       plugin-{api,ui-api,bom,impl}
app/           ide-ui{,-api}, ide-core, ide-desktop, ide-android
tools/         test-support, bench-support
```

Gradle project paths are **flat and independent of that layout**: the Kotlin editor backend is
`:lang-kotlin`, never `:lang:kotlin`, even though it lives at `lang/lang-kotlin`. So a module can be
moved between layers without touching a single `project(":x")` dependency, CI task path, or published
Maven coordinate. `settings.gradle.kts` holds the one mapping from project path to directory, and
fails configuration with a named message if an included module is missing from it.

`build-system/` is spelled out rather than `build/` because `build/` is the root project's own Gradle
output directory. `buildSrc/`, `build-logic/` (an included build, because its plugins need AGP's
classloader) and `samples/` (plugins built as their own apps) stay at the root, where Gradle expects
them.

## Dependency direction

Dependencies point downward only (acyclic). Platform modules carry no domain knowledge; domain
behavior is contributed through extension points.

```
platform-core            no domain knowledge; depended on by all
  └─ vfs-api
       └─ project-model-api
            ├─ build-api
            └─ language-api
deps-api    → project-model-api
index-api   → language-api
analysis-api → language-api, index-api
block-api    → language-api

ide-ui (Compose Multiplatform UI)
ide-core (the engine → UI bridge) → { ide-ui, the implementations }
ide-desktop (JVM launcher) → ide-core
ide-android (Android launcher) → ide-core
```

## Platform and API modules

| Module | Package(s) | Responsibility |
|---|---|---|
| `platform-core` | `dev.ide.platform` | Extension registry, message bus, model read/write lock, activities/progress, disposer, content hashing, plugin ids. |
| `vfs-api` | `dev.ide.vfs` | `VirtualFile`, `VirtualFileSystem`, file events, listeners. |
| `project-model-api` | `dev.ide.model`, `.graph`, `.sync` | Workspace/Project/Module/SourceSet/ContentRoot, order entries + scopes (all open, so a plugin's language can name its own roles/platform/packaging/level/scopes), classpath snapshots, library/SDK tables, module types, variants, facets + their codecs, the registries that resolve all of those, transactions, the project/module graphs, file-icon SPI, the foreign-build-system sync SPI (`ProjectImporter` → `ExternalProjectModel`, `BuildFileWriter`), and the two promoted slices a plugin can resolve: `ModuleSources` + `MODULE_SOURCES` (source sets and source roots) and `ModuleResources` + `MODULE_RESOURCES` (generate a file into a content root of any `ContentRole` and have the IDE see it, plus the Android resource model). |
| `build-api` | `dev.ide.build` | `BuildSystem` SPI; the generic incremental task engine contracts (`Task`/`TaskInputs`/`TaskOutputs`/`TaskGraph`/`TaskExecutor`); the contribution seams (`BuildPlugin`, `RunTaskProvider`/`RunAction`); `BuildControl` + `BUILD_CONTROL`, the narrowed slice of the engine's build service a plugin can resolve. |
| `language-api` | `dev.ide.lang`, `.dom`, `.incremental`, `.resolve`, `.completion` | `LanguageBackend` SPI, source analyzer/compiler contracts, the backend-neutral DOM, incremental parsing, symbol/scope/type resolution, code completion, and a module's analysis inputs (`CompilationContext`, the model binding that builds one, and the `CompilationContextProvider` a plugin supplies its own with). |
| `deps-api` | `dev.ide.deps` | Dependency resolution SPI (Maven coordinates → jars/aars, conflict policy). |
| `vcs-api` | `dev.ide.vcs` | Version-control SPI: the repository/branch/commit/status model, the `VcsProvider` extension point, and the account/credential/forge ports sign-in is built on. Published to Maven Central as part of the plugin SPI, so a plugin can contribute a version-control provider. |
| `agent-api` | `dev.ide.agent` | Coding-agent SPI: the tools an agent can call, the workspace it reads and edits, and the provider-neutral LLM interface behind them. Published to Maven Central as part of the plugin SPI, so a plugin can contribute agent tools or an LLM provider. |
| `index-api` | `dev.ide.index` | Indexing SPI: index extensions, index service, shared value types; `SymbolSearch` + `SYMBOL_SEARCH`, the resolvable symbol/member lookup over them. |
| `analysis-api` | `dev.ide.analysis` | Diagnostics/analyzer/quick-fix SPI: one diagnostic model and one pipeline; compiler errors and analyzer findings merge into the same stream; `ModuleAnalysis` + `MODULE_ANALYSIS`, a module's per-language `SourceAnalyzer` as a resolvable service. |
| `block-api` | `dev.ide.block` | Projectional (block) editor SPI: block tree, block mappings, block edits, the projection service. Published to Maven Central as part of the plugin SPI, so a plugin can contribute a block mapping. |
| `plugin-api` | `dev.ide.plugin`, `.action`, `.external` | The plugin SPI (`Plugin`/`PluginManifest`/`PluginRegistration`), the lean action model (`IdeAction`/`ActionGroup` + named places), and the discovery SPI for plugins the user installed separately (`PluginSource`/`DiscoveredPlugin`/`PluginOrigin`). |
| `plugin-ui-api` | `dev.ide.plugin.ui` | The UI half of the plugin SPI, for a plugin shipped as its own app: the `UiPlugin` facet named by the manifest's `uiEntryPoints`, its tool window / screen / overlay contributions, and the narrow `UiContext` their `@Composable` bodies render against. Compose is `compileOnly` (the host provides it) and it depends on no other CodeAssist module, so publishing it commits to a surface `IdeBackend` changes cannot break. |
| `plugin-bom` | (no code) | The versions a plugin compiles against as one coordinate: the nine published SPI artifacts and the Compose the IDE provides at runtime. Its Compose pins are read out of `PluginTemplate.kt`, so a plugin built with Gradle outside the IDE and one scaffolded inside it cannot end up on different Compose. |

## Implementation modules

| Module | Responsibility |
|---|---|
| `platform-core` (impl) | Extension registry, message bus, model read/write lock, activity/progress engine. |
| `project-model-impl` | Model objects, modifiable-model transactions, `module.toml` load/save, crash-safe writes, the graph + classpath assembly. |
| `build-engine` | The incremental task engine (fingerprints, persistent cache, bounded-parallel executor) and the native Java build system. |
| `index-impl` | The indexing engine: disk-backed segments for static (SDK/library) indices, in-memory incremental data for source. |
| `analysis-impl` | The analysis engine behind `analysis-api` (analyzers, the compiler as a diagnostic provider, profiles, suppression, debounce/cancellation). |
| `block-impl` | The block projection engine and the Java block mapping. |
| `plugin-impl` | The plugin engine: `PluginManager` (topological load, per-plugin unload, fault-tolerant load for plugins the IDE did not write), `PluginCatalog` (enable/disable/dependencies over any manifest set), `ExternalPluginLoader` (compatibility gates + entry-point instantiation), and `ActionManager`. |
| `deps-impl` | The dependency resolver implementation. |
| `vcs-impl` | The Git engine: a JGit-backed working copy (status, staging, commit, branches, diff, stash, fetch/pull/push, clone), the GitHub REST + device-flow client, and the encrypted account store. |

## Language backends

| Module | Responsibility |
|---|---|
| `lang-jdt` | Eclipse JDT/ecj backend (the default): error recovery, working-copy reconcile, completion, batch compile to `.class`; the bytecode members index. |
| `lang-xml` | An Android-agnostic XML backend: an error-tolerant parser, neutral DOM, and context-aware completion driven by injected contributors. |
| `lang-kotlin` | An editor-time Kotlin backend (standalone PSI parse → own symbols/inference/completion) plus Kotlin-to-bytecode codegen for the build. |

## Android support

| Module | Responsibility |
|---|---|
| `android-support` | The Android plugin: `AndroidFacet`, app/library module types, variants, the AIDL compiler, and the native APK pipeline (resource merge, aapt2, R generation, D8 dexing, packaging, signing). Invokes SDK tools behind injected ports. |
| `android-sdk-metadata` | A build-time generator that produces the bundled SDK metadata asset from `attrs.xml` + `android.jar`. |

## UI and launchers

| Module | Responsibility |
|---|---|
| `ide-ui` | The reusable Compose Multiplatform UI (desktop + Android): theme, components, code editor with completion and inline diagnostics, file tree, block editor. Talks only to the `IdeBackend` port. |
| `ide-core` | The shared engine → UI bridge: the `IdeServices` façade over the implementations, and `IdeServicesBackend` implementing `IdeBackend`. |
| `vcs-ui` | The version-control Compose UI as a self-contained plugin: the Git tool window plus the branches, history, diff, sign-in, clone, and GitHub screens. |
| `ide-desktop` | The JVM Compose launcher. |
| `ide-android` | The Android Compose launcher; supplies the on-device ports (dex run, APK install/launch) and the installed-plugin source (`ApkPluginSource`: package-manager discovery, then a `PathClassLoader` over the plugin app's installed APK). |
