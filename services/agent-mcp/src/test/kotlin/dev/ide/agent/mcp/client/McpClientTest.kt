package dev.ide.agent.mcp.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ide.agent.AllowAllGate
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.ToolArgs
import dev.ide.agent.impl.builtinTools
import dev.ide.agent.mcp.CodeAssistMcpServer
import dev.ide.agent.mcp.CodeAssistMcpServerTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpClientTest {

    @Test
    fun `streamable HTTP client talks to the CodeAssist MCP server`() {
        val fake = CodeAssistMcpServerTest.FakeWorkspace(mutableMapOf("src/Main.kt" to "fun main() { println(\"hi\") }"))
        val server = CodeAssistMcpServer.startHttpServer(fake, port = 0, tools = SimpleToolRegistry(builtinTools(fake)), gate = AllowAllGate)
        val client = McpClient(StreamableHttpTransport("http://127.0.0.1:${server.port}/mcp"))
        try {
            runBlocking {
                client.connect()
                assertEquals(CodeAssistMcpServer.DEFAULT_SERVER_NAME, client.serverName)
                val tools = client.listTools()
                assertTrue(tools.any { it.name == "read_file" })
                val result = client.callTool("read_file", buildJsonObject { put("path", "src/Main.kt") })
                assertFalse(result.isError)
                assertTrue("fun main" in result.text, result.text)
            }
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `stdio client runs a local server process`() {
        val project = Files.createTempDirectory("mcp-stdio").toFile()
        File(project, "hello.txt").writeText("hello from disk")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val transport = StdioTransport(
            java,
            listOf("-cp", System.getProperty("java.class.path"), "dev.ide.agent.mcp.MainKt", "--project", project.absolutePath),
        )
        val client = McpClient(transport)
        try {
            runBlocking {
                client.connect(timeoutMs = 60_000)
                val result = client.callTool("read_file", buildJsonObject { put("path", "hello.txt") })
                assertTrue("hello from disk" in result.text, result.text + "\n" + transport.stderr())
            }
        } finally {
            client.close()
            project.deleteRecursively()
        }
    }

    @Test
    fun `an HTTP server that only speaks the old SSE transport is reached through the fallback`() {
        FakeSseServer().use { server ->
            withManager { manager, _ ->
                manager.update(listOf(McpServerConfig("old", McpTransportKind.HTTP, url = server.url)), null)
                val state = awaitStatus(manager, "old", McpServerState.Status.CONNECTED)
                assertEquals(listOf("echo"), state.tools.map { it.name })
                val tool = manager.tools().single()
                assertEquals("mcp__old__echo", tool.spec.name)
                val out = runBlocking { tool.execute(args("""{"text":"ping"}""")) }
                assertEquals("echo: ping", out.content)
            }
        }
    }

    @Test
    fun `OAuth sign-in discovers, registers, exchanges the code, and connects`() {
        FakeOAuthServer().use { server ->
            withManager { manager, store ->
                manager.update(listOf(McpServerConfig("secure", McpTransportKind.HTTP, url = server.mcpUrl)), null)
                awaitStatus(manager, "secure", McpServerState.Status.NEEDS_AUTH)

                val attempt = runBlocking { manager.beginSignIn("secure", "http://localhost:4567/mcp-callback") }
                val url = attempt.authorizeUrl.toHttpUrl()
                assertEquals("registered-client", url.queryParameter("client_id"))
                assertEquals("S256", url.queryParameter("code_challenge_method"))
                assertEquals(server.mcpUrl, url.queryParameter("resource"))
                assertEquals("tools.read", url.queryParameter("scope"))
                server.expectedChallenge = url.queryParameter("code_challenge")

                runBlocking { manager.completeSignIn("secure", attempt, "the-code") }
                val state = awaitStatus(manager, "secure", McpServerState.Status.CONNECTED)
                assertTrue(state.signedIn)
                assertNotNull(store.get(McpServerConfig("secure", McpTransportKind.HTTP, url = server.mcpUrl).identity))
                val out = runBlocking { manager.tools().single().execute(args("""{"text":"hi"}""")) }
                assertEquals("echo: hi", out.content)
            }
        }
    }

    @Test
    fun `an expired sign-in is refreshed before connecting`() {
        FakeOAuthServer().use { server ->
            withManager { manager, store ->
                val config = McpServerConfig("secure", McpTransportKind.HTTP, url = server.mcpUrl)
                store.put(
                    config.identity,
                    McpTokens("stale", refreshToken = "refresh-1", expiresAtMs = 1, tokenEndpoint = server.base + "/token", clientId = "registered-client"),
                )
                manager.update(listOf(config), null)
                awaitStatus(manager, "secure", McpServerState.Status.CONNECTED)
                assertEquals(FakeOAuthServer.GOOD_TOKEN, store.get(config.identity)?.accessToken)
            }
        }
    }

    @Test
    fun `local servers are refused where stdio is not allowed`() {
        withManager(stdio = false) { manager, _ ->
            manager.update(listOf(McpServerConfig("files", McpTransportKind.STDIO, command = "npx")), null)
            val state = awaitStatus(manager, "files", McpServerState.Status.UNSUPPORTED)
            assertTrue(state.error!!.contains("desktop"))
        }
    }

    @Test
    fun `mcp json files in the Claude Code format parse and round-trip`() {
        val parsed = McpServerConfig.parseFile(
            """{"mcpServers":{
                 "linear":{"type":"http","url":"https://mcp.linear.app/mcp"},
                 "docs":{"url":"https://x.dev/sse","type":"sse","headers":{"Authorization":"Bearer t"}},
                 "files":{"command":"npx","args":["-y","pkg","."],"env":{"A":"1"}},
                 "broken":{"type":"http"}
               }}""",
        )
        assertEquals(listOf("linear", "docs", "files"), parsed.map { it.name })
        assertEquals(McpTransportKind.STDIO, parsed[2].transport)
        assertEquals(mapOf("Authorization" to "Bearer t"), parsed[1].headers)
        assertEquals(parsed, McpServerConfig.parseFile(McpServerConfig.toFile(parsed)))
    }

    @Test
    fun `third-party schemas are cut to what every provider accepts`() {
        val schema = Json.parseToJsonElement(
            """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object",
                "${'$'}defs":{"Labels":{"type":"array","items":{"type":"string"}}},
                "properties":{
                  "title":{"type":["string","null"],"description":"Issue title","minLength":1},
                  "labels":{"${'$'}ref":"#/${'$'}defs/Labels"},
                  "priority":{"enum":[1,2,3]},
                  "when":{"type":"string","format":"uri"}
                },
                "required":["title","ghost"],"additionalProperties":false}""",
        ).jsonObject
        val out = McpSchemas.normalize(schema)
        val props = out["properties"]!!.jsonObject
        assertEquals("string", props["title"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("minLength" in props["title"]!!.jsonObject)
        assertEquals("array", props["labels"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("enum" in props["priority"]!!.jsonObject)
        assertFalse("format" in props["when"]!!.jsonObject)
        assertEquals("""["title"]""", out["required"].toString())
        assertFalse("\$schema" in out || "additionalProperties" in out)
    }

    @Test
    fun `tool names stay unique and within the providers' limits`() {
        val taken = HashSet<String>()
        val a = McpRemoteTool.toolName("my server", "do.thing", taken).also { taken += it }
        val b = McpRemoteTool.toolName("my-server!", "do thing", taken)
        assertEquals("mcp__my_server__do_thing", a)
        assertEquals("mcp__my-server__do_thing", b)
        val long = McpRemoteTool.toolName("x".repeat(80), "y", emptySet())
        assertTrue(long.length <= 64)
    }

    @Test
    fun `WWW-Authenticate challenges parse`() {
        val p = McpOAuth.parseChallenge("""Bearer realm="mcp", resource_metadata="https://a.b/.well-known/oauth-protected-resource", scope="a b"""")
        assertEquals("https://a.b/.well-known/oauth-protected-resource", p["resource_metadata"])
        assertEquals("a b", p["scope"])
    }

    // --- helpers ---

    private fun withManager(stdio: Boolean = true, block: (McpManager, MemoryTokenStore) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = MemoryTokenStore()
        val manager = McpManager(scope, store, stdioAllowed = stdio)
        try {
            block(manager, store)
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    private fun awaitStatus(manager: McpManager, name: String, status: McpServerState.Status): McpServerState = runBlocking {
        try {
            withTimeout(20_000) { manager.states.first { list -> list.any { it.name == name && it.status == status } } }
                .first { it.name == name }
        } catch (e: Exception) {
            throw AssertionError("Expected $name to be $status, was ${manager.states.value}", e)
        }
    }

    private fun args(json: String) = object : ToolArgs {
        override fun string(key: String) = error("unused")
        override fun optString(key: String): String? = null
        override fun int(key: String) = error("unused")
        override fun optInt(key: String): Int? = null
        override fun boolean(key: String) = error("unused")
        override fun optBoolean(key: String): Boolean? = null
        override fun stringList(key: String): List<String> = emptyList()
        override fun raw() = json
    }

    class MemoryTokenStore : McpTokenStore {
        private val map = ConcurrentHashMap<String, McpTokens>()
        override fun get(identity: String) = map[identity]
        override fun put(identity: String, tokens: McpTokens) { map[identity] = tokens }
        override fun remove(identity: String) { map.remove(identity) }
    }
}

/** The answers a tiny MCP server with one `echo` tool gives. Null for a notification. */
private fun answer(msg: JsonObject): JsonObject? {
    val id = msg["id"] ?: return null
    val method = msg["method"]?.jsonPrimitive?.content
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        when (method) {
            "initialize" -> putJsonObject("result") {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", "fake"); put("version", "1") }
            }
            "tools/list" -> putJsonObject("result") {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        put("name", "echo")
                        put("description", "Echoes text")
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
                        }
                        putJsonObject("annotations") { put("readOnlyHint", true) }
                    })
                }
            }
            "tools/call" -> putJsonObject("result") {
                val text = msg["params"]!!.jsonObject["arguments"]!!.jsonObject["text"]!!.jsonPrimitive.content
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "echo: $text") }) }
            }
            else -> putJsonObject("error") { put("code", -32601); put("message", "no") }
        }
    }
}

private fun HttpExchange.body(): String = requestBody.readBytes().toString(Charsets.UTF_8)

private fun HttpExchange.respond(code: Int, body: String = "", type: String = "application/json") {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", type)
    sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
}

/** An MCP server on the 2024-11-05 HTTP+SSE transport: GET opens the stream, answers come back on it. */
private class FakeSseServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @Volatile private var stream: OutputStream? = null
    private val done = CountDownLatch(1)
    val url: String get() = "http://127.0.0.1:${server.address.port}/sse"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/sse") { ex ->
            if (ex.requestMethod != "GET") {
                ex.respond(405)
                return@createContext
            }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            out.write("event: endpoint\ndata: /messages?session=1\n\n".toByteArray())
            out.flush()
            stream = out
            done.await()
            runCatching { out.close() }
        }
        server.createContext("/messages") { ex ->
            val msg = Json.parseToJsonElement(ex.body()).jsonObject
            ex.respond(202)
            answer(msg)?.let { reply ->
                synchronized(this) {
                    stream?.apply { write("event: message\ndata: $reply\n\n".toByteArray()); flush() }
                }
            }
        }
        server.start()
    }

    override fun close() {
        done.countDown()
        server.stop(0)
    }
}

/** A Streamable HTTP MCP server behind OAuth, with its own protected-resource and authorization-server metadata. */
private class FakeOAuthServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val mcpUrl: String get() = "$base/mcp"
    @Volatile var expectedChallenge: String? = null

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/mcp") { ex ->
            if (ex.requestHeaders.getFirst("Authorization") != "Bearer $GOOD_TOKEN") {
                ex.responseHeaders.add("WWW-Authenticate", """Bearer resource_metadata="$base/.well-known/oauth-protected-resource/mcp"""")
                ex.respond(401)
                return@createContext
            }
            val reply = answer(Json.parseToJsonElement(ex.body()).jsonObject)
            if (reply == null) ex.respond(202) else ex.respond(200, reply.toString())
        }
        server.createContext("/.well-known/oauth-protected-resource/mcp") { ex ->
            ex.respond(200, """{"resource":"$mcpUrl","authorization_servers":["$base"],"scopes_supported":["tools.read"]}""")
        }
        server.createContext("/.well-known/oauth-authorization-server") { ex ->
            ex.respond(
                200,
                """{"issuer":"$base","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token",
                   "registration_endpoint":"$base/register","code_challenge_methods_supported":["S256"]}""",
            )
        }
        server.createContext("/register") { ex ->
            val body = Json.parseToJsonElement(ex.body()).jsonObject
            check(body["token_endpoint_auth_method"]?.jsonPrimitive?.content == "none")
            ex.respond(201, """{"client_id":"registered-client"}""")
        }
        server.createContext("/token") { ex ->
            val form = ex.body().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val ok = when (form["grant_type"]) {
                "authorization_code" -> form["code"] == "the-code" && form["client_id"] == "registered-client" &&
                    McpOAuth.challengeFor(form["code_verifier"].orEmpty()) == expectedChallenge && form["resource"] == mcpUrl
                "refresh_token" -> form["refresh_token"] == "refresh-1"
                else -> false
            }
            if (ok) {
                ex.respond(200, """{"access_token":"$GOOD_TOKEN","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh-2"}""")
            } else {
                ex.respond(400, """{"error":"invalid_grant","error_description":"bad ${JsonPrimitive(form.toString())}"}""")
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)

    companion object {
        const val GOOD_TOKEN = "good-token"
    }
}
