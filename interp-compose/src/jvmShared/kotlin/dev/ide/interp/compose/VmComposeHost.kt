package dev.ide.interp.compose

import dev.ide.interp.InterpreterHooks
import dev.ide.interp.PreviewResourceResolver
import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import java.util.function.Consumer

/**
 * Milestone-A **phase D** orchestration — drives a source-interpreted `@Preview` on the **interpreted** Compose
 * runtime (the project's own version on the bytecode VM), the counterpart to [ComposePreviewRenderer] for the
 * too-new-project path. Where `ComposePreviewRenderer.Render` composes inline into the IDE's OWN composition
 * (bridged host composer), this host stands the composition up from the PROJECT's interpreted runtime, threads
 * its interpreted (`VmObject`) composer into the same `ComposeDispatcher`/`ComposeRuntime`, and produces the
 * interpreted `LayoutNode` tree that [dev.ide.android.preview.VmComposeRenderer] (phase C) rasterizes.
 *
 * The verified core is [previewDriver]: given the lowered preview it returns the host callback that threads the
 * interpreted composer and interprets the user body under a restart group — exactly the wiring proven end-to-end
 * in `InterpretedSourceComposableSpike` (a source body composing on the interpreted composer, including
 * recomposition). The composer is fed to that callback by the interpreted **setup harness** (a VM-interpreted
 * package that stands up the project runtime's `Composition`/`Recomposer`/`Applier` and hands out
 * `currentComposer`); productizing that harness — and the real-node applier the renderer needs — is the remaining
 * device work (see `docs/compose-runtime-interpretation.md`, phases C/D).
 *
 * The composer this drives is VM-owned, so `ComposeDispatcher.opsFor` selects [VmComposerOps] and the whole
 * group/slot/recomposition protocol runs through the VM — no host reflection on the composer.
 */
class VmComposeHost(
    private val executor: VmLibraryExecutor,
    private val resources: PreviewResourceResolver? = null,
    private val hooks: InterpreterHooks? = null,
    private val tolerateGaps: Boolean = true,
) {

    private val dispatcher = ComposeDispatcher(resources = resources, libraryExecutor = executor)
    private val runtime = ComposeRuntime(dispatcher)

    /**
     * The host callback the interpreted setup harness invokes with the project runtime's `currentComposer`: thread
     * that composer, then interpret [entry] (the lowered `@Preview`, with nested source calls resolved against
     * [program]/[classes]) under [ComposeRuntime]'s restart group. Library composables it calls route through
     * [ComposeDispatcher] → [VmLibraryExecutor.callComposable] with the same interpreted composer; state reads
     * subscribe the scope and recompose through the interpreted snapshot (see phase B′). The preview root is never
     * skipped (`restartable = false`) — it only recomposes when state IT read changed, exactly as
     * `ComposePreviewRenderer.Render` does.
     */
    fun previewDriver(
        entry: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass> = emptyList(),
        args: List<Any?> = emptyList(),
    ): Consumer<Any?> {
        val interpreter = Interpreter(
            program, dispatcher, runtime,
            classes = classes, tolerateGaps = tolerateGaps,
            resources = resources, hooks = hooks, libraryFallback = executor,
        )
        return Consumer { composer ->
            dispatcher.composer = composer
            runtime.invokeComposable(entry.name.hashCode(), restartable = false, force = false, args = emptyList()) {
                interpreter.call(entry, args)
            }
        }
    }

    /** The first content-lambda error swallowed this pass (lazy content that threw outside the render boundary),
     *  or null — mirrors [ComposeDispatcher.contentLambdaError] so the host can surface a partial-render chip. */
    val contentLambdaError: Throwable? get() = dispatcher.contentLambdaError

    companion object {
        /**
         * Route to the interpreted-runtime path (this host) vs. the bridged tree-walker
         * ([ComposePreviewRenderer], the material3 flip) by Compose version distance: the flip aligns while the
         * project's Compose is close to the bundled one, but the composer slot/group protocol drifts the further
         * apart they are (the version ceiling). Beyond [maxAlignedMinorDistance] minor versions, interpret the
         * runtime. Versions are `major.minor(.patch)`; unparseable inputs stay on the safe bridged path.
         */
        fun shouldInterpretRuntime(
            projectComposeVersion: String?,
            bundledComposeVersion: String?,
            maxAlignedMinorDistance: Int = 2,
        ): Boolean {
            val p = parseMajorMinor(projectComposeVersion) ?: return false
            val b = parseMajorMinor(bundledComposeVersion) ?: return false
            if (p.first != b.first) return true // a major-version gap is always too far
            return kotlin.math.abs(p.second - b.second) > maxAlignedMinorDistance
        }

        private fun parseMajorMinor(v: String?): Pair<Int, Int>? {
            val parts = v?.trim()?.split('.') ?: return null
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return major to minor
        }
    }
}
