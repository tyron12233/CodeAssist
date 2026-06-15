package dev.ide.ui.editor.core

import androidx.compose.ui.Modifier

/**
 * Desktop: typing arrives through the editor's common key-event handler (printable chars via
 * `KeyEvent.utf16CodePoint`), so no platform session is needed. IME composition (CJK input) is a
 * known gap — implementing it means providing the skiko `PlatformTextInputMethodRequest`
 * (value/state/onEditCommand) from an `establishTextInputSession` here, mirroring the Android actual.
 */
actual fun Modifier.editorTextInput(session: EditorSession): Modifier = this
