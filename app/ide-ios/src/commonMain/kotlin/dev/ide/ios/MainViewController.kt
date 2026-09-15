package dev.ide.ios

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.ide.ui.CodeAssistApp
import platform.UIKit.UIViewController

/**
 * The iOS entry point, called from Swift: `IdeIosMainViewControllerKt.MainViewController()`.
 *
 * It mirrors what :ide-desktop and :ide-android do at their own entry points: build an `IdeBackend`, hand it
 * to the shared app. [IosBackend] is that backend — real files and real projects over the app's Documents
 * container, and no language intelligence, because everything behind that part of the port is JVM code (the
 * Kotlin compiler's PSI, JDT, ASM, D8) with no Kotlin/Native counterpart.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    CodeAssistApp(backend = remember { IosBackend() })
}
