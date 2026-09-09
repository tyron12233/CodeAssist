package dev.ide.core

import dev.ide.platform.PluginId
import dev.ide.plugin.keymap.KEY_BINDING_EP
import dev.ide.plugin.keymap.KeyBinding
import dev.ide.plugin.keymap.KeyContext
import dev.ide.plugin.keymap.Shortcut
import dev.ide.ui.ext.EDITOR_KEY_DEFAULTS
import dev.ide.ui.ext.KeymapHost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keymap end to end: an [ApplicationEnvironment] loads the built-in keymap plugin, which turns the
 * editor's defaults into bindings, installs the resolver the editor asks through [KeymapHost], and applies
 * the user's rebindings from preferences.
 *
 * [KeymapHost] is process-global (it is the UI's one seam), so each test closes its environment, which
 * unloads the plugin and takes the installation with it.
 */
class KeymapE2ETest {

    private var env: ApplicationEnvironment? = null

    @AfterTest
    fun cleanUp() {
        env?.close()
        env = null
        assertTrue(!KeymapHost.isInstalled, "closing the environment must uninstall the keymap")
    }

    private fun start(preferences: Map<String, String> = emptyMap()): ApplicationEnvironment =
        ApplicationEnvironment(preferences = { key -> preferences[key] }).also { env = it }

    @Test
    fun theEditorsDefaultsBecomeBindingsTheEngineCanSee() {
        val environment = start()

        val bindings = environment.platform.extensions.extensions(KEY_BINDING_EP)
        assertEquals(
            EDITOR_KEY_DEFAULTS.size,
            bindings.size,
            "every editor default is a binding a plugin can see and the user can change",
        )
        assertTrue(
            bindings.all { it.context == KeyContext.Editor },
            "the editor's commands act on a buffer, so they must not fire from the file tree",
        )
        assertEquals(
            "primary+alt+L",
            bindings.first { it.actionId == "editor.reformat" }.shortcut.toString(),
        )
    }

    @Test
    fun theInstalledKeymapAnswersTheEditor() {
        start()

        assertTrue(KeymapHost.isInstalled)
        val outcome = KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = true)
        assertEquals("editor.reformat", assertIs<KeymapHost.Outcome.Command>(outcome).actionId)
        assertEquals("Ctrl/Cmd+Alt+L", KeymapHost.shortcutLabel("editor.reformat"))
    }

    @Test
    fun anEditorCommandDoesNotFireFromOutsideTheEditor() {
        start()

        assertIs<KeymapHost.Outcome.None>(KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = false))
    }

    @Test
    fun aStoredPreferenceRebindsACommandThroughTheWholeChain() {
        start(mapOf("keymap.editor.reformat" to "primary+shift+F"))

        val rebound = KeymapHost.resolve("F", ctrl = true, shift = true, inEditor = true)
        assertEquals("editor.reformat", assertIs<KeymapHost.Outcome.Command>(rebound).actionId)
        assertEquals("Ctrl/Cmd+Shift+F", KeymapHost.shortcutLabel("editor.reformat"))

        // The default is genuinely gone, rather than both keys working.
        assertIs<KeymapHost.Outcome.None>(KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = true))
    }

    @Test
    fun aStoredBlankRemovesAShortcut() {
        start(mapOf("keymap.editor.duplicateLine" to ""))

        assertIs<KeymapHost.Outcome.None>(KeymapHost.resolve("D", ctrl = true, inEditor = true))
        assertNull(KeymapHost.shortcutLabel("editor.duplicateLine"))
    }

    @Test
    fun aStoredValueEqualToTheDefaultIsNotAnOverride() {
        // The settings page writes the default back when the user opens it and saves without editing; that
        // must not be recorded as a rebinding, or the binding's context and origin would be lost.
        val environment = start(mapOf("keymap.editor.reformat" to "primary+alt+L"))

        val outcome = KeymapHost.resolve("L", ctrl = true, alt = true, inEditor = true)
        assertEquals("editor.reformat", assertIs<KeymapHost.Outcome.Command>(outcome).actionId)
        assertEquals(
            EDITOR_KEY_DEFAULTS.size,
            environment.platform.extensions.extensions(KEY_BINDING_EP).size,
            "nothing was added or replaced",
        )
    }

    @Test
    fun theSettingsPageOffersARecorderPerCommand() {
        val environment = start()
        val page = environment.platform.extensions
            .extensions(dev.ide.platform.settings.SETTINGS_PAGE_EP)
            .single { it.id == "keymap" }

        val controls = page.controls()
        assertEquals(EDITOR_KEY_DEFAULTS.size, controls.size, "one row per bindable command")
        assertTrue(
            controls.all { it is dev.ide.platform.settings.SettingControl.Shortcut },
            "each row records a shortcut rather than asking the user to type a spec",
        )
        val reformat = controls
            .filterIsInstance<dev.ide.platform.settings.SettingControl.Shortcut>()
            .single { it.key == "keymap.editor.reformat" }
        assertEquals("primary+alt+L", reformat.default, "the row knows what reset would restore")
        assertEquals("Reformat", reformat.title)
    }

    @Test
    fun aContestedShortcutIsNamedOnTheRowThatLostIt() {
        val environment = start()
        environment.platform.extensions.register(
            KEY_BINDING_EP,
            KeyBinding("editor.save", requireNotNull(Shortcut.parse("primary+alt+L")), order = 500),
            PluginId("collider"),
        )
        val page = environment.platform.extensions
            .extensions(dev.ide.platform.settings.SETTINGS_PAGE_EP)
            .single { it.id == "keymap" }

        val reformat = page.controls()
            .filterIsInstance<dev.ide.platform.settings.SettingControl.Shortcut>()
            .single { it.key == "keymap.editor.reformat" }
        assertTrue(
            reformat.description!!.contains("Also bound to"),
            "the user is the only one who can decide which command keeps the key: ${reformat.description}",
        )
    }

    @Test
    fun aPluginsBindingIsResolvedAlongsideTheEditorsOwn() {
        val environment = start()
        environment.platform.extensions.register(
            KEY_BINDING_EP,
            KeyBinding("com.example.hello.greet", requireNotNull(Shortcut.parse("primary+alt+H"))),
            PluginId("hello"),
        )

        val outcome = KeymapHost.resolve("H", ctrl = true, alt = true, inEditor = true)
        assertEquals("com.example.hello.greet", assertIs<KeymapHost.Outcome.Command>(outcome).actionId)
        // A Global binding also fires in the editor, which is what makes an app-wide shortcut app-wide.
        val global = KeymapHost.resolve("H", ctrl = true, alt = true, inEditor = false)
        assertEquals("com.example.hello.greet", assertIs<KeymapHost.Outcome.Command>(global).actionId)
    }

    @Test
    fun aPluginChordIsHeldUntilItsSecondStroke() {
        val environment = start()
        environment.platform.extensions.register(
            KEY_BINDING_EP,
            KeyBinding("com.example.two.step", requireNotNull(Shortcut.parse("primary+K primary+D"))),
            PluginId("two-step"),
        )

        assertIs<KeymapHost.Outcome.Pending>(KeymapHost.resolve("K", ctrl = true, inEditor = true))
        val done = KeymapHost.resolve("D", ctrl = true, inEditor = true)
        assertEquals("com.example.two.step", assertIs<KeymapHost.Outcome.Command>(done).actionId)
    }

    @Test
    fun aPluginCannotTakeAShortcutTheUserRebound() {
        val environment = start(mapOf("keymap.editor.reformat" to "primary+alt+H"))
        environment.platform.extensions.register(
            KEY_BINDING_EP,
            // Registered with a high order, which is what beats another CONTRIBUTOR. It does not beat a user.
            KeyBinding("com.example.shouty", requireNotNull(Shortcut.parse("primary+alt+H")), order = 1000),
            PluginId("shouty"),
        )

        val outcome = KeymapHost.resolve("H", ctrl = true, alt = true, inEditor = true)
        assertEquals(
            "editor.reformat",
            assertIs<KeymapHost.Outcome.Command>(outcome).actionId,
            "a user's rebinding outranks any contribution, or rebinding would be advisory",
        )
    }
}
