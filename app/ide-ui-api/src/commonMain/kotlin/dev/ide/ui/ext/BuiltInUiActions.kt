package dev.ide.ui.ext

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.icons.IconTint

/**
 * The IDE's built-in UI contributions (the "More" menu + command-palette UI-navigation commands, and the
 * editor tab strip's status dots), contributed through the [UiPlugin] model the same way an in-UI plugin
 * would — the IDE dogfooding its own UI-contribution API. Loaded once per process by [UiPluginHost].
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
                "ui.icons",
                "Icon Manager",
                moreAndPalette,
                "Browse and import icons, and change the app icon",
                "image",
                22
            ) {
                it.navigate(UiDestinations.ICONS)
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

        contributeTabDecorations(scope)
    }

    /**
     * The status dots the IDE itself puts on editor tabs, contributed through the same seam a plugin uses.
     *
     * They are ordered by how much they want the user to act: a file that changed underneath an unsaved tab
     * first (that tab's edits and the file on disk have diverged), then the errors and warnings analysis
     * found, then what the last build reported. One tab shows one dot, so the first of these that applies
     * takes it.
     */
    private fun contributeTabDecorations(scope: UiContributionScope) {
        scope.tabDecoration(
            TabDecorationContribution("ui.tab.stale", order = 50) { tab ->
                if (tab.staleOnDisk) {
                    TabDecoration(IconTint.Info, "changed on disk since you edited it")
                } else {
                    null
                }
            },
        )
        // Errors are filled: the file does not compile. Warnings are a ring, both because they are not
        // urgent and because a filled amber dot is already spoken for by unsaved changes.
        scope.tabDecoration(
            TabDecorationContribution("ui.tab.problems", order = 100) { tab ->
                when {
                    tab.errorCount > 0 -> TabDecoration(IconTint.Error, count(tab.errorCount, "error"))
                    tab.warningCount > 0 ->
                        TabDecoration(IconTint.Warning, count(tab.warningCount, "warning"), TabDotStyle.Outlined)

                    else -> null
                }
            },
        )
        // What a build reported, for the errors analysis does not produce itself: a resource, dex, or
        // packaging failure, or a compiler error the editor's analyzer does not model. Ordered below live
        // analysis, which is the fresher account of the file.
        scope.tabDecoration(
            TabDecorationContribution("ui.tab.buildErrors", order = 120) { tab ->
                val build by tab.backend.build.buildState.collectAsState()
                val errors = remember(build.diagnostics, tab.path) {
                    build.diagnostics.count { it.severity == UiSeverity.Error && it.file == tab.path }
                }
                if (errors > 0) TabDecoration(IconTint.Error, count(errors, "build error")) else null
            },
        )
    }

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
}
