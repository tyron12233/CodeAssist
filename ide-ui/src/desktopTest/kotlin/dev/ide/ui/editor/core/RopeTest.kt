package dev.ide.ui.editor.core

import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correctness of the persistent [Rope]: every operation must agree with the equivalent `String`/`StringBuilder`
 * operation, and the tree must stay height-balanced (so reads/edits stay O(log N)) no matter the edit history.
 */
class RopeTest {

    @Test
    fun emptyAndRoundTrip() {
        assertEquals("", Rope.EMPTY.toString())
        assertEquals(0, Rope.EMPTY.length)
        val s = "hello world\nsecond line\n"
        assertEquals(s, Rope.of(s).toString())
        assertEquals(s.length, Rope.of(s).length)
    }

    @Test
    fun indexedAccessMatchesString() {
        val s = (0 until 5000).joinToString("") { ('a' + (it % 26)) + if (it % 40 == 0) "\n" else "" }
        val rope = Rope.of(s)
        for (i in s.indices) assertEquals(s[i], rope[i], "char at $i")
    }

    @Test
    fun substringMatchesString() {
        val s = "package com.example;\nclass Foo { int x = 42; }\n".repeat(50)
        val rope = Rope.of(s)
        val rnd = Random(1)
        repeat(500) {
            val a = rnd.nextInt(s.length + 1)
            val b = rnd.nextInt(a, s.length + 1)
            assertEquals(s.substring(a, b), rope.substring(a, b), "substring($a, $b)")
            assertEquals(s.subSequence(a, b).toString(), rope.subSequence(a, b).toString(), "subSequence($a, $b)")
        }
    }

    @Test
    fun insertDeleteReplace() {
        var rope = Rope.of("abcdef")
        rope = rope.replace(3, 3, "XYZ")          // insert
        assertEquals("abcXYZdef", rope.toString())
        rope = rope.replace(0, 3, "")             // delete a prefix
        assertEquals("XYZdef", rope.toString())
        rope = rope.replace(1, 4, "..")           // replace a middle range
        assertEquals("X..ef", rope.toString())
    }

    /** Random edits on a rope must always equal the same edits applied to a StringBuilder. */
    @Test
    fun fuzzAgainstStringBuilder() {
        val rnd = Random(99)
        val alphabet = "abc \n{}\"xyz;"
        val reference = StringBuilder("class Main {\n    public static void main(String[] a) {\n    }\n}\n")
        var rope = Rope.of(reference.toString())
        repeat(4000) {
            val len = reference.length
            val start = rnd.nextInt(len + 1)
            val end = (start + rnd.nextInt(0, 6)).coerceAtMost(len)
            val ins = buildString { repeat(rnd.nextInt(0, 6)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }

            reference.replace(start, end, ins)
            rope = rope.replace(start, end, ins)

            // cheap invariants every iteration; full equality + balance periodically (toString is O(n))
            assertEquals(reference.length, rope.length)
            if (it % 50 == 0) {
                assertEquals(reference.toString(), rope.toString(), "after edit #$it")
                assertBalanced(rope)
                if (rope.length > 0) {
                    val probe = rnd.nextInt(rope.length)
                    assertEquals(reference[probe], rope[probe], "char[$probe] after #$it")
                }
            }
        }
        assertEquals(reference.toString(), rope.toString())
    }

    /** Appending one char at a time (the typing pattern) must stay balanced and correct. */
    @Test
    fun sequentialAppendStaysBalanced() {
        var rope: Rope = Rope.EMPTY
        val sb = StringBuilder()
        repeat(20_000) {
            val c = ('a' + (it % 26))
            rope = rope.replace(rope.length, rope.length, c.toString())
            sb.append(c)
            if (it % 1000 == 0) assertBalanced(rope)
        }
        assertEquals(sb.toString(), rope.toString())
        assertBalanced(rope)
    }

    @Test
    fun largeDocumentEditsStayShallow() {
        val s = "0123456789".repeat(200_000) // 2,000,000 chars
        var rope = Rope.of(s)
        assertBalanced(rope)
        val rnd = Random(7)
        repeat(2000) {
            val p = rnd.nextInt(rope.length)
            rope = rope.replace(p, p, "Z")
        }
        assertBalanced(rope)
    }

    /**
     * The Fibonacci-balance invariant guarantees depth ≤ 1.44·log₂(length) — every rope satisfies
     * `length ≥ F(depth + 2)`. Assert that O(log N) ceiling (with a little slack for a just-joined node).
     */
    private fun assertBalanced(rope: Rope) {
        val log2Len = ln(rope.length.coerceAtLeast(2).toDouble()) / ln(2.0)
        val bound = (1.44 * log2Len + 2).toInt()
        assertTrue(rope.depth <= bound, "depth ${rope.depth} exceeds O(log N) bound $bound for length ${rope.length}")
    }
}
