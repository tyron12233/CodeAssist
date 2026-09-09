package dev.ide.ui.editor.core

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

/** Phantom (non-document) text rendered inside a line at column [col] — an inlay hint. */
data class InlayPiece(val col: Int, val text: String)

/** A semantic-highlight run within a line: columns `[start, end)` styled with [style] (already theme- and
 *  modifier-resolved). Overlaid on top of the lexical token spans, so it wins where they overlap. */
data class SemSpan(val start: Int, val end: Int, val style: SpanStyle)

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
    private class Entry(val rev: Int, val inlayRev: Int, val semRev: Int, val layout: TextLayoutResult, var lastUsed: Long)

    private val cache = HashMap<Int, Entry>()
    private var tick = 0L

    private val inlayRevs = InlayRevisions()
    private var inlayStyle: SpanStyle = SpanStyle()

    private val semantic = SemanticSpansByLine()

    /**
     * Set the semantic-highlight overlay (document-line keyed, columns within each line). Like [setInlays] it
     * bumps a per-line stamp only for lines whose spans changed, so only those re-shape on the next draw — the
     * async semantic pass never re-lays-out the whole viewport. Empty map ⇒ purely lexical highlighting.
     */
    fun setSemanticSpans(newSpans: Map<Int, List<SemSpan>>) {
        semantic.update(newSpans)
    }

    /** Widest line laid out so far, px — feeds the horizontal scroll range as lines get measured. */
    var measuredMaxWidth = 0f
        private set

    /**
     * Soft-wrap width in px (0 = no wrap → the classic one-row-per-line layout). When > 0, lines are shaped
     * with `softWrap = true` constrained to this width, so a long line lays out as several rows. Changing it
     * (resize / font-zoom / toggling wrap) invalidates every cached layout — widths no longer hold — but keeps
     * the per-line inlay/semantic stamps (those are content, width-independent).
     */
    private var wrapWidthPx = 0
    fun setWrapWidth(px: Int) {
        val w = px.coerceAtLeast(0)
        if (w == wrapWidthPx) return
        wrapWidthPx = w
        cache.clear()
        measuredMaxWidth = 0f
    }

    /**
     * Smart wrap indent (IntelliJ's "use indent for wrapped lines"): when [wrapIndentEnabled], a wrapped
     * line's continuation rows are indented to its own leading-whitespace column ([TextIndent.restLine]), so
     * the wrapped part lines up under the code instead of the left margin. [charWidthSp] is the monospace
     * advance as a [TextUnit] (so the indent scales with zoom) and [maxIndentCols] caps it to leave room.
     * Because the indent is baked into the laid-out paragraph, every geometry query (caret/tap/selection x+y)
     * already accounts for it. Changing any of these reshapes lines, so the layout cache is cleared.
     */
    private var wrapIndentEnabled = false
    private var charWidthSp: TextUnit = TextUnit.Unspecified
    private var maxIndentCols = 0
    private var extraIndentCols = 0
    fun setWrapIndent(enabled: Boolean, charWidthSp: TextUnit, maxIndentCols: Int, extraIndentCols: Int) {
        if (enabled == wrapIndentEnabled && charWidthSp == this.charWidthSp &&
            maxIndentCols == this.maxIndentCols && extraIndentCols == this.extraIndentCols
        ) return
        wrapIndentEnabled = enabled
        this.charWidthSp = charWidthSp
        this.maxIndentCols = maxIndentCols.coerceAtLeast(0)
        this.extraIndentCols = extraIndentCols.coerceAtLeast(0)
        cache.clear()
    }

    /** Leading-whitespace width of [text] in display columns (tabs to 4-column stops). */
    private fun leadingIndentColumns(text: String): Int {
        var c = 0
        for (ch in text) when (ch) {
            ' ' -> c++
            '\t' -> c += 4 - (c % 4)
            else -> return c
        }
        return c
    }

    /**
     * Set the inlay hints to weave into lines (document-column keyed) and their span style. Delegates to
     * [InlayRevisions], which bumps a per-line stamp only for lines whose pieces changed, so only those
     * re-shape on the next draw. A theme change (the [style]) rebuilds this whole cache instance (it's
     * remembered on the palette/style), so we don't track style for invalidation here — only inlay content.
     */
    fun setInlays(newInlays: Map<Int, List<InlayPiece>>, style: SpanStyle) {
        inlayStyle = style
        inlayRevs.update(newInlays)
    }

    /** Document column → visual (laid-out) column for [line] — accounts for inlays inserted before [rawCol]. */
    fun rawToVisual(line: Int, rawCol: Int): Int {
        val pieces = inlayRevs.piecesFor(line)
        if (pieces.isEmpty()) return rawCol
        var add = 0
        for (p in pieces) if (p.col < rawCol) add += p.text.length
        return rawCol + add
    }

    /** Visual (laid-out) column → document column for [line]; a hit inside an inlay snaps to its anchor. */
    fun visualToRaw(line: Int, visualCol: Int): Int {
        val pieces = inlayRevs.piecesFor(line)
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
        val irev = inlayRevs.stampOf(line)
        val srev = semantic.stampOf(line)
        cache[line]?.let { e ->
            if (e.rev == rev && e.inlayRev == irev && e.semRev == srev) {
                e.lastUsed = ++tick
                return e.layout
            }
        }
        val text = doc.lineText(line)
        val spans = styles.spansFor(line)
        val pieces = inlayRevs.piecesFor(line)
        val sem = semantic.spansFor(line)
        val annotated = when {
            pieces.isEmpty() && spans.isEmpty() && sem.isEmpty() -> AnnotatedString(text)
            pieces.isEmpty() -> AnnotatedString(
                text,
                // Lexical spans first, then semantic on top (later ranges win on overlap), bounded to the line.
                spanStyles = spans.mapNotNull { sp ->
                    palette[sp.type.ordinal]?.let { AnnotatedString.Range(it, sp.start, sp.end) }
                } + sem.mapNotNull { sp ->
                    clampRange(sp.start, sp.end, text.length)?.let { (s, e) -> AnnotatedString.Range(sp.style, s, e) }
                },
            )
            else -> buildInlayAnnotated(text, spans, pieces, sem)
        }
        val layout = if (wrapWidthPx > 0) {
            // Indent continuation rows to the line's own indent (capped) when smart wrap indent is on.
            val style = if (wrapIndentEnabled && charWidthSp != TextUnit.Unspecified) {
                // Original indent + IntelliJ's additional continuation shift, capped to leave room.
                val indentCols = (leadingIndentColumns(text) + extraIndentCols).coerceAtMost(maxIndentCols)
                if (indentCols > 0) baseStyle.copy(textIndent = TextIndent(restLine = charWidthSp * indentCols)) else baseStyle
            } else baseStyle
            measurer.measure(annotated, style = style, softWrap = true, constraints = Constraints(maxWidth = wrapWidthPx))
        } else {
            measurer.measure(annotated, style = baseStyle, softWrap = false, maxLines = 1)
        }
        if (layout.size.width > measuredMaxWidth) measuredMaxWidth = layout.size.width.toFloat()
        cache[line] = Entry(rev, irev, srev, layout, ++tick)
        if (cache.size > MAX_ENTRIES) evict()
        return layout
    }

    /** Splice [pieces] into [text] as phantom runs, mapping the syntax [spans] (then the [sem]antic overlay)
     *  to the shifted visual columns and styling the inlay runs (added last so they win over any span). */
    private fun buildInlayAnnotated(
        text: String,
        spans: List<LineSpan>,
        pieces: List<InlayPiece>,
        sem: List<SemSpan>,
    ): AnnotatedString {
        val sb = StringBuilder(text.length + pieces.sumOf { it.text.length })
        val ranges = ArrayList<AnnotatedString.Range<SpanStyle>>(spans.size + sem.size + pieces.size)
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
        for (sp in sem) {
            val (s, e) = clampRange(sp.start, sp.end, text.length) ?: continue
            ranges.add(AnnotatedString.Range(sp.style, mapCol(pieces, s), mapCol(pieces, e)))
        }
        for (r in inlayRanges) ranges.add(AnnotatedString.Range(inlayStyle, r[0], r[1]))
        return AnnotatedString(sb.toString(), spanStyles = ranges)
    }

    /** Clamp a semantic span to `[0, len]`, or null if it doesn't fall within the line (stale after an edit). */
    private fun clampRange(start: Int, end: Int, len: Int): Pair<Int, Int>? {
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        return if (e > s) s to e else null
    }

    private fun mapCol(pieces: List<InlayPiece>, rawCol: Int): Int {
        var add = 0
        for (p in pieces) if (p.col < rawCol) add += p.text.length
        return rawCol + add
    }

    /** Mirror a document splice: keys at/after [fromOldLine] move by [delta] (Enter stays O(1)-ish). Both the
     *  layout cache and the per-line inlay stamps shift together so a moved line keeps its validation pair. */
    fun shiftKeys(fromOldLine: Int, delta: Int) {
        if (delta == 0) return
        shiftIntKeyed(cache, fromOldLine, delta)
        inlayRevs.shift(fromOldLine, delta)
        semantic.shift(fromOldLine, delta)
    }

    fun clear() {
        cache.clear()
        inlayRevs.clear()
        semantic.clear()
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

/** Shift the [Int]-keyed [map]'s entries at/after [fromOldLine] by [delta], dropping any that go negative. */
internal fun <V> shiftIntKeyed(map: HashMap<Int, V>, fromOldLine: Int, delta: Int) {
    if (map.isEmpty()) return
    val moved = map.entries.filter { it.key >= fromOldLine }.map { it.key to it.value }
    if (moved.isEmpty()) return
    for ((k, _) in moved) map.remove(k)
    for ((k, v) in moved) { val nk = k + delta; if (nk >= 0) map[nk] = v }
}

/**
 * Per-line inlay revision tracking, split out of [LineRenderCache] so it's testable without a `TextMeasurer`
 * (which can't be constructed headlessly). Holds the current inlay map and a **unique-forever stamp per
 * line**, bumped ONLY for lines whose pieces actually change.
 *
 * The point: a single global counter (the old design) invalidated EVERY cached line on any inlay edit — and
 * the host re-anchors inlay offsets on every keystroke, so that re-shaped the whole viewport on each key.
 * With a per-line stamp, a hint change re-shapes only its own line; lines after the caret stay cached.
 */
internal class InlayRevisions {
    private var inlays: Map<Int, List<InlayPiece>> = emptyMap()
    private val stampByLine = HashMap<Int, Int>()
    private var stamp = 0

    /** The unique stamp for [line]; 0 when the line has never carried an inlay. */
    fun stampOf(line: Int): Int = stampByLine[line] ?: 0

    fun piecesFor(line: Int): List<InlayPiece> = inlays[line]?.sortedBy { it.col } ?: emptyList()

    /** Adopt [newInlays], bumping the stamp for each line whose pieces changed, were added, or were removed. */
    fun update(newInlays: Map<Int, List<InlayPiece>>) {
        if (newInlays == inlays) return
        // lines present before whose pieces changed (or were removed: newInlays[line] becomes null)
        for ((line, pieces) in inlays) if (newInlays[line] != pieces) stampByLine[line] = ++stamp
        // lines newly present
        for (line in newInlays.keys) if (line !in inlays) stampByLine[line] = ++stamp
        inlays = newInlays
    }

    /** Mirror a line splice so a moved line keeps its stamp (paired with the layout cache shift). */
    fun shift(fromOldLine: Int, delta: Int) {
        if (delta == 0) return
        shiftIntKeyed(stampByLine, fromOldLine, delta)
        // The inlay map is document-column keyed by line; the host rebuilds it post-edit, so we only need the
        // stamps to move. Drop the stale map so a moved line's pieces aren't read from the wrong line until then.
        if (inlays.isNotEmpty()) inlays = inlays.mapKeys { (k, _) -> if (k >= fromOldLine) k + delta else k }
            .filterKeys { it >= 0 }
    }

    fun clear() {
        inlays = emptyMap()
        stampByLine.clear()
        stamp = 0
    }
}

/**
 * Per-line semantic-overlay tracking, the [SemSpan] analog of [InlayRevisions]. Holds the current document-
 * line-keyed semantic spans and a unique-forever stamp per line, bumped ONLY for lines whose spans changed —
 * so an async semantic-highlight pass re-shapes only the lines it actually recolored, not the whole viewport.
 */
internal class SemanticSpansByLine {
    private var spans: Map<Int, List<SemSpan>> = emptyMap()
    private val stampByLine = HashMap<Int, Int>()
    private var stamp = 0

    fun stampOf(line: Int): Int = stampByLine[line] ?: 0
    fun spansFor(line: Int): List<SemSpan> = spans[line] ?: emptyList()

    /** Adopt [newSpans], bumping the stamp for each line whose spans changed, were added, or were removed. */
    fun update(newSpans: Map<Int, List<SemSpan>>) {
        if (newSpans == spans) return
        for ((line, s) in spans) if (newSpans[line] != s) stampByLine[line] = ++stamp
        for (line in newSpans.keys) if (line !in spans) stampByLine[line] = ++stamp
        spans = newSpans
    }

    /** Mirror a line splice so a moved line keeps its stamp (paired with the layout cache shift). */
    fun shift(fromOldLine: Int, delta: Int) {
        if (delta == 0) return
        shiftIntKeyed(stampByLine, fromOldLine, delta)
        if (spans.isNotEmpty()) spans = spans.mapKeys { (k, _) -> if (k >= fromOldLine) k + delta else k }
            .filterKeys { it >= 0 }
    }

    fun clear() {
        spans = emptyMap()
        stampByLine.clear()
        stamp = 0
    }
}
