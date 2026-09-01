package dev.ide.store.impl

import dev.ide.store.StoreAuth
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The redirect definition, which three places have to agree on: the Android manifest's intent-filter, the
 * Supabase project's allow-list, and the activity that decides whether an incoming VIEW intent is a
 * sign-in or a file to open. Getting it wrong fails only at runtime, and only after a round trip through a
 * browser, so it is worth pinning here.
 */
class StoreAuthRedirectTest {

    @Test
    fun recognisesTheRedirectTheProviderSendsBack() {
        // Implicit flow: the session arrives in the fragment.
        assertTrue(StoreAuth.isAuthRedirect("codeassist://auth-callback#access_token=abc&refresh_token=d"))
        // PKCE: a code in the query instead.
        assertTrue(StoreAuth.isAuthRedirect("codeassist://auth-callback?code=abc"))
        // Bare, and with a trailing slash.
        assertTrue(StoreAuth.isAuthRedirect("codeassist://auth-callback"))
        assertTrue(StoreAuth.isAuthRedirect("codeassist://auth-callback/"))
        // Schemes are case-insensitive per RFC 3986, and some browsers normalise them.
        assertTrue(StoreAuth.isAuthRedirect("CODEASSIST://auth-callback#access_token=abc"))
    }

    @Test
    fun doesNotClaimIntentsThatBelongToTheFileImporter() {
        assertFalse(StoreAuth.isAuthRedirect(null))
        assertFalse(StoreAuth.isAuthRedirect(""))
        assertFalse(StoreAuth.isAuthRedirect("file:///sdcard/Download/thing.caproj"))
        assertFalse(StoreAuth.isAuthRedirect("content://media/external/file/1234"))
        // A different deep link into the same app is not a sign-in.
        assertFalse(StoreAuth.isAuthRedirect("codeassist://open-project?path=/x"))
        // And neither is a lookalike host on another scheme.
        assertFalse(StoreAuth.isAuthRedirect("https://auth-callback"))
    }

    /** The constant the manifest and the Supabase allow-list are written against. */
    @Test
    fun theAndroidRedirectIsBuiltFromTheSchemeAndHost() {
        assertTrue(StoreAuth.ANDROID_REDIRECT == "${StoreAuth.SCHEME}://${StoreAuth.HOST}")
        assertTrue(StoreAuth.isAuthRedirect(StoreAuth.ANDROID_REDIRECT))
    }
}

/**
 * The provider-refusal message, which is what a real GitHub App with no email permission produces.
 *
 * Kept as its own test because the raw GoTrue text ("Error getting user profile from external provider")
 * reads like the user's fault, and the whole point of the mapping is that it is not.
 */
class StoreAuthErrorMessageTest {

    private fun completeWith(errorDescription: String): String {
        val service = SupabaseAccountService(
            url = "https://example.supabase.co",
            apiKey = "publishable-key-for-the-test",
            redirectUrl = dev.ide.store.StoreAuth.ANDROID_REDIRECT,
        )
        val encoded = errorDescription.replace(" ", "+")
        val result = service.complete(
            "${dev.ide.store.StoreAuth.ANDROID_REDIRECT}#error=server_error&error_description=$encoded",
        )
        return (result as dev.ide.store.StoreResult.Failed).message
    }

    @Test
    fun aProviderThatWithholdsTheEmailIsExplainedAsConfigurationNotUserError() {
        val message = completeWith("Error getting user profile from external provider")
        kotlin.test.assertTrue(
            "configuration problem" in message,
            "the message should say it is not the user's fault: $message",
        )
        kotlin.test.assertTrue("email" in message.lowercase(), message)
    }

    @Test
    fun theEmailVariantOfTheSameFailureIsAlsoExplained() {
        val message = completeWith("Error getting user email from external provider")
        kotlin.test.assertTrue("configuration problem" in message, message)
    }

    /** Everything else keeps the backend's own words, which are usually the actionable ones. */
    @Test
    fun otherFailuresArePassedThroughUnchanged() {
        kotlin.test.assertEquals("access_denied", completeWith("access_denied"))
        kotlin.test.assertEquals(
            "Unable to exchange external code",
            completeWith("Unable to exchange external code"),
        )
    }
}
