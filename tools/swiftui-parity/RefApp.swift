// The reference side of the parity harness: Apple's SwiftUI in the iOS Simulator. Shows each case of Cases.swift the
// way a #Preview shows a view (in a window the size of the device, inside its safe area) and saves a screenshot of the
// window as <Documents>/parity/<case>.png, at the device's scale. Then exits.

import SwiftUI
import UIKit

@main
final class ParityApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        self.window = window
        window.makeKeyAndVisible()
        let out = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("parity")
        try? FileManager.default.removeItem(at: out)
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let only = ProcessInfo.processInfo.environment["PARITY_CASE"]
        let cases = (parityCases() + glassCases() + glassMapCases()).filter { only == nil || $0.0 == only }
        capture(cases[...], window: window, into: out)
        return true
    }

    private func capture(_ cases: ArraySlice<(String, AnyView)>, window: UIWindow, into dir: URL) {
        guard let (name, view) = cases.first else {
            print("PARITY DONE \(dir.path)")
            // PARITY_HOLD keeps the last case on screen (for a real screenshot, status bar included).
            if ProcessInfo.processInfo.environment["PARITY_HOLD"] == nil { exit(0) }
            return
        }
        window.rootViewController = UIHostingController(rootView: view)
        // Two run-loop turns and a short wait: layout, then the render server.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            let format = UIGraphicsImageRendererFormat()
            format.scale = window.screen.scale
            format.opaque = true
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            try? image.pngData()?.write(to: dir.appendingPathComponent("\(name).png"))
            print("PARITY CASE \(name) \(window.bounds.width)x\(window.bounds.height) safe=\(window.safeAreaInsets)")
            self.capture(cases.dropFirst(), window: window, into: dir)
        }
    }
}
