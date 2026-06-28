package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Parameter-info (signature help) state + resolution, pulled out of [CodeEditor]. It re-resolves whenever the
 * caret moves or the buffer edits — gated by a cheap local scan ([caretInsideCall]) so the backend is hit only
 * when the caret is actually inside a call's parens — and dismisses/re-arms as the caret leaves the call (so Esc
 * only hides it for the current call). An explicit Ctrl/Cmd-P forces it back even after a dismiss.
 *
 * Created per file via [rememberSignatureHelpController]. The composable drives [resolve] from an effect keyed
 * on text/selection/[epoch]/focus and renders the popup against [help]/[dismissed].
 */
@Stable
internal class SignatureHelpController(private val backend: IdeBackend, private val path: String) {
    var help by mutableStateOf<UiSignatureHelp?>(null)
        private set
    var dismissed by mutableStateOf(false)
        private set

    /** Bumped by the explicit (Ctrl/Cmd-P) trigger; the composable keys its resolve effect on this. */
    var epoch by mutableIntStateOf(0)
        private set

    /** Ctrl/Cmd-P: force the panel even if it was dismissed — re-arm + bump so the resolve effect re-runs. */
    fun triggerExplicit() {
        dismissed = false
        epoch++
    }

    /** Esc / click-outside: hide for the current call (a caret move out of the call re-arms it in [resolve]). */
    fun dismiss() {
        dismissed = true
    }

    /** Recompute help for the caret in [session]; call from a LaunchedEffect keyed on text/selection/epoch/focus. */
    suspend fun resolve(focused: Boolean, session: EditorSession) {
        if (!focused) { help = null; return }
        val sel = session.selection
        val caret = sel.start
        if (sel.start != sel.end || !caretInsideCall(session.doc.chars, caret)) {
            help = null
            dismissed = false
            return
        }
        if (dismissed) return
        delay(40.milliseconds)
        val text = session.doc.text
        help = runCatching { backend.editor.signatureHelp(path, text, caret) }.getOrNull()
    }
}

@Composable
internal fun rememberSignatureHelpController(path: String, backend: IdeBackend): SignatureHelpController =
    remember(path) { SignatureHelpController(backend, path) }
