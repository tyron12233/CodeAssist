package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiMenuGroup
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.backend.UiActionContext
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
    /** Runs a plugin-tier editor action (see [UiAction.actionId]) through the host's action dispatcher. */
    private val onPluginAction: suspend (actionId: String, selStart: Int, selEnd: Int) -> Unit =
        { _, _, _ -> },
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

    /** The plugin action tree for the editor context menu (groups become submenus). Empty until resolved. */
    var editorMenu by mutableStateOf(UiMenuGroup())
        private set

    /** The most recent selection-expand request, for the editor to animate the growing highlight. The logical
     *  selection already jumped to the enclosing node; this only drives the visual tween. Null = nothing to
     *  animate; [SelectionExpand.token] bumps per request so an identical range still restarts the animation. */
    var selectionExpand by mutableStateOf<SelectionExpand?>(null)
        private set
    private var expandToken = 0

    /** Re-resolve the actions available at the current selection (debounced); driven from an effect. */
    suspend fun refreshAvailability(focused: Boolean) {
        delay(250.milliseconds)
        if (!focused) { available = emptyList(); caretDiagnostic = null; return }
        val sel = session.selection
        val diag = diagnosticCoveringLine(session.doc.lineForOffset(sel.min))
        caretDiagnostic = diag
        // Resolve actions PROACTIVELY only when the caret is on a diagnostic (the lightbulb case). Resolving
        // off-diagnostic "caret intentions" here ran on every caret move / on file open — and for Kotlin that
        // triggers a full-file diagnostics analysis (the unresolved-ref scan behind import fixes), which on a
        // deeply-nested Compose file is a cold multi-SECOND inference (profiled: `pass=actions` = 110s on open,
        // returning zero actions — it froze the editor and drove the GC storm). Off-diagnostic intentions are
        // now resolved ON DEMAND in [openMenu] (Alt-Enter), matching IntelliJ (no proactive lightbulb there).
        if (diag == null) { available = emptyList(); menuOpen = false; return }
        val len = session.doc.length
        availStart = diag.startOffset.coerceIn(0, len)
        availEnd = diag.endOffset.coerceIn(availStart, len)
        val result = runCatching { backend.editor.actionsAt(path, session.doc.text, availStart, availEnd) }.getOrNull().orEmpty()
        available = result
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
        // Off-diagnostic intentions are no longer pre-resolved (see [refreshAvailability]), so resolve them now,
        // on explicit request. When the caret IS on a diagnostic, [available] is already populated — reuse it.
        if (available.isEmpty() && caretDiagnostic == null) {
            val sel = session.selection
            val len = session.doc.length
            availStart = sel.min.coerceIn(0, len)
            availEnd = sel.max.coerceIn(availStart, len)
            scope.launch {
                available = runCatching {
                    backend.editor.actionsAt(path, session.doc.text, availStart, availEnd)
                }.getOrNull().orEmpty()
                if (available.isEmpty()) menuOpen = false
            }
        }
    }

    fun closeMenu() { menuOpen = false }

    /**
     * Resolve everything the editor context menu lists: the analysis quick-fixes and intentions at the
     * current selection, plus the plugin action tree for the `EDITOR` place.
     *
     * The context menu is opened deliberately, so unlike the proactive lightbulb it can afford to resolve
     * on demand. That is also the only way its fix and intention sections are populated off a diagnostic
     * line (see [refreshAvailability] for why nothing is resolved proactively there).
     */
    suspend fun resolveContextMenu() {
        val sel = session.selection
        val len = session.doc.length
        availStart = sel.min.coerceIn(0, len)
        availEnd = sel.max.coerceIn(availStart, len)
        val text = session.doc.text
        available = runCatching { backend.editor.actionsAt(path, text, availStart, availEnd) }
            .getOrNull().orEmpty()
        val caret = runCatching { backend.editor.caretContext(path, text, availStart) }.getOrNull()
        editorMenu = runCatching {
            backend.actions.menuFor(
                UiActionContext(
                    place = UiActionPlaces.EDITOR,
                    activeFilePath = path,
                    selectionStart = availStart,
                    selectionEnd = availEnd,
                    caret = caret,
                    documentText = text,
                ),
            )
        }.getOrNull() ?: UiMenuGroup()
    }

    /** Invoke a plugin action picked from the context menu, at the range the menu was resolved for. */
    fun invokeMenuAction(id: String) {
        scope.launch { runCatching { onPluginAction(id, availStart, availEnd) } }
    }

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
            // Analysis-tier entries only. The sheet answers "what fixes this problem?", so the plugin
            // actions that apply anywhere the caret is (they carry an `actionId`) are excluded; they stay
            // reachable from the Alt-Enter popup and the editor overflow menu.
            sheetActions = runCatching { backend.editor.actionsAt(path, text, d.startOffset, d.endOffset) }
                .getOrNull().orEmpty().filter { it.actionId == null }
        }
    }

    fun closeSheet() { sheet = null }

    /** Expand the selection to the smallest enclosing structural node — the "expand selection" gesture. Walks
     *  UP the backend's tolerant DOM one level (word → expression → statement → block → method → class …), so
     *  repeated invocations keep widening it. Triggered by double-clicking/-tapping an existing selection, and
     *  by a 4th-and-further consecutive click. A no-op when nothing larger encloses the range. */
    fun expandSelection() {
        val sel = session.selection
        expandSelection(sel.min, sel.max)
    }

    /** Expand from an explicit base range `[fromStart, fromEnd)` rather than the live selection — used by the
     *  mouse path, where the first click of a double-click has already collapsed the on-screen selection. */
    fun expandSelection(fromStart: Int, fromEnd: Int) {
        val text = session.doc.text
        scope.launch {
            val range = runCatching {
                backend.editor.expandSelection(path, text, fromStart, fromEnd)
            }.getOrNull() ?: return@launch
            val len = session.doc.length
            val start = range.start.coerceIn(0, len)
            val end = range.end.coerceIn(start, len)
            if (end > start) {
                val fromMin = minOf(fromStart, fromEnd).coerceIn(0, len)
                val fromMax = maxOf(fromStart, fromEnd).coerceIn(fromMin, len)
                selectionExpand = SelectionExpand(fromMin, fromMax, start, end, ++expandToken)
                session.setSelectionRange(start, end)
            }
        }
    }

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
        // A plugin-tier action is invoked by registry id, not by list index, and returns the full effect set
        // (edits, caret, files, navigation) rather than only edits, so it goes to the host dispatcher, the
        // same path the toolbar and palette use. Effects that edit land on this very session.
        act.actionId?.let { id ->
            scope.launch { runCatching { onPluginAction(id, ctxStart, ctxEnd) } }
            return
        }
        val text = session.doc.text
        scope.launch {
            val raw = runCatching { backend.editor.applyAction(path, text, ctxStart, ctxEnd, act.id) }.getOrNull().orEmpty()
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

/** A one-shot selection-expand animation request (see [EditorActionsController.selectionExpand]): grow the
 *  drawn highlight from `[fromMin, fromMax)` to `[toMin, toMax)`. [token] bumps per request. */
internal data class SelectionExpand(
    val fromMin: Int,
    val fromMax: Int,
    val toMin: Int,
    val toMax: Int,
    val token: Int,
)

@Composable
internal fun rememberEditorActionsController(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    onPluginAction: suspend (actionId: String, selStart: Int, selEnd: Int) -> Unit = { _, _, _ -> },
    dismissCompletion: () -> Unit,
): EditorActionsController {
    val scope = rememberCoroutineScope()
    // The callback is re-read through a ref so a recomposition with a new lambda does not rebuild the
    // controller (which would drop the open menu and the resolved action list).
    val cb = rememberUpdatedState(onPluginAction)
    return remember(path) {
        EditorActionsController(session, backend, path, scope, dismissCompletion) { id, s, e ->
            cb.value(id, s, e)
        }
    }
}
