package dev.ide.ui.editor.core

/**
 * One changed stretch of a file against its committed text, as line ranges on both sides: lines
 * `[newStart, newEnd)` of the buffer replace lines `[oldStart, oldEnd)` of the base. Lines are what `split('\n')`
 * gives, so they match [EditorDocument]'s line numbers.
 */
data class LineChange(val newStart: Int, val newEnd: Int, val oldStart: Int, val oldEnd: Int) {
    enum class Kind { ADDED, MODIFIED, DELETED }

    val kind: Kind
        get() = when {
            oldEnd == oldStart -> Kind.ADDED
            newEnd == newStart -> Kind.DELETED
            else -> Kind.MODIFIED
        }
}

/**
 * The changed stretches between [base] (the committed text) and [current] (the buffer), in buffer order: a Myers
 * line diff after trimming the common head and tail, which is all an edit to a big file usually leaves to compare.
 * A middle whose edit script would take more than [maxEdits] steps (a file rewritten wholesale) is reported as one
 * modified stretch rather than spending the time and memory to align it.
 */
fun lineChanges(base: String, current: String, maxEdits: Int = 4000): List<LineChange> {
    if (base == current) return emptyList()
    val a = base.split('\n')
    val b = current.split('\n')
    var head = 0
    while (head < a.size && head < b.size && a[head] == b[head]) head++
    var tail = 0
    while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
    val aMid = a.subList(head, a.size - tail)
    val bMid = b.subList(head, b.size - tail)
    if (aMid.isEmpty() && bMid.isEmpty()) return emptyList()
    if (aMid.isEmpty() || bMid.isEmpty()) {
        return listOf(LineChange(head, head + bMid.size, head, head + aMid.size))
    }
    val script = myers(aMid, bMid, maxEdits)
        ?: return listOf(LineChange(head, head + bMid.size, head, head + aMid.size))
    // Group the per-line steps into stretches: a run of deletions and insertions between two kept lines is one.
    val out = ArrayList<LineChange>()
    var i = 0
    var j = 0
    var k = 0
    while (k < script.size) {
        if (script[k] == KEEP) { i++; j++; k++; continue }
        val oldStart = i
        val newStart = j
        while (k < script.size && script[k] != KEEP) {
            if (script[k] == DELETE) i++ else j++
            k++
        }
        out += LineChange(head + newStart, head + j, head + oldStart, head + i)
    }
    return out
}

private const val KEEP: Byte = 0
private const val DELETE: Byte = 1
private const val INSERT: Byte = 2

/** Myers' O(ND) diff of [a] into [b] as an edit script of KEEP / DELETE / INSERT steps, or null past [maxEdits]. */
private fun myers(a: List<String>, b: List<String>, maxEdits: Int): ByteArray? {
    val n = a.size
    val m = b.size
    val ah = IntArray(n) { a[it].hashCode() }
    val bh = IntArray(m) { b[it].hashCode() }
    fun same(x: Int, y: Int) = ah[x] == bh[y] && a[x] == b[y]
    val max = minOf(n + m, maxEdits)
    val offset = max + 1
    var v = IntArray(2 * max + 3)
    val trace = ArrayList<IntArray>()
    var found = -1
    for (d in 0..max) {
        trace += v.copyOf()
        val next = v.copyOf()
        var k = -d
        while (k <= d) {
            var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
            var y = x - k
            while (x < n && y < m && same(x, y)) { x++; y++ }
            next[offset + k] = x
            if (x >= n && y >= m) { found = d; break }
            k += 2
        }
        v = next
        if (found >= 0) { trace += v; break }
    }
    if (found < 0) return null
    // Walk back through the saved frontiers, collecting steps in reverse.
    val steps = ArrayList<Byte>(n + m)
    var x = n
    var y = m
    for (d in found downTo 1) {
        val vd = trace[d] // the frontier before step d
        val k = x - y
        val prevK = if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) k + 1 else k - 1
        val prevX = vd[offset + prevK]
        val prevY = prevX - prevK
        while (x > prevX && y > prevY) { steps += KEEP; x--; y-- }
        if (x == prevX) { steps += INSERT; y-- } else { steps += DELETE; x-- }
    }
    while (x > 0 && y > 0) { steps += KEEP; x--; y-- }
    return ByteArray(steps.size) { steps[steps.size - 1 - it] }
}

/**
 * The base (committed) line that buffer [line] is, given the buffer's [changes] against the base: shifted by
 * the stretches above it. Null for a line inside a changed stretch, which the base does not have.
 */
fun baseLineFor(changes: List<LineChange>, line: Int): Int? {
    var delta = 0
    for (c in changes) {
        if (line < c.newStart) break
        if (line < c.newEnd) return null
        delta += (c.oldEnd - c.oldStart) - (c.newEnd - c.newStart)
    }
    return line + delta
}

/**
 * The edit that undoes [change] in [doc]: replaces its buffer lines with the [base] lines it stands for. Returns
 * the `[start, end)` range of [doc] and its replacement.
 */
fun revertEdit(doc: EditorDocument, base: String, change: LineChange): Triple<Int, Int, String> {
    val old = base.split('\n').subList(change.oldStart, change.oldEnd)
    val count = doc.lineCount
    return when {
        change.newStart >= count -> // lines removed from the very end of the file: put them back after it
            Triple(doc.length, doc.length, old.joinToString("") { "\n" + it })
        change.newEnd < count ->
            Triple(doc.lineStart(change.newStart), doc.lineStart(change.newEnd), old.joinToString("") { it + "\n" })
        else -> // the stretch runs to the end of the file, whose last line has no newline after it
            Triple(doc.lineStart(change.newStart), doc.length, old.joinToString("\n"))
    }
}
