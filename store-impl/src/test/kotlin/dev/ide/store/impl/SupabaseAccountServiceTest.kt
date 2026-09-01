package dev.ide.store.impl

import dev.ide.store.StoreProvider
import dev.ide.store.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OAuth redirect is the part of sign-in most likely to break silently, because which shape arrives
 * depends on the Supabase project's settings: PKCE puts a `code` in the query string, the implicit flow
 * puts the tokens in the URL **fragment**. A reader that handled only one would pass every test against
 * one configuration and fail in production against the other, so both are pinned here.
 */
class SupabaseAccountServiceTest {

    @Test
    fun readsPkceCodeFromTheQueryString() {
        val p = SupabaseAccountService.redirectParams("codeassist://auth-callback?code=abc123&state=xyz")
        assertEquals("abc123", p["code"])
        assertEquals("xyz", p["state"])
        assertNull(p["access_token"])
    }

    @Test
    fun readsImplicitTokensFromTheFragment() {
        val p = SupabaseAccountService.redirectParams(
            "codeassist://auth-callback#access_token=head.body.sig&refresh_token=r-123&expires_in=3600&token_type=bearer",
        )
        assertEquals("head.body.sig", p["access_token"])
        assertEquals("r-123", p["refresh_token"])
        assertEquals("bearer", p["token_type"])
    }

    /** A loopback desktop redirect carries a path and a port; neither may confuse the parse. */
    @Test
    fun handlesLoopbackRedirectWithPathAndPort() {
        val p = SupabaseAccountService.redirectParams("http://127.0.0.1:8976/auth-callback?code=zzz")
        assertEquals("zzz", p["code"])
    }

    /** Query and fragment together — the shape some providers actually send. */
    @Test
    fun mergesQueryAndFragment() {
        val p = SupabaseAccountService.redirectParams("codeassist://cb?state=s1#access_token=t1&refresh_token=r1")
        assertEquals("s1", p["state"])
        assertEquals("t1", p["access_token"])
        assertEquals("r1", p["refresh_token"])
    }

    @Test
    fun urlDecodesValues() {
        val p = SupabaseAccountService.redirectParams("codeassist://cb?error_description=Email%20not%20confirmed")
        assertEquals("Email not confirmed", p["error_description"])
    }

    @Test
    fun emptyAndMalformedRedirectsYieldNothingRatherThanThrowing() {
        assertTrue(SupabaseAccountService.redirectParams("codeassist://auth-callback").isEmpty())
        assertTrue(SupabaseAccountService.redirectParams("").isEmpty())
        assertTrue(SupabaseAccountService.redirectParams("not a url at all").isEmpty())
        // A stray separator must not produce a blank-keyed entry.
        assertTrue(SupabaseAccountService.redirectParams("codeassist://cb?&&").isEmpty())
    }

    /** An OAuth error comes back on the redirect, not as an HTTP status, so it has to be surfaced. */
    @Test
    fun providerErrorOnTheRedirectBecomesAFailure() {
        val svc = service()
        val r = svc.complete("codeassist://auth-callback?error=access_denied&error_description=User%20cancelled")
        assertTrue(r is StoreResult.Failed, "expected Failed, got $r")
        assertEquals("User cancelled", (r as StoreResult.Failed).message)
    }

    @Test
    fun redirectWithNeitherCodeNorTokenIsAFailure() {
        val r = service().complete("codeassist://auth-callback?state=only")
        assertTrue(r is StoreResult.Failed)
    }

    @Test
    fun authorizeUrlNamesTheProviderAndEncodesTheRedirect() {
        val r = service().begin(StoreProvider.GITHUB)
        val url = (r as StoreResult.Ok).value.authorizeUrl
        assertTrue(url.startsWith("https://example.supabase.co/auth/v1/authorize?"), url)
        assertTrue(url.contains("provider=github"), url)
        // The custom scheme must survive as an encoded parameter, not be pasted in raw.
        assertTrue(url.contains("redirect_to=codeassist%3A%2F%2Fauth-callback"), url)
    }

    @Test
    fun googleIsTheOtherSupportedProvider() {
        val url = (service().begin(StoreProvider.GOOGLE) as StoreResult.Ok).value.authorizeUrl
        assertTrue(url.contains("provider=google"), url)
    }

    @Test
    fun unconfiguredServiceOffersNothingAndDoesNotThrow() {
        val svc = SupabaseAccountService(url = "", apiKey = "", redirectUrl = "")
        assertTrue(!svc.authAvailable())
        assertNull(svc.current())
        assertTrue(svc.begin(StoreProvider.GITHUB) is StoreResult.Unavailable)
        assertTrue(svc.complete("codeassist://cb?code=x") is StoreResult.Unavailable)
        svc.signOut() // must not throw
    }

    /** Signing out clears the local session even when the server call cannot be made. */
    @Test
    fun signOutClearsTheStoredRefreshToken() {
        val store = StoreTokenStore.inMemory()
        store.write("r-persisted")
        val svc = SupabaseAccountService(
            url = "https://example.invalid", apiKey = "k", redirectUrl = "codeassist://cb", tokens = store,
        )
        svc.signOut()
        assertNull(store.read(), "a signed-out client must not keep a refresh token")
    }

    /** An in-memory store is the default precisely so nothing is written to disk unasked. */
    @Test
    fun defaultTokenStoreIsMemoryOnly() {
        val a = StoreTokenStore.inMemory()
        assertNull(a.read())
        a.write("x")
        assertEquals("x", a.read())
        assertNull(StoreTokenStore.inMemory().read(), "a fresh store must not see another's token")
    }

    private fun service() = SupabaseAccountService(
        url = "https://example.supabase.co",
        apiKey = "sb_publishable_test",
        redirectUrl = "codeassist://auth-callback",
    )
}
