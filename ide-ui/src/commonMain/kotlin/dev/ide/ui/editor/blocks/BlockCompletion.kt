package dev.ide.ui.editor.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.CompletionList
import dev.ide.ui.editor.CompletionSession
import dev.ide.ui.editor.Ctx
import dev.ide.ui.editor.coversCaret
import dev.ide.ui.editor.isIdentifierChar
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The block canvas's inline editor — the tap-to-type socket/token field, now with the text editor's
 * completion inside it. It mirrors CodeEditor's semantics exactly (a [CompletionSession] cache filtered
 * locally per keystroke, a ~110ms debounced backend refresh that swaps fresh items in underneath, ↑↓ to
 * move, Tab/Enter to accept, Esc to dismiss-then-cancel) but maps everything through the edited span:
 * the live document is [Ctx.source] with this span swapped for the input, [docStart] translating input
 * offsets to document offsets. [expectedValueKind] re-ranks items toward the socket's type
 * ([rankForSocket]); an accepted item's auto-import edits are held and handed to [onCommit] so the host
 * applies them atomically with the block edit. `docStart < 0` disables completion (a plain field).
 */
@Composable
internal fun InlineInput(
    initial: String,
    docStart: Int,
    expectedValueKind: String?,
    ctx: Ctx,
    onCommit: (text: String, extraEdits: List<UiTextEdit>) -> Unit,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var done by remember { mutableStateOf(false) }
    // Compose fires onFocusChanged once with isFocused=false when the field first enters the focus system,
    // BEFORE requestFocus() lands. Committing on that initial blur tore the editor down on every tap (the
    // "flicker"). Only commit on blur once the field has actually held focus.
    var hasFocused by remember { mutableStateOf(false) }
    // The completion cache + view state — the popup stays open and filters locally while the debounced
    // backend refresh runs (see CompletionSession; this is CodeEditor's no-flicker scheme verbatim).
    var session by remember { mutableStateOf<CompletionSession?>(null) }
    var selected by remember { mutableStateOf(0) }
    var dismissed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Auto-import edits from accepted items, held until the user commits the field (they only become
    // real once the block edit lands; offsets above docStart are identical in projected and live text).
    var held by remember { mutableStateOf<List<UiTextEdit>>(emptyList()) }
    var fieldHeight by remember { mutableIntStateOf(0) }

    val canComplete = docStart >= 0
    val spanEnd = docStart + initial.length

    fun commit() {
        if (done) return
        done = true; job?.cancel()
        // Nothing typed and no held auto-import ⇒ a plain tap-in/tap-out: just close the editor instead of
        // round-tripping a no-op block edit (which would churn a re-projection).
        if (tfv.text == initial && held.isEmpty()) ctx.startEdit(null) else onCommit(tfv.text, held)
    }
    fun cancel() { if (!done) { done = true; job?.cancel(); ctx.startEdit(null) } }

    // ---- derived completion view, in DOCUMENT coords (the live text = source with the span swapped) ----
    val caretInInput = tfv.selection.start.coerceIn(0, tfv.text.length)
    val liveText = if (canComplete) ctx.source.replaceRange(docStart, spanEnd, tfv.text) else tfv.text
    val docOffset = docStart.coerceAtLeast(0) + caretInInput
    val liveSession = session?.takeIf { canComplete && it.coversCaret(liveText, docOffset) }
    val activePrefix = liveSession?.let { liveText.substring(it.tokenStart, docOffset) } ?: ""
    val displayed = rankForSocket(liveSession?.filtered(activePrefix) ?: emptyList(), expectedValueKind)
    val showPopup = !dismissed && displayed.isNotEmpty()
    val safeSelected = selected.coerceIn(0, (displayed.size - 1).coerceAtLeast(0))

    // Debounced (or immediate, for Ctrl-Space) backend query against the live text; stale items keep
    // showing while it runs, the result just replaces the cache underneath.
    fun refresh(v: TextFieldValue, immediate: Boolean = false) {
        if (!canComplete) return
        job?.cancel()
        job = ctx.scope.launch {
            if (!immediate) delay(110.milliseconds)
            val live = ctx.source.replaceRange(docStart, spanEnd, v.text)
            val caret = docStart + v.selection.start.coerceIn(0, v.text.length)
            val res = runCatching { ctx.backend.complete(ctx.path, live, caret) }.getOrNull() ?: return@launch
            val sameToken = res.replaceStart == session?.tokenStart
            session = CompletionSession.from(res)
            if (!sameToken) selected = 0
            dismissed = res.items.isEmpty()
        }
    }

    // Accept [item] into the INPUT: the token start comes back in document coords, so shift it by
    // docStart; replace [tokenStartInInput, caretInInput] with the insert text and place the caret per
    // the item. Doc-level additional edits that sit fully above the span (the auto-import) are held.
    fun accept(item: UiCompletionItem) {
        val s = liveSession ?: return
        val tokenStartInInput = (s.tokenStart - docStart).coerceAtLeast(0).coerceAtMost(tfv.text.length)
        // Replace the whole identifier token (extend past the caret) so completing mid-word drops the suffix.
        var end = caretInInput.coerceIn(tokenStartInInput, tfv.text.length)
        while (end < tfv.text.length && isIdentifierChar(tfv.text[end])) end++
        // Already-complete word ⇒ the replace is a no-op; add a space to acknowledge it and advance.
        val noOp = item.insertText == tfv.text.substring(tokenStartInInput, end) &&
            item.additionalEdits.isEmpty() && item.caret == null
        val nextIsSpace = end < tfv.text.length && tfv.text[end].isWhitespace()
        val insert = if (noOp && !nextIsSpace) item.insertText + " " else item.insertText
        val sb = StringBuilder(tfv.text).replace(tokenStartInInput, end, insert)
        held = held + item.additionalEdits.filter { it.end <= docStart }
        val within = (item.caret?.offset ?: insert.length).coerceIn(0, insert.length)
        val caret = (tokenStartInInput + within).coerceIn(0, sb.length)
        val selLen = item.caret?.selectionLength ?: 0
        val sel = if (selLen > 0) TextRange(caret, (caret + selLen).coerceAtMost(sb.length)) else TextRange(caret)
        tfv = TextFieldValue(sb.toString(), sel)
        dismissed = true
        job?.cancel()
    }

    fun handleChange(v: TextFieldValue) {
        val textChanged = v.text != tfv.text
        tfv = v
        if (!textChanged) return // pure caret move: showPopup re-derives, hiding it if the caret left the token
        selected = 0
        val caret = v.selection.start
        val before = if (caret in 1..v.text.length) v.text[caret - 1] else null
        when {
            before == '.' || (before != null && isIdentifierChar(before)) -> { dismissed = false; refresh(v) }
            else -> { dismissed = true; job?.cancel() } // operator / whitespace ⇒ end the session
        }
    }

    Box {
        BasicTextField(
            value = tfv, onValueChange = ::handleChange,
            modifier = Modifier.widthIn(min = 40.dp).clip(RoundedCornerShape(BlockMetrics.socketRadius))
                .background(Ca.colors.block.socket, RoundedCornerShape(BlockMetrics.socketRadius))
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .onGloballyPositioned { fieldHeight = it.size.height }
                .focusRequester(focus).onFocusChanged {
                    if (it.isFocused) hasFocused = true else if (hasFocused) commit()
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // explicit invoke (Ctrl/Cmd-Space) forces an immediate query regardless of state
                    if ((ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.Spacebar) {
                        dismissed = false; refresh(tfv, immediate = true); return@onPreviewKeyEvent true
                    }
                    if (showPopup) when (ev.key) {
                        Key.Escape -> { dismissed = true; job?.cancel(); return@onPreviewKeyEvent true }
                        Key.DirectionDown -> { selected = (safeSelected + 1).coerceAtMost((displayed.size - 1).coerceAtLeast(0)); return@onPreviewKeyEvent true }
                        Key.DirectionUp -> { selected = (safeSelected - 1).coerceAtLeast(0); return@onPreviewKeyEvent true }
                        Key.Tab, Key.Enter -> { displayed.getOrNull(safeSelected)?.let(::accept); return@onPreviewKeyEvent true }
                        else -> {}
                    } else when (ev.key) {
                        Key.Escape -> { cancel(); return@onPreviewKeyEvent true } // popup already gone ⇒ cancel editing
                        Key.Enter -> { commit(); return@onPreviewKeyEvent true }
                        else -> {}
                    }
                    false
                },
            singleLine = true, textStyle = Ca.type.code.copy(color = Ca.colors.block.socketText),
            cursorBrush = SolidColor(Ca.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
        )
        // Simple anchoring: the popup hangs just under the inline field (no caret/window math — the
        // field is a one-line span, so "below the field" and "below the caret" are the same thing).
        if (showPopup) {
            val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(
                offset = IntOffset(0, fieldHeight + gapPx),
                onDismissRequest = { dismissed = true; job?.cancel() },
            ) {
                CompletionList(
                    items = displayed,
                    selectedIndex = safeSelected,
                    prefix = activePrefix,
                    width = 300.dp,
                    maxListHeight = 240.dp,
                    onPick = { item -> selected = displayed.indexOf(item).coerceAtLeast(0); accept(item) },
                    onHover = { selected = it },
                )
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
