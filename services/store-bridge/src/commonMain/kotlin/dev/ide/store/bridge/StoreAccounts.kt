package dev.ide.store.bridge

import dev.ide.ui.backend.UiSignInPhase
import kotlinx.coroutines.launch
import dev.ide.ui.backend.UiStoreAccount
import dev.ide.ui.backend.UiStoreAuthState

/**
 * The store's sign-in state.
 *
 * Split out of [StoreBackend] because it depends on the account port and nothing else: no project, no
 * engine, no host. The phases exist because what the app can observe genuinely changes between them —
 * while the user is in a browser there is nothing to report, and the UI must not pretend otherwise.
 *
 * The redirect arrives through a deep link into the host activity, which can happen while no store screen
 * is on top, so the state cannot live in composition and is held here instead.
 */
class StoreAccounts(
    private val accounts: dev.ide.store.StoreAccountService,
    /** Asked which providers the backend currently allows. Null keeps whatever the build supports. */
    private val source: dev.ide.store.StoreCatalogSource? = null,
    /**
     * Run on a background thread with every account this class adopts, and its result is what the UI is
     * told about. Two things need doing at exactly that moment and nowhere else: the push device has to
     * be bound to the account (a notification addressed to an account reaches no unbound device), and the
     * publisher profile has to exist so the account has a name and a handle to show.
     *
     * A hook rather than a direct call because this class deliberately depends on the account port alone.
     */
    private val onSignedIn: (dev.ide.store.StoreAccount) -> dev.ide.store.StoreAccount = { it },
    /**
     * Where the cold-start session restore runs.
     *
     * Its own scope because the restore must not happen on whoever constructs this: that is app startup,
     * and restoring a session is a network exchange with a network timeout behind it.
     */
    private val scope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + storeIo),
) {

    /**
     * The providers the backend allows, once it has been asked.
     *
     * Null until [refreshProviders] answers, and the build's own list is used until then: a sheet opening
     * before the network replies should offer the provider that has always worked rather than nothing. A
     * backend that answers with fewer providers narrows the list; it never widens it beyond what this build
     * can actually perform.
     */
    private var allowed: List<String>? = null

    /**
     * Ask the backend which providers to offer. Cheap, and safe to call every time a sign-in surface opens.
     *
     * Failure is deliberately silent: not knowing is not the same as nobody being allowed, and hiding the
     * only working sign-in button because a settings read timed out would be worse than being slightly out
     * of date.
     */
    fun refreshProviders() {
        val result = source?.authProviders() ?: return
        if (result is dev.ide.store.StoreResult.Ok) allowed = result.value
    }

    fun authProviders(): List<String> {
        val supported = accounts.providers().map { it.wire }
        val gate = allowed ?: return supported
        // Intersected, not replaced: the backend decides what is *permitted* and the build decides what is
        // *possible*, and offering a provider this build cannot complete would be a dead button.
        return supported.filter { it in gate }
    }

    fun authState(): kotlinx.coroutines.flow.StateFlow<UiStoreAuthState> = authStateFlow

    private val authStateFlow = kotlinx.coroutines.flow.MutableStateFlow(UiStoreAuthState())

    init {
        // A stored credential means the user is already signed in, so a relaunch must not present a
        // signed-out store to someone who never signed out. Restoring it is a network exchange, so it is
        // started here and lands in the flow when it answers rather than holding up construction — which
        // runs during app startup, where a slow or unreachable network would be a visible stall.
        // [hasStoredSession] is the cheap half: nothing is launched for a user who never signed in.
        if (accounts.hasStoredSession()) scope.launch {
            accounts.current()?.let {
                // Signed in is published before [adopt] runs, because adopting is two network calls and a
                // store that reads as signed out for the length of them is worse than one whose name
                // arrives a moment after the session does.
                authStateFlow.value = UiStoreAuthState(UiSignInPhase.SignedIn, it.toUi())
                authStateFlow.value = UiStoreAuthState(UiSignInPhase.SignedIn, adopt(it).toUi())
            }
        }
    }

    fun beginSignIn(provider: String): String? {
        val wanted = dev.ide.store.StoreProvider.entries.firstOrNull { it.wire == provider } ?: return null
        return when (val challenge = accounts.begin(wanted)) {
            is dev.ide.store.StoreResult.Ok -> {
                // Nothing more is observable until the browser comes back, and it may never come back.
                authStateFlow.value = UiStoreAuthState(UiSignInPhase.AwaitingBrowser)
                challenge.value.authorizeUrl
            }
            is dev.ide.store.StoreResult.Unavailable -> {
                authStateFlow.value = UiStoreAuthState(UiSignInPhase.Failed, message = challenge.reason)
                null
            }
            is dev.ide.store.StoreResult.Failed -> {
                authStateFlow.value = UiStoreAuthState(UiSignInPhase.Failed, message = challenge.message)
                null
            }
        }
    }

    fun completeSignIn(redirect: String) {
        authStateFlow.value = UiStoreAuthState(UiSignInPhase.Completing)
        // This class's own scope rather than a caller-supplied one: the redirect is delivered by the host
        // (an Android activity, an iOS URL handler), which has no scope to hand over, and the exchange is
        // a blocking network call that must not run on whichever thread delivered the link.
        scope.launch {
            val next = when (val result = accounts.complete(redirect)) {
                is dev.ide.store.StoreResult.Ok -> {
                    // Same order as the restore above: report the sign-in, then fill in what adopting learns.
                    authStateFlow.value = UiStoreAuthState(UiSignInPhase.SignedIn, result.value.toUi())
                    UiStoreAuthState(UiSignInPhase.SignedIn, adopt(result.value).toUi())
                }
                is dev.ide.store.StoreResult.Unavailable ->
                    UiStoreAuthState(UiSignInPhase.Failed, message = result.reason)
                is dev.ide.store.StoreResult.Failed ->
                    UiStoreAuthState(UiSignInPhase.Failed, message = result.message)
            }
            authStateFlow.value = next
        }
    }

    /**
     * Everything that has to happen once, as soon as an account is known.
     *
     * Failure is swallowed: a device that could not be bound is not push-reachable until the next launch,
     * and a profile that could not be read is a name the screen does not have yet. Neither is a reason to
     * present a signed-in user as signed out.
     */
    private fun adopt(account: dev.ide.store.StoreAccount): dev.ide.store.StoreAccount =
        runCatching { onSignedIn(account) }.getOrDefault(account)

    fun signOut() {
        accounts.signOut()
        authStateFlow.value = UiStoreAuthState()
    }

    /**
     * Re-read whether a session still exists, after a call found out that it does not.
     *
     * A session can die while the app is open: the stored credential is revoked, or it expires, and only
     * a call that tried to use it finds out. Nothing reports that back here, so the callers that make
     * those calls ask afterwards. The answer costs nothing, because [hasStoredSession] reads the token
     * store and never the network, and it is what keeps a screen from saying a signed-out account could
     * not be reached.
     */
    fun recheckSession() {
        if (authStateFlow.value.signedIn && !accounts.hasStoredSession()) {
            authStateFlow.value = UiStoreAuthState()
        }
    }

    private fun dev.ide.store.StoreAccount.toUi() = UiStoreAccount(
        userId = userId,
        email = email,
        handle = handle,
        displayName = displayName,
        avatarUrl = avatarUrl,
        verified = verified,
        isAdmin = isAdmin,
    )

}
