package dev.ide.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a file leaves its tab at the end of a strip that has long since run past its right edge, and its
 * row far down a tree the user last scrolled somewhere else. Both follow the editor instead: the tab strip
 * and the file tree scroll whatever just became active into view, and only as far as it takes, so selecting
 * something already on screen never shifts the list under the finger.
 */
class ActiveItemRevealTest {

    private var clock = 0L

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a newly opened tab is scrolled into view`() {
        val openFiles = mutableStateListOf<OpenFile>()
        repeat(2) { openFiles += OpenFile("/p/File$it.kt", "File$it.kt", "") }
        val active = mutableStateOf(0)
        val strip = LazyListState()

        scene(720, 120) {
            TabsStrip(openFiles, active.value, onSelect = { active.value = it }, onClose = {}, state = strip)
        }.use { scene ->
            pump(scene)
            assertFalse(strip.canScrollForward, "the fixture must start with every tab on screen")

            // Ten more files opened one after another, the last one active, exactly as opening a file does.
            repeat(10) { i ->
                openFiles += OpenFile("/p/More$i.kt", "More$i.kt", "")
                active.value = openFiles.lastIndex
                pump(scene)
            }

            assertTrue(strip.canScrollBackward, "the strip never scrolled, so the new tab is off its right edge")
            assertTrue(fullyVisible(strip, openFiles.lastIndex), "the newly opened tab is not on screen")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `selecting a tab that is already on screen leaves the strip put`() {
        val openFiles = mutableStateListOf<OpenFile>()
        repeat(14) { openFiles += OpenFile("/p/File$it.kt", "File$it.kt", "") }
        val active = mutableStateOf(13)
        val strip = LazyListState()

        scene(720, 120) {
            TabsStrip(openFiles, active.value, onSelect = { active.value = it }, onClose = {}, state = strip)
        }.use { scene ->
            pump(scene)
            val settled = strip.firstVisibleItemIndex to strip.firstVisibleItemScrollOffset
            // Fully visible, not merely in the measured window: revealing a tab clipped by an edge is
            // supposed to move the strip.
            val neighbour = strip.layoutInfo.visibleItemsInfo.map { it.index }.first { it != 13 && fullyVisible(strip, it) }

            active.value = neighbour
            pump(scene)

            assertEquals(
                settled,
                strip.firstVisibleItemIndex to strip.firstVisibleItemScrollOffset,
                "selecting a visible tab moved the strip",
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the file tree scrolls to the file the editor opened`() {
        val activePath = mutableStateOf<String?>(null)
        val tree = LazyListState()
        val root = sourceTree(60)

        scene(320, 420) {
            Box(Modifier.fillMaxSize()) {
                FileNavigator(root, moduleCount = 1, activePath = activePath.value, onOpen = {}, listState = tree)
            }
        }.use { scene ->
            pump(scene)
            assertEquals(0, tree.firstVisibleItemIndex, "the tree starts at its top")

            // The 50th file, well past the visible window: opened from a search hit or a jump, not from here.
            activePath.value = filePath(50)
            pump(scene)
            val revealed = tree.layoutInfo.visibleItemsInfo.map { it.index }
            assertTrue(tree.firstVisibleItemIndex > 0, "the tree never scrolled to the open file")
            assertTrue(FIRST_FILE_ROW + 50 in revealed, "the open file's row is not on screen (visible rows $revealed)")

            // A file already on screen: the tree stays exactly where the reveal left it.
            val settled = tree.firstVisibleItemIndex to tree.firstVisibleItemScrollOffset
            activePath.value = filePath(revealed.first { it > FIRST_FILE_ROW && fullyVisible(tree, it) } - FIRST_FILE_ROW)
            pump(scene)
            assertEquals(
                settled,
                tree.firstVisibleItemIndex to tree.firstVisibleItemScrollOffset,
                "opening a file whose row was already visible moved the tree",
            )
        }
    }

    /** A workspace holding one module and one source root of [files] files, all expanded by default. */
    private fun sourceTree(files: Int) = TreeNode(
        "root", "SampleApp", NodeKind.Workspace, null,
        children = listOf(
            TreeNode(
                "app", "app", NodeKind.Module, null, iconId = "module",
                children = listOf(
                    TreeNode(
                        "src", "src/main/java", NodeKind.SourceRoot, null, iconId = "sourceset.java",
                        children = List(files) { i ->
                            TreeNode("f$i", fileName(i), NodeKind.File, filePath(i), iconId = "kotlin")
                        },
                    ),
                ),
            ),
        ),
    )

    private fun fullyVisible(state: LazyListState, index: Int): Boolean {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
        return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun scene(width: Int, height: Int, content: @Composable () -> Unit) =
        ImageComposeScene(width = width, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }

    /** Publish whatever the test just wrote, then run the reveal animation out. */
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

    /** Zero-padded so the tree's name order is the numeric one, and row `FIRST_FILE_ROW + i` is file `i`. */
    private fun fileName(i: Int) = "File${i.toString().padStart(2, '0')}.kt"

    private fun filePath(i: Int) = "/src/${fileName(i)}"

    private companion object {
        /** Row 0 is the module, row 1 its source root, so the files start at row 2. */
        const val FIRST_FILE_ROW = 2
        const val FRAME_NS = 16_000_000L
    }
}
