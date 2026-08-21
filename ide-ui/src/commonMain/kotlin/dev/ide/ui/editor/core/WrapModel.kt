package dev.ide.ui.editor.core

import dev.ide.ui.editor.folding.FoldModel

/**
 * Variable-height vertical layout for **soft wrap**: maps document lines ⇄ visual rows when a single line can
 * span several wrapped rows. The no-wrap editor path uses [FoldModel] directly (exactly one row per visible
 * line, O(1)); this model is engaged ONLY when word wrap is on, so the default path is untouched.
 *
 * It holds a per-document-line wrap-row count ([rawRows]) and a fold-aware **Fenwick tree** (binary indexed
 * tree) of visual rows, so [topRow] is O(log n), [docLineForRow] is O(log² n), and — the point of the
 * Fenwick — [setRows] is an O(log n) point update. The previous design kept a plain prefix-sum array and
 * marked it dirty on any [setRows], so [ensure] rebuilt the WHOLE prefix (O(n)) on the next frame; because the
 * draw phase corrects each on-screen line's exact wrapped height via [setRows] every frame, a fling through a
 * large wrapped file paid one O(n) rebuild PER FRAME. With the Fenwick, correcting a handful of visible lines
 * costs O(rows·log n) and no rebuild, so the mapping scales to large files.
 *
 * Counts start as a cheap monospace COLUMN estimate (no text shaping — see the caller) and are corrected to
 * the exact `TextLayoutResult.lineCount` as lines are shaped for drawing, via [setRows]. So the document
 * height is instant-but-approximate off-screen and pixel-exact for the rows actually on screen.
 *
 * Folds compose on top: a hidden line contributes 0 rows, a collapsed fold-start line contributes 1 (it
 * renders the single composite `prefix + … + suffix` row), every other visible line contributes its wrap-row
 * count. The fold-aware contribution is recomputed in a full O(n) [ensure] only when the fold projection or the
 * line count changes (both rare); frame-to-frame [setRows] corrections update the tree incrementally. Pure (no
 * Compose) so the mapping is unit-testable headlessly. Owned by one editor surface; not thread-safe.
 */
internal class WrapModel {
    private var rows = IntArray(0)       // wrap rows per doc line, ignoring folds; always >= 1
    // Fenwick over the fold-aware per-line contribution. `contrib[i]` mirrors the value currently reflected in
    // `tree` for line i (0 hidden / 1 collapsed-start / else rows[i]) so [setRows] can compute an exact delta.
    private var contrib = IntArray(0)
    private var tree = IntArray(1)       // 1-indexed Fenwick; tree[i] covers contrib[(i - lowbit)..i-1]
    var totalRows = 0
        private set
    private var built = false
    private var builtFold: FoldModel? = null

    /** Resize to [lineCount] document lines, preserving counts at indices that still exist; new lines default
     *  to one row until estimated/measured. Call whenever the line count changes (the tree goes stale). */
    fun resize(lineCount: Int) {
        if (rows.size == lineCount) return
        val next = IntArray(lineCount) { 1 }
        for (i in 0 until minOf(lineCount, rows.size)) next[i] = rows[i]
        rows = next
        built = false
    }

    /**
     * Set document [line]'s wrap-row count (clamped to >= 1). When the tree is already built for the current
     * fold, this applies an O(log n) point update to the Fenwick (and [totalRows]) instead of invalidating the
     * whole prefix; when it isn't built, it just records the new count and lets the next [ensure] rebuild.
     */
    fun setRows(line: Int, count: Int) {
        if (line !in rows.indices) return
        val r = if (count < 1) 1 else count
        if (rows[line] == r) return
        rows[line] = r
        if (!built) return
        val f = builtFold ?: return
        val newC = contribOf(line, f)
        val old = contrib[line]
        if (newC != old) {
            bitAdd(line, newC - old)
            contrib[line] = newC
            totalRows += newC - old
        }
    }

    /** The raw (fold-agnostic) wrap-row count for [line]. */
    fun rawRows(line: Int): Int = if (line in rows.indices) rows[line] else 1

    /**
     * Rebuild the fold-aware Fenwick if stale — i.e. the line count changed ([resize]) or the fold projection
     * ([fold]) is a different instance (collapse/expand/doc change rebuilds [FoldModel]). O(n); frequent
     * per-frame [setRows] corrections update the tree in place instead of coming through here.
     */
    fun ensure(fold: FoldModel) {
        if (built && builtFold === fold) return
        val n = rows.size
        if (contrib.size != n) contrib = IntArray(n)
        if (tree.size != n + 1) tree = IntArray(n + 1) else tree.fill(0)
        var acc = 0
        for (i in 0 until n) {
            val c = contribOf(i, fold)
            contrib[i] = c
            tree[i + 1] = c // seed each cell with its own value for the O(n) Fenwick build below
            acc += c
        }
        // O(n) in-place Fenwick construction: fold each cell's running total into its parent.
        var i = 1
        while (i <= n) {
            val parent = i + (i and -i)
            if (parent <= n) tree[parent] += tree[i]
            i++
        }
        totalRows = acc
        built = true
        builtFold = fold
    }

    /** Visual row where document [line] starts — the prefix sum of fold-aware contributions before [line].
     *  Requires a prior [ensure] for the current fold model. */
    fun topRow(line: Int): Int {
        val n = rows.size
        if (n == 0) return 0
        return bitPrefix(line.coerceIn(0, n))
    }

    /** Visual rows document [line] occupies after folding: 0 if hidden, 1 on a collapsed fold-start, else its
     *  wrap-row count. Read straight off the mirrored contribution so it matches [topRow] exactly. */
    fun rowsOf(line: Int): Int {
        if (!built || line < 0 || line >= rows.size) return rawRows(line)
        return contrib[line]
    }

    /** The document line shown at visual [row] — the fold-start line for a row inside a collapsed region. */
    fun docLineForRow(row: Int): Int {
        val n = rows.size
        if (n <= 0) return 0
        val r = row.coerceIn(0, (totalRows - 1).coerceAtLeast(0))
        // Largest line index L in [0, n-1] with prefix(L) <= r (prefix(L) = visual rows before line L). Same
        // upper-bound search as the old prefix array, reading Fenwick prefix sums instead of a materialized one.
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (bitPrefix(mid) <= r) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Fold-aware contribution of [line] under [fold]: 0 hidden, 1 collapsed-start, else its raw wrap rows. */
    private fun contribOf(line: Int, fold: FoldModel): Int = when {
        fold.isHidden(line) -> 0
        fold.foldStartingAt(line) != null -> 1
        else -> rows[line]
    }

    /** Add [delta] to line [line]'s Fenwick cell (0-indexed line → 1-indexed tree). */
    private fun bitAdd(line: Int, delta: Int) {
        var i = line + 1
        val n = tree.size - 1
        while (i <= n) {
            tree[i] += delta
            i += i and -i
        }
    }

    /** Sum of the first [len] contributions (contrib[0 until len]) — i.e. visual rows before document line len. */
    private fun bitPrefix(len: Int): Int {
        var i = len
        var s = 0
        while (i > 0) {
            s += tree[i]
            i -= i and -i
        }
        return s
    }
}
