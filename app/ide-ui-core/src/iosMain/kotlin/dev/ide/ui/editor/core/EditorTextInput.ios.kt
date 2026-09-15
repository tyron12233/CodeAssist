package dev.ide.ui.editor.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.MoveCursorCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextEditorState
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * iOS IME wiring: while the editor is focused AND the user has asked for the keyboard, an input session is
 * held open whose edits drive the [EditorSession] bridge directly, the same contract the Android actual
 * implements over `InputConnection`.
 *
 * The shape of the platform contract is different enough to be worth stating. Android's is a PUSH model: we
 * hand the framework an `InputConnection` it calls into, and we push `updateSelection`/`updateExtractedText`
 * back at the keyboard so its mirror stays exact. iOS's [PlatformTextInputMethodRequest] is a PULL model:
 * Compose owns the `UITextInput` responder and reads [PlatformTextInputMethodRequest.state] whenever UIKit
 * asks, writing through [PlatformTextInputMethodRequest.editText] and
 * [PlatformTextInputMethodRequest.onEditCommand]. Both of those read `EditorSession`'s snapshot state, so
 * there is nothing to push and [EditorSession.imeListener] is deliberately left unset here: with no listener
 * `resyncIme` is a no-op, which is exactly right on a platform whose IME cannot hold a stale mirror.
 */
actual fun Modifier.editorTextInput(
    session: EditorSession,
    ime: EditorImeHandle,
    options: EditorImeOptions,
): Modifier = this then EditorTextInputElement(session, ime, options)

/**
 * Whether an input session is live. At most one can be, because iOS text input follows first-responder
 * status and there is exactly one first responder.
 *
 * It gates [textInputCodePoint]. A hardware key press reaches the editor down TWO independent channels on
 * iOS: UIKit hands it to the `UITextInput` responder (which becomes `onEditCommand`, the IME path), and
 * Compose separately dispatches it as a key event (which reaches the editor's own `handleKey`). Android has
 * no such split, because there the IME consumes the key and the view never sees it. Left ungated, every
 * hardware keystroke was inserted more than once.
 */
private var imeSessionActive = false

actual fun textInputCodePoint(event: KeyEvent): Int {
    // While a session is live the IME owns text insertion; this path must stay silent or the character is
    // typed twice. Non-text keys are unaffected: they are handled as commands before this is ever reached.
    if (imeSessionActive) return -1
    // Command chords are shortcuts (iPadOS hardware keyboards send them). Ctrl+Alt is AltGr on some
    // layouts and DOES produce text, so it is not suppressed.
    if ((event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed) return -1
    when (event.key) {
        Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight,
        Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight,
        Key.CapsLock, Key.NumLock, Key.ScrollLock, Key.Function -> return -1
        else -> {}
    }
    val cp = event.utf16CodePoint
    return if (cp in 32..0x10FFFF && cp != 127) cp else -1
}

private data class EditorTextInputElement(
    val session: EditorSession,
    val ime: EditorImeHandle,
    val options: EditorImeOptions,
) : ModifierNodeElement<EditorTextInputNode>() {
    override fun create() = EditorTextInputNode(session, ime, options)
    override fun update(node: EditorTextInputNode) = node.setSession(session, ime, options)
}

private class EditorTextInputNode(
    private var session: EditorSession,
    private var ime: EditorImeHandle,
    private var options: EditorImeOptions,
) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {

    private var job: Job? = null
    private var focused = false
    // Whether the user has explicitly asked for the soft keyboard (a tap). The input session — and thus the
    // keyboard — starts only when this is set AND the surface is focused. Gaining focus on its own never
    // raises the keyboard; losing focus clears the request so a later passive refocus stays silent. Same
    // contract as the Android node, because it is the editor's contract, not the platform's.
    private var wantsKeyboard = false

    fun setSession(s: EditorSession, h: EditorImeHandle, opts: EditorImeOptions) {
        if (h !== ime) { ime.onShow = null; ime.onHide = null; ime = h; registerHandle() }
        val optionsChanged = opts != options
        options = opts
        if (s !== session) {
            session = s
            // A new buffer means a tab switch — never carry the keyboard over; require a fresh tap.
            wantsKeyboard = false
            stopSession()
            return
        }
        // Same buffer, new IME tunables while a session is live. The request reads `options` through a
        // lambda, but `ImeOptions` is read once when the responder is configured, so the session is
        // restarted to re-apply it. Only while focused and already up, so this never raises the keyboard.
        if (optionsChanged && job != null && isAttached && focused) startSession()
    }

    override fun onAttach() {
        registerHandle()
    }

    private fun registerHandle() {
        ime.onShow = {
            wantsKeyboard = true
            // Unlike Android, there is no "show the keyboard on the existing connection" call: on iOS the
            // keyboard follows first-responder status, which the input session owns. If a session is
            // somehow already up the keyboard is already up with it, so starting is the only action.
            if (focused && job == null) startSession()
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
        val h = ime
        imeSessionActive = true
        job = coroutineScope.launch {
            establishTextInputSession {
                startInputMethod(EditorImeRequest(s, h) { options })
            }
        }
    }

    private fun stopSession() {
        job?.cancel()
        job = null
        imeSessionActive = false
        // Cancelling the session resigns first responder, which dismisses the keyboard; no separate hide
        // call is needed (and there is no deactivated-connection polling to shut up, as there is on Android).
        session.imeFinishComposing()
    }
}

/**
 * The editor's buffer seen as the [TextEditorState] Compose reads on every UIKit query.
 *
 * It is a [CharSequence] view over the document's rope, never a materialized `String`: UIKit asks for text
 * around the caret constantly (autocorrect, the loupe, `UITextInput` range maths), and copying the whole
 * document out of the rope per query would make typing cost O(document) instead of O(edited line).
 */
@OptIn(ExperimentalComposeUiApi::class)
private class EditorTextState(private val session: EditorSession) : TextEditorState {
    override val length: Int get() = session.doc.length
    override fun get(index: Int): Char = session.doc.charAt(index)
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        session.doc.substring(startIndex, endIndex)

    override val selection: TextRange get() = session.selection
    override val composition: TextRange? get() = session.composing
}

@OptIn(ExperimentalComposeUiApi::class)
private class EditorImeRequest(
    private val session: EditorSession,
    private val handle: EditorImeHandle,
    private val options: () -> EditorImeOptions,
) : PlatformTextInputMethodRequest {

    override val state: TextEditorState = EditorTextState(session)

    /**
     * The legacy whole-value view, kept correct for any caller that still asks for it. Unlike [state] this
     * materializes the document, and the lazy `EditorDocument.text` is per-document so an edit invalidates
     * it: reading this per keystroke is O(document). [state] is the path that must stay hot.
     */
    override val value: () -> TextFieldValue = {
        TextFieldValue(session.doc.text, session.selection, session.composing)
    }

    override val imeOptions: ImeOptions
        get() = ImeOptions(
            singleLine = false,
            capitalization = KeyboardCapitalization.None,
            // The one suggestion control iOS honours. Off makes the keyboard stop autocorrecting and
            // auto-capitalizing identifiers; there is no iOS analogue of the Android bridge's
            // context-starvation defence, and none is needed, since iOS keyboards respect this flag.
            autoCorrect = options().softKeyboardSuggestions,
            // Ascii, not Text: it keeps the keyboard on a layout that shows the characters code is written
            // with, without the password-field degradation the Android actual has to resort to.
            keyboardType = KeyboardType.Ascii,
            // The editor is multi-line, so Return must insert a newline rather than act as a submit key.
            imeAction = ImeAction.None,
        )

    // Return is text, never an action; nothing here has a "done" to perform.
    override val onImeAction: ((ImeAction) -> Unit)? = null

    // The editor draws its own text on a canvas, so there is no Compose TextLayoutResult to hand UIKit.
    // The cost is the system loupe and selection handles, which the editor provides itself.
    override val textLayoutResult: () -> TextLayoutResult? = { null }

    /** The caret rect, so UIKit can scroll it clear of the keyboard and place its own floating UI. */
    override val focusedRectInRoot: () -> Rect? = {
        handle.caretGeometryProvider?.invoke()?.let {
            Rect(it.horizontalPosition, it.top, it.horizontalPosition + 1f, it.bottom)
        }
    }

    // The editor surface fills its pane and scrolls itself, so there is no meaningful field rect distinct
    // from the caret, and nothing clips the text beyond the pane UIKit already knows about.
    override val textFieldRectInRoot: () -> Rect? = { null }
    override val textClippingRectInRoot: () -> Rect? = { null }
    override val unclippedTextOffsetInRoot: () -> Offset? = { null }

    /**
     * The write path. Compose runs [block] against this scope for a UIKit edit; the whole block is one
     * [EditorSession] batch, so a multi-step keyboard edit is one undo step and one analysis pass.
     */
    override val editText: ((TextEditingScope.() -> Unit) -> Unit) = { block ->
        session.beginBatch()
        try {
            EditorEditingScope(session).block()
        } finally {
            session.endBatch()
        }
    }

    /** The command-list write path, batched for the same reason. */
    override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
        session.beginBatch()
        try {
            commands.forEach { session.applyImeCommand(it) }
        } finally {
            session.endBatch()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class EditorEditingScope(private val session: EditorSession) : TextEditingScope {
    override fun commitText(text: CharSequence, newCursorPosition: Int) {
        session.imeCommitText(text.toString(), newCursorPosition)
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        session.imeSetComposingText(text.toString(), newCursorPosition)
    }

    override fun finishComposingText() {
        session.imeFinishComposing()
    }

    override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
        session.imeDeleteSurroundingCodePoints(lengthBeforeCursor, lengthAfterCursor)
    }
}

internal fun EditorSession.applyImeCommand(command: EditCommand) {
    when (command) {
        is CommitTextCommand -> imeCommitText(command.text, command.newCursorPosition)
        is SetComposingTextCommand -> imeSetComposingText(command.text, command.newCursorPosition)
        is SetComposingRegionCommand -> imeSetComposingRegion(command.start, command.end)
        is FinishComposingTextCommand -> imeFinishComposing()
        is SetSelectionCommand -> imeSetSelection(command.start, command.end)
        is DeleteSurroundingTextCommand ->
            imeDeleteSurrounding(command.lengthBeforeCursor, command.lengthAfterCursor)
        is DeleteSurroundingTextInCodePointsCommand ->
            imeDeleteSurroundingCodePoints(command.lengthBeforeCursor, command.lengthAfterCursor)
        // The framework's own backspace: delete the selection when there is one, else one code point.
        is BackspaceCommand ->
            if (!selection.collapsed) imeCommitText("", 1) else imeDeleteSurroundingCodePoints(1, 0)
        is MoveCursorCommand -> {
            val from = if (command.amount < 0) selection.min else selection.max
            val to = offsetByCodePoints(from, command.amount)
            imeSetSelection(to, to)
        }
        is DeleteAllCommand -> imeReplaceText(0, doc.length, "", 1)
        else -> Unit // an EditCommand added by a later Compose version: ignore rather than corrupt the buffer
    }
}

/**
 * [EditorSession.imeDeleteSurrounding] counts UTF-16 units; UIKit counts code points. Converting here rather
 * than in the session keeps the platform-neutral bridge free of a platform's counting convention, and the
 * walk is bounded by the (tiny) delete length, not the document.
 */
internal fun EditorSession.imeDeleteSurroundingCodePoints(before: Int, after: Int) {
    val beforeChars = selection.min - offsetByCodePoints(selection.min, -before)
    val afterChars = offsetByCodePoints(selection.max, after) - selection.max
    imeDeleteSurrounding(beforeChars, afterChars)
}

/** [offset] moved by [amount] code points (negative moves back), clamped to the buffer. */
internal fun EditorSession.offsetByCodePoints(offset: Int, amount: Int): Int {
    var index = offset.coerceIn(0, doc.length)
    repeat(kotlin.math.abs(amount)) {
        if (amount < 0) {
            if (index <= 0) return 0
            index -= if (index >= 2 && doc.charAt(index - 1).isLowSurrogate() &&
                doc.charAt(index - 2).isHighSurrogate()
            ) 2 else 1
        } else {
            if (index >= doc.length) return doc.length
            index += if (index + 1 < doc.length && doc.charAt(index).isHighSurrogate() &&
                doc.charAt(index + 1).isLowSurrogate()
            ) 2 else 1
        }
    }
    return index
}
