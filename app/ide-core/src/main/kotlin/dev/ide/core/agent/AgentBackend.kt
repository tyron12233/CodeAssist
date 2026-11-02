package dev.ide.core.agent

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentTool
import dev.ide.agent.AllowAllGate
import dev.ide.agent.ContentPart
import dev.ide.agent.FileChange
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.PermissionMode
import dev.ide.agent.ProviderConfig
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.WriteRequest
import dev.ide.agent.impl.AgentLoop
import dev.ide.agent.impl.AgentProviders
import dev.ide.agent.impl.ApiKeyPool
import dev.ide.agent.impl.CheckpointWorkspace
import dev.ide.agent.impl.CodeAssistFreeProvider
import dev.ide.agent.impl.LlmErrorKind
import dev.ide.agent.impl.RequestPacer
import dev.ide.agent.impl.HistoryCompactor
import dev.ide.agent.impl.KeyFailoverClient
import dev.ide.agent.impl.OkHttpLlmTransport
import dev.ide.agent.impl.OpenRouterUsage
import dev.ide.agent.impl.SystemPrompt
import dev.ide.agent.impl.builtinTools
import dev.ide.agent.mcp.CodeAssistMcpServer
import dev.ide.agent.mcp.FtpServer
import dev.ide.agent.mcp.HttpMcpServer
import dev.ide.agent.toolSchema
import dev.ide.core.AI_CREDITS_SERVICE
import dev.ide.core.BackendContext
import dev.ide.core.IdeServices
import dev.ide.platform.log.Log
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentAttachmentKind
import dev.ide.ui.backend.UiAgentSegment
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentCommand
import dev.ide.ui.backend.UiAgentFileChange
import dev.ide.ui.backend.UiAgentMention
import dev.ide.ui.backend.UiAgentSessionSummary
import dev.ide.ui.backend.UiAgentTodo
import dev.ide.ui.backend.UiAgentTodoStatus
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentUsage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiOpenRouterSignIn
import dev.ide.ui.backend.UiAgentUsageLimits
import dev.ide.ui.backend.UiFreeAiState
import dev.ide.ui.backend.UiUsageMeter
import dev.ide.ui.backend.UiUsageMeterKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.readText

/**
 * [AgentService] over the agent engine (agent-impl). Owns the chat transcript state, the per-session agent
 * loop, and the write-permission gate; bring-your-own-key provider configuration is read from the "AI"
 * settings page's preferences (persisted plaintext, matching the keystore-password posture). See
 * docs/agentic-coding.md.
 */
internal class AgentBackend(private val ctx: BackendContext) : AgentService {

    private val transport = OkHttpLlmTransport()
    /**
     * CodeAssist Free AI, when the launcher wired it (a Supabase config and sign-in exist). Resolved like the store
     * ports; absent on desktop builds without Supabase and in tests, where the provider list is BYO-key only.
     */
    private val freeAiCredits: dev.ide.store.AiCreditsService =
        ctx.manager?.applicationContainer?.getServiceOrNull(AI_CREDITS_SERVICE)
            ?.takeIf { it.available() && it.proxyBaseUrl() != null }
            ?: dev.ide.store.AiCreditsService.Unsupported

    /**
     * The built-in providers, with the free tier FIRST when this build has it: with no provider chosen yet the
     * first entry is the default, so someone who has never pasted a key starts on the one option that needs none.
     */
    private val registry: dev.ide.agent.LlmProviderRegistry = run {
        val free = freeAiCredits.proxyBaseUrl()?.let { base ->
            CodeAssistFreeProvider(transport, listOfNotNull(base, freeAiCredits.proxyFallbackUrl())) { freeAiCredits.accessToken() }
        }
        dev.ide.agent.SimpleLlmProviderRegistry(listOfNotNull(free) + AgentProviders.defaults(transport))
    }

    init {
        adoptFreeAiAsDefault()
    }

    /**
     * Once per install, move a user whose selected provider cannot work (no key, or a gateway with no URL) onto
     * CodeAssist Free AI. A user with a working key keeps their provider; a user who never picked one already
     * lands on the free tier, because it is first in [registry]. Recorded as done whatever the outcome, so a later
     * deliberate choice of a keyless provider (to paste a key into it) is never undone.
     */
    private fun adoptFreeAiAsDefault() {
        if (registry.provider(FREE_AI) == null) return
        val manager = ctx.manager ?: return
        if (pref(FREE_AI_DEFAULT_ADOPTED) == "true") return
        manager.setPreference("settings.$AI_PAGE.$FREE_AI_DEFAULT_ADOPTED", "true")
        val selected = pref("provider") ?: return
        if (selected == FREE_AI) return
        val usable = if (selected == GATEWAY) {
            !pref("gatewayBaseUrl").isNullOrBlank()
        } else {
            !pref(keyField(selected)).isNullOrBlank() || backupKeys(selected).isNotEmpty()
        }
        if (!usable) manager.setPreference("settings.$AI_PAGE.provider", FREE_AI)
    }
    private val workspace = IdeAgentWorkspace(ctx)

    /** The agent's own writes go through this, so each turn's changes can be shown as diffs and reverted. */
    private val checkpoints = CheckpointWorkspace(workspace)

    private val sessionStore = AgentSessionStore { ctx.servicesOrNull?.workspaceRoot }

    /** The `ftp_server` tool: start, stop, or query the local FTP asset server. Advertised on the MCP
     *  server and to the in-app chat agent (which both share [tools]), so the feature is controllable from
     *  a client as well as the More-menu toggle. Non-mutating: no project file changes. */
    private val ftpControlTool = object : AgentTool {
        override val spec = ToolSpec(
            name = "ftp_server",
            description = "Start, stop, or query the local FTP asset server (anonymous, bound to " +
                "127.0.0.1:${CodeAssistMcpServer.DEFAULT_FTP_PORT}). When running, files uploaded over FTP " +
                "land in the open project's assets/ folder. To reach it from a PC, forward the control port " +
                "and the passive port above it: \"adb forward tcp:" + CodeAssistMcpServer.DEFAULT_FTP_PORT +
                " tcp:" + CodeAssistMcpServer.DEFAULT_FTP_PORT + "\" and \"adb forward tcp:" +
                (CodeAssistMcpServer.DEFAULT_FTP_PORT + 1) + " tcp:" + (CodeAssistMcpServer.DEFAULT_FTP_PORT + 1) +
                "\". Anyone else on the device can read and write those files too. " +
                "action=status only reports the current state.",
            parameters = toolSchema {
                string("action", "start, stop, or status", enum = listOf("start", "stop", "status"))
            },
        )
        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            when (args.string("action")) {
                "start" -> setFtpServerEnabled(true)
                "stop" -> setFtpServerEnabled(false)
            }
            return if (ftpServer != null) {
                ToolExecutionResult.ok(
                    "FTP asset server is RUNNING on 127.0.0.1:${CodeAssistMcpServer.DEFAULT_FTP_PORT} " +
                        "(uploads land in <project>/assets/).",
                )
            } else {
                ToolExecutionResult.ok("FTP asset server is stopped.")
            }
        }
    }

    // --- ask_user: the agent's multiple-choice questions, answered in the chat ---

    private val _questionRequest = MutableStateFlow<dev.ide.ui.backend.UiAgentQuestionRequest?>(null)
    override val questionRequest: StateFlow<dev.ide.ui.backend.UiAgentQuestionRequest?> get() = _questionRequest.asStateFlow()

    @Volatile
    private var pendingQuestion: CompletableDeferred<List<List<String>>?>? = null
    private val questionIds = AtomicInteger()

    /** Waits like the permission gate: Stop cancels the turn, and the `finally` takes the card down. */
    private val askUser = dev.ide.agent.impl.askUserTool { questions ->
        val deferred = CompletableDeferred<List<List<String>>?>()
        pendingQuestion = deferred
        _questionRequest.value = dev.ide.ui.backend.UiAgentQuestionRequest(
            questionIds.incrementAndGet(),
            questions.map { q ->
                dev.ide.ui.backend.UiAgentQuestion(
                    q.question, q.header,
                    q.options.map { dev.ide.ui.backend.UiAgentQuestionOption(it.label, it.description) },
                    q.multiSelect,
                )
            },
        )
        try {
            deferred.await()
        } finally {
            _questionRequest.value = null
            pendingQuestion = null
        }
    }

    override fun answerQuestion(id: Int, answers: List<List<String>>?) {
        if (_questionRequest.value?.id == id) pendingQuestion?.complete(answers)
    }

    /** The MCP server's tools write straight to the workspace: an external client's edits are not a chat turn,
     *  and recording them into one would let that turn's Undo revert someone else's work. */
    private val mcpTools = SimpleToolRegistry(builtinTools(workspace) + ftpControlTool)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Log.logger("ide.agent")

    /** The MCP servers and skills the user connected (see [AgentConnections]). */
    private val agentConnections = AgentConnections(ctx, scope)

    // ask_user is the chat's alone: an MCP client has no card to answer it with.
    private val builtinChatTools: List<AgentTool> = builtinTools(checkpoints) + ftpControlTool + askUser

    /**
     * The chat's tools: the built-ins, then whatever the connected MCP servers and installed skills add. Read on
     * every request, so a server that connects mid-conversation is usable from the next step.
     */
    /**
     * A new-app conversation that has not created its project yet ([startNewApp]). While set, the agent sees only
     * [newAppTools] (and ask_user), whatever project happens to be open behind the AI tab, and nothing is saved.
     */
    @Volatile
    private var creatingApp = false

    private val newAppTools: List<AgentTool> = NewAppTools.tools({ (ctx as? dev.ide.ui.backend.IdeBackend)?.projects }) {
        creatingApp = false
        _chatState.update { it.copy(creatingApp = false) }
        resolveProjectLibraries()
    } + askUser

    /**
     * Resolve the open project's declared libraries (a new project's template dependencies), wait for it, and say
     * how it went. The editor starts this when it opens a project; a project the agent creates from the AI tab is
     * never shown in the editor, so without this its AndroidX/Compose libraries never reach the classpath.
     */
    private suspend fun resolveProjectLibraries(): String? {
        val deps = ctx.servicesOrNull?.dependencies ?: return null
        deps.startPendingDependencyResolution()
        // The resolve runs in the background; wait for it to start, then to finish.
        val started = kotlinx.coroutines.withTimeoutOrNull(LIBRARIES_START_MS) {
            deps.depsState.first { it.resolving }
        } != null
        val done = if (started) {
            kotlinx.coroutines.withTimeoutOrNull(LIBRARIES_RESOLVE_MS) { deps.depsState.first { !it.resolving } }
        } else {
            deps.depsState.value
        } ?: return "The project's libraries are still downloading. Builds fail until they finish; check again before building."
        if (done.unresolved.isEmpty()) return "The project's libraries are resolved and on the classpath."
        return "These libraries could not be resolved, so code using them will not compile:\n" +
            done.unresolved.joinToString("\n") { "- ${it.coordinate} (${it.module}): ${it.reason}" } +
            "\nTell the user; they may need a network connection."
    }

    /** Conversations across every project, for the AI home tab. */
    private val recentChatIndex = AgentRecentChats { ctx.manager?.sharedRoot?.resolve("agent")?.resolve("recent-chats.json") }

    private val tools = object : dev.ide.agent.AgentToolRegistry {
        override val tools: List<AgentTool>
            get() {
                if (creatingApp) return newAppTools
                val extra = agentConnections.tools()
                if (extra.isEmpty()) return builtinChatTools
                val builtinNames = builtinChatTools.mapTo(HashSet()) { it.spec.name }
                return builtinChatTools + extra.filter { it.spec.name !in builtinNames }
            }

        override fun find(name: String): AgentTool? =
            if (creatingApp) newAppTools.firstOrNull { it.spec.name == name }
            else builtinChatTools.firstOrNull { it.spec.name == name } ?: agentConnections.tools().firstOrNull { it.spec.name == name }
    }

    /** The in-app MCP-over-HTTP server, started when the "MCP server" AI setting is on. The server is
     *  opt-in: enabling it is itself the permission to edit the open project, so its tools run under
     *  [AllowAllGate] (a remote client cannot answer the interactive UI prompts). */
    @Volatile
    private var mcpServer: HttpMcpServer? = null

    /** The local FTP asset server, started when the AI settings "FTP server" toggle (or the `ftp_server`
     *  tool) is on. Anonymous and bound to 127.0.0.1 only; uploads land in `<project>/assets`. */
    @Volatile
    private var ftpServer: FtpServer? = null

    init {
        live += WeakReference(this)
        if (prefBool("mcpServer", default = false)) {
            mcpServer = startMcpServer()
        }
        if (prefBool(FTP_PREF, default = false)) {
            ftpServer = startFtpServer()
        }
    }

    private val _chatState = MutableStateFlow(UiAgentChatState())
    override val chatState: StateFlow<UiAgentChatState> = _chatState.asStateFlow()

    private val _permissionRequest = MutableStateFlow<UiAgentPermissionRequest?>(null)
    override val permissionRequest: StateFlow<UiAgentPermissionRequest?> = _permissionRequest.asStateFlow()

    private val _models = MutableStateFlow<List<UiAgentModel>>(emptyList())
    override val models: StateFlow<List<UiAgentModel>> = _models.asStateFlow()

    private val permIds = AtomicInteger(0)
    private val msgIds = AtomicLong(0)

    @Volatile
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    @Volatile
    private var sessionAllowAll = false

    private var job: Job? = null
    private var loop: AgentLoop? = null
    private var loopSignature: String? = null

    /** The provider/model half of [loopSignature]; a change to it means replayed reasoning must be dropped. */
    private var loopClientSignature: String? = null

    // --- configuration (read from the AI settings page's prefs) ---

    private fun pref(key: String): String? =
        ctx.manager?.preference("settings.$AI_PAGE.$key")?.takeIf { it.isNotBlank() }

    private fun prefInt(key: String): Int? = pref(key)?.toIntOrNull()

    private fun prefBool(key: String, default: Boolean): Boolean = pref(key)?.toBooleanStrictOrNull() ?: default

    /** Binds the MCP-over-HTTP server to the engine workspace; null when startup fails (e.g. port taken). */
    private fun startMcpServer(): HttpMcpServer? = try {
        CodeAssistMcpServer.startHttpServer(
            workspace = workspace,
            port = CodeAssistMcpServer.DEFAULT_HTTP_PORT,
            tools = mcpTools,
            gate = AllowAllGate,
        )
    } catch (e: Exception) {
        // WARN, not ERROR: an ERROR carrying a throwable is what raises the app's critical-error dialog, and
        // this failure is both expected and harmless — the usual cause is the port already being held (the
        // app's own previous process, or another app), and the caller simply leaves the server off. Telling
        // the user about it with a modal on startup, which is where this runs, is the wrong trade.
        log.warn("MCP server not started on port ${CodeAssistMcpServer.DEFAULT_HTTP_PORT}: ${e.message}")
        null
    }

    /** Binds the FTP asset server to `<project>/assets` (created on start); null when no project is open or
     *  startup fails (e.g. port taken). */
    private fun startFtpServer(): FtpServer? = try {
        val assets = ctx.servicesOrNull?.workspaceRoot?.resolve("assets")
            ?: return null
        Files.createDirectories(assets)
        CodeAssistMcpServer.startFtpServer(assets, CodeAssistMcpServer.DEFAULT_FTP_PORT)
    } catch (e: Exception) {
        // WARN for the same reason as the MCP server above: an optional server that cannot take its port is
        // a disabled feature, not an error the user has to dismiss.
        log.warn("FTP server not started on port ${CodeAssistMcpServer.DEFAULT_FTP_PORT}: ${e.message}")
        null
    }

    override fun ftpServerSupported(): Boolean = true

    override fun ftpServerEnabled(): Boolean = ftpServer != null

    override fun setFtpServerEnabled(enabled: Boolean) {
        ctx.manager?.setPreference("settings.$AI_PAGE.$FTP_PREF", enabled.toString())
        if (enabled) {
            if (ftpServer == null) ftpServer = startFtpServer()
        } else {
            ftpServer?.close()
            ftpServer = null
        }
    }

    private fun modePref(): PermissionMode =
        runCatching { PermissionMode.valueOf(ctx.manager?.preference(MODE_PREF).orEmpty()) }
            .getOrDefault(PermissionMode.ASK_EACH)

    private data class ResolvedConfig(
        /** What the user picked, possibly the [GATEWAY] pseudo-provider. */
        val selectedId: String,
        /** The registry provider used to build the client (gateway maps to the OpenAI client). */
        val clientProviderId: String,
        val apiKey: String?,
        val model: String,
        val baseUrl: String?,
        /** An optional additional CA certificate (PEM) to trust for a custom endpoint behind a private/regional
         *  CA (e.g. GigaChat's Russian Trusted Root CA). Only the custom [GATEWAY] endpoint uses it. */
        val caCertificatePem: String? = null,
        /** Keys tried in order after [apiKey] when it hits a limit or is rejected. */
        val backupKeys: List<String> = emptyList(),
    ) {
        /** Every key, the main one first. */
        val keys: List<String> get() = (listOfNotNull(apiKey) + backupKeys).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** A custom gateway is often a local server (Ollama, LM Studio) that takes no key; its URL is what it needs. */
        val ready: Boolean
            get() = if (selectedId == GATEWAY) !baseUrl.isNullOrBlank() else keys.isNotEmpty()
    }

    private fun resolveConfig(): ResolvedConfig {
        val selected = pref("provider") ?: registry.providers.firstOrNull()?.id ?: "anthropic"
        if (selected == GATEWAY) {
            return ResolvedConfig(
                selectedId = GATEWAY,
                clientProviderId = "openai",
                apiKey = pref("gatewayKey"),
                model = pref("gatewayModel").orEmpty(),
                baseUrl = pref("gatewayBaseUrl"),
                caCertificatePem = pref("gatewayCaCert"),
                backupKeys = backupKeys(GATEWAY),
            )
        }
        val provider = registry.provider(selected)
        return ResolvedConfig(
            selectedId = selected,
            clientProviderId = selected,
            // The free tier has no key: the provider fetches the session's token per request. A placeholder stands
            // in for it while signed in, which is what makes the config "ready" and the key pool non-empty.
            apiKey = if (selected == FREE_AI) FREE_AI_SESSION_KEY.takeIf { freeAiCredits.signedIn() } else pref(keyField(selected)),
            model = chosenModel(selected) ?: preferredModels[selected] ?: provider?.defaultModel.orEmpty(),
            baseUrl = null,
            backupKeys = backupKeys(selected),
        )
    }

    /** [providerId]'s backup keys, stored one per line beside its main key. */
    private fun backupKeys(providerId: String): List<String> =
        pref("${keyField(providerId)}.backups")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * The model the user picked for [providerId], or null to let the provider choose. Picks are stored per
     * provider: one shared `model` preference meant switching provider kept the old provider's model id, and
     * the first request then failed against a model the new provider does not have. The legacy shared value is
     * still honoured when it plausibly belongs to this provider.
     */
    private fun chosenModel(providerId: String): String? {
        pref("model.$providerId")?.let { return it }
        val legacy = pref("model") ?: return null
        return legacy.takeIf { modelBelongsTo(it, providerId) }
    }

    private fun modelBelongsTo(model: String, providerId: String): Boolean = when (providerId) {
        "anthropic" -> model.startsWith("claude")
        "gemini" -> model.startsWith("gemini") || model.startsWith("gemma") || model.startsWith("models/")
        "openai" -> !model.startsWith("claude") && !model.startsWith("gemini") && '/' !in model
        "openrouter" -> '/' in model
        FREE_AI -> model.startsWith("claude-")
        else -> true
    }

    /** Per provider, the model [LlmProvider.preferredModel] chose from the account's live list. */
    private val preferredModels = ConcurrentHashMap<String, String>()

    /** Models that failed this session for want of quota, so a suggestion never points back at one. */
    private val exhaustedModels = ConcurrentHashMap.newKeySet<String>()

    /**
     * Models the provider answered "not found" for this session. Gemini keeps listing models it has closed to
     * new projects (2.5 Pro), so the live list alone would keep offering a model that can never answer.
     */
    private val missingModels = ConcurrentHashMap.newKeySet<String>()

    /**
     * Whether [models] came from the custom gateway itself. When its `/v1/models` cannot be read the list falls
     * back to the OpenAI defaults, which a local server does not serve, so those are never offered as a fix.
     */
    @Volatile private var gatewayModelsLive = false

    /** One pacer per key + model, which is the scope a provider counts its per-minute limits against. */
    private val pacers = ConcurrentHashMap<String, RequestPacer>()

    private fun pacerFor(cfg: ResolvedConfig, model: String): RequestPacer {
        val rpm = prefInt("rpm")?.takeIf { it > 0 }
        val tpm = prefInt("tpmK")?.takeIf { it > 0 }?.let { it * 1_000L }
        val key = "${cfg.selectedId}|${cfg.keys.hashCode()}|$model|$rpm|$tpm"
        return pacers.getOrPut(key) { RequestPacer(rpm, tpm) }
    }

    /**
     * One key pool per provider + model + key list, kept for the session so a key that hit its limit stays out of
     * rotation across loop rebuilds. Per model because providers count most limits per model; editing the keys
     * gives a fresh pool, which is also how a rejected key gets another chance.
     */
    private val keyPools = ConcurrentHashMap<String, ApiKeyPool>()

    private fun keyPoolFor(cfg: ResolvedConfig, model: String): ApiKeyPool =
        keyPools.getOrPut("${cfg.selectedId}|$model|${cfg.keys.hashCode()}") { ApiKeyPool(cfg.keys) }

    private fun keyField(providerId: String): String = when (providerId) {
        "openai" -> "openaiKey"
        "gemini" -> "geminiKey"
        "openrouter" -> "openrouterKey"
        GATEWAY -> "gatewayKey"
        else -> "anthropicKey"
    }

    /**
     * Forces the loop to be rebuilt on the next send. The loop itself is kept so the rebuild carries its history
     * over: nulling it here is what used to make picking a model in the chat silently drop the conversation.
     */
    private fun resetLoop() {
        loopSignature = null
    }

    override fun config(): UiAgentConfig {
        val cfg = resolveConfig()
        val builtins = registry.providers.map { p ->
            UiAgentProvider(
                p.id, p.displayName,
                p.models.map { UiAgentModel(it.id, it.displayName) },
                p.defaultModel,
                apiKey = if (p.id == FREE_AI) FREE_AI_SESSION_KEY.takeIf { freeAiCredits.signedIn() }.orEmpty()
                else pref(keyField(p.id)).orEmpty(),
                backupKeys = if (p.id == FREE_AI) emptyList() else backupKeys(p.id),
            )
        }
        // A synthetic "Custom gateway" entry (OpenAI-compatible endpoint); its client is the OpenAI provider.
        val gateway = UiAgentProvider(
            GATEWAY, "Custom gateway", emptyList(), "",
            apiKey = pref("gatewayKey").orEmpty(),
            backupKeys = backupKeys(GATEWAY),
        )
        val configured = cfg.ready
        return UiAgentConfig(
            providers = builtins + gateway,
            selectedProvider = cfg.selectedId,
            model = cfg.model,
            configured = configured,
            mode = modePref().toUi(),
            gatewayBaseUrl = pref("gatewayBaseUrl").orEmpty(),
            gatewayModel = pref("gatewayModel").orEmpty(),
            gatewayCaCert = pref("gatewayCaCert").orEmpty(),
        )
    }

    override fun setPermissionMode(mode: UiAgentPermissionMode) {
        ctx.manager?.setPreference(MODE_PREF, mode.toDomain().name)
    }

    override fun setModel(model: String) {
        val selected = selectedProviderId()
        val field = if (selected == GATEWAY) "gatewayModel" else "model.$selected"
        ctx.manager?.setPreference("settings.$AI_PAGE.$field", model)
        if (model.isNotBlank()) {
            val recents = (listOf(model) + recentModels().filter { it != model }).take(MAX_RECENT_MODELS)
            ctx.manager?.setPreference("settings.$AI_PAGE.recentModels.$selected", recents.joinToString("\n"))
        }
        resetLoop()
    }

    private fun selectedProviderId(): String = pref("provider") ?: registry.providers.firstOrNull()?.id ?: "anthropic"

    private fun prefList(key: String): List<String> =
        pref(key).orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    override fun recentModels(): List<String> = prefList("recentModels.${selectedProviderId()}")

    override fun favoriteModels(): Set<String> = prefList("favoriteModels.${selectedProviderId()}").toSet()

    override fun setModelFavorite(model: String, favorite: Boolean) {
        val current = favoriteModels()
        val next = if (favorite) current + model else current - model
        ctx.manager?.setPreference("settings.$AI_PAGE.favoriteModels.${selectedProviderId()}", next.sorted().joinToString("\n"))
    }

    override fun selectProvider(id: String) {
        ctx.manager?.setPreference("settings.$AI_PAGE.provider", id)
        resetLoop()
    }

    override fun setProviderKey(providerId: String, key: String) {
        ctx.manager?.setPreference("settings.$AI_PAGE.${keyField(providerId)}", key)
        resetLoop()
    }

    override fun setProviderBackupKeys(providerId: String, keys: List<String>) {
        val joined = keys.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        ctx.manager?.setPreference("settings.$AI_PAGE.${keyField(providerId)}.backups", joined)
        resetLoop()
    }

    override fun setGateway(baseUrl: String, model: String, caCert: String) {
        ctx.manager?.setPreference("settings.$AI_PAGE.gatewayBaseUrl", baseUrl)
        ctx.manager?.setPreference("settings.$AI_PAGE.gatewayModel", model)
        ctx.manager?.setPreference("settings.$AI_PAGE.gatewayCaCert", caCert)
        resetLoop()
    }

    private val openRouter = OpenRouterSignIn(transport, scope) { key, freeModel ->
        setProviderKey(OPENROUTER, key)
        selectProvider(OPENROUTER)
        if (freeModel != null) setModel(freeModel)
        refreshModels()
        refreshUsageLimits()
    }

    override fun openRouterSignInSupported(): Boolean = true

    override val openRouterSignIn: StateFlow<UiOpenRouterSignIn> get() = openRouter.state

    override fun beginOpenRouterSignIn(): String? = openRouter.begin()

    override fun cancelOpenRouterSignIn() = openRouter.cancel()

    // Signed-in is read from the stored session (no network), so the chat does not flash a sign-in prompt at a
    // signed-in user before the first wallet read lands.
    private val _freeAiState = MutableStateFlow(
        UiFreeAiState(available = freeAiCredits.available(), signedIn = freeAiCredits.signedIn()),
    )
    override val freeAi: StateFlow<UiFreeAiState> get() = _freeAiState.asStateFlow()
    private var freeAiJob: Job? = null

    override fun refreshFreeAi() {
        if (!freeAiCredits.available()) {
            _freeAiState.value = UiFreeAiState(available = false)
            return
        }
        if (!freeAiCredits.signedIn()) {
            _freeAiState.value = UiFreeAiState(available = true, signedIn = false)
            return
        }
        freeAiJob?.cancel()
        _freeAiState.update { it.copy(available = true, signedIn = true, loading = it.userId == null) }
        freeAiJob = scope.launch(Dispatchers.IO) {
            val next = when (val r = freeAiCredits.wallet()) {
                is dev.ide.store.StoreResult.Ok -> r.value.let { w ->
                    UiFreeAiState(
                        available = true, signedIn = true, userId = w.userId, enabled = w.enabled,
                        points = w.points, freePoints = w.freePoints, earnedPoints = w.earnedPoints,
                        dailyFreePoints = w.dailyFreePoints, adPoints = w.adPoints,
                        adsLeftToday = w.adsLeftToday, adDailyCap = w.adDailyCap,
                        models = w.models.map {
                            dev.ide.ui.backend.UiFreeAiModel(it.id, it.pointsPerStep, it.minPoints, it.available)
                        },
                    )
                }
                is dev.ide.store.StoreResult.Failed ->
                    if (r.status == 401) UiFreeAiState(available = true, signedIn = false)
                    else _freeAiState.value.copy(loading = false, error = r.message)
                is dev.ide.store.StoreResult.Unavailable -> _freeAiState.value.copy(loading = false, error = r.reason)
            }
            _freeAiState.value = if (next.signedIn) next.copy(usage = next.usage ?: _freeAiState.value.usage) else next
            // Signing in or out changes whether the free tier is usable; the loop is rebuilt on the next send.
            resetLoop()
        }
    }

    private var freeAiUsageJob: Job? = null

    override fun refreshFreeAiUsage() {
        if (!freeAiCredits.available() || !freeAiCredits.signedIn()) return
        freeAiUsageJob?.cancel()
        freeAiUsageJob = scope.launch(Dispatchers.IO) {
            when (val r = freeAiCredits.usage()) {
                is dev.ide.store.StoreResult.Ok -> r.value.let { u ->
                    val usage = dev.ide.ui.backend.UiFreeAiUsage(
                        points = u.points, freePointsLeft = u.freePointsLeft, dailyFreePoints = u.dailyFreePoints,
                        dailyCapPoints = u.dailyCapPoints, resetsAtMs = u.resetsAtMs, adPoints = u.adPoints,
                        adsToday = u.adsToday, adDailyCap = u.adDailyCap, spentToday = u.spentToday,
                        stepsToday = u.stepsToday, spentWeek = u.spentWeek, stepsWeek = u.stepsWeek,
                        byModelWeek = u.byModelWeek.map { Triple(it.model, it.points, it.steps) },
                        recent = u.recent.map {
                            dev.ide.ui.backend.UiFreeAiStep(
                                it.atMs, it.model, it.points, it.input, it.output, it.cacheRead, it.cacheWrite, it.webSearches,
                            )
                        },
                    )
                    _freeAiState.update { it.copy(usage = usage, error = null) }
                }
                is dev.ide.store.StoreResult.Failed -> _freeAiState.update { it.copy(error = r.message) }
                is dev.ide.store.StoreResult.Unavailable -> _freeAiState.update { it.copy(error = r.reason) }
            }
        }
    }

    private val _usageLimits = MutableStateFlow<UiAgentUsageLimits?>(null)
    override val usageLimits: StateFlow<UiAgentUsageLimits?> = _usageLimits.asStateFlow()
    private var usageJob: Job? = null

    override fun refreshUsageLimits() {
        val cfg = resolveConfig()
        val name = registry.provider(cfg.selectedId)?.displayName ?: "This provider"
        val key = cfg.keys.firstOrNull()
        if (cfg.selectedId != OPENROUTER || key == null) {
            _usageLimits.value = UiAgentUsageLimits(providerName = name, reported = false)
            return
        }
        val usingFree = cfg.model.endsWith(":free")
        usageJob?.cancel()
        usageJob = scope.launch {
            _usageLimits.value = try {
                OpenRouterUsage.fetch(transport, key).toUi(name, usingFree)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (_usageLimits.value?.takeIf { it.reported } ?: UiAgentUsageLimits(name, reported = true))
                    .copy(error = e.message ?: "OpenRouter did not answer.", usingFreeModel = usingFree)
            }
        }
    }

    private fun OpenRouterUsage.toUi(name: String, usingFree: Boolean): UiAgentUsageLimits {
        val meters = buildList {
            freeRequestsLimit?.let { limit ->
                add(UiUsageMeter(UiUsageMeterKind.FREE_REQUESTS, (freeRequestsUsed ?: 0).toDouble(), limit.toDouble(), freeResetsAtMs))
            }
            budgetLimitUsd?.let { limit ->
                add(UiUsageMeter(UiUsageMeterKind.SPEND, budgetUsedUsd ?: 0.0, limit, budgetResetsAtMs))
            }
        }
        return UiAgentUsageLimits(name, reported = true, meters, spentTodayUsd, spentMonthUsd, usingFree)
    }


    override fun refreshModels() {
        val cfg = resolveConfig()
        val provider = registry.provider(cfg.clientProviderId) ?: return
        val key = cfg.keys.firstOrNull().orEmpty()
        if (!cfg.ready) {
            _models.value = provider.models.map { UiAgentModel(it.id, it.displayName) }
            return
        }
        scope.launch {
            val fetched = runCatching { provider.listModels(ProviderConfig(key, cfg.baseUrl, cfg.caCertificatePem)) }
                .getOrDefault(provider.models)
            gatewayModelsLive = cfg.selectedId == GATEWAY && fetched !== provider.models
            val usable = fetched.filter { it.id !in missingModels }.ifEmpty { fetched }
            rememberPreferred(cfg.selectedId, provider, usable)
            _models.value = usable.map { UiAgentModel(it.id, it.displayName) }
        }
    }

    private fun rememberPreferred(providerId: String, provider: dev.ide.agent.LlmProvider, models: List<LlmModelInfo>) {
        provider.preferredModel(models)?.let { preferredModels[providerId] = it }
    }

    // --- the write-permission gate ---

    private val gate = object : AgentPermissionGate {
        override val mode: PermissionMode get() = modePref()

        override suspend fun authorize(request: WriteRequest): Boolean = when (modePref()) {
            PermissionMode.AUTO_ACCEPT -> true
            PermissionMode.PLAN_ONLY -> false
            PermissionMode.ASK_EACH -> {
                if (sessionAllowAll) {
                    true
                } else {
                    val deferred = CompletableDeferred<Boolean>()
                    pendingPermission = deferred
                    _permissionRequest.value = UiAgentPermissionRequest(
                        permIds.incrementAndGet(), request.tool, request.summary, request.path,
                        changes = request.changes.map { it.toUi() },
                    )
                    try {
                        deferred.await()
                    } finally {
                        _permissionRequest.value = null
                        pendingPermission = null
                    }
                }
            }
        }
    }

    override fun answerPermission(id: Int, decision: UiAgentPermissionDecision) {
        when (decision) {
            UiAgentPermissionDecision.DENY -> pendingPermission?.complete(false)
            UiAgentPermissionDecision.ALLOW_ONCE -> pendingPermission?.complete(true)
            UiAgentPermissionDecision.ALLOW_SESSION -> {
                sessionAllowAll = true
                pendingPermission?.complete(true)
            }
        }
    }

    // --- session lifecycle ---

    /** Notes for the model about things the user did outside the chat (a revert), sent with the next message. */
    private val pendingNotes = CopyOnWriteArrayList<String>()

    /** A saved conversation's model-side history, adopted by the next loop that gets built. */
    @Volatile
    private var pendingHistory: List<LlmMessage>? = null

    override fun newSession() {
        creatingApp = false
        job?.cancel()
        loop?.reset()
        loop = null
        loopSignature = null
        pendingHistory = null
        pendingNotes.clear()
        sessionAllowAll = false
        _permissionRequest.value = null
        _questionRequest.value = null
        _chatState.value = UiAgentChatState()
    }

    override fun stop() {
        job?.cancel()
        pendingPermission?.complete(false)
        finishStreaming()
    }

    /**
     * The loop for the current configuration, (re)built when the provider, model or tuning changed. A rebuild
     * carries the conversation across; only a change of model drops replayed reasoning, whose signatures are
     * bound to the model that produced it. Null (with an error shown) when the agent is not configured.
     */
    private fun ensureLoop(): AgentLoop? {
        val cfg = resolveConfig()
        val provider = registry.provider(cfg.clientProviderId)
        if (provider == null) {
            appendError("Unknown AI provider '${cfg.selectedId}'.")
            return null
        }
        if (!cfg.ready) {
            appendError(
                when (cfg.selectedId) {
                    GATEWAY -> "Add the gateway's base URL to use the agent. Tap the key icon to manage providers."
                    FREE_AI -> "Sign in to use CodeAssist Free AI. Tap the key icon, then sign in with GitHub or Google."
                    else -> "Add an API key to use the agent. Tap the key icon to manage providers."
                },
            )
            return null
        }
        val model = cfg.model.ifBlank { provider.defaultModel }
        val maxIterations = prefInt("maxIterations") ?: DEFAULT_MAX_ITERATIONS
        // The free tier's proxy allows 32K output per step (the Supabase fallback clamps to 16K); writing a file
        // takes the whole file as one tool call's arguments, which 8K cut off mid-call.
        val maxTokens = prefInt("maxTokens") ?: if (cfg.selectedId == FREE_AI) FREE_AI_MAX_TOKENS else DEFAULT_MAX_TOKENS
        val thinkingBudget = prefInt("thinkingBudget")
        val webSearch = prefBool("webSearch", default = true)
        // "default" (or unset) leaves the field off the request, so each provider keeps its own default.
        val effort = pref("reasoningEffort")?.takeIf { it != REASONING_EFFORT_DEFAULT }
        val pacer = pacerFor(cfg, model)
        val clientSignature =
            "${cfg.selectedId}|$model|${cfg.baseUrl}|${cfg.keys.hashCode()}|${cfg.caCertificatePem.hashCode()}"
        val signature = "$clientSignature|$maxIterations|$maxTokens|$thinkingBudget|$webSearch|$effort|${System.identityHashCode(pacer)}"
        val existing = loop
        if (existing != null && loopSignature == signature) return existing

        val restored = pendingHistory
        val carried = restored ?: existing?.snapshot()
        val modelChanged = restored != null || (loopClientSignature != null && loopClientSignature != clientSignature)
        val client = KeyFailoverClient(keyPoolFor(cfg, model)) { key ->
            provider.client(ProviderConfig(key, cfg.baseUrl, cfg.caCertificatePem))
        }
        val built = AgentLoop(
            client, model, tools, gate, ::systemPrompt,
            sessionContext = ::sessionContext,
            maxTokens = maxTokens,
            maxIterations = maxIterations,
            thinkingBudget = thinkingBudget,
            webSearch = webSearch,
            effort = effort,
            // The free tier's proxy sees the whole request before Anthropic trims anything, so the conversation is
            // trimmed here too, and summarized once trimming is not enough.
            compactor = when {
                cfg.selectedId == FREE_AI -> HistoryCompactor(triggerChars = FREE_AI_TRIM_CHARS, targetChars = FREE_AI_TRIM_CHARS / 2)
                provider.managesContext -> HistoryCompactor.serverManaged()
                else -> HistoryCompactor()
            },
            autoCompactChars = if (cfg.selectedId == FREE_AI) FREE_AI_COMPACT_CHARS else null,
            autoCompactTokens = if (cfg.selectedId == FREE_AI) FREE_AI_COMPACT_TOKENS else null,
            pacer = pacer,
            checkpoints = checkpoints,
        )
        if (!carried.isNullOrEmpty()) built.restore(carried, dropThinking = modelChanged)
        pendingHistory = null
        loop = built
        loopSignature = signature
        loopClientSignature = clientSignature
        return built
    }

    override fun send(text: String) {
        if (text.isBlank() || _chatState.value.busy) return
        val activeLoop = ensureLoop() ?: return
        val attachments = _chatState.value.pendingAttachments
        val sessionId = _chatState.value.sessionId ?: UUID.randomUUID().toString()

        val userId = msgIds.incrementAndGet()
        val assistantId = msgIds.incrementAndGet()
        _chatState.update {
            it.copy(
                messages = it.messages +
                    UiAgentMessage(userId, UiAgentRole.USER, text, attachments = attachments) +
                    UiAgentMessage(assistantId, UiAgentRole.ASSISTANT, streaming = true),
                busy = true,
                pendingAttachments = emptyList(),
                sessionId = sessionId,
            )
        }
        checkpoints.startTurn(userId)
        val notes = pendingNotes.toList().also { pendingNotes.clear() }
        runLoop(assistantId, userId) { sink ->
            val (prompt, images) = buildPrompt(text, attachments, notes)
            activeLoop.send(prompt, sink, images)
        }
    }

    override fun retry() {
        if (_chatState.value.busy) return
        val activeLoop = ensureLoop() ?: return
        if (!activeLoop.canResume()) return
        val assistantId = msgIds.incrementAndGet()
        _chatState.update {
            it.copy(
                messages = it.messages + UiAgentMessage(assistantId, UiAgentRole.ASSISTANT, streaming = true),
                busy = true,
            )
        }
        val userId = _chatState.value.messages.lastOrNull { it.role == UiAgentRole.USER }?.id
        runLoop(assistantId, userId) { sink -> activeLoop.retry(sink) }
    }

    /**
     * The model-side form of a user message: [text] with its attachments folded in. A file or selection goes
     * in as a tagged block ahead of the question (path and lines included, so the model can act on them with
     * its tools); an image goes in as an image part; each `@path` mention of a project file attaches that file.
     * Bounded, so a mention of a huge file cannot blow the context.
     */
    private suspend fun buildPrompt(
        text: String,
        attachments: List<UiAgentAttachment>,
        notes: List<String>,
    ): Pair<String, List<ContentPart.Image>> {
        val images = ArrayList<ContentPart.Image>()
        val blocks = StringBuilder()
        val attachedPaths = HashSet<String>()
        for (a in attachments) when (a.kind) {
            UiAgentAttachmentKind.IMAGE -> {
                val data = a.base64 ?: continue
                images += ContentPart.Image(a.mediaType ?: "image/png", data)
            }
            UiAgentAttachmentKind.FILE -> {
                val path = a.path ?: continue
                if (attachedPaths.add(path)) appendFile(blocks, path)
            }
            UiAgentAttachmentKind.SELECTION -> {
                blocks.append("<selection path=\"").append(a.path.orEmpty()).append("\" lines=\"")
                    .append(a.startLine ?: 0).append('-').append(a.endLine ?: 0).append("\">\n")
                    .append(a.text.orEmpty().take(MAX_ATTACHED_CHARS)).append("\n</selection>\n\n")
            }
        }
        for (mention in MENTION.findAll(text)) {
            val path = mention.groupValues[1].trimEnd('.', ',', ':', ';', ')')
            val resolved = resolveProjectFile(path) ?: continue
            if (attachedPaths.add(resolved.toString())) appendFile(blocks, resolved.toString(), label = path)
        }
        if (notes.isNotEmpty()) {
            blocks.append("<system-reminder>\n").append(notes.joinToString("\n")).append("\n</system-reminder>\n\n")
        }
        return (if (blocks.isEmpty()) text else blocks.append(text).toString()) to images
    }

    private suspend fun appendFile(out: StringBuilder, path: String, label: String = path) {
        val body = runCatching { workspace.readFile(path) }.getOrNull() ?: return
        val bounded = if (body.length > MAX_ATTACHED_CHARS) {
            body.take(MAX_ATTACHED_CHARS) + "\n… (truncated; read_file has the rest)"
        } else {
            body
        }
        out.append("<file path=\"").append(label).append("\">\n").append(bounded).append("\n</file>\n\n")
    }

    /** [path] as an existing regular file inside the project, or null. */
    private fun resolveProjectFile(path: String): Path? {
        val root = ctx.servicesOrNull?.workspaceRoot ?: return null
        val resolved = runCatching { root.resolve(path).normalize() }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
    }

    /** Run a loop turn on the scope, folding its events into [assistantId] and mapping any failure to a
     *  retryable error bubble; afterwards mark [userId]'s turn undoable if it changed files, and save the
     *  session. Shared by [send], [retry] and the commands. */
    private fun runLoop(assistantId: Long, userId: Long? = null, block: suspend (AgentEventSink) -> Unit) {
        job = scope.launch {
            val sink = AgentEventSink { event -> applyEvent(assistantId, event) }
            try {
                block(sink)
            } catch (e: CancellationException) {
                finishStreaming()
                afterTurn(userId)
                throw e
            } catch (e: Exception) {
                appendError(e.message ?: "The agent request failed.", canRetry = true)
                finishStreaming()
            }
            afterTurn(userId)
        }
    }

    private fun afterTurn(userId: Long?) {
        // Each step of the turn was a request against the account's limits; show where they stand now.
        refreshUsageLimits()
        if (resolveConfig().selectedId == FREE_AI) refreshFreeAi()
        if (userId != null && checkpoints.hasChanges(userId)) {
            _chatState.update { s ->
                s.copy(messages = s.messages.map { if (it.id == userId) it.copy(canUndo = true) else it })
            }
        }
        saveSession()
    }

    private fun saveSession() {
        // A new app's conversation has no project to live in until it creates one.
        if (creatingApp) return
        val state = _chatState.value
        val id = state.sessionId ?: return
        val history = loop?.snapshot() ?: return
        val root = ctx.servicesOrNull?.workspaceRoot ?: return
        val saved = runCatching { sessionStore.save(id, state.messages, history) }.getOrNull() ?: return
        runCatching { recentChatIndex.record(root, saved) }
    }

    /**
     * A tool title with the open project's root taken off its paths: `read app/src/main/…/MainActivity.kt` rather
     * than the whole `/storage/emulated/0/Android/data/…/projects/<name>/app/…` path, which a phone cuts off long
     * before the part that says which file it is.
     */
    private fun projectRelative(title: String): String {
        val root = ctx.servicesOrNull?.workspaceRoot?.toString()?.trimEnd('/', '\\') ?: return title
        if (root.isEmpty()) return title
        return title.replace("$root/", "").replace("$root\\", "").replace(root, ".")
    }

    override fun startNewApp() {
        newSession()
        creatingApp = true
        _chatState.value = UiAgentChatState(creatingApp = true)
    }

    override fun recentChats(): List<dev.ide.ui.backend.UiAgentRecentChat> =
        runCatching { recentChatIndex.list() }.getOrDefault(emptyList())

    private fun applyEvent(assistantId: Long, event: AgentEvent) {
        // Any event after a wait means the wait is over.
        if (event !is AgentEvent.Waiting) {
            mutateAssistant(assistantId) { if (it.waitUntilMs != null) it.copy(waitUntilMs = null, waitReason = "") else it }
        }
        when (event) {
            is AgentEvent.UserMessage -> Unit // already seeded
            is AgentEvent.AssistantTextDelta -> mutateAssistant(assistantId) { it.withText(event.text) }
            is AgentEvent.AssistantThinkingDelta -> mutateAssistant(assistantId) { it.withThinking(event.text) }
            is AgentEvent.ToolCallStarted -> mutateAssistant(assistantId) {
                it.copy(
                    toolCalls = it.toolCalls + UiAgentToolCall(
                        event.id, projectRelative(event.displaySummary), UiAgentToolStatus.RUNNING, input = event.input,
                    ),
                    segments = it.segments.appendTool(event.id),
                )
            }
            is AgentEvent.ToolCallFinished -> mutateAssistant(assistantId) { m ->
                m.copy(toolCalls = m.toolCalls.map {
                    if (it.id == event.id) {
                        it.copy(
                            status = if (event.ok) UiAgentToolStatus.OK else UiAgentToolStatus.ERROR,
                            detail = event.resultSummary,
                            output = event.output,
                            images = event.images.map { img -> dev.ide.ui.backend.UiAgentToolImage(img.mediaType, img.data) },
                        )
                    } else {
                        it
                    }
                })
            }
            is AgentEvent.ToolCallDenied -> mutateAssistant(assistantId) { m ->
                m.copy(toolCalls = m.toolCalls.map {
                    if (it.id == event.id) it.copy(status = UiAgentToolStatus.DENIED, detail = event.reason) else it
                })
            }
            is AgentEvent.FilesChanged -> mutateAssistant(assistantId) { m ->
                m.copy(toolCalls = m.toolCalls.map {
                    if (it.id == event.id) it.copy(changes = event.changes.map { c -> c.toUi() }) else it
                })
            }
            is AgentEvent.TodosUpdated -> _chatState.update { s ->
                s.copy(todos = event.todos.map { UiAgentTodo(it.content, todoStatus(it.status)) })
            }
            is AgentEvent.Compacted -> _chatState.update { s ->
                // Ahead of the reply in progress, so the note reads where it happened.
                val note = UiAgentMessage(msgIds.incrementAndGet(), UiAgentRole.ASSISTANT, text = AUTO_COMPACTED)
                val at = s.messages.indexOfLast { it.id == assistantId }.takeIf { it >= 0 } ?: s.messages.size
                s.copy(messages = s.messages.take(at) + note + s.messages.drop(at))
            }
            is AgentEvent.Waiting -> mutateAssistant(assistantId) {
                it.copy(waitUntilMs = event.untilEpochMs, waitReason = event.reason)
            }
            is AgentEvent.TurnCompleted -> {
                event.usage?.let { u ->
                    mutateAssistant(assistantId) { m ->
                        m.copy(usage = UiAgentUsage(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens))
                    }
                }
                finishStreaming()
            }
            is AgentEvent.Error -> {
                appendError(event.message, canRetry = true, suggestedModel = suggestionFor(event.kind))
                finishStreaming()
            }
        }
    }

    /**
     * For an error a different model fixes (no quota for this model, its daily allowance spent, or the model
     * retired or closed to the account), the model to offer instead: the provider's pick from the account's live
     * list, then its default, then anything else it lists, skipping every model that has already failed this way.
     * A model picked once stays picked across launches, so without this offer a retired pick failed every send.
     */
    private fun suggestionFor(kind: String?): String? {
        val missing = kind == LlmErrorKind.NOT_FOUND.name
        if (!missing && kind != LlmErrorKind.MODEL_NOT_ON_PLAN.name && kind != LlmErrorKind.DAILY_LIMIT.name) return null
        val cfg = resolveConfig()
        if (cfg.selectedId == GATEWAY) return gatewaySuggestion(missing, cfg.model)
        val provider = registry.provider(cfg.clientProviderId) ?: return null
        exhaustedModels += cfg.model
        if (missing) {
            missingModels += cfg.model
            _models.update { list -> list.filterNot { it.id == cfg.model } }
        }
        val listed = _models.value.map { it.id }.ifEmpty { provider.models.map { it.id } }
        val candidates = listOfNotNull(preferredModels[cfg.selectedId], provider.defaultModel) + listed
        return candidates.firstOrNull { it !in exhaustedModels }
    }

    /**
     * A gateway has no default to fall back on, but when it lists its models a "not found" for the typed name
     * (often a file name such as `Qwen3.5-2B-Q4_0.gguf` where the server's id differs) can offer one it serves:
     * the closest by name, else the first.
     */
    private fun gatewaySuggestion(missing: Boolean, model: String): String? {
        if (!missing || !gatewayModelsLive) return null
        missingModels += model
        val listed = _models.value.map { it.id }.filter { it != model && it !in missingModels }
        if (listed.isEmpty()) return null
        val stem = model.substringAfterLast('/').substringBeforeLast(".gguf").lowercase()
        return listed.firstOrNull { it.lowercase().contains(stem) || stem.contains(it.substringAfterLast('/').lowercase()) }
            ?: listed.first()
    }

    private fun todoStatus(status: String): UiAgentTodoStatus = when (status) {
        "in_progress" -> UiAgentTodoStatus.IN_PROGRESS
        "completed" -> UiAgentTodoStatus.DONE
        else -> UiAgentTodoStatus.PENDING
    }

    private fun FileChange.toUi(): UiAgentFileChange = UiAgentFileChange(displayPath(path), before, after)

    /** A path shown relative to the project root when it is inside it. */
    private fun displayPath(path: String): String {
        val root = ctx.servicesOrNull?.workspaceRoot?.toString() ?: return path
        return if (path.startsWith("$root/")) path.removePrefix("$root/") else path
    }

    /**
     * Appends streamed answer text. A delta that follows a tool call or reasoning starts a new step, and the
     * whole-turn [UiAgentMessage.text] gets a paragraph break there so two steps' sentences do not run together.
     */
    private fun UiAgentMessage.withText(delta: String): UiAgentMessage {
        val last = segments.lastOrNull()
        if (last is UiAgentSegment.Text) {
            return copy(text = text + delta, segments = segments.dropLast(1) + UiAgentSegment.Text(last.text + delta))
        }
        val joined = if (text.isBlank()) delta.trimStart() else text.trimEnd() + "\n\n" + delta.trimStart()
        return copy(text = joined, segments = segments + UiAgentSegment.Text(delta.trimStart()))
    }

    private fun UiAgentMessage.withThinking(delta: String): UiAgentMessage {
        val last = segments.lastOrNull()
        if (last is UiAgentSegment.Thinking) {
            return copy(thinking = thinking + delta, segments = segments.dropLast(1) + UiAgentSegment.Thinking(last.text + delta))
        }
        val joined = if (thinking.isBlank()) delta.trimStart() else thinking.trimEnd() + "\n\n" + delta.trimStart()
        return copy(thinking = joined, segments = segments + UiAgentSegment.Thinking(delta.trimStart()))
    }

    private fun List<UiAgentSegment>.appendTool(id: String): List<UiAgentSegment> {
        val last = lastOrNull()
        return if (last is UiAgentSegment.Tools) dropLast(1) + UiAgentSegment.Tools(last.ids + id) else this + UiAgentSegment.Tools(listOf(id))
    }

    private fun mutateAssistant(id: Long, block: (UiAgentMessage) -> UiAgentMessage) {
        _chatState.update { s -> s.copy(messages = s.messages.map { if (it.id == id) block(it) else it }) }
    }

    private fun finishStreaming() {
        _chatState.update { s ->
            s.copy(
                messages = s.messages.map {
                    if (it.streaming || it.waitUntilMs != null) it.copy(streaming = false, waitUntilMs = null, waitReason = "") else it
                },
                busy = false,
            )
        }
    }

    private fun appendError(message: String, canRetry: Boolean = false, suggestedModel: String? = null) {
        _chatState.update {
            it.copy(
                messages = it.messages + UiAgentMessage(
                    msgIds.incrementAndGet(), UiAgentRole.ASSISTANT,
                    text = message, isError = true, canRetry = canRetry, suggestedModel = suggestedModel,
                ),
                busy = false,
            )
        }
    }

    private fun appendInfo(message: String) {
        _chatState.update {
            it.copy(messages = it.messages + UiAgentMessage(msgIds.incrementAndGet(), UiAgentRole.ASSISTANT, text = message))
        }
    }

    // --- attachments and mentions ---

    override fun attach(attachment: UiAgentAttachment) {
        _chatState.update { s ->
            if (attachment in s.pendingAttachments) s else s.copy(pendingAttachments = s.pendingAttachments + attachment)
        }
    }

    override fun detach(index: Int) {
        _chatState.update { s ->
            s.copy(pendingAttachments = s.pendingAttachments.filterIndexed { i, _ -> i != index })
        }
    }

    /** The project's files (relative paths), cached briefly so typing after `@` does not walk the tree per key. */
    @Volatile
    private var fileCache: Pair<Long, List<String>>? = null

    override suspend fun mentionCandidates(query: String): List<UiAgentMention> {
        val root = ctx.servicesOrNull?.workspaceRoot ?: return emptyList()
        val files = projectFiles(root)
        val q = query.lowercase()
        // Name matches before path matches, then shorter paths first: the file you mean is usually the one whose
        // name you are typing.
        return files.asSequence()
            .map { it to it.substringAfterLast('/').lowercase() }
            .filter { (path, name) -> q.isEmpty() || name.contains(q) || path.lowercase().contains(q) }
            .sortedWith(compareBy({ !it.second.startsWith(q) }, { !it.second.contains(q) }, { it.first.length }))
            .take(MAX_MENTIONS)
            .map { (path, _) -> UiAgentMention(path.substringAfterLast('/'), path, path.substringBeforeLast('/', "")) }
            .toList()
    }

    private fun projectFiles(root: Path): List<String> {
        val now = System.currentTimeMillis()
        fileCache?.let { (at, files) -> if (now - at < FILE_CACHE_MS) return files }
        val files = ArrayList<String>()
        runCatching {
            Files.walk(root).use { stream ->
                val it = stream.iterator()
                while (it.hasNext() && files.size < MAX_INDEXED_FILES) {
                    val p = it.next()
                    val rel = root.relativize(p).toString()
                    if (rel.isEmpty() || rel.split('/').any { seg -> seg in SKIPPED_DIRS || seg.startsWith(".") }) continue
                    if (Files.isRegularFile(p)) files += rel
                }
            }
        }
        fileCache = now to files
        return files
    }

    // --- commands ---

    override fun commands(): List<UiAgentCommand> = COMMANDS

    override fun runCommand(name: String, args: String): Boolean {
        when (name) {
            "clear", "new" -> newSession()
            "compact" -> compact()
            "init" -> send(INIT_PROMPT + if (args.isNotBlank()) "\n\nAlso: $args" else "")
            "undo" -> {
                val last = _chatState.value.messages.lastOrNull { it.role == UiAgentRole.USER && it.canUndo }
                if (last != null) undoTurn(last.id) else appendInfo("Nothing to undo: the agent has not changed any files.")
            }
            else -> return false
        }
        return true
    }

    private fun compact() {
        if (_chatState.value.busy) return
        val activeLoop = ensureLoop() ?: return
        if (!activeLoop.canResume()) {
            appendInfo("Nothing to compact yet.")
            return
        }
        val assistantId = msgIds.incrementAndGet()
        _chatState.update {
            it.copy(
                messages = it.messages + UiAgentMessage(
                    assistantId, UiAgentRole.ASSISTANT, text = COMPACTED, streaming = true,
                    segments = listOf(UiAgentSegment.Text(COMPACTED)),
                ),
                busy = true,
            )
        }
        runLoop(assistantId) { sink -> activeLoop.compactConversation(sink) }
    }

    // --- saved sessions ---

    override fun sessions(): List<UiAgentSessionSummary> = sessionStore.list()

    override fun resumeSession(id: String) {
        val saved = sessionStore.load(id) ?: return
        newSession()
        val maxId = saved.messages.maxOfOrNull { it.id } ?: 0L
        while (msgIds.get() < maxId) msgIds.set(maxId)
        pendingHistory = saved.history
        _chatState.value = UiAgentChatState(messages = saved.messages, sessionId = id)
    }

    override fun deleteSession(id: String) {
        sessionStore.delete(id)
        ctx.servicesOrNull?.workspaceRoot?.let { root -> runCatching { recentChatIndex.remove(root, id) } }
        if (_chatState.value.sessionId == id) newSession()
    }

    // --- undo ---

    override fun undoTurn(messageId: Long) {
        if (_chatState.value.busy) return
        val message = _chatState.value.messages.firstOrNull { it.id == messageId } ?: return
        scope.launch {
            val report = checkpoints.revert(messageId)
            _chatState.update { s ->
                s.copy(messages = s.messages.map {
                    if (it.role == UiAgentRole.USER && it.id >= messageId && it.canUndo) it.copy(canUndo = false, undone = true) else it
                })
            }
            appendInfo(report)
            // The model still remembers making those edits; without this it would edit files that are no longer
            // in the state it thinks they are.
            pendingNotes += "The user reverted every file change the agent made since their message \"" +
                message.text.take(80) + "\". Those files are back to how they were before it; read them again before editing."
            saveSession()
        }
    }

    /** The stable half of the prompt: the top-level system prefix, identical for the life of a conversation. */
    private fun systemPrompt(): String =
        if (creatingApp) {
            SystemPrompt.grounding(tools.tools.map { it.spec.name }, null, listOf(NewAppTools.PROMPT))
        } else {
            SystemPrompt.grounding(
                tools.tools.map { it.spec.name },
                ctx.servicesOrNull?.let { projectInstructions(it) },
                agentConnections.promptSections(),
            )
        }

    // --- connections: MCP servers and skills ---

    override val connections: StateFlow<dev.ide.ui.backend.UiAgentConnections> get() = agentConnections.state

    override fun refreshConnections() = agentConnections.refresh()

    override fun saveMcpServer(
        config: dev.ide.ui.backend.UiMcpServerConfig,
        scope: dev.ide.ui.backend.UiMcpScope,
        previousName: String?,
    ): String? = agentConnections.save(config, scope, previousName)

    override fun removeMcpServer(name: String, scope: dev.ide.ui.backend.UiMcpScope) = agentConnections.remove(name, scope)

    override fun setMcpServerEnabled(name: String, scope: dev.ide.ui.backend.UiMcpScope, enabled: Boolean) =
        agentConnections.setEnabled(name, scope, enabled)

    override fun trustMcpServer(name: String) = agentConnections.trust(name)

    override fun reconnectMcpServer(name: String) = agentConnections.reconnect(name)

    override suspend fun beginMcpSignIn(name: String): String? = agentConnections.beginSignIn(name)

    override fun cancelMcpSignIn() = agentConnections.cancelSignIn()

    override fun signOutMcpServer(name: String) = agentConnections.signOut(name)

    override fun setSkillEnabled(name: String, enabled: Boolean) = agentConnections.setSkillEnabled(name, enabled)

    override fun createSkill(name: String, scope: dev.ide.ui.backend.UiMcpScope): String? = agentConnections.createSkill(name, scope)

    /** The last instruction-file read, keyed by path + modification time so the prefix is not re-read from disk
     *  on every request yet still follows an edit to the file. */
    @Volatile
    private var instructionsCache: Pair<String, String?>? = null

    /** The volatile half: rides after the history each turn, so refreshing it costs no cached tokens. */
    private fun sessionContext(): String =
        SystemPrompt.sessionContext(modePref(), if (creatingApp) "No project is open yet; create one first." else projectContext())

    private fun projectContext(): String? {
        val engine = ctx.servicesOrNull ?: return null
        val root = engine.workspaceRoot.toString()
        val modules = runCatching { engine.modules().joinToString(", ") { it.name } }.getOrNull().orEmpty()
        return buildString {
            append("Project root: ").append(root).append('.')
            if (modules.isNotBlank()) append("\nOpen project modules: ").append(modules).append('.')
            append("\nFile paths are absolute or relative to the project root; a relative path always stays inside the project.")
        }
    }

    /** The first present project instruction file, trimmed to a bounded excerpt (kept stable so it stays
     *  cache-friendly). Null when the project has none. */
    private fun projectInstructions(engine: IdeServices): String? {
        for (name in listOf("AGENTS.md", "CLAUDE.md")) {
            val file = engine.workspaceRoot.resolve(name)
            if (!java.nio.file.Files.isRegularFile(file)) continue
            val stamp = "$file@" + runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
            instructionsCache?.let { (key, body) -> if (key == stamp) return body }
            val body = runCatching { file.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val bounded = if (body.length > MAX_INSTRUCTIONS_CHARS) {
                body.take(MAX_INSTRUCTIONS_CHARS) + "\n… (truncated; use read_memory for the rest)"
            } else {
                body
            }
            instructionsCache = stamp to bounded
            return bounded
        }
        return null
    }

    private fun PermissionMode.toUi(): UiAgentPermissionMode = when (this) {
        PermissionMode.ASK_EACH -> UiAgentPermissionMode.ASK_EACH
        PermissionMode.AUTO_ACCEPT -> UiAgentPermissionMode.AUTO_ACCEPT
        PermissionMode.PLAN_ONLY -> UiAgentPermissionMode.PLAN_ONLY
    }

    private fun UiAgentPermissionMode.toDomain(): PermissionMode = when (this) {
        UiAgentPermissionMode.ASK_EACH -> PermissionMode.ASK_EACH
        UiAgentPermissionMode.AUTO_ACCEPT -> PermissionMode.AUTO_ACCEPT
        UiAgentPermissionMode.PLAN_ONLY -> PermissionMode.PLAN_ONLY
    }

    companion object {
        private const val COMPACTED = "**Conversation compacted.** "

        private val live = CopyOnWriteArrayList<WeakReference<AgentBackend>>()
        private const val LIBRARIES_START_MS = 5_000L
        private const val LIBRARIES_RESOLVE_MS = 10 * 60_000L

        /** The agent of the project at [root] (the most recently opened one when [root] matches none), for an
         *  editor action that runs outside any one project's backend. */
        fun forProject(root: String?): AgentBackend? {
            live.removeAll { it.get() == null }
            val all = live.mapNotNull { it.get() }
            return all.lastOrNull { root != null && it.ctx.servicesOrNull?.workspaceRoot?.toString() == root } ?: all.lastOrNull()
        }

        /** `@path` in a message: a project-relative file to attach. */
        private val MENTION = Regex("""(?<![\w@])@([\w./-]+)""")

        private const val MAX_ATTACHED_CHARS = 40_000
        private const val MAX_MENTIONS = 20
        private const val MAX_INDEXED_FILES = 20_000
        private const val FILE_CACHE_MS = 10_000L
        private val SKIPPED_DIRS = setOf("build", "node_modules", "out", "bin")

        private val COMMANDS = listOf(
            UiAgentCommand("clear", "Start a new conversation"),
            UiAgentCommand("compact", "Summarize the conversation to free up context"),
            UiAgentCommand("init", "Explore the project and write an AGENTS.md for future sessions"),
            UiAgentCommand("undo", "Revert the files the agent changed in the last turn"),
            UiAgentCommand("resume", "Open a saved conversation"),
            UiAgentCommand("model", "Choose the model"),
            UiAgentCommand("mcp", "Manage MCP servers and skills"),
            UiAgentCommand("skills", "Manage the skills the agent can load"),
        )

        private const val INIT_PROMPT = "Explore this project and write an AGENTS.md file at the project root " +
            "for future AI sessions. Cover: what the project is, its modules and layout, how to build and run " +
            "it in CodeAssist, the coding conventions you observe (language, style, architecture), and anything " +
            "non-obvious a newcomer would trip over. Keep it concise and factual; if an AGENTS.md already " +
            "exists, improve it rather than replacing what is still accurate."

        const val AI_PAGE = "ai"
        const val MODE_PREF = "agent.permissionMode"
        const val GATEWAY = "gateway"
        /** CodeAssist Free AI ([CodeAssistFreeProvider]). */
        const val FREE_AI = CodeAssistFreeProvider.ID
        /** Stands in for the free tier's key; the provider ignores it and sends the session's access token. */
        private const val FREE_AI_SESSION_KEY = "codeassist-session"
        /** Free tier: old tool results are trimmed once the conversation passes this many characters (~50K tokens). */
        private const val FREE_AI_TRIM_CHARS = 200_000
        /**
         * Free tier: the next prompt is expected past this many tokens, it is summarized and carries on. Well under the
         * proxy's own clearing (90K), which re-writes the cache on every step once it starts, and under the 100K line
         * past which Haiku 5.5 bills 5x. A step rarely adds more than 20K.
         */
        private const val FREE_AI_COMPACT_TOKENS = 70_000
        /**
         * Free tier: the same, by characters, before the provider has reported a prompt size (a fresh or restored
         * conversation). Code runs about 3 characters a token, so this is roughly [FREE_AI_COMPACT_TOKENS].
         */
        private const val FREE_AI_COMPACT_CHARS = 210_000
        private const val AUTO_COMPACTED =
            "The conversation got long, so its earlier part was summarized to keep going. Details from before may be shorter now."

        /** Output tokens per step on the free tier: the proxy's own cap. */
        private const val FREE_AI_MAX_TOKENS = 32_000
        /** Set once [adoptFreeAiAsDefault] has run on this install. */
        private const val FREE_AI_DEFAULT_ADOPTED = "freeAiDefaultAdopted"
        private const val OPENROUTER = "openrouter"
        private const val MAX_RECENT_MODELS = 5

        /** The `settings.ai.*` pref backing the FTP asset server toggle (`ftpServer`). */
        const val FTP_PREF = "ftpServer"

        /** The port the in-app MCP server listens on (see the "MCP server" AI setting). */
        const val MCP_PORT = CodeAssistMcpServer.DEFAULT_HTTP_PORT

        /** The "leave it to the provider" reasoning-effort choice: nothing is sent on the request. */
        const val REASONING_EFFORT_DEFAULT = "default"

        /** Cap on the project-instruction excerpt folded into the system prompt (keeps requests bounded). */
        const val MAX_INSTRUCTIONS_CHARS = 6_000

        /** Ceiling on tool-call rounds per user turn; the "settings.ai.maxIterations" pref overrides it. */
        const val DEFAULT_MAX_ITERATIONS = 24
        /** Per-response output-token cap; the "settings.ai.maxTokens" pref overrides it. */
        const val DEFAULT_MAX_TOKENS = 8192
    }
}
