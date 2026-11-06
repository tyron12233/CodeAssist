package dev.ide.agent.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * "Sign in with OpenRouter": OpenRouter's OAuth PKCE flow, which hands the app a real API key owned by the user's
 * own OpenRouter account. Nothing of ours is billed and no key is shipped in the app.
 *
 * 1. [start] makes a verifier and the browser URL; the browser goes to `openrouter.ai/auth`, the user signs in
 *    and approves, and OpenRouter redirects to the callback with `?code=`.
 * 2. [exchange] trades that code (plus the verifier, which never left the device) for the key.
 *
 * OpenRouter allows a `http://localhost` callback on any port, which is what the app listens on.
 */
object OpenRouterOAuth {
    const val AUTH_URL = "https://openrouter.ai/auth"
    const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
    private const val KEY_INFO_URL = "https://openrouter.ai/api/v1/key"
    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"

    /** One sign-in attempt: [url] is opened in the browser, [verifier] is kept for [exchange]. */
    class Attempt(val url: String, val verifier: String, val state: String)

    fun start(callbackUrl: String, keyLabel: String = "CodeAssist", random: SecureRandom = SecureRandom()): Attempt {
        val verifier = randomToken(random, 48)
        val state = randomToken(random, 16)
        val url = AUTH_URL +
            "?callback_url=" + enc(callbackUrl) +
            "&code_challenge=" + challenge(verifier) +
            "&code_challenge_method=S256" +
            "&key_label=" + enc(keyLabel) +
            "&state=" + state
        return Attempt(url, verifier, state)
    }

    /** The S256 code challenge for [verifier]: base64url (no padding) of its SHA-256. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Trade the callback's [code] for the user's API key. Throws [LlmHttpException] when OpenRouter refuses it. */
    suspend fun exchange(transport: LlmTransport, code: String, verifier: String): String {
        val body = AgentJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("code", JsonPrimitive(code))
                put("code_verifier", JsonPrimitive(verifier))
                put("code_challenge_method", JsonPrimitive("S256"))
            },
        )
        val response = transport.post(KEYS_URL, mapOf("Content-Type" to "application/json"), body)
        return AgentJson.parseToJsonElement(response).asObj()?.get("key").asStr()?.takeIf { it.isNotBlank() }
            ?: throw LlmHttpException("OpenRouter did not return a key.")
    }

    /**
     * Whether [key]'s account has never bought credits. Such an account can only use the free (`:free`) models,
     * so a paid default would fail on the first message. Null when OpenRouter could not be asked.
     */
    suspend fun isFreeTier(transport: LlmTransport, key: String): Boolean? = runCatching {
        val body = transport.get(KEY_INFO_URL, mapOf("Authorization" to "Bearer $key"))
        AgentJson.parseToJsonElement(body).asObj()?.get("data").asObj()?.get("is_free_tier").asStr()?.toBooleanStrictOrNull()
    }.getOrNull()

    /** The free model to start a free account on, from OpenRouter's live list; null when none could be read. */
    suspend fun freeAgentModel(transport: LlmTransport): String? = runCatching {
        pickFreeAgentModel(transport.get(MODELS_URL, emptyMap()))
    }.getOrNull()

    /**
     * From a `/models` response, the free model best suited to the agent: it must take tool calls (the agent cannot
     * work without them). Coding models first, then the largest context window, since an agent turn carries files.
     */
    fun pickFreeAgentModel(modelsJson: String): String? {
        val data = AgentJson.parseToJsonElement(modelsJson).asObj()?.get("data").asArr() ?: return null
        return data.mapNotNull { it.asObj() }
            .filter { m ->
                val id = m["id"].asStr() ?: return@filter false
                val pricing = m["pricing"].asObj()
                val free = id.endsWith(":free") ||
                    (pricing?.get("prompt").asStr() == "0" && pricing?.get("completion").asStr() == "0")
                val tools = m["supported_parameters"].asArr()?.any { it.asStr() == "tools" } == true
                free && tools
            }
            .sortedWith(
                compareByDescending<JsonObject> { "coder" in it["id"].asStr().orEmpty() }
                    .thenByDescending { it["context_length"].asStr()?.toLongOrNull() ?: 0L },
            )
            .firstNotNullOfOrNull { it["id"].asStr() }
    }

    private fun randomToken(random: SecureRandom, bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
}
