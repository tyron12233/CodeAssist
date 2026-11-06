package com.zipcli.ke.domain

/** Small text helpers shared by the parser ("did you mean"), autocomplete and the commands. */
object Suggest {

    /** Levenshtein distance with an early exit once the result must exceed [limit]. */
    fun distance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (Math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Best "did you mean" candidate for [input], or null when nothing is close enough. */
    fun closest(input: String, candidates: Collection<String>): String? {
        val x = input.lowercase()
        if (x.isEmpty()) return null
        // A unique prefix match ("rep" -> "replace") is the strongest signal.
        val prefixed = candidates.filter { it.lowercase().startsWith(x) }
        if (prefixed.size == 1 && x.length >= 2) return prefixed[0]
        val limit = if (x.length <= 3) 1 else 2
        var best: String? = null
        var bestD = limit + 1
        for (c in candidates.sorted()) {
            val d = distance(x, c.lowercase(), limit)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }

    /** Longest common prefix, compared case-insensitively but returned with the first item's casing. */
    fun commonPrefix(items: List<String>): String {
        if (items.isEmpty()) return ""
        var len = items[0].length
        for (s in items) {
            var k = 0
            val m = minOf(len, s.length)
            while (k < m && items[0][k].equals(s[k], ignoreCase = true)) k++
            len = k
            if (len == 0) break
        }
        return items[0].substring(0, len)
    }
}

/** "file2" sorts before "file10"; case-insensitive. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
