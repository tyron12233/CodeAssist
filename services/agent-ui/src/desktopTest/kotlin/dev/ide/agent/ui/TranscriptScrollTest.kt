package dev.ide.agent.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentSegment
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.theme.CodeAssistTheme
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the transcript follows a streaming reply, driven headlessly: it keeps the end of a growing, taller-than-the-
 * screen turn in view, stops following once the reader scrolls up, and returns to the end when the user sends.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TranscriptScrollTest {

    /** A running turn of [paragraphs] paragraphs after two batches of tool calls, the second still in flight. */
    private fun turn(id: Long, paragraphs: Int): UiAgentMessage {
        val calls = (1..8).map { i ->
            UiAgentToolCall("t$i", "read File$i.kt", if (i == 8) UiAgentToolStatus.RUNNING else UiAgentToolStatus.OK, "40 lines")
        }
        val text = (1..paragraphs).joinToString("\n\n") { "Paragraph $it of the answer, long enough to wrap onto a second line." }
        return UiAgentMessage(
            id, UiAgentRole.ASSISTANT,
            text = text, toolCalls = calls, streaming = true,
            segments = listOf(
                UiAgentSegment.Tools(calls.take(4).map { it.id }),
                UiAgentSegment.Text(text),
                UiAgentSegment.Tools(calls.drop(4).map { it.id }),
            ),
        )
    }

    @Test
    @Timeout(120)
    fun followsTheEndUntilTheReaderScrollsUpAndReturnsOnSend() {
        var messages by mutableStateOf(listOf(UiAgentMessage(1, UiAgentRole.USER, text = "Explain the project."), turn(2, 6)))
        val state = LazyListState()
        val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
            CodeAssistTheme(dark = false) {
                Transcript(messages, busy = true, onRetry = {}, onUndo = {}, onUseModel = {}, listState = state)
            }
        }
        var time = 0L
        fun frames(count: Int = 40) {
            Snapshot.sendApplyNotifications()
            repeat(count) { time += 16_666_667L; scene.render(time) }
        }
        fun update(next: List<UiAgentMessage>) { messages = next; frames() }
        try {
            frames()
            assertTrue(state.layoutInfo.totalItemsCount > 0)
            assertFalse(state.canScrollForward, "opens on the end of the turn")

            // The turn grows well past the screen: its end, not its first tool call, stays in view.
            update(listOf(messages[0], turn(2, 30)))
            assertTrue(state.canScrollBackward, "the turn is taller than the screen")
            assertFalse(state.canScrollForward, "the end of a growing reply stays in view")

            // The reader scrolls back to read; more streaming text must not pull the view down.
            repeat(6) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(400f, 900f), scrollDelta = Offset(0f, -10f))
                frames(6)
            }
            frames(60)
            assertTrue(state.canScrollForward, "the reader scrolled up")
            val held = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            update(listOf(messages[0], turn(2, 40)))
            assertEquals(held, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset, "the view stays where the reader left it")

            // Sending a message returns to the end.
            update(messages + UiAgentMessage(3, UiAgentRole.USER, text = "Thanks.") + turn(4, 2))
            assertFalse(state.canScrollForward, "a sent message returns to the end")
        } finally {
            scene.close()
        }
    }
}
