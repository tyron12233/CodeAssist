package dev.ide.core.auth

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.time.Duration

/**
 * A single-use HTTP listener on `127.0.0.1:[port]` that waits for one sign-in redirect to [path], hands its full
 * URL to [onRedirect], and closes. Also closes after [timeout], for a browser tab the user never finished.
 */
class LoopbackRedirectListener(
    port: Int,
    private val path: String,
    private val timeout: Duration,
    private val pages: LoopbackPages = LoopbackPages.SIGN_IN,
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
        Thread(::serve, "loopback-sign-in").apply { isDaemon = true }.start()
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
                respond(socket, 200, pages.relay)
                false
            }
            else -> {
                val failed = query.split('&').any { it.startsWith("error=") || it.startsWith("error_description=") }
                respond(socket, 200, if (failed) pages.failed else pages.done)
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
}

/**
 * The pages a [LoopbackRedirectListener] answers with: [relay] for a query-less hit (it moves a URL fragment into
 * the query and re-requests), [done] once the redirect arrived, [failed] when it carried an error.
 */
class LoopbackPages(val relay: String, val done: String, val failed: String) {
    companion object {
        /** A plain page in the app's colours; [body] and [extra] are trusted HTML. */
        fun page(title: String, body: String, extra: String = "") = """
            <!doctype html><html><head><meta charset="utf-8"><title>CodeAssist</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:90vh;margin:0;
            background:#f6f6f4;color:#1b1c18}@media(prefers-color-scheme:dark){body{background:#1b1c18;color:#e4e3dc}}
            main{max-width:28rem;padding:0 1rem;text-align:center}h1{font-size:1.25rem}
            a.btn{display:inline-block;margin-top:1rem;padding:.7rem 1.4rem;border-radius:999px;background:#4a6a10;
            color:#fff;text-decoration:none;font-weight:600}</style></head>
            <body><main><h1>$title</h1><p>$body</p></main>$extra</body></html>
        """.trimIndent()

        // Moves the fragment into the query, where the listener can read it, without adding a history entry.
        private val RELAY = page(
            "Finishing sign-in…",
            "One moment.",
            "<script>var h=location.hash.slice(1);" +
                "if(h){location.replace(location.pathname+'?'+h)}" +
                "else{document.querySelector('h1').textContent='Sign-in did not finish';" +
                "document.querySelector('p').textContent='Go back to CodeAssist and try again.'}</script>",
        )

        /** The account sign-in pages (the Projects Store). */
        val SIGN_IN = LoopbackPages(
            relay = RELAY,
            done = page("Signed in", "You can close this tab and go back to CodeAssist."),
            failed = page("Sign-in did not finish", "Go back to CodeAssist to see why, and try again."),
        )
    }
}
