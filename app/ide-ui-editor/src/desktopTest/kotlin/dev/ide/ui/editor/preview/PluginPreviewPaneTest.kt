package dev.ide.ui.editor.preview

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.ui.StubBackend
import dev.ide.ui.ext.EditorPreviewContext
import kotlinx.coroutines.CoroutineScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the host hands a plugin-contributed preview body, and where its calls land.
 *
 * The pane is thin, but everything in it is a place a wire can be crossed: the body must see the live buffer
 * rather than the file, and `openFile`/`openScreen` must reach the host's handlers rather than the members
 * they back (an unqualified call inside the override would resolve to the override and recurse).
 */
class PluginPreviewPaneTest {

    @Test
    fun theBodySeesTheLiveBufferAndItsNavigationReachesTheHost() {
        var seen: EditorPreviewContext? = null
        var opened: Pair<String, Int>? = null
        var navigated: String? = null
        var reported: List<String>? = null

        composeOnce {
            seen = rememberPreviewContext(
                id = "test.scene",
                path = "/p/Level.scene.kt",
                text = "half-typed",
                backend = StubBackend(),
                dark = true,
                onOpenFile = { path, offset -> opened = path to offset },
                onOpenScreen = { navigated = it },
                onReport = { reported = it },
            )
        }

        val ctx = requireNotNull(seen)
        assertEquals("/p/Level.scene.kt", ctx.path)
        assertEquals("half-typed", ctx.text, "the pane follows the buffer, not the file on disk")
        assertEquals(true, ctx.dark, "the surface's scheme reaches the body")

        // Each of these would recurse into itself if the override called the member it backs.
        ctx.openFile("/p/Other.kt", 12)
        ctx.openScreen("test.screen")
        ctx.reportProblems(listOf("no scene root"))
        assertEquals("/p/Other.kt" to 12, opened)
        assertEquals("test.screen", navigated)
        assertEquals(listOf("no scene root"), reported)
    }

    // --- headless composition harness (no UI surface, so no Skiko) ---

    private val recomposers = ArrayList<Recomposer>()

    @AfterTest
    fun tearDown() {
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
