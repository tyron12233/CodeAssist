// Drawing and interaction modifiers: background, overlay, clipping, opacity, shadow, border, gestures, and the
// lifecycle and presentation modifiers a preview accepts without acting on.

import Foundation

// MARK: - Background and overlay

struct _LayerModifier: _NodeModifier {
    let layer: AnyView
    let alignment: Alignment
    let isOverlay: Bool
    func _wrap(_ node: _Node, _ ctx: _ResolveContext) -> _Node {
        let secondary = _resolveView(layer, ctx.child(isOverlay ? "overlay" : "background").with { $0._stackAxis = nil })
        return _LayeredNode(child: node, secondary: secondary, alignment: alignment, isOverlay: isOverlay)
    }
}

extension View {
    public func background<V: View>(alignment: Alignment = .center, @ViewBuilder content: () -> V) -> some View {
        _modified(_LayerModifier(layer: AnyView(content()), alignment: alignment, isOverlay: false))
    }
    public func background<V: View>(_ background: V, alignment: Alignment = .center) -> some View {
        _modified(_LayerModifier(layer: AnyView(background), alignment: alignment, isOverlay: false))
    }
    public func background<S: ShapeStyle>(_ style: S, ignoresSafeAreaEdges edges: Edge.Set = .all) -> some View {
        _modified(_LayerModifier(layer: AnyView(Rectangle().fill(style)), alignment: .center, isOverlay: false))
    }
    public func background(ignoresSafeAreaEdges edges: Edge.Set = .all) -> some View {
        background(BackgroundStyle())
    }
    public func background<S: ShapeStyle, T: Shape>(_ style: S, in shape: T, fillStyle: FillStyle = FillStyle()) -> some View {
        _modified(_LayerModifier(layer: AnyView(shape.fill(style)), alignment: .center, isOverlay: false))
    }
    public func background<T: Shape>(in shape: T, fillStyle: FillStyle = FillStyle()) -> some View {
        background(BackgroundStyle(), in: shape, fillStyle: fillStyle)
    }

    public func overlay<V: View>(alignment: Alignment = .center, @ViewBuilder content: () -> V) -> some View {
        _modified(_LayerModifier(layer: AnyView(content()), alignment: alignment, isOverlay: true))
    }
    public func overlay<V: View>(_ overlay: V, alignment: Alignment = .center) -> some View {
        _modified(_LayerModifier(layer: AnyView(overlay), alignment: alignment, isOverlay: true))
    }
    public func overlay<S: ShapeStyle>(_ style: S, ignoresSafeAreaEdges edges: Edge.Set = .all) -> some View {
        _modified(_LayerModifier(layer: AnyView(Rectangle().fill(style)), alignment: .center, isOverlay: true))
    }
    public func overlay<S: ShapeStyle, T: Shape>(_ style: S, in shape: T, fillStyle: FillStyle = FillStyle()) -> some View {
        _modified(_LayerModifier(layer: AnyView(shape.fill(style)), alignment: .center, isOverlay: true))
    }

    public func border<S: ShapeStyle>(_ content: S, width: CGFloat = 1) -> some View {
        overlay(Rectangle().strokeBorder(content, lineWidth: width))
    }
}

// MARK: - Clipping, opacity, shadow, transforms

@MainActor
final class _EffectNode: _WrapperNode {
    enum Effect {
        case clip(_AnyShape)
        case opacity(Double)
        case shadow(color: _RGBA, radius: CGFloat, x: CGFloat, y: CGFloat)
        case hidden
    }
    let effect: Effect
    init(child: _Node, effect: Effect) {
        self.effect = effect
        super.init(child: child)
    }
    override func emit(_ list: _DisplayList) {
        switch effect {
        case .clip(let shape):
            list.pushClip(shape.path(CGRect(origin: .zero, size: frame.size)).offsetBy(frame.minX, frame.minY))
            child.emit(list)
            list.pop()
        case .opacity(let alpha):
            list.pushOpacity(alpha)
            child.emit(list)
            list.pop()
        case .shadow(let color, let radius, let x, let y):
            list.pushShadow(color: color, radius: radius, x: x, y: y)
            child.emit(list)
            list.pop()
        case .hidden:
            break
        }
    }
}

extension View {
    public func clipShape<S: Shape>(_ shape: S, style: FillStyle = FillStyle()) -> some View {
        _modified(_WrapModifier { node, _ in _EffectNode(child: node, effect: .clip(_AnyShape(shape))) })
    }
    public func clipped(antialiased: Bool = false) -> some View { clipShape(Rectangle()) }
    public func cornerRadius(_ radius: CGFloat, antialiased: Bool = true) -> some View {
        clipShape(RoundedRectangle(cornerRadius: radius, style: .circular))
    }
    public func opacity(_ opacity: Double) -> some View {
        _modified(_WrapModifier { node, _ in _EffectNode(child: node, effect: .opacity(opacity)) })
    }
    public func hidden() -> some View {
        _modified(_WrapModifier { node, _ in _EffectNode(child: node, effect: .hidden) })
    }
    public func shadow(color: Color = Color(.sRGBLinear, white: 0, opacity: 0.33), radius: CGFloat, x: CGFloat = 0, y: CGFloat = 0) -> some View {
        _modified(_WrapModifier { node, ctx in
            _EffectNode(child: node, effect: .shadow(color: color.resolve(ctx.environment.colorScheme), radius: radius, x: x, y: y))
        })
    }
    public func contentShape<S: Shape>(_ shape: S, eoFill: Bool = false) -> some View { self }
    public func compositingGroup() -> some View { self }
    public func drawingGroup(opaque: Bool = false, colorMode: ColorRenderingMode = .nonLinear) -> some View { self }
    public func blur(radius: CGFloat, opaque: Bool = false) -> some View { self }
    public func brightness(_ amount: Double) -> some View { self }
    public func contrast(_ amount: Double) -> some View { self }
    public func saturation(_ amount: Double) -> some View { self }
    public func grayscale(_ amount: Double) -> some View { self }
    public func blendMode(_ blendMode: BlendMode) -> some View { self }
    public func rotationEffect(_ angle: Angle, anchor: UnitPoint = .center) -> some View { self }
    public func scaleEffect(_ scale: CGFloat, anchor: UnitPoint = .center) -> some View { self }
    public func scaleEffect(x: CGFloat = 1, y: CGFloat = 1, anchor: UnitPoint = .center) -> some View { self }
    public func animation<V: Equatable>(_ animation: Animation?, value: V) -> some View { self }
    public func animation(_ animation: Animation?) -> some View { self }
    public func transition(_ t: AnyTransition) -> some View { self }
    public func zIndex(_ value: Double) -> some View { self }
    public func id<ID: Hashable>(_ id: ID) -> some View { self }
    public func tag<V: Hashable>(_ tag: V) -> some View { self }
    public func accessibilityLabel(_ label: Text) -> some View { self }
    public func accessibilityLabel(_ label: String) -> some View { self }
    public func accessibilityHint(_ hint: Text) -> some View { self }
    public func accessibilityHidden(_ hidden: Bool) -> some View { self }
    public func accessibilityIdentifier(_ identifier: String) -> some View { self }
    public func ignoresSafeArea(_ regions: SafeAreaRegions = .all, edges: Edge.Set = .all) -> some View {
        _modified(_WrapModifier { node, _ in _IgnoresSafeAreaNode(child: node, edges: edges) })
    }
    public func edgesIgnoringSafeArea(_ edges: Edge.Set) -> some View { ignoresSafeArea(.all, edges: edges) }
    public func safeAreaInset<V: View>(edge: VerticalEdge, alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil, @ViewBuilder content: () -> V) -> some View { self }
    public func statusBarHidden(_ hidden: Bool = true) -> some View { self }
    public func preferredColorScheme(_ scheme: ColorScheme) -> some View { environment(\.colorScheme, scheme) }
    public func listRowSeparator(_ visibility: Visibility, edges: VerticalEdge.Set = .all) -> some View { self }
    public func listRowInsets(_ insets: EdgeInsets?) -> some View { self }
    public func listRowBackground<V: View>(_ view: V?) -> some View { self }
    public func listSectionSeparator(_ visibility: Visibility, edges: VerticalEdge.Set = .all) -> some View { self }
    public func scrollContentBackground(_ visibility: Visibility) -> some View { self }
    public func scrollIndicators(_ visibility: ScrollIndicatorVisibility, axes: Axis.Set = [.vertical, .horizontal]) -> some View { self }
    public func scrollDisabled(_ disabled: Bool) -> some View { self }
    public func keyboardType(_ type: UIKeyboardType) -> some View { self }
    public func textInputAutocapitalization(_ autocapitalization: TextInputAutocapitalization?) -> some View { self }
    public func autocorrectionDisabled(_ disable: Bool = true) -> some View { self }
    public func submitLabel(_ submitLabel: SubmitLabel) -> some View { self }
    public func onSubmit(of triggers: SubmitTriggers = .text, _ action: @escaping () -> Void) -> some View { self }
    public func textFieldStyle<S: TextFieldStyle>(_ style: S) -> some View { self }
    public func toggleStyle<S: ToggleStyle>(_ style: S) -> some View { self }
    public func pickerStyle<S: PickerStyle>(_ style: S) -> some View { self }
    public func labelStyle<S: LabelStyle>(_ style: S) -> some View { self }
    public func symbolRenderingMode(_ mode: SymbolRenderingMode?) -> some View { self }
    public func symbolVariant(_ variant: SymbolVariants) -> some View { self }
    public func imageScale(_ scale: Image.Scale) -> some View { environment(\.imageScale, scale) }
    public func badge(_ count: Int) -> some View { self }
    public func badge(_ label: Text?) -> some View { self }
    public func help(_ text: String) -> some View { self }
    public func sensoryFeedback<T: Equatable>(_ feedback: SensoryFeedback, trigger: T) -> some View { self }
    public func refreshable(action: @escaping @Sendable () async -> Void) -> some View { self }
    public func searchable(text: Binding<String>, prompt: Text? = nil) -> some View { self }
    public func searchable(text: Binding<String>, prompt: String) -> some View { self }
    public func swipeActions<T: View>(edge: HorizontalEdge = .trailing, allowsFullSwipe: Bool = true, @ViewBuilder content: () -> T) -> some View { self }
    public func contextMenu<MenuItems: View>(@ViewBuilder menuItems: () -> MenuItems) -> some View { self }
    public func focused(_ condition: FocusState<Bool>.Binding) -> some View { self }
    public func focused<Value: Hashable>(_ binding: FocusState<Value>.Binding, equals value: Value) -> some View { self }
}

public enum Visibility: Hashable, CaseIterable, Sendable { case automatic, visible, hidden }
public struct ScrollIndicatorVisibility: Sendable {
    public static let automatic = ScrollIndicatorVisibility()
    public static let visible = ScrollIndicatorVisibility()
    public static let hidden = ScrollIndicatorVisibility()
    public static let never = ScrollIndicatorVisibility()
}
public struct SafeAreaRegions: OptionSet, Sendable {
    public let rawValue: UInt
    public init(rawValue: UInt) { self.rawValue = rawValue }
    public static let container = SafeAreaRegions(rawValue: 1)
    public static let keyboard = SafeAreaRegions(rawValue: 2)
    public static let all: SafeAreaRegions = [.container, .keyboard]
}
extension VerticalEdge {
    public struct Set: OptionSet, Sendable {
        public let rawValue: Int8
        public init(rawValue: Int8) { self.rawValue = rawValue }
        public static let top = Set(rawValue: 1)
        public static let bottom = Set(rawValue: 2)
        public static let all: Set = [.top, .bottom]
    }
}
public enum ColorRenderingMode: Hashable, Sendable { case nonLinear, linear, extendedLinear }
public enum BlendMode: Hashable, Sendable { case normal, multiply, screen, overlay, darken, lighten, colorDodge, colorBurn, softLight, hardLight, difference, exclusion, hue, saturation, color, luminosity, sourceAtop, destinationOver, destinationOut, plusDarker, plusLighter }
public struct AnyTransition: Sendable {
    public static let identity = AnyTransition()
    public static let opacity = AnyTransition()
    public static let scale = AnyTransition()
    public static let slide = AnyTransition()
    public static func move(edge: Edge) -> AnyTransition { AnyTransition() }
    public static func asymmetric(insertion: AnyTransition, removal: AnyTransition) -> AnyTransition { AnyTransition() }
    public func combined(with other: AnyTransition) -> AnyTransition { self }
    public func animation(_ animation: Animation?) -> AnyTransition { self }
}
public struct SensoryFeedback: Sendable {
    public static let success = SensoryFeedback()
    public static let warning = SensoryFeedback()
    public static let error = SensoryFeedback()
    public static let selection = SensoryFeedback()
    public static let increase = SensoryFeedback()
    public static let decrease = SensoryFeedback()
    public static func impact(weight: Int = 0, intensity: Double = 1) -> SensoryFeedback { SensoryFeedback() }
}
public enum UIKeyboardType: Int, Sendable { case `default`, asciiCapable, numbersAndPunctuation, URL, numberPad, phonePad, namePhonePad, emailAddress, decimalPad, twitter, webSearch, asciiCapableNumberPad }
public struct TextInputAutocapitalization: Sendable {
    public static let never = TextInputAutocapitalization()
    public static let words = TextInputAutocapitalization()
    public static let sentences = TextInputAutocapitalization()
    public static let characters = TextInputAutocapitalization()
}
public struct SubmitLabel: Sendable {
    public static let done = SubmitLabel(), go = SubmitLabel(), send = SubmitLabel(), join = SubmitLabel(), route = SubmitLabel()
    public static let search = SubmitLabel(), `return` = SubmitLabel(), next = SubmitLabel(), `continue` = SubmitLabel()
}
public struct SubmitTriggers: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let text = SubmitTriggers(rawValue: 1)
    public static let search = SubmitTriggers(rawValue: 2)
}
public enum SymbolRenderingMode: Sendable { case monochrome, multicolor, hierarchical, palette }
public struct SymbolVariants: Hashable, Sendable {
    public static let none = SymbolVariants(), circle = SymbolVariants(), square = SymbolVariants()
    public static let rectangle = SymbolVariants(), fill = SymbolVariants(), slash = SymbolVariants()
}

@propertyWrapper
public struct FocusState<Value: Hashable>: DynamicProperty {
    @propertyWrapper
    public struct Binding {
        let get: () -> Value
        let set: (Value) -> Void
        public var wrappedValue: Value { get { get() } nonmutating set { set(newValue) } }
        public var projectedValue: Binding { self }
    }
    final class Box { var value: Value; init(_ v: Value) { value = v } }
    let box: Box
    public init() where Value == Bool { box = Box(false) }
    public init<T: Hashable>() where Value == T? { box = Box(nil) }
    public var wrappedValue: Value { get { box.value } nonmutating set { box.value = newValue } }
    public var projectedValue: Binding {
        let box = self.box
        return Binding(get: { box.value }, set: { box.value = $0 })
    }
}

/// Stretches a view that reaches the safe area's edge out to the screen's edge on that side, as iOS does.
@MainActor
final class _IgnoresSafeAreaNode: _WrapperNode {
    let edges: Edge.Set
    init(child: _Node, edges: Edge.Set) {
        self.edges = edges
        super.init(child: child)
    }
    override func place(_ rect: CGRect) {
        var r = rect
        let safe = _Device.safeRect
        let device = CGRect(origin: .zero, size: _Device.size)
        // An edge at or past the safe area's goes out to the screen's (content taller than the safe area, centred in
        // it, reaches past both).
        if edges.contains(.top), r.minY <= safe.minY + 0.5 { r.size.height += r.minY - device.minY; r.origin.y = device.minY }
        if edges.contains(.bottom), r.maxY >= safe.maxY - 0.5 { r.size.height = device.maxY - r.minY }
        if edges.contains(.leading), r.minX <= safe.minX + 0.5 { r.size.width += r.minX - device.minX; r.origin.x = device.minX }
        if edges.contains(.trailing), r.maxX >= safe.maxX - 0.5 { r.size.width = device.maxX - r.minX }
        frame = r
        // The content is offered the stretched frame: a Color or a shape fills it.
        child.place(_align(child, child.sizeThatFits(ProposedViewSize(r.size)), in: r, .center))
    }
}

// MARK: - Gestures and lifecycle

@MainActor
final class _TapNode: _WrapperNode {
    let action: () -> Void
    init(child: _Node, action: @escaping () -> Void) {
        self.action = action
        super.init(child: child)
    }
    override func emit(_ list: _DisplayList) {
        child.emit(list)
        list.hit(frame, action: _Graph.shared.register(action))
    }
}

extension View {
    public func onTapGesture(count: Int = 1, perform action: @escaping () -> Void) -> some View {
        _modified(_WrapModifier { node, _ in _TapNode(child: node, action: action) })
    }
    public func onLongPressGesture(minimumDuration: Double = 0.5, maximumDistance: CGFloat = 10, perform action: @escaping () -> Void, onPressingChanged: ((Bool) -> Void)? = nil) -> some View { self }

    /// Runs once, the first time this view (by identity) appears in a preview.
    public func onAppear(perform action: (() -> Void)? = nil) -> some View {
        _modified(_WrapModifier { node, ctx in
            if let action {
                let key = ctx.path + "#onAppear"
                let seen = ctx.graph.cell(key) { false }
                if !seen.value {
                    seen.value = true
                    ctx.graph.pendingAppear.append(action)
                }
            }
            return node
        })
    }
    public func onDisappear(perform action: (() -> Void)? = nil) -> some View { self }
    public func task(priority: TaskPriority = .userInitiated, _ action: @escaping @Sendable () async -> Void) -> some View { self }
    public func task<T: Equatable>(id value: T, priority: TaskPriority = .userInitiated, _ action: @escaping @Sendable () async -> Void) -> some View { self }
    public func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping (V, V) -> Void) -> some View { self }
    public func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping () -> Void) -> some View { self }
    public func onReceive<P>(_ publisher: P, perform action: @escaping (Any) -> Void) -> some View { self }
}

extension _Graph {
    private static var pending: [() -> Void] = []
    var pendingAppear: [() -> Void] {
        get { _Graph.pending }
        set { _Graph.pending = newValue }
    }
}
