package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.PermissionMode
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.StopReason
import dev.ide.agent.WriteRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckpointAndToolsTest {
    @Test
    fun revertRestoresEditedCreatedAndDeletedFiles() {
        val fake = FakeWorkspace(mutableMapOf("A.kt" to "one", "B.kt" to "keep me"))
        val ws = CheckpointWorkspace(fake)
        runBlocking {
            ws.startTurn(1)
            ws.writeFile("A.kt", "two")
            ws.writeFile("A.kt", "three")
            ws.createFile("C.kt", "new")
            ws.deletePath("B.kt")
            assertTrue(ws.hasChanges(1))
            val report = ws.revert(1)
            assertTrue(report.startsWith("Reverted 3 changes"), report)
        }
        assertEquals("one", fake.content("A.kt"))
        assertNull(fake.content("C.kt"))
        assertEquals("keep me", fake.content("B.kt"))
    }

    @Test
    fun revertUndoesTheChosenTurnAndEveryLaterOneButNotEarlierOnes() {
        val fake = FakeWorkspace(mutableMapOf("A.kt" to "v0"))
        val ws = CheckpointWorkspace(fake)
        runBlocking {
            ws.startTurn(1); ws.writeFile("A.kt", "v1")
            ws.startTurn(5); ws.writeFile("A.kt", "v2")
            ws.startTurn(9); ws.writeFile("A.kt", "v3")
            ws.revert(5)
        }
        assertEquals("v1", fake.content("A.kt"))
    }

    @Test
    fun aCallReportsItsOwnBeforeAndAfter() {
        val fake = FakeWorkspace(mutableMapOf("A.kt" to "v0"))
        val ws = CheckpointWorkspace(fake)
        val changes = runBlocking {
            ws.startTurn(1)
            ws.beginCall("first"); ws.writeFile("A.kt", "v1"); ws.endCall()
            ws.beginCall("second"); ws.writeFile("A.kt", "v2"); ws.endCall()
        }
        assertEquals(1, changes.size)
        assertEquals("v1", changes.single().before)
        assertEquals("v2", changes.single().after)
    }

    @Test
    fun loopAsksWithADiffAndReportsWhatTheEditChanged() {
        val fake = FakeWorkspace(mutableMapOf("A.kt" to "val x = 1\n"))
        val ws = CheckpointWorkspace(fake)
        var asked: WriteRequest? = null
        val gate = object : AgentPermissionGate {
            override val mode = PermissionMode.ASK_EACH
            override suspend fun authorize(request: WriteRequest): Boolean { asked = request; return true }
        }
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmStreamEvent.ToolCallCompleted("c1", "edit_file", """{"path":"A.kt","old_string":"1","new_string":"2"}"""),
                    LlmStreamEvent.Completed(StopReason.TOOL_USE),
                ),
                listOf(LlmStreamEvent.TextDelta("done"), LlmStreamEvent.Completed(StopReason.END_TURN)),
            ),
        )
        val loop = AgentLoop(client, "m", SimpleToolRegistry(builtinTools(ws)), gate, { "sys" }, checkpoints = ws)
        val events = mutableListOf<AgentEvent>()
        runBlocking {
            ws.startTurn(1)
            loop.send("bump x", AgentEventSink { events += it })
        }
        val preview = asked!!.changes.single()
        assertEquals("val x = 1\n", preview.before)
        assertEquals("val x = 2\n", preview.after)
        val changed = events.filterIsInstance<AgentEvent.FilesChanged>().single()
        assertEquals("c1", changed.id)
        assertEquals("val x = 2\n", changed.changes.single().after)
        assertEquals("val x = 2\n", fake.content("A.kt"))
    }

    @Test
    fun todoWriteReportsThePlanAsAnEvent() {
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmStreamEvent.ToolCallCompleted(
                        "t1", "todo_write",
                        """{"todos":[{"content":"Read the code","status":"completed"},{"content":"Fix it","status":"in_progress"}]}""",
                    ),
                    LlmStreamEvent.Completed(StopReason.TOOL_USE),
                ),
                listOf(LlmStreamEvent.TextDelta("ok"), LlmStreamEvent.Completed(StopReason.END_TURN)),
            ),
        )
        val loop = AgentLoop(client, "m", SimpleToolRegistry(builtinTools(FakeWorkspace())), dev.ide.agent.AllowAllGate, { "sys" })
        val events = mutableListOf<AgentEvent>()
        runBlocking { loop.send("plan it", AgentEventSink { events += it }) }
        val todos = events.filterIsInstance<AgentEvent.TodosUpdated>().single().todos
        assertEquals(listOf("completed", "in_progress"), todos.map { it.status })
        assertEquals("Fix it", todos[1].content)
    }

    @Test
    fun nestedAdditionalPropertiesAreStrippedForGemini() {
        val schema = AgentJson.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"properties":{"todos":{"type":"array","items":{"type":"object","additionalProperties":false}}}}""",
        )
        assertTrue("additionalProperties" !in GeminiWire.stripAdditionalProperties(schema).toString())
    }

    @Test
    fun conversationsRoundTripThroughTheSessionCodec() {
        val messages = listOf(
            LlmMessage.user("look at this", listOf(ContentPart.Image("image/png", "AAAA"))),
            LlmMessage.assistant(
                listOf(
                    ContentPart.Thinking("hmm", "sig"),
                    ContentPart.Text("Reading."),
                    ContentPart.ToolUse("c1", "read_file", """{"path":"A.kt"}""", "tsig"),
                ),
            ),
            LlmMessage.toolResult("c1", "contents", isError = true, images = listOf(ContentPart.Image("image/jpeg", "BBBB"))),
            LlmMessage(LlmRole.SYSTEM, listOf(ContentPart.Text("note"))),
        )
        assertEquals(messages, ConversationCodec.decode(ConversationCodec.encode(messages)))
        assertTrue(ConversationCodec.decode("not json").isEmpty())
    }

    @Test
    fun screenshotPreviewReturnsThePngAsAnImageOrExplainsWhyNot() {
        val shot = dev.ide.agent.PreviewImage("Main.kt", "GreetingPreview", byteArrayOf(1, 2, 3), 10, 20)
        val ws = object : dev.ide.agent.AgentWorkspace by FakeWorkspace() {
            var open = true
            override suspend fun previewScreenshot(path: String?) = if (open) shot else null
        }
        val tool = builtinTools(ws).single { it.spec.name == "screenshot_preview" }
        val ok = runBlocking { tool.execute(JsonToolArgs(parseArgsObject("{}"))) }
        assertTrue(ok.content.contains("GreetingPreview"), ok.content)
        assertEquals("AQID", ok.images.single().data)
        ws.open = false
        val none = runBlocking { tool.execute(JsonToolArgs(parseArgsObject("""{"path":"Main.kt"}"""))) }
        assertTrue(none.isError)
        assertTrue(none.content.contains("open the preview"), none.content)
    }
}
