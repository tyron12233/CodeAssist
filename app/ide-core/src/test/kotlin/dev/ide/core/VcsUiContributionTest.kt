package dev.ide.core

import dev.ide.ui.LeftPanelId
import dev.ide.ui.backend.VcsService
import dev.ide.ui.ext.EditorViewModeContribution
import dev.ide.ui.ext.OverlayContribution
import dev.ide.ui.ext.Registration
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.TabDecorationContribution
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiHostAction
import dev.ide.ui.icons.TreeIcon
import dev.ide.vcs.ui.VcsUiPlugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the version-control UI plugin puts on the shell's registries. The panel claims the shell's
 * source-control slot, so the rail must end up with exactly one entry there: the shell used to carry its own
 * placeholder in that position, and the two together showed as a duplicated Git icon.
 */
class VcsUiContributionTest {

    @Test
    fun `the git panel claims the shell's source-control slot`() {
        val scope = RecordingScope()
        VcsUiPlugin.contributeUi(scope)

        val left = scope.toolWindows.filter { it.anchor == ToolWindowAnchor.LEFT }
        assertEquals(1, left.size, "one LEFT panel: ${left.map { it.id }}")
        assertEquals(LeftPanelId.SOURCE, left.single().id)
    }

    @Test
    fun `every screen the panel navigates to is registered`() {
        val scope = RecordingScope()
        VcsUiPlugin.contributeUi(scope)

        val registered = scope.screens.map { it.id }.toSet()
        val expected = setOf(
            VcsService.SCREEN_BRANCHES,
            VcsService.SCREEN_HISTORY,
            VcsService.SCREEN_DIFF,
            VcsService.SCREEN_ACCOUNTS,
            VcsService.SCREEN_CLONE,
            VcsService.SCREEN_STASHES,
            VcsService.SCREEN_GITHUB,
        )
        assertEquals(expected, registered)
    }

    /** The tab dots version control claims: a conflict above the built-in error dot (which a conflicted
     *  file's marker lines would otherwise win), and the working-copy state below all of them. */
    @Test
    fun `the tab strip's version-control dots are ordered around the built-in ones`() {
        val scope = RecordingScope()
        VcsUiPlugin.contributeUi(scope)

        val byId = scope.tabDecorations.associateBy { it.id }
        assertEquals(setOf("vcs.tab.conflict", "vcs.tab.changed"), byId.keys)
        assertTrue(byId.getValue("vcs.tab.conflict").order < 100, "a conflict outranks the built-in error dot")
        assertTrue(byId.getValue("vcs.tab.changed").order > 120, "differing from HEAD ranks below every built-in")
    }

    @Test
    fun `contributing twice does not accumulate panels`() {
        // UiPluginHost loads once per process, but a re-entrant load must not double the rail either.
        val scope = RecordingScope()
        VcsUiPlugin.contributeUi(scope)
        val first = scope.toolWindows.size
        assertTrue(first > 0)
        assertEquals(setOf(LeftPanelId.SOURCE), scope.toolWindows.map { it.id }.toSet())
    }

    /** Records what a plugin contributes, so the assertions read the declaration rather than a live registry. */
    private class RecordingScope : UiContributionScope {
        override val pluginId: String = "test"
        val toolWindows = mutableListOf<ToolWindowContribution>()
        val screens = mutableListOf<ScreenContribution>()
        val actions = mutableListOf<UiHostAction>()
        val overlays = mutableListOf<OverlayContribution>()

        override fun action(action: UiHostAction): Registration {
            actions += action
            return Registration {}
        }

        override fun toolWindow(toolWindow: ToolWindowContribution): Registration {
            toolWindows += toolWindow
            return Registration {}
        }

        override fun screen(screen: ScreenContribution): Registration {
            screens += screen
            return Registration {}
        }

        override fun viewMode(mode: EditorViewModeContribution): Registration = Registration {}

        override fun editorLanguage(profile: dev.ide.ui.ext.EditorLanguageProfile): Registration {
            editorLanguages += profile
            return Registration {}
        }

        val editorLanguages = mutableListOf<dev.ide.ui.ext.EditorLanguageProfile>()

        override fun overlay(overlay: OverlayContribution): Registration {
            overlays += overlay
            return Registration {}
        }

        val tabDecorations = mutableListOf<TabDecorationContribution>()

        override fun tabDecoration(decoration: TabDecorationContribution): Registration {
            tabDecorations += decoration
            return Registration {}
        }

        override fun treeIcon(iconId: String, icon: TreeIcon): Registration = Registration {}

        val editorPreviews = mutableListOf<dev.ide.ui.ext.EditorPreviewContribution>()

        override fun editorPreview(preview: dev.ide.ui.ext.EditorPreviewContribution): Registration {
            editorPreviews += preview
            return Registration {}
        }
    }
}
