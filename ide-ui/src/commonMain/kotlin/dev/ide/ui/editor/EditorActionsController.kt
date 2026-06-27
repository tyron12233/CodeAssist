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
    /** The diagnostic covering the caret line (drives the proactive gutter lightbulb), or null. */
    var caretDiagnostic by mutableStateOf<UiDiagnostic?>(null)
        private set
    // The range [available] was resolved at — the diagnostic's range when on a diagnostic line, else the
    // caret selection. Actions are positional (id = index into the resolution at THIS range), so applying must
    // reuse the same range.
    private var availStart = 0
    private var availEnd = 0
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
        if (!focused) { available = emptyList(); caretDiagnostic = null; return }
        val sel = session.selection
        // When the caret sits on a line a diagnostic covers, resolve (and later apply) the actions at the
        // diagnostic's range — so its quick-fixes are the offered actions and the lightbulb has something to
        // do. Otherwise resolve caret intentions (reachable via Alt-Enter; no proactive lightbulb).
        val diag = diagnosticCoveringLine(session.doc.lineForOffset(sel.min))
        val len = session.doc.length
        availStart = diag?.startOffset?.coerceIn(0, len) ?: sel.min
        availEnd = diag?.endOffset?.coerceIn(availStart, len) ?: sel.max
        val result = runCatching { backend.actionsAt(path, session.doc.text, availStart, availEnd) }.getOrNull().orEmpty()
        available = result
        caretDiagnostic = diag
        when {
            result.isEmpty() -> menuOpen = false
            menuSelected >= result.size -> menuSelected = 0
        }
    }

    /** The most-severe diagnostic whose line span includes [line], or null — gates the proactive lightbulb so
     *  it appears only where the caret has entered a diagnostic's line/range, signalling a fix is available. */
    fun diagnosticCoveringLine(line: Int): UiDiagnostic? {
        val doc = session.doc
        var best: UiDiagnostic? = null
        for (d in session.diagnostics) {
            val s = d.startOffset.coerceIn(0, doc.length)
            val e = d.endOffset.coerceIn(s, doc.length)
            if (line in doc.lineForOffset(s)..doc.lineForOffset(e)) {
                val cur = best
                if (cur == null || d.severity.ordinal < cur.severity.ordinal) best = d
            }
        }
        return best
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
        // Apply at the same range [available] was resolved at (the action id is an index into that resolution).
        runAction(act, availStart, availEnd)
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
