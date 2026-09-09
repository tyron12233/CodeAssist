package dev.ide.ui.editor.folding

import dev.ide.ui.editor.core.EditorDocument

/**
 * A foldable region anchored to the buffer in document offsets. [collapsed] tracks the user's toggle (an
 * import region starts collapsed; a block opens). The editor owns a list of these (shifted on edit like
 * diagnostics) and rebuilds a [FoldModel] from the currently-collapsed ones whenever the set changes.
 */
data class FoldRegion(
    val start: Int,
    val end: Int,
    val placeholder: String,
    val kind: String,
    val collapsed: Boolean,
)

/** What the renderer draws on the one visible line that begins a collapsed fold: the start line's text up to
 *  [prefixEnd], the [placeholder], then the end line's text from [suffixStart] — joined on a single row. */
class FoldedLineInfo(
    val startLine: Int,
    val endLine: Int,
    val prefixEnd: Int,   // document offset where the start line's visible prefix ends (== region.start)
    val suffixStart: Int, // document offset where the end line's visible suffix begins (== region.end)
    val placeholder: String,
)

/**
 * The folding projection over a document: which document lines are HIDDEN by collapsed regions, the
 * compressed document-line ⇄ visual-row mapping the renderer/geometry use, and the composite text for each
 * fold-start line. Pure (no Compose) so the mapping is unit-tested headlessly; the editor rebuilds it only
 * when the collapsed set or the document changes.
 *
 * A collapsed region from offset `s`..`e` keeps its START line visible (rendered as `prefix + placeholder +
 * suffix`, so `fun f() {...}`) and HIDES the lines after it through its END line (their content folds into
 * the start line's suffix). Regions fully contained in another collapsed region contribute nothing extra
 * (already hidden); the survivors are disjoint, so hidden lines form sorted, merged intervals — O(folds) per
 * query, no per-line array, so a 50k-line file with three folds stays cheap.
 */
class FoldModel private constructor(
    val docLineCount: Int,
    private val hiddenStart: IntArray, // inclusive interval starts (doc lines), sorted, disjoint, merged
    private val hiddenEnd: IntArray,   // inclusive interval ends
    private val hiddenBefore: IntArray, // hiddenBefore[i] = count of hidden lines in intervals < i
    private val foldByStartLine: Map<Int, FoldedLineInfo>,
) {
    /** Total hidden document lines (== docLineCount - visualLineCount). */
    val hiddenCount: Int =
        if (hiddenStart.isEmpty()) 0 else hiddenBefore.last() + (hiddenEnd.last() - hiddenStart.last() + 1)

    /** Number of visual rows after folding. */
    val visualLineCount: Int get() = docLineCount - hiddenCount

    val hasFolds: Boolean get() = hiddenStart.isNotEmpty()

    /** True when document [line] is collapsed away (inside a fold, not its visible start line). */
    fun isHidden(line: Int): Boolean {
        val i = intervalIndexFor(line)
        return i >= 0 && line in hiddenStart[i]..hiddenEnd[i]
    }

    /** The fold that begins on document [line] (its start line is visible), or null. */
    fun foldStartingAt(line: Int): FoldedLineInfo? = foldByStartLine[line]

    /** Document line shown at visual [row]. Identity when there are no folds. */
    fun docLineForVisual(row: Int): Int {
        if (hiddenStart.isEmpty()) return row.coerceIn(0, docLineCount - 1)
        // doc line = row + (hidden lines in every interval that starts at/before this row's position). Compare
        // the CONSTANT row against each interval's visible-rows-before threshold (both in visual-row space);
        // accumulate the skipped hidden lines separately — mixing the two spaces miscounts later intervals.
        var added = 0
        for (i in hiddenStart.indices) {
            val visibleBeforeInterval = hiddenStart[i] - hiddenBefore[i]
            if (row < visibleBeforeInterval) break
            added += hiddenEnd[i] - hiddenStart[i] + 1
        }
        return (row + added).coerceIn(0, docLineCount - 1)
    }

    /** Visual row for document [line]; a hidden line maps to the row of its enclosing fold's start line. */
    fun visualForDocLine(line: Int): Int {
        if (hiddenStart.isEmpty()) return line.coerceIn(0, visualLineCount - 1)
        var hidden = 0
        for (i in hiddenStart.indices) {
            if (hiddenStart[i] > line) break
            if (line <= hiddenEnd[i]) // inside this fold → collapse onto its (visible) start line
                return ((hiddenStart[i] - 1) - hiddenBefore[i]).coerceIn(0, visualLineCount - 1)
            hidden += hiddenEnd[i] - hiddenStart[i] + 1 // a fold fully before `line`
        }
        return (line - hidden).coerceIn(0, visualLineCount - 1)
    }

    /** The single-row text for a fold-start [line]: prefix + placeholder + suffix (all from [doc]). */
    fun compositeText(line: Int, doc: EditorDocument): String {
        val info = foldByStartLine[line] ?: return doc.lineText(line)
        val prefix = doc.substring(doc.lineStart(line), info.prefixEnd)
        val suffix = doc.substring(info.suffixStart, doc.lineEnd(info.endLine))
        return prefix + info.placeholder + suffix
    }

    /** The interval whose start is the greatest ≤ [line], or -1. Linear in interval count (few folds). */
    private fun intervalIndexFor(line: Int): Int {
        var ans = -1
        for (i in hiddenStart.indices) {
            if (hiddenStart[i] <= line) ans = i else break
        }
        return ans
    }

    companion object {
        val EMPTY = FoldModel(0, IntArray(0), IntArray(0), IntArray(0), emptyMap())

        fun build(doc: EditorDocument, regions: List<FoldRegion>): FoldModel {
            val collapsed = regions.filter { it.collapsed && it.end > it.start }
            if (collapsed.isEmpty()) return FoldModel(
                doc.lineCount, IntArray(0), IntArray(0), IntArray(0), emptyMap()
            )

            // Drop a region contained in another collapsed region (already hidden); survivors are disjoint.
            val sorted = collapsed.sortedWith(compareBy({ it.start }, { -it.end }))
            val effective = ArrayList<FoldRegion>(sorted.size)
            var coverEnd = -1
            for (r in sorted) {
                if (r.start < coverEnd) continue // nested inside the previous effective region → skip
                effective.add(r); coverEnd = r.end
            }

            val foldByStart = HashMap<Int, FoldedLineInfo>(effective.size)
            // Hidden line intervals: for each region, lines (startLine, endLine]; merge adjacent/overlapping.
            val rawStarts = ArrayList<Int>(effective.size)
            val rawEnds = ArrayList<Int>(effective.size)
            for (r in effective) {
                val startLine = doc.lineForOffset(r.start)
                val endLine = doc.lineForOffset(r.end)
                if (endLine <= startLine) continue // single visual line → nothing to hide
                foldByStart[startLine] =
                    FoldedLineInfo(startLine, endLine, r.start, r.end, r.placeholder)
                rawStarts.add(startLine + 1)
                rawEnds.add(endLine)
            }
            if (rawStarts.isEmpty()) return FoldModel(
                doc.lineCount, IntArray(0), IntArray(0), IntArray(0), emptyMap()
            )

            // Merge intervals (sorted by start; effective regions are disjoint but their line ranges can touch).
            val order = rawStarts.indices.sortedBy { rawStarts[it] }
            val mStart = ArrayList<Int>();
            val mEnd = ArrayList<Int>()
            for (idx in order) {
                val s = rawStarts[idx];
                val e = rawEnds[idx]
                if (mStart.isNotEmpty() && s <= mEnd.last() + 1) {
                    if (e > mEnd.last()) mEnd[mEnd.size - 1] = e
                } else {
                    mStart.add(s); mEnd.add(e)
                }
            }
            val before = IntArray(mStart.size)
            var acc = 0
            for (i in mStart.indices) {
                before[i] = acc; acc += mEnd[i] - mStart[i] + 1
            }
            return FoldModel(
                doc.lineCount, mStart.toIntArray(), mEnd.toIntArray(), before, foldByStart
            )
        }
    }
}
