package dev.ide.build.engine

import dev.ide.build.BuildOutcome
import dev.ide.build.CyclicTaskDependencyException
import dev.ide.build.Task
import dev.ide.build.TaskContainer
import dev.ide.build.TaskContext
import dev.ide.build.TaskExecutor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.TaskProvider
import dev.ide.build.TaskResult
import dev.ide.build.TaskSpec
import dev.ide.platform.ProgressReporter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.nio.file.Paths

/**
 * A task DAG with batched [topologicalLevels] (Kahn's algorithm); tasks in a level are independent.
 *
 * Edges come from three sources, unioned: the external [deps] map (the legacy wiring), each task's own
 * [Task.dependsOn], and *inferred* output→input edges (a task that reads another's declared output path
 * depends on its producer — see [inferOutputDeps]). Those are **hard** deps ([dependencies]) and gate the
 * executor on failure. [Task.mustRunAfter]/[Task.mustRunBefore] add *ordering-only* edges: they sequence
 * [topologicalLevels] but are not returned by [dependencies], so they never block on failure. A cycle in
 * the combined graph throws [CyclicTaskDependencyException].
 */
class TaskGraphImpl(
    override val tasks: List<Task>,
    deps: Map<TaskName, List<TaskName>> = emptyMap(),
    extMustRunAfter: Map<TaskName, List<TaskName>> = emptyMap(),
    extMustRunBefore: Map<TaskName, List<TaskName>> = emptyMap(),
) : TaskGraph {
    private val byName = tasks.associateBy { it.name }

    /** Hard dependencies per task (external + declared + inferred), restricted to tasks in this graph. */
    private val hardDeps: Map<TaskName, Set<TaskName>> = run {
        // Explicit (author-declared) deps are authoritative; inference must not reverse them (see inferOutputDeps).
        val explicit = tasks.associate { t ->
            t.name to (deps[t.name].orEmpty() + t.dependsOn).filterTo(LinkedHashSet()) { it != t.name && it in byName }
        }
        val inferred = inferOutputDeps(explicit)
        // Start from the explicit graph, then fold in inferred edges one at a time, DROPPING any that would
        // close a cycle. An inferred edge (t depends on producer) is only a scheduling convenience — the real
        // data dependency is always also declared explicitly — so refusing the few that conflict can never
        // break correctness, but it guarantees no path-overlap coincidence can forge a cycle (issue #993:
        // two cross-module `classes`/`jar` reverse edges that the one-hop guard alone cannot catch).
        val acc = tasks.associateTo(HashMap()) { it.name to LinkedHashSet(explicit.getValue(it.name)) }
        // True if `target` is reachable from `from` by following current dependency edges in [acc].
        fun reaches(from: TaskName, target: TaskName): Boolean {
            val seen = HashSet<TaskName>()
            val stack = ArrayDeque<TaskName>().apply { add(from) }
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n == target) return true
                if (!seen.add(n)) continue
                acc[n]?.let { stack.addAll(it) }
            }
            return false
        }
        for (t in tasks) for (producer in inferred[t.name].orEmpty()) {
            if (producer == t.name || producer !in byName || producer in acc.getValue(t.name)) continue
            // Adding `t -> producer` closes a cycle iff `t` is already reachable from `producer`; if so, drop it.
            if (!reaches(producer, t.name)) acc.getValue(t.name).add(producer)
        }
        acc
    }

    /** Predecessors per task = hard deps + ordering-only edges (mustRunAfter / mustRunBefore, declared on
     *  the task itself or supplied externally by the configuration phase), inverted as needed. */
    private val predecessors: Map<TaskName, Set<TaskName>> = run {
        val preds = tasks.associateTo(HashMap()) { it.name to LinkedHashSet(hardDeps.getValue(it.name)) }
        fun after(name: TaskName, on: Iterable<TaskName>) = on.filter { it != name && it in byName }.forEach { preds.getValue(name).add(it) }
        fun before(name: TaskName, them: Iterable<TaskName>) = them.filter { it != name && it in byName }.forEach { preds.getValue(it).add(name) }
        for (t in tasks) {
            after(t.name, t.mustRunAfter + extMustRunAfter[t.name].orEmpty())
            before(t.name, t.mustRunBefore + extMustRunBefore[t.name].orEmpty())
        }
        preds
    }

    override fun dependencies(t: Task): List<Task> = hardDeps[t.name].orEmpty().mapNotNull { byName[it] }

    override fun topologicalLevels(): List<List<Task>> {
        val indeg = HashMap<TaskName, Int>().apply { tasks.forEach { put(it.name, predecessors.getValue(it.name).size) } }
        val dependents = HashMap<TaskName, MutableList<TaskName>>()
        for (t in tasks) for (p in predecessors.getValue(t.name)) dependents.getOrPut(p) { ArrayList() }.add(t.name)

        val levels = ArrayList<List<Task>>()
        val placed = HashSet<TaskName>()
        var frontier = tasks.filter { indeg.getValue(it.name) == 0 }
        while (frontier.isNotEmpty()) {
            levels.add(frontier)
            frontier.forEach { placed.add(it.name) }
            val next = ArrayList<Task>()
            for (t in frontier) for (dep in dependents[t.name].orEmpty()) {
                indeg[dep] = indeg.getValue(dep) - 1
                if (indeg.getValue(dep) == 0) next.add(byName.getValue(dep))
            }
            frontier = next
        }
        if (placed.size < tasks.size) throw CyclicTaskDependencyException(findCycle(placed))
        return levels
    }

    /**
     * Infer A→B (B depends on A) when B reads a path A declares as an output (exact or dir containment).
     *
     * Never infer B→A when A **already explicitly depends on B** ([explicit]): the author's declared order
     * (A runs after B) wins, and reversing it would forge a cycle. This guards the `jar → classes` pattern —
     * `jar` explicitly depends on the `classes` lifecycle, yet `classes` tracks the very dirs `jar` reads and
     * writes its artifact near them; without this guard a path-containment coincidence makes `classes` look
     * dependent on `jar`, producing `classes -> jar -> classes`.
     */
    private fun inferOutputDeps(explicit: Map<TaskName, Set<TaskName>>): Map<TaskName, Set<TaskName>> {
        val producers = tasks.flatMap { t -> t.outputs.declaredPaths().map { Paths.get(it).normalize() to t.name } }
        if (producers.isEmpty()) return emptyMap()
        return tasks.associate { t ->
            val ins = t.inputs.declaredPaths().map { Paths.get(it).normalize() }
            t.name to producers.filterTo(LinkedHashSet()) { (out, producer) ->
                producer != t.name &&
                    t.name !in explicit[producer].orEmpty() &&   // don't reverse an explicit dependency
                    ins.any { it == out || it.startsWith(out) || out.startsWith(it) }
            }.map { it.second }.toSet()
        }
    }

    /** Recover one offending cycle (a DFS back-edge) from the predecessor graph, for a useful error. */
    private fun findCycle(placed: Set<TaskName>): List<TaskName> {
        val color = HashMap<TaskName, Int>() // 0 unseen, 1 on-stack, 2 done
        val stack = ArrayList<TaskName>()
        fun dfs(n: TaskName): List<TaskName>? {
            color[n] = 1; stack.add(n)
            for (p in predecessors.getValue(n)) when (color[p] ?: 0) {
                1 -> return stack.subList(stack.indexOf(p), stack.size) + p
                0 -> dfs(p)?.let { return it }
            }
            color[n] = 2; stack.removeAt(stack.size - 1); return null
        }
        for (t in tasks) if (t.name !in placed && (color[t.name] ?: 0) == 0) dfs(t.name)?.let { return it }
        return tasks.filter { it.name !in placed }.map { it.name } // fallback (shouldn't happen)
    }
}

private fun depName(x: Any): TaskName = when (x) {
    is TaskName -> x
    is TaskProvider -> x.name
    is String -> TaskName(x)
    else -> error("a task dependency must be a TaskName, TaskProvider, or String, got: $x")
}

/**
 * The realizable [TaskContainer]: collects lazy registrations + deferred configuration, then [build]s a
 * [TaskGraphImpl]. Factories run once, at [build]; the relationship actions ([register]/[named]/
 * [configureEach]) accumulate by name, so a plugin can depend on a task another plugin registers later.
 */
class DefaultTaskContainer : TaskContainer {
    private val factories = LinkedHashMap<TaskName, () -> Task>()
    private val configs = LinkedHashMap<TaskName, MutableList<TaskSpec.() -> Unit>>()
    private val eachConfigs = ArrayList<TaskSpec.() -> Unit>()

    override fun register(name: TaskName, create: () -> Task): TaskProvider {
        require(factories.put(name, create) == null) { "task already registered: ${name.value}" }
        return Provider(name)
    }

    override fun named(name: TaskName): TaskProvider = Provider(name)

    override fun configureEach(action: TaskSpec.() -> Unit) { eachConfigs += action }

    override fun build(): TaskGraph {
        val tasks = factories.values.map { it() } // realize: run every factory once
        val hard = HashMap<TaskName, MutableList<TaskName>>()
        val after = HashMap<TaskName, MutableList<TaskName>>()
        val before = HashMap<TaskName, MutableList<TaskName>>()
        for (name in factories.keys) {
            val spec = Spec(name, hard, after, before)
            eachConfigs.forEach { spec.it() }     // applies to every task (registered before or after the call)
            configs[name]?.forEach { spec.it() }  // this task's own configuration
        }
        return TaskGraphImpl(tasks, hard, after, before)
    }

    private inner class Provider(override val name: TaskName) : TaskProvider {
        override fun configure(action: TaskSpec.() -> Unit): TaskProvider {
            configs.getOrPut(name) { ArrayList() } += action
            return this
        }
    }

    private class Spec(
        override val name: TaskName,
        private val hard: MutableMap<TaskName, MutableList<TaskName>>,
        private val after: MutableMap<TaskName, MutableList<TaskName>>,
        private val before: MutableMap<TaskName, MutableList<TaskName>>,
    ) : TaskSpec {
        override fun dependsOn(vararg tasks: Any) { hard.getOrPut(name) { ArrayList() } += tasks.map(::depName) }
        override fun mustRunAfter(vararg tasks: Any) { after.getOrPut(name) { ArrayList() } += tasks.map(::depName) }
        override fun mustRunBefore(vararg tasks: Any) { before.getOrPut(name) { ArrayList() } += tasks.map(::depName) }
    }
}

/** Per-task persisted fingerprints (input + output) under a cache dir — the up-to-date oracle. */
class BuildCache(private val root: Path) {
    data class Record(val inputFp: String, val outputFp: String)

    fun get(name: TaskName): Record? {
        val f = fileFor(name)
        if (!Files.isRegularFile(f)) return null
        val lines = runCatching { Files.readAllLines(f) }.getOrNull() ?: return null
        return if (lines.size >= 2) Record(lines[0], lines[1]) else null
    }

    fun put(name: TaskName, inputFp: String, outputFp: String) {
        runCatching { Files.createDirectories(root); Files.write(fileFor(name), listOf(inputFp, outputFp)) }
    }

    private fun fileFor(name: TaskName) = root.resolve(sanitize(name.value) + ".fp")
    private fun sanitize(s: String) = s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}

/** Per-task lifecycle event, streamed so a UI (build console) can show live step status. */
enum class TaskStatus { Running, UpToDate, NoSource, Succeeded, Failed, Blocked }

/**
 * Runs a [TaskGraph] level by level: each task is skipped (UpToDate) when its live input+output
 * fingerprints match the cache, else executed; a task whose dependency failed is skipped as blocked.
 * [AlwaysRun] tasks (e.g. `run`/JavaExec) bypass the cache and always execute. Tasks within a level run
 * concurrently up to [maxParallel]; cancellation is cooperative. [onEvent] streams per-task status.
 */
class TaskExecutorImpl(
    private val cache: BuildCache,
    private val onEvent: (TaskName, TaskStatus) -> Unit = { _, _ -> },
) : TaskExecutor {

    override suspend fun execute(graph: TaskGraph, ctx: TaskContext, maxParallel: Int): BuildOutcome = coroutineScope {
        val ran = Collections.synchronizedList(ArrayList<TaskName>())
        val skipped = Collections.synchronizedList(ArrayList<TaskName>())
        val failed = Collections.synchronizedSet(HashSet<TaskName>())
        val sem = Semaphore(maxParallel.coerceAtLeast(1))

        for (level in graph.topologicalLevels()) {
            level.map { t ->
                async {
                    ctx.checkCanceled()
                    if (graph.dependencies(t).any { it.name in failed }) {
                        failed.add(t.name); onEvent(t.name, TaskStatus.Blocked); return@async
                    }
                    sem.withPermit { runTask(t, ctx, ran, skipped, failed) }
                }
            }.awaitAll()
        }
        BuildOutcome(succeeded = failed.isEmpty(), ranTasks = ran.toList(), skippedTasks = skipped.toList())
    }

    private suspend fun runTask(
        t: Task, ctx: TaskContext,
        ran: MutableList<TaskName>, skipped: MutableList<TaskName>, failed: MutableSet<TaskName>,
    ) {
        // No declared inputs ⇒ nothing to act on (Gradle's NO-SOURCE): skip without running or caching.
        if (t !is AlwaysRun && t.inputs.isEmpty()) {
            skipped.add(t.name); onEvent(t.name, TaskStatus.NoSource); return
        }
        val inFp = t.inputs.fingerprint().value
        if (t !is AlwaysRun) {
            val cached = cache.get(t.name)
            if (cached != null && cached.inputFp == inFp && cached.outputFp == t.outputs.fingerprint().value) {
                skipped.add(t.name); onEvent(t.name, TaskStatus.UpToDate); return
            }
        }
        onEvent(t.name, TaskStatus.Running)
        when (val r = t.execute(ctx)) {
            is TaskResult.Failed -> {
                failed.add(t.name); ctx.logger()("FAILED ${t.name.value}: ${r.message}"); onEvent(t.name, TaskStatus.Failed)
            }
            TaskResult.UpToDate -> {
                skipped.add(t.name); if (t !is AlwaysRun) cache.put(t.name, inFp, t.outputs.fingerprint().value)
                onEvent(t.name, TaskStatus.UpToDate)
            }
            TaskResult.Success -> {
                ran.add(t.name); if (t !is AlwaysRun) cache.put(t.name, inFp, t.outputs.fingerprint().value)
                onEvent(t.name, TaskStatus.Succeeded)
            }
        }
    }
}

/** A no-frills [TaskContext]: a sink logger + a progress reporter, optionally cancellable via [canceled]. */
class SimpleTaskContext(
    private val log: (String) -> Unit = {},
    @Volatile var canceled: Boolean = false,
) : TaskContext {
    override val progress: ProgressReporter = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) { if (message != null) log(message) }
        override fun checkCanceled() { if (canceled) throw kotlinx.coroutines.CancellationException("canceled") }
        override val isCanceled: Boolean get() = canceled
    }
    override fun checkCanceled() { if (canceled) throw kotlinx.coroutines.CancellationException("canceled") }
    override fun logger(): (String) -> Unit = log
}
