package dev.ide.ui.editor

import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult

/**
 * Client-side completion cache + filter — provider-neutral.
 *
 * A session caches the full item set a backend returned for one token (anchored at [tokenStart]) and
 * narrows it locally as the user keeps typing the identifier. This keeps the popup stable while a slow
 * provider catches up: each keystroke filters the cached set instantly (the popup never blinks shut and
 * reopens), and a debounced background refresh swaps in fresh items underneath when they land.
 *
 * It is decoupled from any language: it operates only on the neutral [UiCompletionResult] /
 * [UiCompletionItem] DTOs the [dev.ide.ui.backend.IdeBackend] port already speaks, so every backend gets
 * the same no-flicker behavior with no provider-specific code. A provider that must not be filtered
 * locally (server-side fuzzy ranking, context-sensitive results) opts out via
 * [UiCompletionResult.mayFilterLocally].
 */
data class CompletionSession(
    /** Offset where the partial identifier begins — the cache key for "is this still the same token?". */
    val tokenStart: Int,
    /** The full, ranked candidate set the backend returned for [tokenStart]. */
    val base: List<UiCompletionItem>,
    /** Mirrors [UiCompletionResult.mayFilterLocally]; when false [filtered] returns [base] untouched. */
    val canFilterLocally: Boolean = true,
    /** Mirrors [UiCompletionResult.isIncomplete]: [base] is TRUNCATED, so narrowing it locally may miss
     *  candidates. The popup still filters it visually for instant feedback, but the editor keeps re-querying
     *  per keystroke (it can't skip the backend round-trip the way it can for a complete set). */
    val isIncomplete: Boolean = false,
) {
    /**
     * The items to show for [prefix] (the text typed since [tokenStart]). Ranking is preserved from the
     * backend; matching is lenient (prefix or camel-hump subsequence) so no candidate the user is still
     * narrowing toward is dropped; the authoritative set arrives on the next refresh.
     */
    fun filtered(prefix: String): List<UiCompletionItem> {
        if (prefix.isEmpty() || !canFilterLocally) return base
        // Keep every candidate the user is still narrowing toward (prefix OR camel/subsequence), then re-rank:
        //   1. Buffer words (hippie completion) always sort BELOW real, semantic completions — a guessed word
        //      must never outrank a resolved symbol, even when it is the closer textual match (the reported bug:
        //      a case-insensitive-exact word `text` jumping above the prefix-matching symbol `TextField`).
        //   2. Within each group, float exact matches over prefix fuzzy (case-sensitive winning each pair).
        // The sort is stable, so the backend's semantic ranking (expected type, proximity) survives within a tier.
        return base.asSequence()
            .filter { matchPositions(it.label, prefix) != null }
            .sortedWith(
                compareBy({ if (it.kind == UiCompletionKind.Word) 1 else 0 }, { matchTier(it.label, prefix) }),
            )
            .toList()
    }

    companion object {
        fun from(result: UiCompletionResult): CompletionSession =
            CompletionSession(result.replaceStart, result.items, result.mayFilterLocally, result.isIncomplete)
    }
}

/**
 * Whether the keystroke that landed [caret] can be absorbed by narrowing [session]'s cached set LOCALLY —
 * letting the editor skip a backend re-query (which would otherwise run on the interactive engine lane and
 * preempt the highlighting/diagnostics daemon). True only when the popup is live ([dismissed] false), the
 * same token is still being extended ([CompletionSession.coversCaret]), and the cached set is a COMPLETE,
 * locally-filterable prefix set the client-side [CompletionSession.filtered] is authoritative over. When this
 * is false (new/changed token, a truncated/incomplete set, or a provider that opted out of local filtering)
 * the editor must re-query so it doesn't serve a stale or partial list.
 */
internal fun canNarrowLocally(
    session: CompletionSession?, dismissed: Boolean, text: CharSequence, caret: Int, extra: String = "",
): Boolean =
    !dismissed && session != null && session.canFilterLocally && !session.isIncomplete &&
        session.coversCaret(text, caret, extra)

/**
 * Identifier-continuation test (Java-ish, but neutral enough for most C-family languages). [extra] adds
 * language-specific word characters so the popup stays anchored to the same token in non-Java syntaxes —
 * e.g. XML namespaces (`android:layout_width`) and resource references (`@string/foo`, `?attr/bar`,
 * `@+id/x`). Java passes no extras, so its behavior is unchanged. See [extraWordChars].
 */
internal fun isIdentifierChar(c: Char, extra: String = ""): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '$' || (extra.isNotEmpty() && c in extra)

/**
 * Extra word characters by file type. For XML, a completion "token" may include a namespace colon, the
 * `@ ? + / . -` of a resource reference, so these must not end the session (which would blink the popup
 * shut — the reason typing `android:` used to dismiss completion). Java/other files get none.
 */
internal fun extraWordChars(path: String): String =
    if (path.endsWith(".xml", ignoreCase = true)) ":@?+/.-|" else ""

/** What a just-typed character does to the completion popup. */
internal enum class CompletionKeystroke {
    /** A new context (`.` member access, `@` annotation start) — always re-query the backend for a fresh set. */
    Reopen,

    /** The same identifier token is being extended — narrow the live set locally if possible, else re-query. */
    Extend,

    /** A token-ending character — close the popup. */
    Dismiss,
}

/**
 * How the character [before] the caret should drive the completion popup (see [CompletionKeystroke]). `.` opens
 * a member-access set; `@` opens the annotation-name set (Kotlin/Java — where it is NOT a [extraWordChars] word
 * char, so `wordExtra` is empty; XML instead treats `@` as a resource-reference word char and extends). An
 * identifier character extends the current token; anything else ends the session.
 */
internal fun completionKeystroke(before: Char?, wordExtra: String): CompletionKeystroke = when {
    before == '.' -> CompletionKeystroke.Reopen
    before == '@' && wordExtra.isEmpty() -> CompletionKeystroke.Reopen
    before != null && isIdentifierChar(before, wordExtra) -> CompletionKeystroke.Extend
    else -> CompletionKeystroke.Dismiss
}

/**
 * Is [session] still describing the token under [caret] in [text]? True iff the caret is at/after the
 * anchor and everything from the anchor to the caret is identifier characters (incl. [extra]) — i.e. the
 * user has only extended (or not yet changed) the same identifier, so the cached set can be narrowed.
 */
internal fun CompletionSession.coversCaret(text: CharSequence, caret: Int, extra: String = ""): Boolean {
    if (tokenStart !in 0..caret || tokenStart > text.length) return false
    val end = caret.coerceAtMost(text.length)
    for (i in tokenStart until end) if (!isIdentifierChar(text[i], extra)) return false
    return true
}

/** Case-insensitive prefix match, falling back to a fuzzy (camel-hump / subsequence) match. */
internal fun fuzzyMatches(candidate: String, query: String): Boolean =
    matchPositions(candidate, query) != null

/**
 * The indices of [candidate] that [query] matches, or `null` if it doesn't match at all. A case-insensitive
 * prefix yields the leading run `0..query.length-1`; otherwise the query characters are matched as an
 * in-order subsequence (which subsumes camel-hump and substring matches, e.g. `nf` → `newFile`, `lw` →
 * `layout_width`). Returned positions drive both the popup's match highlighting and the filter — one matcher,
 * so what gets bolded is exactly what matched. Allocation-light: a single `IntArray` of the query length.
 */
internal fun matchPositions(candidate: String, query: String): IntArray? {
    if (query.isEmpty()) return IntArray(0)
    if (candidate.startsWith(query, ignoreCase = true)) return IntArray(query.length) { it }
    val pos = IntArray(query.length)
    var ci = 0
    var qi = 0
    while (qi < query.length && ci < candidate.length) {
        if (candidate[ci].lowercaseChar() == query[qi].lowercaseChar()) { pos[qi] = ci; qi++ }
        ci++
    }
    return if (qi == query.length) pos else null
}

/**
 * A coarse match-quality tier used only to re-order the locally-filtered set (lower = better): an exact
 * match floats above a prefix match floats above a fuzzy hit, with the case-sensitive variant winning each
 * pair. So typing `Text` ranks the exact `Text` above `TextField`/`TextView`. Stable-sorting by this keeps
 * the backend's ranking intact inside each tier while re-tiering by match quality.
 *
 * The exact tiers compare against the candidate's leading IDENTIFIER, not the whole label: a function label
 * carries its signature (`Text(text: String)`), so a whole-label equality check would never fire, letting a
 * longer but already-imported prefix match (`TextField(...)`) outrank the exactly-typed `Text`. The full label
 * is still accepted as exact too, so a signature-less label (a plain name, an XML `android:layout_width`) is
 * unchanged.
 */
internal fun matchTier(candidate: String, query: String): Int {
    if (query.isEmpty()) return 0
    val name = candidate.takeWhile { isIdentifierChar(it) } // "Text(text: String)" -> "Text"
    return when {
        candidate == query || name == query -> 0                 // exact (case-sensitive): the user typed it whole
        candidate.equals(query, ignoreCase = true) || name.equals(query, ignoreCase = true) -> 1 // exact, case only
        candidate.startsWith(query) -> 2                          // case-sensitive prefix
        candidate.startsWith(query, ignoreCase = true) -> 3       // case-insensitive prefix
        else -> 4                                                 // fuzzy (camel-hump / subsequence)
    }
}
