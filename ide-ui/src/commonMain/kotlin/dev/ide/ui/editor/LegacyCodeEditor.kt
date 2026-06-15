package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The previous `BasicTextField`-based editor, kept compiling as a fallback to the canvas editor
 * ([CodeEditor]); switching back is a one-line change in EditorScreen. Known to lag on phones: every
 * keystroke re-highlights and re-lays-out the whole document and recomposes a non-virtualized gutter.
 */
@Composable
internal fun LegacyCodeEditor(
    path: String,
    fileName: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    backend: IdeBackend,
    diagnostics: List<UiDiagnostic>,
    onSave: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Ca.colors
    val syntax = colors.syntax
    val language = remember(fileName) { languageFor(fileName) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var layout by remember(path) { mutableStateOf<TextLayoutResult?>(null) }
    // The live completion cache (see CompletionSession): the popup stays open and is filtered locally as
    // the user types; a debounced backend refresh swaps fresh items in underneath — no close/reopen churn.
    var session by remember(path) { mutableStateOf<CompletionSession?>(null) }
    var selected by remember(path) { mutableStateOf(0) }
    // Set when the user dismisses (Esc) or types a non-identifier; keeps the popup shut until re-triggered.
    var dismissed by remember(path) { mutableStateOf(false) }
    var job by remember(path) { mutableStateOf<Job?>(null) }
    // Window-space geometry used to size the popup so it sits below the caret yet stays inside the editor
    // pane (which, via the root safeDrawing inset, already ends above the keyboard).
    var fieldTopInWindow by remember(path) { mutableStateOf(0f) }
    var canvasBottomInWindow by remember(path) { mutableStateOf(0f) }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val transformation = remember(language, syntax) {
        VisualTransformation { input ->
            TransformedText(highlight(input.text, language, syntax), OffsetMapping.Identity)
        }
    }

    // ---- derived completion view (recomputed every recomposition from the live buffer) ----
    val caretOffset = value.selection.start.coerceIn(0, value.text.length)
    val liveSession = session?.takeIf { it.coversCaret(value.text, caretOffset) }
    val activePrefix = liveSession?.let { value.text.substring(it.tokenStart, caretOffset) } ?: ""
    val displayed = liveSession?.filtered(activePrefix) ?: emptyList()
    val showPopup = !dismissed && displayed.isNotEmpty()
    val safeSelected = selected.coerceIn(0, (displayed.size - 1).coerceAtLeast(0))

    // Debounced (or immediate, for explicit invoke) backend query that refreshes the cached session in
    // place. Stale items keep showing while this runs; results just replace the cache underneath.
    fun refresh(v: TextFieldValue, immediate: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            if (!immediate) delay(110.milliseconds)
            val res = runCatching { backend.complete(path, v.text, v.selection.start) }.getOrNull() ?: return@launch
            val sameToken = res.replaceStart == session?.tokenStart
            session = CompletionSession.from(res)
            if (!sameToken) selected = 0
            dismissed = res.items.isEmpty() // nothing to offer ⇒ stay closed until the next trigger
        }
    }

    fun accept() {
        val s = liveSession ?: return
        val item = displayed.getOrNull(safeSelected) ?: return
        val len = value.text.length
        val mainStart = s.tokenStart.coerceIn(0, len)
        val mainEnd = caretOffset.coerceIn(mainStart, len) // replace the token typed so far: [start, caret]

        // main insertion + any additional edits (e.g. an auto-import); apply highest offset first so
        // earlier offsets stay valid. Edits placed above the caret (the import) shift the caret down.
        data class Ed(val start: Int, val end: Int, val text: String)
        val edits = ArrayList<Ed>()
        edits.add(Ed(mainStart, mainEnd, item.insertText))
        for (e in item.additionalEdits) {
            val st = e.start.coerceIn(0, len); edits.add(Ed(st, e.end.coerceIn(st, len), e.newText))
        }
        val sb = StringBuilder(value.text)
        for (e in edits.sortedByDescending { it.start }) sb.replace(e.start, e.end, e.text)

        // Where the caret lands inside the just-inserted text — the item decides (e.g. between a method's
        // parentheses); default is the end of the insertion. Additional edits at/above the insertion (an
        // auto-import on a line above) shift it down by their net length change.
        val within = (item.caret?.offset ?: item.insertText.length).coerceIn(0, item.insertText.length)
        var caret = mainStart + within
        for (e in item.additionalEdits) {
            val st = e.start.coerceIn(0, len)
            if (st <= mainStart) caret += e.newText.length - (e.end.coerceIn(st, len) - st)
        }
        caret = caret.coerceIn(0, sb.length)
        val selLen = item.caret?.selectionLength ?: 0
        val selection = if (selLen > 0) TextRange(caret, (caret + selLen).coerceAtMost(sb.length)) else TextRange(caret)
        onValueChange(TextFieldValue(sb.toString(), selection))
        dismissed = true
        job?.cancel()
    }

    fun handleChange(raw: TextFieldValue) {
        val v = applySmartEdit(value, raw, language)
        val textChanged = v.text != value.text
        onValueChange(v)
        if (!textChanged) { return } // pure caret move (skip-over): showPopup re-derives, hiding it if the caret left the token
        selected = 0
        val caret = v.selection.start
        val before = if (caret in 1..v.text.length) v.text[caret - 1] else null
        when {
            // `.` starts a fresh member context; an identifier char extends/begins a token. Either way this
            // (re)queries, but the cached session keeps the popup live in between (no flicker).
            before == '.' || (before != null && isIdentifierChar(before)) -> { dismissed = false; refresh(v) }
            else -> { dismissed = true; job?.cancel() } // operator / whitespace ⇒ end the session
        }
    }

    LaunchedEffect(path) { runCatching { focus.requestFocus() } }

    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val caretLine = remember(value.text, value.selection) {
        value.text.substring(0, value.selection.start.coerceIn(0, value.text.length)).count { it == '\n' } + 1
    }
    val errorLines = remember(diagnostics) {
        diagnostics.filter { it.severity == UiSeverity.Error }.map { it.line }.toSet()
    }
    val warningLines = remember(diagnostics) {
        diagnostics.filter { it.severity == UiSeverity.Warning }.map { it.line }.toSet()
    }
    val bracketPair = remember(value.text, value.selection) {
        matchingBracket(value.text, value.selection.start)
    }
    val gutterWidth = (lineCount.toString().length * 9 + 22).coerceAtLeast(44).dp

    Row(modifier.background(colors.editorBg)) {
        LegacyGutter(lineCount, caretLine, errorLines, warningLines, vScroll, gutterWidth)
        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                // the editor pane's bottom edge in window space — the popup is sized to stay above it
                .onGloballyPositioned { canvasBottomInWindow = it.positionInWindow().y + it.size.height },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(start = 8.dp, top = 6.dp, end = 24.dp, bottom = 200.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = ::handleChange,
                    textStyle = Ca.type.code.copy(color = syntax.default),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .focusRequester(focus)
                        .onGloballyPositioned { fieldTopInWindow = it.positionInWindow().y }
                        .drawBehind {
                            val l = layout ?: return@drawBehind
                            // current-line band
                            val cl = l.getLineForOffset(value.selection.start.coerceIn(0, value.text.length))
                            if (cl in 0 until l.lineCount) {
                                drawRect(
                                    color = colors.currentLine,
                                    topLeft = Offset(0f, l.getLineTop(cl)),
                                    size = androidx.compose.ui.geometry.Size(size.width, l.getLineBottom(cl) - l.getLineTop(cl)),
                                )
                            }
                            // matching bracket pair
                            bracketPair?.let { (open, close) ->
                                for (off in intArrayOf(open, close)) {
                                    if (off !in value.text.indices) continue
                                    val box = l.getBoundingBox(off)
                                    drawRect(
                                        color = colors.accent.copy(alpha = 0.45f),
                                        topLeft = Offset(box.left, box.top),
                                        size = Size(box.width, box.height),
                                        style = Stroke(width = 1f),
                                    )
                                }
                            }
                            // diagnostic underlines — wavy, colored by severity (unused = muted, not alarming)
                            for (d in diagnostics) {
                                val color = when (d.severity) {
                                    UiSeverity.Error -> colors.error
                                    UiSeverity.Warning -> if (d.unused) colors.textTertiary else colors.warning
                                    UiSeverity.Info -> colors.info
                                    UiSeverity.Hint -> colors.textTertiary
                                }
                                val s = d.startOffset.coerceIn(0, value.text.length)
                                val e = d.endOffset.coerceIn(s, value.text.length)
                                if (e <= s) continue
                                val startLine = l.getLineForOffset(s)
                                val endLine = l.getLineForOffset(e)
                                for (ln in startLine..endLine) {
                                    val segS = if (ln == startLine) s else l.getLineStart(ln)
                                    val segE = if (ln == endLine) e else l.getLineEnd(ln, visibleEnd = true)
                                    if (segE <= segS) continue
                                    val x1 = l.getHorizontalPosition(segS, true)
                                    val x2 = l.getHorizontalPosition(segE, true)
                                    legacyWavyUnderline(color, x1, x2, l.getLineBottom(ln) - 2f)
                                }
                            }
                        }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            // save (Ctrl/Cmd-S) — handled before the popup gate so it works while completing
                            if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.S) {
                                onSave(); return@onPreviewKeyEvent true
                            }
                            // explicit invoke (Ctrl/Cmd-Space) forces an immediate query regardless of state
                            if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Spacebar) {
                                dismissed = false; refresh(value, immediate = true); return@onPreviewKeyEvent true
                            }
                            if (!showPopup) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.Escape -> { dismissed = true; job?.cancel(); true }
                                Key.DirectionDown -> { selected = (safeSelected + 1).coerceAtMost((displayed.size - 1).coerceAtLeast(0)); true }
                                Key.DirectionUp -> { selected = (safeSelected - 1).coerceAtLeast(0); true }
                                Key.Tab, Key.Enter -> { accept(); true }
                                else -> false
                            }
                        },
                )

                // inline error chips — one per error line, floated to the right of the line's text
                layout?.let { lay ->
                    val chipLines = HashSet<Int>()
                    for (d in diagnostics) {
//                        if (d.severity != UiSeverity.Error) continue
                        val off = d.startOffset.coerceIn(0, value.text.length)
                        val ln = lay.getLineForOffset(off)
                        if (!chipLines.add(ln)) continue
                        val cx = (lay.getLineRight(ln) + 24f).roundToInt()
                        val cy = lay.getLineTop(ln).roundToInt()
                        LegacyErrorChip(d.message, Modifier.offset { IntOffset(cx, cy) })
                    }
                }

                val s = liveSession
                val l = layout
                // Anchor to the token start (not the moving caret) so the popup stays put while typing.
                if (showPopup && s != null && l != null) {
                    val anchor = s.tokenStart.coerceIn(0, value.text.length)
                    val anchorLine = l.getLineForOffset(anchor)
                    val anchorX = l.getHorizontalPosition(anchor, true).roundToInt()
                    val lineBottomPx = l.getLineBottom(anchorLine)

                    val density = LocalDensity.current
                    val gapPx = with(density) { 6.dp.roundToPx() }
                    val marginPx = with(density) { 8.dp.roundToPx() }
                    val positionProvider = remember(anchorX, lineBottomPx, gapPx, marginPx) {
                        LegacyCompletionPopupPositionProvider(anchorX, lineBottomPx.roundToInt(), gapPx, marginPx)
                    }

                    // Vertical room between the caret line and the editor pane's bottom (which already sits
                    // above the keyboard). The list is capped to fit it, so the popup stays *below* the
                    // caret and shrinks rather than flipping up or sliding under the IME.
                    val caretBottomY = fieldTopInWindow + lineBottomPx
                    val roomBelowDp = with(density) { (canvasBottomInWindow - caretBottomY - gapPx - marginPx).toDp() }

                    Popup(
                        popupPositionProvider = positionProvider,
                        onDismissRequest = { dismissed = true; job?.cancel() },
                    ) {
                        // Compact — never as wide as the screen (≤300dp on phones); the list scales to the
                        // room below the caret so it never reaches the keyboard.
                        BoxWithConstraints {
                            val compact = maxWidth < 600.dp
                            val popupWidth = if (compact) (maxWidth * 0.8f).coerceIn(220.dp, 300.dp) else 420.dp
                            val listCap = if (compact) 240.dp else 296.dp
                            val listMax = (roomBelowDp - LegacyDocStripReserve).coerceIn(LegacyMinListHeight, listCap)
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
            }
        }
    }
}

@Composable
private fun LegacyGutter(
    lineCount: Int,
    currentLine: Int,
    errorLines: Set<Int>,
    warningLines: Set<Int>,
    vScroll: androidx.compose.foundation.ScrollState,
    width: androidx.compose.ui.unit.Dp,
) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Ca.colors.editorBg)
            .verticalScroll(vScroll, enabled = false)
            .padding(top = 6.dp),
    ) {
        for (ln in 1..lineCount) {
            Box(Modifier.height(22.dp).fillMaxWidth().padding(end = 10.dp)) {
                // a filled glyph at the gutter's left edge marks an error / warning line
                val mark = when {
                    ln in errorLines -> Ca.colors.error
                    ln in warningLines -> Ca.colors.warning
                    else -> null
                }
                if (mark != null) {
                    Box(Modifier.align(Alignment.CenterStart).padding(start = 5.dp).size(5.dp).background(mark, CircleShape))
                }
                Text(
                    text = ln.toString(),
                    color = when {
                        ln in errorLines -> Ca.colors.error
                        ln in warningLines -> Ca.colors.warning
                        ln == currentLine -> Ca.colors.textSecondary
                        else -> Ca.colors.gutterText
                    },
                    style = Ca.type.codeSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** The inline error chip: a pill at the right of an error line — error-tinted fill, icon, message. */
@Composable
private fun LegacyErrorChip(message: String, modifier: Modifier) {
    Row(
        modifier
            .background(Ca.colors.error.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(CaIcons.error, null, Modifier.size(13.dp), tint = Ca.colors.error)
        Text(
            message,
            color = Ca.colors.error,
            fontSize = 11.5f.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Doc-strip + chrome height to subtract from the room-below before capping the scrollable list. */
private val LegacyDocStripReserve = 44.dp
/** Floor for the list so it stays usable (≈1.5 rows) even when the caret is near the pane's bottom. */
private val LegacyMinListHeight = 64.dp

/**
 * Positions the completion popup just below the caret line and clamps it horizontally so it never
 * overflows the window. [anchorX] is the token-start x and [lineBottom] the caret line's bottom, in the
 * anchor (editor content) coordinate space. It deliberately does *not* flip the popup above the line —
 * the caller instead sizes the list to the room below, so the popup always reads as anchored to the caret.
 */
private class LegacyCompletionPopupPositionProvider(
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

/** A squiggly underline from [x1] to [x2] at baseline [y] (a tight triangle wave reads as wavy). */
private fun DrawScope.legacyWavyUnderline(color: Color, x1: Float, x2: Float, y: Float) {
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
