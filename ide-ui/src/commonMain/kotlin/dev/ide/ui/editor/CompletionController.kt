package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Completion-popup STATE + the async request/keep-alive bookkeeping, pulled out of [CodeEditor]. It owns the
 * current [CompletionSession], the selected row, the dismissed flag, the in-flight request [Job], and the
 * popup-keep-alive latch ([popupVisible] / [shown]).
 *
 * What deliberately stays in the composable: deriving `liveCompletion`/`displayed`/`showPopup` from [current]
 * (they need the live doc/caret/word-chars), and `accept()` itself — it is wired into the canvas geometry
 * (viewport-preserving edits) and the snippet session, so it lives next to them and drives this controller
 * through [suppressNext]/[dismiss]. Created per file via [rememberCompletionController].
 */
@Stable
internal class CompletionController(
    private val session: EditorSession,
    private val backend: IdeBackend,
    private val path: String,
    private val scope: CoroutineScope,
) {
    /** The current completion session (cache + filter), or null. The composable derives the live/filtered view. */
    var current by mutableStateOf<CompletionSession?>(null)
    var selected by mutableIntStateOf(0)
    var dismissed by mutableStateOf(false)

    /** Keep-alive latch for the popup WINDOW (mounted across the 1-frame gaps a keystroke opens up). */
    var popupVisible by mutableStateOf(false)
        private set

    /** The last good render state (token + items + prefix), shown while `displayed` is transiently empty. */
    var shown by mutableStateOf<ShownCompletion?>(null)
        private set

    private var job: Job? = null
    // Set by accept() so the edit it makes — which ends in an identifier char and would otherwise re-trigger
    // completion — leaves the popup closed. Consumed by the text-revision trigger.
    private var suppressNextTrigger = false

    /** Request completion at the caret (debounced unless [immediate]); cancels any prior in-flight request. */
    fun refresh(immediate: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            if (!immediate) delay(110.milliseconds)
            val text = session.doc.text
            val caret = session.selection.start
            val res = runCatching { backend.complete(path, text, caret) }.getOrNull() ?: return@launch
            val sameToken = res.replaceStart == current?.tokenStart
            current = CompletionSession.from(res)
            if (!sameToken) selected = 0
            dismissed = res.items.isEmpty()
        }
    }

    /** Close the popup + cancel any in-flight request (Esc / accept / click-away / non-identifier). */
    fun dismiss() {
        dismissed = true
        job?.cancel()
    }

    /** Re-arm and request fresh items now (Ctrl-Space, or a trigger char `.`/identifier). */
    fun reopen(immediate: Boolean = false) {
        dismissed = false
        refresh(immediate)
    }

    /** accept() is about to insert an identifier; swallow the resulting re-trigger so the popup stays closed. */
    fun suppressNext() { suppressNextTrigger = true }

    /** In the text-revision trigger: true when this revision is accept()'s own edit (then keep it closed). */
    fun consumeSuppressedTrigger(): Boolean {
        if (!suppressNextTrigger) return false
        suppressNextTrigger = false
        dismiss()
        return true
    }

    /** Drive the keep-alive latch from a LaunchedEffect keyed on (dismissed, onToken, hasItems). */
    fun updatePopupVisibility(onToken: Boolean, hasItems: Boolean) {
        popupVisible = when {
            dismissed || !onToken -> false
            hasItems -> true
            else -> popupVisible // transient empty while still on the token: hold the popup open
        }
    }

    fun snapshotShown(tokenStart: Int, items: List<UiCompletionItem>, prefix: String) {
        shown = ShownCompletion(tokenStart, items, prefix)
    }
}

@Composable
internal fun rememberCompletionController(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
): CompletionController {
    val scope = rememberCoroutineScope()
    return remember(path) { CompletionController(session, backend, path, scope) }
}
