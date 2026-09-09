package dev.ide.vcs.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ide.ui.LeftPanelId
import dev.ide.ui.backend.UiVcsChange
import dev.ide.ui.backend.VcsService
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.TabDecoration
import dev.ide.ui.ext.TabDecorationContext
import dev.ide.ui.ext.TabDecorationContribution
import dev.ide.ui.ext.TabDotStyle
import dev.ide.ui.icons.IconTint
import dev.ide.ui.theme.Ide
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
        contributeTabDecorations(scope)
    }

    /**
     * What version control has to say about an open tab, as status dots on the tab strip.
     *
     * A conflict outranks the built-in error dot (order 40 against 100): a conflicted file is full of marker
     * lines, so its errors are a consequence of the conflict and saying "merge conflict" is the more useful
     * of the two. Everything else ranks below the built-ins, since a file differing from HEAD is the normal
     * state of the file you are working on, not news.
     *
     * Both draw as rings rather than filled dots: they are standing properties of the file, and a filled
     * amber dot already means unsaved edits.
     */
    private fun contributeTabDecorations(scope: UiContributionScope) {
        scope.tabDecoration(
            TabDecorationContribution("vcs.tab.conflict", order = 40) { tab ->
                val paths = changedPaths(tab) { it.conflicted }
                if (tab.path in paths) {
                    TabDecoration(IconTint.Error, "merge conflict", TabDotStyle.Outlined)
                } else {
                    null
                }
            },
        )
        scope.tabDecoration(
            TabDecorationContribution("vcs.tab.changed", order = 200) { tab ->
                val untracked = changedPaths(tab) { it.status == UiVcsChange.STATUS_UNTRACKED }
                val changed = changedPaths(tab) { it.status != UiVcsChange.STATUS_UNTRACKED && !it.conflicted }
                when (tab.path) {
                    in untracked -> TabDecoration(
                        IconTint.Fixed(Ide.colors.gitUntracked), "not in version control", TabDotStyle.Outlined,
                    )

                    in changed -> TabDecoration(
                        IconTint.Fixed(Ide.colors.gitModified), "changed since HEAD", TabDotStyle.Outlined,
                    )

                    else -> null
                }
            },
        )
    }

    /**
     * The absolute paths of the working-copy changes matching [select].
     *
     * Changes are reported relative to the working-copy root, which can sit above the project root, so they
     * are joined onto that root rather than compared as they arrive. Recomputed only when the status changes,
     * not on every recomposition of the tab strip.
     */
    @Composable
    private fun changedPaths(
        tab: TabDecorationContext,
        select: (UiVcsChange) -> Boolean,
    ): Set<String> {
        val status by tab.backend.vcs.status.collectAsState()
        return remember(status) {
            if (!status.present || status.root.isEmpty()) {
                emptySet()
            } else {
                (status.staged + status.unstaged + status.conflicted)
                    .filter(select)
                    .mapTo(mutableSetOf()) { "${status.root}/${it.path}" }
            }
        }
    }
}
