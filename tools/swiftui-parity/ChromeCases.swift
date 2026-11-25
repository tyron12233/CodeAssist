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
    ]
}
