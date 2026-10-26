package dev.ide.ui.editor

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which side of an inlay hint a position at the hint's own column is drawn on. The caret must sit where the
 * next typed character will appear: after a parameter-name hint, before a type hint. It used to sit before
 * every hint, so the caret jumped across `x:` the moment the argument was typed.
 */
class InlayCaretSideTest {

    private fun cache(vararg pieces: InlayPiece) =
        LineRenderCache(
            TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr),
            TextStyle(),
            arrayOfNulls<SpanStyle>(0),
        ).apply { setInlays(mapOf(0 to pieces.toList()), SpanStyle()) }

    @Test
    fun theCaretAtAParameterHintIsAfterIt() {
        // `println(` + `x: ` at column 8: the caret at 8 is drawn after the 3-character hint.
        val rc = cache(InlayPiece(8, "x: "))
        assertEquals(11, rc.rawToVisual(0, 8))
        assertEquals(7, rc.rawToVisual(0, 7))
        assertEquals(12, rc.rawToVisual(0, 9))
    }

    @Test
    fun theCaretAtATypeHintIsBeforeIt() {
        // `val name` + `: Int` at column 8: the caret at 8 stays before the hint, at the end of the name.
        val rc = cache(InlayPiece(8, ": Int", relatesToPreceding = true))
        assertEquals(8, rc.rawToVisual(0, 8))
        assertEquals(14, rc.rawToVisual(0, 9))
    }

    @Test
    fun clicksOnEitherSideOfAHintLandOnItsColumn() {
        val rc = cache(InlayPiece(8, "x: "))
        assertEquals(8, rc.visualToRaw(0, 8))
        assertEquals(8, rc.visualToRaw(0, 9), "inside the hint snaps to its anchor")
        assertEquals(8, rc.visualToRaw(0, 11))
        assertEquals(9, rc.visualToRaw(0, 12))
    }
}
