package dev.ide.agent.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns a provider's HTTP or in-stream error into a categorized, user-facing message. All three providers
 * wrap errors as `{"error": {...}}` with a "message" plus a discriminator that differs per provider
 * (Anthropic `error.type`, OpenAI `error.type`/`error.code`, Gemini `error.status`); this reads whichever is
 * present and maps it to an [LlmErrorKind] so the transport knows whether a retry is worth attempting and the
 * chat shows something actionable instead of a raw JSON dump. A provider-suggested retry delay is recovered
 * from the `Retry-After` header, Gemini's `RetryInfo.retryDelay`, or an OpenAI "try again in Ns" message.
 */
enum class LlmErrorKind(val retryable: Boolean) {
    /** A per-minute limit (requests or tokens). Clears by itself, usually within a minute. */
    RATE_LIMIT(true),
    OVERLOADED(true),
    SERVER(true),
    NETWORK(true),
    /** The endpoint's TLS certificate is not trusted. Retrying cannot fix it; trusting its CA can. */
    CERTIFICATE(false),
    QUOTA(false),
    /** A per-day limit. Waiting a minute will not clear it; it resets on the provider's daily boundary. */
    DAILY_LIMIT(false),
    /** The model has no quota at all on the account's plan (Gemini reports `limit: 0`), typically a model that
     *  is not on the free tier. Only a different model, or billing, gets past it. */
    MODEL_NOT_ON_PLAN(false),
    AUTH(false),
    /** The model is unknown to the provider. A different model fixes it. */
    NOT_FOUND(false),
    /** Nothing answers at the request's URL: a wrong base URL or port, which no model choice fixes. */
    ENDPOINT_NOT_FOUND(false),
    CONTEXT_LENGTH(false),
    INVALID_REQUEST(false),
    UNKNOWN(false),
}

internal data class ParsedLlmError(
    val kind: LlmErrorKind,
    val message: String,
    val retryAfterMs: Long? = null,
    val quota: QuotaInfo? = null,
) {
    val retryable: Boolean get() = kind.retryable
}

internal object LlmErrors {
    private const val MAX_DETAIL = 400
    private val limitInMessage = Regex("""limit:\s*([0-9]+)""")
    private val modelInMessage = Regex("""model:\s*([A-Za-z0-9._-]+)""")
    private val retryInMessage = Regex("""try again in\s+([0-9]+(?:\.[0-9]+)?)\s*(ms|s)""", RegexOption.IGNORE_CASE)

    /** Parse an HTTP error response body + status into a categorized error. */
    fun parseHttp(statusCode: Int?, body: String?, retryAfterHeader: String?, url: String? = null): ParsedLlmError {
        val root = body?.takeIf { it.isNotBlank() }
            ?.let { runCatching { AgentJson.parseToJsonElement(it) }.getOrNull() }.asObj()
        // Local servers and web frameworks rarely use the `{"error": {...}}` shape: llama.cpp-style servers send
        // `{"error": "text"}`, FastAPI `{"detail": "text"}`, others a bare `{"message": "text"}`. Their text is
        // read as the error's message so the user sees what the server said.
        val errObj = root?.get("error").asObj()
            ?: (root?.get("error").asStr() ?: root?.get("detail").asStr() ?: root?.get("message").asStr())
                ?.let { buildJsonObject { put("message", it) } }
        // A 404 that is not about a model is a web server's "page not found": the base URL or port is wrong. Saying
        // "pick another model" sent users of a misconfigured gateway (a dashboard port instead of the API port)
        // looking in the wrong place.
        val detail = errObj?.get("message").asStr()
        if (statusCode == 404 && url != null && detail?.contains("model", ignoreCase = true) != true) {
            val tail = detail?.takeIf { it.isNotBlank() }?.let { "\n${it.take(MAX_DETAIL)}" }.orEmpty()
            return ParsedLlmError(LlmErrorKind.ENDPOINT_NOT_FOUND, endpointNotFound(url) + tail)
        }
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
        val quota = quotaInfo(errObj, providerMsg)
        var kind = classify(statusCode, type, code, providerMsg)
        // A rate-limit-shaped error is refined by WHICH quota tripped: Gemini answers a model that is not on the
        // plan, a spent daily allowance and a per-minute burst with the same 429 and the same wording, and only
        // the first of those clears by waiting. Treating all three as "retry shortly" is what made a fresh free
        // key look broken.
        if (kind == LlmErrorKind.RATE_LIMIT || kind == LlmErrorKind.QUOTA) {
            kind = when {
                quota?.limit == 0L -> LlmErrorKind.MODEL_NOT_ON_PLAN
                quota?.window == QuotaInfo.Window.DAY -> LlmErrorKind.DAILY_LIMIT
                else -> kind
            }
        }
        return ParsedLlmError(kind, compose(kind, providerMsg, statusCode, retryAfterMs, quota), retryAfterMs, quota)
    }

    /**
     * Which quota a Google `RESOURCE_EXHAUSTED` tripped, from the `google.rpc.QuotaFailure` detail (`quotaId`
     * like `GenerateRequestsPerDayPerProjectPerModel-FreeTier`, `quotaMetric`, `quotaValue`) with the
     * human-readable message as a fallback (`... limit: 0, model: gemini-2.5-pro`). Null when the error says
     * nothing about a quota.
     */
    internal fun quotaInfo(errObj: JsonObject?, message: String?): QuotaInfo? {
        val violation = errObj?.get("details").asArr()
            ?.mapNotNull { it.asObj() }
            ?.firstOrNull { it["@type"].asStr()?.endsWith("QuotaFailure") == true }
            ?.get("violations").asArr()?.firstOrNull().asObj()
        val quotaId = violation?.get("quotaId").asStr().orEmpty()
        val metric = violation?.get("quotaMetric").asStr().orEmpty()
        val model = violation?.get("quotaDimensions").asObj()?.get("model").asStr()
            ?: modelInMessage.find(message.orEmpty())?.groupValues?.get(1)
        val limit = violation?.get("quotaValue").asStr()?.toLongOrNull()
            ?: limitInMessage.find(message.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
        if (violation == null && limit == null) return null
        val id = quotaId.lowercase()
        val window = when {
            "perday" in id -> QuotaInfo.Window.DAY
            "perminute" in id -> QuotaInfo.Window.MINUTE
            else -> null
        }
        val tokens = "token" in id || "token" in metric.lowercase()
        return QuotaInfo(
            window = window,
            metric = if (tokens) QuotaInfo.Metric.INPUT_TOKENS else QuotaInfo.Metric.REQUESTS,
            limit = limit,
            model = model,
        )
    }

    /** A connection-level failure (no HTTP response). */
    /**
     * A request that never got an HTTP answer, worded for its cause: each one has a different fix, and "check your
     * connection" was misleading for a DNS block, a server that is not running, or an untrusted certificate.
     */
    fun network(t: Throwable?): ParsedLlmError {
        val chain = generateSequence(t) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
        val extra = t?.message?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

        chain.firstOrNull { it is java.net.UnknownHostException }?.let { e ->
            val host = e.message?.let { HOST_IN_MESSAGE.find(it)?.groupValues?.get(1) ?: it.substringBefore(':').trim() }
                ?.takeIf { it.isNotBlank() } ?: "the provider"
            return ParsedLlmError(
                LlmErrorKind.NETWORK,
                "Couldn't look up $host: the device could not resolve its address. Check that it is online. A " +
                    "Private DNS setting, an ad-blocking DNS or VPN, or a network that filters the address can " +
                    "also block the lookup.$extra",
            )
        }
        val certificate = chain.any {
            it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException ||
                it is javax.net.ssl.SSLPeerUnverifiedException
        }
        if (certificate) {
            return ParsedLlmError(
                LlmErrorKind.CERTIFICATE,
                "The AI provider's certificate isn't trusted, so no secure connection was made. For an endpoint " +
                    "behind a private or regional CA, add its CA certificate in the provider settings.$extra",
            )
        }
        if (chain.any { it is java.net.SocketTimeoutException }) {
            return ParsedLlmError(
                LlmErrorKind.NETWORK,
                "The AI provider didn't answer in time. Check your connection and try again.$extra",
            )
        }
        if (chain.any { it is java.net.ConnectException || it is java.net.NoRouteToHostException }) {
            return ParsedLlmError(
                LlmErrorKind.NETWORK,
                "Couldn't connect to the AI provider. For a local or custom endpoint, check that the server is " +
                    "running and that the address and port are right.$extra",
            )
        }
        return ParsedLlmError(
            LlmErrorKind.NETWORK,
            "Couldn't reach the AI provider. Check your connection and try again.$extra",
        )
    }

    /** The host Android names in `Unable to resolve host "x": No address associated with hostname`. */
    private val HOST_IN_MESSAGE = Regex("\"([^\"]+)\"")

    private fun classify(status: Int?, type: String, code: String, msg: String?): LlmErrorKind {
        val m = msg.orEmpty().lowercase()
        // True billing exhaustion — a retry will NOT clear it. OpenAI marks it `insufficient_quota`;
        // Anthropic reports a spent credit balance. This is deliberately narrow: it must NOT catch the generic
        // "you exceeded your current quota / billing details" wording, because Gemini's free tier returns that
        // exact text for a transient per-minute rate limit (see the 429 branch below).
        val billingExhausted = type.contains("insufficient_quota") || code.contains("insufficient_quota") ||
            m.contains("out of credit") || m.contains("credit balance")
        if (billingExhausted) return LlmErrorKind.QUOTA
        val auth = status == 401 || status == 403 || type.contains("authentication") ||
            type.contains("unauthenticated") || type.contains("permission") || type.contains("forbidden") ||
            m.contains("api key not valid") || m.contains("invalid api key") || m.contains("incorrect api key")
        if (auth) return LlmErrorKind.AUTH
        val context = code.contains("context_length") || type.contains("context_length") ||
            m.contains("context length") || m.contains("maximum context") || m.contains("prompt is too long")
        if (context) return LlmErrorKind.CONTEXT_LENGTH
        // A 429 / RESOURCE_EXHAUSTED clears with time — it is a retryable rate limit, honoring any RetryInfo
        // delay. This MUST win over the residual quota-text heuristic so Gemini's free-tier rate limit (which
        // reuses "quota"/"billing" wording and carries a short retryDelay) is not mistaken for permanent
        // billing exhaustion.
        if (status == 429 || type.contains("rate_limit") || code.contains("rate_limit") ||
            type.contains("resource_exhausted")
        ) {
            return LlmErrorKind.RATE_LIMIT
        }
        // Residual billing signals, for a provider that reports exhaustion WITHOUT a 429.
        if (type.contains("billing") || m.contains("exceeded your current quota") || m.contains("billing details")) {
            return LlmErrorKind.QUOTA
        }
        if (status == 529 || type.contains("overloaded") || type.contains("unavailable") || m.contains("overloaded")) {
            return LlmErrorKind.OVERLOADED
        }
        if (status == 404 || type.contains("not_found")) return LlmErrorKind.NOT_FOUND
        if (status != null && status in 500..599) return LlmErrorKind.SERVER
        if (status == 400 || type.contains("invalid") || type.contains("bad_request")) return LlmErrorKind.INVALID_REQUEST
        return LlmErrorKind.UNKNOWN
    }

    private fun compose(kind: LlmErrorKind, detail: String?, status: Int?, retryAfterMs: Long?, quota: QuotaInfo? = null): String {
        val wait = retryAfterMs?.let { " Try again in ${humanDelay(it)}." }.orEmpty()
        val tail = detail?.takeIf { it.isNotBlank() }?.let { "\n${it.take(MAX_DETAIL)}" }.orEmpty()
        val modelName = quota?.model?.let { "'$it'" } ?: "This model"
        return when (kind) {
            LlmErrorKind.RATE_LIMIT -> when (quota?.metric) {
                QuotaInfo.Metric.INPUT_TOKENS -> "Rate limit reached: too many tokens per minute.$wait$tail"
                else -> "Rate limit reached: too many requests.$wait$tail"
            }
            LlmErrorKind.DAILY_LIMIT ->
                "Daily limit reached for $modelName${quota?.limit?.let { " ($it per day)" }.orEmpty()}. It resets on " +
                    "the provider's daily boundary (midnight Pacific time for Gemini). Switch to another model or " +
                    "enable billing to keep going.$tail"
            LlmErrorKind.MODEL_NOT_ON_PLAN ->
                "$modelName has no quota on your plan (its limit is 0), so every request to it is refused. Pick a " +
                    "different model, or enable billing for this API key's project.$tail"
            LlmErrorKind.OVERLOADED -> "The AI provider is temporarily overloaded.$wait$tail"
            LlmErrorKind.SERVER -> "The AI provider reported a server error${status?.let { " ($it)" }.orEmpty()}.$wait$tail"
            LlmErrorKind.NETWORK -> "Couldn't reach the AI provider. Check your connection.$tail"
            LlmErrorKind.CERTIFICATE -> "The AI provider's certificate isn't trusted.$tail"
            LlmErrorKind.QUOTA -> "Your API quota or billing limit is exhausted. Check your provider account and plan.$tail"
            LlmErrorKind.AUTH -> "Authentication failed. Check your API key in Settings > AI.$tail"
            LlmErrorKind.NOT_FOUND -> "The selected model isn't available. Pick another model.$tail"
            LlmErrorKind.ENDPOINT_NOT_FOUND -> "Nothing was found at this address (HTTP 404).$tail"
            LlmErrorKind.CONTEXT_LENGTH ->
                "This conversation is too long for the model's context window. Start a new chat or shorten it.$tail"
            LlmErrorKind.INVALID_REQUEST ->
                detail?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL) ?: "The provider rejected the request."
            LlmErrorKind.UNKNOWN ->
                detail?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL)
                    ?: "The AI request failed${status?.let { " (HTTP $it)" }.orEmpty()}."
        }
    }

    private fun endpointNotFound(url: String): String =
        "No API was found at ${url.substringBefore('?')} (HTTP 404). Check the base URL: it is the API server's " +
            "address and port, not a web dashboard's, and /v1/chat/completions is added to it (for Ollama, " +
            "http://127.0.0.1:11434)."

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

    fun humanDelay(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val totalSec = (ms + 999) / 1000
        if (totalSec < 60) return "${totalSec}s"
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (sec == 0L) "${min}m" else "${min}m ${sec}s"
    }
}

/**
 * The quota a provider said was exceeded. [window] is null when the provider did not say; [limit] is the
 * allowance for that window (0 means the model is not on the plan at all); [model] is the model it applies to.
 */
data class QuotaInfo(
    val window: Window?,
    val metric: Metric,
    val limit: Long?,
    val model: String?,
) {
    enum class Window { MINUTE, DAY }
    enum class Metric { REQUESTS, INPUT_TOKENS }
}
