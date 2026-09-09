package dev.ide.core

import dev.ide.platform.settings.SETTINGS_PAGE_EP
import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration

/**
 * Version control, contributed as a built-in plugin. Registers the "Version Control" settings page (the
 * commit identity and the GitHub connection); the working copy itself is served by [VcsBackend], a concern
 * backend wired by [IdeServicesBackend]. Non-essential, so the whole feature can be turned off from the
 * Plugins settings screen, which also removes its UI facet.
 */
internal class VcsPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "Version Control",
        description = "Git for your projects: changes, commits, branches, history, and GitHub sign-in.",
    )

    override fun register(reg: PluginRegistration) {
        reg.register(SETTINGS_PAGE_EP, VcsSettingsPage)
    }

    companion object {
        /** The plugin id (non-essential; disablable from Settings > Plugins). */
        const val ID = "vcs"

        /** The settings page id, which is also the preference namespace (`settings.vcs.*`). */
        const val PAGE = "vcs"

        const val PREF_USER_NAME: String = "settings.$PAGE.userName"
        const val PREF_USER_EMAIL: String = "settings.$PAGE.userEmail"
        const val PREF_CLIENT_ID: String = "settings.$PAGE.githubClientId"
    }
}

/** The "Version Control" settings page. Values persist under `settings.vcs.*`. */
internal object VcsSettingsPage : SettingsPage {
    override val id: String = VcsPlugin.PAGE
    override val title: String = "Version Control"
    override val iconId: String = "git"
    override val scope: SettingsScope = SettingsScope.APPLICATION
    override val order: Int = 85

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Text(
            key = "userName",
            title = "Name",
            description = "Recorded as the author on every commit you make.",
            placeholder = "Ada Lovelace",
        ),
        SettingControl.Text(
            key = "userEmail",
            title = "Email",
            description = "Recorded alongside your name. Use the address your host knows you by so commits " +
                "are attributed to your account.",
            placeholder = "ada@example.com",
        ),
        SettingControl.Text(
            key = "githubClientId",
            title = "GitHub OAuth client id",
            description = "Enables the browser sign-in flow. Leave blank to sign in with a personal access " +
                "token instead. Register an OAuth App under Developer settings > OAuth Apps, tick Enable " +
                "Device Flow, and paste its client id here. A GitHub App id will not work.",
            advanced = true,
        ),
    )
}
