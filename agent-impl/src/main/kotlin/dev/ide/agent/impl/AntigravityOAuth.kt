package dev.ide.agent.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * The Antigravity "Sign in with Google" flow: an OAuth 2.0 authorization-code + PKCE exchange that mints the
 * refresh token [AntigravityProvider] consumes. It reproduces the Antigravity IDE's own (public, PKCE) client
 * so Google issues a token scoped to the Code Assist backend.
 *
 * Because that client only registers a **loopback** redirect (`http://localhost:36742/oauth-callback`), the
 * flow runs a one-shot local HTTP listener on that fixed port, hands the consent URL to [signIn]'s callback
 * for the caller to open in a browser (a Custom Tab on Android, the default browser on desktop), waits for the
 * browser to redirect back to the listener, and exchanges the returned code for tokens. Works identically on
 * ART and the JVM (`java.net` sockets + `java.security`), so it is shared by both launchers.
 *
 * EXPERIMENTAL — see [AntigravityProvider]. Impersonating this client violates Google's Terms of Service.
 */
class AntigravityOAuth(private val transport: LlmTransport) {

    /**
     * Runs the full flow and returns the OAuth **refresh token** to store as the antigravity credential.
     * [onAuthUrl] is invoked once with the Google consent URL as soon as the listener is up; the caller opens
     * it. Suspends until the browser redirects back, then exchanges the code. Cancelling the calling coroutine
     * (within ~[POLL_MS]) tears down the listener. The project id is left for the provider to discover lazily.
     */
    suspend fun signIn(onAuthUrl: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val verifier = pkceVerifier()
        val stateToken = randomToken()
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), CALLBACK_PORT))
                soTimeout = POLL_MS
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Couldn't start the sign-in listener on port $CALLBACK_PORT (already in use?). Close any Antigravity or Gemini CLI session and try again.",
                e,
            )
        }
        server.use { srv ->
            onAuthUrl(buildAuthUrl(pkceChallenge(verifier), stateToken))
            val (code, returnedState) = awaitRedirect(srv)
            check(returnedState == stateToken) { "Sign-in aborted: the OAuth state did not match (possible interference)." }
            exchangeCode(code, verifier)
                ?: throw IllegalStateException("Google did not return a refresh token. Remove CodeAssist from your Google account's third-party access and try again.")
        }
    }

    /** Blocks (polling, so cancellation is honored) on the loopback listener until the OAuth redirect arrives,
     *  replying to the browser and returning the authorization code + state. */
    private suspend fun awaitRedirect(server: ServerSocket): Pair<String, String> {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("Timed out waiting for the browser sign-in. Try again.")
            }
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            }
            socket.use { s ->
                val requestLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                if (!path.startsWith("/oauth-callback")) {
                    respond(s, PAGE_WAIT) // a stray request (e.g. favicon); keep listening
                    return@use
                }
                val params = parseQuery(path.substringAfter('?', ""))
                params["error"]?.let { err ->
                    respond(s, PAGE_FAIL)
                    throw IllegalStateException("Google returned '$err'. Sign-in was not completed.")
                }
                val code = params["code"]
                if (code != null) {
                    respond(s, PAGE_DONE)
                    return code to params["state"].orEmpty()
                }
                respond(s, PAGE_WAIT)
            }
        }
    }

    /** Exchanges the authorization code for tokens; returns the refresh token, or null if none was issued. */
    private suspend fun exchangeCode(code: String, verifier: String): String? {
        val body = transport.postForm(
            AntigravitySession.TOKEN_URL,
            mapOf("content-type" to "application/x-www-form-urlencoded"),
            mapOf(
                "client_id" to AntigravitySession.CLIENT_ID,
                "client_secret" to AntigravitySession.CLIENT_SECRET,
                "code" to code,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            ),
        )
        return AgentJson.parseToJsonElement(body).asObj()?.get("refresh_token").asStr()
    }

    private fun buildAuthUrl(challenge: String, state: String): String {
        val params = linkedMapOf(
            "client_id" to AntigravitySession.CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES.joinToString(" "),
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline", // request a refresh token
            "prompt" to "consent",
        )
        val query = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "https://accounts.google.com/o/oauth2/v2/auth?$query"
    }

    private fun respond(socket: Socket, message: String) {
        val html = "<!doctype html><meta charset=\"utf-8\">" +
            "<body style=\"font-family:system-ui,sans-serif;text-align:center;padding:48px;color:#333\"><h2>$message</h2></body>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            write(bytes)
            flush()
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) dec(it) to "" else dec(it.substring(0, eq)) to dec(it.substring(eq + 1))
        }

    private fun pkceVerifier(): String = base64Url(ByteArray(64).also { SecureRandom().nextBytes(it) })

    private fun pkceChallenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomToken(): String = base64Url(ByteArray(16).also { SecureRandom().nextBytes(it) })

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

    companion object {
        const val CALLBACK_PORT = 36742
        const val REDIRECT_URI = "http://localhost:36742/oauth-callback"

        /** Accept poll interval; also the responsiveness ceiling for cancellation. */
        const val POLL_MS = 500

        /** Give the user five minutes to complete the consent screen. */
        const val TIMEOUT_MS = 300_000L

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

        const val PAGE_DONE = "Signed in to CodeAssist. You can close this tab and return to the app."
        const val PAGE_WAIT = "Completing sign-in…"
        const val PAGE_FAIL = "Sign-in was not completed. Return to CodeAssist and try again."
    }
}
