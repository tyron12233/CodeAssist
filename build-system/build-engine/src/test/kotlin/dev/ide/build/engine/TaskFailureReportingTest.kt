package dev.ide.build.engine

import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.testkit.withTempDir
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the console says when a build step fails.
 *
 * A task that returns `Failed` has already reported its problems; the engine adds the banner naming it. A
 * task that *throws* is a defect in the build machinery, and the user gets one plain line saying what went
 * wrong — the trace is filed on the DEBUG channel for a bug report, never shown as the failure.
 */
class TaskFailureReportingTest {

    private class Fixed(
        override val name: TaskName,
        private val dir: Path,
        override val dependsOn: List<TaskName> = emptyList(),
        val body: suspend () -> TaskResult = { TaskResult.Success },
    ) : Task {
        private val slug = name.value.replace(':', '_')
        override val inputs: TaskInputs
            get() = TaskInputsImpl().apply {
                filePaths("in", listOf(dir.resolve("$slug.in")))
                property("task", name.value)
            }
        override val outputs: TaskOutputs
            get() = TaskOutputsImpl().apply { filePath("out", dir.resolve("$slug.out")) }
        override suspend fun execute(ctx: TaskContext): TaskResult = body()
    }

    private fun run(make: (Path) -> Task): List<BuildLogEntry> = withTempDir("task-failure") { tmp ->
        val entries = ArrayList<BuildLogEntry>()
        val ctx = SimpleTaskContext(onLog = { entries.add(it) })
        runBlocking {
            TaskExecutorImpl(BuildCache(tmp.resolve("cache")))
                .execute(TaskGraphImpl(listOf(make(tmp))), ctx, maxParallel = 1)
        }
        entries
    }

    @Test fun aThrownTaskReportsItsCauseAndNotItsStackTrace() {
        val log = run { dir -> Fixed(TaskName(":app:dexBuilder"), dir) { error("Java heap space") } }

        val shown = log.filter { it.level != BuildLogLevel.DEBUG }
        assertTrue(
            shown.any { "Java heap space" in it.message },
            "the user is told what went wrong: $shown",
        )
        assertFalse(
            shown.any { it.message.trimStart().startsWith("at ") },
            "a stack frame is never user-facing output: $shown",
        )
        assertTrue(
            shown.any { it.message == "> Task :app:dexBuilder FAILED" && it.level == BuildLogLevel.ERROR },
            "the failing step is named: $shown",
        )
    }

    @Test fun theTraceIsFiledOnTheDebugChannelForABugReport() {
        val log = run { dir -> Fixed(TaskName(":app:dexBuilder"), dir) { error("Java heap space") } }

        val debug = log.filter { it.level == BuildLogLevel.DEBUG }
        assertTrue(
            debug.any { it.message.trimStart().startsWith("at ") },
            "Verbose and a copied report still carry the frames: $debug",
        )
    }

    @Test fun aReportedFailureIsNamedOnceWithItsSummary() {
        val log = run { dir ->
            Fixed(TaskName(":app:compileKotlin"), dir) {
                TaskResult.Failed("Kotlin compilation failed with 2 errors")
            }
        }

        val errors = log.filter { it.level == BuildLogLevel.ERROR }.map { it.message }
        assertEquals(
            listOf("> Task :app:compileKotlin FAILED", "Kotlin compilation failed with 2 errors"),
            errors,
        )
    }

    @Test fun aTaskThatRunsAnnouncesItselfOnce() {
        val log = run { dir -> Fixed(TaskName(":app:jar"), dir) }

        assertEquals(
            listOf("> Task :app:jar"),
            log.filter { it.level == BuildLogLevel.INFO }.map { it.message },
        )
    }

    @Test fun aTaskBlockedByAFailedDependencyReadsAsSkipped() = withTempDir("blocked") { tmp ->
        val failing = Fixed(TaskName(":app:compileKotlin"), tmp) { TaskResult.Failed("nope") }
        val blocked = Fixed(TaskName(":app:dexBuilder"), tmp, dependsOn = listOf(failing.name))
        val entries = ArrayList<BuildLogEntry>()
        runBlocking {
            TaskExecutorImpl(BuildCache(tmp.resolve("cache"))).execute(
                TaskGraphImpl(listOf(failing, blocked)),
                SimpleTaskContext(onLog = { entries.add(it) }),
                maxParallel = 1,
            )
        }

        assertTrue(
            entries.any { it.message == "> Task :app:dexBuilder SKIPPED" },
            "a step that never ran says so, rather than vanishing: $entries",
        )
    }
}
