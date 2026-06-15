package dev.ide.ui.editor.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The line-index splice in [EditorDocument]: incremental [EditorDocument.replace] must always equal a fresh re-split. */
class EditorDocumentTest {

    private fun assertMatchesFresh(doc: EditorDocument) {
        val fresh = EditorDocument.of(doc.text)
        assertEquals(fresh.lineCount, doc.lineCount, "lineCount for ${doc.text.replace("\n", "\\n")}")
        for (i in 0 until doc.lineCount) {
            assertEquals(fresh.lineStart(i), doc.lineStart(i), "lineStart($i)")
            assertEquals(fresh.lineEnd(i), doc.lineEnd(i), "lineEnd($i)")
            assertEquals(fresh.lineText(i), doc.lineText(i), "lineText($i)")
        }
    }

    @Test
    fun emptyAndSingleLine() {
        val empty = EditorDocument.of("")
        assertEquals(1, empty.lineCount)
        assertEquals(0, empty.lineStart(0))
        assertEquals(0, empty.lineEnd(0))

        val one = EditorDocument.of("hello")
        assertEquals(1, one.lineCount)
        assertEquals("hello", one.lineText(0))
    }

    @Test
    fun insertSingleCharKeepsIndex() {
        var doc = EditorDocument.of("ab\ncd\nef")
        doc = doc.replace(4, 4, "X") // inside "cd"
        assertEquals("ab\ncXd\nef", doc.text)
        assertMatchesFresh(doc)
    }

    @Test
    fun insertNewlineSplitsLine() {
        var doc = EditorDocument.of("ab\ncd")
        doc = doc.replace(4, 4, "\n")
        assertEquals("ab\nc\nd", doc.text)
        assertEquals(3, doc.lineCount)
        assertMatchesFresh(doc)
    }

    @Test
    fun deleteAcrossLinesMerges() {
        var doc = EditorDocument.of("one\ntwo\nthree")
        doc = doc.replace(2, 9, "") // "e\ntwo\nt" gone
        assertEquals("onhree", doc.text)
        assertEquals(1, doc.lineCount)
        assertMatchesFresh(doc)
    }

    @Test
    fun replaceMultiLineWithMultiLine() {
        var doc = EditorDocument.of("a\nb\nc\nd")
        doc = doc.replace(2, 5, "X\nY\nZ")
        assertEquals("a\nX\nY\nZ\nd", doc.text)
        assertMatchesFresh(doc)
    }

    @Test
    fun lineForOffsetBoundaries() {
        val doc = EditorDocument.of("ab\ncd\n")
        assertEquals(0, doc.lineForOffset(0))
        assertEquals(0, doc.lineForOffset(2)) // on the '\n' itself → still line 0
        assertEquals(1, doc.lineForOffset(3))
        assertEquals(1, doc.lineForOffset(5))
        assertEquals(2, doc.lineForOffset(6)) // the trailing empty line
        assertEquals(3, doc.lineCount)
    }

    @Test
    fun fuzzAgainstNaiveResplit() {
        val rnd = Random(42)
        val alphabet = "ab\nc\n d{}\"x"
        var doc = EditorDocument.of("fun main() {\n    val x = 1\n}\n")
        repeat(2000) {
            val len = doc.text.length
            val start = rnd.nextInt(len + 1)
            val end = (start + rnd.nextInt(4)).coerceAtMost(len)
            val ins = buildString { repeat(rnd.nextInt(5)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            doc = doc.replace(start, end, ins)
            val expected = StringBuilder(doc.text)
            assertEquals(expected.toString(), doc.text)
            assertMatchesFresh(doc)
        }
    }

    @Test
    fun offsetPositionRoundTrip() {
        val doc = EditorDocument.of("alpha\nbeta\n\ngamma")
        for (off in 0..doc.length) {
            val line = doc.lineForOffset(off)
            val col = off - doc.lineStart(line)
            if (off <= doc.lineEnd(line)) {
                assertEquals(off, doc.offsetOf(line, col), "round-trip at $off")
            }
        }
    }
}
