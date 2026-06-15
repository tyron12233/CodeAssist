package dev.ide.index.impl

import dev.ide.index.IndexOrigin

/**
 * Ranking + matching primitives shared by the in-memory [IndexData] (project-source side) and the on-disk
 * [Segment] (library/SDK side), so a candidate is scored identically no matter which side produced it —
 * the editor popup ranks the same regardless, and the committed quality baseline (`IndexQualityTest`)
 * holds. Allocation-free per candidate (char-by-char; no `lowercase()`/`buildString`); see
 * `IndexQueryBenchmark` for why that matters on ART.
 *
 * These formulas were lifted verbatim out of `IndexData` — do not "improve" the weights without
 * re-recording `baselines/index-quality.json`.
 */
internal object Scoring {

    fun originBonus(o: IndexOrigin): Int = when (o) {
        IndexOrigin.SOURCE -> 30
        IndexOrigin.LIBRARY -> 12
        IndexOrigin.SDK -> 0
    }

    fun scorePrefix(term: String, p: String, origin: IndexOrigin): Int {
        val base = when {
            term == p -> 1000
            term.startsWith(p) -> 820
            term.startsWith(p, ignoreCase = true) -> 680
            else -> 100
        }
        return base - term.length.coerceAtMost(40) + originBonus(origin)
    }

    /** The fuzzy score, or -1 for "no match" (the caller drops non-positive scores). */
    fun scoreFuzzy(term: String, pattern: String, origin: IndexOrigin): Int {
        val base = when {
            term == pattern -> 1000
            term.startsWith(pattern) -> 850
            term.startsWith(pattern, ignoreCase = true) -> 700
            camelSubsequence(term, pattern) -> 600
            containsCi(term, pattern) -> 420
            subsequenceCi(term, pattern) -> 220
            else -> return -1
        }
        return base - term.length.coerceAtMost(40) + originBonus(origin)
    }

    fun trigramsOf(s: String): List<String> {
        if (s.length < 3) return emptyList()
        return (0..s.length - 3).map { s.substring(it, it + 3) }
    }

    /** [needle]'s chars appear in order within [haystack], case-insensitively — no allocation. */
    fun subsequenceCi(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        var i = 0
        for (c in haystack) {
            if (c.lowercaseChar() == needle[i].lowercaseChar()) { i++; if (i == needle.length) return true }
        }
        return false
    }

    /** [haystack] contains [needle] as a substring, case-insensitively — no allocation. */
    fun containsCi(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        val last = haystack.length - needle.length
        for (start in 0..last) {
            if (haystack.regionMatches(start, needle, 0, needle.length, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * [pattern] matches the camel humps of [term] (first letter + uppercase letters + non-letters),
     * case-insensitively. Walks the humps inline instead of materializing a humps string.
     */
    fun camelSubsequence(term: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return true
        var pi = 0
        term.forEachIndexed { i, c ->
            if (i == 0 || c.isUpperCase() || !c.isLetter()) {
                if (c.lowercaseChar() == pattern[pi].lowercaseChar()) { pi++; if (pi == pattern.length) return true }
            }
        }
        return false
    }
}
