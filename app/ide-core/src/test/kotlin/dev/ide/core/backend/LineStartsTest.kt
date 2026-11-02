package dev.ide.core.backend

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [LineStarts] gives the same (line, column) as scanning the text from its start, for every offset. */
class LineStartsTest {

    private fun naive(text: String, offset: Int): Pair<Int, Int> {
        val end = offset.coerceIn(0, text.length)
        var line = 0
        var col = 0
        for (i in 0 until end) if (text[i] == '\n') { line++; col = 0 } else col++
        return line to col
    }

    @Test
    fun matchesAScanFromTheStartForEveryOffset() {
        val rnd = Random(7)
        val samples = listOf("", "\n", "a", "a\n", "\n\nx", "abc\ndef\n\nghi") +
            List(30) { String(CharArray(rnd.nextInt(0, 200)) { if (rnd.nextInt(6) == 0) '\n' else 'x' }) }
        for (text in samples) {
            val lines = LineStarts(text)
            for (offset in -2..text.length + 2) {
                assertEquals(naive(text, offset), lines.line(offset) to lines.column(offset), "'$text' @ $offset")
            }
        }
    }
}
