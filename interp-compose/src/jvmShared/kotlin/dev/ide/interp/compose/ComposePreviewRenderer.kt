package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import java.util.logging.Logger

/**
 * Renders a lowered `@Preview` composable as real Compose UI — the device render surface for the editor's
 * Compose preview (see `docs/compose-interpreter.md`). It hosts the interpreter + [ComposeDispatcher] +
 * [ComposeRuntime] inside the IDE's own composition (so it gets a real `Composer`, window, and Recomposer):
 * [Render] threads the ambient composer into the interpreter, which walks the [ResolvedFunction] tree and
 * composes its `Text`/`Column`/… calls into the live tree. State reads recompose through the real runtime.
 *
 * The interpreter half is device-proven (`ComposeLambdaSpikeTest`, `ComposeRecompositionSpikeTest`); this
 * packages it as a reusable composable the preview panel embeds.
 */
class ComposePreviewRenderer(
    /** The project's library [ClassLoader] (device preview) — see [ComposeDispatcher.loader]. Null on desktop
     *  (library composables resolve against the IDE's bundled Compose-for-Desktop). */
    private val loader: ClassLoader? = null,
) {

    private val dispatcher = ComposeDispatcher(loader = loader)
    private val runtime = ComposeRuntime(dispatcher)
    private val log = Logger.getLogger("ComposePreviewRenderer")

    /**
     * Compose the preview [entry] (looked-up source calls resolved against [program]) into the current slot.
     * If the interpreter fails at runtime (an unsupported construct, an unresolved call, a dispatch error),
     * the failure is caught and handed to [onError] — so the panel shows an investigable error view instead
     * of letting the exception abort the IDE's composition. The restart group is balanced first
     * ([ComposeRuntime]) so the slot table stays consistent.
     *
     * Content-lambda errors (exceptions thrown inside lazy content like `LazyColumn { items { … } }`) are
     * swallowed by [ComposeDispatcher] so they don't kill the host thread, but the first one is captured in
     * [ComposeDispatcher.contentLambdaError]. After every composition pass a [SideEffect] reads that field,
     * resets it, and calls [onPartialError] (null = no error this pass, non-null = partial render failure) so
     * the host can surface a chip-level warning without replacing the (partial) preview content.
     */
    @Composable
    fun Render(
        entry: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass> = emptyList(),
        onError: @Composable (Throwable) -> Unit = {},
        onPartialError: (Throwable?) -> Unit = {},
    ) {
        val interpreter = remember(program, classes) {
            // tolerateGaps: a single unsupported construct skips rather than blanking the whole preview.
            Interpreter(program, dispatcher, runtime, classLoader = loader, classes = classes, tolerateGaps = true)
        }
        // We're inside the IDE's composition: thread its composer, then drive the preview through its own
        // restart group so state changes recompose just the preview subtree.
        dispatcher.composer = currentComposer
        val failure: Throwable? = try {
            // The preview root is never skipped (restartable=false → always re-runs): it only recomposes when
            // state IT read changed, in which case it must run. Skipping is a win for the CHILD composables it
            // invokes (each routed through the interpreter's restartable=returnsUnit path), not the root itself.
            runtime.invokeComposable(entry.name.hashCode(), restartable = false, args = emptyList()) {
                interpreter.call(entry, emptyList())
            }
            null
        } catch (t: Throwable) {
            log.warning("Compose preview render failed: ${t::class.simpleName}: ${t.message}")
            t
        }
        // After each composition pass, drain the content-lambda error (LazyColumn/Scaffold bodies that threw
        // mid-subcompose, outside this try/catch). Reset the field so the NEXT pass starts clean; always call
        // onPartialError so the host knows when the error clears (null) after a fix.
        SideEffect {
            val partial = dispatcher.contentLambdaError
            if (partial != null) {
                log.warning("Compose preview partial render error: ${partial::class.simpleName}: ${partial.message}")
                dispatcher.contentLambdaError = null
            }
            onPartialError(partial)
        }
        if (failure != null) onError(failure)
    }
}
