package dev.ide.core.agent

import dev.ide.agent.AgentTool
import dev.ide.agent.impl.AgentSkill
import dev.ide.agent.impl.AgentSkills
import dev.ide.agent.impl.SystemPrompt
import dev.ide.agent.mcp.client.McpManager
import dev.ide.agent.mcp.client.McpOAuth
import dev.ide.agent.mcp.client.McpServerConfig
import dev.ide.agent.mcp.client.McpServerState
import dev.ide.agent.mcp.client.McpTokenStore
import dev.ide.agent.mcp.client.McpTokens
import dev.ide.agent.mcp.client.McpTransportKind
import dev.ide.core.BackendContext
import dev.ide.core.auth.LoopbackPages
import dev.ide.core.auth.LoopbackRedirectListener
import dev.ide.core.sdk.JdkManager
import dev.ide.platform.log.Log
import dev.ide.ui.backend.UiAgentConnections
import dev.ide.ui.backend.UiAgentSkill
import dev.ide.ui.backend.UiMcpScope
import dev.ide.ui.backend.UiMcpServer
import dev.ide.ui.backend.UiMcpServerConfig
import dev.ide.ui.backend.UiMcpStatus
import dev.ide.ui.backend.UiMcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * The agent's connections: the MCP servers the user configured and the skills installed, for every project and
 * for the open one.
 *
 * Where things live:
 * - App-wide servers: `<shared root>/agent/mcp.json`. Skills: `<shared root>/agent/skills/<name>/SKILL.md`.
 * - A project's servers: `.codeassist/mcp.json`, plus the `.mcp.json` Claude Code and Cursor read (both use the
 *   same `mcpServers` format). Skills: `.codeassist/skills/`, plus `.claude/skills/`.
 *
 * A project's servers can come from a repository someone else wrote, and a local one runs a command, so a server
 * from a project file is not started until the user approves it here. Approval is tied to the server's URL or
 * command: if the file later points it somewhere else, it needs approving again. Servers the user adds through the
 * app are approved as they are saved.
 */
internal class AgentConnections(
    /** The app's shared data folder (servers, sign-ins and skills for every project live under `agent/`). */
    private val sharedRoot: () -> Path?,
    /** The open project's root, or null. */
    private val projectRootOf: () -> Path?,
    private val preference: (String) -> String?,
    private val setPreference: (String, String) -> Unit,
    private val scope: CoroutineScope,
    val stdioSupported: Boolean =
        !JdkManager.isAndroidRuntime(System.getProperty("java.vm.name"), System.getProperty("java.vendor")),
) {
    constructor(ctx: BackendContext, scope: CoroutineScope) : this(
        sharedRoot = { ctx.manager?.sharedRoot },
        projectRootOf = { ctx.servicesOrNull?.workspaceRoot },
        preference = { ctx.manager?.preference(it) },
        setPreference = { k, v -> ctx.manager?.setPreference(k, v) },
        scope = scope,
    )

    private val log = Log.logger("ide.agent.mcp")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val appDir: Path? get() = sharedRoot()?.resolve("agent")
    private val appConfig: Path? get() = appDir?.resolve("mcp.json")
    private val tokenFile: Path? get() = appDir?.resolve("mcp-auth.json")
    private val projectRoot: Path? get() = projectRootOf()
    private val projectConfig: Path? get() = projectRoot?.resolve(".codeassist")?.resolve("mcp.json")
    private val sharedProjectConfig: Path? get() = projectRoot?.resolve(".mcp.json")

    private val tokens = object : McpTokenStore {
        private var cache: MutableMap<String, McpTokens>? = null

        @Synchronized
        private fun all(): MutableMap<String, McpTokens> = cache ?: run {
            val file = tokenFile
            val loaded = runCatching {
                (json.parseToJsonElement(file!!.readText()) as JsonObject).mapNotNull { (k, v) ->
                    (v as? JsonObject)?.let(McpTokens::fromJson)?.let { k to it }
                }.toMap(HashMap())
            }.getOrDefault(HashMap())
            loaded.also { cache = it }
        }

        @Synchronized
        override fun get(identity: String): McpTokens? = all()[identity]

        @Synchronized
        override fun put(identity: String, tokens: McpTokens) {
            all()[identity] = tokens
            save()
        }

        @Synchronized
        override fun remove(identity: String) {
            if (all().remove(identity) != null) save()
        }

        private fun save() {
            val file = tokenFile ?: return
            runCatching {
                file.parent.createDirectories()
                file.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(all().mapValues { it.value.toJson() })))
            }.onFailure { log.warn("Could not save MCP sign-ins: ${it.message}") }
        }
    }

    private val manager = McpManager(scope, tokens, stdioSupported) { log.info(it) }

    /** The live server states, for a test to wait on. */
    internal val serverStates get() = manager.states

    init {
        // A local server is a child process; the OS does not end it when the app exits.
        if (stdioSupported) {
            runCatching { Runtime.getRuntime().addShutdownHook(Thread { manager.close() }) }
        }
    }

    /** One configured server, with where it was found. */
    private data class Entry(val config: McpServerConfig, val scope: UiMcpScope, val file: Path)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var skills: List<AgentSkill> = emptyList()
    @Volatile private var loadedStamp: String? = null
    @Volatile private var checkedAtMs = 0L

    private val _state = MutableStateFlow(UiAgentConnections(mcpSupported = true, stdioSupported = stdioSupported))
    val state: StateFlow<UiAgentConnections> = _state.asStateFlow()

    private val pendingSignIns = java.util.concurrent.ConcurrentHashMap<String, LoopbackRedirectListener>()
    private val signInErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        scope.launch { manager.states.collect { publish() } }
        scope.launch { reload(force = true) }
    }

    // --- what the agent gets ---

    /** The connected servers' tools, plus the `skill` tool when any skill is enabled. */
    fun tools(): List<AgentTool> {
        maybeReload()
        val enabledSkills = enabledSkills()
        return manager.tools() + if (enabledSkills.isNotEmpty()) listOf(skillTool) else emptyList()
    }

    private val skillTool = AgentSkills.tool { enabledSkills() }

    /** System-prompt sections: the skills list and the servers' own notes. */
    fun promptSections(): List<String> = listOfNotNull(
        AgentSkills.promptSection(enabledSkills()),
        SystemPrompt.mcpSection(manager.instructions()),
    )

    private fun enabledSkills(): List<AgentSkill> {
        val disabled = prefSet(SKILLS_DISABLED)
        return skills.filter { skillKey(it) !in disabled }
    }

    // --- loading ---

    /** Re-reads the config files and skill folders when the project changed or a file did; cheap to call often. */
    private fun maybeReload() {
        val now = System.currentTimeMillis()
        if (now - checkedAtMs < RECHECK_MS) return
        checkedAtMs = now
        if (stamp() != loadedStamp) reload(force = false)
    }

    /** What the loaded state depends on: the project, and the config files' and skill folders' modification times. */
    private fun stamp(): String = buildString {
        append(projectRoot)
        listOfNotNull(appConfig, projectConfig, sharedProjectConfig).forEach { append('|').append(mtime(it)) }
        skillRoots().forEach { (dir, _) ->
            append('|').append(mtime(dir))
            runCatching { Files.list(dir).use { s -> s.forEach { d -> append(',').append(mtime(d.resolve(AgentSkills.FILE))) } } }
        }
    }

    @Synchronized
    fun reload(force: Boolean) {
        val stamp = stamp()
        if (!force && stamp == loadedStamp) return
        loadedStamp = stamp
        checkedAtMs = System.currentTimeMillis()
        val list = ArrayList<Entry>()
        appConfig?.let { f -> readConfig(f).forEach { list += Entry(it, UiMcpScope.APP, f) } }
        // The app's own project file wins over the shared .mcp.json for the same name.
        val seen = HashSet<String>()
        listOfNotNull(projectConfig, sharedProjectConfig).forEach { f ->
            readConfig(f).forEach { c -> if (seen.add(c.name)) list += Entry(c, UiMcpScope.PROJECT, f) }
        }
        entries = list
        skills = AgentSkills.scan(skillRoots().map { (dir, scope) -> dir to scope.name.lowercase() })
        applyToManager()
    }

    private fun skillRoots(): List<Pair<Path, UiMcpScope>> = listOfNotNull(
        projectRoot?.resolve(".codeassist")?.resolve("skills")?.let { it to UiMcpScope.PROJECT },
        projectRoot?.resolve(".claude")?.resolve("skills")?.let { it to UiMcpScope.PROJECT },
        appDir?.resolve("skills")?.let { it to UiMcpScope.APP },
    )

    private fun readConfig(file: Path): List<McpServerConfig> =
        if (file.isRegularFile()) runCatching { McpServerConfig.parseFile(file.readText()) }.getOrDefault(emptyList()) else emptyList()

    /** The servers that should be live: enabled, approved, and not hidden by a project server of the same name. */
    private fun applyToManager() {
        val live = effective().filter { (entry, shadowed) -> !shadowed && isEnabled(entry) && isTrusted(entry) }.map { it.first.config }
        manager.update(live, projectRoot?.toFile())
        publish()
    }

    /** Every entry, with whether a project server of the same name hides it. */
    private fun effective(): List<Pair<Entry, Boolean>> {
        val projectNames = entries.filter { it.scope == UiMcpScope.PROJECT }.map { it.config.name }.toSet()
        return entries.map { it to (it.scope == UiMcpScope.APP && it.config.name in projectNames) }
    }

    private fun isEnabled(entry: Entry): Boolean = !entry.config.disabled && key(entry) !in prefSet(MCP_DISABLED)

    private fun isTrusted(entry: Entry): Boolean =
        entry.scope == UiMcpScope.APP || trustKey(entry) in prefSet(MCP_TRUSTED)

    private fun key(entry: Entry): String = "${entry.scope}|${if (entry.scope == UiMcpScope.PROJECT) projectRoot else ""}|${entry.config.name}"

    private fun trustKey(entry: Entry): String = "$projectRoot|${entry.config.name}|${entry.config.identity}"

    private fun skillKey(skill: AgentSkill): String = "${skill.source}|${skill.dir}"

    // --- the UI's view ---

    private fun publish() {
        val states = manager.states.value.associateBy { it.name }
        val servers = effective().map { (entry, shadowed) ->
            val enabled = isEnabled(entry)
            val trusted = isTrusted(entry)
            val s = states[entry.config.name]
            val status = when {
                shadowed || !enabled -> UiMcpStatus.DISABLED
                !trusted -> UiMcpStatus.UNTRUSTED
                s == null -> UiMcpStatus.CONNECTING
                else -> when (s.status) {
                    McpServerState.Status.DISABLED -> UiMcpStatus.DISABLED
                    McpServerState.Status.CONNECTING -> UiMcpStatus.CONNECTING
                    McpServerState.Status.CONNECTED -> UiMcpStatus.CONNECTED
                    McpServerState.Status.NEEDS_AUTH -> UiMcpStatus.NEEDS_AUTH
                    McpServerState.Status.FAILED -> UiMcpStatus.FAILED
                    McpServerState.Status.UNSUPPORTED -> UiMcpStatus.UNSUPPORTED
                }
            }
            UiMcpServer(
                config = entry.config.toUi(),
                scope = entry.scope,
                enabled = enabled,
                status = status,
                error = signInErrors[entry.config.name] ?: s?.error?.takeIf { status != UiMcpStatus.DISABLED && status != UiMcpStatus.UNTRUSTED },
                tools = if (status == UiMcpStatus.CONNECTED) s?.tools?.map { it.title ?: it.name }.orEmpty() else emptyList(),
                signedIn = tokens.get(entry.config.identity) != null,
                signInPending = pendingSignIns.containsKey(entry.config.name),
                shadowed = shadowed,
            )
        }
        val disabledSkills = prefSet(SKILLS_DISABLED)
        _state.value = UiAgentConnections(
            mcpSupported = true,
            stdioSupported = stdioSupported,
            servers = servers,
            skills = skills.map {
                UiAgentSkill(
                    it.name, it.description,
                    if (it.source == "project") UiMcpScope.PROJECT else UiMcpScope.APP,
                    it.dir.resolve(AgentSkills.FILE).toString(),
                    enabled = skillKey(it) !in disabledSkills,
                )
            },
            appSkillsDir = appDir?.resolve("skills")?.toString(),
            projectSkillsDir = projectRoot?.resolve(".codeassist")?.resolve("skills")?.toString(),
            appConfigFile = appConfig?.toString(),
            projectConfigFile = projectConfig?.toString(),
            projectOpen = projectRoot != null,
        )
    }

    // --- editing ---

    fun save(config: UiMcpServerConfig, scope: UiMcpScope, previousName: String?): String? {
        val name = config.name.trim()
        if (name.isEmpty()) return "Give the server a name."
        if (!NAME.matches(name)) return "Use letters, digits, '-' and '_' in the name."
        val parsed = config.toConfig(name) ?: return when (config.transport) {
            UiMcpTransport.STDIO -> "Enter the command to run."
            else -> "Enter the server's URL."
        }
        if (parsed.url != null && !(parsed.url!!.startsWith("https://") || parsed.url!!.startsWith("http://"))) {
            return "The URL must start with https:// (or http:// for a server on this machine)."
        }
        if (parsed.transport == McpTransportKind.STDIO && !stdioSupported) return "Local (command) servers run on desktop only."
        val file = when (scope) {
            UiMcpScope.APP -> appConfig ?: return "No app data folder to save to."
            UiMcpScope.PROJECT -> {
                // Edit a server where it was found; new ones go in the app's own project file.
                entries.firstOrNull { it.scope == UiMcpScope.PROJECT && it.config.name == (previousName ?: name) }?.file
                    ?: projectConfig ?: return "Open a project to add a server to it."
            }
        }
        val current = readConfig(file).toMutableList()
        if (current.any { it.name == name && it.name != previousName }) return "There is already a server named \"$name\"."
        val index = current.indexOfFirst { it.name == (previousName ?: name) }
        if (index >= 0) current[index] = parsed else current += parsed
        val error = write(file, current)
        if (error != null) return error
        if (scope == UiMcpScope.PROJECT) {
            // Added by the user here, so approved as it stands.
            prefAdd(MCP_TRUSTED, "$projectRoot|${parsed.name}|${parsed.identity}")
        }
        reload(force = true)
        return null
    }

    fun remove(name: String, scope: UiMcpScope) {
        val entry = entries.firstOrNull { it.scope == scope && it.config.name == name } ?: return
        write(entry.file, readConfig(entry.file).filterNot { it.name == name })
        tokens.remove(entry.config.identity)
        reload(force = true)
    }

    fun setEnabled(name: String, scope: UiMcpScope, enabled: Boolean) {
        val entry = entries.firstOrNull { it.scope == scope && it.config.name == name } ?: return
        if (enabled) {
            prefRemove(MCP_DISABLED, key(entry))
            // A `disabled: true` in the file is the user's too; turning the server on clears it.
            if (entry.config.disabled) write(entry.file, readConfig(entry.file).map { if (it.name == name) it.copy(disabled = false) else it })
        } else {
            prefAdd(MCP_DISABLED, key(entry))
        }
        reload(force = true)
    }

    fun trust(name: String) {
        val entry = entries.firstOrNull { it.scope == UiMcpScope.PROJECT && it.config.name == name } ?: return
        prefAdd(MCP_TRUSTED, trustKey(entry))
        applyToManager()
    }

    fun reconnect(name: String) {
        signInErrors.remove(name)
        manager.reconnect(name)
    }

    fun refresh() {
        reload(force = true)
    }

    fun setSkillEnabled(name: String, enabled: Boolean) {
        val skill = skills.firstOrNull { it.name == name } ?: return
        if (enabled) prefRemove(SKILLS_DISABLED, skillKey(skill)) else prefAdd(SKILLS_DISABLED, skillKey(skill))
        publish()
    }

    fun createSkill(name: String, scope: UiMcpScope): String? {
        val clean = name.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').takeIf { it.isNotEmpty() } ?: return null
        val root = when (scope) {
            UiMcpScope.APP -> appDir?.resolve("skills")
            UiMcpScope.PROJECT -> projectRoot?.resolve(".codeassist")?.resolve("skills")
        } ?: return null
        val file = root.resolve(clean).resolve(AgentSkills.FILE)
        if (!file.exists()) {
            runCatching {
                file.parent.createDirectories()
                file.writeText(
                    """
                    ---
                    name: $clean
                    description: Say what this skill does and when the agent should use it. The agent reads only this line until it decides to load the skill.
                    ---

                    # $clean

                    Write the instructions the agent should follow for this kind of task: the steps, the conventions, and
                    what a good result looks like. Put longer reference material in other files in this folder and
                    mention them here; the agent can read them when it needs them.
                    """.trimIndent() + "\n",
                )
            }.onFailure { return null }
        }
        reload(force = true)
        return file.toString()
    }

    // --- sign-in ---

    suspend fun beginSignIn(name: String): String? {
        cancelSignIn(name)
        signInErrors.remove(name)
        var listener: LoopbackRedirectListener? = null
        var attempt: McpOAuth.Attempt? = null
        return try {
            val l = LoopbackRedirectListener(0, CALLBACK_PATH, SIGN_IN_TIMEOUT, PAGES) { url ->
                val a = attempt ?: return@LoopbackRedirectListener
                scope.launch { complete(name, a, url) }
            }
            listener = l
            val started = manager.beginSignIn(name, "http://localhost:${l.port}$CALLBACK_PATH")
            attempt = started
            pendingSignIns[name] = l
            l.start()
            // The listener gives up after its timeout; the row stops saying it is waiting then too.
            scope.launch {
                kotlinx.coroutines.delay(SIGN_IN_TIMEOUT)
                if (pendingSignIns.remove(name, l)) {
                    l.close()
                    signInErrors[name] = "The sign-in timed out. Try again."
                    publish()
                }
            }
            publish()
            started.authorizeUrl
        } catch (e: Exception) {
            listener?.close()
            pendingSignIns.remove(name)
            signInErrors[name] = e.message ?: "The sign-in could not start."
            log.warn("MCP sign-in for $name could not start: ${e.message}")
            publish()
            null
        }
    }

    private suspend fun complete(name: String, attempt: McpOAuth.Attempt, url: String) {
        pendingSignIns.remove(name)?.close()
        val query = OpenRouterSignIn.parseQuery(URI(url).rawQuery)
        val code = query["code"]
        try {
            when {
                code.isNullOrBlank() -> throw IllegalStateException(query["error_description"] ?: query["error"] ?: "The server did not approve the sign-in.")
                query["state"] != attempt.state -> throw IllegalStateException("The sign-in response did not match. Try again.")
            }
            manager.completeSignIn(name, attempt, code!!)
            signInErrors.remove(name)
        } catch (e: Exception) {
            signInErrors[name] = e.message ?: "The sign-in did not finish."
        }
        publish()
    }

    fun cancelSignIn(name: String? = null) {
        val names = if (name != null) listOf(name) else pendingSignIns.keys.toList()
        names.forEach { pendingSignIns.remove(it)?.close() }
        publish()
    }

    fun signOut(name: String) {
        manager.signOut(name)
        publish()
    }

    // --- persistence ---

    private fun write(file: Path, servers: List<McpServerConfig>): String? = runCatching {
        file.parent.createDirectories()
        // Keep anything else the file holds (other tools' settings) and replace only the servers.
        val existing = runCatching { json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val serversKey = if (existing?.containsKey("servers") == true && !existing.containsKey("mcpServers")) "servers" else "mcpServers"
        val merged = JsonObject(
            (existing ?: JsonObject(emptyMap())) +
                (serversKey to JsonObject(servers.associate { it.name to McpServerConfig.toEntry(it) })),
        )
        file.writeText(json.encodeToString(JsonObject.serializer(), merged) + "\n")
        null
    }.getOrElse { "Could not save ${file.fileName}: ${it.message}" }

    private fun prefSet(key: String): Set<String> = runCatching {
        (json.parseToJsonElement(preference("settings.ai.$key").orEmpty()) as JsonArray)
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    }.getOrDefault(emptySet())

    private fun prefWrite(key: String, values: Set<String>) {
        setPreference("settings.ai.$key", JsonArray(values.map(::JsonPrimitive)).toString())
    }

    private fun prefAdd(key: String, value: String) = prefWrite(key, prefSet(key) + value)
    private fun prefRemove(key: String, value: String) = prefWrite(key, prefSet(key) - value)

    private fun mtime(p: Path): Long = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)

    companion object {
        private const val MCP_DISABLED = "mcpDisabled"
        private const val MCP_TRUSTED = "mcpTrusted"
        private const val SKILLS_DISABLED = "skillsDisabled"
        private const val RECHECK_MS = 3_000L
        private const val CALLBACK_PATH = "/mcp-callback"
        private val SIGN_IN_TIMEOUT = 10.minutes
        private val NAME = Regex("[A-Za-z0-9_-]{1,40}")

        private val PAGES = LoopbackPages(
            relay = LoopbackPages.SIGN_IN.relay,
            done = LoopbackPages.page(
                "Connected",
                "CodeAssist can now use this server. You can close this tab.",
                "<script>if(/Android/i.test(navigator.userAgent)){var a=document.createElement('a');" +
                    "a.className='btn';a.href='${OpenRouterSignIn.RETURN_LINK}';a.textContent='Back to CodeAssist';" +
                    "document.querySelector('main').appendChild(a)}</script>",
            ),
            failed = LoopbackPages.page("The sign-in did not finish", "Go back to CodeAssist to see why, and try again."),
        )

        private fun McpServerConfig.toUi() = UiMcpServerConfig(
            name = name,
            transport = when (transport) {
                McpTransportKind.HTTP -> UiMcpTransport.HTTP
                McpTransportKind.SSE -> UiMcpTransport.SSE
                McpTransportKind.STDIO -> UiMcpTransport.STDIO
            },
            url = url.orEmpty(),
            headers = headers,
            command = command.orEmpty(),
            args = args,
            env = env,
        )

        private fun UiMcpServerConfig.toConfig(name: String): McpServerConfig? = when (transport) {
            UiMcpTransport.STDIO -> command.trim().takeIf { it.isNotEmpty() }?.let {
                McpServerConfig(name, McpTransportKind.STDIO, command = it, args = args.filter { a -> a.isNotEmpty() }, env = env)
            }
            else -> url.trim().takeIf { it.isNotEmpty() }?.let {
                McpServerConfig(
                    name,
                    if (transport == UiMcpTransport.SSE) McpTransportKind.SSE else McpTransportKind.HTTP,
                    url = it,
                    headers = headers.filterKeys { k -> k.isNotBlank() },
                )
            }
        }
    }
}
