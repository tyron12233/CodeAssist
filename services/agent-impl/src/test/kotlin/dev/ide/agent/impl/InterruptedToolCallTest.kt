package dev.ide.agent.impl

import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AllowAllGate
import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.PermissionMode
import dev.ide.agent.ProviderConfig
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.StopReason
import dev.ide.agent.WriteRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A run stopped (or failed) while its tools were running used to leave the assistant's tool calls in the history
 * with no results. The conversation was saved that way, and every later message in it was refused by the provider
 * ("Missing tool response for tool_call_id(s) ..."), so the user had to abandon it. Every call must be answered.
 */
class InterruptedToolCallTest {

    private fun call(id: String, name: String, args: String) = ContentPart.ToolUse(id, name, args)

    private fun assistantCalling(vararg calls: ContentPart.ToolUse) = LlmMessage.assistant(calls.toList())

    /** Every tool call in [messages] is directly followed by its result, and no result is unanswered. */
    private fun assertPaired(messages: List<LlmMessage>) {
        var i = 0
        while (i < messages.size) {
            val m = messages[i++]
            assertTrue(m.role != LlmRole.TOOL, "a tool result with no call before it at ${i - 1}: $messages")
            val ids = m.content.filterIsInstance<ContentPart.ToolUse>().map { it.id }.toMutableSet()
            while (i < messages.size && messages[i].role == LlmRole.TOOL) {
                messages[i++].content.filterIsInstance<ContentPart.ToolResultPart>().forEach {
                    assertTrue(ids.remove(it.toolCallId), "result ${it.toolCallId} answers no call: $messages")
                }
            }
            assertTrue(ids.isEmpty(), "calls $ids have no result: $messages")
        }
    }

    private fun resultFor(messages: List<LlmMessage>, id: String): ContentPart.ToolResultPart =
        messages.flatMap { it.content }.filterIsInstance<ContentPart.ToolResultPart>().single { it.toolCallId == id }

    @Test
    fun unansweredCallsGetAnInterruptedResultAndStrayResultsAreDropped() {
        val broken = listOf(
            LlmMessage.user("make a game"),
            assistantCalling(call("a", "read_file", "{}"), call("b", "write_file", "{}")),
            LlmMessage.toolResult("a", "contents"),
            LlmMessage.user("Continue"),
            LlmMessage.toolResult("zzz", "answers nothing"),
        )

        val paired = pairToolResults(broken)

        assertPaired(paired)
        assertEquals("contents", resultFor(paired, "a").content, "a real result is kept")
        val b = resultFor(paired, "b")
        assertTrue(b.isError && b.content.startsWith("Interrupted"), "the missing one is marked interrupted: $b")
        // It sits with its call, ahead of the user's next message.
        assertEquals(LlmRole.USER, paired[paired.indexOfFirst { it.content.any { p -> p is ContentPart.ToolResultPart && p.toolCallId == "b" } } + 1].role)
    }

    @Test
    fun aHealthyHistoryIsReturnedAsIs() {
        val healthy = listOf(
            LlmMessage.user("hi"),
            assistantCalling(call("a", "read_file", "{}")),
            LlmMessage.toolResult("a", "contents"),
            LlmMessage.assistant(listOf(ContentPart.Text("done"))),
        )
        assertSame(healthy, pairToolResults(healthy))
    }

    /** Stopping while a write waits for permission, after a read in the same turn already finished. */
    @Test
    fun stoppingMidToolRunLeavesEveryCallAnswered() = runBlocking {
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "val a = 1"))
        val asked = CompletableDeferred<Unit>()
        val waitingGate = object : AgentPermissionGate {
            override val mode = PermissionMode.ASK_EACH
            override suspend fun authorize(request: WriteRequest): Boolean {
                asked.complete(Unit)
                awaitCancellation()
            }
        }
        val requests = ArrayList<LlmRequest>()
        val client = LlmClient { request ->
            requests += request
            val events = if (requests.size == 1) listOf(
                LlmStreamEvent.ToolCallCompleted("read", "read_file", """{"path":"A.kt"}"""),
                LlmStreamEvent.ToolCallCompleted("write", "write_file", """{"path":"B.kt","content":"x"}"""),
                LlmStreamEvent.Completed(StopReason.TOOL_USE),
            ) else listOf(
                LlmStreamEvent.TextDelta("carrying on"),
                LlmStreamEvent.Completed(StopReason.END_TURN),
            )
            events.asFlow()
        }
        val loop = AgentLoop(client, "m", SimpleToolRegistry(builtinTools(ws)), waitingGate, { "system" })

        val run = launch { loop.send("make a game", AgentEventSink { }) }
        asked.await()
        run.cancel() // the user pressed Stop while the permission prompt was up
        run.join()

        val saved = loop.snapshot()
        assertPaired(saved)
        assertTrue(resultFor(saved, "read").content.contains("val a = 1"), "the finished read keeps its real result")
        assertTrue(resultFor(saved, "write").content.startsWith("Interrupted"))

        // "Continue" in the same conversation goes through, with a valid history behind it.
        loop.send("Continue", AgentEventSink { })
        assertEquals(2, requests.size)
        assertPaired(requests[1].messages.filter { it.role != LlmRole.SYSTEM })
    }

    /** A conversation saved broken by an older version mends itself when it is resumed and continued. */
    @Test
    fun aConversationSavedBrokenCanBeContinued() = runBlocking {
        val requests = ArrayList<LlmRequest>()
        val client = LlmClient { request ->
            requests += request
            listOf<LlmStreamEvent>(LlmStreamEvent.TextDelta("ok"), LlmStreamEvent.Completed(StopReason.END_TURN)).asFlow()
        }
        val loop = AgentLoop(client, "m", SimpleToolRegistry(builtinTools(FakeWorkspace())), AllowAllGate, { "system" })
        loop.restore(
            listOf(
                LlmMessage.user("make a game"),
                assistantCalling(call("call_01a154621d1c746a830e084beeaa0290", "write_file", "{}")),
            ),
            dropThinking = false,
        )

        loop.send("Continue", AgentEventSink { })

        val sent = requests.single().messages.filter { it.role != LlmRole.SYSTEM }
        assertPaired(sent)
        assertEquals(LlmRole.USER, sent.last().role)

        // And on the OpenAI-compatible wire the error came from, the call now has its `tool` message.
        val transport = CapturingTransport(listOf("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        OpenAiProvider(transport).client(ProviderConfig("k", baseUrl = "https://gateway.example"))
            .chat(requests.single()).toList()
        val body = transport.lastBody.orEmpty()
        assertTrue(
            body.contains(""""role":"tool","tool_call_id":"call_01a154621d1c746a830e084beeaa0290""""),
            "the gateway request answers the call: $body",
        )
    }
}
