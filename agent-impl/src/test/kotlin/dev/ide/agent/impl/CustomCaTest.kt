package dev.ide.agent.impl

import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Custom-CA support for a provider whose endpoint is served under a CA not in the system trust store (e.g.
 * GigaChat's Russian Trusted Root CA): the config's [ProviderConfig.caCertificatePem] must thread through to the
 * transport request, and [Tls.trusting] must build a trust manager from a valid PEM (and reject garbage).
 */
class CustomCaTest {

    private class CaptureTransport : LlmTransport {
        var lastSse: SseRequest? = null
        override fun sse(request: SseRequest): Flow<String> {
            lastSse = request
            return listOf("data: [DONE]").asFlow()
        }
    }

    @Test
    fun providerThreadsTheCaCertificateIntoTheRequest() = runBlocking {
        val t = CaptureTransport()
        OpenAiProvider(t)
            .client(ProviderConfig(apiKey = "k", baseUrl = "https://api.giga.chat", caCertificatePem = "PEM-DATA"))
            .chat(LlmRequest("m", null, listOf(LlmMessage.user("hi")))).toList()
        assertEquals("PEM-DATA", t.lastSse?.caCertificatePem)
    }

    @Test
    fun tlsTrustingRejectsAnUnparseablePem() {
        assertNull(Tls.trusting("not a certificate"))
        assertNull(Tls.trusting(""))
    }

    @Test
    fun tlsTrustingBuildsATrustManagerFromAValidPem() {
        // Derive a REAL certificate PEM from the JVM's own trust store (no hardcoded blob), re-encode it, and
        // feed it back: a valid cert must parse and the system + custom trust store must assemble into a usable
        // SSL factory (the handshake itself needs a live server, so this checks the assembly, not the handshake).
        val anchor = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
            .acceptedIssuers.first()
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(anchor.encoded) +
            "\n-----END CERTIFICATE-----\n"
        assertNotNull(Tls.trusting(pem), "a valid CA PEM must build a trust manager")
    }
}
