package dev.ide.core.agent

import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentSegment
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSessionStoreTest {

    @Test
    fun aTurnsStepsSurviveSaveAndLoadInOrder() {
        val root = Files.createTempDirectory("agent-sessions")
        try {
            val store = AgentSessionStore { root }
            val segments = listOf(
                UiAgentSegment.Thinking("Check the theme."),
                UiAgentSegment.Text("Let me read it."),
                UiAgentSegment.Tools(listOf("a", "b")),
                UiAgentSegment.Text("Fixed."),
            )
            val turn = UiAgentMessage(
                2, UiAgentRole.ASSISTANT,
                text = "Let me read it.\n\nFixed.", thinking = "Check the theme.",
                toolCalls = listOf(
                    UiAgentToolCall("a", "read Theme.kt", UiAgentToolStatus.OK, "48 lines"),
                    UiAgentToolCall("b", "edit Theme.kt", UiAgentToolStatus.OK, "Edited Theme.kt"),
                ),
                segments = segments,
            )
            store.save("s1", listOf(UiAgentMessage(1, UiAgentRole.USER, text = "Fix the theme."), turn), emptyList())

            val loaded = store.load("s1")!!.messages
            assertEquals(2, loaded.size)
            assertEquals(segments, loaded[1].segments)
            assertEquals(turn.text, loaded[1].text)
            assertEquals(listOf("a", "b"), loaded[1].toolCalls.map { it.id })
            assertTrue(loaded[0].segments.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
