// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
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
 * A browser with a keyboard types through key events, which the editor's common key handler already takes, so
 * there is no IME session to establish. Composition (CJK, dead keys on a touch screen) is the gap this leaves,
 * the same one the desktop build has.
 */
actual fun Modifier.editorTextInput(
    session: EditorSession,
    ime: EditorImeHandle,
    options: EditorImeOptions,
): Modifier = this

actual fun textInputCodePoint(event: KeyEvent): Int {
    // Ctrl/Cmd chords are shortcuts. Ctrl+Alt is AltGr on some layouts and DOES produce text, so it is not suppressed.
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
