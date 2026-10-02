package dev.ide.agent.ui

/**
 * A line diff between two texts, grouped into hunks with a few lines of context, for showing what the agent
 * changed (or is asking to change) in a file.
 *
 * The common prefix and suffix are stripped first, so a typical agent edit (a few lines in a large file) diffs
 * only the region that moved. The middle is diffed with Myers' algorithm; when that region is so large and so
 * different that the search would be expensive, it falls back to showing the whole region as replaced, which is
 * the honest answer for a rewrite anyway.
 */
internal object LineDiff {
    enum class Kind { CONTEXT, ADDED, REMOVED }

    /** One rendered line. [oldLine]/[newLine] are 1-based numbers on each side (null where the line is absent). */
    data class Line(val kind: Kind, val text: String, val oldLine: Int?, val newLine: Int?)

    data class Hunk(val lines: List<Line>)

    data class Result(val hunks: List<Hunk>, val added: Int, val removed: Int)

    private const val CONTEXT = 3
    private const val MAX_EDIT_SEARCH = 2_000
    private const val MAX_TRACE_INTS = 4_000_000

    fun diff(before: String?, after: String?): Result {
        val a = before?.let(::lines).orEmpty()
        val b = after?.let(::lines).orEmpty()
        val ops = edits(a, b)
        val added = ops.count { it.kind == Kind.ADDED }
        val removed = ops.count { it.kind == Kind.REMOVED }
        return Result(hunks(ops), added, removed)
    }

    /** A file's lines; the newline that ends the last line does not start an empty one after it. */
    private fun lines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    private fun edits(a: List<String>, b: List<String>): List<Line> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val out = ArrayList<Line>(a.size + b.size)
        for (i in 0 until prefix) out += Line(Kind.CONTEXT, a[i], i + 1, i + 1)
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        out += middle(midA, midB, prefix)
        for (k in 0 until suffix) {
            val i = a.size - suffix + k
            val j = b.size - suffix + k
            out += Line(Kind.CONTEXT, a[i], i + 1, j + 1)
        }
        return out
    }

    /** Myers' O(ND) diff of the differing middle, offset by [base] lines on both sides. */
    private fun middle(a: List<String>, b: List<String>, base: Int): List<Line> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        val max = n + m
        // The trace keeps one copy of the frontier per step, so the step budget shrinks as the region grows.
        val limit = minOf(max, MAX_EDIT_SEARCH, MAX_TRACE_INTS / (2 * max + 2))
        val offset = max
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        loop@ for (d in 0..limit) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = true; break@loop }
                k += 2
            }
        }
        if (!found) return replaced(a, b, base)

        // Walk the trace backwards to recover the edit script.
        val script = ArrayList<Line>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) k + 1 else k - 1
            val prevX = vd[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--; y--
                script += Line(Kind.CONTEXT, a[x], base + x + 1, base + y + 1)
            }
            if (d == 0) break
            if (x == prevX) {
                y--
                script += Line(Kind.ADDED, b[y], null, base + y + 1)
            } else {
                x--
                script += Line(Kind.REMOVED, a[x], base + x + 1, null)
            }
        }
        script.reverse()
        return script
    }

    private fun replaced(a: List<String>, b: List<String>, base: Int): List<Line> =
        a.mapIndexed { i, s -> Line(Kind.REMOVED, s, base + i + 1, null) } +
            b.mapIndexed { j, s -> Line(Kind.ADDED, s, null, base + j + 1) }

    /** Groups changed lines with [CONTEXT] lines either side; distant changes become separate hunks. */
    private fun hunks(lines: List<Line>): List<Hunk> {
        val changed = lines.indices.filter { lines[it].kind != Kind.CONTEXT }
        if (changed.isEmpty()) return emptyList()
        val out = ArrayList<Hunk>()
        var start = (changed.first() - CONTEXT).coerceAtLeast(0)
        var end = (changed.first() + CONTEXT).coerceAtMost(lines.lastIndex)
        for (i in changed.drop(1)) {
            if (i - CONTEXT <= end + 1) {
                end = (i + CONTEXT).coerceAtMost(lines.lastIndex)
            } else {
                out += Hunk(lines.subList(start, end + 1))
                start = (i - CONTEXT).coerceAtLeast(0)
                end = (i + CONTEXT).coerceAtMost(lines.lastIndex)
            }
        }
        out += Hunk(lines.subList(start, end + 1))
        return out
    }
}
