# Build system

The build is structured around a generic incremental task engine and one or more build systems that
register tasks into it. The engine is build-system-agnostic; the native Java and Android pipelines are
implementations on top of it.

## The `BuildSystem` SPI

```kotlin
interface BuildSystem {
    val id: BuildSystemId
    fun supports(type: ModuleType): Boolean
    fun createBuildGraph(project: Project, request: BuildRequest, ctx: BuildContext): TaskGraph
    fun tasks(project: Project): List<TaskDescriptor>            // assemble, test, lint, clean, …
    fun runTasks(project: Project): List<RunTaskSpec>            // rows for the Run picker
    fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction?
}
```

`createBuildGraph` turns a build request into an executable task DAG over the module graph, with the host's
`BuildContext` supplying the contributed `BuildPlugin`s and the paths/classpaths their tasks need.
`runTasks`/`actionFor` are the enumerate-then-execute pair behind the Run picker, so a build system's own
targets are runnable without the host knowing their ids. Reading a project model out of a build system's files
is a separate SPI (`ProjectImporter`, below), so a build system only builds.

The engine picks a build system per build: one contributed to `platform.buildSystem` whose id matches
`Project.buildSystemId` owns that project outright, otherwise the built-ins are tried by `supports(moduleType)`
and then the contributed ones. Because every build system satisfies the same SPI, the workspace coordinator can
mix them across linked projects.

## The incremental task engine

A build is a DAG of tasks, each declaring typed inputs and outputs:

```kotlin
interface Task {
    val name: String
    val inputs: TaskInputs               // files, dirs, scalar properties, classpath hashes
    val outputs: TaskOutputs             // files, dirs
    suspend fun execute(ctx: TaskContext): TaskResult
}
```

- **Up-to-date checking.** Before running a task the engine fingerprints its inputs (and the existing
  outputs) and compares against the persisted record. A match skips the task; a mismatch runs it and
  records the new fingerprints. Editing one file re-runs only the tasks whose inputs actually changed.
- **Caching.** Two tiers: an up-to-date check, and an optional output cache keyed by input fingerprint
  (which can restore outputs after a clean or share them across variants). On device the cache is
  bounded with LRU eviction.
- **Execution.** A `TaskExecutor` runs the topological levels with bounded parallelism on a coroutine
  dispatcher, supports cooperative cancellation, and streams per-task status and logs to the build
  console.

### Structured diagnostics

Alongside the raw text log (`ctx.logger(): (String) -> Unit`, which stays the untyped transcript — a
program's stdout, step banners, tool chatter), a task streams **structured** diagnostics through
`ctx.diagnostics: DiagnosticSink` *as it discovers them*, rather than concatenating text into a final
`TaskResult.Failed`:

```kotlin
data class BuildDiagnostic(
    val severity: BuildSeverity,                 // ERROR / WARNING / INFO
    val message: String,
    val kind: DiagnosticKind = DiagnosticKind.GENERIC,   // compiler / resource / dex / dependency / lint / packaging — extensible
    val source: String = "",                     // "java", "kotlin", "aapt2", "d8", "apksigner", …
    val location: DiagnosticLocation? = null,    // path + 1-based line/column range (-1 when unknown)
    val code: String? = null,
    val detail: String? = null,                  // a snippet, a hint, or the raw tool line
    val task: TaskName? = null,                  // filled in by the engine
)
```

- **Tagging.** The engine wraps the `TaskContext` per task so every reported diagnostic is stamped with
  the running `TaskName` automatically — producers never need to know their own name.
- **Producing them.** Tool output is text, so `TaskContext.reportToolDiagnostics(source, messages, kind)`
  (build-engine) feeds it through `CompilerOutputParser`, which understands the GNU/javac/kotlinc/aapt2
  single-line form (`path:line[:col]: error|warning: message`) and the ecj batch block form
  (`N. ERROR in <file> (at line L)` … `----------`), streaming one structured diagnostic per problem.
  Un-classifiable but clearly-problematic lines are surfaced location-less so nothing is silently
  dropped; pure chatter is ignored (it still rides the text log). The native compile tasks, and the
  Android `aapt2`/`d8`/`r8`/`apksigner` tasks, all report through this path.
- **To the UI.** The host wires `SimpleTaskContext(onDiagnostic = …)` to append each diagnostic (mapped
  to `BuildDiagnosticUi`) to `BuildState.diagnostics` live. The build console is tabbed — **Problems**,
  **Log**, **Steps** — over a persistent header (live status pill, error/warning counts, elapsed, Run/
  Stop/Copy) and a running-progress strip. The Problems tab groups diagnostics by file with a severity
  filter, shows the captured `detail` snippet inline, and jumps to the `file:line` in the editor on click;
  it auto-opens when a build fails.

### Structured transcript (the log)

The raw transcript is itself structured. `ctx.logger()` routes through `ctx.buildLog: BuildLogSink`,
which carries a `BuildLogEntry(message, level: BuildLogLevel, task: TaskName?, timestampMs)` — the engine
stamps each entry with the running task (same per-task wrapping as diagnostics), so a plain
`ctx.logger()("…")` call still produces a task-attributed `INFO` line while a task can log `WARN`/`ERROR`
directly. The host wires `SimpleTaskContext(onLog = …)` to map them to `BuildLogLine`s (level mapped, a
host-formatted local time, untyped tool lines best-effort level-inferred from the usual `e:`/`w:`/
`error:`/`warning:` prefixes) on `BuildState.log`. The console's **Log** tab groups lines by the task that
produced them (collapsible), colors them by level, and offers a level filter + text search.

## Extending the build

Build logic is contributed, not hard-coded: the built-in Java and Android pipelines are `Plugin`s over the
task container, and a plugin adds its own the same way through `platform.buildPlugin`.

```kotlin
class BuildInfoPlugin : BuildPlugin {
    override val id = "build-info"
    override fun appliesTo(config: BuildConfiguration) = config.request.goal != BuildGoal.CLEAN

    override fun apply(config: BuildConfiguration) {
        for (module in config.project.modules) {
            val gen = TaskName(":${module.name}:generateBuildInfo")
            val out = config.env.generatedDir(module, id)
            config.tasks.register(gen) { WriteBuildInfoTask(gen, out) }        // lazy: runs at realize
            config.tasks.named(Lifecycle.compileJava(module.name)).configure { dependsOn(gen) }
            config.tasks.named(Lifecycle.assemble(module.name)).configure { dependsOn(gen) }
        }
    }
}
```

The host reads the extension point per build and hands the plugins to the build system in `BuildContext`; each
one is applied after the build system's own plugins and before the container is realized. That ordering is what
lets a contributed task wire by name to tasks it does not own, in both directions. Configuring a task that no
build system registered is ignored, so one plugin can target several pipelines without probing which is
running, and a plugin that throws is skipped with a logged warning rather than making the project unbuildable.

A task is the same `Task` the built-ins implement: declare typed inputs and outputs (build-engine's
`TaskInputsImpl`/`TaskOutputsImpl` cover files, dirs, properties, and classpath hashes) and the engine gives it
up-to-date checking, caching, parallelism, cancellation, and console/diagnostic streaming for free. Declaring no
inputs marks the task NO-SOURCE and skips it.

### Lifecycle task names

Contributed tasks anchor to the per-module names each pipeline registers (`Lifecycle` in build-api spells them
out). Every one is optional: configuring an absent task is a no-op.

| Name | What it fronts |
|---|---|
| `:<module>:generateSources` | Source generators, ahead of every compile task. |
| `:<module>:compileKotlin` | Kotlin compilation, ahead of `compileJava`. |
| `:<module>:compileJava` | Java compilation. |
| `:<module>:processResources` | JVM resources copied into the output. |
| `:<module>:classes` | Everything compiled and resources in place. |
| `:<module>:jar` | The module's jar. |
| `:<module>:assemble` | The module's build products, complete. Android suffixes the variant (`assembleDebug`). |

The Android pipeline adds its own variant-suffixed steps (`mergeResources`, `aapt2Link`, `dexBuilder`,
`packageApk`, `sign`, …), listed with the pipeline below.

### Contributing runnable tasks

A `RunTaskProvider` puts rows in the Run picker (`tasksFor(module)`) and executes the ones it owns
(`actionFor(...)` returning a `RunAction`: the graph, the console header, an optional banner, and an optional
post-build step such as installing an APK). Its graph runs through the same executor, console, step list, and
cancellation as a built-in task. An id that reuses a built-in prefix (`build:`, `run:`, `assemble:`) is
dispatched by the host's own pipeline instead, so a provider can offer a row that reuses existing machinery.

## Bringing your own project model

A build system the IDE cannot execute can still describe a project. A `ProjectImporter`
(`platform.projectImporter`) reads its files and returns a declarative snapshot:

```kotlin
interface ProjectImporter {
    val id: BuildSystemId                       // becomes Project.buildSystemId
    val displayName: String
    fun detect(root: Path): Detection?          // is this folder mine?
    val ownership: ModelOwnership               // EXTERNAL: the build files are the source of truth
    fun syncFiles(): List<String>               // globs whose change makes the model stale
    suspend fun resolve(request: SyncRequest): SyncOutcome
}
```

`ExternalProjectModel` holds modules (directory, module-type id, language level, source sets, dependencies,
facets) plus the repositories the build files declare. It is plain data: an importer never touches a model
transaction, which keeps it a pure function of the files it read, testable on its own, and comparable between
syncs. Facets travel as a table name plus TOML-representable values and are decoded by the codec registered for
that table (`platform.facetCodec`), so an importer can emit an `android` facet without depending on the plugin
that defines it, and module types are named by id against `platform.moduleType`.

The host owns everything that is the same for every importer: selecting one by `detect` (highest confidence),
applying the snapshot in a single transaction (`ExternalModelApplier`: add, refresh, remove), binding the
project to the importer's `BuildSystemId`, merging the declared repositories into `.platform/repositories.txt`,
recording the owner and the importer's notes in `.platform/external-project`, and stamping the matched
`syncFiles` in `.platform/sync/<id>.stamp`. A later change to any of those files makes the stamp mismatch, which
is what surfaces the "sync needed" line in the compatibility banner.

Ownership decides what a sync may do. Under `ModelOwnership.EXTERNAL` the build files are the truth: each sync
re-declares dependencies and facets from them and removes modules they no longer declare. Declarations made in
the IDE therefore have to reach those files, which is what `BuildFileWriter` (`platform.buildFileWriter`) is
for: the host routes an add/remove through the writer registered for the project's build system, and when there
is none it still applies the change to the model but says in its result that the build files need the same edit.

## The native Java pipeline

For `java-lib` / `java-cli` modules the graph is `compileJava → jar`, plus a run graph whose exec task
runs a console application's `main` on the runtime classpath (the equivalent of Gradle's `application`
plugin `run`). Compilation goes through a `JavaCompile` port so the compiler backend is pluggable.

On device (ART, where there is no `java` binary to fork) the run graph is `compileJava → dexRun →
runDex`: the runtime classpath is dexed through an injected `RunDexBackend` and handed to an injected
runner. The dexer instruments the run's classpath with two bytecode passes — one that turns
`System.exit`-style calls into a controlled exit the runner catches (so a program's exit ends the run,
not the IDE), and a run sandbox that rewrites network / file / reflection / process call sites to
trampolines mediated by a permission broker (which prompts on an undecided category). This is a
best-effort guard over a curated API set, not a hardened sandbox.

The backend dexes scope-aware and **content-hash cached** (the host impl, `RunDexer` in `:android-support`,
reuses the same caching the Android pipeline uses): immutable library jars (stdlib + dependencies) are
instrumented + dexed once, keyed by content hash + a guard/D8 version, and reused from a per-project staging
cache and a shared cross-project cache; only the changed user class output is re-dexed per build. This keeps
a source edit from re-dexing the whole runtime classpath (the dominant `dexRun` cost). The instrumented run
cache is namespaced apart from the APK pipeline's uninstrumented dex cache. Output is a flat `classes*.dex`
set (libraries first, in a stable order so an unchanged library keeps the same bytes across runs and ART
reuses its oat); the runner loads every `.dex` under it (multidex), so no separate merge step is needed.

### Kotlin/Java mixed modules

A `KotlinCompile` port drives a `compileKotlin` task registered ahead of `compileJava` for any module
containing `.kt`. Kotlin emits to a sibling classes directory that joins the Java compile classpath,
the jar/run classpath, while Kotlin is fed the module's `.java` for resolution — interop in both
directions. Incremental compilation holds across edits.

### Jetpack Compose

Compose code can't be compiled like ordinary Kotlin: the Compose compiler plugin rewrites every
`@Composable` function (threading a synthetic `Composer` + `$changed`/`$default` ints, wrapping bodies in
restart groups). Without it the emitted bytecode is unusable at runtime. So the in-process K2 compiler
(`KotlinJvmCompiler`) takes a generic *compiler-plugin* input — plugin classpaths + `-P` options — and the
host applies the Compose plugin to any module that depends on the Compose runtime (detected by
`androidx.compose.runtime.Composable` on the compile classpath). The plugin jar is bundled as a resource
(`ComposeCompilerPlugin`), the same way the Kotlin stdlib is.

On ART the plugin's `ComposePluginRegistrar` is also dexed into the app: kotlinc reads the plugin's
`META-INF/services` descriptor from the jar but resolves the registrar *class* through parent delegation to
the app classloader, since a jar's bytecode can't be defined at runtime on ART — the same arrangement that
lets the bundled compiler itself run on device. (Note: this is distinct from the on-device Compose
*interpreter* used for live `@Preview`, which doesn't compile user code — see `docs/compose-interpreter.md`.)

## The native Android pipeline

The Android build expresses the APK build as an incremental task DAG, faithful to the Android Gradle
plugin's shape:

```
[compileAidl →] mergeResources → aapt2Compile → aapt2Link (+R) → [compileKotlin →] compileJava
  → dexBuilder → {mergeProjectDex, mergeLibDex, mergeExtDex} → packageApk → sign   (debug / no minify)
  → minify<Variant>WithR8 (shrink+optimize+obfuscate+dex, +resource shrink) → [shrinkResources →] packageApk → sign   (release / minify)
mergeNativeLibs, mergeJavaResource ⇒ packageApk    (run alongside dexing; feed the packager)
```

- **Resources.** A real `mergeResources` folds dependency library, AAR, and app resources; aapt2
  compiles and links them and emits the R class. `values` resources are merged **by entry** — each
  `<resources>` child keyed by (qualifier, tag, type, name), last source wins — so a resource that arrives
  from more than one source (the same library reached through two cache paths, a wrapper AAR plus the AAR it
  forwards to) collapses to one definition instead of reaching `aapt2 link` as a conflict.
- **Dexing.** One dex-builder task archives three scopes (project / sub-module / external) into
  per-class dex archives. The project scope is per-class-file incremental (only changed classes
  re-dex, with the unchanged ones as the desugaring classpath); sub-module and external scopes are
  per-jar content-hash buckets (an unchanged library is reused). Scope merges run only when their
  scope changed. `minSdk ≥ 21` uses native multidex; below that, a single merge produces one
  `classes.dex`. **The native-multidex merge is itself bucketed + incremental (AGP's `DexMergingTask`):**
  classes are distributed across a fixed number of buckets by a stable hash of their class path, each bucket
  merges into its own indexed group, and — keyed by a persisted per-bucket signature — only the buckets whose
  classes changed are re-merged, so editing one class re-merges one bucket instead of the whole scope. Library
  jars are dexed **in parallel** (a worker pool sized from cores and free heap,
  with each D8 invocation's thread count capped so `workers × threads` doesn't oversubscribe — small,
  memory-safe fan-out on a phone; wide on a desktop), reusing three tiers before doing any work: the
  module's own bucket (unchanged since last build), a **shared cross-project content-addressed cache**
  (so a given AndroidX/Compose jar is dexed once per machine, not once per project), then D8. Jar content
  hashes are themselves cached by path+size+mtime so unchanged libraries aren't re-read each build.
  Each library is dexed against the **rest of the library universe as a desugaring classpath** (D8
  `--classpath`, the jar itself excluded), so D8 can resolve the interface hierarchies that default/static
  interface-method desugaring needs — eliminating the "Type … not found, required for … desugaring"
  warnings. android.jar (the bootclasspath) and the classpath jars are handed to D8 as **shared, cached
  resource providers** (AGP's `ClassFileProviderFactory`): each jar is opened + class-indexed once per process
  and reused across every dex invocation, so archiving dozens of libraries no longer re-parses android.jar
  (~26 MB) per library. Archiving runs **in-process** (reusing those shared providers); only the dex *merge*
  (the memory peak) forks a bigger-heap VM on device. The shared cache key is scoped like AGP's dexing
  transforms: when no desugaring applies (minSdk ≥
  26, no core-library desugaring) a library's dex depends only on its own bytes, so the key is **own-content
  only** (AGP's `DexingNoClasspathTransform`) and the bucket is shared across every project regardless of
  classpath; when desugaring applies, the key folds a digest of the **scope-appropriate** library universe
  (AGP's `DexingWithClasspathTransform`) — for an external library that universe is the **external library set
  alone**, since deps point down and an external library never desugars against your sub-modules, app, or R.
  Because the app's project classes and the generated `R.jar` are deliberately kept out of the external
  universe, editing app code, a resource (R shifts), or a sub-module never invalidates an external-library
  bucket. The key also carries a `DEX_CACHE_FORMAT` stamp that must be bumped whenever the bundled r8 version
  changes.
- **R.jar placement (AGP-faithful).** The app's `R.jar` (`compile_and_runtime_not_namespaced_r_class_jar`) is
  dexed in its **own archive scope** and **merged into the project dex layer** (`mergeProjectDex`), where AGP
  keeps R — not the external scope. It is content-hashed, so it re-dexes only when resources change, and being
  out of the external scope means a resource edit re-dexes/re-merges only the small project layer while
  `mergeExtDex` (the stable library layer) stays up-to-date. A resource-only build therefore never touches the
  dependency libraries — the material-you case where a single R shift used to re-dex all ~60 AndroidX/Material
  libraries. Stale `R.class` left in the project *class* output by older compile-R builds are still excluded
  from the project scope (the authoritative R comes only from `R.jar`).
- **In-process memory budget.** On a phone every in-process D8/R8 invocation runs in the IDE's own small,
  shared ART heap, so OOM — not cores — is the limit. The worker/thread plan is sized from `maxMemory()`
  (collapsing to a single worker on a tight heap), R8 (the heaviest whole-program pass) runs with a capped
  worker pool, and the on-device launcher requests `android:largeHeap` to raise the per-app ceiling.
- **Minification (R8) + ProGuard configuration.** When a build type sets `minifyEnabled`, the
  dexBuilder→merge chain is replaced by a single `minify<Variant>WithR8` task that shrinks, optimizes,
  obfuscates, and dexes the app plus every library jar in one pass. Keep rules are gathered AGP-style, in
  order: aapt2's manifest/layout-derived rules (aapt2 link `--proguard`, so XML-referenced activities and
  custom views survive), the build type's `proguardFiles` (a bundled default such as
  `proguard-android-optimize.txt`, resolved like AGP's `getDefaultProguardFile(...)`, plus module-relative
  files), dependency-library and AAR `consumerProguardFiles`, and inline `proguardRules`. `r8FullMode`
  (default on) selects R8 full mode versus ProGuard-compatibility mode. The obfuscation mapping is written to
  `outputs/mapping/<variant>/mapping.txt`. With no keep rules at all, R8 falls back to a pass-through config
  so the dex stays correct.
- **Resource shrinking.** `shrinkResources` (requires `minifyEnabled`) drops resources unreachable from the
  shrunken code. aapt2 links in `--proto-format`, R8's integrated resource shrinker reads/writes the proto
  resources during the same pass, and a `shrinkResources<Variant>` task converts the result back to binary
  (`aapt2 convert`) for packaging — falling back to the un-shrunk archive if R8 emits none, so a shrinker
  hiccup never breaks the APK.
- **Core-library desugaring.** `coreLibraryDesugaringEnabled` makes D8 (debug) / R8 (release) rewrite
  `java.time`/`java.util.stream`/etc. backport call sites per the desugar config, and an L8 task
  (`l8DexDesugarLib<Variant>`) dexes the `desugar_jdk_libs` runtime into its own packaged dex layer (kept
  whole, since L8 release-shrinking against R8's emitted keep rules drops internal helper classes). The
  desugar runtime + config jars are an injected host artifact; when a host ships none the flag is a no-op.
  The config folds into the dex cache key only when enabled, so a no-desugaring build's cache is unchanged.
- **AIDL.** A module whose `aidl/` source root holds `.aidl` files gets a `compileAidl<Variant>` step that
  generates the Binder `IInterface`/`Stub`/`Proxy` Java into a generated source root, which then joins the
  Java and Kotlin compile source paths. There is no `buildFeatures` flag: the task exists exactly when
  `.aidl` files do, and a module without any registers no task. AGP shells this step out to the SDK's `aidl`
  binary, which ships only as a linux-x86_64 executable; the compiler here is a Kotlin implementation
  (`android-support`'s `aidl` package), so the same code serves the desktop build, the on-device build, and
  the editor's pre-build resolution. Dependency modules' and AAR `aidl/` folders are import roots,
  contributing type declarations without being generated a second time, and a library's own `aidl/` is
  packaged into its `.aar`. Framework types are classified from `platforms/android-NN/framework.aidl` when
  the host has one, and otherwise by reading `android.jar` for an `android.os.Parcelable` or
  `android.os.IInterface` supertype.
- **Library-aware.** JAR and AAR dependencies are routed: code to compile/dex, AAR resources into the
  merged app R, AAR assets and JNI into the package.
- **Packaging: native libs + Java resources (AGP-faithful).** Two merge tasks feed the packager, mirroring
  AGP's `merge<Variant>NativeLibs` / `merge<Variant>JavaResource`. `mergeNativeLibs` gathers every `.so` — the
  module's own `src/<set>/jniLibs`, each dependency android-lib's jniLibs, exploded-AAR `jni`, and the `.so`
  entries under `lib` inside dependency jars — into one `<abi>`-laid-out directory the packager maps under
  `lib`. `mergeJavaResource` gathers Java resources — the module's own `src/<set>/resources` and the non-class
  entries of the sub-module + external dependency jars — into a `merged-java-res.jar` whose entries the packager
  copies to the APK root; `.class` entries are skipped (they are dexed). Both apply the module's
  `packaging { }` block (`AndroidPackaging` on the facet) **layered over a faithful set of AGP defaults**: Java
  resources exclude jar signatures, the per-jar `MANIFEST.MF`, Maven/tooling metadata, licence/notice noise,
  Kotlin module/metadata files, and the coroutines debug probe, and **concatenate** the `META-INF/services`
  registrations (ServiceLoader), so a module with no configured packaging still behaves like AGP. Patterns are
  AGP-style globs relative to the APK root (a double-star crosses a slash, a single star stays within a segment,
  a leading slash is optional). Conflicts resolve as exclude → merge → pickFirst → first-wins-with-warning
  (AGP errors on the last case; the on-device IDE is lenient). Sources are offered project-first so a pickFirst
  or unconfigured duplicate keeps the module's own copy. Native `.so` are packaged as-is: debug-symbol stripping
  needs the NDK, absent on device (AGP without an NDK does the same). For an app bundle these merges feed the
  base-module zip too (Java resources under `root/`, `.so` under `lib`).
- **Decoupled library R.** Each library module gets a non-final R from its own resources (kept out of
  its dexed output, so ids are not inlined); the app generates and dexes the final R for all library
  packages. A library is therefore compiled once, independent of the app, with no duplicate R.
- **Multi-module.** The whole module-dependency closure is compiled and every output dexed.
- **Variant-aware.** A build targets one variant (`BuildRequest.variant`), and a dependency library is built
  in the variant that matches it — same build type first, then the most flavor overlap, else the library's
  default variant. The matched variant selects which source sets, resources, R and library dependencies the
  library contributes, so a `debug`- or flavor-only resource or dependency never leaks into the wrong variant.
  Build-variant-scoped dependencies (a `debugImplementation`-style declaration carrying a config qualifier)
  are filtered into the classpath the same way (`Module.classpath(scope, variant)`).

The build/run default variant is the module's **active variant** — persisted per module and shared with the
editor (which analyzes against that variant's classpath). The Run picker still lists every variant's
`assemble`/`bundle`/`androidRun` task explicitly; a task id without a variant suffix falls back to the active
variant.

Tool access is split by the ART reality behind injected ports: aapt2 and zipalign are native binaries
invoked as subprocesses; D8/R8 and apksigner are pure-Java and run either as a subprocess (desktop) or
in process (on device). Factory methods select the subprocess or in-process wiring.

## Gradle compatibility

A Gradle compatibility layer lets an existing Gradle project be opened without executing Gradle. It
statically reads `settings.gradle(.kts)`, each `build.gradle(.kts)`, `gradle.properties`, and version
catalogs, extracts the declarative shape (modules, plugins, `android { }`, `dependencies { }`, source
sets, build types/flavors), and maps it to the project model — then builds with the native engine.

Because Gradle build scripts are Turing-complete, static extraction cannot perfectly handle arbitrary
logic. The strategy is to parse the conventional, declarative majority robustly with a tolerant
block-structured parser, tolerate the rest by recording a diagnostic and continuing, and offer explicit
overrides for values that cannot be extracted. The output is the same project model the native build
system produces, so once synced a Gradle-imported project and a native project are treated identically.

### Implementation (`ide-core`)

`GradleImport` (with the `dev.ide.core.gradle` reader primitives) is the tolerant, **non-evaluating** reader.
It is used both to recover legacy Gradle projects into the picker and to re-sync an open compatibility-mode
project from its scripts.

- **`GradleScript`** — a brace-aware, comment-stripping reader (never throws): locate a named block's body
  (`android { … }`, `dependencies { … }`, `plugins { … }`) by balanced braces, split a block into its
  top-level statements, and enumerate a block's direct child blocks (build types, product flavors). All
  scanning respects string literals, so a brace or `//` inside a quoted string never confuses matching. This
  replaced the earlier line-by-line regex reader, which missed multi-line declarations, block comments, and
  nested blocks.
- **`GradleVersionCatalog`** — reads `gradle/libs.versions.toml` and resolves the type-safe accessors a
  modern AGP build uses (`libs.androidx.core.ktx`, `libs.bundles.compose`, `libs.plugins.android.application`)
  back to coordinates / plugin ids. Handles the `[versions]/[libraries]/[plugins]/[bundles]` sections, the
  `module`/`group`+`name`/shorthand library forms, `version.ref`, and Gradle's accessor normalization (`-`/`_`
  → `.`). It is a dedicated reader because the shared `module.toml` TOML parser rejects the dotted
  `version.ref` key catalogs rely on.
- **`GradleImport`** — composes the two: extracts modules (from `include`), the module type (from the
  `plugins { }` block / `apply plugin:` / a catalog `alias(...)`, with Kotlin/Compose detection), the
  `android { }` SDK / namespace / build-types / product-flavors, and the `dependencies { }` declarations.
  Dependencies cover inline coordinates, `project(...)` module deps, `platform(...)`/`enforcedPlatform(...)`
  BOMs (→ `PlatformDependency`, not a library), catalog accessors, and `kotlin("stdlib")`. `$var`/`${var}`
  are interpolated from `gradle.properties` and `ext`/`def`/`val` assignments; a `debugImplementation`/
  `freeApi`-style configuration maps to the base scope plus the build-variant qualifier the model's
  `OrderEntry.variant` carries. Anything it can't resolve (an unknown catalog alias, an unresolved version
  variable) is collected into a **`SyncReport`** rather than silently dropped.
- **`GradleProjectImporter`** is the `ProjectImporter` over that reader: it detects a Gradle folder, and maps a
  parsed `ProjectSpec` onto an `ExternalProjectModel` (module type ids, source sets, dependency declarations,
  and the `android { }` block encoded through the same facet codec that persists it). Nothing Gradle-specific
  touches the model: the host applies the snapshot, records the marker and the file stamp, and merges the
  declared repositories (see "Bringing your own project model"). `ProjectService.syncProject()` runs a sync and,
  when the model changed, re-resolves dependencies (`retryDependencyResolution`) and re-indexes.
- **`GradleBuildFileWriter`** is the matching `BuildFileWriter`: adding or removing a dependency in the IDE
  edits the module's `dependencies { }` block (creating it when absent) so the declaration is still there after
  the next sync. Edits are located on a comment-masked copy of the script and applied as a single line
  insertion or deletion, leaving the rest of the file byte-for-byte intact.

### Surfacing compatibility mode

An imported project is flagged with a `.platform/external-project` marker recording the importer that owns it,
a one-line summary, and the reader's notes (the older `.platform/imported-from-gradle` marker is still read, so
a workspace imported by an earlier build keeps its compatibility surface). `ProjectInfo.compatibility` is
populated for the open project, so the editor shows a persistent amber **compat chip** in the top bar
(`EditorTopBar`) and a dismissible **details banner** (`GradleCompatBanner`) explaining the limitations, listing
the reader's notes, and offering **Re-sync**. The chip re-opens a dismissed banner, so the limitation is never
fully out of sight. When the recorded file stamp no longer matches the scripts on disk, the banner leads with
"the build files changed since the last sync" instead. `ProjectService.importExternalProject(sourceRootPath)`
imports any folder an importer claims (not just the legacy-recovery path) into a new compatibility-mode
workspace.

The project is bound to `BuildSystemId.GRADLE_COMPAT`, so a build system contributed for that id would take its
builds over; with none registered the native Java/Android pipelines build it as before. Still out of scope: a
live file-watch sync (the stamp is compared on demand), and executing Gradle itself.
