package dev.ide.agent.mcp

import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import io.modelcontextprotocol.json.McpJsonDefaults
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpToolMappingTest {

    private val mapper = McpJsonDefaults.getMapper()

    @Test
    fun `tool spec maps to an mcp tool preserving name description and schema`() {
        val spec = ToolSpec(
            "read_file",
            "Read a file's current text.",
            toolSchema {
                string("path", "File path.")
                integer("start_line", "First line (1-based).", required = false)
            },
        )

        val tool = spec.toMcpTool(mapper)

        assertEquals("read_file", tool.name())
        assertEquals("Read a file's current text.", tool.description())
        val schema = tool.inputSchema()
        assertEquals("object", schema["type"])
        assertTrue((schema["properties"] as Map<*, *>).containsKey("path"))
        assertEquals(listOf("path"), schema["required"])
    }

    @Test
    fun `blank parameters map to an empty input schema`() {
        val tool = ToolSpec("ping", "Pings.", "").toMcpTool(mapper)
        assertEquals(emptyMap<String, Any>(), tool.inputSchema())
    }

    @Test
    fun `mcp args are read through the tool args accessors with coercion`() {
        val args = MapToolArgs(
            mapOf(
                "path" to "Main.kt",
                "limit" to 5,
                "verbose" to true,
                "tags" to listOf("a", "b"),
            ),
        )

        assertEquals("Main.kt", args.string("path"))
        assertEquals(5, args.int("limit"))
        assertTrue(args.boolean("verbose"))
        assertEquals(listOf("a", "b"), args.stringList("tags"))
        assertNull(args.optString("missing"))
        assertEquals("5", args.optString("limit"), "numbers coerce to their string form")
        assertEquals("Main.kt", args.optString("path"))
    }

    @Test
    fun `missing required argument throws`() {
        val args = MapToolArgs(emptyMap())
        val thrown = runCatching { args.string("path") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertEquals("Missing required argument 'path'.", thrown.message)
    }

    @Test
    fun `tool result maps to call tool result with error flag`() {
        val ok = ToolExecutionResult.ok("done").toCallToolResult()
        assertFalse(ok.isError())
        assertEquals("done", (ok.content().single() as io.modelcontextprotocol.spec.McpSchema.TextContent).text())

        val err = ToolExecutionResult.error("boom").toCallToolResult()
        assertTrue(err.isError())
        assertEquals("boom", (err.content().single() as io.modelcontextprotocol.spec.McpSchema.TextContent).text())
    }
}
