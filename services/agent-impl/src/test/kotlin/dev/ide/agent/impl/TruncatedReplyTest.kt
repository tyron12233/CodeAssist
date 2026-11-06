package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AllowAllGate
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.StopReason
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reply cut off at the output limit (typically a whole file being written as one tool call) used to end the turn
 * silently, leaving "I'm writing the file now" and nothing written.
 */
class TruncatedReplyTest {

    private fun run(stops: List<StopReason>): Pair<List<LlmRequest>, List<AgentEvent>> {
        val requests = mutableListOf<LlmRequest>()
        val events = mutableListOf<AgentEvent>()
        val client = LlmClient { request ->
            val stop = stops[requests.size.coerceAtMost(stops.lastIndex)]
            requests += request
            listOf(LlmStreamEvent.TextDelta("Writing the file now."), LlmStreamEvent.Completed(stop)).asFlow()
        }
        val loop = AgentLoop(client, "m", SimpleToolRegistry(builtinTools(FakeWorkspace())), AllowAllGate, { "system" })
        runBlocking { loop.send("make a login screen", AgentEventSink { events += it }) }
        return requests to events
    }

    private fun LlmRequest.mentionsTruncation() =
        messages.any { m -> m.content.any { it is dev.ide.agent.ContentPart.Text && it.text.startsWith("Your previous reply hit the output token limit") } }

    @Test
    fun aCutOffReplyIsToldAndRetriedOnce() {
        val (requests, events) = run(listOf(StopReason.MAX_TOKENS, StopReason.END_TURN))
        assertEquals(2, requests.size)
        assertTrue(requests[1].mentionsTruncation(), "the retry tells the model its reply was cut off")
        assertTrue(events.none { it is AgentEvent.Error })
        assertTrue(events.any { it is AgentEvent.TurnCompleted })
    }

    @Test
    fun cutOffTwiceIsReportedNotSilent() {
        val (requests, events) = run(listOf(StopReason.MAX_TOKENS))
        assertEquals(2, requests.size)
        val error = events.filterIsInstance<AgentEvent.Error>().single()
        assertTrue(error.message.contains("output limit"))
    }
}

/**
 * Every request must extend the one before it, unchanged, or the prompt cache (and current models' earlier
 * reasoning) is lost from the first difference. The session context used to be added to each request and dropped
 * from the next, so a tool loop re-wrote its whole conversation to the cache on every step.
 */
class AppendOnlyHistoryTest {
    @Test
    fun eachStepExtendsThePreviousRequestUnchanged() {
        val requests = mutableListOf<LlmRequest>()
        val client = LlmClient { request ->
            requests += request
            val events = if (requests.size == 1) {
                listOf(
                    LlmStreamEvent.ToolCallCompleted("c1", "read_file", """{"path":"A.kt"}"""),
                    LlmStreamEvent.Completed(StopReason.TOOL_USE),
                )
            } else {
                listOf(LlmStreamEvent.TextDelta("done"), LlmStreamEvent.Completed(StopReason.END_TURN))
            }
            events.asFlow()
        }
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "val a = 1"))
        val loop = AgentLoop(
            client, "m", SimpleToolRegistry(builtinTools(ws)), AllowAllGate, { "system" },
            sessionContext = { "Permission mode: ask." },
        )
        runBlocking { loop.send("read A", AgentEventSink { }) }

        assertEquals(2, requests.size)
        val first = requests[0].messages
        val second = requests[1].messages
        assertEquals(first, second.take(first.size), "the second request starts with the first, unchanged")
        assertEquals(1, second.count { it.role == dev.ide.agent.LlmRole.SYSTEM }, "the context is written once")
    }
}

/** A conversation past the size the provider takes is summarized mid-task, and the task carries on from it. */
class AutoCompactTest {
    @Test
    fun aLongConversationIsSummarizedAndTheTurnContinues() {
        val requests = mutableListOf<LlmRequest>()
        val events = mutableListOf<AgentEvent>()
        val client = LlmClient { request ->
            requests += request
            val asksForSummary = request.messages.lastOrNull()?.content?.any {
                it is dev.ide.agent.ContentPart.Text && it.text.startsWith("This conversation is getting too long")
            } == true
            val reply = if (asksForSummary) "SUMMARY: building a login screen in Login.kt." else "Done."
            listOf(LlmStreamEvent.TextDelta(reply), LlmStreamEvent.Completed(StopReason.END_TURN)).asFlow()
        }
        val loop = AgentLoop(
            client, "m", SimpleToolRegistry(builtinTools(FakeWorkspace())), AllowAllGate, { "system" },
            autoCompactChars = 500,
        )
        loop.restore(
            listOf(
                dev.ide.agent.LlmMessage.user("x".repeat(400)),
                dev.ide.agent.LlmMessage.assistant(listOf(dev.ide.agent.ContentPart.Text("y".repeat(400)))),
            ),
            dropThinking = false,
        )
        runBlocking { loop.send("now add a forgot-password link", AgentEventSink { events += it }) }

        assertEquals(2, requests.size, "one summary request, then the real one")
        assertTrue(events.any { it is AgentEvent.Compacted })
        val sent = requests[1].messages.flatMap { m -> m.content.filterIsInstance<dev.ide.agent.ContentPart.Text>().map { it.text } }
        assertTrue(sent.any { it.startsWith("SUMMARY:") }, "the task goes on from the summary")
        assertTrue(sent.none { it == "x".repeat(400) }, "the old messages are gone")
        // The summary is not shown as the answer.
        assertTrue(events.filterIsInstance<AgentEvent.AssistantTextDelta>().none { it.text.startsWith("SUMMARY") })
    }

    /** A client that reports [promptTokens] for every ordinary step and records what it was sent. */
    private fun measuringClient(requests: MutableList<LlmRequest>, promptTokens: Int) = LlmClient { request ->
        requests += request
        val asksForSummary = request.messages.lastOrNull()?.content?.any {
            it is dev.ide.agent.ContentPart.Text && it.text.startsWith("This conversation is getting too long")
        } == true
        val reply = if (asksForSummary) "SUMMARY: the login screen is done." else "Done."
        listOf(
            LlmStreamEvent.TextDelta(reply),
            LlmStreamEvent.Usage(dev.ide.agent.TokenUsage(inputTokens = 10, cacheReadTokens = promptTokens - 10)),
            LlmStreamEvent.Completed(StopReason.END_TURN),
        ).asFlow()
    }

    @Test
    fun aMeasuredPromptOverTheTokenLimitIsSummarizedBeforeTheNextStep() {
        val requests = mutableListOf<LlmRequest>()
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
            measuringClient(requests, promptTokens = 80_000), "m", SimpleToolRegistry(builtinTools(FakeWorkspace())),
            AllowAllGate, { "system" },
            // Far above this short transcript: only the measured token count can trigger it.
            autoCompactChars = 1_000_000,
            autoCompactTokens = 70_000,
        )
        runBlocking {
            loop.send("build a login screen", AgentEventSink { events += it })
            loop.send("now add a forgot-password link", AgentEventSink { events += it })
        }

        assertEquals(3, requests.size, "the first step, a summary, then the second step")
        assertTrue(events.any { it is AgentEvent.Compacted })
        val summary = requests[1]
        // The summary reuses the cached prefix: the same tools and thinking as the steps around it.
        assertEquals(requests[0].tools, summary.tools)
        assertEquals(requests[0].thinking, summary.thinking)
        assertEquals(requests[0].effort, summary.effort)
        val sent = requests[2].messages.flatMap { m -> m.content.filterIsInstance<dev.ide.agent.ContentPart.Text>().map { it.text } }
        assertTrue(sent.any { it.startsWith("SUMMARY:") })
        assertTrue(sent.none { it == "build a login screen" }, "the old messages are gone")
    }

    @Test
    fun aMeasuredPromptUnderTheTokenLimitIsLeftAlone() {
        val requests = mutableListOf<LlmRequest>()
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
            measuringClient(requests, promptTokens = 40_000), "m", SimpleToolRegistry(builtinTools(FakeWorkspace())),
            AllowAllGate, { "system" },
            // Below this transcript's size: once a measurement exists, tokens decide, not characters.
            autoCompactChars = 10,
            autoCompactTokens = 70_000,
        )
        runBlocking {
            loop.send("build a login screen", AgentEventSink { events += it })
            loop.send("now add a forgot-password link", AgentEventSink { events += it })
        }

        assertEquals(2, requests.size, "no summary request")
        assertTrue(events.none { it is AgentEvent.Compacted })
    }
}
