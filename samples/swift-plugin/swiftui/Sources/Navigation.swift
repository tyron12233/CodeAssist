// Navigation and lists. First versions, to be calibrated against the Simulator: NavigationStack draws the large title
// of its root's navigationTitle; List, Form and Section lay rows out as iOS's inset grouped style does.

import Foundation

struct _NavigationTitleKey: EnvironmentKey { static let defaultValue: _TitleBox? = nil }

/// Where a navigationTitle inside a NavigationStack reports its title, during resolution.
final class _TitleBox { var title: String? }

extension View {
    public func navigationTitle(_ title: Text) -> some View {
        _modified(_TitleModifier(title: title.spans.map(\.text).joined()))
    }
    public func navigationTitle(_ titleKey: LocalizedStringKey) -> some View {
        _modified(_TitleModifier(title: titleKey.spans.map(\.text).joined()))
    }
    public func navigationTitle<S: StringProtocol>(_ title: S) -> some View {
        _modified(_TitleModifier(title: String(title)))
    }
    public func navigationBarTitleDisplayMode(_ displayMode: NavigationBarItem.TitleDisplayMode) -> some View { self }
    public func navigationBarBackButtonHidden(_ hidesBackButton: Bool = true) -> some View { self }
    public func navigationDestination<D: Hashable, C: View>(for data: D.Type, @ViewBuilder destination: @escaping (D) -> C) -> some View { self }
    public func navigationDestination<V: View>(isPresented: Binding<Bool>, @ViewBuilder destination: () -> V) -> some View { self }
    public func toolbar<Content: View>(@ViewBuilder content: () -> Content) -> some View { self }
    public func toolbar(_ visibility: Visibility, for bars: ToolbarPlacement...) -> some View { self }
    public func toolbarBackground(_ visibility: Visibility, for bars: ToolbarPlacement...) -> some View { self }
    public func sheet<Content: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> Content) -> some View { self }
    public func sheet<Item: Identifiable, Content: View>(item: Binding<Item?>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (Item) -> Content) -> some View { self }
    public func fullScreenCover<Content: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> Content) -> some View { self }
    public func alert<A: View>(_ titleKey: LocalizedStringKey, isPresented: Binding<Bool>, @ViewBuilder actions: () -> A) -> some View { self }
    public func alert<A: View, M: View>(_ titleKey: LocalizedStringKey, isPresented: Binding<Bool>, @ViewBuilder actions: () -> A, @ViewBuilder message: () -> M) -> some View { self }
    public func confirmationDialog<A: View>(_ titleKey: LocalizedStringKey, isPresented: Binding<Bool>, titleVisibility: Visibility = .automatic, @ViewBuilder actions: () -> A) -> some View { self }
    public func presentationDetents(_ detents: Set<PresentationDetent>) -> some View { self }
    public func listStyle<S: ListStyle>(_ style: S) -> some View {
        let kind: _ListStyleKind = style is PlainListStyle ? .plain : style is GroupedListStyle ? .grouped : .insetGrouped
        return _modified(_EnvironmentModifier { $0._listStyle = kind })
    }
}

struct _TitleModifier: _NodeModifier {
    let title: String
    func _context(_ ctx: _ResolveContext) -> _ResolveContext {
        ctx.environment[_NavigationTitleKey.self]?.title = title
        return ctx
    }
}

public enum NavigationBarItem {
    public enum TitleDisplayMode: Sendable { case automatic, inline, large }
}
public struct ToolbarPlacement: Sendable {
    public static let navigationBar = ToolbarPlacement(), tabBar = ToolbarPlacement(), bottomBar = ToolbarPlacement(), automatic = ToolbarPlacement()
}
public struct ToolbarItemPlacement: Sendable {
    public static let automatic = ToolbarItemPlacement(), principal = ToolbarItemPlacement(), primaryAction = ToolbarItemPlacement()
    public static let topBarLeading = ToolbarItemPlacement(), topBarTrailing = ToolbarItemPlacement(), bottomBar = ToolbarItemPlacement()
    public static let navigationBarLeading = ToolbarItemPlacement(), navigationBarTrailing = ToolbarItemPlacement()
    public static let confirmationAction = ToolbarItemPlacement(), cancellationAction = ToolbarItemPlacement()
}
public struct ToolbarItem<ID, Content: View>: View {
    let content: Content
    public init(placement: ToolbarItemPlacement = .automatic, @ViewBuilder content: () -> Content) where ID == () { self.content = content() }
    public var body: some View { content }
}
public struct ToolbarItemGroup<Content: View>: View {
    let content: Content
    public init(placement: ToolbarItemPlacement = .automatic, @ViewBuilder content: () -> Content) { self.content = content() }
    public var body: some View { content }
}
public struct PresentationDetent: Hashable, Sendable {
    let id: String
    public static let medium = PresentationDetent(id: "medium"), large = PresentationDetent(id: "large")
    public static func height(_ height: CGFloat) -> PresentationDetent { PresentationDetent(id: "h\(height)") }
    public static func fraction(_ fraction: CGFloat) -> PresentationDetent { PresentationDetent(id: "f\(fraction)") }
}

public struct NavigationStack<Root: View>: View, _PrimitiveView {
    let root: Root
    public init(@ViewBuilder root: () -> Root) { self.root = root() }
    public init<P>(path: Binding<P>, @ViewBuilder root: () -> Root) { self.root = root() }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let box = _TitleBox()
        let probe = _TitleBox()
        // The title is only known once the root has resolved; resolve once to find it, then for real knowing whether
        // content sits under a large title.
        _ = _resolveView(root, ctx.child("probe").with { $0[_NavigationTitleKey.self] = probe; $0._stackAxis = nil })
        let large = probe.title != nil
        let content = _resolveView(root, ctx.with {
            $0[_NavigationTitleKey.self] = box
            $0._stackAxis = nil
            $0[_UnderLargeTitleKey.self] = large
        })
        let body = content.count == 1 ? content[0]
            : _StackNode(path: ctx.path + "/root", env: ctx.environment, axis: .vertical, alignment: .center, spacing: nil, children: content)
        // Content fills what is left below the bar, centred, as a navigation stack's root view is.
        let filled = _FlexFrameNode(child: body, minWidth: nil, idealWidth: nil, maxWidth: .infinity,
                                    minHeight: nil, idealHeight: nil, maxHeight: .infinity, alignment: .center)
        guard let title = box.title else { return [filled] }
        // iOS 26's large-title bar, measured: 106 points below the safe area's top, the title (34 pt bold) at
        // (16, 58) in it.
        let titleCtx = ctx.child("title").with { $0.font = Font(size: 34, weight: .bold); $0._stackAxis = .vertical }
        let titleNodes = _resolveView(Text(title), titleCtx).map {
            _FixedFrameNode(child: _FlexFrameNode(child: _PaddingNode(child: $0, insets: EdgeInsets(top: 58, leading: 16, bottom: 0, trailing: 16)),
                                                  minWidth: nil, idealWidth: nil, maxWidth: .infinity, minHeight: nil, idealHeight: nil, maxHeight: nil, alignment: .topLeading),
                            width: nil, height: 106, alignment: .topLeading)
        }
        let stack = _StackNode(path: ctx.path, env: ctx.environment, axis: .vertical, alignment: .leading, spacing: 0, children: titleNodes + [filled])
        stack.edgeBackground = body.edgeBackground
        return [stack]
    }
}

public struct NavigationView<Content: View>: View {
    let content: Content
    public init(@ViewBuilder content: () -> Content) { self.content = content() }
    public var body: some View { NavigationStack { content } }
}

public struct NavigationLink<Label: View, Destination: View>: View {
    let label: Label
    public init(destination: Destination, @ViewBuilder label: () -> Label) { self.label = label() }
    public init(@ViewBuilder destination: () -> Destination, @ViewBuilder label: () -> Label) { self.label = label() }
    public var body: some View {
        HStack {
            label
            Spacer()
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.tertiaryLabel)
        }
    }
}

extension NavigationLink where Label == Text {
    public init(_ titleKey: LocalizedStringKey, destination: Destination) { self.init(destination: destination) { Text(titleKey) } }
    public init<S: StringProtocol>(_ title: S, destination: Destination) { self.init(destination: destination) { Text(title) } }
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder destination: () -> Destination) {
        self.init(destination: destination) { Text(titleKey) }
    }
    public init<S: StringProtocol>(_ title: S, @ViewBuilder destination: () -> Destination) {
        self.init(destination: destination) { Text(title) }
    }
}

extension NavigationLink where Destination == EmptyView {
    public init<P: Hashable>(value: P?, @ViewBuilder label: () -> Label) { self.label = label() }
}

extension NavigationLink where Destination == EmptyView, Label == Text {
    public init<P: Hashable>(_ titleKey: LocalizedStringKey, value: P?) { label = Text(titleKey) }
}

// MARK: - Lists

enum _ListStyleKind { case automatic, plain, grouped, insetGrouped }

public protocol ListStyle {}
public struct PlainListStyle: ListStyle { public init() {} }
public struct GroupedListStyle: ListStyle { public init() {} }
public struct InsetGroupedListStyle: ListStyle { public init() {} }
public struct InsetListStyle: ListStyle { public init() {} }
public struct SidebarListStyle: ListStyle { public init() {} }
public struct DefaultListStyle: ListStyle { public init() {} }
extension ListStyle where Self == PlainListStyle { public static var plain: PlainListStyle { PlainListStyle() } }
extension ListStyle where Self == GroupedListStyle { public static var grouped: GroupedListStyle { GroupedListStyle() } }
extension ListStyle where Self == InsetGroupedListStyle { public static var insetGrouped: InsetGroupedListStyle { InsetGroupedListStyle() } }
extension ListStyle where Self == InsetListStyle { public static var inset: InsetListStyle { InsetListStyle() } }
extension ListStyle where Self == SidebarListStyle { public static var sidebar: SidebarListStyle { SidebarListStyle() } }
extension ListStyle where Self == DefaultListStyle { public static var automatic: DefaultListStyle { DefaultListStyle() } }

struct _InListKey: EnvironmentKey { static let defaultValue = false }
struct _UnderLargeTitleKey: EnvironmentKey { static let defaultValue = false }

/// iOS 26's inset grouped list, as measured in the Simulator (tools/swiftui-parity, Probe.swift and pixel scans): cards
/// inset 16 from the screen's edges with continuous corners of radius 26, rows at least 52 tall with their content 16
/// in from the card's edges, 1-point #E8E8E8 separators inset 16 on both sides; a header (17 pt semibold, secondary)
/// 10 above its card, a footer (footnote, secondary) 8 below it.
enum _ListMetrics {
    static let cardInset: CGFloat = 16
    static let rowInset: CGFloat = 16
    static let rowMinHeight: CGFloat = 52
    static let rowVerticalPadding: CGFloat = 11
    static let cornerRadius: CGFloat = 26
    static let separator: CGFloat = 1
    static let headerToCard: CGFloat = 10
    static let cardToFooter: CGFloat = 8
    /// Above the first section: before its header, or before its card when it has none (none under a large title).
    static let firstHeaderTop: CGFloat = 10
    static let firstCardTopNoHeader: CGFloat = 35
    /// Between sections: from a card to the next header (or card), and from a footer to the next header.
    static let cardToNextHeader: CGFloat = 27 + 2.0 / 3
    static let cardToNextCard: CGFloat = 35
    static let footerToNext: CGFloat = 16
    static let bottom: CGFloat = 35
    static let separatorColor = Color(light: _RGBA(hex: 0xE8E8E8), dark: _RGBA(hex: 0x38383A))
}

/// A section, resolved: what a List lays out as one card.
@MainActor
final class _SectionMarker: _Node {
    let header: [_Node]
    let rows: [_Node]
    let footer: [_Node]
    init(path: String, env: EnvironmentValues, header: [_Node], rows: [_Node], footer: [_Node]) {
        self.header = header
        self.rows = rows
        self.footer = footer
        super.init(path: path, env: env)
    }
    // Outside a List a Section is its parts stacked: _resolve does that instead of returning a marker.
}

public struct Section<Parent: View, Content: View, Footer: View>: View, _PrimitiveView {
    let header: Parent
    let content: Content
    let footer: Footer
    public init(@ViewBuilder content: () -> Content, @ViewBuilder header: () -> Parent, @ViewBuilder footer: () -> Footer) {
        self.header = header()
        self.content = content()
        self.footer = footer()
    }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let inList = ctx.environment[_InListKey.self]
        let headerCtx = ctx.child("header").with {
            $0.font = Font(size: 17, weight: .semibold)
            $0._foreground = AnyShapeStyle(Color.secondary)
            $0._stackAxis = .vertical
        }
        let footerCtx = ctx.child("footer").with { $0.font = .footnote; $0._foreground = AnyShapeStyle(Color.secondary); $0._stackAxis = .vertical }
        let header = _resolveView(self.header, headerCtx)
        let rows = _resolveView(content, ctx.child("rows").with { $0._stackAxis = .vertical })
        let footer = _resolveView(self.footer, footerCtx)
        if inList {
            return [_SectionMarker(path: ctx.path, env: ctx.environment, header: header, rows: rows, footer: footer)]
        }
        return header + rows + footer
    }
}

extension Section where Parent == EmptyView, Footer == EmptyView {
    public init(@ViewBuilder content: () -> Content) { self.init(content: content, header: { EmptyView() }, footer: { EmptyView() }) }
}
extension Section where Footer == EmptyView {
    public init(@ViewBuilder content: () -> Content, @ViewBuilder header: () -> Parent) { self.init(content: content, header: header, footer: { EmptyView() }) }
}
extension Section where Parent == Text, Footer == EmptyView {
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder content: () -> Content) {
        self.init(content: content, header: { Text(titleKey) }, footer: { EmptyView() })
    }
    public init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) {
        self.init(content: content, header: { Text(title) }, footer: { EmptyView() })
    }
}
extension Section where Parent == EmptyView {
    public init(@ViewBuilder content: () -> Content, @ViewBuilder footer: () -> Footer) { self.init(content: content, header: { EmptyView() }, footer: footer) }
}

/// The list's sections, laid out down the screen: each a header, a card of rows and a footer, spaced as iOS spaces them.
@MainActor
func _listBody(_ ctx: _ResolveContext, sections: [_SectionMarker]) -> _Node {
    let env = ctx.environment
    typealias M = _ListMetrics
    var children: [_Node] = []
    var previousHadFooter = false
    for (index, section) in sections.enumerated() {
        let hasHeader = !section.header.isEmpty
        // Space above this section's header (or its card, without one).
        let gap: CGFloat
        if index == 0 {
            gap = hasHeader ? M.firstHeaderTop : (env[_UnderLargeTitleKey.self] ? 0 : M.firstCardTopNoHeader)
        } else if previousHadFooter {
            gap = M.footerToNext
        } else {
            gap = hasHeader ? M.cardToNextHeader : M.cardToNextCard
        }
        var block: [_Node] = []
        for node in section.header {
            block.append(_PaddingNode(child: node, insets: EdgeInsets(top: 0, leading: M.cardInset + M.rowInset, bottom: M.headerToCard, trailing: M.cardInset + M.rowInset)))
        }
        // The card: rows separated by inset hairlines, on a rounded white (secondary grouped) background.
        var cardRows: [_Node] = []
        for (i, row) in section.rows.enumerated() {
            let padded = _PaddingNode(child: row, insets: EdgeInsets(top: M.rowVerticalPadding, leading: M.rowInset, bottom: M.rowVerticalPadding, trailing: M.rowInset))
            cardRows.append(_FlexFrameNode(child: padded, minWidth: nil, idealWidth: nil, maxWidth: .infinity,
                                           minHeight: M.rowMinHeight, idealHeight: nil, maxHeight: nil, alignment: .leading))
            if i < section.rows.count - 1 {
                let line = _ShapeNode(path: section.path + "/sep\(i)", env: env, shape: _AnyShape(Rectangle()),
                                      fill: AnyShapeStyle(M.separatorColor), stroke: nil)
                let sized = _FixedFrameNode(child: line, width: nil, height: M.separator, alignment: .center)
                cardRows.append(_PaddingNode(child: sized, insets: EdgeInsets(top: 0, leading: M.rowInset, bottom: 0, trailing: M.rowInset)))
            }
        }
        if !cardRows.isEmpty {
            let card = _StackNode(path: section.path + "/card", env: env, axis: .vertical, alignment: .leading, spacing: 0, children: cardRows)
            let bg = _ShapeNode(path: section.path + "/cardbg", env: env, shape: _AnyShape(RoundedRectangle(cornerRadius: M.cornerRadius)),
                                fill: AnyShapeStyle(Color.secondarySystemGroupedBackground), stroke: nil)
            block.append(_PaddingNode(child: _LayeredNode(child: card, secondary: [bg], alignment: .center, isOverlay: false),
                                      insets: EdgeInsets(top: 0, leading: M.cardInset, bottom: 0, trailing: M.cardInset)))
        }
        for node in section.footer {
            block.append(_PaddingNode(child: node, insets: EdgeInsets(top: M.cardToFooter, leading: M.cardInset + M.rowInset, bottom: 0, trailing: M.cardInset + M.rowInset)))
        }
        let stacked = _StackNode(path: section.path + "/block", env: env, axis: .vertical, alignment: .leading, spacing: 0, children: block)
        children.append(_PaddingNode(child: stacked, insets: EdgeInsets(top: gap, leading: 0, bottom: 0, trailing: 0)))
        previousHadFooter = !section.footer.isEmpty
    }
    let all = _StackNode(path: ctx.path + "/sections", env: env, axis: .vertical, alignment: .leading, spacing: 0, children: children)
    return _PaddingNode(child: all, insets: EdgeInsets(top: 0, leading: 0, bottom: M.bottom, trailing: 0))
}

public struct List<SelectionValue: Hashable, Content: View>: View, _PrimitiveView {
    let content: Content
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let env = ctx.environment
        let inner = ctx.with { $0._stackAxis = .vertical; $0[_InListKey.self] = true }
        let nodes = _resolveView(content, inner)
        // Rows outside any Section form sections of their own, between the explicit ones.
        var sections: [_SectionMarker] = []
        var loose: [_Node] = []
        func flush() {
            guard !loose.isEmpty else { return }
            sections.append(_SectionMarker(path: ctx.path + "/implicit\(sections.count)", env: env, header: [], rows: loose, footer: []))
            loose = []
        }
        for node in nodes {
            if let section = node as? _SectionMarker { flush(); sections.append(section) } else { loose.append(node) }
        }
        flush()
        let body = _listBody(inner, sections: sections)
        let scroll = _ScrollNode(path: ctx.path, env: env, axes: .vertical, content: body)
        scroll.edgeBackground = .systemGroupedBackground
        return [scroll]
    }
}

extension List where SelectionValue == Never {
    public init(@ViewBuilder content: () -> Content) { self.content = content() }
}

extension List {
    public init(selection: Binding<SelectionValue?>?, @ViewBuilder content: () -> Content) { self.content = content() }
    public init(selection: Binding<Set<SelectionValue>>?, @ViewBuilder content: () -> Content) { self.content = content() }
}

extension List where SelectionValue == Never {
    public init<Data: RandomAccessCollection, RowContent: View>(_ data: Data, @ViewBuilder rowContent: @escaping (Data.Element) -> RowContent)
        where Content == ForEach<Data, Data.Element.ID, RowContent>, Data.Element: Identifiable {
        content = ForEach(data, content: rowContent)
    }
    public init<Data: RandomAccessCollection, ID: Hashable, RowContent: View>(_ data: Data, id: KeyPath<Data.Element, ID>, @ViewBuilder rowContent: @escaping (Data.Element) -> RowContent)
        where Content == ForEach<Data, ID, RowContent> {
        content = ForEach(data, id: id, content: rowContent)
    }
}

public struct Form<Content: View>: View {
    let content: Content
    public init(@ViewBuilder content: () -> Content) { self.content = content() }
    public var body: some View { List { content } }
}

public struct TabView<SelectionValue: Hashable, Content: View>: View {
    let content: Content
    public init(@ViewBuilder content: () -> Content) where SelectionValue == Int { self.content = content() }
    public init(selection: Binding<SelectionValue>?, @ViewBuilder content: () -> Content) { self.content = content() }
    public var body: some View { content }
}

extension View {
    public func tabItem<V: View>(@ViewBuilder _ label: () -> V) -> some View { self }
    public func tabViewStyle<S>(_ style: S) -> some View { self }
}
