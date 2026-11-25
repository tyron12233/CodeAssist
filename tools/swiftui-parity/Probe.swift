// Calibration probe: real SwiftUI in the Simulator prints the frames of views laid out a given way, so the engine's
// constants (default spacings, control sizes, symbol boxes) are measured rather than guessed. Output lines:
//   PROBE <name> <x> <y> <w> <h>

import SwiftUI
import UIKit

struct Report: ViewModifier {
    let name: String
    func body(content: Content) -> some View {
        content.background(GeometryReader { p in
            Color.clear.onAppear {
                let f = p.frame(in: .global)
                print("PROBE \(name) \(f.minX) \(f.minY) \(f.width) \(f.height)")
            }
        })
    }
}

extension View {
    func report(_ name: String) -> some View { modifier(Report(name: name)) }
}

let styles: [(String, Font)] = [
    ("largeTitle", .largeTitle), ("title", .title), ("title2", .title2), ("title3", .title3), ("headline", .headline),
    ("body", .body), ("callout", .callout), ("subheadline", .subheadline), ("footnote", .footnote), ("caption", .caption),
    ("caption2", .caption2),
]

struct ListProbe: View {
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Apples").report("list.row1")
                    Text("Bananas").report("list.row2")
                    Toggle("Wi-Fi", isOn: .constant(true)).report("list.toggle")
                    NavigationLink("General") { Text("x") }.report("list.link")
                    Label("Favorites", systemImage: "star").report("list.label")
                } header: {
                    Text("Fruit").report("list.header")
                } footer: {
                    Text("Fresh every morning.").report("list.footer")
                }
                Section("Second") {
                    Text("Carrots").report("list.row3")
                }
            }
            .navigationTitle("Settings")
            .background(GeometryReader { p in Color.clear.onAppear { let f = p.frame(in: .global); print("PROBE list.frame \(f.minX) \(f.minY) \(f.width) \(f.height)") } })
        }
    }
}

struct ProbeView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 40) {
                ForEach(styles, id: \.0) { name, font in
                    VStack {
                        Text("Ag").font(font).report("ln.\(name).1")
                        Text("Ag\nAg").font(font).report("ln.\(name).2")
                        Text("Ag\nAg\nAg").font(font).report("ln.\(name).3")
                    }
                }
                ForEach([12, 17, 34], id: \.self) { (n: Int) in
                    VStack {
                        Text("Ag").font(.system(size: CGFloat(n))).report("ln.s\(n).1")
                        Text("Ag\nAg").font(.system(size: CGFloat(n))).report("ln.s\(n).2")
                        Text("Ag\nAg\nAg").font(.system(size: CGFloat(n))).report("ln.s\(n).3")
                    }
                }
                ForEach([8, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24, 28, 34, 40, 48, 60], id: \.self) { (n: Int) in
                    VStack {
                        Rectangle().frame(width: 10, height: 10).report("sp.\(n).r0")
                        Text("Ag").font(.system(size: CGFloat(n))).report("sp.\(n).t1")
                        Text("Ag").font(.system(size: CGFloat(n))).report("sp.\(n).t2")
                        Rectangle().frame(width: 10, height: 10).report("sp.\(n).r3")
                    }
                }
                // Text-to-text default spacing in a VStack, for each style with itself and with body.
                ForEach(styles, id: \.0) { name, font in
                    VStack {
                        Text("Ag").font(font).report("vs.\(name).a")
                        Text("Ag").font(font).report("vs.\(name).b")
                        Text("Ag").font(.body).report("vs.\(name).body")
                    }
                }
                VStack {
                    Text("Ag").report("vs.text-rect.a")
                    Rectangle().frame(width: 10, height: 10).report("vs.text-rect.b")
                    Rectangle().frame(width: 10, height: 10).report("vs.rect-rect.c")
                    Text("Ag").report("vs.rect-text.d")
                }
                HStack {
                    Text("Ag").report("hs.text-text.a")
                    Text("Ag").report("hs.text-text.b")
                    Rectangle().frame(width: 10, height: 10).report("hs.text-rect.c")
                    Rectangle().frame(width: 10, height: 10).report("hs.rect-rect.d")
                    Image(systemName: "star").report("hs.rect-image.e")
                }
                HStack {
                    Text("A").report("hs.spacer.a")
                    Spacer().report("hs.spacer.s")
                    Text("B").report("hs.spacer.b")
                }.frame(width: 200)
                VStack {
                    Button("Automatic") {}.report("btn.automatic")
                    Button("Bordered") {}.buttonStyle(.bordered).report("btn.bordered")
                    Button("Prominent") {}.buttonStyle(.borderedProminent).report("btn.prominent")
                    Text("Bordered").report("btn.label")
                    Button("Small") {}.buttonStyle(.bordered).controlSize(.small).report("btn.bordered.small")
                    Button("Large") {}.buttonStyle(.borderedProminent).controlSize(.large).report("btn.prominent.large")
                }
                VStack {
                    Toggle("Wi-Fi", isOn: .constant(true)).report("toggle.row")
                    Toggle("", isOn: .constant(true)).labelsHidden().report("toggle.switch")
                    Slider(value: .constant(0.5)).report("slider")
                    Stepper("Step", value: .constant(1)).report("stepper")
                    ProgressView(value: 0.5).report("progress")
                    Divider().report("divider")
                    TextField("Name", text: .constant("")).report("textfield")
                }.padding()
                ForEach(styles, id: \.0) { name, font in
                    HStack {
                        Image(systemName: "star").font(font).report("sym.star.\(name)")
                        Image(systemName: "heart.fill").font(font).report("sym.heart.\(name)")
                        Image(systemName: "chevron.right").font(font).report("sym.chevron.\(name)")
                        Image(systemName: "gearshape").font(font).report("sym.gear.\(name)")
                        Text("Ag").font(font).report("sym.text.\(name)")
                    }
                }
                Label("Label", systemImage: "star").report("label")
            }
        }
    }
}

@main
final class ProbeApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        self.window = window
        let which = ProcessInfo.processInfo.environment["PROBE"] ?? ""
        window.rootViewController = which == "list" ? UIHostingController(rootView: AnyView(ListProbe())) : UIHostingController(rootView: AnyView(ProbeView()))
        window.makeKeyAndVisible()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            print("PROBE DONE")
            fflush(stdout)
            exit(0)
        }
        return true
    }
}
