import SwiftUI
import UIKit
import CodeAssistUi

// The Kotlin side owns everything above this line: `MainViewController()` builds the Compose
// `UIViewController` that renders `CodeAssistApp`. SwiftUI's only job is to put it on screen.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct CodeAssistApp: App {
    init() {
        // A build that embeds a JVM hands it over before the UI exists: the layout editor is mounted when the
        // UI is first composed, and only a host with a JVM backend has one to mount.
        if let jvm = JvmHost.start() as? EmbeddedJvm {
            MainViewControllerKt.attachEmbeddedJvm(jvm: jvm)
        }
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                // The IDE draws its own chrome and reads the safe-area insets itself, as it does on Android.
                .ignoresSafeArea(.all)
                // `codeassist://store/<id>` opens that project's page; `codeassist://auth-callback` is a
                // sign-in coming back. Kotlin tells the two apart -- the same code the Android activity
                // runs -- so this only has to hand the URL over.
                .onOpenURL { url in
                    _ = MainViewControllerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}
