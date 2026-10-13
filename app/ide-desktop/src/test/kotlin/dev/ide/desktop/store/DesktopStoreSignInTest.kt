package dev.ide.desktop.store

import dev.ide.store.StoreAccountService
import dev.ide.store.StoreAuthChallenge
import dev.ide.store.StoreProvider
import dev.ide.store.StoreResult
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Desktop store sign-in: the loopback redirect listener, the account wrapper that starts it, and the token file. */
class DesktopStoreSignInTest {

    private fun get(port: Int, target: String): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$target").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2_000
        c.readTimeout = 2_000
        val status = c.responseCode
        val body = (if (status < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return status to body
    }

    @Test
    fun aFragmentRedirectIsRelayedThenDeliveredOnce() {
        val delivered = ArrayBlockingQueue<String>(2)
        val listener = LoopbackRedirectListener(0, "/auth-callback", 10.seconds) { delivered.add(it) }
        listener.start()
        val port = listener.port

        // Supabase's implicit flow: the tokens are in the fragment, which the browser does not send.
        val (relayStatus, relay) = get(port, "/auth-callback")
        assertEquals(200, relayStatus)
        assertTrue("location.hash" in relay, "the page moves the fragment into the query")
        assertNull(delivered.poll(), "nothing to deliver yet")

        val (otherStatus, _) = get(port, "/favicon.ico")
        assertEquals(404, otherStatus)

        val (status, _) = get(port, "/auth-callback?access_token=a&refresh_token=r&expires_in=3600")
        assertEquals(200, status)
        assertEquals(
            "http://127.0.0.1:$port/auth-callback?access_token=a&refresh_token=r&expires_in=3600",
            delivered.poll(2, TimeUnit.SECONDS),
        )

        // One-shot: the listener is gone once the redirect is in.
        Thread.sleep(100)
        assertFailsWith<ConnectException> { get(port, "/auth-callback?access_token=b") }
        assertNull(delivered.poll())
    }

    @Test
    fun beginStartsTheListenerAndReportsABusyPort() {
        val delegate = object : StoreAccountService {
            override fun begin(provider: StoreProvider) =
                StoreResult.Ok(StoreAuthChallenge("https://auth.example/authorize", "unused"))
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { busy ->
            val signIn = LoopbackSignIn(delegate, "http://127.0.0.1:${busy.localPort}/auth-callback")
            val result = signIn.begin(StoreProvider.GITHUB)
            assertIs<StoreResult.Unavailable<StoreAuthChallenge>>(result)
            assertTrue("${busy.localPort}" in result.reason)
        }

        val free = ServerSocket(0).use { it.localPort }
        val delivered = ArrayBlockingQueue<String>(1)
        val signIn = LoopbackSignIn(delegate, "http://127.0.0.1:$free/auth-callback")
        signIn.onRedirect = { delivered.add(it) }
        assertIs<StoreResult.Ok<StoreAuthChallenge>>(signIn.begin(StoreProvider.GITHUB))
        // Starting again replaces the first listener rather than failing on its own port.
        assertIs<StoreResult.Ok<StoreAuthChallenge>>(signIn.begin(StoreProvider.GITHUB))
        get(free, "/auth-callback?code=xyz")
        assertEquals("http://127.0.0.1:$free/auth-callback?code=xyz", delivered.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun theTokenFileIsOwnerOnlyAndSignOutRemovesIt() {
        val dir = Files.createTempDirectory("store-token")
        try {
            val file = dir.resolve("store-session")
            val store = DesktopTokenStore(file)
            assertNull(store.read())

            store.write("refresh-1")
            assertEquals("refresh-1", store.read())
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file),
                )
            }
            store.write("refresh-2")
            assertEquals("refresh-2", store.read())
            assertEquals(listOf("store-session"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })

            store.write(null)
            assertNull(store.read())
            assertTrue(Files.notExists(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
