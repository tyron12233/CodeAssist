package dev.ide.zipcli.plugin

import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration

/**
 * ZipCLI as a built-in, non-essential plugin. It contributes no extension points of its own: its surface is
 * the [ZipCliAgentTool] the AI agent offers, which the host builds only while this plugin is enabled (the same
 * `pluginCatalog.isEnabled(ID)` check the agent and Git plugins use). Turning it off in Settings > Plugins
 * therefore removes the tool without touching anything else.
 */
class ZipCliPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "ZipCLI",
        description = "File, search-and-replace and ZIP-archive commands the AI agent can run over the open project.",
    )

    override fun register(reg: PluginRegistration) {
        // Nothing to register: see the class comment.
    }

    companion object {
        /** The plugin id (non-essential; disablable from Settings > Plugins). */
        const val ID = "zipcli"
    }
}
