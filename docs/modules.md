# Module map

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
| `project-model-api` | `dev.ide.model`, `.graph` | Workspace/Project/Module/SourceSet/ContentRoot, order entries + scopes, classpath snapshots, library/SDK tables, module types, variants, facets, transactions, the project/module graphs, file-icon SPI. |
| `build-api` | `dev.ide.build` | `BuildSystem` SPI; the generic incremental task engine contracts (`Task`/`TaskInputs`/`TaskOutputs`/`TaskGraph`/`TaskExecutor`). |
| `language-api` | `dev.ide.lang`, `.dom`, `.incremental`, `.resolve`, `.completion` | `LanguageBackend` SPI, source analyzer/compiler contracts, the backend-neutral DOM, incremental parsing, symbol/scope/type resolution, code completion. |
| `deps-api` | `dev.ide.deps` | Dependency resolution SPI (Maven coordinates → jars/aars, conflict policy). |
| `index-api` | `dev.ide.index` | Indexing SPI: index extensions, index service, shared value types. |
| `analysis-api` | `dev.ide.analysis` | Diagnostics/analyzer/quick-fix SPI: one diagnostic model and one pipeline; compiler errors and analyzer findings merge into the same stream. |
| `block-api` | `dev.ide.block` | Projectional (block) editor SPI: block tree, block mappings, block edits, the projection service. |

## Implementation modules

| Module | Responsibility |
|---|---|
| `platform-core` (impl) | Extension registry, message bus, model read/write lock, activity/progress engine. |
| `project-model-impl` | Model objects, modifiable-model transactions, `module.toml` load/save, crash-safe writes, the graph + classpath assembly. |
| `build-engine` | The incremental task engine (fingerprints, persistent cache, bounded-parallel executor) and the native Java build system. |
| `index-impl` | The indexing engine: disk-backed segments for static (SDK/library) indices, in-memory incremental data for source. |
| `analysis-impl` | The analysis engine behind `analysis-api` (analyzers, the compiler as a diagnostic provider, profiles, suppression, debounce/cancellation). |
| `block-impl` | The block projection engine and the Java block mapping. |
| `deps-impl` | The dependency resolver implementation. |

## Language backends

| Module | Responsibility |
|---|---|
| `lang-jdt` | Eclipse JDT/ecj backend (the default): error recovery, working-copy reconcile, completion, batch compile to `.class`; the bytecode members index. |
| `lang-xml` | An Android-agnostic XML backend: an error-tolerant parser, neutral DOM, and context-aware completion driven by injected contributors. |
| `lang-kotlin` | An editor-time Kotlin backend (standalone PSI parse → own symbols/inference/completion) plus Kotlin-to-bytecode codegen for the build. |

## Android support

| Module | Responsibility |
|---|---|
| `android-support` | The Android plugin: `AndroidFacet`, app/library module types, variants, and the native APK pipeline (resource merge, aapt2, R generation, D8 dexing, packaging, signing). Invokes SDK tools behind injected ports. |
| `android-sdk-metadata` | A build-time generator that produces the bundled SDK metadata asset from `attrs.xml` + `android.jar`. |

## UI and launchers

| Module | Responsibility |
|---|---|
| `ide-ui` | The reusable Compose Multiplatform UI (desktop + Android): theme, components, code editor with completion and inline diagnostics, file tree, block editor. Talks only to the `IdeBackend` port. |
| `ide-core` | The shared engine → UI bridge: the `IdeServices` façade over the implementations, and `IdeServicesBackend` implementing `IdeBackend`. |
| `ide-desktop` | The JVM Compose launcher. |
| `ide-android` | The Android Compose launcher; supplies the on-device ports (dex run, APK install/launch). |
