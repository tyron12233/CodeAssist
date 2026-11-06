package dev.ide.lsp.client

import dev.ide.lang.dom.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class LineIndexTest {
    @Test
    fun positionsRoundTripAcrossEveryLineTerminator() {
        val text = "ab\ncd\r\nef\rgh"
        val lines = LineIndex(text)
        assertEquals(4, lines.lineCount)
        for (offset in listOf(0, 2, 3, 5, 7, 9, 10, 12)) {
            assertEquals(offset, lines.toOffset(lines.position(offset)), "offset $offset")
        }
        assertEquals(7, lines.offset(2, 0))
        assertEquals(5, lines.lineEnd(1)) // before the \r\n
        assertEquals(10, lines.offset(3, 0))
    }

    @Test
    fun aPositionPastTheLineIsItsEnd() {
        val lines = LineIndex("abc\ndef")
        assertEquals(3, lines.offset(0, 99))
        assertEquals(7, lines.offset(5, 0))
        assertEquals(0, lines.offset(-1, 4))
    }

    @Test
    fun charactersAreUtf16CodeUnits() {
        // An emoji is two UTF-16 units, which is what LSP's default position encoding counts.
        val text = "a😀b"
        val lines = LineIndex(text)
        assertEquals(3, lines.offset(0, 3))
        assertEquals(TextRange(3, 4), lines.toRange(lines.range(TextRange(3, 4))))
    }
}
