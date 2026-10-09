package dev.ide.ui.editor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The completion list must show its first (selected) row after a keystroke re-ranks it. The rows are keyed, so a
 * LazyColumn left alone keeps the viewport on the row that was first visible; a new best match ranked above it
 * then sat just off the top. That only happens once the popup is shorter than the list (the build console open
 * trims it), so the popup here is a few rows tall.
 */
class CompletionListAnchorTest {

    private fun item(label: String) = UiCompletionItem(label, label, "(): Unit", "demo", null, UiCompletionKind.Method, 0)

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun aNewTopMatchIsScrolledIntoView() {
        val before = (0 until 12).map { item("item$it") }
        val items = mutableStateOf(before)
        val listState = LazyListState()
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            CodeAssistTheme(dark = true) {
                CompletionList(
                    items.value, selectedIndex = 0, prefix = "i", width = 300.dp,
                    onPick = {}, onHover = {}, maxListHeight = 120.dp, listState = listState,
                )
            }
        }
        try {
            scene.render()
            assertEquals(0, listState.firstVisibleItemIndex)

            // The next keystroke ranks a new match first; the rest keep their keys.
            items.value = listOf(item("index")) + before
            scene.render(16_000_000L)
            scene.render(32_000_000L)
            assertEquals(0, listState.firstVisibleItemIndex, "the new first row must be visible")
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        } finally {
            scene.close()
        }
    }
}
