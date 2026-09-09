package dev.ide.ui.ext

import androidx.compose.ui.input.key.Key

/**
 * The canonical keymap name for a toolkit [Key], or null for a key no shortcut can name.
 *
 * Deliberately a table rather than a derivation from `Key.keyCode` or `Key.toString()`: both differ between
 * the Android and desktop targets, and a shortcut written into a user's settings file has to keep meaning the
 * same key across an update and across platforms.
 *
 * Shared rather than editor-local because two surfaces need the same answer: the editor, turning a press into
 * a command, and the Settings shortcut recorder, turning a press into the spec it stores. A recorder that
 * named keys differently from the resolver would record shortcuts that never fire.
 */
fun keymapKeyName(key: Key): String? = KEY_NAMES[key]

private val KEY_NAMES: Map<Key, String> = buildMap {
    put(Key.A, "A"); put(Key.B, "B"); put(Key.C, "C"); put(Key.D, "D"); put(Key.E, "E")
    put(Key.F, "F"); put(Key.G, "G"); put(Key.H, "H"); put(Key.I, "I"); put(Key.J, "J")
    put(Key.K, "K"); put(Key.L, "L"); put(Key.M, "M"); put(Key.N, "N"); put(Key.O, "O")
    put(Key.P, "P"); put(Key.Q, "Q"); put(Key.R, "R"); put(Key.S, "S"); put(Key.T, "T")
    put(Key.U, "U"); put(Key.V, "V"); put(Key.W, "W"); put(Key.X, "X"); put(Key.Y, "Y")
    put(Key.Z, "Z")

    put(Key.Zero, "0"); put(Key.One, "1"); put(Key.Two, "2"); put(Key.Three, "3"); put(Key.Four, "4")
    put(Key.Five, "5"); put(Key.Six, "6"); put(Key.Seven, "7"); put(Key.Eight, "8"); put(Key.Nine, "9")

    put(Key.F1, "F1"); put(Key.F2, "F2"); put(Key.F3, "F3"); put(Key.F4, "F4")
    put(Key.F5, "F5"); put(Key.F6, "F6"); put(Key.F7, "F7"); put(Key.F8, "F8")
    put(Key.F9, "F9"); put(Key.F10, "F10"); put(Key.F11, "F11"); put(Key.F12, "F12")

    put(Key.DirectionUp, "Up"); put(Key.DirectionDown, "Down")
    put(Key.DirectionLeft, "Left"); put(Key.DirectionRight, "Right")
    put(Key.MoveHome, "Home"); put(Key.MoveEnd, "End")
    put(Key.PageUp, "PageUp"); put(Key.PageDown, "PageDown")

    put(Key.Enter, "Enter"); put(Key.NumPadEnter, "Enter")
    put(Key.Escape, "Escape")
    put(Key.Tab, "Tab")
    put(Key.Spacebar, "Space")
    put(Key.Backspace, "Backspace")
    put(Key.Delete, "Delete")
    put(Key.Insert, "Insert")

    put(Key.Slash, "Slash"); put(Key.Backslash, "Backslash")
    // Both the main-row and numpad forms of the zoom keys, since a shortcut written `primary+Equals` should
    // work whichever the user's keyboard sends.
    put(Key.Minus, "Minus"); put(Key.NumPadSubtract, "Minus")
    put(Key.Equals, "Equals"); put(Key.Plus, "Equals"); put(Key.NumPadAdd, "Equals")
    put(Key.Comma, "Comma"); put(Key.Period, "Period")
    put(Key.Semicolon, "Semicolon"); put(Key.Apostrophe, "Quote")
    put(Key.Grave, "Backtick")
    put(Key.LeftBracket, "LeftBracket"); put(Key.RightBracket, "RightBracket")
}
