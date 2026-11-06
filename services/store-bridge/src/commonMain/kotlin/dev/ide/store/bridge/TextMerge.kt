package dev.ide.store.bridge

/**
 * A three-way merge of text by lines: the version both sides started from ([base]), the user's ([ours]) and
 * the publisher's new one ([theirs]).
 *
 * Where only one side changed a stretch of lines, that side's lines are taken; where both changed the same
 * stretch differently, the result keeps both, between conflict markers, for the user to settle in the editor.
 * This is the diff3 rule Git's own merge follows: the stretches are found by matching each side against the
 * base, and a stretch is "stable" where all three agree.
 *
 * Line endings are kept as found: lines are split after each `\n`, so a file with CRLF endings merges as one.
 */
object TextMerge {

    data class Result(val text: String, val conflicts: Int)

    fun merge(
        base: String,
        ours: String,
        theirs: String,
        oursLabel: String = "Your version",
        theirsLabel: String = "New version",
    ): Result {
        if (ours == theirs) return Result(ours, 0)
        if (ours == base) return Result(theirs, 0)
        if (theirs == base) return Result(ours, 0)
        val b = lines(base)
        val o = lines(ours)
        val t = lines(theirs)
        val mapO = matches(b, o)
        val mapT = matches(b, t)

        val out = StringBuilder()
        var conflicts = 0
        var i = 0
        var j = 0
        var k = 0
        while (i < b.size || j < o.size || k < t.size) {
            // A stable run: base lines that both sides kept, in place.
            var n = 0
            while (i + n < b.size && mapO[i + n] == j + n && mapT[i + n] == k + n) n++
            if (n > 0) {
                for (x in 0 until n) out.append(b[i + x])
                i += n; j += n; k += n
                continue
            }
            // The next base line both sides still have marks the end of the changed stretch.
            var next = i
            while (next < b.size && !(mapO[next] >= j && mapT[next] >= k)) next++
            val endO = if (next < b.size) mapO[next] else o.size
            val endT = if (next < b.size) mapT[next] else t.size
            val chunkB = b.subList(i, next)
            val chunkO = o.subList(j, endO)
            val chunkT = t.subList(k, endT)
            when {
                chunkO == chunkB -> chunkT.forEach(out::append)
                chunkT == chunkB -> chunkO.forEach(out::append)
                chunkO == chunkT -> chunkO.forEach(out::append)
                else -> {
                    // Lines the two sides agree on at either end of the stretch are not in conflict, so they
                    // go outside the markers (Git's "zealous" trim): the user is shown only what differs.
                    var pre = 0
                    while (pre < chunkO.size && pre < chunkT.size && chunkO[pre] == chunkT[pre]) pre++
                    var post = 0
                    while (post < chunkO.size - pre && post < chunkT.size - pre &&
                        chunkO[chunkO.size - 1 - post] == chunkT[chunkT.size - 1 - post]
                    ) post++
                    chunkO.subList(0, pre).forEach(out::append)
                    val midO = chunkO.subList(pre, chunkO.size - post)
                    val midT = chunkT.subList(pre, chunkT.size - post)
                    if (midO.isNotEmpty() || midT.isNotEmpty()) {
                        conflicts++
                        out.append("<<<<<<< ").append(oursLabel).append('\n')
                        appendSide(out, midO)
                        out.append("=======\n")
                        appendSide(out, midT)
                        out.append(">>>>>>> ").append(theirsLabel).append('\n')
                    }
                    chunkO.subList(chunkO.size - post, chunkO.size).forEach(out::append)
                }
            }
            i = next; j = endO; k = endT
        }
        return Result(out.toString(), conflicts)
    }

    /** One side of a conflict, ended with a newline even when the file's last line had none. */
    private fun appendSide(out: StringBuilder, lines: List<String>) {
        lines.forEach(out::append)
        if (lines.isNotEmpty() && !lines.last().endsWith('\n')) out.append('\n')
    }

    /** Lines with their terminators kept, so joining them gives the text back exactly. */
    internal fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                if (start < text.length) out += text.substring(start)
                break
            }
            out += text.substring(start, nl + 1)
            start = nl + 1
        }
        return out
    }

    /**
     * For each line of [a], the index of the line of [b] it is matched to (a longest common subsequence),
     * or -1. Common leading and trailing lines are matched first, which is most of any real file, so the
     * quadratic part only ever sees the stretch that actually changed.
     */
    internal fun matches(a: List<String>, b: List<String>): IntArray {
        val map = IntArray(a.size) { -1 }
        var lo = 0
        while (lo < a.size && lo < b.size && a[lo] == b[lo]) { map[lo] = lo; lo++ }
        var hiA = a.size
        var hiB = b.size
        while (hiA > lo && hiB > lo && a[hiA - 1] == b[hiB - 1]) { hiA--; hiB--; map[hiA] = hiB }
        val n = hiA - lo
        val m = hiB - lo
        if (n == 0 || m == 0) return map
        // A changed middle too big to compare line by line is left unmatched: it then merges as one block,
        // which is a conflict where both sides touched it, and that is the safe answer.
        if (n.toLong() * m > MAX_CELLS) return map
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (x in n - 1 downTo 0) for (y in m - 1 downTo 0) {
            dp[x][y] = if (a[lo + x] == b[lo + y]) dp[x + 1][y + 1] + 1 else maxOf(dp[x + 1][y], dp[x][y + 1])
        }
        var x = 0
        var y = 0
        while (x < n && y < m) {
            when {
                a[lo + x] == b[lo + y] -> { map[lo + x] = lo + y; x++; y++ }
                dp[x + 1][y] >= dp[x][y + 1] -> x++
                else -> y++
            }
        }
        return map
    }

    /** About 64 MB of table at most; a source file's changed stretch is a tiny fraction of this. */
    private const val MAX_CELLS = 16_000_000L
}
