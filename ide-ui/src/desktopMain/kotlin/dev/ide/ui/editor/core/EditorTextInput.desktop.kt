package dev.ide.ui.editor.core

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Desktop: typing arrives through the editor's common key-event handler (printable chars via
 * `KeyEvent.utf16CodePoint`), so no platform session is needed. IME composition (CJK input) is a
 * known gap — implementing it means providing the skiko `PlatformTextInputMethodRequest`
 * (value/state/onEditCommand) from an `establishTextInputSession` here, mirroring the Android actual.
 */
actual fun Modifier.editorTextInput(session: EditorSession, ime: EditorImeHandle): Modifier = this

actual fun textInputCodePoint(event: KeyEvent): Int {
    // Ctrl/⌘ chords are shortcuts. Ctrl+Alt is AltGr on some layouts and DOES produce text, so don't
    // suppress it.
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
