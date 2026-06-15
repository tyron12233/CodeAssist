package dev.ide.build.engine

import dev.ide.build.CyclicTaskDependencyException
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskGraph
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The richer task-relationship API: inferred output→input deps, declared dependsOn, mustRunAfter/Before
 *  ordering, cycle detection, and NO-SOURCE skipping. */
class EngineDependencyTest {

    /** A configurable task: declares input/output paths + relations, records whether it executed, and
     *  writes each output as the concatenation of its inputs (so edits propagate down the chain). */
    private class TestTask(
        override val name: TaskName,
        private val inPaths: List<Path> = emptyList(),
        private val outPaths: List<Path> = emptyList(),
        override val dependsOn: List<TaskName> = emptyList(),
        override val mustRunAfter: List<TaskName> = emptyList(),
        override val mustRunBefore: List<TaskName> = emptyList(),
        private val noInputs: Boolean = false,
    ) : Task {
        @Volatile var executed = false
        override val inputs: TaskInputs get() = TaskInputsImpl().apply {
            if (!noInputs) { filePaths("in", inPaths); property("id", name.value) }
        }
        override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { outPaths.forEachIndexed { i, p -> filePath("o$i", p) } }
        override suspend fun execute(ctx: TaskContext): TaskResult {
            executed = true
            val content = if (inPaths.isEmpty()) name.value else inPaths.joinToString { runCatching { Files.readString(it) }.getOrDefault("") }
            outPaths.forEach { Files.createDirectories(it.parent); Files.writeString(it, content) }
            return TaskResult.Success
        }
    }

    private fun levelOf(graph: TaskGraph, name: String): Int =
        graph.topologicalLevels().indexOfFirst { lvl -> lvl.any { it.name.value == name } }

    @Test
    fun readingAnotherTasksOutputInfersTheDependency() {
        val dir = Files.createTempDirectory("dep")
        try {
            val a = dir.resolve("a").also { Files.writeString(it, "1") }
            val x = dir.resolve("x")
            val y = dir.resolve("y")
            val taskA = TestTask(TaskName("A"), inPaths = listOf(a), outPaths = listOf(x))
            val taskB = TestTask(TaskName("B"), inPaths = listOf(x), outPaths = listOf(y)) // reads A's output → implicit dep
            val graph = TaskGraphImpl(listOf(taskB, taskA)) // declaration order shouldn't matter, no explicit deps
            assertEquals(listOf("A"), graph.dependencies(taskB).map { it.name.value }, "B reads A's output ⇒ depends on A")
            assertTrue(levelOf(graph, "A") < levelOf(graph, "B"), "A must be scheduled before B")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun inferredDependencyIsIncremental() = runBlocking {
        val dir = Files.createTempDirectory("dep-inc")
        try {
            val a = dir.resolve("a").also { Files.writeString(it, "1") }
            val x = dir.resolve("x"); val y = dir.resolve("y")
            val exec = TaskExecutorImpl(BuildCache(dir.resolve("cache")))
            val ctx = SimpleTaskContext()
            fun graph() = TaskGraphImpl(listOf(
                TestTask(TaskName("A"), inPaths = listOf(a), outPaths = listOf(x)),
                TestTask(TaskName("B"), inPaths = listOf(x), outPaths = listOf(y)),
            ))
            assertEquals(setOf("A", "B"), exec.execute(graph(), ctx).ranTasks.map { it.value }.toSet(), "first build runs both")
            assertTrue(exec.execute(graph(), ctx).ranTasks.isEmpty(), "unchanged re-run does zero work")
            Files.writeString(a, "2")
            assertEquals(setOf("A", "B"), exec.execute(graph(), ctx).ranTasks.map { it.value }.toSet(), "editing A re-runs A then B")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun declaredDependsOnIsAHardDependency() {
        val a = TestTask(TaskName("A"))
        val b = TestTask(TaskName("B"), dependsOn = listOf(TaskName("A")))
        val graph = TaskGraphImpl(listOf(b, a))
        assertEquals(listOf("A"), graph.dependencies(b).map { it.name.value })
        assertTrue(levelOf(graph, "A") < levelOf(graph, "B"))
    }

    @Test
    fun mustRunAfterOrdersWithoutBlocking() {
        val a = TestTask(TaskName("A"))
        val b = TestTask(TaskName("B"), mustRunAfter = listOf(TaskName("A")))
        val graph = TaskGraphImpl(listOf(b, a))
        assertTrue(levelOf(graph, "A") < levelOf(graph, "B"), "mustRunAfter sequences B after A")
        assertTrue(graph.dependencies(b).isEmpty(), "ordering-only: not a hard dependency, so it never blocks on failure")
    }

    @Test
    fun mustRunBeforeOrders() {
        val a = TestTask(TaskName("A"), mustRunBefore = listOf(TaskName("B")))
        val b = TestTask(TaskName("B"))
        val graph = TaskGraphImpl(listOf(b, a))
        assertTrue(levelOf(graph, "A") < levelOf(graph, "B"))
        assertTrue(graph.dependencies(b).isEmpty())
    }

    @Test
    fun cyclicDependencyIsDetected() {
        val a = TestTask(TaskName("A"), dependsOn = listOf(TaskName("B")))
        val b = TestTask(TaskName("B"), dependsOn = listOf(TaskName("A")))
        val graph = TaskGraphImpl(listOf(a, b))
        val ex = assertFailsWith<CyclicTaskDependencyException> { graph.topologicalLevels() }
        assertTrue(ex.cycle.map { it.value }.toSet().containsAll(setOf("A", "B")), "cycle should name both tasks: ${ex.cycle.map { it.value }}")
    }

    @Test
    fun outputInferenceNeverReversesAnExplicitDependency() {
        // The `jar → classes` shape: `jar` explicitly depends on the `classes` lifecycle, `classes` TRACKS
        // the dir `jar` reads, and `jar`'s artifact lands *inside* a tracked dir (a path-containment
        // coincidence). Inference must not then make `classes` depend on `jar` — that forged the on-device
        // `:feature:classes -> :feature:jar -> :feature:classes` cycle.
        val dir = Files.createTempDirectory("infer-rev")
        try {
            val classesDir = dir.resolve("build/classes")
            val jarFile = classesDir.resolve("libs/feature.jar")   // jar's output nested UNDER classes' tracked dir
            val compile = TestTask(TaskName("compile"), outPaths = listOf(classesDir.resolve("Main.class")))
            // `classes` aggregates: it tracks the classes dir as input (up-to-date tracking, not a real read).
            val classes = TestTask(TaskName("classes"), inPaths = listOf(classesDir), dependsOn = listOf(TaskName("compile")))
            val jar = TestTask(TaskName("jar"), inPaths = listOf(classesDir), outPaths = listOf(jarFile),
                dependsOn = listOf(TaskName("classes")))
            val graph = TaskGraphImpl(listOf(jar, classes, compile))
            graph.topologicalLevels()   // must NOT throw CyclicTaskDependencyException
            assertFalse(graph.dependencies(classes).any { it.name.value == "jar" }, "classes must not depend on jar")
            assertTrue(levelOf(graph, "classes") < levelOf(graph, "jar"), "explicit order kept: classes before jar")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun taskWithNoInputsIsSkippedAsNoSource() = runBlocking {
        val dir = Files.createTempDirectory("nosrc")
        try {
            val empty = TestTask(TaskName("E"), noInputs = true)
            val outcome = TaskExecutorImpl(BuildCache(dir.resolve("cache"))).execute(TaskGraphImpl(listOf(empty)), SimpleTaskContext())
            assertFalse(empty.executed, "a task with no declared inputs must not execute (NO-SOURCE)")
            assertTrue(outcome.ranTasks.isEmpty())
            assertEquals(listOf("E"), outcome.skippedTasks.map { it.value })
        } finally { dir.toFile().deleteRecursively() }
    }
}
