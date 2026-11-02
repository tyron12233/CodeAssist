package dev.ide.agent.mcp.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The wire under an [McpClient]: delivers JSON-RPC messages to a server and hands every message the server sends
 * to the callback given to [start]. A transport whose replies come back on the request itself (Streamable HTTP)
 * delivers them to that callback before [send] returns; the others deliver them from a reader of their own.
 */
interface McpTransport : AutoCloseable {
    /** Opens the transport. [onMessage] gets every message from the server; [onClosed] once, when it goes away. */
    suspend fun start(onMessage: (JsonObject) -> Unit, onClosed: (Throwable?) -> Unit)

    suspend fun send(message: JsonObject)

    /** The protocol version initialize settled on, for a transport that carries it as a header. */
    fun negotiated(protocolVersion: String) {}

    override fun close()
}

/** The server answered with a JSON-RPC error. */
class McpException(message: String, val code: Int? = null) : Exception(message)

/**
 * The server wants credentials it was not given (HTTP 401, or 403 with `insufficient_scope`). [wwwAuthenticate] is
 * the challenge, which names where to discover how to sign in.
 */
class McpAuthRequiredException(val wwwAuthenticate: String?, message: String = "This server needs you to sign in.") :
    IOException(message)

/** The server forgot this session (HTTP 404 on a request carrying a session id); a new initialize starts another. */
class McpSessionExpiredException : IOException("The MCP session expired.")

/** The server did not answer [method] within the time allowed. */
class McpTimeoutException(val method: String, timeoutMs: Long) :
    IOException("The server did not answer $method within ${timeoutMs / 1000} s.")

/** An HTTP failure that is neither an auth challenge nor an expired session. */
class McpHttpException(val status: Int, message: String) : IOException(message)

/** One tool as the server lists it. [readOnly] and [destructive] are the server's hints; null when it gave none. */
data class McpToolInfo(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: JsonObject,
    val readOnly: Boolean?,
    val destructive: Boolean?,
)

/** What a tool call returned, flattened for the model: its text, any images (base64), and whether it failed. */
data class McpCallResult(
    val text: String,
    val images: List<Pair<String, String>> = emptyList(),
    val isError: Boolean = false,
)

/**
 * A Model Context Protocol client: the handshake, the tool list, and tool calls, over any [McpTransport].
 *
 * Deliberately the subset an agent needs. It declares no client capabilities (no sampling, roots or elicitation),
 * so a well-behaved server never asks for them; one that does anyway gets a method-not-found error, and a `ping`
 * gets its empty answer.
 */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "CodeAssist",
    private val clientVersion: String = "1.0",
) : AutoCloseable {

    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closedBy: Throwable? = null

    @Volatile
    var closed: Boolean = false
        private set

    var serverName: String? = null
        private set
    var serverVersion: String? = null
        private set

    /** The server's own instructions for using it, sent with its initialize answer. */
    var instructions: String? = null
        private set
    var protocolVersion: String? = null
        private set

    /** Called when the server says its tool list changed. */
    @Volatile
    var onToolsChanged: (() -> Unit)? = null

    /** Called once when the transport goes away under the client. */
    @Volatile
    var onDisconnected: ((Throwable?) -> Unit)? = null

    /** Opens the transport and runs the initialize handshake. */
    suspend fun connect(timeoutMs: Long = CONNECT_TIMEOUT_MS) {
        transport.start(::onMessage, ::onTransportClosed)
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", LATEST_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", clientName)
                    put("version", clientVersion)
                }
            },
            timeoutMs,
        )
        protocolVersion = result.str("protocolVersion") ?: LATEST_PROTOCOL_VERSION
        (result["serverInfo"] as? JsonObject)?.let {
            serverName = it.str("name")
            serverVersion = it.str("version")
        }
        instructions = result.str("instructions")?.takeIf { it.isNotBlank() }
        transport.negotiated(protocolVersion!!)
        notify("notifications/initialized")
    }

    /** Every tool the server offers, following its pagination (bounded, so a looping cursor cannot hang us). */
    suspend fun listTools(timeoutMs: Long = CONNECT_TIMEOUT_MS): List<McpToolInfo> {
        val out = ArrayList<McpToolInfo>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = request(
                "tools/list",
                cursor?.let { c -> buildJsonObject { put("cursor", c) } },
                timeoutMs,
            )
            (result["tools"] as? JsonArray)?.forEach { el ->
                val t = el as? JsonObject ?: return@forEach
                val name = t.str("name") ?: return@forEach
                val annotations = t["annotations"] as? JsonObject
                out += McpToolInfo(
                    name = name,
                    title = t.str("title") ?: annotations?.str("title"),
                    description = t.str("description"),
                    inputSchema = t["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                    readOnly = annotations?.bool("readOnlyHint"),
                    destructive = annotations?.bool("destructiveHint"),
                )
            }
            cursor = result.str("nextCursor")?.takeIf { it.isNotEmpty() }
            if (cursor == null) return out
        }
        return out
    }

    suspend fun callTool(name: String, arguments: JsonObject, timeoutMs: Long = CALL_TIMEOUT_MS): McpCallResult {
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMs,
        )
        return flatten(result)
    }

    /** Sends a request and waits for its answer; a JSON-RPC error becomes an [McpException]. */
    suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject {
        closedBy?.let { throw IOException("The MCP connection is closed: ${it.message}", it) }
        if (closed) throw IOException("The MCP connection is closed.")
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id.toString()] = answer
        try {
            transport.send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    if (params != null) put("params", params)
                },
            )
            val response = try {
                withTimeout(timeoutMs) { answer.await() }
            } catch (e: TimeoutCancellationException) {
                // Not a cancellation of the caller: a plain failure it can report.
                throw McpTimeoutException(method, timeoutMs)
            }
            (response["error"] as? JsonObject)?.let { err ->
                throw McpException(
                    err.str("message") ?: "The server returned an error.",
                    (err["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                )
            }
            return response["result"] as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            pending.remove(id.toString())
        }
    }

    suspend fun notify(method: String, params: JsonObject? = null) {
        transport.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            },
        )
    }

    private fun onMessage(message: JsonObject) {
        val id = message["id"]
        val method = message.str("method")
        when {
            method == null && id != null && id !is JsonNull -> pending[idKey(id)]?.complete(message)
            method != null && id != null && id !is JsonNull -> scope.launch { answerServerRequest(id, method) }
            method == "notifications/tools/list_changed" -> onToolsChanged?.invoke()
        }
    }

    /** `ping` gets its empty answer; anything else is a capability this client never declared. */
    private suspend fun answerServerRequest(id: JsonElement, method: String) {
        val reply = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (method == "ping") {
                putJsonObject("result") {}
            } else {
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "CodeAssist does not support $method")
                }
            }
        }
        try {
            transport.send(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The server will time its request out; nothing else to do.
        }
    }

    private fun onTransportClosed(cause: Throwable?) {
        if (closedBy != null) return
        val reason = cause ?: IOException("The MCP server closed the connection.")
        closedBy = reason
        pending.values.forEach { it.completeExceptionally(IOException(reason.message ?: "The MCP server disconnected.", reason)) }
        if (!closed) onDisconnected?.invoke(cause)
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.forEach { it.completeExceptionally(IOException("The MCP connection was closed.")) }
        scope.cancel()
        runCatching { transport.close() }
    }

    companion object {
        /** The newest protocol revision this client speaks; a server answers with the one it settles on. */
        const val LATEST_PROTOCOL_VERSION = "2025-06-18"
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val CALL_TIMEOUT_MS = 180_000L
        private const val MAX_PAGES = 20

        /** A tool result's text, past which the rest is cut: a huge result would crowd the conversation out. */
        const val MAX_RESULT_CHARS = 40_000

        private fun idKey(id: JsonElement): String = (id as? JsonPrimitive)?.contentOrNull ?: id.toString()

        /**
         * A `tools/call` result as text plus images. Text blocks are joined; an embedded resource contributes its
         * text (or a note that it was binary); a resource link and audio are described rather than dropped, so the
         * model knows they exist. Structured content is used when the server sent no text at all.
         */
        fun flatten(result: JsonObject): McpCallResult {
            val text = StringBuilder()
            val images = ArrayList<Pair<String, String>>()
            (result["content"] as? JsonArray)?.forEach { el ->
                val block = el as? JsonObject ?: return@forEach
                when (block.str("type")) {
                    "text" -> text.appendBlock(block.str("text").orEmpty())
                    "image" -> {
                        val data = block.str("data")
                        val mime = block.str("mimeType") ?: "image/png"
                        if (data != null && mime in SUPPORTED_IMAGES) images += mime to data
                        else text.appendBlock("[image: $mime]")
                    }
                    "resource" -> {
                        val res = block["resource"] as? JsonObject
                        val body = res?.str("text")
                        val uri = res?.str("uri").orEmpty()
                        text.appendBlock(if (body != null) "<resource uri=\"$uri\">\n$body\n</resource>" else "[binary resource: $uri]")
                    }
                    "resource_link" -> text.appendBlock(
                        "[resource: ${block.str("name") ?: block.str("uri").orEmpty()} ${block.str("uri").orEmpty()}]".trim(),
                    )
                    "audio" -> text.appendBlock("[audio: ${block.str("mimeType") ?: "audio"}]")
                }
            }
            if (text.isEmpty() && images.isEmpty()) {
                result["structuredContent"]?.let { text.append(it.toString()) }
            }
            val isError = result.bool("isError") == true
            var out = text.toString()
            if (out.length > MAX_RESULT_CHARS) {
                out = out.take(MAX_RESULT_CHARS) + "\n… (cut at $MAX_RESULT_CHARS characters; ask the tool for less)"
            }
            if (out.isEmpty() && images.isEmpty()) out = if (isError) "The tool failed without a message." else "(no output)"
            return McpCallResult(out, images, isError)
        }

        private val SUPPORTED_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        private fun StringBuilder.appendBlock(s: String) {
            if (isNotEmpty()) append("\n\n")
            append(s)
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
