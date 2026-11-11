package dev.ide.agent.mcp

import dev.ide.agent.AgentTool
import dev.ide.agent.AllowAllGate
import dev.ide.agent.ContentPart
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.impl.builtinTools
import dev.ide.agent.toolSchema
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a client such as Claude is given beyond the tools themselves: how to use them (the server's
 * instructions), which ones are safe to call unasked (the hints), a result for a call that outlasts its request,
 * and the pictures a tool produced. Each through the real SDK client over HTTP.
 */
class McpClientGuidanceTest {

    private val fake = CodeAssistMcpServerTest.FakeWorkspace("src/Main.kt" to "fun main() {}")

    private fun tool(name: String, mutating: Boolean = false, run: suspend () -> ToolExecutionResult) = object : AgentTool {
        override val spec = ToolSpec(name, "test tool $name", toolSchema { })
        override val mutating = mutating
        override suspend fun execute(args: ToolArgs) = run()
    }

    private fun <T> withClient(extra: List<AgentTool> = emptyList(), maxWaitMs: Long = 5_000, notes: List<String> = emptyList(), block: (McpSyncClient, McpSchema.InitializeResult) -> T): T {
        val server = CodeAssistMcpServer.startHttpServer(
            workspace = fake, port = 0, tools = SimpleToolRegistry(builtinTools(fake) + extra), gate = AllowAllGate,
            notes = notes, maxWaitMs = maxWaitMs,
        )
        val client = McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://127.0.0.1:${server.port}").jsonMapper(McpJsonDefaults.getMapper()).build(),
        ).build()
        try {
            return block(client, client.initialize())
        } finally {
            client.close()
            server.close()
        }
    }

    private fun McpSchema.CallToolResult.text() = content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }

    @Test
    fun `the server tells the client's model how to use it, with the host's notes after`() {
        withClient(notes = listOf("Modules: use create_module.")) { _, init ->
            val text = init.instructions()
            assertTrue("project_overview" in text && "task_status" in text && "no shell" in text.lowercase(), text)
            assertTrue(text.trimEnd().endsWith("Modules: use create_module."), text)
            assertTrue(text.length < 3_000, "short enough that a client keeps it: ${text.length}")
        }
    }

    @Test
    fun `tools say whether they only read, can destroy work, or reach the network`() {
        withClient(extra = listOf(tool("remove_module", mutating = true) { ToolExecutionResult.ok("") })) { client, _ ->
            val hints = client.listTools().tools().associate { it.name() to it.annotations() }
            fun readOnly(name: String) = hints.getValue(name).readOnlyHint()
            fun destructive(name: String) = hints.getValue(name).destructiveHint()
            assertEquals(true, readOnly("read_file"))
            assertEquals(true, readOnly("project_overview"))
            assertEquals(true, readOnly("task_status"))
            assertEquals(false, readOnly("edit_file"))
            assertEquals(false, destructive("edit_file"), "an edit is a change, not a loss")
            assertEquals(false, readOnly("todo_write"), "not gated, but it is not a read either")
            assertEquals(true, destructive("delete_path"))
            assertEquals(true, destructive("remove_module"))
            assertEquals(false, destructive("read_file"))
            assertEquals(true, hints.getValue("web_fetch").openWorldHint())
            assertEquals(false, hints.getValue("read_file").openWorldHint())
            assertTrue(hints.values.all { it != null }, "every tool carries hints")
        }
    }

    @Test
    fun `a call that outlasts its request is handed back as a job and its result is kept`() {
        var runs = 0
        val slow = tool("slow_build", mutating = true) { runs++; delay(900); ToolExecutionResult.ok("BUILD OK") }
        withClient(extra = listOf(slow), maxWaitMs = 150) { client, _ ->
            val first = client.callTool(McpSchema.CallToolRequest("slow_build", emptyMap()))
            assertFalse(first.isError(), first.text())
            val id = Regex("job=\"(job-\\d+)\"").find(first.text())?.groupValues?.get(1)
            assertTrue(id != null && "Still running" in first.text(), first.text())

            val early = client.callTool(McpSchema.CallToolRequest("task_status", mapOf("job" to id, "wait_seconds" to 0)))
            assertTrue("Still running" in early.text(), early.text())

            val done = client.callTool(McpSchema.CallToolRequest("task_status", mapOf("job" to id, "wait_seconds" to 10)))
            assertEquals("BUILD OK", done.text())
            assertEquals(1, runs, "the work ran once; waiting on it did not start it again")

            val again = client.callTool(McpSchema.CallToolRequest("task_status", mapOf("job" to id)))
            assertTrue(again.isError() && "No job" in again.text(), again.text())
        }
    }

    @Test
    fun `a quick call is answered directly, and a failing one says why`() {
        val quick = tool("quick") { ToolExecutionResult.ok("done") }
        val broken = tool("broken") { error("the disk is full") }
        withClient(extra = listOf(quick, broken)) { client, _ ->
            assertEquals("done", client.callTool(McpSchema.CallToolRequest("quick", emptyMap())).text())
            val failed = client.callTool(McpSchema.CallToolRequest("broken", emptyMap()))
            assertTrue(failed.isError() && "the disk is full" in failed.text(), failed.text())
        }
    }

    @Test
    fun `a tool's images reach the client as images`() {
        val shot = tool("shot") { ToolExecutionResult("Screenshot of MainScreen", images = listOf(ContentPart.Image("image/png", "iVBORw0KGgo="))) }
        withClient(extra = listOf(shot)) { client, _ ->
            val result = client.callTool(McpSchema.CallToolRequest("shot", emptyMap()))
            assertEquals("Screenshot of MainScreen", result.text())
            val image = result.content().filterIsInstance<McpSchema.ImageContent>().single()
            assertEquals("image/png", image.mimeType())
            assertEquals("iVBORw0KGgo=", image.data())
        }
    }
}
