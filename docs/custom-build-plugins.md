# Writing custom build plugins

Build logic in CodeAssist is contributed, not hard-coded. The native Java and Android pipelines are
themselves `Plugin`s over a task container, registered through the same interfaces a plugin uses, so a step
you add is not a second-class citizen: it is registered the same way, realized in the same graph, and gets
the same up-to-date checking, parallel scheduling, cancellation, and console output as `compileJava`.

This guide covers the whole build extension surface: writing a task, contributing it to an existing pipeline,
generating source before compilation, adding a Kotlin compiler plugin, putting a row in the Run picker, and
bringing your own build system and project model.

It assumes you have read [writing-plugins.md](writing-plugins.md), because every extension point below is
contributed through the plugin model described there. [build-system.md](build-system.md) is the reference for
the subsystem itself: the engine, both native pipelines, and the Gradle compatibility layer.
[custom-language-support.md](custom-language-support.md) covers the same seams from a language author's
point of view, more briefly.

**Contents**

1. [Before you begin](#1-before-you-begin)
2. [How a build graph is assembled](#2-how-a-build-graph-is-assembled)
3. [Choosing your seam](#3-choosing-your-seam)
4. [Step 1: write a task](#4-step-1-write-a-task)
5. [Step 2: contribute it](#5-step-2-contribute-it)
6. [Generating source](#6-generating-source)
7. [Kotlin compiler plugins](#7-kotlin-compiler-plugins)
8. [Rows in the Run picker](#8-rows-in-the-run-picker)
9. [Bringing your own build system](#9-bringing-your-own-build-system)
10. [Registering the whole thing](#10-registering-the-whole-thing)
11. [Worked example: the Hello plugin's build facet](#11-worked-example-the-hello-plugins-build-facet)
12. [Case study: the Crashlytics build id](#12-case-study-the-crashlytics-build-id)
13. [Test it](#13-test-it)
14. [Checklist](#14-checklist)
15. [Appendix A: the extension points](#appendix-a-the-extension-points)
16. [Appendix B: class index](#appendix-b-class-index)

---

## 1. Before you begin

### What a build extension is

Four extension points cover the two things a build extension does: **add work to a build**, and **bring in a
build system the IDE does not own**.

| Point | Type | Adds |
| --- | --- | --- |
| `platform.buildPlugin` | `BuildPlugin` | Tasks, on every graph that is assembled |
| `platform.sourceGenerator` | `SourceGenerator` | Generated Kotlin/Java, ahead of compilation |
| `platform.kotlinCompilerPlugin` | `KotlinCompilerPlugin` | A bytecode transformer inside kotlinc |
| `platform.runTaskProvider` | `RunTaskProvider` | Rows in the Run picker, and their execution |
| `platform.buildSystem` | `BuildSystem` | A whole pipeline |

Two more belong to the project model rather than the build, and complete the picture for a foreign build
system: `platform.projectImporter` reads its files into a project model, and `platform.buildFileWriter` edits
them back. Both are covered in [section 9](#9-bringing-your-own-build-system).

### Prerequisites

- A working plugin, in-tree or as its own app. [writing-plugins.md](writing-plugins.md) covers both.
- `build-api` on the compile classpath. In-tree that is `implementation(project(":build-api"))`; out of tree
  it is a published coordinate (`io.github.tyron12233:build-api`, versioned through `plugin-bom`), declared
  `compileOnly` like the rest of the SPI.

### What is published, and what is not

This distinction shapes how much code you write, so it is worth knowing up front.

| Module | Published | Holds |
| --- | --- | --- |
| `build-api` | yes | `BuildSystem`, `BuildPlugin`, `Task`, `TaskInputs`/`TaskOutputs` **and their implementations**, `TaskGraph`, `AlwaysRun`, `SourceGenerator`, `KotlinCompilerPlugin`, `RunTaskProvider`, `BuildDiagnostic`, `Lifecycle` |
| `project-model-api` | yes | `Module`, `Project`, `ProjectImporter`, `BuildFileWriter`, facet codecs |
| `build-engine` | **no** | `TaskGraphImpl`, `DefaultTaskContainer`, `TaskExecutorImpl`, `BuildCache`, `applyBuildPlugins`, the generic tasks and path helpers |

Everything a build extension is declared against is published, and so is everything a *task* needs:
`TaskInputsImpl` and `TaskOutputsImpl` live in build-api beside the interfaces they implement, because
declaring inputs and outputs is the one thing no task can skip. What is not published is the machinery that
*runs* a graph, which the host owns anyway. The one consequence for a plugin shipped as its own app is that
building a `TaskGraph` for a `RunAction` means implementing that interface (a few lines for a graph with no
edges: see [section 8](#8-rows-in-the-run-picker)).

### The modules involved

| Module | Role |
| --- | --- |
| [`build-api`](../build-api) | The SPI and the engine's contracts |
| [`build-engine`](../build-engine) | The engine: container, graph, fingerprints, executor, generic tasks |
| [`jvm-build`](../jvm-build) | `JavaBuildSystem` and `JavaPlugin`, the reference pipeline |
| [`android-support`](../android-support) | `AndroidBuildSystem` and the APK/AAB task set |
| [`ide-core`](../ide-core) | `BuildService`: reads the extension points, builds the `BuildContext`, drives the executor |

---

## 2. How a build graph is assembled

A build has a configuration phase and an execution phase, as Gradle does.

```
BuildService.buildContext()        reads BUILD_PLUGIN_EP per build, builds BuildEnv
        |
        v
BuildSystem.createBuildGraph(project, request, ctx)
        |
        +-- the pipeline's own plugins register their tasks   (JavaPlugin, the Android task set)
        +-- applyBuildPlugins(config, ctx.plugins)            every contributed plugin that appliesTo
        |
        v
TaskContainer.build()              realize: run the factories, apply deferred configuration,
        |                          resolve name edges, infer output-to-input edges, detect cycles
        v
TaskExecutorImpl.execute(graph)    level by level, bounded parallelism, up-to-date checks
```

Three properties of the configuration phase are what let a contributed task behave like a built-in one.

**Registration is lazy.** `tasks.register(name) { MyTask(...) }` stores a factory; it runs once, when the
container is realized. Nothing your factory touches has to exist at registration time.

**Configuration is deferred and keyed by name.** `tasks.named(name).configure { dependsOn(...) }` works
whether or not `name` is registered yet, so you can wire to a task another plugin registers later, and it can
wire to yours. `configureEach { }` applies to every task, those registered before the call and after it.

**Contributed plugins are applied last.** Both native pipelines register their own tasks first and call
`applyBuildPlugins` afterwards, then realize once. That ordering is what lets your task be wired in both
directions: yours can depend on `:app:compileJava`, and `:app:assembleDebug` can depend on yours.

Ordering among contributed plugins is registration order, gated by `appliesTo`. If your plugin throws, it is
skipped and the rest of the graph is built: a broken extension must not make a project unbuildable. The
reason is not lost: `applyBuildPlugins` logs it (filterable by plugin in the Logs viewer) and hands it to
`BuildContext.onExtensionError`, which the host turns into a `WARNING` on the build console's Problems tab
and a `WARN` line in its log, on the build the graph was being assembled for.

The realized graph carries three kinds of edge, unioned:

- **Declared hard dependencies**, from `dependsOn` or `Task.dependsOn`. They gate execution and block on
  failure.
- **Inferred hard dependencies**, from matching a task's declared input paths against another task's declared
  output paths. Consuming an output makes you depend on its producer without naming it.
- **Ordering-only edges**, from `mustRunAfter` / `mustRunBefore`. They sequence the topological levels when
  both tasks are present, but pull nothing into the graph and do not block on failure.

A cycle in the combined graph throws `CyclicTaskDependencyException`, which names the tasks on the loop.

---

## 3. Choosing your seam

Most of the work is picking the right one.

| You want to | Use | Notes |
| --- | --- | --- |
| Produce an artifact, post-process an output, stamp a resource, run a check | `BuildPlugin` | Wires by name to the pipeline's tasks |
| Emit Kotlin or Java source to be compiled | `SourceGenerator` | The build wires the output as a source root; a `BuildPlugin` cannot do this portably, see below |
| Transform bytecode during Kotlin compilation | `KotlinCompilerPlugin` | Runs inside kotlinc; you supply the plugin jar |
| Offer something runnable | `RunTaskProvider` | Its graph runs through the host's executor and console |
| Own a project's builds outright | `BuildSystem` | A genuinely different pipeline |
| Open a project the IDE cannot build | `ProjectImporter` | Reading the model is separate from building it |

### Why generated source is its own seam

A compile task reads the module's source roots, and the two pipelines disagree about which roots those are:

- The **native Java/Kotlin pipeline** compiles the module's `ContentRole.SOURCE` **and**
  `ContentRole.GENERATED` roots, resolved when the task executes.
- The **Android pipeline** compiles the variant's `ContentRole.SOURCE` roots plus whichever generated
  directories the pipeline itself wired in (the R class, `generateSources` output, AIDL and ViewBinding
  output). Those lists are fixed when the task is constructed, which happens before your plugin is applied.

So a task that writes a `.kt` file into a directory nothing reads is not compiled by anything. Two
conventions make sure that does not happen:

- A `SourceGenerator` is run by each pipeline into a directory that pipeline has already wired into its
  compile source path: `build/generated/ksp/<variant>` on the Android pipeline, the module's declared
  `ContentRole.GENERATED` root (or `build/generated` when it declares none) on the native one. Both register
  `generateSources` whenever a generator is contributed, so a generator never silently fails to run.
- A `BuildPlugin`'s own task can emit source too, as long as it writes under `build/generated`, which both
  pipelines treat as a source root whether or not the module declares it. `BuildEnv.generatedDir(module, id)`
  returns `build/generated/<id>`, so the conventional call lands in the right place. Nested roots are
  collapsed, so a project that also declares `build/generated` does not get every file twice.

The compile tasks resolve their source roots when the graph is built, so a generated directory that appears
for the first time during a build is picked up on the next one. Prefer `SourceGenerator` for source anyway:
its task is ordered ahead of every compile task for you.

One caveat if you take the `BuildPlugin` route anyway, in a project you control: a module that declares a
`ContentRole.GENERATED` root gets it compiled by the native pipeline (and `BuildEnv.generatedDir(module, id)`
returns `build/generated/<id>`, which is inside the conventional `build/generated` root), but the Android
pipeline ignores `GENERATED` roots entirely.

---

## 4. Step 1: write a task

```kotlin
interface Task {
    val name: TaskName                                      // ":app:writeBuildReport"
    val inputs: TaskInputs
    val outputs: TaskOutputs
    val dependsOn: List<TaskName> get() = emptyList()        // hard: failure blocks this task
    val mustRunAfter: List<TaskName> get() = emptyList()     // ordering only
    val mustRunBefore: List<TaskName> get() = emptyList()
    suspend fun execute(ctx: TaskContext): TaskResult
}
```

A task name is `:<module>:<task>`, and the same string is what appears in the console's Steps tab and in a
`dependsOn` edge. Keep the verb-first Gradle convention (`generateFoo`, `mergeFoo`, `packageFoo`), and suffix
the variant on an Android pipeline task (`injectFooDebug`), because that is what the pipeline's own anchors
do.

### Inputs and outputs are the whole basis of incrementality

Before running a task the engine fingerprints its declared inputs and its declared outputs, and compares both
against the record persisted for that task name. A match makes the task `UP-TO-DATE` without running it. Under-declare
and you ship stale output; over-declare and you never skip.

Declare them as `get()` properties, not `val`s, so they are re-read every time the engine fingerprints the
task:

```kotlin
override val inputs: TaskInputs get() = TaskInputsImpl().apply {
    filePaths("sources", mySources)                          // content-hashed
    dirPaths("deps", depOutputDirs(module))                  // recursive content
    classpath("compileClasspath", module.classpath(DependencyScope.IMPLEMENTATION))  // hash-based
    property("level", levelOf(module.languageLevel))
}
override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("classes", outputDir) }
```

`TaskInputs` and `TaskOutputs` declare `files`/`property`/`classpath` and `files`/`dir` over `VirtualFile`s;
`TaskInputsImpl` and `TaskOutputsImpl`, in the same module, add the `Path`-based `filePaths`, `dirPaths`,
`filePath` and `dirPath` variants most tasks actually use, and hash file and directory content live at
fingerprint time rather than at declaration time. That content sensitivity is what makes "change one file,
re-run only the affected subgraph" correct where a path-only hash would miss it.

Three behaviours worth knowing:

- **Declaring nothing is meaningful.** `inputs.isEmpty()` makes the engine report the task NO-SOURCE and skip
  it without running or caching, which is how `processResources` behaves for a module with no resource roots.
- **Declared paths create edges.** `declaredPaths()` on both sides is what the graph matches to infer
  output-to-input dependencies. It does not cover a directory that is empty when the graph is built, which is
  why `generateSources` gets an explicit edge to the compile tasks rather than relying on inference.
- **Outputs are fingerprinted too**, so deleting or editing an output re-runs the task.

Keep values that change on their own out of the inputs. A timestamp or a build counter makes the task, and
everything downstream of it, re-run on every build. The Crashlytics build id in
[section 12](#12-case-study-the-crashlytics-build-id) is a constant for exactly this reason.

### Executing

```kotlin
override suspend fun execute(ctx: TaskContext): TaskResult {
    ctx.checkCanceled()
    ...
    return TaskResult.Success
}
```

`TaskContext` carries five things:

- `checkCanceled()`. Call it on entry and inside any loop; cancellation is cooperative, and a task that never
  polls cannot be stopped.
- `progress`, a `ProgressReporter` for long work.
- `logger()`, the raw transcript channel: a tool's stdout, a step banner. Each line reaches the console's Log
  tab as an `INFO` entry attributed to your task.
- `buildLog`, the same stream with a level attached (`BuildLogEntry(message, level)`), for the lines worth
  colouring.
- `diagnostics`, the structured channel: `BuildDiagnostic(severity, message, kind, source, location, code,
  detail)`. The console's Problems tab groups these by file, shows `detail` inline, and opens the
  `location` in the editor on click. The engine stamps each one with the running task name, so you never set
  `task` yourself.

Report diagnostics **as you discover them** rather than concatenating text into a final `Failed`. If your task
shells out to a tool, `TaskContext.reportToolDiagnostics(source, messages, kind)` in `build-engine` parses the
usual formats (`path:line:col: error: message`, and the ecj batch block form) into one diagnostic per problem;
lines it cannot classify are surfaced without a location so nothing is silently dropped.

Return `Success`, `UpToDate`, or `Failed(message, cause)`. Do not throw for an expected failure such as a
compile error: `Failed` carries it into the console properly. An unexpected throwable is caught by the
executor, reported as a failure with a truncated stack trace, and does not take the build process down, but it
is a worse diagnostic than the one you could have written.

`AlwaysRun` (a marker interface in `build-api`) opts a task out of the up-to-date check entirely, which is
what a `run`-style task wants.

---

## 5. Step 2: contribute it

```kotlin
class BuildReportPlugin : BuildPlugin {
    override val id = "build-report"
    override fun appliesTo(config: BuildConfiguration) = config.request.goal != BuildGoal.CLEAN

    override fun apply(config: BuildConfiguration) {
        for (module in config.project.modules) {
            val report = TaskName(":${module.name}:buildReport")
            val out = config.env.buildDir(module).resolve("reports/build-report.txt")
            config.tasks.register(report) { BuildReportTask(report, module, out) }
            config.tasks.named(Lifecycle.assemble(module.name)).configure { dependsOn(report) }
        }
    }
}
```

`BuildConfiguration` is what your plugin sees:

| Member | Use |
| --- | --- |
| `project` | The project being built, and its modules |
| `request` | `targets`, `variant`, `goal` (`ASSEMBLE`, `TEST`, `CLEAN`, `INSTALL`, `BUNDLE`, ...) |
| `tasks` | The container to contribute to |
| `buildSystemId` | Which pipeline is assembling this graph, if you need to key off it |
| `env` | `workspaceRoot`, `sharedCachesRoot`, `bootClasspath(module)`, `buildDir(module)`, `generatedDir(module, id)` |

Gate on `appliesTo` rather than returning early from `apply`: it is checked before your plugin runs, and it
keeps the reason for contributing nothing in one place. Gate on the *goal* (a `CLEAN` graph has none of the
anchors below), on `buildSystemId` when your logic is pipeline-specific, and on the project itself (a
classpath probe, a declared dependency, a facet) when your logic is conditional.

To gate on a setting of your own, resolve `SETTINGS_ACCESS` and read your settings page:

```kotlin
private val page = MySettingsPage()
private lateinit var services: ServiceLookup

override fun register(reg: PluginRegistration) {
    services = reg.appServices                       // held, not resolved here
    reg.register(SETTINGS_PAGE_EP, page)
    reg.register(BUILD_PLUGIN_EP, MyBuildPlugin { enabled() })
}

private fun enabled(): Boolean =
    services.getServiceOrNull(SETTINGS_ACCESS)?.reader(page)?.bool("enabled", true) ?: true
```

`SettingsPage.onChanged` only fires when the user changes something, and a value cached from it does not
survive a restart, so read the page when the value is needed. The page is passed rather than its id because
its `scope` decides which store the values live in; a `PROJECT`-scoped page has values only while a project
is open. The
built-in conditional steps all work this way: the Compose compiler plugin probes the compile classpath, KSP
requires a directly-declared processor runtime, the Crashlytics step probes the runtime classpath.

### Lifecycle anchors

`Lifecycle` in [`Plugins.kt`](../build-api/src/main/kotlin/dev/ide/build/Plugins.kt) names the per-module
tasks a pipeline registers. Every one is optional, and configuring an absent task is a no-op, which is what
lets one plugin target several pipelines without probing which is running.

| Anchor | Fronts |
| --- | --- |
| `generateSources(module)` | Source generators, ahead of every compile task |
| `compileKotlin(module)` | Kotlin compilation, ahead of `compileJava` |
| `compileJava(module)` | Java compilation |
| `processResources(module)` | JVM resources copied into the output |
| `classes(module)` | Everything compiled and resources in place |
| `jar(module)` | The module's jar |
| `assemble(module, variant)` | The module's build products, complete |

Your plugin's final task belongs on `assemble`. The Android pipeline suffixes it with the variant being built
(`:app:assembleDebug`), so pass `config.request.variant.name`; naming both spellings is harmless, since only
the one that exists takes the edge.

The Android pipeline registers a much larger variant-suffixed set beyond those anchors
(`mergeResources`, `aapt2Compile`, `aapt2Link`, `dexBuilder`, `mergeProjectDex`, `packageApk`, `sign`,
`minify<Variant>WithR8`, `bundle`, and more). They are listed in [build-system.md](build-system.md), and
wiring to them by name works exactly the same way. They are not part of `Lifecycle` because they are one
pipeline's internal shape rather than a contract every pipeline offers, so check the source for the spelling
you need and expect it to be less stable than an anchor.

### Two more details

**`register` is exclusive.** Registering a name twice throws. Derive your task names from the module so two
modules cannot collide, and use one plugin id per contribution so a duplicate registration of the plugin
itself is visible.

**The extension point is read per build**, rather than cached when the project opens, so a plugin that
registers after a project is already open contributes to the next build of it.

---

## 6. Generating source

```kotlin
interface SourceGenerator {
    val id: String                                    // used for the generated sub-directory and in logs
    fun appliesTo(request: SourceGenRequest): Boolean
    fun generate(request: SourceGenRequest): SourceGenResult
}
```

The build's `generateSources` task runs every applicable generator into a directory it has already wired as a
source root, so what you write compiles and indexes exactly like hand-written code. The generated files also
reach the editor: they are indexed, resolvable, and navigable, and appear in the project tree.

Which directory that is depends on the pipeline: see the end of [section 3](#3-choosing-your-seam).

`SourceGenRequest` is plain paths and names, with no model types:

| Field | Meaning |
| --- | --- |
| `moduleName` | The module being generated for |
| `kotlinSources` / `javaSources` | The module's **hand-written** sources, never the generated root |
| `sourceRoots` | The hand-written source root directories, for package inference |
| `classpath` | The compile classpath, for symbol resolution |
| `outputDir` | Where to write. Created for you before `generate` is called |
| `declaredDependencies` | The module's **directly declared** `group:name` coordinates, never the transitive closure |
| `acceptedWarnings` | Generator-defined warning ids the user chose to proceed past |

Two rules matter more than the rest.

**Gate `appliesTo` on a signal from the project.** A generator that always applies adds a file to every module
of every project the IDE opens. `declaredDependencies` is the right signal when activation should follow an
explicit opt-in: that is how KSP matches AGP, where a processor runs because the module declared its runtime,
not because it arrived transitively through some other library. A classpath marker is the right signal when
the point is "this library is present". Reading the module's own sources for a reference to what you generate
also works, and is what the sample in [section 11](#11-worked-example-the-hello-plugins-build-facet) does.

**Do not write your own output as an input.** The task declares the hand-written sources and the classpath as
its inputs and `outputDir` as its output; a generator that read its own previous output would never settle.
Keep generated content a pure function of the request, with no timestamps, so an unchanged module skips the
whole step.

`SourceGenResult(success, messages)` decides the build. Messages reach the log either way; `success = false`
fails the task with them. If your generator wants to refuse for a reason the user can knowingly accept, mint a
warning id and check `acceptedWarnings` for it: the IDE surfaces such a problem as an editor banner with an
acknowledgement action, persists the choice on the module, and passes it back here.

[`KspSourceGenerator`](../lang-ksp/src/main/kotlin/dev/ide/ksp/KspSourceGenerator.kt) is the reference
implementation, and [kotlin-compiler-plugins-and-codegen.md](kotlin-compiler-plugins-and-codegen.md) explains
what running processors on device involves.

---

## 7. Kotlin compiler plugins

A Kotlin compiler plugin is a bytecode transformer that runs inside kotlinc and emits no new source. Compose,
kotlinx.serialization and Parcelize are the built-in contributors.

```kotlin
interface KotlinCompilerPlugin {
    val pluginId: String                                       // the `-P plugin:<id>:` prefix
    val displayName: String get() = pluginId
    val description: String get() = ""
    val loading: KotlinPluginLoading get() = COMPILE_CLASSPATH
    fun appliesTo(module: Module, classpath: List<Path>): Boolean
    fun classpath(module: Module): List<Path>                  // kotlinc's `-Xplugin` set
    fun options(module: Module): List<String> = emptyList()    // `<pluginId>:<key>=<value>`
}
```

The `compileKotlin` tasks ask every registered plugin whether it applies to the module (the classpath they
pass is the effective compile classpath, so a probe such as Compose's `androidx.compose.runtime.Composable`
check works), then feed the union of the applicable plugins' classpaths and options to the compiler.

`loading` decides how the plugin's registrar reaches the compiler. `COMPILE_CLASSPATH` hands the jar to
kotlinc as `-Xplugin`, which requires the registrar class to be loadable by the compiler itself: on device
that means it must be dexed into the app, because a jar's bytecode cannot be defined at runtime there.
`RUNTIME_REGISTRAR` loads the plugin through a `KotlinPluginLoader` (a `URLClassLoader` on the desktop, a
D8-dexed `DexClassLoader` on ART) and registers it programmatically, which is the path for a plugin that is
not bundled into the app.

`KotlinCompilerPlugin` and `KOTLIN_COMPILER_PLUGIN_EP` live in `build-api`, alongside the rest of the build
SPI: the interface is plain paths and strings, so contributing one needs neither the Kotlin language module
nor the compiler. `BUILTIN_KOTLIN_COMPILER_PLUGINS` (Compose, serialization, Parcelize) stays in `lang-kotlin`
with the implementations.

What you cannot avoid is shipping the plugin jar itself, and on device its registrar has to be loadable from a
dexed classloader, which is what `KotlinPluginLoading.RUNTIME_REGISTRAR` is for.

Some libraries need both halves: a compiler plugin *and* build-time work that the library's own Gradle plugin
would normally do. Hilt is the worked example in
[kotlin-compiler-plugins-and-codegen.md](kotlin-compiler-plugins-and-codegen.md), and the Crashlytics case in
[section 12](#12-case-study-the-crashlytics-build-id) is the same shape with no compiler plugin at all.

---

## 8. Rows in the Run picker

```kotlin
interface RunTaskProvider {
    fun tasksFor(module: Module): List<RunTaskSpec>
    fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? = null
}
```

`tasksFor` enumerates; `actionFor` turns a chosen row back into something runnable. `tasksFor` is called for
every module while the picker is being populated, so keep it to reading the model.

**Dispatch is by id prefix.** An id carrying a built-in prefix (`build:`, `run:`, `assemble:`) is dispatched
to the host's own pipeline and needs no `actionFor`, which is how a provider can offer a row that reuses
existing machinery. Any other id comes back to your `actionFor`. Namespace yours (`myplugin:doThing:<module>`)
and return `null` for a spec that is not yours.

A `RunAction` is a console header, the `TaskGraph` to run, an optional banner notice, and an optional
`onSuccess` step for work that follows a successful build (installing an APK, reporting an artifact path). The
host streams the graph through the same executor, console, step list, and cancellation path as a built-in
task, so a one-task graph is a perfectly good answer:

```kotlin
class SingleTaskGraph(private val task: Task) : TaskGraph {
    override val tasks: List<Task> = listOf(task)
    override fun dependencies(t: Task): List<Task> = emptyList()
    override fun topologicalLevels(): List<List<Task>> = listOf(tasks)
}
```

In-tree, `TaskGraphImpl` and `DefaultTaskContainer` from `build-engine` build a real graph instead, with edge
inference and cycle detection.

A `BuildSystem` bound to a project offers the same pair through `runTasks(project)` and
`actionFor(spec, project, ctx)`, for targets that belong to the pipeline rather than to a module.

---

## 9. Bringing your own build system

### The pipeline

```kotlin
interface BuildSystem {
    val id: BuildSystemId
    fun supports(moduleType: ModuleType): Boolean
    fun createBuildGraph(project: Project, request: BuildRequest, ctx: BuildContext): TaskGraph
    fun tasks(project: Project): List<TaskDescriptor>
    fun runTasks(project: Project): List<RunTaskSpec> = emptyList()
    fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? = null
}
```

Selection happens two ways, and knowing which one you want decides how invasive your build system is:

- **By project.** A contributed system whose `id` matches `Project.buildSystemId` owns that project's builds
  outright, ahead of the built-ins. This is how a foreign build system takes over a project its importer
  claimed.
- **By module type.** Otherwise the built-ins are tried first, then the contributed systems, by
  `supports(moduleType)`. This is how you add support for a new module type inside an otherwise native
  project.

Honour the `BuildContext` you are handed: call `applyBuildPlugins(config, ctx.plugins)` after registering your
own tasks and before realizing the container, or contributed build logic will silently not apply to your
pipeline. Reuse the engine rather than reimplementing it: `DefaultTaskContainer` for the configuration phase,
`TaskGraphImpl` for the graph, `TaskExecutorImpl` and `BuildCache` for execution, and the generic tasks in
`BuildTasks.kt` (`LifecycleTask`, `ProcessResourcesTask`, `JarTask`) for the shapes every pipeline needs.
Register the `Lifecycle` names that make sense for your language, so a contributed plugin has anchors to wire
to.

[`JavaBuildSystem`](../jvm-build/src/main/kotlin/dev/ide/build/jvm/JavaBuildSystem.kt) is a little over a
hundred lines and is the model to follow.

### The project model

Reading a build system's files is a separate SPI, so a build system the IDE cannot execute can still describe
a project.

```kotlin
interface ProjectImporter {
    val id: BuildSystemId                        // becomes Project.buildSystemId
    val displayName: String
    fun detect(root: Path): Detection?           // is this folder mine? must not throw, must not write
    val ownership: ModelOwnership                // EXTERNAL: the build files are the source of truth
    fun syncFiles(): List<String>                // globs whose change makes the model stale
    suspend fun resolve(request: SyncRequest): SyncOutcome
}
```

An importer returns data and never touches the model: `ExternalProjectModel` holds modules (directory,
module-type id, language level, source sets, dependencies, facets) plus the repositories the build files
declare. That keeps it a pure function of the files it read, testable on its own, and comparable between syncs.

Two indirections keep an importer independent of the plugins that define what it names. A module type is named
by its id against `platform.moduleType`. A facet travels as a table name plus TOML-representable values
(`ExternalFacet("android", mapOf(...))`) and is decoded by the codec registered for that table
(`platform.facetCodec`), so an importer can emit an Android facet without depending on Android support.

The host owns everything that is the same for every importer: picking one by `detect` (highest confidence),
applying the snapshot in a single transaction, binding the project to your `BuildSystemId`, merging the
repositories you declared, and stamping the `syncFiles` it matched so a later change to any of them surfaces
as "sync needed".

`ownership` decides what a sync may do. Under `ModelOwnership.EXTERNAL` the build files are the truth: each
sync re-declares dependencies and facets from them and removes modules they no longer declare. Which means a
declaration the user makes in the IDE has to reach those files, and that is what a `BuildFileWriter` is for:

```kotlin
interface BuildFileWriter {
    val id: BuildSystemId
    fun addDependency(module: Module, coordinate: Coordinate, scope: DependencyScope): WriteOutcome
    fun removeDependency(module: Module, coordinate: Coordinate): WriteOutcome
}
```

With no writer registered the host still applies the change to the model, so the classpath works now, and says
in its result that the build files need the same edit.

[`GradleProjectImporter`](../ide-core/src/main/kotlin/dev/ide/core/gradle/GradleProjectImporter.kt) and
`GradleBuildFileWriter` are the reference pair, and [build-system.md](build-system.md) describes the
compatibility layer they belong to.

---

## 10. Registering the whole thing

Build contributions are plain extension-point registrations, so they belong in your plugin's engine facet
next to everything else it registers:

```kotlin
class MyPlugin : Plugin {
    override fun register(reg: PluginRegistration) {
        reg.register(BUILD_PLUGIN_EP, MyBuildPlugin())
        reg.register(SOURCE_GENERATOR_EP, MyGenerator())
        reg.register(RUN_TASK_PROVIDER_EP, MyRunTaskProvider())
        reg.register(BUILD_SYSTEM_EP, MyBuildSystem())
        reg.register(PROJECT_IMPORTER_EP, MyImporter())
        reg.register(BUILD_FILE_WRITER_EP, MyWriter())
    }
}
```

Note the name collision: `dev.ide.plugin.Plugin` is the plugin SPI, and `dev.ide.build.Plugin` is the
build-logic interface that `BuildPlugin` extends. A file that needs both wants an import alias.

**In-tree**, declare the plugin in [`BuiltInPlugins.kt`](../ide-core/src/main/kotlin/dev/ide/core/BuiltInPlugins.kt)
and depend on `:build-api` from your module. `GradleSupportPlugin` and `KspSupportPlugin` there are two-line
examples of exactly this.

**As its own app**, add the SPI to your plugin project and register from the entry point the manifest names:

```kotlin
dependencies {
    compileOnly(platform("io.github.tyron12233:plugin-bom:<version>"))
    compileOnly("io.github.tyron12233:plugin-api")
    compileOnly("io.github.tyron12233:build-api")   // brings project-model-api, vfs-api, platform-core
}
```

One constraint is worth knowing, and it is not about publishing: your build code runs inside the IDE process,
which targets **minSdk 26**, so it is subject to the same JDK method floor as the rest of the app. `Path.of`,
`Files.readString`, `Files.writeString` and `Stream.toList()` are not available on the older devices the IDE
supports; use `Paths.get`, `Files.readAllBytes`, `Files.write` and `Collectors.toList()`. D8 dexes the too-new
call happily and it throws only when the line is reached, and a `runCatching` around it turns the crash into a
silent wrong result.

Declare what you contribute in the manifest's `capabilities`, which is the list the user reads at the consent
gate: `build.task` for build steps, `build.sourceGenerator` for generated source, `build.runTask` for a Run
row. All three are engine-facet capabilities, so a manifest declaring one with no `entryPoints` is flagged, as
is a spelling the IDE does not know.

---

## 11. Worked example: the Hello plugin's build facet

[`samples/hello-plugin`](../samples/hello-plugin) is a plugin shipped as its own app, and
[`HelloBuildPlugin.kt`](../samples/hello-plugin/src/main/kotlin/com/example/hello/HelloBuildPlugin.kt) is its
build facet: three contributions, plus the engine interfaces an out-of-tree plugin implements by hand. It is a
module of this build, so it cannot drift from an SPI change without failing the build.

Read it in this order.

**The task, `HelloBuildReportTask`.** It writes a short report about the module under
`build/reports/hello-build-report/`. Its entire declared input is the report text, which is exactly the
condition under which its output would differ, so the second build reports it UP-TO-DATE and the report never
churns. It reports a `WARNING` diagnostic (not a log line) when the module declares no source roots that exist,
and logs at `ERROR` level through `buildLog` if writing fails.

**The build plugin, `HelloBuildReportPlugin`.** One task per module, `appliesTo` gated on the goal and on the
plugin's own settings toggle (read through `SETTINGS_ACCESS`, resolved per call so the current value wins),
and the task hung off `assemble` by name. It names both `:<module>:assemble` and the variant-suffixed
`:<module>:assemble<Variant>`, so the same plugin works on the Java and Android pipelines without asking which
is running.

**The generator, `HelloBuildInfoGenerator`.** It emits `hello.buildinfo.HelloBuildInfo` (module name, source
file count, declared dependencies) as Kotlin, or as Java for a module with no `.kt`. `appliesTo` returns true
only when the module's own sources mention the class, so nothing is generated into a project that never asked
for it. The generated content is a pure function of the request: no timestamp, so an unchanged module skips
the step.

**The Run row, `HelloRunTaskProvider`.** One row per module, `hello:buildReport:<module>`, which writes the
same report through the same task without a full build. It shows the id-prefix rule (the id must not start
with a built-in prefix or it never comes back to `actionFor`) and the minimal `TaskGraph`.

**The one-task graph, `SingleTaskGraph`.** `RunAction` wants a `TaskGraph`, and for a single task with no
dependencies the interface is three lines, which is less code than reaching for the engine's real graph. The
task's inputs and outputs use `TaskInputsImpl`/`TaskOutputsImpl` straight from `build-api`.

Build and install it with:

```
./gradlew :samples:hello-plugin:installDebug
```

Then restart the IDE, allow the plugin, and build any project: the console's Steps tab lists
`:<module>:helloBuildReport`, and the Run picker carries its row.

---

## 12. Case study: the Crashlytics build id

A real contribution in the Android pipeline, and a good illustration of why this seam exists at all.

The Firebase Crashlytics runtime reads a build id out of a string resource. Nothing in a project's build files
names that resource: applying the Crashlytics **Gradle plugin** is what normally writes it, and CodeAssist has
no Gradle plugins. Without it, every app that merely has `firebase-crashlytics` on its classpath dies at
startup, before any user code runs, because `FirebaseInitProvider` initialises Crashlytics from a `<provider>`
in the merged manifest and `CrashlyticsCore.onPreExecute` throws.

The parts, in [`android-support`](../android-support):

- [`crashlytics/Crashlytics.kt`](../android-support/src/main/kotlin/dev/ide/android/support/crashlytics/Crashlytics.kt)
  holds the contract read off the library's own bytecode: the resource name, the file name the Gradle plugin
  writes, the XML it writes, and the classpath probe. Pure string and path logic, no Android types, so it is
  unit-testable without an SDK.
- [`tasks/InjectCrashlyticsMappingFileIdTask.kt`](../android-support/src/main/kotlin/dev/ide/android/support/tasks/InjectCrashlyticsMappingFileIdTask.kt)
  is the task: it writes one `values/` XML into a generated resource directory. Its input is a single
  `property`, and its output is that directory.
- `AndroidBuildSystem` registers `inject<Variant>CrashlyticsMappingFileId` when the probe matches, and adds
  the generated directory to the resource merge's inputs.

Four decisions in it are worth copying:

**Probe the right classpath.** The probe reads the *runtime* closure, not the compile classpath, and does not
require a direct declaration: Crashlytics initialises itself from the manifest merged out of its own AAR, so
an app that reaches it transitively through any Firebase convenience artifact crashes just the same. Hilt's
probe is the opposite (directly-declared only) because activation there follows an explicit opt-in. Match the
probe to the failure mode.

**Probe model data, not the file system.** The check matches the resolver's Maven-layout cache paths, which
are model data, so it opens no files and is safe to call while the graph is being built, before a first clean
build has exploded any AAR.

**Keep the output stable.** The id written is the constant the real plugin uses when mapping-file upload is
off. A unique id per build would rewrite the resource every build and dirty the resource merge every time.

**Generate where the pipeline already looks.** The task writes into a directory the pipeline adds to
`mergeResources`, below the app's own `res` so the app can still override. Nothing generated is useful unless
some existing task consumes it, which is the same constraint [section 3](#3-choosing-your-seam) describes for
generated source.

Two more of the same shape are worth reading: the AIDL step (`compileAidl<Variant>`, which exists exactly when
a module has `.aidl` files and registers no task otherwise) and the `google-services.json` step, which
generates string resources into the same merge.

---

## 13. Test it

Every seam here is testable without a device, and the existing tests are the fastest way to see the wiring
end to end.

| Test | Covers |
| --- | --- |
| [`BuildPluginContributionTest`](../jvm-build/src/test/kotlin/dev/ide/build/jvm/BuildPluginContributionTest.kt) | A contributed task lands in the graph, is wired by name in both directions, runs, and is gated by `appliesTo`; a throwing plugin does not break the build |
| [`SourceGenerationTest`](../jvm-build/src/test/kotlin/dev/ide/build/jvm/SourceGenerationTest.kt) | A generator's output is compiled and runs, the graph edge exists, and an unchanged rebuild does not re-run it |
| [`TaskContainerTest`](../build-engine/src/test/kotlin/dev/ide/build/engine/TaskContainerTest.kt) | Lazy registration, configuring a not-yet-registered task, `configureEach`, cycle detection |
| [`EngineDependencyTest`](../build-engine/src/test/kotlin/dev/ide/build/engine/EngineDependencyTest.kt) | Declared, external and inferred edges; ordering-only relations |
| [`IncrementalEngineTest`](../build-engine/src/test/kotlin/dev/ide/build/engine/IncrementalEngineTest.kt) | Up-to-date checks, NO-SOURCE, re-run on input change |
| [`BuildSystemExtensionTest`](../ide-core/src/test/kotlin/dev/ide/core/BuildSystemExtensionTest.kt) | Build-system selection, Run-picker merging, a contributed action executing, an unknown id failing with a message |

The shape of a build-plugin test is short: build a workspace with the model store, realize a graph with your
plugin in the `BuildContext`, assert on the graph, then run it through `TaskExecutorImpl`.

```kotlin
val graph = JavaBuildSystem().createBuildGraph(
    project,
    BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.ASSEMBLE),
    BuildContext(plugins = listOf(MyBuildPlugin()), env = buildEnv(dir)),
)
assertTrue(":app:myTask" in graph.tasks.map { it.name.value })
val compile = graph.tasks.single { it.name == Lifecycle.compileJava("app") }
assertTrue(graph.dependencies(compile).any { it.name.value == ":app:myTask" })

val outcome = runBlocking {
    TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(), 2)
}
assertTrue(outcome.succeeded)
```

Assert on more than "it succeeded". The two things that break in practice are scheduling
(`topologicalLevels()` puts your task before its consumer) and incrementality (a second `execute` reports your
task in `skippedTasks`, and your generator's invocation counter has not moved).

---

## 14. Checklist

- [ ] The right seam: `SourceGenerator` for source, `BuildPlugin` for everything else, `BuildSystem` only for
      a genuinely different pipeline.
- [ ] Task names are `:<module>:<verbFirstName>`, variant-suffixed on the Android pipeline.
- [ ] Inputs and outputs are declared honestly, as `get()` properties, and contain nothing that changes on its
      own.
- [ ] `checkCanceled()` on entry and in every loop.
- [ ] Failures return `TaskResult.Failed`; problems are reported through `diagnostics` as they are found, not
      concatenated into a final message.
- [ ] `appliesTo` gates on the goal, and on a project signal when the contribution is conditional.
- [ ] The final task is wired to `assemble`, both the plain and the variant-suffixed spelling.
- [ ] Anything generated is consumed by a task that already exists, or it is dead output.
- [ ] Run-picker ids are namespaced and do not start with `build:`, `run:` or `assemble:`.
- [ ] A contributed `BuildSystem` calls `applyBuildPlugins` before realizing its container.
- [ ] The manifest declares the build capabilities it actually contributes.
- [ ] No `Path.of` / `Files.readString` / `Files.writeString` / `Stream.toList()`: the IDE process is
      minSdk 26.
- [ ] Tests cover the graph edge, the scheduling order, and the second build being up to date.

---

## Appendix A: the extension points

| FQN | Id | Type | Contribute to add |
| --- | --- | --- | --- |
| `dev.ide.build.BUILD_PLUGIN_EP` | `platform.buildPlugin` | `BuildPlugin` | Tasks on every build graph |
| `dev.ide.build.SOURCE_GENERATOR_EP` | `platform.sourceGenerator` | `SourceGenerator` | Generated source before compilation |
| `dev.ide.build.RUN_TASK_PROVIDER_EP` | `platform.runTaskProvider` | `RunTaskProvider` | Rows in the Run picker |
| `dev.ide.build.BUILD_SYSTEM_EP` | `platform.buildSystem` | `BuildSystem` | A whole pipeline |
| `dev.ide.model.sync.PROJECT_IMPORTER_EP` | `platform.projectImporter` | `ProjectImporter` | Reading a foreign project model |
| `dev.ide.model.sync.BUILD_FILE_WRITER_EP` | `platform.buildFileWriter` | `BuildFileWriter` | Writing declarations back to build files |
| `dev.ide.model.FACET_CODEC_EP` | `platform.facetCodec` | `FacetCodec` | Decoding a facet an importer emits |
| `dev.ide.model.ModuleTypeExtensionPoint` | `platform.moduleType` | `ModuleType` | A module type an importer can name |
| `dev.ide.build.KOTLIN_COMPILER_PLUGIN_EP` | `platform.kotlinCompilerPlugin` | `KotlinCompilerPlugin` | A bytecode transformer inside kotlinc |

## Appendix B: class index

### The SPI: [`build-api`](../build-api)

| File | Holds |
| --- | --- |
| [`Build.kt`](../build-api/src/main/kotlin/dev/ide/build/Build.kt) | `BuildSystem`, `BuildContext`, `BuildRequest`, `BuildGoal`, `Task`, `TaskInputs`, `TaskOutputs`, `TaskContext`, `TaskResult`, `TaskGraph`, `TaskExecutor`, `RunTaskProvider`, `RunTaskSpec`, `RunAction` |
| [`Plugins.kt`](../build-api/src/main/kotlin/dev/ide/build/Plugins.kt) | `Plugin`, `BuildPlugin`, `BuildConfiguration`, `BuildEnv`, `TaskContainer`, `TaskProvider`, `TaskSpec`, `Lifecycle` |
| [`TaskFingerprints.kt`](../build-api/src/main/kotlin/dev/ide/build/TaskFingerprints.kt) | `TaskInputsImpl`, `TaskOutputsImpl` |
| [`SourceGenerator.kt`](../build-api/src/main/kotlin/dev/ide/build/SourceGenerator.kt) | `SourceGenerator`, `SourceGenRequest`, `SourceGenResult` |
| [`KotlinCompilerPlugin.kt`](../build-api/src/main/kotlin/dev/ide/build/KotlinCompilerPlugin.kt) | `KotlinCompilerPlugin`, `KotlinPluginLoading`, `ResolvedKotlinPlugins`, `KOTLIN_COMPILER_PLUGIN_EP` |
| [`BuildDiagnostics.kt`](../build-api/src/main/kotlin/dev/ide/build/BuildDiagnostics.kt) | `BuildDiagnostic`, `BuildSeverity`, `DiagnosticKind`, `DiagnosticLocation`, `DiagnosticSink`, `BuildLogEntry`, `BuildLogLevel`, `BuildLogSink` |

### The engine: [`build-engine`](../build-engine) (not published)

| File | Holds |
| --- | --- |
| [`TaskEngine.kt`](../build-engine/src/main/kotlin/dev/ide/build/engine/TaskEngine.kt) | `DefaultTaskContainer`, `TaskGraphImpl`, `TaskExecutorImpl`, `BuildCache`, `TaskStatus`, `SimpleTaskContext` |
| [`BuildTasks.kt`](../build-engine/src/main/kotlin/dev/ide/build/engine/BuildTasks.kt) | `applyBuildPlugins`, `SimpleBuildConfiguration`, `DefaultBuildEnv`, `LifecycleTask`, `ProcessResourcesTask`, `JarTask`, the module/path helpers |
| [`JvmBuildHelpers.kt`](../build-engine/src/main/kotlin/dev/ide/build/engine/JvmBuildHelpers.kt) | `sourceRootDirs`, `generatedRoot`, `collapseNestedRoots`, the classpath/output paths |
| [`GenerateSourcesTask.kt`](../build-engine/src/main/kotlin/dev/ide/build/engine/GenerateSourcesTask.kt) | `GenerateSourcesTask` |
| [`CompilerOutputParser.kt`](../build-engine/src/main/kotlin/dev/ide/build/engine/CompilerOutputParser.kt) | Tool-output to `BuildDiagnostic` parsing, behind `reportToolDiagnostics` |
| [`ProgramRun.kt`](../build-engine/src/main/kotlin/dev/ide/build/engine/ProgramRun.kt) | `ProgramIo`, `RunWindow` |

### Reference implementations

| Where | What |
| --- | --- |
| [`JavaPlugin`](../jvm-build/src/main/kotlin/dev/ide/build/jvm/JavaPlugin.kt) | The `compileJava → processResources → classes → jar` chain, plus `compileKotlin` and `generateSources` |
| [`JavaBuildSystem`](../jvm-build/src/main/kotlin/dev/ide/build/jvm/JavaBuildSystem.kt) | A minimal `BuildSystem` |
| [`AndroidBuildSystem`](../android-support/src/main/kotlin/dev/ide/android/support/AndroidBuildSystem.kt) | The APK/AAB pipeline, and the conditional steps it registers |
| [`KspSourceGenerator`](../lang-ksp/src/main/kotlin/dev/ide/ksp/KspSourceGenerator.kt) | A `SourceGenerator` |
| [`ComposeCompilerPlugin`](../lang-kotlin/src/main/kotlin/dev/ide/lang/kotlin/compile/ComposeCompilerPlugin.kt) | A `KotlinCompilerPlugin` |
| [`GradleProjectImporter`](../ide-core/src/main/kotlin/dev/ide/core/gradle/GradleProjectImporter.kt) | A `ProjectImporter` and its `BuildFileWriter` |
| [`HelloBuildPlugin.kt`](../samples/hello-plugin/src/main/kotlin/com/example/hello/HelloBuildPlugin.kt) | All of the above from an installed plugin app |
| [`BuildService`](../ide-core/src/main/kotlin/dev/ide/core/services/BuildService.kt) | The host side: reading the points, the `BuildContext`, dispatch |
