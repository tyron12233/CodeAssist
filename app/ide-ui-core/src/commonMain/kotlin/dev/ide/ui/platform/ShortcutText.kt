package dev.ide.ui.platform

import dev.ide.ui.ext.APP_COMMAND_EQUIVALENTS
import dev.ide.ui.ext.KeymapHost

/**
 * A keymap shortcut spec (`primary+shift+N`) as this platform writes shortcuts in a menu: `Ctrl+Shift+N` on
 * Android, Windows and Linux, `⇧⌘N` on a Mac. Unlike the Settings page's `Ctrl/Cmd` form, a menu names the
 * one key the user will actually press. A chord's strokes are separated by a space.
 */
fun shortcutText(spec: String, commandKey: Boolean = usesCommandKey): String =
    spec.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { stroke ->
        val parts = stroke.split('+')
        val mods = parts.dropLast(1).map { it.lowercase() }.toSet()
        val key = keyText(parts.last(), commandKey)
        if (commandKey) {
            // macOS order and glyphs: Control, Option, Shift, Command, then the key, with no separators.
            buildString {
                if ("ctrl" in mods) append('⌃')
                if ("alt" in mods) append('⌥')
                if ("shift" in mods) append('⇧')
                if ("primary" in mods || "meta" in mods) append('⌘')
                append(key)
            }
        } else {
            buildList {
                if ("primary" in mods || "ctrl" in mods) add("Ctrl")
                if ("meta" in mods) add("Meta")
                if ("alt" in mods) add("Alt")
                if ("shift" in mods) add("Shift")
                add(key)
            }.joinToString("+")
        }
    }

/** The shortcut in force for [actionId] (or the app command it is equivalent to), as [shortcutText] renders it,
 *  or null when it has none. */
fun shortcutTextFor(actionId: String): String? =
    KeymapHost.shortcutSpec(APP_COMMAND_EQUIVALENTS[actionId] ?: actionId)?.let { shortcutText(it) }

/**
 * A shortcut spec as the keys to draw on keycaps, one list per stroke of a chord: the modifiers in this
 * platform's order, then the key (`⇧` `⌘` `T` on a Mac, `Ctrl` `Shift` `T` elsewhere).
 */
fun shortcutKeys(spec: String, commandKey: Boolean = usesCommandKey): List<List<String>> =
    spec.split(' ').filter { it.isNotEmpty() }.map { stroke ->
        val parts = stroke.split('+')
        val mods = parts.dropLast(1).map { it.lowercase() }.toSet()
        buildList {
            if (commandKey) {
                if ("ctrl" in mods) add("⌃")
                if ("alt" in mods) add("⌥")
                if ("shift" in mods) add("⇧")
                if ("primary" in mods || "meta" in mods) add("⌘")
            } else {
                if ("primary" in mods || "ctrl" in mods) add("Ctrl")
                if ("meta" in mods) add("Meta")
                if ("alt" in mods) add("Alt")
                if ("shift" in mods) add("Shift")
            }
            // The Mac page glyphs (⇞ ⇟) are unreadable at keycap size, so a keycap spells them out.
            add(
                when (val key = parts.last()) {
                    "PageUp" -> "PgUp"
                    "PageDown" -> "PgDn"
                    else -> keyText(key, commandKey)
                },
            )
        }
    }

private fun keyText(name: String, commandKey: Boolean): String = when (name) {
    "Slash" -> "/"
    "Backslash" -> "\\"
    "Minus" -> "-"
    "Equals" -> "="
    "Comma" -> ","
    "Period" -> "."
    "Semicolon" -> ";"
    "Quote" -> "'"
    "Backtick" -> "`"
    "LeftBracket" -> "["
    "RightBracket" -> "]"
    "PageUp" -> if (commandKey) "⇞" else "PgUp"
    "PageDown" -> if (commandKey) "⇟" else "PgDn"
    "Enter" -> if (commandKey) "↩" else "Enter"
    "Escape" -> "Esc"
    "Tab" -> if (commandKey) "⇥" else "Tab"
    "Backspace" -> if (commandKey) "⌫" else "Backspace"
    "Delete" -> if (commandKey) "⌦" else "Del"
    "Insert" -> "Ins"
    "Up" -> "↑"
    "Down" -> "↓"
    "Left" -> "←"
    "Right" -> "→"
    else -> name
}

/**
 * Whether a hardware keyboard is attached, so shortcut hints are worth showing. Always true on desktop; on
 * Android it follows the configuration (a Chromebook, a tablet keyboard, desktop mode), and a phone with no
 * keyboard shows no hints it cannot use.
 */
@androidx.compose.runtime.Composable
expect fun hasHardwareKeyboard(): Boolean
