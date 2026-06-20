package dev.ide.ui.editor.engine

import dev.ide.ui.backend.AnalysisPreempted
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiSemanticToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The editor "highlighting daemon" passes, run in priority order (IntelliJ's TextEditorHighlightingPass set). */
enum class DaemonPass { DIAGNOSTICS, SEMANTIC, INLAY, FOLDS, PREVIEWS }

/** Daemon lifecycle, emitted to a [DaemonObserver] — the instrument a headless harness records a timeline from. */
enum class DaemonPhase {
    /** A document change cancelled the in-flight run and scheduled a fresh one (after the reparse delay). */
    RESTARTED,
    /** The reparse delay elapsed without a newer edit; the pass run begins. */
    RUN_STARTED,
    PASS_STARTED,
    PASS_DONE,
    /** A pass was preempted by a higher-priority engine call (completion); the daemon waits and retries it. */
    PASS_PREEMPTED,
    /** A pass didn't apply to this file (language gate) and contributed nothing. */
    PASS_SKIPPED,
    RUN_FINISHED,
    /** A newer edit (or close) cancelled the run mid-flight. */
    RUN_CANCELLED,
}

/** Observes daemon lifecycle for diagnostics/tests. Null in production. */
fun interface DaemonObserver {
    fun on(phase: DaemonPhase, pass: DaemonPass?, revision: Int)
}

/**
 * Tunables, mirroring IntelliJ's `DaemonCodeAnalyzerSettings.autoReparseDelay`: one debounce for the whole
 * pass run (not a separate one per channel), and a single preempt-retry policy shared by every pass.
 */
data class EditorDaemonPolicy(
    /** Quiet period after the last edit before the pass run starts (IntelliJ default is ~300ms). */
    val autoReparseDelay: Duration = 300.milliseconds,
    /** After a pass is preempted by completion, how long to wait before retrying it. */
    val preemptRetryDelay: Duration = 150.milliseconds,
    /** Max consecutive preemptions of one pass before giving up this run (a later edit retriggers). */
    val maxPreemptRetries: Int = 8,
    /** Passes to run, in priority order. */
    val passOrder: List<DaemonPass> = listOf(
        DaemonPass.DIAGNOSTICS, DaemonPass.SEMANTIC, DaemonPass.INLAY, DaemonPass.FOLDS, DaemonPass.PREVIEWS,
    ),
)

/**
 * The editor's highlighting daemon — modelled on IntelliJ's `DaemonCodeAnalyzer`.
 *
 * Instead of N independent debounced effects each retrying on its own (the old [CodeEditor] shape, which
 * fanned out 4–5 concurrent passes per keystroke that all then independently retried on preemption — a
 * retry storm against the one engine thread), this is ONE restartable daemon: a document change [restart]s it
 * (cancelling any in-flight run), and after the reparse delay it runs the highlighting passes **in priority
 * order, sequentially** — honest, because the backend serializes them onto a single worker anyway — each
 * cancellable, with ONE unified preempt-retry. A completion request (the interactive lane) preempts the
 * running pass; the daemon catches that, waits, and retries that pass rather than abandoning the whole run.
 *
 * Pure / non-Compose so it runs headless: a test drives [restart] with a virtual clock and reads the
 * [DaemonObserver] timeline to see exactly how the passes interleave (and what a completion blocks).
 *
 * The host sets the `on*` sinks (apply results to the editor session / Compose state) and the language gate
 * [appliesTo]; results from a superseded run are dropped (the revision guard), so a stale pass can't clobber
 * a newer edit's state.
 */
class EditorEngineDaemon(
    private val scope: CoroutineScope,
    private val backend: IdeBackend,
    private val path: String,
    private val policy: EditorDaemonPolicy = EditorDaemonPolicy(),
    private val observer: DaemonObserver? = null,
) {
    // Result sinks — set by the host. Each is invoked only for the run that is still current.
    var onDiagnostics: (List<UiDiagnostic>) -> Unit = {}
    var onSemanticTokens: (List<UiSemanticToken>) -> Unit = {}
    var onInlayHints: (List<UiInlayHint>) -> Unit = {}
    var onCodeFolds: (List<UiFoldRegion>) -> Unit = {}
    var onComposePreviews: (List<UiComposePreview>) -> Unit = {}

    /** Whether [pass] should run for this file (language gate); a skipped pass clears its sink. */
    var appliesTo: (DaemonPass) -> Boolean = { true }
    /** Inlay hints are user-toggleable; gate them without touching [appliesTo]. */
    var inlayEnabled: Boolean = true

    @Volatile private var revision = 0
    private var job: Job? = null

    /** A document change: cancel the in-flight run and schedule a fresh one after the reparse delay. */
    fun restart(text: String) {
        val myRev = ++revision
        job?.cancel()
        observer?.on(DaemonPhase.RESTARTED, null, myRev)
        job = scope.launch {
            delay(policy.autoReparseDelay)
            runPasses(text, myRev)
        }
    }

    /** Stop the daemon (file closed / editor disposed). */
    fun close() {
        job?.cancel()
        job = null
    }

    private suspend fun runPasses(text: String, myRev: Int) {
        observer?.on(DaemonPhase.RUN_STARTED, null, myRev)
        try {
            for (pass in policy.passOrder) runPass(pass, text, myRev)
            observer?.on(DaemonPhase.RUN_FINISHED, null, myRev)
        } catch (c: CancellationException) {
            observer?.on(DaemonPhase.RUN_CANCELLED, null, myRev)
            throw c
        }
    }

    private suspend fun runPass(pass: DaemonPass, text: String, myRev: Int) {
        if (!enabled(pass)) {
            applyEmpty(pass, myRev)
            observer?.on(DaemonPhase.PASS_SKIPPED, pass, myRev)
            return
        }
        var attempt = 0
        while (true) {
            observer?.on(DaemonPhase.PASS_STARTED, pass, myRev)
            try {
                fetchAndApply(pass, text, myRev)
                observer?.on(DaemonPhase.PASS_DONE, pass, myRev)
                return
            } catch (preempt: AnalysisPreempted) {
                observer?.on(DaemonPhase.PASS_PREEMPTED, pass, myRev)
                if (++attempt >= policy.maxPreemptRetries) return
                delay(policy.preemptRetryDelay) // a higher-priority call holds the engine; wait, then retry this pass
            } catch (c: CancellationException) {
                throw c // a newer edit (or close) superseded this run
            } catch (e: Throwable) {
                if (clearOnError(pass)) applyEmpty(pass, myRev)
                return // a non-preemption failure: drop this pass for the run, a later edit retriggers
            }
        }
    }

    /** Fetch from the backend then apply — but only if this run is still current (drop superseded results). */
    private suspend fun fetchAndApply(pass: DaemonPass, text: String, myRev: Int) {
        when (pass) {
            DaemonPass.DIAGNOSTICS -> {
                backend.updateDocument(path, text) // the engine's live overlay must see the buffer first
                val r = backend.analyze(path, text)
                ifCurrent(myRev) { onDiagnostics(r) }
            }
            DaemonPass.SEMANTIC -> {
                val r = backend.semanticTokens(path, text)
                ifCurrent(myRev) { onSemanticTokens(r) }
            }
            DaemonPass.INLAY -> {
                val r = backend.hintsAt(path, text, 0, text.length)
                ifCurrent(myRev) { onInlayHints(r) }
            }
            DaemonPass.FOLDS -> {
                val r = backend.codeFolds(path, text)
                ifCurrent(myRev) { onCodeFolds(r) }
            }
            DaemonPass.PREVIEWS -> {
                val r = backend.composePreviews(path, text)
                ifCurrent(myRev) { onComposePreviews(r) }
            }
        }
    }

    private fun enabled(pass: DaemonPass): Boolean =
        appliesTo(pass) && (pass != DaemonPass.INLAY || inlayEnabled)

    /** SEMANTIC/FOLDS keep their last result on a transient error (it tracks the text via in-place shifting);
     *  the others clear so a half-typed buffer never shows stale phantom hints/diagnostics/previews. */
    private fun clearOnError(pass: DaemonPass): Boolean = when (pass) {
        DaemonPass.SEMANTIC, DaemonPass.FOLDS -> false
        else -> true
    }

    private suspend fun applyEmpty(pass: DaemonPass, myRev: Int) = ifCurrent(myRev) {
        when (pass) {
            DaemonPass.DIAGNOSTICS -> onDiagnostics(emptyList())
            DaemonPass.SEMANTIC -> onSemanticTokens(emptyList())
            DaemonPass.INLAY -> onInlayHints(emptyList())
            DaemonPass.FOLDS -> onCodeFolds(emptyList())
            DaemonPass.PREVIEWS -> onComposePreviews(emptyList())
        }
    }

    private suspend fun ifCurrent(myRev: Int, apply: () -> Unit) {
        if (myRev == revision && coroutineContext.isActive) apply()
    }
}
