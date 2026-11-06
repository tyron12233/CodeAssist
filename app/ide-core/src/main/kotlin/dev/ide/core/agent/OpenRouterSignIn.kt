package dev.ide.core.agent

import dev.ide.agent.impl.LlmTransport
import dev.ide.agent.impl.OpenRouterOAuth
import dev.ide.core.auth.LoopbackPages
import dev.ide.core.auth.LoopbackRedirectListener
import dev.ide.platform.log.Log
import dev.ide.ui.backend.UiOpenRouterSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * "Sign in with OpenRouter" (see [OpenRouterOAuth]). While a sign-in is pending, a one-shot listener on a free
 * loopback port catches OpenRouter's redirect; the code is exchanged for a key, which goes to [onKey].
 *
 * The same code path serves Android and desktop: on both the browser runs on the same machine as the app, so
 * `http://localhost:<port>` reaches the listener. On Android the page it shows offers a link back into the app.
 */
internal class OpenRouterSignIn(
    private val transport: LlmTransport,
    private val scope: CoroutineScope,
    private val timeout: Duration = 10.minutes,
    /** Saves the key and selects OpenRouter; [freeModel] is set as the model when non-null. */
    private val onKey: (key: String, freeModel: String?) -> Unit,
) {
    private val log = Log.logger("ide.agent.openrouter")
    private val _state = MutableStateFlow<UiOpenRouterSignIn>(UiOpenRouterSignIn.Idle)
    val state: StateFlow<UiOpenRouterSignIn> = _state.asStateFlow()

    private var listener: LoopbackRedirectListener? = null
    private var expiry: Job? = null

    @Synchronized
    fun begin(): String? {
        close()
        // Set before the listener starts, so a redirect can never arrive ahead of it.
        var attempt: OpenRouterOAuth.Attempt? = null
        val l = try {
            LoopbackRedirectListener(0, CALLBACK_PATH, timeout, PAGES) { url -> attempt?.let { complete(url, it) } }
        } catch (e: Exception) {
            log.warn("OpenRouter sign-in listener could not start: ${e.message}")
            _state.value = UiOpenRouterSignIn.Failed("Could not open a local port for the sign-in.")
            return null
        }
        val started = OpenRouterOAuth.start("http://localhost:${l.port}$CALLBACK_PATH")
        attempt = started
        listener = l
        l.start()
        _state.value = UiOpenRouterSignIn.Waiting
        expiry = scope.launch {
            delay(timeout)
            if (_state.value == UiOpenRouterSignIn.Waiting) {
                _state.value = UiOpenRouterSignIn.Failed("Sign-in timed out. Try again.")
            }
        }
        return started.url
    }

    @Synchronized
    fun cancel() {
        close()
        _state.value = UiOpenRouterSignIn.Idle
    }

    private fun close() {
        listener?.close()
        listener = null
        expiry?.cancel()
        expiry = null
    }

    private fun complete(url: String, attempt: OpenRouterOAuth.Attempt) {
        expiry?.cancel()
        val query = parseQuery(URI(url).rawQuery)
        val code = query["code"]
        val returnedState = query["state"]
        when {
            code.isNullOrBlank() -> {
                _state.value = UiOpenRouterSignIn.Failed(query["error_description"] ?: query["error"] ?: "OpenRouter did not approve the sign-in.")
                return
            }
            // OpenRouter echoes `state`; a mismatch means this redirect is not from the sign-in we started.
            returnedState != null && returnedState != attempt.state -> {
                _state.value = UiOpenRouterSignIn.Failed("The sign-in response did not match. Try again.")
                return
            }
        }
        scope.launch {
            try {
                val key = OpenRouterOAuth.exchange(transport, code!!, attempt.verifier)
                // A brand-new account has no credits, so only free models answer; unknown counts as free.
                val freeModel = if (OpenRouterOAuth.isFreeTier(transport, key) != false) {
                    OpenRouterOAuth.freeAgentModel(transport)
                } else {
                    null
                }
                onKey(key, freeModel)
                _state.value = UiOpenRouterSignIn.Connected(freeModel)
            } catch (e: Exception) {
                log.warn("OpenRouter key exchange failed: ${e.message}")
                _state.value = UiOpenRouterSignIn.Failed(e.message ?: "OpenRouter did not return a key.")
            }
        }
    }

    companion object {
        const val CALLBACK_PATH = "/openrouter-callback"

        /** The app link the "done" page offers on Android; MainActivity just brings the app forward for it. */
        const val RETURN_LINK = "codeassist://agent"

        fun parseQuery(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
                val k = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

        private val PAGES = LoopbackPages(
            relay = LoopbackPages.SIGN_IN.relay,
            done = LoopbackPages.page(
                "OpenRouter connected",
                "Your key is saved in CodeAssist. You can close this tab.",
                // Only Android registers the link; a desktop browser would show an error for it.
                "<script>if(/Android/i.test(navigator.userAgent)){var a=document.createElement('a');" +
                    "a.className='btn';a.href='$RETURN_LINK';a.textContent='Back to CodeAssist';" +
                    "document.querySelector('main').appendChild(a)}</script>",
            ),
            failed = LoopbackPages.page("OpenRouter sign-in did not finish", "Go back to CodeAssist to see why, and try again."),
        )
    }
}
