package dev.ide.core.backend

import dev.ide.ui.backend.UiSignInPhase
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
internal class StoreAccounts(
    private val accounts: dev.ide.store.StoreAccountService,
    /** Asked which providers the backend currently allows. Null keeps whatever the build supports. */
    private val source: dev.ide.store.StoreCatalogSource? = null,
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

    private val authStateFlow = kotlinx.coroutines.flow.MutableStateFlow(
        // A stored refresh token means the user is already signed in, so a relaunch must not present a
        // signed-out store to someone who never signed out.
        accounts.current()?.let { UiStoreAuthState(UiSignInPhase.SignedIn, it.toUi()) } ?: UiStoreAuthState(),
    )

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
        // Own thread rather than a caller-supplied scope: the redirect is delivered by the host activity,
        // which has no scope to hand over, and the exchange is a blocking network call.
        Thread({
            val next = when (val result = accounts.complete(redirect)) {
                is dev.ide.store.StoreResult.Ok ->
                    UiStoreAuthState(UiSignInPhase.SignedIn, result.value.toUi())
                is dev.ide.store.StoreResult.Unavailable ->
                    UiStoreAuthState(UiSignInPhase.Failed, message = result.reason)
                is dev.ide.store.StoreResult.Failed ->
                    UiStoreAuthState(UiSignInPhase.Failed, message = result.message)
            }
            authStateFlow.value = next
        }, "store-sign-in").apply { isDaemon = true }.start()
    }

    fun signOut() {
        accounts.signOut()
        authStateFlow.value = UiStoreAuthState()
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
