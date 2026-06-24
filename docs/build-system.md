# Build system

The build is structured around a generic incremental task engine and one or more build systems that
register tasks into it. The engine is build-system-agnostic; the native Java and Android pipelines are
implementations on top of it.

## The `BuildSystem` SPI

```kotlin
interface BuildSystem {
    val id: BuildSystemId
    suspend fun sync(project: ProjectContext): SyncResult        // populate/refresh the model
    fun supports(type: ModuleType): Boolean
    fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph
    fun tasks(project: Project): List<TaskDescriptor>            // assemble, test, lint, clean, …
}
```

`sync` imports or refreshes the project model from the build system's source of truth.
`createBuildGraph` turns a build request into an executable task DAG over the module graph. Because
every build system satisfies the same SPI, the workspace coordinator can mix them across linked
projects.

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
  to `BuildDiagnosticUi`) to `BuildState.diagnostics` live; the build console groups them by file into a
  "Problems" view with severity counts, click-to-open at the line — separate from the raw log pane.

## The native Java pipeline

For `java-lib` / `java-cli` modules the graph is `compileJava → jar`, plus a run graph whose exec task
runs a console application's `main` on the runtime classpath (the equivalent of Gradle's `application`
plugin `run`). Compilation goes through a `JavaCompile` port so the compiler backend is pluggable.

On device (ART, where there is no `java` binary to fork) the run graph is `compileJava → dexRun →
runDex`: the runtime classpath is dexed through an injected dex backend and handed to an injected
runner. The dexer instruments the run's classpath with two bytecode passes — one that turns
`System.exit`-style calls into a controlled exit the runner catches (so a program's exit ends the run,
not the IDE), and a run sandbox that rewrites network / file / reflection / process call sites to
trampolines mediated by a permission broker (which prompts on an undecided category). This is a
best-effort guard over a curated API set, not a hardened sandbox.

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
mergeResources → aapt2Compile → aapt2Link (+R) → [compileKotlin →] compileJava
  → dexBuilder → {mergeProjectDex, mergeLibDex, mergeExtDex} → packageApk → sign   (debug / no minify)
  → minify<Variant>WithR8 (shrink+optimize+obfuscate+dex, +resource shrink) → [shrinkResources →] packageApk → sign   (release / minify)
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
  `classes.dex`. Library jars are dexed **in parallel** (a worker pool sized from cores and free heap,
  with each D8 invocation's thread count capped so `workers × threads` doesn't oversubscribe — small,
  memory-safe fan-out on a phone; wide on a desktop), reusing three tiers before doing any work: the
  module's own bucket (unchanged since last build), a **shared cross-project content-addressed cache**
  (so a given AndroidX/Compose jar is dexed once per machine, not once per project), then D8. Jar content
  hashes are themselves cached by path+size+mtime so unchanged libraries aren't re-read each build.
  Each library is dexed against the **rest of the library universe as a desugaring classpath** (D8
  `--classpath`, the jar itself excluded), so D8 can resolve the interface hierarchies that default/static
  interface-method desugaring needs — eliminating the "Type … not found, required for … desugaring"
  warnings. The shared cache key folds a digest of that whole library universe, so a cached bucket is only
  reused under an identical desugaring classpath; the app's own (project) classes are kept out of the
  universe, so an app edit never invalidates a library bucket. The key also carries a `DEX_CACHE_FORMAT`
  stamp that must be bumped whenever the bundled r8 version changes.
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
- **Library-aware.** JAR and AAR dependencies are routed: code to compile/dex, AAR resources into the
  merged app R, AAR assets and JNI into the package.
- **Decoupled library R.** Each library module gets a non-final R from its own resources (kept out of
  its dexed output, so ids are not inlined); the app generates and dexes the final R for all library
  packages. A library is therefore compiled once, independent of the app, with no duplicate R.
- **Multi-module.** The whole module-dependency closure is compiled and every output dexed.

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
