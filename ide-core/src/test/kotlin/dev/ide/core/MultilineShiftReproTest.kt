package dev.ide.core

import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.shiftDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals

/** Editing an early line must re-map a diagnostic on a far line: the squiggle stays on the same content,
 *  and its line tracks any added/removed newlines. */
class MultilineShiftReproTest {

    private val text = "package app;\nclass T {\n  void m() {}\n  int x;\n  String s = 5;\n}"

    private fun diagOnLine5(): UiDiagnostic {
        val start = text.indexOf("5")
        return UiDiagnostic(UiSeverity.Error, line = 5, col = 1, message = "type mismatch", startOffset = start, endOffset = start + 1)
    }

    /** Shift the line-5 diagnostic through [new] and assert it still underlines "5" on [expectedLine]. */
    private fun check(new: String, expectedLine: Int) {
        val shifted = shiftDiagnostics(listOf(diagOnLine5()), text, new).single()
        assertEquals("5", new.substring(shifted.startOffset, shifted.endOffset), "must still underline \"5\"")
        assertEquals(expectedLine, shifted.line, "diagnostic line should track newline changes")
        assertEquals(new.take(shifted.startOffset).count { it == '\n' } + 1, shifted.line, "line must match offset")
    }

    @Test fun insertCharOnLine2() = check(text.replace("class T {", "class TT {"), expectedLine = 5)
    @Test fun insertNewlineOnLine2() = check(text.replace("class T {", "class T \n{"), expectedLine = 6)
    @Test fun deleteCharOnLine2() = check(text.replace("class T {", "class {"), expectedLine = 5)
    @Test fun deleteWholeLine2() = check(text.replace("class T {\n", ""), expectedLine = 4)
    @Test fun insertOnLine1() = check(text.replace("package app;", "package appx;"), expectedLine = 5)
}
