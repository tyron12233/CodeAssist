package dev.ide.agent.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * The single HTTP + Server-Sent-Events transport shared by every provider. [sse] POSTs a JSON body and
 * emits each SSE `data:` payload as a string; the calling provider decodes those payloads into
 * [dev.ide.agent.LlmStreamEvent]s. Kept behind an interface so tests replay recorded payloads offline
 * with no network.
 */
interface LlmTransport {
    fun sse(request: SseRequest): Flow<String>

    /** A plain GET returning the response body (used to list a provider's models). */
    suspend fun get(url: String, headers: Map<String, String>): String =
        throw UnsupportedOperationException("get not supported by this transport")
}

data class SseRequest(val url: String, val headers: Map<String, String>, val jsonBody: String)

/**
 * Thrown when a provider request fails at the HTTP layer. [message] is a categorized, user-facing string
 * (see [LlmErrors]); [statusCode] is the HTTP status when there was a response; [retryable] says whether the
 * failure is transient (rate limit / overload / 5xx / network) and [retryAfterMs] carries any
 * provider-suggested wait so the transport can back off intelligently.
 */
class LlmHttpException(
    message: String,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The default transport. One [OkHttpClient] with streaming-friendly timeouts (no read/call timeout so a
 *  long generation is not cut off); runs the same on desktop JVM and ART. */
class OkHttpLlmTransport(
    client: OkHttpClient? = null,
) : LlmTransport {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val factory: EventSource.Factory = EventSources.createFactory(http)
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Retries a transient pre-stream failure (rate limit, overload, 5xx, network) with exponential backoff,
     * honoring any provider-suggested delay. Retries only while nothing has been emitted yet, so a mid-stream
     * drop never re-POSTs and duplicates already-shown output; a provider asking for a very long wait is
     * surfaced instead of blocking the UI.
     */
    override fun sse(request: SseRequest): Flow<String> = flow {
        var attempt = 0
        while (true) {
            var emitted = false
            try {
                rawSse(request).collect { emitted = true; emit(it) }
                return@flow
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                val failure = t as? LlmHttpException
                val retryAfter = failure?.retryAfterMs
                val canRetry = failure?.retryable == true && !emitted && attempt < MAX_RETRIES &&
                    (retryAfter == null || retryAfter <= MAX_AUTO_WAIT_MS)
                if (!canRetry) throw t
                attempt++
                delay((retryAfter ?: backoffMs(attempt)).coerceIn(MIN_DELAY_MS, MAX_AUTO_WAIT_MS))
            }
        }
    }

    private fun rawSse(request: SseRequest): Flow<String> = callbackFlow {
        val httpRequest = Request.Builder()
            .url(request.url)
            .post(request.jsonBody.toRequestBody(jsonMedia))
            .apply { request.headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // Backpressure onto OkHttp's reader thread rather than dropping deltas.
                trySendBlocking(data)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(toException(t, response))
            }
        }

        val eventSource = factory.newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    override suspend fun get(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw toException(null, response, body)
            body
        }
    }

    /** Build a categorized, user-facing [LlmHttpException] from a failure (network) or error response. */
    private fun toException(t: Throwable?, response: Response?, prefetchedBody: String? = null): LlmHttpException {
        if (response == null) {
            val net = LlmErrors.network(t)
            return LlmHttpException(net.message, retryable = net.retryable, cause = t)
        }
        val body = prefetchedBody ?: runCatching { response.body?.string() }.getOrNull()
        val parsed = LlmErrors.parseHttp(response.code, body, response.header("retry-after"))
        return LlmHttpException(parsed.message, response.code, parsed.retryAfterMs, parsed.retryable, t)
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val MIN_DELAY_MS = 500L
        const val MAX_AUTO_WAIT_MS = 20_000L

        /** 1s, 2s, 4s, ... (attempt is 1-based). */
        fun backoffMs(attempt: Int): Long = 1000L shl (attempt - 1).coerceIn(0, 5)
    }
}
