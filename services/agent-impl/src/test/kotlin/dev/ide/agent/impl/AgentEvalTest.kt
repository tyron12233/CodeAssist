package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.AllowAllGate
import dev.ide.agent.LlmProvider
import dev.ide.agent.ProviderConfig
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task-level evaluation of the agent: it drives the REAL loop, the REAL tools, and a REAL provider request
 * builder against a scripted model, so a task is judged on the end state it produces rather than on the
 * events it happened to emit.
 *
 * Everything here runs offline. Scripting the model turns fixes the one variable an offline run cannot
 * supply — the model's judgement — and leaves the parts this repo actually owns under test: whether the
 * tools do what the model asked, whether the loop feeds results back correctly, and whether the request it
 * builds still caches. Those are the parts a refactor breaks silently.
 *
 * The prompt-prefix check is the important one. Prompt caching is a prefix match, so a change anywhere in
 * prompt assembly can quietly stop every request from caching while every test still passes and every
 * response still looks right — the only symptom is the bill. [promptPrefixIsStableAcrossAToolLoop] fails
 * instead.
 */
class AgentEvalTest {

    /** Records every request body, and replays one scripted SSE turn per call. */
    private class RecordingTransport(private val turns: List<List<String>>) : LlmTransport {
        val bodies = mutableListOf<String>()
        private var index = 0

        override fun sse(request: SseRequest): Flow<String> {
            bodies += request.jsonBody
            return turns[minOf(index++, turns.lastIndex)].asFlow()
        }
    }

    /** The SSE frames for one Anthropic turn that calls [tool] with [argsJson]. */
    private fun toolTurn(id: String, tool: String, argsJson: String): List<String> = listOf(
        """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":0}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"$id","name":"$tool"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta",""" +
            """"partial_json":${quote(argsJson)}}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
        """{"type":"message_stop"}""",
    )

    /** The SSE frames for one Anthropic turn that just answers with [text]. */
    private fun textTurn(text: String): List<String> = listOf(
        """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":900}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${quote(text)}}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
        """{"type":"message_stop"}""",
    )

    private fun quote(s: String): String = AgentJson.encodeToString(kotlinx.serialization.json.JsonPrimitive(s))

    /** Runs one task end to end against a scripted model and returns the recorded request bodies. */
    private fun runTask(
        workspace: AgentWorkspace,
        prompt: String,
        turns: List<List<String>>,
        provider: LlmProvider = AnthropicProvider(RecordingTransport(emptyList())),
    ): Pair<List<String>, List<AgentEvent>> {
        val transport = RecordingTransport(turns)
        val tools = SimpleToolRegistry(builtinTools(workspace))
        val loop = AgentLoop(
            client = AnthropicProvider(transport).client(ProviderConfig("k")),
            model = provider.defaultModel,
            tools = tools,
            gate = AllowAllGate,
            systemPrompt = { SystemPrompt.grounding(tools.specs().map { it.name }) },
            sessionContext = { SystemPrompt.sessionContext(dev.ide.agent.PermissionMode.AUTO_ACCEPT, "Project root: /project") },
        )
        val events = mutableListOf<AgentEvent>()
        runBlocking { loop.send(prompt, AgentEventSink { events += it }) }
        return transport.bodies to events
    }

    // --- Task evals: judged on the end state, not on the transcript ------------------------------------

    @Test
    fun editsAFileAndReportsBack() {
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "val x = 1"))
        val (_, events) = runTask(
            ws,
            "rename x to y",
            listOf(
                toolTurn("t1", "edit_file", """{"path":"A.kt","old_string":"val x = 1","new_string":"val y = 1"}"""),
                textTurn("Renamed x to y."),
            ),
        )

        assertEquals("val y = 1", ws.content("A.kt"), "the task is judged on the file, not on what was said")
        assertTrue(events.any { it is AgentEvent.TurnCompleted })
        assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().all { it.ok })
    }

    @Test
    fun recoversWhenATaskStartsWithAFailingToolCall() {
        // A wrong path must come back as an error result the model can act on, not end the run.
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "val x = 1"))
        val (_, events) = runTask(
            ws,
            "read the file",
            listOf(
                toolTurn("t1", "read_file", """{"path":"Missing.kt"}"""),
                toolTurn("t2", "read_file", """{"path":"A.kt"}"""),
                textTurn("It contains val x = 1."),
            ),
        )

        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>()
        assertEquals(2, finished.size)
        assertTrue(!finished[0].ok, "the bad path fails")
        assertTrue(finished[1].ok, "and the run continues to a good one")
        assertTrue(events.any { it is AgentEvent.TurnCompleted })
    }

    @Test
    fun reportsCacheAccountingForTheWholeTurn() {
        // A turn is several requests; the figure the user sees has to cover all of them.
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "val x = 1"))
        val (_, events) = runTask(
            ws,
            "read the file",
            listOf(toolTurn("t1", "read_file", """{"path":"A.kt"}"""), textTurn("Done.")),
        )

        val usage = events.filterIsInstance<AgentEvent.TurnCompleted>().single().usage
        assertEquals(20, usage?.inputTokens, "both requests are counted")
        assertEquals(900, usage?.cacheReadTokens)
        assertEquals(12, usage?.outputTokens)
    }

    // --- Regression guards for the things that fail silently -------------------------------------------

    @Test
    fun promptPrefixIsStableAcrossAToolLoop() {
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "val x = 1", "B.kt" to "val z = 2"))
        val (bodies, _) = runTask(
            ws,
            "read both files",
            listOf(
                toolTurn("t1", "read_file", """{"path":"A.kt"}"""),
                toolTurn("t2", "read_file", """{"path":"B.kt"}"""),
                textTurn("Read both."),
            ),
        )
        assertEquals(3, bodies.size, "three requests: two tool rounds plus the answer")

        bodies.zipWithNext().forEachIndexed { i, (earlier, later) ->
            val a = AgentJson.parseToJsonElement(earlier).jsonObj()
            val b = AgentJson.parseToJsonElement(later).jsonObj()

            // Tools and the system prompt render ahead of the conversation, so any drift there throws away
            // every cached turn behind them.
            assertEquals(a["tools"], b["tools"], "tool definitions drifted between request $i and ${i + 1}")
            assertEquals(a["system"], b["system"], "system prompt drifted between request $i and ${i + 1}")

            // The earlier request's conversation must reappear byte-identical as a prefix of the later one.
            // cache_control is stripped first: the breakpoint marker moves forward every request by design,
            // and a previously-marked block is still a cache hit.
            val earlierMsgs = stripCacheControl(a["messages"]!!) as JsonArray
            val laterMsgs = stripCacheControl(b["messages"]!!) as JsonArray
            val shared = earlierMsgs.size - 1 // the trailing session-context message is rebuilt each turn
            assertEquals(
                earlierMsgs.subList(0, shared),
                laterMsgs.subList(0, shared),
                "the conversation prefix was rewritten between request $i and ${i + 1}, so nothing after it caches",
            )
        }
    }

    @Test
    fun theToolSurfaceStaysWithinItsBudget() {
        // Tools render at position 0 of every request. Measured 2026-09-15: 10,943 chars across 30 tools,
        // roughly 3K tokens. That is small enough that deferred tool loading would LOSE — it trades a cached
        // prefix costing ~0.1x per request for a whole extra round trip every time the model needs a tool it
        // cannot see. This budget is the cheaper control: it caps growth without paying that round trip.
        // Revisit deferral only if the surface grows several times over, or if tool-choice accuracy (not
        // token cost) becomes the measured problem.
        val specs = builtinTools(FakeWorkspace()).map { it.spec }
        val bytes = specs.sumOf { it.name.length + it.description.length + it.parameters.length }

        assertTrue(
            bytes < TOOL_SURFACE_BUDGET,
            "the tool surface is $bytes chars across ${specs.size} tools, over the $TOOL_SURFACE_BUDGET budget. " +
                "Trim a description, merge overlapping tools, or raise the budget deliberately.",
        )
        assertEquals(specs.size, specs.map { it.name }.distinct().size, "tool names must be unique")
    }

    @Test
    fun everyToolSchemaIsStrictReady() {
        // Strict tool use needs both of these, and it is what stops a malformed call costing a whole round
        // trip to discover. A hand-written schema that forgets them silently opts that tool out.
        builtinTools(FakeWorkspace()).map { it.spec }.forEach { spec: ToolSpec ->
            assertTrue(
                spec.parameters.contains("\"additionalProperties\":false"),
                "${spec.name} is missing additionalProperties:false",
            )
            assertTrue(spec.parameters.contains("\"required\":"), "${spec.name} is missing a required list")
        }
    }

    private fun JsonElement.jsonObj(): JsonObject = this as JsonObject

    /** Removes every `cache_control` marker, so two requests can be compared on content alone. */
    private fun stripCacheControl(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterKeys { it != "cache_control" }.mapValues { (_, v) -> stripCacheControl(v) },
        )
        is JsonArray -> JsonArray(element.map { stripCacheControl(it) })
        else -> element
    }

    private companion object {
        /** Characters of tool names, descriptions, and schemas. Raise it deliberately, never incidentally. */
        const val TOOL_SURFACE_BUDGET = 16_000
    }
}
