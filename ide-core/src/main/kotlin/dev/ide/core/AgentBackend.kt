package dev.ide.core

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.PermissionMode
import dev.ide.agent.ProviderConfig
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.WriteRequest
import dev.ide.agent.impl.AgentLoop
import dev.ide.agent.impl.AgentProviders
import dev.ide.agent.impl.OkHttpLlmTransport
import dev.ide.agent.impl.SystemPrompt
import dev.ide.agent.impl.builtinTools
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentChatState
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.backend.UiAgentProvider
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val registry = AgentProviders.registry(transport)
    private val workspace = IdeAgentWorkspace(ctx)
    private val tools = SimpleToolRegistry(builtinTools(workspace))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    // --- configuration (read from the AI settings page's prefs) ---

    private fun pref(key: String): String? =
        ctx.manager?.preference("settings.$AI_PAGE.$key")?.takeIf { it.isNotBlank() }

    private fun prefInt(key: String): Int? = pref(key)?.toIntOrNull()

    private fun prefBool(key: String, default: Boolean): Boolean = pref(key)?.toBooleanStrictOrNull() ?: default

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
    )

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
            )
        }
        val provider = registry.provider(selected)
        return ResolvedConfig(
            selectedId = selected,
            clientProviderId = selected,
            apiKey = pref(keyField(selected)),
            model = pref("model") ?: provider?.defaultModel.orEmpty(),
            baseUrl = null,
        )
    }

    private fun keyField(providerId: String): String = when (providerId) {
        "openai" -> "openaiKey"
        "gemini" -> "geminiKey"
        "openrouter" -> "openrouterKey"
        GATEWAY -> "gatewayKey"
        else -> "anthropicKey"
    }

    private fun resetLoop() {
        loop = null
        loopSignature = null
    }

    override fun config(): UiAgentConfig {
        val cfg = resolveConfig()
        val builtins = registry.providers.map { p ->
            UiAgentProvider(
                p.id, p.displayName,
                p.models.map { UiAgentModel(it.id, it.displayName) },
                p.defaultModel,
                apiKey = pref(keyField(p.id)).orEmpty(),
            )
        }
        // A synthetic "Custom gateway" entry (OpenAI-compatible endpoint); its client is the OpenAI provider.
        val gateway = UiAgentProvider(GATEWAY, "Custom gateway", emptyList(), "", apiKey = pref("gatewayKey").orEmpty())
        val configured = !cfg.apiKey.isNullOrBlank() && (cfg.selectedId != GATEWAY || !cfg.baseUrl.isNullOrBlank())
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
        val selected = pref("provider") ?: registry.providers.firstOrNull()?.id ?: "anthropic"
        val field = if (selected == GATEWAY) "gatewayModel" else "model"
        ctx.manager?.setPreference("settings.$AI_PAGE.$field", model)
        resetLoop()
    }

    override fun selectProvider(id: String) {
        ctx.manager?.setPreference("settings.$AI_PAGE.provider", id)
        resetLoop()
    }

    override fun setProviderKey(providerId: String, key: String) {
        ctx.manager?.setPreference("settings.$AI_PAGE.${keyField(providerId)}", key)
        resetLoop()
    }

    override fun setGateway(baseUrl: String, model: String, caCert: String) {
        ctx.manager?.setPreference("settings.$AI_PAGE.gatewayBaseUrl", baseUrl)
        ctx.manager?.setPreference("settings.$AI_PAGE.gatewayModel", model)
        ctx.manager?.setPreference("settings.$AI_PAGE.gatewayCaCert", caCert)
        resetLoop()
    }

    override fun refreshModels() {
        val cfg = resolveConfig()
        val provider = registry.provider(cfg.clientProviderId) ?: return
        val key = cfg.apiKey
        if (key.isNullOrBlank()) {
            _models.value = provider.models.map { UiAgentModel(it.id, it.displayName) }
            return
        }
        scope.launch {
            val fetched = runCatching { provider.listModels(ProviderConfig(key, cfg.baseUrl, cfg.caCertificatePem)) }
                .getOrDefault(provider.models)
            _models.value = fetched.map { UiAgentModel(it.id, it.displayName) }
        }
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

    override fun newSession() {
        job?.cancel()
        loop?.reset()
        loop = null
        loopSignature = null
        sessionAllowAll = false
        _permissionRequest.value = null
        _chatState.value = UiAgentChatState()
    }

    override fun stop() {
        job?.cancel()
        pendingPermission?.complete(false)
        finishStreaming()
    }

    override fun send(text: String) {
        if (text.isBlank() || _chatState.value.busy) return
        val cfg = resolveConfig()
        val provider = registry.provider(cfg.clientProviderId)
        if (provider == null) {
            appendError("Unknown AI provider '${cfg.selectedId}'.")
            return
        }
        if (cfg.apiKey.isNullOrBlank()) {
            appendError("Add an API key to use the agent. Tap the key icon to manage providers.")
            return
        }

        val model = cfg.model.ifBlank { provider.defaultModel }
        val maxIterations = prefInt("maxIterations") ?: DEFAULT_MAX_ITERATIONS
        val maxTokens = prefInt("maxTokens") ?: DEFAULT_MAX_TOKENS
        val thinkingBudget = prefInt("thinkingBudget")
        val webSearch = prefBool("webSearch", default = true)
        val signature =
            "${cfg.selectedId}|$model|${cfg.baseUrl}|${cfg.apiKey.hashCode()}|${cfg.caCertificatePem.hashCode()}|$maxIterations|$maxTokens|$thinkingBudget|$webSearch"
        if (loop == null || loopSignature != signature) {
            val client = provider.client(ProviderConfig(cfg.apiKey, cfg.baseUrl, cfg.caCertificatePem))
            loop = AgentLoop(
                client, model, tools, gate, ::systemPrompt,
                maxTokens = maxTokens,
                maxIterations = maxIterations,
                thinkingBudget = thinkingBudget,
                webSearch = webSearch,
            )
            loopSignature = signature
        }
        val activeLoop = loop ?: return

        val userId = msgIds.incrementAndGet()
        val assistantId = msgIds.incrementAndGet()
        _chatState.update {
            it.copy(
                messages = it.messages +
                    UiAgentMessage(userId, UiAgentRole.USER, text) +
                    UiAgentMessage(assistantId, UiAgentRole.ASSISTANT, streaming = true),
                busy = true,
            )
        }

        runLoop(assistantId) { sink -> activeLoop.send(text, sink) }
    }

    override fun retry() {
        if (_chatState.value.busy) return
        val activeLoop = loop ?: return
        if (!activeLoop.canResume()) return
        val assistantId = msgIds.incrementAndGet()
        _chatState.update {
            it.copy(
                messages = it.messages + UiAgentMessage(assistantId, UiAgentRole.ASSISTANT, streaming = true),
                busy = true,
            )
        }
        runLoop(assistantId) { sink -> activeLoop.retry(sink) }
    }

    /** Run a loop turn on the scope, folding its events into [assistantId] and mapping any failure to a
     *  retryable error bubble. Shared by [send] and [retry]. */
    private fun runLoop(assistantId: Long, block: suspend (AgentEventSink) -> Unit) {
        job = scope.launch {
            val sink = AgentEventSink { event -> applyEvent(assistantId, event) }
            try {
                block(sink)
            } catch (e: CancellationException) {
                finishStreaming()
                throw e
            } catch (e: Exception) {
                appendError(e.message ?: "The agent request failed.", canRetry = true)
                finishStreaming()
            }
        }
    }

    private fun applyEvent(assistantId: Long, event: AgentEvent) {
        when (event) {
            is AgentEvent.UserMessage -> Unit // already seeded
            is AgentEvent.AssistantTextDelta ->
                mutateAssistant(assistantId) { it.copy(text = it.text + event.text) }
            is AgentEvent.AssistantThinkingDelta ->
                mutateAssistant(assistantId) { it.copy(thinking = it.thinking + event.text) }
            is AgentEvent.ToolCallStarted -> mutateAssistant(assistantId) {
                it.copy(toolCalls = it.toolCalls + UiAgentToolCall(event.id, event.displaySummary, UiAgentToolStatus.RUNNING))
            }
            is AgentEvent.ToolCallFinished -> mutateAssistant(assistantId) { m ->
                m.copy(toolCalls = m.toolCalls.map {
                    if (it.id == event.id) {
                        it.copy(
                            status = if (event.ok) UiAgentToolStatus.OK else UiAgentToolStatus.ERROR,
                            detail = event.resultSummary,
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
            is AgentEvent.TurnCompleted -> finishStreaming()
            is AgentEvent.Error -> {
                appendError(event.message, canRetry = true)
                finishStreaming()
            }
        }
    }

    private fun mutateAssistant(id: Long, block: (UiAgentMessage) -> UiAgentMessage) {
        _chatState.update { s -> s.copy(messages = s.messages.map { if (it.id == id) block(it) else it }) }
    }

    private fun finishStreaming() {
        _chatState.update { s ->
            s.copy(
                messages = s.messages.map { if (it.streaming) it.copy(streaming = false) else it },
                busy = false,
            )
        }
    }

    private fun appendError(message: String, canRetry: Boolean = false) {
        _chatState.update {
            it.copy(
                messages = it.messages + UiAgentMessage(
                    msgIds.incrementAndGet(), UiAgentRole.ASSISTANT,
                    text = message, isError = true, canRetry = canRetry,
                ),
                busy = false,
            )
        }
    }

    private fun systemPrompt(): String =
        SystemPrompt.build(modePref(), tools.tools.map { it.spec.name }, projectContext())

    private fun projectContext(): String? {
        val engine = ctx.servicesOrNull ?: return null
        val root = engine.workspaceRoot.toString()
        val modules = runCatching { engine.modules().joinToString(", ") { it.name } }.getOrNull().orEmpty()
        return buildString {
            append("Project root: ").append(root).append('.')
            if (modules.isNotBlank()) append("\nOpen project modules: ").append(modules).append('.')
            append("\nFile paths are absolute or relative to the project root; a relative path always stays inside the project.")
            // Fold in the project's own instruction files (CLAUDE.md/AGENTS.md) so the agent always honors
            // project conventions without a tool round-trip; its own saved notes stay behind read_memory.
            projectInstructions(engine)?.let { append("\n\nProject instructions (follow these):\n").append(it) }
        }
    }

    /** The first present project instruction file, trimmed to a bounded excerpt (kept stable so it stays
     *  cache-friendly). Null when the project has none. */
    private fun projectInstructions(engine: IdeServices): String? {
        for (name in listOf("AGENTS.md", "CLAUDE.md")) {
            val file = engine.workspaceRoot.resolve(name)
            if (!java.nio.file.Files.isRegularFile(file)) continue
            val body = runCatching { file.readText() }.getOrNull()?.trim() ?: continue
            if (body.isEmpty()) continue
            return if (body.length > MAX_INSTRUCTIONS_CHARS) body.take(MAX_INSTRUCTIONS_CHARS) + "\n… (truncated; use read_memory for the rest)" else body
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
        const val AI_PAGE = "ai"
        const val MODE_PREF = "agent.permissionMode"
        const val GATEWAY = "gateway"

        /** Cap on the project-instruction excerpt folded into the system prompt (keeps requests bounded). */
        const val MAX_INSTRUCTIONS_CHARS = 6_000

        /** Ceiling on tool-call rounds per user turn; the "settings.ai.maxIterations" pref overrides it. */
        const val DEFAULT_MAX_ITERATIONS = 24
        /** Per-response output-token cap; the "settings.ai.maxTokens" pref overrides it. */
        const val DEFAULT_MAX_TOKENS = 8192
    }
}
