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
import dev.ide.ui.ext.APP_KEY_DEFAULTS
import dev.ide.ui.ext.EDITOR_KEY_DEFAULTS
import dev.ide.ui.ext.displayShortcut
import dev.ide.ui.ext.KeymapHost

/**
 * The keymap, wired: the editor's and the app's defaults as bindings, the resolver the UI asks, and the Settings
 * page the user rebinds from.
 *
 * The defaults themselves live in `dev.ide.ui.ext.EDITOR_KEY_DEFAULTS` and `APP_KEY_DEFAULTS` rather than
 * here, and this reads them.
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

        // The app-wide commands (tabs, go-to, tool windows, run): Global, so they fire wherever focus is, the
        // editor included. Every spec is a binding, the aliases too; the first is the one menus show and the
        // one a user's rebinding replaces (an override replaces all of an action's defaults).
        for (default in APP_KEY_DEFAULTS) {
            for (spec in default.specs) {
                val shortcut = Shortcut.parse(spec)
                if (shortcut == null) {
                    log.warn("built-in shortcut '$spec' for ${default.actionId} did not parse")
                    continue
                }
                reg.register(KEY_BINDING_EP, KeyBinding(default.actionId, shortcut, KeyContext.Global))
            }
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
        val specs = KeymapHost.installSpecs { actionId -> resolver.shortcutFor(actionId)?.toString() }
        reg.onDispose { specs.dispose() }

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
        for ((actionId, spec) in defaultSpecs()) {
            val stored = preference(preferenceKey(actionId)) ?: continue
            // A stored value equal to the default is not an override; skipping it keeps the resolver's
            // override list to what the user actually changed.
            if (stored == spec) continue
            out[actionId] = stored
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
            fun control(actionId: String, title: String, default: String, group: String): SettingControl {
                val inForce = resolver.shortcutFor(actionId)?.toString()
                // An action's own aliases share nothing with each other, so only another command is a conflict.
                val others = inForce?.let { conflicts[it] }?.filter { it != actionId }.orEmpty()
                return SettingControl.Shortcut(
                    key = preferenceKey(actionId),
                    title = title,
                    // Only a conflict is worth a line under the command; how to record a shortcut is said once,
                    // at the top of the page, rather than under every row.
                    description = if (others.isEmpty()) null
                    else "Also bound to ${others.joinToString(", ") { titleOf(it) }}.",
                    default = default,
                    group = group,
                )
            }
            return APP_KEY_DEFAULTS.map { control(it.actionId, it.title, it.specs.firstOrNull().orEmpty(), it.group) } +
                EDITOR_KEY_DEFAULTS.map { control(it.actionId, commandTitle(it.actionId), it.spec, "Editor") }
        }
    }

    private companion object {
        fun preferenceKey(actionId: String) = "keymap.$actionId"

        /** Every bindable command's default spec (the first, for an app command with aliases; "" for none). */
        fun defaultSpecs(): List<Pair<String, String>> =
            APP_KEY_DEFAULTS.map { it.actionId to it.specs.firstOrNull().orEmpty() } +
                EDITOR_KEY_DEFAULTS.map { it.actionId to it.spec }

        /** The title a command is listed under: an app command's own, else derived from its id. */
        fun titleOf(actionId: String): String =
            APP_KEY_DEFAULTS.firstOrNull { it.actionId == actionId }?.title ?: commandTitle(actionId)

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
