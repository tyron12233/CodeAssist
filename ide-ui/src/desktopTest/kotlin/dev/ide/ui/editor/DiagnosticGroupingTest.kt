package dev.ide.ui.editor

import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [diagnosticsByStartLine], the group a diagnostic chip stands for: everything starting on one line,
 * most severe first, so the chip shows the loudest message, badges the whole group, and the sheet can list the
 * rest, including the ones stacked on the very same span and the Info/Hints that never get a chip.
 */
class DiagnosticGroupingTest {

    // A 3-line buffer of 10 characters per line: line = offset / 10.
    private val lineOf = { off: Int -> off / 10 }

    private fun diag(severity: UiSeverity, start: Int, end: Int = start + 3, message: String = "m$start") =
        UiDiagnostic(severity, lineOf(start) + 1, start % 10 + 1, message, start, end)

    @Test
    fun groupsByStartLineAndOrdersBySeverity() {
        val warn = diag(UiSeverity.Warning, 2)
        val err = diag(UiSeverity.Error, 6)
        val other = diag(UiSeverity.Error, 14)
        val byLine = diagnosticsByStartLine(listOf(warn, err, other), lineOf)

        assertEquals(setOf(0, 1), byLine.keys)
        assertEquals(listOf(err, warn), byLine[0], "the more severe diagnostic leads its line's group")
        assertEquals(listOf(other), byLine[1])
    }

    @Test
    fun sameSeverityKeepsPositionOrder() {
        val late = diag(UiSeverity.Error, 7)
        val early = diag(UiSeverity.Error, 1)
        assertEquals(listOf(early, late), diagnosticsByStartLine(listOf(late, early), lineOf)[0])
    }

    @Test
    fun sameSpanDiagnosticsShareOneGroup() {
        val hint = diag(UiSeverity.Hint, 4, 8, "could be val")
        val warn = diag(UiSeverity.Warning, 4, 8, "never used")
        val group = diagnosticsByStartLine(listOf(hint, warn), lineOf).getValue(0)

        assertEquals(listOf(warn, hint), group, "one squiggle, one chip, but both are in the group")
        assertEquals(1, group.map { it.startOffset to it.endOffset }.distinct().size)
    }

    @Test
    fun aMultiLineSpanCountsOnlyOnItsStartLine() {
        val spanning = diag(UiSeverity.Error, 3, end = 27)
        val byLine = diagnosticsByStartLine(listOf(spanning), lineOf)
        assertEquals(setOf(0), byLine.keys)
    }

    @Test
    fun emptyInEmptyOut() {
        assertEquals(emptyMap(), diagnosticsByStartLine(emptyList(), lineOf))
    }
}
