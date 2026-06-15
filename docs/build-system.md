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

## The native Android pipeline

The Android build expresses the APK build as an incremental task DAG, faithful to the Android Gradle
plugin's shape:

```
mergeResources → aapt2Compile → aapt2Link (+R) → [compileKotlin →] compileJava
  → dexBuilder → {mergeProjectDex, mergeLibDex, mergeExtDex} → packageApk → sign
```

- **Resources.** A real `mergeResources` folds dependency library, AAR, and app resources; aapt2
  compiles and links them and emits the R class.
- **Dexing.** One dex-builder task archives three scopes (project / sub-module / external) into
  per-class dex archives. The project scope is per-class-file incremental (only changed classes
  re-dex, with the unchanged ones as the desugaring classpath); sub-module and external scopes are
  per-jar content-hash buckets (an unchanged library is reused). Scope merges run only when their
  scope changed. `minSdk ≥ 21` uses native multidex; below that, a single merge produces one
  `classes.dex`.
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
