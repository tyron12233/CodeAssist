// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.editor.core

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint

// The page owns a hidden <textarea> (window.playgroundIme, in the Playground's index.html) that the soft keyboard types
// into on a touch device; it hands over what was typed as ("text", s), ("enter"), ("backspace"), ("delete").
@JsFun("(handler) => (window.playgroundIme ? window.playgroundIme.attach(handler) : false)")
private external fun imeAttach(handler: (String, String) -> Unit): Boolean

@JsFun("() => { if (window.playgroundIme) window.playgroundIme.detach(); }")
private external fun imeDetach()

@JsFun("() => { if (window.playgroundIme) window.playgroundIme.show(); }")
private external fun imeShow()

@JsFun("() => { if (window.playgroundIme) window.playgroundIme.hide(); }")
private external fun imeHide()

/**
 * A browser with a keyboard types through key events, which the editor's common key handler already takes. A touch
 * screen has no such keys, so the soft keyboard is bridged here: while the editor is composed, the page's hidden text
 * field feeds what is typed into the session (the same calls the hardware keys make), and [EditorImeHandle.show] raises
 * the keyboard. A page without the field (one that does not define `window.playgroundIme`) is left as it was. The gap
 * is composition: a predictive-text or CJK word reaches the editor when it is finished, not as it is composed.
 */
@Suppress("DEPRECATION")
actual fun Modifier.editorTextInput(
    session: EditorSession,
    ime: EditorImeHandle,
    options: EditorImeOptions,
): Modifier = composed {
    DisposableEffect(session, ime) {
        val attached = imeAttach { kind, data ->
            when (kind) {
                "text" -> session.commitText(data)
                "enter" -> session.commitText("\n")
                "backspace" -> session.backspace(false)
                "backspace-word" -> session.backspace(true)
                "delete" -> session.deleteForward(false)
            }
        }
        if (attached) {
            ime.onShow = { imeShow() }
            ime.onHide = { imeHide() }
        }
        onDispose {
            if (attached) {
                ime.onShow = null
                ime.onHide = null
                imeDetach()
            }
        }
    }
    Modifier
}

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
