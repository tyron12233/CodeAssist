package dev.ide.interp.compose

import dev.ide.interp.ComposableInvoker
import dev.ide.interp.InterpProfile
import dev.ide.interp.InterpTrace

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
            // The body failed mid-composition (e.g. an interpreter error). Balance the restart group we opened
            // so the slot table isn't left corrupt, then rethrow for the caller (the preview renderer) to
            // surface as an error view rather than aborting the whole composition.
            runCatching { ComposableAbi.endRestartGroup(group) }
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
            dispatcher.composer = recomposeComposer
            // A state-driven recomposition of THIS scope — it runs on the Recomposer's thread, outside any
            // Render trace, so open its own profiler pass (the child composables it re-runs synchronously are
            // counted within it). One line per independently-invalidated scope.
            InterpProfile.trace("interp.recompose", "recompose") {
                invokeComposable(callSiteKey, restartable, force = true, args, body)
            }
        }
        return result
    }
}
