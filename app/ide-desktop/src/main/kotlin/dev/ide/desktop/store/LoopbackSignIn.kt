package dev.ide.desktop.store

import dev.ide.core.auth.LoopbackRedirectListener
import dev.ide.platform.log.Log
import dev.ide.store.StoreAccountService
import dev.ide.store.StoreAuthChallenge
import dev.ide.store.StoreProvider
import dev.ide.store.StoreResult
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
