package dev.ide.core

import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.shiftDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals

/** [shiftDiagnostics] keeps squiggles aligned to the buffer as the user edits, before re-analysis lands. */
class DiagnosticShiftTest {

    private fun diag(start: Int, end: Int, text: String) =
        UiDiagnostic(UiSeverity.Error, line = text.take(start).count { it == '\n' } + 1, col = 1, message = "x", startOffset = start, endOffset = end)

    @Test
    fun insertBeforeDiagnosticShiftsItRight() {
        val old = "ab cd"
        val new = "abXX cd"            // insert 2 chars at offset 2 (before "cd" diagnostic at 3..5)
        val d = diag(3, 5, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(5 to 7, shifted.startOffset to shifted.endOffset)
    }

    @Test
    fun insertInsideDiagnosticExtendsIt() {
        val old = "foo"
        val new = "fXoo"              // insert 1 char at offset 1, inside diagnostic 0..3
        val d = diag(0, 3, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(0 to 4, shifted.startOffset to shifted.endOffset, "edit inside should extend the end")
    }

    @Test
    fun insertAfterDiagnosticLeavesItUnchanged() {
        val old = "ab cd"
        val new = "ab cdYY"           // append after the "ab" diagnostic at 0..2
        val d = diag(0, 2, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(0 to 2, shifted.startOffset to shifted.endOffset)
    }

    @Test
    fun editAfterDiagnosticInTheMiddleLeavesItUnchanged() {
        val old = "alpha; beta;"
        val new = "alpha; beXta;"        // insert deep in "beta", well after the "alpha" diagnostic at 0..5
        val d = diag(0, 5, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(0 to 5, shifted.startOffset to shifted.endOffset, "a diagnostic before the edit must not move")
    }

    @Test
    fun deletionAfterDiagnosticLeavesItUnchanged() {
        val old = "alpha; beta;"
        val new = "alpha; bta;"          // delete 'e' in "beta", after the "alpha" diagnostic at 0..5
        val d = diag(0, 5, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(0 to 5, shifted.startOffset to shifted.endOffset)
    }

    @Test
    fun insertExactlyAtDiagnosticEndDoesNotExtendOrShiftIt() {
        val old = "foo bar"
        val new = "fooX bar"             // insert at offset 3, exactly where the "foo" diagnostic 0..3 ends
        val d = diag(0, 3, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(0 to 3, shifted.startOffset to shifted.endOffset, "inserted text at the end boundary is outside the diagnostic")
    }

    @Test
    fun deletionInsideDiagnosticShrinksIt() {
        val old = "abcdef"
        val new = "abef"              // delete 2 chars at offset 2, diagnostic 0..6
        val d = diag(0, 6, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(0 to 4, shifted.startOffset to shifted.endOffset)
    }

    @Test
    fun deletingTheWholeDiagnosticDropsIt() {
        val old = "abcdef"
        val new = "af"                // delete "bcde" (offsets 1..5), diagnostic was 1..5
        val d = diag(1, 5, old)
        assertEquals(emptyList(), shiftDiagnostics(listOf(d), old, new))
    }

    @Test
    fun lineIsRecomputedAfterNewlineInsert() {
        val old = "a;b"
        val new = "a;\nb"             // insert a newline at offset 2; diagnostic on "b" (was 2..3)
        val d = diag(2, 3, old)
        val shifted = shiftDiagnostics(listOf(d), old, new).single()
        assertEquals(3 to 4, shifted.startOffset to shifted.endOffset)
        assertEquals(2, shifted.line, "b moved to line 2")
    }
}
