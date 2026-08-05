package dev.ide.agent.impl

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.AllowAllGate
import dev.ide.agent.ContentPart
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
import dev.ide.agent.ToolSpec
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

/** Records the request body a provider builds, then replays a minimal completion so the flow terminates. */
private class CapturingTransport(
    private val payloads: List<String> =
        listOf("""{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""),
) : LlmTransport {
    var lastBody: String? = null
        private set

    override fun sse(request: SseRequest): Flow<String> {
        lastBody = request.jsonBody
        return payloads.asFlow()
    }
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

    private var memory: String = ""
    override suspend fun readMemory(): String = memory
    override suspend fun writeMemory(content: String): String { memory = content; return "saved ${content.length} chars" }
    override suspend fun fetchUrl(url: String, maxChars: Int): String = "content of $url"
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
    fun openRouterUsesOpenAiWireAtItsBaseUrl() {
        val transport = CapturingTransport(payloads = listOf("[DONE]"))
        var sseUrl: String? = null
        val recording = object : LlmTransport {
            override fun sse(request: SseRequest): Flow<String> {
                sseUrl = request.url
                return transport.sse(request)
            }
        }
        runBlocking {
            OpenRouterProvider(recording).client(ProviderConfig("k"))
                .chat(LlmRequest("anthropic/claude-3.5-sonnet", "sys", listOf(LlmMessage.user("hi")))).toList()
        }
        assertEquals("https://openrouter.ai/api/v1/chat/completions", sseUrl)
        // OpenRouter is not the official OpenAI endpoint, so the request uses max_tokens (not max_completion_tokens).
        assertTrue(transport.lastBody!!.contains("\"max_tokens\""), transport.lastBody)
        assertTrue(transport.lastBody!!.contains("\"model\":\"anthropic/claude-3.5-sonnet\""), transport.lastBody)
    }

    @Test
    fun openAiSendsReasoningEffortOnlyWhenRequested() {
        val transport = CapturingTransport(payloads = listOf("[DONE]"))
        val client = OpenAiProvider(transport).client(ProviderConfig("k"))

        runBlocking {
            client.chat(LlmRequest("gpt-5.6-luna", null, listOf(LlmMessage.user("hi")), reasoningEffort = "none")).toList()
        }
        assertTrue(transport.lastBody!!.contains("\"reasoning_effort\":\"none\""), transport.lastBody)

        // Left null (the default), the field is absent so non-reasoning models aren't sent an unknown parameter.
        runBlocking {
            client.chat(LlmRequest("gpt-4o", null, listOf(LlmMessage.user("hi")))).toList()
        }
        assertTrue(!transport.lastBody!!.contains("reasoning_effort"), transport.lastBody)
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
    fun agentLoopForwardsReasoningEffort() {
        var captured: LlmRequest? = null
        val client = LlmClient { request ->
            captured = request
            listOf<LlmStreamEvent>(LlmStreamEvent.Completed(StopReason.END_TURN)).asFlow()
        }
        val loop = AgentLoop(
            client = client,
            model = "gpt-5.6-luna",
            tools = SimpleToolRegistry(builtinTools(FakeWorkspace())),
            gate = AllowAllGate,
            systemPrompt = { "system" },
            reasoningEffort = "none",
        )

        runBlocking { loop.send("hi", AgentEventSink { }) }

        assertEquals("none", captured?.reasoningEffort)
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

        // Gemini free tier: a per-minute RATE LIMIT reuses the same "exceeded your current quota / billing
        // details" wording a truly-exhausted paid quota would — but it is a transient 429 carrying a short
        // RetryInfo delay, so it must classify as a retryable rate limit, not permanent billing exhaustion.
        val geminiFreeTier = LlmErrors.parseHttp(
            429,
            """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"You exceeded your current quota, please check your plan and billing details. For more information, head to https://ai.google.dev/gemini-api/docs/rate-limits.","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"38s"}]}}""",
            null,
        )
        assertEquals(LlmErrorKind.RATE_LIMIT, geminiFreeTier.kind)
        assertEquals(38000L, geminiFreeTier.retryAfterMs)
        assertTrue(geminiFreeTier.retryable)

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

    @Test
    fun compactorElidesStaleToolResultsButKeepsRecentAndText() {
        val big = "x".repeat(10_000)
        val history = mutableListOf<LlmMessage>()
        // Six tool-call rounds; with keepRecentToolMessages = 4 the two oldest tool results are stale.
        repeat(6) { i ->
            history += LlmMessage.user("question $i")
            history += LlmMessage.assistant(listOf(ContentPart.ToolUse("c$i", "read_file", "{}")))
            history += LlmMessage.toolResult("c$i", big)
        }

        val compacted = HistoryCompactor().compact(history)
        val toolResults = compacted.flatMap { it.content }.filterIsInstance<ContentPart.ToolResultPart>()

        assertEquals(6, toolResults.size)
        assertEquals(2, toolResults.count { it.content.contains("characters elided to save context") })
        assertEquals(4, toolResults.count { it.content == big }, "the four most recent results stay verbatim")
        // User text is never elided.
        assertEquals(6, compacted.count { it.role == dev.ide.agent.LlmRole.USER })
        assertTrue(compacted.any { m -> m.content.any { it is ContentPart.Text && it.text == "question 0" } })
    }

    @Test
    fun geminiThinkingConfigReflectsBudget() {
        fun bodyFor(model: String, thinking: Boolean, budget: Int?): String {
            val transport = CapturingTransport()
            runBlocking {
                GeminiProvider(transport).client(ProviderConfig("k"))
                    .chat(LlmRequest(model, "sys", listOf(LlmMessage.user("hi")), thinking = thinking, thinkingBudget = budget))
                    .toList()
            }
            return transport.lastBody!!
        }

        // Thinking off -> budget 0 disables reasoning on a flash model.
        val off = bodyFor("gemini-2.5-flash", thinking = false, budget = null)
        assertTrue(off.contains("\"thinkingConfig\""), off)
        assertTrue(off.contains("\"thinkingBudget\":0"), off)

        // An explicit budget is forwarded verbatim.
        assertTrue(bodyFor("gemini-2.5-flash", thinking = true, budget = 512).contains("\"thinkingBudget\":512"))

        // 2.5 Pro cannot disable thinking; a 0 is clamped up to the minimum instead of being rejected.
        assertTrue(bodyFor("gemini-2.5-pro", thinking = false, budget = null).contains("\"thinkingBudget\":128"))

        // Thinking on with no explicit budget leaves the model default (no thinkingConfig emitted).
        assertTrue(!bodyFor("gemini-2.5-flash", thinking = true, budget = null).contains("thinkingConfig"))
    }

    @Test
    fun anthropicRequestSetsPromptCacheBreakpoints() {
        val transport = CapturingTransport()
        runBlocking {
            AnthropicProvider(transport).client(ProviderConfig("k")).chat(
                LlmRequest(
                    "claude-sonnet-5",
                    "You are a helpful coding agent.",
                    listOf(LlmMessage.user("hi")),
                    tools = listOf(ToolSpec("read_file", "Reads a file.", """{"type":"object","properties":{}}""")),
                    thinking = false,
                ),
            ).toList()
        }
        val body = transport.lastBody!!
        // The system block, the tool block, and the conversation prefix each carry an ephemeral cache breakpoint.
        assertTrue(body.contains("\"cache_control\""), body)
        assertTrue(body.contains("\"ephemeral\""), body)
    }

    @Test
    fun newToolsAreRegistered() {
        val names = SimpleToolRegistry(builtinTools(FakeWorkspace())).tools.map { it.spec.name }.toSet()
        listOf(
            "go_to_definition", "find_references", "project_diagnostics", "rename_symbol",
            "list_quick_fixes", "apply_quick_fix", "format_file", "organize_imports",
            "list_tasks", "run_task", "search_dependency", "read_memory", "write_memory",
            "web_fetch", "http_request",
        ).forEach { assertTrue(it in names, "missing tool: $it") }
    }

    @Test
    fun memoryToolsRoundTrip() {
        val reg = SimpleToolRegistry(builtinTools(FakeWorkspace()))
        runBlocking {
            reg.find("write_memory")!!.execute(JsonToolArgs(parseArgsObject("""{"content":"remember: use tabs"}""")))
            val out = reg.find("read_memory")!!.execute(JsonToolArgs(parseArgsObject("{}")))
            assertTrue(out.content.contains("use tabs"), out.content)
        }
    }

    @Test
    fun anthropicInjectsWebSearchToolOnlyWhenEnabled() {
        fun body(webSearch: Boolean): String {
            val transport = CapturingTransport()
            runBlocking {
                AnthropicProvider(transport).client(ProviderConfig("k")).chat(
                    LlmRequest("claude-sonnet-5", "sys", listOf(LlmMessage.user("hi")), webSearch = webSearch, thinking = false),
                ).toList()
            }
            return transport.lastBody!!
        }
        assertTrue(body(true).contains(AnthropicProvider.WEB_SEARCH_TOOL), "web search tool should be present")
        assertTrue(!body(false).contains(AnthropicProvider.WEB_SEARCH_TOOL), "web search tool should be absent when off")
    }

    @Test
    fun geminiInjectsSearchGroundingWhenEnabled() {
        val transport = CapturingTransport()
        runBlocking {
            GeminiProvider(transport).client(ProviderConfig("k")).chat(
                LlmRequest("gemini-2.5-flash", "sys", listOf(LlmMessage.user("hi")), webSearch = true),
            ).toList()
        }
        assertTrue(transport.lastBody!!.contains("google_search"), transport.lastBody)
    }

    @Test
    fun readToolsInOneTurnAllExecute() {
        val ws = FakeWorkspace(mutableMapOf("A.kt" to "aaa", "B.kt" to "bbb"))
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmStreamEvent.ToolCallCompleted("c1", "read_file", """{"path":"A.kt"}"""),
                    LlmStreamEvent.ToolCallCompleted("c2", "read_file", """{"path":"B.kt"}"""),
                    LlmStreamEvent.Completed(StopReason.TOOL_USE),
                ),
                listOf(
                    LlmStreamEvent.TextDelta("done"),
                    LlmStreamEvent.Completed(StopReason.END_TURN),
                ),
            ),
        )
        val loop = AgentLoop(client, "m", SimpleToolRegistry(builtinTools(ws)), AllowAllGate, { "sys" })

        val events = mutableListOf<AgentEvent>()
        runBlocking { loop.send("read both files", AgentEventSink { events += it }) }

        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>()
        assertEquals(2, finished.size, "both read tools should run in the one turn")
        assertTrue(finished.all { it.ok }, "both reads should succeed")
    }
}
