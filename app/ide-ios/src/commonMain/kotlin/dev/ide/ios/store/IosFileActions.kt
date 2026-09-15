package dev.ide.ios.store

import dev.ide.store.StoreAuth
import dev.ide.ui.backend.FileActions
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The host's link handling.
 *
 * One method carries the whole sign-in round trip, and the branch inside it is the interesting part. A
 * sign-in URL is opened as an `ASWebAuthenticationSession`, which hands the redirect straight back here;
 * anything else is a plain link and goes to Safari. Deciding by URL rather than by adding a second method
 * to the port keeps the contract the same on every host — the store screens call `beginSignIn` and then
 * ask their host to open what it returns, exactly as they do on Android.
 *
 * The prefix matched is the authorize endpoint of the store's own project, not "any URL with a redirect
 * in it": an arbitrary link that happened to look like one must not be able to open an auth sheet.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileActions(
    private val authorizePrefix: String,
    private val onSignInRedirect: (String) -> Unit,
) : FileActions {

    private val auth = IosAuthSession(StoreAuth.SCHEME)

    override val canOpenUrl: Boolean = true

    override fun openUrl(url: String) {
        if (authorizePrefix.isNotBlank() && url.startsWith(authorizePrefix)) {
            auth.start(url, onSignInRedirect)
            return
        }
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target)
    }

    // Importing and sharing are the document-picker and share-sheet integrations this host has not built
    // yet; the affordances are hidden rather than offered and inert.
    override val canImport: Boolean = false

    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) = onImported(emptyList())

    override val canShare: Boolean = false

    override fun share(path: String) = Unit
}
