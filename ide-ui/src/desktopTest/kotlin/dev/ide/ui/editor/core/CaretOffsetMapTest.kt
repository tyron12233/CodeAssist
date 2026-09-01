package dev.ide.ui.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [mapOffsetThroughEdits] — keeping the caret on its logical spot across a whole-buffer source transform
 * (Reformat / Optimize Imports). The regression it guards: a reformat edit that *spans* the caret (a dedent /
 * a normalized newline+indent run the caret sits inside) used to drag the caret to the beginning of the line
 * on save, because the old loop subtracted the whole edit's delta whenever the edit merely *started* before
 * the caret.
 */
class CaretOffsetMapTest {

    private fun edit(start: Int, end: Int, text: String) = RangeEdit(start, end, text, start + text.length)

    @Test
    fun editEntirelyBeforeCaretShiftsByDelta() {
        // Delete 2 chars in [0,4)->"  " before a caret at 10 => caret moves left by 2.
        assertEquals(8, mapOffsetThroughEdits(10, listOf(edit(0, 4, "  "))))
        // Insert 3 chars at offset 2 before a caret at 10 => caret moves right by 3.
        assertEquals(13, mapOffsetThroughEdits(10, listOf(edit(2, 2, "XYZ"))))
    }

    @Test
    fun editAtOrAfterCaretLeavesItUntouched() {
        assertEquals(5, mapOffsetThroughEdits(5, listOf(edit(20, 24, "xx"))))
        assertEquals(5, mapOffsetThroughEdits(5, listOf(edit(5, 9, "zzzz")))) // starts exactly at the caret
    }

    @Test
    fun dedentSpanningTheCaretDoesNotCollapseToLineStart() {
        // "if (a) {\n        d..." — offset 8 is the '\n', 9..16 are eight indent spaces, 17 is 'd'.
        // The formatter rewrites the newline+8-space run [8,17) to newline+4-space "\n    " (net -4).
        // Caret at 12 sits *inside* that run (the 4th indent space).
        val edits = listOf(edit(8, 17, "\n    "))
        // Correct: the caret keeps its depth into the indent, clamped to the new (shorter) run -> offset 12,
        // still in the indentation. The OLD math produced 8 (the '\n' — i.e. the line start).
        assertEquals(12, mapOffsetThroughEdits(12, edits))
    }

    @Test
    fun caretPastAShrunkSpanClampsToTheReplacementEnd() {
        // Replace [10,20) (10 chars) with "ab" (2 chars); caret at 18 was deep inside the removed run.
        // It clamps to the end of the replacement: 10 + min(8, 2) = 12.
        assertEquals(12, mapOffsetThroughEdits(18, listOf(edit(10, 20, "ab"))))
    }

    @Test
    fun caretInsideAGrownSpanKeepsItsRelativePosition() {
        // Replace [10,14) with a longer "wwwwwwww"; caret at 12 (2 into the old span) stays 2 into the new one.
        assertEquals(12, mapOffsetThroughEdits(12, listOf(edit(10, 14, "wwwwwwww"))))
    }

    @Test
    fun multipleEditsAccumulateAndOrderDoesNotMatter() {
        // Two deletions before the caret (net -4) plus one edit after it (no effect).
        val edits = listOf(edit(0, 3, "x"), edit(5, 8, "y"), edit(40, 44, "zz"))
        assertEquals(26, mapOffsetThroughEdits(30, edits))
        assertEquals(26, mapOffsetThroughEdits(30, edits.reversed()), "order-independent")
    }

    @Test
    fun neverReturnsNegative() {
        assertEquals(0, mapOffsetThroughEdits(2, listOf(edit(0, 10, ""))))
    }
}
