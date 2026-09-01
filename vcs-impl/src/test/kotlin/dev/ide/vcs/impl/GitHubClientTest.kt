package dev.ide.vcs.impl

import dev.ide.vcs.VcsException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the GitHub client decides before it ever reaches the network. The device grant is only offered when a
 * usable client id is configured, and an id from the wrong kind of GitHub application is named as such rather
 * than being allowed to fail later as an empty repository list or a failed publish.
 */
class GitHubClientTest {

    @Test
    fun `an unconfigured build offers token sign-in only`() {
        val client = GitHubClient(clientId = "")
        assertFalse(client.deviceAuthSupported)

        val failure = assertFailsWithVcs { client.startDeviceAuth() }
        assertContains(failure, "personal access token")
    }

    @Test
    fun `an oauth app client id enables the browser flow`() {
        assertTrue(GitHubClient(clientId = "Ov23liExampleClientId").deviceAuthSupported)
    }

    @Test
    fun `a github app client id is rejected by name`() {
        for (id in listOf("Iv23liMFYnBW6t6O1Y0F", "Iv1.8a61f9b3a7aba766")) {
            val failure = assertFailsWithVcs { GitHubClient(clientId = id).startDeviceAuth() }
            assertContains(failure, "GitHub App client id")
            assertContains(failure, "OAuth App")
        }
    }

    @Test
    fun `the default client id is a placeholder or an oauth app id`() {
        // A GitHub App id shipped as the default would degrade sign-in silently for every install.
        val shipped = GitHubClient.DEFAULT_CLIENT_ID
        assertTrue(
            shipped.isEmpty() || shipped.startsWith("Ov"),
            "the shipped client id must come from an OAuth App: $shipped",
        )
    }

    @Test
    fun `a json body is read as json`() {
        val parsed = GitHubClient.parseBody("""{"device_code":"abc","interval":5}""") as JsonObject
        assertEquals("abc", (parsed["device_code"] as JsonPrimitive).content)
        assertEquals(5, (parsed["interval"] as JsonPrimitive).int)
    }

    @Test
    fun `a form-encoded oauth reply is read as fields`() {
        // What the OAuth endpoints answer when the Accept header is not honoured.
        val parsed = GitHubClient.parseBody(
            "device_code=abc123&user_code=WDJB-MJHT&verification_uri=https%3A%2F%2Fgithub.com%2Flogin%2Fdevice&interval=5",
        ) as JsonObject
        assertEquals("abc123", (parsed["device_code"] as JsonPrimitive).content)
        assertEquals("WDJB-MJHT", (parsed["user_code"] as JsonPrimitive).content)
        assertEquals("https://github.com/login/device", (parsed["verification_uri"] as JsonPrimitive).content)
    }

    @Test
    fun `a form-encoded error reply keeps the error field`() {
        val parsed = GitHubClient.parseBody("error=authorization_pending&error_description=Pending") as JsonObject
        assertEquals("authorization_pending", (parsed["error"] as JsonPrimitive).content)
    }

    @Test
    fun `a body that is neither json nor fields resolves to nothing`() {
        assertNull(GitHubClient.parseBody(""))
        assertNull(GitHubClient.parseBody("   "))
        assertNull(GitHubClient.parseBody("<html>gateway timeout</html>"))
    }

    @Test
    fun `a dns failure reads as a connection problem, not a native error`() {
        // What Android throws for a DNS miss; the raw text is `android_getaddrinfo failed: EAI_NODATA`.
        val dns = IOException("wrapped", UnknownHostException("android_getaddrinfo failed: EAI_NODATA"))
        val message = assertNotNull(networkFailureMessage(dns, "github.com"))
        assertContains(message, "github.com")
        assertContains(message, "internet connection")
        assertFalse("EAI_NODATA" in message, "the platform's wording must not reach the user")
    }

    @Test
    fun `a timeout and a refused connection each read as themselves`() {
        assertContains(assertNotNull(networkFailureMessage(SocketTimeoutException(), "github.com")), "in time")
        assertContains(
            assertNotNull(networkFailureMessage(ConnectException("refused"), "github.com")),
            "Could not connect",
        )
    }

    @Test
    fun `a failure that is not about the network is left alone`() {
        assertNull(networkFailureMessage(IOException("unexpected end of stream")))
        assertNull(networkFailureMessage(IllegalStateException("boom")))
    }

    @Test
    fun `a network failure keeps its own message rather than the git prefix`() {
        val failure = IOException("transport", UnknownHostException("no address"))
        val wrapped = failure.asVcsFailure("Could not push to origin", host = "github.com")
        assertTrue(wrapped is VcsException)
        assertContains(wrapped.message.orEmpty(), "internet connection")
    }

    /** Run [block] expecting a [VcsException], returning its message. */
    private fun assertFailsWithVcs(block: () -> Unit): String {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is VcsException, "expected a VcsException, got $failure")
        return failure.message.orEmpty()
    }
}
