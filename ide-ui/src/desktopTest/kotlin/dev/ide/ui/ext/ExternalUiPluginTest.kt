package dev.ide.ui.ext

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.plugin.ui.Overlay
import dev.ide.plugin.ui.Screen
import dev.ide.plugin.ui.ToolWindow
import dev.ide.plugin.ui.UiContext
import dev.ide.plugin.ui.UiHandle
import dev.ide.plugin.ui.UiRegistration
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.icons.TreeIcon
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.ide.plugin.ui.UiPlugin as ExternalUiPlugin

/**
 * The bridge from an installed plugin's published UI facet (`plugin-ui-api`) onto the host's contribution
 * model: what the host ends up with for each contribution, and what the plugin's body sees when it renders.
 *
 * The bodies are composable, so each case resolves inside a headless composition (no UI surface, so no
 * Skiko). This is the half of the installed-UI path that needs the Compose compiler, which is why it lives
 * here rather than beside the loader tests in :ide-core.
 */
class ExternalUiPluginTest {

    /** A tool window's declaration survives the crossing, anchor and order included. */
    @Test
    fun toolWindowDeclarationIsCarriedOver() {
        val facet = facet { ui ->
            ui.toolWindow(
                ToolWindow(
                    id = "com.example.panel",
                    title = "Panel",
                    iconId = "sparkle",
                    anchor = dev.ide.plugin.ui.ToolWindowAnchor.BOTTOM,
                    order = 5,
                ) { },
            )
        }
        val scope = RecordingScope("com.example.x")

        facet.asUiPlugin("com.example.x").contributeUi(scope)

        val contributed = scope.toolWindows.single()
        assertEquals("com.example.panel", contributed.id, "ids pass through verbatim, un-namespaced")
        assertEquals("Panel", contributed.title)
        assertEquals("sparkle", contributed.iconId)
        assertEquals(5, contributed.order)
        assertEquals(ToolWindowAnchor.BOTTOM, contributed.anchor)
    }

    /** The host's manifest decides the attribution id, not what the facet says about itself. */
    @Test
    fun theHostsIdIsWhatTheFacetIsRegisteredUnder() {
        val facet = facet { ui -> registeredFor = ui.pluginId }
        val scope = RecordingScope("com.example.host")

        val bridged = facet.asUiPlugin("com.example.host")

        assertEquals("com.example.host", bridged.id)
        bridged.contributeUi(scope)
        assertEquals("com.example.host", registeredFor, "the facet's self-declared id must not be used")
    }

    /** Screens and overlays cross too, so a plugin's command can navigate to its own screen by id. */
    @Test
    fun screensAndOverlaysAreCarriedOver() {
        val facet = facet { ui ->
            ui.screen(Screen(id = "com.example.screen", title = "Details") { })
            ui.overlay(Overlay(id = "com.example.overlay") { })
        }
        val scope = RecordingScope("com.example.x")

        facet.asUiPlugin("com.example.x").contributeUi(scope)

        assertEquals("com.example.screen", scope.screens.single().id)
        assertEquals("Details", scope.screens.single().title)
        assertEquals("com.example.overlay", scope.overlays.single().id)
    }

    /** Disposing the handle the plugin was given removes the host-side registration. */
    @Test
    fun disposingTheHandleRemovesTheContribution() {
        var handle: UiHandle? = null
        val facet = facet { ui ->
            handle = ui.toolWindow(ToolWindow("id", "Title", "sparkle", dev.ide.plugin.ui.ToolWindowAnchor.LEFT) { })
        }
        val scope = RecordingScope("com.example.x")

        facet.asUiPlugin("com.example.x").contributeUi(scope)
        assertEquals(0, scope.disposed)

        handle!!.dispose()

        assertEquals(1, scope.disposed, "the plugin's handle must dispose the host's registration")
    }

    /**
     * What a panel body sees: the narrow context, filled from the host's own. The published surface carries
     * no `IdeBackend`, so this is the whole of what a plugin can read about where the user is.
     */
    @Test
    fun theBodySeesTheHostsStateThroughTheNarrowContext() {
        var seen: UiContext? = null
        val facet = facet { ui ->
            ui.toolWindow(ToolWindow("id", "Title", "sparkle", dev.ide.plugin.ui.ToolWindowAnchor.LEFT) { ctx ->
                seen = ctx
            })
        }
        val scope = RecordingScope("com.example.x")
        facet.asUiPlugin("com.example.x").contributeUi(scope)
        val host = FakeToolWindowContext(activeFilePath = "/stub/src/App.kt")

        composeOnce { scope.toolWindows.single().content(host) }

        val ctx = requireNotNull(seen)
        assertEquals("/stub/src/App.kt", ctx.activeFilePath)
        assertEquals("/stub", ctx.projectPath, "the open project's root, read off the host's backend")

        // The two operations a panel cannot do for itself land on the host.
        ctx.openFile("/stub/src/Other.kt", 42)
        ctx.openScreen("com.example.screen")
        assertEquals("/stub/src/Other.kt" to 42, host.opened)
        assertEquals("com.example.screen", host.navigatedTo)
    }

    /** An overlay is app-wide, so it is told about no file rather than about the wrong one. */
    @Test
    fun anOverlaySeesNoActiveFile() {
        var seen: UiContext? = null
        val facet = facet { ui -> ui.overlay(Overlay("id") { ctx -> seen = ctx }) }
        val scope = RecordingScope("com.example.x")
        facet.asUiPlugin("com.example.x").contributeUi(scope)

        composeOnce { scope.overlays.single().content(FakeOverlayContext()) }

        assertNull(requireNotNull(seen).activeFilePath)
        assertEquals("/stub", seen?.projectPath)
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private var registeredFor: String? = null

    /** An installed plugin's UI facet, defined by what it registers. */
    private fun facet(contribute: (UiRegistration) -> Unit): ExternalUiPlugin = object : ExternalUiPlugin {
        // Deliberately not the id the host is told: the tests check which one wins.
        override val id = "com.example.self-declared"
        override fun contribute(ui: UiRegistration) = contribute(ui)
    }

    private class FakeToolWindowContext(
        override val activeFilePath: String?,
        override val backend: IdeBackend = StubBackend(),
    ) : ToolWindowContext {
        var opened: Pair<String, Int>? = null
        var navigatedTo: String? = null
        override fun openFile(path: String, offset: Int) { opened = path to offset }
        override fun openScreen(id: String) { navigatedTo = id }
    }

    private class FakeOverlayContext(override val backend: IdeBackend = StubBackend()) : OverlayContext

    /** A [UiContributionScope] that records what the bridge hands it. */
    private class RecordingScope(override val pluginId: String) : UiContributionScope {
        val toolWindows = mutableListOf<ToolWindowContribution>()
        val screens = mutableListOf<ScreenContribution>()
        val overlays = mutableListOf<OverlayContribution>()
        var disposed = 0
            private set

        private fun handle() = Registration { disposed++ }

        override fun action(action: UiHostAction) = handle()
        override fun toolWindow(toolWindow: ToolWindowContribution) = handle().also { toolWindows += toolWindow }
        override fun screen(screen: ScreenContribution) = handle().also { screens += screen }
        override fun viewMode(mode: EditorViewModeContribution) = handle()
        override fun overlay(overlay: OverlayContribution) = handle().also { overlays += overlay }
        override fun tabDecoration(decoration: TabDecorationContribution) = handle()
        override fun treeIcon(iconId: String, icon: TreeIcon) = handle()
        override fun editorLanguage(profile: EditorLanguageProfile) = handle()
    }

    // --- headless composition harness (no UI) ---

    private val recomposers = ArrayList<Recomposer>()

    @AfterTest fun tearDown() {
        recomposers.forEach { it.cancel() }
    }

    private fun composeOnce(content: @Composable () -> Unit) {
        val recomposer = Recomposer(CoroutineScope(BroadcastFrameClock()).coroutineContext)
        recomposers += recomposer
        val composition = Composition(UnitApplier, recomposer)
        composition.setContent(content)
        composition.dispose()
    }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }
}
