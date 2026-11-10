package dev.ide.android.e2e

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A loopback server in the TEST process standing in for two remote services, so the agent's real network
 * stack (okhttp, SSE parsing, kotlinx.serialization, the MCP SDK + Jackson) runs against something
 * deterministic with no API key:
 *  - an OpenAI-compatible chat endpoint (`POST /v1/chat/completions`, streamed) that always answers [REPLY];
 *  - a Streamable-HTTP MCP server at `/mcp` exposing one tool, [TOOL].
 * The app allows cleartext to 127.0.0.1 (network_security_config), and loopback is shared across apps.
 */
class MockAiServer : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"
    val chatRequests = AtomicInteger()
    val mcpRequests = AtomicInteger()

    init {
        thread(name = "mock-ai", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val out = client.getOutputStream()
        val requestLine = input.readLine() ?: return
        val (method, path) = requestLine.split(" ").let { it[0] to it[1].substringBefore('?') }
        var length = 0
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toInt()
        }
        val body = CharArray(length).also { var read = 0; while (read < length) { val n = input.read(it, read, length - read); if (n < 0) break; read += n } }.let(::String)
        when {
            method == "POST" && path.endsWith("/chat/completions") -> { chatRequests.incrementAndGet(); chat(out) }
            method == "GET" && path.endsWith("/models") -> respond(out, 200, "application/json", """{"object":"list","data":[{"id":"$MODEL","object":"model"}]}""")
            path.startsWith("/mcp") -> { mcpRequests.incrementAndGet(); mcp(out, method, body) }
            else -> respond(out, 404, "text/plain", "not found")
        }
    }

    private fun chat(out: OutputStream) {
        fun chunk(delta: String, finish: String?) =
            """{"id":"e2e","object":"chat.completion.chunk","created":0,"model":"$MODEL","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""
        val events = listOf(
            chunk("""{"role":"assistant","content":""}""", null),
            chunk("""{"content":"$REPLY"}""", null),
            chunk("{}", "stop"),
        )
        val sse = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        respond(out, 200, "text/event-stream", sse)
    }

    private fun mcp(out: OutputStream, method: String, body: String) {
        if (method == "GET") return respond(out, 405, "text/plain", "")
        if (method == "DELETE") return respond(out, 200, "text/plain", "")
        val rpc = Regex(""""method"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val id = Regex(""""id"\s*:\s*("[^"]*"|\d+)""").find(body)?.groupValues?.get(1)
            ?: return respond(out, 202, "text/plain", "") // a notification
        val result = when (rpc) {
            "initialize" -> {
                val version = Regex(""""protocolVersion"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: "2025-06-18"
                """{"protocolVersion":"$version","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"e2e-mock","version":"1.0.0"}}"""
            }
            "tools/list" -> """{"tools":[{"name":"$TOOL","description":"Echoes its input (e2e mock).","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}"""
            "tools/call" -> """{"content":[{"type":"text","text":"echo"}],"isError":false}"""
            else -> "{}"
        }
        respond(out, 200, "application/json", """{"jsonrpc":"2.0","id":$id,"result":$result}""", extra = "Mcp-Session-Id: e2e-session\r\n")
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = mapOf(200 to "OK", 202 to "Accepted", 404 to "Not Found", 405 to "Method Not Allowed")[code] ?: "OK"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    override fun close() = socket.close()

    companion object {
        const val MODEL = "e2e-mock"
        const val REPLY = "E2E-PONG from the mock model"
        const val TOOL = "e2e_echo"
    }
}
