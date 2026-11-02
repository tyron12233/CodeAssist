package dev.ide.agent.impl

import dev.ide.agent.ToolSpec
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
}
