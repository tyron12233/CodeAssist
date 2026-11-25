// Parity cases for the views the API audit found missing (Picker, Menu, grids, TextEditor, DatePicker, ...). Same rules
// as Cases.swift: deterministic, one screen each.

#if canImport(UIKit)
import SwiftUI
#endif

struct CardStyle: ViewModifier {
    @State private var pressed = false
    func body(content: Content) -> some View {
        content
            .padding()
            .background(RoundedRectangle(cornerRadius: 12).fill(.blue.opacity(0.15)))
    }
}

struct WidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

@MainActor
func apiCases() -> [(String, AnyView)] {
    let date = Date(timeIntervalSince1970: 1_700_000_000)
    return [
        ("picker-form", AnyView(Form {
            Picker("Flavor", selection: .constant(1)) {
                Text("Vanilla").tag(1)
                Text("Chocolate").tag(2)
            }
            Picker("Size", selection: .constant("M")) {
                Text("Small").tag("S")
                Text("Medium").tag("M")
            }
        })),
        ("picker-segmented", AnyView(VStack(spacing: 20) {
            Picker("Mode", selection: .constant(1)) {
                Text("Day").tag(0)
                Text("Week").tag(1)
                Text("Month").tag(2)
            }
            .pickerStyle(.segmented)
            Picker("Two", selection: .constant("a")) {
                Text("First").tag("a")
                Text("Second").tag("b")
            }
            .pickerStyle(.segmented)
        }.padding())),
        ("picker-standalone", AnyView(Picker("Sort", selection: .constant(0)) {
            Text("Name").tag(0)
            Text("Date").tag(1)
        })),
        ("menu", AnyView(VStack(spacing: 30) {
            Menu("Options") {
                Button("Rename") {}
                Button("Delete", role: .destructive) {}
            }
            Menu {
                Button("Share") {}
            } label: {
                Label("More", systemImage: "ellipsis.circle")
            }
        })),
        ("lazyvgrid", AnyView(ScrollView {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                ForEach(0..<9, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 12).fill(i % 2 == 0 ? Color.blue : Color.orange).frame(height: 80)
                }
            }
            .padding()
        })),
        ("lazyvgrid-adaptive", AnyView(ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 80))], spacing: 8) {
                ForEach(0..<10, id: \.self) { i in
                    Text("\(i)").frame(maxWidth: .infinity, minHeight: 60).background(Color.green.opacity(0.3))
                }
            }
            .padding()
        })),
        ("lazyhgrid", AnyView(ScrollView(.horizontal) {
            LazyHGrid(rows: [GridItem(.fixed(60)), GridItem(.fixed(60))], spacing: 10) {
                ForEach(0..<8, id: \.self) { _ in
                    Circle().fill(.purple).frame(width: 60)
                }
            }
            .padding()
        })),
        ("grid", AnyView(Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 12) {
            GridRow {
                Text("Name").bold()
                Text("Score").bold()
            }
            Divider()
            GridRow {
                Text("Ada")
                Text("98")
            }
            GridRow {
                Text("Grace Hopper")
                Text("100")
            }
        }.padding())),
        ("texteditor", AnyView(TextEditor(text: .constant("Notes go here.\nSecond line."))
            .frame(height: 200)
            .padding())),
        ("datepicker", AnyView(Form {
            DatePicker("Date", selection: .constant(date), displayedComponents: .date)
            DatePicker("Time", selection: .constant(date), displayedComponents: .hourAndMinute)
        }.environment(\.timeZone, TimeZone(identifier: "UTC")!))),
        ("content-unavailable", AnyView(ContentUnavailableView("No Results", systemImage: "magnifyingglass",
                                                              description: Text("Try a different search.")))),
        ("labeled-content", AnyView(Form {
            LabeledContent("Version", value: "1.0")
            LabeledContent("Build") { Text("42") }
        })),
        ("groupbox", AnyView(GroupBox("Activity") {
            Text("10,000 steps today")
        }.padding())),
        ("disclosure", AnyView(Form {
            DisclosureGroup("Advanced", isExpanded: .constant(true)) {
                Text("Option A")
                Text("Option B")
            }
            DisclosureGroup("Closed", isExpanded: .constant(false)) {
                Text("Hidden")
            }
        })),
        ("view-that-fits", AnyView(VStack(spacing: 20) {
            ViewThatFits {
                Text("A very long title that cannot fit in a narrow box")
                Text("Short title")
            }
            .frame(width: 150)
            .border(.gray)
            ViewThatFits {
                Text("Fits fine")
                Text("Fallback")
            }
            .border(.gray)
        })),
        ("gauge", AnyView(VStack(spacing: 30) {
            Gauge(value: 0.4) { Text("Fuel") }
            Gauge(value: 70, in: 0...100) { Text("Speed") } currentValueLabel: { Text("70") }
        }.padding())),
        ("share-link", AnyView(ShareLink(item: URL(string: "https://example.com")!))),
        ("color-picker", AnyView(Form {
            ColorPicker("Accent", selection: .constant(Color.red))
        })),
        ("control-group", AnyView(ControlGroup {
            Button("Cut") {}
            Button("Copy") {}
            Button("Paste") {}
        }.padding())),
        ("edit-button", AnyView(NavigationStack {
            List {
                Text("Alpha")
                Text("Beta")
            }
            .navigationTitle("Items")
            .toolbar { EditButton() }
        })),
        ("async-image", AnyView(AsyncImage(url: nil) { image in
            image.resizable()
        } placeholder: {
            Color.gray.opacity(0.3)
        }
        .frame(width: 200, height: 200))),
        ("compat-modifiers", AnyView(VStack(spacing: 20) {
            Text("Custom modifier").modifier(CardStyle())
            Text("Tap gesture")
                .gesture(TapGesture().onEnded {})
                .simultaneousGesture(LongPressGesture().onEnded { _ in })
            Image(systemName: "bell").symbolEffect(.bounce, value: 1).contentTransition(.symbolEffect(.replace))
            Text("Material").padding().background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
            Text("Preference").preference(key: WidthKey.self, value: 10).onPreferenceChange(WidthKey.self) { _ in }
            Text("No hits").allowsHitTesting(false).accessibilityAddTraits(.isHeader)
            Text("Hidden labels").labelsHidden().scrollTargetLayout()
        })),
        ("compat-position", AnyView(ZStack {
            Circle().fill(.red).frame(width: 40, height: 40).position(x: 100, y: 120)
            Text("Positioned").position(x: 250, y: 300)
        })),
        ("canvas", AnyView(Canvas { context, size in
            context.fill(Path(ellipseIn: CGRect(x: 20, y: 20, width: 120, height: 120)), with: .color(.orange))
            context.stroke(Path(CGRect(x: 160, y: 20, width: 120, height: 120)), with: .color(.blue), lineWidth: 6)
            var shifted = context
            shifted.translateBy(x: 0, y: 160)
            shifted.fill(Path(roundedRect: CGRect(x: 20, y: 20, width: 260, height: 60), cornerRadius: 12), with: .color(.green))
            context.draw(Text("Canvas"), at: CGPoint(x: 150, y: 300))
        }
        .frame(width: 300, height: 340))),
        ("split-view", AnyView(NavigationSplitView {
            List {
                Text("Inbox")
                Text("Sent")
            }
            .navigationTitle("Mail")
        } detail: {
            Text("Select a mailbox")
        })),
    ]
}
