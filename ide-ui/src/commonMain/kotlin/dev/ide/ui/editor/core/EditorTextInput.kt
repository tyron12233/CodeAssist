package dev.ide.ui.editor.core

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent

/**
 * Platform IME integration for the custom editor surface.
 *
 * The editor draws its own text and applies its own edits, so it can't use `BasicTextField`'s input
 * pipeline; instead each platform wires the soft keyboard straight to the [EditorSession] bridge:
 *
 * - **Android**: a `PlatformTextInputModifierNode` that, while the editor is focused, establishes a
 *   text-input session whose `InputConnection` applies commits/composition to the session immediately
 *   (the sora-editor model — a keystroke mutates the buffer synchronously; nothing waits on analysis).
 * - **Desktop**: a no-op — hardware keys are handled by the editor's common key-event handler.
 *   (Desktop IME composition — CJK — can later implement the skiko text-input session here.)
 *
 * Must sit **before** `Modifier.focusable()` in the editor's modifier chain so the node observes the
 * focus target's events.
 *
 * The soft keyboard is shown **only** on an explicit [EditorImeHandle.show] (the editor calls it from a
 * tap) — never merely because the surface gained focus. So programmatic focus (opening a file, switching
 * tabs), focus restored when a sheet/overlay closes, or returning from another screen leaves the keyboard
 * hidden; the caret is placed and hardware keys still work (those flow through the focusable's key handler,
 * not the IME session). Losing focus drops the request, so the next passive refocus won't reopen it.
 */
expect fun Modifier.editorTextInput(session: EditorSession, ime: EditorImeHandle): Modifier

/**
 * A small imperative handle the editor uses to ask the platform IME to show/hide. Showing the keyboard is
 * an *event*, not a state derived from focus — so the editor fires [show] on a deliberate tap, and the
 * platform node starts the input session (which raises the keyboard) only then. [hide] tears the session
 * down. Created with `remember { EditorImeHandle() }` and passed to [editorTextInput]; the platform node
 * registers its implementation on attach and clears it on detach.
 */
class EditorImeHandle {
    internal var onShow: (() -> Unit)? = null
    internal var onHide: (() -> Unit)? = null

    /**
     * Supplies the primary caret's geometry for `InputConnection.requestCursorUpdates` / `CursorAnchorInfo`
     * (the IME uses it to place its floating UI, handwriting box, etc.). The editor's render layer sets this;
     * the platform IME bridge reads it. Coordinates are in the IME root view's pixel space. Null when no layout
     * is available yet. Only the Android bridge consults it; desktop leaves it unset.
     */
    var caretGeometryProvider: (() -> EditorCaretGeometry?)? = null

    /** Ask the platform to raise the soft keyboard for the editor (no-op until the node is attached/focused). */
    fun show() { onShow?.invoke() }

    /** Ask the platform to dismiss the soft keyboard / end the input session. */
    fun hide() { onHide?.invoke() }
}

/**
 * The primary caret's pixel geometry in the IME root view's coordinate space, for `CursorAnchorInfo`'s
 * insertion-marker location. [baseline] is where glyphs sit; [top]/[bottom] bound the caret line.
 */
class EditorCaretGeometry(
    val horizontalPosition: Float,
    val top: Float,
    val baseline: Float,
    val bottom: Float,
)

/**
 * The Unicode code point to insert for a hardware key-down [event], or **-1** when the event is not text
 * input — a modifier/lock/function/navigation key, or a command-key chord (Ctrl/⌘). The editor's key
 * handler calls this for the printable-character fall-through.
 *
 * Platform-specific because classifying a modifier key robustly needs the native event: Compose's
 * [androidx.compose.ui.input.key.Key] doesn't expose "is this a modifier", and Bluetooth keyboards can
 * report key codes its enum leaves as `Key.Unknown`. Android defers to `android.view.KeyEvent.isModifierKey`
 * (which knows every modifier keycode regardless of Compose's mapping); desktop mirrors the old
 * `utf16CodePoint` path with a modifier blocklist.
 */
expect fun textInputCodePoint(event: KeyEvent): Int
