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

    @Test
    fun `a body with non-ASCII text is framed by bytes, not chars`() {
        val fake = CodeAssistMcpServerTest.FakeWorkspace("a.kt" to "val greeting = \"hi\"")
        val server = CodeAssistMcpServer.startHttpServer(fake, port = 0)
        val http = java.net.http.HttpClient.newHttpClient()
        try {
            val base = "http://127.0.0.1:${server.port}/mcp"
            val session = initialize(http, base)
            // Content-Length counts bytes, and this payload runs well ahead of its char count, so a
            // char-counted read would still be waiting for the difference when the client gave up.
            val call =
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"edit_file","arguments":{"path":"a.kt","old_string":"hi","new_string":"héllo 🎉 こんにちは"}}}"""
            val response = http.send(post(base, call, session), java.net.http.HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("val greeting = \"héllo 🎉 こんにちは\"", fake.readNow("a.kt"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `a foreign Origin is refused so a rebound hostname cannot drive the server`() {
        val server = CodeAssistMcpServer.startHttpServer(CodeAssistMcpServerTest.FakeWorkspace(), port = 0)
        val http = java.net.http.HttpClient.newHttpClient()
        try {
            val base = "http://127.0.0.1:${server.port}/mcp"
            val foreign = http.send(
                post(base, INIT_BODY, null, origin = "http://evil.example"),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(403, foreign.statusCode(), "a page on another origin must not reach the tools")

            val local = http.send(
                post(base, INIT_BODY, null, origin = "http://127.0.0.1:5173"),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, local.statusCode(), "a loopback origin is the local client and stays allowed")
        } finally {
            server.close()
        }
    }

    @Test
    fun `an oversized Content-Length is refused instead of allocated`() {
        val server = CodeAssistMcpServer.startHttpServer(CodeAssistMcpServerTest.FakeWorkspace(), port = 0)
        try {
            java.net.Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 20_000
                socket.getOutputStream().write(
                    "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 2000000000\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                val status = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
                assertTrue(status.orEmpty().startsWith("HTTP/1.1 413"), "expected 413, got: $status")
            }
        } finally {
            server.close()
        }
    }

    private fun post(
        url: String,
        body: String,
        sessionId: String?,
        origin: String? = null,
    ): java.net.http.HttpRequest {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            // A deadline, so a framing regression fails the test instead of hanging CI on a read that
            // will never be satisfied.
            .timeout(java.time.Duration.ofSeconds(20))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId)
        if (origin != null) builder.header("Origin", origin)
        return builder.build()
    }

    /** Runs the initialize handshake over raw HTTP and returns the session id the server handed out. */
    private fun initialize(http: java.net.http.HttpClient, base: String): String {
        val response = http.send(post(base, INIT_BODY, null), java.net.http.HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        return response.headers().firstValue("mcp-session-id").orElseThrow()
    }

    private companion object {
        const val INIT_BODY =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}"""
    }

    private fun newClient(port: Int) = McpClient.sync(
        HttpClientStreamableHttpTransport
            .builder("http://127.0.0.1:$port")
            .jsonMapper(McpJsonDefaults.getMapper())
            .build(),
    ).build()
}
