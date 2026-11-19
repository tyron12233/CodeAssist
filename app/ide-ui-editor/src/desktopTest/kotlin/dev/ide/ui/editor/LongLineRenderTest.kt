package dev.ide.ui.editor

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import dev.ide.ui.editor.core.LineStyles
import dev.ide.ui.editor.core.MAX_RENDERED_LINE_COLS
import dev.ide.ui.editor.core.MAX_TOKENIZED_LINE_COLS
import dev.ide.ui.editor.folding.FoldModel
import dev.ide.ui.editor.folding.FoldRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A line of hundreds of thousands of characters (a minified script, a page with an inlined image) is laid out
 * only up to [MAX_RENDERED_LINE_COLS]: shaping the whole of it in the draw held the main thread until the
 * system ended the app. Everything that asks the layout for a column has to stay inside what was laid out.
 */
class LongLineRenderTest {

    private fun cache() = LineRenderCache(
        TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr),
        TextStyle(),
        arrayOfNulls<SpanStyle>(64),
    )

    private val long = "<p>" + "a".repeat(MAX_RENDERED_LINE_COLS * 30) + "</p>"

    @Test
    fun aVeryLongLineIsLaidOutOnlyUpToTheCut() {
        val doc = EditorDocument.of("short\n$long\nshort")
        val styles = LineStyles(CodeLanguage.Java).also { it.reset(doc) }
        val rc = cache()

        val layout = rc.layoutFor(1, doc, styles)
        val laidOut = layout.layoutInput.text.length
        assertTrue(laidOut in MAX_RENDERED_LINE_COLS..MAX_RENDERED_LINE_COLS + 4, "cut at the limit plus its marker, was $laidOut")
        assertEquals(long.take(MAX_RENDERED_LINE_COLS), layout.layoutInput.text.text.take(MAX_RENDERED_LINE_COLS))

        // The caret at the far end of the line is drawn at the cut rather than asking for a column that is not there.
        val endCol = rc.rawToVisual(1, long.length)
        assertTrue(endCol <= laidOut, "a column past the cut stays inside the layout")
        layout.getHorizontalPosition(endCol, usePrimaryDirection = true)

        assertEquals("short", rc.layoutFor(0, doc, styles).layoutInput.text.text, "an ordinary line is untouched")
    }

    @Test
    fun aColumnPastTheCutMapsToTheCutWithInlaysToo() {
        val rc = cache().apply { setInlays(mapOf(0 to listOf(InlayPiece(8, "x: "))), SpanStyle()) }
        assertEquals(MAX_RENDERED_LINE_COLS + 3, rc.rawToVisual(0, MAX_RENDERED_LINE_COLS * 5))
        assertEquals(MAX_RENDERED_LINE_COLS, rc.visualToRaw(0, MAX_RENDERED_LINE_COLS + 3 + 2), "a tap on the marker is a tap on the cut")
        assertEquals(11, rc.rawToVisual(0, 8), "columns before the cut are as they were")
    }

    @Test
    fun aLineTooLongToTokenizeIsPlainAndHandsItsStateOn() {
        val huge = "int x = " + "1 + ".repeat(MAX_TOKENIZED_LINE_COLS) + "0;"
        val doc = EditorDocument.of("/* open\n$huge\nstill comment */ int y = 2;\nint z = 3;")
        val styles = LineStyles(CodeLanguage.Java).also { it.reset(doc) }

        assertTrue(styles.spansFor(1).isEmpty(), "no span is built for a line of ${huge.length} columns")
        // The block comment opened on line 0 still closes on line 2: the long line passed the state through.
        val reference = LineStyles(CodeLanguage.Java).also { it.reset(EditorDocument.of("/* open\nx\nstill comment */ int y = 2;\nint z = 3;")) }
        fun sig(s: LineStyles, line: Int) = s.spansFor(line).joinToString(",") { "${it.start}-${it.end}:${it.type}" }
        assertEquals(sig(reference, 2), sig(styles, 2), "the line after it is coloured as if it were an ordinary line")
        assertEquals(sig(reference, 3), sig(styles, 3))

        // An edit inside the long line re-lexes without building spans either.
        val edited = EditorDocument.of("/* open\n" + huge + "9\nstill comment */ int y = 2;\nint z = 3;")
        styles.splice(edited, firstLine = 1, removed = 1, inserted = 1)
        assertTrue(styles.spansFor(1).isEmpty())
        assertEquals(sig(reference, 2), sig(styles, 2))
    }

    @Test
    fun aFoldOnAVeryLongLineShowsOnlyTheRenderedPartOfEachSide() {
        val doc = EditorDocument.of("$long {\nbody\n} $long")
        val open = long.length + 2
        val close = doc.lineStart(2)
        val model = FoldModel.build(doc, listOf(FoldRegion(start = open, end = close + 1, placeholder = "...", kind = "test", collapsed = true)))
        assertTrue(model.foldStartingAt(0) != null, "the fold starts on the long line")

        val text = model.compositeText(0, doc)
        assertEquals(MAX_RENDERED_LINE_COLS, model.compositePrefixLength(0, doc))
        assertTrue(text.length <= 2 * MAX_RENDERED_LINE_COLS + 3, "both sides are cut, was ${text.length}")
        assertEquals("...", text.substring(MAX_RENDERED_LINE_COLS, MAX_RENDERED_LINE_COLS + 3), "the placeholder follows the cut prefix")
    }
}
