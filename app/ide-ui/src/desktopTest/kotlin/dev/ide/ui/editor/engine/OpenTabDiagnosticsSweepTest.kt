package dev.ide.ui.editor.engine

import dev.ide.ui.OpenFile
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The background half of editor analysis: every open tab is analyzed once per generation, so a tab's
 * diagnostics (and the status dot over them) do not wait for the user to click on it.
 */
class OpenTabDiagnosticsSweepTest {

    /** The focused tab is the daemon's; the others are this sweep's. */
    @Test
    fun analyzesEveryTabExceptTheFocusedOne() = runTest {
        val backend = RecordingBackend()
        val tabs = listOf(tab("/p/A.kt"), tab("/p/B.kt"), tab("/p/C.kt"))

        val analyzed = OpenTabDiagnosticsSweep(backend).sweep(tabs, activePath = "/p/A.kt", generation = 1)

        assertEquals(listOf("/p/B.kt", "/p/C.kt"), analyzed)
        assertEquals(listOf("/p/B.kt", "/p/C.kt"), backend.analyzed)
    }

    /** The results land on the tab's own session, which is what the strip and the editor read. */
    @Test
    fun appliesTheResultToTheTabsSession() = runTest {
        val backend = RecordingBackend()
        val tab = tab("/p/B.kt")

        OpenTabDiagnosticsSweep(backend).sweep(listOf(tab), activePath = "/p/A.kt", generation = 1)

        assertEquals(1, tab.session.diagnostics.size)
        assertEquals(UiSeverity.Error, tab.session.diagnostics.single().severity)
    }

    /** A read-only (library) tab has no file to analyze, and asks the engine for nothing. */
    @Test
    fun skipsReadOnlyTabs() = runTest {
        val backend = RecordingBackend()
        val tabs = listOf(OpenFile("library://x/Foo.class", "Foo.class", "class Foo", readOnly = true))

        val analyzed = OpenTabDiagnosticsSweep(backend).sweep(tabs, activePath = null, generation = 1)

        assertTrue(analyzed.isEmpty())
        assertTrue(backend.analyzed.isEmpty())
    }

    /** A tab is analyzed once per generation, however often the sweep restarts (a tab opening or closing,
     *  the focused tab changing), and again when a new generation says the answer may have changed. */
    @Test
    fun analyzesOncePerGenerationAndAgainOnTheNext() = runTest {
        val backend = RecordingBackend()
        val sweep = OpenTabDiagnosticsSweep(backend)
        val tabs = listOf(tab("/p/B.kt"))

        sweep.sweep(tabs, activePath = null, generation = 1)
        val repeat = sweep.sweep(tabs, activePath = null, generation = 1)
        val nextGeneration = sweep.sweep(tabs, activePath = null, generation = 2)

        assertTrue(repeat.isEmpty(), "the same generation does not re-analyze a tab")
        assertEquals(listOf("/p/B.kt"), nextGeneration, "a new generation re-analyzes every open tab")
        assertEquals(listOf("/p/B.kt", "/p/B.kt"), backend.analyzed)
    }

    /** A failing pass (a preemption, an unsupported language) leaves the tab unstamped, so the next sweep
     *  retries it rather than the tab staying blank until the next build. */
    @Test
    fun retriesATabWhosePassFailed() = runTest {
        val backend = RecordingBackend(failFirst = true)
        val sweep = OpenTabDiagnosticsSweep(backend)
        val tabs = listOf(tab("/p/B.kt"))

        val first = sweep.sweep(tabs, activePath = null, generation = 1)
        val second = sweep.sweep(tabs, activePath = null, generation = 1)

        assertTrue(first.isEmpty())
        assertEquals(listOf("/p/B.kt"), second)
    }

    private fun tab(path: String): OpenFile = OpenFile(path, path.substringAfterLast('/'), "class A")

    /** Records what it was asked to analyze, and answers with one error per file. */
    private class RecordingBackend(private val failFirst: Boolean = false) : StubBackend() {
        val analyzed = ArrayList<String>()
        private var calls = 0

        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> {
            if (failFirst && calls++ == 0) throw IllegalStateException("preempted")
            analyzed += path
            return listOf(UiDiagnostic(UiSeverity.Error, 0, 0, "boom", 0, 1))
        }
    }
}
