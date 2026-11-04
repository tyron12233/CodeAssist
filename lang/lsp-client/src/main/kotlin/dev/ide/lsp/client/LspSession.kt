package dev.ide.lsp.client

import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One running language server for one workspace root: the `initialize` handshake, the server's copy of every
 * document the IDE has asked about, the diagnostics it publishes, and the requests it sends back.
 *
 * Every blocking call takes a [cancel] check that it polls while it waits (the engine's cancel token, on the
 * engine thread): a completion keystroke must be able to take the thread back from a slow hover. A request the
 * caller gives up on is cancelled at the server too.
 */
class LspSession(
    val definition: LanguageServerDefinition,
    val context: LanguageServerContext,
    private val events: Events,
    private val clientVersion: String = "dev",
) {
    /** What the session reports back to its owner, from the dispatch thread. */
    interface Events {
        /** [path]'s pushed diagnostics changed and no analysis call was waiting for them. */
        fun diagnosticsChanged(session: LspSession, path: Path) {}

        /** The server asked to edit the workspace outside a command the IDE ran. Return whether it was applied. */
        fun applyEdit(session: LspSession, edit: JsonObject): Boolean = false

        /** `window/showMessage`: [type] 1 error, 2 warning, 3 info, 4 log. */
        fun message(session: LspSession, type: Int, text: String) {}

        /** The server asked the client to re-pull semantic tokens, inlay hints or diagnostics. */
        fun refreshRequested(session: LspSession) {}

        /** The server went away; [failure] is null for a clean exit. */
        fun exited(session: LspSession, failure: Throwable?) {}

        /** The handshake finished; [waited] are the files something asked about while it was still starting. */
        fun ready(session: LspSession, waited: Set<Path>) {}
    }

    enum class State { NEW, STARTING, READY, UNAVAILABLE, FAILED, CLOSED }

    @Volatile var state: State = State.NEW
        private set

    /** Why the session is [State.FAILED] or [State.UNAVAILABLE], for the log and the user. */
    @Volatile var failureReason: String? = null
        private set

    /** The server's `capabilities`, from `initialize`. Empty until [State.READY]. */
    @Volatile var capabilities: JsonObject = EMPTY
        private set

    val name: String get() = definition.id
    private val log get() = context.logger
    private val ready = CountDownLatch(1)
    @Volatile private var rpc: JsonRpcConnection? = null
    @Volatile private var server: LanguageServerConnection? = null

    // ---------------------------------------------------------------------------------------------- lifecycle

    /** Start the server and complete the handshake. Blocking: call it from a background thread, once. */
    fun start(initializeTimeoutMs: Long = INITIALIZE_TIMEOUT_MS) {
        synchronized(this) {
            if (state != State.NEW) return
            state = State.STARTING
        }
        try {
            // Throwable, not Exception: a plugin's start can fail with a LinkageError (a missing native library, a
            // class from a newer SPI), and anything escaping here would leave the session STARTING forever.
            val connection = try {
                definition.start(context)
            } catch (e: Throwable) {
                fail(State.FAILED, "could not start: ${e.message ?: e}", e)
                return
            }
            if (connection == null) {
                fail(State.UNAVAILABLE, "not available on this device", null)
                return
            }
            server = connection
            connection.errors?.let { drainErrors(it) }
            val jsonRpc = JsonRpcConnection(connection.input, connection.output, name, log)
            jsonRpc.requestHandler = JsonRpcConnection.RequestHandler(::onServerRequest)
            jsonRpc.notificationHandler = JsonRpcConnection.NotificationHandler(::onServerNotification)
            jsonRpc.onClosed = { failure -> onConnectionClosed(failure) }
            rpc = jsonRpc
            jsonRpc.start()

            val result = jsonRpc.request("initialize", initializeParams()).future
                .get(initializeTimeoutMs, TimeUnit.MILLISECONDS).obj()
            capabilities = result?.obj("capabilities") ?: EMPTY
            jsonRpc.notify("initialized", EMPTY)
            parseJsonOrNull(definition.configuration(null, context))?.let { settings ->
                jsonRpc.notify("workspace/didChangeConfiguration", buildJsonObject { put("settings", settings) })
            }
            val nowReady = synchronized(this) {
                if (state == State.STARTING) { state = State.READY; true } else false
            }
            val info = result?.obj("serverInfo")
            log.info("$name started for ${context.root}" + (info?.str("name")?.let { " ($it ${info.str("version").orEmpty()})" } ?: ""))
            if (nowReady) {
                ready.countDown()
                val waited = HashSet(askedWhileStarting)
                askedWhileStarting.clear()
                if (waited.isNotEmpty()) events.ready(this, waited)
            }
        } catch (e: TimeoutException) {
            fail(State.FAILED, "did not answer initialize within ${initializeTimeoutMs / 1000}s", null)
        } catch (e: ExecutionException) {
            fail(State.FAILED, "initialize failed: ${e.cause?.message ?: e.message}", e.cause)
        } catch (e: Throwable) {
            fail(State.FAILED, "could not start: ${e.message ?: e}", e)
        } finally {
            ready.countDown()
        }
    }

    /** Wait up to [timeoutMs] for the handshake, polling [cancel]. True once [State.READY]. */
    fun awaitReady(timeoutMs: Long, cancel: () -> Unit = {}): Boolean {
        if (state == State.READY) return true
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            if (state == State.READY) return true
            if (state != State.NEW && state != State.STARTING) return false
            val left = deadline - System.nanoTime()
            if (left <= 0) return false
            cancel()
            ready.await(minOf(left, POLL_NANOS), TimeUnit.NANOSECONDS)
        }
    }

    val isReady: Boolean get() = state == State.READY

    private val askedWhileStarting = ConcurrentHashMap.newKeySet<Path>()

    /**
     * Something asked about [path] before the handshake finished and went without an answer: once ready, the
     * session announces it through [Events.ready] so the editor asks again rather than showing nothing.
     */
    fun noteAskedWhileStarting(path: Path) {
        if (state == State.NEW || state == State.STARTING) askedWhileStarting.add(path)
    }

    /** `shutdown` + `exit`, then release the connection. Idempotent; safe from any thread. */
    fun shutdown() {
        val wasReady = synchronized(this) {
            if (state == State.CLOSED) return
            val r = state == State.READY
            state = State.CLOSED
            r
        }
        ready.countDown()
        val connection = rpc
        if (wasReady && connection != null && !connection.isClosed) {
            runCatching { connection.request("shutdown", null).future.get(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            connection.notify("exit", null)
        }
        connection?.close()
        server?.close()
        documents.clear()
        if (methodStats.isNotEmpty() && dev.ide.platform.log.PerfTrace.enabled) {
            log.info("lsp-perf server=$name session summary:\n" + methodStats.entries.sortedBy { it.key }.joinToString("\n") { (m, s) -> "  $m $s" })
        }
    }

    private fun fail(newState: State, reason: String, cause: Throwable?) {
        synchronized(this) {
            if (state == State.CLOSED) return
            state = newState
            failureReason = reason
        }
        if (newState == State.UNAVAILABLE) log.info("$name: $reason") else log.warn("$name: $reason", cause)
        rpc?.close()
        server?.close()
    }

    private fun onConnectionClosed(failure: Throwable?) {
        val wasLive = synchronized(this) {
            if (state == State.CLOSED || state == State.FAILED) false
            else {
                state = State.FAILED
                failureReason = "exited" + (failure?.message?.let { ": $it" } ?: "")
                true
            }
        }
        ready.countDown()
        if (!wasLive) return
        log.warn("$name exited unexpectedly", failure)
        server?.close()
        diagnosticsLock.withLock { diagnosticsArrived.signalAll() }
        events.exited(this, failure)
    }

    private fun drainErrors(stream: java.io.InputStream) {
        Thread({
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { if (it.isNotBlank()) log.debug("$name stderr: $it") }
                }
            }
        }, "lsp-$name-stderr").apply { isDaemon = true; start() }
    }

    // ---------------------------------------------------------------------------------------------- requests

    /**
     * Send [method] and wait up to [timeoutMs] for its result, polling [cancel]. Null for a JSON `null`, a
     * timeout, an error response or a session that is not running; the caller treats all of those as "no
     * answer" (they are logged at debug). [cancel] throwing cancels the request at the server and rethrows.
     */
    fun request(method: String, params: JsonElement?, timeoutMs: Long, cancel: () -> Unit = {}): JsonElement? =
        try {
            requestOrThrow(method, params, timeoutMs, cancel)
        } catch (e: ResponseErrorException) {
            if (e.code != ResponseErrorException.REQUEST_CANCELLED && e.code != ResponseErrorException.CONTENT_MODIFIED) {
                log.debug("$name: $method failed: ${e.message}")
            }
            null
        } catch (e: TimeoutException) {
            log.debug("$name: $method timed out after ${timeoutMs}ms")
            null
        } catch (e: ConnectionClosedException) {
            null
        }

    /** [request], but an error response, a timeout and a closed connection are thrown rather than swallowed. */
    fun requestOrThrow(method: String, params: JsonElement?, timeoutMs: Long, cancel: () -> Unit = {}): JsonElement? {
        if (!awaitReady(timeoutMs, cancel)) throw ConnectionClosedException("$name is not running")
        val connection = rpc ?: throw ConnectionClosedException("$name is not running")
        val pending = connection.request(method, params)
        val sent = System.nanoTime()
        var outcome = Outcome.ERROR
        val deadline = sent + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                cancel()
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    connection.cancel(pending.id)
                    outcome = Outcome.TIMEOUT
                    throw TimeoutException("$method timed out")
                }
                try {
                    return pending.future.get(minOf(left, POLL_NANOS), TimeUnit.NANOSECONDS).also { outcome = Outcome.OK }
                } catch (_: TimeoutException) {
                    // poll [cancel] again
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                } catch (e: CancellationException) {
                    throw ConnectionClosedException("$method was cancelled")
                }
            }
        } catch (e: Throwable) {
            if (!pending.future.isDone) {
                connection.cancel(pending.id)
                if (outcome == Outcome.ERROR) outcome = Outcome.CANCELLED
            }
            throw e
        } finally {
            record(method, System.nanoTime() - sent, pending, outcome)
        }
    }

    // ---------------------------------------------------------------------------------------------- timing

    private enum class Outcome { OK, ERROR, TIMEOUT, CANCELLED }

    /** How one kind of request has gone this session: what [stats] reports. */
    class MethodStats internal constructor() {
        @Volatile var count: Int = 0; internal set
        @Volatile var totalMillis: Long = 0; internal set
        @Volatile var maxMillis: Long = 0; internal set
        @Volatile var totalChars: Long = 0; internal set
        @Volatile var maxChars: Int = 0; internal set
        /** Time the client spent parsing answers: the part a phone pays for a large one. */
        @Volatile var parseMillis: Long = 0; internal set
        @Volatile var timeouts: Int = 0; internal set
        /** Requests the editor gave up on (a keystroke preempted them) before the server answered. */
        @Volatile var cancelled: Int = 0; internal set

        override fun toString(): String =
            "n=$count avg=${if (count == 0) 0 else totalMillis / count}ms max=${maxMillis}ms " +
                "avgSize=${if (count == 0) 0 else totalChars / count / 1024}KB maxSize=${maxChars / 1024}KB " +
                "parse=${parseMillis}ms timeouts=$timeouts cancelled=$cancelled"
    }

    private val methodStats = ConcurrentHashMap<String, MethodStats>()

    /** Per-method request timings for this session, for the log and for a performance report. */
    fun stats(): Map<String, MethodStats> = methodStats.toMap()

    private fun record(method: String, nanos: Long, pending: JsonRpcConnection.Pending, outcome: Outcome) {
        val millis = TimeUnit.NANOSECONDS.toMillis(nanos)
        val chars = pending.responseChars
        val parse = TimeUnit.NANOSECONDS.toMillis(pending.parseNanos)
        val s = methodStats.getOrPut(method) { MethodStats() }
        synchronized(s) {
            s.count++
            s.totalMillis += millis
            if (millis > s.maxMillis) s.maxMillis = millis
            s.totalChars += chars
            if (chars > s.maxChars) s.maxChars = chars
            s.parseMillis += parse
            if (outcome == Outcome.TIMEOUT) s.timeouts++
            if (outcome == Outcome.CANCELLED) s.cancelled++
        }
        val line = "lsp-perf server=$name method=$method took=${millis}ms size=${chars / 1024}KB parse=${parse}ms ${outcome.name.lowercase()}"
        when {
            // Always worth knowing: these are what make the editor feel slow.
            millis >= SLOW_REQUEST_MS || chars >= LARGE_RESPONSE_CHARS -> log.info("$line (slow or large)")
            dev.ide.platform.log.PerfTrace.enabled -> log.info(line)
        }
    }

    fun notify(method: String, params: JsonElement?) {
        if (state == State.READY) rpc?.notify(method, params)
    }

    /**
     * A `WorkspaceEdit` this server sent, as edits per file, each in the text the server's positions refer to:
     * its copy of an open document, else the file on disk. A file the edit creates comes back as one insertion
     * at offset 0 into a file that does not exist yet.
     */
    fun documentEdits(edit: JsonObject): Map<Path, List<dev.ide.lang.incremental.DocumentEdit>> {
        val converted = LspConvert.workspaceEdit(edit, null) { p -> document(p)?.text ?: LspConvert.readText(p) }
        return converted.edits.entries.associate { (file, edits) -> java.nio.file.Paths.get(file.path) to edits }
    }

    /** Whether the server advertised [provider] (`hoverProvider`, `renameProvider`, ...). */
    fun supports(provider: String): Boolean = capabilities.capability(provider)

    // ---------------------------------------------------------------------------------------------- documents

    /** The server's copy of one document, as last sent. */
    class Document internal constructor(val uri: String, val path: Path) {
        @Volatile var version: Int = 0
            internal set
        @Volatile var text: String = ""
            internal set
        /** [diagnosticsSequence] value when [text] was last sent, so a wait can tell a stale publish from a fresh one. */
        @Volatile internal var sentAt: Long = 0
        @Volatile private var index: LineIndex? = null
        @Volatile private var indexedText: String? = null

        val lines: LineIndex
            get() {
                val t = text
                val cached = index
                if (cached != null && indexedText === t) return cached
                return LineIndex(t).also { index = it; indexedText = t }
            }
    }

    private val documents = ConcurrentHashMap<String, Document>()
    private val documentLock = Any()

    /**
     * Make the server's copy of [path] equal [text]: `didOpen` the first time, `didChange` (the whole text,
     * which every sync kind accepts) when it differs. Null when the session is not running.
     */
    fun sync(path: Path, text: CharSequence): Document? {
        if (state != State.READY) return null
        val connection = rpc ?: return null
        val uri = uriOf(path)
        val content = text.toString()
        synchronized(documentLock) {
            val existing = documents[uri]
            if (existing != null) {
                if (existing.text == content) return existing
                val previous = existing.text
                val previousLines = existing.lines
                existing.version++
                existing.text = content
                existing.sentAt = diagnosticsSequence.incrementAndGet()
                val kind = syncKind()
                if (kind != SYNC_NONE) {
                    connection.notify("textDocument/didChange", buildJsonObject {
                        putJsonObject("textDocument") { put("uri", uri); put("version", existing.version) }
                        putJsonArray("contentChanges") {
                            if (kind == SYNC_INCREMENTAL) {
                                // Only the span that changed: a keystroke in a large file is a few bytes, not the file.
                                val change = incrementalChange(previous, content)
                                addJsonObject {
                                    putJsonObject("range") {
                                        put("start", previousLines.position(change.start))
                                        put("end", previousLines.position(change.oldEnd))
                                    }
                                    put("text", change.text)
                                }
                            } else {
                                addJsonObject { put("text", content) }
                            }
                        }
                    })
                }
                return existing
            }
            val doc = Document(uri, path)
            doc.version = 1
            doc.text = content
            doc.sentAt = diagnosticsSequence.incrementAndGet()
            documents[uri] = doc
            if (openClose()) {
                connection.notify("textDocument/didOpen", buildJsonObject {
                    putJsonObject("textDocument") {
                        put("uri", uri)
                        put("languageId", runCatching { definition.languageIdFor(path) }.getOrDefault(definition.languages.first().id))
                        put("version", doc.version)
                        put("text", content)
                    }
                })
            }
            return doc
        }
    }

    fun document(path: Path): Document? = documents[uriOf(path)]

    fun openDocuments(): List<Document> = documents.values.toList()

    /** `didClose`: the editor closed [path]'s tab. The server falls back to the file on disk. */
    fun close(path: Path) {
        val uri = uriOf(path)
        synchronized(documentLock) {
            documents.remove(uri) ?: return
            if (openClose()) rpc?.notify("textDocument/didClose", buildJsonObject { put("textDocument", textDocument(uri)) })
        }
        diagnosticsLock.withLock { published.remove(uri); pulled.remove(uri) }
    }

    /** `didSave`, for a server that asked for it, with the text when it asked for that too. */
    fun saved(path: Path) {
        val doc = document(path) ?: return
        val save = when (val sync = capabilities["textDocumentSync"]) {
            is JsonObject -> sync["save"]
            else -> null
        } ?: return
        if (save is JsonPrimitive && save.content == "false") return
        val includeText = (save as? JsonObject)?.bool("includeText") == true
        notify("textDocument/didSave", buildJsonObject {
            put("textDocument", textDocument(doc.uri))
            if (includeText) put("text", doc.text)
        })
    }

    /** `workspace/didChangeWatchedFiles` with [changes] as (path, type: 1 created, 2 changed, 3 deleted). */
    fun filesChanged(changes: List<Pair<Path, Int>>) {
        if (changes.isEmpty()) return
        notify("workspace/didChangeWatchedFiles", buildJsonObject {
            putJsonArray("changes") {
                for ((p, type) in changes) addJsonObject { put("uri", uriOf(p)); put("type", type) }
            }
        })
    }

    private fun syncKind(): Int = when (val sync = capabilities["textDocumentSync"]) {
        is JsonPrimitive -> sync.content.toIntOrNull() ?: SYNC_FULL
        is JsonObject -> sync.int("change") ?: SYNC_NONE
        else -> SYNC_FULL
    }

    private fun openClose(): Boolean = when (val sync = capabilities["textDocumentSync"]) {
        is JsonObject -> sync.bool("openClose") ?: false
        null, JsonNull -> true
        else -> true
    }

    // ---------------------------------------------------------------------------------------------- diagnostics

    /** Diagnostics for one document and the text whose offsets they are in. */
    class Diagnostics(val document: Document, val items: JsonArray, val lines: LineIndex)

    private class Published(val version: Int?, val items: JsonArray, val sequence: Long)

    private val diagnosticsLock = ReentrantLock()
    private val diagnosticsArrived = diagnosticsLock.newCondition()
    private val diagnosticsSequence = AtomicLong()
    private val published = HashMap<String, Published>()
    private val pulled = HashMap<String, Pair<String?, JsonArray>>() // uri -> (resultId, items)
    private val waiting = HashMap<String, Int>()
    /** Documents whose last wait timed out with nothing published: not waited on again until something is. */
    private val silent = HashSet<String>()

    /**
     * The diagnostics for [path] once the server has caught up with [text]: pulled (`textDocument/diagnostic`)
     * from a server that supports it, otherwise the next `publishDiagnostics` after the text was sent, waited
     * for up to [waitMs]. A publish that arrives after the wait is announced through [Events.diagnosticsChanged]
     * so the editor asks again.
     */
    fun diagnostics(path: Path, text: CharSequence, waitMs: Long, cancel: () -> Unit = {}): Diagnostics? {
        val doc = sync(path, text) ?: return null
        if (capabilities.capability("diagnosticProvider")) {
            pull(doc, waitMs, cancel)?.let { return Diagnostics(doc, it, doc.lines) }
        }
        val uri = doc.uri
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        diagnosticsLock.withLock {
            waiting.merge(uri, 1, Int::plus)
            try {
                while (true) {
                    val p = published[uri]
                    val fresh = p != null && (if (p.version != null) p.version >= doc.version else p.sequence > doc.sentAt)
                    if (fresh || state != State.READY) break
                    if (uri in silent) break
                    val left = deadline - System.nanoTime()
                    if (left <= 0) {
                        if (p == null) silent += uri
                        break
                    }
                    diagnosticsArrived.awaitNanos(minOf(left, POLL_NANOS))
                    // [cancel] may throw: the finally below keeps [waiting] balanced.
                    cancel()
                }
            } finally {
                waiting.merge(uri, -1) { a, b -> (a + b).takeIf { it > 0 } }
            }
            val items = published[uri]?.items ?: JsonArray(emptyList())
            return Diagnostics(doc, items, doc.lines)
        }
    }

    private fun pull(doc: Document, waitMs: Long, cancel: () -> Unit): JsonArray? {
        val previous = diagnosticsLock.withLock { pulled[doc.uri] }
        val result = request("textDocument/diagnostic", buildJsonObject {
            put("textDocument", textDocument(doc.uri))
            previous?.first?.let { put("previousResultId", it) }
        }, waitMs, cancel).obj() ?: return null
        val items = when (result.str("kind")) {
            "unchanged" -> previous?.second ?: return null
            else -> result.arr("items") ?: JsonArray(emptyList())
        }
        diagnosticsLock.withLock { pulled[doc.uri] = result.str("resultId") to items }
        return items
    }

    private fun onPublishDiagnostics(params: JsonObject) {
        val uri = params.str("uri") ?: return
        val items = params.arr("diagnostics") ?: JsonArray(emptyList())
        val announce = diagnosticsLock.withLock {
            val before = published[uri]
            published[uri] = Published(params.int("version"), items, diagnosticsSequence.incrementAndGet())
            silent -= uri
            diagnosticsArrived.signalAll()
            (waiting[uri] ?: 0) == 0 && before?.items != items
        }
        if (announce) pathOf(uri)?.let { events.diagnosticsChanged(this, it) }
    }

    // ---------------------------------------------------------------------------------------------- edits

    @Volatile private var capture: MutableList<JsonObject>? = null
    private val captureLock = Any()

    /**
     * Run [block] (an `executeCommand`) while collecting the `workspace/applyEdit` requests the server sends
     * during it, answering each as applied: the edits become the action's own result, which the IDE applies
     * the way it applies any quick fix, rather than being written behind the editor's back.
     */
    fun <T> captureEdits(block: () -> T): Pair<T, List<JsonObject>> = synchronized(captureLock) {
        val edits = java.util.Collections.synchronizedList(ArrayList<JsonObject>())
        capture = edits
        try {
            val result = block()
            result to edits.toList()
        } finally {
            capture = null
        }
    }

    // ---------------------------------------------------------------------------------------------- server -> client

    private val registrations = ConcurrentHashMap<String, String>() // id -> method

    /** Whether the server registered [method] dynamically (`client/registerCapability`). */
    fun registered(method: String): Boolean = registrations.containsValue(method)

    private fun onServerRequest(method: String, params: JsonElement?): JsonElement? = when (method) {
        "workspace/configuration" -> buildJsonArray {
            for (item in params.obj()?.arr("items").orEmpty()) {
                val section = item.obj()?.str("section")
                add(parseJsonOrNull(runCatching { definition.configuration(section, context) }.getOrNull()) ?: JsonNull)
            }
        }
        "workspace/applyEdit" -> {
            val edit = params.obj()?.obj("edit") ?: EMPTY
            val sink = capture
            val applied = if (sink != null) { sink += edit; true } else runCatching { events.applyEdit(this, edit) }.getOrDefault(false)
            buildJsonObject { put("applied", applied) }
        }
        "workspace/workspaceFolders" -> buildJsonArray { add(workspaceFolder()) }
        "client/registerCapability" -> {
            for (r in params.obj()?.arr("registrations").orEmpty()) {
                val o = r.obj() ?: continue
                val id = o.str("id") ?: continue
                o.str("method")?.let { registrations[id] = it }
            }
            null
        }
        "client/unregisterCapability" -> {
            // The protocol spells this field "unregisterations".
            for (r in params.obj()?.arr("unregisterations").orEmpty()) r.obj()?.str("id")?.let { registrations.remove(it) }
            null
        }
        "window/workDoneProgress/create" -> null
        "window/showMessageRequest" -> {
            params.obj()?.let { p -> p.str("message")?.let { events.message(this, p.int("type") ?: 3, it) } }
            null
        }
        "window/showDocument" -> buildJsonObject { put("success", false) }
        "workspace/semanticTokens/refresh", "workspace/inlayHint/refresh", "workspace/codeLens/refresh",
        "workspace/diagnostic/refresh", "workspace/foldingRange/refresh" -> {
            if (method == "workspace/diagnostic/refresh") diagnosticsLock.withLock { pulled.clear() }
            events.refreshRequested(this)
            null
        }
        else -> throw ResponseErrorException(ResponseErrorException.METHOD_NOT_FOUND, "unsupported: $method")
    }

    private fun onServerNotification(method: String, params: JsonElement?) {
        val p = params.obj()
        when (method) {
            "textDocument/publishDiagnostics" -> p?.let(::onPublishDiagnostics)
            "window/logMessage" -> p?.str("message")?.let { msg ->
                when (p.int("type")) {
                    1 -> log.warn("$name: $msg")
                    2 -> log.info("$name: $msg")
                    else -> log.debug("$name: $msg")
                }
            }
            "window/showMessage" -> p?.str("message")?.let { events.message(this, p.int("type") ?: 3, it) }
            else -> Unit // $/progress, telemetry/event, $/logTrace: nothing to show
        }
    }

    // ---------------------------------------------------------------------------------------------- initialize

    private fun workspaceFolder(): JsonObject = buildJsonObject {
        put("uri", uriOf(context.root))
        put("name", context.root.fileName?.toString() ?: context.root.toString())
    }

    private fun initializeParams(): JsonObject = buildJsonObject {
        put("processId", JsonNull)
        putJsonObject("clientInfo") { put("name", "CodeAssist"); put("version", clientVersion) }
        put("locale", java.util.Locale.getDefault().toLanguageTag())
        put("rootPath", context.root.toString())
        put("rootUri", uriOf(context.root))
        putJsonArray("workspaceFolders") { add(workspaceFolder()) }
        parseJsonOrNull(runCatching { definition.initializationOptions(context) }.getOrNull())?.let { put("initializationOptions", it) }
        put("capabilities", CLIENT_CAPABILITIES)
        put("trace", "off")
    }

    companion object {
        const val INITIALIZE_TIMEOUT_MS = 60_000L
        /** A request this slow, or an answer this large, is logged even with timing logs off. */
        const val SLOW_REQUEST_MS = 1_500L
        const val LARGE_RESPONSE_CHARS = 1_000_000
        private const val SHUTDOWN_TIMEOUT_MS = 2_000L
        private val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20)
        private const val SYNC_NONE = 0
        private const val SYNC_FULL = 1
        private const val SYNC_INCREMENTAL = 2

        /** The one replacement that turns [old] into [new]: `old[start, oldEnd)` becomes [Change.text]. */
        internal class Change(val start: Int, val oldEnd: Int, val text: String)

        /**
         * The smallest single replacement from [old] to [new]: the common prefix and suffix are left alone. Its
         * ends never split a surrogate pair or a `\r\n`, whose middle has no position a server agrees on.
         */
        internal fun incrementalChange(old: String, new: String): Change {
            var start = 0
            val shorter = minOf(old.length, new.length)
            while (start < shorter && old[start] == new[start]) start++
            var oldEnd = old.length
            var newEnd = new.length
            while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) { oldEnd--; newEnd-- }
            fun splitsStart(i: Int, t: String) = i in 1 until t.length &&
                (Character.isLowSurrogate(t[i]) || (t[i - 1] == '\r' && t[i] == '\n'))
            while (start > 0 && (splitsStart(start, old) || splitsStart(start, new))) start--
            fun splitsEnd(i: Int, t: String) = i in 1 until t.length &&
                (Character.isLowSurrogate(t[i]) || (t[i - 1] == '\r' && t[i] == '\n'))
            while (oldEnd < old.length && newEnd < new.length && (splitsEnd(oldEnd, old) || splitsEnd(newEnd, new))) { oldEnd++; newEnd++ }
            return Change(start, oldEnd, new.substring(start, newEnd))
        }
        private val EMPTY = JsonObject(emptyMap())

        val SEMANTIC_TOKEN_TYPES = listOf(
            "namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter", "variable",
            "property", "enumMember", "event", "function", "method", "macro", "keyword", "modifier", "comment",
            "string", "number", "regexp", "operator", "decorator",
        )
        val SEMANTIC_TOKEN_MODIFIERS = listOf(
            "declaration", "definition", "readonly", "static", "deprecated", "abstract", "async", "modification",
            "documentation", "defaultLibrary",
        )

        /** What this client understands, sent with `initialize`. Only what the adapters actually consume. */
        val CLIENT_CAPABILITIES: JsonObject = buildJsonObject {
            putJsonObject("general") { putJsonArray("positionEncodings") { add("utf-16") } }
            putJsonObject("workspace") {
                put("applyEdit", true)
                putJsonObject("workspaceEdit") {
                    put("documentChanges", true)
                    putJsonArray("resourceOperations") { add("create") }
                }
                put("configuration", true)
                put("workspaceFolders", true)
                putJsonObject("didChangeConfiguration") { put("dynamicRegistration", true) }
                putJsonObject("didChangeWatchedFiles") { put("dynamicRegistration", true) }
                putJsonObject("executeCommand") {}
                putJsonObject("semanticTokens") { put("refreshSupport", true) }
                putJsonObject("inlayHint") { put("refreshSupport", true) }
                putJsonObject("diagnostics") { put("refreshSupport", true) }
            }
            putJsonObject("textDocument") {
                putJsonObject("synchronization") { put("didSave", true) }
                putJsonObject("completion") {
                    put("contextSupport", true)
                    putJsonObject("completionItem") {
                        put("snippetSupport", true)
                        put("deprecatedSupport", true)
                        put("labelDetailsSupport", true)
                        putJsonArray("documentationFormat") { add("markdown"); add("plaintext") }
                        putJsonObject("tagSupport") { putJsonArray("valueSet") { add(1) } }
                    }
                    putJsonObject("completionItemKind") { putJsonArray("valueSet") { for (k in 1..25) add(k) } }
                }
                putJsonObject("hover") { putJsonArray("contentFormat") { add("markdown"); add("plaintext") } }
                putJsonObject("signatureHelp") {
                    put("contextSupport", true)
                    putJsonObject("signatureInformation") {
                        putJsonArray("documentationFormat") { add("markdown"); add("plaintext") }
                        putJsonObject("parameterInformation") { put("labelOffsetSupport", true) }
                        put("activeParameterSupport", true)
                    }
                }
                putJsonObject("declaration") { put("linkSupport", true) }
                putJsonObject("definition") { put("linkSupport", true) }
                putJsonObject("typeDefinition") { put("linkSupport", true) }
                putJsonObject("implementation") { put("linkSupport", true) }
                putJsonObject("references") {}
                putJsonObject("documentHighlight") {}
                putJsonObject("documentSymbol") {
                    put("hierarchicalDocumentSymbolSupport", true)
                    putJsonObject("symbolKind") { putJsonArray("valueSet") { for (k in 1..26) add(k) } }
                }
                putJsonObject("codeAction") {
                    putJsonObject("codeActionLiteralSupport") {
                        putJsonObject("codeActionKind") {
                            putJsonArray("valueSet") {
                                listOf(
                                    "", "quickfix", "refactor", "refactor.extract", "refactor.inline", "refactor.rewrite",
                                    "source", "source.organizeImports", "source.fixAll",
                                ).forEach { add(it) }
                            }
                        }
                    }
                    put("isPreferredSupport", true)
                    put("disabledSupport", true)
                    put("dataSupport", true)
                    putJsonObject("resolveSupport") { putJsonArray("properties") { add("edit") } }
                }
                putJsonObject("formatting") {}
                putJsonObject("rangeFormatting") {}
                putJsonObject("rename") { put("prepareSupport", true) }
                putJsonObject("publishDiagnostics") {
                    put("relatedInformation", true)
                    put("versionSupport", true)
                    put("codeDescriptionSupport", true)
                    put("dataSupport", true)
                    putJsonObject("tagSupport") { putJsonArray("valueSet") { add(1); add(2) } }
                }
                putJsonObject("foldingRange") {
                    put("lineFoldingOnly", false)
                    putJsonObject("foldingRangeKind") { putJsonArray("valueSet") { add("comment"); add("imports"); add("region") } }
                    putJsonObject("foldingRange") { put("collapsedText", true) }
                }
                putJsonObject("semanticTokens") {
                    putJsonObject("requests") { putJsonObject("full") { put("delta", true) }; put("range", false) }
                    putJsonArray("tokenTypes") { SEMANTIC_TOKEN_TYPES.forEach { add(it) } }
                    putJsonArray("tokenModifiers") { SEMANTIC_TOKEN_MODIFIERS.forEach { add(it) } }
                    putJsonArray("formats") { add("relative") }
                    put("overlappingTokenSupport", false)
                    put("multilineTokenSupport", false)
                    put("augmentsSyntaxTokens", true)
                }
                putJsonObject("inlayHint") {}
                putJsonObject("diagnostic") { put("relatedDocumentSupport", false) }
            }
            putJsonObject("window") {
                put("workDoneProgress", true)
                putJsonObject("showMessage") {}
                putJsonObject("showDocument") { put("support", false) }
            }
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
