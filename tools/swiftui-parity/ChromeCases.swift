// Parity cases for iOS 26's app chrome: the floating glass tab bar, navigation bar items and inline titles, bottom
// toolbars and sheets. Same rules as Cases.swift: deterministic, one screen each.

#if canImport(UIKit)
import SwiftUI
#endif

@MainActor
func chromeCases() -> [(String, AnyView)] {
    [
        ("tab-basic", AnyView(TabView {
            Tab("Home", systemImage: "house") { Text("Home screen") }
            Tab("Search", systemImage: "magnifyingglass") { Text("Search screen") }
            Tab("Settings", systemImage: "gearshape") { Text("Settings screen") }
        })),
        ("tab-legacy-items", AnyView(TabView {
            Text("Inbox").tabItem { Label("Inbox", systemImage: "tray") }
            Text("Sent").tabItem { Label("Sent", systemImage: "paperplane") }
        })),
        ("tab-selection", AnyView(TabView(selection: .constant(2)) {
            Tab("Home", systemImage: "house", value: 1) { Text("Home") }
            Tab("Library", systemImage: "books.vertical", value: 2) { Text("Library") }
            Tab("Profile", systemImage: "person.crop.circle", value: 3) { Text("Profile") }
            Tab("More", systemImage: "ellipsis", value: 4) { Text("More") }
        })),
        ("tab-five", AnyView(TabView {
            Tab("Listen Now", systemImage: "play.circle") { Text("Listen") }
            Tab("Browse", systemImage: "square.grid.2x2") { Text("Browse") }
            Tab("Radio", systemImage: "dot.radiowaves.left.and.right") { Text("Radio") }
            Tab("Library", systemImage: "music.note.list") { Text("Library") }
            Tab("Downloads", systemImage: "arrow.down.circle") { Text("Downloads") }
        })),
        ("tab-one", AnyView(TabView {
            Tab("Only", systemImage: "star") { Text("Only tab") }
        })),
        ("tab-list", AnyView(TabView {
            Tab("Settings", systemImage: "gearshape") {
                NavigationStack {
                    List {
                        Text("General")
                        Text("Privacy")
                    }
                    .navigationTitle("Settings")
                }
            }
            Tab("About", systemImage: "info.circle") { Text("About") }
        })),
        ("toolbar-trailing-icon", AnyView(NavigationStack {
            Text("Content")
                .navigationTitle("Notes")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Add", systemImage: "plus") {}
                    }
                }
        })),
        ("toolbar-text-buttons", AnyView(NavigationStack {
            Text("Content")
                .navigationTitle("Edit")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") {} }
                    ToolbarItem(placement: .confirmationAction) { Button("Done") {} }
                }
        })),
        ("toolbar-two-icons", AnyView(NavigationStack {
            Text("Content")
                .navigationTitle("Photos")
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("Share", systemImage: "square.and.arrow.up") {}
                        Button("More", systemImage: "ellipsis") {}
                    }
                }
        })),
        ("title-inline", AnyView(NavigationStack {
            Text("Content")
                .navigationTitle("Details")
                .navigationBarTitleDisplayMode(.inline)
        })),
        ("title-inline-toolbar", AnyView(NavigationStack {
            List {
                Text("One")
                Text("Two")
            }
            .navigationTitle("Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close", systemImage: "xmark") {} }
                ToolbarItem(placement: .topBarTrailing) { Button("Save") {} }
            }
        })),
        ("toolbar-bottom", AnyView(NavigationStack {
            Text("Content")
                .navigationTitle("Mail")
                .toolbar {
                    ToolbarItemGroup(placement: .bottomBar) {
                        Button("Filter", systemImage: "line.3.horizontal.decrease") {}
                        Spacer()
                        Button("Compose", systemImage: "square.and.pencil") {}
                    }
                }
        })),
        ("sheet-large", AnyView(Text("Behind the sheet")
            .sheet(isPresented: .constant(true)) {
                Text("Sheet content")
            })),
        ("sheet-medium", AnyView(Text("Behind the sheet")
            .sheet(isPresented: .constant(true)) {
                Text("Half sheet")
                    .presentationDetents([.medium])
            })),
        ("text-inline-symbols", AnyView(VStack(alignment: .leading, spacing: 16) {
            Text("\(Image(systemName: "star.fill")) Favorites")
            Text("Battery \(Image(systemName: "battery.100")) full").font(.title)
            Text(Image(systemName: "heart.fill")) + Text(" Loved by \(Image(systemName: "person.2.fill")) friends")
            Text("Tap \(Image(systemName: "plus.circle")) to add a row, or \(Image(systemName: "trash")) to remove one, then wrap onto the next line.")
                .font(.callout).foregroundStyle(.secondary)
            Label("Inline in a label", systemImage: "info.circle").font(.footnote)
        }.padding())),
        ("nav-no-title", AnyView(NavigationStack {
            Text("Content")
        })),
        ("sheet-nav", AnyView(Text("Behind the sheet")
            .sheet(isPresented: .constant(true)) {
                NavigationStack {
                    Form {
                        Section("Name") {
                            Text("Ada Lovelace")
                        }
                    }
                    .navigationTitle("New Contact")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") {} }
                        ToolbarItem(placement: .confirmationAction) { Button("Done") {} }
                    }
                }
            })),
        ("tab-nav-toolbar", AnyView(TabView {
            Tab("Inbox", systemImage: "tray") {
                NavigationStack {
                    List {
                        Text("Welcome")
                        Text("Receipt")
                    }
                    .navigationTitle("Inbox")
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) { Button("Compose", systemImage: "square.and.pencil") {} }
                    }
                }
            }
            Tab("Archive", systemImage: "archivebox") { Text("Archive") }
        })),
        ("nav-push-value", AnyView(NavigationStack(path: .constant([1])) {
            List {
                NavigationLink("One", value: 1)
            }
            .navigationTitle("Numbers")
            .navigationDestination(for: Int.self) { n in
                Text("Number \(n)").navigationTitle("Detail")
            }
        })),
        ("nav-push-inline", AnyView(NavigationStack(path: .constant([1])) {
            Text("Root")
                .navigationTitle("Numbers")
                .navigationDestination(for: Int.self) { n in
                    List { Text("Row \(n)") }
                        .navigationTitle("Detail")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Edit") {} } }
                }
        })),
        ("nav-push-notitle", AnyView(NavigationStack(path: .constant(["a"])) {
            Text("Root")
                .navigationTitle("Root")
                .navigationDestination(for: String.self) { s in Text("Pushed \(s)") }
        })),
        ("nav-push-presented", AnyView(NavigationStack {
            Text("Root")
                .navigationTitle("Root")
                .navigationDestination(isPresented: .constant(true)) { Text("Shown") }
        })),
        ("nav-links-outside-list", AnyView(NavigationStack {
            VStack(spacing: 20) {
                NavigationLink("Plain link") { Text("A") }
                NavigationLink { Text("B") } label: { Label("With icon", systemImage: "star") }
            }
            .navigationTitle("Links")
        })),
        ("tab-search", AnyView(TabView {
            Tab("Home", systemImage: "house") { Text("Home") }
            Tab("Library", systemImage: "books.vertical") { Text("Library") }
            Tab(role: .search) { Text("Search") }
        })),
        ("sheet-two-detents", AnyView(Text("Behind the sheet")
            .sheet(isPresented: .constant(true)) {
                Text("Resizable")
                    .presentationDetents([.medium, .large])
            })),
    ]
}

/// Dark-mode twins of cases: the reference renders them with the window in dark mode, ours with the dark scheme.
@MainActor
func darkCases() -> [(String, AnyView)] {
    let names: Set = ["text-styles", "buttons", "toggle", "card", "symbols", "list-sections", "form-toggles",
                      "navigation-label-links", "tab-basic", "tab-list", "toolbar-two-icons", "toolbar-text-buttons",
                      "title-inline-toolbar", "toolbar-bottom", "sheet-large", "sheet-medium", "sheet-nav",
                      "nav-push-value", "tab-search", "glass-buttons", "glass-stripes-rect"]
    return (parityCases() + glassCases() + chromeCases()).filter { names.contains($0.0) }.map { ($0.0 + "-dark", $0.1) }
}
