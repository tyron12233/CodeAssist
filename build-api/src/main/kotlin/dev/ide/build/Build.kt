package dev.ide.build

import dev.ide.model.ClasspathSnapshot
import dev.ide.model.Module
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.model.sync.SyncMessage
import dev.ide.platform.ContentHash
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.VirtualFile

/**
 * build-api — the contract the rest of the IDE talks to, plus the generic incremental task engine.
 *
 * The design separates three concerns Gradle bundles together:
 *  - the [BuildSystem] SPI (this file),
 *  - the generic, reusable incremental [Task]/[TaskGraph] engine (this file),
 *  - the implementations (the native Java/Android pipelines, plus whatever a plugin contributes through
 *    [BUILD_SYSTEM_EP] or [BUILD_PLUGIN_EP]), which live in their own modules.
 *
 * It mirrors Gradle's model->task-graph->incremental-execution pipeline without hosting Gradle.
 *
 * Reading a foreign build system's project model is a separate SPI
 * ([dev.ide.model.sync.ProjectImporter]), so a build system only builds.
 */

// ---------------------------------------------------------------------------
// BuildSystem SPI
// ---------------------------------------------------------------------------

interface BuildSystem {
    val id: dev.ide.model.BuildSystemId

    /** True if this build system can build the given module type. */
    fun supports(moduleType: dev.ide.model.ModuleType): Boolean

    /** Turn a build request into an executable task DAG over the module graph. */
    fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph

    /**
     * Turn a build request into a task DAG with the host's [BuildContext]: the [BuildPlugin]s contributed
     * through [BUILD_PLUGIN_EP] (to apply after this system's own plugins, before realizing the container)
     * plus the host paths and per-module platform classpath in [BuildContext.env]. The default ignores the
     * context and delegates, so a build system that contributes no extension seam needs no change.
     */
    fun createBuildGraph(project: Project, request: BuildRequest, ctx: BuildContext): TaskGraph =
        createBuildGraph(project, request)

    /** Named, runnable tasks (assemble, test, lint, clean) for UI/CLI. */
    fun tasks(project: Project): List<TaskDescriptor>

    /**
     * Run-picker rows this build system offers for [project]. The host lists these for the build system the
     * project is bound to ([Project.buildSystemId]) and dispatches a chosen row back through [actionFor], so
     * a build system's own tasks are runnable without the host knowing their ids.
     */
    fun runTasks(project: Project): List<RunTaskSpec> = emptyList()

    /** The executable form of one of this system's [runTasks] rows; null when [spec] isn't one of its own. */
    fun actionFor(spec: RunTaskSpec, project: Project, ctx: BuildContext): RunAction? = null

    /** Read build files / native manifests and refresh the project model. */
    @Deprecated(
        "Model sync moved to the ProjectImporter SPI (platform.projectImporter); a BuildSystem only builds.",
        level = DeprecationLevel.WARNING,
    )
    suspend fun sync(project: Project, progress: ProgressReporter): SyncResult = SyncResult(true, emptyList())
}

/**
 * What the host hands a [BuildSystem] when it realizes a graph: the contributed [BuildPlugin]s and the
 * environment their tasks need. Passing this rather than widening [BuildRequest] keeps the request a pure
 * description of *what* to build while the context carries *who else contributes* and *where things live*.
 */
class BuildContext(
    /** Applied after the build system's own plugins, in registration order, gated by [BuildPlugin.appliesTo]. */
    val plugins: List<BuildPlugin> = emptyList(),
    val env: BuildEnv,
    /** The registry the graph was realized against, for a plugin that needs to read another extension point. */
    val extensions: ExtensionRegistry? = null,
    /**
     * Where a build system reports a contributed [BuildPlugin] it had to skip: one whose [BuildPlugin.apply]
     * or [BuildPlugin.appliesTo] threw. Such a plugin is skipped rather than allowed to make the project
     * unbuildable, so this is the only trace of it; the host routes it to the build console. A build system
     * that assembles a graph must pass it to `applyBuildPlugins`.
     */
    val onExtensionError: (String) -> Unit = {},

    /**
     * Runs a compiled program by interpreting its bytecode, for a graph that needs to execute what it built.
     *
     * This is how a plugin's own Run row runs something: put an [InterpretExecTask] in the graph and the
     * program runs on the VM, with its output, input and (for a windowed program) its frames and input events
     * going through the [ProgramIo] the host supplied. There is no other way to run code, and deliberately so:
     * the IDE dexes nothing and hands no class loader the user's code, which is what keeps a run inside the
     * dynamic-code rules it has to live by.
     *
     * Null when the host wired none (a test, or a build-only engine that never runs anything). A plugin that
     * needs it should say so rather than assume: report that running is unavailable here.
     */
    val programInterpreter: ProgramInterpreter? = null,
)

data class BuildRequest(
    val targets: List<ModuleId>,
    val variant: VariantSelector,
    val goal: BuildGoal,
)

enum class BuildGoal {
    COMPILE_ONLY, ASSEMBLE, TEST, LINT, PACKAGE, INSTALL, BUNDLE, CLEAN,
    /** Compile + dex (populate the shared library-dex cache) but stop before packaging the APK — used to
     *  prepare a project's libraries for the real-view layout preview without a full assemble. */
    DEX,
}

@JvmInline value class VariantSelector(val name: String)

data class TaskDescriptor(val name: String, val group: String, val description: String)

/** Outcome of the deprecated [BuildSystem.sync]; new code reads [dev.ide.model.sync.SyncOutcome]. */
data class SyncResult(val success: Boolean, val messages: List<SyncMessage>)

// ---------------------------------------------------------------------------
// BuildSystem / run-task extension points
// ---------------------------------------------------------------------------

/**
 * Plugin-contributed build systems. The IDE's own Java/Android build systems are per-project, context-heavy
 * objects the engine constructs and holds directly (they capture per-project state and the Android one defers
 * SDK detection), so they are not modelled as application extensions. This point is the seam through which a
 * plugin adds a [BuildSystem]. The engine selects one two ways:
 *  - by project: a contributed system whose [BuildSystem.id] matches [Project.buildSystemId] owns that
 *    project's builds outright, ahead of the built-ins. This is how a foreign build system takes over a
 *    project its [dev.ide.model.sync.ProjectImporter] imported.
 *  - by module type: otherwise the built-ins are tried first, then this point, by [BuildSystem.supports],
 *    so a plugin can add support for a new module type inside an otherwise native project.
 */
val BUILD_SYSTEM_EP = ExtensionPoint<BuildSystem>("platform.buildSystem")

/**
 * A Run-picker option a [RunTaskProvider] or [BuildSystem] offers: the neutral form of the host's row.
 * [group] is a coarse icon/category key (e.g. `build`, `run`, `android`). The host dispatches [id] to its
 * built-in pipeline when it carries a built-in prefix (`build:`/`run:`/`assemble:`), otherwise back to the
 * contributor's [RunTaskProvider.actionFor] / [BuildSystem.actionFor], so an id may be anything.
 */
data class RunTaskSpec(val id: String, val label: String, val group: String)

/**
 * An executable Run-picker row: the [graph] to run, the console [header] to print, an optional [banner]
 * notice, and an optional [onSuccess] step for work that follows a successful build (install and launch an
 * APK, report an artifact path). The host streams the graph through the same executor, console, and
 * cancellation path as a built-in task.
 */
class RunAction(
    val header: String,
    val graph: TaskGraph,
    val banner: String? = null,
    val onSuccess: (suspend (log: (String) -> Unit) -> Unit)? = null,
)

/**
 * Contributes Run-picker options for a module, and executes the ones it owns. [tasksFor] enumerates;
 * [actionFor] turns a chosen row back into something runnable. A provider that reuses a built-in id prefix
 * (`build:`/`run:`/`assemble:`) is dispatched by the host's own pipeline and needs no [actionFor].
 */
interface RunTaskProvider {
    fun tasksFor(module: Module): List<RunTaskSpec>

    /** The executable form of [spec] for [module]; null when [spec] is not this provider's own. */
    fun actionFor(spec: RunTaskSpec, project: Project, module: Module, ctx: BuildContext): RunAction? = null
}

/** Plugin-contributed Run-picker options, merged into the host's built-in enumeration ([RunTaskProvider]). */
val RUN_TASK_PROVIDER_EP = ExtensionPoint<RunTaskProvider>("platform.runTaskProvider")

// ---------------------------------------------------------------------------
// The generic incremental task engine (mimics Gradle's task model)
// ---------------------------------------------------------------------------

@JvmInline value class TaskName(val value: String)   // ":app:compileFreeDebugJava"

/**
 * A unit of build work that declares typed inputs/outputs so it can be skipped when nothing changed.
 *
 * A task expresses its relationships three ways (all optional — a graph may also wire deps externally):
 *  - [dependsOn]: hard dependencies — they must finish successfully first, and a failure blocks this task.
 *    The same effect arises *implicitly* when this task's inputs read another task's outputs (the engine
 *    infers that edge from the declared paths — see [TaskInputs.declaredPaths]/[TaskOutputs.declaredPaths]).
 *  - [mustRunAfter] / [mustRunBefore]: ordering only — they sequence execution when both tasks are in the
 *    graph, but do not pull the other in and do not block on its failure (Gradle's `mustRunAfter`).
 */
interface Task {
    val name: TaskName
    val inputs: TaskInputs
    val outputs: TaskOutputs

    /** Hard dependencies: must complete successfully before this task; their failure blocks it. */
    val dependsOn: List<TaskName> get() = emptyList()

    /** Ordering only: if present in the graph, this task runs after them (no dependency, no blocking). */
    val mustRunAfter: List<TaskName> get() = emptyList()

    /** Ordering only: if present in the graph, this task runs before them. */
    val mustRunBefore: List<TaskName> get() = emptyList()

    suspend fun execute(ctx: TaskContext): TaskResult
}

interface TaskInputs {
    fun files(key: String, files: Iterable<VirtualFile>)
    fun property(key: String, value: Any?)
    fun classpath(key: String, cp: ClasspathSnapshot)   // hash-based
    /** Stable hash of all declared inputs; compared against the persisted record for up-to-date checks. */
    fun fingerprint(): ContentHash

    /** True if nothing at all was declared — the task has no work to base on, so the engine skips it (NO-SOURCE). */
    fun isEmpty(): Boolean = false

    /** Declared input paths (absolute strings) — the engine matches these against other tasks' output paths
     *  to infer dependencies automatically (consuming an output ⇒ depending on its producer). */
    fun declaredPaths(): Set<String> = emptySet()
}

interface TaskOutputs {
    fun files(key: String, files: Iterable<VirtualFile>)
    fun dir(key: String, dir: VirtualFile)
    fun fingerprint(): ContentHash

    /** Declared output paths (absolute strings), for implicit output→input dependency inference. */
    fun declaredPaths(): Set<String> = emptySet()
}

interface TaskContext {
    val progress: ProgressReporter
    fun checkCanceled()

    /**
     * The raw text transcript channel (a program's stdout, step banners, untyped tool chatter). Routes each
     * line to [buildLog] as a [BuildLogLevel.INFO] [BuildLogEntry] by default, so the plain and structured
     * views never diverge and an existing task that only calls `logger()` still feeds the structured log.
     */
    fun logger(): (String) -> Unit = { buildLog.log(BuildLogEntry(it)) }

    /**
     * Structured diagnostics streamed as the task runs (see [BuildDiagnostic]). Defaults to a no-op so
     * every existing [TaskContext] stays source-compatible; the engine wires a real sink that tags each
     * diagnostic with the running task and forwards it to the host.
     */
    val diagnostics: DiagnosticSink get() = DiagnosticSink.NOOP

    /**
     * The structured transcript channel — the same lines as [logger] but each carrying a [BuildLogLevel]
     * and (once the engine tags it) the producing [TaskName], so a console can color, filter, and group
     * output by task. Defaults to a no-op; the engine wires a real sink and stamps each entry with the
     * running task. A task can call this directly to log at a non-INFO level.
     */
    val buildLog: BuildLogSink get() = BuildLogSink.NOOP
}

sealed interface TaskResult {
    object UpToDate : TaskResult                       // skipped: inputs/outputs unchanged
    object Success : TaskResult
    data class Failed(val message: String, val cause: Throwable? = null) : TaskResult
}

interface TaskGraph {
    val tasks: List<Task>
    /** The *hard* dependencies of [t] (declared, external, or inferred from output→input) — what blocks it
     *  on failure. Ordering-only relations ([Task.mustRunAfter]) affect [topologicalLevels] but not this. */
    fun dependencies(t: Task): List<Task>
    /** Batched levels; tasks within a level are independent and may run in parallel.
     *  @throws CyclicTaskDependencyException if dependency + ordering edges form a cycle. */
    fun topologicalLevels(): List<List<Task>>
}

/** Thrown when task dependency/ordering edges form a cycle; [cycle] lists the tasks on the offending loop. */
class CyclicTaskDependencyException(val cycle: List<TaskName>) :
    RuntimeException("cyclic task dependency: ${cycle.joinToString(" -> ") { it.value }}")

/** Marker for tasks that must run every time (never up-to-date), like Gradle's `JavaExec`/`run`. */
interface AlwaysRun

/**
 * Runs a [TaskGraph]: up-to-date checks via input/output [ContentHash] fingerprints persisted in
 * the build cache, bounded-parallel execution per level, cooperative cancellation, progress streaming.
 * A task marked [AlwaysRun] bypasses the up-to-date check.
 */
interface TaskExecutor {
    suspend fun execute(graph: TaskGraph, ctx: TaskContext, maxParallel: Int = 2): BuildOutcome
}

data class BuildOutcome(val succeeded: Boolean, val ranTasks: List<TaskName>, val skippedTasks: List<TaskName>)
