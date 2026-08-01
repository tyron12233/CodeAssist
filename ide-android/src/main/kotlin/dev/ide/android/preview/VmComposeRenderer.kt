package dev.ide.android.preview

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Canvas
import dev.ide.interp.compose.VmLibraryExecutor
import dev.ide.platform.log.Log

/**
 * Milestone-A **phase C** (the render boundary) — device-targeted **scaffold, UNVERIFIED**. Turns the
 * INTERPRETED Compose `LayoutNode` tree (produced on the bytecode VM at the project's own Compose version, via
 * the phase-B/B′ threading) into pixels. It is the counterpart to [dev.ide.interp.compose.ComposePreviewRenderer]
 * for the too-new-project path: that one composes into the IDE's OWN composition (a real host `Owner` draws it),
 * which an interpreted (`VmObject`) node tree cannot join — so the interpreted tree must be measured/laid-out/drawn
 * SEPARATELY, to a bitmap the preview panel displays (the preview already streams a bitmap out-of-process; see
 * `docs/compose-preview-isolation.md`).
 *
 * ## The render boundary (approach A/C)
 * "Interpret up to the draw commands, bridge the actual pixel drawing." The host side here is thin and confident:
 * allocate an ARGB_8888 [Bitmap], wrap it in a real `android.graphics.Canvas`, and bridge that into an
 * `androidx.compose.ui.graphics.Canvas`. That Compose canvas is handed INTO the VM; the interpreted draw commands
 * (`drawRect`/`drawText`/…) land on it and reach the real `android.graphics.Canvas` → real pixels. `android.graphics`
 * is the bridged floor, which is why phase C is device-only.
 *
 * ## What is NOT yet implemented (the device-verify core)
 * Measuring/laying-out/drawing a `LayoutNode` in Compose is driven by an `Owner` — a **55-abstract-member**
 * interface (`getRoot`/`getDensity`/`getLayoutDirection`/`getSharedDrawScope`/`getGraphicsContext`/
 * `snapshotObserver`/`getFontFamilyResolver`/`onRequestMeasure`/`measureAndLayout`/`createLayer`/… plus ~40
 * interaction members irrelevant to a static one-shot render). Because the nodes are interpreted `VmObject`s, a
 * host `Owner` cannot drive them (it would call node methods on VM objects); the `Owner` + the measure/layout/draw
 * passes must run INTERPRETED, inside the VM, over the interpreted root. So the plan is a small **interpreted render
 * harness** (a package the VM's policy interprets, its bytecode read from the classpath — the phase-D
 * `VmComposeHost` productizes it) that:
 *   1. builds a minimal `Owner` (the static-preview subset of the 55 members; stub the interaction members),
 *   2. `attach`es the interpreted root and runs one measure(`Constraints(width,height)`)/layout pass,
 *   3. draws the tree to the bridged [Canvas] this class hands in.
 * [render] wires the host↔VM call to that harness ([RENDER_HARNESS_FQN].[RENDER_ENTRY]); the harness itself, the
 * minimal `Owner`, and the exact measure/layout/draw entry points still need to be written and **verified on a
 * device** (no `android.graphics` off-device). Until then [render] returns an error result rather than pretending.
 *
 * See `docs/compose-runtime-interpretation.md` (phase C).
 */
class VmComposeRenderer(private val executor: VmLibraryExecutor) {

    private val log = Log.logger("VmComposeRenderer")

    /** The outcome of a render: [bitmap] on success, else [error] describes why (surfaced on the preview chip). */
    data class RenderResult(val bitmap: Bitmap?, val error: String? = null)

    /**
     * Render the interpreted composition rooted at [root] (an interpreted `androidx.compose.ui.node.LayoutNode`,
     * i.e. a `VmObject` the [executor] owns — produced by the phase-D `VmComposeHost` from the project runtime)
     * into an [widthPx] x [heightPx] ARGB_8888 bitmap at [density]. The host allocates the bitmap and the bridged
     * Compose [Canvas]; the VM does the measure/layout/draw over the interpreted tree, its draw commands landing on
     * the bridged canvas.
     */
    fun render(root: Any, widthPx: Int, heightPx: Int, density: Float): RenderResult {
        if (widthPx <= 0 || heightPx <= 0) return RenderResult(null, "invalid size ${widthPx}x$heightPx")
        if (!executor.ownsInstance(root)) {
            return RenderResult(null, "root is not an interpreted LayoutNode (Vm-owned); phase D must supply it")
        }
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        return try {
            // The bridged draw surface: a real android.graphics.Canvas over the bitmap, wrapped as a Compose
            // Canvas. Handed into the VM so the interpreted draw commands reach real pixels (the bridge floor).
            val androidCanvas = android.graphics.Canvas(bitmap)
            val composeCanvas: Canvas = Canvas(androidCanvas)

            // Delegate measure/layout/draw to the interpreted render harness (see the class doc). The harness sets
            // up a minimal interpreted Owner over `root`, measures at the given constraints, and draws to
            // `composeCanvas`. UNVERIFIED: the harness + minimal Owner are not written yet; this call resolves them
            // from the VM's own byte source once they exist, and needs device verification.
            executor.invokeStatic(
                RENDER_HARNESS_FQN, RENDER_ENTRY,
                listOf(root, composeCanvas, widthPx, heightPx, density), 0,
            )
            RenderResult(bitmap)
        } catch (t: Throwable) {
            // No harness yet (or an interpret error mid-render): degrade to a reported error, never crash the host.
            bitmap.recycle()
            val msg = "${t::class.simpleName}: ${t.message ?: "interpreted render failed"}"
            log.warn("VmComposeRenderer: $msg (phase C render harness is not implemented/verified yet)")
            RenderResult(null, msg)
        }
    }

    private companion object {
        /** The interpreted render harness (phase D productizes it as VM-interpreted code): a minimal Owner +
         *  measure/layout/draw over the interpreted root, drawing to the host-supplied bridged Compose Canvas. */
        const val RENDER_HARNESS_FQN = "dev.ide.interp.compose.VmComposeRenderHarness"
        const val RENDER_ENTRY = "measureLayoutDraw"
    }
}
