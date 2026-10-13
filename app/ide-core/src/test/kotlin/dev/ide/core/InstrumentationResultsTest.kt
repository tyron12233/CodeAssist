package dev.ide.core

import dev.ide.core.services.InstrumentationResults
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.UiTestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `am instrument -r` output read into the Run screen's test results ([InstrumentationResults] -> [TestOutput]). */
class InstrumentationResultsTest {

    private fun status(cls: String, test: String, code: Int, vararg extra: String) = buildString {
        appendLine("INSTRUMENTATION_STATUS: class=$cls")
        appendLine("INSTRUMENTATION_STATUS: current=1")
        appendLine("INSTRUMENTATION_STATUS: id=AndroidJUnitRunner")
        appendLine("INSTRUMENTATION_STATUS: numtests=4")
        extra.forEach { appendLine(it) }
        appendLine("INSTRUMENTATION_STATUS: stream=")
        appendLine("INSTRUMENTATION_STATUS: test=$test")
        appendLine("INSTRUMENTATION_STATUS_CODE: $code")
    }

    private fun read(output: String): Pair<InstrumentationResults, StringBuilder> {
        val out = StringBuilder()
        var now = 1000L
        val results = InstrumentationResults({ out.append(it) }, clock = { now += 7; now })
        output.lineSequence().forEach(results::line)
        return results to out
    }

    @Test
    fun readsPassesFailuresAndSkips() {
        val cls = "com.example.app.ExampleInstrumentedTest"
        val output = status(cls, "useAppContext", 1) + status(cls, "useAppContext", 0) +
            status(cls, "fails", 1) +
            status(cls, "fails", -2, "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: expected:<a> but was:<b>", "\tat org.junit.Assert.fail(Assert.java:89)", "\tat com.example.app.ExampleInstrumentedTest.fails(ExampleInstrumentedTest.kt:30)") +
            status(cls, "ignored", -3) +
            """
            INSTRUMENTATION_RESULT: stream=

            Time: 0.123
            There was 1 failure:

            INSTRUMENTATION_CODE: -1
            """.trimIndent()
        val (results, out) = read(output)
        val problem = results.finish()
        assertNull(problem)
        assertEquals(1, results.failed)

        val tests = assertNotNull(TestOutput.split(listOf(ConsoleChunk(out.toString(), ConsoleChunkKind.OUTPUT))).tests)
        val byName = tests.cases.associateBy { it.name }
        assertEquals(UiTestStatus.Passed, byName.getValue("useAppContext").status)
        assertTrue(byName.getValue("useAppContext").durationMs > 0)
        val fails = byName.getValue("fails")
        assertEquals(UiTestStatus.Failed, fails.status)
        assertTrue(fails.message.startsWith("java.lang.AssertionError: expected:<a> but was:<b>"), fails.message)
        assertTrue("ExampleInstrumentedTest.kt:30" in fails.message, "the stack continues over lines: ${fails.message}")
        assertEquals(UiTestStatus.Skipped, byName.getValue("ignored").status)
        assertTrue(tests.finished)
    }

    @Test
    fun aCrashFailsTheTestThatWasRunning() {
        val cls = "com.example.app.CrashTest"
        val output = status(cls, "crashes", 1) + """
            INSTRUMENTATION_RESULT: shortMsg=Process crashed.
            INSTRUMENTATION_CODE: 0
        """.trimIndent()
        val (results, out) = read(output)
        assertEquals("Process crashed.", results.finish())
        val case = assertNotNull(TestOutput.split(listOf(ConsoleChunk(out.toString(), ConsoleChunkKind.OUTPUT))).tests).cases.single()
        assertEquals(UiTestStatus.Failed, case.status)
        assertEquals("Process crashed.", case.message)
    }

    @Test
    fun aMissingRunnerIsSaid() {
        val (results, out) = read("INSTRUMENTATION_FAILED: com.example.app.test/androidx.test.runner.AndroidJUnitRunner")
        assertEquals("com.example.app.test/androidx.test.runner.AndroidJUnitRunner", results.finish())
        assertEquals(0, results.finished)
        assertTrue(assertNotNull(TestOutput.split(listOf(ConsoleChunk(out.toString(), ConsoleChunkKind.OUTPUT))).tests).finished)
    }
}
