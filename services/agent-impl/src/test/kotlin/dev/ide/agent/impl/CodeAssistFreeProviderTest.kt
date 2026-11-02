package dev.ide.agent.impl

import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CodeAssist Free AI: the Anthropic wire, sent to the proxy, with the session's token as the key.
 */
class CodeAssistFreeProviderTest {

    private class Recording : LlmTransport {
        val requests = mutableListOf<SseRequest>()
        override fun sse(request: SseRequest): Flow<String> {
            requests += request
            return flowOf()
        }
    }

    private val base = "https://example.supabase.co/functions/v1/ai-proxy"
    private val request = LlmRequest(CodeAssistFreeProvider.HAIKU, "system", listOf(LlmMessage.user("hi")))

    @Test
    fun sendsTheSessionTokenToTheProxyAndIgnoresTheConfiguredKey() {
        runBlocking {
            val t = Recording()
            var tokenCalls = 0
            val provider = CodeAssistFreeProvider(t, base) { tokenCalls++; "jwt-$tokenCalls" }
            val client = provider.client(ProviderConfig("placeholder"))
            client.chat(request).toList()
            client.chat(request).toList()

            assertEquals(2, t.requests.size)
            assertEquals("$base/v1/messages", t.requests[0].url)
            // Fetched per request, because the access token lasts an hour and a chat lasts longer.
            assertEquals("jwt-1", t.requests[0].headers["x-api-key"])
            assertEquals("jwt-2", t.requests[1].headers["x-api-key"])
            assertTrue(AgentJson.parseToJsonElement(t.requests[0].jsonBody).asObj()!!["model"].asStr() == CodeAssistFreeProvider.HAIKU)
        }
    }

    @Test
    fun signedOutFailsWithoutCallingTheProxy() {
        runBlocking {
            val t = Recording()
            val events = CodeAssistFreeProvider(t, base) { null }.client(ProviderConfig("placeholder")).chat(request).toList()
            assertEquals(0, t.requests.size)
            val failed = events.single() as LlmStreamEvent.Failed
            assertEquals(CodeAssistFreeProvider.SIGNED_OUT_MESSAGE, failed.message)
        }
    }
}

/** The API refuses more than 20 strict tools in one request; the agent has more tools than that. */
class StrictToolCapTest {
    private class Recording : LlmTransport {
        val bodies = mutableListOf<String>()
        override fun sse(request: SseRequest): Flow<String> {
            bodies += request.jsonBody
            return flowOf()
        }
    }

    @Test
    fun atMostTwentyToolsAreStrictAndTheyAreTheOnesWithArguments() {
        runBlocking {
            val tools = (1..34).map { i ->
                val props = (0 until (i % 4)).joinToString(",") { "\"p$it\":{\"type\":\"string\"}" }
                dev.ide.agent.ToolSpec("t$i", "tool $i", """{"type":"object","properties":{$props},"additionalProperties":false}""")
            }
            val t = Recording()
            val req = LlmRequest("claude-haiku-5-5", "s", listOf(LlmMessage.user("hi")), tools = tools)
            AnthropicProvider(t).client(ProviderConfig("k")).chat(req).toList()
            val sent = AgentJson.parseToJsonElement(t.bodies.single()).asObj()!!["tools"].asArr()!!.mapNotNull { it.asObj() }
            val strict = sent.filter { it["strict"]?.toString() == "true" }.map { it["name"].asStr()!! }
            assertEquals(AnthropicProvider.MAX_STRICT_TOOLS, strict.size)
            // Every tool with 3 or 2 arguments (17 of them) is strict before any 1-argument tool; no 0-argument tool is.
            val argsOf = { name: String -> name.removePrefix("t").toInt() % 4 }
            assertTrue(strict.none { argsOf(it) == 0 })
            assertEquals(17, strict.count { argsOf(it) >= 2 })
        }
    }
}
