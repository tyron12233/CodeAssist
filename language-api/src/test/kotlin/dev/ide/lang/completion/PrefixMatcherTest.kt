package dev.ide.lang.completion

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The graded [PrefixMatcher]: exact / prefix / camel-hump / substring grades, positions, and pushdown. */
class PrefixMatcherTest {

    @Test
    fun emptyPrefixMatchesEverything() {
        val m = PrefixMatcher("")
        assertTrue(m.matches("anything"))
        assertContentEquals(IntArray(0), m.matchPositions("anything"))
        assertEquals("", m.indexPrefix)
    }

    @Test
    fun gradesOrderFromExactToSubstring() {
        val m = PrefixMatcher("Text")
        assertEquals(PrefixMatcher.Grade.EXACT, m.grade("Text"))
        assertEquals(PrefixMatcher.Grade.PREFIX, m.grade("TextField"))
        assertEquals(PrefixMatcher.Grade.PREFIX_CI, m.grade("textView"))
        assertEquals(PrefixMatcher.Grade.SUBSTRING, m.grade("plainText"))
        assertNull(m.grade("Button"))
    }

    @Test
    fun camelHumpMatchesHumpStarts() {
        assertEquals(PrefixMatcher.Grade.HUMP, PrefixMatcher("mDL").grade("myDynamicList"))
        assertEquals(PrefixMatcher.Grade.HUMP, PrefixMatcher("NPE").grade("NullPointerException"))
        assertEquals(PrefixMatcher.Grade.HUMP, PrefixMatcher("nf").grade("newFile"))
        assertEquals(PrefixMatcher.Grade.HUMP, PrefixMatcher("lw").grade("layout_width"))
        // A hump-shaped query that IS a literal prefix stays at the better grade.
        assertEquals(PrefixMatcher.Grade.PREFIX, PrefixMatcher("buildStr").grade("buildString"))
    }

    @Test
    fun humpIsAnchoredAtTheFirstCharacter() {
        // A hump match must start at the name's first char; `DL` only middle-matches nothing here.
        assertNull(PrefixMatcher("DL").grade("myDynamicList"))
        assertNull(PrefixMatcher("PE").grade("NullPointerException"))
    }

    @Test
    fun humpBacktracksPastAFailedEarlyBinding() {
        // Greedy binds `b` to the first hump `B` and fails `c`; backtracking rebinds to the second `B`,
        // after which `c` continues the run.
        assertEquals(PrefixMatcher.Grade.HUMP, PrefixMatcher("abc").grade("aBxBc"))
    }

    @Test
    fun humpRunsContinueLowercase() {
        assertEquals(PrefixMatcher.Grade.HUMP, PrefixMatcher("myDyLi").grade("myDynamicList"))
        assertNull(PrefixMatcher("myxDL").grade("myDynamicList"))
    }

    @Test
    fun positionsFollowTheGrade() {
        assertContentEquals(intArrayOf(0, 1, 2), PrefixMatcher("tex").matchPositions("textView"))
        assertContentEquals(intArrayOf(0, 2, 9), PrefixMatcher("mDL").matchPositions("myDynamicList"))
        assertContentEquals(intArrayOf(5, 6, 7, 8), PrefixMatcher("Text").matchPositions("plainText"))
        assertNull(PrefixMatcher("zz").matchPositions("myDynamicList"))
    }

    @Test
    fun indexPushdownWidensOnlyForHumpQueries() {
        assertFalse(PrefixMatcher("buildstr").isHumpQuery)
        assertEquals("buildstr", PrefixMatcher("buildstr").indexPrefix)
        assertTrue(PrefixMatcher("mDL").isHumpQuery)
        assertEquals("m", PrefixMatcher("mDL").indexPrefix)
        assertTrue(PrefixMatcher("NPE").isHumpQuery)
        assertEquals("N", PrefixMatcher("NPE").indexPrefix)
        // An all-uppercase single char is a plain prefix, not a hump pattern.
        assertFalse(PrefixMatcher("N").isHumpQuery)
    }

    @Test
    fun indexPrefixesPushesDownTheFullPrefixForHumpQueries() {
        // A plain query pushes down only itself.
        assertEquals(listOf("listof"), PrefixMatcher("listof").indexPrefixes)
        // A hump query pushes down BOTH the full prefix (rescues a plain-prefix match like `listOf` from the
        // first-char query's result cap) AND the first character (covers hump matches that diverge after it).
        // The reported bug: typing `listOf` collapsed the query to `l`, whose 2000-hit cap dropped `listOf`.
        assertEquals(listOf("listOf", "l"), PrefixMatcher("listOf").indexPrefixes)
        assertEquals(listOf("mDL", "m"), PrefixMatcher("mDL").indexPrefixes)
    }

    @Test
    fun digitBoundariesAreHumpStarts() {
        assertEquals(PrefixMatcher.Grade.HUMP, PrefixMatcher("v2").grade("view2Model"))
    }

    @Test
    fun middleMatchingNeedsThreeTypedChars() {
        // A 1-2 char query middle-matches nearly everything — noise, not recall.
        assertNull(PrefixMatcher("T").grade("Button"))
        assertNull(PrefixMatcher("xt").grade("Text"))
        assertEquals(PrefixMatcher.Grade.SUBSTRING, PrefixMatcher("ext").grade("Text"))
    }
}
