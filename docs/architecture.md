# Architecture

An extensible, on-device IDE framework for Android/Java development. The framework models projects
abstractly enough to support multiple build systems and multiple language backends behind stable
interfaces, owns its own project structure, and runs entirely on device — where a full Gradle runtime
is impractical.

## Design goals

- **Abstract project model.** No Android- or Gradle-specific knowledge is baked into the core types.
  Domain behavior is contributed through extension points.
- **Build-system independence.** A custom, lightweight build system and a Gradle compatibility layer
  both implement the same SPI. Each project is bound to one build system; linked projects may use
  different ones.
- **Pluggable language backends.** The editor and the build talk to a backend-neutral DOM and an SPI,
  not to a concrete parser. Eclipse JDT/ecj is the default; others slot in behind the same contract.
- **On-device first.** The runtime is Android's ART. Components are chosen for that reality: pure-Java
  tools (ecj, D8/R8, apksigner) run in process; native binaries (aapt2) are invoked as subprocesses
  or replaced with pure-Java equivalents.
- **Crash-safe, lazily loadable model.** The process can be killed at any time, so persistence is
  durable (atomic write-and-rename) and the model loads lazily.

## Layering

Modules have one-directional dependencies. Platform modules expose stable API plus minimal
implementation; domain-specific behavior ships as plugins against published extension points —
including the bundled Android support and build systems.

```
            UI layer (editors, project view, build console)
                              ▲
            Plugins: android-support, language backends, build systems
                              ▲  (implement extension points)
            Platform SPI: project-model, build-api, language-api, deps-api,
                          vfs-api, extension framework, analysis-api
                              ▲
            Platform core: extension registry, VFS, model store,
                           task scheduler, read/write lock, message bus
```

See [modules.md](modules.md) for the concrete module map and responsibilities.

## The project model

The project model is the spine of the framework: the build system, language backends, indexing, and
navigation all read from it. It is abstract, extensible via facets, observable through a message bus,
and mutated only through transactions.

### Containment hierarchy

```
Workspace                         the set of open projects; orchestrates linked builds
 └── Project                      a buildable unit bound to ONE build system
      ├── buildSystem: BuildSystemId   (native | gradle-compat | …)
      ├── Module                  unit of compilation (a "subproject")
      │    ├── type: ModuleType   (android-app | android-lib | java-lib | java-cli | …)
      │    ├── Facet              domain config (AndroidFacet, JavaFacet, …)
      │    ├── SourceSet          (main, test, debug, release, …)
      │    │    └── ContentRoot   (dir + roles: source/resource/android-res/aidl/assets/…)
      │    ├── dependencies: OrderEntry   (module | library | sdk)
      │    └── languageLevel, outputs, …
      └── variants, settings
 ├── LibraryTable (workspace-scoped, interned shared libraries)
 └── SdkTable     (installed Android/Java SDKs)
```

- **Workspace** owns the open projects, the project graph between them, and the workspace-wide library
  and SDK tables.
- **Project** is the unit bound to a build system. Different projects can use different build systems,
  and the workspace coordinates building them in dependency order.
- **Module** is the unit of compilation. (This is not a JPMS `module-info.java` module; JPMS support,
  if added, is a `JavaFacet` concern.)
- **SourceSet** groups content roots that compile together under one configuration.
- **ContentRoot** is a directory tagged with roles (Java source, resources, Android res, aidl, assets,
  generated, excluded). Roles, rather than fixed fields, keep it extensible.
- **Facet** attaches domain-specific configuration to a module without the core knowing the domain.
  `AndroidFacet` holds the manifest path, SDK levels, build types, and flavors; `JavaFacet` holds the
  language level and annotation processors.

`ModuleType` is itself an extension point, so new module types (for example, a Kotlin library) are
registered by a plugin rather than added to the core.

### Dependencies and classpath assembly

Dependencies are an ordered list of order entries per module, each carrying a `DependencyScope` that
mirrors Gradle/Maven (`API`, `IMPLEMENTATION`, `COMPILE_ONLY`, `RUNTIME_ONLY`, `TEST_IMPLEMENTATION`).
An `exported` flag reproduces Gradle's `api` vs `implementation` distinction: an exported entry is
visible to downstream modules' compile classpaths; a non-exported one is not.

`Module.classpath(scope, transitive)` walks the order entries and produces a deduplicated, ordered,
content-hashed `ClasspathSnapshot`:

1. Start with the module's own direct entries matching the requested scope.
2. For each module dependency, include its outputs and recurse into its *exported* entries only.
3. A `COMPILE_ONLY` SDK dependency (such as `android.jar`) is placed on the compile classpath and
   filtered out of the packaged/runtime classpath.

Because classpath correctness lives in the model, build systems and language backends simply consume a
`ClasspathSnapshot` — they never re-derive dependency semantics. The snapshot's content hash is a
build-cache key and a language-backend cache key, so a classpath change invalidates both compilation
and editor analysis.

### Mutation

Readers see an immutable, consistent snapshot. Structural changes go through a modifiable model:
stage edits, then commit atomically under the write lock.

```kotlin
val tx = project.beginModification()           // requires write lock
val mod = tx.addModule("feature-login", AndroidLibModuleType)
mod.addDependency(ModuleDependency("core", scope = API, exported = true))
tx.commit()                                     // atomic; publishes a new snapshot + events
```

On commit, the store swaps in a new snapshot and publishes typed events (`ModuleAdded`,
`DependenciesChanged`, …) on the message bus. A build that started before the commit keeps using its
captured snapshot until it finishes.

### On-disk format (native mode)

In native mode the IDE owns the project structure, so it persists a declarative manifest per module
(`module.toml`) plus workspace-level tables. The format is human-diffable, fast to parse, and not an
executable script — there is no script interpreter on the sync path. Derived state (build cache,
indices, resolved dependencies) lives under a disposable `caches/` directory. Writes go to a temp file
and are atomically renamed, so a model file is never left half-written.

In gradle-compat mode the truth lives in the Gradle files; the framework holds only a derived model
snapshot and caches.

## The two graphs

Two dependency graphs are kept distinct so that linked projects with their own build systems can be
built in parallel or in sequence.

- **Project graph** (workspace level): a DAG of projects. An edge `A → B` means A depends on B's
  published outputs. Each node carries its own build-system id, so the graph can mix a native project
  and a gradle-compat project. Edges record dependency substitution (an external coordinate resolves
  to a locally built sibling project).
- **Module graph** (per project): a DAG of modules connected by module dependencies, all built by that
  project's single build system. Provides topological levels for parallel build and reverse-dependency
  lookup for incremental rebuilds.

A workspace build request is planned in nested layers: resolve the target to the set of projects
needed, order the project graph into levels, build each level's projects (each delegating to its own
build system), and within a project run the task graph over the module graph. Parallelism happens at
both levels, bounded by device-aware limits. A `CoordinationPolicy`
(`PARALLEL_BY_LEVEL` / `STRICTLY_SEQUENTIAL` / `MANUAL_ORDER`) selects the strategy.

## The build-system abstraction

The design separates three concerns that a typical build tool bundles together:

- **The `BuildSystem` SPI** — a stable contract the rest of the IDE talks to (`sync`, `supports`,
  `createBuildGraph`, `tasks`).
- **A generic incremental task engine** — a DAG of tasks, each declaring typed inputs and outputs,
  executed only when fingerprints change, with results restorable from a cache. This engine is
  build-system-agnostic.
- **Implementations** — the native build system (Java and Android pipelines) and a Gradle
  compatibility importer.

The task engine is the durable idea: a build is a DAG of tasks with typed inputs/outputs, up-to-date
checking by fingerprint, and a persistent cache. Editing one file re-runs only the affected subgraph.
See [build-system.md](build-system.md) for the engine and the native pipelines.

## Concurrency model

A single process edits files, parses them, mutates the model, and builds — concurrently — so a
threading discipline keeps the model and ASTs consistent:

- **One model read/write lock** per workspace: many concurrent readers, exactly one writer, writer
  excludes readers.
- **Read actions** wrap any code that reads the model or a DOM. **Write actions** wrap structural
  mutations and VFS event publication; they are serialized and kept short.
- **Long work runs off the UI thread** as cancellable, progress-reporting activities that take read
  actions in slices and check for cancellation.
- **Snapshot isolation** for long jobs: a build or full-project analysis captures an immutable model
  snapshot at start, so a mid-build edit produces a new snapshot without disturbing the in-flight build.

The ordering rule that ties it together: mutate under the write lock, publish an event, and let
consumers invalidate or recompute under a read action on a background activity.

## Extension points

Everything domain-specific plugs in through a minimal extension-point registry. Module types, build
systems, language backends, analyzers, quick-fixes, file icons, project templates, and block mappings
are all contributed this way. See [extension-points.md](extension-points.md).
