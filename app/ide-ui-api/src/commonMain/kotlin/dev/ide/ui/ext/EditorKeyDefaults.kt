package dev.ide.ui.ext

/**
 * The editor's own shortcuts, as data, in the one place both tiers can read.
 *
 * These are the bindings that used to be `if` conditions inside the editor's key handler. They live here, in
 * the neutral UI api module, because two things need them and they must not drift apart:
 *
 *  - the editor, which falls back to them when no keymap is installed (a preview, a snapshot test, any host
 *    that wires no engine). Without a fallback, migrating the shortcuts off the `if` chain would have silently
 *    cost every such host its shortcuts;
 *  - the engine tier, which turns each one into a `dev.ide.plugin.keymap.KeyBinding` on
 *    `platform.keyBinding`, so a plugin can see what is taken, a conflict can be reported, and the user can
 *    rebind it. That direction works because `ide-core` can see this module; the reverse cannot, which is why
 *    the table is here rather than there.
 *
 * [primary] is the platform's command modifier: Command on macOS, Control elsewhere, matching what every one
 * of these shortcuts meant when it was written `isCtrlPressed || isMetaPressed`.
 */
class EditorKeyDefault(
    val actionId: String,
    val key: String,
    val primary: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /**
     * The canonical shortcut spec, e.g. `primary+alt+L`.
     *
     * Formatted here rather than parsed anywhere: this is the direction that matters, since the engine tier
     * needs a spec string and the editor needs no parser at all if the table is already structured.
     */
    val spec: String
        get() = buildString {
            if (primary) append("primary+")
            if (alt) append("alt+")
            if (shift) append("shift+")
            append(key)
        }
}

/**
 * The editor's default keymap.
 *
 * Every entry is a NAMED COMMAND. The caret keys, the editing keys, text input, and the keys a popup or a
 * live template owns while it is open are deliberately absent: they are the text-input and modal contracts
 * rather than commands, they have no action to bind to, and an IME commit is not a key press at all.
 *
 * The modifier variants that used to be branches inside one handler are separate commands here (declaration
 * vs implementation, next vs previous diagnostic, line vs block comment), which is what makes each of them
 * independently rebindable.
 */
val EDITOR_KEY_DEFAULTS: List<EditorKeyDefault> = listOf(
    EditorKeyDefault("editor.reformat", "L", primary = true, alt = true),
    EditorKeyDefault("editor.optimizeImports", "O", primary = true, alt = true),
    EditorKeyDefault("editor.save", "S", primary = true),
    EditorKeyDefault("editor.find", "F", primary = true),
    EditorKeyDefault("editor.replace", "R", primary = true),
    EditorKeyDefault("editor.goToLine", "G", primary = true),
    EditorKeyDefault("editor.quickDoc", "Q", primary = true),
    EditorKeyDefault("editor.goToDeclaration", "B", primary = true),
    EditorKeyDefault("editor.goToImplementation", "B", primary = true, alt = true),
    EditorKeyDefault("editor.goToTypeDeclaration", "B", primary = true, shift = true),
    EditorKeyDefault("editor.goToSuper", "U", primary = true),
    EditorKeyDefault("editor.completeCode", "Space", primary = true),
    EditorKeyDefault("editor.parameterInfo", "P", primary = true),
    EditorKeyDefault("editor.rename", "F2"),
    EditorKeyDefault("editor.renameAlternate", "F6", shift = true),
    EditorKeyDefault("editor.nextDiagnostic", "F8"),
    EditorKeyDefault("editor.previousDiagnostic", "F8", shift = true),
    EditorKeyDefault("editor.toggleComment", "Slash", primary = true),
    EditorKeyDefault("editor.toggleBlockComment", "Slash", primary = true, shift = true),
    EditorKeyDefault("editor.duplicateLine", "D", primary = true),
    EditorKeyDefault("editor.deleteLine", "K", primary = true, shift = true),
    EditorKeyDefault("editor.joinLines", "J", primary = true, shift = true),
    EditorKeyDefault("editor.moveLineUp", "Up", alt = true, shift = true),
    EditorKeyDefault("editor.moveLineDown", "Down", alt = true, shift = true),
    EditorKeyDefault("editor.codeActions", "Enter", alt = true),
    EditorKeyDefault("editor.codeActionsAlternate", "Period", primary = true),
)

/**
 * The fallback resolver: the editor's defaults as a flat lookup, for when no keymap is installed.
 *
 * A lookup rather than a matcher. The real keymap does the interesting work (user overrides, plugin bindings,
 * chords, conflicts) and this only has to answer for the table above, so `primary` is expanded at build time
 * into the one boolean the editor can observe, and a press is a key in a map. That is what keeps the matching
 * rules from existing twice: they exist once, in the engine tier, and this is a table.
 *
 * No chords, because none of the defaults is one. A chord can only arrive from a plugin or a user, and either
 * implies the real keymap is present.
 */
object EditorKeyFallback {
    private class Press(val key: String, val primary: Boolean, val shift: Boolean, val alt: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Press && key == other.key && primary == other.primary &&
                shift == other.shift && alt == other.alt

        override fun hashCode(): Int {
            var h = key.hashCode()
            h = 31 * h + primary.hashCode()
            h = 31 * h + shift.hashCode()
            h = 31 * h + alt.hashCode()
            return h
        }
    }

    private val byPress: Map<Press, String> =
        EDITOR_KEY_DEFAULTS.associate { Press(it.key, it.primary, it.shift, it.alt) to it.actionId }

    /** The command id for a press, or null. [ctrl] or [meta] both satisfy a `primary` binding. */
    fun resolve(key: String, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): String? =
        byPress[Press(key, primary = ctrl || meta, shift = shift, alt = alt)]

    /** The default shortcut for [actionId], for a UI that labels a command with no keymap installed. */
    fun specFor(actionId: String): String? =
        EDITOR_KEY_DEFAULTS.firstOrNull { it.actionId == actionId }?.spec
}

/**
 * A shortcut spec rendered for a human: `primary+alt+L` as `Ctrl/Cmd+Alt+L`.
 *
 * Here rather than in the engine tier because it is a rendering decision, and here rather than in two places
 * because the engine's labeler and this module's fallback both need it and must agree.
 *
 * `primary` shows BOTH names on purpose. It genuinely matches either modifier, which is what the shortcuts
 * meant before they were data, so naming only the platform's own would be a lie on the other one.
 */
fun displayShortcut(spec: String): String = spec
    .split(' ')
    .joinToString(" ") { stroke ->
        stroke.split('+').joinToString("+") { part ->
            when (part.lowercase()) {
                "primary" -> "Ctrl/Cmd"
                "ctrl" -> "Ctrl"
                "shift" -> "Shift"
                "alt" -> "Alt"
                "meta" -> "Cmd"
                else -> part
            }
        }
    }
