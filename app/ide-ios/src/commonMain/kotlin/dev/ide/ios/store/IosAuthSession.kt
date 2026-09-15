package dev.ide.ios.store

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * The sign-in browser, as iOS wants it done.
 *
 * `ASWebAuthenticationSession` rather than opening Safari and waiting for a deep link back. It is what
 * Apple provides for exactly this, and it is better on both sides of the round trip: the callback URL is
 * handed straight to the completion handler, so nothing has to be routed through the app delegate and no
 * other URL the app opens can be mistaken for a sign-in; and the session's cookies are the user's real
 * Safari ones, so an account already signed in to GitHub does not have to type a password into a window
 * the app put on screen.
 *
 * The session is held for its lifetime on purpose: `ASWebAuthenticationSession` is released the moment
 * nothing references it, taking the sheet with it before the user has seen it.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAuthSession(private val callbackScheme: String) {

    private var session: ASWebAuthenticationSession? = null
    private val anchor = PresentationAnchor()

    /**
     * Open [url] and report the redirect the provider comes back with.
     *
     * [onRedirect] is not called when the user dismisses the sheet: a cancelled sign-in is not a failed
     * one, and reporting it as an error would leave a message on screen for something the user just chose.
     * Must be called on the main thread, which the tap that reaches it already is.
     */
    fun start(url: String, onRedirect: (String) -> Unit) {
        val target = NSURL.URLWithString(url) ?: return
        val session = ASWebAuthenticationSession(
            uRL = target,
            callbackURLScheme = callbackScheme,
            completionHandler = { callback, _ ->
                callback?.absoluteString?.let(onRedirect)
            },
        )
        session.presentationContextProvider = anchor
        this.session = session
        session.start()
    }

    /** Where the sheet is presented from: the app's own window. */
    private class PresentationAnchor :
        NSObject(),
        ASWebAuthenticationPresentationContextProvidingProtocol {

        override fun presentationAnchorForWebAuthenticationSession(
            session: ASWebAuthenticationSession,
        ): ASPresentationAnchor = UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }
}
