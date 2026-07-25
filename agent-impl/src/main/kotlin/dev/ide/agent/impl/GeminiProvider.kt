package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Google Gemini provider (`streamGenerateContent?alt=sse`). Gemini uses `user`/`model` roles and
 * carries tool results as `functionResponse` parts. It has no per-call id, so the tool name is used as the
 * correlation id. Its function-declaration schema does not accept `additionalProperties`, so that key is
 * stripped from tool parameter schemas.
 */
class GeminiProvider(private val transport: LlmTransport) : LlmProvider {
    override val id: String = "gemini"
    override val displayName: String = "Google Gemini"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro"),
        LlmModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash"),
    )
    override val defaultModel: String = "gemini-2.5-pro"

    override fun client(config: ProviderConfig): LlmClient {
        // Per-client (per-session) context cache: the stable system instruction + tool declarations are the
        // largest payload re-sent on every step of a turn, so caching them provider-side stops re-billing
        // them each iteration. Held here so the state lives for the session, not a single request.
        val cache = GeminiContextCache()
        return LlmClient { request -> stream(request, config, cache) }
    }

    override suspend fun listModels(config: ProviderConfig): List<LlmModelInfo> = runCatching {
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val body = transport.get("$base/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to config.apiKey))
        val listed = AgentJson.parseToJsonElement(body).asObj()?.get("models").asArr() ?: return@runCatching models
        listed.mapNotNull { it.asObj() }
            .filter { m -> m["supportedGenerationMethods"].asArr()?.any { it.asStr() == "generateContent" } == true }
            .mapNotNull { m ->
                val name = m["name"].asStr()?.removePrefix("models/") ?: return@mapNotNull null
                LlmModelInfo(name, m["displayName"].asStr() ?: name)
            }
            .filter { it.id.startsWith("gemini") }
            .ifEmpty { models }
    }.getOrDefault(models)

    private fun stream(request: LlmRequest, config: ProviderConfig, cache: GeminiContextCache): Flow<LlmStreamEvent> = flow {
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        // Resolve (lazily create) the provider-side cache for this request's stable prefix; null falls back to
        // sending the system instruction + tools inline, so caching never makes a request worse.
        val cachedContent = cache.resolve(cacheKey(request), estimateTokens(request)) {
            createCache(base, config, request)
        }
        val sse = SseRequest(
            url = "$base/v1beta/models/${request.model}:streamGenerateContent?alt=sse",
            headers = jsonHeaders(config),
            jsonBody = buildBody(request, cachedContent),
        )
        val decoder = GeminiStreamDecoder()
        transport.sse(sse).collect { data -> decoder.decode(data).forEach { emit(it) } }
        decoder.finish().forEach { emit(it) }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "Gemini stream error", e)) }

    private fun jsonHeaders(config: ProviderConfig): Map<String, String> = mapOf(
        "x-goog-api-key" to config.apiKey,
        "content-type" to "application/json",
    )

    /** POSTs a `cachedContents` resource holding the request's system instruction + tools; returns its name
     *  (`cachedContents/...`) or null on any failure (caller then falls back to inline). */
    private suspend fun createCache(base: String, config: ProviderConfig, request: LlmRequest): String? {
        val body = buildJsonObject {
            put("model", "models/${request.model}")
            request.system?.takeIf { it.isNotBlank() }?.let { put("system_instruction", GeminiWire.systemInstruction(it)) }
            if (request.tools.isNotEmpty()) put("tools", GeminiWire.toolDeclarations(request.tools))
            put("ttl", "${GeminiContextCache.TTL_SECONDS}s")
        }.toString()
        val response = transport.post("$base/v1beta/cachedContents", jsonHeaders(config), body)
        return AgentJson.parseToJsonElement(response).asObj()?.get("name").asStr()
    }

    /** A stable key for the cacheable payload: model + system instruction + tool surface. */
    private fun cacheKey(request: LlmRequest): String = buildString {
        append(request.model).append('|').append(request.system?.hashCode() ?: 0)
        request.tools.forEach { append('|').append(it.name).append(':').append(it.parameters.length) }
    }

    /** A rough token estimate (~4 chars/token) for the cacheable payload, to skip creating a cache that would
     *  be rejected for falling below the provider's minimum cacheable size. */
    private fun estimateTokens(request: LlmRequest): Int {
        val chars = (request.system?.length ?: 0) +
            request.tools.sumOf { it.name.length + it.description.length + it.parameters.length }
        return chars / 4
    }

    private fun buildBody(request: LlmRequest, cachedContent: String?): String = buildJsonObject {
        if (cachedContent != null) {
            // The system instruction + tools live in the cache; sending them inline as well is rejected.
            put("cachedContent", cachedContent)
        } else {
            request.system?.takeIf { it.isNotBlank() }?.let { put("system_instruction", GeminiWire.systemInstruction(it)) }
        }
        put("contents", GeminiWire.contents(request.messages))
        if (cachedContent == null && request.tools.isNotEmpty()) {
            put("tools", GeminiWire.toolDeclarations(request.tools))
        }
        put("generationConfig", buildJsonObject {
            put("maxOutputTokens", request.maxTokens)
            thinkingBudget(request)?.let { budget ->
                put("thinkingConfig", buildJsonObject { put("thinkingBudget", budget) })
            }
        })
    }.toString()

    /** Resolves the Gemini thinking budget: 0 to disable when reasoning is off, the requested cap when one is
     *  set, or null to leave the model's default. 2.5 Pro cannot disable thinking, so a 0 there is clamped up
     *  to the minimum rather than rejected by the API. */
    private fun thinkingBudget(request: LlmRequest): Int? {
        val requested = request.thinkingBudget
        val budget = when {
            !request.thinking -> 0
            requested != null -> requested
            else -> return null
        }
        val isPro = request.model.contains("pro", ignoreCase = true)
        return if (isPro) budget.coerceAtLeast(PRO_MIN_THINKING_BUDGET) else budget.coerceAtLeast(0)
    }

    companion object {
        const val DEFAULT_BASE = "https://generativelanguage.googleapis.com"

        /** 2.5 Pro cannot fully disable thinking; the smallest budget it accepts. */
        const val PRO_MIN_THINKING_BUDGET = 128
    }
}

/**
 * Session-scoped state for Gemini's explicit context cache (`cachedContents`). Holds the stable system
 * instruction + tool declarations provider-side so they are billed once instead of on every step of a turn.
 *
 * The policy is conservative so caching is never a net loss on a metered free tier: it does nothing on the
 * first turn (a single-shot chat should not spend an extra request creating a cache), it skips creation when
 * the payload is below the provider's minimum cacheable size, and it falls back to inline on any error and
 * remembers that a given payload could not be cached so it does not retry the creation every turn.
 */
internal class GeminiContextCache {
    private var turn = 0
    private var name: String? = null
    private var currentKey: String? = null
    private var failedKey: String? = null
    private var expireAtMs: Long = 0

    /**
     * Returns a `cachedContents/...` name to reference for this request, or null to fall back to inline. The
     * [creator] is invoked (and its result cached) only when a fresh cache is actually needed.
     */
    suspend fun resolve(key: String, estimatedTokens: Int, creator: suspend () -> String?): String? {
        turn++
        if (turn <= 1) return null
        if (key == failedKey) return null
        if (name != null && key == currentKey && !nearExpiry()) return name
        if (estimatedTokens < MIN_CACHE_TOKENS) {
            failedKey = key
            return null
        }
        val created = runCatching { creator() }.getOrNull()
        if (created.isNullOrBlank()) {
            failedKey = key
            return null
        }
        name = created
        currentKey = key
        failedKey = null
        expireAtMs = System.currentTimeMillis() + TTL_SECONDS * 1000L
        return created
    }

    private fun nearExpiry(): Boolean = System.currentTimeMillis() > expireAtMs - NEAR_EXPIRY_MS

    companion object {
        const val TTL_SECONDS = 3600L
        /** Refresh the cache a minute before its TTL so a long turn never references an expired name. */
        const val NEAR_EXPIRY_MS = 60_000L
        /** Below this the provider rejects a cache create, so do not spend a request attempting it. */
        const val MIN_CACHE_TOKENS = 2_048
    }
}

/** Decoder for Gemini's SSE candidate stream. Function calls arrive whole (no streamed arguments). */
internal class GeminiStreamDecoder {
    private var inputTokens = 0
    private var outputTokens = 0
    private var stopReason: StopReason = StopReason.END_TURN
    private var sawCompletion = false
    private val usedIds = HashMap<String, Int>()
    var completed: Boolean = false
        private set

    fun decode(data: String): List<LlmStreamEvent> {
        val json = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return emptyList()
        val out = ArrayList<LlmStreamEvent>()

        json["usageMetadata"].asObj()?.let { usage ->
            usage["promptTokenCount"].asInt()?.let { inputTokens = it }
            usage["candidatesTokenCount"].asInt()?.let { outputTokens = it }
        }

        val candidate = json["candidates"].asArr()?.firstOrNull().asObj()
        candidate?.get("content").asObj()?.get("parts").asArr()?.forEach { partElement ->
            val part = partElement.asObj() ?: return@forEach
            part["text"].asStr()?.let { if (it.isNotEmpty()) out += LlmStreamEvent.TextDelta(it) }
            part["functionCall"].asObj()?.let { fc ->
                val name = fc["name"].asStr().orEmpty()
                val id = correlationId(name)
                val args = fc["args"]?.toString() ?: "{}"
                // Gemini 2.5 returns a per-part thought signature that MUST be echoed back on the functionCall
                // when continuing, or tool use fails with "missing a thought signature".
                val signature = part["thoughtSignature"].asStr()
                out += LlmStreamEvent.ToolCallStarted(id, name)
                out += LlmStreamEvent.ToolCallCompleted(id, name, args, signature)
            }
        }

        candidate?.get("finishReason").asStr()?.let {
            stopReason = mapStop(it)
            sawCompletion = true
        }
        return out
    }

    /** Emits usage and completion at stream end (Gemini has no explicit terminal event). */
    fun finish(): List<LlmStreamEvent> {
        if (completed) return emptyList()
        completed = true
        return listOf(
            LlmStreamEvent.Usage(TokenUsage(inputTokens, outputTokens)),
            LlmStreamEvent.Completed(if (sawCompletion) stopReason else StopReason.END_TURN),
        )
    }

    /** Gemini has no per-call id; disambiguate repeated tool names within a turn with a suffix. */
    private fun correlationId(name: String): String {
        val count = usedIds.getOrDefault(name, 0)
        usedIds[name] = count + 1
        return if (count == 0) name else "$name#$count"
    }

    private fun mapStop(s: String): StopReason = when (s) {
        "STOP" -> StopReason.END_TURN
        "MAX_TOKENS" -> StopReason.MAX_TOKENS
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> StopReason.REFUSAL
        else -> StopReason.END_TURN
    }
}
