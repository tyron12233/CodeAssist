package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.blocks.DragState
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders the block canvas off-screen to a PNG so the new chain/depth layout can be eyeballed without a
 *  full app launch. Not an assertion — a visual snapshot dropped at [OUT]. */
class BlockRenderSnapshot {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderTypedSample() {
        val (file, src) = typedSampleFile()
        snapshot("blocks-typed.png", 820, 1500) {
            val ctx = previewCtx(src)
            Box(Modifier.width(400.dp).heightIn(min = 740.dp).background(Ca.colors.editorBg).padding(14.dp)) {
                PuzzleCanvas(file, ctx)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderDepthCapAndFocus() {
        // A2 canvas: a 5-deep nested call collapses its inner level to a drill-in chip.
        val (deepFile, deepSrc) = deepSampleFile()
        snapshot("blocks-deep.png", 1500, 760) {
            val ctx = previewCtx(deepSrc)
            Box(Modifier.width(720.dp).heightIn(min = 360.dp).background(Ca.colors.editorBg).padding(14.dp)) {
                PuzzleCanvas(deepFile, ctx)
            }
        }
        // A2 drill-in: the focus sheet the chip opens, with the expression re-rooted + editable.
        val (focusNode, focusSrc) = deepFocusExpr()
        snapshot("blocks-focus.png", 1100, 900) {
            val ctx = previewCtx(focusSrc)
            Box(Modifier.fillMaxSize().background(Ca.colors.editorBg)) {
                FocusSheet(focusNode, ctx, canBack = false, onBack = {}, onClose = {})
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderOperatorChain() {
        // A3: a 4-operand `&&` chain in an if-condition lays out as a vertical operator block.
        val (file, src) = opSampleFile()
        snapshot("blocks-ops.png", 900, 900) {
            val ctx = previewCtx(src)
            Box(Modifier.width(440.dp).heightIn(min = 420.dp).background(Ca.colors.editorBg).padding(14.dp)) {
                PuzzleCanvas(file, ctx)
            }
        }
    }

    @Composable
    private fun previewCtx(src: String): Ctx {
        val drag = remember { DragState() }
        val scope = rememberCoroutineScope()
        return Ctx("/preview/Sample.java", PreviewBackend, scope, src, null, null, drag, {}, {}, { _, _ -> }, {})
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            scene.render()                       // first frame (fonts may still be the fallback)
            val img = scene.render(16_000_000L)   // second frame after recomposition settles
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = "$OUT_DIR/$name"
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $out (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/52154a21-c913-4db9-af75-e8600ca9445f/scratchpad"
    }
}
