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

    // ---- the two Compose-bearing editor surfaces ----

    /** An anchored-widget layer crosses with its id, order, predicate, and each widget's anchor mapped. */
    @Test
    fun editorLayerWidgetsAndAnchorsAreCarriedOver() {
        val facet = facet { ui ->
            ui.editorLayer(
                dev.ide.plugin.ui.EditorLayer(
                    id = "com.example.lens",
                    order = 42,
                    appliesTo = { it.endsWith(".kt") },
                ) {
                    listOf(
                        dev.ide.plugin.ui.EditorWidget(
                            dev.ide.plugin.ui.EditorAnchor.AboveLine(7), key = "lens-7",
                        ) {},
                        dev.ide.plugin.ui.EditorWidget(
                            dev.ide.plugin.ui.EditorAnchor.AtOffset(120), key = "mark-120",
                        ) {},
                        dev.ide.plugin.ui.EditorWidget(
                            dev.ide.plugin.ui.EditorAnchor.AfterLine(3), key = "note-3",
                        ) {},
                    )
                },
            )
        }
        val scope = RecordingScope("com.example.x")
        facet.asUiPlugin("com.example.x").contributeUi(scope)

        val layer = scope.editorLayers.single()
        assertEquals("com.example.lens", layer.id)
        assertEquals(42, layer.order)
        assertTrue(layer.appliesTo("/p/App.kt"))
        assertTrue(!layer.appliesTo("/p/App.java"))

        var widgets: List<EditorWidget> = emptyList()
        composeOnce { widgets = layer.widgets(FakeLayerContext()) }

        assertEquals(listOf("lens-7", "mark-120", "note-3"), widgets.map { it.key })
        assertEquals(7, (widgets[0].anchor as EditorAnchor.AboveLine).line)
        assertEquals(120, (widgets[1].anchor as EditorAnchor.AtOffset).offset)
        assertEquals(3, (widgets[2].anchor as EditorAnchor.AfterLine).line)
    }

    /** What a layer's producer sees: the live buffer, the viewport, the caret, and the file as the active one. */
    @Test
    fun theLayerProducerSeesTheLiveBufferAndTheViewport() {
        var seen: dev.ide.plugin.ui.EditorLayerContext? = null
        val facet = facet { ui ->
            ui.editorLayer(dev.ide.plugin.ui.EditorLayer("id") { ctx -> seen = ctx; emptyList() })
        }
        val scope = RecordingScope("com.example.x")
        facet.asUiPlugin("com.example.x").contributeUi(scope)

        composeOnce { scope.editorLayers.single().widgets(FakeLayerContext()) }

        val ctx = requireNotNull(seen)
        assertEquals("/stub/src/App.kt", ctx.path)
        assertEquals("/stub/src/App.kt", ctx.activeFilePath, "the decorated file is the active one")
        assertEquals("half-typed", ctx.text)
        assertEquals(10..20, ctx.visibleLines)
        assertEquals(4, ctx.caretOffset)
        assertEquals("/stub", ctx.projectPath)
    }

    /** A painter crosses with its depth mapped, and its body receives the host's geometry. */
    @Test
    fun editorPainterDeclarationAndGeometryAreCarriedOver() {
        var seenTextLeft = -1f
        var seenLineTop = -1f
        val facet = facet { ui ->
            ui.editorPainter(
                dev.ide.plugin.ui.EditorPainter(
                    id = "com.example.blame",
                    order = 5,
                    layer = dev.ide.plugin.ui.EditorPaintLayer.AboveText,
                    appliesTo = { it.endsWith(".kt") },
                ) { ctx ->
                    seenTextLeft = ctx.textLeft
                    seenLineTop = ctx.lineTop(2)
                },
            )
        }
        val scope = RecordingScope("com.example.x")
        facet.asUiPlugin("com.example.x").contributeUi(scope)

        val painter = scope.editorPainters.single()
        assertEquals("com.example.blame", painter.id)
        assertEquals(5, painter.order)
        assertEquals(EditorPaintLayer.AboveText, painter.layer, "the published depth maps to the internal one")
        assertTrue(painter.appliesTo("/p/App.kt"))

        // The paint body runs against a DrawScope, so drive it the way the editor's draw does.
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(8, 8)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(1f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(bitmap),
            androidx.compose.ui.geometry.Size(8f, 8f),
        ) {
            painter.paint(this, FakePaintContext())
        }

        assertEquals(48f, seenTextLeft)
        assertEquals(46f, seenLineTop, "geometry comes from the host, not recomputed by the plugin")
    }

    /** A contributed view mode crosses whole, including the claim that makes a file open into it. */
    @Test
    fun viewModeDeclarationAndItsClaimAreCarriedOver() {
        val facet = facet { ui ->
            ui.viewMode(
                dev.ide.plugin.ui.EditorViewMode(
                    id = "com.example.scene",
                    label = "Scene",
                    iconId = "layers",
                    order = 7,
                    appliesTo = { it.endsWith(".scene") },
                    isDefault = { it.endsWith(".scene") },
                ) { },
            )
        }
        val scope = RecordingScope("com.example.x")

        facet.asUiPlugin("com.example.x").contributeUi(scope)

        val mode = scope.viewModes.single()
        assertEquals("com.example.scene", mode.id)
        assertEquals("Scene", mode.label)
        assertEquals("layers", mode.iconId)
        assertEquals(7, mode.order)
        assertTrue(mode.appliesTo("/p/Level.scene"))
        assertTrue(!mode.appliesTo("/p/App.kt"))
        assertTrue(mode.isDefault("/p/Level.scene"), "the file kind claim survives the crossing")
        assertTrue(!mode.isDefault("/p/App.kt"))
    }

    /** What a pane sees, and that its edits go back into the tab's one shared buffer. */
    @Test
    fun theViewModeBodySeesTheLiveBufferAndWritesThroughToIt() {
        var seen: dev.ide.plugin.ui.EditorViewModeContext? = null
        val facet = facet { ui ->
            ui.viewMode(dev.ide.plugin.ui.EditorViewMode("id", "Scene") { ctx -> seen = ctx })
        }
        val scope = RecordingScope("com.example.x")
        facet.asUiPlugin("com.example.x").contributeUi(scope)
        val host = FakeViewModeContext()

        composeOnce { scope.viewModes.single().content(host) }

        val ctx = requireNotNull(seen)
        assertEquals("/stub/src/Level.scene", ctx.path)
        assertEquals("/stub/src/Level.scene", ctx.activeFilePath, "the pane's file is the active one")
        assertEquals("{\"x\":1}", ctx.text)
        assertEquals(3, ctx.caretOffset)
        assertEquals("/stub", ctx.projectPath)

        ctx.replaceText(1, 4, "\"y\"")
        assertEquals(listOf(Triple(1, 4, "\"y\"")), host.edits, "an edit lands on the tab's shared buffer")
    }

    /** A preview pane's declaration, including the predicate that decides which files it claims. */
    @Test
    fun editorPreviewDeclarationIsCarriedOver() {
        val facet = facet { ui ->
            ui.editorPreview(
                dev.ide.plugin.ui.EditorPreview(
                    id = "com.example.scene",
                    title = "Scene",
                    appliesTo = { it.endsWith(".scene.kt") },
                ) { },
            )
        }
        val scope = RecordingScope("com.example.x")

        facet.asUiPlugin("com.example.x").contributeUi(scope)

        val contributed = scope.editorPreviews.single()
        assertEquals("com.example.scene", contributed.id)
        assertEquals("Scene", contributed.title)
        assertTrue(contributed.appliesTo("/p/Level.scene.kt"))
        assertTrue(!contributed.appliesTo("/p/Level.kt"))
    }

    /**
     * What a preview body sees: the file, the LIVE buffer rather than the file on disk, and a way to report
     * problems back into the host's chip.
     */
    @Test
    fun thePreviewBodySeesTheLiveBufferAndCanReportProblems() {
        var seen: dev.ide.plugin.ui.EditorPreviewContext? = null
        val facet = facet { ui ->
            ui.editorPreview(
                dev.ide.plugin.ui.EditorPreview("id", "Scene", { true }) { ctx -> seen = ctx },
            )
        }
        val scope = RecordingScope("com.example.x")
        facet.asUiPlugin("com.example.x").contributeUi(scope)
        val host = FakePreviewContext(path = "/stub/src/Level.scene.kt", text = "half-typed", dark = true)

        composeOnce { scope.editorPreviews.single().content(host) }

        val ctx = requireNotNull(seen)
        assertEquals("/stub/src/Level.scene.kt", ctx.path)
        assertEquals("/stub/src/Level.scene.kt", ctx.activeFilePath, "the previewed file is the active one")
        assertEquals("half-typed", ctx.text)
        assertTrue(ctx.dark)
        assertEquals("/stub", ctx.projectPath)

        ctx.reportProblems(listOf("no scene root"))
        assertEquals(listOf("no scene root"), host.reported)
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

    private class FakeViewModeContext : ViewModeContext {
        val edits = mutableListOf<Triple<Int, Int, String>>()
        override val backend: IdeBackend = StubBackend()
        override val filePath = "/stub/src/Level.scene"
        override val text = "{\"x\":1}"
        override val caretOffset = 3
        override fun replaceText(start: Int, end: Int, newText: String) {
            edits += Triple(start, end, newText)
        }
    }

    private class FakeLayerContext : EditorLayerContext {
        override val path = "/stub/src/App.kt"
        override val text = "half-typed"
        override val visibleLines = 10..20
        override val caretOffset = 4
        override val backend: IdeBackend = StubBackend()
    }

    private class FakePaintContext : EditorPaintContext {
        override val path = "/stub/src/App.kt"
        override val visibleLines = 0..9
        override val lineCount = 10
        override val lineHeight = 20f
        override val charWidth = 8f
        override val gutterWidth = 40f
        override val textLeft = 48f
        override fun lineTop(line: Int) = 6f + line * 20f
        override fun xOf(offset: Int) = 48f + offset * 8f
        override fun lineOf(offset: Int) = offset / 10
        override fun lineRange(line: Int) = (line * 10)..(line * 10 + 9)
        override fun isHidden(line: Int) = false
    }

    private class FakePreviewContext(
        override val path: String,
        override val text: String,
        override val dark: Boolean = false,
        override val backend: IdeBackend = StubBackend(),
    ) : EditorPreviewContext {
        var reported: List<String> = emptyList()
        override fun reportProblems(problems: List<String>) { reported = problems }
    }

    /** A [UiContributionScope] that records what the bridge hands it. */
    private class RecordingScope(override val pluginId: String) : UiContributionScope {
        val toolWindows = mutableListOf<ToolWindowContribution>()
        val screens = mutableListOf<ScreenContribution>()
        val overlays = mutableListOf<OverlayContribution>()
        val editorPreviews = mutableListOf<EditorPreviewContribution>()
        val viewModes = mutableListOf<EditorViewModeContribution>()
        val editorLayers = mutableListOf<EditorLayerContribution>()
        val editorPainters = mutableListOf<EditorPainterContribution>()
        var disposed = 0
            private set

        private fun handle() = Registration { disposed++ }

        override fun action(action: UiHostAction) = handle()
        override fun toolWindow(toolWindow: ToolWindowContribution) = handle().also { toolWindows += toolWindow }
        override fun screen(screen: ScreenContribution) = handle().also { screens += screen }
        override fun viewMode(mode: EditorViewModeContribution) = handle().also { viewModes += mode }
        override fun overlay(overlay: OverlayContribution) = handle().also { overlays += overlay }
        override fun tabDecoration(decoration: TabDecorationContribution) = handle()
        override fun treeIcon(iconId: String, icon: TreeIcon) = handle()
        override fun editorLanguage(profile: EditorLanguageProfile) = handle()
        override fun editorPreview(preview: EditorPreviewContribution) =
            handle().also { editorPreviews += preview }

        override fun editorLayer(layer: EditorLayerContribution) =
            handle().also { editorLayers += layer }

        override fun editorPainter(painter: EditorPainterContribution) =
            handle().also { editorPainters += painter }
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
