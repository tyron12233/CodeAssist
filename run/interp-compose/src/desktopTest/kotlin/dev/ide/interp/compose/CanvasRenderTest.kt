package dev.ide.interp.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders INTERPRETED `Canvas { … }` previews to a bitmap (via [ImageComposeScene]) and asserts on actual
 * PIXELS — the full compose + layout + DRAW pipeline through the interpreter. This is the regression guard for
 * the value-class-operator/property fix (`Size / 2F`, `size.width`): those silently produced a zero-size draw
 * (a blank preview) before, because the packed `Size` is a `Long` the interpreter divided as raw bits / had no
 * getter for. The bodies here are the EXACT shapes from Google's `Canvas` samples that showed "no preview".
 *
 * Rasterization needs Skiko's native lib; where it can't load (some headless CI) [redPixels] returns -1 and the
 * checks no-op rather than fail — the value-level fix is guarded Skiko-free by `SizeValueClassProbeTest`.
 */
class CanvasRenderTest {

    private val HEADER = """
        package demo
        import androidx.compose.foundation.Canvas
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.geometry.Offset
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.graphics.PointMode
        import androidx.compose.ui.graphics.drawscope.inset
        import androidx.compose.ui.graphics.drawscope.rotate
        import androidx.compose.ui.graphics.drawscope.translate
        import androidx.compose.ui.graphics.drawscope.withTransform
        import androidx.compose.ui.unit.dp
    """.trimIndent()

    /** Red pixels painted by the Canvas body, or -1 if Skiko rasterization is unavailable in this environment. */
    private fun redPixels(body: String, wrap: Boolean = false): Int {
        val code = "$HEADER\n@Composable fun P() { Canvas(Modifier.fillMaxSize()) {\n$body\n} }"
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(CRDoc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = program["P/0"] ?: error("no P/0; have ${program.keys}")
        val renderer = ComposePreviewRenderer()
        val w = 100; val h = 100
        return try {
            @OptIn(ExperimentalComposeUiApi::class)
            val scene = ImageComposeScene(w, h, Density(1f)) {
                if (wrap) Box(Modifier.wrapContentSize()) { renderer.Render(entry, program, emptyList(), emptyList(), onError = {}, onPartialError = {}) }
                else renderer.Render(entry, program, emptyList(), emptyList(), onError = {}, onPartialError = {})
            }
            try {
                val bmp = Bitmap.makeFromImage(scene.render())
                var red = 0
                for (y in 0 until h step 3) for (x in 0 until w step 3) {
                    val c = bmp.getColor(x, y)
                    if (((c shr 16) and 0xFF) > 180 && ((c shr 8) and 0xFF) < 70 && (c and 0xFF) < 70) red++
                }
                red
            } finally { scene.close() }
        } catch (t: Throwable) {
            // Skiko native lib missing / no rendering context — signal "skip", don't fail.
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) -1
            else throw t
        }
    }

    private fun assertPaints(name: String, body: String) {
        val red = redPixels(body)
        if (red < 0) { println("[CanvasRenderTest] Skiko unavailable — skipping '$name'"); return }
        assertTrue(red > 20, "$name should paint (value-class draw args must compute); red pixels=$red")
    }

    @Test fun sizeDivDrawRect() =                 // CanvasCircleExample: drawRect(size = size / 2F)
        assertPaints("size / 2F", "val q = size / 2F; drawRect(color = Color.Red, size = q)")

    @Test fun sizeWidthHeightLine() =             // CanvasDrawDiagonalLineExample: size.width / size.height
        assertPaints("size.width", "drawLine(start = Offset(size.width, 0f), end = Offset(0f, size.height), color = Color.Red, strokeWidth = 20f)")

    @Test fun rotateWithSizeTopLeft() =           // CanvasTransformationRotate
        assertPaints("rotate", "rotate(degrees = 45F) { drawRect(color = Color.Red, topLeft = Offset(size.width / 3F, size.height / 3F), size = size / 3F) }")

    @Test fun insetNamedWithSize() =              // CanvasTransformationInset: inset(horizontal =, vertical =)
        assertPaints("inset", "val q = size / 2F; inset(horizontal = 20f, vertical = 10f) { drawRect(color = Color.Red, size = q) }")

    @Test fun withTransformTranslateRotate() =    // CanvasMultipleTransformations
        assertPaints("withTransform", "withTransform({ translate(left = size.width / 5F); rotate(degrees = 45F) }) { drawRect(color = Color.Red, topLeft = Offset(size.width / 3F, size.height / 3F), size = size / 3F) }")

    @Test fun drawPointsWithSizeAndDpToPx() =     // CanvasDrawOtherShapes
        assertPaints("drawPoints", "drawPoints(listOf(Offset(10f, 10f), Offset(size.width / 3f, size.height / 2f), Offset(size.width, size.height)), color = Color.Red, pointMode = PointMode.Points, strokeWidth = 10.dp.toPx())")

    @Test fun plainDrawRectStillPaints() =        // baseline: `size` passed through unchanged must keep working
        assertPaints("drawRect(size = size)", "drawRect(color = Color.Red, size = size)")

    @Test fun companionColorReadStillPaints() {
        // Regression: `Color.Magenta` (a value-class COMPANION property whose binding owner is the value class
        // `Color`) must NOT be misrouted to `Color.getMagenta-impl(companion)` — it would throw and blank the draw.
        val red = redPixels("drawRect(color = Color.Red, size = size)")
        if (red < 0) return
        assertTrue(red > 20, "a companion color read must still paint; red=$red")
    }

    /** Non-transparent, non-white pixels a FULL `@Composable fun P()` [source] paints, or -1 if Skiko is
     *  unavailable. Used for the `Modifier.drawWithCache { … }` samples, which use a `Spacer`, not a `Canvas`. */
    private fun nonBlankPixels(source: String): Int {
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(CRDoc(source)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = program["P/0"] ?: error("no P/0; have ${program.keys}")
        val renderer = ComposePreviewRenderer()
        val w = 120; val h = 120
        return try {
            @OptIn(ExperimentalComposeUiApi::class)
            val scene = ImageComposeScene(w, h, Density(1f)) {
                renderer.Render(entry, program, emptyList(), emptyList(), onError = {}, onPartialError = {})
            }
            try {
                val bmp = Bitmap.makeFromImage(scene.render())
                var n = 0
                for (y in 0 until h step 3) for (x in 0 until w step 3) {
                    val c = bmp.getColor(x, y)
                    val a = (c ushr 24) and 0xFF
                    val lum = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                    if (a > 40 && lum < 235) n++
                }
                n
            } finally { scene.close() }
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) -1 else throw t
        }
    }

    @Test fun drawWithCachePathRenders() {
        // CanvasDrawPath: `Modifier.drawWithCache { val p = Path(); … size.width …; onDrawBehind { drawPath(p) } }`.
        // Was reported "crashes CodeAssist" — the `size.width` in the cache block threw, the guard returned a null
        // DrawResult, and Compose NPE'd during draw. With the value-class fix the block computes → real DrawResult.
        val n = nonBlankPixels(
            """
            package demo
            import androidx.compose.foundation.layout.Spacer
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.draw.drawWithCache
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.graphics.Path
            import androidx.compose.ui.graphics.drawscope.Stroke
            @Composable fun P() {
                Spacer(Modifier.drawWithCache {
                    val path = Path(); path.moveTo(0f, 0f); path.lineTo(size.width / 2f, size.height / 2f); path.lineTo(size.width, 0f); path.close()
                    onDrawBehind { drawPath(path, Color.Magenta, style = Stroke(width = 10f)) }
                }.fillMaxSize())
            }
            """.trimIndent(),
        )
        if (n < 0) return
        assertTrue(n > 20, "drawWithCache + drawPath should render; painted=$n")
    }

    @Test fun drawWithCacheTextMeasurerRenders() {
        // CanvasMeasureText: `drawWithCache { val m = textMeasurer.measure(…, Constraints.fixedWidth((size.width*…).toInt())); onDrawBehind { drawRect(…, m.size.toSize()); drawText(m) } }`.
        val n = nonBlankPixels(
            """
            package demo
            import androidx.compose.foundation.layout.Spacer
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.draw.drawWithCache
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.text.AnnotatedString
            import androidx.compose.ui.text.TextStyle
            import androidx.compose.ui.text.drawText
            import androidx.compose.ui.text.rememberTextMeasurer
            import androidx.compose.ui.unit.Constraints
            import androidx.compose.ui.unit.sp
            import androidx.compose.ui.unit.toSize
            @Composable fun P() {
                val tm = rememberTextMeasurer()
                Spacer(Modifier.drawWithCache {
                    val m = tm.measure(AnnotatedString("Hello World Lorem ipsum"), constraints = Constraints.fixedWidth((size.width * 2f / 3f).toInt()), style = TextStyle(fontSize = 18.sp))
                    onDrawBehind { drawRect(Color(0xFFF48FB1), size = m.size.toSize()); drawText(m) }
                }.fillMaxSize())
            }
            """.trimIndent(),
        )
        if (n < 0) return
        assertTrue(n > 20, "drawWithCache + TextMeasurer + drawText should render; painted=$n")
    }

    @Test fun fillMaxSizeCanvasPaintsInsideWrapContentSize() {
        // The preview PANE composes the preview under `Modifier.wrapContentSize()` in its wrap mode; a
        // fillMaxSize Canvas must still fill (the scene's bounded max is passed through) and paint.
        val red = redPixels("drawRect(color = Color.Red, size = size)", wrap = true)
        if (red < 0) return
        assertTrue(red > 20, "a fillMaxSize Canvas must paint inside wrapContentSize; red=$red")
    }

    private class CRDoc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = CRF(); override val version = 1L
        override fun length() = text.length
    }
    private class CRF : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
