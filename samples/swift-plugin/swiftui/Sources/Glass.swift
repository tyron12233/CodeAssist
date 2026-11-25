// Liquid Glass (iOS 26): the Glass API, and the node that has the host draw glass from what lies behind it.
//
// Glass cannot be a plain fill: it is computed from the content underneath (a lens at the rim that samples further
// in, a veil that lifts it towards white, a light blur, a bright rim, a wide soft shadow). So a glass view emits a
// "glass" operation before its content, and the painter, which has already painted everything behind it, processes
// that region (SwiftPreviewPainter on the device, Ours.swift in the parity harness). The parameters of that model are
// fitted against the iOS Simulator by tools/swiftui-parity (GlassCases.swift).

import Foundation

public struct Glass: Equatable, Sendable {
    enum Variant: String, Sendable { case regular, clear, identity }
    let variant: Variant
    var tintColor: Color?
    var isInteractive = false

    public static var regular: Glass { Glass(variant: .regular) }
    public static var clear: Glass { Glass(variant: .clear) }
    public static var identity: Glass { Glass(variant: .identity) }

    public func tint(_ color: Color?) -> Glass { var g = self; g.tintColor = color; return g }
    public func interactive(_ isEnabled: Bool = true) -> Glass { var g = self; g.isInteractive = isEnabled; return g }
}

/// A glass effect's default shape: a capsule.
public struct DefaultGlassEffectShape: Shape {
    public init() {}
    public func path(in rect: CGRect) -> Path { Capsule().path(in: rect) }
}

public struct GlassEffectTransition: Sendable {
    public static var matchedGeometry: GlassEffectTransition { GlassEffectTransition() }
    public static var materialize: GlassEffectTransition { GlassEffectTransition() }
    public static var identity: GlassEffectTransition { GlassEffectTransition() }
}

/// Groups glass shapes so that, on iOS, nearby ones blend into each other. Previews draw each shape on its own.
public struct GlassEffectContainer<Content: View>: View {
    let spacing: CGFloat?
    let content: Content
    public init(spacing: CGFloat? = nil, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }
    public var body: some View { content }
}

@propertyWrapper
public struct Namespace: DynamicProperty, Sendable {
    public struct ID: Hashable, Sendable { let value: Int }
    private static let counter = _NamespaceCounter()
    let id: Int
    public init() { id = Namespace.counter.next() }
    public var wrappedValue: ID { ID(value: id) }
}

final class _NamespaceCounter: @unchecked Sendable {
    private var value = 0
    private let lock = NSLock()
    func next() -> Int { lock.lock(); defer { lock.unlock() }; value += 1; return value }
}

extension View {
    public func glassEffect(_ glass: Glass = .regular, in shape: some Shape = DefaultGlassEffectShape()) -> some View {
        _modified(_WrapModifier { node, ctx in
            glass.variant == .identity ? node : _GlassNode(child: node, glass: glass, shape: _AnyShape(shape))
        })
    }
    public func glassEffectID(_ id: (some Hashable & Sendable)?, in namespace: Namespace.ID) -> some View { self }
    public func glassEffectUnion(id: (some Hashable & Sendable)?, namespace: Namespace.ID) -> some View { self }
    public func glassEffectTransition(_ transition: GlassEffectTransition) -> some View { self }
    public func matchedGeometryEffect<ID: Hashable>(id: ID, in namespace: Namespace.ID, properties: MatchedGeometryProperties = .frame,
                                                    anchor: UnitPoint = .center, isSource: Bool = true) -> some View { self }
    public func backgroundExtensionEffect() -> some View { self }
}

public struct MatchedGeometryProperties: OptionSet, Sendable {
    public let rawValue: UInt32
    public init(rawValue: UInt32) { self.rawValue = rawValue }
    public static let position = MatchedGeometryProperties(rawValue: 1)
    public static let size = MatchedGeometryProperties(rawValue: 2)
    public static let frame: MatchedGeometryProperties = [.position, .size]
}

/// A view with glass behind it: the glass operation (its shape in place, variant, tint), then the view.
@MainActor
final class _GlassNode: _WrapperNode {
    let glass: Glass
    let shape: _AnyShape

    init(child: _Node, glass: Glass, shape: _AnyShape) {
        self.glass = glass
        self.shape = shape
        super.init(child: child)
    }

    override func emit(_ list: _DisplayList) {
        let path = shape.path(CGRect(origin: .zero, size: frame.size)).offsetBy(frame.minX, frame.minY)
        list.glass(path, bounds: frame, variant: glass.variant.rawValue,
                   tint: glass.tintColor?.resolve(env), dark: env.colorScheme == .dark)
        child.emit(list)
    }
}

// MARK: - Glass buttons

public struct GlassButtonStyle: PrimitiveButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct GlassProminentButtonStyle: PrimitiveButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
extension PrimitiveButtonStyle where Self == GlassButtonStyle { public static var glass: GlassButtonStyle { GlassButtonStyle() } }
extension PrimitiveButtonStyle where Self == GlassProminentButtonStyle { public static var glassProminent: GlassProminentButtonStyle { GlassProminentButtonStyle() } }
