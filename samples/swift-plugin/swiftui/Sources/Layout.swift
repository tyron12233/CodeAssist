// Layout: SwiftUI's two-pass protocol. A parent proposes a size, the child answers with the size it wants
// (sizeThatFits), then the parent places it (place). Every container here follows SwiftUI's documented behaviour, and
// the public `Layout` protocol lets user code define its own.

import Foundation

// MARK: - Proposals

public struct ProposedViewSize: Equatable, Hashable, Sendable {
    public var width: CGFloat?
    public var height: CGFloat?

    public init(width: CGFloat?, height: CGFloat?) {
        self.width = width
        self.height = height
    }
    public init(_ size: CGSize) { self.init(width: size.width, height: size.height) }

    public static let zero = ProposedViewSize(width: 0, height: 0)
    public static let unspecified = ProposedViewSize(width: nil, height: nil)
    public static let infinity = ProposedViewSize(width: .infinity, height: .infinity)

    public func replacingUnspecifiedDimensions(by size: CGSize = CGSize(width: 10, height: 10)) -> CGSize {
        CGSize(width: width ?? size.width, height: height ?? size.height)
    }

    subscript(axis: Axis) -> CGFloat? {
        get { axis == .horizontal ? width : height }
        set { if axis == .horizontal { width = newValue } else { height = newValue } }
    }

    init(axis: Axis, along: CGFloat?, across: CGFloat?) {
        if axis == .horizontal { self.init(width: along, height: across) } else { self.init(width: across, height: along) }
    }
}

extension CGSize {
    subscript(axis: Axis) -> CGFloat {
        get { axis == .horizontal ? width : height }
        set { if axis == .horizontal { width = newValue } else { height = newValue } }
    }
    init(axis: Axis, along: CGFloat, across: CGFloat) {
        if axis == .horizontal { self.init(width: along, height: across) } else { self.init(width: across, height: along) }
    }
}

extension Axis {
    var cross: Axis { self == .horizontal ? .vertical : .horizontal }
}

// MARK: - Alignment

public protocol AlignmentID {
    static func defaultValue(in context: ViewDimensions) -> CGFloat
}

public struct ViewDimensions: Equatable {
    public let width: CGFloat
    public let height: CGFloat
    let firstBaseline: CGFloat?
    let lastBaseline: CGFloat?
    var explicit: [ObjectIdentifier: CGFloat] = [:]

    init(size: CGSize, firstBaseline: CGFloat? = nil, lastBaseline: CGFloat? = nil, explicit: [ObjectIdentifier: CGFloat] = [:]) {
        width = size.width
        height = size.height
        self.firstBaseline = firstBaseline
        self.lastBaseline = lastBaseline
        self.explicit = explicit
    }

    public subscript(guide: HorizontalAlignment) -> CGFloat { explicit[guide.id] ?? guide.compute(self) }
    public subscript(guide: VerticalAlignment) -> CGFloat { explicit[guide.id] ?? guide.compute(self) }
    public subscript(explicit guide: HorizontalAlignment) -> CGFloat? { explicit[guide.id] }
    public subscript(explicit guide: VerticalAlignment) -> CGFloat? { explicit[guide.id] }

    public static func == (a: ViewDimensions, b: ViewDimensions) -> Bool { a.width == b.width && a.height == b.height }
}

public struct HorizontalAlignment: Equatable, Sendable {
    let id: ObjectIdentifier
    let compute: @Sendable (ViewDimensions) -> CGFloat
    public init(_ id: AlignmentID.Type) {
        self.id = ObjectIdentifier(id)
        compute = { id.defaultValue(in: $0) }
    }
    init(key: AnyObject.Type, _ compute: @escaping @Sendable (ViewDimensions) -> CGFloat) {
        id = ObjectIdentifier(key)
        self.compute = compute
    }
    public static func == (a: HorizontalAlignment, b: HorizontalAlignment) -> Bool { a.id == b.id }

    final class _Leading {}
    final class _Center {}
    final class _Trailing {}
    public static let leading = HorizontalAlignment(key: _Leading.self) { _ in 0 }
    public static let center = HorizontalAlignment(key: _Center.self) { $0.width / 2 }
    public static let trailing = HorizontalAlignment(key: _Trailing.self) { $0.width }
    public static let listRowSeparatorLeading = HorizontalAlignment(key: _Leading.self) { _ in 0 }
    public static let listRowSeparatorTrailing = HorizontalAlignment(key: _Trailing.self) { $0.width }
}

public struct VerticalAlignment: Equatable, Sendable {
    let id: ObjectIdentifier
    let compute: @Sendable (ViewDimensions) -> CGFloat
    public init(_ id: AlignmentID.Type) {
        self.id = ObjectIdentifier(id)
        compute = { id.defaultValue(in: $0) }
    }
    init(key: AnyObject.Type, _ compute: @escaping @Sendable (ViewDimensions) -> CGFloat) {
        id = ObjectIdentifier(key)
        self.compute = compute
    }
    public static func == (a: VerticalAlignment, b: VerticalAlignment) -> Bool { a.id == b.id }

    final class _Top {}
    final class _Center {}
    final class _Bottom {}
    final class _FirstBaseline {}
    final class _LastBaseline {}
    public static let top = VerticalAlignment(key: _Top.self) { _ in 0 }
    public static let center = VerticalAlignment(key: _Center.self) { $0.height / 2 }
    public static let bottom = VerticalAlignment(key: _Bottom.self) { $0.height }
    public static let firstTextBaseline = VerticalAlignment(key: _FirstBaseline.self) { $0.firstBaseline ?? $0.height }
    public static let lastTextBaseline = VerticalAlignment(key: _LastBaseline.self) { $0.lastBaseline ?? $0.height }
}

public struct Alignment: Equatable, Sendable {
    public var horizontal: HorizontalAlignment
    public var vertical: VerticalAlignment
    public init(horizontal: HorizontalAlignment, vertical: VerticalAlignment) {
        self.horizontal = horizontal
        self.vertical = vertical
    }
    public static let center = Alignment(horizontal: .center, vertical: .center)
    public static let leading = Alignment(horizontal: .leading, vertical: .center)
    public static let trailing = Alignment(horizontal: .trailing, vertical: .center)
    public static let top = Alignment(horizontal: .center, vertical: .top)
    public static let bottom = Alignment(horizontal: .center, vertical: .bottom)
    public static let topLeading = Alignment(horizontal: .leading, vertical: .top)
    public static let topTrailing = Alignment(horizontal: .trailing, vertical: .top)
    public static let bottomLeading = Alignment(horizontal: .leading, vertical: .bottom)
    public static let bottomTrailing = Alignment(horizontal: .trailing, vertical: .bottom)
    public static let centerFirstTextBaseline = Alignment(horizontal: .center, vertical: .firstTextBaseline)
    public static let centerLastTextBaseline = Alignment(horizontal: .center, vertical: .lastTextBaseline)
    public static let leadingFirstTextBaseline = Alignment(horizontal: .leading, vertical: .firstTextBaseline)
    public static let leadingLastTextBaseline = Alignment(horizontal: .leading, vertical: .lastTextBaseline)
    public static let trailingFirstTextBaseline = Alignment(horizontal: .trailing, vertical: .firstTextBaseline)
    public static let trailingLastTextBaseline = Alignment(horizontal: .trailing, vertical: .lastTextBaseline)
}

public enum Edge: Int8, CaseIterable, Hashable, Sendable {
    case top, leading, bottom, trailing
    public struct Set: OptionSet, Hashable, Sendable {
        public let rawValue: Int8
        public init(rawValue: Int8) { self.rawValue = rawValue }
        public init(_ edge: Edge) { rawValue = 1 << edge.rawValue }
        public static let top = Set(.top)
        public static let leading = Set(.leading)
        public static let bottom = Set(.bottom)
        public static let trailing = Set(.trailing)
        public static let all: Set = [.top, .leading, .bottom, .trailing]
        public static let horizontal: Set = [.leading, .trailing]
        public static let vertical: Set = [.top, .bottom]
    }
}

public enum HorizontalEdge: Int8, CaseIterable, Hashable, Sendable { case leading, trailing }
public enum VerticalEdge: Int8, CaseIterable, Hashable, Sendable { case top, bottom }

public struct EdgeInsets: Equatable, Sendable {
    public var top: CGFloat
    public var leading: CGFloat
    public var bottom: CGFloat
    public var trailing: CGFloat
    public init(top: CGFloat, leading: CGFloat, bottom: CGFloat, trailing: CGFloat) {
        self.top = top
        self.leading = leading
        self.bottom = bottom
        self.trailing = trailing
    }
    public init() { self.init(top: 0, leading: 0, bottom: 0, trailing: 0) }
    var horizontal: CGFloat { leading + trailing }
    var vertical: CGFloat { top + bottom }
}

// MARK: - Nodes

/// A resolved view: something that can be sized, placed and drawn. Every view resolves into these.
@MainActor
class _Node {
    let path: String
    let env: EnvironmentValues
    var frame: CGRect = .zero
    var layoutPriority: Double = 0
    /// A colour this view paints across the whole screen, safe areas included, when it is a preview's root (a List's
    /// grouped background does that on iOS).
    var edgeBackground: Color?
    /// In a List row: how far past the row's leading edge its separator starts (a Label's title, past its icon column).
    var separatorLeading: CGFloat?
    /// Explicit alignment guides set with `.alignmentGuide`, in this node's own coordinates.
    var guides: [ObjectIdentifier: (ViewDimensions) -> CGFloat] = [:]
    private var sizeCache: [ProposedViewSize: CGSize] = [:]

    init(path: String, env: EnvironmentValues) {
        self.path = path
        self.env = env
    }

    /// Answer [proposal] with the size this view takes. Cached: stacks ask the same child several times.
    final func sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        if let cached = sizeCache[proposal] { return cached }
        let size = _sizeThatFits(proposal)
        sizeCache[proposal] = size
        return size
    }

    func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { .zero }

    /// Place this view at [rect] (in the preview's coordinates), and its children within it.
    func place(_ rect: CGRect) { frame = rect }

    /// Draw this view (and its children) into [list].
    func emit(_ list: _DisplayList) {}

    var isSpacer: Bool { false }
    /// Views that stacks lay out as text for their default spacing (Text and things built from it).
    var isTextLike: Bool { false }
    /// How a vertical stack spaces this view from its neighbours when it is given no spacing: text has its own
    /// spacing, derived from its font (see `_defaultSpacing`); anything else is nil, the platform's 8 points.
    var textSpacing: _TextSpacing? { nil }

    func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) { (nil, nil) }

    func dimensions(_ size: CGSize) -> ViewDimensions {
        let (first, last) = baselines(for: size)
        var dims = ViewDimensions(size: size, firstBaseline: first, lastBaseline: last)
        for (id, guide) in guides { dims.explicit[id] = guide(dims) }
        return dims
    }
}

/// A node that wraps one child and changes nothing about layout by default.
@MainActor
class _WrapperNode: _Node {
    let child: _Node
    init(child: _Node) {
        self.child = child
        super.init(path: child.path, env: child.env)
        layoutPriority = child.layoutPriority
        guides = child.guides
        edgeBackground = child.edgeBackground
        separatorLeading = child.separatorLeading
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { child.sizeThatFits(proposal) }
    override func place(_ rect: CGRect) {
        frame = rect
        child.place(rect)
    }
    override func emit(_ list: _DisplayList) { child.emit(list) }
    override var isSpacer: Bool { child.isSpacer }
    override var isTextLike: Bool { child.isTextLike }
    /// A wrapper that only decorates (a background, a tap, a priority) keeps its child's spacing; ones that change
    /// the child's bounds (padding, frames) override this to be a plain view.
    override var textSpacing: _TextSpacing? { child.textSpacing }
    override func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) { child.baselines(for: size) }
}

/// Where [childSize] goes inside [rect] for [alignment].
@MainActor
func _align(_ child: _Node, _ childSize: CGSize, in rect: CGRect, _ alignment: Alignment) -> CGRect {
    let outer = ViewDimensions(size: rect.size)
    let inner = child.dimensions(childSize)
    let x = rect.minX + outer[alignment.horizontal] - inner[alignment.horizontal]
    let y = rect.minY + outer[alignment.vertical] - inner[alignment.vertical]
    return CGRect(origin: CGPoint(x: x, y: y), size: childSize)
}

// MARK: - Modifier plumbing

/// How an engine modifier acts: on the context its content resolves in, and/or on each node the content becomes.
@MainActor
protocol _NodeModifier {
    func _context(_ ctx: _ResolveContext) -> _ResolveContext
    func _wrap(_ node: _Node, _ ctx: _ResolveContext) -> _Node
    /// Whether the modifier wraps the content's nodes as ONE (backgrounds of a group) rather than each.
    var _wrapsAsOne: Bool { get }
}

extension _NodeModifier {
    func _context(_ ctx: _ResolveContext) -> _ResolveContext { ctx }
    func _wrap(_ node: _Node, _ ctx: _ResolveContext) -> _Node { node }
    var _wrapsAsOne: Bool { false }
}

struct _ModifiedView<Content: View, Modifier: _NodeModifier>: View, _PrimitiveView {
    let content: Content
    let modifier: Modifier
    var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let inner = modifier._context(ctx)
        let nodes = _resolveView(content, inner)
        // A modifier applies to each view its content is (as `.padding()` on a Group pads each member).
        return nodes.map { modifier._wrap($0, inner) }
    }
}

extension View {
    func _modified<M: _NodeModifier>(_ modifier: M) -> _ModifiedView<Self, M> {
        _ModifiedView(content: self, modifier: modifier)
    }
}

// MARK: - Stacks

/// HStack and VStack: SwiftUI's stack algorithm. Space left after spacing goes to children by layout priority (highest
/// first, lower priorities keeping their minimum), and within a priority to the least flexible first, each offered an
/// equal share of what remains.
@MainActor
final class _StackNode: _Node {
    let axis: Axis
    let alignment: Alignment
    let spacing: CGFloat?
    let children: [_Node]

    init(path: String, env: EnvironmentValues, axis: Axis, alignment: Alignment, spacing: CGFloat?, children: [_Node]) {
        self.axis = axis
        self.alignment = alignment
        self.spacing = spacing
        self.children = children
        super.init(path: path, env: env)
        // A row's separator follows its leading content: a Label first in a row (a NavigationLink's) sets it.
        if axis == .horizontal { separatorLeading = children.first?.separatorLeading }
    }

    private func gaps() -> [CGFloat] {
        guard children.count > 1 else { return [] }
        return (1..<children.count).map { i in spacing ?? _defaultSpacing(children[i - 1], children[i], axis: axis) }
    }

    /// The size each child takes for [proposal]: the allocation both passes share.
    private func allocate(_ proposal: ProposedViewSize) -> [CGSize] {
        let across = proposal[axis.cross]
        guard let along = proposal[axis] else {
            return children.map { $0.sizeThatFits(ProposedViewSize(axis: axis, along: nil, across: across)) }
        }
        var remaining = max(0, along - gaps().reduce(0, +))
        var sizes = [CGSize](repeating: .zero, count: children.count)
        let minAlong = children.map { $0.sizeThatFits(ProposedViewSize(axis: axis, along: 0, across: across))[axis] }
        let maxAlong = children.map { $0.sizeThatFits(ProposedViewSize(axis: axis, along: .infinity, across: across))[axis] }
        let priorities = Set(children.map(\.layoutPriority)).sorted(by: >)
        for priority in priorities {
            let group = children.indices.filter { children[$0].layoutPriority == priority }
            let reservedForLower = children.indices
                .filter { children[$0].layoutPriority < priority }
                .reduce(CGFloat(0)) { $0 + minAlong[$1] }
            var available = max(0, remaining - reservedForLower)
            let ordered = group.sorted { (maxAlong[$0] - minAlong[$0]) < (maxAlong[$1] - minAlong[$1]) }
            for (n, index) in ordered.enumerated() {
                let share = available / CGFloat(ordered.count - n)
                let size = children[index].sizeThatFits(ProposedViewSize(axis: axis, along: share, across: across))
                sizes[index] = size
                available = max(0, available - size[axis])
                remaining = max(0, remaining - size[axis])
            }
        }
        return sizes
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        if children.isEmpty { return .zero }
        let sizes = allocate(proposal)
        let along = sizes.reduce(0) { $0 + $1[axis] } + gaps().reduce(0, +)
        let across = crossExtent(sizes)
        return CGSize(axis: axis, along: along, across: across)
    }

    /// The cross size: the span the children cover once aligned on the stack's alignment guide.
    private func crossExtent(_ sizes: [CGSize]) -> CGFloat {
        var lo = CGFloat.infinity, hi = -CGFloat.infinity
        for (child, size) in zip(children, sizes) {
            let dims = child.dimensions(size)
            let guide = axis == .horizontal ? dims[alignment.vertical] : dims[alignment.horizontal]
            lo = min(lo, -guide)
            hi = max(hi, size[axis.cross] - guide)
        }
        return children.isEmpty ? 0 : hi - lo
    }

    override func place(_ rect: CGRect) {
        frame = rect
        let sizes = allocate(ProposedViewSize(rect.size))
        let gaps = gaps()
        // Cross alignment: every child's guide sits on one line, and that line is placed so the group is aligned in
        // the stack's own frame by the same guide.
        var lo = CGFloat.infinity
        for (child, size) in zip(children, sizes) {
            let dims = child.dimensions(size)
            let guide = axis == .horizontal ? dims[alignment.vertical] : dims[alignment.horizontal]
            lo = min(lo, -guide)
        }
        let groupCross = crossExtent(sizes)
        let outer = ViewDimensions(size: CGSize(axis: axis.cross, along: groupCross, across: 0))
        _ = outer
        let crossOrigin = axis == .horizontal ? rect.minY : rect.minX
        let crossSpan = rect.size[axis.cross]
        // Where the group's guide line falls inside the stack.
        let guideLine: CGFloat = {
            let fraction: CGFloat
            if axis == .horizontal {
                fraction = alignment.vertical == .top ? 0 : alignment.vertical == .bottom ? 1 : 0.5
            } else {
                fraction = alignment.horizontal == .leading ? 0 : alignment.horizontal == .trailing ? 1 : 0.5
            }
            let slack = crossSpan - groupCross
            return crossOrigin + slack * fraction - lo
        }()
        var cursor = axis == .horizontal ? rect.minX : rect.minY
        for (i, child) in children.enumerated() {
            let size = sizes[i]
            let dims = child.dimensions(size)
            let guide = axis == .horizontal ? dims[alignment.vertical] : dims[alignment.horizontal]
            let crossPos = guideLine - guide
            let origin = axis == .horizontal ? CGPoint(x: cursor, y: crossPos) : CGPoint(x: crossPos, y: cursor)
            child.place(CGRect(origin: origin, size: size))
            cursor += size[axis] + (i < gaps.count ? gaps[i] : 0)
        }
    }

    override func emit(_ list: _DisplayList) { children.forEach { $0.emit(list) } }

    override func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) {
        guard axis == .vertical, let first = children.first, let last = children.last else {
            return children.first?.baselines(for: size) ?? (nil, nil)
        }
        let sizes = allocate(ProposedViewSize(size))
        let firstBaseline = first.baselines(for: sizes[0]).first
        let lastSizes = sizes.dropLast().reduce(CGFloat(0)) { $0 + $1.height } + gaps().reduce(0, +)
        let lastBaseline = last.baselines(for: sizes[sizes.count - 1]).last.map { lastSizes + $0 }
        return (firstBaseline, lastBaseline)
    }
}

/// A text view's spacing preferences in a vertical stack, measured against iOS (tools/swiftui-parity, Probe.swift):
/// to another text, the larger of the two facing values (0 for a fixed-size font, a per-style value for Dynamic Type);
/// to anything else, a share of the point size (0.3626 above the text, 0.6102 below, each + 0.1416).
struct _TextSpacing {
    var topToText: CGFloat
    var bottomToText: CGFloat
    var topToOther: CGFloat
    var bottomToOther: CGFloat

    init(size: CGFloat, style: Font.TextStyle?) {
        topToOther = 0.36264 * size + 0.1416
        bottomToOther = 0.61020 * size + 0.1416
        let between: CGFloat
        switch style {
        case .largeTitle?, .title?: between = 2.0 / 3
        case .title3?: between = 4.0 / 3
        case .subheadline?: between = 7.0 / 3
        case .footnote?: between = 8.0 / 3
        case .caption2?: between = 0
        case nil, .extraLargeTitle?, .extraLargeTitle2?: between = 0
        default: between = 2
        }
        topToText = between
        // A Dynamic Type style is never further than 2 points from the body text that follows it.
        bottomToText = style == nil ? 0 : min(between, 2)
    }
}

/// The spacing a stack puts between two neighbours when it is given none. Across (HStack): the platform's 8 points.
/// Down (VStack): 8 between ordinary views, and text's own spacing where text is involved. None next to a Spacer,
/// whose own minimum length plays that part.
@MainActor
func _defaultSpacing(_ a: _Node, _ b: _Node, axis: Axis) -> CGFloat {
    if a.isSpacer || b.isSpacer { return 0 }
    guard axis == .vertical else { return 8 }
    switch (a.textSpacing, b.textSpacing) {
    case let (above?, below?): return max(above.bottomToText, below.topToText)
    case let (above?, nil): return above.bottomToOther
    case let (nil, below?): return below.topToOther
    case (nil, nil): return 8
    }
}

/// ZStack: every child is offered the stack's proposal, the stack takes the size of their aligned union.
@MainActor
final class _ZStackNode: _Node {
    let alignment: Alignment
    let children: [_Node]

    init(path: String, env: EnvironmentValues, alignment: Alignment, children: [_Node]) {
        self.alignment = alignment
        self.children = children
        super.init(path: path, env: env)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        var lo = CGPoint(x: CGFloat.infinity, y: .infinity), hi = CGPoint(x: -CGFloat.infinity, y: -CGFloat.infinity)
        for child in children {
            let size = child.sizeThatFits(proposal)
            let dims = child.dimensions(size)
            let gx = dims[alignment.horizontal], gy = dims[alignment.vertical]
            lo = CGPoint(x: min(lo.x, -gx), y: min(lo.y, -gy))
            hi = CGPoint(x: max(hi.x, size.width - gx), y: max(hi.y, size.height - gy))
        }
        return children.isEmpty ? .zero : CGSize(width: hi.x - lo.x, height: hi.y - lo.y)
    }

    override func place(_ rect: CGRect) {
        frame = rect
        for child in children {
            child.place(_align(child, child.sizeThatFits(ProposedViewSize(rect.size)), in: rect, alignment))
        }
    }

    override func emit(_ list: _DisplayList) { children.forEach { $0.emit(list) } }
}

/// Spacer: as long as the stack lets it be along the stack's axis (never shorter than its minimum), nothing across.
@MainActor
final class _SpacerNode: _Node {
    let minLength: CGFloat?
    let axis: Axis?

    init(path: String, env: EnvironmentValues, minLength: CGFloat?, axis: Axis?) {
        self.minLength = minLength
        self.axis = axis
        super.init(path: path, env: env)
    }

    override var isSpacer: Bool { true }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let minimum = minLength ?? 8
        switch axis {
        case .horizontal: return CGSize(width: max(minimum, proposal.width ?? minimum), height: 0)
        case .vertical: return CGSize(width: 0, height: max(minimum, proposal.height ?? minimum))
        case nil:
            return CGSize(width: max(minimum, proposal.width ?? minimum), height: max(minimum, proposal.height ?? minimum))
        }
    }
}

// MARK: - Padding, frames, offsets

@MainActor
final class _PaddingNode: _WrapperNode {
    override var textSpacing: _TextSpacing? { nil }
    let insets: EdgeInsets
    init(child: _Node, insets: EdgeInsets) {
        self.insets = insets
        super.init(child: child)
    }

    private func inner(_ proposal: ProposedViewSize) -> ProposedViewSize {
        ProposedViewSize(
            width: proposal.width.map { max(0, $0 - insets.horizontal) },
            height: proposal.height.map { max(0, $0 - insets.vertical) }
        )
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let size = child.sizeThatFits(inner(proposal))
        return CGSize(width: size.width + insets.horizontal, height: size.height + insets.vertical)
    }

    override func place(_ rect: CGRect) {
        frame = rect
        let inset = CGRect(
            x: rect.minX + insets.leading, y: rect.minY + insets.top,
            width: max(0, rect.width - insets.horizontal), height: max(0, rect.height - insets.vertical)
        )
        child.place(CGRect(origin: inset.origin, size: child.sizeThatFits(ProposedViewSize(inset.size))))
    }

    override func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) {
        let inner = CGSize(width: max(0, size.width - insets.horizontal), height: max(0, size.height - insets.vertical))
        let (f, l) = child.baselines(for: inner)
        return (f.map { $0 + insets.top }, l.map { $0 + insets.top })
    }
}

/// `.frame(width:height:alignment:)`: a fixed dimension where one is given, the child's where not.
@MainActor
final class _FixedFrameNode: _WrapperNode {
    override var textSpacing: _TextSpacing? { nil }
    let width: CGFloat?
    let height: CGFloat?
    let alignment: Alignment

    init(child: _Node, width: CGFloat?, height: CGFloat?, alignment: Alignment) {
        self.width = width
        self.height = height
        self.alignment = alignment
        super.init(child: child)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        if let width, let height { return CGSize(width: width, height: height) }
        let size = child.sizeThatFits(ProposedViewSize(width: width ?? proposal.width, height: height ?? proposal.height))
        return CGSize(width: width ?? size.width, height: height ?? size.height)
    }

    override func place(_ rect: CGRect) {
        frame = rect
        let size = child.sizeThatFits(ProposedViewSize(rect.size))
        child.place(_align(child, size, in: rect, alignment))
    }

    override func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) { (nil, nil) }
}

/// `.frame(minWidth:idealWidth:maxWidth:minHeight:idealHeight:maxHeight:alignment:)`, as SwiftUI documents it: the
/// child is proposed the frame's proposal (ideal dimensions standing in for unspecified ones) clamped to the limits;
/// with both limits in a dimension the frame takes the proposal clamped to them; with one, it takes the proposal
/// clamped to that limit when the proposal is past the child's size on that side; with none, the child's size.
@MainActor
final class _FlexFrameNode: _WrapperNode {
    override var textSpacing: _TextSpacing? { nil }
    let minWidth, idealWidth, maxWidth, minHeight, idealHeight, maxHeight: CGFloat?
    let alignment: Alignment

    init(child: _Node, minWidth: CGFloat?, idealWidth: CGFloat?, maxWidth: CGFloat?,
         minHeight: CGFloat?, idealHeight: CGFloat?, maxHeight: CGFloat?, alignment: Alignment) {
        self.minWidth = minWidth
        self.idealWidth = idealWidth
        self.maxWidth = maxWidth
        self.minHeight = minHeight
        self.idealHeight = idealHeight
        self.maxHeight = maxHeight
        self.alignment = alignment
        super.init(child: child)
    }

    private static func clamp(_ value: CGFloat, _ lo: CGFloat?, _ hi: CGFloat?) -> CGFloat {
        var v = value
        if let hi { v = min(v, hi) }
        if let lo { v = max(v, lo) }
        return v
    }

    private func childProposal(_ proposal: ProposedViewSize) -> ProposedViewSize {
        let w = (proposal.width ?? idealWidth).map { Self.clamp($0, minWidth, maxWidth) }
        let h = (proposal.height ?? idealHeight).map { Self.clamp($0, minHeight, maxHeight) }
        return ProposedViewSize(width: w, height: h)
    }

    private static func dimension(proposed: CGFloat?, ideal: CGFloat?, child: CGFloat, lo: CGFloat?, hi: CGFloat?) -> CGFloat {
        if lo == nil && hi == nil { return child }
        let proposed = proposed ?? ideal ?? child
        if lo != nil && hi != nil { return clamp(proposed, lo, hi) }
        // One limit: the proposal where it is past the child on the limit's side, never beyond the limit.
        if let lo { return max(lo, min(proposed, child)) }
        if let hi { return min(hi, max(proposed, child)) }
        return child
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let size = child.sizeThatFits(childProposal(proposal))
        return CGSize(
            width: Self.dimension(proposed: proposal.width, ideal: idealWidth, child: size.width, lo: minWidth, hi: maxWidth),
            height: Self.dimension(proposed: proposal.height, ideal: idealHeight, child: size.height, lo: minHeight, hi: maxHeight)
        )
    }

    override func place(_ rect: CGRect) {
        frame = rect
        let size = child.sizeThatFits(childProposal(ProposedViewSize(rect.size)))
        child.place(_align(child, size, in: rect, alignment))
    }

    override func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) { (nil, nil) }
}

@MainActor
final class _FixedSizeNode: _WrapperNode {
    let horizontal: Bool
    let vertical: Bool
    init(child: _Node, horizontal: Bool, vertical: Bool) {
        self.horizontal = horizontal
        self.vertical = vertical
        super.init(child: child)
    }
    private func adjust(_ p: ProposedViewSize) -> ProposedViewSize {
        ProposedViewSize(width: horizontal ? nil : p.width, height: vertical ? nil : p.height)
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { child.sizeThatFits(adjust(proposal)) }
    override func place(_ rect: CGRect) {
        frame = rect
        let size = child.sizeThatFits(adjust(ProposedViewSize(rect.size)))
        child.place(_align(child, size, in: rect, .center))
    }
}

@MainActor
final class _OffsetNode: _WrapperNode {
    let offset: CGSize
    init(child: _Node, offset: CGSize) {
        self.offset = offset
        super.init(child: child)
    }
    override func place(_ rect: CGRect) {
        frame = rect
        child.place(rect.offsetBy(dx: offset.width, dy: offset.height))
    }
}

/// `.background` and `.overlay`: the secondary view is offered the primary's size and aligned on it.
@MainActor
final class _LayeredNode: _WrapperNode {
    let secondary: [_Node]
    let alignment: Alignment
    let isOverlay: Bool

    init(child: _Node, secondary: [_Node], alignment: Alignment, isOverlay: Bool) {
        self.secondary = secondary
        self.alignment = alignment
        self.isOverlay = isOverlay
        super.init(child: child)
    }

    override func place(_ rect: CGRect) {
        frame = rect
        child.place(rect)
        for layer in secondary {
            layer.place(_align(layer, layer.sizeThatFits(ProposedViewSize(rect.size)), in: rect, alignment))
        }
    }

    override func emit(_ list: _DisplayList) {
        if !isOverlay { secondary.forEach { $0.emit(list) } }
        child.emit(list)
        if isOverlay { secondary.forEach { $0.emit(list) } }
    }
}

// MARK: - Layout modifiers

struct _WrapModifier: _NodeModifier {
    let wrap: @MainActor (_Node, _ResolveContext) -> _Node
    func _wrap(_ node: _Node, _ ctx: _ResolveContext) -> _Node { wrap(node, ctx) }
}

extension View {
    public func padding(_ insets: EdgeInsets) -> some View {
        _modified(_WrapModifier { node, _ in _PaddingNode(child: node, insets: insets) })
    }

    public func padding(_ edges: Edge.Set = .all, _ length: CGFloat? = nil) -> some View {
        let l = length ?? 16
        return padding(EdgeInsets(
            top: edges.contains(.top) ? l : 0,
            leading: edges.contains(.leading) ? l : 0,
            bottom: edges.contains(.bottom) ? l : 0,
            trailing: edges.contains(.trailing) ? l : 0
        ))
    }

    public func padding(_ length: CGFloat) -> some View { padding(.all, length) }

    public func frame(width: CGFloat? = nil, height: CGFloat? = nil, alignment: Alignment = .center) -> some View {
        _modified(_WrapModifier { node, _ in _FixedFrameNode(child: node, width: width, height: height, alignment: alignment) })
    }

    public func frame(
        minWidth: CGFloat? = nil, idealWidth: CGFloat? = nil, maxWidth: CGFloat? = nil,
        minHeight: CGFloat? = nil, idealHeight: CGFloat? = nil, maxHeight: CGFloat? = nil,
        alignment: Alignment = .center
    ) -> some View {
        _modified(_WrapModifier { node, _ in
            _FlexFrameNode(child: node, minWidth: minWidth, idealWidth: idealWidth, maxWidth: maxWidth,
                           minHeight: minHeight, idealHeight: idealHeight, maxHeight: maxHeight, alignment: alignment)
        })
    }

    public func fixedSize() -> some View { fixedSize(horizontal: true, vertical: true) }

    public func fixedSize(horizontal: Bool, vertical: Bool) -> some View {
        _modified(_WrapModifier { node, _ in _FixedSizeNode(child: node, horizontal: horizontal, vertical: vertical) })
    }

    public func layoutPriority(_ value: Double) -> some View {
        _modified(_WrapModifier { node, _ in
            let wrapper = _WrapperNode(child: node)
            wrapper.layoutPriority = value
            return wrapper
        })
    }

    public func offset(_ offset: CGSize) -> some View {
        _modified(_WrapModifier { node, _ in _OffsetNode(child: node, offset: offset) })
    }

    public func offset(x: CGFloat = 0, y: CGFloat = 0) -> some View { offset(CGSize(width: x, height: y)) }

    func _separatorLeading(_ offset: CGFloat) -> some View {
        _modified(_WrapModifier { node, _ in
            let wrapper = _WrapperNode(child: node)
            wrapper.separatorLeading = offset
            return wrapper
        })
    }

    public func alignmentGuide(_ g: HorizontalAlignment, computeValue: @escaping (ViewDimensions) -> CGFloat) -> some View {
        _modified(_WrapModifier { node, _ in
            let wrapper = _WrapperNode(child: node)
            wrapper.guides[g.id] = computeValue
            return wrapper
        })
    }

    public func alignmentGuide(_ g: VerticalAlignment, computeValue: @escaping (ViewDimensions) -> CGFloat) -> some View {
        _modified(_WrapModifier { node, _ in
            let wrapper = _WrapperNode(child: node)
            wrapper.guides[g.id] = computeValue
            return wrapper
        })
    }
}
