package dev.ide.ui.editor.core

import dev.ide.ui.editor.folding.FoldModel

/**
 * Variable-height vertical layout for **soft wrap**: maps document lines ⇄ visual rows when a single line can
 * span several wrapped rows. The no-wrap editor path uses [FoldModel] directly (exactly one row per visible
 * line, O(1)); this model is engaged ONLY when word wrap is on, so the default path is untouched.
 *
 * It holds a per-document-line wrap-row count ([rawRows]) and a fold-aware prefix sum of visual rows
 * ([prefix]) so [topRow] is O(1) and [docLineForRow] is O(log n). Counts start as a cheap monospace COLUMN
 * estimate (no text shaping — see the caller) and are corrected to the exact `TextLayoutResult.lineCount` as
 * lines are shaped for drawing, via [setRows]. So the document height is instant-but-approximate off-screen
 * and pixel-exact for the rows actually on screen.
 *
 * Folds compose on top: a hidden line contributes 0 rows, a collapsed fold-start line contributes 1 (it
 * renders the single composite `prefix + … + suffix` row), every other visible line contributes its wrap-row
 * count. Pure (no Compose) so the mapping is unit-testable headlessly. Owned by one editor surface; not
 * thread-safe.
 */
internal class WrapModel {
    private var rows = IntArray(0)    // wrap rows per doc line, ignoring folds; always >= 1
    private var prefix = IntArray(1)  // prefix[i] = fold-aware visual rows before doc line i; size lineCount + 1
    var totalRows = 0
        private set
    private var built = false
    private var builtFold: FoldModel? = null

    /** Resize to [lineCount] document lines, preserving counts at indices that still exist; new lines default
     *  to one row until estimated/measured. Call whenever the line count changes (the prefix goes stale). */
    fun resize(lineCount: Int) {
        if (rows.size == lineCount) return
        val next = IntArray(lineCount) { 1 }
        for (i in 0 until minOf(lineCount, rows.size)) next[i] = rows[i]
        rows = next
        built = false
    }

    /** Set document [line]'s wrap-row count (clamped to >= 1); marks the prefix dirty only if it changed. */
    fun setRows(line: Int, count: Int) {
        if (line !in rows.indices) return
        val r = if (count < 1) 1 else count
        if (rows[line] != r) { rows[line] = r; built = false }
    }

    /** The raw (fold-agnostic) wrap-row count for [line]. */
    fun rawRows(line: Int): Int = if (line in rows.indices) rows[line] else 1

    /** Rebuild the fold-aware prefix sum if stale — i.e. counts changed since the last build, or the fold
     *  projection ([fold]) is a different instance (collapse/expand/doc change rebuilds [FoldModel]). */
    fun ensure(fold: FoldModel) {
        if (built && builtFold === fold) return
        val n = rows.size
        if (prefix.size != n + 1) prefix = IntArray(n + 1)
        var acc = 0
        for (i in 0 until n) {
            prefix[i] = acc
            acc += when {
                fold.isHidden(i) -> 0
                fold.foldStartingAt(i) != null -> 1 // collapsed start renders one composite row
                else -> rows[i]
            }
        }
        prefix[n] = acc
        totalRows = acc
        built = true
        builtFold = fold
    }

    /** Visual row where document [line] starts. Requires a prior [ensure] for the current fold model. */
    fun topRow(line: Int): Int {
        if (prefix.size <= 1) return 0
        return prefix[line.coerceIn(0, prefix.size - 1)]
    }

    /** Visual rows document [line] occupies after folding: 0 if hidden, 1 on a collapsed fold-start, else its
     *  wrap-row count. Derived from the prefix so it matches [topRow] exactly. */
    fun rowsOf(line: Int): Int {
        if (line < 0 || line + 1 >= prefix.size) return rawRows(line)
        return prefix[line + 1] - prefix[line]
    }

    /** The document line shown at visual [row] — the fold-start line for a row inside a collapsed region. */
    fun docLineForRow(row: Int): Int {
        val n = prefix.size - 1
        if (n <= 0) return 0
        val r = row.coerceIn(0, (totalRows - 1).coerceAtLeast(0))
        // Largest line index L in [0, n-1] with prefix[L] <= r. Hidden lines share their fold-start's prefix
        // value but sit at higher indices than the following visible line only when the fold ends the file, so
        // the upper-bound search naturally returns the visible line that owns the row.
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (prefix[mid] <= r) lo = mid else hi = mid - 1
        }
        return lo
    }
}
