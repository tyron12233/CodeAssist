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
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The single HTTP + Server-Sent-Events transport shared by every provider. [sse] POSTs a JSON body and
 * emits each SSE `data:` payload as a string; the calling provider decodes those payloads into
 * [dev.ide.agent.LlmStreamEvent]s. Kept behind an interface so tests replay recorded payloads offline
 * with no network.
 */
interface LlmTransport {
    fun sse(request: SseRequest): Flow<String>

    /** A plain GET returning the response body (used to list a provider's models). [caCertificatePem] adds a CA
     *  to trust for this call (see [ProviderConfig][dev.ide.agent.ProviderConfig]). */
    suspend fun get(url: String, headers: Map<String, String>, caCertificatePem: String? = null): String =
        throw UnsupportedOperationException("get not supported by this transport")

    /** A non-streaming JSON POST returning the response body (used for out-of-band calls such as creating a
     *  provider-side context cache). Throws an [LlmHttpException] on an error response. */
    suspend fun post(url: String, headers: Map<String, String>, jsonBody: String, caCertificatePem: String? = null): String =
        throw UnsupportedOperationException("post not supported by this transport")

    /** A non-streaming `application/x-www-form-urlencoded` POST returning the response body (used for OAuth
     *  token exchange). Throws an [LlmHttpException] on an error response. */
    suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>, caCertificatePem: String? = null): String =
        throw UnsupportedOperationException("postForm not supported by this transport")
}

/** [caCertificatePem]: an optional additional CA (PEM) to trust for this request's host — for a custom endpoint
 *  behind a private/regional CA (see [ProviderConfig][dev.ide.agent.ProviderConfig]). */
data class SseRequest(val url: String, val headers: Map<String, String>, val jsonBody: String, val caCertificatePem: String? = null)

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

    /** Variant clients that additionally trust a user-supplied CA, cached by its PEM so a provider reuses one. */
    private val caClients = ConcurrentHashMap<String, OkHttpClient>()
    private val jsonMedia = "application/json".toMediaType()

    /** The base client, or one that trusts the system CAs PLUS the certificate(s) in [caPem] — for a custom
     *  endpoint behind a private/regional CA not in the system trust store (e.g. GigaChat). A blank/null PEM, or
     *  one that can't be parsed, falls back to the base client (which then fails the handshake as before). */
    private fun clientFor(caPem: String?): OkHttpClient {
        val pem = caPem?.takeIf { it.isNotBlank() } ?: return http
        return caClients.getOrPut(pem) {
            val tls = Tls.trusting(pem) ?: return@getOrPut http
            http.newBuilder().sslSocketFactory(tls.first, tls.second).build()
        }
    }

    private fun factoryFor(caPem: String?): EventSource.Factory = EventSources.createFactory(clientFor(caPem))

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

        val eventSource = factoryFor(request.caCertificatePem).newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    override suspend fun get(url: String, headers: Map<String, String>, caCertificatePem: String?): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        clientFor(caCertificatePem).newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw toException(null, response, body)
            body
        }
    }

    override suspend fun post(url: String, headers: Map<String, String>, jsonBody: String, caCertificatePem: String?): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(jsonMedia))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            clientFor(caCertificatePem).newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw toException(null, response, body)
                body
            }
        }

    override suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>, caCertificatePem: String?): String =
        withContext(Dispatchers.IO) {
            val formBody = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            clientFor(caCertificatePem).newCall(request).execute().use { response ->
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

/**
 * Builds an [SSLSocketFactory] + [X509TrustManager] that trust the platform's system CAs PLUS the X.509
 * certificate(s) in a PEM string. Used to reach a custom provider endpoint served under a private or regional CA
 * (e.g. GigaChat's "Russian Trusted Root CA") WITHOUT weakening validation: the chain is still verified, just
 * against an expanded trust set. Returns null if the PEM has no parseable certificate or the trust store can't be
 * assembled (the caller then falls back to the default client).
 */
internal object Tls {
    fun trusting(pem: String): Pair<SSLSocketFactory, X509TrustManager>? {
        val certs = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(pem.byteInputStream())
                .filterIsInstance<X509Certificate>()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            // Seed with the platform's default trust anchors so system CAs keep working alongside the custom one.
            val defAlg = TrustManagerFactory.getDefaultAlgorithm()
            TrustManagerFactory.getInstance(defAlg).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?.acceptedIssuers?.forEachIndexed { i, c -> ks.setCertificateEntry("sys-$i", c) }
            // Add the user-supplied CA(s) (a root + intermediate chain is fine — each becomes a trust anchor).
            certs.forEachIndexed { i, c -> ks.setCertificateEntry("user-$i", c) }
            val tmf = TrustManagerFactory.getInstance(defAlg).apply { init(ks) }
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory to tm
        }.getOrNull()
    }
}
