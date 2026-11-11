package dev.ide.agent.mcp

import dev.ide.agent.AllowAllGate
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.impl.builtinTools
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRelayClientTest {

    @Test
    fun `a new secret is 256 bits of base64url, and no two are alike`() {
        val a = McpRelayClient.newSecret()
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(a), a)
        assertFalse(a == McpRelayClient.newSecret())
        assertEquals("https://relay.example/c/$a/mcp", McpRelayClient.publicUrl("https://relay.example/", a))
    }

    @Test
    fun `an answer is split into frames that carry its status once and rejoin exactly`() {
        val body = "x".repeat(McpRelayClient.CHUNK_CHARS * 2 + 17)
        val frames = McpRelayClient.responseFrames("r1", 200, mapOf("content-type" to "application/json"), body)
            .map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(3, frames.size)
        assertEquals(listOf(0, 1, 2), frames.map { it.getValue("i").jsonPrimitive.int })
        assertTrue(frames.all { it.getValue("n").jsonPrimitive.int == 3 && it.getValue("id").jsonPrimitive.content == "r1" })
        assertEquals(200, frames[0].getValue("status").jsonPrimitive.int)
        assertTrue("status" !in frames[1] && "headers" !in frames[2], "only the first frame carries the status and headers")
        assertEquals(body, frames.joinToString("") { it.getValue("data").jsonPrimitive.content })
        // An empty answer (a 202) is still one frame, or the relay would wait for it forever.
        assertEquals(1, McpRelayClient.responseFrames("r2", 202, emptyMap(), "").size)
    }

    /**
     * The whole path, against a running relay: `RELAY_TEST_URL=http://127.0.0.1:8799`, started with
     * `npx wrangler@4 dev --local --port 8799 --var RELAY_TEST_AUTH:1` in `supabase/cloudflare/mcp-relay`. The real
     * MCP SDK client plays Claude, calling the link's public URL; the answer comes from this process's server.
     */
    @Test
    fun `an MCP client reaches the hosted server through the relay`() {
        val relay = System.getenv("RELAY_TEST_URL")
        assumeTrue(!relay.isNullOrBlank(), "no relay running; set RELAY_TEST_URL")
        val fake = CodeAssistMcpServerTest.FakeWorkspace("src/Main.kt" to "fun main() { println(\"hi\") }")
        val server = CodeAssistMcpServer.startHttpServer(workspace = fake, port = 0, tools = SimpleToolRegistry(builtinTools(fake)), gate = AllowAllGate)
        val connected = CountDownLatch(1)
        val link = McpRelayClient(
            relayBaseUrl = relay!!, secret = McpRelayClient.newSecret(), localUrl = "http://127.0.0.1:${server.port}/mcp",
            token = { "test:relay-e2e" },
            onState = { if (it == McpRelayClient.State.CONNECTED) connected.countDown() },
        ).start()
        try {
            assertTrue(connected.await(15, TimeUnit.SECONDS), "the device connects; state=${link.state}")
            val client = McpClient.sync(
                HttpClientStreamableHttpTransport.builder(relay).endpoint(link.publicUrl.removePrefix(relay.trimEnd('/'))).jsonMapper(McpJsonDefaults.getMapper()).build(),
            ).build()
            try {
                assertEquals(CodeAssistMcpServer.DEFAULT_SERVER_NAME, client.initialize().serverInfo().name())
                val tools = client.listTools().tools().map { it.name() }
                assertTrue("read_file" in tools, "tools=$tools")
                val read = client.callTool(McpSchema.CallToolRequest("read_file", mapOf("path" to "src/Main.kt")))
                val text = read.content().filterIsInstance<McpSchema.TextContent>().joinToString { it.text() }
                assertTrue("println" in text, text)
            } finally {
                client.close()
            }
        } finally {
            link.close()
            server.close()
        }
    }
}
