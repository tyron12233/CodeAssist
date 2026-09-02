package com.example.hello

import dev.ide.platform.log.Logger
import dev.ide.platform.settings.PreferenceReader
import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.UI_ACTION_EP

/**
 * The entry point named by `res/raw/codeassist_plugin.toml`. The IDE instantiates this class off the
 * installed APK with its own classloader as the parent, so the SPI types below bind to the IDE's copies.
 *
 * It contributes to three surfaces, each a plain extension-point registration:
 *  - a command in the command palette and the More menu ([UI_ACTION_EP]);
 *  - a category in Settings ([SETTINGS_PAGE_EP]);
 *  - a log line attributed to this plugin, which the Logs screen can filter by.
 */
class HelloPlugin : Plugin {

    // The host's discovered manifest is authoritative; this one is what a built-in would declare inline.
    override val manifest = PluginManifest(
        id = "com.example.hello",
        name = "Hello Plugin",
        version = "1.0.0",
        description = "Sample plugin shipped as its own app.",
        entryPoints = listOf("com.example.hello.HelloPlugin"),
        capabilities = listOf("ui.settingsPage", "ui.action"),
        minHostVersion = "3.12.0",
    )

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("HelloPlugin")
        log.info("loaded from a separate APK, api=${manifest.apiVersion}, version=${manifest.version}")

        reg.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "com.example.hello.greet",
                text = "Hello: say hello",
                places = setOf(ActionPlaces.COMMAND_PALETTE, ActionPlaces.MORE_MENU),
                iconId = "sparkle",
            ) { ctx ->
                log.info("greet invoked from '${ctx.place.id}', project=${ctx.projectRoot ?: "none"}")
                ActionResult.message("Hello from a plugin installed as its own app.")
            },
        )

        reg.register(SETTINGS_PAGE_EP, HelloSettingsPage(log))
        log.info("registered 1 action and 1 settings page")
    }
}

/** A Settings category the IDE renders generically from these control declarations. */
private class HelloSettingsPage(private val log: Logger) : SettingsPage {

    override val id = "com.example.hello"
    override val title = "Hello Plugin"
    override val iconId = "sparkle"
    override val scope = SettingsScope.APPLICATION
    override val order = 2000

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Text(
            key = "name",
            title = "Who to greet",
            description = "Used by the greeting below and by the palette command.",
            default = "world",
            placeholder = "world",
        ),
        SettingControl.Toggle(
            key = "loud",
            title = "Shout the greeting",
            description = "Uppercase the greeting.",
            default = false,
        ),
        SettingControl.Action(
            key = "greet",
            title = "Say hello",
            description = "Proves a plugin from another APK is running host code.",
            buttonLabel = "Say it",
        ),
    )

    override fun onChanged(key: String, values: PreferenceReader) {
        log.info("setting '$key' changed to '${values.raw(key)}'")
    }

    override fun onAction(key: String, values: PreferenceReader): String? {
        if (key != "greet") return null
        val greeting = "Hello, ${values.string("name", "world")}!"
        return if (values.bool("loud", false)) greeting.uppercase() else greeting
    }
}
