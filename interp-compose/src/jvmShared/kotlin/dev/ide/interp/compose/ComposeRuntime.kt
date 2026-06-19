package dev.ide.interp.compose

import dev.ide.interp.ComposableInvoker

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

    override fun invokeComposable(callSiteKey: Int, restartable: Boolean, args: List<Any?>, body: () -> Any?): Any? {
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
            val argsChanged = if (restartable) ComposableAbi.argsChanged(group, args) else true
            result = if (restartable && !argsChanged && ComposableAbi.isSkipping(group)) {
                ComposableAbi.skipToGroupEnd(group)
                Unit // a skipped Unit composable contributes no value; its nodes are reused from last time
            } else {
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
        ComposableAbi.updateScope(scope) { recomposeComposer ->
            dispatcher.composer = recomposeComposer
            invokeComposable(callSiteKey, restartable, args, body)
        }
        return result
    }
}
