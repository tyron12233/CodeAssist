package dev.ide.ui.editor.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Matrix
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
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
actual fun Modifier.editorTextInput(
    session: EditorSession,
    ime: EditorImeHandle,
    options: EditorImeOptions,
): Modifier = this then EditorTextInputElement(session, ime, options)

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
    // raises the keyboard; losing focus clears the request so a later passive refocus stays silent.
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
        // Same buffer, but the IME tunables (e.g. the keyboard-suggestions toggle) changed while a connection
        // is live: ask the IME to re-read createInputConnection so the new inputType takes effect now. The
        // request reads `options` lazily, so it already sees the updated value.
        if (optionsChanged && job != null && isAttached) restartInput()
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
        val h = ime
        job = coroutineScope.launch {
            establishTextInputSession {
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val bridge = EditorImeBridge(s, view, imm, h)
                s.imeListener = bridge
                try {
                    startInputMethod(EditorImeRequest(s, view, bridge) { options })
                } finally {
                    if (s.imeListener === bridge) s.imeListener = null
                }
            }
        }
    }

    private fun stopSession() {
        val hadSession = job != null
        job?.cancel()
        job = null
        session.imeFinishComposing()
        // The input session is bound to focus, not keyboard visibility: cancelling it deactivates the
        // InputConnection, but the soft keyboard keeps floating. A keyboard left up over a dead connection has
        // the IME poll it forever ("getSurroundingText / getTextBeforeCursor on inactive InputConnection" log
        // spam after a focus/overlay transition). Dismiss it so the IME unbinds. Only when we actually had a
        // session up, so we never yank some other focused field's keyboard.
        if (hadSession) hideSoftKeyboard()
    }

    // Re-raise the soft keyboard on the host view's existing input connection, without tearing down and
    // restarting the session (which would reset composing state). Used when the session is still open but
    // the user had dismissed the keyboard.
    private fun showSoftKeyboard() {
        val view = requireView()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, 0)
    }

    // Dismiss the soft keyboard for the host window. Paired with [stopSession]'s connection teardown so the IME
    // stops polling a deactivated InputConnection. Guarded on attachment because [stopSession] also runs from
    // [onDetach]; a detached node has no view.
    private fun hideSoftKeyboard() {
        if (!isAttached) return
        val view = requireView()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // Tell the IME to drop and re-create the input connection so it re-reads the EditorInfo (inputType etc.).
    // Used when the editor's IME options change mid-edit; keeps the session/keyboard up (no focus churn).
    private fun restartInput() {
        val view = requireView()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(view)
    }
}

private class EditorImeRequest(
    private val session: EditorSession,
    private val view: View,
    private val bridge: EditorImeBridge,
    private val options: () -> EditorImeOptions,
) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        // "Raw code" mode = the suggestions setting is OFF. We DON'T use TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        // (gave a password-style keyboard) or IME_FLAG_NO_PERSONALIZED_LEARNING (triggers Gboard's incognito
        // UI). NO_SUGGESTIONS is still hinted for IMEs that honor it; the real defense against autocorrect/
        // auto-space (which Gboard applies even with NO_SUGGESTIONS) is the input connection STARVING the IME
        // of text context — see [EditorInputConnection]'s rawMode. Mirrors sora-editor's `disallowSuggestions`.
        val rawMode = !options().softKeyboardSuggestions
        var inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (rawMode) inputType = inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttributes.inputType = inputType
        outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttributes.initialSelStart = session.selection.min
        outAttributes.initialSelEnd = session.selection.max
        return EditorInputConnection(session, view, bridge, rawMode)
    }
}

/** Cap on a full extracted-text snapshot sent over the binder; larger buffers window around the caret so a big
 *  file never risks `TransactionTooLargeException`. Per-keystroke updates are partial, so this only bounds the
 *  one-shot/initial snapshot. */
private const val MAX_EXTRACT_CHARS = 100_000
private const val NO_MONITOR = -1

/**
 * Owns the editor's IME-push side. Set as the [EditorSession.ImeListener], it forwards every edit/selection
 * change to the keyboard: a partial `updateExtractedText` so an IME that monitors our text stays exact without
 * us materializing the whole document per keystroke ([onTextChanged]), the canonical `updateSelection`
 * ([onStateChanged]), and `updateCursorAnchorInfo` when the IME asked for cursor updates. Because a monitoring
 * IME never drifts, smart edits no longer need a disruptive `restartInput` (see [EditorSession.resyncIme]).
 *
 * The [InputConnection] writes [monitorToken]/[cursorUpdateMode] here as the IME turns those features on.
 */
private class EditorImeBridge(
    private val session: EditorSession,
    private val view: View,
    private val imm: InputMethodManager,
    private val handle: EditorImeHandle,
) : EditorSession.ImeListener {

    /** Token from a `GET_EXTRACTED_TEXT_MONITOR` request, or [NO_MONITOR] when the IME isn't mirroring text. */
    var monitorToken = NO_MONITOR
    /** Last `requestCursorUpdates` mode (`CURSOR_UPDATE_IMMEDIATE`/`MONITOR` bits), 0 when off. */
    var cursorUpdateMode = 0
    /** Bumped per [InputConnection] created for this session. After a `restartInput` the framework closes the
     *  OLD connection only after its replacement is live, so a close must prove it belongs to the newest
     *  connection before it resets the shared state above ([EditorInputConnection.closeConnection]). */
    var connectionGeneration = 0
    /** Whether the last full snapshot we pushed was windowed (huge buffer). A per-edit partial update addresses
     *  the IME's mirror with absolute offsets, valid only while the mirror is the whole document; a windowed
     *  mirror is re-based, so we keep pushing full snapshots until it spans the whole buffer from 0 again. */
    private var mirrorWindowed = false

    override fun onStateChanged() {
        pushSelection()
        maybePushCursorAnchor()
    }

    override fun onTextChanged(span: EditSpan?) {
        if (monitorToken != NO_MONITOR) {
            val et = if (span != null && !mirrorWindowed && session.doc.length <= MAX_EXTRACT_CHARS) {
                partialExtractedText(session, span)
            } else {
                fullExtracted()
            }
            imm.updateExtractedText(view, monitorToken, et)
        }
        pushSelection()
        maybePushCursorAnchor()
    }

    /** A full snapshot, recording whether the mirror it establishes is windowed so the next per-edit push knows
     *  if absolute partial offsets still address it. The InputConnection's one-shot query routes here too. */
    fun fullExtracted(): ExtractedText {
        val et = fullExtractedText(session)
        mirrorWindowed = et.startOffset != 0
        return et
    }

    override fun onRestartInput() {
        imm.restartInput(view)
    }

    override fun isSyncingExtractedText(): Boolean = monitorToken != NO_MONITOR

    private fun pushSelection() {
        val sel = session.selection
        val comp = session.composing
        imm.updateSelection(view, sel.min, sel.max, comp?.min ?: -1, comp?.max ?: -1)
    }

    private fun maybePushCursorAnchor() {
        if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_MONITOR != 0) pushCursorAnchor()
    }

    fun pushCursorAnchor() {
        imm.updateCursorAnchorInfo(view, buildCursorAnchorInfo(session, view, handle))
    }
}

/** Initial/one-shot snapshot + once-per-batch full refresh (windowed around the caret past [MAX_EXTRACT_CHARS]). */
private fun fullExtractedText(session: EditorSession): ExtractedText =
    session.extractedTextSnapshot(MAX_EXTRACT_CHARS).toAndroid()

/** Per-edit partial update; the offset/selection arithmetic is the commonMain (unit-tested) [partialExtractedSnapshot]. */
private fun partialExtractedText(session: EditorSession, span: EditSpan): ExtractedText =
    session.partialExtractedSnapshot(span).toAndroid()

private fun ExtractedTextSnapshot.toAndroid(): ExtractedText {
    val et = ExtractedText()
    et.text = text
    et.startOffset = startOffset
    et.selectionStart = selectionStart
    et.selectionEnd = selectionEnd
    et.partialStartOffset = partialStartOffset
    et.partialEndOffset = partialEndOffset
    et.flags = if (selectionStart != selectionEnd) ExtractedText.FLAG_SELECTING else 0
    return et
}

/** Build `CursorAnchorInfo` from [session] state plus the editor's caret geometry (when wired). Coordinates from
 *  the provider are in the view's pixel space, so the matrix is just the view's on-screen translation. */
private fun buildCursorAnchorInfo(session: EditorSession, view: View, handle: EditorImeHandle): CursorAnchorInfo {
    val sel = session.selection
    val comp = session.composing
    val b = CursorAnchorInfo.Builder()
    b.setSelectionRange(sel.min, sel.max)
    if (comp != null && comp.min < comp.max) {
        b.setComposingText(comp.min, session.doc.substring(comp.min, comp.max))
    }
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    b.setMatrix(Matrix().apply { setTranslate(loc[0].toFloat(), loc[1].toFloat()) })
    handle.caretGeometryProvider?.invoke()?.let { c ->
        b.setInsertionMarkerLocation(
            c.horizontalPosition, c.top, c.baseline, c.bottom,
            CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
        )
    }
    return b.build()
}

/**
 * The bridge between the IME and the [EditorSession]. Extends [BaseInputConnection] only for the
 * misc defaults (sendKeyEvent dispatches into the view → Compose's key pipeline → the editor's
 * common key handler); every text operation is overridden to act on the session directly.
 */
private class EditorInputConnection(
    private val session: EditorSession,
    private val view: View,
    private val bridge: EditorImeBridge,
    /**
     * Context-starvation mode (Settings → Editor → Keyboard suggestions OFF). The IME is handed NO text
     * context — empty before/after/selected/surrounding/extracted text, re-composing existing text
     * ([setComposingRegion]) refused, and its `setSelection` ignored — so it can't run autocorrect /
     * auto-space / suggestions over the buffer. The active composing word is still tracked ([setComposingText])
     * so the IME stays in sync. Mirrors sora-editor's `disallowSuggestions`. A normal keyboard otherwise.
     */
    private val rawMode: Boolean,
) : BaseInputConnection(view, true) {

    private val generation = ++bridge.connectionGeneration
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
        val s = text?.toString() ?: ""
        // Composing text never spans a line break; refuse one and let the IME fall back to committing the text,
        // so the underlined composing region can't straddle two lines.
        if ('\n' in s) return false
        // Honor composition even in rawMode. An IME (esp. SwiftKey / Gboard glide typing) keeps its OWN composing
        // buffer and re-sends the whole word as it grows/shrinks, expecting each call to REPLACE the region.
        // The old rawMode path committed + rejected, leaving our buffer with no composing region — so the IME's
        // next setComposingText landed AFTER the committed word instead of replacing it (typing "hello" then
        // backspace became "hellohell": "delete re-inserts the suggestion"). What actually suppresses autocorrect/
        // auto-space is the context starvation below (empty getText*/extracted, ignored setSelection, rejected
        // setComposingRegion); tracking the active composing word does NOT re-enable it.
        session.imeSetComposingText(s, newCursorPosition)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (rawMode) return false // never let a starved IME re-compose over existing text
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
        // Convert code-point counts to char counts by walking surrogate pairs outward from the edges the
        // session will actually delete at — outside the composing region, matching [EditorSession.imeDeleteSurrounding].
        val sel = session.selection
        val comp = session.composing
        var before = 0
        var i = minOf(sel.min, comp?.min ?: sel.min)
        var remaining = beforeLength
        while (remaining > 0 && i > 0) {
            val step = if (i >= 2 && text[i - 1].isLowSurrogate() && text[i - 2].isHighSurrogate()) 2 else 1
            i -= step
            before += step
            remaining--
        }
        var after = 0
        var j = maxOf(sel.max, comp?.max ?: sel.max)
        remaining = afterLength
        while (remaining > 0 && j < text.length) {
            val step = if (j + 1 < text.length && text[j].isHighSurrogate() && text[j + 1].isLowSurrogate()) 2 else 1
            j += step
            after += step
            remaining--
        }
        session.imeDeleteSurrounding(before, after)
        return true
    }

    // The text-context queries: in rawMode they return EMPTY, starving the IME so it can't autocorrect/
    // auto-space the surrounding code (Gboard does this even with NO_SUGGESTIONS). Sora's `disallowSuggestions`.
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        if (rawMode) "" else session.imeTextBeforeCursor(n)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        if (rawMode) "" else session.imeTextAfterCursor(n)

    override fun getSelectedText(flags: Int): CharSequence? =
        if (rawMode) null else session.selectedText()

    /** Half-open [beforeLength]/[afterLength] window of text around the selection (API 31+). Implemented
     *  explicitly so monitoring IMEs do one read instead of three; empty in [rawMode]. */
    @RequiresApi(31)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
        if (rawMode || beforeLength < 0 || afterLength < 0) return SurroundingText("", 0, 0, -1)
        val before = session.imeTextBeforeCursor(beforeLength)
        val selected = session.selectedText() ?: ""
        val after = session.imeTextAfterCursor(afterLength)
        return SurroundingText(
            before + selected + after, before.length, before.length + selected.length,
            session.selection.min - before.length,
        )
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        if (rawMode) return false // a starved IME thinks the field is empty — don't let it yank the caret
        session.imeSetSelection(start, end)
        return true
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun performEditorAction(actionCode: Int): Boolean {
        session.commitText("\n") // multiline editor: any action key behaves as Enter
        return true
    }

    // Clipboard / edit actions an IME or accessibility service can fire (its clipboard chip, "select all", …),
    // routed to the same session ops the editor's own shortcuts use.
    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.selectAll -> session.selectAll()
            android.R.id.cut -> session.cutSelection()?.let(::setClipboardText)
            android.R.id.copy -> session.selectedText()?.let(::setClipboardText)
            android.R.id.paste, android.R.id.pasteAsPlainText -> clipboardText()?.let(session::commitText)
            android.R.id.undo -> session.undo()
            android.R.id.redo -> session.redo()
            else -> return super.performContextMenuAction(id)
        }
        return true
    }

    private fun setClipboardText(text: String) {
        val cm = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    private fun clipboardText(): String? {
        val cm = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.coerceToText(view.context)?.toString()?.takeIf { it.isNotEmpty() }
    }

    // Hand the IME a real view of our buffer so it can mirror text + cursor continuously (and not drift after a
    // smart edit). A MONITOR request arms per-edit `updateExtractedText` pushes via the bridge. In rawMode we
    // return null (and never arm the monitor) so the IME can't mirror — its only window into our text is closed.
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (rawMode) return null
        if (request != null && flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) {
            bridge.monitorToken = request.token
        }
        return bridge.fullExtracted()
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        bridge.cursorUpdateMode = cursorUpdateMode
        if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) bridge.pushCursorAnchor()
        return true
    }

    override fun closeConnection() {
        while (batchDepth > 0) {
            batchDepth--
            session.endBatch()
        }
        // Only the newest connection may reset shared IME state: after a restartInput the framework
        // deactivates the OLD connection last, and that late close must not disarm the monitor /
        // cursor-update mode the replacement has already armed, nor kill its live composition.
        if (bridge.connectionGeneration == generation) {
            bridge.monitorToken = NO_MONITOR
            bridge.cursorUpdateMode = 0
            session.imeFinishComposing()
        }
        super.closeConnection()
    }
}
