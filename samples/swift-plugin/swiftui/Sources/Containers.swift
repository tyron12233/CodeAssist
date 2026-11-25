// Containers: stacks, Spacer, Divider, ForEach, ScrollView.

import Foundation

// MARK: - Stacks

public struct VStack<Content: View>: View, _PrimitiveView {
    let alignment: HorizontalAlignment
    let spacing: CGFloat?
    let content: Content
    public init(alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.spacing = spacing
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let children = _resolveView(content, ctx.with { $0._stackAxis = .vertical })
        return [_StackNode(path: ctx.path, env: ctx.environment, axis: .vertical,
                           alignment: Alignment(horizontal: alignment, vertical: .center), spacing: spacing, children: children)]
    }
}

public struct HStack<Content: View>: View, _PrimitiveView {
    let alignment: VerticalAlignment
    let spacing: CGFloat?
    let content: Content
    public init(alignment: VerticalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.spacing = spacing
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let children = _resolveView(content, ctx.with { $0._stackAxis = .horizontal })
        return [_StackNode(path: ctx.path, env: ctx.environment, axis: .horizontal,
                           alignment: Alignment(horizontal: .center, vertical: alignment), spacing: spacing, children: children)]
    }
}

public struct ZStack<Content: View>: View, _PrimitiveView {
    let alignment: Alignment
    let content: Content
    public init(alignment: Alignment = .center, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let children = _resolveView(content, ctx.with { $0._stackAxis = nil })
        return [_ZStackNode(path: ctx.path, env: ctx.environment, alignment: alignment, children: children)]
    }
}

/// Lazy stacks lay out like their eager counterparts in a preview: everything is visible at once anyway.
public struct LazyVStack<Content: View>: View {
    let alignment: HorizontalAlignment
    let spacing: CGFloat?
    let content: Content
    public init(alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil, pinnedViews: PinnedScrollableViews = .init(), @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.spacing = spacing
        self.content = content()
    }
    public var body: some View { VStack(alignment: alignment, spacing: spacing) { content } }
}

public struct LazyHStack<Content: View>: View {
    let alignment: VerticalAlignment
    let spacing: CGFloat?
    let content: Content
    public init(alignment: VerticalAlignment = .center, spacing: CGFloat? = nil, pinnedViews: PinnedScrollableViews = .init(), @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.spacing = spacing
        self.content = content()
    }
    public var body: some View { HStack(alignment: alignment, spacing: spacing) { content } }
}

public struct PinnedScrollableViews: OptionSet, Sendable {
    public let rawValue: UInt32
    public init(rawValue: UInt32) { self.rawValue = rawValue }
    public init() { rawValue = 0 }
    public static let sectionHeaders = PinnedScrollableViews(rawValue: 1)
    public static let sectionFooters = PinnedScrollableViews(rawValue: 2)
}

// MARK: - Spacer, Divider

public struct Spacer: View, _PrimitiveView {
    public var minLength: CGFloat?
    public init(minLength: CGFloat? = nil) { self.minLength = minLength }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_SpacerNode(path: ctx.path, env: ctx.environment, minLength: minLength, axis: ctx.environment._stackAxis)]
    }
}

public struct Divider: View, _PrimitiveView {
    public init() {}
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_DividerNode(path: ctx.path, env: ctx.environment, axis: ctx.environment._stackAxis == .horizontal ? .vertical : .horizontal)]
    }
}

/// A hairline (one pixel at @3x) across the stack's axis, in the separator colour.
@MainActor
final class _DividerNode: _Node {
    let axis: Axis
    init(path: String, env: EnvironmentValues, axis: Axis) {
        self.axis = axis
        super.init(path: path, env: env)
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let thickness = 1 / _scale
        return axis == .horizontal
            ? CGSize(width: proposal.width ?? thickness, height: thickness)
            : CGSize(width: thickness, height: proposal.height ?? thickness)
    }
    override func emit(_ list: _DisplayList) {
        list.shape(Path(frame), style: AnyShapeStyle(Color.separator), stroke: nil, env: env)
    }
}

// MARK: - ForEach

public struct ForEach<Data: RandomAccessCollection, ID: Hashable, Content> {
    public var data: Data
    public var content: (Data.Element) -> Content
    let id: (Data.Element) -> ID
}

extension ForEach: View, _PrimitiveView where Content: View {
    public init(_ data: Data, id: KeyPath<Data.Element, ID>, @ViewBuilder content: @escaping (Data.Element) -> Content) {
        self.data = data
        self.content = content
        self.id = { $0[keyPath: id] }
    }

    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        // Each element's identity is its id, so its state follows it when the collection is reordered.
        // An element's id is also its tag unless it has one: what a Picker or TabView over a ForEach selects by.
        data.flatMap { element -> [_Node] in
            let key = id(element)
            let nodes = _resolveView(content(element), ctx.child("id:\(key)"))
            for node in nodes where node.tagValue == nil { node.tagValue = AnyHashable(key) }
            return nodes
        }
    }
}

extension ForEach where Content: View, Data.Element: Identifiable, ID == Data.Element.ID {
    public init(_ data: Data, @ViewBuilder content: @escaping (Data.Element) -> Content) {
        self.data = data
        self.content = content
        self.id = { $0.id }
    }
}

extension ForEach where Data == Range<Int>, ID == Int, Content: View {
    public init(_ data: Range<Int>, @ViewBuilder content: @escaping (Int) -> Content) {
        self.data = data
        self.content = content
        self.id = { $0 }
    }
}

extension ForEach where Content: View {
    public init<C: MutableCollection & RandomAccessCollection, RowContent: View>(
        _ data: Binding<C>, id: KeyPath<C.Element, ID>, @ViewBuilder content: @escaping (Binding<C.Element>) -> RowContent
    ) where Data == IndexedIdentifierCollection<C, ID>, Content == RowContent {
        let collection = IndexedIdentifierCollection(base: data, id: id)
        self.data = collection
        self.content = { content($0.binding) }
        self.id = { $0.id }
    }

    public init<C: MutableCollection & RandomAccessCollection, RowContent: View>(
        _ data: Binding<C>, @ViewBuilder content: @escaping (Binding<C.Element>) -> RowContent
    ) where Data == IndexedIdentifierCollection<C, ID>, Content == RowContent, C.Element: Identifiable, ID == C.Element.ID {
        self.init(data, id: \.id, content: content)
    }
}

/// What `ForEach($items)` iterates: each element as a binding into the collection, with its id.
public struct IndexedIdentifierCollection<Base: MutableCollection & RandomAccessCollection, ID: Hashable>: RandomAccessCollection {
    public struct Element {
        let binding: Binding<Base.Element>
        let id: ID
    }
    let base: Binding<Base>
    let idPath: KeyPath<Base.Element, ID>
    init(base: Binding<Base>, id: KeyPath<Base.Element, ID>) {
        self.base = base
        idPath = id
    }
    public var startIndex: Int { 0 }
    public var endIndex: Int { base.wrappedValue.count }
    public subscript(position: Int) -> Element {
        let index = base.wrappedValue.index(base.wrappedValue.startIndex, offsetBy: position)
        let base = self.base
        let binding = Binding<Base.Element>(
            get: { base.wrappedValue[index] },
            set: { base.wrappedValue[index] = $0 }
        )
        return Element(binding: binding, id: base.wrappedValue[index][keyPath: idPath])
    }
}

// MARK: - ScrollView

public struct ScrollView<Content: View>: View, _PrimitiveView {
    let axes: Axis.Set
    let showsIndicators: Bool
    let content: Content
    public init(_ axes: Axis.Set = .vertical, showsIndicators: Bool = true, @ViewBuilder content: () -> Content) {
        self.axes = axes
        self.showsIndicators = showsIndicators
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let inner = ctx.with { $0._stackAxis = nil }
        let children = _resolveView(content, inner)
        let stack = children.count == 1 ? children[0]
            : _StackNode(path: ctx.path + "/content", env: ctx.environment, axis: axes.contains(.horizontal) && !axes.contains(.vertical) ? .horizontal : .vertical,
                         alignment: .center, spacing: nil, children: children)
        return [_ScrollNode(path: ctx.path, env: ctx.environment, axes: axes, content: stack)]
    }
}

/// A scroll view takes what it is offered (it is as flexible as a Color), offers its content unlimited length along
/// the scrolling axes, and shows the content's start. Scrolling arrives with the interactive preview.
@MainActor
final class _ScrollNode: _Node {
    let axes: Axis.Set
    let content: _Node
    var offset = CGPoint.zero

    init(path: String, env: EnvironmentValues, axes: Axis.Set, content: _Node) {
        self.axes = axes
        self.content = content
        super.init(path: path, env: env)
    }

    private func contentProposal(_ size: CGSize) -> ProposedViewSize {
        ProposedViewSize(width: axes.contains(.horizontal) ? nil : size.width, height: axes.contains(.vertical) ? nil : size.height)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let ideal = content.sizeThatFits(contentProposal(proposal.replacingUnspecifiedDimensions(by: .zero)))
        return CGSize(width: proposal.width ?? ideal.width, height: proposal.height ?? ideal.height)
    }

    override func place(_ rect: CGRect) {
        frame = rect
        let size = content.sizeThatFits(contentProposal(rect.size))
        // Narrower than the scroll view across its axis: centred, as SwiftUI does.
        let x = axes.contains(.horizontal) ? rect.minX : rect.minX + max(0, (rect.width - size.width) / 2)
        let y = axes.contains(.vertical) ? rect.minY : rect.minY + max(0, (rect.height - size.height) / 2)
        content.place(CGRect(origin: CGPoint(x: x - offset.x, y: y - offset.y), size: size))
    }

    override func emit(_ list: _DisplayList) {
        list.pushClip(Path(frame))
        content.emit(list)
        list.pop()
    }
}

public struct ScrollViewReader<Content: View>: View {
    let content: (ScrollViewProxy) -> Content
    public init(@ViewBuilder content: @escaping (ScrollViewProxy) -> Content) { self.content = content }
    public var body: some View { content(ScrollViewProxy()) }
}

public struct ScrollViewProxy {
    public func scrollTo<ID: Hashable>(_ id: ID, anchor: UnitPoint? = nil) {}
}

// MARK: - GeometryReader

/// Lays out its content with the size it is given, which it reports through a GeometryProxy: so it is resolved during
/// layout, not before it.
public struct GeometryReader<Content: View>: View, _PrimitiveView {
    let content: (GeometryProxy) -> Content
    public init(@ViewBuilder content: @escaping (GeometryProxy) -> Content) { self.content = content }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let content = self.content
        return [_GeometryNode(path: ctx.path, env: ctx.environment) { proxy in
            _resolveView(content(proxy), ctx.with { $0._stackAxis = nil })
        }]
    }
}

public struct GeometryProxy {
    public let size: CGSize
    let globalFrame: CGRect
    public var safeAreaInsets: EdgeInsets { EdgeInsets() }
    public func frame(in coordinateSpace: CoordinateSpace) -> CGRect {
        switch coordinateSpace {
        case .local: return CGRect(origin: .zero, size: size)
        default: return globalFrame
        }
    }
    public func frame(in coordinateSpace: some CoordinateSpaceProtocol) -> CGRect { globalFrame }
}

public enum CoordinateSpace: Hashable, Sendable {
    case global, local
    case named(AnyHashable)
}

public protocol CoordinateSpaceProtocol {}
public struct GlobalCoordinateSpace: CoordinateSpaceProtocol {}
public struct LocalCoordinateSpace: CoordinateSpaceProtocol {}
extension CoordinateSpaceProtocol where Self == GlobalCoordinateSpace { public static var global: GlobalCoordinateSpace { GlobalCoordinateSpace() } }
extension CoordinateSpaceProtocol where Self == LocalCoordinateSpace { public static var local: LocalCoordinateSpace { LocalCoordinateSpace() } }

@MainActor
final class _GeometryNode: _Node {
    let make: (GeometryProxy) -> [_Node]
    var children: [_Node] = []

    init(path: String, env: EnvironmentValues, make: @escaping (GeometryProxy) -> [_Node]) {
        self.make = make
        super.init(path: path, env: env)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { proposal.replacingUnspecifiedDimensions() }

    override func place(_ rect: CGRect) {
        frame = rect
        children = make(GeometryProxy(size: rect.size, globalFrame: rect))
        // Content sits at the reader's top leading corner.
        for child in children {
            child.place(CGRect(origin: rect.origin, size: child.sizeThatFits(ProposedViewSize(rect.size))))
        }
    }

    override func emit(_ list: _DisplayList) { children.forEach { $0.emit(list) } }
}
