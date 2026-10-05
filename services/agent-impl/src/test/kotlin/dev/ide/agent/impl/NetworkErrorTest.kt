package dev.ide.agent.impl

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A request that never got an HTTP answer is described by its cause, since each cause has a different fix. */
class NetworkErrorTest {

    @Test
    fun aDnsFailureNamesTheHostAndWhatCanBlockTheLookup() {
        val e = UnknownHostException("""Unable to resolve host "generativelanguage.googleapis.com": No address associated with hostname""")
        val parsed = LlmErrors.network(e)
        assertEquals(LlmErrorKind.NETWORK, parsed.kind)
        assertTrue(parsed.retryable, "a DNS blip clears on its own")
        assertTrue(parsed.message.startsWith("Couldn't look up generativelanguage.googleapis.com:"), parsed.message)
        assertTrue(parsed.message.contains("Private DNS"), parsed.message)

        // The JVM's form carries just the host.
        assertTrue(LlmErrors.network(UnknownHostException("api.example.com")).message.contains("look up api.example.com:"))
    }

    @Test
    fun anUntrustedCertificateIsNotRetriedAndPointsAtTheCaSetting() {
        val e = SSLHandshakeException("handshake failed").apply { initCause(CertPathValidatorException("Trust anchor not found")) }
        val parsed = LlmErrors.network(e)
        assertEquals(LlmErrorKind.CERTIFICATE, parsed.kind)
        assertFalse(parsed.retryable)
        assertTrue(parsed.message.contains("CA certificate"), parsed.message)
    }

    @Test
    fun refusedAndTimedOutConnectionsSaySo() {
        val refused = LlmErrors.network(ConnectException("Failed to connect to /127.0.0.1:8080"))
        assertTrue(refused.message.contains("server is running"), refused.message)
        assertTrue(refused.retryable)

        val timeout = LlmErrors.network(IOException("stream failed", SocketTimeoutException("timeout")))
        assertTrue(timeout.message.contains("didn't answer in time"), timeout.message)

        val other = LlmErrors.network(IOException("unexpected end of stream"))
        assertTrue(other.message.startsWith("Couldn't reach the AI provider."), other.message)
    }
}
