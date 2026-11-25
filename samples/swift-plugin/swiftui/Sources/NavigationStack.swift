// NavigationStack: its bar (titles, toolbar items on glass, the back button) and its stack of pushed screens, which
// NavigationLink, navigationDestination and a path binding drive. Measured on the iPhone 17 Pro Simulator
// (tools/swiftui-parity ChromeCases.swift: nav-*): a pushed screen keeps a large title when the one under it had one,
// and gets a 44-point glass circle at the bar's leading edge with chevron.left (large image scale) in it.

import Foundation

/// A screen pushed onto a NavigationStack: a value one of its navigationDestination(for:) shows, or a view.
enum _PushEntry {
    case value(AnyHashable)
    case view((_ResolveContext) -> [_Node])
}

/// What NavigationLinks push through: the nearest NavigationStack's stack.
@MainActor
final class _Navigator {
    let push: (_PushEntry) -> Void
    init(push: @escaping (_PushEntry) -> Void) { self.push = push }
}
struct _NavigatorKey: EnvironmentKey { static let defaultValue: _Navigator? = nil }

public struct NavigationPath: Equatable {
    var elements: [AnyHashable] = []
    public init() {}
    public init<S: Sequence>(_ elements: S) where S.Element: Hashable { self.elements = elements.map { AnyHashable($0) } }
    public var count: Int { elements.count }
    public var isEmpty: Bool { elements.isEmpty }
    public mutating func append<V: Hashable>(_ value: V) { elements.append(AnyHashable(value)) }
    public mutating func removeLast(_ k: Int = 1) { elements.removeLast(k) }
}

public struct NavigationStack<Root: View>: View, _PrimitiveView {
    let root: Root
    let pathGet: (() -> [AnyHashable])?
    let pathSet: (([AnyHashable]) -> Void)?

    public init(@ViewBuilder root: () -> Root) {
        self.root = root()
        pathGet = nil
        pathSet = nil
    }
    public init(path: Binding<NavigationPath>, @ViewBuilder root: () -> Root) {
        self.root = root()
        pathGet = { path.wrappedValue.elements }
        pathSet = { path.wrappedValue.elements = $0 }
    }
    public init<Data>(path: Binding<Data>, @ViewBuilder root: () -> Root)
        where Data: MutableCollection, Data: RandomAccessCollection, Data: RangeReplaceableCollection, Data.Element: Hashable {
        self.root = root()
        pathGet = { path.wrappedValue.map { AnyHashable($0) } }
        pathSet = { path.wrappedValue = Data($0.compactMap { $0.base as? Data.Element }) }
    }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        // The stack: the path binding's values, then views and values pushed without one (kept by the engine).
        let cell = ctx.graph.cell(ctx.path + "/stack") { [_PushEntry]() }
        let bound = pathGet?() ?? []
        let entries = bound.map(_PushEntry.value) + cell.value
        let pathSet = self.pathSet
        let navigator = _Navigator { entry in
            if case .value(let v) = entry, let pathSet, cell.value.isEmpty {
                pathSet((self.pathGet?() ?? []) + [v])
            } else {
                cell.value.append(entry)
            }
        }
        func pop(to depth: Int) -> () -> Void {
            { // Leave `depth` screens on the stack (0: back to the root).
                if depth < bound.count {
                    pathSet?(Array(bound.prefix(depth)))
                    cell.value = []
                } else {
                    cell.value = Array(cell.value.prefix(depth - bound.count))
                }
            }
        }
        let root = self.root
        let base = ctx.with { $0[_NavigatorKey.self] = navigator }

        // Walk up the stack: each screen's navigationDestination handlers say how the values above it look, and a
        // destination it presents goes over it. Only the top screen is resolved for real.
        var destinations: [ObjectIdentifier: (AnyHashable, _ResolveContext) -> [_Node]] = [:]
        var resolve: (_ResolveContext) -> [_Node] = { _resolveView(root, $0) }
        var back: (() -> Void)?
        var depth = 0
        var large = false
        var box = _TitleBox()
        while true {
            let pageCtx = depth == 0 ? base : base.child("page\(depth)")
            box = _TitleBox()
            _ = resolve(pageCtx.child("probe").with { $0[_NavigationTitleKey.self] = box; $0._stackAxis = nil; $0._isProbe = true })
            // A large title stays large on screens pushed over one, unless they say otherwise.
            let mode = box.displayMode ?? .automatic
            large = !box.barHidden && box.title != nil && (mode == .large || (mode == .automatic && (depth == 0 || large)))
            destinations.merge(box.destinations) { $1 }
            let next: ((_ResolveContext) -> [_Node], () -> Void)?
            if depth < entries.count {
                switch entries[depth] {
                case .view(let r): next = (r, pop(to: depth))
                case .value(let v):
                    if let handler = destinations[ObjectIdentifier(type(of: v.base))] {
                        next = ({ handler(v, $0) }, pop(to: depth))
                    } else {
                        next = nil
                    }
                }
            } else if let shown = box.presented.first {
                next = (shown.resolve, shown.pop)
            } else {
                next = nil
            }
            guard let (r, p) = next, depth < 32 else { break }
            resolve = r
            back = p
            depth += 1
        }
        let pageCtx = depth == 0 ? base : base.child("page\(depth)")
        return [_navigationPage(pageCtx, resolve: resolve, probe: box, large: large, back: box.backHidden ? nil : back)]
    }
}

/// One screen of a NavigationStack with its bar, from what a probe of it found (title, display mode, items).
@MainActor
func _navigationPage(_ ctx: _ResolveContext, resolve: (_ResolveContext) -> [_Node], probe: _TitleBox, large: Bool, back: (() -> Void)?) -> _Node {
    let hidden = probe.barHidden
    let barHeight: CGFloat = hidden ? 0 : large ? _BarMetrics.largeHeight : _BarMetrics.inlineHeight
    let env = ctx.environment
    var safe = env._safeRect
    let barTop = hidden ? 0 : env[_BarTopKey.self]
    safe.origin.y += barHeight + barTop
    safe.size.height -= barHeight + barTop
    if probe.items.contains(where: { $0.placement == .bottom }) {
        safe.size.height = max(0, min(safe.maxY, env._screenRect.maxY - _BarMetrics.bottomInset) - safe.minY)
    }
    let box = _TitleBox()
    let content = resolve(ctx.with {
        $0[_NavigationTitleKey.self] = box
        $0._stackAxis = nil
        $0[_UnderLargeTitleKey.self] = large
        $0._safeRect = safe
        $0[_BarTopKey.self] = 0
    })
    let body = content.count == 1 ? content[0]
        : _StackNode(path: ctx.path + "/root", env: env, axis: .vertical, alignment: .center, spacing: nil, children: content)
    var largeTitle: _Node?, inlineTitle: _Node?
    if !hidden, let title = box.title {
        if large {
            let titleCtx = ctx.child("title").with { $0.font = Font(size: 34, weight: .bold); $0._stackAxis = .vertical; $0._foreground = nil }
            largeTitle = _resolveView(Text(verbatim: title), titleCtx).first
        } else {
            let titleCtx = ctx.child("title").with { $0.font = Font(size: 17, weight: .semibold); $0._stackAxis = .vertical; $0._foreground = nil; $0.lineLimit = 1 }
            inlineTitle = _resolveView(Text(verbatim: title), titleCtx).first
        }
    }
    var leading: [_Node] = [], trailing: [_Node] = [], bottom: [[_Node]] = [[]]
    var backButton: _Node?
    if !hidden {
        for item in box.items {
            switch item.placement {
            case .leading: leading += item.nodes
            case .trailing: trailing += item.nodes
            case .principal: inlineTitle = item.nodes.first
            case .bottom:
                if item.splits { bottom.append([]) } else { bottom[bottom.count - 1] += item.nodes }
            }
        }
        if let back {
            let chevronCtx = ctx.child("back").with {
                $0.font = .body
                $0.imageScale = .large
                $0._weight = .semibold
                $0._foreground = AnyShapeStyle(_Chrome.barLabel)
                $0._stackAxis = nil
            }
            backButton = _resolveView(Image(systemName: "chevron.left"), chevronCtx).first.map { _TapNode(child: $0, action: back) }
        }
    }
    bottom = bottom.filter { !$0.isEmpty }
    return _NavStackNode(path: ctx.path, env: env, content: body, largeTitle: largeTitle, inlineTitle: inlineTitle,
                         back: backButton, leading: leading, trailing: trailing, bottomGroups: bottom, barHeight: barHeight)
}

extension View {
    public func navigationDestination<D: Hashable, C: View>(for data: D.Type, @ViewBuilder destination: @escaping (D) -> C) -> some View {
        _modified(_BoxModifier { box in
            box.destinations[ObjectIdentifier(D.self)] = { value, ctx in
                guard let d = value.base as? D else { return [] }
                return _resolveView(destination(d), ctx)
            }
        })
    }
    public func navigationDestination<V: View>(isPresented: Binding<Bool>, @ViewBuilder destination: () -> V) -> some View {
        let view = destination()
        return _modified(_BoxModifier { box in
            if isPresented.wrappedValue { box.presented.append(({ _resolveView(view, $0) }, { isPresented.wrappedValue = false })) }
        })
    }
    public func navigationDestination<D: Hashable, C: View>(item: Binding<D?>, @ViewBuilder destination: @escaping (D) -> C) -> some View {
        _modified(_BoxModifier { box in
            if let value = item.wrappedValue { box.presented.append(({ _resolveView(destination(value), $0) }, { item.wrappedValue = nil })) }
        })
    }
    public func navigationBarBackButtonHidden(_ hidesBackButton: Bool = true) -> some View {
        _modified(_BoxModifier { if hidesBackButton { $0.backHidden = true } })
    }
}

/// iOS 26's NavigationLink: in a List, a row with a disclosure chevron (chevron.right at body size, tertiary label);
/// elsewhere a tinted label, as a borderless button. Either pushes its destination onto the nearest NavigationStack.
public struct NavigationLink<Label: View, Destination: View>: View, _PrimitiveView {
    let label: Label
    let entry: () -> _PushEntry?
    public var body: Never { _neverBody() }

    init(label: Label, entry: @escaping () -> _PushEntry?) {
        self.label = label
        self.entry = entry
    }
    public init(destination: Destination, @ViewBuilder label: () -> Label) {
        self.label = label()
        entry = { .view { _resolveView(destination, $0) } }
    }
    public init(@ViewBuilder destination: () -> Destination, @ViewBuilder label: () -> Label) {
        self.init(destination: destination(), label: label)
    }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let env = ctx.environment
        let navigator = env[_NavigatorKey.self]
        let entry = self.entry
        let action = { if let navigator, let e = entry() { navigator.push(e) } }
        let nodes: [_Node]
        if env[_InListKey.self] {
            nodes = _resolveView(HStack {
                label
                Spacer()
                Image(systemName: "chevron.right").font(.body).foregroundStyle(Color.tertiaryLabel)
            }, ctx.child("row"))
        } else {
            let tint: Color = env._tint ?? .accentColor
            nodes = _resolveView(label, ctx.child("label").with { $0._foreground = AnyShapeStyle(env.isEnabled ? tint : Color.gray) })
        }
        guard env.isEnabled else { return nodes }
        return nodes.map { _TapNode(child: $0, action: action) }
    }
}

extension NavigationLink where Label == Text {
    public init(_ titleKey: LocalizedStringKey, destination: Destination) { self.init(destination: destination) { Text(titleKey) } }
    public init<S: StringProtocol>(_ title: S, destination: Destination) { self.init(destination: destination) { Text(title) } }
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder destination: () -> Destination) {
        self.init(destination: destination()) { Text(titleKey) }
    }
    public init<S: StringProtocol>(_ title: S, @ViewBuilder destination: () -> Destination) {
        self.init(destination: destination()) { Text(title) }
    }
}

extension NavigationLink where Destination == EmptyView {
    public init<P: Hashable>(value: P?, @ViewBuilder label: () -> Label) {
        self.init(label: label(), entry: { value.map { .value(AnyHashable($0)) } })
    }
}

extension NavigationLink where Destination == EmptyView, Label == Text {
    public init<P: Hashable>(_ titleKey: LocalizedStringKey, value: P?) {
        self.init(label: Text(titleKey), entry: { value.map { .value(AnyHashable($0)) } })
    }
    public init<S: StringProtocol, P: Hashable>(_ title: S, value: P?) {
        self.init(label: Text(title), entry: { value.map { .value(AnyHashable($0)) } })
    }
}
