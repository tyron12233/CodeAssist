package dev.ide.ui.editor.core

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

/**
 * Entries of shaped text the editor's [TextMeasurer] keeps, keyed by content rather than by line.
 *
 * [LineRenderCache] already caches a shaped line against its line number, so this second cache exists for the
 * one thing that cannot catch: source code repeats itself. Blank lines, `}`, `    }`, `    )` — about a
 * quarter of the lines in a real file are character-for-character identical to another line, and each one
 * used to pay a full `Paragraph` construction on the way into the viewport.
 *
 * Measured on device (ART, shaping twelve distinct lines — one scroll step): **0.32 ms with no cache against
 * 0.13 ms with one**, repeatably. Everything from 16 entries to 256 lands within run-to-run noise of that
 * 0.13, so the number below is chosen from the middle of a flat region, not a sharp optimum — the win is in
 * having a cache at all, and there is no evidence a bigger one buys anything more to pay memory for.
 */
const val MEASURER_CACHE_ENTRIES: Int = 64

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

    /** Replace one line's semantic spans (empty = none), bumping its stamp only if they changed. The per-edit
     *  path: an edit re-bins just the lines it touched, the rest having moved with [shiftKeys]. */
    fun setSemanticLine(line: Int, spans: List<SemSpan>) {
        semantic.setLine(line, spans)
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

    /** Replace one line's inlay pieces (empty = none), bumping its stamp only if they changed; see
     *  [setSemanticLine]. */
    fun setInlayLine(line: Int, pieces: List<InlayPiece>) {
        inlayRevs.setLine(line, pieces)
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
 * A per-line overlay store: one value and one **unique-forever stamp** per document line, held in arrays
 * indexed by the line itself.
 *
 * Both overlays the render cache carries — inlay pieces and semantic spans — want the same three things, and
 * the third is what makes the shape of this class. A read is per line, several times per visible line, every
 * frame. A stamp must be unique forever, because it is what lets a cached layout validate with one int compare
 * and never take a stale hit. And an edit that adds or removes a line has to renumber everything below it,
 * sixty times a second while someone holds Enter.
 *
 * Keyed maps get the first two right and the third badly wrong. The previous implementation held a
 * `Map<Int, List<T>>` plus a `HashMap<Int, Int>` of stamps, and renumbered them with
 * `mapKeys { … }.filterKeys { … }` — which rebuilds every entry in the file to move a line boundary. On a
 * 4000-line file with semantic coloring that measured **0.31 ms and a garbage collection every eight
 * newlines**, all of it to add one to a few thousand integers.
 *
 * Indexed by line, the same edit is two `copyInto` calls over a region of references: no per-entry
 * allocation, no rehash, and no boxing on the read path either. It also costs less memory than the maps did —
 * an array of N references against N `HashMap.Node`s with boxed `Integer` keys — and N is bounded, because
 * the editor suppresses both overlays above [LARGE_FILE_LINE_LIMIT].
 */
internal class LineOverlay<T> {
    private var values: Array<Any?> = EMPTY_VALUES
    private var stamps: IntArray = EMPTY_STAMPS
    /** Logical length: lines `[0, length)` may hold a value. Never exceeds the arrays' capacity. */
    private var length = 0
    private var stamp = 0

    /**
     * The last map adopted by [update], kept only so re-pushing it is free. The host calls `setInlays` /
     * `setSemanticSpans` on every recomposition with a `remember`ed map, so the overwhelmingly common case is
     * the same instance arriving again. Null once a [splice] has moved lines, because the arrays then describe
     * a document the map no longer does.
     */
    private var source: Map<Int, List<T>>? = null

    /** The value for [line], or an empty list — O(1), no boxing. */
    @Suppress("UNCHECKED_CAST")
    fun valueAt(line: Int): List<T> {
        if (line < 0 || line >= length) return emptyList()
        return (values[line] as List<T>?) ?: emptyList()
    }

    /** The unique stamp for [line]; 0 when the line has never carried a value. */
    fun stampOf(line: Int): Int = if (line < 0 || line >= length) 0 else stamps[line]

    /**
     * Adopt [newSource], bumping the stamp only for lines whose value actually changed, was added, or was
     * removed — so an analysis pass re-shapes only the lines it really recolored, not the whole viewport.
     * [normalize] (optional) puts a line's list into the canonical order the reads expect, once here rather
     * than on every read.
     */
    fun update(newSource: Map<Int, List<T>>, normalize: ((List<T>) -> List<T>)? = null) {
        val current = source
        if (current != null && (newSource === current || newSource == current)) return

        var maxLine = -1
        for (line in newSource.keys) if (line > maxLine) maxLine = line
        ensureCapacity(maxOf(maxLine + 1, length))

        // Lines that held a value and no longer match: bumped (a removal bumps too — its woven text vanishes).
        var line = 0
        while (line < length) {
            if (values[line] != null && newSource[line] == null) {
                values[line] = null
                stamps[line] = ++stamp
            }
            line++
        }
        for ((ln, raw) in newSource) {
            if (ln < 0) continue
            val value = normalize?.invoke(raw) ?: raw
            if (values[ln] != value) {
                values[ln] = value
                stamps[ln] = ++stamp
            }
        }
        if (maxLine + 1 > length) length = maxLine + 1
        source = newSource
    }

    /**
     * Set one line's value ([normalize]d like [update]; an empty list clears it), bumping its stamp only when
     * it differs from what the line holds. The per-edit counterpart of [update]: the caller re-bins just the
     * lines an edit touched, after [splice] has moved the rest.
     */
    fun setLine(line: Int, newValue: List<T>, normalize: ((List<T>) -> List<T>)? = null) {
        if (line < 0) return
        val value: List<T>? = if (newValue.isEmpty()) null else (normalize?.invoke(newValue) ?: newValue)
        source = null // the arrays no longer describe the last adopted map
        if (line >= length) {
            if (value == null) return
            ensureCapacity(line + 1)
            length = line + 1
        }
        if (values[line] != value) {
            values[line] = value
            stamps[line] = ++stamp
        }
    }

    /**
     * Mirror a document line splice: lines at/after [fromLine] move by [delta], carrying their stamps, and any
     * pushed below zero are dropped. Two region copies, no allocation beyond a grow.
     */
    fun splice(fromLine: Int, delta: Int) {
        if (delta == 0 || length == 0) return
        val from = fromLine.coerceAtLeast(0)
        if (from >= length) return
        source = null // the arrays now describe a document the adopted map does not

        if (delta > 0) {
            ensureCapacity(length + delta)
            values.copyInto(values, from + delta, from, length)
            stamps.copyInto(stamps, from + delta, from, length)
            values.fill(null, from, from + delta) // the inserted lines start with no overlay
            stamps.fill(0, from, from + delta)
            length += delta
        } else {
            // Lines that would land at a negative index are dropped, so the copy starts at the first survivor.
            val src = from + maxOf(0, -(from + delta))
            if (src < length) {
                values.copyInto(values, src + delta, src, length)
                stamps.copyInto(stamps, src + delta, src, length)
            }
            val newLength = maxOf(0, length + delta)
            values.fill(null, newLength, length) // the vacated tail must not keep a moved line's value alive
            stamps.fill(0, newLength, length)
            length = newLength
        }
    }

    fun clear() {
        values.fill(null, 0, length)
        stamps.fill(0, 0, length)
        length = 0
        stamp = 0
        source = null
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= values.size) return
        val grown = maxOf(needed, values.size * 2, 64)
        values = values.copyOf(grown)
        stamps = stamps.copyOf(grown)
    }

    private companion object {
        val EMPTY_VALUES = arrayOfNulls<Any?>(0)
        val EMPTY_STAMPS = IntArray(0)
    }
}

/**
 * Per-line inlay tracking, split out of [LineRenderCache] so it's testable without a `TextMeasurer` (which
 * can't be constructed headlessly).
 *
 * The point of the per-line stamp: a single global counter (the original design) invalidated EVERY cached line
 * on any inlay edit — and the host re-anchors inlay offsets on every keystroke, so that re-shaped the whole
 * viewport on each key. With a per-line stamp, a hint change re-shapes only its own line.
 */
internal class InlayRevisions {
    private val overlay = LineOverlay<InlayPiece>()

    /** The unique stamp for [line]; 0 when the line has never carried an inlay. */
    fun stampOf(line: Int): Int = overlay.stampOf(line)

    /** [line]'s pieces in column order. Sorted once when adopted, not on every read — this is called several
     *  times per visible line per frame (it backs [LineRenderCache.rawToVisual]). */
    fun piecesFor(line: Int): List<InlayPiece> = overlay.valueAt(line)

    /** Adopt [newInlays], bumping the stamp for each line whose pieces changed, were added, or were removed. */
    fun update(newInlays: Map<Int, List<InlayPiece>>) = overlay.update(newInlays, ::inColumnOrder)

    /** Replace one line's pieces, bumping its stamp only if they changed. */
    fun setLine(line: Int, pieces: List<InlayPiece>) = overlay.setLine(line, pieces, ::inColumnOrder)

    private fun inColumnOrder(pieces: List<InlayPiece>): List<InlayPiece> =
        if (pieces.size < 2) pieces else pieces.sortedBy { it.col }

    /** Mirror a line splice so a moved line keeps its stamp and its pieces. */
    fun shift(fromOldLine: Int, delta: Int) = overlay.splice(fromOldLine, delta)

    fun clear() = overlay.clear()
}

/**
 * Per-line semantic-overlay tracking, the [SemSpan] analog of [InlayRevisions] — a unique-forever stamp per
 * line, bumped ONLY for lines whose spans changed, so an async semantic-highlight pass re-shapes only the
 * lines it actually recolored.
 */
internal class SemanticSpansByLine {
    private val overlay = LineOverlay<SemSpan>()

    fun stampOf(line: Int): Int = overlay.stampOf(line)
    fun spansFor(line: Int): List<SemSpan> = overlay.valueAt(line)

    /** Adopt [newSpans], bumping the stamp for each line whose spans changed, were added, or were removed. */
    fun update(newSpans: Map<Int, List<SemSpan>>) = overlay.update(newSpans)

    /** Replace one line's spans, bumping its stamp only if they changed. */
    fun setLine(line: Int, spans: List<SemSpan>) = overlay.setLine(line, spans)

    /** Mirror a line splice so a moved line keeps its stamp and its spans. */
    fun shift(fromOldLine: Int, delta: Int) = overlay.splice(fromOldLine, delta)

    fun clear() = overlay.clear()
}
