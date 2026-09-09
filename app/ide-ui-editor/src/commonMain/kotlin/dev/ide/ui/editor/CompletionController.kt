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

    /** The token start the in-flight [job] is resolving, so a continuation keystroke on the SAME token can
     *  coalesce into it instead of stacking another round-trip. -1 when nothing is in flight. */
    private var inFlightTokenStart = -1

    // Set by accept() so the edit it makes — which ends in an identifier char and would otherwise re-trigger
    // completion — leaves the popup closed. Consumed by the text-revision trigger.
    private var suppressNextTrigger = false

    /** Whether typing auto-opens the popup (Settings → Completion). When off, only explicit triggers open it. */
    var autoPopupEnabled: Boolean = true

    /** Debounce (ms) before an auto-popup request fires (Settings → Completion → Advanced). */
    var delayMs: Int = 110

    /** Request completion at the caret (debounced unless [immediate]); cancels any prior in-flight request.
     *
     *  Coalescing: while a request for the SAME token is still in flight, a continuation keystroke does NOT
     *  stack another round-trip — the in-flight result is a complete prefix set the popup narrows client-side
     *  for whatever was typed since. Without this, a backend slower than the inter-keystroke gap (the on-device
     *  K2/IPC case, ~50-130ms) stacks one full completion PER keystroke because `current` lags behind the caret
     *  and [canNarrowLocally] keeps failing. Skipped for [immediate] (Ctrl-Space) and on a token change (a
     *  fresh context — e.g. after `.` — genuinely needs a new query). */
    fun refresh(immediate: Boolean = false) {
        val tokenStart = tokenStartAt(session.doc.text, session.selection.start)
        if (!immediate && job?.isActive == true && inFlightTokenStart == tokenStart) return
        job?.cancel()
        inFlightTokenStart = tokenStart
        job = scope.launch {
            if (!immediate) delay(delayMs.milliseconds)
            val text = session.doc.text
            val caret = session.selection.start
            val res = runCatching { backend.editor.complete(path, text, caret) }.getOrNull()
                ?: return@launch
            val sameToken = res.replaceStart == current?.tokenStart
            current = CompletionSession.from(res)
            if (!sameToken) selected = 0
            dismissed = res.items.isEmpty()
        }
    }

    /** Start of the identifier token the caret sits in (walks back over word chars, language-aware via
     *  [extraWordChars]). Matches [CompletionSession.coversCaret]'s notion of "the same token". */
    private fun tokenStartAt(text: CharSequence, caret: Int): Int {
        val extra = extraWordChars(path)
        var s = caret.coerceIn(0, text.length)
        while (s > 0 && isIdentifierChar(text[s - 1], extra)) s--
        return s
    }

    /** Report an ACCEPTED item so the backend's per-project acceptance stats learn the user's picks
     *  (frequently accepted items rank higher on later completions). Fire-and-forget. */
    fun noteAccepted(item: UiCompletionItem) {
        scope.launch { runCatching { backend.editor.completionAccepted(path, item.label) } }
    }

    /** Close the popup + cancel any in-flight request (Esc / accept / click-away / non-identifier). */
    fun dismiss() {
        dismissed = true
        job?.cancel()
        inFlightTokenStart = -1
    }

    /** Re-arm and request fresh items now (Ctrl-Space, or a trigger char `.`/identifier). */
    fun reopen(immediate: Boolean = false) {
        dismissed = false
        refresh(immediate)
    }

    /** accept() is about to insert an identifier; swallow the resulting re-trigger so the popup stays closed. */
    fun suppressNext() {
        suppressNextTrigger = true
    }

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
