package dev.ide.ui.editor

import dev.ide.ui.editor.core.RangeEdit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the caret lands after a code action's edits ([caretAfterEdits]). */
class CaretAfterEditsTest {

    private fun edit(start: Int, end: Int, text: String) = RangeEdit(start, end, text, start + text.length)

    @Test
    fun anEditBeforeTheCaretShiftsIt() {
        assertEquals(15, caretAfterEdits(10, listOf(edit(0, 0, "abcde"))))
        assertEquals(7, caretAfterEdits(10, listOf(edit(2, 5, ""))))
    }

    @Test
    fun anEditAfterTheCaretLeavesItAlone() {
        assertEquals(10, caretAfterEdits(10, listOf(edit(20, 25, ""))))
    }

    @Test
    fun anEditCoveringTheCaretPutsItAtTheEndOfTheReplacement() {
        // A fix deleting the line [30, 50) the caret sits on: the caret lands where the line was, not 20 back.
        assertEquals(30, caretAfterEdits(40, listOf(edit(30, 50, ""))))
        assertEquals(33, caretAfterEdits(40, listOf(edit(30, 50, "val"))))
        // ...after an earlier edit's shift.
        assertEquals(32, caretAfterEdits(40, listOf(edit(0, 0, "xy"), edit(30, 50, ""))))
    }
}
