package dev.ide.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.ui.HomeTab
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * The Explore feed keeps its place: across a switch to another home tab and back, and across the whole home
 * screen being replaced (opening a listing) and shown again, which is what AppNavGraph's holder does.
 */
class HomeTabStateTest {

    private var storeList: LazyListState? = null

    @Composable
    private fun Feed() {
        val state = rememberLazyListState()
        storeList = state
        LazyColumn(state = state) { items(100) { Text("Row $it") } }
    }

    @Composable
    private fun Home(tab: HomeTab) = HomeScreen(
        tab = tab,
        onSelectTab = {},
        projectsContent = { Text("Projects") },
        storeContent = { Feed() },
        learnContent = {},
        challengesContent = {},
    )

    @Test
    fun `switching to another tab and back keeps the feed's scroll position`() {
        var tab by mutableStateOf(HomeTab.Store)
        scene { Home(tab) }.use { scene ->
            settle(scene)
            val first = storeList!!
            first.requestScrollToItem(40)
            settle(scene)
            assertEquals(40, first.firstVisibleItemIndex)

            tab = HomeTab.Projects
            settle(scene)
            tab = HomeTab.Store
            settle(scene)
            assertNotSame(first, storeList, "the tab was really rebuilt")
            assertEquals(40, storeList!!.firstVisibleItemIndex)
        }
    }

    @Test
    fun `leaving the home screen for another screen and coming back keeps it too`() {
        var onHome by mutableStateOf(true)
        scene {
            val holder = rememberSaveableStateHolder()
            if (onHome) holder.SaveableStateProvider("home") { Home(HomeTab.Store) } else Text("A listing")
        }.use { scene ->
            settle(scene)
            storeList!!.requestScrollToItem(25)
            settle(scene)

            onHome = false
            settle(scene)
            onHome = true
            settle(scene)
            assertEquals(25, storeList!!.firstVisibleItemIndex)
        }
    }

    private var clock = 0L

    private fun scene(content: @Composable () -> Unit) =
        ImageComposeScene(width = 600, height = 1200, density = Density(2f)) { CodeAssistTheme(dark = true) { content() } }

    /** Render past the tab crossfade. */
    private fun settle(scene: ImageComposeScene) {
        repeat(40) { clock += 16_666_667L; scene.render(clock) }
    }

    private inline fun <T> ImageComposeScene.use(block: (ImageComposeScene) -> T): T = try { block(this) } finally { close() }
}
