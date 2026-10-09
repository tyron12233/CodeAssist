package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.editor.blocks.BlockCanvas
import dev.ide.ui.editor.blocks.BlockCanvasState
import dev.ide.ui.editor.blocks.BlockDrag
import dev.ide.ui.editor.blocks.BlockGeometry
import dev.ide.ui.editor.blocks.BlockLayouter
import dev.ide.ui.editor.blocks.ComposeTextMeasure
import dev.ide.ui.editor.blocks.Gap
import dev.ide.ui.editor.blocks.Hidden
import dev.ide.ui.editor.blocks.OutlineScreen
import dev.ide.ui.editor.blocks.buildOutline
import dev.ide.ui.editor.blocks.findFirst
import dev.ide.ui.editor.blocks.rememberBlockInk
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders block pages off-screen to PNGs so the canvas can be eyeballed without launching the app. Not an
 * assertion; the snapshots land in `<tmpdir>/codeassist-snapshots`.
 */
class BlockRenderSnapshot {

    @Test
    fun renderTypedPage() {
        snapshot("blocks-typed.png", 900, 1300) { Page(typedSampleFile()) }
    }

    @Test
    fun renderNestedPages() {
        snapshot("blocks-deep.png", 1500, 500) { Page(deepSampleFile()) }
        snapshot("blocks-ops.png", 900, 700) { Page(opSampleFile()) }
        snapshot("blocks-loop.png", 900, 1000) { Page(sampleFile()) }
    }

    @Test
    fun renderOutline() {
        val (root, src) = typedSampleFile()
        snapshot("blocks-outline.png", 822, 900) {
            val ink = rememberBlockInk(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
            OutlineScreen(buildOutline(root, src, kotlin = false), null, {}, {}, {}, {}, {}, {}, ink, Modifier.fillMaxSize())
        }
    }

    /** A page panned past the canvas edge paints nothing outside the canvas (the screen above it stays clean). */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theCanvasPaintsOnlyInsideItsBounds() {
        val (root, src) = typedSampleFile()
        val scene = ImageComposeScene(width = 600, height = 600, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                val fn = remember { findFirst(root) { it.label == "method" }!! }
                val g = BlockGeometry(LocalDensity.current.density)
                val measurer = rememberTextMeasurer(cacheSize = 0)
                val style = Ide.type.codeSmall
                val measure = remember { ComposeTextMeasure(measurer, style, style) }
                val ink = rememberBlockInk(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
                val layoutFor = remember { { h: Hidden?, gap: Gap? -> BlockLayouter(measure, g, hidden = h, gap = gap).layoutFunction(fn, src, emptyList()) } }
                // Scrolled down, so the page's top rows sit above the canvas.
                val state = remember { BlockCanvasState().apply { offset = androidx.compose.ui.geometry.Offset(0f, -120f) } }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.padding(top = 150.dp).size(300.dp, 100.dp)) {
                        BlockCanvas(
                            layoutFor, state, remember { BlockDrag() }, ink, null,
                            onTap = { _, _ -> }, startDrag = { _, _, _ -> null }, onDrop = { _, _, _, _ -> },
                            modifier = Modifier.fillMaxSize(), zoomControls = false,
                        )
                    }
                }
            }
        }
        try {
            scene.render()
            val img = scene.render(16_000_000L)
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(img)
            val painted = (0 until 600).flatMap { x -> (0 until 298).map { y -> x to y } }.count { (x, y) -> bitmap.getColor(x, y) != 0xFF000000.toInt() }
            assertEquals(0, painted, "pixels painted above the canvas")
            val inside = (0 until 600).flatMap { x -> (300 until 500).map { y -> x to y } }.count { (x, y) -> bitmap.getColor(x, y) != 0xFF000000.toInt() }
            assertTrue(inside > 0, "the page is drawn inside the canvas")
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Page(sample: Pair<UiBlockNode, String>) {
        val (root, src) = sample
        val fn = remember { findFirst(root) { it.label == "method" }!! }
        val g = BlockGeometry(LocalDensity.current.density)
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val style = Ide.type.codeSmall
        val measure = remember { ComposeTextMeasure(measurer, style, style) }
        val ink = rememberBlockInk(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
        val layoutFor = remember { { h: Hidden?, gap: Gap? -> BlockLayouter(measure, g, hidden = h, gap = gap).layoutFunction(fn, src, emptyList()) } }
        Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
            BlockCanvas(
                layoutFor, remember { BlockCanvasState() }, remember { BlockDrag() }, ink, null,
                onTap = { _, _ -> }, startDrag = { _, _, _ -> null }, onDrop = { _, _, _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
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
        val OUT_DIR: String = java.io.File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }.absolutePath
    }
}
