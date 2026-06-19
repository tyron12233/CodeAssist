package dev.ide.ui.editor.core

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireView
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Android IME wiring: while the editor is focused, an input session is held open whose
 * [InputConnection] drives the [EditorSession] bridge directly — commit/composition edits mutate the
 * line-indexed buffer synchronously (O(edited line)), and the session pushes selection updates back
 * through [InputMethodManager.updateSelection] so the keyboard's composing state stays coherent.
 */
actual fun Modifier.editorTextInput(session: EditorSession, ime: EditorImeHandle): Modifier =
    this then EditorTextInputElement(session, ime)

actual fun textInputCodePoint(event: KeyEvent): Int {
    val native = event.nativeKeyEvent
    // Android's own keycode classification catches every modifier/lock/function/sym key — including the
    // non-standard codes some Bluetooth keyboards emit that Compose's Key enum leaves as Key.Unknown
    // (the "shift / combos typed as text" bug). This is why we go through the native event, not Compose's Key.
    if (AndroidKeyEvent.isModifierKey(native.keyCode)) return -1
    // Ctrl/Meta chords are shortcuts, never text. AltGr is reported as Alt (not Ctrl), so it still types.
    if (native.isCtrlPressed || native.isMetaPressed) return -1
    // unicodeChar folds in Shift/AltGr; 0 = a non-printable key (arrows, F-keys, …), and a dead-key accent
    // has its COMBINING_ACCENT high bit set (a negative Int here) so the range check drops it — as before.
    val cp = native.unicodeChar
    return if (cp in 32..0x10FFFF && cp != 127) cp else -1
}

private data class EditorTextInputElement(
    val session: EditorSession,
    val ime: EditorImeHandle,
) : ModifierNodeElement<EditorTextInputNode>() {
    override fun create() = EditorTextInputNode(session, ime)
    override fun update(node: EditorTextInputNode) = node.setSession(session, ime)
}

private class EditorTextInputNode(
    private var session: EditorSession,
    private var ime: EditorImeHandle,
) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {

    private var job: Job? = null
    private var focused = false
    // Whether the user has explicitly asked for the soft keyboard (a tap). The input session — and thus the
    // keyboard — starts only when this is set AND the surface is focused. Gaining focus on its own never
    // raises the keyboard; losing focus clears the request so a later passive refocus stays silent.
    private var wantsKeyboard = false

    fun setSession(s: EditorSession, h: EditorImeHandle) {
        if (h !== ime) { ime.onShow = null; ime.onHide = null; ime = h; registerHandle() }
        if (s === session) return
        session = s
        // A new buffer means a tab switch — never carry the keyboard over; require a fresh tap.
        wantsKeyboard = false
        stopSession()
    }

    override fun onAttach() {
        registerHandle()
    }

    private fun registerHandle() {
        ime.onShow = {
            wantsKeyboard = true
            // Dismissing the keyboard (back press) hides the IME but leaves the input session alive
            // (it's bound to focus, not visibility), so a tap that finds job != null must re-raise the
            // keyboard directly — otherwise the session stays open and the keyboard never comes back.
            if (focused) {
                if (job == null) startSession() else showSoftKeyboard()
            }
        }
        ime.onHide = {
            wantsKeyboard = false
            stopSession()
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused
        if (now == focused) return
        focused = now
        // Only the explicit request raises the keyboard — focus alone does not. Losing focus tears the
        // session down and forgets the request (so closing a sheet / returning to the screen won't reopen it).
        if (now) {
            if (wantsKeyboard && job == null) startSession()
        } else {
            wantsKeyboard = false
            stopSession()
        }
    }

    override fun onDetach() {
        ime.onShow = null
        ime.onHide = null
        stopSession()
    }

    private fun startSession() {
        job?.cancel()
        val s = session
        job = coroutineScope.launch {
            establishTextInputSession {
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val listener = object : EditorSession.ImeListener {
                    override fun onStateChanged() {
                        val sel = s.selection
                        val comp = s.composing
                        imm.updateSelection(view, sel.min, sel.max, comp?.min ?: -1, comp?.max ?: -1)
                    }

                    override fun onRestartInput() {
                        imm.restartInput(view)
                    }
                }
                s.imeListener = listener
                try {
                    startInputMethod(EditorImeRequest(s, view))
                } finally {
                    if (s.imeListener === listener) s.imeListener = null
                }
            }
        }
    }

    private fun stopSession() {
        job?.cancel()
        job = null
        session.imeFinishComposing()
    }

    // Re-raise the soft keyboard on the host view's existing input connection, without tearing down and
    // restarting the session (which would reset composing state). Used when the session is still open but
    // the user had dismissed the keyboard.
    private fun showSoftKeyboard() {
        val view = requireView()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, 0)
    }
}

private class EditorImeRequest(
    private val session: EditorSession,
    private val view: View,
) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        outAttributes.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS // code, not prose — keep autocorrect out of identifiers
        outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttributes.initialSelStart = session.selection.min
        outAttributes.initialSelEnd = session.selection.max
        return EditorInputConnection(session, view)
    }
}

/**
 * The bridge between the IME and the [EditorSession]. Extends [BaseInputConnection] only for the
 * misc defaults (sendKeyEvent dispatches into the view → Compose's key pipeline → the editor's
 * common key handler); every text operation is overridden to act on the session directly.
 */
private class EditorInputConnection(
    private val session: EditorSession,
    view: View,
) : BaseInputConnection(view, true) {

    private var batchDepth = 0

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        session.beginBatch()
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (batchDepth > 0) {
            batchDepth--
            session.endBatch()
        }
        return batchDepth > 0
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        session.imeCommitText(text?.toString() ?: "", newCursorPosition)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        session.imeSetComposingText(text?.toString() ?: "", newCursorPosition)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        session.imeSetComposingRegion(start, end)
        return true
    }

    override fun finishComposingText(): Boolean {
        session.imeFinishComposing()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        session.imeDeleteSurrounding(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        val text = session.doc.chars // CharSequence over the rope — no full-string materialization
        // convert code-point counts to char counts by walking surrogate pairs
        var before = 0
        var i = session.selection.min
        repeat(beforeLength) {
            if (i <= 0) return@repeat
            val step = if (i >= 2 && text[i - 1].isLowSurrogate() && text[i - 2].isHighSurrogate()) 2 else 1
            i -= step
            before += step
        }
        var after = 0
        var j = session.selection.max
        repeat(afterLength) {
            if (j >= text.length) return@repeat
            val step = if (j + 1 < text.length && text[j].isHighSurrogate() && text[j + 1].isLowSurrogate()) 2 else 1
            j += step
            after += step
        }
        session.imeDeleteSurrounding(before, after)
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = session.imeTextBeforeCursor(n)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = session.imeTextAfterCursor(n)

    override fun getSelectedText(flags: Int): CharSequence? = session.selectedText()

    override fun setSelection(start: Int, end: Int): Boolean {
        session.imeSetSelection(start, end)
        return true
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun performEditorAction(actionCode: Int): Boolean {
        session.commitText("\n") // multiline editor: any action key behaves as Enter
        return true
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun closeConnection() {
        while (batchDepth > 0) {
            batchDepth--
            session.endBatch()
        }
        session.imeFinishComposing()
        super.closeConnection()
    }
}
