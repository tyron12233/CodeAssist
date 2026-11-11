package dev.ide.core.agent

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import dev.ide.core.IdeServices
import dev.ide.core.build.BuildRunner
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.UiTestRun
import dev.ide.ui.backend.UiTestStatus
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `run_tests`: run a module's unit tests and say, test by test, what passed and what failed and why.
 *
 * `run_task` could already start a `test:<module>` task, but it answers with the BUILD's log, and a test run's
 * results are not in it: they are records in the program's own output, which the Run screen's Tests tab reads
 * ([TestOutput]). So the agent was told the task "succeeded" or "failed" and nothing about which test. This reads
 * the same records the Tests tab does.
 */
internal object TestTools {

    fun tools(engine: () -> IdeServices?, runner: (IdeServices) -> BuildRunner): List<AgentTool> = listOf(
        object : AgentTool {
            override val spec = ToolSpec(
                name = "run_tests",
                description = "Compile a module and run its unit tests (JUnit 4, JUnit 5, TestNG, kotlin.test), returning " +
                    "each failed test with its message and the counts of passed, failed and skipped. Runs every test " +
                    "of the module, one class, or one method.",
                parameters = toolSchema {
                    string("module", "The module whose tests to run. Defaults to the first module that has tests.", required = false)
                    string("test_class", "Fully-qualified test class, to run only that class.", required = false)
                    string("method", "A test method of test_class, to run only that test.", required = false)
                },
            )
            // Runs the project's code, like run_program.
            override val mutating = true

            override fun summarize(args: ToolArgs) = "run tests" +
                (args.optString("test_class")?.let { " in ${it.substringAfterLast('.')}" } ?: "") +
                (args.optString("module")?.let { " of $it" } ?: "")

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val svc = engine() ?: return ToolExecutionResult.error("No project is open.")
                val build = runner(svc)
                val testable = build.runTasks().filter { TestOutput.kindOf(it.id) == TestOutput.LOCAL }
                    .mapNotNull { TestOutput.parseTaskId(it.id)?.first }
                val module = args.optString("module")?.takeIf { it.isNotBlank() } ?: testable.firstOrNull()
                    ?: return ToolExecutionResult.error("No module of this project has unit tests (a test source set with tests in it).")
                if (module !in testable) {
                    return ToolExecutionResult.error(
                        "Module \"$module\" has no unit tests to run. Modules with tests: ${testable.joinToString().ifEmpty { "(none)" }}.",
                    )
                }
                val cls = args.optString("test_class")?.takeIf { it.isNotBlank() }
                val method = args.optString("method")?.takeIf { it.isNotBlank() }
                if (method != null && cls == null) return ToolExecutionResult.error("method needs test_class.")
                val targets = if (cls == null) emptyList() else listOf(TestOutput.target(cls, method))
                return run(build, module, TestOutput.taskId(module, targets))
            }
        },
    )

    private suspend fun run(build: BuildRunner, module: String, taskId: String): ToolExecutionResult {
        if (build.buildState.value.status == RunStatus.Running) {
            return ToolExecutionResult.error("A build is already running. Try again once it finishes.")
        }
        val before = build.runConsole.value?.id
        build.runTask(taskId)
        // The build first (dropping the state a previous run left behind), then the run it starts.
        val built = withTimeoutOrNull(TIMEOUT_MS) {
            build.buildState
                .dropWhile { it.status == RunStatus.Idle || it.status == RunStatus.Succeeded || it.status == RunStatus.Failed }
                .first { it.status == RunStatus.Succeeded || it.status == RunStatus.Failed }
        } ?: return ToolExecutionResult.error("The tests did not finish building within ${TIMEOUT_MS / 1000}s.")
        fun ours(c: RunConsoleUi?) = c != null && c.id != before
        val console = withTimeoutOrNull(if (built.status == RunStatus.Failed) NO_RUN_GRACE_MS else TIMEOUT_MS) {
            build.runConsole.first { ours(it) && it?.phase == RunPhase.Finished }
        }
        if (console == null) {
            // Nothing ran: the tests did not compile.
            val diagnostics = built.diagnostics.joinToString("\n") { d ->
                "${d.severity} ${d.message}" + if (d.file != null && d.line >= 0) " (${d.file}:${d.line})" else ""
            }
            val log = built.log.takeLast(LOG_LINES).joinToString("\n") { it.message }
            return ToolExecutionResult.error(
                "The tests of \"$module\" did not run: the build ${if (built.status == RunStatus.Failed) "failed" else "ended without starting them"}.\n" +
                    diagnostics.ifBlank { log },
            )
        }
        val view = TestOutput.split(console.transcript)
        val output = view.transcript.joinToString("") { it.text }.trim()
        val tests = view.tests ?: return ToolExecutionResult.error(
            "The run produced no test results.\n" + output.takeLast(OUTPUT_CHARS),
        )
        val text = report(module, tests, output)
        return if (tests.failed > 0) ToolExecutionResult.error(text) else ToolExecutionResult.ok(text)
    }

    /** Failures first and in full, since they are what the agent acts on; the passes are a count and names. */
    internal fun report(module: String, run: UiTestRun, output: String): String = buildString {
        if (run.noTests || (run.cases.isEmpty() && run.classFailures.isEmpty())) {
            append("No tests matched in \"").append(module).append("\".")
            return@buildString
        }
        append("Tests of \"").append(module).append("\": ")
        append(run.passed).append(" passed, ").append(run.failed).append(" failed, ").append(run.skipped).append(" skipped")
        append(" (").append(run.durationMs).append(" ms)")
        if (!run.finished) append(". The run stopped before it finished")
        append('.')
        run.classFailures.forEach { (cls, message) ->
            append("\n\nFAILED ").append(cls).append(" (the whole class)\n").append(message.trim().prependIndent("  "))
        }
        run.cases.filter { it.status == UiTestStatus.Failed }.forEach { c ->
            append("\n\nFAILED ").append(c.className).append(" > ").append(c.name)
            if (c.message.isNotBlank()) append('\n').append(c.message.trim().take(MESSAGE_CHARS).prependIndent("  "))
        }
        val skipped = run.cases.filter { it.status == UiTestStatus.Skipped }
        if (skipped.isNotEmpty()) append("\n\nSkipped: ").append(skipped.joinToString { "${it.className.substringAfterLast('.')}.${it.name}" })
        val passed = run.cases.filter { it.status == UiTestStatus.Passed }
        if (passed.isNotEmpty() && passed.size <= LISTED_PASSES) {
            append("\n\nPassed: ").append(passed.joinToString { "${it.className.substringAfterLast('.')}.${it.name}" })
        }
        if (output.isNotBlank()) append("\n\nOutput:\n").append(output.takeLast(OUTPUT_CHARS))
    }

    private const val TIMEOUT_MS = 600_000L
    /** After a failed build, how long to allow for a run to appear anyway before calling it a compile failure. */
    private const val NO_RUN_GRACE_MS = 2_000L
    private const val LOG_LINES = 60
    private const val OUTPUT_CHARS = 4_000
    private const val MESSAGE_CHARS = 2_000
    private const val LISTED_PASSES = 40
}
