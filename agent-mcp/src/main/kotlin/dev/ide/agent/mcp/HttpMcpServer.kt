package dev.ide.agent.mcp

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStreamableServerSession
import io.modelcontextprotocol.spec.McpStreamableServerTransport
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import reactor.core.publisher.Mono
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * An [McpStreamableServerTransportProvider] that speaks MCP's Streamable HTTP transport over a plain
 * `java.net.ServerSocket` HTTP/1.1 server — no servlet container required, so it runs in-process in the IDE
 * (or any pure-JVM host). The listener is loopback-only; a desktop client reaches it through
 * `adb forward tcp:8765 tcp:8765`, which dials the device's own 127.0.0.1, followed by an opencode
 * `remote` MCP server pointing at `http://127.0.0.1:8765/mcp`.
 *
 * Sessions are created on the first `initialize` request and keyed by the `Mcp-Session-Id` the server
 * hands out; every later request/notification/response for a session is routed by that header, mirroring
 * the SDK's servlet provider. Responses to requests are returned as a JSON-RPC body (request-response
 * mode); `GET` streams and `DELETE` session teardown follow the protocol (the SDK clients fall back to
 * request-response mode on the `GET` 405). Message handling is serialized per session, so the blocking
 * sync server never sees interleaved calls.
 */
class HttpStreamableServerTransportProvider(
    private val mapper: McpJsonMapper = McpJsonDefaults.getMapper(),
) : McpStreamableServerTransportProvider {

    private val sessions = ConcurrentHashMap<String, McpStreamableServerSession>()
    private val socketServer = HttpServerSocket(this)

    @Volatile
    private var sessionFactory: McpStreamableServerSession.Factory? = null

    /** Starts the HTTP listener on [port] (0 picks an ephemeral port) and returns the bound port. */
    fun bind(port: Int): Int = socketServer.bind(port)

    /** The port the listener is bound to, or -1 before [bind]. */
    val boundPort: Int get() = socketServer.boundPort

    /** Stops the HTTP listener and releases the socket. */
    override fun close() {
        socketServer.close()
        sessions.clear()
    }

    override fun setSessionFactory(sessionFactory: McpStreamableServerSession.Factory) {
        this.sessionFactory = sessionFactory
    }

    override fun notifyClients(method: String, params: Any?): Mono<Void> =
        sessions.values
            .map { it.sendNotification(method, params) }
            .fold(Mono.empty<Void>()) { acc, m -> acc.then(m) }

    override fun notifyClient(sessionId: String, method: String, params: Any?): Mono<Void> {
        val session = sessions[sessionId] ?: return Mono.empty()
        return session.sendNotification(method, params)
    }

    override fun closeGracefully(): Mono<Void> = Mono.fromRunnable { close() }

    // --- message routing (called from the socket server's per-connection threads) ---

    internal fun handlePost(body: String, sessionIdHeader: String?): HttpResponse {
        val message = try {
            McpSchema.deserializeJsonRpcMessage(mapper, body)
        } catch (e: Exception) {
            return errorResponse(McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid message format: ${e.message}")
        }

        if (message is McpSchema.JSONRPCRequest && message.method() == McpSchema.METHOD_INITIALIZE) {
            return handleInitialize(message)
        }

        val sessionId = sessionIdHeader?.trim()?.takeIf { it.isNotEmpty() }
        if (sessionId == null) {
            return errorResponse(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "Session ID required in mcp-session-id header")
        }
        val session = sessions[sessionId]
            ?: return errorResponse(McpSchema.ErrorCodes.INTERNAL_ERROR, "Session not found: $sessionId")

        return synchronized(session) {
            when (message) {
                is McpSchema.JSONRPCResponse -> {
                    session.accept(message).block()
                    HttpResponse(202)
                }
                is McpSchema.JSONRPCNotification -> {
                    session.accept(message).block()
                    HttpResponse(202)
                }
                is McpSchema.JSONRPCRequest -> handleRequest(session, message)
                else -> errorResponse(McpSchema.ErrorCodes.INVALID_REQUEST, "Unknown message type")
            }
        }
    }

    private fun handleInitialize(request: McpSchema.JSONRPCRequest): HttpResponse {
        val factory = sessionFactory
            ?: return errorResponse(McpSchema.ErrorCodes.INTERNAL_ERROR, "Server not initialized")
        val initRequest = try {
            mapper.convertValue(request.params(), object : TypeRef<McpSchema.InitializeRequest>() {})
        } catch (e: Exception) {
            return errorResponse(McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid initialize params: ${e.message}")
        }
        return try {
            val init = factory.startSession(initRequest)
            sessions[init.session().id] = init.session()
            val initResult = init.initResult().block()
            val body = mapper.writeValueAsString(McpSchema.JSONRPCResponse.result(request.id(), initResult))
            HttpResponse(200, headers = mapOf("Mcp-Session-Id" to init.session().id), body = body)
        } catch (e: Exception) {
            errorResponse(McpSchema.ErrorCodes.INTERNAL_ERROR, "Failed to initialize session: ${e.message}")
        }
    }

    private fun handleRequest(
        session: McpStreamableServerSession,
        request: McpSchema.JSONRPCRequest,
    ): HttpResponse {
        val captured = AtomicReference<McpSchema.JSONRPCMessage>()
        val transport = CapturingMcpTransport(mapper) { captured.set(it) }
        try {
            session.responseStream(request, transport).block()
        } catch (e: Exception) {
            return errorResponse(McpSchema.ErrorCodes.INTERNAL_ERROR, "Error processing request: ${e.message}")
        }
        val response = captured.get()
            ?: return errorResponse(McpSchema.ErrorCodes.INTERNAL_ERROR, "No response produced for request")
        return HttpResponse(200, body = mapper.writeValueAsString(response))
    }

    internal fun handleDelete(sessionIdHeader: String?): HttpResponse {
        val sessionId = sessionIdHeader?.trim()?.takeIf { it.isNotEmpty() }
            ?: return errorResponse(McpSchema.ErrorCodes.METHOD_NOT_FOUND, "Session ID required in mcp-session-id header")
        val session = sessions.remove(sessionId)
            ?: return errorResponse(McpSchema.ErrorCodes.INTERNAL_ERROR, "Session not found: $sessionId")
        runCatching { session.delete().block() }
        return HttpResponse(200)
    }

    private fun errorResponse(code: Int, message: String): HttpResponse {
        val status = when (code) {
            McpSchema.ErrorCodes.INVALID_REQUEST -> 400
            McpSchema.ErrorCodes.METHOD_NOT_FOUND -> 404
            else -> 500
        }
        // Transport-level errors carry a JSON-RPC error envelope with a null id; the SDK's
        // JSONRPCResponse factories reject null ids, so the body is written by hand.
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"jsonrpc":"2.0","id":null,"error":{"code":$code,"message":"$escaped"}}"""
        return HttpResponse(status, body = body)
    }

    /** A per-request [McpStreamableServerTransport] that captures the response the session produces. */
    private class CapturingMcpTransport(
        private val mapper: McpJsonMapper,
        private val onMessage: (McpSchema.JSONRPCMessage) -> Unit,
    ) : McpStreamableServerTransport {
        override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> =
            Mono.fromRunnable { onMessage(message) }

        override fun sendMessage(message: McpSchema.JSONRPCMessage, messageId: String): Mono<Void> =
            sendMessage(message)

        override fun <T> unmarshalFrom(data: Any, typeRef: TypeRef<T>): T = mapper.convertValue(data, typeRef)

        override fun closeGracefully(): Mono<Void> = Mono.empty()
    }
}

/** A bound, running MCP-over-HTTP server; close it to stop listening. */
class HttpMcpServer internal constructor(
    private val provider: HttpStreamableServerTransportProvider,
) : AutoCloseable {
    /** The port the server is listening on. */
    val port: Int get() = provider.boundPort

    override fun close() {
        provider.close()
    }
}

internal class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

/**
 * Minimal HTTP/1.1 server: one thread per connection, `Connection: close`, no keep-alive.
 *
 * Bound to loopback only. The transport authenticates nobody and its tools can edit the open project, so
 * the port must not be reachable off the device; `adb forward` needs nothing more than 127.0.0.1.
 * [isLocal] then covers the one case loopback binding does not: a browser aimed at the port by a hostname
 * the attacker rebinds to it.
 */
private class HttpServerSocket(
    private val provider: HttpStreamableServerTransportProvider,
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private val acceptThread = Thread(::acceptLoop, "mcp-http-accept").apply { isDaemon = true }

    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun bind(port: Int): Int {
        synchronized(lock) {
            check(serverSocket == null) { "HTTP server already bound to port $boundPort" }
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(LOOPBACK, port))
            serverSocket = server
            acceptThread.start()
            return server.localPort
        }
    }

    fun close() {
        synchronized(lock) {
            serverSocket?.close()
            serverSocket = null
        }
    }

    private fun acceptLoop() {
        while (true) {
            val server = serverSocket ?: return
            if (server.isClosed) return
            val client = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            Thread({ handle(client) }, "mcp-http-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.use {
            try {
                // A peer that connects and then stalls would otherwise pin this thread for good.
                it.soTimeout = READ_TIMEOUT_MS
                val request = readRequest(it.getInputStream())
                val response = when {
                    !request.isLocal() -> HttpResponse(403, body = "Forbidden")
                    request.method == "POST" -> provider.handlePost(request.body, request.header("mcp-session-id"))
                    request.method == "DELETE" -> provider.handleDelete(request.header("mcp-session-id"))
                    else -> HttpResponse(405, body = "Method not allowed")
                }
                writeResponse(it.getOutputStream(), response)
            } catch (e: RequestTooLargeException) {
                runCatching { writeResponse(it.getOutputStream(), HttpResponse(413, body = "Payload too large")) }
            } catch (e: IOException) {
                // client hung up, or the read timed out; nothing to send
            } catch (e: Exception) {
                runCatching {
                    writeResponse(it.getOutputStream(), HttpResponse(500, body = "Internal error: ${e.message}"))
                }
            }
        }
    }

    /**
     * Whether the request came from this device. Loopback binding already keeps the network out, but a
     * browser can be steered at 127.0.0.1 by a hostname the attacker rebinds to it, which is why the MCP
     * Streamable HTTP transport asks servers to check these two headers. A native client (opencode, curl)
     * sends no `Origin`, so only a present-and-foreign one is refused; a missing `Host` is likewise let
     * through, since a browser always sends one.
     */
    private fun HttpRequest.isLocal(): Boolean {
        val host = header("host")?.let {
            if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it.substringBefore(':')
        }
        if (host != null && host !in LOCAL_HOSTS) return false
        val origin = header("origin") ?: return true
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        return originHost.trim('[', ']') in LOCAL_HOSTS
    }

    /**
     * Reads one request. The head is read byte-wise rather than through a `Reader` so the body can be taken
     * as exactly `Content-Length` *bytes*: that header counts bytes, and decoding first left any body with
     * a non-ASCII character (an emoji in an edit, an accented path) short by the difference and blocked the
     * read until the socket timed out.
     */
    private fun readRequest(input: InputStream): HttpRequest {
        val stream = BufferedInputStream(input)
        val requestLine = readHeaderLine(stream) ?: throw IOException("empty request")
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: "/"
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readHeaderLine(stream) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) throw RequestTooLargeException()
        val body = if (length > 0) {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = stream.read(bytes, read, length - read)
                if (n < 0) break
                read += n
            }
            String(bytes, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(method, path, headers, body)
    }

    /** Reads one CRLF- (or bare LF-) terminated head line as ISO-8859-1, HTTP's byte-per-char default. */
    private fun readHeaderLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            when {
                b < 0 -> return if (line.size() == 0) null else decode(line)
                b == '\n'.code -> return decode(line).removeSuffix("\r")
                else -> line.write(b)
            }
        }
    }

    private fun decode(buffer: ByteArrayOutputStream): String =
        String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.write("HTTP/1.1 ${response.status} ${reasonPhrase(response.status)}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Content-Length: ${response.body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        response.headers.forEach { (k, v) -> writer.write("$k: $v\r\n") }
        writer.write("\r\n")
        writer.write(response.body)
        writer.flush()
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    private companion object {
        /** The only address the listener binds: see the class doc for why it is not configurable. */
        const val LOOPBACK = "127.0.0.1"

        /** How long a connection may go without sending, so a stalled peer cannot hold its thread. */
        const val READ_TIMEOUT_MS = 30_000

        /** Ceiling on `Content-Length`, so a bogus header cannot allocate the IDE into an OOM. */
        const val MAX_BODY_BYTES = 16 * 1024 * 1024

        /** The hosts a request may claim and still count as local (see [isLocal]). */
        val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}

/** Raised when a request declares a `Content-Length` past the server's body ceiling. */
private class RequestTooLargeException : Exception()

private class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name]
}
