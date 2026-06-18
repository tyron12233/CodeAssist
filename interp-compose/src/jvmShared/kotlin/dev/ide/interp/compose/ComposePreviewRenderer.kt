package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction

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

    /**
     * Compose the preview [entry] (looked-up source calls resolved against [program]) into the current slot.
     * If the interpreter fails at runtime (an unsupported construct, an unresolved call, a dispatch error),
     * the failure is caught and handed to [onError] — so the panel shows an investigable error view instead
     * of letting the exception abort the IDE's composition. The restart group is balanced first
     * ([ComposeRuntime]) so the slot table stays consistent.
     */
    @Composable
    fun Render(
        entry: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass> = emptyList(),
        onError: @Composable (Throwable) -> Unit = {},
    ) {
        val interpreter = remember(program, classes) {
            Interpreter(program, dispatcher, runtime, classLoader = loader, classes = classes)
        }
        // We're inside the IDE's composition: thread its composer, then drive the preview through its own
        // restart group so state changes recompose just the preview subtree.
        dispatcher.composer = currentComposer
        val failure: Throwable? = try {
            runtime.invokeComposable(entry.name.hashCode()) { interpreter.call(entry, emptyList()) }
            null
        } catch (t: Throwable) {
            t
        }
        if (failure != null) onError(failure)
    }
}
