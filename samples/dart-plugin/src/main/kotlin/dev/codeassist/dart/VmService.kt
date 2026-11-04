package dev.codeassist.dart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/** A VM service call the VM refused or failed. */
class VmServiceException(val code: Int, message: String, val data: JsonElement?) : IOException(message)

/**
 * A client for the Dart VM service of a running Flutter app: JSON-RPC 2.0 over a WebSocket, which is how
 * `flutter run` restarts and hot-reloads an app, plus the DevFS upload that puts files where the app can read
 * them. The VM service only listens on localhost, so this is a plain socket to 127.0.0.1.
 *
 * A WebSocket client of its own (RFC 6455, the little of it a localhost JSON-RPC peer needs) rather than a
 * library, because a plugin cannot count on one in the IDE and should not carry one for two hundred lines.
 */
class VmService private constructor(private val httpBase: String, private val socket: Socket) : AutoCloseable {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output: OutputStream = socket.getOutputStream()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val writeLock = Any()
    private val random = SecureRandom()

    /** Stream events (`streamListen`): the stream id and the event object. Called on the reader thread. */
    @Volatile var onEvent: (stream: String, event: JsonObject) -> Unit = { _, _ -> }

    /** Runs once when the connection ends: the app exited or was killed. */
    @Volatile var onClosed: () -> Unit = {}

    @Volatile var isClosed = false
        private set

    private val reader = Thread({ readLoop() }, "flutter-vm-service").apply { isDaemon = true }

    /** Call [method] and wait for its result. Throws [VmServiceException] for an error answer. */
    fun call(method: String, params: Map<String, Any?> = emptyMap(), timeoutMs: Long = 60_000): JsonObject {
        val id = nextId.getAndIncrement().toString()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", toJson(params))
        }
        try {
            sendText(Json.encodeToString(JsonElement.serializer(), message))
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } catch (e: java.util.concurrent.TimeoutException) {
                // An IOException like every other way a call fails, so the run reports it rather than dying of it.
                throw IOException("the app did not answer $method within ${timeoutMs / 1000} s")
            }
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Write [file] into the DevFS [fsName] at [path] (relative to the DevFS root), the way `flutter run`
     * uploads: an HTTP PUT of the gzipped bytes to the VM service, naming the file in two headers.
     */
    fun upload(fsName: String, path: String, file: Path) {
        val connection = URL(httpBase).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setChunkedStreamingMode(64 * 1024)
            // Without these a VM service that stops answering holds the run forever, past Stop.
            connection.connectTimeout = 10_000
            connection.readTimeout = UPLOAD_TIMEOUT_MS
            // A fresh socket for every upload. Reusing a pooled one now and then sends the request down a
            // connection the VM service has finished with, and it never answers: the run hung on a reload.
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("dev_fs_name", fsName)
            connection.setRequestProperty("dev_fs_uri_b64", Base64.getEncoder().encodeToString(path.toByteArray()))
            // The fastest level: the kernel is tens of megabytes, compressed on the phone for a socket to localhost,
            // where the default level costs seconds and saves nothing that matters.
            object : GZIPOutputStream(connection.outputStream, 64 * 1024) {
                init { def.setLevel(Deflater.BEST_SPEED) }
            }.use { gz -> Files.copy(file, gz) }
            val code = connection.responseCode
            if (code != 200) throw IOException("uploading $path to the app failed: HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { sendFrame(OP_CLOSE, ByteArray(0)) }
        runCatching { socket.close() }
    }

    // ------------------------------------------------------------------------------------------- the socket

    private fun handshake(path: String, host: String, port: Int) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val request = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val status = readLine()
        if (!status.startsWith("HTTP/1.1 101")) throw IOException("the VM service refused the WebSocket: $status")
        var accept: String? = null
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                accept = line.substring(colon + 1).trim()
            }
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
        )
        if (accept != expected) throw IOException("the VM service answered the WebSocket handshake wrongly")
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("the VM service closed the connection")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("US-ASCII")
    }

    private fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /** One masked, unfragmented frame: what a client sends (RFC 6455 section 5.3). */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            output.write(WsFrames.encode(opcode, payload, ByteArray(4).also(random::nextBytes)))
            output.flush()
        }
    }

    private fun readLoop() {
        val message = ByteArrayOutputStream()
        try {
            while (!isClosed) {
                val frame = WsFrames.read(input)
                when (frame.opcode) {
                    OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                        message.write(frame.payload)
                        if (frame.fin) {
                            handle(message.toString("UTF-8"))
                            message.reset()
                        }
                    }
                    OP_PING -> sendFrame(OP_PONG, frame.payload)
                    OP_CLOSE -> break
                }
            }
        } catch (_: IOException) {
            // The app went away, or close() was called.
        } finally {
            isClosed = true
            runCatching { socket.close() }
            val gone = IOException("the app's VM service connection closed")
            pending.values.forEach { it.completeExceptionally(gone) }
            runCatching { onClosed() }
        }
    }

    private fun handle(text: String) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = (message["id"] as? JsonPrimitive)?.contentOrNull
        if (id != null) {
            val future = pending[id] ?: return
            val error = message["error"] as? JsonObject
            if (error != null) {
                val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
                val text2 = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "error $code"
                future.completeExceptionally(VmServiceException(code, text2, error["data"]))
            } else {
                future.complete(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            return
        }
        if ((message["method"] as? JsonPrimitive)?.contentOrNull == "streamNotify") {
            val params = message["params"] as? JsonObject ?: return
            val stream = (params["streamId"] as? JsonPrimitive)?.contentOrNull ?: return
            val event = params["event"] as? JsonObject ?: return
            runCatching { onEvent(stream, event) }
        }
    }

    companion object {
        private const val UPLOAD_TIMEOUT_MS = 120_000
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONTINUATION = 0
        const val OP_TEXT = 1
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10

        /**
         * Connect to the VM service at [httpUri] (what the engine prints: `http://127.0.0.1:PORT/TOKEN=/`). Its
         * WebSocket is the same path plus `ws`.
         */
        fun connect(httpUri: String, timeoutMs: Int = 10_000): VmService {
            val base = if (httpUri.endsWith("/")) httpUri else "$httpUri/"
            val uri = URI(base)
            val socket = Socket()
            socket.connect(InetSocketAddress(uri.host, uri.port), timeoutMs)
            socket.tcpNoDelay = true
            val service = VmService(base, socket)
            try {
                service.handshake("${uri.rawPath}ws", uri.host, uri.port)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            service.reader.start()
            return service
        }

        private fun toJson(value: Any?): JsonElement = when (value) {
            null -> kotlinx.serialization.json.JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map(::toJson))
            else -> JsonPrimitive(value.toString())
        }
    }
}

/** WebSocket framing (RFC 6455 section 5.2), separate so it can be tested without a socket. */
object WsFrames {
    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** A client frame: FIN set, masked with [mask]. */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> out.write(0x80 or n)
            n < 65536 -> { out.write(0x80 or 126); out.write(n ushr 8); out.write(n and 0xff) }
            else -> {
                out.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) out.write(((n.toLong() ushr shift) and 0xff).toInt())
            }
        }
        out.write(mask)
        for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
        return out.toByteArray()
    }

    /** The next frame on [input], unmasking it when it is masked (a server's frames are not). */
    fun read(input: InputStream): Frame {
        val b0 = readByte(input)
        val b1 = readByte(input)
        var length = (b1 and 0x7f).toLong()
        if (length == 126L) length = ((readByte(input) shl 8) or readByte(input)).toLong()
        else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        if (length > Int.MAX_VALUE) throw IOException("a WebSocket frame of $length bytes")
        val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(length.toInt()).also { readFully(input, it) }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Frame(b0 and 0x80 != 0, b0 and 0x0f, payload)
    }

    private fun readByte(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException("WebSocket closed") }

    private fun readFully(input: InputStream, into: ByteArray) {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) throw EOFException("WebSocket closed inside a frame")
            read += n
        }
    }
}
