package dev.ide.ui.ext

import dev.ide.ui.backend.UiActionPlaces

/**
 * The IDE's built-in UI contributions (the "More" menu + command-palette UI-navigation commands), contributed
 * through the [UiPlugin] model the same way an in-UI plugin would — the IDE dogfooding its own UI-contribution
 * API. Loaded once per process by [UiPluginHost].
 */
object BuiltInUiPlugin : UiPlugin {
    override val id = "ide-ui"

    override fun contributeUi(scope: UiContributionScope) {
        val more = setOf(UiActionPlaces.MORE_MENU)
        val palette = UiActionPlaces.COMMAND_PALETTE
        val moreAndPalette = setOf(UiActionPlaces.MORE_MENU, palette)

        scope.action(
            SimpleUiAction(
                "ui.hub",
                "Settings & Tools",
                moreAndPalette,
                "Settings · code style · SDK manager · keystore manager",
                "gear",
                10
            ) {
                it.navigate(UiDestinations.HUB)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.modules",
                "Modules",
                more,
                "Add/remove modules · Java version · dependencies · repositories",
                "layers",
                20
            ) {
                it.navigate(UiDestinations.MODULES)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.dependencies",
                "Manage dependencies",
                setOf(palette),
                iconId = "layers",
                order = 25
            ) {
                it.navigate(UiDestinations.DEPENDENCIES)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.reindex",
                "Re-index project",
                more,
                "Rebuild symbol & completion indexes",
                "refresh",
                40
            ) {
                it.backend.search.reindex()
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.logs",
                "View logs",
                more,
                "Editor, analysis & build logs — share when something's off",
                "terminal",
                50
            ) {
                it.navigate(UiDestinations.LOGS)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.toggleTheme",
                "Toggle theme",
                moreAndPalette,
                "Switch between light and dark",
                "eye",
                60
            ) {
                it.toggleTheme()
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.closeProject",
                "Close project",
                more,
                "Back to all projects",
                "close",
                70
            ) {
                it.navigate(UiDestinations.PROJECTS)
            },
        )
    }
}
