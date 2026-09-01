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
