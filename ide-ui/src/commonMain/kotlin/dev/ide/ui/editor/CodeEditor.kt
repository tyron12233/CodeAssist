package dev.ide.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollable2DState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.scrollable2D
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.ide.ui.editor.core.EditorCaretGeometry
import dev.ide.ui.editor.core.EditorImeHandle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.editor.core.WrapModel
import dev.ide.ui.editor.core.smartEnter
import dev.ide.ui.editor.core.editorTextInput
import dev.ide.ui.editor.core.textInputCodePoint
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The code editor surface, rebuilt sora-editor-style for typing latency on phones: the document is a
 * line-indexed buffer ([EditorSession]/[EditorDocument]), a keystroke re-tokenizes and re-shapes only
 * the edited line, and rendering draws only the visible lines from a per-line layout cache onto
 * one canvas (gutter included — no per-line composables anywhere). Scrolling is two scroll offsets
 * read in the draw phase, so a fling redraws without recomposing; the soft keyboard talks straight
 * to the session through a platform `InputConnection` (see EditorTextInput.android.kt).
 *
 * Feature parity with the legacy editor: gutter with error/warning marks, current-line band, 2px
 * accent caret, bracket-match boxes, severity-colored squiggles, inline error chips, smart edits,
 * and the live completion popup (auto on `.`/identifier, Ctrl-Space explicit; ↑↓ move, Tab/Enter
 * accept, Esc dismiss). Plus: touch selection handles and a floating Copy/Cut/Paste toolbar, which
 * the BasicTextField used to provide. No soft-wrap — one gutter row per logical line.
 */
@Composable
fun CodeEditor(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onNavigate: (path: String, offset: Int) -> Unit = { _, _ -> },
    onRenamed: (newPath: String?) -> Unit = {},
    /** Bump from the host (a toolbar Find button) to open the in-file find bar; 0 = no request. */
    findEpoch: Int = 0,
    /** Editor text zoom; 1.0 = the theme's code size. Driven by pinch + Ctrl-+/-/0; hoisted so it persists across tabs. */
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    /** Tapped a `@Preview` gutter icon — the host switches to the Preview surface rendering this variant. */
    onPreview: (variantId: String) -> Unit = {},
    /** Whether typing auto-opens the completion popup (Settings → Completion); Ctrl-Space always works. */
    completionAutoPopup: Boolean = true,
    /** Debounce (ms) before an auto-popup completion request (Settings → Completion → Advanced). */
    completionDelayMs: Int = 110,
    /**
     * Scroll both axes at once with a single touch drag (Settings → Editor). Off = the classic
     * orientation-locked drag (one axis per gesture). Touch-only: desktop trackpad/wheel already pans 2D.
     */
    twoAxisScroll: Boolean = true,
    /** Whether a two-finger pinch zooms the code font (Settings → Editor); Ctrl-+/-/0 always works. */
    pinchZoom: Boolean = true,
    /** Soft-wrap long lines at the viewport edge (Settings → Editor). Off = one row per line + h-scroll. */
    wordWrap: Boolean = false,
    /** Indent wrapped continuation rows to the line's own indent (Settings → Editor); only when [wordWrap]. */
    wrapIndent: Boolean = true,
    /** Render programming ligatures (`->`, `!=`, …) when the code font provides them (Settings → Editor; on). */
    fontLigatures: Boolean = true,
) {
    // Source code is intrinsically left-to-right: the gutter sits at the left edge and lines flow right.
    // On an RTL system locale (e.g. Arabic) Compose flips `LocalLayoutDirection`, which would make
    // `rememberTextMeasurer` shape lines right-to-left (right-aligned/mirrored text) and mirror the
    // `Modifier.offset`/`Popup` anchoring for the gutter chips, lightbulb, and completion popup. Pin the
    // whole editor subtree to LTR so it renders identically regardless of the device language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        CodeEditorContent(
            path, session, backend, modifier, onSave, onNavigate, onRenamed,
            findEpoch, fontScale, onFontScaleChange, onPreview, completionAutoPopup, completionDelayMs,
            twoAxisScroll, pinchZoom, wordWrap, wrapIndent, fontLigatures,
        )
    }
}

@Composable
private fun CodeEditorContent(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onNavigate: (path: String, offset: Int) -> Unit = { _, _ -> },
    onRenamed: (newPath: String?) -> Unit = {},
    findEpoch: Int = 0,
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    onPreview: (variantId: String) -> Unit = {},
    completionAutoPopup: Boolean = true,
    completionDelayMs: Int = 110,
    twoAxisScroll: Boolean = true,
    pinchZoom: Boolean = true,
    wordWrap: Boolean = false,
    wrapIndent: Boolean = true,
    fontLigatures: Boolean = true,
) {
    val colors = Ca.colors
    val syntax = colors.syntax
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The soft keyboard is raised only through this handle (on a deliberate tap) — never on focus alone, so
    // tab switches, returning to the screen, or closing a sheet don't pop it open. See [EditorImeHandle].
    val editorIme = remember { EditorImeHandle() }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current

    // ---- the edit engine: the per-tab source of truth, owned by the host and shared with the block
    // editor. This composable only renders and drives it; there is no TextFieldValue mirror to sync. ----
    val editorSession = session

    // ---- text metrics + per-line layout cache ----
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val typography = Ca.type
    // Zoom scales the code + gutter text size; the metrics/render-cache below key on these styles, so a zoom
    // recomputes line metrics and re-shapes lines at the new size (the cache is rebuilt — expected on zoom).
    val zoom = clampFontScale(fontScale)
    val liveScale = rememberUpdatedState(zoom) // read inside the pinch gesture (pointerInput captures once)
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
    val gutterStyle = remember(typography, zoom) { typography.codeSmall.copy(fontSize = typography.codeSmall.fontSize * zoom) }
    val metrics = remember(measurer, codeStyle, density) {
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
        val rowAdvance = if (wrapProbe.lineCount > 1) wrapProbe.getLineTop(1) - wrapProbe.getLineTop(0)
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
    val renderCache = remember(editorSession, measurer, codeStyle, palette) {
        LineRenderCache(measurer, codeStyle, palette)
    }

    // Inlay hints, semantic tokens, folds and @Preview markers are all produced by the editor highlighting
    // daemon ([EditorEngineDaemon], driven by EditorCenter so it runs in every view mode) and live on the
    // session, which shifts them in place between passes — the canvas just reads them.
    val inlayHints = editorSession.inlayHints

    // If the caret lands inside a collapsed fold (go-to-definition, rename, programmatic navigation), reveal it
    // — tapping can't put the caret in hidden text (offsetAt clamps to the visible prefix), so this only fires
    // for non-tap navigation.
    LaunchedEffect(editorSession.selection) {
        editorSession.expandFoldAt(editorSession.selection.end)
    }

    // (@Preview gutter markers are now the daemon's PREVIEWS pass — applied to the session above.)
    val inlayStyle = remember(colors) { SpanStyle(color = colors.textTertiary, fontStyle = FontStyle.Italic) }
    val perLineInlays = remember(inlayHints, editorSession.doc) {
        if (inlayHints.isEmpty()) emptyMap() else buildMap<Int, MutableList<InlayPiece>> {
            val d = editorSession.doc
            for (h in inlayHints) {
                val off = h.offset.coerceIn(0, d.length)
                val line = d.lineForOffset(off)
                val col = off - d.lineStart(line)
                val txt = (if (h.paddingLeft) " " else "") + h.text + (if (h.paddingRight) " " else "")
                getOrPut(line) { ArrayList() }.add(InlayPiece(col, txt))
            }
        }
    }
    renderCache.setInlays(perLineInlays, inlayStyle)

    // Semantic overlay → per-line spans (recomputed when the tokens, buffer, or theme change), pushed to the
    // render cache which re-shapes only the lines whose spans actually changed (per-line stamp).
    val perLineSemantic = remember(editorSession.semanticTokens, editorSession.doc, syntax) {
        perLineSemanticSpans(editorSession.semanticTokens, editorSession.doc, syntax)
    }
    renderCache.setSemanticSpans(perLineSemantic)

    // Collapsed fold-start lines render a composite "prefix + placeholder + suffix" on one row (e.g.
    // `fun f() {...}`). Measured on demand (only the handful of visible fold-start lines) and cached until the
    // buffer or fold set changes; the placeholder run is dimmed and chip-tinted so it reads as foldable.
    val foldPlaceholderStyle = remember(colors) {
        // A faint chip behind `...` — a low-alpha overlay (NOT hairline.copy(alpha=…), which would replace the
        // hairline's alpha and paint a near-opaque white box in dark mode).
        val chipBg = if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
        SpanStyle(color = colors.textTertiary, background = chipBg)
    }
    val compositeCache = remember(editorSession.doc, editorSession.foldModel, codeStyle, foldPlaceholderStyle, palette) {
        HashMap<Int, TextLayoutResult>()
    }
    fun compositeLayoutFor(line: Int): TextLayoutResult = compositeCache.getOrPut(line) {
        val fm = editorSession.foldModel
        val info = fm.foldStartingAt(line)
        val doc = editorSession.doc
        if (info == null) return@getOrPut renderCache.layoutFor(line, doc, editorSession.styles)
        val text = fm.compositeText(line, doc)
        val prefixLen = (info.prefixEnd - doc.lineStart(line)).coerceIn(0, text.length)
        val phEnd = (prefixLen + info.placeholder.length).coerceAtMost(text.length)
        // Carry the real lexical coloring onto the composite: the start line's spans color the prefix and the
        // end line's spans color the suffix (remapped past the placeholder), so a folded line reads in the
        // same syntax colors as the rest — not flat default text.
        val ranges = ArrayList<AnnotatedString.Range<SpanStyle>>()
        for (sp in editorSession.styles.spansFor(line)) {
            val st = palette[sp.type.ordinal] ?: continue
            val s = sp.start.coerceIn(0, prefixLen); val e = sp.end.coerceIn(0, prefixLen)
            if (e > s) ranges.add(AnnotatedString.Range(st, s, e))
        }
        val suffixCol0 = info.suffixStart - doc.lineStart(info.endLine) // suffix's start column on the end line
        val base = phEnd
        for (sp in editorSession.styles.spansFor(info.endLine)) {
            val st = palette[sp.type.ordinal] ?: continue
            val cs = maxOf(sp.start, suffixCol0); val ce = sp.end
            if (ce > cs) ranges.add(AnnotatedString.Range(st, (base + cs - suffixCol0).coerceAtMost(text.length), (base + ce - suffixCol0).coerceAtMost(text.length)))
        }
        ranges.add(AnnotatedString.Range(foldPlaceholderStyle, prefixLen, phEnd))
        measurer.measure(AnnotatedString(text, spanStyles = ranges), style = codeStyle, softWrap = false, maxLines = 1)
    }
    // Document lines that begin ANY fold region (collapsed or open) — drives the gutter chevrons.
    val foldableStartLines = remember(editorSession.foldRegions, editorSession.doc) {
        editorSession.foldRegions.mapTo(HashSet<Int>()) { editorSession.doc.lineForOffset(it.start) }
    }

    // Per-number layout cache, keyed by the actual line number (not just its digit count) so the gutter
    // renders real numbers — caching by digit-count rendered "0"/"00"/… for every line.
    val gutterNumberCache = remember(measurer, gutterStyle) { HashMap<Int, TextLayoutResult>() }
    fun numberLayout(n: Int): TextLayoutResult =
        gutterNumberCache.getOrPut(n) {
            measurer.measure(AnnotatedString(n.toString()), style = gutterStyle, softWrap = false, maxLines = 1)
        }

    // A fold strip on the inner edge of the gutter holds the ▸/▾ chevrons (collapsed folds always show one;
    // an expandable line shows one on the caret line). Reserved so the line numbers don't reflow when a
    // chevron appears.
    val foldStripPx = with(density) { 14.dp.toPx() }
    val gutterWidthPx = remember(editorSession.doc.lineCount, density, foldStripPx) {
        with(density) {
            (editorSession.doc.lineCount.toString().length * 9 + 22).coerceAtLeast(44).dp.toPx() + foldStripPx
        }
    }

    // ---- scrolling: plain offsets read in the draw phase (a fling never recomposes) ----
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // The editor content's offset within the IME root view, so the caret-geometry provider can report
    // CursorAnchorInfo coordinates in view-pixel space (see the editorTextInput node).
    val contentInWindow = remember { mutableStateOf(Offset.Zero) }
    val vOffset = remember(path) { mutableFloatStateOf(0f) }
    val hOffset = remember(path) { mutableFloatStateOf(0f) }
    fun layoutFor(line: Int) = renderCache.layoutFor(line, editorSession.doc, editorSession.styles)

    // ---- soft wrap: a variable-height vertical projection, engaged only when [wordWrap] is on ----
    // Off, the editor keeps its O(1) one-row-per-line geometry (the [FoldModel] path), so the default is
    // byte-for-byte unchanged. On, [WrapModel] turns per-line wrap-row counts into a fold-aware row prefix sum.
    val wrapModel = remember(editorSession) { WrapModel() }
    val wrapWidthPx = if (wordWrap && viewport.width > 0)
        (viewport.width - gutterWidthPx - metrics.padLeft - metrics.padRight).toInt()
            .coerceAtLeast(maxOf(1, (metrics.charWidth * 8f).toInt()))
    else 0
    renderCache.setWrapWidth(wrapWidthPx)
    val wrapCols = if (wrapWidthPx > 0) (wrapWidthPx / metrics.charWidth).toInt().coerceAtLeast(1) else 0
    // Smart wrap indent (IntelliJ-style): continuation rows align under the line's own indent. Capped at half
    // the wrap width so wrapped text always keeps room. The indent is applied during measurement in the line
    // cache (TextIndent.restLine), so all caret/tap/selection geometry tracks it automatically.
    val wrapIndentActive = wordWrap && wrapIndent && wrapWidthPx > 0
    val wrapMaxIndentCols = if (wrapCols > 0) wrapCols / 2 else 0
    // IntelliJ indents wrapped parts to the original indent PLUS a small continuation shift (one indent level),
    // so the wrap reads as a continuation rather than aligning flush under the code.
    val wrapExtraIndentCols = 4
    renderCache.setWrapIndent(wrapIndentActive, with(density) { metrics.charWidth.toSp() }, wrapMaxIndentCols, wrapExtraIndentCols)
    // Cheap monospace estimate of a line's wrap-row count — count display columns (tabs to 4-col stops); the
    // first row holds `wrapCols`, continuation rows hold `wrapCols - indent`. No text shaping; on-screen lines
    // are corrected to the exact lineCount in [VLayout.correctRange], so this only sizes the OFF-screen extent.
    fun estimateWrapRows(line: Int): Int {
        if (wrapCols <= 0) return 1
        val d = editorSession.doc
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
        val indent = if (wrapIndentActive) ((if (leading < 0) cols else leading) + wrapExtraIndentCols).coerceAtMost(wrapMaxIndentCols) else 0
        val contCols = (wrapCols - indent).coerceAtLeast(1)
        return 1 + (cols - wrapCols + contCols - 1) / contCols
    }
    // (Re-)seed estimates on width / indent change and on STRUCTURAL edits (line add/remove → indices shift,
    // so a fresh estimate realigns them). Deliberately NOT keyed on textRevision: a same-line edit must NOT
    // re-estimate, because that would overwrite the exact wrap heights `correctRange` recorded for the visible
    // lines — and then `caretTarget` (composition) would place the caret using estimates while the draw renders
    // text at corrected positions, drifting the caret off the text on every keystroke. The edited line's own
    // height is re-corrected by the next draw (it's visible); lines above the caret are untouched, so the
    // caret's row stays exact. Off-screen lines keep their last estimate/correction (only scroll extent ages).
    val wrapEstimateSig = remember(editorSession) { mutableStateOf<Any?>(null) }
    if (wordWrap && wrapWidthPx > 0) {
        wrapModel.resize(editorSession.doc.lineCount)
        val sig = Triple(wrapWidthPx, editorSession.doc.lineCount, wrapIndentActive)
        if (wrapEstimateSig.value != sig) {
            wrapEstimateSig.value = sig
            for (ln in 0 until editorSession.doc.lineCount) wrapModel.setRows(ln, estimateWrapRows(ln))
        }
    }
    // Wrap geometry is "active" only once the viewport has been measured (wrapWidthPx > 0). Until then —
    // the first frame after open/rotate — fall back to the fold model's one-row-per-line mapping so nothing
    // collapses onto row 0 for a frame. (wrapWidthPx is 0 whenever !wordWrap or the viewport isn't sized.)
    val wrapActive = wrapWidthPx > 0
    // The vertical projection the renderer + geometry share. `correctRange` shapes the on-screen lines and
    // records their exact wrapped height (the draw calls it before positioning), so what's visible is
    // pixel-aligned while off-screen rows ride the estimate.
    // IMPORTANT: read the fold model LIVE (`editorSession.foldModel`), not a captured snapshot. The gesture
    // detectors hold this object's geometry across recompositions (their pointerInput keys don't change on a
    // plain edit), so a captured fold model would map taps through a stale line count after an edit. The
    // session + wrapModel are stable instances; their mutable state is read fresh on each call.
    val vlayout: VLayout = object : VLayout {
        private val fold get() = editorSession.foldModel
        override val totalRows: Int
            get() = if (wrapActive) { wrapModel.ensure(fold); wrapModel.totalRows } else fold.visualLineCount
        override fun topRow(line: Int): Int =
            if (wrapActive) { wrapModel.ensure(fold); wrapModel.topRow(line) } else fold.visualForDocLine(line)
        override fun rowsOf(line: Int): Int {
            val f = fold
            return when {
                wrapActive -> { wrapModel.ensure(f); wrapModel.rowsOf(line) }
                f.isHidden(line) -> 0
                else -> 1
            }
        }
        override fun docLineForRow(row: Int): Int =
            if (wrapActive) { wrapModel.ensure(fold); wrapModel.docLineForRow(row) } else fold.docLineForVisual(row)
        override fun correctRange(first: Int, last: Int) {
            if (!wrapActive) return
            val f = fold
            var ln = first.coerceAtLeast(0)
            val end = last.coerceAtMost(editorSession.doc.lineCount - 1)
            while (ln <= end) {
                if (!f.isHidden(ln) && f.foldStartingAt(ln) == null)
                    wrapModel.setRows(ln, layoutFor(ln).lineCount)
                ln++
            }
            wrapModel.ensure(f)
        }
    }

    fun contentHeight() = metrics.padTop + vlayout.totalRows * metrics.lineHeight + metrics.padBottom
    fun contentWidth() = if (wrapActive) viewport.width.toFloat()
        else metrics.padLeft + max(renderCache.measuredMaxWidth, editorSession.maxLineChars * metrics.charWidth) + metrics.padRight
    fun maxV() = (contentHeight() - viewport.height).coerceAtLeast(0f)
    fun maxH() = if (wrapActive) 0f else (contentWidth() - (viewport.width - gutterWidthPx)).coerceAtLeast(0f)
    val vScroll = rememberScrollableState { delta ->
        val old = vOffset.floatValue
        val new = (old + delta).coerceIn(0f, maxV())
        vOffset.floatValue = new
        new - old
    }
    val hScroll = rememberScrollableState { delta ->
        val old = hOffset.floatValue
        val new = (old + delta).coerceIn(0f, maxH())
        hOffset.floatValue = new
        new - old
    }
    // Two-axis (free) scrolling: one state pans both offsets from a single drag, so a diagonal swipe moves
    // vertically and horizontally at once instead of orientation-locking. `scrollable2D` has no
    // `reverseDirection`, so the natural-scroll sign is applied here (drag down → reveal earlier lines →
    // vOffset shrinks); the consumed Offset is reported back in the input frame for fling/nested-scroll.
    val scroll2D = rememberScrollable2DState { delta ->
        val oldV = vOffset.floatValue
        val newV = (oldV - delta.y).coerceIn(0f, maxV())
        vOffset.floatValue = newV
        val oldH = hOffset.floatValue
        val newH = (oldH - delta.x).coerceIn(0f, maxH())
        hOffset.floatValue = newH
        Offset(oldH - newH, oldV - newV)
    }
    // 2D drag is a touch concern; desktop trackpad/wheel already pans both axes through the 1D scrollables
    // below (wheel events never orientation-lock), and `scrollable2D` carries no mouse-wheel handling — so
    // restrict it to touch platforms and leave the desktop path untouched.
    val useTwoAxisScroll = twoAxisScroll && isMobilePlatform
    // A zoom rescales the line metrics (hence the content size), and the viewport changes on resize/rotate;
    // re-clamp the scroll so a zoom-out can't strand the viewport past the document end — where taps would
    // map to a coerced position that no longer matches what's rendered.
    // Re-clamp on fold changes too: collapsing a region shrinks the content height without changing the line
    // count, so a stale vOffset could otherwise strand the viewport past the (now shorter) document end.
    LaunchedEffect(zoom, editorSession.doc.lineCount, editorSession.foldRegions, viewport, wordWrap, wrapWidthPx, wrapIndentActive) {
        if (wordWrap) hOffset.floatValue = 0f // wrapped: there is no horizontal scroll
        vOffset.floatValue = vOffset.floatValue.coerceIn(0f, maxV())
        hOffset.floatValue = hOffset.floatValue.coerceIn(0f, maxH())
    }

    // ---- geometry helpers (viewport coordinates ↔ document offsets) — routed through [vlayout] so a collapsed
    // region occupies one visual row and a wrapped line occupies several; within a line, the line's own
    // TextLayoutResult resolves the wrapped sub-row (getLineForOffset/getLineTop) and the x position ----
    fun lineTop(line: Int) = metrics.padTop + vlayout.topRow(line) * metrics.lineHeight - vOffset.floatValue
    fun textLeft() = gutterWidthPx + metrics.padLeft - hOffset.floatValue
    fun caretGeometry(offset: Int): Triple<Int, Float, Float> { // line, xInViewport, topInViewport (of the sub-row)
        val doc = editorSession.doc
        val line = doc.lineForOffset(offset)
        val vcol = renderCache.rawToVisual(line, offset - doc.lineStart(line))
        val layout = layoutFor(line)
        val x = layout.getHorizontalPosition(vcol, usePrimaryDirection = true)
        val subTop = if (wordWrap) layout.getLineTop(layout.getLineForOffset(vcol)) else 0f
        return Triple(line, textLeft() + x, lineTop(line) + subTop)
    }
    // Feed the platform IME bridge the caret's pixel geometry for CursorAnchorInfo (the keyboard positions its
    // floating UI / handwriting box from it). Viewport coordinates are offset by the content's window position so
    // they land in view-pixel space; best-effort (null when layout isn't ready) and only read by the Android node.
    DisposableEffect(editorIme) {
        editorIme.caretGeometryProvider = {
            runCatching {
                val (_, x, top) = caretGeometry(editorSession.selection.min)
                val base = contentInWindow.value
                EditorCaretGeometry(
                    horizontalPosition = base.x + x,
                    top = base.y + top,
                    baseline = base.y + top + metrics.lineHeight,
                    bottom = base.y + top + metrics.lineHeight,
                )
            }.getOrNull()
        }
        onDispose { editorIme.caretGeometryProvider = null }
    }
    /** The document line shown at viewport [y] (the fold-start line when [y] is on a collapsed row). */
    fun lineAtY(y: Float): Int {
        val row = floor((y + vOffset.floatValue - metrics.padTop) / metrics.lineHeight)
            .toInt().coerceIn(0, (vlayout.totalRows - 1).coerceAtLeast(0))
        return vlayout.docLineForRow(row)
    }
    /** Handle a tap that targets folding: the gutter fold strip toggles the line's fold; tapping a collapsed
     *  line's placeholder (the dimmed `...` past the visible prefix) expands it. Returns true when handled. */
    fun foldActionAt(pos: Offset): Boolean {
        val doc = editorSession.doc
        val line = lineAtY(pos.y)
        val startsFold = editorSession.foldRegions.any { doc.lineForOffset(it.start) == line }
        if (startsFold && pos.x >= gutterWidthPx - foldStripPx && pos.x < gutterWidthPx) {
            return editorSession.toggleFoldAtLine(line)
        }
        val info = editorSession.foldModel.foldStartingAt(line)
        if (info != null && pos.x >= textLeft()) {
            val prefixCols = (info.prefixEnd - doc.lineStart(line)).coerceAtLeast(0)
            val prefixX = textLeft() + compositeLayoutFor(line).getHorizontalPosition(prefixCols, usePrimaryDirection = true)
            if (pos.x >= prefixX) return editorSession.toggleFoldAtLine(line)
        }
        return false
    }
    fun offsetAt(pos: Offset): Int {
        val doc = editorSession.doc
        val line = lineAtY(pos.y)
        val xInLine = pos.x - textLeft()
        val layout = layoutFor(line)
        // y within the line's paragraph picks the wrapped sub-row; mid-row when not wrapping (single row).
        val yInLine = if (wrapActive)
            ((pos.y + vOffset.floatValue - metrics.padTop) - vlayout.topRow(line) * metrics.lineHeight)
                .coerceIn(0f, (layout.size.height - 1f).coerceAtLeast(0f))
        else metrics.lineHeight / 2f
        val visualCol = layout.getOffsetForPosition(Offset(xInLine.coerceAtLeast(0f), yInLine))
        val col = renderCache.visualToRaw(line, visualCol)
        // On a collapsed fold-start line only the prefix (text before the fold) is real; clamp the caret there.
        val maxCol = editorSession.foldModel.foldStartingAt(line)?.let { it.prefixEnd - doc.lineStart(line) } ?: doc.lineLength(line)
        return doc.lineStart(line) + col.coerceAtMost(maxCol)
    }

    // ---- focus / caret blink / touch chrome ----
    var isFocused by remember { mutableStateOf(false) }
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(editorSession.editCount, editorSession.selection.start, isFocused) {
        blinkOn = true // caret solid through every edit or cursor move; blink only at rest
        while (isFocused) {
            delay(530.milliseconds)
            blinkOn = !blinkOn
        }
    }

    // ---- caret position animation: the caret glides to its new spot instead of teleporting ----
    // Tracked in content space (scroll-independent) so a scroll doesn't fight the animation; the renderer
    // subtracts the scroll offsets. The Animatable is keyed on the session so switching tabs starts fresh.
    val caretAnim = remember(editorSession) { Animatable(Offset.Zero, Offset.VectorConverter) }
    var caretAnimReady by remember(editorSession) { mutableStateOf(false) }
    // The last text revision the caret animation reacted to — lets it tell a typing-driven caret advance
    // (text changed) from a navigation move (arrows/click/go-to), so typing snaps and only navigation glides.
    var caretAnimRev by remember(editorSession) { mutableIntStateOf(editorSession.textRevision) }
    val caretTarget = run {
        val off = editorSession.selection.end
        val d = editorSession.doc
        val ln = d.lineForOffset(off)
        val vcol = renderCache.rawToVisual(ln, off - d.lineStart(ln))
        val layout = layoutFor(ln)
        val x = gutterWidthPx + metrics.padLeft + layout.getHorizontalPosition(vcol, usePrimaryDirection = true)
        // Content-space Y uses the VISUAL row (folds above it shrink Y) plus the wrapped sub-row within the line.
        val subTop = if (wordWrap) layout.getLineTop(layout.getLineForOffset(vcol)) else 0f
        Offset(x, metrics.padTop + vlayout.topRow(ln) * metrics.lineHeight + subTop)
    }
    LaunchedEffect(caretTarget) {
        // Snap on the first placement (file open) and across off-screen jumps (go-to-symbol, PageUp/Down) —
        // a glide across the whole document reads as a glitch; glide only for moves within a viewport.
        val far = viewport.height > 0 && kotlin.math.abs(caretTarget.y - caretAnim.value.y) > viewport.height
        // Typing advances the caret on (nearly) every keystroke; gliding then keeps a 60fps spring redraw loop
        // running the whole time someone types — costly on a phone. Snap when the buffer changed (typing/edit),
        // and reserve the glide for pure caret moves (arrows, taps, go-to) where it reads as intentional motion.
        val edited = editorSession.textRevision != caretAnimRev
        caretAnimRev = editorSession.textRevision
        // Word wrap: snap, don't glide. A single caret move can cross several wrapped sub-rows of one line, so
        // the content-space glide would sweep diagonally across rows at every sub-row boundary (it used to just
        // step cleanly), and a still-settling wrap-row prefix can shift the target mid-glide — both read as a
        // janky/teleporting caret. IntelliJ snaps the caret too; snapping keeps it pinned to the right spot.
        if (!caretAnimReady || far || edited || wordWrap) {
            caretAnimReady = true
            caretAnim.snapTo(caretTarget)
        } else {
            caretAnim.animateTo(
                caretTarget,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
            )
        }
    }
    var lastInputWasTouch by remember { mutableStateOf(false) }
    // The document line the mouse is hovering (desktop) — drives showing an expandable fold chevron on hover,
    // IntelliJ-style. -1 when the pointer is a touch or has left the editor.
    var hoveredLine by remember(editorSession) { mutableIntStateOf(-1) }
    var handlesVisible by remember(path) { mutableStateOf(false) }
    // triple-tap → select line: a double-tap "arms" this for a brief window; the next quick tap nearby then
    // selects the whole line instead of placing the caret. Keeps detectTapGestures' single/double logic intact.
    var tripleArmed by remember(path) { mutableStateOf(false) }
    var tripleArmPos by remember(path) { mutableStateOf(Offset.Zero) }
    var tripleArmJob by remember(path) { mutableStateOf<Job?>(null) }
    // Mouse click-count tracking (single → caret, double → word, triple → line); reset across files.
    // We count clicks ourselves rather than via detectTapGestures so the mouse path can own the whole
    // gesture (click + drag-to-select) and consume the drag before the scroll containers steal it.
    var mouseClicks by remember(path) { mutableIntStateOf(0) }
    var mouseLastClickMs by remember(path) { mutableLongStateOf(0L) }
    var mouseLastClickPos by remember(path) { mutableStateOf(Offset.Zero) }

    // ---- completion — popup state + async request/keep-alive in [CompletionController]; accept() (below)
    // stays in this surface because it's wired into the canvas geometry + snippet session ----
    val completion = rememberCompletionController(path, editorSession, backend)
    // Apply the user's completion prefs to the controller (idempotent per recompose).
    completion.autoPopupEnabled = completionAutoPopup
    completion.delayMs = completionDelayMs
    // Active snippet/template expansion (tab-stop stepping), or null. Reset when the file changes.
    var snippet by remember(path) { mutableStateOf<SnippetSession?>(null) }
    var paneTopInWindow by remember(path) { mutableFloatStateOf(0f) }
    var paneBottomInWindow by remember(path) { mutableFloatStateOf(0f) }

    // ---- signature help (parameter info): the IntelliJ panel floated ABOVE the call the caret is inside ----
    // Independent of completion: it re-resolves on every caret move / edit while the caret is inside a call
    // (gated by a cheap local scan so we don't hit the backend elsewhere), and dismisses when it leaves.
    val sig = rememberSignatureHelpController(path, backend) // parameter-info state + resolution

    // ---- code actions (lightbulb) + diagnostic sheet — state + behaviour in [EditorActionsController];
    // opening either dismisses the completion popup so they don't overlap ----
    val acts = rememberEditorActionsController(path, editorSession, backend) { completion.dismiss() }

    // ---- in-file find / replace (Ctrl-F / Ctrl-R; or the toolbar Find button via findEpoch) ----
    // State + behaviour live in [FindReplaceController]; the surface only opens it, paints its matches, and
    // renders the [FindReplaceBar] against it.
    val find = rememberFindReplaceController(path, editorSession)

    // ---- rename refactoring (F2 / Shift-F6): prompt for a new name, then a project-wide rename ----
    var rename by remember(path) { mutableStateOf<RenameUiState?>(null) }
    var renameBusy by remember(path) { mutableStateOf(false) }
    var renameError by remember(path) { mutableStateOf<String?>(null) }

    fun startRename() {
        if (renameBusy) return
        val text = editorSession.doc.text
        val caret = editorSession.selection.start
        scope.launch {
            val target = runCatching { backend.prepareRename(path, text, caret) }.getOrNull()
            if (target != null) { renameError = null; rename = RenameUiState(caret, target.oldName, target.kind, target.oldName) }
        }
    }

    fun commitRename() {
        val r = rename ?: return
        if (renameBusy || r.newName.isBlank() || r.newName == r.oldName) { rename = null; return }
        renameBusy = true; renameError = null
        val text = editorSession.doc.text
        scope.launch {
            val result = runCatching { backend.rename(path, text, r.offset, r.newName) }
                .getOrElse { dev.ide.ui.backend.UiRenameResult(false, it.message ?: "Rename failed") }
            renameBusy = false
            if (result.success) { rename = null; onRenamed(result.newPath) }
            else renameError = result.message
        }
    }

    // Read the document + selection straight off the session (snapshot reads → this body recomposes on
    // edit), but derive everything from the rope's O(log N) random access / small substrings — the body
    // never forces the O(n) full-text materialization (only debounced consumers pull the full String).
    val doc = editorSession.doc
    val diagnostics = editorSession.diagnostics // the session owns and auto-shifts these; rendered here
    val docLength = doc.length
    val caretOffset = editorSession.selection.start.coerceIn(0, docLength)
    // Language-specific word chars (XML namespace `:` and resource-ref `@?+/.-`) so the popup survives
    // them as the user types. Java gets none, preserving its behavior.
    val wordExtra = extraWordChars(path)
    val liveCompletion = completion.current?.takeIf { it.coversCaret(doc.chars, caretOffset, wordExtra) }
    val activePrefix = liveCompletion?.let { doc.substring(it.tokenStart, caretOffset) } ?: ""
    val displayed = liveCompletion?.filtered(activePrefix) ?: emptyList()
    val showPopup = !completion.dismissed && displayed.isNotEmpty()
    val safeSelected = completion.selected.coerceIn(0, (displayed.size - 1).coerceAtLeast(0))

    // Keep the popup *window* mounted across the 1-frame gaps a keystroke opens up, instead of unmounting and
    // recreating the Popup window each keystroke (the blink the user sees). It opens once there are items, and
    // then stays open as long as the caret is still on the same token (`liveCompletion != null`) and the user
    // hasn't dismissed it — a momentarily-empty `displayed` (a stale cached session between the debounced
    // refreshes, which land ~110ms apart) does NOT close it. It closes only on an explicit dismiss
    // (Esc/accept/click-away/non-identifier → `dismissed`) or when the caret leaves the token. No timer, so
    // nothing races the refresh debounce. `shownCompletion` snapshots the last good render state (token +
    // items + prefix), so while `displayed` is transiently empty the window shows real content, never an empty box.
    val onToken = liveCompletion != null
    val hasItems = displayed.isNotEmpty()
    LaunchedEffect(completion.dismissed, onToken, hasItems) {
        completion.updatePopupVisibility(onToken, hasItems)
    }
    SideEffect {
        val live = liveCompletion
        if (live != null && hasItems) completion.snapshotShown(live.tokenStart, displayed, activePrefix)
    }

    // Accept [picked], or — for the keyboard path — the currently-selected item. Callers that already know
    // the item (a click/tap on a row) MUST pass it: `safeSelected` is captured at composition time, so a
    // same-frame `selected = …; accept()` would still read the stale selection and accept the wrong row.
    // Apply completion [edits] but keep the viewport visually stationary when they insert line(s) ABOVE the
    // visible area — the off-screen auto-import case. Without this, an inserted import line shifts every line
    // below it down by one row, so the whole view (and the caret you were looking at) suddenly jumps. We anchor
    // on the completion line ([anchorLine], captured pre-edit): if any edit lands above the first visible line,
    // nudge the scroll by the anchor's visual-line delta so it — and thus the visible region — stays put. An
    // insertion the user can actually SEE (within the viewport) is left to shift naturally.
    fun applyEditsKeepingViewport(edits: List<RangeEdit>, finalSel: TextRange, anchorLine: Int) {
        val doc = editorSession.doc
        val topLine = lineAtY(0f).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
        val topVisibleStart = doc.lineStart(topLine)
        val insertsAboveViewport = edits.any { it.start < topVisibleStart && '\n' in it.text }
        val visualBefore = editorSession.foldModel.visualForDocLine(anchorLine)
        editorSession.applyEdits(edits, finalSel)
        if (insertsAboveViewport) {
            val anchorAfter = editorSession.doc.lineForOffset(finalSel.min)
            val delta = (editorSession.foldModel.visualForDocLine(anchorAfter) - visualBefore) * metrics.lineHeight
            if (delta > 0f) vOffset.floatValue = (vOffset.floatValue + delta).coerceIn(0f, maxV())
        }
    }

    fun accept(picked: UiCompletionItem? = null) {
        val s = liveCompletion ?: return
        val item = picked ?: displayed.getOrNull(safeSelected) ?: return
        val chars = editorSession.doc.chars
        val len = chars.length
        val mainStart = s.tokenStart.coerceIn(0, len)
        val anchorLine = editorSession.doc.lineForOffset(mainStart) // the completion line, for viewport stability
        // Replace the WHOLE identifier token under the caret, not just the typed prefix: completing in the
        // middle of a word (get<caret>TextState) then removes the trailing suffix instead of leaving it.
        var mainEnd = caretOffset.coerceIn(mainStart, len)
        while (mainEnd < len && isIdentifierChar(chars[mainEnd], wordExtra)) mainEnd++

        // If the item is exactly the token already present (the word is fully typed), the replace would be a
        // no-op and feel like nothing happened — append a space to acknowledge it and advance the caret,
        // unless something non-space already follows.
        val noOp = item.insertText == chars.subSequence(mainStart, mainEnd).toString() &&
            item.additionalEdits.isEmpty() && item.caret == null
        val nextIsSpace = mainEnd < len && chars[mainEnd].isWhitespace()
        val insert = if (noOp && !nextIsSpace) item.insertText + " " else item.insertText

        val edits = ArrayList<RangeEdit>()
        edits.add(RangeEdit(mainStart, mainEnd, insert, mainStart + insert.length))
        for (e in item.additionalEdits) {
            val st = e.start.coerceIn(0, len)
            edits.add(RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length))
        }
        // Snippet/postfix item: apply the edits, then drive tab-stop stepping. The inserted text lands at
        // `base` (mainStart shifted by any additionalEdits that delete text before it, e.g. postfix removing
        // the `receiver.`), and the snippet offsets are relative to that.
        // The text edit we're about to apply ends in an identifier char; without this the revision trigger
        // would immediately reopen the popup. Keep it closed until the user types again.
        completion.suppressNext()
        val snip = item.snippet
        if (snip != null) {
            var base = mainStart
            for (e in item.additionalEdits) {
                val st = e.start.coerceIn(0, len)
                if (st <= mainStart) base += e.newText.length - (e.end.coerceIn(st, len) - st)
            }
            applyEditsKeepingViewport(edits, TextRange(base), anchorLine)
            snippet = SnippetSession.start(editorSession, base, snip)
            completion.dismiss()
            return
        }
        // caret lands inside the inserted text (the item decides); edits above shift it by their delta
        val within = (item.caret?.offset ?: insert.length).coerceIn(0, insert.length)
        var caret = mainStart + within
        for (e in item.additionalEdits) {
            val st = e.start.coerceIn(0, len)
            if (st <= mainStart) caret += e.newText.length - (e.end.coerceIn(st, len) - st)
        }
        // XML attribute-value completion: hop the caret past the existing closing quote, so the next keystroke
        // lands outside the "" (Android-Studio behavior). Only for plain end-of-insert items (not name=""), in XML.
        if (item.caret == null && wordExtra.isNotEmpty() && mainEnd < len && (chars[mainEnd] == '"' || chars[mainEnd] == '\'')) {
            caret += 1
        }
        val selLen = item.caret?.selectionLength ?: 0
        val sel = if (selLen > 0) TextRange(caret, caret + selLen) else TextRange(caret)
        applyEditsKeepingViewport(edits, sel, anchorLine)
        completion.dismiss()
    }

    // Smart Enter (Shift+Enter): finish the current line, then open an indented new line — IntelliJ's
    // "Complete Statement". The decision is a pure function ([smartEnter]); we just apply its edit.
    fun completeStatement() {
        completion.dismiss()
        val edit = smartEnter(editorSession.doc.chars, editorSession.selection.start, editorSession.language)
        editorSession.applyEdits(listOf(edit), TextRange(edit.caret))
    }


    // keep the per-line render cache aligned with line splices (a render concern, owned by this surface)
    SideEffect {
        editorSession.onLinesShifted = { from, delta -> renderCache.shiftKeys(from, delta) }
        // Active template session re-anchors its tab stops on each edit (typing inside a placeholder).
        // (Inlay-hint offsets are shifted by the session itself now, alongside the other overlays.)
        editorSession.onSnippetEdit = { span -> snippet?.onEdit(span) }
    }

    // completion triggering — fires only when the buffer's *text* actually advances (textRevision bumps on
    // text edits, never on caret moves). The baseline is captured at mount so switching to an already-edited
    // tab (or toggling back from Blocks) doesn't spuriously pop the popup. The trigger char is read off the
    // rope (O(log N)); no String is built.
    var lastSeenRev by remember(path) { mutableIntStateOf(editorSession.textRevision) }
    LaunchedEffect(editorSession.textRevision) {
        val rev = editorSession.textRevision
        if (rev == lastSeenRev) return@LaunchedEffect // mount / no real edit since the last handled one
        lastSeenRev = rev
        handlesVisible = false // typing puts the touch chrome away (Android convention)
        completion.selected = 0
        // This revision is accept()'s own edit (it inserts an identifier, which would otherwise re-trigger
        // completion). Swallow it once so the popup stays closed until the next real keystroke.
        if (completion.consumeSuppressedTrigger()) return@LaunchedEffect
        val d = editorSession.doc
        val caret = editorSession.selection.start
        val before = if (caret in 1..d.length) d.charAt(caret - 1) else null

        // (XML tag auto-close happens atomically in the typing path — smartInsert in EditOps — so it fires
        // only on a real `>` keystroke, not on the caret moves / IME re-commits that also bump textRevision.)

        // Linked tag editing (XML): as the open tag's name is edited, rewrite its matching close tag to match
        // (e.g. `<TextView…>` → `<MyView…>` updates `</TextView>`). Driven by the text edit, so it tracks
        // typing/paste/backspace alike; the re-edit it applies re-enters this effect, converging in one step.
        if (editorSession.language == CodeLanguage.Xml) {
            val sync = XmlEditing.linkedTagRenameEdit(d.chars, caret)
            if (sync != null) {
                editorSession.applyEdits(listOf(sync), TextRange(caret))
                return@LaunchedEffect
            }
        }

        when {
            // Auto-open only when enabled in Settings; Ctrl-Space (an explicit reopen) always works.
            before == '.' || (before != null && isIdentifierChar(before, extraWordChars(path))) ->
                if (completion.autoPopupEnabled) completion.reopen() else completion.dismiss()
            else -> completion.dismiss()
        }
    }

    // code-action availability — debounced on the selection + text revision, so the lightbulb appears when
    // the caret rests on (or selects) something actionable. Cheap on the engine side (cached diagnostics +
    // the syntax tree; no fresh binding analysis), and the full text is materialized once per pause, here.
    LaunchedEffect(path, editorSession.selection, editorSession.textRevision) {
        acts.refreshAvailability(isFocused)
    }

    // signature help — re-resolve whenever the caret moves or the buffer changes (or Ctrl/Cmd-P bumps sigEpoch).
    // Gated by a cheap local scan so we only call the backend when the caret is actually inside a call's parens;
    // dismisses (and re-arms) when the caret leaves the call, so Esc only hides it for the current call.
    LaunchedEffect(path, editorSession.textRevision, editorSession.selection, sig.epoch, isFocused) {
        sig.resolve(isFocused, editorSession)
    }

    // host (toolbar Find button) requested the find bar
    LaunchedEffect(findEpoch) {
        if (findEpoch > 0) find.openBar(replace = false)
    }

    // recompute find matches when the query/options change or the buffer edits (debounced); select the match
    // nearest the caret so it scrolls into view. Closed or empty query ⇒ no matches (nothing highlights).
    LaunchedEffect(find.open, find.query, find.options, editorSession.textRevision) {
        if (find.open && find.query.isNotEmpty()) delay(120.milliseconds)
        find.recompute()
    }

    // ---- bring the caret into view after every edit/caret move ----
    LaunchedEffect(editorSession.editCount, viewport) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        val doc = editorSession.doc
        val line = doc.lineForOffset(editorSession.selection.end)
        val vcol = renderCache.rawToVisual(line, editorSession.selection.end - doc.lineStart(line))
        val layout = layoutFor(line)
        // Content-space top of the caret's (wrapped) sub-row.
        val subTop = if (wordWrap) layout.getLineTop(layout.getLineForOffset(vcol)) else 0f
        val top = metrics.padTop + vlayout.topRow(line) * metrics.lineHeight + subTop
        val bottom = top + metrics.lineHeight
        val vh = viewport.height.toFloat()
        if (top < vOffset.floatValue) vOffset.floatValue = (top - metrics.lineHeight).coerceIn(0f, maxV())
        else if (bottom > vOffset.floatValue + vh) vOffset.floatValue = (bottom - vh + metrics.lineHeight).coerceIn(0f, maxV())
        // Horizontal follow only when not wrapping — a wrapped buffer never scrolls sideways.
        if (!wordWrap) {
            val caretX = layout.getHorizontalPosition(vcol, usePrimaryDirection = true)
            val textViewW = viewport.width - gutterWidthPx - metrics.padLeft
            val margin = metrics.charWidth * 3
            if (caretX < hOffset.floatValue + margin) hOffset.floatValue = (caretX - margin).coerceIn(0f, maxH())
            else if (caretX > hOffset.floatValue + textViewW - margin) {
                hOffset.floatValue = (caretX - textViewW + margin).coerceIn(0f, maxH())
            }
        }
    }

    LaunchedEffect(path) { runCatching { focus.requestFocus() } }

    // ---- per-line diagnostic segments (recomputed per edit — O(diagnostics), they are few) ----
    // Keyed on the document *instance* (an edit swaps it; a caret move doesn't), so the key compare is an
    // O(1) reference check instead of an O(n) String equals on the whole text every keystroke.
    val diagByLine = remember(diagnostics, doc) { mapDiagnosticsToLines(diagnostics, doc) }
    val bracketPair = remember(doc, editorSession.selection) {
        matchingBracket(doc.chars, editorSession.selection.start)
    }

    // ---- keyboard handling ----
    fun handleKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        val word = ev.isCtrlPressed || ev.isAltPressed
        val select = ev.isShiftPressed
        val shortcut = ev.isCtrlPressed || ev.isMetaPressed
        val pageLines = (viewport.height / metrics.lineHeight).toInt().coerceAtLeast(1)
        lastInputWasTouch = false
        when (ev.key) {
            Key.DirectionLeft -> {
                if (ev.isMetaPressed) editorSession.moveLineStart(select) else editorSession.moveHorizontal(-1, select, word)
                return true
            }
            Key.DirectionRight -> {
                if (ev.isMetaPressed) editorSession.moveLineEnd(select) else editorSession.moveHorizontal(1, select, word)
                return true
            }
            Key.DirectionUp -> {
                if (ev.isMetaPressed) editorSession.moveDocBoundary(-1, select) else editorSession.moveVertical(-1, select)
                return true
            }
            Key.DirectionDown -> {
                if (ev.isMetaPressed) editorSession.moveDocBoundary(1, select) else editorSession.moveVertical(1, select)
                return true
            }
            Key.MoveHome -> {
                if (ev.isCtrlPressed) editorSession.moveDocBoundary(-1, select) else editorSession.moveLineStart(select)
                return true
            }
            Key.MoveEnd -> {
                if (ev.isCtrlPressed) editorSession.moveDocBoundary(1, select) else editorSession.moveLineEnd(select)
                return true
            }
            Key.PageUp -> { editorSession.moveVertical(-pageLines, select); return true }
            Key.PageDown -> { editorSession.moveVertical(pageLines, select); return true }
            Key.Backspace -> { editorSession.backspace(word); return true }
            Key.Delete -> { editorSession.deleteForward(word); return true }
            Key.Enter, Key.NumPadEnter -> {
                // Shift+Enter = complete statement (IntelliJ's Smart Enter): finish the line then open a new one.
                if (ev.isShiftPressed && !shortcut && !ev.isAltPressed) completeStatement()
                else editorSession.commitText("\n")
                return true
            }
            Key.Tab -> {
                if (!shortcut && !ev.isAltPressed) {
                    if (ev.isShiftPressed) editorSession.dedent() else editorSession.indent()
                    return true
                }
                return false
            }
        }
        if (shortcut) {
            when (ev.key) {
                Key.A -> { editorSession.selectAll(); return true }
                Key.C -> { editorSession.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }; return true }
                Key.X -> { editorSession.cutSelection()?.let { clipboard.setText(AnnotatedString(it)) }; return true }
                Key.V -> {
                    clipboard.getText()?.text?.let { if (it.isNotEmpty()) editorSession.commitText(it) }
                    return true
                }
                // Undo (⌘/Ctrl-Z), redo (⌘/Ctrl-Shift-Z or Ctrl-Y). Dismiss the popup/snippet first so they
                // don't act on the reverted buffer.
                Key.Z -> { completion.dismiss(); snippet = null; if (ev.isShiftPressed) editorSession.redo() else editorSession.undo(); return true }
                Key.Y -> { completion.dismiss(); snippet = null; editorSession.redo(); return true }
                // Zoom: ⌘/Ctrl with +/-/0 (mirrors the pinch gesture).
                Key.Equals, Key.Plus, Key.NumPadAdd -> { onFontScaleChange(clampFontScale(fontScale * 1.1f)); return true }
                Key.Minus, Key.NumPadSubtract -> { onFontScaleChange(clampFontScale(fontScale / 1.1f)); return true }
                Key.Zero -> { onFontScaleChange(1f); return true }
            }
            return false
        }

        // Printable-character insert. Whether a key is text — versus a modifier/lock/function/navigation
        // key or an unhandled command chord — is decided per-platform (Android consults the native
        // KeyEvent's isModifierKey/unicodeChar, robust against Bluetooth keycodes Compose's Key enum doesn't
        // name; desktop mirrors the utf16CodePoint path). Returns the code point to insert, or -1.
        val cp = textInputCodePoint(ev)
        if (cp >= 0) {
            editorSession.commitText(codePointToString(cp))
            return true
        }
        return false
    }

    Box(
        modifier
            .background(colors.editorBg)
            .clipToBounds()
            .onGloballyPositioned {
                paneTopInWindow = it.positionInWindow().y
                paneBottomInWindow = it.positionInWindow().y + it.size.height
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .onGloballyPositioned { contentInWindow.value = it.positionInWindow() }
                .editorTextInput(editorSession, editorIme)
                .focusRequester(focus)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.S) {
                        onSave(); return@onPreviewKeyEvent true
                    }
                    // Find (⌘/Ctrl-F) / find+replace (⌘/Ctrl-R); seed the query from the current selection.
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && (ev.key == Key.F || ev.key == Key.R)) {
                        val seed = editorSession.selectedText()?.takeIf { it.isNotEmpty() && '\n' !in it }
                        find.openBar(replace = ev.key == Key.R, seed = seed)
                        completion.dismiss()
                        return@onPreviewKeyEvent true
                    }
                    // Go to definition (⌘/Ctrl-B): resolve the resource/symbol at the caret and jump to it.
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.B) {
                        val text = editorSession.doc.text
                        val caret = editorSession.selection.start
                        scope.launch {
                            runCatching { backend.definitionAt(path, text, caret) }.getOrNull()
                                ?.let { onNavigate(it.path, it.offset) }
                        }
                        return@onPreviewKeyEvent true
                    }
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Spacebar) {
                        completion.reopen(immediate = true); return@onPreviewKeyEvent true
                    }
                    // Parameter info (Ctrl/Cmd-P, a la IntelliJ): force the signature-help panel even if it was
                    // dismissed — re-arm + bump the epoch so the resolve effect re-runs for the call at the caret.
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.P) {
                        sig.triggerExplicit(); return@onPreviewKeyEvent true
                    }
                    // Rename (F2, or Shift-F6 a la IntelliJ): prompt for a new name → project-wide rename.
                    if (ev.key == Key.F2 || (ev.isShiftPressed && ev.key == Key.F6)) {
                        startRename(); return@onPreviewKeyEvent true
                    }
                    // Code actions: Alt+Enter (or Ctrl/Cmd-.) opens the lightbulb menu; when it's open the
                    // arrows + Enter/Tab drive it and Esc closes it (checked before completion's own keys).
                    if ((ev.isAltPressed && ev.key == Key.Enter) || ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Period)) {
                        if (acts.available.isNotEmpty()) acts.openMenu()
                        return@onPreviewKeyEvent true
                    }
                    if (acts.menuOpen) {
                        return@onPreviewKeyEvent when (ev.key) {
                            Key.Escape -> { acts.closeMenu(); true }
                            Key.DirectionDown -> { acts.moveSelection(1); true }
                            Key.DirectionUp -> { acts.moveSelection(-1); true }
                            Key.Enter, Key.Tab -> { acts.applyAt(acts.menuSelected); true }
                            else -> false
                        }
                    }
                    // Active template (snippet) session steers Tab/Shift-Tab/Escape when the completion popup
                    // isn't up. Tab advances to the next stop (mirroring linked placeholders), Shift-Tab goes
                    // back, Escape jumps to the final caret. Falls through (snippet ends) when stops run out.
                    val sn = snippet
                    if (sn != null && !showPopup) {
                        when (ev.key) {
                            Key.Tab -> { if (ev.isShiftPressed) sn.prev() else if (!sn.next()) snippet = null; return@onPreviewKeyEvent true }
                            Key.Escape -> { sn.finish(); snippet = null; return@onPreviewKeyEvent true }
                            else -> Unit
                        }
                    }
                    // Esc closes the (informational) signature-help panel when no completion popup is open; the
                    // panel captures no other keys, so everything else flows through to the editor.
                    if (sig.help != null && !sig.dismissed && !showPopup && ev.key == Key.Escape) {
                        sig.dismiss(); return@onPreviewKeyEvent true
                    }
                    if (!showPopup) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape -> { completion.dismiss(); true }
                        Key.DirectionDown -> { completion.selected = (safeSelected + 1).coerceAtMost((displayed.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionUp -> { completion.selected = (safeSelected - 1).coerceAtLeast(0); true }
                        Key.Tab, Key.Enter -> { accept(); true }
                        else -> false
                    }
                }
                .onKeyEvent { handleKey(it) }
                // pinch-to-zoom: a 2-finger gesture scales the editor font. Watched on the Initial pass
                // (outer→inner) so a pinch is claimed for zoom BEFORE the scroll containers below treat the
                // two-finger movement as a pan — otherwise the editor scrolled/jittered (and a stray tap could
                // land) while zooming. Acts ONLY when ≥2 fingers are down, so single-finger scroll/selection
                // still flow unconsumed to the detectors below. Disabled via Settings → Editor → Pinch to zoom
                // (then a 2-finger gesture falls through to the scroll containers as a pan).
                .then(
                    if (!pinchZoom) Modifier else Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    val z = event.calculateZoom()
                                    if (z != 1f) onFontScaleChange(clampFontScale(liveScale.value * z))
                                    // Consume the whole 2-finger gesture (even on a no-zoom frame) so it stays a
                                    // pure pinch — the scrollable/tap detectors below see consumed changes and
                                    // skip it, instead of stealing the pan as a scroll.
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                )
                // Scroll: either one 2D state (free diagonal pan, touch) or the orientation-locked pair (the
                // classic one-axis-per-gesture drag + desktop wheel/trackpad). `scrollable2D` is the
                // orientation-unlocked sibling of `scrollable` — same drag-gesture node and tap/selection
                // coexistence — so it drops into the exact same slot.
                .then(
                    if (useTwoAxisScroll) Modifier.scrollable2D(scroll2D)
                    else Modifier
                        .scrollable(vScroll, Orientation.Vertical, reverseDirection = true)
                        .scrollable(hScroll, Orientation.Horizontal, reverseDirection = true),
                )
                // Mouse hover (desktop): track the hovered line so an expandable fold shows its chevron on hover
                // (IntelliJ-style). Observation only — never consumes, so it doesn't disturb taps/scroll/drag.
                .pointerInput(editorSession, metrics, gutterWidthPx, wrapActive) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull()
                            when {
                                change?.type != PointerType.Mouse -> {}
                                event.type == PointerEventType.Exit -> hoveredLine = -1
                                event.type == PointerEventType.Move || event.type == PointerEventType.Enter -> {
                                    val ln = lineAtY(change.position.y)
                                    if (ln != hoveredLine) hoveredLine = ln
                                }
                            }
                        }
                    }
                }
                // Keyed on metrics/gutterWidth too: a gesture block captures the geometry helpers
                // (offsetAt/lineAtY) once when it launches, so it must re-launch when a zoom rescales the
                // line metrics — otherwise a post-zoom tap maps through stale lineHeight to the wrong line.
                .pointerInput(editorSession, metrics, gutterWidthPx, wrapActive) {
                    // longPress flag, shared across this detector's callbacks within one gesture.
                    var longPressed = false
                    detectTapGestures(
                        // Place the caret in onPress (fires on the first finger-lift) instead of onTap — when
                        // onDoubleTap is set, onTap is held back by the double-tap timeout (~300ms), which is the
                        // lag. tryAwaitRelease() returns on that first up; false if the gesture became a scroll
                        // (cancelled), and the longPressed guard avoids clobbering a long-press word selection.
                        onPress = { pos ->
                            longPressed = false
                            val released = tryAwaitRelease()
                            if (released && !longPressed) {
                                focus.requestFocus()
                                // Third quick tap near the double-tap → select the whole line.
                                val triple = tripleArmed && (pos - tripleArmPos).getDistance() < 60f
                                // A tap on a gutter error/warning glyph opens that line's diagnostic sheet
                                // (full message + fixes) instead of moving the caret.
                                val gutterDiag = if (pos.x < gutterWidthPx) acts.diagnosticOnLine(lineAtY(pos.y)) else null
                                when {
                                    foldActionAt(pos) -> {} // toggled/expanded a fold (gutter chevron or placeholder)
                                    triple -> {
                                        tripleArmed = false; tripleArmJob?.cancel()
                                        editorSession.selectLineAt(offsetAt(pos))
                                        if (lastInputWasTouch) handlesVisible = true
                                    }
                                    gutterDiag != null -> acts.openSheet(gutterDiag)
                                    else -> {
                                        val newCaret = offsetAt(pos)
                                        // Re-tap the existing caret position (same line/column) to TOGGLE the
                                        // Paste/Select-all toolbar; a first tap just places the caret. Tapping a
                                        // new spot hides it. This is the dismiss path (Android places the bar on a
                                        // re-tap, not on every tap, so it isn't stuck open).
                                        val prev = editorSession.selection
                                        val reTap = prev.collapsed && prev.start == newCaret
                                        editorSession.setCaret(newCaret)
                                        if (lastInputWasTouch) {
                                            handlesVisible = reTap && !handlesVisible
                                            editorIme.show() // explicit tap → raise the keyboard
                                        }
                                    }
                                }
                            }
                        },
                        onDoubleTap = { pos ->
                            focus.requestFocus()
                            editorSession.selectWordAt(offsetAt(pos))
                            if (lastInputWasTouch) handlesVisible = true
                            // arm triple-tap: a quick third tap nearby (within the window below) selects the line
                            tripleArmed = true; tripleArmPos = pos
                            tripleArmJob?.cancel()
                            tripleArmJob = scope.launch { delay(320.milliseconds); tripleArmed = false }
                        },
                        onLongPress = { pos ->
                            longPressed = true
                            focus.requestFocus()
                            // Long-press → select the word under the finger and raise the selection chrome
                            // (handles + the floating toolbar), the standard Android text gesture. Code actions
                            // are reached from the lightbulb in that toolbar (and the gutter bulb), so this
                            // gesture is never overloaded.
                            completion.dismiss()
                            editorSession.selectWordAt(offsetAt(pos))
                            if (lastInputWasTouch) {
                                handlesVisible = true
                                editorIme.show() // explicit long-press → raise the keyboard
                            }
                        },
                    )
                }
                // Innermost pointer handler: it sees events first on the Main pass, so it can claim a
                // mouse drag (and the touch selection-handle drags) BEFORE the scroll containers above
                // swallow the movement. Mouse gestures are owned end-to-end here (click-count → caret/
                // word/line + drag-to-select) and the down is consumed, so detectTapGestures stays
                // touch-only. Touch taps fall through unconsumed to detectTapGestures.
                .pointerInput(editorSession, metrics, gutterWidthPx, wrapActive) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        if (down.type == PointerType.Mouse) {
                            lastInputWasTouch = false
                            focus.requestFocus()
                            down.consume() // keep the scroll containers + detectTapGestures out of it
                            // Count consecutive clicks ourselves: 1 → caret, 2 → word, 3 → line, then wrap.
                            val near = (down.position - mouseLastClickPos).getDistance() < 24f
                            mouseClicks =
                                if (near && down.uptimeMillis - mouseLastClickMs <= 300L) (mouseClicks % 3) + 1 else 1
                            mouseLastClickMs = down.uptimeMillis
                            mouseLastClickPos = down.position
                            val anchor = offsetAt(down.position)
                            // A click on a gutter error/warning glyph opens that line's diagnostic sheet.
                            val gutterDiag = if (down.position.x < gutterWidthPx)
                                acts.diagnosticOnLine(lineAtY(down.position.y)) else null
                            when {
                                mouseClicks == 1 && foldActionAt(down.position) -> {} // fold chevron / placeholder
                                gutterDiag != null && mouseClicks == 1 -> acts.openSheet(gutterDiag)
                                mouseClicks == 2 -> editorSession.selectWordAt(anchor)
                                mouseClicks == 3 -> editorSession.selectLineAt(anchor)
                                else -> editorSession.setCaret(anchor)
                            }
                            // Anchor the drag at the current selection start so a drag after a double/
                            // triple click still extends from where the click landed.
                            val dragAnchor = editorSession.selection.start
                            drag(down.id) { change ->
                                editorSession.setSelectionRange(dragAnchor, offsetAt(change.position))
                                change.consume()
                            }
                            return@awaitEachGesture
                        }
                        // Touch: only claim the gesture for a selection-handle drag; otherwise leave it
                        // unconsumed so the scrollables and detectTapGestures (tap/double-tap) still run.
                        lastInputWasTouch = true
                        val handleRadius = 14.dp.toPx()
                        fun handleCenter(offset: Int): Offset {
                            val (_, x, top) = caretGeometry(offset)
                            return Offset(x, top + metrics.lineHeight + handleRadius * 0.6f)
                        }
                        val sel = editorSession.selection
                        val hit: Char? = when {
                            handlesVisible && !sel.collapsed &&
                                (down.position - handleCenter(sel.min)).getDistance() < handleRadius -> 'a'
                            handlesVisible && !sel.collapsed &&
                                (down.position - handleCenter(sel.max)).getDistance() < handleRadius -> 'b'
                            handlesVisible && sel.collapsed &&
                                (down.position - handleCenter(sel.start)).getDistance() < handleRadius -> 'c'
                            else -> null
                        }
                        if (hit != null) {
                            down.consume()
                            // The handle sits ~a line below its anchor, so the finger covers the row below.
                            // Lift the hit-point back up to the anchored line so dragging tracks what you see.
                            val lift = metrics.lineHeight * 0.5f + handleRadius * 0.6f
                            drag(down.id) { change ->
                                val off = offsetAt(change.position.copy(y = change.position.y - lift))
                                when (hit) {
                                    'a' -> editorSession.setSelectionRange(editorSession.selection.max, off)
                                    'b' -> editorSession.setSelectionRange(editorSession.selection.min, off)
                                    else -> editorSession.setCaret(off)
                                }
                                change.consume()
                            }
                        }
                    }
                }
                .drawBehind {
                    drawEditor(
                        session = editorSession,
                        metrics = metrics,
                        gutterWidth = gutterWidthPx,
                        vOff = vOffset.floatValue,
                        hOff = hOffset.floatValue,
                        layoutFor = ::layoutFor,
                        compositeLayoutFor = ::compositeLayoutFor,
                        rawToVisual = renderCache::rawToVisual,
                        foldModel = editorSession.foldModel,
                        vlayout = vlayout,
                        wrap = wrapActive,
                        foldableStartLines = foldableStartLines,
                        foldStripWidth = foldStripPx,
                        hoveredLine = hoveredLine,
                        numberLayout = ::numberLayout,
                        diagByLine = diagByLine,
                        bracketPair = bracketPair,
                        findMatches = if (find.open) find.matches else emptyList(),
                        currentMatch = find.currentIndex,
                        colors = EditorDrawColors(
                            background = colors.editorBg,
                            currentLine = colors.currentLine,
                            caret = colors.accent,
                            selection = colors.accent.copy(alpha = 0.30f),
                            gutterText = colors.gutterText,
                            gutterCurrent = colors.textSecondary,
                            gutterBorder = colors.separator,
                            error = colors.error,
                            warning = colors.warning,
                            info = colors.info,
                            muted = colors.textTertiary,
                            composing = colors.textSecondary,
                            indentGuide = colors.hairline,
                            findMatch = colors.warning.copy(alpha = 0.28f),
                            findCurrent = colors.accent.copy(alpha = 0.5f),
                        ),
                        caretVisible = isFocused && (blinkOn || !editorSession.selection.collapsed),
                        caretContent = caretAnim.value, // animated, content-space; read here → redraw per frame
                        handlesVisible = handlesVisible && lastInputWasTouch,
                        handleColor = colors.accent,
                    )
                },
        )

        // inline diagnostic chips — one per line, the most severe diagnostic on it; composed once and
        // positioned in the layout phase so scrolling moves them without recomposition. Only Error and
        // Warning get a chip (Info/Hint stay quiet: squiggle + gutter only).
        run {
            // The most-severe Error/Warning per line, memoized on (diagnostics, doc): a caret-only move
            // recomposes the editor (editCount/selection bump the effects) but leaves the buffer untouched,
            // so this is a cache hit then — no per-move HashMap rebuild. It changes only on an actual edit.
            val chipPerLine = remember(diagnostics, doc) {
                val m = HashMap<Int, UiDiagnostic>()
                for (d in diagnostics) {
                    if (d.severity != UiSeverity.Error && d.severity != UiSeverity.Warning) continue
                    val off = d.startOffset.coerceIn(0, doc.length)
                    val ln = doc.lineForOffset(off)
                    val cur = m[ln]
                    // lower ordinal = more severe (Error before Warning)
                    if (cur == null || d.severity.ordinal < cur.severity.ordinal) m[ln] = d
                }
                m
            }
            val fm = editorSession.foldModel
            // Clip the chips to the code area (right of the gutter): a chip that scrolls left then slides UNDER
            // the gutter — which is painted behind and shows through the clipped-out strip — instead of
            // overlapping it. Draw-only clip; the chips keep their absolute positions.
            Box(
                Modifier.matchParentSize().drawWithContent {
                    clipRect(left = gutterWidthPx) { this@drawWithContent.drawContent() }
                },
            ) {
                for ((ln, d) in chipPerLine) {
                    if (fm.isHidden(ln)) continue // diagnostic inside a collapsed region → no chip
                    // Place after the composite text on a fold-start line, else after the real line. When wrapping,
                    // sit after the end of the line's LAST wrapped row (its right edge + that row's vertical offset).
                    val chipLayout = if (fm.foldStartingAt(ln) != null) compositeLayoutFor(ln) else layoutFor(ln)
                    val lastSub = if (wordWrap) (chipLayout.lineCount - 1).coerceAtLeast(0) else 0
                    val lineWidth = if (wordWrap) chipLayout.getLineRight(lastSub) else chipLayout.size.width.toFloat()
                    DiagnosticChip(
                        d.severity,
                        d.unused,
                        d.message,
                        onClick = { acts.openSheet(d) },
                        modifier = Modifier.offset {
                            IntOffset(
                                (gutterWidthPx + metrics.padLeft + lineWidth + 24f - hOffset.floatValue).roundToInt(),
                                (metrics.padTop + (vlayout.topRow(ln) + lastSub) * metrics.lineHeight - vOffset.floatValue).roundToInt(),
                            )
                        },
                    )
                }
            }
        }

        // floating selection toolbar (touch): Copy / Cut / Paste / Select all above the selection
        if (handlesVisible && lastInputWasTouch) {
            val selMin = editorSession.selection.min
            val (_, selX, selTop) = caretGeometry(selMin)
            val gapPx = with(density) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(selX, selTop, gapPx) {
                    AboveAnchorPositionProvider(selX.roundToInt(), selTop.roundToInt(), gapPx)
                },
            ) {
                SelectionToolbar(
                    hasSelection = !editorSession.selection.collapsed,
                    // Keep quick-FIXES out of this clipboard toolbar: on a diagnostic the gutter lightbulb owns
                    // them (tap → fix list), so the toolbar stays Copy/Cut/Paste only. Off a diagnostic there's
                    // no gutter bulb, so the toolbar still surfaces caret INTENTIONS here (the only touch path).
                    hasActions = acts.available.isNotEmpty() && acts.caretDiagnostic == null,
                    onActions = { handlesVisible = false; acts.openMenu() },
                    onCopy = {
                        editorSession.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }
                        handlesVisible = false
                    },
                    onCut = {
                        editorSession.cutSelection()?.let { clipboard.setText(AnnotatedString(it)) }
                        handlesVisible = false
                    },
                    onPaste = {
                        clipboard.getText()?.text?.let { if (it.isNotEmpty()) editorSession.commitText(it) }
                        handlesVisible = false
                    },
                    onSelectAll = { editorSession.selectAll() },
                )
            }
        }

        // completion popup, anchored at the token start in viewport coordinates. Mounted on `popupVisible`
        // (the keep-alive latch) and rendered from `shownCompletion` (the last good state) so a keystroke's
        // transient session swap / filter miss doesn't blink the window shut.
        val shown = completion.shown
        if (completion.popupVisible && shown != null) {
            val anchor = shown.tokenStart.coerceIn(0, docLength)
            val (anchorLine, anchorX, anchorTop) = caretGeometry(anchor)
            val lineBottomPx = anchorTop + metrics.lineHeight
            val gapPx = with(density) { 6.dp.roundToPx() }
            val marginPx = with(density) { 8.dp.roundToPx() }
            val positionProvider = remember(anchorX, lineBottomPx, gapPx, marginPx) {
                CompletionPopupPositionProvider(
                    anchorX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                    lineBottomPx.roundToInt(),
                    gapPx,
                    marginPx,
                )
            }
            // room between the caret line and the pane bottom (which already sits above the keyboard)
            val caretBottomY = paneTopInWindow + lineBottomPx
            val roomBelowDp = with(density) { (paneBottomInWindow - caretBottomY - gapPx - marginPx).toDp() }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { completion.dismiss() },
                // The popup is non-focusable (typing must reach the editor), but Compose still registers it
                // for outside-touch dismissal — and every tap on the SOFT KEYBOARD is a touch outside the
                // popup window, so it fired onDismissRequest on each keystroke and blinked the popup shut.
                // Disable click-outside dismissal; the popup closes on Esc, accept, or the caret leaving the
                // token (handled above), not on a stray outside touch.
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                BoxWithConstraints {
                    val compact = maxWidth < 600.dp
                    val popupWidth = if (compact) (maxWidth * 0.85f).coerceIn(240.dp, 340.dp) else 440.dp
                    // Fill the room below the caret (the popup always opens below it) so the list auto-expands —
                    // and re-expands as the user scrolls, since `roomBelowDp` derives from caretGeometry/vOffset
                    // and recomputes on each scroll. `roomBelowDp` already runs to the editor pane's bottom (above
                    // the keyboard / symbol bar; the hidden bottom nav is NOT reserved), minus the gap+margin —
                    // no extra strip reserve (docs are beside/flip now, never a strip under the list). Bounded
                    // only by a generous ceiling for tall desktop windows.
                    val listMax = roomBelowDp.coerceIn(MinListHeight, MaxListHeight)
                    val items = shown.items
                    CompletionList(
                        items = items,
                        selectedIndex = safeSelected.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                        prefix = shown.prefix,
                        width = popupWidth,
                        maxListHeight = listMax,
                        // Narrow screens flip to docs on demand (the side panel would squish); wide shows it beside.
                        docsBeside = !compact,
                        onPick = { item ->
                            completion.selected = items.indexOf(item).coerceAtLeast(0)
                            accept(item) // accept the tapped row, not the (stale) currently-selected index
                        },
                        onHover = { completion.selected = it },
                    )
                }
            }
        }

        // signature-help (parameter-info) panel — floated ABOVE the caret line, independent of the completion
        // popup below it. Non-focusable so typing keeps reaching the editor; dismissed by the host logic above.
        val sigHelp = sig.help
        if (sigHelp != null && !sig.dismissed && isFocused && sigHelp.signatures.isNotEmpty()) {
            val (_, sigX, sigTop) = caretGeometry(caretOffset)
            val gapPx = with(density) { 6.dp.roundToPx() }
            val positionProvider = remember(sigX, sigTop, gapPx) {
                AboveAnchorPositionProvider(
                    sigX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                    sigTop.roundToInt(),
                    gapPx,
                )
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { sig.dismiss() },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                SignatureHelpPopup(sigHelp, mobile = isMobilePlatform)
            }
        }

        // lightbulb floating just ABOVE the caret — ONLY when the caret has entered a line/range a diagnostic
        // covers (so it clearly signals "there's a fix to apply here"), there are actions, the completion popup
        // isn't showing, and the fix menu isn't already open. Tap it → the fix list (opens below the caret).
        // Caret intentions elsewhere stay reachable via Alt-Enter / the selection toolbar, just without a bulb.
        if (acts.available.isNotEmpty() && acts.caretDiagnostic != null && !showPopup && !acts.menuOpen && isFocused) {
            val (_, bulbX, bulbTop) = caretGeometry(caretOffset)
            val gapPx = with(density) { 6.dp.roundToPx() }
            val positionProvider = remember(bulbX, bulbTop, gapPx) {
                AboveAnchorPositionProvider(
                    bulbX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                    bulbTop.roundToInt(),
                    gapPx,
                )
            }
            Popup(popupPositionProvider = positionProvider) {
                FloatingLightbulb(onClick = { acts.openMenu() })
            }
        }

        // @Preview gutter icons — a tappable glyph in the gutter beside each Compose @Preview annotation.
        // Tapping switches this tab to the Preview surface rendering that specific variant. Positioned per line
        // and read in the layout phase, so they scroll with the document like the diagnostic chips. Variants of
        // one annotation (a MultiPreview / @PreviewParameter expansion) share an offset → one icon per line.
        val seenPreviewLines = HashSet<Int>()
        for (p in editorSession.previewMarkers) {
            val ln = doc.lineForOffset(p.offset.coerceIn(0, docLength))
            if (editorSession.foldModel.isHidden(ln)) continue // @Preview folded away → no gutter icon
            if (!seenPreviewLines.add(ln)) continue // one icon per annotation line
            PreviewGutterIcon(
                onClick = { onPreview(p.variantId) },
                modifier = Modifier.offset {
                    IntOffset(
                        1.dp.roundToPx(),
                        (metrics.padTop + vlayout.topRow(ln) * metrics.lineHeight - vOffset.floatValue + (metrics.lineHeight - 20.dp.toPx()) / 2f).roundToInt(),
                    )
                },
            )
        }

        // code-actions menu, anchored below the caret line (same position machinery as completion)
        if (acts.menuOpen && acts.available.isNotEmpty()) {
            val (_, anchorX, anchorTop) = caretGeometry(caretOffset)
            val lineBottomPx = anchorTop + metrics.lineHeight
            val gapPx = with(density) { 6.dp.roundToPx() }
            val marginPx = with(density) { 8.dp.roundToPx() }
            val positionProvider = remember(anchorX, lineBottomPx, gapPx, marginPx) {
                CompletionPopupPositionProvider(
                    anchorX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()),
                    lineBottomPx.roundToInt(),
                    gapPx,
                    marginPx,
                )
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { acts.closeMenu() },
            ) {
                BoxWithConstraints {
                    val compact = maxWidth < 600.dp
                    val popupWidth = if (compact) (maxWidth * 0.9f).coerceIn(240.dp, 340.dp) else 360.dp
                    CodeActionsMenu(
                        actions = acts.available,
                        selectedIndex = acts.menuSelected.coerceIn(0, (acts.available.size - 1).coerceAtLeast(0)),
                        width = popupWidth,
                        onPick = { acts.applyAt(it) },
                    )
                }
            }
        }

        // rename prompt — a small centered card over the editor
        rename?.let { r ->
            RenamePopup(
                state = r,
                busy = renameBusy,
                error = renameError,
                onChange = { rename = r.copy(newName = it) },
                onCommit = { commitRename() },
                onCancel = { if (!renameBusy) { rename = null; renameError = null } },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // diagnostic sheet — full (scrollable) message + that diagnostic's fixes, docked at the pane bottom
        acts.sheet?.let { d ->
            DiagnosticSheet(
                severity = d.severity,
                unused = d.unused,
                message = d.message,
                actions = acts.sheetActions,
                onPick = { acts.applySheetFix(it) },
                onDismiss = { acts.closeSheet() },
            )
        }

        // find / replace bar, docked at the top of the editor
        if (find.open) {
            FindReplaceBar(
                query = find.query,
                replace = find.replaceWith,
                replaceMode = find.replaceMode,
                options = find.options,
                matchCount = find.matches.size,
                currentIndex = if (find.matches.isEmpty()) -1 else find.currentIndex,
                regexError = find.regexError,
                onQueryChange = { find.query = it },
                onReplaceChange = { find.replaceWith = it },
                onToggleReplaceMode = { find.replaceMode = !find.replaceMode },
                onOptionsChange = { find.options = it },
                onPrev = { find.goto(find.currentIndex - 1) },
                onNext = { find.goto(find.currentIndex + 1) },
                onReplaceOne = { find.replaceCurrent() },
                onReplaceAll = { find.replaceAll() },
                onClose = { find.open = false; runCatching { focus.requestFocus() } },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}
