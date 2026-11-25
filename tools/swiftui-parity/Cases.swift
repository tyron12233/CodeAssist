// Parity cases: the SAME source is compiled against Apple's SwiftUI (RefApp.swift, in the iOS Simulator) and against
// CodeAssist's engine (Ours.swift, on the Mac), and the two renderings are compared pixel by pixel.
//
// Each case is one screen's worth of a SwiftUI feature. Keep them deterministic: no dates, no randomness.

#if canImport(UIKit)
import SwiftUI
#endif

@MainActor
func parityCases() -> [(String, AnyView)] {
    [
        ("text-body", AnyView(Text("Hello, world!"))),
        ("text-styles", AnyView(VStack(alignment: .leading) {
            Text("Large Title").font(.largeTitle)
            Text("Title").font(.title)
            Text("Title 2").font(.title2)
            Text("Title 3").font(.title3)
            Text("Headline").font(.headline)
            Text("Body").font(.body)
            Text("Callout").font(.callout)
            Text("Subheadline").font(.subheadline)
            Text("Footnote").font(.footnote)
            Text("Caption").font(.caption)
            Text("Caption 2").font(.caption2)
        })),
        ("text-wrap", AnyView(Text("SwiftUI wraps long text onto as many lines as it needs, within the width it is offered by its parent view.")
            .padding())),
        ("vstack-default-spacing", AnyView(VStack {
            Text("One")
            Text("Two")
            Rectangle().fill(.blue).frame(width: 100, height: 40)
            Rectangle().fill(.green).frame(width: 60, height: 40)
            Text("Three")
        })),
        ("hstack-spacer", AnyView(HStack {
            Text("Leading")
            Spacer()
            Text("Trailing")
        }.padding())),
        ("padding-background", AnyView(Text("Padded")
            .padding()
            .background(.yellow)
            .padding(8)
            .background(.orange))),
        ("frames", AnyView(VStack(spacing: 12) {
            Text("max width leading").frame(maxWidth: .infinity, alignment: .leading).background(.mint)
            Text("fixed 200x60").frame(width: 200, height: 60).background(.cyan)
            Text("min width 250").frame(minWidth: 250).background(.pink)
            Text("bottom trailing").frame(width: 300, height: 80, alignment: .bottomTrailing).background(.indigo.opacity(0.3))
        })),
        ("zstack-align", AnyView(ZStack(alignment: .topLeading) {
            Rectangle().fill(.blue).frame(width: 200, height: 200)
            Rectangle().fill(.red).frame(width: 80, height: 80)
            Text("ZStack").foregroundStyle(.white).padding(4)
        })),
        ("shapes", AnyView(VStack(spacing: 16) {
            RoundedRectangle(cornerRadius: 24).fill(.blue).frame(width: 200, height: 100)
            Circle().fill(.red).frame(width: 80, height: 80)
            Capsule().fill(.green).frame(width: 160, height: 44)
            RoundedRectangle(cornerRadius: 12).stroke(.purple, lineWidth: 4).frame(width: 120, height: 60)
        })),
        ("colors", AnyView(VStack(spacing: 0) {
            HStack(spacing: 0) {
                Color.red; Color.orange; Color.yellow; Color.green; Color.mint; Color.teal
            }
            HStack(spacing: 0) {
                Color.cyan; Color.blue; Color.indigo; Color.purple; Color.pink; Color.brown
            }
            HStack(spacing: 0) {
                Color.gray; Color.black; Color.primary; Color.secondary; Color.white; Color.accentColor
            }
        }.frame(height: 300))),
        ("buttons", AnyView(VStack(spacing: 20) {
            Button("Automatic") {}
            Button("Bordered") {}.buttonStyle(.bordered)
            Button("Prominent") {}.buttonStyle(.borderedProminent)
            Button("Destructive", role: .destructive) {}
            Button("Plain") {}.buttonStyle(.plain)
        })),
        ("toggle", AnyView(VStack {
            Toggle("Wi-Fi", isOn: .constant(true))
            Toggle("Bluetooth", isOn: .constant(false))
        }.padding())),
        ("weights", AnyView(VStack(alignment: .leading, spacing: 4) {
            Text("Ultralight").fontWeight(.ultraLight)
            Text("Thin").fontWeight(.thin)
            Text("Light").fontWeight(.light)
            Text("Regular").fontWeight(.regular)
            Text("Medium").fontWeight(.medium)
            Text("Semibold").fontWeight(.semibold)
            Text("Bold").fontWeight(.bold)
            Text("Heavy").fontWeight(.heavy)
            Text("Black").fontWeight(.black)
        }.font(.title2))),
        ("card", AnyView(VStack(alignment: .leading, spacing: 8) {
            Text("Weekly summary").font(.headline)
            Text("You walked 42 km this week, 12% more than last week.").font(.subheadline).foregroundStyle(.secondary)
            HStack {
                Text("42 km").font(.title.bold())
                Spacer()
                Text("+12%").foregroundStyle(.green)
            }
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.gray.opacity(0.15)))
        .padding())),
        ("symbols", AnyView(VStack(spacing: 16) {
            HStack(spacing: 20) {
                Image(systemName: "star.fill"); Image(systemName: "heart"); Image(systemName: "gearshape")
                Image(systemName: "chevron.right"); Image(systemName: "person.crop.circle")
            }
            HStack(spacing: 20) {
                Image(systemName: "star.fill"); Image(systemName: "heart"); Image(systemName: "gearshape")
            }.font(.largeTitle)
            Label("Favorites", systemImage: "star")
            Label("Settings", systemImage: "gearshape").font(.title3)
        })),
        ("list-plain-rows", AnyView(List {
            Text("Apples")
            Text("Bananas")
            Text("Cherries")
        })),
        ("list-sections", AnyView(List {
            Section("Fruit") {
                Text("Apples")
                Text("Bananas")
            }
            Section {
                Text("Carrots")
            } header: {
                Text("Vegetables")
            } footer: {
                Text("Fresh every morning.")
            }
        })),
        ("form-toggles", AnyView(Form {
            Section("Connectivity") {
                Toggle("Wi-Fi", isOn: .constant(true))
                Toggle("Bluetooth", isOn: .constant(false))
            }
            Section {
                Text("Version 1.0")
            }
        })),
        ("list-labels", AnyView(List {
            Label("Favorites", systemImage: "star.fill")
            Label("Privacy", systemImage: "lock.fill")
            Label("Wi-Fi", systemImage: "wifi")
            Label("Settings", systemImage: "gearshape")
        })),
        ("navigation-label-links", AnyView(NavigationStack {
            List {
                Section {
                    NavigationLink { Text("General") } label: { Label("General", systemImage: "gearshape") }
                    NavigationLink { Text("Privacy") } label: { Label("Privacy", systemImage: "lock.fill") }
                    NavigationLink { Text("Favorites") } label: { Label("Favorites", systemImage: "star.fill") }
                } footer: {
                    Text("Changes apply right away.")
                }
            }
            .navigationTitle("Settings")
        })),
        ("navigation-title", AnyView(NavigationStack {
            Text("Content")
                .navigationTitle("Inbox")
        })),
        ("navigation-list", AnyView(NavigationStack {
            List {
                NavigationLink("General") { Text("General") }
                NavigationLink("Privacy") { Text("Privacy") }
                NavigationLink("About") { Text("About") }
            }
            .navigationTitle("Settings")
        })),
    ]
}
