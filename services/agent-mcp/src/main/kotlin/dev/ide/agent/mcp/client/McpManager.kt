package dev.ide.agent.mcp.client

import dev.ide.agent.AgentTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/** Where sign-ins are kept between runs, keyed by [McpServerConfig.identity]. */
interface McpTokenStore {
    fun get(identity: String): McpTokens?
    fun put(identity: String, tokens: McpTokens)
    fun remove(identity: String)
}

/** A server's state as the UI shows it. */
data class McpServerState(
    val name: String,
    val status: Status,
    val error: String? = null,
    val tools: List<McpToolInfo> = emptyList(),
    /** Whether a sign-in is stored for this server. */
    val signedIn: Boolean = false,
    val serverName: String? = null,
    val instructions: String? = null,
) {
    enum class Status { DISABLED, CONNECTING, CONNECTED, NEEDS_AUTH, FAILED, UNSUPPORTED }
}

/**
 * The live connections to the servers the user configured, and their tools as [AgentTool]s.
 *
 * [update] is given the whole wanted set whenever it may have changed; servers whose config is unchanged keep their
 * connection, the rest are closed or opened. Connecting happens in the background, so a slow or dead server never
 * holds up the chat: its tools simply appear when it answers. A call on a dropped connection reconnects once; an
 * expired sign-in is refreshed once; a server that still refuses is marked as needing sign-in.
 */
class McpManager(
    private val scope: CoroutineScope,
    private val tokens: McpTokenStore,
    /** Whether local (stdio) servers may run here: false on Android, which has no Node or Python. */
    private val stdioAllowed: Boolean,
    private val log: (String) -> Unit = {},
) {
    private class Live(
        val config: McpServerConfig,
        val workingDir: File?,
        val lock: Mutex = Mutex(),
        @Volatile var client: McpClient? = null,
        @Volatile var tools: List<McpToolInfo> = emptyList(),
        @Volatile var challenge: String? = null,
        @Volatile var job: Job? = null,
    )

    private val servers = LinkedHashMap<String, Live>()
    private val refreshLock = Mutex()

    private val _states = MutableStateFlow<List<McpServerState>>(emptyList())
    val states: StateFlow<List<McpServerState>> = _states.asStateFlow()

    /** Bumped whenever the set of tools changes, so a host can tell its prompt or tool list moved. */
    private val _toolsVersion = MutableStateFlow(0)
    val toolsVersion: StateFlow<Int> = _toolsVersion.asStateFlow()

    @Volatile
    private var toolCache: Pair<Int, List<AgentTool>>? = null

    /** Connects, keeps, or drops servers so that exactly [configs] are live. Names must be unique. */
    @Synchronized
    fun update(configs: List<McpServerConfig>, workingDir: File?) {
        val wanted = configs.associateBy { it.name }
        val iterator = servers.entries.iterator()
        while (iterator.hasNext()) {
            val (name, live) = iterator.next()
            val next = wanted[name]
            if (next == null || next != live.config || (next.transport == McpTransportKind.STDIO && live.workingDir != workingDir)) {
                disconnect(live)
                iterator.remove()
            }
        }
        for (config in configs) {
            if (config.name in servers) continue
            val live = Live(config, workingDir)
            servers[config.name] = live
            connectInBackground(live)
        }
        // Keep the configured order.
        val ordered = configs.mapNotNull { c -> servers[c.name]?.let { c.name to it } }
        servers.clear()
        ordered.forEach { (k, v) -> servers[k] = v }
        publish()
    }

    fun reconnect(name: String) {
        val live = synchronized(this) { servers[name] } ?: return
        disconnect(live)
        connectInBackground(live)
        publish()
    }

    /** Every tool of every connected server, named so no two collide. */
    fun tools(): List<AgentTool> {
        val version = _toolsVersion.value
        toolCache?.let { (v, list) -> if (v == version) return list }
        val taken = HashSet<String>()
        val out = ArrayList<AgentTool>()
        val snapshot = synchronized(this) { servers.values.toList() }
        for (live in snapshot) {
            if (live.client == null) continue
            for (info in live.tools) {
                val name = McpRemoteTool.toolName(live.config.name, info.name, taken)
                taken += name
                out += McpRemoteTool(live.config.name, info, name) { tool, args -> call(live.config.name, tool, args) }
            }
        }
        toolCache = version to out
        return out
    }

    /** The connected servers' own usage notes, for the system prompt. */
    fun instructions(): List<Pair<String, String>> = synchronized(this) { servers.values.toList() }
        .mapNotNull { live -> live.client?.instructions?.let { live.config.name to it } }

    /** Starts a browser sign-in for [name], returning what [completeSignIn] needs once the redirect arrives. */
    suspend fun beginSignIn(name: String, redirectUri: String): McpOAuth.Attempt {
        val live = synchronized(this) { servers[name] } ?: throw IOException("No server named $name.")
        val url = live.config.url ?: throw IOException("Only HTTP servers sign in with a browser.")
        return McpOAuth.begin(url, redirectUri, live.challenge)
    }

    suspend fun completeSignIn(name: String, attempt: McpOAuth.Attempt, code: String) {
        val live = synchronized(this) { servers[name] } ?: throw IOException("No server named $name.")
        val issued = McpOAuth.exchange(attempt, code)
        tokens.put(live.config.identity, issued)
        reconnect(name)
    }

    fun signOut(name: String) {
        val live = synchronized(this) { servers[name] } ?: return
        tokens.remove(live.config.identity)
        reconnect(name)
    }

    fun close() {
        synchronized(this) {
            servers.values.forEach(::disconnect)
            servers.clear()
        }
        publish()
    }

    // --- internals ---

    private suspend fun call(name: String, tool: String, arguments: JsonObject): McpCallResult {
        val live = synchronized(this) { servers[name] }
            ?: return McpCallResult("The MCP server \"$name\" is no longer configured.", isError = true)
        // A call is retried only when the server provably did not run it (an expired session, or credentials it
        // refused before looking at the request), so a tool that changes something is never run twice.
        for (attempt in 0..1) {
            val client = live.client ?: connectNow(live)
                ?: return McpCallResult(stateOf(live).error ?: "The MCP server \"$name\" is not connected.", isError = true)
            try {
                return client.callTool(tool, arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpException) {
                return McpCallResult("The tool failed: ${e.message}", isError = true)
            } catch (e: McpTimeoutException) {
                return McpCallResult("The MCP server \"$name\" did not answer in time.", isError = true)
            } catch (e: McpSessionExpiredException) {
                dropClient(live)
                if (attempt == 0) continue
                return McpCallResult("The MCP server \"$name\" keeps dropping its session.", isError = true)
            } catch (e: McpAuthRequiredException) {
                live.challenge = e.wwwAuthenticate
                dropClient(live)
                if (attempt == 0 && refreshTokens(live)) continue
                setState(live, McpServerState.Status.NEEDS_AUTH, "Sign in to ${live.config.name} to use its tools.")
                return McpCallResult(
                    "The MCP server \"$name\" needs the user to sign in. Tell the user to open Connections and sign in to it.",
                    isError = true,
                )
            } catch (e: IOException) {
                // The connection broke mid-call: whether the tool ran is unknown, so it is not run again here.
                dropClient(live)
                return McpCallResult(
                    "The connection to the MCP server \"$name\" broke during the call (${e.message}). It may or may not have run.",
                    isError = true,
                )
            }
        }
        return McpCallResult("The MCP server \"$name\" could not be reached.", isError = true)
    }

    private fun connectInBackground(live: Live) {
        live.job = scope.launch { connectNow(live) }
    }

    /** Connects [live] if it is not connected; null when it cannot be (the state says why). */
    private suspend fun connectNow(live: Live): McpClient? = live.lock.withLock {
        live.client?.let { return@withLock it }
        val config = live.config
        if (config.disabled) {
            setState(live, McpServerState.Status.DISABLED)
            return@withLock null
        }
        if (config.transport == McpTransportKind.STDIO && !stdioAllowed) {
            setState(live, McpServerState.Status.UNSUPPORTED, "Local (command) servers run on desktop only. Use a remote URL on this device.")
            return@withLock null
        }
        setState(live, McpServerState.Status.CONNECTING)
        var failure: Exception? = null
        for (attempt in 0..1) {
            try {
                return@withLock open(live, transportFor(config, live.workingDir))
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpAuthRequiredException) {
                live.challenge = e.wwwAuthenticate
                // An expired sign-in is renewed once; otherwise the user has to sign in.
                if (attempt == 0 && refreshTokens(live)) continue
                setState(live, McpServerState.Status.NEEDS_AUTH, e.message)
                return@withLock null
            } catch (e: Exception) {
                failure = e
                break
            }
        }
        val e = failure ?: return@withLock null
        if (config.transport == McpTransportKind.HTTP && e is McpHttpException && e.status in 400..499) {
            // A server that only speaks the older SSE transport refuses the POST; try that before giving up.
            try {
                return@withLock open(live, LegacySseTransport(config.url!!, headersFor(config)))
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: McpAuthRequiredException) {
                live.challenge = e2.wwwAuthenticate
                setState(live, McpServerState.Status.NEEDS_AUTH, e2.message)
                return@withLock null
            } catch (_: Exception) {
                // Report the original failure: the server is far more likely to be a broken Streamable one.
            }
        }
        log("MCP ${config.name}: ${e.message}")
        setState(live, McpServerState.Status.FAILED, describe(e))
        null
    }

    /** Handshakes over [transport] and lists the tools; on success [live] is connected through it. */
    private suspend fun open(live: Live, transport: McpTransport): McpClient {
        val client = McpClient(transport)
        try {
            client.connect()
            val listed = client.listTools()
            client.onToolsChanged = { scope.launch { relist(live, client) } }
            client.onDisconnected = { cause ->
                if (live.client === client) {
                    live.client = null
                    setState(live, McpServerState.Status.FAILED, cause?.message ?: "The server disconnected.")
                    bumpTools()
                }
            }
            live.client = client
            live.tools = listed
            live.challenge = null
            setState(live, McpServerState.Status.CONNECTED)
            bumpTools()
            log("MCP ${live.config.name}: connected, ${listed.size} tools")
            return client
        } catch (e: Throwable) {
            client.close()
            throw e
        }
    }

    private suspend fun relist(live: Live, client: McpClient) {
        if (live.client !== client) return
        try {
            live.tools = client.listTools()
            bumpTools()
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun transportFor(config: McpServerConfig, workingDir: File?): McpTransport = when (config.transport) {
        McpTransportKind.HTTP -> StreamableHttpTransport(config.url!!, headersFor(config))
        McpTransportKind.SSE -> LegacySseTransport(config.url!!, headersFor(config))
        McpTransportKind.STDIO -> StdioTransport(config.command!!, config.args, config.env, workingDir)
    }

    /** The configured headers, plus the stored sign-in's bearer token (refreshed first when it has expired). */
    private fun headersFor(config: McpServerConfig): McpHeaders = {
        val stored = tokens.get(config.identity)?.let { t -> if (t.expired()) refreshTokens(config) ?: t else t }
        val hasAuth = config.headers.keys.any { it.equals("Authorization", ignoreCase = true) }
        if (stored != null && !hasAuth) config.headers + ("Authorization" to "Bearer ${stored.accessToken}") else config.headers
    }

    private suspend fun refreshTokens(live: Live): Boolean = refreshTokens(live.config) != null

    /** A renewed sign-in for [config], stored; null when there is nothing to renew or the server refused. */
    private suspend fun refreshTokens(config: McpServerConfig): McpTokens? = refreshLock.withLock {
        val current = tokens.get(config.identity) ?: return@withLock null
        if (current.refreshToken == null) return@withLock null
        try {
            McpOAuth.refresh(current).also { tokens.put(config.identity, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("MCP ${config.name}: token refresh failed: ${e.message}")
            null
        }
    }

    private fun dropClient(live: Live) {
        val c = live.client ?: return
        live.client = null
        c.close()
        bumpTools()
    }

    private fun disconnect(live: Live) {
        live.job?.cancel()
        live.job = null
        live.client?.close()
        live.client = null
        live.tools = emptyList()
        stateMap.remove(live.config.name)
        bumpTools()
    }

    private val stateMap = java.util.concurrent.ConcurrentHashMap<String, McpServerState>()

    private fun stateOf(live: Live): McpServerState =
        stateMap[live.config.name] ?: McpServerState(live.config.name, McpServerState.Status.CONNECTING)

    private fun setState(live: Live, status: McpServerState.Status, error: String? = null) {
        val client = live.client
        stateMap[live.config.name] = McpServerState(
            name = live.config.name,
            status = status,
            error = error,
            tools = if (status == McpServerState.Status.CONNECTED) live.tools else emptyList(),
            signedIn = tokens.get(live.config.identity) != null,
            serverName = client?.serverName,
            instructions = client?.instructions,
        )
        publish()
    }

    private fun bumpTools() {
        _toolsVersion.update { it + 1 }
    }

    private fun publish() {
        val names = synchronized(this) { servers.values.toList() }
        _states.value = names.map { live ->
            (stateMap[live.config.name] ?: McpServerState(live.config.name, McpServerState.Status.CONNECTING))
                .copy(signedIn = tokens.get(live.config.identity) != null)
        }
    }

    companion object {
        internal fun describe(e: Throwable): String = when (e) {
            is java.net.UnknownHostException -> "Could not find the server (${e.message}). Check the URL and your connection."
            is java.net.ConnectException -> "Could not connect to the server: ${e.message}"
            is java.net.SocketTimeoutException -> "The server did not answer in time."
            is kotlinx.coroutines.TimeoutCancellationException -> "The server did not answer in time."
            is McpException -> "The server refused: ${e.message}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
