package dev.ide.ui.editor.core

import dev.ide.ui.editor.folding.FoldModel
import dev.ide.ui.editor.folding.FoldRegion
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Fenwick-backed [WrapModel] must produce exactly the same line ⇄ row mapping as a naive prefix-sum
 * reference — for [WrapModel.topRow], [WrapModel.rowsOf], [WrapModel.docLineForRow] and [WrapModel.totalRows] —
 * under random wrap-row counts, random collapsed folds, the incremental [WrapModel.setRows] path, and resizes.
 */
class WrapModelTest {

    // ---- naive reference over rows + fold ----
    private fun refContrib(rows: IntArray, fold: FoldModel): IntArray = IntArray(rows.size) { i ->
        when {
            fold.isHidden(i) -> 0
            fold.foldStartingAt(i) != null -> 1
            else -> rows[i]
        }
    }

    private fun refPrefix(contrib: IntArray): IntArray {
        val p = IntArray(contrib.size + 1)
        var acc = 0
        for (i in contrib.indices) { p[i] = acc; acc += contrib[i] }
        p[contrib.size] = acc
        return p
    }

    private fun refDocLineForRow(prefix: IntArray, total: Int, n: Int, row: Int): Int {
        val r = row.coerceIn(0, (total - 1).coerceAtLeast(0))
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (prefix[mid] <= r) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun assertMatchesReference(wm: WrapModel, rows: IntArray, fold: FoldModel) {
        val n = rows.size
        val contrib = refContrib(rows, fold)
        val prefix = refPrefix(contrib)
        val total = prefix[n]
        assertEquals(total, wm.totalRows, "totalRows")
        for (l in 0..n + 1) assertEquals(prefix[l.coerceIn(0, n)], wm.topRow(l), "topRow($l)")
        for (l in 0 until n) assertEquals(contrib[l], wm.rowsOf(l), "rowsOf($l)")
        for (r in -1..total + 2) {
            assertEquals(refDocLineForRow(prefix, total, n, r), wm.docLineForRow(r), "docLineForRow($r)")
        }
    }

    /** A document of [n] single-word lines (no trailing newline → exactly [n] lines). */
    private fun docOf(n: Int): EditorDocument =
        EditorDocument.of((0 until n).joinToString("\n") { "line$it" })

    /** A FoldModel over [doc] collapsing the given (startLine, endLine) pairs (endLine > startLine). */
    private fun foldOf(doc: EditorDocument, ranges: List<Pair<Int, Int>>): FoldModel {
        if (ranges.isEmpty()) return FoldModel.build(doc, emptyList())
        val regions = ranges.map { (s, e) ->
            FoldRegion(
                start = doc.lineStart(s),
                end = doc.lineStart(e),
                placeholder = "...",
                kind = "test",
                collapsed = true,
            )
        }
        return FoldModel.build(doc, regions)
    }

    @Test
    fun noFoldUniformRows() {
        val n = 20
        val wm = WrapModel()
        wm.resize(n)
        val rows = IntArray(n) { 1 }
        val fold = foldOf(docOf(n), emptyList())
        wm.ensure(fold)
        assertMatchesReference(wm, rows, fold)
    }

    @Test
    fun variedRowsNoFold() {
        val n = 30
        val wm = WrapModel()
        wm.resize(n)
        val rows = IntArray(n) { (it % 4) + 1 }
        for (i in 0 until n) wm.setRows(i, rows[i]) // before ensure → recorded, applied on build
        val fold = foldOf(docOf(n), emptyList())
        wm.ensure(fold)
        assertMatchesReference(wm, rows, fold)
    }

    @Test
    fun withCollapsedFolds() {
        val n = 40
        val doc = docOf(n)
        val wm = WrapModel()
        wm.resize(n)
        val rows = IntArray(n) { (it % 5) + 1 }
        for (i in 0 until n) wm.setRows(i, rows[i])
        val fold = foldOf(doc, listOf(3 to 8, 12 to 12 + 1, 20 to 30))
        wm.ensure(fold)
        assertMatchesReference(wm, rows, fold)
    }

    @Test
    fun incrementalSetRowsAfterBuildMatchesReference() {
        val n = 50
        val doc = docOf(n)
        val wm = WrapModel()
        wm.resize(n)
        val rows = IntArray(n) { 1 }
        val fold = foldOf(doc, listOf(5 to 10, 25 to 33))
        wm.ensure(fold)
        assertMatchesReference(wm, rows, fold)
        // Now correct visible lines' exact row counts one by one (the per-frame path) and re-verify each time
        // that the incremental Fenwick update kept the whole mapping consistent.
        val rnd = Random(3)
        repeat(400) {
            val line = rnd.nextInt(n)
            val count = rnd.nextInt(1, 7)
            rows[line] = count.coerceAtLeast(1)
            wm.setRows(line, count)
            assertMatchesReference(wm, rows, fold)
        }
    }

    @Test
    fun resizeThenReuse() {
        val wm = WrapModel()
        // Start small, then grow, then shrink — each time re-estimate + fold + verify.
        for (n in intArrayOf(8, 64, 33, 1, 100)) {
            wm.resize(n)
            val doc = docOf(n)
            val rows = IntArray(n) { (it * 7 + 1) % 4 + 1 }
            for (i in 0 until n) wm.setRows(i, rows[i])
            val folds = if (n >= 20) listOf(2 to 6, 10 to 15) else emptyList()
            val fold = foldOf(doc, folds)
            wm.ensure(fold)
            assertMatchesReference(wm, rows, fold)
        }
    }

    @Test
    fun fuzzAgainstReference() {
        val rnd = Random(2026)
        val wm = WrapModel()
        var n = 25
        wm.resize(n)
        var doc = docOf(n)
        var rows = IntArray(n) { rnd.nextInt(1, 5) }
        for (i in 0 until n) wm.setRows(i, rows[i])
        var fold = foldOf(doc, listOf(4 to 9, 15 to 20))
        wm.ensure(fold)
        assertMatchesReference(wm, rows, fold)
        repeat(600) {
            when (rnd.nextInt(3)) {
                0 -> { // correct a line's rows (incremental path)
                    val line = rnd.nextInt(n)
                    val c = rnd.nextInt(1, 8)
                    rows[line] = c
                    wm.setRows(line, c)
                }
                1 -> { // resize + re-estimate + refold
                    n = rnd.nextInt(1, 60)
                    wm.resize(n)
                    doc = docOf(n)
                    val newRows = IntArray(n) { if (it < rows.size) rows[it] else 1 }
                    rows = newRows
                    val folds = buildList {
                        if (n > 12) add(2 to rnd.nextInt(3, minOf(n, 10)))
                        if (n > 25) add(15 to rnd.nextInt(16, minOf(n, 24)))
                    }.filter { it.second > it.first }
                    fold = foldOf(doc, folds)
                    for (i in 0 until n) wm.setRows(i, rows[i])
                    wm.ensure(fold)
                }
                2 -> { // change the fold projection only
                    val folds = if (n > 14) listOf(1 to rnd.nextInt(2, minOf(n, 12))).filter { it.second > it.first } else emptyList()
                    fold = foldOf(doc, folds)
                    wm.ensure(fold)
                }
            }
            assertMatchesReference(wm, rows, fold)
        }
    }
}
