package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import dev.ide.interp.InterpProfile
import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import java.util.logging.Logger

/**
 * A `@PreviewParameter` provider resolved for rendering: the provider to instantiate ([providerClass] when it
 * is project source, else [providerFqn] for a library class) and how many of its sample values to render.
 */
data class PreviewParameterBinding(
    val providerClass: ResolvedClass?,
    val providerFqn: String?,
    val limit: Int,
)

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
    /** When true (the editor default), a single unsupported/failed construct is SKIPPED so the rest of the
     *  preview still renders. Pass false to make the first failure THROW to [Render]'s boundary → [onError] →
     *  a visible error instead of a silently-empty preview (used by Learn lessons, whose snippets we author
     *  and want to fail loudly if a construct doesn't dispatch). */
    private val tolerateGaps: Boolean = true,
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
    /**
     * The sample values a `@PreviewParameter` [binding] yields (instantiating its provider against [program]/
     * [classes]). Non-composable: the host calls this once (in a `remember`) to learn how many entries to lay
     * out, then renders one [Render] per value. Empty when the provider can't be built (the host falls back to
     * a single argument-less render).
     */
    fun parameterValues(
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass>,
        binding: PreviewParameterBinding,
    ): List<Any?> = runCatching {
        Interpreter(program, dispatcher, runtime, classLoader = loader, classes = classes, tolerateGaps = tolerateGaps)
            .previewParameterValues(binding.providerClass, binding.providerFqn, binding.limit)
    }.getOrElse {
        log.warning("Compose preview @PreviewParameter resolution failed: ${it::class.simpleName}: ${it.message}")
        emptyList()
    }

    @Composable
    fun Render(
        entry: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass> = emptyList(),
        /** Arguments to invoke [entry] with — a single `@PreviewParameter` sample value, or empty for a plain
         *  preview. The host wraps each value's Render in a distinct `key(...)` so their slots don't collide. */
        args: List<Any?> = emptyList(),
        onError: @Composable (Throwable) -> Unit = {},
        onPartialError: (Throwable?) -> Unit = {},
    ) {
        // Live edit: the lowerer REUSES the ResolvedFunction instance for a function whose text is unchanged, so
        // the set of functions that actually changed since the last render is exactly those whose instance now
        // differs — an identity diff of consecutive program maps. A changed function always gets a fresh instance
        // (no false negatives → no stale body); if the pipeline ever copies instances, everything just looks
        // dirty and re-runs (still correct — state survives via the edit-stable call-site keys). First render:
        // empty (a fresh composition skips nothing anyway).
        val prevProgram = remember { arrayOfNulls<Map<String, ResolvedFunction>>(1) }
        val dirtyCallees = remember(program) {
            val prev = prevProgram[0]
            (if (prev == null) emptySet() else program.keys.filterTo(HashSet()) { program[it] !== prev[it] })
                .also { prevProgram[0] = program }
        }
        val interpreter = remember(program, classes) {
            // tolerateGaps: a single unsupported construct skips rather than blanking the whole preview (the
            // editor default); a lesson passes false so a gap surfaces as a visible error instead of a blank.
            Interpreter(program, dispatcher, runtime, classLoader = loader, classes = classes, tolerateGaps = tolerateGaps, dirtyCallees = dirtyCallees)
        }
        // Phase label for the profiler: the very first composition, an edit that dirtied some functions
        // (live-edit re-render), or a plain re-render. State-driven recompositions of a single child scope are
        // measured separately (they don't re-run Render — see ComposeRuntime's updateScope trace).
        val firstPass = remember { booleanArrayOf(true) }
        val phase = when {
            firstPass[0] -> "first"
            dirtyCallees.isNotEmpty() -> "liveEdit"
            else -> "render"
        }
        firstPass[0] = false
        // We're inside the IDE's composition: thread its composer, then drive the preview through its own
        // restart group so state changes recompose just the preview subtree.
        dispatcher.composer = currentComposer
        val failure: Throwable? = try {
            // The preview root is never skipped (restartable=false → always re-runs): it only recomposes when
            // state IT read changed, in which case it must run. Skipping is a win for the CHILD composables it
            // invokes (each routed through the interpreter's restartable=returnsUnit path), not the root itself.
            InterpProfile.trace("interp.render", phase) {
                runtime.invokeComposable(entry.name.hashCode(), restartable = false, force = false, args = emptyList()) {
                    interpreter.call(entry, args)
                }
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
