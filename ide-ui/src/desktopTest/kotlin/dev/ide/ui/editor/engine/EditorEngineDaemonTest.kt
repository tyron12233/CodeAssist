package dev.ide.ui.editor.engine

import dev.ide.ui.backend.AnalysisPreempted
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiSemanticToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless tests for [EditorEngineDaemon] — the editor's highlighting daemon driven WITHOUT Compose, on a
 * virtual clock. A [FakeEngine] backend records every pass call (and can preempt a chosen one on demand), so
 * a test can type, let the reparse delay elapse, and assert exactly how the passes ran — the "run the editor
 * headless and see how the engine work interleaves" harness.
 */
class EditorEngineDaemonTest {

    /** A backend that records pass calls in order and simulates per-pass engine latency; can preempt a pass. */
    private class FakeEngine(
        private val latencyMs: Long = 10,
    ) : IdeBackend {
        val calls = CopyOnWriteArrayList<String>()
        /** Pass names to throw [AnalysisPreempted] from ONCE (consumed on use), modelling a completion cut-in. */
        val preemptOnce = mutableSetOf<String>()

        private suspend fun pass(name: String) {
            calls += name
            if (preemptOnce.remove(name)) throw AnalysisPreempted()
            delay(latencyMs)
        }

        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> { pass("DIAGNOSTICS"); return emptyList() }
        override suspend fun semanticTokens(path: String, text: String): List<UiSemanticToken> { pass("SEMANTIC"); return emptyList() }
        override suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> { pass("INLAY"); return emptyList() }
        override suspend fun codeFolds(path: String, text: String): List<UiFoldRegion> { pass("FOLDS"); return emptyList() }
        override suspend fun composePreviews(path: String, text: String): List<UiComposePreview> { pass("PREVIEWS"); return emptyList() }

        // --- inert IdeBackend surface ---
        override val project = ProjectInfo("p", "/p", 1)
        override fun fileTree(mode: TreeViewMode) = TreeNode("root", "p", NodeKind.Workspace, null)
        override fun readFile(path: String) = ""
        override fun moduleNameForFile(path: String): String? = null
        override fun updateDocument(path: String, text: String) {}
        override fun saveFile(path: String, text: String) {}
        override suspend fun complete(path: String, text: String, offset: Int) = UiCompletionResult(emptyList(), offset, offset)
        override val indexStatus: StateFlow<IndexUiStatus> = MutableStateFlow(IndexUiStatus())
        override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> = emptyList()
        override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> = emptyList()
        override val buildState: StateFlow<BuildState> = MutableStateFlow(BuildState())
        override fun runBuild() {}
        override fun stopBuild() {}
    }

    private class Recorder : DaemonObserver {
        val events = CopyOnWriteArrayList<Triple<DaemonPhase, DaemonPass?, Int>>()
        override fun on(phase: DaemonPhase, pass: DaemonPass?, revision: Int) { events += Triple(phase, pass, revision) }
        fun passesDone(): List<DaemonPass> = events.filter { it.first == DaemonPhase.PASS_DONE }.mapNotNull { it.second }
        fun render(): String = events.joinToString("\n") { (ph, p, r) -> "  rev$r  ${p ?: "-"}  $ph" }
    }

    private val policy = EditorDaemonPolicy(autoReparseDelay = 300.milliseconds, preemptRetryDelay = 150.milliseconds)

    @Test
    fun runsAllPassesInPriorityOrderAfterTheReparseDelay() = runTest {
        val fake = FakeEngine()
        val rec = Recorder()
        val daemon = EditorEngineDaemon(this, fake, "App.kt", policy, rec)
        daemon.restart("v1")
        advanceUntilIdle()
        assertEquals(
            listOf("DIAGNOSTICS", "SEMANTIC", "INLAY", "FOLDS", "PREVIEWS"), fake.calls.toList(),
            "passes must run sequentially in priority order",
        )
        assertEquals(
            listOf(DaemonPass.DIAGNOSTICS, DaemonPass.SEMANTIC, DaemonPass.INLAY, DaemonPass.FOLDS, DaemonPass.PREVIEWS),
            rec.passesDone(),
        )
        daemon.close()
    }

    @Test
    fun aNewEditCancelsTheInFlightRunAndOnlyTheLatestRuns() = runTest {
        val fake = FakeEngine()
        val rec = Recorder()
        val daemon = EditorEngineDaemon(this, fake, "App.kt", policy, rec)
        daemon.restart("v1")
        advanceTimeBy(100) // still inside the 300ms reparse delay — v1 hasn't started its passes
        daemon.restart("v2") // supersedes v1
        advanceUntilIdle()
        // v1's passes never ran (it was cancelled during the debounce); only v2 produced a full run.
        assertEquals(5, fake.calls.size, "exactly one run's worth of passes should execute")
        assertFalse(rec.events.any { it.first == DaemonPhase.RUN_STARTED && it.third == 1 }, "v1 must not start its passes")
        assertTrue(rec.events.any { it.first == DaemonPhase.RUN_FINISHED && it.third == 2 }, "v2 must finish")
        daemon.close()
    }

    @Test
    fun aPreemptedPassWaitsAndRetriesRatherThanAbandoningTheRun() = runTest {
        val fake = FakeEngine().apply { preemptOnce += "DIAGNOSTICS" } // a completion cuts in on the first diagnostics pass
        val rec = Recorder()
        val daemon = EditorEngineDaemon(this, fake, "App.kt", policy, rec)
        daemon.restart("v1")
        advanceUntilIdle()
        // DIAGNOSTICS ran, was preempted, then retried; every pass ultimately completed.
        assertEquals(listOf("DIAGNOSTICS", "DIAGNOSTICS", "SEMANTIC", "INLAY", "FOLDS", "PREVIEWS"), fake.calls.toList())
        assertTrue(rec.events.any { it.first == DaemonPhase.PASS_PREEMPTED && it.second == DaemonPass.DIAGNOSTICS })
        assertEquals(5, rec.passesDone().size, "all five passes finished after the retry")
        daemon.close()
    }

    @Test
    fun aSkippedLanguageGatedPassDoesNotCallTheBackend() = runTest {
        val fake = FakeEngine()
        val rec = Recorder()
        val daemon = EditorEngineDaemon(this, fake, "App.kt", policy, rec).apply {
            // e.g. a non-Kotlin file: only diagnostics + inlay apply, the Kotlin-only passes are skipped.
            appliesTo = { it == DaemonPass.DIAGNOSTICS || it == DaemonPass.INLAY }
        }
        daemon.restart("v1")
        advanceUntilIdle()
        assertEquals(listOf("DIAGNOSTICS", "INLAY"), fake.calls.toList())
        assertTrue(rec.events.any { it.first == DaemonPhase.PASS_SKIPPED && it.second == DaemonPass.SEMANTIC })
        daemon.close()
    }

    @Test
    fun printsAKeystrokeTimeline() = runTest {
        val fake = FakeEngine().apply { preemptOnce += "SEMANTIC" }
        val rec = Recorder()
        val daemon = EditorEngineDaemon(this, fake, "App.kt", policy, rec)
        daemon.restart("fun main() {}")
        advanceUntilIdle()
        println("\n=== daemon timeline (one keystroke, SEMANTIC preempted once) ===")
        println(rec.render())
        daemon.close()
    }
}
