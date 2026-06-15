package dev.ide.ui.editor.core

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

/** Phantom (non-document) text rendered inside a line at column [col] — an inlay hint. */
data class InlayPiece(val col: Int, val text: String)

/**
 * Per-line measured-text cache — the Compose analog of sora-editor's per-line render nodes + measure
 * cache. A line's [TextLayoutResult] (Skia paragraph: shaped, styled, ready to `drawText`) is built
 * once and reused until the line's [LineStyles] revision changes, so a keystroke re-shapes exactly
 * the edited line and a scroll frame shapes only lines newly entering the viewport. Entries validate
 * with one int compare; the unique-forever revision stamps make stale hits impossible, so eviction
 * and key remapping only ever cost a re-layout, never a wrong draw.
 *
 * Inlay hints (inferred types, parameter names) are woven in as **phantom text**: [setInlays] supplies a
 * per-line list of [InlayPiece]s that are spliced into the laid-out line and styled with the inlay span.
 * Because that shifts the visual columns of the real text, the layout-using geometry must translate document
 * columns through [rawToVisual] / [visualToRaw]. When there are no inlays (or they're disabled) both are the
 * identity and the hot typing path is byte-for-byte unchanged.
 */
class LineRenderCache(
    private val measurer: TextMeasurer,
    private val baseStyle: TextStyle,
    /** TokenType.ordinal → span style (theme-resolved). Rebuild the cache on theme change. */
    private val palette: Array<SpanStyle?>,
) {
    private class Entry(val rev: Int, val inlayRev: Int, val layout: TextLayoutResult, var lastUsed: Long)

    private val cache = HashMap<Int, Entry>()
    private var tick = 0L

    private var inlays: Map<Int, List<InlayPiece>> = emptyMap()
    private var inlayStyle: SpanStyle = SpanStyle()
    private var inlayRev = 0

    /** Widest line laid out so far, px — feeds the horizontal scroll range as lines get measured. */
    var measuredMaxWidth = 0f
        private set

    /**
     * Set the inlay hints to weave into lines (document-column keyed) and their span style. A no-op when the
     * content is unchanged; otherwise bumps the inlay revision so affected lines re-shape lazily on next draw.
     */
    fun setInlays(newInlays: Map<Int, List<InlayPiece>>, style: SpanStyle) {
        if (newInlays == inlays && style == inlayStyle) return
        inlays = newInlays
        inlayStyle = style
        inlayRev++
    }

    private fun piecesFor(line: Int): List<InlayPiece> = inlays[line]?.sortedBy { it.col } ?: emptyList()

    /** Document column → visual (laid-out) column for [line] — accounts for inlays inserted before [rawCol]. */
    fun rawToVisual(line: Int, rawCol: Int): Int {
        val pieces = piecesFor(line)
        if (pieces.isEmpty()) return rawCol
        var add = 0
        for (p in pieces) if (p.col < rawCol) add += p.text.length
        return rawCol + add
    }

    /** Visual (laid-out) column → document column for [line]; a hit inside an inlay snaps to its anchor. */
    fun visualToRaw(line: Int, visualCol: Int): Int {
        val pieces = piecesFor(line)
        if (pieces.isEmpty()) return visualCol
        var add = 0
        for (p in pieces) {
            val vstart = p.col + add
            if (visualCol <= vstart) break
            if (visualCol < vstart + p.text.length) return p.col // inside the inlay → snap to anchor
            add += p.text.length
        }
        return visualCol - add
    }

    fun layoutFor(line: Int, doc: EditorDocument, styles: LineStyles): TextLayoutResult {
        val rev = styles.revOf(line)
        cache[line]?.let { e ->
            if (e.rev == rev && e.inlayRev == inlayRev) {
                e.lastUsed = ++tick
                return e.layout
            }
        }
        val text = doc.lineText(line)
        val spans = styles.spansFor(line)
        val pieces = piecesFor(line)
        val annotated = when {
            pieces.isEmpty() && spans.isEmpty() -> AnnotatedString(text)
            pieces.isEmpty() -> AnnotatedString(
                text,
                spanStyles = spans.mapNotNull { sp ->
                    palette[sp.type.ordinal]?.let { AnnotatedString.Range(it, sp.start, sp.end) }
                },
            )
            else -> buildInlayAnnotated(text, spans, pieces)
        }
        val layout = measurer.measure(annotated, style = baseStyle, softWrap = false, maxLines = 1)
        if (layout.size.width > measuredMaxWidth) measuredMaxWidth = layout.size.width.toFloat()
        cache[line] = Entry(rev, inlayRev, layout, ++tick)
        if (cache.size > MAX_ENTRIES) evict()
        return layout
    }

    /** Splice [pieces] into [text] as phantom runs, mapping the syntax [spans] to the shifted visual columns
     *  and styling the inlay runs (added last so they win over any overlapping syntax span). */
    private fun buildInlayAnnotated(
        text: String,
        spans: List<LineSpan>,
        pieces: List<InlayPiece>,
    ): AnnotatedString {
        val sb = StringBuilder(text.length + pieces.sumOf { it.text.length })
        val ranges = ArrayList<AnnotatedString.Range<SpanStyle>>(spans.size + pieces.size)
        val inlayRanges = ArrayList<IntArray>(pieces.size)
        var pi = 0
        for (col in 0..text.length) {
            while (pi < pieces.size && pieces[pi].col == col) {
                val s = sb.length
                sb.append(pieces[pi].text)
                inlayRanges.add(intArrayOf(s, sb.length))
                pi++
            }
            if (col < text.length) sb.append(text[col])
        }
        for (sp in spans) {
            val st = palette[sp.type.ordinal] ?: continue
            ranges.add(AnnotatedString.Range(st, mapCol(pieces, sp.start), mapCol(pieces, sp.end)))
        }
        for (r in inlayRanges) ranges.add(AnnotatedString.Range(inlayStyle, r[0], r[1]))
        return AnnotatedString(sb.toString(), spanStyles = ranges)
    }

    private fun mapCol(pieces: List<InlayPiece>, rawCol: Int): Int {
        var add = 0
        for (p in pieces) if (p.col < rawCol) add += p.text.length
        return rawCol + add
    }

    /** Mirror a document splice: cache keys at/after [fromOldLine] move by [delta] (Enter stays O(1)-ish). */
    fun shiftKeys(fromOldLine: Int, delta: Int) {
        if (delta == 0 || cache.isEmpty()) return
        val moved = cache.entries.filter { it.key >= fromOldLine }
        if (moved.isEmpty()) return
        for (e in moved) cache.remove(e.key)
        for (e in moved) {
            val k = e.key + delta
            if (k >= 0) cache[k] = e.value
        }
    }

    fun clear() {
        cache.clear()
        measuredMaxWidth = 0f
    }

    private fun evict() {
        // drop the least-recently-used quarter in one pass (no per-access bookkeeping structures)
        val byAge = cache.entries.sortedBy { it.value.lastUsed }
        for (i in 0 until cache.size / 4) cache.remove(byAge[i].key)
    }

    private companion object {
        /** ~4 viewports of lines; beyond this, scroll-back re-measures (µs per line). */
        const val MAX_ENTRIES = 512
    }
}
