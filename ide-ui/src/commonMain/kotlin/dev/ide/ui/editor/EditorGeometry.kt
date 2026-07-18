package dev.ide.ui.editor

import androidx.compose.foundation.gestures.Scrollable2DState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.rememberScrollable2DState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import dev.ide.ui.backend.UiFileSymbol
import dev.ide.ui.editor.core.EditorCaretGeometry
import dev.ide.ui.editor.core.EditorImeHandle
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.WrapModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.floor
import kotlin.math.max

/**
 * The editor's viewport geometry: the scroll offsets, the word-wrap projection ([WrapModel] + [VLayout]), and
 * the coordinate helpers that map between viewport pixels and document offsets (caret placement, tap → offset,
 * fold hit-testing, sticky-header hit-testing). Pulled out of [CodeEditor] to keep its emission composable under
 * ART's per-method instruction limit.
 *
 * Reads its render inputs (metrics, gutter width, the render cache) LIVE through [render], which
 * [rememberEditorGeometry] re-points every recomposition — so a zoom (which rebuilds [EditorRenderState]) is
 * picked up without recreating this holder, keeping the scroll offset put across a zoom.
 */
@Stable
internal class EditorGeometry(private val session: EditorSession) {
    lateinit var render: EditorRenderState
    lateinit var viewport: MutableState<IntSize>
    /** The editor content's offset within the IME root view, for CursorAnchorInfo in view-pixel space. */
    lateinit var contentInWindow: MutableState<Offset>
    lateinit var vOffset: MutableFloatState
    lateinit var hOffset: MutableFloatState
    lateinit var vScroll: ScrollableState
    lateinit var hScroll: ScrollableState
    lateinit var scroll2D: Scrollable2DState
    /** The file's declarations, fetched debounced by the host; drives sticky headers + their hit-testing. */
    lateinit var editorStructure: MutableState<List<UiFileSymbol>>

    private lateinit var wrapModel: WrapModel

    // ---- per-frame inputs, refreshed by rememberEditorGeometry ----
    var wordWrap: Boolean = false
    var wrapActive: Boolean = false
    var wrapWidthPx: Int = 0
    var wrapCols: Int = 0
    var wrapIndentActive: Boolean = false
    var wrapMaxIndentCols: Int = 0
    var wrapExtraIndentCols: Int = 4

    private val metrics get() = render.metrics
    private val gutterWidthPx get() = render.gutterWidthPx

    // The vertical projection the renderer + geometry share. `correctRange` shapes the on-screen lines and
    // records their exact wrapped height (the draw calls it before positioning), so what's visible is
    // pixel-aligned while off-screen rows ride the estimate.
    // IMPORTANT: read the fold model LIVE (`session.foldModel`), not a captured snapshot — the gesture
    // detectors hold this object across recompositions, so a captured fold model would map taps through a stale
    // line count after an edit. The session + wrapModel are stable instances; their mutable state is read fresh.
    val vlayout: VLayout = object : VLayout {
        private val fold get() = session.foldModel
        override val totalRows: Int
            get() = if (wrapActive) {
                wrapModel.ensure(fold); wrapModel.totalRows
            } else fold.visualLineCount

        override fun topRow(line: Int): Int =
            if (wrapActive) {
                wrapModel.ensure(fold); wrapModel.topRow(line)
            } else fold.visualForDocLine(line)

        override fun rowsOf(line: Int): Int {
            val f = fold
            return when {
                wrapActive -> {
                    wrapModel.ensure(f); wrapModel.rowsOf(line)
                }

                f.isHidden(line) -> 0
                else -> 1
            }
        }

        override fun docLineForRow(row: Int): Int =
            if (wrapActive) {
                wrapModel.ensure(fold); wrapModel.docLineForRow(row)
            } else fold.docLineForVisual(row)

        override fun correctRange(first: Int, last: Int) {
            if (!wrapActive) return
            val f = fold
            var ln = first.coerceAtLeast(0)
            val end = last.coerceAtMost(session.doc.lineCount - 1)
            while (ln <= end) {
                if (!f.isHidden(ln) && f.foldStartingAt(ln) == null)
                    wrapModel.setRows(ln, render.layoutFor(ln).lineCount)
                ln++
            }
            wrapModel.ensure(f)
        }
    }

    fun contentHeight() = metrics.padTop + vlayout.totalRows * metrics.lineHeight + metrics.padBottom

    fun contentWidth() = if (wrapActive) viewport.value.width.toFloat()
    else metrics.padLeft + max(
        render.renderCache.measuredMaxWidth,
        session.maxLineChars * metrics.charWidth,
    ) + metrics.padRight

    fun maxV() = (contentHeight() - viewport.value.height).coerceAtLeast(0f)
    fun maxH() =
        if (wrapActive) 0f else (contentWidth() - (viewport.value.width - gutterWidthPx)).coerceAtLeast(0f)

    // Cheap monospace estimate of a line's wrap-row count — count display columns (tabs to 4-col stops); the
    // first row holds `wrapCols`, continuation rows hold `wrapCols - indent`. No text shaping; on-screen lines
    // are corrected to the exact lineCount in [VLayout.correctRange], so this only sizes the OFF-screen extent.
    fun estimateWrapRows(line: Int): Int {
        if (wrapCols <= 0) return 1
        val d = session.doc
        val end = d.lineEnd(line)
        var cols = 0
        var leading = -1
        var i = d.lineStart(line)
        while (i < end) {
            val ch = d.charAt(i)
            if (leading < 0 && ch != ' ' && ch != '\t') leading = cols
            cols += if (ch == '\t') 4 - (cols % 4) else 1
            i++
        }
        if (cols <= wrapCols) return 1
        val indent =
            if (wrapIndentActive) ((if (leading < 0) cols else leading) + wrapExtraIndentCols).coerceAtMost(wrapMaxIndentCols)
            else 0
        val contCols = (wrapCols - indent).coerceAtLeast(1)
        return 1 + (cols - wrapCols + contCols - 1) / contCols
    }

    // ---- geometry helpers (viewport coordinates ↔ document offsets) — routed through [vlayout] so a collapsed
    // region occupies one visual row and a wrapped line occupies several; within a line, the line's own
    // TextLayoutResult resolves the wrapped sub-row (getLineForOffset/getLineTop) and the x position ----
    fun lineTop(line: Int) =
        metrics.padTop + vlayout.topRow(line) * metrics.lineHeight - vOffset.floatValue

    fun textLeft() = gutterWidthPx + metrics.padLeft - hOffset.floatValue

    /** line, xInViewport, topInViewport (of the sub-row). */
    fun caretGeometry(offset: Int): Triple<Int, Float, Float> {
        val doc = session.doc
        val line = doc.lineForOffset(offset)
        val vcol = render.renderCache.rawToVisual(line, offset - doc.lineStart(line))
        val layout = render.layoutFor(line)
        val x = layout.getHorizontalPosition(vcol, usePrimaryDirection = true)
        val subTop = if (wordWrap) layout.getLineTop(layout.getLineForOffset(vcol)) else 0f
        return Triple(line, textLeft() + x, lineTop(line) + subTop)
    }

    /** The document line shown at viewport [y] (the fold-start line when [y] is on a collapsed row). */
    fun lineAtY(y: Float): Int {
        val row = floor((y + vOffset.floatValue - metrics.padTop) / metrics.lineHeight)
            .toInt().coerceIn(0, (vlayout.totalRows - 1).coerceAtLeast(0))
        return vlayout.docLineForRow(row)
    }

    /** If [pos] lands on a pinned sticky-header row, the declaration it stands for (to jump to); else null.
     *  Mirrors the renderer's [stickyHeaderItems] so the hit-test and the drawn rows always agree. */
    fun stickyHeaderHit(pos: Offset): UiFileSymbol? {
        val structure = editorStructure.value
        if (structure.isEmpty()) return null
        val firstVisibleLine = lineAtY(0f)
        if (firstVisibleLine <= 0) return null
        val sticky = stickyHeaderItems(structure, firstVisibleLine, session.doc, STICKY_MAX)
        if (sticky.isEmpty()) return null
        return sticky.getOrNull(floor(pos.y / metrics.lineHeight).toInt())
    }

    /** Handle a tap that targets folding: the gutter fold strip toggles the line's fold; tapping a collapsed
     *  line's placeholder (the dimmed `...` past the visible prefix) expands it. Returns true when handled. */
    fun foldActionAt(pos: Offset): Boolean {
        val doc = session.doc
        val line = lineAtY(pos.y)
        val startsFold = session.foldRegions.any { doc.lineForOffset(it.start) == line }
        if (startsFold && pos.x >= gutterWidthPx - render.foldStripPx && pos.x < gutterWidthPx) {
            return session.toggleFoldAtLine(line)
        }
        val info = session.foldModel.foldStartingAt(line)
        if (info != null && pos.x >= textLeft()) {
            val prefixCols = (info.prefixEnd - doc.lineStart(line)).coerceAtLeast(0)
            val prefixX = textLeft() + render.compositeLayoutFor(line)
                .getHorizontalPosition(prefixCols, usePrimaryDirection = true)
            if (pos.x >= prefixX) return session.toggleFoldAtLine(line)
        }
        return false
    }

    fun offsetAt(pos: Offset): Int {
        val doc = session.doc
        val line = lineAtY(pos.y)
        val xInLine = pos.x - textLeft()
        val layout = render.layoutFor(line)
        // y within the line's paragraph picks the wrapped sub-row; mid-row when not wrapping (single row).
        val yInLine = if (wrapActive)
            ((pos.y + vOffset.floatValue - metrics.padTop) - vlayout.topRow(line) * metrics.lineHeight)
                .coerceIn(0f, (layout.size.height - 1f).coerceAtLeast(0f))
        else metrics.lineHeight / 2f
        val visualCol = layout.getOffsetForPosition(Offset(xInLine.coerceAtLeast(0f), yInLine))
        val col = render.renderCache.visualToRaw(line, visualCol)
        // On a collapsed fold-start line only the prefix (text before the fold) is real; clamp the caret there.
        val maxCol = session.foldModel.foldStartingAt(line)?.let { it.prefixEnd - doc.lineStart(line) }
            ?: doc.lineLength(line)
        return doc.lineStart(line) + col.coerceAtMost(maxCol)
    }

    /** The caret's target position in CONTENT space (scroll-independent) for the caret glide animation. Uses the
     *  VISUAL row (folds above it shrink Y) plus the wrapped sub-row within the line. */
    fun caretTargetContent(): Offset {
        val off = session.selection.end
        val d = session.doc
        val ln = d.lineForOffset(off)
        val vcol = render.renderCache.rawToVisual(ln, off - d.lineStart(ln))
        val layout = render.layoutFor(ln)
        val x = gutterWidthPx + metrics.padLeft + layout.getHorizontalPosition(vcol, usePrimaryDirection = true)
        val subTop = if (wordWrap) layout.getLineTop(layout.getLineForOffset(vcol)) else 0f
        return Offset(x, metrics.padTop + vlayout.topRow(ln) * metrics.lineHeight + subTop)
    }

    internal fun setWrapModel(m: WrapModel) {
        wrapModel = m
    }
}

@Composable
internal fun rememberEditorGeometry(
    session: EditorSession,
    render: EditorRenderState,
    editorIme: EditorImeHandle,
    wordWrap: Boolean,
    wrapIndent: Boolean,
): EditorGeometry {
    val density = LocalDensity.current
    val state = remember(session) { EditorGeometry(session) }
    state.render = render
    state.wordWrap = wordWrap

    state.viewport = remember { mutableStateOf(IntSize.Zero) }
    state.contentInWindow = remember { mutableStateOf(Offset.Zero) }
    state.vOffset = remember(session) { mutableFloatStateOf(0f) }
    state.hOffset = remember(session) { mutableFloatStateOf(0f) }
    state.editorStructure = remember(session) { mutableStateOf(emptyList()) }

    // ---- soft wrap: a variable-height vertical projection, engaged only when [wordWrap] is on ----
    // Off, the editor keeps its O(1) one-row-per-line geometry (the [FoldModel] path); on, [WrapModel] turns
    // per-line wrap-row counts into a fold-aware row prefix sum.
    val wrapModel = remember(session) { WrapModel() }
    state.setWrapModel(wrapModel)
    val metrics = render.metrics
    val gutterWidthPx = render.gutterWidthPx
    val viewportWidth = state.viewport.value.width
    val wrapWidthPx = if (wordWrap && viewportWidth > 0)
        (viewportWidth - gutterWidthPx - metrics.padLeft - metrics.padRight).toInt()
            .coerceAtLeast(maxOf(1, (metrics.charWidth * 8f).toInt()))
    else 0
    render.renderCache.setWrapWidth(wrapWidthPx)
    val wrapCols = if (wrapWidthPx > 0) (wrapWidthPx / metrics.charWidth).toInt().coerceAtLeast(1) else 0
    // Smart wrap indent (IntelliJ-style): continuation rows align under the line's own indent. Capped at half
    // the wrap width so wrapped text always keeps room. The indent is applied during measurement in the line
    // cache (TextIndent.restLine), so all caret/tap/selection geometry tracks it automatically.
    val wrapIndentActive = wordWrap && wrapIndent && wrapWidthPx > 0
    val wrapMaxIndentCols = if (wrapCols > 0) wrapCols / 2 else 0
    val wrapExtraIndentCols = 4
    render.renderCache.setWrapIndent(
        wrapIndentActive,
        with(density) { metrics.charWidth.toSp() },
        wrapMaxIndentCols,
        wrapExtraIndentCols,
    )
    state.wrapWidthPx = wrapWidthPx
    state.wrapCols = wrapCols
    state.wrapIndentActive = wrapIndentActive
    state.wrapMaxIndentCols = wrapMaxIndentCols
    state.wrapExtraIndentCols = wrapExtraIndentCols
    // Wrap geometry is "active" only once the viewport has been measured (wrapWidthPx > 0). Until then — the
    // first frame after open/rotate — fall back to the fold model's one-row-per-line mapping.
    state.wrapActive = wrapWidthPx > 0

    // (Re-)seed estimates on width / indent change and on STRUCTURAL edits (line add/remove → indices shift, so
    // a fresh estimate realigns them). Deliberately NOT keyed on textRevision: a same-line edit must NOT
    // re-estimate, because that would overwrite the exact wrap heights `correctRange` recorded for the visible
    // lines. The edited line's own height is re-corrected by the next draw (it's visible); lines above the caret
    // are untouched, so the caret's row stays exact.
    val wrapEstimateSig = remember(session) { mutableStateOf<Any?>(null) }
    if (wordWrap && wrapWidthPx > 0) {
        wrapModel.resize(session.doc.lineCount)
        val sig = Triple(wrapWidthPx, session.doc.lineCount, wrapIndentActive)
        if (wrapEstimateSig.value != sig) {
            wrapEstimateSig.value = sig
            for (ln in 0 until session.doc.lineCount) wrapModel.setRows(ln, state.estimateWrapRows(ln))
        }
    }

    // ---- scrolling: plain offsets read in the draw phase (a fling never recomposes) ----
    val vOffset = state.vOffset
    val hOffset = state.hOffset
    state.vScroll = rememberScrollableState { delta ->
        val old = vOffset.floatValue
        val new = (old + delta).coerceIn(0f, state.maxV())
        vOffset.floatValue = new
        new - old
    }
    state.hScroll = rememberScrollableState { delta ->
        val old = hOffset.floatValue
        val new = (old + delta).coerceIn(0f, state.maxH())
        hOffset.floatValue = new
        new - old
    }
    // Two-axis (free) scrolling: one state pans both offsets from a single drag, so a diagonal swipe moves
    // vertically and horizontally at once. `scrollable2D` has no `reverseDirection`, so the natural-scroll sign
    // is applied here; the consumed Offset is reported back in the input frame for fling/nested-scroll.
    state.scroll2D = rememberScrollable2DState { delta ->
        val oldV = vOffset.floatValue
        val newV = (oldV - delta.y).coerceIn(0f, state.maxV())
        vOffset.floatValue = newV
        val oldH = hOffset.floatValue
        val newH = (oldH - delta.x).coerceIn(0f, state.maxH())
        hOffset.floatValue = newH
        Offset(oldH - newH, oldV - newV)
    }

    // A zoom rescales the line metrics (hence the content size), and the viewport changes on resize/rotate; re-
    // clamp the scroll so a zoom-out can't strand the viewport past the document end. Re-clamp on fold changes
    // too: collapsing a region shrinks the content height without changing the line count.
    LaunchedEffect(
        render.codeStyle,
        session.doc.lineCount,
        session.foldRegions,
        state.viewport.value,
        wordWrap,
        wrapWidthPx,
        wrapIndentActive,
    ) {
        if (wordWrap) hOffset.floatValue = 0f // wrapped: there is no horizontal scroll
        vOffset.floatValue = vOffset.floatValue.coerceIn(0f, state.maxV())
        hOffset.floatValue = hOffset.floatValue.coerceIn(0f, state.maxH())
    }

    // If the caret lands inside a collapsed fold (go-to-definition, rename, programmatic navigation), reveal it.
    LaunchedEffect(session.selection) {
        session.expandFoldAt(session.selection.end)
    }

    // Feed the platform IME bridge the caret's pixel geometry for CursorAnchorInfo (the keyboard positions its
    // floating UI / handwriting box from it). Viewport coordinates are offset by the content's window position so
    // they land in view-pixel space; best-effort (null when layout isn't ready) and only read by the Android node.
    DisposableEffect(editorIme) {
        editorIme.caretGeometryProvider = {
            runCatching {
                val (_, x, top) = state.caretGeometry(session.selection.min)
                val base = state.contentInWindow.value
                val lineHeight = state.render.metrics.lineHeight // read live: a zoom rescales metrics
                EditorCaretGeometry(
                    horizontalPosition = base.x + x,
                    top = base.y + top,
                    baseline = base.y + top + lineHeight,
                    bottom = base.y + top + lineHeight,
                )
            }.getOrNull()
        }
        onDispose { editorIme.caretGeometryProvider = null }
    }

    // ---- bring the caret into view after every edit/caret move ----
    LaunchedEffect(session.editCount, state.viewport.value) {
        if (state.viewport.value == IntSize.Zero) return@LaunchedEffect
        val doc = session.doc
        val line = doc.lineForOffset(session.selection.end)
        val vcol = render.renderCache.rawToVisual(line, session.selection.end - doc.lineStart(line))
        val layout = render.layoutFor(line)
        val subTop = if (wordWrap) layout.getLineTop(layout.getLineForOffset(vcol)) else 0f
        val top = metrics.padTop + state.vlayout.topRow(line) * metrics.lineHeight + subTop
        val bottom = top + metrics.lineHeight
        val vh = state.viewport.value.height.toFloat()
        if (top < vOffset.floatValue) vOffset.floatValue = (top - metrics.lineHeight).coerceIn(0f, state.maxV())
        else if (bottom > vOffset.floatValue + vh) vOffset.floatValue =
            (bottom - vh + metrics.lineHeight).coerceIn(0f, state.maxV())
        // Horizontal follow only when not wrapping — a wrapped buffer never scrolls sideways.
        if (!wordWrap) {
            val caretX = layout.getHorizontalPosition(vcol, usePrimaryDirection = true)
            val textViewW = state.viewport.value.width - gutterWidthPx - metrics.padLeft
            val margin = metrics.charWidth * 3
            if (caretX < hOffset.floatValue + margin) hOffset.floatValue = (caretX - margin).coerceIn(0f, state.maxH())
            else if (caretX > hOffset.floatValue + textViewW - margin) {
                hOffset.floatValue = (caretX - textViewW + margin).coerceIn(0f, state.maxH())
            }
        }
    }

    // ---- restore + track the persisted scroll position (see EditorSession.viewportTopLine) ----
    // The restore target is captured ONCE at first composition, so the live tracker below can't clobber it
    // before it's applied. Once the viewport is first measured, scroll so that saved first-visible line sits at
    // the top. Declared AFTER the bring-caret-into-view effect so, in the rare case the caret was off-screen, it
    // wins the tie and honors the saved viewport. Best-effort — folds/wrap can shift it a little.
    val restoreScrollLine = remember(session) { session.viewportTopLine }
    val scrollRestored = remember(session) { mutableStateOf(false) }
    LaunchedEffect(session, state.viewport.value) {
        if (scrollRestored.value || state.viewport.value == IntSize.Zero) return@LaunchedEffect
        if (restoreScrollLine > 0) {
            val top = metrics.padTop + state.vlayout.topRow(restoreScrollLine) * metrics.lineHeight
            vOffset.floatValue = top.coerceIn(0f, state.maxV())
        }
        scrollRestored.value = true
    }
    // Keep the session's scroll anchor current as the user scrolls, so the next session save persists it. A
    // fling changes vOffset every frame; debounce to the settled top line (a plain snapshot Int write, no
    // per-frame cost, and it only re-emits the host's save when the top line actually changes).
    LaunchedEffect(session) {
        snapshotFlow { vOffset.floatValue }.collectLatest {
            delay(200)
            if (state.viewport.value != IntSize.Zero) {
                session.viewportTopLine = state.lineAtY(0f).coerceAtLeast(0)
            }
        }
    }
    return state
}
