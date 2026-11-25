// SF Symbol metrics, from iOS itself: for every symbol name in names.txt (bundled with the app; the Mac's
// CoreGlyphs name_availability.plist), the frame of its image at each Dynamic Type point size, regular weight, medium
// scale. Sizes only: no artwork leaves the Simulator. Written to <Documents>/symbol-metrics.tsv, which becomes
// samples/swift-plugin/swiftui/symbol-metrics.tsv (the engine reads it; sizes between those measured interpolate).

import UIKit

@main
final class SymbolProbe: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let names = (try? String(contentsOf: Bundle.main.url(forResource: "names", withExtension: "txt")!, encoding: .utf8))?
            .split(separator: "\n").map(String.init) ?? []
        // Sizes in thirds of a point (an iPhone's pixel), per point size, as UIKit sizes the symbol's image, which is the
        // frame SwiftUI gives Image(systemName:) (checked against SwiftUI's own frames for star, heart.fill,
        // chevron.right and gearshape at 12, 17 and 34 pt).
        let sizes: [CGFloat] = [11, 12, 13, 15, 16, 17, 20, 22, 28, 34]
        let configs = sizes.map { UIImage.SymbolConfiguration(pointSize: $0, weight: .regular, scale: .medium) }
        var out = "# SF Symbol frames by point size (\(sizes.map { "\(Int($0))" }.joined(separator: ","))): w,h,baseline in thirds of a point\n"
        var missing = 0
        for name in names {
            var cells: [String] = []
            for config in configs {
                guard let image = UIImage(systemName: name, withConfiguration: config) else { break }
                let t = { (v: CGFloat) in Int((v * 3).rounded()) }
                cells.append("\(t(image.size.width)),\(t(image.size.height)),\(t(image.baselineOffsetFromBottom ?? 0))")
            }
            if cells.count != sizes.count { missing += 1; continue }
            out += name + "\t" + cells.joined(separator: " ") + "\n"
        }
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("symbol-metrics.tsv")
        try? out.write(to: url, atomically: true, encoding: .utf8)
        print("SYMBOLS \(names.count - missing) of \(names.count) -> \(url.path)")
        fflush(stdout)
        exit(0)
    }
}
