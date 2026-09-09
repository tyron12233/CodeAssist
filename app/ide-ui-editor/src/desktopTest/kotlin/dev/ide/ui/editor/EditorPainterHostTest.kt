package dev.ide.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.folding.FoldModel
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.ext.EditorPaintContext
import dev.ide.ui.ext.EditorPaintLayer
import dev.ide.ui.ext.EditorPainterContribution
import dev.ide.ui.ext.EditorPainterRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor painter tier: how painters are resolved for a file, what the geometry context answers, and what
 * happens to a painter that throws inside the draw.
 *
 * The registry is process-global (like every `dev.ide.ui.ext` registry), so each test disposes what it
 * registered.
 */
class EditorPainterHostTest {

    private val disposals = ArrayList<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        disposals.forEach { it() }
        disposals.clear()
    }

    private fun register(painter: EditorPainterContribution) {
        val reg = EditorPainterRegistry.register(painter)
        disposals += { reg.dispose() }
    }

    private fun painter(
        id: String,
        layer: EditorPaintLayer = EditorPaintLayer.BelowText,
        order: Int = 1000,
        appliesTo: (String) -> Boolean = { true },
        paint: androidx.compose.ui.graphics.drawscope.DrawScope.(EditorPaintContext) -> Unit = {},
    ) = EditorPainterContribution(id, order, layer, appliesTo, paint)

    /** A real [CanvasDrawScope] over a small bitmap, so `runPainters` is exercised on a genuine DrawScope. */
    private fun draw(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        val bitmap = ImageBitmap(8, 8)
        val size = Size(8f, 8f)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) { block() }
    }

    private fun context(text: String = "one\ntwo\nthree"): EditorPaintContext {
        val doc = EditorDocument.of(text)
        return CanvasPaintContext(
            path = "App.kt",
            visible = 0..2,
            doc = doc,
            metrics = EditorMetrics(
                lineHeight = 20f, charWidth = 8f, padTop = 6f, padLeft = 8f, padRight = 24f, padBottom = 200f,
            ),
            gutterWidthPx = 40f,
            textLeftPx = 48f,
            foldModel = FoldModel.build(doc, emptyList()),
            lineTopOf = { line -> 6f + line * 20f },
            xAt = { offset -> 48f + offset * 8f },
        )
    }

    // ---- resolution ----

    @Test
    fun paintersResolveByFileAndLayerInOrder() {
        register(painter("late", order = 20))
        register(painter("early", order = 10))
        register(painter("above", layer = EditorPaintLayer.AboveText))
        register(painter("other-file", appliesTo = { it.endsWith(".java") }))

        assertEquals(
            listOf("early", "late"),
            EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText).map { it.id },
            "below-text painters for this file, lowest order first",
        )
        assertEquals(
            listOf("above"),
            EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.AboveText).map { it.id },
        )
        // The file-gated painter claims only what its predicate claims; the ungated ones claim everything.
        val forJava = EditorPainterRegistry.forFile("A.java", EditorPaintLayer.BelowText).map { it.id }
        assertEquals(listOf("early", "late", "other-file"), forJava)
        assertFalse(
            "other-file" in EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText).map { it.id },
            "a painter gated to .java says nothing about a .kt file",
        )
    }

    @Test
    fun aPainterWhosePredicateThrowsIsSkippedRatherThanBreakingResolution() {
        register(painter("thrower", appliesTo = { error("boom") }))
        register(painter("fine"))

        assertEquals(
            listOf("fine"),
            EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText).map { it.id },
        )
    }

    // ---- the draw guard ----

    @Test
    fun aPainterThatThrowsIsRetiredForTheSessionAndTheRestStillDraw() {
        var goodDraws = 0
        register(painter("bad") { error("painter blew up") })
        register(painter("good") { goodDraws++ })

        val ctx = context()
        val painters = EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText)
        draw { runPainters(painters, ctx) }

        assertTrue(EditorPainterRegistry.isRetired("bad"))
        assertFalse(EditorPainterRegistry.isRetired("good"))
        assertEquals(1, goodDraws, "the failure does not abandon the rest of the list")
        assertNotNull(EditorPainterRegistry.retirements["bad"], "the reason is kept, not swallowed")

        // Retirement is what makes this survivable: the next frame no longer calls it at all.
        assertEquals(
            listOf("good"),
            EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText).map { it.id },
        )
    }

    @Test
    fun reRegisteringARetiredPainterGivesItAnotherChance() {
        val bad = painter("bad") { error("boom") }
        val reg = EditorPainterRegistry.register(bad)
        draw { runPainters(EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText), context()) }
        assertTrue(EditorPainterRegistry.isRetired("bad"))
        reg.dispose()

        register(painter("bad"))
        assertFalse(EditorPainterRegistry.isRetired("bad"), "a reloaded plugin is not still retired")
    }

    // ---- geometry ----

    @Test
    fun theContextAnswersTheEditorsOwnGeometry() {
        val ctx = context()

        assertEquals(3, ctx.lineCount)
        assertEquals(20f, ctx.lineHeight)
        assertEquals(40f, ctx.gutterWidth)
        assertEquals(48f, ctx.textLeft)
        assertEquals(26f, ctx.lineTop(1), "line tops come from the draw's own row map")
        assertEquals(1, ctx.lineOf(5))
        assertEquals(4..7, ctx.lineRange(1))
        assertFalse(ctx.isHidden(1))
    }

    @Test
    fun anOffsetOutsideTheDocumentIsClampedIntoIt() {
        val ctx = context("one")
        assertEquals(48f, ctx.xOf(0))
        // Clamped to the document's end (3 chars * 8px past the text edge), not answered as garbage.
        assertEquals(72f, ctx.xOf(999))
        assertEquals(48f, ctx.xOf(-5))
    }

    @Test
    fun anOffsetTheLayoutRefusesAnswersTheTextEdgeRatherThanThrowing() {
        val doc = EditorDocument.of("one")
        val ctx = CanvasPaintContext(
            path = "App.kt",
            visible = 0..0,
            doc = doc,
            metrics = EditorMetrics(20f, 8f, 6f, 8f, 24f, 200f),
            gutterWidthPx = 40f,
            textLeftPx = 48f,
            foldModel = FoldModel.build(doc, emptyList()),
            lineTopOf = { 6f },
            // Stands in for the editor's own xOf, which throws when the offset is outside the shaped line.
            // A painter can hold an offset from a decoration pass that is one edit stale, and the draw must
            // survive that: a mark a few pixels wrong for one frame beats taking the editor's draw down.
            xAt = { error("offset not laid out") },
        )

        assertEquals(48f, ctx.xOf(1))
    }

    @Test
    fun aFoldedLineIsReportedAsHiddenSoAPainterCanSkipIt() {
        val doc = EditorDocument.of("one\ntwo\nthree")
        // "one\ntwo\nthree": collapsing offsets 0..7 folds lines 0..1, hiding line 1 behind line 0.
        val folds = listOf(FoldRegion(start = 0, end = 7, placeholder = "...", kind = "test", collapsed = true))
        val model = FoldModel.build(doc, folds)
        val ctx = CanvasPaintContext(
            path = "App.kt",
            visible = 0..2,
            doc = doc,
            metrics = EditorMetrics(20f, 8f, 6f, 8f, 24f, 200f),
            gutterWidthPx = 40f,
            textLeftPx = 48f,
            foldModel = model,
            lineTopOf = { 6f },
            xAt = { 48f },
        )

        assertTrue(ctx.isHidden(1), "a line inside a collapsed fold is not on screen")
    }

    @Test
    fun noPaintersIsTheNoneSentinel() {
        assertTrue(EditorPainters.NONE.isEmpty)
        draw { runPainters(emptyList(), context()) } // must not throw
    }

    @Test
    fun aPainterSeesTheDrawScopeItCanDrawWith() {
        var sawSize: Size? = null
        register(painter("measure") { sawSize = size })
        draw { runPainters(EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText), context()) }

        assertEquals(Size(8f, 8f), sawSize, "the painter draws in the editor's own scope")
    }

    @Test
    fun colorsAPainterUsesAreItsOwn() {
        // The point of the painter tier: unlike a decoration, nothing here resolves a theme role, so a plugin
        // that needs its own gradient has a way to draw one.
        var drawn = false
        register(
            painter("gradient") { ctx ->
                drawRect(Color(0x40FF0000), Offset(ctx.textLeft, ctx.lineTop(0)), Size(10f, ctx.lineHeight))
                drawn = true
            },
        )
        draw { runPainters(EditorPainterRegistry.forFile("App.kt", EditorPaintLayer.BelowText), context()) }
        assertTrue(drawn)
        assertFalse(EditorPainterRegistry.isRetired("gradient"))
    }
}
