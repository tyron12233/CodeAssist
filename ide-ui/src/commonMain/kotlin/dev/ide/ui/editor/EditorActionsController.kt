package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.RangeEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Code-actions (the lightbulb: quick-fixes + caret intentions) and the diagnostic sheet, pulled out of
 * [CodeEditor]. It tracks which actions are available at the caret, the open menu + its selection, and the
 * diagnostic detail sheet with that diagnostic's fixes — and applies any chosen action by round-tripping the
 * backend's edits through the [EditorSession] (so the normal reparse/re-analyze follows).
 *
 * [dismissCompletion] is invoked when opening the menu/sheet so the completion popup doesn't overlap them.
 * Created per file via [rememberEditorActionsController].
 */
@Stable
internal class EditorActionsController(
    private val session: EditorSession,
    private val backend: IdeBackend,
    private val path: String,
    private val scope: CoroutineScope,
    private val dismissCompletion: () -> Unit,
) {
    var available by mutableStateOf<List<UiAction>>(emptyList())
        private set
    var menuOpen by mutableStateOf(false)
        private set
    var menuSelected by mutableIntStateOf(0)
        private set
    var sheet by mutableStateOf<UiDiagnostic?>(null)
        private set
    var sheetActions by mutableStateOf<List<UiAction>>(emptyList())
        private set

    /** Re-resolve the actions available at the current selection (debounced); driven from an effect. */
    suspend fun refreshAvailability(focused: Boolean) {
        delay(250.milliseconds)
        if (!focused) { available = emptyList(); return }
        val sel = session.selection
        val result = runCatching { backend.actionsAt(path, session.doc.text, sel.min, sel.max) }.getOrNull().orEmpty()
        available = result
        when {
            result.isEmpty() -> menuOpen = false
            menuSelected >= result.size -> menuSelected = 0
        }
    }

    fun openMenu() {
        dismissCompletion()
        menuSelected = 0
        menuOpen = true
    }

    fun closeMenu() { menuOpen = false }

    fun moveSelection(delta: Int) {
        menuSelected = (menuSelected + delta).coerceIn(0, (available.size - 1).coerceAtLeast(0))
    }

    fun applyAt(index: Int) {
        val act = available.getOrNull(index) ?: return
        menuOpen = false
        val sel = session.selection
        runAction(act, sel.min, sel.max)
    }

    /** The most-severe diagnostic whose start sits on [line], or null — drives gutter-glyph and chip taps. */
    fun diagnosticOnLine(line: Int): UiDiagnostic? {
        var best: UiDiagnostic? = null
        for (d in session.diagnostics) {
            if (session.doc.lineForOffset(d.startOffset.coerceIn(0, session.doc.length)) != line) continue
            val cur = best
            if (cur == null || d.severity.ordinal < cur.severity.ordinal) best = d
        }
        return best
    }

    /** Open the diagnostic sheet for [d] and fetch the quick-fixes registered for its range. */
    fun openSheet(d: UiDiagnostic) {
        dismissCompletion()
        menuOpen = false
        sheet = d
        sheetActions = emptyList()
        val text = session.doc.text
        scope.launch {
            sheetActions = runCatching { backend.actionsAt(path, text, d.startOffset, d.endOffset) }.getOrNull().orEmpty()
        }
    }

    fun closeSheet() { sheet = null }

    fun applySheetFix(index: Int) {
        val d = sheet ?: return
        val act = sheetActions.getOrNull(index) ?: return
        sheet = null
        runAction(act, d.startOffset, d.endOffset)
    }

    // Apply [act]: ask the backend for its edits over the buffer at the context range [ctxStart,ctxEnd), then
    // splice them in (the editor round-trip — reparse + re-analyze follow the normal text path). The caret is
    // kept on its logical spot by shifting it by the net delta of edits that land at/before it.
    private fun runAction(act: UiAction, ctxStart: Int, ctxEnd: Int) {
        val text = session.doc.text
        scope.launch {
            val raw = runCatching { backend.applyAction(path, text, ctxStart, ctxEnd, act.id) }.getOrNull().orEmpty()
            if (raw.isEmpty()) return@launch
            val len = session.doc.length
            val edits = raw.map { e ->
                val st = e.start.coerceIn(0, len)
                RangeEdit(st, e.end.coerceIn(st, len), e.newText, st + e.newText.length)
            }
            var caret = ctxStart
            for (e in edits) if (e.start <= caret) caret += e.text.length - (e.end - e.start)
            session.applyEdits(edits, TextRange(caret.coerceAtLeast(0)))
        }
    }
}

@Composable
internal fun rememberEditorActionsController(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    dismissCompletion: () -> Unit,
): EditorActionsController {
    val scope = rememberCoroutineScope()
    return remember(path) { EditorActionsController(session, backend, path, scope, dismissCompletion) }
}
