package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.AllowAllGate
import dev.ide.agent.DiagnosticInfo
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ModuleInfo
import dev.ide.agent.ProjectOverview
import dev.ide.agent.ProviderConfig
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.StopReason
import dev.ide.agent.SymbolHit
import dev.ide.agent.TextEdit
import dev.ide.agent.TextMatch
import dev.ide.agent.WorkspaceEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Replays recorded SSE `data:` payloads, so provider decoding is exercised offline. */
private class FakeTransport(private val payloads: List<String>) : LlmTransport {
    override fun sse(request: SseRequest): Flow<String> = payloads.asFlow()
}

/** Returns a scripted turn per chat() call, driving the agent loop deterministically. */
private class ScriptedClient(private val turns: List<List<LlmStreamEvent>>) : LlmClient {
    private var index = 0
    override fun chat(request: LlmRequest): Flow<LlmStreamEvent> = turns[index++].asFlow()
}

/** An in-memory [AgentWorkspace] backed by a path -> content map. */
private class FakeWorkspace(private val files: MutableMap<String, String> = mutableMapOf()) : AgentWorkspace {
    fun content(path: String): String? = files[path]

    override fun projectRoot(): String = "/project"
    override suspend fun readFile(path: String, startLine: Int?, endLine: Int?): String =
        files[path] ?: throw IllegalArgumentException("no such file: $path")
    override suspend fun listDir(path: String): List<WorkspaceEntry> = emptyList()
    override suspend fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, limit: Int): List<TextMatch> = emptyList()
    override suspend fun findSymbol(query: String, limit: Int): List<SymbolHit> = emptyList()
    override suspend fun diagnostics(path: String): List<DiagnosticInfo> = emptyList()
    override suspend fun projectOverview(): ProjectOverview = ProjectOverview("test", listOf(ModuleInfo("app", "java", "17", emptyList(), emptyList())))
    override suspend fun createFile(path: String, content: String): String { files[path] = content; return path }
    override suspend fun writeFile(path: String, content: String) { files[path] = content }
    override suspend fun applyEdits(path: String, edits: List<TextEdit>) {
        var text = files[path] ?: ""
        edits.sortedByDescending { it.offset }.forEach { e ->
            text = text.substring(0, e.offset) + e.newText + text.substring(e.offset + e.oldLength)
        }
        files[path] = text
    }
    override suspend fun createDir(path: String): String = path
    override suspend fun renamePath(path: String, newName: String): String = newName
    override suspend fun movePath(path: String, destDir: String): String = "$destDir/${path.substringAfterLast('/')}"
    override suspend fun deletePath(path: String): Boolean = files.remove(path) != null
    override suspend fun addDependency(module: String, coordinate: String): String = "added $coordinate to $module"
}

private fun request(): LlmRequest = LlmRequest("model", null, listOf(LlmMessage.user("hi")))

class AgentTest {
    @Test
    fun anthropicDecodesTextAndToolCall() {
        val provider = AnthropicProvider(
            FakeTransport(
                listOf(
                    """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file"}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"A.kt\"}"}}""",
                    """{"type":"content_block_stop","index":1}""",
                    """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
                    """{"type":"message_stop"}""",
                ),
            ),
        )
        val events = runBlocking { provider.client(ProviderConfig("k")).chat(request()).toList() }

        assertEquals("Hello", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
        val call = events.filterIsInstance<LlmStreamEvent.ToolCallCompleted>().single()
        assertEquals("read_file", call.name)
        assertTrue(call.arguments.contains("A.kt"), "arguments were: ${call.arguments}")
        assertEquals(StopReason.TOOL_USE, events.filterIsInstance<LlmStreamEvent.Completed>().last().stopReason)
        val usage = events.filterIsInstance<LlmStreamEvent.Usage>().last().usage
        assertEquals(10, usage.inputTokens)
        assertEquals(5, usage.outputTokens)
    }

    @Test
    fun openAiDecodesTextAndToolCall() {
        val provider = OpenAiProvider(
            FakeTransport(
                listOf(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}""",
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"A.kt\"}"}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                    """{"choices":[],"usage":{"prompt_tokens":8,"completion_tokens":3}}""",
                    "[DONE]",
                ),
            ),
        )
        val events = runBlocking { provider.client(ProviderConfig("k")).chat(request()).toList() }

        assertEquals("Hi", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
        val call = events.filterIsInstance<LlmStreamEvent.ToolCallCompleted>().single()
        assertEquals("read_file", call.name)
        assertTrue(call.arguments.contains("A.kt"), "arguments were: ${call.arguments}")
        assertEquals(StopReason.TOOL_USE, events.filterIsInstance<LlmStreamEvent.Completed>().last().stopReason)
        assertEquals(8, events.filterIsInstance<LlmStreamEvent.Usage>().last().usage.inputTokens)
    }

    @Test
    fun geminiDecodesTextAndToolCall() {
        val provider = GeminiProvider(
            FakeTransport(
                listOf(
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hi"}]}}]}""",
                    """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"read_file","args":{"path":"A.kt"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":2}}""",
                ),
            ),
        )
        val events = runBlocking { provider.client(ProviderConfig("k")).chat(request()).toList() }

        assertEquals("Hi", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
        val call = events.filterIsInstance<LlmStreamEvent.ToolCallCompleted>().single()
        assertEquals("read_file", call.name)
        assertTrue(call.arguments.contains("A.kt"), "arguments were: ${call.arguments}")
        assertEquals(7, events.filterIsInstance<LlmStreamEvent.Usage>().last().usage.inputTokens)
    }

    @Test
    fun agentLoopRunsToolThenAnswers() {
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "hi"))
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmStreamEvent.ToolCallCompleted("c1", "read_file", """{"path":"A.kt"}"""),
                    LlmStreamEvent.Completed(StopReason.TOOL_USE),
                ),
                listOf(
                    LlmStreamEvent.TextDelta("The file says hi."),
                    LlmStreamEvent.Completed(StopReason.END_TURN),
                ),
            ),
        )
        val loop = AgentLoop(
            client = client,
            model = "model",
            tools = SimpleToolRegistry(builtinTools(ws)),
            gate = AllowAllGate,
            systemPrompt = { "system" },
        )

        val events = mutableListOf<AgentEvent>()
        runBlocking { loop.send("read A.kt", AgentEventSink { events += it }) }

        val started = events.filterIsInstance<AgentEvent.ToolCallStarted>().single()
        assertEquals("read_file", started.name)
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(finished.ok)
        assertEquals(
            "The file says hi.",
            events.filterIsInstance<AgentEvent.AssistantTextDelta>().joinToString("") { it.text },
        )
        assertNotNull(events.filterIsInstance<AgentEvent.TurnCompleted>().lastOrNull())
    }

    @Test
    fun editFileToolAppliesReplacement() {
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "val x = 1"))
        val editTool = SimpleToolRegistry(builtinTools(ws)).find("edit_file")
        assertNotNull(editTool)
        val args = JsonToolArgs(parseArgsObject("""{"path":"A.kt","old_string":"1","new_string":"2"}"""))

        val result = runBlocking { editTool.execute(args) }

        assertTrue(!result.isError, "unexpected error: ${result.content}")
        assertEquals("val x = 2", ws.content("A.kt"))
    }

    @Test
    fun providerErrorsAreCategorizedWithRetryHints() {
        // OpenAI: insufficient quota is a billing problem, not worth retrying.
        val quota = LlmErrors.parseHttp(
            429,
            """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota"}}""",
            null,
        )
        assertEquals(LlmErrorKind.QUOTA, quota.kind)
        assertTrue(!quota.retryable)

        // Gemini: RESOURCE_EXHAUSTED with a RetryInfo delay is a retryable rate limit.
        val gemini = LlmErrors.parseHttp(
            429,
            """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded.","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"5s"}]}}""",
            null,
        )
        assertEquals(LlmErrorKind.RATE_LIMIT, gemini.kind)
        assertEquals(5000L, gemini.retryAfterMs)
        assertTrue(gemini.retryable)

        // Anthropic: 529 overloaded is transient.
        val overloaded = LlmErrors.parseHttp(
            529,
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            null,
        )
        assertEquals(LlmErrorKind.OVERLOADED, overloaded.kind)
        assertTrue(overloaded.retryable)

        // A bad key is an auth failure, not retryable.
        val auth = LlmErrors.parseHttp(
            401,
            """{"error":{"message":"invalid x-api-key","type":"authentication_error"}}""",
            null,
        )
        assertEquals(LlmErrorKind.AUTH, auth.kind)
        assertTrue(!auth.retryable)

        // The Retry-After header (seconds) supplies the backoff delay.
        val rateLimited = LlmErrors.parseHttp(429, """{"error":{"type":"rate_limit_error","message":"slow down"}}""", "12")
        assertEquals(LlmErrorKind.RATE_LIMIT, rateLimited.kind)
        assertEquals(12000L, rateLimited.retryAfterMs)
    }
}
