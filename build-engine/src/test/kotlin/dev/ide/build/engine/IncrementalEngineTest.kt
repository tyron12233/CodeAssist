package dev.ide.build.engine

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Incremental execution: re-running an unchanged graph does zero work, and changing one
 * input re-runs only the affected subgraph. The chain A → B → C (each consumes the prior's output)
 * plus an independent D proves both: editing A's source re-runs A,B,C but leaves D up-to-date.
 */
class IncrementalEngineTest {

    /** Copies its input file to its output file, declaring both for fingerprinting. */
    private class CopyTask(override val name: TaskName, private val input: Path, private val output: Path) : Task {
        override val inputs: TaskInputs get() = TaskInputsImpl().apply { filePaths("in", listOf(input)) }
        override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("out", output) }
        override suspend fun execute(ctx: TaskContext): TaskResult {
            Files.createDirectories(output.parent)
            Files.writeString(output, Files.readString(input))
            return TaskResult.Success
        }
    }

    @Test
    fun reRunDoesZeroWorkAndOneChangeReRunsOnlyTheAffectedSubgraph() = runBlocking {
        val dir = Files.createTempDirectory("engine")
        try {
            val a = dir.resolve("a.txt").also { Files.writeString(it, "1") }
            val d = dir.resolve("d.txt").also { Files.writeString(it, "x") }
            val out = dir.resolve("out")
            val exec = TaskExecutorImpl(BuildCache(dir.resolve("cache")))
            val ctx = SimpleTaskContext()

            fun graph() = TaskGraphImpl(
                listOf(
                    CopyTask(TaskName("A"), a, out.resolve("A")),
                    CopyTask(TaskName("B"), out.resolve("A"), out.resolve("B")),
                    CopyTask(TaskName("C"), out.resolve("B"), out.resolve("C")),
                    CopyTask(TaskName("D"), d, out.resolve("D")),
                ),
                mapOf(
                    TaskName("B") to listOf(TaskName("A")),
                    TaskName("C") to listOf(TaskName("B")),
                ),
            )

            val first = exec.execute(graph(), ctx, maxParallel = 2)
            assertTrue(first.succeeded)
            assertEquals(setOf("A", "B", "C", "D"), first.ranTasks.map { it.value }.toSet(), "first build runs everything")

            val unchanged = exec.execute(graph(), ctx, maxParallel = 2)
            assertTrue(unchanged.ranTasks.isEmpty(), "re-run must do zero work, ran=${unchanged.ranTasks.map { it.value }}")
            assertEquals(setOf("A", "B", "C", "D"), unchanged.skippedTasks.map { it.value }.toSet())

            Files.writeString(a, "2") // edit A's source only
            val afterEdit = exec.execute(graph(), ctx, maxParallel = 2)
            assertEquals(setOf("A", "B", "C"), afterEdit.ranTasks.map { it.value }.toSet(), "only the affected subgraph re-runs")
            assertEquals(setOf("D"), afterEdit.skippedTasks.map { it.value }.toSet(), "the independent task stays up-to-date")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
