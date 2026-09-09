// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.keymap

import dev.ide.platform.ExtensionPoint

/**
 * Keyboard shortcuts, as data.
 *
 * The IDE's shortcuts used to be `if` conditions inside the key handlers that acted on them, which made three
 * things impossible at once: a plugin could not bind a key, a user could not rebind one, and nothing could
 * say which shortcut a key already meant. A binding is a shortcut plus the id of an action, so all three
 * become lookups against one table.
 *
 * This is deliberately about NAMED COMMANDS and nothing else. Caret motion, text input and a dialog's Escape
 * are not commands and are not here: they are the text-input contract, they have no action id to bind to, and
 * putting a keymap lookup on the path a keystroke takes into the buffer would be both slower and wrong (an
 * IME commit is not a key press). What a keymap answers is "the user pressed this; which command did they
 * mean".
 */

/**
 * A modifier held with a key.
 *
 * [Primary] is the one that matters most and the one to reach for: it is Command on macOS and Control
 * everywhere else, which is what every shortcut in this IDE already meant by testing `isCtrlPressed ||
 * isMetaPressed`. Binding [Ctrl] or [Meta] explicitly asks for that physical key on every platform, which is
 * occasionally right (a terminal binding) and usually not.
 */
enum class KeyModifier {
    /** Command on macOS, Control elsewhere. The modifier a shortcut normally wants. */
    Primary,
    Ctrl,
    Shift,
    Alt,
    Meta,
}

/**
 * One key press: a key name plus the modifiers held with it.
 *
 * [key] is a canonical name, not a platform key code, because this module cannot see one: it is compared
 * against a table in the UI layer, which is the only place that knows about the toolkit's key type. The
 * canonical names are:
 *
 *  - letters `A`..`Z` and digits `0`..`9` (a letter is upper case regardless of Shift);
 *  - function keys `F1`..`F12`;
 *  - `Up`, `Down`, `Left`, `Right`, `Home`, `End`, `PageUp`, `PageDown`;
 *  - `Enter`, `Escape`, `Tab`, `Space`, `Backspace`, `Delete`, `Insert`;
 *  - the punctuation a shortcut normally uses: `Slash`, `Backslash`, `Minus`, `Equals`, `Comma`, `Period`,
 *    `Semicolon`, `Quote`, `Backtick`, `LeftBracket`, `RightBracket`.
 *
 * A name outside that set parses fine and simply never matches, so a binding written for a key this build
 * cannot see is inert rather than an error.
 */
data class KeyStroke(
    val key: String,
    val modifiers: Set<KeyModifier> = emptySet(),
) {
    /** The canonical text form, e.g. `primary+alt+L`. Round-trips through [Shortcut.parse]. */
    override fun toString(): String = buildString {
        // A fixed modifier order, so two bindings for the same combination are the same string and a text
        // comparison is enough to spot a duplicate.
        for (m in MODIFIER_ORDER) if (m in modifiers) append(m.name.lowercase()).append('+')
        append(key)
    }

    companion object {
        internal val MODIFIER_ORDER =
            listOf(KeyModifier.Primary, KeyModifier.Ctrl, KeyModifier.Meta, KeyModifier.Alt, KeyModifier.Shift)
    }
}

/**
 * What the user actually pressed: a key name and the PHYSICAL modifiers held with it.
 *
 * Separate from [KeyStroke] because the two are not the same thing. A stroke is what a binding asks for and
 * can say [KeyModifier.Primary], which is not a key anyone holds; a press is what a keyboard reported. Keeping
 * them apart is what lets [KeyStroke.matches] decide that Command on a Mac and Control elsewhere both satisfy
 * one binding, without a binding having to know which platform it is on.
 */
data class KeyPress(
    val key: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

/**
 * Whether [press] is this stroke.
 *
 * [KeyModifier.Primary] is satisfied by either command modifier, which is exactly what this IDE's shortcuts
 * meant when they were `isCtrlPressed || isMetaPressed` conditions: preserving that is the point, since a
 * migration that changed which keys work would be a regression however tidy the model.
 *
 * Shift and Alt must match EXACTLY, in both directions. That is stricter than the code this replaces, where
 * some conditions checked for an absent modifier and most did not, so `primary+shift+K` no longer also fires
 * on `primary+K`, and vice versa. Declaring `Primary` together with `Ctrl` or `Meta` is meaningless and the
 * explicit one is ignored.
 */
fun KeyStroke.matches(press: KeyPress): Boolean {
    if (key != press.key) return false
    if (press.shift != (KeyModifier.Shift in modifiers)) return false
    if (press.alt != (KeyModifier.Alt in modifiers)) return false
    return if (KeyModifier.Primary in modifiers) {
        press.ctrl || press.meta
    } else {
        press.ctrl == (KeyModifier.Ctrl in modifiers) && press.meta == (KeyModifier.Meta in modifiers)
    }
}

/**
 * A shortcut: one key press, or a chord of several pressed in sequence (`primary+K primary+D`).
 *
 * A chord is a list rather than a special case because the resolver treats every shortcut the same way: it
 * matches strokes one at a time and holds the ones that are still a prefix of something.
 */
data class Shortcut(val strokes: List<KeyStroke>) {
    init {
        require(strokes.isNotEmpty()) { "a shortcut needs at least one key stroke" }
    }

    /** True when this is a plain single-press shortcut rather than a chord. */
    val isChord: Boolean get() = strokes.size > 1

    override fun toString(): String = strokes.joinToString(" ")

    companion object {
        /** One stroke, spelled out. */
        fun of(key: String, vararg modifiers: KeyModifier) = Shortcut(listOf(KeyStroke(key, modifiers.toSet())))

        /**
         * Parse the text form: modifiers and a key joined by `+`, strokes separated by spaces.
         *
         * Modifier names are matched case-insensitively, and `cmd` / `command` / `super` are accepted for
         * [KeyModifier.Meta] and `option` for [KeyModifier.Alt], since that is what a macOS user writes.
         * Returns null for anything unparseable rather than throwing: a spec comes from a settings file or a
         * plugin manifest, where the right answer to a typo is to ignore the binding and keep the rest.
         */
        fun parse(spec: String): Shortcut? {
            val strokeSpecs = spec.trim().split(' ').filter { it.isNotEmpty() }
            if (strokeSpecs.isEmpty()) return null
            val strokes = ArrayList<KeyStroke>(strokeSpecs.size)
            for (strokeSpec in strokeSpecs) {
                // Not filtered: a dangling separator ("primary+") leaves an empty last part, and dropping it
                // would silently read the modifier as the key and bind "primary" as a key name.
                val parts = strokeSpec.split('+')
                if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
                val modifiers = LinkedHashSet<KeyModifier>()
                for (part in parts.dropLast(1)) modifiers.add(modifierOf(part) ?: return null)
                val keySpec = parts.last()
                // A bare modifier name is not a key, whichever position it appears in. Without this,
                // "shift" parses as a shortcut for a key called "shift", which can never be pressed.
                if (modifierOf(keySpec) != null) return null
                val key = canonicalKey(keySpec) ?: return null
                strokes.add(KeyStroke(key, modifiers))
            }
            return Shortcut(strokes)
        }

        private fun modifierOf(name: String): KeyModifier? = when (name.lowercase()) {
            "primary", "mod" -> KeyModifier.Primary
            "ctrl", "control" -> KeyModifier.Ctrl
            "shift" -> KeyModifier.Shift
            "alt", "option" -> KeyModifier.Alt
            "meta", "cmd", "command", "super" -> KeyModifier.Meta
            else -> null
        }

        /** A key name normalized to its canonical spelling, or null when it is not a key name at all. */
        private fun canonicalKey(name: String): String? {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return null
            ALIASES[trimmed.lowercase()]?.let { return it }
            // A single letter is upper-cased; everything else keeps the spelling it was written with, so a key
            // this build does not know still round-trips through a settings file untouched.
            return if (trimmed.length == 1) trimmed.uppercase() else trimmed
        }

        private val ALIASES: Map<String, String> = buildMap {
            put("up", "Up"); put("arrowup", "Up")
            put("down", "Down"); put("arrowdown", "Down")
            put("left", "Left"); put("arrowleft", "Left")
            put("right", "Right"); put("arrowright", "Right")
            put("home", "Home"); put("end", "End")
            put("pageup", "PageUp"); put("pgup", "PageUp")
            put("pagedown", "PageDown"); put("pgdown", "PageDown"); put("pgdn", "PageDown")
            put("enter", "Enter"); put("return", "Enter")
            put("escape", "Escape"); put("esc", "Escape")
            put("tab", "Tab")
            put("space", "Space"); put("spacebar", "Space")
            put("backspace", "Backspace")
            put("delete", "Delete"); put("del", "Delete")
            put("insert", "Insert"); put("ins", "Insert")
            put("slash", "Slash"); put("/", "Slash")
            put("backslash", "Backslash"); put("\\", "Backslash")
            put("minus", "Minus"); put("-", "Minus")
            put("equals", "Equals"); put("=", "Equals"); put("plus", "Equals")
            put("comma", "Comma"); put(",", "Comma")
            put("period", "Period"); put(".", "Period"); put("dot", "Period")
            put("semicolon", "Semicolon"); put(";", "Semicolon")
            put("quote", "Quote"); put("'", "Quote")
            put("backtick", "Backtick"); put("`", "Backtick")
            put("leftbracket", "LeftBracket"); put("[", "LeftBracket")
            put("rightbracket", "RightBracket"); put("]", "RightBracket")
            for (n in 1..12) put("f$n", "F$n")
        }
    }
}

/**
 * Where a binding applies.
 *
 * A closed set, and closed for a reason: the host has to be able to EVALUATE a context to decide whether a
 * binding is live, so an open vocabulary would let a plugin name a condition nothing can answer. Both values
 * here are answerable at the moment a key arrives.
 */
enum class KeyContext {
    /** Anywhere in the IDE, whatever has focus. */
    Global,

    /** Only while an editor tab has focus, so the binding cannot fire from a dialog or the file tree. */
    Editor,
}

/**
 * A shortcut bound to an action.
 *
 * [actionId] names a `dev.ide.plugin.action.IdeAction` (or one of the editor's own commands, which carry ids
 * of the same shape). The binding does not need the action to exist yet: bindings and actions are contributed
 * independently, and a binding for an action nothing registers simply never fires.
 *
 * [order] resolves a collision, highest first. A user's own override always outranks any contribution, so
 * this only decides between contributors: a plugin that means to replace a built-in binding raises its order
 * and takes the shortcut, and the built-in is then reported as shadowed rather than silently dead.
 */
class KeyBinding(
    val actionId: String,
    val shortcut: Shortcut,
    val context: KeyContext = KeyContext.Global,
    val order: Int = 0,
) {
    override fun toString(): String = "$shortcut -> $actionId (${context.name.lowercase()})"
}

/**
 * Plugins contribute their default key bindings here.
 *
 * These are DEFAULTS. A user's rebinding of the same action, or of the same shortcut, wins over anything on
 * this extension point, which is what makes a keymap a keymap rather than a race between plugins.
 *
 * ```
 * override fun register(reg: PluginRegistration) {
 *     reg.register(KEY_BINDING_EP, KeyBinding("com.example.hello.greet", Shortcut.parse("primary+alt+H")!!))
 * }
 * ```
 */
val KEY_BINDING_EP = ExtensionPoint<KeyBinding>("platform.keyBinding")
