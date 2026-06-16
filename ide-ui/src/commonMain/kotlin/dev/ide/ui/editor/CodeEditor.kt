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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAction
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
) {
    val colors = Ca.colors
    val syntax = colors.syntax
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current

    // ---- the edit engine: the per-tab source of truth, owned by the host and shared with the block
    // editor. This composable only renders and drives it; there is no TextFieldValue mirror to sync. ----
    val editorSession = session

    // ---- text metrics + per-line layout cache ----
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val typography = Ca.type
    val codeStyle = remember(syntax, typography) { typography.code.copy(color = syntax.default) }
    val gutterStyle = typography.codeSmall
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
        inlayHints = runCatching { backend.hintsAt(path, text, 0, text.length) }.getOrDefault(emptyList())
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

    // Per-number layout cache, keyed by the actual line number (not just its digit count) so the gutter
    // renders real numbers — caching by digit-count rendered "0"/"00"/… for every line.
    val gutterNumberCache = remember(measurer, gutterStyle) { HashMap<Int, TextLayoutResult>() }
    fun numberLayout(n: Int): TextLayoutResult =
        gutterNumberCache.getOrPut(n) {
            measurer.measure(AnnotatedString(n.toString()), style = gutterStyle, softWrap = false, maxLines = 1)
        }

    val gutterWidthPx = remember(editorSession.doc.lineCount, density) {
        with(density) {
            (editorSession.doc.lineCount.toString().length * 9 + 22).coerceAtLeast(44).dp.toPx()
        }
    }

    // ---- scrolling: plain offsets read in the draw phase (a fling never recomposes) ----
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val vOffset = remember(path) { mutableFloatStateOf(0f) }
    val hOffset = remember(path) { mutableFloatStateOf(0f) }
    fun contentHeight() = metrics.padTop + editorSession.doc.lineCount * metrics.lineHeight + metrics.padBottom
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

    // ---- geometry helpers (viewport coordinates ↔ document offsets) ----
    fun lineTop(line: Int) = metrics.padTop + line * metrics.lineHeight - vOffset.floatValue
    fun textLeft() = gutterWidthPx + metrics.padLeft - hOffset.floatValue
    fun layoutFor(line: Int) = renderCache.layoutFor(line, editorSession.doc, editorSession.styles)
    fun caretGeometry(offset: Int): Triple<Int, Float, Float> { // line, xInViewport, topInViewport
        val doc = editorSession.doc
        val line = doc.lineForOffset(offset)
        val x = layoutFor(line).getHorizontalPosition(renderCache.rawToVisual(line, offset - doc.lineStart(line)), usePrimaryDirection = true)
        return Triple(line, textLeft() + x, lineTop(line))
    }
    fun offsetAt(pos: Offset): Int {
        val doc = editorSession.doc
        val line = floor((pos.y + vOffset.floatValue - metrics.padTop) / metrics.lineHeight)
            .toInt().coerceIn(0, doc.lineCount - 1)
        val xInLine = pos.x - textLeft()
        val visualCol = layoutFor(line).getOffsetForPosition(Offset(xInLine.coerceAtLeast(0f), metrics.lineHeight / 2f))
        val col = renderCache.visualToRaw(line, visualCol)
        return doc.lineStart(line) + col.coerceAtMost(doc.lineLength(line))
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
    val caretTarget = run {
        val off = editorSession.selection.end
        val d = editorSession.doc
        val ln = d.lineForOffset(off)
        val x = gutterWidthPx + metrics.padLeft +
            layoutFor(ln).getHorizontalPosition(renderCache.rawToVisual(ln, off - d.lineStart(ln)), usePrimaryDirection = true)
        Offset(x, metrics.padTop + ln * metrics.lineHeight)
    }
    LaunchedEffect(caretTarget) {
        // Snap on the first placement (file open) and across off-screen jumps (go-to-symbol, PageUp/Down) —
        // a glide across the whole document reads as a glitch; glide only for moves within a viewport.
        val far = viewport.height > 0 && kotlin.math.abs(caretTarget.y - caretAnim.value.y) > viewport.height
        if (!caretAnimReady || far) {
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
    var handlesVisible by remember(path) { mutableStateOf(false) }

    // ---- completion (same session/cache/filter machinery as before) ----
    var completion by remember(path) { mutableStateOf<CompletionSession?>(null) }
    var selected by remember(path) { mutableIntStateOf(0) }
    var dismissed by remember(path) { mutableStateOf(false) }
    var job by remember(path) { mutableStateOf<Job?>(null) }
    // Active snippet/template expansion (tab-stop stepping), or null. Reset when the file changes.
    var snippet by remember(path) { mutableStateOf<SnippetSession?>(null) }
    var paneTopInWindow by remember(path) { mutableFloatStateOf(0f) }
    var paneBottomInWindow by remember(path) { mutableFloatStateOf(0f) }

    // ---- code actions (lightbulb): quick-fixes + caret intentions at the current selection ----
    var actions by remember(path) { mutableStateOf<List<UiAction>>(emptyList()) }
    var actionsOpen by remember(path) { mutableStateOf(false) }
    var actionSelected by remember(path) { mutableIntStateOf(0) }

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

    fun accept() {
        val s = liveCompletion ?: return
        val item = displayed.getOrNull(safeSelected) ?: return
        val chars = editorSession.doc.chars
        val len = chars.length
        val mainStart = s.tokenStart.coerceIn(0, len)
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
            editorSession.applyEdits(edits, TextRange(base))
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
        editorSession.applyEdits(edits, sel)
        dismissed = true
        job?.cancel()
    }

    // Apply the chosen code action: ask the backend for its edits over the current buffer + selection, then
    // splice them in (the editor round-trip — reparse + re-analyze follow the normal text path). The caret is
    // kept on its logical spot by shifting it by the net delta of edits that land at/before it.
    fun applyActionAt(index: Int) {
        val act = actions.getOrNull(index) ?: return
        actionsOpen = false
        val text = editorSession.doc.text
        val selAtCall = editorSession.selection
        scope.launch {
            val raw = runCatching { backend.applyAction(path, text, selAtCall.min, selAtCall.max, act.id) }.getOrNull().orEmpty()
            if (raw.isEmpty()) return@launch
            val len = editorSession.doc.length
            val edits = raw.map { e ->
                val st = e.start.coerceIn(0, len)
                RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length)
            }
            var caret = selAtCall.min
            for (e in edits) if (e.start <= caret) caret += e.text.length - (e.end - e.start)
            editorSession.applyEdits(edits, TextRange(caret.coerceAtLeast(0)))
        }
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

    // ---- bring the caret into view after every edit/caret move ----
    LaunchedEffect(editorSession.editCount, viewport) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        val (line, _, _) = caretGeometry(editorSession.selection.end)
        val top = metrics.padTop + line * metrics.lineHeight
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
                if (!shortcut && !ev.isAltPressed) { editorSession.commitText("    "); return true }
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
            }
            return false
        }

        when (ev.key) {
            Key.ShiftLeft, Key.ShiftRight,
            Key.CtrlLeft, Key.CtrlRight,
            Key.AltLeft, Key.AltRight,
            Key.MetaLeft, Key.MetaRight,
            Key.CapsLock, Key.NumLock, Key.ScrollLock -> return false
            else -> Unit
        }

        val cp = ev.utf16CodePoint
        if (cp >= 32 && cp != 127) {
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
                .editorTextInput(editorSession)
                .focusRequester(focus)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.S) {
                        onSave(); return@onPreviewKeyEvent true
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
                // selection-handle and mouse-selection drags claim the pointer before the scrollables
                .pointerInput(editorSession) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        lastInputWasTouch = down.type != PointerType.Mouse
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
                        when {
                            hit != null -> {
                                down.consume()
                                drag(down.id) { change ->
                                    val off = offsetAt(change.position)
                                    when (hit) {
                                        'a' -> editorSession.setSelectionRange(editorSession.selection.max, off)
                                        'b' -> editorSession.setSelectionRange(editorSession.selection.min, off)
                                        else -> editorSession.setCaret(off)
                                    }
                                    change.consume()
                                }
                            }
                            down.type == PointerType.Mouse -> {
                                focus.requestFocus()
                                val anchor = offsetAt(down.position)
                                editorSession.setCaret(anchor)
                                drag(down.id) { change ->
                                    editorSession.extendSelectionTo(offsetAt(change.position))
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                .scrollable(vScroll, Orientation.Vertical, reverseDirection = true)
                .scrollable(hScroll, Orientation.Horizontal, reverseDirection = true)
                .pointerInput(editorSession) {
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
                                editorSession.setCaret(offsetAt(pos))
                                if (lastInputWasTouch) {
                                    handlesVisible = true
                                    keyboard?.show()
                                }
                            }
                        },
                        onDoubleTap = { pos ->
                            focus.requestFocus()
                            editorSession.selectWordAt(offsetAt(pos))
                            if (lastInputWasTouch) handlesVisible = true
                        },
                        onLongPress = { pos ->
                            longPressed = true
                            focus.requestFocus()
                            editorSession.selectWordAt(offsetAt(pos))
                            if (lastInputWasTouch) {
                                handlesVisible = true
                                keyboard?.show()
                            }
                        },
                    )
                }
                .drawBehind {
                    drawEditor(
                        session = editorSession,
                        metrics = metrics,
                        gutterWidth = gutterWidthPx,
                        vOff = vOffset.floatValue,
                        hOff = hOffset.floatValue,
                        layoutFor = ::layoutFor,
                        rawToVisual = renderCache::rawToVisual,
                        numberLayout = ::numberLayout,
                        diagByLine = diagByLine,
                        bracketPair = bracketPair,
                        colors = EditorDrawColors(
                            background = colors.editorBg,
                            currentLine = colors.currentLine,
                            caret = colors.accent,
                            selection = colors.accent.copy(alpha = 0.30f),
                            gutterText = colors.gutterText,
                            gutterCurrent = colors.textSecondary,
                            error = colors.error,
                            warning = colors.warning,
                            info = colors.info,
                            muted = colors.textTertiary,
                            composing = colors.textSecondary,
                            indentGuide = colors.hairline,
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
            val chipPerLine = HashMap<Int, UiDiagnostic>()
            for (d in diagnostics) {
                if (d.severity != UiSeverity.Error && d.severity != UiSeverity.Warning) continue
                val off = d.startOffset.coerceIn(0, docLength)
                val ln = doc.lineForOffset(off)
                val cur = chipPerLine[ln]
                // lower ordinal = more severe (Error before Warning)
                if (cur == null || d.severity.ordinal < cur.severity.ordinal) chipPerLine[ln] = d
            }
            for ((ln, d) in chipPerLine) {
                val lineWidth = layoutFor(ln).size.width
                DiagnosticChip(
                    d.severity,
                    d.unused,
                    d.message,
                    Modifier.offset {
                        IntOffset(
                            (gutterWidthPx + metrics.padLeft + lineWidth + 24f - hOffset.floatValue).roundToInt(),
                            (metrics.padTop + ln * metrics.lineHeight - vOffset.floatValue).roundToInt(),
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

        // completion popup, anchored at the live token start in viewport coordinates
        val live = liveCompletion
        if (showPopup && live != null) {
            val anchor = live.tokenStart.coerceIn(0, docLength)
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
            ) {
                BoxWithConstraints {
                    val compact = maxWidth < 600.dp
                    val popupWidth = if (compact) (maxWidth * 0.8f).coerceIn(220.dp, 300.dp) else 420.dp
                    val listCap = if (compact) 240.dp else 296.dp
                    val listMax = (roomBelowDp - DocStripReserve).coerceIn(MinListHeight, listCap)
                    CompletionList(
                        items = displayed,
                        selectedIndex = safeSelected,
                        prefix = activePrefix,
                        width = popupWidth,
                        maxListHeight = listMax,
                        onPick = { item ->
                            selected = displayed.indexOf(item).coerceAtLeast(0)
                            accept()
                        },
                        onHover = { selected = it },
                    )
                }
            }
        }

        // lightbulb on the caret line whenever actions are available and no completion popup is showing
        if (actions.isNotEmpty() && !showPopup && isFocused) {
            val caretLn = doc.lineForOffset(caretOffset)
            val lineTopPx = metrics.padTop + caretLn * metrics.lineHeight - vOffset.floatValue
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
    }
}

/** What the rename prompt is editing: where the caret was, the symbol's old name + kind, and the typed name. */
private data class RenameUiState(val offset: Int, val oldName: String, val kind: String, val newName: String)

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
                textStyle = Ca.type.body.copy(color = Ca.colors.textPrimary, fontFamily = FontFamily.Monospace),
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
    val error: Color,
    val warning: Color,
    val info: Color,
    val muted: Color,
    val composing: Color,
    val indentGuide: Color,
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
    rawToVisual: (Int, Int) -> Int,
    numberLayout: (Int) -> TextLayoutResult,
    diagByLine: Map<Int, List<DiagSeg>>,
    bracketPair: Pair<Int, Int>?,
    colors: EditorDrawColors,
    caretVisible: Boolean,
    caretContent: Offset,
    handlesVisible: Boolean,
    handleColor: Color,
) {
    val doc = session.doc
    val sel = session.selection
    val lineH = metrics.lineHeight
    val firstVisible = floor((vOff - metrics.padTop) / lineH).toInt().coerceAtLeast(0)
    val lastVisible = (((vOff + size.height - metrics.padTop) / lineH).toInt()).coerceAtMost(doc.lineCount - 1)
    val textLeft = gutterWidth + metrics.padLeft - hOff
    fun lineTop(line: Int) = metrics.padTop + line * lineH - vOff
    fun xOf(line: Int, offset: Int): Float =
        textLeft + layoutFor(line).getHorizontalPosition(rawToVisual(line, offset - doc.lineStart(line)), usePrimaryDirection = true)

    val caretLine = doc.lineForOffset(sel.end)

    // current-line band across the full width (incl. gutter; gutter bg repaints its slice below)
    if (sel.collapsed) {
        drawRect(colors.currentLine, Offset(0f, lineTop(caretLine)), Size(size.width, lineH))
    }

    clipRect(left = gutterWidth, top = 0f, right = size.width, bottom = size.height) {
        // selection background
        if (!sel.collapsed) {
            val sLine = doc.lineForOffset(sel.min)
            val eLine = doc.lineForOffset(sel.max)
            for (line in max(sLine, firstVisible)..min(eLine, lastVisible)) {
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
            for (line in firstVisible..lastVisible) {
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

        // text — cached per-line layouts; only a cache miss (edited or newly-visible line) shapes text
        for (line in firstVisible..lastVisible) {
            if (doc.lineLength(line) == 0) continue
            drawText(layoutFor(line), topLeft = Offset(textLeft, lineTop(line)))
        }

        // IME composing underline
        session.composing?.let { comp ->
            val cs = doc.lineForOffset(comp.min)
            val ce = doc.lineForOffset(comp.max)
            for (line in max(cs, firstVisible)..min(ce, lastVisible)) {
                val x0 = if (line == cs) xOf(line, comp.min) else textLeft
                val x1 = if (line == ce) xOf(line, comp.max) else textLeft + layoutFor(line).size.width
                if (x1 > x0) {
                    val y = lineTop(line) + lineH - 2f
                    drawLine(colors.composing, Offset(x0, y), Offset(x1, y), strokeWidth = 1.5f)
                }
            }
        }

        // diagnostic squiggles
        for (line in firstVisible..lastVisible) {
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
    for (line in firstVisible..lastVisible) {
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
        val num = numberLayout(line + 1)
        drawText(
            num,
            color = numColor,
            topLeft = Offset(
                gutterWidth - 10.dp.toPx() - num.size.width,
                lineTop(line) + (lineH - num.size.height) / 2f,
            ),
        )
    }

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
private fun DiagnosticChip(severity: UiSeverity, unused: Boolean, message: String, modifier: Modifier) {
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
    ) {
        if (hasSelection) {
            ToolbarAction("Copy", onCopy)
            ToolbarAction("Cut", onCut)
        }
        ToolbarAction("Paste", onPaste)
        ToolbarAction("Select all", onSelectAll)
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

private fun codePointToString(cp: Int): String = when {
    cp < 0x10000 -> cp.toChar().toString()
    else -> {
        val v = cp - 0x10000
        charArrayOf(((v ushr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()).concatToString()
    }
}
