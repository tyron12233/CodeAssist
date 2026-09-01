package dev.ide.vcs.ui

import dev.ide.ui.LeftPanelId
import dev.ide.ui.backend.VcsService
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin

/**
 * Version control's Compose UI, as one self-contained plugin: the Git tool window plus the full screens its
 * deeper flows need (branches, history, a diff, sign-in, clone, and GitHub).
 *
 * Co-declared with its engine facet `VcsPlugin` as one `BuiltInPlugin` in ide-core's `BuiltInPlugins`, so a
 * single enable/disable decision governs both halves. The panel is the entry point; everything reached from
 * it is a screen rather than a nested sheet, because a sidebar panel is far too narrow for a branch list, a
 * commit history, or a diff on a phone.
 */
object VcsUiPlugin : UiPlugin {
    override val id: String = "vcs-ui"

    override fun contributeUi(scope: UiContributionScope) {
        scope.toolWindow(
            ToolWindowContribution(
                // The shell's source-control slot: registering under this id puts the panel in the rail
                // position the placeholder used to hold, and makes the phone bottom-nav slot that maps to it
                // open this panel. Nothing else claims the id.
                id = LeftPanelId.SOURCE,
                title = "Git",
                iconId = "git",
                anchor = ToolWindowAnchor.LEFT,
                order = 40,
                content = { ctx -> GitPanel(ctx) },
            ),
        )
        scope.screen(ScreenContribution(VcsService.SCREEN_BRANCHES, "Branches") { ctx -> BranchesScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_HISTORY, "History") { ctx -> HistoryScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_DIFF, "Diff") { ctx -> DiffScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_ACCOUNTS, "Accounts") { ctx -> AccountsScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_CLONE, "Clone") { ctx -> CloneScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_STASHES, "Stashes") { ctx -> StashesScreen(ctx) })
        scope.screen(ScreenContribution(VcsService.SCREEN_GITHUB, "GitHub") { ctx -> GitHubScreen(ctx) })
    }
}
