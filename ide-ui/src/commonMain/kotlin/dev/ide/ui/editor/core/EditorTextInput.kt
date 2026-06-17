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
 */
expect fun Modifier.editorTextInput(session: EditorSession): Modifier

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
