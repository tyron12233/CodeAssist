package dev.ide.lang.completion

/**
 * Graded completion-prefix matching, shared by every contributor and both Kotlin stacks. Replaces the
 * scattered `startsWith(prefix, ignoreCase = true)` gates so `mDL` completes `myDynamicList` and `NPE`
 * completes `NullPointerException` everywhere, and so match quality is ONE signal the ranking chain can
 * weigh instead of a per-backend boolean.
 *
 * A name matches at exactly one [Grade] (the best that applies, [grade]); [matchPositions] returns the
 * matched character indices so the popup bolds exactly what matched, whatever the grade.
 *
 * Camel-hump matches are anchored at the name's first character (IntelliJ's un-wildcarded matcher does the
 * same): each further query character continues the current run or jumps to a hump start — an uppercase
 * char, a char after a separator, or a digit run's first digit. Middle matches are the [Grade.SUBSTRING]
 * fallback, deliberately last like IntelliJ's middle matching.
 *
 * Index-backed candidate sources can't enumerate-then-filter at classpath scale; [indexPrefix] is the
 * longest plain prefix every match shares — the full prefix for a plain query, the first character for a
 * hump-shaped one — so their query pushdown widens only when the user actually typed a hump pattern.
 */
class PrefixMatcher(val prefix: String) {

    /** Match quality, best first — the ordering signal for the match-grade weigher. */
    enum class Grade { EXACT, PREFIX, PREFIX_CI, HUMP, SUBSTRING }

    /** Whether the query looks like a deliberate camel-hump pattern (an uppercase char after the first
     *  position). Index-backed sources use this to decide between full-prefix pushdown and the wider
     *  [indexPrefix] query + hump filter. */
    val isHumpQuery: Boolean = prefix.length >= 2 && prefix.drop(1).any { it.isUpperCase() }

    /** The longest plain (case-insensitive) prefix every matching name is guaranteed to start with. */
    val indexPrefix: String = if (isHumpQuery) prefix.take(1) else prefix

    /**
     * The plain (case-insensitive) prefixes an index query should push down to reach EVERY match. A plain query
     * needs only [indexPrefix] (the whole prefix). A HUMP query (`listOf` once the caret passes the capital,
     * `mDL`) needs BOTH: [indexPrefix] is only the first character (a hump match may diverge after char 0),
     * whose result cap can truncate a plain-prefix match — the concrete `listOf`-typed-in-full bug — before it
     * is reached; the FULL [prefix] is a narrow, uncapped query that rescues such a typed-in-full name. The full
     * prefix is listed FIRST so it wins the result cap; callers de-duplicate the overlap.
     */
    val indexPrefixes: List<String> = if (isHumpQuery) listOf(prefix, indexPrefix) else listOf(indexPrefix)

    fun matches(name: String): Boolean = grade(name) != null

    /** The best [Grade] at which [name] matches, or null if it doesn't. An empty prefix matches everything.
     *  Middle ([Grade.SUBSTRING]) matches need [MIN_SUBSTRING_QUERY] typed chars — a 1-2 char query middle-
     *  matches nearly every candidate, which is noise, not recall (IntelliJ's middle matching is as shy).
     *  Allocation-free (candidate sets run to thousands per keystroke; see the alloc/op benchmarks). */
    fun grade(name: String): Grade? = when {
        prefix.isEmpty() -> Grade.PREFIX
        name == prefix -> Grade.EXACT
        name.startsWith(prefix) -> Grade.PREFIX
        name.startsWith(prefix, ignoreCase = true) -> Grade.PREFIX_CI
        humpMatch(name, null) -> Grade.HUMP
        prefix.length >= MIN_SUBSTRING_QUERY && name.contains(prefix, ignoreCase = true) -> Grade.SUBSTRING
        else -> null
    }

    /** The indices of [name] the query matched (for popup highlighting), or null on no match. */
    fun matchPositions(name: String): IntArray? {
        if (prefix.isEmpty()) return EMPTY
        if (name.startsWith(prefix, ignoreCase = true)) return IntArray(prefix.length) { it }
        val hump = IntArray(prefix.length)
        if (humpMatch(name, hump)) return hump
        if (prefix.length < MIN_SUBSTRING_QUERY) return null
        val at = name.indexOf(prefix, ignoreCase = true)
        return if (at >= 0) IntArray(prefix.length) { at + it } else null
    }

    /**
     * Camel-hump match: the first query char matches [name]'s first char (case-insensitively); each later
     * query char either continues the current contiguous run or jumps forward to a hump start. Backtracking
     * (rather than a greedy scan) so an early hump binding that strands a later char is retried; inputs are
     * identifier-sized, so the small recursion is fine. [out] records matched indices when non-null.
     */
    private fun humpMatch(name: String, out: IntArray?): Boolean {
        if (name.isEmpty() || !name[0].equals(prefix[0], ignoreCase = true)) return false
        out?.set(0, 0)

        fun tryFrom(qi: Int, ni: Int): Boolean {
            if (qi == prefix.length) return true
            var i = ni
            while (i < name.length) {
                if (name[i].equals(prefix[qi], ignoreCase = true) && (i == ni || isHumpStart(name, i))) {
                    out?.set(qi, i)
                    if (tryFrom(qi + 1, i + 1)) return true
                }
                i++
            }
            return false
        }
        return tryFrom(1, 1)
    }

    private fun isHumpStart(name: String, i: Int): Boolean {
        if (i == 0) return true
        val c = name[i]
        val prev = name[i - 1]
        return c.isUpperCase() || !prev.isLetterOrDigit() || (c.isDigit() && !prev.isDigit())
    }

    private companion object {
        val EMPTY = IntArray(0)
        const val MIN_SUBSTRING_QUERY = 3
    }
}
