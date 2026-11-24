// CodeAssist's SwiftUI: the SwiftUI API, reimplemented to run where Apple's framework does not (Android, Linux), for
// previews. Declarations follow the public interface of Apple's SwiftUI so that code written for SwiftUI compiles
// unchanged; the implementation resolves views into a tree, lays it out with SwiftUI's rules (Layout.swift) and emits a
// positioned display list (DisplayList.swift) that the IDE paints.
//
// This file: the view protocol, view builders and the structural views (tuples, conditionals, optionals, AnyView,
// Group, EmptyView), and how a view resolves.

import Foundation

// MARK: - View

@MainActor @preconcurrency
public protocol View {
    associatedtype Body: View
    @ViewBuilder @MainActor @preconcurrency var body: Self.Body { get }
}

extension Never: View {
    public typealias Body = Never
    public var body: Never { fatalError("Never has no body") }
}

/// Implemented by the views the engine knows directly. A view that is not one is resolved through its `body`.
@MainActor
protocol _PrimitiveView {
    func _resolve(_ ctx: _ResolveContext) -> [_Node]
}

extension _PrimitiveView {
    /// What `body` returns for a primitive: it has none, the engine reads it directly.
    func _neverBody() -> Never { fatalError("\(Self.self) is a primitive view and has no body") }
}

// MARK: - View builder

@resultBuilder
public struct ViewBuilder {
    public static func buildBlock() -> EmptyView { EmptyView() }
    public static func buildBlock<Content: View>(_ content: Content) -> Content { content }
    public static func buildBlock<each Content: View>(_ content: repeat each Content) -> TupleView<(repeat each Content)> {
        TupleView((repeat each content))
    }
    public static func buildExpression<Content: View>(_ content: Content) -> Content { content }
    public static func buildIf<Content: View>(_ content: Content?) -> Content? { content }
    public static func buildOptional<Content: View>(_ content: Content?) -> Content? { content }
    public static func buildEither<TrueContent: View, FalseContent: View>(first: TrueContent) -> _ConditionalContent<TrueContent, FalseContent> {
        _ConditionalContent(storage: .trueContent(first))
    }
    public static func buildEither<TrueContent: View, FalseContent: View>(second: FalseContent) -> _ConditionalContent<TrueContent, FalseContent> {
        _ConditionalContent(storage: .falseContent(second))
    }
    public static func buildLimitedAvailability<Content: View>(_ content: Content) -> AnyView { AnyView(content) }
    public static func buildArray<Content: View>(_ components: [Content]) -> ForEach<Range<Int>, Int, Content> {
        ForEach(components.indices, id: \.self) { components[$0] }
    }
}

// MARK: - Structural views


public struct EmptyView: View, _PrimitiveView {
    public init() {}
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] { [] }
}


public struct TupleView<T>: View, _PrimitiveView {
    public var value: T
    public init(_ value: T) { self.value = value }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        // A tuple of views: each element resolves with its position as its identity.
        var nodes: [_Node] = []
        for (index, child) in Mirror(reflecting: value).children.enumerated() {
            guard let view = child.value as? any View else { continue }
            nodes += _resolveAny(view, ctx.child("\(index)"))
        }
        return nodes
    }
}


public struct _ConditionalContent<TrueContent: View, FalseContent: View>: View, _PrimitiveView {
    public enum Storage {
        case trueContent(TrueContent)
        case falseContent(FalseContent)
    }
    public let storage: Storage
    init(storage: Storage) { self.storage = storage }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        switch storage {
        case .trueContent(let view): return _resolveView(view, ctx.child("t"))
        case .falseContent(let view): return _resolveView(view, ctx.child("f"))
        }
    }
}

extension Optional: View where Wrapped: View {
    public typealias Body = Never
    public var body: Never { fatalError("Optional is a primitive view") }
}

extension Optional: _PrimitiveView where Wrapped: View {
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        switch self {
        case .some(let view): return _resolveView(view, ctx.child("s"))
        case .none: return []
        }
    }
}


public struct AnyView: View, _PrimitiveView {
    let storage: any View
    let typeID: ObjectIdentifier

    public init<V: View>(_ view: V) {
        if let any = view as? AnyView { self = any; return }
        storage = view
        typeID = ObjectIdentifier(V.self)
    }
    public init<V: View>(erasing view: V) { self.init(view) }
    public init?(_fromValue value: Any) {
        guard let view = value as? any View else { return nil }
        self.init(view)
    }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        // A different wrapped type is a different view: its state does not carry over.
        _resolveAny(storage, ctx.child("any\(typeID.hashValue)"))
    }
}


public struct Group<Content> {
    public var content: Content
}

extension Group: View, _PrimitiveView where Content: View {
    public init(@ViewBuilder content: () -> Content) { self.content = content() }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] { _resolveView(content, ctx) }
}

// MARK: - Resolving

/// Resolve [view] (any view) into nodes: primitives directly, anything else through its body, with its dynamic
/// properties (@State, @Binding, @Environment, ...) bound to this position in the tree first.
@MainActor
func _resolveView<V: View>(_ view: V, _ ctx: _ResolveContext) -> [_Node] {
    if let primitive = view as? any _PrimitiveView { return primitive._resolve(ctx) }
    let ctx = ctx.child(_typeName(V.self))
    _bindDynamicProperties(of: view, ctx)
    ctx.graph.currentPath = ctx.path
    return _resolveView(view.body, ctx)
}

@MainActor
func _resolveAny(_ view: any View, _ ctx: _ResolveContext) -> [_Node] {
    func open<V: View>(_ v: V) -> [_Node] { _resolveView(v, ctx) }
    return open(view)
}

func _typeName(_ type: Any.Type) -> String {
    let name = String(describing: type)
    // Generic arguments are part of identity too, but keep the key short.
    return name.count > 60 ? String(name.prefix(60)) + "#\(name.hashValue)" : name
}

/// Where a view is being resolved: its identity (path), its environment, the graph it belongs to.
@MainActor
final class _ResolveContext {
    let graph: _Graph
    let path: String
    var environment: EnvironmentValues

    init(graph: _Graph, path: String, environment: EnvironmentValues) {
        self.graph = graph
        self.path = path
        self.environment = environment
    }

    func child(_ component: String) -> _ResolveContext {
        _ResolveContext(graph: graph, path: path + "/" + component, environment: environment)
    }

    func with(_ transform: (inout EnvironmentValues) -> Void) -> _ResolveContext {
        var env = environment
        transform(&env)
        return _ResolveContext(graph: graph, path: path, environment: env)
    }
}
