package dev.ide.ui.editor

/**
 * In-file find/replace matching — pure and compose-free so it's unit-testable. A [Match] is a half-open
 * `[start, end)` document range. [findMatches] supports case-insensitive (default), whole-word, and regex
 * search; an invalid regex yields no matches (the bar surfaces that as a 0-count rather than throwing).
 */
data class Match(val start: Int, val end: Int)

data class FindOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

private fun isWordCh(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')

/** True when `[start, end)` is bounded by non-word chars (or the buffer edges) on both sides. */
private fun isWholeWord(s: CharSequence, start: Int, end: Int): Boolean =
    !isWordCh(if (start > 0) s[start - 1] else null) && !isWordCh(if (end < s.length) s[end] else null)

/** All non-overlapping matches of [query] in [text]. Empty when the query is blank or a regex won't compile. */
fun findMatches(text: CharSequence, query: String, opts: FindOptions): List<Match> {
    if (query.isEmpty()) return emptyList()
    val s = text.toString() // find is an explicit action — one materialization is fine
    val out = ArrayList<Match>()
    if (opts.regex) {
        val options = if (opts.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val re = runCatching { Regex(query, options) }.getOrNull() ?: return emptyList()
        for (m in re.findAll(s)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (end <= start) continue // skip zero-width matches (would not advance / not selectable)
            if (!opts.wholeWord || isWholeWord(s, start, end)) out.add(Match(start, end))
        }
    } else {
        var i = 0
        while (i <= s.length) {
            val idx = s.indexOf(query, i, ignoreCase = !opts.caseSensitive)
            if (idx < 0) break
            val end = idx + query.length
            if (!opts.wholeWord || isWholeWord(s, idx, end)) out.add(Match(idx, end))
            i = end // non-overlapping
        }
    }
    return out
}

/** Index of the first match at/after [caret], wrapping to 0; -1 when there are none. Drives "find next from caret". */
fun matchIndexFrom(matches: List<Match>, caret: Int): Int {
    if (matches.isEmpty()) return -1
    val i = matches.indexOfFirst { it.start >= caret }
    return if (i >= 0) i else 0
}
