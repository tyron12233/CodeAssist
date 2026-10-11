package dev.ide.android

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import dev.ide.ui.ext.APP_KEY_DEFAULTS
import dev.ide.ui.ext.EDITOR_KEY_DEFAULTS
import dev.ide.ui.ext.KeymapHost

/**
 * The app's shortcuts as Android's Keyboard Shortcuts list shows them (Meta+/ on a Chromebook, a tablet with a
 * keyboard, desktop mode): one group per Keymap settings group, each row the shortcut in force, so a rebinding
 * shows here too. A command with no shortcut, or a chord (the list has no way to show one), is left out.
 */
internal object AppKeyboardShortcuts {

    fun groups(): List<KeyboardShortcutGroup> {
        val rows = APP_KEY_DEFAULTS.map { Triple(it.group, it.title, it.actionId) } +
            EDITOR_KEY_DEFAULTS.map { Triple("Editor", titleOf(it.actionId), it.actionId) }
        return rows.groupBy({ it.first }) { (_, title, actionId) ->
            KeymapHost.shortcutSpec(actionId)?.let { info(title, it) }
        }.mapNotNull { (group, infos) ->
            infos.filterNotNull().takeIf { it.isNotEmpty() }?.let { KeyboardShortcutGroup("CodeAssist: $group", it) }
        }
    }

    private fun info(title: String, spec: String): KeyboardShortcutInfo? {
        if (' ' in spec) return null
        val parts = spec.split('+')
        val keyCode = keyCode(parts.last()) ?: return null
        var meta = 0
        for (m in parts.dropLast(1)) meta = meta or when (m.lowercase()) {
            // Android writes shortcuts with Ctrl, and `primary` is Ctrl or Command; Ctrl is the one to show.
            "primary", "ctrl" -> KeyEvent.META_CTRL_ON
            "shift" -> KeyEvent.META_SHIFT_ON
            "alt" -> KeyEvent.META_ALT_ON
            "meta" -> KeyEvent.META_META_ON
            else -> 0
        }
        return KeyboardShortcutInfo(title, keyCode, meta)
    }

    private fun keyCode(name: String): Int? = when {
        name.length == 1 && name[0] in 'A'..'Z' -> KeyEvent.KEYCODE_A + (name[0] - 'A')
        name.length == 1 && name[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (name[0] - '0')
        name.length in 2..3 && name[0] == 'F' && name.drop(1).toIntOrNull() in 1..12 ->
            KeyEvent.KEYCODE_F1 + (name.drop(1).toInt() - 1)
        else -> when (name) {
            "Tab" -> KeyEvent.KEYCODE_TAB
            "Enter" -> KeyEvent.KEYCODE_ENTER
            "Escape" -> KeyEvent.KEYCODE_ESCAPE
            "Space" -> KeyEvent.KEYCODE_SPACE
            "Backspace" -> KeyEvent.KEYCODE_DEL
            "Delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "Insert" -> KeyEvent.KEYCODE_INSERT
            "Up" -> KeyEvent.KEYCODE_DPAD_UP
            "Down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "Left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "Right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "Home" -> KeyEvent.KEYCODE_MOVE_HOME
            "End" -> KeyEvent.KEYCODE_MOVE_END
            "PageUp" -> KeyEvent.KEYCODE_PAGE_UP
            "PageDown" -> KeyEvent.KEYCODE_PAGE_DOWN
            "Slash" -> KeyEvent.KEYCODE_SLASH
            "Backslash" -> KeyEvent.KEYCODE_BACKSLASH
            "Minus" -> KeyEvent.KEYCODE_MINUS
            "Equals" -> KeyEvent.KEYCODE_EQUALS
            "Comma" -> KeyEvent.KEYCODE_COMMA
            "Period" -> KeyEvent.KEYCODE_PERIOD
            "Semicolon" -> KeyEvent.KEYCODE_SEMICOLON
            "Quote" -> KeyEvent.KEYCODE_APOSTROPHE
            "Backtick" -> KeyEvent.KEYCODE_GRAVE
            "LeftBracket" -> KeyEvent.KEYCODE_LEFT_BRACKET
            "RightBracket" -> KeyEvent.KEYCODE_RIGHT_BRACKET
            "Menu" -> KeyEvent.KEYCODE_MENU
            else -> null
        }
    }

    /** `editor.goToDeclaration` as `Go to declaration`, as the Keymap settings page titles it. */
    private fun titleOf(actionId: String): String = buildString {
        for ((i, c) in actionId.substringAfterLast('.').withIndex()) {
            if (i > 0 && c.isUpperCase()) append(' ')
            append(if (i == 0) c.uppercaseChar() else c.lowercaseChar())
        }
    }
}
