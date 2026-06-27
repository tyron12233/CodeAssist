package dev.ide.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The pure canvas-drawing layer of the code editor: it owns NO state — given the session, the resolved
// metrics/colors, and the per-line layout lambdas, it paints one frame (gutter + text + decorations) onto the
// single editor canvas. Split out of CodeEditor.kt so the (large, hot) draw path reads on its own.

internal class EditorMetrics(
    val lineHeight: Float,
    val charWidth: Float,
    val padTop: Float,
    val padLeft: Float,
    val padRight: Float,
    val padBottom: Float,
)

internal class EditorDrawColors(
    val background: Color,
    val currentLine: Color,
    val caret: Color,
    val selection: Color,
    val gutterText: Color,
    val gutterCurrent: Color,
    val gutterBorder: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val muted: Color,
    val composing: Color,
    val indentGuide: Color,
    val findMatch: Color,
    val findCurrent: Color,
)

internal class DiagSeg(val startCol: Int, val endCol: Int, val severity: UiSeverity, val unused: Boolean)

internal fun mapDiagnosticsToLines(diagnostics: List<UiDiagnostic>, doc: EditorDocument): Map<Int, List<DiagSeg>> {
    if (diagnostics.isEmpty()) return emptyMap()
    val out = HashMap<Int, MutableList<DiagSeg>>()
    for (d in diagnostics) {
        // all severities get a squiggle (coloured per severity in the draw phase); the gutter glyph
        // below still only lights for Error/Warning.
        val s = d.startOffset.coerceIn(0, doc.length)
        val e = d.endOffset.coerceIn(s, doc.length)
        if (e <= s) continue
        val startLine = doc.lineForOffset(s)
        val endLine = min(doc.lineForOffset(e), startLine + 200) // bound degenerate whole-file spans
        for (ln in startLine..endLine) {
            val segS = if (ln == startLine) s - doc.lineStart(ln) else 0
            val segE = if (ln == endLine) e - doc.lineStart(ln) else doc.lineLength(ln)
            if (segE <= segS) continue
            out.getOrPut(ln) { ArrayList(2) }.add(DiagSeg(segS, segE, d.severity, d.unused))
        }
    }
    return out
}

/**
 * The vertical projection the editor + renderer share: document lines ⇄ visual rows. When not wrapping it is
 * the [dev.ide.ui.editor.folding.FoldModel]'s one-row-per-visible-line mapping; when wrapping, a line spans
 * several rows ([rowsOf]) and [topRow] is the cumulative-row offset. [correctRange] shapes the on-screen lines
 * and records their exact wrapped height so the frame is pixel-aligned (a no-op when not wrapping).
 */
internal interface VLayout {
    val totalRows: Int
    fun topRow(line: Int): Int
    fun rowsOf(line: Int): Int
    fun docLineForRow(row: Int): Int
    fun correctRange(first: Int, last: Int)
}

internal fun DrawScope.drawEditor(
    session: EditorSession,
    metrics: EditorMetrics,
    gutterWidth: Float,
    vOff: Float,
    hOff: Float,
    layoutFor: (Int) -> TextLayoutResult,
    compositeLayoutFor: (Int) -> TextLayoutResult,
    rawToVisual: (Int, Int) -> Int,
    foldModel: dev.ide.ui.editor.folding.FoldModel,
    vlayout: VLayout,
    wrap: Boolean,
    foldableStartLines: Set<Int>,
    foldStripWidth: Float,
    hoveredLine: Int,
    numberLayout: (Int) -> TextLayoutResult,
    diagByLine: Map<Int, List<DiagSeg>>,
    bracketPair: Pair<Int, Int>?,
    findMatches: List<Match>,
    currentMatch: Int,
    colors: EditorDrawColors,
    caretVisible: Boolean,
    caretContent: Offset,
    handlesVisible: Boolean,
    handleColor: Color,
) {
    val doc = session.doc
    val sel = session.selection
    val lineH = metrics.lineHeight
    // Viewport bounds are visual ROWS (folds compress, wrap expands); map back to the doc lines they show.
    // When wrapping, shape the on-screen lines first so their exact wrapped heights feed the row map before we
    // position anything this frame (off-screen rows keep their cheap estimate).
    if (wrap) {
        val aTop = floor((vOff - metrics.padTop) / lineH).toInt().coerceAtLeast(0)
        val aBot = ((vOff + size.height - metrics.padTop) / lineH).toInt().coerceAtMost((vlayout.totalRows - 1).coerceAtLeast(0))
        vlayout.correctRange(vlayout.docLineForRow(aTop), vlayout.docLineForRow(aBot) + 2)
    }
    val topRow = floor((vOff - metrics.padTop) / lineH).toInt().coerceAtLeast(0)
    val botRow = ((vOff + size.height - metrics.padTop) / lineH).toInt().coerceAtMost((vlayout.totalRows - 1).coerceAtLeast(0))
    val firstVisible = vlayout.docLineForRow(topRow)
    val lastVisible = vlayout.docLineForRow(botRow)
    // The DISTINCT document lines actually ON SCREEN — iterated by the per-line draw loops. With wrap (or
    // folds) several rows map to one line, so dedupe consecutive repeats. `botRow` is clamped to the current
    // row count while `topRow` follows the raw scroll offset, so a shrunk document under a stale scroll can
    // leave botRow < topRow for a frame: treat it as nothing visible.
    val visibleLines = when {
        botRow < topRow -> emptyList()
        !wrap && !foldModel.hasFolds -> (firstVisible..lastVisible).toList()
        else -> {
            val out = ArrayList<Int>(botRow - topRow + 1)
            var prev = -1
            var r = topRow
            while (r <= botRow) { val ln = vlayout.docLineForRow(r); if (ln != prev) { out.add(ln); prev = ln }; r++ }
            out
        }
    }
    val textLeft = gutterWidth + metrics.padLeft - hOff
    fun lineTop(line: Int) = metrics.padTop + vlayout.topRow(line) * lineH - vOff
    fun xOf(line: Int, offset: Int): Float =
        textLeft + layoutFor(line).getHorizontalPosition(rawToVisual(line, offset - doc.lineStart(line)), usePrimaryDirection = true)
    // Viewport-Y of the TOP of the wrapped sub-row holding [offset] on [line] (== lineTop when not wrapping).
    fun yTopOf(line: Int, offset: Int): Float {
        if (!wrap) return lineTop(line)
        val l = layoutFor(line)
        return lineTop(line) + l.getLineTop(l.getLineForOffset(rawToVisual(line, offset - doc.lineStart(line))))
    }
    // Fill the visual columns [vStart, vEnd] on [line] with [color], one rect per wrapped sub-row. vEnd == -1
    // means "to the end of the line" (interior lines of a multi-line selection/match); [trailingMarker] adds a
    // sliver past the last row's end to mark a selected line break. Reduces to one rect when not wrapping.
    fun fillRange(line: Int, vStart: Int, vEnd: Int, color: Color, trailingMarker: Boolean) {
        val l = layoutFor(line)
        val top = lineTop(line)
        val marker = if (trailingMarker) metrics.charWidth * 0.6f else 0f
        if (!wrap) {
            val x0 = textLeft + l.getHorizontalPosition(vStart, usePrimaryDirection = true)
            val x1 = if (vEnd >= 0) textLeft + l.getHorizontalPosition(vEnd, usePrimaryDirection = true)
            else textLeft + l.size.width + marker
            if (x1 > x0) drawRect(color, Offset(x0, top), Size(x1 - x0, lineH))
            return
        }
        val firstSub = l.getLineForOffset(vStart)
        val lastSub = if (vEnd >= 0) l.getLineForOffset(vEnd) else (l.lineCount - 1).coerceAtLeast(0)
        for (s in firstSub..lastSub) {
            val x0 = textLeft + (if (s == firstSub) l.getHorizontalPosition(vStart, usePrimaryDirection = true) else l.getLineLeft(s))
            val x1 = textLeft + when {
                s == lastSub && vEnd >= 0 -> l.getHorizontalPosition(vEnd, usePrimaryDirection = true)
                else -> l.getLineRight(s) + if (s == lastSub) marker else 0f
            }
            if (x1 > x0) drawRect(color, Offset(x0, top + s * lineH), Size(x1 - x0, lineH))
        }
    }

    val caretLine = doc.lineForOffset(sel.end)
    val caretSubRow = if (wrap) {
        val l = layoutFor(caretLine)
        l.getLineForOffset(rawToVisual(caretLine, sel.end - doc.lineStart(caretLine)))
    } else 0

    // current-line band across the full width (incl. gutter; gutter bg repaints its slice below) — on the
    // caret's wrapped sub-row when wrapping.
    if (sel.collapsed) {
        drawRect(colors.currentLine, Offset(0f, lineTop(caretLine) + caretSubRow * lineH), Size(size.width, lineH))
    }

    clipRect(left = gutterWidth, top = 0f, right = size.width, bottom = size.height) {
        // find-match highlights (under the selection/text): every match tinted, the current one stronger.
        if (findMatches.isNotEmpty()) {
            for ((idx, m) in findMatches.withIndex()) {
                val sLine = doc.lineForOffset(m.start)
                val eLine = doc.lineForOffset(m.end)
                if (eLine < firstVisible || sLine > lastVisible) continue
                val color = if (idx == currentMatch) colors.findCurrent else colors.findMatch
                for (line in max(sLine, firstVisible)..min(eLine, lastVisible)) {
                    if (foldModel.isHidden(line)) continue
                    val vStart = if (line == sLine) rawToVisual(line, m.start - doc.lineStart(line)) else 0
                    val vEnd = if (line == eLine) rawToVisual(line, m.end - doc.lineStart(line)) else -1
                    fillRange(line, vStart, vEnd, color, trailingMarker = false)
                }
            }
        }

        // selection background
        if (!sel.collapsed) {
            val sLine = doc.lineForOffset(sel.min)
            val eLine = doc.lineForOffset(sel.max)
            for (line in max(sLine, firstVisible)..min(eLine, lastVisible)) {
                if (foldModel.isHidden(line)) continue
                val vStart = if (line == sLine) rawToVisual(line, sel.min - doc.lineStart(line)) else 0
                val vEnd = if (line == eLine) rawToVisual(line, sel.max - doc.lineStart(line)) else -1
                fillRange(line, vStart, vEnd, colors.selection, trailingMarker = true) // marker = selected line break
            }
        }

        // indent guides ("bracket lines") — a faint vertical at each 4-column indent level, bridged across
        // blank lines so a guide spans a block's empty rows. Drawn under the text.
        run {
            val unit = 4
            fun indentCols(line: Int): Int {
                val end = doc.lineEnd(line)
                var i = doc.lineStart(line)
                var c = 0
                while (i < end) {
                    when (doc.charAt(i)) {
                        ' ' -> c++
                        '\t' -> c += unit
                        else -> return c
                    }
                    i++
                }
                return -1 // blank line
            }
            for (line in visibleLines) {
                var cols = indentCols(line)
                if (cols < 0) { // blank: bridge with the shallower of the nearest non-blank neighbours
                    var up = line - 1
                    while (up >= 0 && indentCols(up) < 0) up--
                    var dn = line + 1
                    while (dn < doc.lineCount && indentCols(dn) < 0) dn++
                    val a = if (up >= 0) indentCols(up) else 0
                    val b = if (dn < doc.lineCount) indentCols(dn) else 0
                    cols = min(a, b)
                }
                val spanH = (if (wrap) vlayout.rowsOf(line).coerceAtLeast(1) else 1) * lineH
                var level = unit
                while (level < cols) {
                    val x = textLeft + level * metrics.charWidth
                    if (x >= gutterWidth) {
                        drawLine(colors.indentGuide, Offset(x, lineTop(line)), Offset(x, lineTop(line) + spanH), strokeWidth = 1f)
                    }
                    level += unit
                }
            }
        }

        // text — cached per-line layouts; only a cache miss (edited or newly-visible line) shapes text. A line
        // hidden inside a collapsed fold is skipped; the line that STARTS a collapsed fold draws its composite
        // (`prefix + placeholder + suffix`) instead of its raw text.
        for (line in visibleLines) {
            if (foldModel.foldStartingAt(line) != null) {
                drawText(compositeLayoutFor(line), topLeft = Offset(textLeft, lineTop(line)))
            } else if (doc.lineLength(line) != 0) {
                drawText(layoutFor(line), topLeft = Offset(textLeft, lineTop(line)))
            }
        }

        // IME composing underline
        session.composing?.let { comp ->
            val cs = doc.lineForOffset(comp.min)
            val ce = doc.lineForOffset(comp.max)
            for (line in max(cs, firstVisible)..min(ce, lastVisible)) {
                if (foldModel.isHidden(line)) continue
                val l = layoutFor(line)
                val vStart = if (line == cs) rawToVisual(line, comp.min - doc.lineStart(line)) else 0
                val vEnd = if (line == ce) rawToVisual(line, comp.max - doc.lineStart(line)) else -1
                val firstSub = if (wrap) l.getLineForOffset(vStart) else 0
                val lastSub = if (wrap) (if (vEnd >= 0) l.getLineForOffset(vEnd) else (l.lineCount - 1).coerceAtLeast(0)) else 0
                for (s in firstSub..lastSub) {
                    val x0 = textLeft + (if (s == firstSub) l.getHorizontalPosition(vStart, usePrimaryDirection = true) else l.getLineLeft(s))
                    val x1 = textLeft + when {
                        s == lastSub && vEnd >= 0 -> l.getHorizontalPosition(vEnd, usePrimaryDirection = true)
                        wrap -> l.getLineRight(s)
                        else -> l.size.width.toFloat()
                    }
                    if (x1 > x0) {
                        val y = lineTop(line) + s * lineH + lineH - 2f
                        drawLine(colors.composing, Offset(x0, y), Offset(x1, y), strokeWidth = 1.5f)
                    }
                }
            }
        }

        // diagnostic squiggles
        for (line in visibleLines) {
            val segs = diagByLine[line] ?: continue
            val layout = layoutFor(line)
            val maxCol = doc.lineLength(line)
            for (seg in segs) {
                val color = when (seg.severity) {
                    UiSeverity.Error -> colors.error
                    UiSeverity.Warning -> if (seg.unused) colors.muted else colors.warning
                    UiSeverity.Info -> colors.info
                    UiSeverity.Hint -> colors.muted
                }
                val c0 = seg.startCol.coerceIn(0, maxCol)
                val c1 = seg.endCol.coerceIn(c0, maxCol)
                if (c1 <= c0) continue
                val v0 = rawToVisual(line, c0)
                val v1 = rawToVisual(line, c1)
                if (!wrap) {
                    val x0 = textLeft + layout.getHorizontalPosition(v0, usePrimaryDirection = true)
                    val x1 = textLeft + layout.getHorizontalPosition(v1, usePrimaryDirection = true)
                    wavyUnderline(color, x0, x1, lineTop(line) + lineH - 2f)
                } else {
                    val firstSub = layout.getLineForOffset(v0)
                    val lastSub = layout.getLineForOffset(v1)
                    for (s in firstSub..lastSub) {
                        val x0 = textLeft + (if (s == firstSub) layout.getHorizontalPosition(v0, usePrimaryDirection = true) else layout.getLineLeft(s))
                        val x1 = textLeft + (if (s == lastSub) layout.getHorizontalPosition(v1, usePrimaryDirection = true) else layout.getLineRight(s))
                        wavyUnderline(color, x0, x1, lineTop(line) + s * lineH + lineH - 2f)
                    }
                }
            }
        }

        // matching-bracket boxes
        bracketPair?.let { (open, close) ->
            for (off in intArrayOf(open, close)) {
                if (off < 0 || off >= doc.length) continue
                val line = doc.lineForOffset(off)
                if (line !in firstVisible..lastVisible) continue
                val x0 = xOf(line, off)
                val x1 = xOf(line, off + 1)
                drawRect(
                    color = colors.caret.copy(alpha = 0.45f),
                    topLeft = Offset(x0, yTopOf(line, off)),
                    size = Size(x1 - x0, lineH),
                    style = Stroke(width = 1f),
                )
            }
        }

        // caret — drawn at the animated content position (minus scroll), so it glides to a new spot
        if (caretVisible && sel.collapsed) {
            val cx = caretContent.x - hOff
            val cy = caretContent.y - vOff
            if (cy + lineH > 0f && cy < size.height) {
                drawRect(colors.caret, Offset(cx - 1f, cy), Size(2.dp.toPx(), lineH))
            }
        }
    }

    // gutter: opaque background over anything scrolled beneath it, then the band slice + numbers
    drawRect(colors.background, Offset(0f, 0f), Size(gutterWidth, size.height))
    if (sel.collapsed && caretLine in firstVisible..lastVisible) {
        drawRect(colors.currentLine, Offset(0f, lineTop(caretLine) + caretSubRow * lineH), Size(gutterWidth, lineH))
    }
    val dotR = 2.5.dp.toPx()
    // The fold strip occupies the inner [gutterWidth - foldStripWidth, gutterWidth) band; numbers right-align
    // just left of it so a chevron never overlaps a number.
    val numberRight = gutterWidth - foldStripWidth - 4.dp.toPx()
    val chevronCx = gutterWidth - foldStripWidth / 2f
    for (line in visibleLines) {
        val segs = diagByLine[line]
        val hasError = segs?.any { it.severity == UiSeverity.Error } == true
        val hasWarning = !hasError && segs?.any { it.severity == UiSeverity.Warning } == true
        val numColor = when {
            hasError -> colors.error
            hasWarning -> colors.warning
            line == caretLine -> colors.gutterCurrent
            else -> colors.gutterText
        }
        if (hasError || hasWarning) {
            drawCircle(
                color = if (hasError) colors.error else colors.warning,
                radius = dotR,
                center = Offset(5.dp.toPx() + dotR, lineTop(line) + lineH / 2f),
            )
        }
        // Fold chevron: a collapsed fold always shows ▸ (so it can be re-expanded); an open foldable line
        // shows ▾ on the caret line and on mouse hover (IntelliJ-style — hover is a no-op on touch).
        val collapsed = foldModel.foldStartingAt(line) != null
        val expandable = !collapsed && line in foldableStartLines
        if (collapsed || (expandable && (line == caretLine || line == hoveredLine))) {
            drawFoldChevron(chevronCx, lineTop(line) + lineH / 2f, expanded = !collapsed, color = colors.gutterCurrent)
        }
        val num = numberLayout(line + 1)
        drawText(
            num,
            color = numColor,
            topLeft = Offset(
                numberRight - num.size.width,
                lineTop(line) + (lineH - num.size.height) / 2f,
            ),
        )
    }
    // A hairline at the gutter's right edge separates it from the code area.
    drawLine(colors.gutterBorder, Offset(gutterWidth, 0f), Offset(gutterWidth, size.height), strokeWidth = 1f)

    // touch selection handles (under the selection edges / the caret)
    if (handlesVisible) {
        val r = 6.dp.toPx()
        val points = if (sel.collapsed) listOf(sel.start) else listOf(sel.min, sel.max)
        for (off in points) {
            val line = doc.lineForOffset(off)
            if (line !in firstVisible..lastVisible) continue
            val x = xOf(line, off)
            if (x < gutterWidth) continue
            drawCircle(handleColor, r, Offset(x, yTopOf(line, off) + lineH + r * 0.8f))
        }
    }
}

/** A small fold chevron centered at ([cx], [cy]): ▾ when [expanded] (an open foldable line), ▸ when collapsed. */
private fun DrawScope.drawFoldChevron(cx: Float, cy: Float, expanded: Boolean, color: Color) {
    val r = 3.2.dp.toPx()
    val path = Path().apply {
        if (expanded) { // ▾ points down
            moveTo(cx - r, cy - r * 0.6f); lineTo(cx + r, cy - r * 0.6f); lineTo(cx, cy + r * 0.7f)
        } else {        // ▸ points right
            moveTo(cx - r * 0.6f, cy - r); lineTo(cx + r * 0.7f, cy); lineTo(cx - r * 0.6f, cy + r)
        }
        close()
    }
    drawPath(path, color.copy(alpha = 0.8f))
}

/** A squiggly underline from [x1] to [x2] at baseline [y] (a tight triangle wave reads as wavy). */
private fun DrawScope.wavyUnderline(color: Color, x1: Float, x2: Float, y: Float) {
    if (x2 <= x1) return
    val amplitude = 1.6f
    val step = 2.2f
    val path = Path().apply {
        moveTo(x1, y)
        var x = x1
        var up = true
        while (x < x2) {
            val nx = (x + step).coerceAtMost(x2)
            lineTo(nx, if (up) y - amplitude else y + amplitude)
            x = nx
            up = !up
        }
    }
    drawPath(path, color, style = Stroke(width = 1.4f))
}
