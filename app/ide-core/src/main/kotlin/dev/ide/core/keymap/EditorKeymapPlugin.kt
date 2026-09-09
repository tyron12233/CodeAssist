package dev.ide.core.keymap

import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.impl.KeymapResolver
import dev.ide.plugin.keymap.KEY_BINDING_EP
import dev.ide.plugin.keymap.KeyBinding
import dev.ide.plugin.keymap.KeyContext
import dev.ide.plugin.keymap.KeyPress
import dev.ide.plugin.keymap.Shortcut
import dev.ide.ui.ext.EDITOR_KEY_DEFAULTS
import dev.ide.ui.ext.displayShortcut
import dev.ide.ui.ext.KeymapHost

/**
 * The keymap, wired: the editor's defaults as bindings, the resolver the UI asks, and the Settings page the
 * user rebinds from.
 *
 * The defaults themselves live in `dev.ide.ui.ext.EDITOR_KEY_DEFAULTS` rather than here, and this reads them.
 * The editor needs them with no engine present (a preview, a snapshot test), and the engine needs them as
 * bindings so a plugin can see what is taken and the user can change one; one table read from two directions
 * is the only arrangement where those cannot drift.
 *
 * A user's rebinding is a preference, `keymap.<actionId>`, holding a shortcut spec. It is application-scoped
 * on purpose: a keymap is a property of the person typing, not of the project open.
 */
class EditorKeymapPlugin(
    private val preference: (String) -> String?,
) : Plugin {

    override val manifest = PluginManifest(
        id = "keymap",
        name = "Keymap",
        description = "Keyboard shortcuts: the editor's defaults, a plugin's own bindings, and the user's " +
            "rebindings, resolved in one table.",
    )

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("keymap")

        // The editor's own shortcuts, as bindings. Every one is Editor-scoped: they act on a buffer, so they
        // must not fire from the file tree or a dialog.
        for (default in EDITOR_KEY_DEFAULTS) {
            val shortcut = Shortcut.parse(default.spec)
            if (shortcut == null) {
                // A default the parser rejects is this build's bug, not the user's, and losing one shortcut
                // silently is exactly the failure a keymap is supposed to make visible.
                log.warn("built-in shortcut '${default.spec}' for ${default.actionId} did not parse")
                continue
            }
            reg.register(KEY_BINDING_EP, KeyBinding(default.actionId, shortcut, KeyContext.Editor))
        }

        // The resolver reads the extension registry live, so it must be built over the same one this
        // registration writes to. `contributeVia` is the documented way to reach it and runs synchronously.
        lateinit var resolver: KeymapResolver
        reg.contributeVia { extensions, _ ->
            resolver = KeymapResolver(extensions, userBindings = { userBindings() })
        }

        // The UI asks through KeymapHost, which speaks primitives: it is common Compose code and cannot see
        // the keymap types. This is the one place the two vocabularies meet.
        val installation = KeymapHost.install(
            resolver = { key, ctrl, shift, alt, meta, inEditor ->
                val press = KeyPress(key = key, ctrl = ctrl, shift = shift, alt = alt, meta = meta)
                val context = if (inEditor) KeyContext.Editor else KeyContext.Global
                when (val result = resolver.resolve(press, context)) {
                    is KeymapResolver.Result.Command -> KeymapHost.Outcome.Command(result.actionId)
                    is KeymapResolver.Result.Pending ->
                        KeymapHost.Outcome.Pending(result.prefix.joinToString(" ") { it.key })
                    KeymapResolver.Result.None -> KeymapHost.Outcome.None
                }
            },
            labeler = { actionId -> resolver.shortcutFor(actionId)?.let(::displayLabel) },
        )
        reg.onDispose { installation.dispose() }

        reg.register(SETTINGS_PAGE_EP, KeymapSettingsPage(resolver))

        val conflicts = resolver.conflicts()
        if (conflicts.isNotEmpty()) {
            // Reported once at load rather than left for the user to discover by pressing a key and getting
            // the wrong command.
            log.warn("shortcuts claimed by more than one command: $conflicts")
        }
    }

    /** The user's rebindings, as `actionId -> spec`, read fresh so a change applies without a restart. */
    private fun userBindings(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (default in EDITOR_KEY_DEFAULTS) {
            val stored = preference(preferenceKey(default.actionId)) ?: continue
            // A stored value equal to the default is not an override; skipping it keeps the resolver's
            // override list to what the user actually changed.
            if (stored == default.spec) continue
            out[default.actionId] = stored
        }
        return out
    }

    /**
     * The Settings page: one recorder per command.
     *
     * [SettingControl.Shortcut] records the shortcut by having the user press it, and stores the spec, so what
     * the recorder writes and what a plugin declares in a `KeyBinding` are one syntax. A command whose
     * shortcut is contested says so in its description: the user is the only one who can decide which of two
     * commands should keep a key.
     */
    private inner class KeymapSettingsPage(private val resolver: KeymapResolver) : SettingsPage {
        override val id = "keymap"
        override val title = "Keymap"
        override val iconId = "keyboard"
        override val scope = SettingsScope.APPLICATION
        override val order = 25

        override fun controls(): List<SettingControl> {
            val conflicts = resolver.conflicts()
            return EDITOR_KEY_DEFAULTS.map { default ->
                val inForce = resolver.shortcutFor(default.actionId)?.toString()
                val contested = inForce != null && conflicts.containsKey(inForce)
                SettingControl.Shortcut(
                    key = preferenceKey(default.actionId),
                    title = commandTitle(default.actionId),
                    description = buildString {
                        if (contested) {
                            append("Also bound to ")
                            append(
                                conflicts.getValue(inForce).filter { it != default.actionId }
                                    .joinToString(", ") { commandTitle(it) },
                            )
                            append(". ")
                        }
                        append("Press a shortcut to change it; Backspace clears it.")
                    },
                    default = default.spec,
                    group = "Editor",
                )
            }
        }
    }

    private companion object {
        fun preferenceKey(actionId: String) = "keymap.$actionId"

        /** `editor.goToDeclaration` as `Go to declaration`. */
        fun commandTitle(actionId: String): String {
            val name = actionId.substringAfterLast('.')
            val spaced = buildString {
                for ((i, c) in name.withIndex()) {
                    if (i > 0 && c.isUpperCase()) append(' ')
                    append(if (i == 0) c.uppercaseChar() else c.lowercaseChar())
                }
            }
            return spaced
        }

        fun displayLabel(shortcut: Shortcut): String = displayShortcut(shortcut.toString())

    }
}
