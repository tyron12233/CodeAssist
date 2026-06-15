package dev.ide.deps.impl

/**
 * A pragmatic Maven-style version comparator (a compact stand-in for `ComparableVersion`) — enough to
 * pick "newest" reliably for conflict resolution. It splits a version into numeric and qualifier tokens
 * (on `.`, `-`, `_`, and digit↔letter boundaries) and compares token-by-token: numbers numerically,
 * known qualifiers by rank (alpha < beta < milestone < rc < snapshot < release < sp), and a numeric
 * token outranks a qualifier. Missing trailing tokens compare as "0"/release, so `1.2` == `1.2.0`.
 */
object MavenVersion : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val n = maxOf(ta.size, tb.size)
        for (i in 0 until n) {
            val x = ta.getOrNull(i) ?: Token.ZERO
            val y = tb.getOrNull(i) ?: Token.ZERO
            val c = x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    /** Highest version under this ordering, or null for an empty input. */
    fun newest(versions: Collection<String>): String? = versions.maxWithOrNull(this)

    private sealed class Token : Comparable<Token> {
        data class Num(val value: Long) : Token()
        data class Qualifier(val text: String) : Token()

        override fun compareTo(other: Token): Int = when {
            this is Num && other is Num -> value.compareTo(other.value)
            this is Num && other is Qualifier -> 1          // 1.0 > 1.0-rc
            this is Qualifier && other is Num -> -1
            this is Qualifier && other is Qualifier -> rank(text).compareTo(rank(other.text)).let {
                if (it != 0) it else text.compareTo(other.text)
            }
            else -> 0
        }

        companion object {
            val ZERO: Token = Num(0)
            private fun rank(q: String): Int = when (q.lowercase()) {
                "alpha", "a" -> 0
                "beta", "b" -> 1
                "milestone", "m" -> 2
                "rc", "cr" -> 3
                "snapshot" -> 4
                "", "ga", "final", "release" -> 5
                "sp" -> 6
                else -> 5 // unknown qualifier sorts as a release-ish patch
            }
        }
    }

    private fun tokenize(version: String): List<Token> {
        val out = ArrayList<Token>()
        val sb = StringBuilder()
        var lastDigit: Boolean? = null
        fun flush() {
            if (sb.isEmpty()) return
            val s = sb.toString()
            out += if (lastDigit == true) Token.Num(s.toLongOrNull() ?: 0L) else Token.Qualifier(s)
            sb.setLength(0)
        }
        for (ch in version) {
            when {
                ch == '.' || ch == '-' || ch == '_' || ch == '+' -> { flush(); lastDigit = null }
                ch.isDigit() -> { if (lastDigit == false) flush(); sb.append(ch); lastDigit = true }
                else -> { if (lastDigit == true) flush(); sb.append(ch); lastDigit = false }
            }
        }
        flush()
        return out
    }
}
