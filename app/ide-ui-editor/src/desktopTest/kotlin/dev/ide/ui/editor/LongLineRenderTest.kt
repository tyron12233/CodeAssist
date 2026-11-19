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
}
