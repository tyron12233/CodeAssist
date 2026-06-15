package dev.ide.build

import dev.ide.model.ClasspathSnapshot
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.platform.ContentHash
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

enum class BuildGoal { COMPILE_ONLY, ASSEMBLE, TEST, LINT, PACKAGE, INSTALL, CLEAN }

@JvmInline value class VariantSelector(val name: String)

data class TaskDescriptor(val name: String, val group: String, val description: String)

data class SyncResult(val success: Boolean, val messages: List<SyncMessage>)
data class SyncMessage(val severity: SyncSeverity, val text: String, val file: VirtualFile? = null)
enum class SyncSeverity { INFO, WARNING, ERROR }

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
    fun logger(): (String) -> Unit
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
