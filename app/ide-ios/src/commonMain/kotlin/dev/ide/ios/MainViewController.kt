package dev.ide.ios

import androidx.compose.ui.window.ComposeUIViewController
import dev.ide.ui.CodeAssistApp
import platform.UIKit.UIViewController

/**
 * The iOS entry point, called from Swift: `IdeIosMainViewControllerKt.MainViewController()`.
 *
 * It mirrors what :ide-desktop and :ide-android do at their own entry points: build an `IdeBackend`, hand it
 * to the shared app. [IosBackend] is that backend — real files, real projects and the real Projects Store
 * over the app's own container, and no language intelligence, because everything behind that part of the
 * port is JVM code (the Kotlin compiler's PSI, JDT, ASM, D8) with no Kotlin/Native counterpart.
 *
 * The backend and the link handler are built together because sign-in needs both halves: the store hands
 * back a URL to open, and the browser hands back the redirect that finishes it.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    CodeAssistApp(
        backend = host.backend,
        fileActions = host.fileActions,
        openStoreItemId = host.pendingStoreItem,
        onStoreItemIdHandled = host::storeItemHandled,
    )
}

/**
 * The host, built once for the process rather than per composition.
 *
 * Swift needs to reach it to hand over a URL the system opened, and that can arrive before this
 * controller exists (a cold launch from a link) or while it is off screen.
 */
val host: IosHost by lazy { IosHost() }

/** Called from Swift when the app is opened with a URL. Returns whether it was one the app knows. */
fun handleDeepLink(url: String): Boolean = host.handleUrl(url)
