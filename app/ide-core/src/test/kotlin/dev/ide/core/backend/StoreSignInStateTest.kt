package dev.ide.core.backend

import dev.ide.store.StoreAccount
import dev.ide.store.StoreAccountService
import dev.ide.store.StoreAuthChallenge
import dev.ide.store.StoreProvider
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiSignInPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in state the UI renders from.
 *
 * The phases matter because the app's knowledge genuinely differs between them: while the user is in a
 * browser nothing is observable, and a UI that showed progress there would be inventing it. These tests
 * pin that each transition says only what is known, and that a failure keeps the backend's own words
 * instead of a generic message.
 */
class StoreSignInStateTest {

    private class FakeAccounts(
        private val challenge: StoreResult<StoreAuthChallenge> =
            StoreResult.Ok(StoreAuthChallenge("https://provider/authorize?x=1", "codeassist://auth-callback")),
        private val completion: StoreResult<StoreAccount> = StoreResult.Ok(StoreAccount("user-1", email = "a@b.c")),
        private val existing: StoreAccount? = null,
        private val supported: List<StoreProvider> = listOf(StoreProvider.GITHUB),
    ) : StoreAccountService {
        var signedOut = false
        override fun authAvailable() = supported.isNotEmpty()
        override fun providers() = supported
        override fun current() = if (signedOut) null else existing
        override fun begin(provider: StoreProvider) = challenge
        override fun complete(redirect: String) = completion
        override fun signOut() { signedOut = true }
    }

    /** Waits for the completion thread; the engine deliberately does the exchange off the caller. */
    private fun awaitPhase(backend: StoreAccounts, phase: UiSignInPhase) {
        val deadline = System.currentTimeMillis() + 5_000
        while (backend.authState().value.phase != phase && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(phase, backend.authState().value.phase, "never reached $phase")
    }

    private fun backend(accounts: StoreAccountService) = StoreAccounts(accounts)

    @Test
    fun offersOnlyTheProvidersTheBackendHas() {
        assertEquals(listOf("github"), backend(FakeAccounts()).authProviders())
        // No provider configured: the UI must not render a button that cannot succeed.
        assertEquals(emptyList(), backend(FakeAccounts(supported = emptyList())).authProviders())
        assertEquals(emptyList(), backend(StoreAccountService.Unsupported).authProviders())
    }

    @Test
    fun startsSignedOutAndWaitsOnTheBrowserWithoutClaimingProgress() {
        val b = backend(FakeAccounts())
        assertEquals(UiSignInPhase.SignedOut, b.authState().value.phase)

        val url = b.beginSignIn("github")
        assertEquals("https://provider/authorize?x=1", url, "the caller needs the URL to open")
        assertEquals(UiSignInPhase.AwaitingBrowser, b.authState().value.phase)
        assertNull(b.authState().value.account, "nobody is signed in while the browser is still open")
    }

    @Test
    fun completingTheRedirectSignsIn() {
        val b = backend(FakeAccounts())
        b.beginSignIn("github")
        b.completeSignIn("codeassist://auth-callback#access_token=t")
        awaitPhase(b, UiSignInPhase.SignedIn)
        val state = b.authState().value
        assertTrue(state.signedIn)
        assertEquals("user-1", state.account?.userId)
        // The label falls through to whatever is actually set; a fresh account has no handle yet.
        assertEquals("a@b.c", state.account?.label)
    }

    @Test
    fun aFailedExchangeKeepsTheBackendsOwnReason() {
        val b = backend(FakeAccounts(completion = StoreResult.Failed("That sign-in link has expired")))
        b.completeSignIn("codeassist://auth-callback#access_token=t")
        awaitPhase(b, UiSignInPhase.Failed)
        assertEquals("That sign-in link has expired", b.authState().value.message)
        assertNull(b.authState().value.account)
    }

    /** Offline is not a broken sign-in and must not read like one. */
    @Test
    fun anUnavailableProviderFailsWithoutAUrlToOpen() {
        val b = backend(FakeAccounts(challenge = StoreResult.Unavailable("No connection")))
        assertNull(b.beginSignIn("github"), "there is nothing to open")
        assertEquals(UiSignInPhase.Failed, b.authState().value.phase)
        assertEquals("No connection", b.authState().value.message)
    }

    @Test
    fun anUnknownProviderIsRefusedRatherThanSentToTheBackend() {
        val b = backend(FakeAccounts())
        assertNull(b.beginSignIn("facebook"))
        assertEquals(UiSignInPhase.SignedOut, b.authState().value.phase, "a typo must not look like a failure")
    }

    /** A stored session has to survive a relaunch, or every launch would present a signed-out store. */
    @Test
    fun anExistingSessionIsSignedInFromTheStart() {
        val b = backend(FakeAccounts(existing = StoreAccount("user-9", handle = "nordlys")))
        assertEquals(UiSignInPhase.SignedIn, b.authState().value.phase)
        assertEquals("nordlys", b.authState().value.account?.label)
    }

    @Test
    fun signingOutClearsTheAccount() {
        val accounts = FakeAccounts(existing = StoreAccount("user-9"))
        val b = backend(accounts)
        b.signOut()
        assertTrue(accounts.signedOut, "the port has to be told, not just the flow")
        assertEquals(UiSignInPhase.SignedOut, b.authState().value.phase)
        assertNull(b.authState().value.account)
    }
}
