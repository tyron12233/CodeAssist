package dev.ide.ui.editor

import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.editor.core.EditSpan
import dev.ide.ui.editor.folding.FoldRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The live shifts hand back the SAME list when an edit moves nothing, and a correct copy when it does. */
class ShiftIdentityTest {

    private val tokens = listOf(
        UiSemanticToken(0, 3, "class"),
        UiSemanticToken(10, 14, "function"),
    )

    @Test
    fun editPastEveryTokenKeepsTheList() {
        val shifted = shiftSemanticTokens(tokens, EditSpan(20, 0, 1), docLength = 31)
        assertSame(tokens, shifted)
    }

    @Test
    fun editBeforeATokenCopiesAndShiftsOnlyWhatMoved() {
        val shifted = shiftSemanticTokens(tokens, EditSpan(5, 0, 2), docLength = 32)
        assertNotSame(tokens, shifted)
        assertSame(tokens[0], shifted[0], "an untouched token is reused")
        assertEquals(UiSemanticToken(12, 16, "function"), shifted[1])
    }

    @Test
    fun consumedTokenIsDroppedAfterAnUnchangedPrefix() {
        val shifted = shiftSemanticTokens(tokens, EditSpan(9, 6, 0), docLength = 24)
        assertEquals(listOf(tokens[0]), shifted)
    }

    @Test
    fun foldsAndInlaysKeepTheirListWhenNothingMoves() {
        val folds = listOf(FoldRegion(2, 8, "...", "block", false))
        assertSame(folds, shiftFoldRegions(folds, EditSpan(9, 0, 1), docLength = 20))
        val hints = listOf(UiInlayHint(4, listOf(UiInlayPart(": Int")), UiInlayKind.Type))
        assertSame(hints, shiftInlayHints(hints, EditSpan(4, 0, 1), docLength = 20))
        assertEquals(5, shiftInlayHints(hints, EditSpan(3, 0, 1), docLength = 20).single().offset)
    }
}
