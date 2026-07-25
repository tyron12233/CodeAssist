package dev.ide.agent.impl

import dev.ide.agent.LlmClient
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The Antigravity "unified gateway" provider. It reaches Google's Code Assist backend
 * (`cloudcode-pa.googleapis.com/v1internal:streamGenerateContent`) with an OAuth Bearer token instead of an
 * API key, giving access to Gemini 3 / Claude / GPT-OSS at Antigravity's rate limits. The wire is the same
 * Gemini `contents`/`parts` dialect [GeminiProvider] speaks (reused via [GeminiWire] + [GeminiStreamDecoder]),
 * wrapped in a `{project, model, request, ...}` envelope, with each streamed candidate nested under `response`.
 *
 * EXPERIMENTAL. This talks to an undocumented internal endpoint by impersonating the Antigravity IDE's OAuth
 * client; it violates Google's Terms of Service and has led to account bans. Ship it opt-in and warned.
 *
 * The credential ([ProviderConfig.apiKey]) is either an OAuth refresh token (starts with `1//`, exchanged for
 * short-lived access tokens here) or a raw access token, optionally suffixed with an explicit project id:
 * `<token>` or `<token>|<projectId>`. When no project id is supplied the free-tier project is discovered via
 * `loadCodeAssist` / `onboardUser`. [ProviderConfig.baseUrl] overrides the endpoint (e.g. the daily sandbox).
 */
class AntigravityProvider(private val transport: LlmTransport) : LlmProvider {
    override val id: String = "antigravity"
    override val displayName: String = "Antigravity (Google, experimental)"
    override val models: List<LlmModelInfo> = listOf(
        LlmModelInfo("gemini-3-pro-high", "Gemini 3 Pro (high reasoning)", supportsThinking = true),
        LlmModelInfo("gemini-3-pro-low", "Gemini 3 Pro (low reasoning)", supportsThinking = true),
        LlmModelInfo("gemini-3-flash", "Gemini 3 Flash", supportsThinking = true),
        LlmModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        LlmModelInfo("claude-opus-4-6-thinking", "Claude Opus 4.6 (thinking)", supportsThinking = true),
        LlmModelInfo("gpt-oss-120b-medium", "GPT-OSS 120B"),
    )
    override val defaultModel: String = "gemini-3-pro-high"

    override fun client(config: ProviderConfig): LlmClient {
        // Per-client (per-session) OAuth + project state, so a token exchange and the project handshake happen
        // once for the session rather than on every step of a turn.
        val session = AntigravitySession(transport, config)
        return LlmClient { request -> stream(request, config, session) }
    }

    private fun stream(request: LlmRequest, config: ProviderConfig, session: AntigravitySession): Flow<LlmStreamEvent> = flow {
        val token = session.accessToken()
        val project = session.projectId(token)
        val base = config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE
        val sse = SseRequest(
            url = "$base/$API_VERSION:streamGenerateContent?alt=sse",
            headers = apiHeaders(token),
            jsonBody = buildBody(request, project),
        )
        val decoder = GeminiStreamDecoder()
        transport.sse(sse).collect { data -> decoder.decode(unwrap(data)).forEach { emit(it) } }
        decoder.finish().forEach { emit(it) }
    }.catch { e -> emit(LlmStreamEvent.Failed(e.message ?: "Antigravity stream error", e)) }

    private fun buildBody(request: LlmRequest, project: String): String = buildJsonObject {
        put("project", project)
        put("model", request.model)
        // The reasoning level for Gemini 3 / thinking Claude models is carried in the model id (…-high / …-low /
        // …-thinking), so no thinkingConfig is sent here.
        put("request", buildJsonObject {
            request.system?.takeIf { it.isNotBlank() }?.let { put("systemInstruction", GeminiWire.systemInstruction(it)) }
            put("contents", GeminiWire.contents(request.messages))
            if (request.tools.isNotEmpty()) put("tools", GeminiWire.toolDeclarations(request.tools))
            put("generationConfig", buildJsonObject { put("maxOutputTokens", request.maxTokens) })
        })
        put("userAgent", "antigravity")
        put("requestId", UUID.randomUUID().toString())
    }.toString()

    /** Unwraps the `{ "response": { ... } }` gateway envelope so the standard Gemini decoder reads the inner
     *  candidate/usage payload; passes anything without that key through unchanged. */
    private fun unwrap(data: String): String {
        val obj = runCatching { AgentJson.parseToJsonElement(data).asObj() }.getOrNull() ?: return data
        return obj["response"]?.toString() ?: data
    }

    companion object {
        const val DEFAULT_BASE = "https://cloudcode-pa.googleapis.com"
        const val API_VERSION = "v1internal"

        // The Antigravity IDE's own client identity, reproduced so the endpoint accepts the request.
        const val USER_AGENT = "antigravity/1.15.8 linux/amd64"
        const val API_CLIENT = "google-cloud-sdk vscode_cloudshelleditor/0.1"
        const val CLIENT_METADATA = """{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}"""

        /** Headers every Code Assist call carries (the Bearer token plus the IDE-identity trio). */
        fun apiHeaders(accessToken: String): Map<String, String> = mapOf(
            "Authorization" to "Bearer $accessToken",
            "content-type" to "application/json",
            "User-Agent" to USER_AGENT,
            "X-Goog-Api-Client" to API_CLIENT,
            "Client-Metadata" to CLIENT_METADATA,
        )
    }
}

/**
 * Session-scoped OAuth + project state for [AntigravityProvider]. Exchanges a refresh token for short-lived
 * access tokens (cached until near expiry, refreshed under a mutex) and resolves the Code Assist project id
 * once — an explicit id from the credential, otherwise discovered via `loadCodeAssist` / `onboardUser`.
 */
internal class AntigravitySession(
    private val transport: LlmTransport,
    config: ProviderConfig,
) {
    private val base: String = config.baseUrl?.trimEnd('/') ?: AntigravityProvider.DEFAULT_BASE
    private val refreshToken: String?
    private val staticAccess: String?

    private val mutex = Mutex()
    private var project: String
    private var cachedAccess: String? = null
    private var accessExpiryMs: Long = 0

    init {
        val credential = config.apiKey.trim()
        val pipe = credential.indexOf('|')
        val token = if (pipe >= 0) credential.substring(0, pipe) else credential
        project = if (pipe >= 0) credential.substring(pipe + 1).substringBefore('|').trim() else ""
        // Google refresh tokens start with "1//"; anything else is treated as a raw (short-lived) access token.
        if (token.startsWith("1//")) {
            refreshToken = token
            staticAccess = null
        } else {
            refreshToken = null
            staticAccess = token
        }
    }

    suspend fun accessToken(): String {
        staticAccess?.let { return it }
        val refresh = refreshToken
            ?: throw IllegalStateException("Antigravity needs an OAuth token. Paste a refresh or access token in the AI provider settings.")
        mutex.withLock {
            cachedAccess?.let { if (System.currentTimeMillis() < accessExpiryMs - EXPIRY_SKEW_MS) return it }
            val body = transport.postForm(
                TOKEN_URL,
                mapOf("content-type" to "application/x-www-form-urlencoded"),
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                ),
            )
            val obj = AgentJson.parseToJsonElement(body).asObj()
            val access = obj?.get("access_token").asStr()
                ?: throw IllegalStateException("Antigravity sign-in failed: no access token returned. Re-authenticate the account.")
            cachedAccess = access
            accessExpiryMs = System.currentTimeMillis() + (obj?.get("expires_in").asInt() ?: 3600) * 1000L
            return access
        }
    }

    suspend fun projectId(accessToken: String): String {
        if (project.isNotBlank()) return project
        mutex.withLock {
            if (project.isNotBlank()) return project
            project = discoverProject(accessToken)
            return project
        }
    }

    private suspend fun discoverProject(accessToken: String): String {
        runCatching { loadCodeAssist(accessToken) }.getOrNull()?.let { return it }
        // Free tier: no pre-existing project, so onboard one.
        runCatching { onboardFreeProject(accessToken) }.getOrNull()?.let { return it }
        throw IllegalStateException(
            "Antigravity could not resolve a Google Cloud project. Add one as '<token>|<projectId>' in the AI provider settings.",
        )
    }

    private suspend fun loadCodeAssist(accessToken: String): String? {
        val body = transport.post(
            "$base/${AntigravityProvider.API_VERSION}:loadCodeAssist",
            AntigravityProvider.apiHeaders(accessToken),
            buildJsonObject { put("metadata", metadata()) }.toString(),
        )
        val p = AgentJson.parseToJsonElement(body).asObj()?.get("cloudaicompanionProject")
        // The field is a bare project-id string on some responses and a { id } object on others.
        return p.asStr()?.takeIf { it.isNotBlank() } ?: p.asObj()?.get("id").asStr()?.takeIf { it.isNotBlank() }
    }

    private suspend fun onboardFreeProject(accessToken: String): String? {
        repeat(ONBOARD_ATTEMPTS) { attempt ->
            val body = transport.post(
                "$base/${AntigravityProvider.API_VERSION}:onboardUser",
                AntigravityProvider.apiHeaders(accessToken),
                buildJsonObject { put("tierId", "FREE"); put("metadata", metadata()) }.toString(),
            )
            val obj = AgentJson.parseToJsonElement(body).asObj()
            val id = obj?.get("response").asObj()?.get("cloudaicompanionProject").asObj()?.get("id").asStr()
            if (obj?.get("done").asBool() == true && !id.isNullOrBlank()) return id
            if (attempt < ONBOARD_ATTEMPTS - 1) delay(ONBOARD_DELAY_MS)
        }
        return null
    }

    private fun metadata() = buildJsonObject {
        put("ideType", "IDE_UNSPECIFIED")
        put("platform", "PLATFORM_UNSPECIFIED")
        put("pluginType", "GEMINI")
    }

    companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"

        // The Antigravity IDE's public (PKCE) OAuth client. The "secret" is a desktop-app OAuth secret (not
        // confidential; it is only accepted alongside a valid refresh token minted for this same client), but
        // it is NOT committed: both values are injected at build time from a gitignored `agent.properties` via
        // [AntigravitySecrets] (generated by agent-impl/build.gradle.kts). They are empty in a build without
        // that file, in which case the provider can't authenticate.
        const val CLIENT_ID = AntigravitySecrets.CLIENT_ID
        const val CLIENT_SECRET = AntigravitySecrets.CLIENT_SECRET

        /** Refresh a minute before expiry so a long turn never sends a stale token. */
        const val EXPIRY_SKEW_MS = 60_000L
        const val ONBOARD_ATTEMPTS = 6
        const val ONBOARD_DELAY_MS = 2_000L
    }
}
