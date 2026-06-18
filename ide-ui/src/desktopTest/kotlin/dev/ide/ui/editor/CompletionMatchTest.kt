package dev.ide.ui.editor

import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The provider-neutral completion matcher: prefix + fuzzy matching, match positions, and the re-tiering. */
class CompletionMatchTest {

    private fun item(label: String, sort: Int = 0) =
        UiCompletionItem(label = label, insertText = label, detail = null, kind = UiCompletionKind.Method, sortPriority = sort)

    private fun session(vararg labels: String) =
        CompletionSession.from(UiCompletionResult(labels.map { item(it) }, replaceStart = 0, replaceEnd = 0))

    @Test
    fun emptyQueryMatchesEverythingWithNoHighlight() {
        assertContentEquals(intArrayOf(), matchPositions("newFile", ""))
    }

    @Test
    fun caseInsensitivePrefixMatchesLeadingRun() {
        assertContentEquals(intArrayOf(0, 1, 2), matchPositions("newFile", "new"))
        assertContentEquals(intArrayOf(0, 1, 2), matchPositions("NewFile", "new"))
    }

    @Test
    fun camelHumpSubsequenceMatchesHumpPositions() {
        // n -> index 0, F -> index 3
        assertContentEquals(intArrayOf(0, 3), matchPositions("newFile", "nf"))
    }

    @Test
    fun subsequenceAcrossSeparatorsMatches() {
        // layout_w inside android:layout_width is a contiguous run starting at index 8
        val pos = matchPositions("android:layout_width", "layout_w")
        assertContentEquals((8..15).toList().toIntArray(), pos)
    }

    @Test
    fun nonMatchReturnsNull() {
        assertNull(matchPositions("newFile", "xyz"))
        assertNull(matchPositions("abc", "abcd")) // query longer than any subsequence
    }

    @Test
    fun fuzzyMatchesMirrorsPositions() {
        assertTrue(fuzzyMatches("newFile", "nf"))
        assertFalse(fuzzyMatches("newFile", "zzz"))
    }

    @Test
    fun filterDropsNonMatchesAndFloatsPrefixMatchesFirst() {
        // Both "format" (prefix) and "newFile" (fuzzy nf-ish via subsequence) match "f"-ish queries; for a
        // real prefix query the prefix match must outrank the scattered fuzzy hit even though it came later.
        val s = session("newFile", "format", "unrelated")
        val out = s.filtered("for")
        assertEquals(listOf("format"), out.map { it.label }) // only "format" contains the subsequence f-o-r in order... newFile has f-..-no 'o' after 'F'
    }

    @Test
    fun prefixTierBeatsFuzzyTierStably() {
        // "fb" matches "fooBar" (prefix-ish? no) and "fooBar" via f..B; "fb" is a camel match for fooBar.
        // "fb" is a real prefix of "fbCount". The prefix match should come first.
        val s = session("fooBar", "fbCount")
        val out = s.filtered("fb")
        assertEquals(listOf("fbCount", "fooBar"), out.map { it.label })
    }

    @Test
    fun matchTierRanksExactThenPrefixThenFuzzy() {
        assertEquals(0, matchTier("Text", "Text"))       // exact (case-sensitive)
        assertEquals(1, matchTier("text", "Text"))       // exact, case-insensitive
        assertEquals(2, matchTier("fooBar", "foo"))      // case-sensitive prefix
        assertEquals(3, matchTier("FooBar", "foo"))      // case-insensitive prefix
        assertEquals(4, matchTier("fooBar", "fb"))       // fuzzy
    }

    @Test
    fun filterFloatsExactMatchAboveLongerPrefixMatch() {
        // Typing the whole word: the exact "Text" must outrank "TextField"/"TextView" even when the backend
        // returned them first.
        val s = session("TextField", "TextView", "Text")
        val out = s.filtered("Text")
        assertEquals("Text", out.first().label)
        assertEquals(listOf("Text", "TextField", "TextView"), out.map { it.label })
    }
}
