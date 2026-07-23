package dev.ide.build

import dev.ide.model.ClasspathSnapshot
import dev.ide.model.Module
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.platform.ContentHash
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.VirtualFile

/**
 * build-api — the contract the rest of the IDE talks to, plus the generic incremental task engine.
 *
 * The design separates three concerns Gradle bundles together:
 *  - the [BuildSystem] SPI (this file),
 *  - the generic, reusable incremental [Task]/[TaskGraph] engine (this file),
 *  - the implementations (native + gradle-compat), which live in plugin modules.
 *
 * It mirrors Gradle's model->task-graph->incremental-execution pipeline without hosting Gradle.
 */

// ---------------------------------------------------------------------------
// BuildSystem SPI
// ---------------------------------------------------------------------------

interface BuildSystem {
    val id: dev.ide.model.BuildSystemId

    /** Read build files / native manifests and refresh the project model. */
    suspend fun sync(project: Project, progress: ProgressReporter): SyncResult

    /** True if this build system can build the given module type. */
    fun supports(moduleType: dev.ide.model.ModuleType): Boolean

    /** Turn a build request into an executable task DAG over the module graph. */
    fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph

    /** Named, runnable tasks (assemble, test, lint, clean) for UI/CLI. */
    fun tasks(project: Project): List<TaskDescriptor>
}

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

data class SyncResult(val success: Boolean, val messages: List<SyncMessage>)
data class SyncMessage(val severity: SyncSeverity, val text: String, val file: VirtualFile? = null)
enum class SyncSeverity { INFO, WARNING, ERROR }

// ---------------------------------------------------------------------------
// BuildSystem / run-task extension points
// ---------------------------------------------------------------------------

/**
 * Plugin-contributed build systems. The IDE's own Java/Android build systems are per-project, context-heavy
 * objects the engine constructs and holds directly — they capture per-project state and the Android one defers
 * SDK detection, so they are not modelled as application extensions. This point is the seam through which a
 * *plugin* adds a [BuildSystem] for a new module type: the engine selects a module's build system by
 * [BuildSystem.supports] — its own built-ins first, then this point.
 */
val BUILD_SYSTEM_EP = ExtensionPoint<BuildSystem>("platform.buildSystem")

/**
 * A Run-picker option a [RunTaskProvider] offers for a module — the neutral form of the host's Run-picker row.
 * [id] is dispatched by the host; reuse a built-in prefix (`build:`/`run:`/`assemble:`/…) to run through the
 * existing pipeline. [group] is a coarse icon/category key (e.g. `build`, `run`, `android`).
 */
data class RunTaskSpec(val id: String, val label: String, val group: String)

/** Contributes extra Run-picker options for a module. The enumeration seam — the host keeps id dispatch, so a
 *  provider reuses a built-in id prefix to execute through the existing task pipeline. */
interface RunTaskProvider {
    fun tasksFor(module: Module): List<RunTaskSpec>
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

/**
 * Runs a [TaskGraph]: up-to-date checks via input/output [ContentHash] fingerprints persisted in
 * the build cache, bounded-parallel execution per level, cooperative cancellation, progress streaming.
 */
interface TaskExecutor {
    suspend fun execute(graph: TaskGraph, ctx: TaskContext, maxParallel: Int = 2): BuildOutcome
}

data class BuildOutcome(val succeeded: Boolean, val ranTasks: List<TaskName>, val skippedTasks: List<TaskName>)
