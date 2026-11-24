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
        let content = _resolveView(root, ctx.with { $0[_NavigationTitleKey.self] = box; $0._stackAxis = nil })
        let body = content.count == 1 ? content[0]
            : _StackNode(path: ctx.path + "/root", env: ctx.environment, axis: .vertical, alignment: .center, spacing: nil, children: content)
        // Content fills the stack, below a large-title bar when there is a title.
        let filled = _FlexFrameNode(child: body, minWidth: nil, idealWidth: nil, maxWidth: .infinity,
                                    minHeight: nil, idealHeight: nil, maxHeight: .infinity, alignment: .top)
        guard let title = box.title else { return [filled] }
        let titleCtx = ctx.child("title").with { $0.font = Font(size: 34, weight: .bold); $0._stackAxis = .vertical }
        let titleNodes = _resolveView(Text(title), titleCtx).map {
            _FlexFrameNode(child: _PaddingNode(child: $0, insets: EdgeInsets(top: 3, leading: 16, bottom: 8, trailing: 16)),
                           minWidth: nil, idealWidth: nil, maxWidth: .infinity, minHeight: nil, idealHeight: nil, maxHeight: nil, alignment: .leading)
        }
        return [_StackNode(path: ctx.path, env: ctx.environment, axis: .vertical, alignment: .leading, spacing: 0, children: titleNodes + [filled])]
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

struct _InSectionKey: EnvironmentKey { static let defaultValue = false }

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
        _listSection(ctx, header: AnyView(header), rows: AnyView(content), footer: AnyView(footer))
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

/// An inset grouped section: an uppercase-free footnote header, rows on a rounded card with hairlines between them.
@MainActor
func _listSection(_ ctx: _ResolveContext, header: AnyView, rows: AnyView, footer: AnyView) -> [_Node] {
    let env = ctx.environment
    let rowCtx = ctx.child("rows").with { $0._stackAxis = .vertical; $0[_InSectionKey.self] = true }
    let rowNodes = _resolveView(rows, rowCtx)
    var cardChildren: [_Node] = []
    for (i, row) in rowNodes.enumerated() {
        let padded = _FlexFrameNode(
            child: _PaddingNode(child: row, insets: EdgeInsets(top: 11, leading: 20, bottom: 11, trailing: 20)),
            minWidth: nil, idealWidth: nil, maxWidth: .infinity, minHeight: 52, idealHeight: nil, maxHeight: nil, alignment: .leading)
        cardChildren.append(padded)
        if i < rowNodes.count - 1 {
            cardChildren.append(_PaddingNode(child: _DividerNode(path: ctx.path + "/sep\(i)", env: env, axis: .horizontal),
                                             insets: EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 0)))
        }
    }
    let card = _StackNode(path: ctx.path + "/card", env: env, axis: .vertical, alignment: .leading, spacing: 0, children: cardChildren)
    let cardBg = _ShapeNode(path: ctx.path + "/cardbg", env: env, shape: _AnyShape(RoundedRectangle(cornerRadius: 26)),
                            fill: AnyShapeStyle(Color.secondarySystemGroupedBackground), stroke: nil)
    var children: [_Node] = []
    let headerCtx = ctx.child("header").with { $0.font = .footnote; $0._foreground = AnyShapeStyle(Color.secondary) }
    for node in _resolveView(header, headerCtx) {
        children.append(_PaddingNode(child: node, insets: EdgeInsets(top: 0, leading: 20, bottom: 6, trailing: 20)))
    }
    children.append(_PaddingNode(child: _LayeredNode(child: card, secondary: [cardBg], alignment: .center, isOverlay: false),
                                 insets: EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16)))
    let footerCtx = ctx.child("footer").with { $0.font = .footnote; $0._foreground = AnyShapeStyle(Color.secondary) }
    for node in _resolveView(footer, footerCtx) {
        children.append(_PaddingNode(child: node, insets: EdgeInsets(top: 6, leading: 20, bottom: 0, trailing: 20)))
    }
    return [_StackNode(path: ctx.path, env: env, axis: .vertical, alignment: .leading, spacing: 0, children: children)]
}

public struct List<SelectionValue: Hashable, Content: View>: View, _PrimitiveView {
    let content: Content
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let env = ctx.environment
        let inner = ctx.with { $0._stackAxis = .vertical }
        // Rows that are not in a Section form one section of their own.
        let probe = _resolveView(content, inner.child("probe"))
        let sectioned = probe.allSatisfy { $0 is _StackNode && ($0 as! _StackNode).children.contains { $0 is _PaddingNode } && $0.path.contains("Section") }
        let sections = sectioned ? probe : _listSection(inner, header: AnyView(EmptyView()), rows: AnyView(content), footer: AnyView(EmptyView()))
        let stack = _StackNode(path: ctx.path + "/sections", env: env, axis: .vertical, alignment: .leading, spacing: 20, children: sections)
        let padded = _PaddingNode(child: stack, insets: EdgeInsets(top: 0, leading: 0, bottom: 20, trailing: 0))
        let scroll = _ScrollNode(path: ctx.path, env: env, axes: .vertical, content: padded)
        let bg = _ShapeNode(path: ctx.path + "/bg", env: env, shape: _AnyShape(Rectangle()), fill: AnyShapeStyle(Color.systemGroupedBackground), stroke: nil)
        return [_LayeredNode(child: scroll, secondary: [bg], alignment: .center, isOverlay: false)]
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
