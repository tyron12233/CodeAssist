// API the audit against Apple's SwiftUI interface found missing, beyond views: gestures, materials, preferences,
// animatable data, symbol effects and content transitions, and the common modifiers. Where a still preview can show
// what they do they do it (position lays out, allowsHitTesting(false) stops taps, a tap gesture runs its action, a
// material fills); the rest are accepted so code compiles, and leave the view as it is.

import Foundation

// MARK: - Gestures

/// A gesture as the engine runs it: previews receive taps only, so what a gesture does on a tap.
@MainActor
protocol _TapGesture {
    var _onTap: (() -> Void)? { get }
}

@MainActor @preconcurrency
public protocol Gesture {
    associatedtype Value
    associatedtype Body: Gesture
    var body: Body { get }
}
extension Never: Gesture { public typealias Value = Never }
extension Gesture {
    public var body: Never { fatalError("\(Self.self) is a primitive gesture") }
}

public struct TapGesture: Gesture, _TapGesture {
    public typealias Value = Void
    public typealias Body = Never
    public var count: Int
    public init(count: Int = 1) { self.count = count }
    var _onTap: (() -> Void)? { nil }
}
public struct SpatialTapGesture: Gesture, _TapGesture {
    public struct Value: Equatable, Sendable { public var location: CGPoint }
    public typealias Body = Never
    public init(count: Int = 1, coordinateSpace: CoordinateSpace = .local) {}
    var _onTap: (() -> Void)? { nil }
}
public struct LongPressGesture: Gesture, _TapGesture {
    public typealias Value = Bool
    public typealias Body = Never
    public init(minimumDuration: Double = 0.5, maximumDistance: CGFloat = 10) {}
    var _onTap: (() -> Void)? { nil }
}
public struct DragGesture: Gesture, _TapGesture {
    public struct Value: Equatable, Sendable {
        public var time: Date = Date()
        public var location: CGPoint = .zero
        public var startLocation: CGPoint = .zero
        public var translation: CGSize = .zero
        public var velocity: CGSize = .zero
        public var predictedEndLocation: CGPoint = .zero
        public var predictedEndTranslation: CGSize = .zero
    }
    public typealias Body = Never
    public init(minimumDistance: CGFloat = 10, coordinateSpace: CoordinateSpace = .local) {}
    var _onTap: (() -> Void)? { nil }
}
public struct MagnifyGesture: Gesture, _TapGesture {
    public struct Value: Equatable, Sendable { public var time: Date = Date(); public var magnification: CGFloat = 1; public var velocity: CGFloat = 0; public var startAnchor: UnitPoint = .center; public var startLocation: CGPoint = .zero }
    public typealias Body = Never
    public init(minimumScaleDelta: CGFloat = 0.01) {}
    var _onTap: (() -> Void)? { nil }
}
public struct RotateGesture: Gesture, _TapGesture {
    public struct Value: Equatable, Sendable { public var time: Date = Date(); public var rotation: Angle = .zero; public var velocity: Angle = .zero; public var startAnchor: UnitPoint = .center; public var startLocation: CGPoint = .zero }
    public typealias Body = Never
    public init(minimumAngleDelta: Angle = .degrees(1)) {}
    var _onTap: (() -> Void)? { nil }
}
public struct MagnificationGesture: Gesture, _TapGesture {
    public typealias Value = CGFloat
    public typealias Body = Never
    public init(minimumScaleDelta: CGFloat = 0.01) {}
    var _onTap: (() -> Void)? { nil }
}
public struct RotationGesture: Gesture, _TapGesture {
    public typealias Value = Angle
    public typealias Body = Never
    public init(minimumAngleDelta: Angle = .degrees(1)) {}
    var _onTap: (() -> Void)? { nil }
}

/// A gesture with an action for when it ends: a tap runs it (with the value a tap gives).
public struct _EndedGesture<G: Gesture>: Gesture, _TapGesture {
    public typealias Value = G.Value
    public typealias Body = Never
    let base: G
    let tap: (() -> Void)?
    var _onTap: (() -> Void)? {
        let inner = (base as? _TapGesture)?._onTap
        return { inner?(); self.tap?() }
    }
}
/// A gesture whose changes or updates the preview never produces: it behaves as the gesture it wraps.
public struct _ModifiedGesture<G: Gesture, V>: Gesture, _TapGesture {
    public typealias Value = V
    public typealias Body = Never
    let base: G
    var _onTap: (() -> Void)? { (base as? _TapGesture)?._onTap }
}
public struct SimultaneousGesture<First: Gesture, Second: Gesture>: Gesture, _TapGesture {
    public struct Value { public var first: First.Value?; public var second: Second.Value? }
    public typealias Body = Never
    let first: First, second: Second
    var _onTap: (() -> Void)? {
        let a = (first as? _TapGesture)?._onTap, b = (second as? _TapGesture)?._onTap
        if a == nil && b == nil { return nil }
        return { a?(); b?() }
    }
}
public struct SequenceGesture<First: Gesture, Second: Gesture>: Gesture, _TapGesture {
    public enum Value { case first(First.Value), second(First.Value, Second.Value?) }
    public typealias Body = Never
    let first: First, second: Second
    var _onTap: (() -> Void)? { (first as? _TapGesture)?._onTap }
}
public struct ExclusiveGesture<First: Gesture, Second: Gesture>: Gesture, _TapGesture {
    public enum Value { case first(First.Value), second(Second.Value) }
    public typealias Body = Never
    let first: First, second: Second
    var _onTap: (() -> Void)? { (first as? _TapGesture)?._onTap ?? (second as? _TapGesture)?._onTap }
}

/// What a tap produces as each gesture's value.
func _tapValue<V>(_ type: V.Type) -> V? {
    switch type {
    case is Void.Type: return (() as! V)
    case is Bool.Type: return (true as! V)
    case is SpatialTapGesture.Value.Type: return (SpatialTapGesture.Value(location: .zero) as! V)
    default: return nil
    }
}

extension Gesture {
    public func onEnded(_ action: @escaping (Value) -> Void) -> _EndedGesture<Self> {
        _EndedGesture(base: self, tap: _tapValue(Value.self).map { v in { action(v) } })
    }
    public func onChanged(_ action: @escaping (Value) -> Void) -> _ModifiedGesture<Self, Value> where Value: Equatable { _ModifiedGesture(base: self) }
    public func map<T>(_ body: @escaping (Value) -> T) -> _ModifiedGesture<Self, T> { _ModifiedGesture(base: self) }
    public func updating<State>(_ state: GestureState<State>, body: @escaping (Value, inout State, inout Transaction) -> Void) -> _ModifiedGesture<Self, Value> {
        _ModifiedGesture(base: self)
    }
    public func simultaneously<Other: Gesture>(with other: Other) -> SimultaneousGesture<Self, Other> { SimultaneousGesture(first: self, second: other) }
    public func sequenced<Other: Gesture>(before other: Other) -> SequenceGesture<Self, Other> { SequenceGesture(first: self, second: other) }
    public func exclusively<Other: Gesture>(before other: Other) -> ExclusiveGesture<Self, Other> { ExclusiveGesture(first: self, second: other) }
}

public struct GestureMask: OptionSet, Sendable {
    public let rawValue: UInt32
    public init(rawValue: UInt32) { self.rawValue = rawValue }
    public static let none = GestureMask([]), gesture = GestureMask(rawValue: 1), subviews = GestureMask(rawValue: 2)
    public static let all: GestureMask = [.gesture, .subviews]
}

/// @GestureState: its initial value, since previews have no gesture in progress.
@propertyWrapper
public struct GestureState<Value>: DynamicProperty {
    let initial: Value
    public init(wrappedValue: Value) { initial = wrappedValue }
    public init(initialValue: Value) { initial = initialValue }
    public init(wrappedValue: Value, resetTransaction: Transaction) { initial = wrappedValue }
    public var wrappedValue: Value { initial }
    public var projectedValue: GestureState<Value> { self }
}

extension View {
    public func gesture<G: Gesture>(_ gesture: G, including mask: GestureMask = .all) -> some View {
        _modified(_WrapModifier { node, _ in
            if let tap = (gesture as? _TapGesture)?._onTap { return _TapNode(child: node, action: tap) }
            return node
        })
    }
    public func gesture<G: Gesture>(_ gesture: G, isEnabled: Bool) -> some View { self.gesture(gesture, including: isEnabled ? .all : .none) }
    public func simultaneousGesture<G: Gesture>(_ gesture: G, including mask: GestureMask = .all) -> some View { self.gesture(gesture, including: mask) }
    public func highPriorityGesture<G: Gesture>(_ gesture: G, including mask: GestureMask = .all) -> some View { self.gesture(gesture, including: mask) }
    public func defersSystemGestures(on edges: Edge.Set) -> some View { self }
}

// MARK: - Materials

/// A material, drawn as the translucent white (or dark) fill it reads as over a plain background.
public struct Material: ShapeStyle, _ResolvableStyle, Sendable {
    let lightAlpha: Double, darkAlpha: Double
    public static let ultraThin = Material(lightAlpha: 0.5, darkAlpha: 0.45)
    public static let thin = Material(lightAlpha: 0.62, darkAlpha: 0.6)
    public static let regular = Material(lightAlpha: 0.72, darkAlpha: 0.7)
    public static let thick = Material(lightAlpha: 0.86, darkAlpha: 0.82)
    public static let ultraThick = Material(lightAlpha: 0.94, darkAlpha: 0.9)
    public static let bar = Material(lightAlpha: 0.8, darkAlpha: 0.8)
    func _resolvedColor(_ env: EnvironmentValues) -> Color {
        Color(light: _RGBA(0.98, 0.98, 0.98, lightAlpha), dark: _RGBA(0.11, 0.11, 0.12, darkAlpha))
    }
}
extension ShapeStyle where Self == Material {
    public static var ultraThinMaterial: Material { .ultraThin }
    public static var thinMaterial: Material { .thin }
    public static var regularMaterial: Material { .regular }
    public static var thickMaterial: Material { .thick }
    public static var ultraThickMaterial: Material { .ultraThick }
    public static var bar: Material { .bar }
}

// MARK: - Preferences

public protocol PreferenceKey {
    associatedtype Value
    static var defaultValue: Value { get }
    static func reduce(value: inout Value, nextValue: () -> Value)
}
extension PreferenceKey where Value == Void {
    public static var defaultValue: Void { () }
    public static func reduce(value: inout Void, nextValue: () -> Void) {}
}

/// An anchor: a geometry value that a GeometryProxy turns into a value in its own space.
public struct Anchor<Value> {
    public struct Source {}
}
extension Anchor.Source where Value == CGRect {
    public static var bounds: Anchor<CGRect>.Source { Anchor<CGRect>.Source() }
    public static func rect(_ r: CGRect) -> Anchor<CGRect>.Source { Anchor<CGRect>.Source() }
}
extension Anchor.Source where Value == CGPoint {
    public static var center: Anchor<CGPoint>.Source { Anchor<CGPoint>.Source() }
    public static var top: Anchor<CGPoint>.Source { Anchor<CGPoint>.Source() }
    public static var bottom: Anchor<CGPoint>.Source { Anchor<CGPoint>.Source() }
    public static var leading: Anchor<CGPoint>.Source { Anchor<CGPoint>.Source() }
    public static var trailing: Anchor<CGPoint>.Source { Anchor<CGPoint>.Source() }
    public static func point(_ p: CGPoint) -> Anchor<CGPoint>.Source { Anchor<CGPoint>.Source() }
    public static func unitPoint(_ p: UnitPoint) -> Anchor<CGPoint>.Source { Anchor<CGPoint>.Source() }
}

extension View {
    /// Preferences flow up a tree previews resolve once, top down: a preference set below reaches no observer above,
    /// which sees the key's default value.
    public func preference<K: PreferenceKey>(key: K.Type = K.self, value: K.Value) -> some View { self }
    public func transformPreference<K: PreferenceKey>(_ key: K.Type = K.self, _ callback: @escaping (inout K.Value) -> Void) -> some View { self }
    public func anchorPreference<A, K: PreferenceKey>(key: K.Type = K.self, value: Anchor<A>.Source, transform: @escaping (Anchor<A>) -> K.Value) -> some View { self }
    public func transformAnchorPreference<A, K: PreferenceKey>(key: K.Type = K.self, value: Anchor<A>.Source, transform: @escaping (inout K.Value, Anchor<A>) -> Void) -> some View { self }
    public func onPreferenceChange<K: PreferenceKey>(_ key: K.Type = K.self, perform action: @escaping (K.Value) -> Void) -> some View where K.Value: Equatable { self }
    public func overlayPreferenceValue<K: PreferenceKey, V: View>(_ key: K.Type, alignment: Alignment = .center, @ViewBuilder _ transform: @escaping (K.Value) -> V) -> some View {
        overlay(alignment: alignment) { transform(K.defaultValue) }
    }
    public func backgroundPreferenceValue<K: PreferenceKey, V: View>(_ key: K.Type, alignment: Alignment = .center, @ViewBuilder _ transform: @escaping (K.Value) -> V) -> some View {
        background(alignment: alignment) { transform(K.defaultValue) }
    }
}

// MARK: - Animatable data

public protocol VectorArithmetic: AdditiveArithmetic {
    mutating func scale(by rhs: Double)
    var magnitudeSquared: Double { get }
}
extension Double: VectorArithmetic {
    public mutating func scale(by rhs: Double) { self *= rhs }
    public var magnitudeSquared: Double { self * self }
}
extension Float: VectorArithmetic {
    public mutating func scale(by rhs: Double) { self *= Float(rhs) }
    public var magnitudeSquared: Double { Double(self * self) }
}
#if !canImport(Darwin)
extension CGFloat: VectorArithmetic {
    public mutating func scale(by rhs: Double) { self *= CGFloat(rhs) }
    public var magnitudeSquared: Double { Double(self * self) }
}
#else
extension CGFloat: VectorArithmetic {
    public mutating func scale(by rhs: Double) { self *= CGFloat(rhs) }
    public var magnitudeSquared: Double { Double(self * self) }
}
#endif
public struct EmptyAnimatableData: VectorArithmetic {
    public init() {}
    public static var zero: EmptyAnimatableData { EmptyAnimatableData() }
    public static func + (l: Self, r: Self) -> Self { l }
    public static func - (l: Self, r: Self) -> Self { l }
    public mutating func scale(by rhs: Double) {}
    public var magnitudeSquared: Double { 0 }
}
public struct AnimatablePair<First: VectorArithmetic, Second: VectorArithmetic>: VectorArithmetic {
    public var first: First
    public var second: Second
    public init(_ first: First, _ second: Second) { self.first = first; self.second = second }
    public static var zero: Self { Self(.zero, .zero) }
    public static func + (l: Self, r: Self) -> Self { Self(l.first + r.first, l.second + r.second) }
    public static func - (l: Self, r: Self) -> Self { Self(l.first - r.first, l.second - r.second) }
    public mutating func scale(by rhs: Double) { first.scale(by: rhs); second.scale(by: rhs) }
    public var magnitudeSquared: Double { first.magnitudeSquared + second.magnitudeSquared }
}
public protocol Animatable {
    associatedtype AnimatableData: VectorArithmetic
    var animatableData: AnimatableData { get set }
}
extension Animatable where AnimatableData == EmptyAnimatableData {
    public var animatableData: EmptyAnimatableData { get { EmptyAnimatableData() } set {} }
}
public protocol AnimatableModifier: Animatable, ViewModifier {}

// MARK: - View modifiers

/// A custom view modifier: `body(content:)` builds what the modified view becomes, `content` standing for the view.
@MainActor @preconcurrency
public protocol ViewModifier {
    associatedtype Body: View
    @ViewBuilder @MainActor @preconcurrency func body(content: Content) -> Body
    typealias Content = _ViewModifier_Content<Self>
}

/// The view a modifier modifies, where its body places it: resolved there, in that place's environment.
public struct _ViewModifier_Content<Modifier: ViewModifier>: View, _PrimitiveView {
    let resolve: (_ResolveContext) -> [_Node]
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] { resolve(ctx) }
}

public struct ModifiedContent<Content, Modifier> {
    public var content: Content
    public var modifier: Modifier
    public init(content: Content, modifier: Modifier) {
        self.content = content
        self.modifier = modifier
    }
}

extension ModifiedContent: View, _PrimitiveView where Content: View, Modifier: ViewModifier {
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let ctx = ctx.child("mod:" + _typeName(Modifier.self))
        // The modifier's own @State and @Environment live at its place in the tree, as a view's do.
        _bindDynamicProperties(of: modifier, ctx)
        let content = self.content
        let placeholder = _ViewModifier_Content<Modifier>(resolve: { _resolveView(content, $0.child("content")) })
        return _resolveView(modifier.body(content: placeholder), ctx)
    }
}

extension ModifiedContent: ViewModifier where Content: ViewModifier, Modifier: ViewModifier {
    public func body(content: _ViewModifier_Content<Self>) -> some View {
        content.modifier(self.content).modifier(modifier)
    }
}

public struct EmptyModifier: ViewModifier {
    public static let identity = EmptyModifier()
    public init() {}
    public func body(content: Content) -> some View { content }
}

extension View {
    public func modifier<M>(_ modifier: M) -> ModifiedContent<Self, M> { ModifiedContent(content: self, modifier: modifier) }
}
extension ViewModifier {
    public func concat<T>(_ modifier: T) -> ModifiedContent<Self, T> { ModifiedContent(content: self, modifier: modifier) }
}

// MARK: - Symbol effects and content transitions

public protocol SymbolEffect {}
public protocol IndefiniteSymbolEffect {}
public protocol DiscreteSymbolEffect {}
public protocol TransitionSymbolEffect {}
public protocol ContentTransitionSymbolEffect {}
public struct _SymbolEffect: SymbolEffect, IndefiniteSymbolEffect, DiscreteSymbolEffect, TransitionSymbolEffect, ContentTransitionSymbolEffect, Sendable {
    public var up: _SymbolEffect { self }
    public var down: _SymbolEffect { self }
    public var byLayer: _SymbolEffect { self }
    public var wholeSymbol: _SymbolEffect { self }
    public var reversing: _SymbolEffect { self }
    public var cumulative: _SymbolEffect { self }
    public var iterative: _SymbolEffect { self }
    public var hideInactiveLayers: _SymbolEffect { self }
    public var downUp: _SymbolEffect { self }
    public var upUp: _SymbolEffect { self }
    public var offUp: _SymbolEffect { self }
    public var clockwise: _SymbolEffect { self }
    public var counterClockwise: _SymbolEffect { self }
    public var magic: _SymbolEffect { self }
    public func magic(fallback: _SymbolEffect) -> _SymbolEffect { self }
}
extension SymbolEffect where Self == _SymbolEffect {
    public static var bounce: _SymbolEffect { _SymbolEffect() }
    public static var pulse: _SymbolEffect { _SymbolEffect() }
    public static var variableColor: _SymbolEffect { _SymbolEffect() }
    public static var scale: _SymbolEffect { _SymbolEffect() }
    public static var appear: _SymbolEffect { _SymbolEffect() }
    public static var disappear: _SymbolEffect { _SymbolEffect() }
    public static var replace: _SymbolEffect { _SymbolEffect() }
    public static var wiggle: _SymbolEffect { _SymbolEffect() }
    public static var rotate: _SymbolEffect { _SymbolEffect() }
    public static var breathe: _SymbolEffect { _SymbolEffect() }
    public static var automatic: _SymbolEffect { _SymbolEffect() }
    public static var drawOn: _SymbolEffect { _SymbolEffect() }
    public static var drawOff: _SymbolEffect { _SymbolEffect() }
}
public struct SymbolEffectOptions: Sendable {
    public static let `default` = SymbolEffectOptions(), nonRepeating = SymbolEffectOptions()
    public static func `repeat`(_ count: Int) -> SymbolEffectOptions { SymbolEffectOptions() }
    public static func speed(_ speed: Double) -> SymbolEffectOptions { SymbolEffectOptions() }
    public static var repeating: SymbolEffectOptions { SymbolEffectOptions() }
    public func `repeat`(_ count: Int) -> SymbolEffectOptions { self }
    public func speed(_ speed: Double) -> SymbolEffectOptions { self }
}

public struct ContentTransition: Sendable {
    public static let identity = ContentTransition(), opacity = ContentTransition(), interpolate = ContentTransition()
    public static func numericText(countsDown: Bool = false) -> ContentTransition { ContentTransition() }
    public static func numericText(value: Double) -> ContentTransition { ContentTransition() }
    public static func symbolEffect<T: ContentTransitionSymbolEffect & SymbolEffect>(_ effect: T, options: SymbolEffectOptions = .default) -> ContentTransition { ContentTransition() }
    public static var symbolEffect: ContentTransition { ContentTransition() }
}

extension View {
    public func symbolEffect<T: SymbolEffect & IndefiniteSymbolEffect>(_ effect: T, options: SymbolEffectOptions = .default, isActive: Bool = true) -> some View { self }
    public func symbolEffect<T: SymbolEffect & DiscreteSymbolEffect, U: Equatable>(_ effect: T, options: SymbolEffectOptions = .default, value: U) -> some View { self }
    public func symbolEffectsRemoved(_ isEnabled: Bool = true) -> some View { self }
    public func contentTransition(_ transition: ContentTransition) -> some View { self }
}

// MARK: - Visual effects

public protocol VisualEffect: Sendable {}
public struct EmptyVisualEffect: VisualEffect {
    public init() {}
}
extension VisualEffect {
    public func scaleEffect(_ scale: CGFloat, anchor: UnitPoint = .center) -> Self { self }
    public func scaleEffect(_ scale: CGSize, anchor: UnitPoint = .center) -> Self { self }
    public func scaleEffect(x: CGFloat = 1, y: CGFloat = 1, anchor: UnitPoint = .center) -> Self { self }
    public func opacity(_ opacity: Double) -> Self { self }
    public func offset(_ offset: CGSize) -> Self { self }
    public func offset(x: CGFloat = 0, y: CGFloat = 0) -> Self { self }
    public func rotationEffect(_ angle: Angle, anchor: UnitPoint = .center) -> Self { self }
    public func rotation3DEffect(_ angle: Angle, axis: (x: CGFloat, y: CGFloat, z: CGFloat), anchor: UnitPoint = .center, anchorZ: CGFloat = 0, perspective: CGFloat = 1) -> Self { self }
    public func blur(radius: CGFloat, opaque: Bool = false) -> Self { self }
    public func brightness(_ amount: Double) -> Self { self }
    public func saturation(_ amount: Double) -> Self { self }
    public func grayscale(_ amount: Double) -> Self { self }
    public func hueRotation(_ angle: Angle) -> Self { self }
}
public enum ScrollTransitionPhase: Equatable, Sendable {
    case topLeading, identity, bottomTrailing
    public var isIdentity: Bool { self == .identity }
    public var value: Double { switch self { case .topLeading: return -1; case .identity: return 0; case .bottomTrailing: return 1 } }
}
public struct ScrollTransitionConfiguration: Sendable {
    public static let animated = ScrollTransitionConfiguration(), interactive = ScrollTransitionConfiguration(), identity = ScrollTransitionConfiguration()
    public static func animated(_ animation: Animation) -> ScrollTransitionConfiguration { ScrollTransitionConfiguration() }
    public func threshold(_ threshold: ScrollTransitionConfiguration.Threshold) -> ScrollTransitionConfiguration { self }
    public struct Threshold: Sendable {
        public static let visible = Threshold(), hidden = Threshold(), centered = Threshold()
        public static func visible(_ amount: Double) -> Threshold { Threshold() }
        public func interpolated(towards other: Threshold, amount: Double) -> Threshold { self }
        public func inset(by distance: Double) -> Threshold { self }
    }
}
extension View {
    /// Scroll transitions draw in their identity phase: the view as it rests in the middle of a scroll view.
    public func scrollTransition(_ configuration: ScrollTransitionConfiguration = .interactive, axis: Axis? = nil,
                                 transition: @escaping (EmptyVisualEffect, ScrollTransitionPhase) -> some VisualEffect) -> some View { self }
    public func scrollTransition(topLeading: ScrollTransitionConfiguration, bottomTrailing: ScrollTransitionConfiguration, axis: Axis? = nil,
                                 transition: @escaping (EmptyVisualEffect, ScrollTransitionPhase) -> some VisualEffect) -> some View { self }
    public func visualEffect(_ effect: @escaping (EmptyVisualEffect, GeometryProxy) -> some VisualEffect) -> some View { self }
}

// MARK: - Layout and hit testing

@MainActor
final class _PositionNode: _WrapperNode {
    let point: CGPoint
    init(child: _Node, point: CGPoint) {
        self.point = point
        super.init(child: child)
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { proposal.replacingUnspecifiedDimensions(by: CGSize(width: 10, height: 10)) }
    override func place(_ rect: CGRect) {
        frame = rect
        let s = child.sizeThatFits(.unspecified)
        child.place(CGRect(x: rect.minX + point.x - s.width / 2, y: rect.minY + point.y - s.height / 2, width: s.width, height: s.height))
    }
}

@MainActor
final class _NoHitNode: _WrapperNode {
    override func emit(_ list: _DisplayList) {
        let inner = _DisplayList()
        child.emit(inner)
        list.append(inner.ops.filter { !$0.hasPrefix("{\"o\":\"hit\"") && !$0.hasPrefix("{\"o\":\"field\"") })
    }
}

public struct ListSectionSpacing: Sendable {
    public static let `default` = ListSectionSpacing(), compact = ListSectionSpacing()
    public static func custom(_ spacing: CGFloat) -> ListSectionSpacing { ListSectionSpacing() }
}
public enum Prominence: Sendable { case standard, increased }

extension View {
    /// The view's centre at [x], [y] in the space it is given, which it fills.
    public func position(_ position: CGPoint) -> some View {
        _modified(_WrapModifier { node, _ in _PositionNode(child: node, point: position) })
    }
    public func position(x: CGFloat = 0, y: CGFloat = 0) -> some View { position(CGPoint(x: x, y: y)) }
    public func allowsHitTesting(_ enabled: Bool) -> some View {
        _modified(_WrapModifier { node, _ in enabled ? node : _NoHitNode(child: node) })
    }
    /// The container is the screen (or the sheet) the view is in: its width or height, divided as asked.
    public func containerRelativeFrame(_ axes: Axis.Set, alignment: Alignment = .center) -> some View {
        _modified(_WrapModifier { node, ctx in
            let c = ctx.environment._safeRect
            return _FlexFrameNode(child: node, minWidth: axes.contains(.horizontal) ? c.width : nil, idealWidth: nil,
                                  maxWidth: axes.contains(.horizontal) ? c.width : nil, minHeight: axes.contains(.vertical) ? c.height : nil,
                                  idealHeight: nil, maxHeight: axes.contains(.vertical) ? c.height : nil, alignment: alignment)
        })
    }
    public func containerRelativeFrame(_ axes: Axis.Set, count: Int, span: Int = 1, spacing: CGFloat, alignment: Alignment = .center) -> some View {
        _modified(_WrapModifier { node, ctx in
            let c = ctx.environment._safeRect
            func part(_ length: CGFloat) -> CGFloat {
                let n = CGFloat(max(1, count))
                return ((length - spacing * (n - 1)) / n) * CGFloat(span) + spacing * CGFloat(span - 1)
            }
            let w = axes.contains(.horizontal) ? part(c.width) : nil, h = axes.contains(.vertical) ? part(c.height) : nil
            return _FlexFrameNode(child: node, minWidth: w, idealWidth: nil, maxWidth: w, minHeight: h, idealHeight: nil, maxHeight: h, alignment: alignment)
        })
    }
    public func containerRelativeFrame(_ axes: Axis.Set, alignment: Alignment = .center, _ length: @escaping (CGFloat, Axis) -> CGFloat) -> some View {
        _modified(_WrapModifier { node, ctx in
            let c = ctx.environment._safeRect
            let w = axes.contains(.horizontal) ? length(c.width, .horizontal) : nil, h = axes.contains(.vertical) ? length(c.height, .vertical) : nil
            return _FlexFrameNode(child: node, minWidth: w, idealWidth: nil, maxWidth: w, minHeight: h, idealHeight: nil, maxHeight: h, alignment: alignment)
        })
    }
    public func safeAreaPadding(_ insets: EdgeInsets) -> some View { padding(insets) }
    public func safeAreaPadding(_ edges: Edge.Set = .all, _ length: CGFloat? = nil) -> some View { padding(edges, length) }
    public func safeAreaPadding(_ length: CGFloat) -> some View { padding(length) }
    public func onGeometryChange<T: Equatable>(for type: T.Type, of transform: @escaping (GeometryProxy) -> T, action: @escaping (T) -> Void) -> some View { self }
    public func onGeometryChange<T: Equatable>(for type: T.Type, of transform: @escaping (GeometryProxy) -> T, action: @escaping (T, T) -> Void) -> some View { self }
    public func mask<Mask: View>(alignment: Alignment = .center, @ViewBuilder _ mask: () -> Mask) -> some View { self }
    @_disfavoredOverload public func mask<Mask: View>(_ mask: Mask) -> some View { self }
    public func listRowSpacing(_ spacing: CGFloat?) -> some View { self }
    public func listSectionSpacing(_ spacing: ListSectionSpacing) -> some View { self }
    public func listSectionSpacing(_ spacing: CGFloat) -> some View { self }
    public func listRowSeparatorTint(_ color: Color?, edges: VerticalEdge.Set = .all) -> some View { self }
    public func listSectionSeparatorTint(_ color: Color?, edges: VerticalEdge.Set = .all) -> some View { self }
    public func headerProminence(_ prominence: Prominence) -> some View { self }
    public func privacySensitive(_ sensitive: Bool = true) -> some View { self }
    public func colorMultiply(_ color: Color) -> some View { self }
    public func hueRotation(_ angle: Angle) -> some View { self }
    public func luminanceToAlpha() -> some View { self }
}

// MARK: - Scrolling

public protocol ScrollTargetBehavior {}
public struct PagingScrollTargetBehavior: ScrollTargetBehavior { public init() {} }
public struct ViewAlignedScrollTargetBehavior: ScrollTargetBehavior {
    public enum LimitBehavior: Sendable { case automatic, always, never, alwaysByFew, alwaysByOne }
    public init(limitBehavior: LimitBehavior = .automatic) {}
}
extension ScrollTargetBehavior where Self == PagingScrollTargetBehavior { public static var paging: PagingScrollTargetBehavior { PagingScrollTargetBehavior() } }
extension ScrollTargetBehavior where Self == ViewAlignedScrollTargetBehavior {
    public static var viewAligned: ViewAlignedScrollTargetBehavior { ViewAlignedScrollTargetBehavior() }
    public static func viewAligned(limitBehavior: ViewAlignedScrollTargetBehavior.LimitBehavior) -> ViewAlignedScrollTargetBehavior { ViewAlignedScrollTargetBehavior() }
}
public struct ScrollPosition: Sendable {
    public init(idType: (some Hashable & Sendable).Type = Never.self, edge: Edge? = nil) {}
    public init(edge: Edge) {}
    public init(point: CGPoint) {}
    public init(x: CGFloat = 0, y: CGFloat = 0) {}
    public mutating func scrollTo(edge: Edge) {}
    public mutating func scrollTo(point: CGPoint) {}
    public mutating func scrollTo(x: CGFloat = 0, y: CGFloat = 0) {}
    public mutating func scrollTo(id: some Hashable & Sendable, anchor: UnitPoint? = nil) {}
    public var edge: Edge? { nil }
    public var point: CGPoint? { nil }
    public var isPositionedByUser: Bool { false }
}
public struct ScrollBounceBehavior: Sendable { public static let automatic = ScrollBounceBehavior(), always = ScrollBounceBehavior(), basedOnSize = ScrollBounceBehavior() }
public struct ScrollDismissesKeyboardMode: Sendable { public static let automatic = ScrollDismissesKeyboardMode(), immediately = ScrollDismissesKeyboardMode(), interactively = ScrollDismissesKeyboardMode(), never = ScrollDismissesKeyboardMode() }
public struct ContentMarginPlacement: Sendable { public static let automatic = ContentMarginPlacement(), scrollContent = ContentMarginPlacement(), scrollIndicators = ContentMarginPlacement() }
public enum ScrollPhase: Equatable, Sendable {
    case idle, tracking, interacting, decelerating, animating
    public var isScrolling: Bool { self != .idle }
}
public struct ScrollGeometry: Equatable, Sendable {
    public var contentOffset: CGPoint = .zero
    public var contentSize: CGSize = .zero
    public var contentInsets: EdgeInsets = EdgeInsets()
    public var containerSize: CGSize = .zero
    public var visibleRect: CGRect = .zero
    public var bounds: CGRect = .zero
}
public struct ScrollEdgeEffectStyle: Sendable { public static let automatic = ScrollEdgeEffectStyle(), soft = ScrollEdgeEffectStyle(), hard = ScrollEdgeEffectStyle() }

extension View {
    public func scrollTargetBehavior(_ behavior: some ScrollTargetBehavior) -> some View { self }
    public func scrollTargetLayout(isEnabled: Bool = true) -> some View { self }
    public func scrollPosition<ID: Hashable>(id: Binding<ID?>, anchor: UnitPoint? = nil) -> some View { self }
    public func scrollPosition(_ position: Binding<ScrollPosition>, anchor: UnitPoint? = nil) -> some View { self }
    public func contentMargins(_ edges: Edge.Set = .all, _ insets: EdgeInsets, for placement: ContentMarginPlacement = .automatic) -> some View { self }
    public func contentMargins(_ edges: Edge.Set = .all, _ length: CGFloat?, for placement: ContentMarginPlacement = .automatic) -> some View { self }
    public func contentMargins(_ length: CGFloat, for placement: ContentMarginPlacement = .automatic) -> some View { self }
    public func scrollBounceBehavior(_ behavior: ScrollBounceBehavior, axes: Axis.Set = [.vertical]) -> some View { self }
    public func scrollDismissesKeyboard(_ mode: ScrollDismissesKeyboardMode) -> some View { self }
    public func scrollClipDisabled(_ disabled: Bool = true) -> some View { self }
    public func defaultScrollAnchor(_ anchor: UnitPoint?) -> some View { self }
    public func defaultScrollAnchor(_ anchor: UnitPoint?, for role: Any) -> some View { self }
    public func scrollIndicatorsFlash(onAppear: Bool) -> some View { self }
    public func scrollIndicatorsFlash(trigger value: some Equatable) -> some View { self }
    public func onScrollGeometryChange<T: Equatable>(for type: T.Type, of transform: @escaping (ScrollGeometry) -> T, action: @escaping (T, T) -> Void) -> some View { self }
    public func onScrollPhaseChange(_ action: @escaping (ScrollPhase, ScrollPhase) -> Void) -> some View { self }
    public func onScrollVisibilityChange(threshold: Double = 0.5, _ action: @escaping (Bool) -> Void) -> some View { self }
    public func onScrollTargetVisibilityChange<ID: Hashable>(idType: ID.Type, threshold: Double = 0.5, _ action: @escaping ([ID]) -> Void) -> some View { self }
    public func scrollEdgeEffectStyle(_ style: ScrollEdgeEffectStyle?, for edges: Edge.Set) -> some View { self }
    public func scrollEdgeEffectHidden(_ hidden: Bool = true, for edges: Edge.Set = .all) -> some View { self }
}

// MARK: - Navigation, toolbars, presentation

public enum ToolbarRole: Sendable { case automatic, navigationStack, browser, editor }
public struct PresentationAdaptation: Sendable {
    public static let automatic = PresentationAdaptation(), none = PresentationAdaptation(), popover = PresentationAdaptation()
    public static let sheet = PresentationAdaptation(), fullScreenCover = PresentationAdaptation()
}
public struct PresentationContentInteraction: Sendable { public static let automatic = PresentationContentInteraction(), resizes = PresentationContentInteraction(), scrolls = PresentationContentInteraction() }
public protocol PresentationSizing {}
public struct AutomaticPresentationSizing: PresentationSizing {}
extension PresentationSizing where Self == AutomaticPresentationSizing {
    public static var automatic: AutomaticPresentationSizing { AutomaticPresentationSizing() }
    public static var form: AutomaticPresentationSizing { AutomaticPresentationSizing() }
    public static var page: AutomaticPresentationSizing { AutomaticPresentationSizing() }
    public static var fitted: AutomaticPresentationSizing { AutomaticPresentationSizing() }
}
public enum PopoverAttachmentAnchor: Sendable { case rect(Anchor<CGRect>.Source), point(UnitPoint) }
public protocol NavigationTransition {}
public struct _NavigationTransition: NavigationTransition {}
extension NavigationTransition where Self == _NavigationTransition {
    public static var automatic: _NavigationTransition { _NavigationTransition() }
    public static func zoom(sourceID: some Hashable, in namespace: Namespace.ID) -> _NavigationTransition { _NavigationTransition() }
}
public struct ContainerBackgroundPlacement: Sendable { public static let navigation = ContainerBackgroundPlacement(), tabView = ContainerBackgroundPlacement(), navigationSplitView = ContainerBackgroundPlacement() }

extension View {
    /// On an iPhone a popover presents as a sheet.
    public func popover<Content: View>(isPresented: Binding<Bool>, attachmentAnchor: PopoverAttachmentAnchor = .rect(.bounds), arrowEdge: Edge? = nil,
                                       @ViewBuilder content: @escaping () -> Content) -> some View {
        sheet(isPresented: isPresented, content: content)
    }
    public func popover<Item: Identifiable, Content: View>(item: Binding<Item?>, attachmentAnchor: PopoverAttachmentAnchor = .rect(.bounds),
                                                          arrowEdge: Edge? = nil, @ViewBuilder content: @escaping (Item) -> Content) -> some View {
        sheet(item: item, content: content)
    }
    public func navigationBarTitle(_ title: Text) -> some View { navigationTitle(title) }
    public func navigationBarTitle(_ titleKey: LocalizedStringKey) -> some View { navigationTitle(titleKey) }
    @_disfavoredOverload public func navigationBarTitle<S: StringProtocol>(_ title: S) -> some View { navigationTitle(title) }
    public func navigationBarTitle(_ title: Text, displayMode: NavigationBarItem.TitleDisplayMode) -> some View {
        navigationTitle(title).navigationBarTitleDisplayMode(displayMode)
    }
    public func navigationBarTitle(_ titleKey: LocalizedStringKey, displayMode: NavigationBarItem.TitleDisplayMode) -> some View {
        navigationTitle(titleKey).navigationBarTitleDisplayMode(displayMode)
    }
    public func navigationBarItems<L: View, T: View>(leading: L, trailing: T) -> some View {
        toolbar {
            ToolbarItem(placement: .topBarLeading) { leading }
            ToolbarItem(placement: .topBarTrailing) { trailing }
        }
    }
    public func navigationBarItems<L: View>(leading: L) -> some View { toolbar { ToolbarItem(placement: .topBarLeading) { leading } } }
    public func navigationBarItems<T: View>(trailing: T) -> some View { toolbar { ToolbarItem(placement: .topBarTrailing) { trailing } } }
    public func navigationSubtitle(_ subtitle: Text) -> some View { self }
    public func navigationSubtitle(_ subtitleKey: LocalizedStringKey) -> some View { self }
    @_disfavoredOverload public func navigationSubtitle<S: StringProtocol>(_ subtitle: S) -> some View { self }
    public func navigationTransition(_ style: some NavigationTransition) -> some View { self }
    public func matchedTransitionSource(id: some Hashable, in namespace: Namespace.ID) -> some View { self }
    public func toolbarRole(_ role: ToolbarRole) -> some View { self }
    public func toolbarTitleMenu<C: View>(@ViewBuilder content: () -> C) -> some View { self }
    public func toolbarBackgroundVisibility(_ visibility: Visibility, for bars: ToolbarPlacement...) -> some View { self }
    public func toolbarVisibility(_ visibility: Visibility, for bars: ToolbarPlacement...) -> some View { self }
    public func toolbarForegroundStyle<S: ShapeStyle>(_ style: S, for bars: ToolbarPlacement...) -> some View { self }
    public func containerBackground<S: ShapeStyle>(_ style: S, for container: ContainerBackgroundPlacement) -> some View { self }
    public func containerBackground<V: View>(for container: ContainerBackgroundPlacement, alignment: Alignment = .center, @ViewBuilder content: () -> V) -> some View { self }
    public func presentationCompactAdaptation(_ adaptation: PresentationAdaptation) -> some View { self }
    public func presentationCompactAdaptation(horizontal: PresentationAdaptation, vertical: PresentationAdaptation) -> some View { self }
    public func presentationContentInteraction(_ behavior: PresentationContentInteraction) -> some View { self }
    public func presentationSizing(_ sizing: some PresentationSizing) -> some View { self }
    public func inspector<V: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> V) -> some View {
        sheet(isPresented: isPresented, content: content)
    }
    public func inspectorColumnWidth(_ width: CGFloat) -> some View { self }
    public func inspectorColumnWidth(min: CGFloat? = nil, ideal: CGFloat, max: CGFloat? = nil) -> some View { self }
    public func statusBar(hidden: Bool) -> some View { self }
    public func persistentSystemOverlays(_ visibility: Visibility) -> some View { self }
    public func navigationSplitViewStyle(_ style: some NavigationSplitViewStyle) -> some View { self }
    public func navigationSplitViewColumnWidth(_ width: CGFloat) -> some View { self }
    public func navigationSplitViewColumnWidth(min: CGFloat? = nil, ideal: CGFloat, max: CGFloat? = nil) -> some View { self }
}

// MARK: - Styles of the views in MoreViews.swift

public protocol ProgressViewStyle {}
public struct CircularProgressViewStyle: ProgressViewStyle { public init() {}; public init(tint: Color) {} }
public struct LinearProgressViewStyle: ProgressViewStyle { public init() {}; public init(tint: Color) {} }
public struct DefaultProgressViewStyle: ProgressViewStyle { public init() {} }
extension ProgressViewStyle where Self == CircularProgressViewStyle { public static var circular: CircularProgressViewStyle { CircularProgressViewStyle() } }
extension ProgressViewStyle where Self == LinearProgressViewStyle { public static var linear: LinearProgressViewStyle { LinearProgressViewStyle() } }
extension ProgressViewStyle where Self == DefaultProgressViewStyle { public static var automatic: DefaultProgressViewStyle { DefaultProgressViewStyle() } }
public protocol GroupBoxStyle {}
public struct DefaultGroupBoxStyle: GroupBoxStyle { public init() {} }
extension GroupBoxStyle where Self == DefaultGroupBoxStyle { public static var automatic: DefaultGroupBoxStyle { DefaultGroupBoxStyle() } }
public protocol LabeledContentStyle {}
public struct AutomaticLabeledContentStyle: LabeledContentStyle { public init() {} }
extension LabeledContentStyle where Self == AutomaticLabeledContentStyle { public static var automatic: AutomaticLabeledContentStyle { AutomaticLabeledContentStyle() } }
public protocol DisclosureGroupStyle {}
public struct AutomaticDisclosureGroupStyle: DisclosureGroupStyle { public init() {} }
extension DisclosureGroupStyle where Self == AutomaticDisclosureGroupStyle { public static var automatic: AutomaticDisclosureGroupStyle { AutomaticDisclosureGroupStyle() } }
public protocol FormStyle {}
public struct GroupedFormStyle: FormStyle { public init() {} }
public struct ColumnsFormStyle: FormStyle { public init() {} }
public struct AutomaticFormStyle: FormStyle { public init() {} }
extension FormStyle where Self == GroupedFormStyle { public static var grouped: GroupedFormStyle { GroupedFormStyle() } }
extension FormStyle where Self == ColumnsFormStyle { public static var columns: ColumnsFormStyle { ColumnsFormStyle() } }
extension FormStyle where Self == AutomaticFormStyle { public static var automatic: AutomaticFormStyle { AutomaticFormStyle() } }

extension View {
    public func progressViewStyle<S: ProgressViewStyle>(_ style: S) -> some View { self }
    public func menuStyle<S: MenuStyle>(_ style: S) -> some View { self }
    public func menuOrder(_ order: MenuOrder) -> some View { self }
    public func datePickerStyle<S: DatePickerStyle>(_ style: S) -> some View { self }
    public func groupBoxStyle<S: GroupBoxStyle>(_ style: S) -> some View { self }
    public func gaugeStyle<S: GaugeStyle>(_ style: S) -> some View { self }
    public func labeledContentStyle<S: LabeledContentStyle>(_ style: S) -> some View { self }
    public func disclosureGroupStyle<S: DisclosureGroupStyle>(_ style: S) -> some View { self }
    public func controlGroupStyle<S: ControlGroupStyle>(_ style: S) -> some View { self }
    public func formStyle<S: FormStyle>(_ style: S) -> some View { self }
    public func textEditorStyle<S: TextEditorStyle>(_ style: S) -> some View { self }
    public func labelsHidden() -> some View { self }
}

// MARK: - Input, focus, drag and drop, accessibility

public struct EventModifiers: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let capsLock = EventModifiers(rawValue: 1), shift = EventModifiers(rawValue: 2), control = EventModifiers(rawValue: 4)
    public static let option = EventModifiers(rawValue: 8), command = EventModifiers(rawValue: 16), numericPad = EventModifiers(rawValue: 32)
    public static let all = EventModifiers(rawValue: 63)
}
public struct KeyEquivalent: Hashable, Sendable, ExpressibleByExtendedGraphemeClusterLiteral {
    public let character: Character
    public init(_ character: Character) { self.character = character }
    public init(extendedGraphemeClusterLiteral value: Character) { character = value }
    public static let upArrow = KeyEquivalent("\u{F700}"), downArrow = KeyEquivalent("\u{F701}"), leftArrow = KeyEquivalent("\u{F702}")
    public static let rightArrow = KeyEquivalent("\u{F703}"), escape = KeyEquivalent("\u{1B}"), delete = KeyEquivalent("\u{8}")
    public static let deleteForward = KeyEquivalent("\u{F728}"), home = KeyEquivalent("\u{F729}"), end = KeyEquivalent("\u{F72B}")
    public static let pageUp = KeyEquivalent("\u{F72C}"), pageDown = KeyEquivalent("\u{F72D}"), clear = KeyEquivalent("\u{F739}")
    public static let tab = KeyEquivalent("\t"), space = KeyEquivalent(" "), `return` = KeyEquivalent("\r")
}
public struct KeyboardShortcut: Sendable {
    public init(_ key: KeyEquivalent, modifiers: EventModifiers = .command) {}
    public static let defaultAction = KeyboardShortcut(.return, modifiers: []), cancelAction = KeyboardShortcut(.escape, modifiers: [])
}
public struct KeyPress: Sendable {
    public enum Result: Sendable { case handled, ignored }
    public struct Phases: OptionSet, Sendable {
        public let rawValue: Int
        public init(rawValue: Int) { self.rawValue = rawValue }
        public static let down = Phases(rawValue: 1), `repeat` = Phases(rawValue: 2), up = Phases(rawValue: 4), all = Phases(rawValue: 7)
    }
    public var key: KeyEquivalent
    public var characters: String
    public var modifiers: EventModifiers
    public var phase: Phases
}
public struct FocusInteractions: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let activate = FocusInteractions(rawValue: 1), edit = FocusInteractions(rawValue: 2), automatic = FocusInteractions(rawValue: 3)
}
public struct TextSelectability: Sendable { public static let enabled = TextSelectability(), disabled = TextSelectability() }
public struct AccessibilityTraits: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let isButton = AccessibilityTraits(rawValue: 1), isHeader = AccessibilityTraits(rawValue: 2), isSelected = AccessibilityTraits(rawValue: 4)
    public static let isLink = AccessibilityTraits(rawValue: 8), isSearchField = AccessibilityTraits(rawValue: 16), isImage = AccessibilityTraits(rawValue: 32)
    public static let playsSound = AccessibilityTraits(rawValue: 64), isKeyboardKey = AccessibilityTraits(rawValue: 128), isStaticText = AccessibilityTraits(rawValue: 256)
    public static let isSummaryElement = AccessibilityTraits(rawValue: 512), updatesFrequently = AccessibilityTraits(rawValue: 1024)
    public static let startsMediaSession = AccessibilityTraits(rawValue: 2048), allowsDirectInteraction = AccessibilityTraits(rawValue: 4096)
    public static let causesPageTurn = AccessibilityTraits(rawValue: 8192), isModal = AccessibilityTraits(rawValue: 16384), isToggle = AccessibilityTraits(rawValue: 32768)
}
public struct AccessibilityChildBehavior: Sendable { public static let ignore = AccessibilityChildBehavior(), contain = AccessibilityChildBehavior(), combine = AccessibilityChildBehavior() }
public struct AccessibilityActionKind: Sendable {
    public static let `default` = AccessibilityActionKind(), escape = AccessibilityActionKind(), magicTap = AccessibilityActionKind(), delete = AccessibilityActionKind(), showMenu = AccessibilityActionKind()
    init() {}
    public init(named name: Text) {}
}
public enum AccessibilityAdjustmentDirection: Sendable { case increment, decrement }
public enum AccessibilityHeadingLevel: Sendable { case unspecified, h1, h2, h3, h4, h5, h6 }
public struct HoverEffect: Sendable { public static let automatic = HoverEffect(), highlight = HoverEffect(), lift = HoverEffect() }

extension View {
    public func onOpenURL(perform action: @escaping (URL) -> Void) -> some View { self }
    public func onKeyPress(_ key: KeyEquivalent, action: @escaping () -> KeyPress.Result) -> some View { self }
    public func onKeyPress(_ key: KeyEquivalent, phases: KeyPress.Phases, action: @escaping (KeyPress) -> KeyPress.Result) -> some View { self }
    public func onKeyPress(keys: Set<KeyEquivalent>, phases: KeyPress.Phases = [.down, .repeat], action: @escaping (KeyPress) -> KeyPress.Result) -> some View { self }
    public func onKeyPress(characters: CharacterSet, phases: KeyPress.Phases = [.down, .repeat], action: @escaping (KeyPress) -> KeyPress.Result) -> some View { self }
    public func onKeyPress(phases: KeyPress.Phases = [.down, .repeat], action: @escaping (KeyPress) -> KeyPress.Result) -> some View { self }
    public func focusable(_ isFocusable: Bool = true) -> some View { self }
    public func focusable(_ isFocusable: Bool = true, interactions: FocusInteractions) -> some View { self }
    public func focusEffectDisabled(_ disabled: Bool = true) -> some View { self }
    public func keyboardShortcut(_ key: KeyEquivalent, modifiers: EventModifiers = .command) -> some View { self }
    public func keyboardShortcut(_ shortcut: KeyboardShortcut) -> some View { self }
    public func textSelection(_ selectability: TextSelectability) -> some View { self }
    public func hoverEffect(_ effect: HoverEffect = .automatic, isEnabled: Bool = true) -> some View { self }
    public func hoverEffectDisabled(_ disabled: Bool = true) -> some View { self }
    public func onHover(perform action: @escaping (Bool) -> Void) -> some View { self }
    public func draggable<T: Transferable>(_ payload: @autoclosure @escaping () -> T) -> some View { self }
    public func draggable<T: Transferable, V: View>(_ payload: @autoclosure @escaping () -> T, @ViewBuilder preview: () -> V) -> some View { self }
    public func dropDestination<T: Transferable>(for payloadType: T.Type = T.self, action: @escaping ([T], CGPoint) -> Bool,
                                                 isTargeted: @escaping (Bool) -> Void = { _ in }) -> some View { self }
    public func accessibilityValue(_ valueDescription: Text) -> some View { self }
    public func accessibilityValue(_ valueKey: LocalizedStringKey) -> some View { self }
    @_disfavoredOverload public func accessibilityValue<S: StringProtocol>(_ value: S) -> some View { self }
    public func accessibilityAddTraits(_ traits: AccessibilityTraits) -> some View { self }
    public func accessibilityRemoveTraits(_ traits: AccessibilityTraits) -> some View { self }
    public func accessibilityElement(children: AccessibilityChildBehavior = .ignore) -> some View { self }
    public func accessibilityAction(_ actionKind: AccessibilityActionKind = .default, _ handler: @escaping () -> Void) -> some View { self }
    public func accessibilityAction(named name: Text, _ handler: @escaping () -> Void) -> some View { self }
    public func accessibilityAction(named nameKey: LocalizedStringKey, _ handler: @escaping () -> Void) -> some View { self }
    public func accessibilityAdjustableAction(_ handler: @escaping (AccessibilityAdjustmentDirection) -> Void) -> some View { self }
    public func accessibilityHeading(_ level: AccessibilityHeadingLevel) -> some View { self }
    public func accessibilitySortPriority(_ sortPriority: Double) -> some View { self }
    public func accessibilityInputLabels(_ inputLabels: [Text]) -> some View { self }
    public func accessibilityInputLabels(_ inputLabels: [LocalizedStringKey]) -> some View { self }
    public func accessibilityRepresentation<V: View>(@ViewBuilder representation: () -> V) -> some View { self }
    public func accessibilityChildren<V: View>(@ViewBuilder children: () -> V) -> some View { self }
    public func accessibilityFocused(_ condition: Binding<Bool>) -> some View { self }
    public func searchSuggestions<S: View>(@ViewBuilder _ suggestions: () -> S) -> some View { self }
    public func searchSuggestions(_ visibility: Visibility, for placements: Any...) -> some View { self }
    public func searchCompletion(_ completion: String) -> some View { self }
    public func searchScopes<V: Hashable, S: View>(_ scope: Binding<V>, @ViewBuilder scopes: () -> S) -> some View { self }
    public func onContinuousHover(coordinateSpace: CoordinateSpace = .local, perform action: @escaping (HoverPhase) -> Void) -> some View { self }
}
public enum HoverPhase: Equatable, Sendable { case active(CGPoint), ended }

// MARK: - Canvas

/// GraphicsContext: what a Canvas renderer draws with. Records fills, strokes, text and images; translation and
/// scale apply to what follows (rotation is not drawn yet).
public struct GraphicsContext {
    final class Recorder {
        var items: [(path: Path?, style: AnyShapeStyle?, stroke: StrokeStyle?, text: Text?, image: Image?, at: CGRect, anchor: UnitPoint, alpha: Double)] = []
    }
    let recorder: Recorder
    var tx: CGFloat = 0, ty: CGFloat = 0, sx: CGFloat = 1, sy: CGFloat = 1
    public var opacity: Double = 1
    public var blendMode: BlendMode = .normal
    public var environment: EnvironmentValues

    public struct Shading {
        let style: AnyShapeStyle
        public static func color(_ color: Color) -> Shading { Shading(style: AnyShapeStyle(color)) }
        public static func style<S: ShapeStyle>(_ style: S) -> Shading { Shading(style: AnyShapeStyle(style)) }
        public static func linearGradient(_ gradient: Gradient, startPoint: CGPoint, endPoint: CGPoint, options: Int = 0) -> Shading {
            Shading(style: AnyShapeStyle(gradient.stops.first?.color ?? .clear))
        }
        public static func radialGradient(_ gradient: Gradient, center: CGPoint, startRadius: CGFloat, endRadius: CGFloat, options: Int = 0) -> Shading {
            Shading(style: AnyShapeStyle(gradient.stops.first?.color ?? .clear))
        }
        public static var foreground: Shading { Shading(style: AnyShapeStyle(Color.primary)) }
    }
    public struct ResolvedText { let text: Text; public func measure(in size: CGSize) -> CGSize { .zero } }
    public struct ResolvedImage { let image: Image; public var size: CGSize { CGSize(width: 24, height: 24) } }

    func map(_ p: Path) -> Path {
        var copy = Path()
        func m(_ q: CGPoint) -> CGPoint { CGPoint(x: q.x * sx + tx, y: q.y * sy + ty) }
        func r(_ rect: CGRect) -> CGRect { CGRect(x: rect.minX * sx + tx, y: rect.minY * sy + ty, width: rect.width * sx, height: rect.height * sy) }
        copy.elements = p.elements.map {
            switch $0 {
            case .move(let q): return .move(m(q))
            case .line(let q): return .line(m(q))
            case .quad(let q, let c): return .quad(m(q), control: m(c))
            case .curve(let q, let c1, let c2): return .curve(m(q), control1: m(c1), control2: m(c2))
            case .close: return .close
            case .rect(let x): return .rect(r(x))
            case .roundedRect(let x, let s, let c): return .roundedRect(r(x), CGSize(width: s.width * sx, height: s.height * sy), continuous: c)
            case .ellipse(let x): return .ellipse(r(x))
            }
        }
        return copy
    }

    public func fill(_ path: Path, with shading: Shading, style: FillStyle = FillStyle()) {
        recorder.items.append((map(path), shading.style, nil, nil, nil, .zero, .center, opacity))
    }
    public func stroke(_ path: Path, with shading: Shading, style: StrokeStyle) {
        var scaled = style
        scaled.lineWidth = style.lineWidth * sx
        recorder.items.append((map(path), shading.style, scaled, nil, nil, .zero, .center, opacity))
    }
    public func stroke(_ path: Path, with shading: Shading, lineWidth: CGFloat = 1) { stroke(path, with: shading, style: StrokeStyle(lineWidth: lineWidth)) }
    public func draw(_ text: Text, at point: CGPoint, anchor: UnitPoint = .center) {
        recorder.items.append((nil, nil, nil, text, nil, CGRect(x: point.x * sx + tx, y: point.y * sy + ty, width: 0, height: 0), anchor, opacity))
    }
    public func draw(_ text: Text, in rect: CGRect) { draw(text, at: CGPoint(x: rect.minX, y: rect.minY), anchor: .topLeading) }
    public func draw(_ text: ResolvedText, at point: CGPoint, anchor: UnitPoint = .center) { draw(text.text, at: point, anchor: anchor) }
    public func draw(_ image: Image, in rect: CGRect, style: FillStyle = FillStyle()) {
        let r = CGRect(x: rect.minX * sx + tx, y: rect.minY * sy + ty, width: rect.width * sx, height: rect.height * sy)
        recorder.items.append((nil, nil, nil, nil, image, r, .topLeading, opacity))
    }
    public func draw(_ image: Image, at point: CGPoint, anchor: UnitPoint = .center) {
        recorder.items.append((nil, nil, nil, nil, image, CGRect(x: point.x * sx + tx, y: point.y * sy + ty, width: 0, height: 0), anchor, opacity))
    }
    public func draw(_ image: ResolvedImage, at point: CGPoint, anchor: UnitPoint = .center) { draw(image.image, at: point, anchor: anchor) }
    public func draw(_ image: ResolvedImage, in rect: CGRect, style: FillStyle = FillStyle()) { draw(image.image, in: rect, style: style) }
    public func resolve(_ text: Text) -> ResolvedText { ResolvedText(text: text) }
    public func resolve(_ image: Image) -> ResolvedImage { ResolvedImage(image: image) }
    public mutating func translateBy(x: CGFloat, y: CGFloat) { tx += x * sx; ty += y * sy }
    public mutating func scaleBy(x: CGFloat, y: CGFloat) { sx *= x; sy *= y }
    public mutating func rotate(by angle: Angle) {}
    public mutating func concatenate(_ matrix: CGAffineTransform) {}
    public mutating func clip(to path: Path, style: FillStyle = FillStyle(), options: Int = 0) {}
    public mutating func addFilter(_ filter: Filter, options: Int = 0) {}
    public func drawLayer(content: (inout GraphicsContext) throws -> Void) rethrows { var copy = self; try content(&copy) }
    public struct Filter { public static func blur(radius: CGFloat) -> Filter { Filter() }; public static func shadow(color: Color = .black, radius: CGFloat, x: CGFloat = 0, y: CGFloat = 0) -> Filter { Filter() } }
    public enum BlendMode: Sendable { case normal, multiply, screen, overlay, darken, lighten, plusLighter, plusDarker, sourceAtop, destinationOver, clear, copy }
}

/// Canvas: the renderer runs when the canvas is drawn, at its size; what it records is drawn in its frame.
public struct Canvas<Symbols: View>: View, _PrimitiveView {
    let renderer: (inout GraphicsContext, CGSize) -> Void
    public init(opaque: Bool = false, colorMode: ColorRenderingMode = .nonLinear, rendersAsynchronously: Bool = false,
                renderer: @escaping (inout GraphicsContext, CGSize) -> Void) where Symbols == EmptyView {
        self.renderer = renderer
    }
    public init(opaque: Bool = false, colorMode: ColorRenderingMode = .nonLinear, rendersAsynchronously: Bool = false,
                renderer: @escaping (inout GraphicsContext, CGSize) -> Void, @ViewBuilder symbols: () -> Symbols) {
        self.renderer = renderer
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] { [_CanvasNode(path: ctx.path, ctx: ctx, renderer: renderer)] }
}

@MainActor
final class _CanvasNode: _Node {
    let ctx: _ResolveContext
    let renderer: (inout GraphicsContext, CGSize) -> Void
    init(path: String, ctx: _ResolveContext, renderer: @escaping (inout GraphicsContext, CGSize) -> Void) {
        self.ctx = ctx
        self.renderer = renderer
        super.init(path: path, env: ctx.environment)
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { proposal.replacingUnspecifiedDimensions(by: CGSize(width: 10, height: 10)) }
    override func emit(_ list: _DisplayList) {
        let recorder = GraphicsContext.Recorder()
        var gc = GraphicsContext(recorder: recorder, environment: env)
        renderer(&gc, frame.size)
        list.pushClip(Path(frame))
        for (i, item) in recorder.items.enumerated() {
            if item.alpha < 1 { list.pushOpacity(item.alpha) }
            if let path = item.path, let style = item.style {
                list.shape(path.offsetBy(frame.minX, frame.minY), style: style, stroke: item.stroke, env: env)
            } else if let text = item.text {
                if let node = _resolveView(text, ctx.child("text\(i)")).first { placeAndEmit(node, item.at, item.anchor, list) }
            } else if let image = item.image {
                let view: AnyView = item.at.width > 0 ? AnyView(image.resizable()) : AnyView(image)
                if let node = _resolveView(view, ctx.child("image\(i)")).first {
                    if item.at.width > 0 {
                        node.place(item.at.offsetBy(dx: frame.minX, dy: frame.minY))
                        node.emit(list)
                    } else {
                        placeAndEmit(node, item.at, item.anchor, list)
                    }
                }
            }
            if item.alpha < 1 { list.pop() }
        }
        list.pop()
    }
    private func placeAndEmit(_ node: _Node, _ at: CGRect, _ anchor: UnitPoint, _ list: _DisplayList) {
        let s = node.sizeThatFits(.unspecified)
        node.place(CGRect(x: frame.minX + at.minX - s.width * anchor.x, y: frame.minY + at.minY - s.height * anchor.y, width: s.width, height: s.height))
        node.emit(list)
    }
}

// MARK: - Gradients, equatable views

public struct EllipticalGradient: ShapeStyle, View, _PrimitiveView, _ResolvableStyle {
    let gradient: Gradient
    let center: UnitPoint
    let endFraction: CGFloat
    public init(gradient: Gradient, center: UnitPoint = .center, startRadiusFraction: CGFloat = 0, endRadiusFraction: CGFloat = 0.5) {
        self.gradient = gradient; self.center = center; endFraction = endRadiusFraction
    }
    public init(colors: [Color], center: UnitPoint = .center, startRadiusFraction: CGFloat = 0, endRadiusFraction: CGFloat = 0.5) {
        self.init(gradient: Gradient(colors: colors), center: center, startRadiusFraction: startRadiusFraction, endRadiusFraction: endRadiusFraction)
    }
    public var body: Never { _neverBody() }
    func _resolvedColor(_ env: EnvironmentValues) -> Color { gradient.stops.first?.color ?? .clear }
    /// Drawn as a circular gradient reaching the fraction of 200 points (a typical frame's half-size).
    func _gradient(_ env: EnvironmentValues) -> _Gradient? {
        _Gradient(kind: .radial(center: center, start: 0, end: endFraction * 200), stops: gradient.resolved(env))
    }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_ShapeNode(path: ctx.path, env: ctx.environment, shape: _AnyShape(Rectangle()), fill: AnyShapeStyle(self), stroke: nil)]
    }
}

public struct EquatableView<Content: View & Equatable>: View {
    public var content: Content
    public init(content: Content) { self.content = content }
    public var body: some View { content }
}
extension View where Self: Equatable {
    public func equatable() -> EquatableView<Self> { EquatableView(content: self) }
}

// MARK: - More of the common modifiers

public struct UITextContentType: Hashable, Sendable {
    let raw: String
    public init(rawValue: String) { raw = rawValue }
    public static let name = UITextContentType(rawValue: "name"), emailAddress = UITextContentType(rawValue: "email")
    public static let password = UITextContentType(rawValue: "password"), newPassword = UITextContentType(rawValue: "newPassword")
    public static let oneTimeCode = UITextContentType(rawValue: "oneTimeCode"), username = UITextContentType(rawValue: "username")
    public static let telephoneNumber = UITextContentType(rawValue: "tel"), URL = UITextContentType(rawValue: "url")
    public static let fullStreetAddress = UITextContentType(rawValue: "address"), postalCode = UITextContentType(rawValue: "postalCode")
    public static let givenName = UITextContentType(rawValue: "givenName"), familyName = UITextContentType(rawValue: "familyName")
}
public enum UITextAutocapitalizationType: Int, Sendable { case none, words, sentences, allCharacters }
public protocol NavigationViewStyle {}
public struct StackNavigationViewStyle: NavigationViewStyle { public init() {} }
public struct DefaultNavigationViewStyle: NavigationViewStyle { public init() {} }
public struct ColumnNavigationViewStyle: NavigationViewStyle { public init() {} }
public struct DoubleColumnNavigationViewStyle: NavigationViewStyle { public init() {} }
extension NavigationViewStyle where Self == StackNavigationViewStyle { public static var stack: StackNavigationViewStyle { StackNavigationViewStyle() } }
extension NavigationViewStyle where Self == DefaultNavigationViewStyle { public static var automatic: DefaultNavigationViewStyle { DefaultNavigationViewStyle() } }
extension NavigationViewStyle where Self == ColumnNavigationViewStyle { public static var columns: ColumnNavigationViewStyle { ColumnNavigationViewStyle() } }
public struct ActionSheet {
    public struct Button {
        public static func `default`(_ label: Text, action: (() -> Void)? = {}) -> Button { Button() }
        public static func cancel(_ label: Text, action: (() -> Void)? = {}) -> Button { Button() }
        public static func cancel(_ action: (() -> Void)? = {}) -> Button { Button() }
        public static func destructive(_ label: Text, action: (() -> Void)? = {}) -> Button { Button() }
    }
    public init(title: Text, message: Text? = nil, buttons: [ActionSheet.Button] = [.cancel()]) {}
}
public struct BadgeProminence: Sendable { public static let standard = BadgeProminence(), increased = BadgeProminence(), decreased = BadgeProminence() }
public struct ListItemTint: Sendable {
    public static func fixed(_ tint: Color) -> ListItemTint { ListItemTint() }
    public static func preferred(_ tint: Color) -> ListItemTint { ListItemTint() }
    public static let monochrome = ListItemTint()
}
public struct MenuIndicatorVisibility: Sendable {}
public struct TabViewBottomAccessoryPlacement: Sendable { public static let expanded = TabViewBottomAccessoryPlacement(), inline = TabViewBottomAccessoryPlacement() }
public struct TabSearchActivation: Sendable { public static let automatic = TabSearchActivation(), searchTabSelection = TabSearchActivation() }

extension View {
    public func textContentType(_ textContentType: UITextContentType?) -> some View { self }
    public func autocapitalization(_ style: UITextAutocapitalizationType) -> some View { self }
    public func disableAutocorrection(_ disable: Bool?) -> some View { self }
    public func navigationViewStyle<S: NavigationViewStyle>(_ style: S) -> some View { self }
    public func actionSheet(isPresented: Binding<Bool>, content: () -> ActionSheet) -> some View { self }
    public func actionSheet<T: Identifiable>(item: Binding<T?>, content: (T) -> ActionSheet) -> some View { self }
    public func deleteDisabled(_ isDisabled: Bool) -> some View { self }
    public func moveDisabled(_ isDisabled: Bool) -> some View { self }
    public func selectionDisabled(_ isDisabled: Bool = true) -> some View { self }
    public func searchFocused(_ isFocused: FocusState<Bool>.Binding) -> some View { self }
    public func focusedValue<Value>(_ keyPath: WritableKeyPath<FocusedValues, Value?>, _ value: Value) -> some View { self }
    public func focusedSceneValue<Value>(_ keyPath: WritableKeyPath<FocusedValues, Value?>, _ value: Value) -> some View { self }
    public func focusedObject<T: ObservableObject>(_ object: T?) -> some View { self }
    public func focusedSceneObject<T: ObservableObject>(_ object: T?) -> some View { self }
    public func defaultFocus<V: Hashable>(_ binding: FocusState<V>.Binding, _ value: V) -> some View { self }
    public func badgeProminence(_ prominence: BadgeProminence) -> some View { self }
    public func listItemTint(_ tint: ListItemTint?) -> some View { self }
    public func listItemTint(_ tint: Color?) -> some View { self }
    public func sectionIndexLabel(_ label: Text?) -> some View { self }
    @_disfavoredOverload public func sectionIndexLabel<S: StringProtocol>(_ label: S) -> some View { self }
    public func listSectionIndexVisibility(_ visibility: Visibility) -> some View { self }
    public func menuIndicator(_ visibility: Visibility) -> some View { self }
    public func safeAreaBar<V: View>(edge: VerticalEdge, alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> V) -> some View {
        safeAreaInset(edge: edge, alignment: alignment, spacing: spacing, content: content)
    }
    public func scenePadding(_ edges: Edge.Set = .all) -> some View { padding(edges) }
    public func flipsForRightToLeftLayoutDirection(_ enabled: Bool) -> some View { self }
    public func projectionEffect(_ transform: ProjectionTransform) -> some View { self }
    public func tabViewBottomAccessory<V: View>(@ViewBuilder content: () -> V) -> some View { self }
    public func tabViewSearchActivation(_ activation: TabSearchActivation) -> some View { self }
    public func labelIconToTitleSpacing(_ spacing: CGFloat) -> some View { self }
    public func labelReservedIconWidth(_ value: CGFloat) -> some View { self }
    public func labelsVisibility(_ visibility: Visibility) -> some View { self }
    public func toolbarTitleAlignment(_ alignment: HorizontalAlignment) -> some View { self }
    public func typeSelectEquivalent(_ text: Text?) -> some View { self }
    public func accessibilityCustomContent(_ label: Text, _ value: Text, importance: Int = 0) -> some View { self }
    public func accessibilityTextContentType(_ value: Int) -> some View { self }
}
public struct ProjectionTransform: Sendable {
    public init() {}
    public init(_ m: CGAffineTransform) {}
}
public struct FocusedValues {}
