package dev.codeassist.csharp.editor.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class CsLineOffsetsTest {

    private val text = "using System;\nint x = 5;\n\nConsole.WriteLine(x);"
    private val offsets = CsLineOffsets(text)

    // Line starts: 1 -> 0, 2 -> 14, 3 -> 25, 4 -> 26.

    @Test
    fun `the first column of a line is its start`() {
        assertEquals(0, offsets.offsetOf(1, 1))
        assertEquals(14, offsets.offsetOf(2, 1))
        assertEquals(26, offsets.offsetOf(4, 1))
    }

    @Test
    fun `a column is counted from one`() {
        assertEquals(14 + 4, offsets.offsetOf(2, 5))
    }

    @Test
    fun `a column past the end of the line is clamped onto the line`() {
        // Line 2 is "int x = 5;" with its newline at offset 24.
        assertEquals(24, offsets.offsetOf(2, 200))
    }

    @Test
    fun `an empty line has only its start`() {
        assertEquals(25, offsets.offsetOf(3, 1))
        assertEquals(25, offsets.offsetOf(3, 9))
    }

    @Test
    fun `a line past the end falls on the last line`() {
        assertEquals(text.length, offsets.offsetOf(99, 999))
        assertEquals(26, offsets.offsetOf(99, 1))
    }

    @Test
    fun `line zero and negative columns are clamped`() {
        assertEquals(0, offsets.offsetOf(0, 0))
        assertEquals(0, offsets.offsetOf(-3, -3))
    }

    @Test
    fun `a text with no newline is one line`() {
        val single = CsLineOffsets("abc")
        assertEquals(1, single.offsetOf(1, 2))
        assertEquals(3, single.offsetOf(5, 5))
    }

    @Test
    fun `an empty text has offset zero`() {
        assertEquals(0, CsLineOffsets("").offsetOf(1, 1))
    }

    @Test
    fun `a token is the whole identifier at the position`() {
        val range = offsets.tokenAt(2, 5) // the `x` in `int x = 5;`
        assertEquals(18, range.start)
        assertEquals(19, range.end)
        val word = offsets.tokenAt(4, 1) // `Console`
        assertEquals(26, word.start)
        assertEquals(33, word.end)
    }

    @Test
    fun `a token starts at the given column even in the middle of a word`() {
        val range = offsets.tokenAt(4, 4) // `sole`
        assertEquals(29, range.start)
        assertEquals(33, range.end)
    }

    @Test
    fun `an underscore and digits belong to a token`() {
        val range = CsLineOffsets("my_var2 = 1;").tokenAt(1, 1)
        assertEquals(0, range.start)
        assertEquals(7, range.end)
    }

    @Test
    fun `where there is no identifier the token is one character`() {
        val range = offsets.tokenAt(2, 6) // the space after `x`
        assertEquals(19, range.start)
        assertEquals(20, range.end)
    }

    @Test
    fun `a token at the end of the text does not run past it`() {
        val range = CsLineOffsets("a;").tokenAt(1, 3)
        assertEquals(2, range.start)
        assertEquals(2, range.end)
    }
}
