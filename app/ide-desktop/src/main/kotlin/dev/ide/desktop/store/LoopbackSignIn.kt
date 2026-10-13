package dev.ide.desktop.store

import dev.ide.platform.log.Log
import dev.ide.store.StoreAccountService
import dev.ide.store.StoreAuthChallenge
import dev.ide.store.StoreProvider
import dev.ide.store.StoreResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Store sign-in on desktop, where the browser cannot hand a deep link back to the app: while a sign-in is
 * pending, a one-shot listener on the loopback redirect ([dev.ide.store.StoreAuth.DESKTOP_REDIRECT]) catches
 * where the provider sends the browser and passes it to [onRedirect].
 *
 * Supabase's default flow puts the tokens in the URL fragment, which a browser never sends to a server. So a
 * request with no query is answered with a page whose script re-requests the same path with the fragment as
 * the query; a request that has one (that re-request, a PKCE `code`, or an error) is the redirect.
 *
 * Everything else about the account comes from [delegate].
 */
internal class LoopbackSignIn(
    private val delegate: StoreAccountService,
    redirectUrl: String,
    private val timeout: Duration = 5.minutes,
) : StoreAccountService by delegate {

    /** Receives the completed redirect. Set by the launcher once the backend that finishes sign-in exists. */
    @Volatile
    var onRedirect: (String) -> Unit = {}

    private val redirect = URI(redirectUrl)
    private val log = Log.logger("ide.store.auth")
    private var listener: LoopbackRedirectListener? = null

    override fun begin(provider: StoreProvider): StoreResult<StoreAuthChallenge> {
        val challenge = delegate.begin(provider)
        if (challenge !is StoreResult.Ok) return challenge
        synchronized(this) {
            // A second click on a sign-in button starts over: the first browser tab is abandoned anyway.
            listener?.close()
            listener = runCatching {
                LoopbackRedirectListener(redirect.port, redirect.path, timeout) { url -> onRedirect(url) }.also { it.start() }
            }.getOrElse {
                log.warn("Store sign-in listener could not start on port ${redirect.port}: ${it.message}")
                return StoreResult.Unavailable(
                    "Sign-in needs port ${redirect.port} on this computer, and another program is using it.",
                )
            }
        }
        return challenge
    }
}

/**
 * A single-use HTTP listener on `127.0.0.1:[port]` that waits for one sign-in redirect to [path], hands its full
 * URL to [onRedirect], and closes. Also closes after [timeout], for a browser tab the user never finished.
 */
internal class LoopbackRedirectListener(
    port: Int,
    private val path: String,
    private val timeout: Duration,
    private val onRedirect: (String) -> Unit,
) : AutoCloseable {

    private val server = ServerSocket().apply {
        reuseAddress = true
        // Loopback only: nothing off this machine can reach it.
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }
    private val origin = "http://127.0.0.1:${server.localPort}"

    /** The port actually bound, which differs from the requested one only when that was 0 (tests). */
    val port: Int get() = server.localPort

    fun start() {
        Thread(::serve, "store-sign-in").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun serve() {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        try {
            while (!server.isClosed) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                server.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val done = server.accept().use(::answer)
                if (done) break
            }
        } catch (_: SocketTimeoutException) {
            // Abandoned sign-in.
        } catch (_: java.net.SocketException) {
            // Closed by a newer sign-in or by the app.
        } finally {
            close()
        }
    }

    /** Answer one request; true once the redirect has been delivered. */
    private fun answer(socket: Socket): Boolean {
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val target = reader.readLine()?.split(' ')?.getOrNull(1) ?: return false
        // Drain the headers; nothing in them matters.
        while (reader.readLine()?.isNotEmpty() == true) Unit

        val requestPath = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        return when {
            requestPath != path -> {
                respond(socket, 404, "Not found")
                false
            }
            query.isEmpty() -> {
                respond(socket, 200, RELAY_PAGE)
                false
            }
            else -> {
                val failed = query.split('&').any { it.startsWith("error=") || it.startsWith("error_description=") }
                respond(socket, 200, if (failed) FAILED_PAGE else DONE_PAGE)
                onRedirect("$origin$path?$query")
                true
            }
        }
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            // The tokens are in this page's URL; do not send them on to anything it might load.
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private companion object {
        fun page(title: String, body: String, script: String = "") = """
            <!doctype html><html><head><meta charset="utf-8"><title>CodeAssist</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:90vh;margin:0;
            background:#f6f6f4;color:#1b1c18}@media(prefers-color-scheme:dark){body{background:#1b1c18;color:#e4e3dc}}
            main{max-width:28rem;padding:0 1rem;text-align:center}h1{font-size:1.25rem}</style></head>
            <body><main><h1>$title</h1><p>$body</p></main>$script</body></html>
        """.trimIndent()

        // Moves the fragment into the query, where the listener can read it, without adding a history entry.
        val RELAY_PAGE = page(
            "Finishing sign-in…",
            "One moment.",
            "<script>var h=location.hash.slice(1);" +
                "if(h){location.replace(location.pathname+'?'+h)}" +
                "else{document.querySelector('h1').textContent='Sign-in did not finish';" +
                "document.querySelector('p').textContent='Go back to CodeAssist and try again.'}</script>",
        )
        val DONE_PAGE = page("Signed in", "You can close this tab and go back to CodeAssist.")
        val FAILED_PAGE = page("Sign-in did not finish", "Go back to CodeAssist to see why, and try again.")
    }
}
