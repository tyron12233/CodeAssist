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
        let cases = (parityCases() + glassCases() + glassMapCases() + chromeCases() + darkCases()).filter { only == nil || $0.0 == only }
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
        window.overrideUserInterfaceStyle = name.hasSuffix("-dark") ? .dark : .light
        window.rootViewController = UIHostingController(rootView: view)
        // Two run-loop turns and a short wait: layout, then the render server. A sheet animates in first.
        let wait = name.hasPrefix("sheet") ? 1.5 : 0.6
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) {
            let format = UIGraphicsImageRendererFormat()
            format.scale = window.screen.scale
            format.opaque = true
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            try? image.pngData()?.write(to: dir.appendingPathComponent("\(name).png"))
            // PARITY_DUMP prints the UIKit view tree (frames in window points): the chrome SwiftUI hands to UIKit (tab
            // bars, navigation bars, sheets) has no SwiftUI frames to probe.
            if ProcessInfo.processInfo.environment["PARITY_DUMP"] != nil { dumpTree(window, in: window, depth: 0) }
            print("PARITY CASE \(name) \(window.bounds.width)x\(window.bounds.height) safe=\(window.safeAreaInsets)")
            self.capture(cases.dropFirst(), window: window, into: dir)
        }
    }
}

@MainActor
func dumpTree(_ view: UIView, in window: UIWindow, depth: Int) {
    let f = view.convert(view.bounds, to: window)
    var line = String(repeating: " ", count: depth) + "\(type(of: view)) \(fmt(f.minX)) \(fmt(f.minY)) \(fmt(f.width)) \(fmt(f.height))"
    if view.isHidden || view.alpha < 0.01 { line += " hidden" } else if view.alpha < 1 { line += " a=\(fmt(view.alpha))" }
    if let c = view.backgroundColor { line += " bg=\(hex(c))" }
    if view.layer.cornerRadius > 0 { line += " r=\(fmt(view.layer.cornerRadius))" }
    if let l = view as? UILabel {
        line += " text=\"\(l.text ?? "")\" font=\(l.font.fontName)/\(fmt(l.font.pointSize)) color=\(hex(l.textColor))"
    }
    if let i = view as? UIImageView, let img = i.image {
        line += " img=\(img.size.width)x\(img.size.height) sym=\(img.isSymbolImage) tint=\(hex(i.tintColor))"
        if let cfg = img.symbolConfiguration { line += " cfg=\(cfg)" }
    }
    print("TREE " + line)
    for sub in view.subviews { dumpTree(sub, in: window, depth: depth + 1) }
}
func fmt(_ v: CGFloat) -> String { String(format: "%.2f", v) }
@MainActor
func hex(_ c: UIColor) -> String {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    c.getRed(&r, green: &g, blue: &b, alpha: &a)
    return String(format: "%02X%02X%02X/%.2f", Int(round(r * 255)), Int(round(g * 255)), Int(round(b * 255)), a)
}
