package dev.ide.agent.impl

import dev.ide.agent.ToolSpec
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrictToolsTest {

    @Test
    fun `every built-in tool schema is still eligible for strict mode`() {
        val specs = (builtinTools(FakeWorkspace()) + askUserTool { null }).map { it.spec }
        val eligible = specs.filter { AnthropicProvider.strictToolNames(listOf(it)).isNotEmpty() }
        assertEquals(specs.map { it.name }, eligible.map { it.name })
    }

    @Test
    fun `an open third-party schema is never marked strict`() {
        val open = ToolSpec("mcp__x__create_issue", "d", """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}}}""")
        val nestedOpen = ToolSpec(
            "mcp__x__nested", "d",
            """{"type":"object","additionalProperties":false,"properties":{"o":{"type":"object","properties":{"z":{"type":"string"}}}}}""",
        )
        val closed = ToolSpec("read", "d", """{"type":"object","properties":{"p":{"type":"string"}},"required":["p"],"additionalProperties":false}""")
        val strict = AnthropicProvider.strictToolNames(listOf(open, nestedOpen, closed))
        assertFalse("mcp__x__create_issue" in strict)
        assertFalse("mcp__x__nested" in strict)
        assertTrue("read" in strict)
    }

    private fun tool(name: String, required: Int, optional: Int): ToolSpec {
        val props = (0 until required + optional).joinToString(",") { "\"p$it\":{\"type\":\"string\"}" }
        val req = (0 until required).joinToString(",") { "\"p$it\"" }
        return ToolSpec(name, "d", """{"type":"object","properties":{$props},"required":[$req],"additionalProperties":false}""")
    }

    /** The API refused the real tool set with "The compiled grammar is too large": optional parameters are what grow it. */
    @Test
    fun `strict tools stay inside the optional parameter budget and a tool that does not fit is skipped`() {
        val tools = listOf(tool("big", 1, 5), tool("wide", 1, 4), tool("mid", 2, 1), tool("plain", 2, 0), tool("small", 0, 1))
        val strict = AnthropicProvider.strictToolNames(tools)
        // big (5) fits; wide (4) would make 9 and is skipped; the ones after it still get in.
        assertEquals(setOf("big", "mid", "plain", "small"), strict)
    }

    @Test
    fun `the built-in tool set stays inside the optional parameter budget`() {
        val specs = (builtinTools(FakeWorkspace()) + askUserTool { null }).map { it.spec }
        val strict = AnthropicProvider.strictToolNames(specs)
        val optional = specs.filter { it.name in strict }.sumOf { spec ->
            val obj = AgentJson.parseToJsonElement(spec.parameters).asObj()!!
            obj["properties"].asObj()!!.size - obj["required"].asArr()!!.size
        }
        assertTrue(optional <= AnthropicProvider.MAX_STRICT_OPTIONAL_PARAMS, "optional=$optional")
        assertTrue(strict.size <= AnthropicProvider.MAX_STRICT_TOOLS)
    }

    @Test
    fun `a refused grammar is retried without strict and strict stays off afterwards`() {
        val bodies = mutableListOf<String>()
        val transport = object : LlmTransport {
            override fun sse(request: SseRequest): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
                bodies += request.jsonBody
                if ("\"strict\":true" in request.jsonBody) {
                    throw LlmHttpException(
                        "The compiled grammar is too large, which would cause performance issues. " +
                            "Simplify your tool schemas or reduce the number of strict tools.",
                        statusCode = 400,
                    )
                }
                emit("""{"type":"message_stop"}""")
            }
        }
        val client = AnthropicProvider(transport).client(dev.ide.agent.ProviderConfig("k"))
        val request = dev.ide.agent.LlmRequest("claude-haiku-5-5", "s", listOf(dev.ide.agent.LlmMessage.user("hi")), tools = listOf(tool("t", 1, 0)))
        val first = kotlinx.coroutines.runBlocking { client.chat(request).toList() }
        assertTrue(first.none { it is dev.ide.agent.LlmStreamEvent.Failed }, first.toString())
        assertTrue(first.any { it is dev.ide.agent.LlmStreamEvent.Completed })
        assertEquals(2, bodies.size)
        kotlinx.coroutines.runBlocking { client.chat(request).toList() }
        assertEquals(3, bodies.size)
        assertFalse("\"strict\"" in bodies.last())
    }
}
