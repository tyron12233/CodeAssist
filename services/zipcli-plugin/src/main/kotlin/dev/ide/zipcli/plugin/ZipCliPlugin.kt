package dev.ide.zipcli.plugin

import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration

/**
 * ZipCLI as a built-in, non-essential plugin. It contributes no extension points of its own; its surfaces are
 * the [ZipCliAgentTool] the AI agent offers and the terminal tab of the `zipcli-ui` module (its UI facet,
 * declared together with this one in `BuiltInPlugins`). The host builds the agent tool only while this plugin is
 * enabled (the same `pluginCatalog.isEnabled(ID)` check the agent and Git plugins use), and the UI facet is
 * dropped with it, so turning it off in Settings > Plugins removes both.
 */
class ZipCliPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "ZipCLI",
        description = "File, search-and-replace and ZIP-archive commands: a terminal tab, and a tool the AI agent can run over the open project.",
    )

    override fun register(reg: PluginRegistration) {
        // Nothing to register: see the class comment.
    }

    companion object {
        /** The plugin id (non-essential; disablable from Settings > Plugins). */
        const val ID = "zipcli"
    }
}
