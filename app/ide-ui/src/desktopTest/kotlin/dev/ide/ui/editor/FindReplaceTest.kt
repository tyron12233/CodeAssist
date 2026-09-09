package dev.ide.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [findMatches]/[matchIndexFrom] semantics: case, whole-word, regex, non-overlap, find-from-caret. */
class FindReplaceTest {

    @Test
    fun caseInsensitiveByDefault() {
        val m = findMatches("Foo foo FOO", "foo", FindOptions())
        assertEquals(listOf(Match(0, 3), Match(4, 7), Match(8, 11)), m)
    }

    @Test
    fun caseSensitiveWhenAsked() {
        val m = findMatches("Foo foo FOO", "foo", FindOptions(caseSensitive = true))
        assertEquals(listOf(Match(4, 7)), m)
    }

    @Test
    fun nonOverlapping() {
        // "aa" in "aaaa" → matches at 0 and 2, not 1 (editors don't overlap)
        assertEquals(listOf(Match(0, 2), Match(2, 4)), findMatches("aaaa", "aa", FindOptions()))
    }

    @Test
    fun wholeWordRespectsBoundaries() {
        val text = "in int print in"
        val m = findMatches(text, "in", FindOptions(wholeWord = true))
        // "in" (0..2) and the trailing "in" (13..15); not the "in" inside "int" or "print"
        assertEquals(listOf(Match(0, 2), Match(13, 15)), m)
    }

    @Test
    fun regexMatches() {
        val m = findMatches("a1 b22 c333", "[a-z][0-9]+", FindOptions(regex = true))
        assertEquals(listOf(Match(0, 2), Match(3, 6), Match(7, 11)), m)
    }

    @Test
    fun invalidRegexYieldsNoMatches() {
        assertTrue(findMatches("abc", "(", FindOptions(regex = true)).isEmpty())
    }

    @Test
    fun emptyQueryYieldsNoMatches() {
        assertTrue(findMatches("abc", "", FindOptions()).isEmpty())
    }

    @Test
    fun zeroWidthRegexIsSkipped() {
        // "a*" can match empty; we must not emit zero-width matches (they'd be unselectable / stall navigation)
        val m = findMatches("baab", "a*", FindOptions(regex = true))
        assertTrue(m.all { it.end > it.start })
        assertEquals(listOf(Match(1, 3)), m)
    }

    @Test
    fun findFromCaretWraps() {
        val matches = listOf(Match(2, 4), Match(10, 12), Match(20, 22))
        assertEquals(1, matchIndexFrom(matches, caret = 5))   // first at/after 5 → index 1
        assertEquals(0, matchIndexFrom(matches, caret = 25))  // past the last → wrap to 0
        assertEquals(0, matchIndexFrom(matches, caret = 0))
        assertEquals(-1, matchIndexFrom(emptyList(), caret = 0))
    }
}
