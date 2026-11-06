package dev.ide.lsp.client

import dev.ide.platform.log.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A JSON-RPC error response: the server refused or failed the request. */
class ResponseErrorException(val code: Int, message: String, val data: JsonElement? = null) : RuntimeException(message) {
    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
        const val REQUEST_CANCELLED = -32800
        const val CONTENT_MODIFIED = -32801
    }
}

/** The connection closed (the server exited or the session was shut down) before the response arrived. */
class ConnectionClosedException(message: String) : IOException(message)

/**
 * JSON-RPC 2.0 over the base protocol's `Content-Length` framing: what LSP is spoken in.
 *
 * One reader thread decodes messages. Responses complete their request's future right there; everything the
 * server initiates (requests and notifications alike) is handed to one dispatch thread, in arrival order, so a
 * slow handler never stalls the responses behind it and a `publishDiagnostics` is never observed before the
 * notification that preceded it.
 */
class JsonRpcConnection(
    input: InputStream,
    private val output: OutputStream,
    private val name: String,
    private val log: Logger,
) : AutoCloseable {

    /** Answers a request the server sent. Return the result (null for JSON `null`), or throw [ResponseErrorException]. */
    fun interface RequestHandler {
        fun handle(method: String, params: JsonElement?): JsonElement?
    }

    fun interface NotificationHandler {
        fun handle(method: String, params: JsonElement?)
    }

    /**
     * A request in flight: its [id], for `$/cancelRequest`, and the [future] its result completes. Once it has,
     * [responseChars] is the size of the server's answer in characters (bytes, for ASCII JSON) and [parseNanos] how long the client took to parse it,
     * which is where a large answer (a 10 000-item completion list) costs on a phone.
     */
    class Pending(val id: Long, val future: CompletableFuture<JsonElement?>) {
        @Volatile var responseChars: Int = 0
            internal set
        @Volatile var parseNanos: Long = 0
            internal set
    }

    @Volatile var requestHandler: RequestHandler = RequestHandler { method, _ ->
        throw ResponseErrorException(ResponseErrorException.METHOD_NOT_FOUND, "unhandled method $method")
    }
    @Volatile var notificationHandler: NotificationHandler = NotificationHandler { _, _ -> }

    /** Runs once, on the reader thread, when the stream ends or [close] is called. */
    @Volatile var onClosed: (Throwable?) -> Unit = {}

    private val input = BufferedInputStream(input, 64 * 1024)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val writeLock = Any()
    private val dispatcher: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lsp-$name-dispatch").apply { isDaemon = true }
    }
    private var reader: Thread? = null
    @Volatile var isClosed: Boolean = false
        private set

    fun start() {
        check(reader == null) { "already started" }
        reader = Thread({ readLoop() }, "lsp-$name-reader").apply { isDaemon = true; start() }
    }

    fun request(method: String, params: JsonElement?): Pending {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonElement?>()
        val entry = Pending(id, future)
        if (isClosed) {
            future.completeExceptionally(ConnectionClosedException("$name is not running"))
            return entry
        }
        pending[id] = entry
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            })
        } catch (e: IOException) {
            pending.remove(id)
            future.completeExceptionally(e)
        }
        return entry
    }

    fun notify(method: String, params: JsonElement?) {
        if (isClosed) return
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            })
        } catch (e: IOException) {
            log.debug("$name: could not send $method: ${e.message}")
        }
    }

    /** Give up on request [id]: its future is cancelled and the server is told it may stop working on it. */
    fun cancel(id: Long) {
        val entry = pending.remove(id) ?: return
        entry.future.cancel(false)
        notify("$/cancelRequest", buildJsonObject { put("id", id) })
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        failPending(ConnectionClosedException("$name closed"))
        dispatcher.shutdown()
        runCatching { input.close() }
    }

    // ---------------------------------------------------------------------------------------------- writing

    private fun send(message: JsonObject) {
        val body = Json.encodeToString(JsonElement.serializer(), message).toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        synchronized(writeLock) {
            output.write(header)
            output.write(body)
            output.flush()
        }
    }

    private fun respond(id: JsonElement, result: JsonElement?) {
        runCatching {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result ?: JsonNull)
            })
        }.onFailure { log.debug("$name: could not answer request $id: ${it.message}") }
    }

    private fun respondError(id: JsonElement, code: Int, message: String) {
        runCatching {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject { put("code", code); put("message", message) })
            })
        }.onFailure { log.debug("$name: could not answer request $id: ${it.message}") }
    }

    // ---------------------------------------------------------------------------------------------- reading

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (!isClosed) {
                val body = readMessage() ?: break
                val started = System.nanoTime()
                val message = try {
                    Json.parseToJsonElement(body).jsonObject
                } catch (e: Exception) {
                    log.warn("$name: unreadable message from the server", e)
                    continue
                }
                handle(message, body.length, System.nanoTime() - started)
            }
        } catch (e: IOException) {
            if (!isClosed) failure = e
        } catch (e: Throwable) {
            failure = e
            log.warn("$name: reader failed", e)
        } finally {
            val wasOpen = !isClosed
            isClosed = true
            failPending(ConnectionClosedException(if (failure != null) "$name failed: ${failure.message}" else "$name exited"))
            dispatcher.shutdown()
            if (wasOpen || failure != null) runCatching { onClosed(failure) }
        }
    }

    /** One message body, or null at a clean end of stream between messages. */
    private fun readMessage(): String? {
        var contentLength = -1
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) {
                if (contentLength >= 0) break
                continue // tolerate stray blank lines between messages
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull()
                    ?: throw IOException("bad Content-Length header: $line")
            }
        }
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bytes, read, contentLength - read)
            if (n < 0) throw EOFException("stream ended inside a message")
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** A header line without its `\r\n`, or null at end of stream before any byte of it. */
    private fun readHeaderLine(): String? {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw EOFException("stream ended inside a header")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(Charsets.US_ASCII.name())
    }

    private fun handle(message: JsonObject, bytes: Int, parseNanos: Long) {
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val id = message["id"]?.takeIf { it !is JsonNull }
        if (method == null) {
            // A response to one of ours.
            val key = (id as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: return
            val entry = pending.remove(key) ?: return
            entry.responseChars = bytes
            entry.parseNanos = parseNanos
            val future = entry.future
            val error = message["error"] as? JsonObject
            if (error != null) {
                val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: ResponseErrorException.INTERNAL_ERROR
                val text = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "error $code"
                future.completeExceptionally(ResponseErrorException(code, text, error["data"]))
            } else {
                future.complete(message["result"]?.takeIf { it !is JsonNull })
            }
            return
        }
        val params = message["params"]?.takeIf { it !is JsonNull }
        if (dispatcher.isShutdown) return
        if (id == null) {
            dispatcher.execute {
                try {
                    notificationHandler.handle(method, params)
                } catch (e: Throwable) {
                    log.warn("$name: handling $method failed", e)
                }
            }
        } else {
            dispatcher.execute {
                try {
                    respond(id, requestHandler.handle(method, params))
                } catch (e: ResponseErrorException) {
                    respondError(id, e.code, e.message ?: "error")
                } catch (e: Throwable) {
                    log.warn("$name: answering $method failed", e)
                    respondError(id, ResponseErrorException.INTERNAL_ERROR, e.message ?: e.toString())
                }
            }
        }
    }

    private fun failPending(cause: Throwable) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.future.completeExceptionally(cause) }
    }
}
