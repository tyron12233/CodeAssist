package dev.ide.ui.editor.core

import dev.ide.ui.editor.matchingBracket
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The leaf-wise rope scans visit exactly the chars, in exactly the order, of a plain indexed loop over the
 * same text, so the bracket helpers built on them answer the same on the rope as on a `String`.
 */
class RopeScanTest {

    /** A multi-leaf, edit-fragmented document and its text. */
    private fun fragmentedDoc(rnd: Random): EditorDocument {
        val alphabet = "ab (){}[]\n"
        var doc = EditorDocument.of(buildString { repeat(3000) { append(alphabet[rnd.nextInt(alphabet.length)]) } })
        repeat(200) {
            val at = rnd.nextInt(doc.length + 1)
            val end = (at + rnd.nextInt(4)).coerceAtMost(doc.length)
            doc = doc.replace(at, end, buildString { repeat(rnd.nextInt(3)) { append(alphabet[rnd.nextInt(alphabet.length)]) } })
        }
        return doc
    }

    @Test
    fun scansVisitTheSameCharsAsAnIndexedLoop() {
        val rnd = Random(3)
        val doc = fragmentedDoc(rnd)
        val text = doc.text
        repeat(300) {
            val a = rnd.nextInt(-5, text.length + 5)
            val b = rnd.nextInt(-5, text.length + 5)
            val stopAt = rnd.nextInt(text.length + 1)
            val fromRope = StringBuilder()
            val fromString = StringBuilder()
            val r1 = doc.chars.scanForward(a, b) { i, c -> fromRope.append(i).append(c); i == stopAt }
            val r2 = text.scanForward(a, b) { i, c -> fromString.append(i).append(c); i == stopAt }
            assertEquals(fromString.toString(), fromRope.toString())
            assertEquals(r2, r1)
            fromRope.clear(); fromString.clear()
            val r3 = doc.chars.scanBackward(a, b) { i, c -> fromRope.append(i).append(c); i == stopAt }
            val r4 = text.scanBackward(a, b) { i, c -> fromString.append(i).append(c); i == stopAt }
            assertEquals(fromString.toString(), fromRope.toString())
            assertEquals(r4, r3)
        }
    }

    @Test
    fun scansMatchAPlainLoopOverAString() {
        val text = "fun f() { g(1) }"
        val seen = StringBuilder()
        assertEquals(-1, text.scanForward(0, text.length) { _, c -> seen.append(c); false })
        assertEquals(text, seen.toString())
        assertEquals(text.indexOf('{'), text.scanBackward(text.length - 1, 0) { _, c -> c == '{' })
    }

    @Test
    fun matchingBracketAgreesBetweenRopeAndString() {
        val rnd = Random(5)
        val doc = fragmentedDoc(rnd)
        val text = doc.text
        repeat(500) {
            val caret = rnd.nextInt(text.length + 1)
            assertEquals(matchingBracket(text, caret), matchingBracket(doc.chars, caret), "caret $caret")
        }
    }
}
