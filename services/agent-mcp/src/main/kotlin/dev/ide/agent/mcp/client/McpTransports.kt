package dev.ide.agent.mcp.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Extra request headers, asked for on every request so a refreshed token is picked up without reconnecting. */
typealias McpHeaders = suspend () -> Map<String, String>

private val JSON_TYPE = "application/json".toMediaType()
private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

/** The HTTP client MCP connections share: no read timeout, because an event stream may stay quiet for minutes. */
val mcpHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/**
 * MCP's Streamable HTTP transport: every message is a POST to one URL, and a request's answer comes back on that
 * POST, either as JSON or as an event stream that ends with it. The session id the server issues at initialize is
 * echoed on every later request.
 *
 * No standing GET stream is opened for server-initiated messages: an agent only needs answers to its own requests,
 * and a `tools/list_changed` sent mid-request still arrives on that request's stream.
 */
class StreamableHttpTransport(
    private val url: String,
    private val headers: McpHeaders = { emptyMap() },
    private val http: OkHttpClient = mcpHttpClient,
) : McpTransport {

    @Volatile private var sessionId: String? = null
    @Volatile private var protocol: String? = null
    private var onMessage: (JsonObject) -> Unit = {}

    override suspend fun start(onMessage: (JsonObject) -> Unit, onClosed: (Throwable?) -> Unit) {
        this.onMessage = onMessage
    }

    override fun negotiated(protocolVersion: String) {
        protocol = protocolVersion
    }

    override suspend fun send(message: JsonObject) {
        val isInitialize = message.str("method") == "initialize"
        val requestId = message["id"]?.primitiveContent()?.takeIf { message.str("method") != null }
        val request = Request.Builder()
            .url(url)
            .post(message.toString().toRequestBody(JSON_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .apply {
                headers().forEach { (k, v) -> header(k, v) }
                sessionId?.let { header("Mcp-Session-Id", it) }
                protocol?.let { header("MCP-Protocol-Version", it) }
            }
            .build()
        execute(http.newCall(request)) { response ->
            response.header("Mcp-Session-Id")?.let { if (isInitialize || sessionId == null) sessionId = it }
            checkStatus(response, expiredSession = !isInitialize && sessionId != null)
            if (response.code == 202 || response.code == 204) return@execute
            val type = response.header("Content-Type").orEmpty().lowercase()
            val body = response.body ?: return@execute
            if (type.startsWith("text/event-stream")) {
                readEvents(body.source()) { _, data ->
                    val msg = parseMessage(data) ?: return@readEvents true
                    msg.forEach(onMessage)
                    // Done once our own answer is in; the server may keep the stream open after it.
                    requestId == null || msg.none { it["id"]?.primitiveContent() == requestId && it.str("method") == null }
                }
            } else {
                val text = body.string()
                if (text.isNotBlank()) parseMessage(text)?.forEach(onMessage)
            }
        }
    }

    override fun close() {
        val id = sessionId ?: return
        sessionId = null
        // Best effort, off the caller's thread: a server that keeps sessions open is told this one is over.
        Thread {
            runCatching {
                val req = Request.Builder().url(url).delete().header("Mcp-Session-Id", id).build()
                http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(req).execute().close()
            }
        }.apply { isDaemon = true }.start()
    }
}

/**
 * The older HTTP+SSE transport (protocol 2024-11-05): a GET opens an event stream whose first `endpoint` event
 * names where to POST, and every answer arrives on that stream.
 */
class LegacySseTransport(
    private val url: String,
    private val headers: McpHeaders = { emptyMap() },
    private val http: OkHttpClient = mcpHttpClient,
) : McpTransport {

    private val endpoint = CompletableDeferred<String>()
    @Volatile private var call: Call? = null
    @Volatile private var closed = false

    override suspend fun start(onMessage: (JsonObject) -> Unit, onClosed: (Throwable?) -> Unit) {
        val request = Request.Builder().url(url).get()
            .header("Accept", "text/event-stream")
            .apply { headers().forEach { (k, v) -> header(k, v) } }
            .build()
        val c = http.newCall(request)
        call = c
        Thread({
            var failure: Throwable? = null
            try {
                c.execute().use { response ->
                    checkStatus(response, expiredSession = false)
                    val source = response.body?.source() ?: throw IOException("The server sent no event stream.")
                    readEvents(source) { event, data ->
                        when (event) {
                            "endpoint" -> {
                                val resolved = url.toHttpUrlOrNull()?.resolve(data.trim())?.toString()
                                if (resolved == null) {
                                    endpoint.completeExceptionally(IOException("The server named an invalid endpoint: $data"))
                                } else {
                                    endpoint.complete(resolved)
                                }
                            }
                            else -> parseMessage(data)?.forEach(onMessage)
                        }
                        true
                    }
                }
            } catch (e: Throwable) {
                failure = e
            }
            endpoint.completeExceptionally(failure ?: IOException("The server closed the event stream."))
            if (!closed) onClosed(failure)
        }, "mcp-sse").apply { isDaemon = true }.start()
        withTimeout(McpClient.CONNECT_TIMEOUT_MS) { endpoint.await() }
    }

    override suspend fun send(message: JsonObject) {
        val target = endpoint.await()
        val request = Request.Builder().url(target)
            .post(message.toString().toRequestBody(JSON_TYPE))
            .apply { headers().forEach { (k, v) -> header(k, v) } }
            .build()
        execute(http.newCall(request)) { response -> checkStatus(response, expiredSession = false) }
    }

    override fun close() {
        closed = true
        call?.cancel()
    }
}

/**
 * A local server run as a child process, speaking newline-delimited JSON-RPC on its stdin and stdout (desktop only:
 * there is no Node or Python on a phone). Its stderr is kept, so a server that dies at start says why.
 */
class StdioTransport(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDir: File? = null,
) : McpTransport {

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val stderrTail = ArrayDeque<String>()
    @Volatile private var closed = false

    /** The last lines the server wrote to stderr, for an error message. */
    fun stderr(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

    override suspend fun start(onMessage: (JsonObject) -> Unit, onClosed: (Throwable?) -> Unit) = withContext(Dispatchers.IO) {
        val p = try {
            ProcessBuilder(launchCommand(command, args))
                .apply {
                    workingDir?.let { directory(it) }
                    environment().putAll(env)
                    if (!isWindows) environment()["PATH"] = extendedPath(environment()["PATH"])
                }
                .start()
        } catch (e: IOException) {
            throw IOException("Could not start \"$command\": ${e.message}", e)
        }
        process = p
        writer = OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8)
        Thread({
            BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    synchronized(stderrTail) {
                        stderrTail.addLast(line)
                        while (stderrTail.size > 20) stderrTail.removeFirst()
                    }
                }
            }
        }, "mcp-stdio-err").apply { isDaemon = true }.start()
        Thread({
            try {
                BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                    // A server may log to stdout before it starts speaking JSON; anything unparseable is skipped.
                    lines.forEach { line -> if (line.isNotBlank()) parseMessage(line)?.forEach(onMessage) }
                }
            } catch (_: IOException) {
            }
            if (!closed) {
                val code = runCatching { p.waitFor(2, TimeUnit.SECONDS); p.exitValue() }.getOrNull()
                val tail = stderr().lines().takeLast(3).joinToString("\n").takeIf { it.isNotBlank() }
                onClosed(IOException(buildString {
                    append("The server process exited")
                    if (code != null) append(" (code $code)")
                    if (tail != null) append(":\n").append(tail)
                }))
            }
        }, "mcp-stdio-out").apply { isDaemon = true }.start()
    }

    override suspend fun send(message: JsonObject) = withContext(Dispatchers.IO) {
        val w = writer ?: throw IOException("The server process is not running.")
        synchronized(w) {
            w.write(message.toString())
            w.write("\n")
            w.flush()
        }
    }

    override fun close() {
        closed = true
        val p = process ?: return
        runCatching { writer?.close() }
        p.destroy()
        Thread {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

        /**
         * On macOS and Linux the command runs through the user's login shell, so `npx` or `uvx` resolve on the PATH a
         * terminal would give them; an app started from the Dock or a launcher inherits a bare PATH. On Windows,
         * `cmd /c` resolves `npx` to `npx.cmd`.
         */
        internal fun launchCommand(command: String, args: List<String>): List<String> = when {
            isWindows -> listOf("cmd.exe", "/c", command) + args
            else -> listOf(System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/sh", "-lc", "exec \"\$@\"", "mcp", command) + args
        }

        private fun extendedPath(path: String?): String {
            val home = System.getProperty("user.home")
            val extra = listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin", "$home/.cargo/bin", "$home/.bun/bin")
            val parts = path.orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
            return (parts + extra.filter { it !in parts }).joinToString(File.pathSeparator)
        }
    }
}

/** Runs [call] on the IO pool, cancelling it if the caller is cancelled (a timeout, or the user pressing Stop). */
private suspend fun execute(call: Call, block: (Response) -> Unit) {
    val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
    try {
        withContext(Dispatchers.IO) { call.execute().use(block) }
    } finally {
        handle?.dispose()
    }
}

/** Turns a failed status into the matching exception; returns normally for a success. */
private fun checkStatus(response: Response, expiredSession: Boolean) {
    val code = response.code
    if (code in 200..299) return
    val challenge = response.header("WWW-Authenticate")
    when {
        code == 401 -> throw McpAuthRequiredException(challenge)
        code == 403 && challenge?.contains("insufficient_scope") == true ->
            throw McpAuthRequiredException(challenge, "This server needs more permissions. Sign in again.")
        code == 404 && expiredSession -> throw McpSessionExpiredException()
    }
    val detail = runCatching { response.body?.string() }.getOrNull().orEmpty().trim().take(300)
    throw McpHttpException(code, "The server answered HTTP $code" + if (detail.isNotEmpty()) ": $detail" else ".")
}

/**
 * Reads server-sent events until the stream ends or [onEvent] returns false. [onEvent] gets the event name
 * ("message" when unnamed) and its data, with multi-line data joined by newlines.
 */
internal fun readEvents(source: BufferedSource, onEvent: (event: String, data: String) -> Boolean) {
    var event = "message"
    val data = StringBuilder()
    var hasData = false
    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (hasData && !onEvent(event, data.toString())) return
                event = "message"
                data.setLength(0)
                hasData = false
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.substringAfter(':').trim()
            line.startsWith("data:") -> {
                if (hasData) data.append('\n')
                data.append(line.substringAfter(':').removePrefix(" "))
                hasData = true
            }
        }
    }
    if (hasData) onEvent(event, data.toString())
}

/** One JSON-RPC message, or a batch of them; null for anything that is not JSON-RPC. */
internal fun parseMessage(text: String): List<JsonObject>? = runCatching {
    when (val el = LENIENT.parseToJsonElement(text)) {
        is JsonObject -> listOf(el)
        is JsonArray -> el.filterIsInstance<JsonObject>()
        else -> null
    }
}.getOrNull()
