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

    private fun item(label: String, sort: Int = 0, kind: UiCompletionKind = UiCompletionKind.Method) =
        UiCompletionItem(label = label, insertText = label, detail = null, kind = kind, sortPriority = sort)

    private fun session(vararg labels: String) =
        CompletionSession.from(UiCompletionResult(labels.map { item(it) }, replaceStart = 0, replaceEnd = 0))

    private fun sessionOf(vararg items: UiCompletionItem) =
        CompletionSession.from(UiCompletionResult(items.toList(), replaceStart = 0, replaceEnd = 0))

    private fun sessionAt(tokenStart: Int, incomplete: Boolean = false, mayFilter: Boolean = true) =
        CompletionSession.from(UiCompletionResult(
            listOf(item("bar")), replaceStart = tokenStart, replaceEnd = tokenStart,
            mayFilterLocally = mayFilter, isIncomplete = incomplete,
        ))

    // --- canNarrowLocally: the gate that lets the editor skip a backend re-query while narrowing client-side ---

    @Test fun narrowsLocallyWhenCompleteSessionStillCoversTheToken() {
        // popup live, "foo.bar" with the token at 4, caret after "bar" → narrow the cached set, no re-query
        assertTrue(canNarrowLocally(sessionAt(4), dismissed = false, text = "foo.bar", caret = 7))
    }

    @Test fun requeriesWhenSessionIsIncomplete() {
        // truncated set → extending the prefix could surface dropped candidates → must re-query
        assertFalse(canNarrowLocally(sessionAt(4, incomplete = true), dismissed = false, text = "foo.bar", caret = 7))
    }

    @Test fun requeriesWhenProviderOptedOutOfLocalFiltering() {
        assertFalse(canNarrowLocally(sessionAt(4, mayFilter = false), dismissed = false, text = "foo.bar", caret = 7))
    }

    @Test fun requeriesWhenPopupDismissedOrNoSession() {
        assertFalse(canNarrowLocally(sessionAt(4), dismissed = true, text = "foo.bar", caret = 7))
        assertFalse(canNarrowLocally(null, dismissed = false, text = "foo.bar", caret = 7))
    }

    @Test fun requeriesWhenCaretLeftTheToken() {
        assertFalse(canNarrowLocally(sessionAt(4), dismissed = false, text = "foo.b r", caret = 7)) // non-ident in span
        assertFalse(canNarrowLocally(sessionAt(4), dismissed = false, text = "foo.bar", caret = 3)) // caret before tokenStart
    }

    // --- completionKeystroke: which typed char opens / extends / closes the popup ---

    @Test fun memberAccessDotReopens() {
        assertEquals(CompletionKeystroke.Reopen, completionKeystroke('.', wordExtra = ""))
    }

    @Test fun annotationAtSignReopensInKotlinJava() {
        // The reported bug: typing `@` must open the annotation-name popup (Kotlin/Java have no word extras).
        assertEquals(CompletionKeystroke.Reopen, completionKeystroke('@', wordExtra = ""))
    }

    @Test fun atSignExtendsInXmlWhereItIsAResourceRefWordChar() {
        // In XML `@` is a word char (`@string/…`), so it extends an in-flight session rather than reopening.
        assertEquals(CompletionKeystroke.Extend, completionKeystroke('@', wordExtra = ":@?+/.-|"))
    }

    @Test fun identifierCharExtends() {
        assertEquals(CompletionKeystroke.Extend, completionKeystroke('a', wordExtra = ""))
        assertEquals(CompletionKeystroke.Extend, completionKeystroke('_', wordExtra = ""))
    }

    @Test fun tokenEndingCharDismisses() {
        assertEquals(CompletionKeystroke.Dismiss, completionKeystroke(' ', wordExtra = ""))
        assertEquals(CompletionKeystroke.Dismiss, completionKeystroke('(', wordExtra = ""))
        assertEquals(CompletionKeystroke.Dismiss, completionKeystroke(null, wordExtra = ""))
    }

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

    @Test
    fun bufferWordNeverOutranksRealSymbols() {
        // The reported bug: typing `Text`, a buffer word `text` (case-insensitive-exact, tier 1) floated above
        // the real, prefix-matching symbols (`TextField`, tier 2). A word must rank below every real completion
        // regardless of how closely it matches.
        val out = sessionOf(
            item("TextField(value: String)", kind = UiCompletionKind.Method),
            item("TextButton(onClick: () -> Unit)", kind = UiCompletionKind.Method),
            item("text", sort = 1000, kind = UiCompletionKind.Word),
        ).filtered("Text")
        assertEquals(UiCompletionKind.Word, out.last().kind, "the buffer word must sort last; got ${out.map { it.label to it.kind }}")
        assertTrue(out.indexOfFirst { it.kind == UiCompletionKind.Word } == out.lastIndex, "all real symbols precede the word")
    }

    @Test
    fun exactSymbolBeatsWordAndOtherSymbols() {
        // An exact (case-sensitive) symbol match goes to the very top; a buffer word with the same text sinks
        // below the real symbols. Satisfies "exact matches first" + "rank words low" together.
        val out = sessionOf(
            item("TextField(value: String)", kind = UiCompletionKind.Method),
            item("Text(text: String)", kind = UiCompletionKind.Method),
            item("Text", sort = 1000, kind = UiCompletionKind.Word),
        ).filtered("Text")
        assertEquals("Text(text: String)", out.first().label, "exact-name symbol first; got ${out.map { it.label }}")
        assertEquals(UiCompletionKind.Word, out.last().kind, "the word ranks last even though its text is an exact match")
    }

    @Test
    fun matchTierTreatsLeadingIdentifierOfASignatureLabelAsExact() {
        // Real function labels carry a signature, so the exact tier must key off the leading name, not the
        // whole label, else `Text(...)` would never reach the exact tier.
        assertEquals(0, matchTier("Text(text: String)", "Text"))
        assertEquals(2, matchTier("TextField(value: String, onValueChange: (String) -> Unit)", "Text"))
        assertEquals(1, matchTier("text(s: String)", "Text")) // exact, case-only
    }

    @Test
    fun exactMatchFloatsAboveInScopeLongerMatchEvenWithSignatureLabels() {
        // The reported case: the backend returned the already-imported `TextField` first (in-scope ranks above
        // needs-import), but typing `Text` whole must still float the exact `Text` to the top. With labels that
        // carry signatures, this only works once the exact tier keys off the leading identifier.
        val s = session(
            "TextField(value: String, onValueChange: (String) -> Unit)",
            "Text(text: String)",
        )
        val out = s.filtered("Text")
        assertEquals("Text(text: String)", out.first().label)
    }
}
