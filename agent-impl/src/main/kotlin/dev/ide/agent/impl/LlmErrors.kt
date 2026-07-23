package dev.ide.agent.impl

import kotlinx.serialization.json.JsonObject

/**
 * Turns a provider's HTTP or in-stream error into a categorized, user-facing message. All three providers
 * wrap errors as `{"error": {...}}` with a "message" plus a discriminator that differs per provider
 * (Anthropic `error.type`, OpenAI `error.type`/`error.code`, Gemini `error.status`); this reads whichever is
 * present and maps it to an [LlmErrorKind] so the transport knows whether a retry is worth attempting and the
 * chat shows something actionable instead of a raw JSON dump. A provider-suggested retry delay is recovered
 * from the `Retry-After` header, Gemini's `RetryInfo.retryDelay`, or an OpenAI "try again in Ns" message.
 */
internal enum class LlmErrorKind(val retryable: Boolean) {
    RATE_LIMIT(true),
    OVERLOADED(true),
    SERVER(true),
    NETWORK(true),
    QUOTA(false),
    AUTH(false),
    NOT_FOUND(false),
    CONTEXT_LENGTH(false),
    INVALID_REQUEST(false),
    UNKNOWN(false),
}

internal data class ParsedLlmError(
    val kind: LlmErrorKind,
    val message: String,
    val retryAfterMs: Long? = null,
) {
    val retryable: Boolean get() = kind.retryable
}

internal object LlmErrors {
    private const val MAX_DETAIL = 400
    private val retryInMessage = Regex("""try again in\s+([0-9]+(?:\.[0-9]+)?)\s*(ms|s)""", RegexOption.IGNORE_CASE)

    /** Parse an HTTP error response body + status into a categorized error. */
    fun parseHttp(statusCode: Int?, body: String?, retryAfterHeader: String?): ParsedLlmError {
        val root = body?.takeIf { it.isNotBlank() }
            ?.let { runCatching { AgentJson.parseToJsonElement(it) }.getOrNull() }
        val errObj = root.asObj()?.get("error").asObj()
        val headerMs = retryAfterHeader?.trim()?.toLongOrNull()?.times(1000)
        return parseErrorObj(statusCode, errObj, headerMs)
    }

    /** Parse an already-decoded `error` object (an in-stream error event carries no HTTP status). */
    fun parseErrorObj(statusCode: Int?, errObj: JsonObject?, retryAfterHeaderMs: Long?): ParsedLlmError {
        val providerMsg = errObj?.get("message").asStr()?.trim()
        val type = (errObj?.get("type").asStr() ?: errObj?.get("status").asStr()).orEmpty().lowercase()
        val code = errObj?.get("code").asStr().orEmpty().lowercase()
        val retryAfterMs = retryAfterHeaderMs
            ?: geminiRetryDelayMs(errObj)
            ?: retryDelayFromMessage(providerMsg)
        val kind = classify(statusCode, type, code, providerMsg)
        return ParsedLlmError(kind, compose(kind, providerMsg, statusCode, retryAfterMs), retryAfterMs)
    }

    /** A connection-level failure (no HTTP response). */
    fun network(t: Throwable?): ParsedLlmError {
        val extra = t?.message?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return ParsedLlmError(
            LlmErrorKind.NETWORK,
            "Couldn't reach the AI provider. Check your connection and try again.$extra",
        )
    }

    private fun classify(status: Int?, type: String, code: String, msg: String?): LlmErrorKind {
        val m = msg.orEmpty().lowercase()
        val quota = type.contains("insufficient_quota") || code.contains("insufficient_quota") ||
            type.contains("billing") || m.contains("exceeded your current quota") ||
            m.contains("billing details") || m.contains("out of credit") || m.contains("credit balance")
        if (quota) return LlmErrorKind.QUOTA
        val auth = status == 401 || status == 403 || type.contains("authentication") ||
            type.contains("unauthenticated") || type.contains("permission") || type.contains("forbidden") ||
            m.contains("api key not valid") || m.contains("invalid api key") || m.contains("incorrect api key")
        if (auth) return LlmErrorKind.AUTH
        val context = code.contains("context_length") || type.contains("context_length") ||
            m.contains("context length") || m.contains("maximum context") || m.contains("prompt is too long")
        if (context) return LlmErrorKind.CONTEXT_LENGTH
        if (status == 429 || type.contains("rate_limit") || code.contains("rate_limit") ||
            type.contains("resource_exhausted")
        ) {
            return LlmErrorKind.RATE_LIMIT
        }
        if (status == 529 || type.contains("overloaded") || type.contains("unavailable") || m.contains("overloaded")) {
            return LlmErrorKind.OVERLOADED
        }
        if (status == 404 || type.contains("not_found")) return LlmErrorKind.NOT_FOUND
        if (status != null && status in 500..599) return LlmErrorKind.SERVER
        if (status == 400 || type.contains("invalid") || type.contains("bad_request")) return LlmErrorKind.INVALID_REQUEST
        return LlmErrorKind.UNKNOWN
    }

    private fun compose(kind: LlmErrorKind, detail: String?, status: Int?, retryAfterMs: Long?): String {
        val wait = retryAfterMs?.let { " Try again in ${humanDelay(it)}." }.orEmpty()
        val tail = detail?.takeIf { it.isNotBlank() }?.let { "\n${it.take(MAX_DETAIL)}" }.orEmpty()
        return when (kind) {
            LlmErrorKind.RATE_LIMIT -> "Rate limit reached: too many requests.$wait$tail"
            LlmErrorKind.OVERLOADED -> "The AI provider is temporarily overloaded.$wait$tail"
            LlmErrorKind.SERVER -> "The AI provider reported a server error${status?.let { " ($it)" }.orEmpty()}.$wait$tail"
            LlmErrorKind.NETWORK -> "Couldn't reach the AI provider. Check your connection.$tail"
            LlmErrorKind.QUOTA -> "Your API quota or billing limit is exhausted. Check your provider account and plan.$tail"
            LlmErrorKind.AUTH -> "Authentication failed. Check your API key in Settings > AI.$tail"
            LlmErrorKind.NOT_FOUND -> "The selected model isn't available for your account. Pick another model.$tail"
            LlmErrorKind.CONTEXT_LENGTH ->
                "This conversation is too long for the model's context window. Start a new chat or shorten it.$tail"
            LlmErrorKind.INVALID_REQUEST ->
                detail?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL) ?: "The provider rejected the request."
            LlmErrorKind.UNKNOWN ->
                detail?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL)
                    ?: "The AI request failed${status?.let { " (HTTP $it)" }.orEmpty()}."
        }
    }

    /** Google's `error.details[]` carries a `RetryInfo` with a `retryDelay` like "5s" or "1.5s". */
    private fun geminiRetryDelayMs(errObj: JsonObject?): Long? {
        val details = errObj?.get("details").asArr() ?: return null
        for (d in details) {
            val delay = d.asObj()?.get("retryDelay").asStr() ?: continue
            val secs = delay.trim().removeSuffix("s").toDoubleOrNull() ?: continue
            return (secs * 1000).toLong()
        }
        return null
    }

    private fun retryDelayFromMessage(msg: String?): Long? {
        val match = retryInMessage.find(msg ?: return null) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (match.groupValues[2].equals("ms", true)) value.toLong() else (value * 1000).toLong()
    }

    private fun humanDelay(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val totalSec = (ms + 999) / 1000
        if (totalSec < 60) return "${totalSec}s"
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (sec == 0L) "${min}m" else "${min}m ${sec}s"
    }
}
