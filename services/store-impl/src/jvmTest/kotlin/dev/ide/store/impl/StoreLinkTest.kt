package dev.ide.store.impl

import dev.ide.store.StoreAuth
import dev.ide.store.StoreLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store deep link, which the same three parties have to agree on as the sign-in redirect does: the
 * Android manifest's intent-filter, the activity deciding what an incoming VIEW intent is, and whatever
 * wrote the link (the moderation page, today). It shares a scheme with the sign-in redirect and is told
 * apart by host alone, so the two claim checks have to stay disjoint.
 */
class StoreLinkTest {

    @Test
    fun recognisesLinksIntoTheStore() {
        assertTrue(StoreLink.isStoreLink("codeprimer://store"))
        assertTrue(StoreLink.isStoreLink("codeprimer://store/"))
        assertTrue(StoreLink.isStoreLink("codeprimer://store/ktor-service"))
        assertTrue(StoreLink.isStoreLink("codeprimer://store?from=review"))
        // Schemes are case-insensitive per RFC 3986, and some browsers normalise them.
        assertTrue(StoreLink.isStoreLink("CODEASSIST://store/ktor-service"))
    }

    @Test
    fun doesNotClaimAnythingElseTheActivityHandles() {
        assertFalse(StoreLink.isStoreLink(null))
        assertFalse(StoreLink.isStoreLink(""))
        assertFalse(StoreLink.isStoreLink("file:///sdcard/Download/thing.caproj"))
        assertFalse(StoreLink.isStoreLink("content://media/external/file/1234"))
        assertFalse(StoreLink.isStoreLink("https://store"))
        // The host is matched whole, so a longer host starting with the same letters is not a store link.
        assertFalse(StoreLink.isStoreLink("codeprimer://storefront"))
    }

    /**
     * The two `codeprimer://` links must be disjoint in both directions. The activity tries the sign-in
     * redirect first, so an overlap would silently route a store link into the token exchange.
     */
    @Test
    fun theSignInRedirectAndTheStoreLinkNeverClaimEachOther() {
        assertFalse(StoreAuth.isAuthRedirect("codeprimer://store/ktor-service"))
        assertFalse(StoreLink.isStoreLink(StoreAuth.MOBILE_REDIRECT))
        assertFalse(StoreLink.isStoreLink("codeprimer://auth-callback#access_token=abc"))
    }

    @Test
    fun readsTheItemIdOffTheLink() {
        assertEquals("ktor-service", StoreLink.itemId("codeprimer://store/ktor-service"))
        assertEquals("ktor-service", StoreLink.itemId("codeprimer://store/ktor-service/"))
        assertEquals("ktor-service", StoreLink.itemId("codeprimer://store/ktor-service?from=review"))
        assertEquals("ktor-service", StoreLink.itemId("codeprimer://store/ktor-service#top"))
    }

    /** A link that names no item is the store itself, which the app reads as its Store tab. */
    @Test
    fun aLinkWithNoItemNamesTheStoreItself() {
        assertNull(StoreLink.itemId("codeprimer://store"))
        assertNull(StoreLink.itemId("codeprimer://store/"))
        assertNull(StoreLink.itemId("codeprimer://store?from=review"))
        assertNull(StoreLink.itemId("file:///sdcard/thing.caproj"))
    }

    /**
     * A path deeper than one segment is kept whole rather than being cut down to its first segment. No
     * item id contains a slash, so the result resolves to nothing and the app falls back to the Store tab;
     * taking the first segment would instead make a malformed link look like a valid one.
     */
    @Test
    fun aDeeperPathIsNotSilentlyTruncatedToSomethingValid() {
        assertEquals("a/b", StoreLink.itemId("codeprimer://store/a/b"))
    }

    @Test
    fun theLinkIsBuiltFromTheSchemeAndHost() {
        assertEquals("${StoreAuth.SCHEME}://${StoreLink.HOST}", StoreLink.PREFIX)
        assertEquals("codeprimer://store/kmp-starter", StoreLink.forItem("kmp-starter"))
        assertEquals("kmp-starter", StoreLink.itemId(StoreLink.forItem("kmp-starter")))
    }
}
