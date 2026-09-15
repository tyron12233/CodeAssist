package dev.ide.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ide.ios.store.IosFileActions
import dev.ide.ios.store.StoreConfig
import dev.ide.store.StoreAuth
import dev.ide.store.StoreLink
import dev.ide.ui.backend.FileActions

/**
 * What this host is made of: a backend, and the link handling the backend cannot do for itself.
 *
 * They are built together because sign-in is a round trip through both. The store produces an authorize
 * URL, only the host can open a browser, and the redirect that comes back has to reach the store again —
 * so whoever owns one has to own the other.
 */
class IosHost {

    val backend: IosBackend = IosBackend()

    /**
     * The store item a `codeassist://store/<id>` link asked for, or "" for the Store tab itself.
     *
     * Null until a link arrives and again once the app has acted on it: the same id arriving twice has to
     * re-fire, a link being tapped twice being the ordinary case.
     */
    var pendingStoreItem: String? by mutableStateOf(null)
        private set

    /**
     * A URL the system handed the app.
     *
     * Two kinds reach here and they are told apart by host, not by parsing: `codeassist://store[/<id>]`
     * opens the store, and a sign-in redirect is claimed by whoever is waiting for it. The sign-in case is
     * a fallback — `ASWebAuthenticationSession` normally hands the redirect straight back to the caller
     * without the system ever routing it — but a provider that bounces through Safari still lands here,
     * and dropping it would leave the user looking at a store that never noticed they signed in.
     */
    fun handleUrl(url: String): Boolean = when {
        StoreAuth.isAuthRedirect(url) -> {
            backend.store.completeSignIn(url)
            true
        }
        StoreLink.isStoreLink(url) -> {
            pendingStoreItem = StoreLink.itemId(url) ?: ""
            true
        }
        else -> false
    }

    /** Called once the app has acted on [pendingStoreItem]. */
    fun storeItemHandled() {
        pendingStoreItem = null
    }

    val fileActions: FileActions = IosFileActions(
        // Only this project's authorize endpoint opens an auth sheet; every other link goes to Safari.
        authorizePrefix = if (StoreConfig.SUPABASE_URL.isBlank()) {
            ""
        } else {
            "${StoreConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/authorize"
        },
        onSignInRedirect = { redirect -> backend.store.completeSignIn(redirect) },
    )
}
