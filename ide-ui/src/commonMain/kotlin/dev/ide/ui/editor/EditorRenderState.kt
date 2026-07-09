package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import dev.ide.ui.theme.CaTypography
import dev.ide.ui.theme.CodeAssistColors

/**
 * The editor's text-metrics + per-line render state: the code/gutter [TextStyle]s, the measured
 * [EditorMetrics], the syntax [palette], the [LineRenderCache], and the on-demand fold-composite / gutter-number
 * layout caches. Pulled out of [CodeEditor] so its emission composable stays under ART's per-method instruction
 * limit (see the note at the bottom of CodeEditor.kt).
 *
 * The holder instance is stable per tab ([rememberEditorRenderState] keys it on the [EditorSession]); each field
 * is assigned every recomposition from a `remember` that keeps its ORIGINAL key, so a zoom rebuilds the styles /
 * metrics / render cache exactly as before while the scroll geometry (owned by [EditorGeometry]) is untouched.
 */
@Stable
internal class EditorRenderState(private val session: EditorSession) {
    lateinit var codeStyle: TextStyle
    lateinit var gutterStyle: TextStyle
    lateinit var metrics: EditorMetrics
    lateinit var palette: Array<SpanStyle?>
    lateinit var renderCache: LineRenderCache
    lateinit var foldPlaceholderStyle: SpanStyle

    /** Reserved width for the ▸/▾ fold chevrons on the inner edge of the gutter. */
    var foldStripPx: Float = 0f
    /** Gutter width in px (line-number digits + padding + [foldStripPx]); recomputed on line-count change. */
    var gutterWidthPx: Float = 0f
    /** Document lines that begin ANY fold region (collapsed or open) — drives the gutter chevrons. */
    var foldableStartLines: Set<Int> = emptySet()

    internal lateinit var measurer: TextMeasurer
    internal lateinit var compositeCache: HashMap<Int, TextLayoutResult>
    internal lateinit var gutterNumberCache: HashMap<Int, TextLayoutResult>

    /** The shaped layout for [line] straight off the render cache (re-tokenizes/re-shapes only if it changed). */
    fun layoutFor(line: Int): TextLayoutResult =
        renderCache.layoutFor(line, session.doc, session.styles)

    /** Per-number gutter layout, cached by the actual line number so real numbers render (not "0"/"00"/…). */
    fun numberLayout(n: Int): TextLayoutResult =
        gutterNumberCache.getOrPut(n) {
            measurer.measure(AnnotatedString(n.toString()), style = gutterStyle, softWrap = false, maxLines = 1)
        }

    /**
     * A collapsed fold-start line renders its composite "prefix + placeholder + suffix" on one row (e.g.
     * `fun f() {...}`). Measured on demand (only the handful of visible fold-start lines) and cached until the
     * buffer or fold set changes; the placeholder run is dimmed + chip-tinted so it reads as foldable. The real
     * lexical coloring is carried onto the composite so a folded line reads in the same syntax colors.
     */
    fun compositeLayoutFor(line: Int): TextLayoutResult = compositeCache.getOrPut(line) {
        val fm = session.foldModel
        val info = fm.foldStartingAt(line)
        val doc = session.doc
        if (info == null) return@getOrPut renderCache.layoutFor(line, doc, session.styles)
        val text = fm.compositeText(line, doc)
        val prefixLen = (info.prefixEnd - doc.lineStart(line)).coerceIn(0, text.length)
        val phEnd = (prefixLen + info.placeholder.length).coerceAtMost(text.length)
        val ranges = ArrayList<AnnotatedString.Range<SpanStyle>>()
        for (sp in session.styles.spansFor(line)) {
            val st = palette[sp.type.ordinal] ?: continue
            val s = sp.start.coerceIn(0, prefixLen)
            val e = sp.end.coerceIn(0, prefixLen)
            if (e > s) ranges.add(AnnotatedString.Range(st, s, e))
        }
        val suffixCol0 = info.suffixStart - doc.lineStart(info.endLine) // suffix's start column on the end line
        val base = phEnd
        for (sp in session.styles.spansFor(info.endLine)) {
            val st = palette[sp.type.ordinal] ?: continue
            val cs = maxOf(sp.start, suffixCol0)
            val ce = sp.end
            if (ce > cs) ranges.add(
                AnnotatedString.Range(
                    st,
                    (base + cs - suffixCol0).coerceAtMost(text.length),
                    (base + ce - suffixCol0).coerceAtMost(text.length),
                ),
            )
        }
        ranges.add(AnnotatedString.Range(foldPlaceholderStyle, prefixLen, phEnd))
        measurer.measure(AnnotatedString(text, spanStyles = ranges), style = codeStyle, softWrap = false, maxLines = 1)
    }
}

@Composable
internal fun rememberEditorRenderState(
    session: EditorSession,
    measurer: TextMeasurer,
    density: Density,
    colors: CodeAssistColors,
    typography: CaTypography,
    zoom: Float,
    fontLigatures: Boolean,
): EditorRenderState {
    val syntax = colors.syntax
    val state = remember(session) { EditorRenderState(session) }
    state.measurer = measurer

    // Zoom scales the code + gutter text size; the metrics/render-cache below key on these styles, so a zoom
    // recomputes line metrics and re-shapes lines at the new size (the cache is rebuilt — expected on zoom).
    val codeStyle = remember(syntax, typography, zoom, fontLigatures) {
        // Drop the theme's explicit lineHeight: the editor stacks visual rows itself at `metrics.lineHeight`,
        // and an explicit lineHeight makes a soft-wrapped paragraph space its MIDDLE rows differently from its
        // (trimmed) first/last rows — non-uniform spacing that the row model can't track. Unspecified ⇒ the
        // font's uniform natural advance, so a wrapped line's rows are evenly spaced and match the model.
        // Ligatures: programming ligatures (-> != >= …) come from the font's calt/liga OpenType features, which
        // the shaper enables by default — so OFF must disable them explicitly; ON leaves the defaults. They
        // keep the monospace advance, so column geometry (charWidth, caret, tap) is unaffected.
        typography.code.copy(
            color = syntax.default,
            fontSize = typography.code.fontSize * zoom,
            lineHeight = TextUnit.Unspecified,
            fontFeatureSettings = if (fontLigatures) null else "liga off, calt off, clig off, dlig off",
        )
    }
    state.codeStyle = codeStyle
    state.gutterStyle = remember(typography, zoom) {
        typography.codeSmall.copy(fontSize = typography.codeSmall.fontSize * zoom)
    }
    state.metrics = remember(measurer, codeStyle, density) {
        val probe = measurer.measure(AnnotatedString("MMMMMMMMMM"), style = codeStyle, softWrap = false, maxLines = 1)
        // Row height = the SOFT-WRAP inter-row advance, measured from a probe that actually wraps — not the
        // single-line box height nor a hard-newline advance (both differ by ~1px from how wrapped rows lay out).
        // The editor positions every visual row by `lineHeight`, so it MUST equal the advance `drawText` uses
        // for a wrapped paragraph's rows, or stacked lines drift (overlap / gap) under word wrap.
        val cw = probe.size.width / 10
        val wrapProbe = measurer.measure(
            AnnotatedString("M".repeat(240)), style = codeStyle, softWrap = true,
            constraints = Constraints(maxWidth = (cw * 40).coerceAtLeast(cw + 1)),
        )
        val rowAdvance =
            if (wrapProbe.lineCount > 1) wrapProbe.getLineTop(1) - wrapProbe.getLineTop(0)
            else probe.size.height.toFloat()
        with(density) {
            EditorMetrics(
                lineHeight = rowAdvance,
                charWidth = probe.size.width / 10f,
                padTop = 6.dp.toPx(),
                padLeft = 8.dp.toPx(),
                padRight = 24.dp.toPx(),
                padBottom = 200.dp.toPx(),
            )
        }
    }
    val palette = remember(syntax) { paletteFor(syntax) }
    state.palette = palette
    state.renderCache = remember(session, measurer, codeStyle, palette) {
        LineRenderCache(measurer, codeStyle, palette)
    }

    // Inlay hints, semantic tokens, folds and @Preview markers are produced by the highlighting daemon and live
    // on the session (shifted in place between passes); the render cache re-shapes only the lines whose spans
    // actually changed (per-line stamp), so we push the current overlay maps to it each recomposition.
    val inlayStyle = remember(colors) { SpanStyle(color = colors.textTertiary, fontStyle = FontStyle.Italic) }
    val inlayHints = session.inlayHints
    val perLineInlays = remember(inlayHints, session.doc) {
        if (inlayHints.isEmpty()) emptyMap() else buildMap<Int, MutableList<InlayPiece>> {
            val d = session.doc
            for (h in inlayHints) {
                val off = h.offset.coerceIn(0, d.length)
                val line = d.lineForOffset(off)
                val col = off - d.lineStart(line)
                val txt = (if (h.paddingLeft) " " else "") + h.text + (if (h.paddingRight) " " else "")
                getOrPut(line) { ArrayList() }.add(InlayPiece(col, txt))
            }
        }
    }
    state.renderCache.setInlays(perLineInlays, inlayStyle)
    val perLineSemantic = remember(session.semanticTokens, session.doc, syntax) {
        perLineSemanticSpans(session.semanticTokens, session.doc, syntax)
    }
    state.renderCache.setSemanticSpans(perLineSemantic)

    val foldPlaceholderStyle = remember(colors) {
        // A faint chip behind `...` — a low-alpha overlay (NOT hairline.copy(alpha=…), which would replace the
        // hairline's alpha and paint a near-opaque white box in dark mode).
        val chipBg = if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
        SpanStyle(color = colors.textTertiary, background = chipBg)
    }
    state.foldPlaceholderStyle = foldPlaceholderStyle
    state.compositeCache = remember(session.doc, session.foldModel, codeStyle, foldPlaceholderStyle, palette) {
        HashMap()
    }
    state.foldableStartLines = remember(session.foldRegions, session.doc) {
        session.foldRegions.mapTo(HashSet()) { session.doc.lineForOffset(it.start) }
    }
    state.gutterNumberCache = remember(measurer, state.gutterStyle) { HashMap() }

    val foldStripPx = with(density) { 14.dp.toPx() }
    state.foldStripPx = foldStripPx
    state.gutterWidthPx = remember(session.doc.lineCount, density, foldStripPx) {
        with(density) {
            (session.doc.lineCount.toString().length * 9 + 22).coerceAtLeast(44).dp.toPx() + foldStripPx
        }
    }
    return state
}
