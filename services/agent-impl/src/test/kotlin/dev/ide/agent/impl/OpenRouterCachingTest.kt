package dev.ide.agent.impl

import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Prompt caching through OpenRouter: what each model family gets in the request, and that cache reads and writes
 * come back out of the usage chunk.
 */
class OpenRouterCachingTest {

    private class Recording(val chunks: List<String> = listOf("[DONE]")) : LlmTransport {
        val bodies = mutableListOf<JsonObject>()
        override fun sse(request: SseRequest): Flow<String> {
            bodies += AgentJson.parseToJsonElement(request.jsonBody).asObj()!!
            return flowOf(*chunks.toTypedArray())
        }
    }

    private fun request(model: String) = LlmRequest(model, "You are a coding agent.", listOf(LlmMessage.user("hi")))

    private fun systemBlock(body: JsonObject): JsonObject? =
        body["messages"].asArr()?.firstOrNull().asObj()?.takeIf { it["role"].asStr() == "system" }
            ?.get("content").asArr()?.firstOrNull().asObj()

    @Test
    fun claudeGetsALongLivedSystemBreakpointAndTheAutomaticTail() {
        runBlocking {
            val t = Recording()
            OpenRouterProvider(t).client(ProviderConfig("k")).chat(request("anthropic/claude-sonnet-5")).toList()
            val body = t.bodies.single()
            val marker = assertNotNull(systemBlock(body)?.get("cache_control").asObj())
            assertEquals("ephemeral", marker["type"].asStr())
            assertEquals("1h", marker["ttl"].asStr())
            // The request-level marker caches the conversation tail (5-minute default, after the 1h one).
            assertEquals("ephemeral", body["cache_control"].asObj()?.get("type").asStr())
            assertNull(body["cache_control"].asObj()?.get("ttl"))
        }
    }

    @Test
    fun qwenOnAlibabaGetsOnlyTheSystemBreakpoint() {
        runBlocking {
            val t = Recording()
            OpenRouterProvider(t).client(ProviderConfig("k")).chat(request("qwen/qwen3-coder-plus")).toList()
            val body = t.bodies.single()
            val marker = assertNotNull(systemBlock(body)?.get("cache_control").asObj())
            assertNull(marker["ttl"], "Alibaba's TTL is fixed")
            assertNull(body["cache_control"], "request-level caching is Anthropic-only")
        }
    }

    @Test
    fun selfCachingModelsAreSentNoMarkers() {
        runBlocking {
            val t = Recording()
            OpenRouterProvider(t).client(ProviderConfig("k")).chat(request("qwen/qwen3-coder:free")).toList()
            val body = t.bodies.single()
            assertEquals("You are a coding agent.", body["messages"].asArr()?.first().asObj()?.get("content").asStr())
            assertNull(body["cache_control"])
        }
    }

    @Test
    fun aConversationKeepsOneSessionIdAndANewOneGetsItsOwn() {
        runBlocking {
            val t = Recording()
            val client = OpenRouterProvider(t).client(ProviderConfig("k"))
            client.chat(request("deepseek/deepseek-chat")).toList()
            client.chat(request("deepseek/deepseek-chat")).toList()
            OpenRouterProvider(t).client(ProviderConfig("k")).chat(request("deepseek/deepseek-chat")).toList()
            val ids = t.bodies.map { assertNotNull(it["session_id"].asStr()) }
            assertEquals(ids[0], ids[1])
            assertNotEquals(ids[0], ids[2])
        }
    }

    @Test
    fun aPlainGatewaySendsNoSessionIdOrMarkers() {
        runBlocking {
            val t = Recording()
            OpenAiProvider(t).client(ProviderConfig("", baseUrl = "http://127.0.0.1:11434/v1")).chat(request("claude-local")).toList()
            val body = t.bodies.single()
            assertNull(body["session_id"])
            assertNull(body["cache_control"])
        }
    }

    @Test
    fun cacheReadsAndWritesAreSplitOutOfPromptTokens() {
        runBlocking {
            val usageChunk = """{"choices":[],"usage":{"prompt_tokens":12000,"completion_tokens":300,
                "prompt_tokens_details":{"cached_tokens":9000,"cache_write_tokens":2500}}}"""
            val t = Recording(listOf(usageChunk, "[DONE]"))
            val events = OpenRouterProvider(t).client(ProviderConfig("k")).chat(request("anthropic/claude-sonnet-5")).toList()
            val usage = events.filterIsInstance<LlmStreamEvent.Usage>().single().usage
            assertEquals(TokenUsage(inputTokens = 500, outputTokens = 300, cacheReadTokens = 9000, cacheWriteTokens = 2500), usage)
            assertEquals(12000, usage.promptTokens)
        }
    }
}
