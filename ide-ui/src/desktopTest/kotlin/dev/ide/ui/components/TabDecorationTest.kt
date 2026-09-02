package dev.ide.ui.components

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.ui.OpenFile
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.ext.TabDecoration
import dev.ide.ui.ext.TabDecorationContext
import dev.ide.ui.ext.TabDecorationContribution
import dev.ide.ui.ext.TabDecorationRegistry
import dev.ide.ui.ext.TabDotStyle
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.icons.IconTint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The editor tab strip's decoration seam: what the built-in dots report for a tab, and how a contributed
 * producer interleaves with them. The producers are composable, so each case resolves inside a headless
 * composition (no UI surface, so no Skiko).
 */
class TabDecorationTest {

    /** A tab whose analysis pass left errors behind lights the dot red, described by its error count. */
    @Test
    fun builtInErrorDotLightsForErrors() {
        UiPluginHost.ensureLoaded()
        val tab = tab("App.kt", errors = 2, warnings = 1)

        val decoration = decorate(tab)

        assertSame(IconTint.Error, decoration?.tint, "an erroring tab is decorated with the theme's error tint")
        assertEquals("2 errors", decoration?.description)
        assertEquals(TabDotStyle.Filled, decoration?.style, "errors are urgent, so the dot is filled")
    }

    /** Warnings get the slot when nothing worse applies, as a ring: the filled amber means unsaved edits. */
    @Test
    fun warningsDrawAsARing() {
        UiPluginHost.ensureLoaded()

        val decoration = decorate(tab("Warned.kt", errors = 0, warnings = 3))

        assertSame(IconTint.Warning, decoration?.tint)
        assertEquals("3 warnings", decoration?.description)
        assertEquals(TabDotStyle.Outlined, decoration?.style)
    }

    @Test
    fun cleanTabIsLeftUndecorated() {
        UiPluginHost.ensureLoaded()
        assertNull(decorate(tab("Clean.kt", errors = 0, warnings = 0)))
    }

    /** A file that changed underneath unsaved edits outranks that file's own errors: it is the fact the user
     *  has to act on, and the host cannot reload the tab without losing their work. */
    @Test
    fun changedOnDiskOutranksErrors() {
        UiPluginHost.ensureLoaded()
        val tab = tab("Diverged.kt", errors = 1, warnings = 0).also { it.staleOnDisk = true }

        val decoration = decorate(tab)

        assertSame(IconTint.Info, decoration?.tint)
        assertEquals("changed on disk since you edited it", decoration?.description)
    }

    /** What the build reported about a file decorates its tab too, for the failures analysis never sees. */
    @Test
    fun buildErrorsDecorateTheirOwnFile() {
        UiPluginHost.ensureLoaded()
        val backend = BuildFailureBackend(
            listOf(
                BuildDiagnosticUi(UiSeverity.Error, "resource linking failed", file = "/p/Broken.kt"),
                BuildDiagnosticUi(UiSeverity.Warning, "deprecated", file = "/p/Other.kt"),
            ),
        )

        val broken = decorate(tab("Broken.kt", errors = 0, warnings = 0), backend = backend)
        val other = decorate(tab("Other.kt", errors = 0, warnings = 0), backend = backend)

        assertSame(IconTint.Error, broken?.tint)
        assertEquals("1 build error", broken?.description)
        assertNull(other, "a build warning on another file leaves this tab alone")
    }

    /** One tab has one dot: the lowest-ordered producer that returns a decoration claims it. */
    @Test
    fun lowestOrderProducerClaimsTheDot() {
        UiPluginHost.ensureLoaded()
        val reg = TabDecorationRegistry.register(
            TabDecorationContribution("test.pinned", order = 10) { TabDecoration(IconTint.Accent, "pinned") },
        )
        try {
            val decoration = decorate(tab("App.kt", errors = 1, warnings = 0))
            assertSame(IconTint.Accent, decoration?.tint, "order 10 outranks the built-in error dot at 100")
        } finally {
            reg.dispose()
        }
    }

    /** A producer that declines leaves the tab to the next one rather than blanking it. */
    @Test
    fun decliningProducerFallsThroughToTheNext() {
        UiPluginHost.ensureLoaded()
        val reg = TabDecorationRegistry.register(
            TabDecorationContribution("test.declines", order = 10) { null },
        )
        try {
            assertSame(IconTint.Error, decorate(tab("App.kt", errors = 1, warnings = 0))?.tint)
        } finally {
            reg.dispose()
        }
    }

    /** The context reports the tab's own state, so a producer can decide per tab rather than per project. */
    @Test
    fun contextReportsTheTabsState() {
        val tab = tab("App.kt", errors = 1, warnings = 2)
        var seen: TabDecorationContext? = null
        val reg = TabDecorationRegistry.register(
            TabDecorationContribution("test.capture", order = 1) { seen = it; null },
        )
        try {
            decorate(tab)
            assertEquals("/p/App.kt", seen?.path)
            assertEquals("App.kt", seen?.name)
            assertEquals(1, seen?.errorCount)
            assertEquals(2, seen?.warningCount)
            assertEquals(3, seen?.diagnostics?.size)
            assertEquals(false, seen?.modified, "a freshly opened tab has no unsaved edits")
            assertEquals(false, seen?.staleOnDisk)
            assertEquals(true, seen?.active)
        } finally {
            reg.dispose()
        }
    }

    // --- helpers ---

    /** A [StubBackend] whose last build reported [diagnostics]. */
    private class BuildFailureBackend(diagnostics: List<BuildDiagnosticUi>) : StubBackend() {
        override val buildState: StateFlow<BuildState> = MutableStateFlow(BuildState(diagnostics = diagnostics))
    }

    private fun tab(name: String, errors: Int, warnings: Int): OpenFile =
        OpenFile("/p/$name", name, "class A").also { file ->
            file.session.applyAnalysis(
                List(errors) { diagnostic(UiSeverity.Error, it) } +
                        List(warnings) { diagnostic(UiSeverity.Warning, it) },
            )
        }

    private fun diagnostic(severity: UiSeverity, index: Int) =
        UiDiagnostic(severity, line = index, col = 0, message = "$severity $index", startOffset = 0, endOffset = 1)

    private fun decorate(
        file: OpenFile,
        active: Boolean = true,
        backend: StubBackend = StubBackend(),
    ): TabDecoration? {
        var decoration: TabDecoration? = null
        composeOnce {
            decoration = TabDecorationRegistry.decorationFor(OpenTabDecorationContext(file, active, backend))
        }
        return decoration
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
