package dev.ide.ui.ext

import dev.ide.ui.backend.UiActionPlaces

/**
 * The app's built-in UI-side actions, registered through [UiActionRegistry] the same way an in-UI plugin
 * would. Currently the "More" menu's secondary actions; the command palette's UI-navigation commands and the
 * top-bar's stateful buttons move here in later increments.
 */
object BuiltInUiActions {
    private var registered = false

    /** Idempotent: registers the built-ins once for the process. */
    fun ensureRegistered() {
        if (registered) return
        registered = true

        val more = setOf(UiActionPlaces.MORE_MENU)
        val palette = UiActionPlaces.COMMAND_PALETTE
        val moreAndPalette = setOf(UiActionPlaces.MORE_MENU, palette)

        UiActionRegistry.register(
            SimpleUiAction("ui.settings", "Open Settings", moreAndPalette, "Appearance · editor · completion · analysis · build", "gear", 10) {
                it.navigate(UiDestinations.SETTINGS)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.modules", "Modules", more, "Add/remove modules · Java version · dependencies · repositories", "layers", 20) {
                it.navigate(UiDestinations.MODULES)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.dependencies", "Manage dependencies", setOf(palette), iconId = "layers", order = 25) {
                it.navigate(UiDestinations.DEPENDENCIES)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.sdk", "SDK Manager", moreAndPalette, "Download Android SDK packages & JDK sources", "pkg", 30) {
                it.navigate(UiDestinations.SDK)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.keystores", "Keystore Manager", moreAndPalette, "Create/import signing keystores & assign them to builds", "key", 32) {
                it.navigate(UiDestinations.KEYSTORES)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.reindex", "Re-index project", more, "Rebuild symbol & completion indexes", "refresh", 40) {
                it.backend.search.reindex()
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.logs", "View logs", more, "Editor, analysis & build logs — share when something's off", "terminal", 50) {
                it.navigate(UiDestinations.LOGS)
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.toggleTheme", "Toggle theme", moreAndPalette, "Switch between light and dark", "eye", 60) {
                it.toggleTheme()
            },
        )
        UiActionRegistry.register(
            SimpleUiAction("ui.closeProject", "Close project", more, "Back to all projects", "close", 70) {
                it.navigate(UiDestinations.PROJECTS)
            },
        )
    }
}
