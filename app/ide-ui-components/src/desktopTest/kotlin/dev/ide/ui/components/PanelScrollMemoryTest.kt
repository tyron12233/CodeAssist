package dev.ide.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A sidebar panel is composed only while it is the selected one, and not at all while its pane (the desktop
 * dock, the phone push drawer) is closed, so its list is disposed along with the scroll offset the user left
 * it at. [PanelContent] plus the host's state holder is what carries that offset across the gap; these drive
 * the real [SidebarPane] through a collapse and back to check that it does.
 */
class PanelScrollMemoryTest {

    private var clock = 0L

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a collapsed pane reopens its panel scrolled where it was left`() {
        val selected = mutableStateOf<String?>(FILES)
        val scrollTo = mutableStateOf(-1)
        var listState: LazyListState? = null
        var disposals = 0

        val panels = listOf(
            SidebarPanel(FILES, "Files", CaIcons.docText, 10) {
                val rows = rememberLazyListState()
                listState = rows
                DisposableEffect(Unit) { onDispose { disposals++ } }
                // Scrolls on demand from the test, and only then: re-entering the composition runs this with
                // the key back at -1, so a restored offset is never overwritten by the scroll that set it up.
                LaunchedEffect(scrollTo.value) { if (scrollTo.value >= 0) rows.scrollToItem(scrollTo.value) }
                LazyColumn(Modifier.fillMaxSize(), state = rows) {
                    items(ROW_COUNT) { i -> Text("row $i", Modifier.height(22.dp)) }
                }
            },
        )

        scene(320, 420) { SidebarPane(panels, selected.value, RailSide.Left, paneWidth = 300.dp) }.use { scene ->
            pump(scene)
            scrollTo.value = 40
            pump(scene)
            val before = listState
            assertEquals(40, before?.firstVisibleItemIndex, "the panel never scrolled, so there is nothing to keep")
            scrollTo.value = -1
            pump(scene)

            selected.value = null
            pump(scene)
            assertTrue(disposals > 0, "the panel outlived the collapse, so this proves nothing about restoring it")

            selected.value = FILES
            pump(scene)

            val after = listState
            assertNotSame(before, after, "the panel was never rebuilt, so its state was never restored")
            assertEquals(40, after?.firstVisibleItemIndex, "the panel came back scrolled to the top")
        }
    }

    /** Every panel keeps its own offset: switching to another one and back is the same disposal. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `each panel keeps its own scroll position across a switch`() {
        val selected = mutableStateOf(FILES)
        val scrollTo = mutableStateOf(-1)
        val states = HashMap<String, LazyListState>()

        fun panel(id: String, title: String) = SidebarPanel(id, title, CaIcons.docText, 10) {
            val rows = rememberLazyListState()
            states[id] = rows
            LaunchedEffect(scrollTo.value) { if (scrollTo.value >= 0) rows.scrollToItem(scrollTo.value) }
            LazyColumn(Modifier.fillMaxSize(), state = rows) {
                items(ROW_COUNT) { i -> Text("$title $i", Modifier.height(22.dp)) }
            }
        }

        val panels = listOf(panel(FILES, "Files"), panel(SEARCH, "Search"))
        scene(320, 420) { SidebarPane(panels, selected.value, RailSide.Left, paneWidth = 300.dp) }.use { scene ->
            pump(scene)
            scrollTo.value = 30
            pump(scene)
            scrollTo.value = -1

            selected.value = SEARCH
            pump(scene)
            assertEquals(0, states[SEARCH]?.firstVisibleItemIndex, "a panel shown for the first time starts at its top")

            scrollTo.value = 12
            pump(scene)
            scrollTo.value = -1
            selected.value = FILES
            pump(scene)
            assertEquals(30, states[FILES]?.firstVisibleItemIndex, "Files lost the offset it was switched away from")

            selected.value = SEARCH
            pump(scene)
            assertEquals(12, states[SEARCH]?.firstVisibleItemIndex, "Search lost the offset it was switched away from")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun scene(width: Int, height: Int, content: @Composable () -> Unit) =
        ImageComposeScene(width = width, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }

    /** Publish whatever the test just wrote, then run frames until the pane's 260ms open/collapse is done. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun pump(scene: ImageComposeScene, frames: Int = 40) {
        Snapshot.sendApplyNotifications()
        repeat(frames) { clock += FRAME_NS; scene.render(clock) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private companion object {
        const val FILES = "files"
        const val SEARCH = "search"
        const val ROW_COUNT = 200
        const val FRAME_NS = 16_000_000L
    }
}
