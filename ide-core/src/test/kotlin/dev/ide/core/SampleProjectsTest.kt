package dev.ide.core

import dev.ide.core.services.RunCapture
import dev.ide.model.LanguageLevel
import dev.ide.model.template.TemplateArgs
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the bundled **sample projects** strictly work — including their interactive input handling. Each one
 * is created from its template exactly as the store's "Create project" flow does, then compiled and run with a
 * scripted stdin session, and its output is checked. If a sample doesn't build, run, or handle input, this
 * fails the build — so a broken example can never ship.
 */
class SampleProjectsTest {

    /** Create the sample from its template, then compile + run it feeding [input] to its stdin. */
    private fun createAndRun(templateId: String, input: String): RunCapture {
        val dir = Files.createTempDirectory("sample-$templateId")
        try {
            IdeServices.createProjectAt(
                dir, templateId, mapOf(TemplateArgs.NAME to templateId),
                IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
            ).use { ide -> return runBlocking { ide.runAndCapture("app", stdin = input) } }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun calculatorEvaluatesTypedExpressions() {
        val cap = createAndRun("sample-calculator", "2 + 3 * 4\n(2 + 3) * 4\nquit\n")
        assertTrue(cap.compiled, "Calculator should compile + run; diagnostics=${cap.diagnostics}, stdout=${cap.stdout}")
        assertEquals(0, cap.exitCode, "Calculator should exit 0; stdout=${cap.stdout}")
        assertTrue("2 + 3 * 4 = 14.0" in cap.stdout, "precedence result missing; stdout=${cap.stdout}")
        assertTrue("(2 + 3) * 4 = 20.0" in cap.stdout, "parentheses result missing; stdout=${cap.stdout}")
    }

    @Test
    fun notesRunsTypedCommands() {
        val cap = createAndRun("sample-notes", "add Buy milk\nadd Learn Kotlin\ndone 2\nlist\nquit\n")
        assertTrue(cap.compiled, "Notes should compile + run; diagnostics=${cap.diagnostics}, stdout=${cap.stdout}")
        assertEquals(0, cap.exitCode, "Notes should exit 0; stdout=${cap.stdout}")
        assertTrue("Added #2: Learn Kotlin" in cap.stdout, "add command output missing; stdout=${cap.stdout}")
        assertTrue("[x] 2. Learn Kotlin" in cap.stdout, "completed note missing from list; stdout=${cap.stdout}")
    }

    @Test
    fun weatherLooksUpTypedCity() {
        val cap = createAndRun("sample-weather", "London\nquit\n")
        assertTrue(cap.compiled, "Weather should compile + run; diagnostics=${cap.diagnostics}, stdout=${cap.stdout}")
        assertEquals(0, cap.exitCode, "Weather should exit 0; stdout=${cap.stdout}")
        assertTrue("3-day forecast for London" in cap.stdout, "city header missing; stdout=${cap.stdout}")
        assertTrue("Mon: Cloudy, 11-18" in cap.stdout, "forecast line missing; stdout=${cap.stdout}")
    }
}
