package dev.ide.interp.compose

import dev.ide.interp.ComposableInvoker
import dev.ide.interp.InterpProfile
import dev.ide.interp.InterpTrace
import dev.ide.interp.InterpreterException

/**
 * The recomposition half of the Compose bridge (see `docs/compose-interpreter.md`, step 5). It re-implements
 * the compiler plugin's restartable-composable shape at interpretation time, driving the **real** runtime:
 * open a restart group around the interpreted composable body, then register the body's re-execution as the
 * scope's recomposition. When a `MutableState` the body read changes, the real Recomposer invalidates the
 * scope and calls back here, re-running just that composable body with a fresh composer.
 *
 * It shares the ambient [ComposeDispatcher.composer] with the dispatcher (which threads it into the library
 * composable calls the body makes).
 */
class ComposeRuntime(private val dispatcher: ComposeDispatcher) : ComposableInvoker {

    override fun invokeComposable(callSiteKey: Int, restartable: Boolean, force: Boolean, args: List<Any?>, body: () -> Any?): Any? {
        val outer = dispatcher.composer ?: return body() // no composition in progress → just run it
        // Capture the composer position before opening the restart group, so a body that throws mid-composition
        // (a library composable that failed with a node still open) can be unwound to here rather than leaving a
        // dangling node that crashes the enclosing composition — see [ComposableAbi.endToMarker].
        val marker = ComposableAbi.currentMarker(outer)
        val group = ComposableAbi.startRestartGroup(outer, callSiteKey)
        dispatcher.composer = group
        val result: Any?
        val scope: Any?
        try {
            // The `$changed` fast path: record the args into the slot table, then skip re-interpreting the body
            // when this is a skippable (Unit-returning) composable, nothing it was passed changed, and the
            // runtime says this group may skip (i.e. it wasn't invalidated by its own state read). The arg
            // recording must happen every pass to keep slot positions stable, so it runs before the skip test.
            // [force] (the callee's body was live-edited) defeats the skip so the new body actually runs.
            val argsChanged = force || if (restartable) ComposableAbi.argsChanged(group, args) else true
            result = if (restartable && !argsChanged && ComposableAbi.isSkipping(group)) {
                InterpProfile.count("composablesSkip")
                if (InterpTrace.enabled) InterpTrace.log("compose key=$callSiteKey SKIP (restartable=$restartable argsChanged=$argsChanged skipping=true)")
                ComposableAbi.skipToGroupEnd(group)
                Unit // a skipped Unit composable contributes no value; its nodes are reused from last time
            } else {
                InterpProfile.count("composablesRun")
                if (InterpTrace.enabled) InterpTrace.log("compose key=$callSiteKey RUN  (restartable=$restartable argsChanged=$argsChanged force=$force)")
                body()
            }
            scope = ComposableAbi.endRestartGroup(group)
        } catch (t: Throwable) {
            // The body failed mid-composition (e.g. an interpreter error, or a library composable that threw
            // with a node still open). Unwind to the pre-restart-group marker so any dangling group/node is
            // closed and the slot table isn't left corrupt, then rethrow for the caller (the preview renderer)
            // to surface as an error view rather than aborting — and, critically, so the IDE's own composition
            // around the preview doesn't crash on its next `endNode`.
            runCatching { ComposableAbi.endToMarker(outer, marker) }
            throw t
        } finally {
            dispatcher.composer = outer
        }
        // On recomposition the runtime hands us a fresh composer; re-run the whole composable (it reopens its
        // own restart group and re-registers), exactly as the plugin's restart lambda re-invokes the function.
        // force=true: the Recomposer invokes this callback ONLY when the scope was invalidated by a state the
        // body read, so the body MUST re-run — mirroring the compiler's restart lambda re-invoking with
        // `$changed or 0b1` (the force bit) so the skip check can't skip an invalidated scope. Without it an
        // argument-less composable (`Counter`, restartable + argsChanged=false + skipping=true) wrongly skips
        // its OWN state-driven recomposition and shows the stale value. Child-skip (an unchanged child of a
        // recomposing parent) is unaffected: that decision is made at the call site, not on this restart path.
        ComposableAbi.updateScope(scope) { recomposeComposer ->
            // Recomposition-storm breaker: a composable that writes a state it also reads invalidates ITSELF every
            // pass, so the real Recomposer re-runs it back-to-back with no frame boundary — an ANR the interpreter's
            // loop guard never sees (it isn't an interpreted loop). Bound recompositions per scope per rolling
            // window; on a storm, stop re-running (the last content stands) and surface it through the partial-error
            // channel instead of freezing the UI thread.
            if (recomposeStorm(callSiteKey)) {
                dispatcher.contentLambdaError = dispatcher.contentLambdaError
                    ?: InterpreterException("preview stopped: a composable recomposed over $MAX_RECOMPOSITIONS_PER_WINDOW times in ${RECOMPOSE_WINDOW_NANOS / 1_000_000}ms (a state written while composing keeps invalidating it)")
                return@updateScope
            }
            dispatcher.composer = recomposeComposer
            // A state-driven recomposition of THIS scope runs on the Recomposer's thread, outside any Render trace
            // AND outside the renderer's try/catch, so open its own profiler pass and CONTAIN any throw here — a
            // crash on the Recomposer thread would take down the whole composition (the preview is in-process). The
            // child composables it re-runs synchronously are counted within the trace.
            try {
                InterpProfile.trace("interp.recompose", "recompose") {
                    invokeComposable(callSiteKey, restartable, force = true, args, body)
                }
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c // recomposition cancellation is control flow — never swallow it
            } catch (t: Throwable) {
                dispatcher.contentLambdaError = dispatcher.contentLambdaError ?: t
            }
        }
        return result
    }

    // Per-callSiteKey recomposition counters for the storm breaker (see the updateScope callback). Touched only on
    // the single Recomposer thread that drives updateScope callbacks, so it needs no synchronization.
    private val recomposeWindows = HashMap<Int, LongArray>()

    /** True once [callSiteKey] has recomposed more than [MAX_RECOMPOSITIONS_PER_WINDOW] times within the current
     *  rolling [RECOMPOSE_WINDOW_NANOS] window — the signature of a self-invalidation storm. */
    private fun recomposeStorm(callSiteKey: Int): Boolean {
        val now = System.nanoTime()
        if (recomposeWindows.size > STORM_KEY_CAP) recomposeWindows.clear() // safety valve across long edit sessions
        val w = recomposeWindows.getOrPut(callSiteKey) { longArrayOf(now, 0L) }
        if (now - w[0] > RECOMPOSE_WINDOW_NANOS) { w[0] = now; w[1] = 0L }
        w[1] = w[1] + 1
        return w[1] > MAX_RECOMPOSITIONS_PER_WINDOW
    }

    private companion object {
        // A single scope past this within the window is a runaway self-invalidation, not real UI: a synchronous
        // storm does tens of thousands per second (caught in ~ms), while even a fast timer/animation of one scope
        // stays well under this — so the bound catches storms without tripping legitimate high-frequency updates.
        const val MAX_RECOMPOSITIONS_PER_WINDOW = 1000L
        const val RECOMPOSE_WINDOW_NANOS = 1_000_000_000L // 1s
        const val STORM_KEY_CAP = 8192
    }
}
