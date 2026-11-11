package dev.ide.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The device's half of the hosted relay (`supabase/cloudflare/mcp-relay`): one WebSocket held open to the relay,
 * down which each MCP request Claude posts to this link's public URL arrives, and back up which the answer goes.
 *
 * It does not speak MCP. A request is replayed as it came against the MCP server this app hosts on its own
 * loopback ([localUrl]), and that server's status, body and the two headers a client reads are sent back. So a
 * relayed client gets exactly the tools a local one does.
 *
 * Both directions split a body into frames of [CHUNK_CHARS], because a WebSocket message is limited in size and a
 * tool's answer (a screenshot) is not.
 *
 * Reconnects on its own with a growing delay until [close]. Two answers stop it instead, since retrying cannot
 * help: the link belongs to another account ([State.IN_USE]), and this account opened the same link somewhere
 * else, which takes it over ([State.REPLACED]). A rejected session is refreshed once per attempt
 * ([refreshToken]) before the client reports [State.SIGNED_OUT] and keeps trying at the slow rate.
 */
class McpRelayClient(
    relayBaseUrl: String,
    private val secret: String,
    private val localUrl: String,
    private val token: () -> String?,
    private val refreshToken: (stale: String?) -> String? = { null },
    private val onState: (State) -> Unit = {},
    private val http: OkHttpClient = defaultClient(),
) : AutoCloseable {

    enum class State { CONNECTING, CONNECTED, OFFLINE, SIGNED_OUT, IN_USE, REPLACED, CLOSED }

    private val base = relayBaseUrl.trimEnd('/')
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mcp-relay").apply { isDaemon = true } }
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "mcp-relay-call").apply { isDaemon = true } }
    private val incoming = ConcurrentHashMap<String, Incoming>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var retry: ScheduledFuture<*>? = null
    private var delayMs = FIRST_RETRY_MS
    private var refreshed = false

    @Volatile var state: State = State.CONNECTING
        private set

    /** The address to give Claude as a custom connector. Holding it is the whole of the authorization. */
    val publicUrl: String get() = publicUrl(base, secret)

    fun start(): McpRelayClient {
        scheduler.execute(::connect)
        return this
    }

    override fun close() {
        closed = true
        retry?.cancel(false)
        socket?.close(1000, "closed")
        socket = null
        scheduler.shutdownNow()
        workers.shutdownNow()
        publish(State.CLOSED)
    }

    private fun publish(next: State) {
        if (state == next) return
        state = next
        runCatching { onState(next) }
    }

    private fun connect() {
        if (closed) return
        val session = token()
        if (session.isNullOrBlank()) {
            publish(State.SIGNED_OUT)
            scheduleRetry(SLOW_RETRY_MS)
            return
        }
        if (state != State.SIGNED_OUT) publish(State.CONNECTING)
        val request = Request.Builder()
            .url(base.replaceFirst("http", "ws") + "/device?link=" + secret)
            .header("Authorization", "Bearer $session")
            .build()
        socket = http.newWebSocket(request, Listener(session))
    }

    private fun scheduleRetry(afterMs: Long) {
        if (closed) return
        retry = runCatching { scheduler.schedule(::connect, afterMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private inner class Listener(private val session: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            delayMs = FIRST_RETRY_MS
            refreshed = false
            publish(State.CONNECTED)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            if (frame.string("t") != "req") return
            val id = frame.string("id") ?: return
            val index = frame.int("i") ?: 0
            val total = (frame.int("n") ?: 1).coerceIn(1, MAX_FRAMES)
            val entry = incoming.getOrPut(id) { Incoming(total) }
            if (index == 0) {
                entry.method = frame.string("method") ?: "POST"
                entry.headers = (frame["headers"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            }
            if (!entry.add(index, frame.string("data").orEmpty())) return
            incoming.remove(id)
            val body = entry.body()
            runCatching { workers.execute { answer(webSocket, id, entry.method, entry.headers, body) } }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (code == REPLACED_CODE) stop(State.REPLACED) else dropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code == REPLACED_CODE) stop(State.REPLACED) else dropped(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            response?.close()
            when (code) {
                403 -> stop(State.IN_USE)
                401 -> {
                    // A session expires hourly; a stale one is the usual 401. Ask for a fresh one once, then say so.
                    if (!refreshed && !closed) {
                        refreshed = true
                        val fresh = runCatching { refreshToken(session) }.getOrNull()
                        if (!fresh.isNullOrBlank() && fresh != session) { scheduleRetry(0); return }
                    }
                    if (socket === webSocket) socket = null
                    publish(State.SIGNED_OUT)
                    scheduleRetry(SLOW_RETRY_MS)
                }
                else -> dropped(webSocket)
            }
        }
    }

    /** The socket is gone for a reason a retry can fix (no network, the relay restarting). */
    private fun dropped(webSocket: WebSocket) {
        if (socket !== webSocket || closed) return
        socket = null
        incoming.clear()
        if (state != State.REPLACED && state != State.IN_USE) publish(State.OFFLINE)
        scheduleRetry(delayMs)
        delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private fun stop(final: State) {
        socket = null
        incoming.clear()
        if (!closed) publish(final)
    }

    /** Replay one relayed request against the local MCP server and send what it said. */
    private fun answer(webSocket: WebSocket, id: String, method: String, headers: Map<String, String>, body: String) {
        var status = 502
        var responseHeaders = emptyMap<String, String>()
        var text: String
        try {
            val builder = Request.Builder().url(localUrl)
            headers.forEach { (name, value) -> if (name.lowercase() in REQUEST_HEADERS) builder.header(name, value) }
            when (method) {
                "DELETE" -> builder.delete()
                else -> builder.post(body.toRequestBody((headers["content-type"] ?: "application/json").toMediaTypeOrNull()))
            }
            http.newBuilder().readTimeout(LOCAL_TIMEOUT_S, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { response ->
                status = response.code
                responseHeaders = RESPONSE_HEADERS.mapNotNull { name -> response.header(name)?.let { name to it } }.toMap()
                text = response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            status = 502
            responseHeaders = mapOf("content-type" to "application/json")
            text = """{"jsonrpc":"2.0","error":{"code":-32000,"message":"CodeAssist could not reach its own MCP server."},"id":null}"""
        }
        for (frame in responseFrames(id, status, responseHeaders, text)) {
            if (!webSocket.send(frame)) return
        }
    }

    /** A request being put back together from its frames. */
    private class Incoming(private val total: Int) {
        var method: String = "POST"
        var headers: Map<String, String> = emptyMap()
        private val parts = arrayOfNulls<String>(total)
        private var received = 0

        /** True once every frame is in. */
        @Synchronized
        fun add(index: Int, data: String): Boolean {
            if (index !in 0 until total) return false
            if (parts[index] == null) received++
            parts[index] = data
            return received == total
        }

        fun body(): String = parts.joinToString("") { it.orEmpty() }
    }

    companion object {
        /** Text per frame; kept in step with the relay's own. */
        const val CHUNK_CHARS = 256_000
        private const val MAX_FRAMES = 4096
        private const val REPLACED_CODE = 4000
        private const val FIRST_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val SLOW_RETRY_MS = 60_000L
        /** Under the relay's own wait, so a slow tool is answered as an error here before the relay gives up. */
        private const val LOCAL_TIMEOUT_S = 110L
        private val REQUEST_HEADERS = setOf("accept", "content-type", "mcp-session-id", "mcp-protocol-version", "last-event-id")
        private val RESPONSE_HEADERS = listOf("content-type", "mcp-session-id")

        /** A new link's secret: 256 random bits, as the relay requires (base64url, 43 characters). */
        fun newSecret(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun publicUrl(relayBaseUrl: String, secret: String): String = relayBaseUrl.trimEnd('/') + "/c/" + secret + "/mcp"

        /** The frames that carry one answer: the status and headers ride on the first. */
        fun responseFrames(id: String, status: Int, headers: Map<String, String>, body: String): List<String> {
            val total = maxOf(1, (body.length + CHUNK_CHARS - 1) / CHUNK_CHARS)
            return (0 until total).map { i ->
                buildJsonObject {
                    put("t", "res")
                    put("id", id)
                    if (i == 0) {
                        put("status", status)
                        putJsonObject("headers") { headers.forEach { (k, v) -> put(k, v) } }
                    }
                    put("i", i)
                    put("n", total)
                    put("data", body.substring(i * CHUNK_CHARS, minOf(body.length, (i + 1) * CHUNK_CHARS)))
                }.toString()
            }
        }

        /** Protocol pings keep the socket alive through idle NATs and show a dead one within the interval. */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
