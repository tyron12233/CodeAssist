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

    /** The object-copying shift the compact one replaced, kept as the reference it must agree with. */
    private fun referenceShift(tokens: List<UiSemanticToken>, edit: EditSpan, docLength: Int): List<UiSemanticToken> {
        fun mapStart(o: Int) = when {
            o < edit.start -> o
            o == edit.start -> if (edit.removed == 0) o + edit.delta else o
            o < edit.start + edit.removed -> edit.start
            else -> o + edit.delta
        }
        fun mapEnd(o: Int) = when {
            o <= edit.start -> o
            o <= edit.start + edit.removed -> edit.start
            else -> o + edit.delta
        }
        return tokens.mapNotNull { t ->
            val start = mapStart(t.startOffset).coerceIn(0, docLength)
            val end = mapEnd(t.endOffset).coerceIn(start, docLength)
            if (end <= start) null else t.copy(startOffset = start, endOffset = end)
        }
    }

    @Test
    fun chainedShiftsAgreeWithCopyingEveryToken() {
        val random = kotlin.random.Random(7)
        repeat(200) { run ->
            var docLength = 400
            var expected = (0 until 60).map { k ->
                val start = k * 6 + random.nextInt(2)
                UiSemanticToken(start, start + 1 + random.nextInt(4), if (k % 2 == 0) "class" else "function")
            }
            var actual: List<UiSemanticToken> = expected
            repeat(25) { step ->
                val at = random.nextInt(docLength + 1)
                val removed = if (random.nextInt(3) == 0) random.nextInt(minOf(8, docLength - at) + 1) else 0
                val inserted = if (removed == 0 || random.nextBoolean()) random.nextInt(1, 4) else 0
                val edit = EditSpan(at, removed, inserted)
                docLength += inserted - removed
                expected = referenceShift(expected, edit, docLength)
                actual = shiftSemanticTokens(actual, edit, docLength)
                assertEquals(expected, actual.toList(), "run $run step $step: $edit")
                val viaAccessors = (actual as? ShiftedSemanticTokens)?.let { s ->
                    (0 until s.size).map { s.tokenAt(it).copy(startOffset = s.startAt(it), endOffset = s.endAt(it)) }
                } ?: actual
                assertEquals(expected, viaAccessors, "run $run step $step: accessors")
            }
        }
    }

    @Test
    fun aShiftedTokenThatEndsWhereItStartedReusesTheOriginal() {
        val once = shiftSemanticTokens(tokens, EditSpan(5, 0, 2), docLength = 32)
        val back = shiftSemanticTokens(once, EditSpan(5, 2, 0), docLength = 30)
        assertSame(tokens[1], back[1], "offsets back where the analysis put them read as the analysis' token")
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
