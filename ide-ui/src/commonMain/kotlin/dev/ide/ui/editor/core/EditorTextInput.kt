package dev.ide.ui.editor.core

import androidx.compose.ui.Modifier

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
