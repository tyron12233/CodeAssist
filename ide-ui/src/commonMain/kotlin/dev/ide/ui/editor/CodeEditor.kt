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
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
import dev.ide.ui.editor.core.EditorImeHandle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.EditSpan
import dev.ide.ui.editor.core.InlayPiece
import dev.ide.ui.editor.core.LineRenderCache
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.editor.core.RangeEdit
import dev.ide.ui.editor.core.TokenType
import dev.ide.ui.editor.core.editorTextInput
import dev.ide.ui.editor.core.textInputCodePoint
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.SyntaxColors
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
    showInlayHints: Boolean = true,
    /** Bump from the host (a toolbar Find button) to open the in-file find bar; 0 = no request. */
    findEpoch: Int = 0,
    /** Editor text zoom; 1.0 = the theme's code size. Driven by pinch + Ctrl-+/-/0; hoisted so it persists across tabs. */
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    /** Tapped a `@Preview` gutter icon — the host switches to the Preview surface rendering this function. */
    onPreview: (functionName: String) -> Unit = {},
) {
    // Source code is intrinsically left-to-right: the gutter sits at the left edge and lines flow right.
    // On an RTL system locale (e.g. Arabic) Compose flips `LocalLayoutDirection`, which would make
    // `rememberTextMeasurer` shape lines right-to-left (right-aligned/mirrored text) and mirror the
    // `Modifier.offset`/`Popup` anchoring for the gutter chips, lightbulb, and completion popup. Pin the
    // whole editor subtree to LTR so it renders identically regardless of the device language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        CodeEditorContent(
            path, session, backend, modifier, onSave, onNavigate, onRenamed,
            showInlayHints, findEpoch, fontScale, onFontScaleChange, onPreview,
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
    showInlayHints: Boolean = true,
    findEpoch: Int = 0,
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    onPreview: (functionName: String) -> Unit = {},
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
    val codeStyle = remember(syntax, typography, zoom) {
        typography.code.copy(color = syntax.default, fontSize = typography.code.fontSize * zoom)
    }
    val gutterStyle = remember(typography, zoom) { typography.codeSmall.copy(fontSize = typography.codeSmall.fontSize * zoom) }
    val metrics = remember(measurer, codeStyle, density) {
        val probe = measurer.measure(AnnotatedString("MMMMMMMMMM"), style = codeStyle, softWrap = false, maxLines = 1)
        with(density) {
            EditorMetrics(
                lineHeight = probe.size.height.toFloat(),
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

    // ---- inlay hints (inferred var/lambda types, parameter names, chaining) — fetched debounced, woven into
    // the line layouts as dimmed phantom text. Disabled ⇒ no fetch and the layout/offset math is the identity.
    var inlayHints by remember(path) { mutableStateOf<List<UiInlayHint>>(emptyList()) }
    LaunchedEffect(path, editorSession.textRevision, showInlayHints) {
        if (!showInlayHints) { inlayHints = emptyList(); return@LaunchedEffect }
        delay(300.milliseconds)
        val text = editorSession.doc.text
        // Hints share the one engine thread with completion/analysis; a completion request preempts this pass
        // (surfaced as AnalysisPreempted). Retry a few times — keeping the current hints in the meantime —
        // so a pass that loses the race to a keystroke still lands once typing settles, rather than the hints
        // going missing until the next edit (the old behavior was: clear on preempt, only retrigger on an edit).
        var attempt = 0
        while (attempt++ < 8) {
            try {
                inlayHints = backend.hintsAt(path, text, 0, text.length)
                break
            } catch (preempted: dev.ide.ui.backend.AnalysisPreempted) {
                delay(150.milliseconds) // let the interactive call finish, then try again
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel // the effect itself was cancelled (new edit/file) — don't swallow
            } catch (e: Throwable) {
                inlayHints = emptyList()
                break
            }
        }
    }
    // ---- semantic highlighting (type-aware coloring): fetched debounced off the engine thread and overlaid
    // on the lexical spans. Java/Kotlin only (other backends return nothing). Shares the engine thread with
    // completion/analysis, so a completion request preempts this pass (AnalysisPreempted) — retry a few times,
    // keeping the current tokens, so the coloring survives a keystroke race instead of vanishing until the
    // next edit. The session shifts the tokens in place on each edit, so they track the text in between.
    LaunchedEffect(path, editorSession.textRevision) {
        if (!path.endsWith(".java") && !path.endsWith(".kt") && !path.endsWith(".kts")) {
            editorSession.applySemanticTokens(emptyList()); return@LaunchedEffect
        }
        delay(280.milliseconds)
        val text = editorSession.doc.text
        var attempt = 0
        while (attempt++ < 8) {
            try {
                editorSession.applySemanticTokens(backend.semanticTokens(path, text))
                break
            } catch (preempted: dev.ide.ui.backend.AnalysisPreempted) {
                delay(150.milliseconds)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                break // keep whatever we had; a later edit retriggers
            }
        }
    }

    // ---- code folding: foldable regions (imports, type/function bodies, comments) fetched debounced off the
    // engine thread. Java/Kotlin only. The session preserves the user's collapse toggles across refetches and
    // collapses `collapsedByDefault` regions (imports) once per file; offsets shift in place between passes.
    LaunchedEffect(path, editorSession.textRevision) {
        if (!path.endsWith(".java") && !path.endsWith(".kt") && !path.endsWith(".kts")) {
            editorSession.applyCodeFolds(emptyList()); return@LaunchedEffect
        }
        delay(320.milliseconds)
        val text = editorSession.doc.text
        var attempt = 0
        while (attempt++ < 8) {
            try {
                val fresh = backend.codeFolds(path, text).map {
                    dev.ide.ui.editor.folding.FoldRegion(it.startOffset, it.endOffset, it.placeholder, it.kind, it.collapsedByDefault)
                }
                editorSession.applyCodeFolds(fresh)
                break
            } catch (preempted: dev.ide.ui.backend.AnalysisPreempted) {
                delay(150.milliseconds)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                break // keep whatever we had; a later edit retriggers
            }
        }
    }

    // If the caret lands inside a collapsed fold (go-to-definition, rename, programmatic navigation), reveal it
    // — tapping can't put the caret in hidden text (offsetAt clamps to the visible prefix), so this only fires
    // for non-tap navigation.
    LaunchedEffect(editorSession.selection) {
        editorSession.expandFoldAt(editorSession.selection.end)
    }

    // ---- @Preview gutter markers: the file's Compose @Preview functions, fetched debounced. Each draws a
    // tappable glyph in the gutter on its line → switch this tab to the Preview surface for that function.
    // Kotlin-only (the backend is a no-op elsewhere); cheap, so it just rides the edit cadence.
    // Stored on the session so they shift with edits (the gutter icon tracks its function while typing); the
    // debounced fetch refills the authoritative set.
    LaunchedEffect(path, editorSession.textRevision) {
        if (!path.endsWith(".kt") && !path.endsWith(".kts")) { editorSession.applyComposePreviews(emptyList()); return@LaunchedEffect }
        delay(400.milliseconds)
        val text = editorSession.doc.text
        editorSession.applyComposePreviews(runCatching { backend.composePreviews(path, text) }.getOrDefault(emptyList()))
    }
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
    val vOffset = remember(path) { mutableFloatStateOf(0f) }
    val hOffset = remember(path) { mutableFloatStateOf(0f) }
    fun contentHeight() = metrics.padTop + editorSession.foldModel.visualLineCount * metrics.lineHeight + metrics.padBottom
    fun contentWidth() = metrics.padLeft +
        max(renderCache.measuredMaxWidth, editorSession.maxLineChars * metrics.charWidth) + metrics.padRight
    fun maxV() = (contentHeight() - viewport.height).coerceAtLeast(0f)
    fun maxH() = (contentWidth() - (viewport.width - gutterWidthPx)).coerceAtLeast(0f)
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
    // A zoom rescales the line metrics (hence the content size), and the viewport changes on resize/rotate;
    // re-clamp the scroll so a zoom-out can't strand the viewport past the document end — where taps would
    // map to a coerced position that no longer matches what's rendered.
    // Re-clamp on fold changes too: collapsing a region shrinks the content height without changing the line
    // count, so a stale vOffset could otherwise strand the viewport past the (now shorter) document end.
    LaunchedEffect(zoom, editorSession.doc.lineCount, editorSession.foldRegions, viewport) {
        vOffset.floatValue = vOffset.floatValue.coerceIn(0f, maxV())
        hOffset.floatValue = hOffset.floatValue.coerceIn(0f, maxH())
    }

    // ---- geometry helpers (viewport coordinates ↔ document offsets) — all routed through the fold model so a
    // collapsed region occupies a single visual row and the hidden lines below it contribute no height ----
    fun lineTop(line: Int) = metrics.padTop + editorSession.foldModel.visualForDocLine(line) * metrics.lineHeight - vOffset.floatValue
    fun textLeft() = gutterWidthPx + metrics.padLeft - hOffset.floatValue
    fun layoutFor(line: Int) = renderCache.layoutFor(line, editorSession.doc, editorSession.styles)
    fun caretGeometry(offset: Int): Triple<Int, Float, Float> { // line, xInViewport, topInViewport
        val doc = editorSession.doc
        val line = doc.lineForOffset(offset)
        val x = layoutFor(line).getHorizontalPosition(renderCache.rawToVisual(line, offset - doc.lineStart(line)), usePrimaryDirection = true)
        return Triple(line, textLeft() + x, lineTop(line))
    }
    /** The document line shown at viewport [y] (the fold-start line when [y] is on a collapsed row). */
    fun lineAtY(y: Float): Int {
        val fm = editorSession.foldModel
        val row = floor((y + vOffset.floatValue - metrics.padTop) / metrics.lineHeight)
            .toInt().coerceIn(0, (fm.visualLineCount - 1).coerceAtLeast(0))
        return fm.docLineForVisual(row)
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
        val visualCol = layoutFor(line).getOffsetForPosition(Offset(xInLine.coerceAtLeast(0f), metrics.lineHeight / 2f))
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
        val x = gutterWidthPx + metrics.padLeft +
            layoutFor(ln).getHorizontalPosition(renderCache.rawToVisual(ln, off - d.lineStart(ln)), usePrimaryDirection = true)
        // Content-space Y uses the VISUAL row so the caret tracks the collapsed layout (folds above it shrink Y).
        Offset(x, metrics.padTop + editorSession.foldModel.visualForDocLine(ln) * metrics.lineHeight)
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
        if (!caretAnimReady || far || edited) {
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

    // ---- completion (same session/cache/filter machinery as before) ----
    var completion by remember(path) { mutableStateOf<CompletionSession?>(null) }
    var selected by remember(path) { mutableIntStateOf(0) }
    var dismissed by remember(path) { mutableStateOf(false) }
    var job by remember(path) { mutableStateOf<Job?>(null) }
    // Active snippet/template expansion (tab-stop stepping), or null. Reset when the file changes.
    var snippet by remember(path) { mutableStateOf<SnippetSession?>(null) }
    var paneTopInWindow by remember(path) { mutableFloatStateOf(0f) }
    var paneBottomInWindow by remember(path) { mutableFloatStateOf(0f) }

    // ---- signature help (parameter info): the IntelliJ panel floated ABOVE the call the caret is inside ----
    // Independent of completion: it re-resolves on every caret move / edit while the caret is inside a call
    // (gated by a cheap local scan so we don't hit the backend elsewhere), and dismisses when it leaves.
    var sigHelp by remember(path) { mutableStateOf<dev.ide.ui.backend.UiSignatureHelp?>(null) }
    var sigDismissed by remember(path) { mutableStateOf(false) }
    var sigEpoch by remember(path) { mutableIntStateOf(0) } // bumped by the explicit (Ctrl/Cmd-P) trigger

    // ---- code actions (lightbulb): quick-fixes + caret intentions at the current selection ----
    var actions by remember(path) { mutableStateOf<List<UiAction>>(emptyList()) }
    var actionsOpen by remember(path) { mutableStateOf(false) }
    var actionSelected by remember(path) { mutableIntStateOf(0) }

    // ---- diagnostic sheet: tap a gutter glyph / inline chip → full (scrollable) message + its fixes ----
    var diagnosticSheet by remember(path) { mutableStateOf<UiDiagnostic?>(null) }
    var sheetActions by remember(path) { mutableStateOf<List<UiAction>>(emptyList()) }

    // ---- in-file find / replace (Ctrl-F / Ctrl-R; or the toolbar Find button via findEpoch) ----
    var findOpen by remember(path) { mutableStateOf(false) }
    var replaceMode by remember(path) { mutableStateOf(false) }
    var findQuery by remember(path) { mutableStateOf("") }
    var replaceText by remember(path) { mutableStateOf("") }
    var findOptions by remember(path) { mutableStateOf(FindOptions()) }
    var matches by remember(path) { mutableStateOf<List<Match>>(emptyList()) }
    var currentMatch by remember(path) { mutableIntStateOf(0) }
    var regexError by remember(path) { mutableStateOf(false) }

    fun gotoMatch(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        currentMatch = i
        val m = matches[i]
        editorSession.setSelectionRange(m.start, m.end) // selects + scrolls the match into view
    }

    fun replaceCurrent() {
        val m = matches.getOrNull(currentMatch) ?: return
        editorSession.applyEdits(
            listOf(RangeEdit(m.start, m.end, replaceText, m.start + replaceText.length)),
            TextRange(m.start + replaceText.length),
        )
        // matches recompute on the textRevision bump; currentMatch then points at the following occurrence
    }

    fun replaceAll() {
        if (matches.isEmpty()) return
        val edits = matches.map { RangeEdit(it.start, it.end, replaceText, it.start + replaceText.length) }
        editorSession.applyEdits(edits, TextRange(matches.first().start + replaceText.length))
        currentMatch = 0
    }

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
    val liveCompletion = completion?.takeIf { it.coversCaret(doc.chars, caretOffset, wordExtra) }
    val activePrefix = liveCompletion?.let { doc.substring(it.tokenStart, caretOffset) } ?: ""
    val displayed = liveCompletion?.filtered(activePrefix) ?: emptyList()
    val showPopup = !dismissed && displayed.isNotEmpty()
    val safeSelected = selected.coerceIn(0, (displayed.size - 1).coerceAtLeast(0))

    // Keep the popup *window* mounted across the 1-frame gaps a keystroke opens up, instead of unmounting and
    // recreating the Popup window each keystroke (the blink the user sees). It opens once there are items, and
    // then stays open as long as the caret is still on the same token (`liveCompletion != null`) and the user
    // hasn't dismissed it — a momentarily-empty `displayed` (a stale cached session between the debounced
    // refreshes, which land ~110ms apart) does NOT close it. It closes only on an explicit dismiss
    // (Esc/accept/click-away/non-identifier → `dismissed`) or when the caret leaves the token. No timer, so
    // nothing races the refresh debounce. `shownCompletion` snapshots the last good render state (token +
    // items + prefix), so while `displayed` is transiently empty the window shows real content, never an empty box.
    var popupVisible by remember(path) { mutableStateOf(false) }
    val onToken = liveCompletion != null
    val hasItems = displayed.isNotEmpty()
    LaunchedEffect(dismissed, onToken, hasItems) {
        popupVisible = when {
            dismissed || !onToken -> false
            hasItems -> true
            else -> popupVisible // transient empty while still on the token: hold the popup open
        }
    }
    val shownCompletion = remember(path) { mutableStateOf<ShownCompletion?>(null) }
    SideEffect {
        val live = liveCompletion
        if (live != null && hasItems) shownCompletion.value = ShownCompletion(live.tokenStart, displayed, activePrefix)
    }

    fun refresh(immediate: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            if (!immediate) delay(110.milliseconds)
            val text = editorSession.doc.text
            val caret = editorSession.selection.start
            val res = runCatching { backend.complete(path, text, caret) }.getOrNull() ?: return@launch
            val sameToken = res.replaceStart == completion?.tokenStart
            completion = CompletionSession.from(res)
            if (!sameToken) selected = 0
            dismissed = res.items.isEmpty()
        }
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
        val snip = item.snippet
        if (snip != null) {
            var base = mainStart
            for (e in item.additionalEdits) {
                val st = e.start.coerceIn(0, len)
                if (st <= mainStart) base += e.newText.length - (e.end.coerceIn(st, len) - st)
            }
            applyEditsKeepingViewport(edits, TextRange(base), anchorLine)
            snippet = SnippetSession.start(editorSession, base, snip)
            dismissed = true
            job?.cancel()
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
        dismissed = true
        job?.cancel()
    }

    // Apply [act]: ask the backend for its edits over the buffer at the context range [ctxStart,ctxEnd), then
    // splice them in (the editor round-trip — reparse + re-analyze follow the normal text path). The caret is
    // kept on its logical spot by shifting it by the net delta of edits that land at/before it.
    fun runAction(act: UiAction, ctxStart: Int, ctxEnd: Int) {
        val text = editorSession.doc.text
        scope.launch {
            val raw = runCatching { backend.applyAction(path, text, ctxStart, ctxEnd, act.id) }.getOrNull().orEmpty()
            if (raw.isEmpty()) return@launch
            val len = editorSession.doc.length
            val edits = raw.map { e ->
                val st = e.start.coerceIn(0, len)
                RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length)
            }
            var caret = ctxStart
            for (e in edits) if (e.start <= caret) caret += e.text.length - (e.end - e.start)
            editorSession.applyEdits(edits, TextRange(caret.coerceAtLeast(0)))
        }
    }

    fun applyActionAt(index: Int) {
        val act = actions.getOrNull(index) ?: return
        actionsOpen = false
        val sel = editorSession.selection
        runAction(act, sel.min, sel.max)
    }

    // The most-severe diagnostic whose start sits on [line], or null — drives gutter-glyph and chip taps.
    fun diagnosticOnLine(line: Int): UiDiagnostic? {
        var best: UiDiagnostic? = null
        for (d in editorSession.diagnostics) {
            if (editorSession.doc.lineForOffset(d.startOffset.coerceIn(0, editorSession.doc.length)) != line) continue
            val cur = best
            if (cur == null || d.severity.ordinal < cur.severity.ordinal) best = d
        }
        return best
    }

    // Open the diagnostic sheet for [d] and fetch the quick-fixes registered for its range.
    fun openDiagnosticSheet(d: UiDiagnostic) {
        dismissed = true; job?.cancel(); actionsOpen = false
        diagnosticSheet = d
        sheetActions = emptyList()
        val text = editorSession.doc.text
        scope.launch {
            sheetActions = runCatching { backend.actionsAt(path, text, d.startOffset, d.endOffset) }.getOrNull().orEmpty()
        }
    }

    fun applySheetFix(index: Int) {
        val d = diagnosticSheet ?: return
        val act = sheetActions.getOrNull(index) ?: return
        diagnosticSheet = null
        runAction(act, d.startOffset, d.endOffset)
    }

    // keep the per-line render cache aligned with line splices (a render concern, owned by this surface)
    SideEffect {
        editorSession.onLinesShifted = { from, delta -> renderCache.shiftKeys(from, delta) }
        // Active template session re-anchors its tab stops on each edit (typing inside a placeholder); the
        // same per-edit signal keeps inlay-hint offsets aligned between debounced refetches, so a hint after
        // an edit doesn't lag at a stale position.
        editorSession.onSnippetEdit = { span ->
            snippet?.onEdit(span)
            if (inlayHints.isNotEmpty()) inlayHints = shiftInlayHints(inlayHints, span)
        }
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
        selected = 0
        val d = editorSession.doc
        val caret = editorSession.selection.start
        val before = if (caret in 1..d.length) d.charAt(caret - 1) else null

        // Auto-close XML tags: typing `>` after `<TextView …` inserts `</TextView>` and leaves the caret
        // between them. Guarded so it never re-fires on its own insertion (see XmlEditing.tagToClose).
        if (before == '>' && extraWordChars(path).isNotEmpty()) {
            val tag = XmlEditing.tagToClose(d.chars, caret)
            if (tag != null) {
                val close = "</$tag>"
                editorSession.applyEdits(listOf(RangeEdit(caret, caret, close, caret)), TextRange(caret))
                dismissed = true
                return@LaunchedEffect
            }
        }

        when {
            before == '.' || (before != null && isIdentifierChar(before, extraWordChars(path))) -> {
                dismissed = false
                refresh()
            }
            else -> {
                dismissed = true
                job?.cancel()
            }
        }
    }

    // code-action availability — debounced on the selection + text revision, so the lightbulb appears when
    // the caret rests on (or selects) something actionable. Cheap on the engine side (cached diagnostics +
    // the syntax tree; no fresh binding analysis), and the full text is materialized once per pause, here.
    LaunchedEffect(path, editorSession.selection, editorSession.textRevision) {
        delay(250.milliseconds)
        if (!isFocused) { actions = emptyList(); return@LaunchedEffect }
        val text = editorSession.doc.text
        val sel = editorSession.selection
        val result = runCatching { backend.actionsAt(path, text, sel.min, sel.max) }.getOrNull().orEmpty()
        actions = result
        when {
            result.isEmpty() -> actionsOpen = false
            actionSelected >= result.size -> actionSelected = 0
        }
    }

    // signature help — re-resolve whenever the caret moves or the buffer changes (or Ctrl/Cmd-P bumps sigEpoch).
    // Gated by a cheap local scan so we only call the backend when the caret is actually inside a call's parens;
    // dismisses (and re-arms) when the caret leaves the call, so Esc only hides it for the current call.
    LaunchedEffect(path, editorSession.textRevision, editorSession.selection, sigEpoch, isFocused) {
        if (!isFocused) { sigHelp = null; return@LaunchedEffect }
        val sel = editorSession.selection
        val caret = sel.start
        if (sel.start != sel.end || !caretInsideCall(editorSession.doc.chars, caret)) {
            sigHelp = null
            sigDismissed = false
            return@LaunchedEffect
        }
        if (sigDismissed) return@LaunchedEffect
        delay(40.milliseconds)
        val text = editorSession.doc.text
        sigHelp = runCatching { backend.signatureHelp(path, text, caret) }.getOrNull()
    }

    // host (toolbar Find button) requested the find bar
    LaunchedEffect(findEpoch) {
        if (findEpoch > 0) { findOpen = true; replaceMode = false }
    }

    // recompute find matches when the query/options change or the buffer edits (debounced); select the match
    // nearest the caret so it scrolls into view. Closed or empty query ⇒ no matches (nothing highlights).
    LaunchedEffect(findOpen, findQuery, findOptions, editorSession.textRevision) {
        if (!findOpen || findQuery.isEmpty()) { matches = emptyList(); regexError = false; return@LaunchedEffect }
        delay(120.milliseconds)
        regexError = findOptions.regex && runCatching { Regex(findQuery) }.isFailure
        val found = findMatches(editorSession.doc.text, findQuery, findOptions)
        matches = found
        if (found.isEmpty()) currentMatch = 0
        else {
            currentMatch = matchIndexFrom(found, editorSession.selection.start).coerceIn(0, found.size - 1)
            editorSession.setSelectionRange(found[currentMatch].start, found[currentMatch].end)
        }
    }

    // ---- bring the caret into view after every edit/caret move ----
    LaunchedEffect(editorSession.editCount, viewport) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        val (line, _, _) = caretGeometry(editorSession.selection.end)
        val top = metrics.padTop + editorSession.foldModel.visualForDocLine(line) * metrics.lineHeight
        val bottom = top + metrics.lineHeight
        val vh = viewport.height.toFloat()
        if (top < vOffset.floatValue) vOffset.floatValue = (top - metrics.lineHeight).coerceIn(0f, maxV())
        else if (bottom > vOffset.floatValue + vh) vOffset.floatValue = (bottom - vh + metrics.lineHeight).coerceIn(0f, maxV())
        val doc = editorSession.doc
        val caretX = layoutFor(line).getHorizontalPosition(
            renderCache.rawToVisual(line, editorSession.selection.end - doc.lineStart(line)), usePrimaryDirection = true,
        )
        val textViewW = viewport.width - gutterWidthPx - metrics.padLeft
        val margin = metrics.charWidth * 3
        if (caretX < hOffset.floatValue + margin) hOffset.floatValue = (caretX - margin).coerceIn(0f, maxH())
        else if (caretX > hOffset.floatValue + textViewW - margin) {
            hOffset.floatValue = (caretX - textViewW + margin).coerceIn(0f, maxH())
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
            Key.Enter, Key.NumPadEnter -> { editorSession.commitText("\n"); return true }
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
                Key.Z -> { dismissed = true; job?.cancel(); snippet = null; if (ev.isShiftPressed) editorSession.redo() else editorSession.undo(); return true }
                Key.Y -> { dismissed = true; job?.cancel(); snippet = null; editorSession.redo(); return true }
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
                        editorSession.selectedText()?.takeIf { it.isNotEmpty() && '\n' !in it }?.let { findQuery = it }
                        replaceMode = ev.key == Key.R
                        findOpen = true
                        dismissed = true; job?.cancel()
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
                        dismissed = false; refresh(immediate = true); return@onPreviewKeyEvent true
                    }
                    // Parameter info (Ctrl/Cmd-P, a la IntelliJ): force the signature-help panel even if it was
                    // dismissed — re-arm + bump the epoch so the resolve effect re-runs for the call at the caret.
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.P) {
                        sigDismissed = false; sigEpoch++; return@onPreviewKeyEvent true
                    }
                    // Rename (F2, or Shift-F6 a la IntelliJ): prompt for a new name → project-wide rename.
                    if (ev.key == Key.F2 || (ev.isShiftPressed && ev.key == Key.F6)) {
                        startRename(); return@onPreviewKeyEvent true
                    }
                    // Code actions: Alt+Enter (or Ctrl/Cmd-.) opens the lightbulb menu; when it's open the
                    // arrows + Enter/Tab drive it and Esc closes it (checked before completion's own keys).
                    if ((ev.isAltPressed && ev.key == Key.Enter) || ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Period)) {
                        if (actions.isNotEmpty()) { dismissed = true; job?.cancel(); actionSelected = 0; actionsOpen = true }
                        return@onPreviewKeyEvent true
                    }
                    if (actionsOpen) {
                        return@onPreviewKeyEvent when (ev.key) {
                            Key.Escape -> { actionsOpen = false; true }
                            Key.DirectionDown -> { actionSelected = (actionSelected + 1).coerceAtMost((actions.size - 1).coerceAtLeast(0)); true }
                            Key.DirectionUp -> { actionSelected = (actionSelected - 1).coerceAtLeast(0); true }
                            Key.Enter, Key.Tab -> { applyActionAt(actionSelected); true }
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
                    if (sigHelp != null && !sigDismissed && !showPopup && ev.key == Key.Escape) {
                        sigDismissed = true; return@onPreviewKeyEvent true
                    }
                    if (!showPopup) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape -> { dismissed = true; job?.cancel(); true }
                        Key.DirectionDown -> { selected = (safeSelected + 1).coerceAtMost((displayed.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionUp -> { selected = (safeSelected - 1).coerceAtLeast(0); true }
                        Key.Tab, Key.Enter -> { accept(); true }
                        else -> false
                    }
                }
                .onKeyEvent { handleKey(it) }
                // pinch-to-zoom: a 2-finger gesture scales the editor font. Watched on the Initial pass
                // (outer→inner) so a pinch is claimed for zoom BEFORE the scroll containers below treat the
                // two-finger movement as a pan — otherwise the editor scrolled/jittered (and a stray tap could
                // land) while zooming. Acts ONLY when ≥2 fingers are down, so single-finger scroll/selection
                // still flow unconsumed to the detectors below.
                .pointerInput(Unit) {
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
                }
                .scrollable(vScroll, Orientation.Vertical, reverseDirection = true)
                .scrollable(hScroll, Orientation.Horizontal, reverseDirection = true)
                // Mouse hover (desktop): track the hovered line so an expandable fold shows its chevron on hover
                // (IntelliJ-style). Observation only — never consumes, so it doesn't disturb taps/scroll/drag.
                .pointerInput(editorSession, metrics, gutterWidthPx) {
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
                .pointerInput(editorSession, metrics, gutterWidthPx) {
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
                                val gutterDiag = if (pos.x < gutterWidthPx) diagnosticOnLine(lineAtY(pos.y)) else null
                                when {
                                    foldActionAt(pos) -> {} // toggled/expanded a fold (gutter chevron or placeholder)
                                    triple -> {
                                        tripleArmed = false; tripleArmJob?.cancel()
                                        editorSession.selectLineAt(offsetAt(pos))
                                        if (lastInputWasTouch) handlesVisible = true
                                    }
                                    gutterDiag != null -> openDiagnosticSheet(gutterDiag)
                                    else -> {
                                        editorSession.setCaret(offsetAt(pos))
                                        if (lastInputWasTouch) {
                                            handlesVisible = true
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
                            dismissed = true; job?.cancel()
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
                .pointerInput(editorSession, metrics, gutterWidthPx) {
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
                                diagnosticOnLine(lineAtY(down.position.y)) else null
                            when {
                                mouseClicks == 1 && foldActionAt(down.position) -> {} // fold chevron / placeholder
                                gutterDiag != null && mouseClicks == 1 -> openDiagnosticSheet(gutterDiag)
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
                        foldableStartLines = foldableStartLines,
                        foldStripWidth = foldStripPx,
                        hoveredLine = hoveredLine,
                        numberLayout = ::numberLayout,
                        diagByLine = diagByLine,
                        bracketPair = bracketPair,
                        findMatches = if (findOpen) matches else emptyList(),
                        currentMatch = currentMatch,
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
            for ((ln, d) in chipPerLine) {
                if (fm.isHidden(ln)) continue // diagnostic inside a collapsed region → no chip
                // Place after the composite text on a fold-start line, else after the real line.
                val lineWidth = if (fm.foldStartingAt(ln) != null) compositeLayoutFor(ln).size.width else layoutFor(ln).size.width
                DiagnosticChip(
                    d.severity,
                    d.unused,
                    d.message,
                    onClick = { openDiagnosticSheet(d) },
                    modifier = Modifier.offset {
                        IntOffset(
                            (gutterWidthPx + metrics.padLeft + lineWidth + 24f - hOffset.floatValue).roundToInt(),
                            (metrics.padTop + fm.visualForDocLine(ln) * metrics.lineHeight - vOffset.floatValue).roundToInt(),
                        )
                    },
                )
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
                    // The lightbulb appears in the toolbar whenever the caret/selection has actions available —
                    // the easy, discoverable way to reach quick-fixes & intentions on a phone (tap → lightbulb).
                    hasActions = actions.isNotEmpty(),
                    onActions = { handlesVisible = false; dismissed = true; job?.cancel(); actionSelected = 0; actionsOpen = true },
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
        val shown = shownCompletion.value
        if (popupVisible && shown != null) {
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
                onDismissRequest = { dismissed = true; job?.cancel() },
                // The popup is non-focusable (typing must reach the editor), but Compose still registers it
                // for outside-touch dismissal — and every tap on the SOFT KEYBOARD is a touch outside the
                // popup window, so it fired onDismissRequest on each keystroke and blinked the popup shut.
                // Disable click-outside dismissal; the popup closes on Esc, accept, or the caret leaving the
                // token (handled above), not on a stray outside touch.
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                BoxWithConstraints {
                    val compact = maxWidth < 600.dp
                    val popupWidth = if (compact) (maxWidth * 0.8f).coerceIn(220.dp, 300.dp) else 420.dp
                    val listCap = if (compact) 240.dp else 296.dp
                    val listMax = (roomBelowDp - DocStripReserve).coerceIn(MinListHeight, listCap)
                    val items = shown.items
                    CompletionList(
                        items = items,
                        selectedIndex = safeSelected.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                        prefix = shown.prefix,
                        width = popupWidth,
                        maxListHeight = listMax,
                        onPick = { item ->
                            selected = items.indexOf(item).coerceAtLeast(0)
                            accept(item) // accept the tapped row, not the (stale) currently-selected index
                        },
                        onHover = { selected = it },
                    )
                }
            }
        }

        // signature-help (parameter-info) panel — floated ABOVE the caret line, independent of the completion
        // popup below it. Non-focusable so typing keeps reaching the editor; dismissed by the host logic above.
        val sig = sigHelp
        if (sig != null && !sigDismissed && isFocused && sig.signatures.isNotEmpty()) {
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
                onDismissRequest = { sigDismissed = true },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                SignatureHelpPopup(sig)
            }
        }

        // lightbulb on the caret line whenever actions are available and no completion popup is showing
        if (actions.isNotEmpty() && !showPopup && isFocused) {
            val caretLn = doc.lineForOffset(caretOffset)
            val lineTopPx = metrics.padTop + editorSession.foldModel.visualForDocLine(caretLn) * metrics.lineHeight - vOffset.floatValue
            ActionLightbulb(
                onClick = { dismissed = true; job?.cancel(); actionSelected = 0; actionsOpen = true },
                modifier = Modifier.offset {
                    IntOffset(
                        (gutterWidthPx - 19.dp.toPx()).roundToInt(),
                        (lineTopPx + (metrics.lineHeight - 18.dp.toPx()) / 2f).roundToInt(),
                    )
                },
            )
        }

        // @Preview gutter icons — a tappable glyph in the gutter beside each Compose @Preview function.
        // Tapping switches this tab to the Preview surface rendering that specific composable. Positioned
        // per line and read in the layout phase, so they scroll with the document like the diagnostic chips.
        for (p in editorSession.previewMarkers) {
            val ln = doc.lineForOffset(p.offset.coerceIn(0, docLength))
            if (editorSession.foldModel.isHidden(ln)) continue // @Preview folded away → no gutter icon
            PreviewGutterIcon(
                onClick = { onPreview(p.functionName) },
                modifier = Modifier.offset {
                    IntOffset(
                        1.dp.roundToPx(),
                        (metrics.padTop + editorSession.foldModel.visualForDocLine(ln) * metrics.lineHeight - vOffset.floatValue + (metrics.lineHeight - 20.dp.toPx()) / 2f).roundToInt(),
                    )
                },
            )
        }

        // code-actions menu, anchored below the caret line (same position machinery as completion)
        if (actionsOpen && actions.isNotEmpty()) {
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
                onDismissRequest = { actionsOpen = false },
            ) {
                BoxWithConstraints {
                    val compact = maxWidth < 600.dp
                    val popupWidth = if (compact) (maxWidth * 0.9f).coerceIn(240.dp, 340.dp) else 360.dp
                    CodeActionsMenu(
                        actions = actions,
                        selectedIndex = actionSelected.coerceIn(0, (actions.size - 1).coerceAtLeast(0)),
                        width = popupWidth,
                        onPick = { applyActionAt(it) },
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
        diagnosticSheet?.let { d ->
            DiagnosticSheet(
                severity = d.severity,
                unused = d.unused,
                message = d.message,
                actions = sheetActions,
                onPick = { applySheetFix(it) },
                onDismiss = { diagnosticSheet = null },
            )
        }

        // find / replace bar, docked at the top of the editor
        if (findOpen) {
            FindReplaceBar(
                query = findQuery,
                replace = replaceText,
                replaceMode = replaceMode,
                options = findOptions,
                matchCount = matches.size,
                currentIndex = if (matches.isEmpty()) -1 else currentMatch,
                regexError = regexError,
                onQueryChange = { findQuery = it },
                onReplaceChange = { replaceText = it },
                onToggleReplaceMode = { replaceMode = !replaceMode },
                onOptionsChange = { findOptions = it },
                onPrev = { gotoMatch(currentMatch - 1) },
                onNext = { gotoMatch(currentMatch + 1) },
                onReplaceOne = { replaceCurrent() },
                onReplaceAll = { replaceAll() },
                onClose = { findOpen = false; runCatching { focus.requestFocus() } },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** What the rename prompt is editing: where the caret was, the symbol's old name + kind, and the typed name. */
private data class RenameUiState(val offset: Int, val oldName: String, val kind: String, val newName: String)

/** The last good completion render state, latched so the popup window survives a keystroke's transient gaps. */
private data class ShownCompletion(val tokenStart: Int, val items: List<UiCompletionItem>, val prefix: String)

/** A centered prompt for the new identifier; Enter renames, Esc cancels. Auto-focused, with the name selected. */
@Composable
private fun RenamePopup(
    state: RenameUiState,
    busy: Boolean,
    error: String?,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    // Prefill with the old name, fully selected, so typing replaces it (the IntelliJ rename feel).
    var field by remember { mutableStateOf(TextFieldValue(state.newName, androidx.compose.ui.text.TextRange(0, state.newName.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier.padding(top = 48.dp).width(320.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.lg))
            .padding(16.dp),
    ) {
        Text("Rename ${state.kind}", color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, if (error != null) Ca.colors.error else Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { field = it; onChange(it.text) },
                singleLine = true,
                enabled = !busy,
                textStyle = Ca.type.body.copy(color = Ca.colors.textPrimary, fontFamily = Ca.type.codeFamily),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter -> { onCommit(); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                },
            )
        }
        if (error != null) {
            Spacer(Modifier.size(6.dp))
            Text(error, color = Ca.colors.error, style = Ca.type.caption2)
        }
        Spacer(Modifier.size(6.dp))
        Text(if (busy) "Renaming…" else "Enter to rename '${state.oldName}', Esc to cancel",
            color = Ca.colors.textTertiary, style = Ca.type.caption2)
    }
}

// ---- drawing ----

private class EditorMetrics(
    val lineHeight: Float,
    val charWidth: Float,
    val padTop: Float,
    val padLeft: Float,
    val padRight: Float,
    val padBottom: Float,
)

private class EditorDrawColors(
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

/**
 * Re-anchor inlay-hint offsets to a single text edit so they stay attached between debounced refetches.
 * A hint inside the replaced span is dropped (the next fetch repositions it); one after it shifts by the
 * length delta; one before it is untouched.
 */
private fun shiftInlayHints(hints: List<UiInlayHint>, span: EditSpan): List<UiInlayHint> {
    val es = span.start
    val ee = span.start + span.removed
    val d = span.added - span.removed
    if (d == 0 && span.removed == 0) return hints
    return hints.mapNotNull { h ->
        when {
            h.offset <= es -> h
            h.offset >= ee -> if (d == 0) h else h.copy(offset = h.offset + d)
            else -> null
        }
    }
}

private class DiagSeg(val startCol: Int, val endCol: Int, val severity: UiSeverity, val unused: Boolean)

private fun mapDiagnosticsToLines(diagnostics: List<UiDiagnostic>, doc: EditorDocument): Map<Int, List<DiagSeg>> {
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

private fun DrawScope.drawEditor(
    session: EditorSession,
    metrics: EditorMetrics,
    gutterWidth: Float,
    vOff: Float,
    hOff: Float,
    layoutFor: (Int) -> TextLayoutResult,
    compositeLayoutFor: (Int) -> TextLayoutResult,
    rawToVisual: (Int, Int) -> Int,
    foldModel: dev.ide.ui.editor.folding.FoldModel,
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
    // Viewport bounds are visual ROWS (folds compress them); map back to the doc lines they show. The
    // firstVisible..lastVisible doc range still covers every visible row in the viewport (hidden lines inside
    // it are skipped per-loop), so the existing line-keyed loops keep working with one `isHidden` guard each.
    val topRow = floor((vOff - metrics.padTop) / lineH).toInt().coerceAtLeast(0)
    val botRow = ((vOff + size.height - metrics.padTop) / lineH).toInt().coerceAtMost((foldModel.visualLineCount - 1).coerceAtLeast(0))
    val firstVisible = foldModel.docLineForVisual(topRow)
    val lastVisible = foldModel.docLineForVisual(botRow)
    // The document lines actually ON SCREEN (one per visual row) — iterated by the per-line draw loops instead
    // of `firstVisible..lastVisible`, which would walk every hidden line of a large collapsed region each frame.
    // `botRow` is clamped to the current line count while `topRow` follows the raw scroll offset, so a
    // document that shrank under a stale scroll position can leave botRow < topRow for a frame. Guard the
    // array size (a negative size threw NegativeArraySizeException) and treat it as nothing visible.
    val visibleLines = when {
        botRow < topRow -> emptyList()
        !foldModel.hasFolds -> (firstVisible..lastVisible).toList()
        else -> IntArray(botRow - topRow + 1) { foldModel.docLineForVisual(topRow + it) }.toList()
    }
    val textLeft = gutterWidth + metrics.padLeft - hOff
    fun lineTop(line: Int) = metrics.padTop + foldModel.visualForDocLine(line) * lineH - vOff
    fun xOf(line: Int, offset: Int): Float =
        textLeft + layoutFor(line).getHorizontalPosition(rawToVisual(line, offset - doc.lineStart(line)), usePrimaryDirection = true)

    val caretLine = doc.lineForOffset(sel.end)

    // current-line band across the full width (incl. gutter; gutter bg repaints its slice below)
    if (sel.collapsed) {
        drawRect(colors.currentLine, Offset(0f, lineTop(caretLine)), Size(size.width, lineH))
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
                    val x0 = if (line == sLine) xOf(line, m.start) else textLeft
                    val x1 = if (line == eLine) xOf(line, m.end) else textLeft + layoutFor(line).size.width
                    if (x1 > x0) drawRect(color, Offset(x0, lineTop(line)), Size(x1 - x0, lineH))
                }
            }
        }

        // selection background
        if (!sel.collapsed) {
            val sLine = doc.lineForOffset(sel.min)
            val eLine = doc.lineForOffset(sel.max)
            for (line in max(sLine, firstVisible)..min(eLine, lastVisible)) {
                if (foldModel.isHidden(line)) continue
                val x0 = if (line == sLine) xOf(line, sel.min) else textLeft
                val x1 = if (line == eLine) xOf(line, sel.max)
                else textLeft + layoutFor(line).size.width + metrics.charWidth * 0.6f // mark the line break
                if (x1 > x0) drawRect(colors.selection, Offset(x0, lineTop(line)), Size(x1 - x0, lineH))
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
                var level = unit
                while (level < cols) {
                    val x = textLeft + level * metrics.charWidth
                    if (x >= gutterWidth) {
                        drawLine(colors.indentGuide, Offset(x, lineTop(line)), Offset(x, lineTop(line) + lineH), strokeWidth = 1f)
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
                val x0 = if (line == cs) xOf(line, comp.min) else textLeft
                val x1 = if (line == ce) xOf(line, comp.max) else textLeft + layoutFor(line).size.width
                if (x1 > x0) {
                    val y = lineTop(line) + lineH - 2f
                    drawLine(colors.composing, Offset(x0, y), Offset(x1, y), strokeWidth = 1.5f)
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
                val x0 = textLeft + layout.getHorizontalPosition(rawToVisual(line, c0), usePrimaryDirection = true)
                val x1 = textLeft + layout.getHorizontalPosition(rawToVisual(line, c1), usePrimaryDirection = true)
                wavyUnderline(color, x0, x1, lineTop(line) + lineH - 2f)
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
                    topLeft = Offset(x0, lineTop(line)),
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
        drawRect(colors.currentLine, Offset(0f, lineTop(caretLine)), Size(gutterWidth, lineH))
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
            drawCircle(handleColor, r, Offset(x, lineTop(line) + lineH + r * 0.8f))
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

// ---- chrome ----

/** The inline diagnostic chip: a pill at the right of a diagnostic line — severity-tinted fill, icon,
 *  message. Colour/icon follow [severity]; an [unused] warning is muted rather than alarming. */
@Composable
private fun DiagnosticChip(severity: UiSeverity, unused: Boolean, message: String, onClick: () -> Unit, modifier: Modifier) {
    val color = when (severity) {
        UiSeverity.Error -> Ca.colors.error
        UiSeverity.Warning -> if (unused) Ca.colors.textTertiary else Ca.colors.warning
        UiSeverity.Info -> Ca.colors.info
        UiSeverity.Hint -> Ca.colors.textTertiary
    }
    val icon = when (severity) {
        UiSeverity.Error -> CaIcons.error
        UiSeverity.Warning -> CaIcons.warning
        UiSeverity.Info, UiSeverity.Hint -> CaIcons.info
    }
    Row(
        modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(
            message,
            color = color,
            fontSize = 11.5f.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectionToolbar(
    hasSelection: Boolean,
    hasActions: Boolean,
    onActions: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        Modifier
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasSelection) {
            ToolbarAction("Copy", onCopy)
            ToolbarAction("Cut", onCut)
        }
        ToolbarAction("Paste", onPaste)
        ToolbarAction("Select all", onSelectAll)
        // Quick-fixes / intentions for the caret position, when any exist.
        if (hasActions) {
            Box(
                Modifier
                    .clickable(onClick = onActions)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.lightbulb, "Quick actions", Modifier.size(16.dp), tint = Ca.colors.warning)
            }
        }
    }
}

@Composable
private fun ToolbarAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Ca.colors.textPrimary,
        style = Ca.type.caption,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

// ---- popup positioning ----

/** Doc-strip + chrome height to subtract from the room-below before capping the scrollable list. */
private val DocStripReserve = 44.dp

/** Floor for the list so it stays usable (≈1.5 rows) even when the caret is near the pane's bottom. */
private val MinListHeight = 64.dp

/**
 * Positions the completion popup just below the caret line and clamps it horizontally so it never
 * overflows the window. [anchorX]/[lineBottom] are in the editor pane's coordinate space.
 */
private class CompletionPopupPositionProvider(
    private val anchorX: Int,
    private val lineBottom: Int,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.left + anchorX).coerceIn(marginPx, maxX)
        val y = anchorBounds.top + lineBottom + gapPx
        return IntOffset(x, y)
    }
}

/** Positions the selection toolbar centered above an anchor point in the pane's coordinate space. */
private class AboveAnchorPositionProvider(
    private val anchorX: Int,
    private val anchorTop: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + anchorX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top + anchorTop - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

// ---- helpers ----

/**
 * A cheap, bounded backward scan: is the caret inside an open `(...)` call argument list? Used only as a gate
 * so signature help hits the backend just when the caret is plausibly in a call (the backend does the real,
 * literal-aware resolution). It bails at a statement boundary (`;`/`{`) or an opening `[`/`{` at depth 0 so an
 * array index / lambda body doesn't read as a call. False positives only cost one backend call that returns null.
 */
private fun caretInsideCall(chars: CharSequence, caret: Int): Boolean {
    var depth = 0
    var i = (caret - 1).coerceAtMost(chars.length - 1)
    var guard = 0
    while (i >= 0 && guard < 4000) {
        when (chars[i]) {
            ')', ']', '}' -> depth++
            '(' -> { if (depth == 0) return true; depth-- }
            '[' -> { if (depth == 0) return false; depth-- }
            '{', ';' -> if (depth == 0) return false
        }
        i--; guard++
    }
    return false
}

private fun paletteFor(syntax: SyntaxColors): Array<SpanStyle?> {
    val palette = arrayOfNulls<SpanStyle>(TokenType.entries.size)
    palette[TokenType.KEYWORD.ordinal] = SpanStyle(color = syntax.keyword)
    palette[TokenType.STRING.ordinal] = SpanStyle(color = syntax.string)
    palette[TokenType.COMMENT.ordinal] = SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic)
    palette[TokenType.NUMBER.ordinal] = SpanStyle(color = syntax.number)
    palette[TokenType.ANNOTATION.ordinal] = SpanStyle(color = syntax.annotation)
    palette[TokenType.FUNC.ordinal] = SpanStyle(color = syntax.func)
    palette[TokenType.TYPE.ordinal] = SpanStyle(color = syntax.type)
    palette[TokenType.PUNCT.ordinal] = SpanStyle(color = syntax.punctuation)
    palette[TokenType.PROPERTY.ordinal] = SpanStyle(color = syntax.property)
    return palette
}

/** Editor zoom limits (× the theme code size) and the clamp the pinch/keyboard zoom both go through. */
private const val MIN_FONT_SCALE = 0.6f
private const val MAX_FONT_SCALE = 2.6f
private fun clampFontScale(s: Float): Float = s.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

private fun codePointToString(cp: Int): String = when {
    cp < 0x10000 -> cp.toChar().toString()
    else -> {
        val v = cp - 0x10000
        charArrayOf(((v ushr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()).concatToString()
    }
}
