// iOS 26's app chrome: the floating glass tab bar, navigation bar items on glass platters, bottom toolbars and sheets.
// Every number here was measured on the iPhone 17 Pro Simulator (tools/swiftui-parity: ChromeCases.swift, and
// `run.sh dump <case>` for the frames UIKit gives the chrome, which SwiftUI hands to UIKit).

import Foundation

enum _Chrome {
    /// Content on chrome glass (bar buttons, unselected tabs): the label colour as the glass's vibrancy draws it.
    static let barLabel = Color(light: _RGBA(hex: 0x191919), dark: _RGBA(hex: 0xF2F2F2))
    /// The selected tab's pill, darkening the glass under it (#FDFDFD to #EBEBEB on white).
    static let tabSelection = Color(light: _RGBA(0, 0, 0, 0.07), dark: _RGBA(1, 1, 1, 0.1))
    /// The dimming behind a sheet: black at 20%.
    static let dimming = Color(light: _RGBA(0, 0, 0, 0.2), dark: _RGBA(0, 0, 0, 0.4))
}

// MARK: - Tab bar

/// What a tab shows in the bar: from a `Tab`, or a view's `.tabItem { }`.
struct _TabLabel {
    var title: String?
    var systemImage: String?
}

/// The title and symbol a label view names: a Text, an Image, a Label of both, or views holding them.
func _labelParts(_ value: Any, depth: Int = 0) -> _TabLabel {
    if let text = value as? Text { return _TabLabel(title: text.spans.map(\.text).joined(), systemImage: nil) }
    if let image = value as? Image { return _TabLabel(title: nil, systemImage: image.systemName) }
    if let any = value as? AnyView { return _labelParts(any.storage, depth: depth + 1) }
    var result = _TabLabel()
    guard depth < 8 else { return result }
    for child in Mirror(reflecting: value).children {
        let parts = _labelParts(child.value, depth: depth + 1)
        result.title = result.title ?? parts.title
        result.systemImage = result.systemImage ?? parts.systemImage
    }
    return result
}

public struct TabRole: Hashable, Sendable {
    let id: Int
    public static let search = TabRole(id: 1)
}

/// A tab of a TabView (iOS 18's API). Its content is only resolved while it is the selected tab.
public struct Tab<Value: Hashable, Content: View>: View, _PrimitiveView {
    let label: _TabLabel
    let value: Value?
    let role: TabRole?
    let content: Content
    public var body: Never { _neverBody() }

    init(label: _TabLabel, value: Value?, role: TabRole?, content: Content) {
        self.label = label
        self.value = value
        self.role = role
        self.content = content
    }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let content = self.content
        var label = self.label
        if role == .search {
            label.title = label.title ?? "Search"
            label.systemImage = label.systemImage ?? "magnifyingglass"
        }
        return [_TabMarker(path: ctx.path, env: ctx.environment, label: label, value: value.map { AnyHashable($0) },
                           isSearch: role == .search, resolve: { _resolveView(content, ctx.child("content")) })]
    }
}

extension Tab {
    public init<S: StringProtocol>(_ title: S, systemImage: String, value: Value, role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(title: String(title), systemImage: systemImage), value: value, role: role, content: content())
    }
    public init(_ titleKey: LocalizedStringKey, systemImage: String, value: Value, role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(title: titleKey.spans.map(\.text).joined(), systemImage: systemImage), value: value, role: role, content: content())
    }
    public init<S: StringProtocol>(_ title: S, image: String, value: Value, role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(title: String(title), systemImage: nil), value: value, role: role, content: content())
    }
    public init(value: Value, role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(), value: value, role: role, content: content())
    }
    public init<L: View>(value: Value, role: TabRole? = nil, @ViewBuilder content: () -> Content, @ViewBuilder label: () -> L) {
        self.init(label: _labelParts(label()), value: value, role: role, content: content())
    }
}

extension Tab where Value == Never {
    public init<S: StringProtocol>(_ title: S, systemImage: String, role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(title: String(title), systemImage: systemImage), value: nil, role: role, content: content())
    }
    public init(_ titleKey: LocalizedStringKey, systemImage: String, role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(title: titleKey.spans.map(\.text).joined(), systemImage: systemImage), value: nil, role: role, content: content())
    }
    public init<S: StringProtocol>(_ title: S, image: String, role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(title: String(title), systemImage: nil), value: nil, role: role, content: content())
    }
    public init(role: TabRole? = nil, @ViewBuilder content: () -> Content) {
        self.init(label: _TabLabel(), value: nil, role: role, content: content())
    }
    public init<L: View>(role: TabRole? = nil, @ViewBuilder content: () -> Content, @ViewBuilder label: () -> L) {
        self.init(label: _labelParts(label()), value: nil, role: role, content: content())
    }
}

public struct TabSection<Header: View, Content: View>: View {
    let content: Content
    public init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) where Header == Text { self.content = content() }
    public init(@ViewBuilder content: () -> Content, @ViewBuilder header: () -> Header) { self.content = content() }
    public var body: some View { content }
}

/// A `Tab`, resolved: its label, its selection value and how to resolve its content when selected.
@MainActor
final class _TabMarker: _Node {
    let label: _TabLabel
    let value: AnyHashable?
    let isSearch: Bool
    let resolve: () -> [_Node]
    init(path: String, env: EnvironmentValues, label: _TabLabel, value: AnyHashable?, isSearch: Bool, resolve: @escaping () -> [_Node]) {
        self.label = label
        self.value = value
        self.isSearch = isSearch
        self.resolve = resolve
        super.init(path: path, env: env)
    }
}

/// iOS 26's floating tab bar, measured: a capsule of regular glass 62 tall, 21 above the screen's bottom, centred and
/// at most 360 wide; inside it (4 in) the tabs' buttons, 54 tall, overlapping their neighbours by 8; the selected one
/// on a darker pill. A button is at least 94 wide (98 with four tabs, 74 with five or more) or its title plus 31; when
/// they do not fit, they overlap more. Icon: the symbol's fill variant, 24-point frame, centred 20 below the button's top;
/// title 10 pt (semibold selected, medium otherwise) 35 below it. Content's safe area ends 83 above the screen's bottom.
enum _TabBarMetrics {
    static let inset: CGFloat = 83
    static let platterHeight: CGFloat = 62
    static let platterBottom: CGFloat = 21
    static let maxPlatterWidth: CGFloat = 360
    static let padding: CGFloat = 4
    static let buttonHeight: CGFloat = 54
    static let iconCenter: CGFloat = 20
    static let titleTop: CGFloat = 35
    /// UIKit reports 17-point images but draws them in image views the size of a 24-point symbol's frame.
    static let iconSize: CGFloat = 24

    static func minimumButtonWidth(_ count: Int) -> CGFloat { count <= 3 ? 94 : count == 4 ? 98 : 74 }
}

struct _TabEntry {
    let label: _TabLabel
    let value: AnyHashable
    var isSearch = false
    let nodes: () -> [_Node]
}

public struct TabView<SelectionValue: Hashable, Content: View>: View, _PrimitiveView {
    let content: Content
    let selection: Binding<SelectionValue>?
    public init(@ViewBuilder content: () -> Content) where SelectionValue == Int {
        self.content = content()
        selection = nil
    }
    public init(selection: Binding<SelectionValue>?, @ViewBuilder content: () -> Content) {
        self.content = content()
        self.selection = selection
    }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let env = ctx.environment
        let screen = env._screenRect
        var safe = env._safeRect
        safe.size.height = max(0, screen.maxY - _TabBarMetrics.inset - safe.minY)
        let inner = ctx.with { $0._safeRect = safe; $0._stackAxis = nil }

        var entries: [_TabEntry] = []
        for (index, node) in _resolveView(content, inner).enumerated() {
            if let tab = node as? _TabMarker {
                entries.append(_TabEntry(label: tab.label, value: tab.value ?? AnyHashable(index), isSearch: tab.isSearch, nodes: tab.resolve))
            } else {
                entries.append(_TabEntry(label: node.tabLabel ?? _TabLabel(), value: node.tagValue ?? AnyHashable(index), nodes: { [node] }))
            }
        }

        let selected: AnyHashable
        let select: (AnyHashable) -> Void
        if let selection {
            selected = AnyHashable(selection.wrappedValue)
            select = { value in if let typed = value.base as? SelectionValue { selection.wrappedValue = typed } }
        } else {
            let cell = ctx.graph.cell(ctx.path + "/selection") { entries.first?.value ?? AnyHashable(0) }
            selected = cell.value
            select = { cell.value = $0 }
        }
        let selectedIndex = entries.firstIndex { $0.value == selected } ?? 0

        let content: _Node? = entries.isEmpty ? nil : {
            let nodes = entries[selectedIndex].nodes()
            return nodes.count == 1 ? nodes[0]
                : _StackNode(path: ctx.path + "/content", env: inner.environment, axis: .vertical, alignment: .center, spacing: nil, children: nodes)
        }()

        let tint = env._tint ?? .accentColor
        var items: [_TabViewNode.Item] = []
        for (index, entry) in entries.enumerated() {
            let isSelected = index == selectedIndex
            let color = AnyShapeStyle(isSelected ? tint : _Chrome.barLabel)
            let iconCtx = ctx.child("bar\(index)/icon").with {
                $0.font = Font(size: _TabBarMetrics.iconSize, weight: .regular)
                $0._foreground = color
                $0.symbolVariants = .fill
                $0._stackAxis = nil
            }
            let titleCtx = ctx.child("bar\(index)/title").with {
                $0.font = Font(size: 10, weight: isSelected ? .semibold : .medium)
                $0._foreground = color
                $0._stackAxis = nil
                $0.lineLimit = 1
            }
            let value = entry.value
            items.append(_TabViewNode.Item(
                icon: entry.label.systemImage.flatMap { _resolveView(Image(systemName: $0), iconCtx).first },
                // The search tab is its own glass circle, icon only.
                title: entry.isSearch ? nil : entry.label.title.flatMap { _resolveView(Text(verbatim: $0), titleCtx).first },
                isSearch: entry.isSearch,
                action: { select(value) }))
        }
        let node = _TabViewNode(path: ctx.path, env: env, content: content, contentSafe: safe, items: items, selected: selectedIndex)
        node.edgeBackground = content?.edgeBackground
        return [node]
    }
}

@MainActor
final class _TabViewNode: _Node {
    struct Item {
        let icon: _Node?
        let title: _Node?
        let isSearch: Bool
        let action: () -> Void
    }
    let content: _Node?
    let contentSafe: CGRect
    let items: [Item]
    let selected: Int
    private var platter: CGRect = .zero
    private var buttons: [CGRect] = []
    private var searchCircle: CGRect?
    private var tabs: [Item] { items.filter { !$0.isSearch } }
    private var selectedTab: Int? { items.indices.contains(selected) && !items[selected].isSearch ? items[..<selected].filter { !$0.isSearch }.count : nil }

    init(path: String, env: EnvironmentValues, content: _Node?, contentSafe: CGRect, items: [Item], selected: Int) {
        self.content = content
        self.contentSafe = contentSafe
        self.items = items
        self.selected = selected
        super.init(path: path, env: env)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        proposal.replacingUnspecifiedDimensions(by: env._screenRect.size)
    }

    override func place(_ rect: CGRect) {
        frame = rect
        if let content {
            content.place(_align(content, content.sizeThatFits(ProposedViewSize(contentSafe.size)), in: contentSafe, .center))
        }
        typealias M = _TabBarMetrics
        let screen = env._screenRect
        // A search tab, measured: a 62 circle of glass at the trailing edge (21 in), its icon centred; the other tabs'
        // bar then starts at the leading edge (21 in) instead of being centred.
        searchCircle = nil
        if let search = items.first(where: \.isSearch) {
            let circle = CGRect(x: screen.maxX - 21 - M.platterHeight, y: screen.maxY - M.platterBottom - M.platterHeight,
                                width: M.platterHeight, height: M.platterHeight)
            searchCircle = circle
            if let icon = search.icon {
                let size = icon.sizeThatFits(.unspecified)
                icon.place(CGRect(x: circle.midX - size.width / 2, y: circle.midY - size.height / 2, width: size.width, height: size.height))
            }
        }
        let items = tabs
        let n = items.count
        guard n > 0 else { return }
        let minimum = M.minimumButtonWidth(n)
        let widths = items.map { max(minimum, ($0.title?.sizeThatFits(.unspecified).width ?? 0) + 31) }
        let total = widths.reduce(0, +)
        let room = (searchCircle == nil ? M.maxPlatterWidth : M.maxPlatterWidth - M.platterHeight - 8) - 2 * M.padding
        var overlap: CGFloat = 8
        if n > 1, total - overlap * CGFloat(n - 1) > room { overlap = (total - room) / CGFloat(n - 1) }
        let inner = total - (n > 1 ? overlap * CGFloat(n - 1) : 0)
        let width = inner + 2 * M.padding
        platter = CGRect(x: searchCircle == nil ? screen.midX - width / 2 : screen.minX + 21, y: screen.maxY - M.platterBottom - M.platterHeight,
                         width: width, height: M.platterHeight)
        buttons = []
        var x = platter.minX + M.padding
        for (index, item) in items.enumerated() {
            let button = CGRect(x: x, y: platter.minY + M.padding, width: widths[index], height: M.buttonHeight)
            buttons.append(button)
            x += widths[index] - overlap
            if let icon = item.icon {
                let size = icon.sizeThatFits(.unspecified)
                icon.place(CGRect(x: button.midX - size.width / 2, y: button.minY + M.iconCenter - size.height / 2, width: size.width, height: size.height))
            }
            if let title = item.title {
                let size = title.sizeThatFits(.unspecified)
                title.place(CGRect(x: button.midX - size.width / 2, y: button.minY + M.titleTop, width: size.width, height: size.height))
            }
        }
    }

    override func emit(_ list: _DisplayList) {
        content?.emit(list)
        let dark = env.colorScheme == .dark
        if let circle = searchCircle, let search = items.first(where: \.isSearch) {
            list.glass(Circle().path(in: circle), bounds: circle, variant: "regular", tint: nil, dark: dark)
            search.icon?.emit(list)
            list.hit(circle, action: _Graph.shared.register(search.action))
        }
        let tabs = self.tabs
        guard !tabs.isEmpty else { return }
        list.glass(Capsule().path(in: platter), bounds: platter, variant: "regular", tint: nil, dark: dark)
        if let selected = selectedTab, buttons.indices.contains(selected) {
            list.shape(Capsule().path(in: buttons[selected]), style: AnyShapeStyle(_Chrome.tabSelection), stroke: nil, env: env)
        }
        for (index, item) in tabs.enumerated() {
            item.icon?.emit(list)
            item.title?.emit(list)
            list.hit(buttons[index], action: _Graph.shared.register(item.action))
        }
    }
}

extension View {
    public func tabItem<V: View>(@ViewBuilder _ label: () -> V) -> some View {
        let parts = _labelParts(label())
        // Kept on the node (wrappers copy it outwards) for the TabView around it.
        return _modified(_WrapModifier { node, _ in node.tabLabel = parts; return node })
    }
    public func tabViewStyle<S>(_ style: S) -> some View { self }
    public func tabBarMinimizeBehavior(_ behavior: TabBarMinimizeBehavior) -> some View { self }
}

public struct TabBarMinimizeBehavior: Sendable {
    public static let automatic = TabBarMinimizeBehavior(), never = TabBarMinimizeBehavior()
    public static let onScrollDown = TabBarMinimizeBehavior(), onScrollUp = TabBarMinimizeBehavior()
}
public struct PageTabViewStyle: Sendable {
    public enum IndexDisplayMode: Sendable { case automatic, always, never }
    public init(indexDisplayMode: IndexDisplayMode = .automatic) {}
}
public struct DefaultTabViewStyle: Sendable { public init() {} }
public struct SidebarAdaptableTabViewStyle: Sendable { public init() {} }
extension PageTabViewStyle { public static var page: PageTabViewStyle { PageTabViewStyle() } }

// MARK: - Toolbars

/// Where a toolbar item goes in the bars iOS 26 draws.
enum _BarPlacement: Equatable { case leading, trailing, principal, bottom }

public struct ToolbarItemPlacement: Sendable {
    let kind: _BarPlacement
    let emphasized: Bool
    init(_ kind: _BarPlacement, emphasized: Bool = false) {
        self.kind = kind
        self.emphasized = emphasized
    }
    public static let automatic = ToolbarItemPlacement(.trailing), primaryAction = ToolbarItemPlacement(.trailing)
    public static let principal = ToolbarItemPlacement(.principal)
    public static let topBarLeading = ToolbarItemPlacement(.leading), topBarTrailing = ToolbarItemPlacement(.trailing)
    public static let navigationBarLeading = ToolbarItemPlacement(.leading), navigationBarTrailing = ToolbarItemPlacement(.trailing)
    public static let bottomBar = ToolbarItemPlacement(.bottom), status = ToolbarItemPlacement(.bottom)
    public static let cancellationAction = ToolbarItemPlacement(.leading), destructiveAction = ToolbarItemPlacement(.trailing)
    /// iOS sets a confirming action's title in semibold.
    public static let confirmationAction = ToolbarItemPlacement(.trailing, emphasized: true)
    public static let secondaryAction = ToolbarItemPlacement(.trailing), navigation = ToolbarItemPlacement(.leading)
    public static let keyboard = ToolbarItemPlacement(.bottom)
}

public struct ToolbarPlacement: Sendable {
    let id: Int
    public static let navigationBar = ToolbarPlacement(id: 1), tabBar = ToolbarPlacement(id: 2), bottomBar = ToolbarPlacement(id: 3)
    public static let automatic = ToolbarPlacement(id: 0)
}

/// Toolbar items, resolved: their placement and the views they show.
@MainActor
final class _BarItemMarker: _Node {
    let placement: _BarPlacement
    let nodes: [_Node]
    let splits: Bool
    init(path: String, env: EnvironmentValues, placement: _BarPlacement, nodes: [_Node], isSpacer: Bool = false) {
        self.placement = placement
        self.nodes = nodes
        self.splits = isSpacer
        super.init(path: path, env: env)
    }
}

public struct ToolbarItem<ID, Content: View>: View, _PrimitiveView {
    let placement: ToolbarItemPlacement
    let content: Content
    public init(placement: ToolbarItemPlacement = .automatic, @ViewBuilder content: () -> Content) where ID == () {
        self.placement = placement
        self.content = content()
    }
    public init(id: String, placement: ToolbarItemPlacement = .automatic, @ViewBuilder content: () -> Content) where ID == String {
        self.placement = placement
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let inner = placement.emphasized ? ctx.with { $0._weight = .semibold } : ctx
        return [_BarItemMarker(path: ctx.path, env: ctx.environment, placement: placement.kind, nodes: _resolveView(content, inner))]
    }
}

public struct ToolbarItemGroup<Content: View>: View, _PrimitiveView {
    let placement: ToolbarItemPlacement
    let content: Content
    public init(placement: ToolbarItemPlacement = .automatic, @ViewBuilder content: () -> Content) {
        self.placement = placement
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let inner = placement.emphasized ? ctx.with { $0._weight = .semibold } : ctx
        // A Spacer between a group's items splits it, as it does in a bottom bar.
        return _resolveView(content, inner).map {
            _BarItemMarker(path: $0.path, env: $0.env, placement: placement.kind, nodes: $0.isSpacer ? [] : [$0], isSpacer: $0.isSpacer)
        }
    }
}

/// iOS 26's spacer between toolbar items: splits the glass they share.
public struct ToolbarSpacer: View, _PrimitiveView {
    public enum Sizing: Sendable { case fixed, flexible }
    let placement: ToolbarItemPlacement
    public init(_ sizing: Sizing = .flexible, placement: ToolbarItemPlacement = .automatic) { self.placement = placement }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_BarItemMarker(path: ctx.path, env: ctx.environment, placement: placement.kind, nodes: [], isSpacer: true)]
    }
}

struct _ToolbarModifier<C: View>: _NodeModifier {
    let content: C
    func _context(_ ctx: _ResolveContext) -> _ResolveContext {
        guard let box = ctx.environment[_NavigationTitleKey.self] else { return ctx }
        // Bar items: label-coloured on their glass, icons only for labels, at body size.
        let itemCtx = ctx.child("toolbar").with {
            $0._tint = _Chrome.barLabel
            $0._foreground = nil
            if $0._labelStyle == .automatic { $0._labelStyle = .iconOnly }
            $0.font = .body
            // Bar icons draw at the large image scale (UIKit reports body-size images in large-scale image views).
            $0.imageScale = .large
            $0._stackAxis = .horizontal
            $0._buttonStyle = .automatic
            $0[_InListKey.self] = false
        }
        for node in _resolveView(content, itemCtx) {
            if let marker = node as? _BarItemMarker {
                box.items.append(marker)
            } else {
                box.items.append(_BarItemMarker(path: node.path, env: node.env, placement: .trailing, nodes: node.isSpacer ? [] : [node], isSpacer: node.isSpacer))
            }
        }
        return ctx
    }
}

struct _BoxModifier: _NodeModifier {
    let update: (_TitleBox) -> Void
    func _context(_ ctx: _ResolveContext) -> _ResolveContext {
        if let box = ctx.environment[_NavigationTitleKey.self] { update(box) }
        return ctx
    }
}

extension View {
    public func toolbar<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        _modified(_ToolbarModifier(content: content()))
    }
    public func toolbar<Content: View>(id: String, @ViewBuilder content: () -> Content) -> some View {
        _modified(_ToolbarModifier(content: content()))
    }
    public func toolbar(_ visibility: Visibility, for bars: ToolbarPlacement...) -> some View {
        _modified(_BoxModifier { box in
            if visibility == .hidden, bars.contains(where: { $0.id == ToolbarPlacement.navigationBar.id || $0.id == 0 }) { box.barHidden = true }
        })
    }
    public func toolbarBackground(_ visibility: Visibility, for bars: ToolbarPlacement...) -> some View { self }
    public func toolbarBackground<S: ShapeStyle>(_ style: S, for bars: ToolbarPlacement...) -> some View { self }
    public func toolbarColorScheme(_ colorScheme: ColorScheme?, for bars: ToolbarPlacement...) -> some View { self }
    public func toolbarTitleDisplayMode(_ mode: ToolbarTitleDisplayMode) -> some View {
        _modified(_BoxModifier { $0.displayMode = mode.mode })
    }
    public func navigationBarTitleDisplayMode(_ displayMode: NavigationBarItem.TitleDisplayMode) -> some View {
        _modified(_BoxModifier { $0.displayMode = displayMode })
    }
    public func navigationBarHidden(_ hidden: Bool) -> some View {
        _modified(_BoxModifier { if hidden { $0.barHidden = true } })
    }
}

public struct ToolbarTitleDisplayMode: Sendable {
    let mode: NavigationBarItem.TitleDisplayMode
    public static let automatic = ToolbarTitleDisplayMode(mode: .automatic), inline = ToolbarTitleDisplayMode(mode: .inline)
    public static let large = ToolbarTitleDisplayMode(mode: .large), inlineLarge = ToolbarTitleDisplayMode(mode: .large)
}

/// Navigation bar items on glass, measured: platters of regular glass 44 tall at the bar's top, 16 in from the screen's
/// edges; a lone icon button on a 44 circle; otherwise each item padded (12 a side for a title, 7.17 for an icon at
/// the large image scale, icons 16 apart) inside 4 of padding. The inline title: 17 pt semibold, centred on the platters' centre line.
/// The bar is 54 tall (106 with a large title, whose 34 pt bold title sits 58 below the bar's top, 16 in).
/// A bottom bar: platters 48 tall, 28 in from the screen's edges and bottom; content's safe area ends 86 above it.
enum _BarMetrics {
    static let inlineHeight: CGFloat = 54
    static let largeHeight: CGFloat = 106
    static let platterHeight: CGFloat = 44
    static let edge: CGFloat = 16
    static let bottomPlatterHeight: CGFloat = 48
    static let bottomEdge: CGFloat = 28
    static let bottomInset: CGFloat = 86
}

/// One glass platter of bar items: its frame and its items' frames, from the items' sizes.
@MainActor
func _layoutPlatter(_ items: [_Node], height: CGFloat) -> (width: CGFloat, slots: [CGFloat]) {
    let sizes = items.map { $0.sizeThatFits(.unspecified) }
    if items.count == 1, !items[0].isTextLike, sizes[0].width <= height - 8 {
        return (height, [height])
    }
    var slots: [CGFloat] = []
    var width: CGFloat = 8
    for (i, item) in items.enumerated() {
        let slot = sizes[i].width + (item.isTextLike ? 24 : 14 + 1.0 / 3)
        if i > 0, !item.isTextLike, !items[i - 1].isTextLike { width += 16 }
        slots.append(slot)
        width += slot
    }
    return (width, slots)
}

@MainActor
final class _NavStackNode: _Node {
    let content: _Node
    let largeTitle: _Node?
    let inlineTitle: _Node?
    let back: _Node?
    let leading: [_Node]
    let trailing: [_Node]
    let bottomGroups: [[_Node]]
    let barHeight: CGFloat
    private var platters: [CGRect] = []

    init(path: String, env: EnvironmentValues, content: _Node, largeTitle: _Node?, inlineTitle: _Node?, back: _Node?, leading: [_Node],
         trailing: [_Node], bottomGroups: [[_Node]], barHeight: CGFloat) {
        self.content = content
        self.largeTitle = largeTitle
        self.inlineTitle = inlineTitle
        self.back = back
        self.leading = leading
        self.trailing = trailing
        self.bottomGroups = bottomGroups
        self.barHeight = barHeight
        super.init(path: path, env: env)
        edgeBackground = content.edgeBackground
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        proposal.replacingUnspecifiedDimensions(by: env._safeRect.size)
    }

    private func place(_ node: _Node, centeredAt point: CGPoint) {
        let size = node.sizeThatFits(.unspecified)
        node.place(CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height))
    }

    /// Lays a platter's items out from its leading edge; returns the platter.
    private func placePlatter(_ items: [_Node], x: CGFloat, alignTrailing: Bool, y: CGFloat, height: CGFloat) -> CGRect {
        let (width, slots) = _layoutPlatter(items, height: height)
        let platter = CGRect(x: alignTrailing ? x - width : x, y: y, width: width, height: height)
        if slots.count == 1, width == height {
            place(items[0], centeredAt: CGPoint(x: platter.midX, y: platter.midY))
            return platter
        }
        var cursor = platter.minX + 4
        for (i, item) in items.enumerated() {
            if i > 0, !item.isTextLike, !items[i - 1].isTextLike { cursor += 16 }
            place(item, centeredAt: CGPoint(x: cursor + slots[i] / 2, y: platter.midY))
            cursor += slots[i]
        }
        return platter
    }

    override func place(_ rect: CGRect) {
        frame = rect
        typealias M = _BarMetrics
        let screen = env._screenRect
        let barTop = barHeight > 0 ? env[_BarTopKey.self] : 0
        var rect = rect
        rect.origin.y += barTop
        rect.size.height -= barTop
        var body = rect
        body.origin.y += barHeight
        body.size.height -= barHeight
        if !bottomGroups.isEmpty { body.size.height = max(0, min(body.maxY, screen.maxY - M.bottomInset) - body.minY) }
        content.place(_align(content, content.sizeThatFits(ProposedViewSize(body.size)), in: body, .center))
        platters = []
        if let largeTitle {
            let size = largeTitle.sizeThatFits(ProposedViewSize(width: rect.width - 2 * M.edge, height: nil))
            largeTitle.place(CGRect(x: rect.minX + M.edge, y: rect.minY + 58, width: size.width, height: size.height))
        }
        if let inlineTitle { place(inlineTitle, centeredAt: CGPoint(x: rect.midX, y: rect.minY + M.platterHeight / 2)) }
        var leadingX = rect.minX + M.edge
        if let back {
            // The back button: chevron.left centred in a 44 circle (measured 0.17 left of centre).
            let circle = CGRect(x: leadingX, y: rect.minY, width: M.platterHeight, height: M.platterHeight)
            platters.append(circle)
            place(back, centeredAt: CGPoint(x: circle.midX - 1.0 / 6, y: circle.midY))
            leadingX = circle.maxX + 8
        }
        if !leading.isEmpty {
            platters.append(placePlatter(leading, x: leadingX, alignTrailing: false, y: rect.minY, height: M.platterHeight))
        }
        if !trailing.isEmpty {
            platters.append(placePlatter(trailing, x: rect.maxX - M.edge, alignTrailing: true, y: rect.minY, height: M.platterHeight))
        }
        let bottomY = screen.maxY - M.bottomEdge - M.bottomPlatterHeight
        for (i, group) in bottomGroups.enumerated() where !group.isEmpty {
            let last = i == bottomGroups.count - 1 && bottomGroups.count > 1
            if bottomGroups.count == 1 {
                let (width, _) = _layoutPlatter(group, height: M.bottomPlatterHeight)
                platters.append(placePlatter(group, x: screen.midX - width / 2, alignTrailing: false, y: bottomY, height: M.bottomPlatterHeight))
            } else if i == 0 {
                platters.append(placePlatter(group, x: screen.minX + M.bottomEdge, alignTrailing: false, y: bottomY, height: M.bottomPlatterHeight))
            } else if last {
                platters.append(placePlatter(group, x: screen.maxX - M.bottomEdge, alignTrailing: true, y: bottomY, height: M.bottomPlatterHeight))
            } else {
                let (width, _) = _layoutPlatter(group, height: M.bottomPlatterHeight)
                platters.append(placePlatter(group, x: screen.midX - width / 2, alignTrailing: false, y: bottomY, height: M.bottomPlatterHeight))
            }
        }
    }

    override func emit(_ list: _DisplayList) {
        content.emit(list)
        largeTitle?.emit(list)
        inlineTitle?.emit(list)
        for platter in platters {
            list.glass(Capsule().path(in: platter), bounds: platter, variant: "regular", tint: nil, dark: env.colorScheme == .dark)
        }
        back?.emit(list)
        for item in leading + trailing + bottomGroups.flatMap({ $0 }) { item.emit(list) }
    }
}

// MARK: - Sheets

/// A sheet or full-screen cover the render in progress presents: drawn over everything once the screen is drawn.
@MainActor
final class _PresentationRequest {
    let fullScreen: Bool
    let ctx: _ResolveContext
    let resolve: (_ResolveContext) -> [_Node]
    let dismiss: () -> Void
    init(fullScreen: Bool, ctx: _ResolveContext, resolve: @escaping (_ResolveContext) -> [_Node], dismiss: @escaping () -> Void) {
        self.fullScreen = fullScreen
        self.ctx = ctx
        self.resolve = resolve
        self.dismiss = dismiss
    }
}

/// Where a sheet's content reports what it declares about the sheet (detents).
final class _SheetBox {
    var detents: Set<PresentationDetent>?
    var dragIndicator: Visibility?
}
struct _SheetBoxKey: EnvironmentKey { static let defaultValue: _SheetBox? = nil }

struct _PresentModifier: _NodeModifier {
    let request: (_ResolveContext) -> _PresentationRequest?
    func _context(_ ctx: _ResolveContext) -> _ResolveContext {
        if !ctx.environment._isProbe, let r = request(ctx) { ctx.graph.presentations.append(r) }
        return ctx
    }
}

struct _SheetBoxModifier: _NodeModifier {
    let update: (_SheetBox) -> Void
    func _context(_ ctx: _ResolveContext) -> _ResolveContext {
        if let box = ctx.environment[_SheetBoxKey.self] { update(box) }
        return ctx
    }
}

extension View {
    public func sheet<Content: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> Content) -> some View {
        _modified(_PresentModifier { ctx in
            guard isPresented.wrappedValue else { return nil }
            return _PresentationRequest(fullScreen: false, ctx: ctx.child("sheet"), resolve: { _resolveView(content(), $0) },
                                        dismiss: { isPresented.wrappedValue = false; onDismiss?() })
        })
    }
    public func sheet<Item: Identifiable, Content: View>(item: Binding<Item?>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (Item) -> Content) -> some View {
        _modified(_PresentModifier { ctx in
            guard let value = item.wrappedValue else { return nil }
            return _PresentationRequest(fullScreen: false, ctx: ctx.child("sheet"), resolve: { _resolveView(content(value), $0) },
                                        dismiss: { item.wrappedValue = nil; onDismiss?() })
        })
    }
    public func fullScreenCover<Content: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> Content) -> some View {
        _modified(_PresentModifier { ctx in
            guard isPresented.wrappedValue else { return nil }
            return _PresentationRequest(fullScreen: true, ctx: ctx.child("cover"), resolve: { _resolveView(content(), $0) },
                                        dismiss: { isPresented.wrappedValue = false; onDismiss?() })
        })
    }
    public func fullScreenCover<Item: Identifiable, Content: View>(item: Binding<Item?>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (Item) -> Content) -> some View {
        _modified(_PresentModifier { ctx in
            guard let value = item.wrappedValue else { return nil }
            return _PresentationRequest(fullScreen: true, ctx: ctx.child("cover"), resolve: { _resolveView(content(value), $0) },
                                        dismiss: { item.wrappedValue = nil; onDismiss?() })
        })
    }
    public func presentationDetents(_ detents: Set<PresentationDetent>) -> some View {
        _modified(_SheetBoxModifier { $0.detents = detents })
    }
    public func presentationDetents(_ detents: Set<PresentationDetent>, selection: Binding<PresentationDetent>) -> some View {
        _modified(_SheetBoxModifier { $0.detents = [selection.wrappedValue] })
    }
    public func presentationDragIndicator(_ visibility: Visibility) -> some View {
        _modified(_SheetBoxModifier { $0.dragIndicator = visibility })
    }
    public func presentationCornerRadius(_ cornerRadius: CGFloat?) -> some View { self }
    public func presentationBackground<S: ShapeStyle>(_ style: S) -> some View { self }
    public func presentationBackgroundInteraction(_ interaction: PresentationBackgroundInteraction) -> some View { self }
    public func interactiveDismissDisabled(_ isDisabled: Bool = true) -> some View { self }
}

public struct PresentationBackgroundInteraction: Sendable {
    public static let automatic = PresentationBackgroundInteraction(), enabled = PresentationBackgroundInteraction()
    public static let disabled = PresentationBackgroundInteraction()
    public static func enabled(upThrough detent: PresentationDetent) -> PresentationBackgroundInteraction { PresentationBackgroundInteraction() }
}

public struct PresentationDetent: Hashable, Sendable {
    let id: String
    let height: CGFloat?
    let fraction: CGFloat?
    public static let medium = PresentationDetent(id: "medium", height: nil, fraction: nil)
    public static let large = PresentationDetent(id: "large", height: nil, fraction: nil)
    public static func height(_ height: CGFloat) -> PresentationDetent { PresentationDetent(id: "h\(height)", height: height, fraction: nil) }
    public static func fraction(_ fraction: CGFloat) -> PresentationDetent { PresentationDetent(id: "f\(fraction)", height: nil, fraction: fraction) }
}

/// iOS 26's sheets, measured. Large: opaque, from the safe area's top (62) to the screen's bottom, continuous corners
/// 37.5 at the top and 65 at the bottom; content laid out down to 34 above the bottom. Smaller detents float: 8 in from
/// the screen's sides and bottom, regular glass, corners 36 and 56.5; medium's top at 415 on an 874-point screen. The
/// screen behind is dimmed by black at 20%.
@MainActor
func _present(_ request: _PresentationRequest, index: Int, list: _DisplayList) {
    let screen = CGRect(origin: .zero, size: _Device.size)
    let env = request.ctx.environment
    let graph = request.ctx.graph
    let dismiss = DismissAction(action: request.dismiss)

    if request.fullScreen {
        let ctx = request.ctx.with {
            $0._safeRect = _Device.safeRect
            $0._screenRect = screen
            $0[_DismissKey.self] = dismiss
            $0[_IsPresentedKey.self] = true
        }
        let nodes = request.resolve(ctx)
        let node = nodes.count == 1 ? nodes[0] : _StackNode(path: ctx.path, env: ctx.environment, axis: .vertical, alignment: .center, spacing: nil, children: nodes)
        list.shape(Path(screen), style: AnyShapeStyle(node.edgeBackground ?? Color.systemBackground), stroke: nil, env: env)
        list.hit(screen, action: graph.register {})
        node.place(_align(node, node.sizeThatFits(ProposedViewSize(_Device.safeRect.size)), in: _Device.safeRect, .center))
        node.emit(list)
        return
    }

    // Which detent: the content says, through .presentationDetents; the smallest is where a sheet opens.
    let probe = _SheetBox()
    _ = request.resolve(request.ctx.child("probe").with { $0[_SheetBoxKey.self] = probe; $0._isProbe = true })
    let detents = probe.detents ?? [.large]
    func top(_ d: PresentationDetent) -> CGFloat {
        if d == .large { return _Device.safeArea.top }
        if d == .medium { return 415 }
        if let h = d.height { return screen.maxY - 8 - h }
        if let f = d.fraction { return screen.maxY - 8 - f * (screen.height - _Device.safeArea.top - 8) }
        return _Device.safeArea.top
    }
    let detent = detents.max { top($0) < top($1) } ?? .large
    let large = detent == .large
    let sheet = large
        ? CGRect(x: 0, y: _Device.safeArea.top, width: screen.width, height: screen.height - _Device.safeArea.top)
        : CGRect(x: 8, y: top(detent), width: screen.width - 16, height: screen.maxY - 8 - top(detent))
    let contentRect = large
        ? CGRect(x: sheet.minX, y: sheet.minY, width: sheet.width, height: screen.maxY - _Device.safeArea.bottom - sheet.minY)
        : CGRect(x: sheet.minX, y: sheet.minY, width: sheet.width, height: sheet.height - 32 - 2.0 / 3)
    let radii = large
        ? RectangleCornerRadii(topLeading: 37.5, bottomLeading: 65, bottomTrailing: 65, topTrailing: 37.5)
        : RectangleCornerRadii(topLeading: 36, bottomLeading: 56.5, bottomTrailing: 56.5, topTrailing: 36)

    let ctx = request.ctx.with {
        $0[_SheetBoxKey.self] = _SheetBox()
        $0[_BarTopKey.self] = 16
        $0._elevated = true
        $0._safeRect = contentRect
        $0._screenRect = sheet
        $0[_DismissKey.self] = dismiss
        $0[_IsPresentedKey.self] = true
    }
    let nodes = request.resolve(ctx)
    let node = nodes.count == 1 ? nodes[0] : _StackNode(path: ctx.path, env: ctx.environment, axis: .vertical, alignment: .center, spacing: nil, children: nodes)

    list.shape(Path(screen), style: AnyShapeStyle(_Chrome.dimming), stroke: nil, env: env)
    list.hit(screen, action: graph.register(request.dismiss))
    let shape = _continuousPath(sheet, radii)
    if large {
        if let background = node.edgeBackground {
            // A list's background fills the sheet's bottom corners square (measured: a Form in a sheet).
            let square = _continuousPath(sheet, RectangleCornerRadii(topLeading: radii.topLeading, topTrailing: radii.topTrailing))
            list.shape(square, style: AnyShapeStyle(background), stroke: nil, env: ctx.environment)
        } else {
            list.shape(shape, style: AnyShapeStyle(Color.systemBackground), stroke: nil, env: ctx.environment)
        }
    } else {
        list.glass(shape, bounds: sheet, variant: "regular", tint: nil, dark: env.colorScheme == .dark)
        if let background = node.edgeBackground { list.shape(shape, style: AnyShapeStyle(background), stroke: nil, env: ctx.environment) }
    }
    list.hit(sheet, action: graph.register {})
    node.place(_align(node, node.sizeThatFits(ProposedViewSize(contentRect.size)), in: contentRect, .center))
    node.emit(list)
    // The grabber, shown when the sheet can change size (or asked for): a capsule 34.67 x 5, 5 below the top.
    let showsGrabber = probe.dragIndicator.map { $0 == .visible } ?? (detents.count > 1)
    if showsGrabber {
        let grabber = CGRect(x: sheet.midX - 17 - 1.0 / 3, y: sheet.minY + 5, width: 34 + 2.0 / 3, height: 5)
        list.shape(Capsule().path(in: grabber), style: AnyShapeStyle(Color(light: _RGBA(hex: 0xAFAFB2), dark: _RGBA(hex: 0x5B5B60))), stroke: nil, env: env)
    }
}
