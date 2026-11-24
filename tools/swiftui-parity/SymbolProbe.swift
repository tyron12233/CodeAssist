// SF Symbol metrics, from iOS itself: for every symbol name in names.txt (bundled with the app), the size and
// alignment insets of its image at 100 pt, regular weight, medium scale, as UIKit (and so SwiftUI) lays it out.
// Sizes only: no artwork leaves the Simulator. Written to <Documents>/symbols.tsv:
//   name <TAB> width <TAB> height <TAB> insetTop <TAB> insetLeft <TAB> insetBottom <TAB> insetRight <TAB> baselineOffset
// The engine scales these by font size / 100 (sizes scale linearly with the point size: Probe.swift checked body to
// large title).

import UIKit

@main
final class SymbolProbe: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let names = (try? String(contentsOf: Bundle.main.url(forResource: "names", withExtension: "txt")!, encoding: .utf8))?
            .split(separator: "\n").map(String.init) ?? []
        let config = UIImage.SymbolConfiguration(pointSize: 100, weight: .regular, scale: .medium)
        var out = ""
        var missing = 0
        for name in names {
            guard let image = UIImage(systemName: name, withConfiguration: config) else { missing += 1; continue }
            let i = image.alignmentRectInsets
            out += "\(name)\t\(image.size.width)\t\(image.size.height)\t\(i.top)\t\(i.left)\t\(i.bottom)\t\(i.right)\t\(image.baselineOffsetFromBottom ?? 0)\n"
        }
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("symbols.tsv")
        try? out.write(to: url, atomically: true, encoding: .utf8)
        print("SYMBOLS \(names.count - missing) of \(names.count) -> \(url.path)")
        fflush(stdout)
        exit(0)
    }
}
