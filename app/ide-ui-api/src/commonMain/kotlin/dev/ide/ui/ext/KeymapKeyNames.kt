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
    // The context-menu key (between the right Alt and Ctrl on a PC keyboard).
    put(Key.Menu, "Menu")

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

/** Keys that type nothing and move nothing, so binding one bare (F5) or with only Shift (Shift+F10) takes
 *  nothing away from the keyboard. */
private val NON_TYPING_KEYS: Set<String> = (1..12).map { "F$it" }.toSet() + setOf("Insert", "Menu")

/**
 * What a press records as in a shortcut recorder: the keymap spec (`primary+alt+L`), or null when the press
 * cannot be a shortcut. A key that types or moves something (a letter, a digit, Space, Enter, Tab, an arrow)
 * needs Ctrl, Alt or Meta, because bound bare or with only Shift it would stop typing or moving the caret
 * everywhere. A key that does neither (F1 to F12, Insert, Menu) records bare or with any modifiers, which is
 * how F5 or Shift+F10 is bound. Ctrl and Meta both record as `primary`, so a binding means Command on a Mac
 * and Control elsewhere. Null for a modifier on its own too, since it has no [keymapKeyName].
 *
 * The one rule both recorders apply, so the Keymap page and a plugin's shortcut setting accept the same keys.
 */
fun recordedShortcutSpec(key: Key, ctrl: Boolean, meta: Boolean, alt: Boolean, shift: Boolean): String? {
    val name = keymapKeyName(key) ?: return null
    val primary = ctrl || meta
    if (!primary && !alt && name !in NON_TYPING_KEYS) return null
    return buildString {
        if (primary) append("primary+")
        if (alt) append("alt+")
        if (shift) append("shift+")
        append(name)
    }
}
