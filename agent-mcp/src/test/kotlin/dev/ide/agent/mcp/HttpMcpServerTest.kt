package dev.ide.agent.mcp

import dev.ide.agent.AllowAllGate
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.impl.builtinTools
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end Streamable HTTP tests: the real SDK HTTP client (with its 405-on-GET fallback to
 * request-response mode) talks to [HttpStreamableServerTransportProvider] over an in-process socket, so
 * the whole wire path a remote opencode client will exercise is covered offline.
 */
class HttpMcpServerTest {

    @Test
    fun `streamable HTTP handshake and tool calls work end to end`() {
        val fake = CodeAssistMcpServerTest.FakeWorkspace("src/Main.kt" to "fun main() { println(\"hi\") }")
        val server = CodeAssistMcpServer.startHttpServer(
            workspace = fake,
            port = 0,
            tools = SimpleToolRegistry(builtinTools(fake)),
            gate = AllowAllGate,
        )
        val client = McpClient.sync(
            HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:${server.port}")
                .jsonMapper(McpJsonDefaults.getMapper())
                .build(),
        ).build()

        try {
            val init = client.initialize()
            assertEquals(CodeAssistMcpServer.DEFAULT_SERVER_NAME, init.serverInfo().name())

            val toolNames = client.listTools().tools().map { it.name() }.toSet()
            assertTrue("read_file" in toolNames)
            assertTrue("edit_file" in toolNames)

            val result = client.callTool(
                McpSchema.CallToolRequest.builder("read_file")
                    .arguments(mapOf("path" to "src/Main.kt"))
                    .build(),
            )
            assertFalse(result.isError())
            val text = result.content().joinToString("\n") { (it as McpSchema.TextContent).text() }
            assertTrue("fun main" in text)

            val edited = client.callTool(
                McpSchema.CallToolRequest.builder("edit_file")
                    .arguments(mapOf("path" to "src/Main.kt", "old_string" to "hi", "new_string" to "hey"))
                    .build(),
            )
            assertFalse(edited.isError())
            assertEquals("fun main() { println(\"hey\") }", fake.readNow("src/Main.kt"))
        } finally {
            client.closeGracefully()
            server.close()
        }
    }

    @Test
    fun `second client gets its own session`() {
        val fake = CodeAssistMcpServerTest.FakeWorkspace("a.txt" to "a")
        val server = CodeAssistMcpServer.startHttpServer(fake, port = 0)

        val first = newClient(server.port)
        val second = newClient(server.port)
        try {
            first.initialize()
            second.initialize()
            assertTrue(first.listTools().tools().isNotEmpty())
            assertTrue(second.listTools().tools().isNotEmpty())
        } finally {
            first.closeGracefully()
            second.closeGracefully()
            server.close()
        }
    }

    @Test
    fun `raw HTTP wire format matches the streamable transport contract`() {
        val fake = CodeAssistMcpServerTest.FakeWorkspace()
        val server = CodeAssistMcpServer.startHttpServer(fake, port = 0)
        val base = "http://127.0.0.1:${server.port}/mcp"
        val http = java.net.http.HttpClient.newHttpClient()
        try {
            val get = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(base))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(405, get.statusCode(), "GET stream must be refused so clients fall back to request-response mode")

            val initBody =
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}"""
            val init = http.send(post(base, initBody, null), java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals(200, init.statusCode())
            val sessionId = init.headers().firstValue("mcp-session-id").orElseThrow()
            assertTrue(init.body().contains("\"serverInfo\""))
            assertTrue(init.body().contains("\"codeassist\""))

            val toolsBody = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val tools = http.send(post(base, toolsBody, sessionId), java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals(200, tools.statusCode())
            assertTrue(tools.body().contains("\"read_file\""))

            val missing = http.send(post(base, toolsBody, null), java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals(404, missing.statusCode(), "a request without a session id must be rejected, body: ${missing.body()}")
        } finally {
            server.close()
        }
    }

    private fun post(url: String, body: String, sessionId: String?): java.net.http.HttpRequest {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId)
        return builder.build()
    }

    private fun newClient(port: Int) = McpClient.sync(
        HttpClientStreamableHttpTransport
            .builder("http://127.0.0.1:$port")
            .jsonMapper(McpJsonDefaults.getMapper())
            .build(),
    ).build()
}
