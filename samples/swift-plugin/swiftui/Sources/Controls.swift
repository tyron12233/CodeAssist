// Controls: Button (with the public ButtonStyle protocol and iOS's built-in styles), Image and SF Symbols, Label,
// Toggle, TextField, Slider, Stepper, ProgressView, Link. Their looks are iOS's; numbers marked "calibrated" were
// measured against the iOS Simulator by tools/swiftui-parity.

import Foundation

// MARK: - Button

public enum ButtonRole: Hashable, Sendable { case cancel, destructive, close, confirm }

public struct ButtonStyleConfiguration {
    public struct Label: View, _PrimitiveView {
        let nodes: [_Node]
        public var body: Never { _neverBody() }
        func _resolve(_ ctx: _ResolveContext) -> [_Node] { nodes }
    }
    public let role: ButtonRole?
    public let label: Label
    public let isPressed: Bool
}

public protocol ButtonStyle {
    associatedtype Body: View
    @ViewBuilder func makeBody(configuration: Configuration) -> Body
    typealias Configuration = ButtonStyleConfiguration
}

public struct PrimitiveButtonStyleConfiguration {
    public typealias Label = ButtonStyleConfiguration.Label
    public let role: ButtonRole?
    public let label: Label
    let action: () -> Void
    public func trigger() { action() }
}

public protocol PrimitiveButtonStyle {
    associatedtype Body: View
    @ViewBuilder func makeBody(configuration: Configuration) -> Body
    typealias Configuration = PrimitiveButtonStyleConfiguration
}

enum _ButtonStyleKind {
    case automatic, plain, borderless, bordered, borderedProminent, glass, glassProminent
    case custom((ButtonStyleConfiguration) -> AnyView)
    case customPrimitive((PrimitiveButtonStyleConfiguration) -> AnyView)
}

public struct PlainButtonStyle: PrimitiveButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct BorderlessButtonStyle: PrimitiveButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct BorderedButtonStyle: PrimitiveButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct BorderedProminentButtonStyle: PrimitiveButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
public struct DefaultButtonStyle: PrimitiveButtonStyle {
    public init() {}
    public func makeBody(configuration: Configuration) -> some View { configuration.label }
}
extension PrimitiveButtonStyle where Self == PlainButtonStyle { public static var plain: PlainButtonStyle { PlainButtonStyle() } }
extension PrimitiveButtonStyle where Self == BorderlessButtonStyle { public static var borderless: BorderlessButtonStyle { BorderlessButtonStyle() } }
extension PrimitiveButtonStyle where Self == BorderedButtonStyle { public static var bordered: BorderedButtonStyle { BorderedButtonStyle() } }
extension PrimitiveButtonStyle where Self == BorderedProminentButtonStyle { public static var borderedProminent: BorderedProminentButtonStyle { BorderedProminentButtonStyle() } }
extension PrimitiveButtonStyle where Self == DefaultButtonStyle { public static var automatic: DefaultButtonStyle { DefaultButtonStyle() } }

extension View {
    public func buttonStyle<S: ButtonStyle>(_ style: S) -> some View {
        _modified(_EnvironmentModifier { $0._buttonStyle = .custom { AnyView(style.makeBody(configuration: $0)) } })
    }
    public func buttonStyle<S: PrimitiveButtonStyle>(_ style: S) -> some View {
        let kind: _ButtonStyleKind
        switch style {
        case is PlainButtonStyle: kind = .plain
        case is BorderlessButtonStyle: kind = .borderless
        case is BorderedButtonStyle: kind = .bordered
        case is BorderedProminentButtonStyle: kind = .borderedProminent
        case is DefaultButtonStyle: kind = .automatic
        case is GlassButtonStyle: kind = .glass
        case is GlassProminentButtonStyle: kind = .glassProminent
        default: kind = .customPrimitive { AnyView(style.makeBody(configuration: $0)) }
        }
        return _modified(_EnvironmentModifier { $0._buttonStyle = kind })
    }
    public func buttonBorderShape(_ shape: ButtonBorderShape) -> some View { self }
}

public struct ButtonBorderShape: Sendable {
    public static let automatic = ButtonBorderShape(), capsule = ButtonBorderShape(), circle = ButtonBorderShape()
    public static let roundedRectangle = ButtonBorderShape()
    public static func roundedRectangle(radius: CGFloat) -> ButtonBorderShape { ButtonBorderShape() }
}

public struct Button<Label: View>: View, _PrimitiveView {
    let role: ButtonRole?
    let action: () -> Void
    let label: Label

    public init(action: @escaping () -> Void, @ViewBuilder label: () -> Label) {
        role = nil
        self.action = action
        self.label = label()
    }
    public init(role: ButtonRole?, action: @escaping () -> Void, @ViewBuilder label: () -> Label) {
        self.role = role
        self.action = action
        self.label = label()
    }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let env = ctx.environment
        let tint: Color = role == .destructive ? .red : (env._tint ?? .accentColor)
        let style = env._buttonStyle
        let labelCtx = ctx.child("label").with { $0._buttonStyle = .automatic }

        let styled: [_Node]
        switch style {
        case .custom(let make):
            let labelNodes = _resolveView(label, labelCtx)
            styled = _resolveView(make(ButtonStyleConfiguration(role: role, label: .init(nodes: labelNodes), isPressed: false)), ctx.child("style"))
        case .customPrimitive(let make):
            let labelNodes = _resolveView(label, labelCtx)
            let config = PrimitiveButtonStyleConfiguration(role: role, label: .init(nodes: labelNodes), action: action)
            return _resolveView(make(config), ctx.child("style"))
        case .plain:
            styled = _resolveView(label, labelCtx)
        case .automatic, .borderless:
            let disabled = !env.isEnabled
            styled = _resolveView(label, labelCtx.with { $0._foreground = AnyShapeStyle(disabled ? Color.gray : tint) })
        case .glass, .glassProminent:
            // iOS 26's glass buttons, measured: the label (primary, or white on prominent) padded 12 x 7 on a capsule of
            // regular glass, tinted with the accent colour when prominent.
            let prominent: Bool
            if case .glassProminent = style { prominent = true } else { prominent = false }
            let inner = _resolveView(label, labelCtx.with { $0._foreground = AnyShapeStyle(prominent ? Color.white : Color.primary) })
            styled = inner.map { node in
                let padded = _PaddingNode(child: node, insets: EdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12))
                return _GlassNode(child: padded, glass: prominent ? Glass.regular.tint(tint) : .regular, shape: _AnyShape(Capsule()))
            }
        case .bordered:
            // A tinted label on a translucent fill of the tint, in a capsule.
            let inner = _resolveView(label, labelCtx.with { $0._foreground = AnyShapeStyle(tint) })
            styled = inner.map { node in
                let padded = _PaddingNode(child: node, insets: EdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12))
                let fill = _ShapeNode(path: ctx.path + "/fill", env: env, shape: _AnyShape(Capsule()), fill: AnyShapeStyle(Color.tertiarySystemFill), stroke: nil)
                return _LayeredNode(child: padded, secondary: [fill], alignment: .center, isOverlay: false)
            }
        case .borderedProminent:
            let inner = _resolveView(label, labelCtx.with { $0._foreground = AnyShapeStyle(Color.white) })
            styled = inner.map { node in
                let padded = _PaddingNode(child: node, insets: EdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12))
                let fill = _ShapeNode(path: ctx.path + "/fill", env: env, shape: _AnyShape(Capsule()), fill: AnyShapeStyle(tint), stroke: nil)
                return _LayeredNode(child: padded, secondary: [fill], alignment: .center, isOverlay: false)
            }
        }
        guard env.isEnabled else { return styled }
        return styled.map { _TapNode(child: $0, action: action) }
    }
}

extension Button where Label == Text {
    public init(_ titleKey: LocalizedStringKey, action: @escaping () -> Void) {
        self.init(action: action) { Text(titleKey) }
    }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, action: @escaping () -> Void) {
        self.init(action: action) { Text(title) }
    }
    public init(_ titleKey: LocalizedStringKey, role: ButtonRole?, action: @escaping () -> Void) {
        self.init(role: role, action: action) { Text(titleKey) }
    }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, role: ButtonRole?, action: @escaping () -> Void) {
        self.init(role: role, action: action) { Text(title) }
    }
}

extension Button where Label == SwiftUILabel<Text, Image> {
    public init(_ titleKey: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) {
        self.init(action: action) { SwiftUILabel(titleKey, systemImage: systemImage) }
    }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, systemImage: String, action: @escaping () -> Void) {
        self.init(action: action) { SwiftUILabel(title, systemImage: systemImage) }
    }
}

// MARK: - Image

public struct Image: Equatable, Sendable {
    let systemName: String?
    let assetName: String?
    var isResizable = false
    var renderingMode: TemplateRenderingMode?

    public init(systemName: String) { self.systemName = systemName; assetName = nil }
    public init(systemName: String, variableValue: Double?) { self.init(systemName: systemName) }
    public init(_ name: String, bundle: Bundle? = nil) { systemName = nil; assetName = name }
    public init(decorative name: String, bundle: Bundle? = nil) { self.init(name, bundle: bundle) }

    public func resizable(capInsets: EdgeInsets = EdgeInsets(), resizingMode: ResizingMode = .stretch) -> Image {
        var copy = self
        copy.isResizable = true
        return copy
    }
    public func renderingMode(_ renderingMode: TemplateRenderingMode?) -> Image {
        var copy = self
        copy.renderingMode = renderingMode
        return copy
    }
    public func interpolation(_ interpolation: Interpolation) -> Image { self }
    public func antialiased(_ isAntialiased: Bool) -> Image { self }
    public func symbolRenderingMode(_ mode: SymbolRenderingMode?) -> Image { self }

    public enum ResizingMode: Hashable, Sendable { case tile, stretch }
    public enum TemplateRenderingMode: Hashable, Sendable { case template, original }
    public enum Interpolation: Hashable, Sendable { case none, low, medium, high }
    public enum Scale: Hashable, Sendable { case small, medium, large }
}

extension Image: View, _PrimitiveView {
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_ImageNode(path: ctx.path, env: ctx.environment, image: self)]
    }
}

/// An SF Symbol, drawn by the host from the open symbol set (by name), sized from the font as iOS sizes symbols; or
/// an asset image, which previews draw as a placeholder until asset catalogs are read.
@MainActor
final class _ImageNode: _Node {
    let image: Image
    init(path: String, env: EnvironmentValues, image: Image) {
        self.image = image
        super.init(path: path, env: env)
    }

    private var fontSize: CGFloat { (env.font ?? .body).size }

    /// The symbol's name with the environment's variants (`.symbolVariant(.fill)`, a tab bar's fill) applied where
    /// the symbol has them.
    private lazy var symbolName: String? = {
        guard var name = image.systemName else { return nil }
        for suffix in env.symbolVariants.suffixes where !name.contains(suffix + ".") && !name.hasSuffix(suffix) {
            if _SymbolMetrics.shared.frame(name + suffix, size: 17, large: false) != nil { name += suffix }
        }
        return name
    }()

    /// A symbol's frame at the font's point size, as iOS gives it (_SymbolMetrics, measured in the Simulator); a name
    /// the table does not know gets a box of the font's size.
    private var intrinsic: CGSize {
        if let name = symbolName {
            if let m = _SymbolMetrics.shared.frame(name, size: fontSize, large: env[_ImageScaleKey.self] == .large) { return m.size }
            let s = _ceilToPixel(fontSize * 1.2)
            return CGSize(width: s, height: s)
        }
        return CGSize(width: 100, height: 100)
    }

    override func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) {
        guard let name = symbolName, !image.isResizable,
              let m = _SymbolMetrics.shared.frame(name, size: fontSize, large: env[_ImageScaleKey.self] == .large) else { return (nil, nil) }
        return (size.height - m.baseline, size.height - m.baseline)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        if image.isResizable { return proposal.replacingUnspecifiedDimensions(by: intrinsic) }
        return intrinsic
    }

    override func emit(_ list: _DisplayList) {
        let color = (env._foreground?._resolvedColor(env) ?? .primary).resolve(env)
        if let name = symbolName {
            let weight = (env._weight ?? (env.font ?? .body).weight).value
            list.symbol(frame, name: name, size: image.isResizable ? min(frame.width, frame.height) / 1.2 : fontSize, weight: weight, color: color,
                        large: env[_ImageScaleKey.self] == .large)
        } else {
            list.shape(Path(roundedRect: frame, cornerRadius: 4), style: AnyShapeStyle(Color.secondarySystemFill), stroke: nil, env: env)
            list.symbol(frame, name: "photo", size: min(frame.width, frame.height) / 3, weight: 400, color: Color.gray.resolve(env))
        }
    }
}

public enum ContentMode: Hashable, CaseIterable, Sendable { case fit, fill }

/// SF Symbol frames by point size, measured from iOS (tools/swiftui-parity/SymbolProbe.swift): the file named by
/// CODEASSIST_SYMBOL_METRICS, one line per symbol, `w,h,baseline` in thirds of a point at each of the sizes its header
/// lists. Between two measured sizes a frame interpolates; outside them it scales.
final class _SymbolMetrics: @unchecked Sendable {
    static let shared = _SymbolMetrics()
    private var sizes: [CGFloat] = []
    /// Where the large-scale entry at body size (`L17`) sits in a row, if the table has one.
    private var largeIndex: Int?
    private var table: [String: [Int]]?

    struct Frame {
        let size: CGSize
        let baseline: CGFloat
    }

    private func load() -> [String: [Int]] {
        if let table { return table }
        var result: [String: [Int]] = [:]
        if let path = ProcessInfo.processInfo.environment["CODEASSIST_SYMBOL_METRICS"],
           let text = try? String(contentsOfFile: path, encoding: .utf8) {
            for line in text.split(separator: "\n") {
                if line.hasPrefix("#") {
                    if let open = line.firstIndex(of: "("), let close = line.firstIndex(of: ")") {
                        let tokens = line[line.index(after: open)..<close].split(separator: ",")
                        sizes = tokens.compactMap { Double($0) }.map { CGFloat($0) }
                        largeIndex = tokens.firstIndex(of: "L17")
                    }
                    continue
                }
                guard let tab = line.firstIndex(of: "\t") else { continue }
                let values = line[line.index(after: tab)...].split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Int($0) }
                result[String(line[..<tab])] = values
            }
        }
        table = result
        return result
    }

    /// [large]: the large symbol scale (a List row's Label icon, `.imageScale(.large)`): exact at 17 pt, elsewhere the
    /// medium frame scaled by this symbol's large/medium ratio at 17 pt.
    func frame(_ name: String, size: CGFloat, large: Bool = false) -> Frame? {
        guard let medium = frame(name, size: size) else { return nil }
        guard large, let li = largeIndex, let v = load()[name], v.count > 3 * li + 2, let m17 = frame(name, size: 17) else { return medium }
        let lw = CGFloat(v[3 * li]) / 3, lh = CGFloat(v[3 * li + 1]) / 3, lb = CGFloat(v[3 * li + 2]) / 3
        if size == 17 { return Frame(size: CGSize(width: lw, height: lh), baseline: lb) }
        let fw = lw / max(m17.size.width, 0.1), fh = lh / max(m17.size.height, 0.1)
        return Frame(size: CGSize(width: _roundToPixel(medium.size.width * fw), height: _roundToPixel(medium.size.height * fh)), baseline: medium.baseline * fh)
    }

    func frame(_ name: String, size: CGFloat) -> Frame? {
        guard let v = load()[name], !sizes.isEmpty, v.count >= sizes.count * 3 else { return nil }
        func at(_ i: Int) -> (CGFloat, CGFloat, CGFloat) { (CGFloat(v[3 * i]) / 3, CGFloat(v[3 * i + 1]) / 3, CGFloat(v[3 * i + 2]) / 3) }
        let (w, h, b): (CGFloat, CGFloat, CGFloat)
        if let exact = sizes.firstIndex(of: size) {
            (w, h, b) = at(exact)
        } else if size < sizes[0] {
            let f = size / sizes[0]; let a = at(0); (w, h, b) = (a.0 * f, a.1 * f, a.2 * f)
        } else if size > sizes[sizes.count - 1] {
            let f = size / sizes[sizes.count - 1]; let a = at(sizes.count - 1); (w, h, b) = (a.0 * f, a.1 * f, a.2 * f)
        } else {
            let hi = sizes.firstIndex { $0 > size }!
            let lo = hi - 1
            let t = (size - sizes[lo]) / (sizes[hi] - sizes[lo])
            let a = at(lo), c = at(hi)
            (w, h, b) = (a.0 + (c.0 - a.0) * t, a.1 + (c.1 - a.1) * t, a.2 + (c.2 - a.2) * t)
        }
        return Frame(size: CGSize(width: _roundToPixel(w), height: _roundToPixel(h)), baseline: b)
    }
}

func _roundToPixel(_ v: CGFloat) -> CGFloat { (v * _scale).rounded() / _scale }

@MainActor
final class _AspectNode: _WrapperNode {
    let ratio: CGFloat?
    let mode: ContentMode
    init(child: _Node, ratio: CGFloat?, mode: ContentMode) {
        self.ratio = ratio
        self.mode = mode
        super.init(child: child)
    }
    private func aspect() -> CGFloat {
        if let ratio { return ratio }
        let ideal = child.sizeThatFits(.unspecified)
        return ideal.height > 0 ? ideal.width / ideal.height : 1
    }
    private func fitted(_ proposal: ProposedViewSize) -> CGSize {
        let r = aspect()
        let p = proposal.replacingUnspecifiedDimensions(by: child.sizeThatFits(.unspecified))
        let byWidth = CGSize(width: p.width, height: p.width / r)
        let byHeight = CGSize(width: p.height * r, height: p.height)
        if mode == .fit { return byWidth.height <= p.height ? byWidth : byHeight }
        return byWidth.height >= p.height ? byWidth : byHeight
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { child.sizeThatFits(ProposedViewSize(fitted(proposal))) }
    override func place(_ rect: CGRect) {
        frame = rect
        let size = child.sizeThatFits(ProposedViewSize(fitted(ProposedViewSize(rect.size))))
        child.place(_align(child, size, in: rect, .center))
    }
}

extension View {
    public func aspectRatio(_ aspectRatio: CGFloat? = nil, contentMode: ContentMode) -> some View {
        _modified(_WrapModifier { node, _ in _AspectNode(child: node, ratio: aspectRatio, mode: contentMode) })
    }
    public func aspectRatio(_ aspectRatio: CGSize, contentMode: ContentMode) -> some View {
        self.aspectRatio(aspectRatio.width / max(aspectRatio.height, 0.0001), contentMode: contentMode)
    }
    public func scaledToFit() -> some View { aspectRatio(contentMode: .fit) }
    public func scaledToFill() -> some View { aspectRatio(contentMode: .fill) }
}

// MARK: - Label

/// SwiftUI's `Label` (named so inside the engine because ButtonStyleConfiguration and others have a nested `Label`).
public struct SwiftUILabel<Title: View, Icon: View>: View {
    let title: Title
    let icon: Icon
    public init(@ViewBuilder title: () -> Title, @ViewBuilder icon: () -> Icon) {
        self.title = title()
        self.icon = icon()
    }
    @Environment(\.self) private var environment

    public var body: some View {
        if environment[_InListKey.self] {
            // iOS's list row label, measured: the icon at the large scale, in the accent colour, centred in a
            // 24-point column; the title 16 points after it, so titles line up whatever the icons' widths.
            HStack(spacing: 0) {
                icon.imageScale(.large).foregroundStyle(TintShapeStyle()).frame(width: 24)
                title.padding(.leading, 16)
            }
            ._separatorLeading(40)
        } else {
            switch environment._labelStyle {
            case .iconOnly: icon
            case .titleOnly: title
            case .unavailable:
                // ContentUnavailableView's label: a large secondary symbol over a title2 bold title.
                VStack(spacing: 15 + 1.0 / 3) {
                    icon.font(.system(size: 48)).foregroundStyle(.secondary)
                    title.font(.title2.bold())
                }
            default:
                HStack(spacing: 8) {
                    icon
                    title
                }
            }
        }
    }
}

extension SwiftUILabel where Title == Text, Icon == Image {
    public init(_ titleKey: LocalizedStringKey, systemImage name: String) {
        self.init(title: { Text(titleKey) }, icon: { Image(systemName: name) })
    }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, systemImage name: String) {
        self.init(title: { Text(title) }, icon: { Image(systemName: name) })
    }
    public init(_ titleKey: LocalizedStringKey, image name: String) {
        self.init(title: { Text(titleKey) }, icon: { Image(name) })
    }
}

public typealias Label<Title: View, Icon: View> = SwiftUILabel<Title, Icon>

public protocol LabelStyle {}
enum _LabelStyleKind { case automatic, iconOnly, titleOnly, titleAndIcon, unavailable }
public struct TitleOnlyLabelStyle: LabelStyle { public init() {} }
public struct IconOnlyLabelStyle: LabelStyle { public init() {} }
public struct TitleAndIconLabelStyle: LabelStyle { public init() {} }
public struct DefaultLabelStyle: LabelStyle { public init() {} }
extension LabelStyle where Self == TitleOnlyLabelStyle { public static var titleOnly: TitleOnlyLabelStyle { TitleOnlyLabelStyle() } }
extension LabelStyle where Self == IconOnlyLabelStyle { public static var iconOnly: IconOnlyLabelStyle { IconOnlyLabelStyle() } }
extension LabelStyle where Self == TitleAndIconLabelStyle { public static var titleAndIcon: TitleAndIconLabelStyle { TitleAndIconLabelStyle() } }
extension LabelStyle where Self == DefaultLabelStyle { public static var automatic: DefaultLabelStyle { DefaultLabelStyle() } }

// MARK: - Toggle

public protocol ToggleStyle {}
public struct SwitchToggleStyle: ToggleStyle { public init() {} }
public struct DefaultToggleStyle: ToggleStyle { public init() {} }
public struct ButtonToggleStyle: ToggleStyle { public init() {} }
extension ToggleStyle where Self == SwitchToggleStyle { public static var `switch`: SwitchToggleStyle { SwitchToggleStyle() } }
extension ToggleStyle where Self == DefaultToggleStyle { public static var automatic: DefaultToggleStyle { DefaultToggleStyle() } }
extension ToggleStyle where Self == ButtonToggleStyle { public static var button: ButtonToggleStyle { ButtonToggleStyle() } }

/// iOS's switch: a capsule track (green when on) with a white knob. Calibrated size: see _SwitchNode.
public struct Toggle<Label: View>: View {
    let isOn: Binding<Bool>
    let label: Label
    public init(isOn: Binding<Bool>, @ViewBuilder label: () -> Label) {
        self.isOn = isOn
        self.label = label()
    }
    public var body: some View {
        HStack {
            label
            Spacer()
            _Switch(isOn: isOn)
        }
    }
}

extension Toggle where Label == Text {
    public init(_ titleKey: LocalizedStringKey, isOn: Binding<Bool>) { self.init(isOn: isOn) { Text(titleKey) } }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, isOn: Binding<Bool>) { self.init(isOn: isOn) { Text(title) } }
}

struct _Switch: View, _PrimitiveView {
    let isOn: Binding<Bool>
    var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let binding = isOn
        let node = _SwitchNode(path: ctx.path, env: ctx.environment, isOn: binding.wrappedValue)
        return [_TapNode(child: node) { binding.wrappedValue.toggle() }]
    }
}

@MainActor
final class _SwitchNode: _Node {
    let isOn: Bool
    static let size = CGSize(width: 61, height: 28) // calibrated
    init(path: String, env: EnvironmentValues, isOn: Bool) {
        self.isOn = isOn
        super.init(path: path, env: env)
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { Self.size }
    /// iOS 26's switch, measured: a 63 x 28 track drawn 1 point past each side of its 61-point frame, and a 37 x 24
    /// knob inset 2 points; off, the track is #C5C5C7.
    override func emit(_ list: _DisplayList) {
        let track = isOn ? (env._tint ?? .green) : Color(light: _RGBA(hex: 0xC5C5C7), dark: _RGBA(hex: 0x39393D))
        let rect = frame.insetBy(dx: -1, dy: 0)
        list.shape(Path(roundedRect: rect, cornerRadius: rect.height / 2, style: .circular), style: AnyShapeStyle(track), stroke: nil, env: env)
        let knob = CGRect(x: isOn ? rect.maxX - 2 - 37 : rect.minX + 2, y: rect.minY + 2, width: 37, height: 24)
        list.pushShadow(color: _RGBA(0, 0, 0, 0.12), radius: 2, x: 0, y: 1)
        list.shape(Path(roundedRect: knob, cornerRadius: 12, style: .circular), style: AnyShapeStyle(Color.white), stroke: nil, env: env)
        list.pop()
    }
}

// MARK: - TextField

public protocol TextFieldStyle {}
public struct DefaultTextFieldStyle: TextFieldStyle { public init() {} }
public struct PlainTextFieldStyle: TextFieldStyle { public init() {} }
public struct RoundedBorderTextFieldStyle: TextFieldStyle { public init() {} }
extension TextFieldStyle where Self == DefaultTextFieldStyle { public static var automatic: DefaultTextFieldStyle { DefaultTextFieldStyle() } }
extension TextFieldStyle where Self == PlainTextFieldStyle { public static var plain: PlainTextFieldStyle { PlainTextFieldStyle() } }
extension TextFieldStyle where Self == RoundedBorderTextFieldStyle { public static var roundedBorder: RoundedBorderTextFieldStyle { RoundedBorderTextFieldStyle() } }

public struct TextField<Label: View>: View, _PrimitiveView {
    let text: Binding<String>
    let prompt: String
    let secure: Bool
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let value = text.wrappedValue
        let shown = value.isEmpty ? Text(prompt).foregroundColor(Color.tertiaryLabel)
            : Text(secure ? String(repeating: "•", count: value.count) : value)
        let nodes = _resolveView(shown, ctx.child("text"))
        let binding = text
        return nodes.map { node in
            _FieldNode(child: _FlexFrameNode(child: node, minWidth: nil, idealWidth: nil, maxWidth: .infinity,
                                             minHeight: nil, idealHeight: nil, maxHeight: nil, alignment: .leading),
                       setter: ctx.graph.register(setter: { binding.wrappedValue = $0 }), text: value, secure: secure)
        }
    }
}

extension TextField where Label == Text {
    public init(_ titleKey: LocalizedStringKey, text: Binding<String>) {
        self.text = text
        prompt = titleKey.spans.map(\.text).joined()
        secure = false
    }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, text: Binding<String>) {
        self.text = text
        prompt = String(title)
        secure = false
    }
    public init(_ titleKey: LocalizedStringKey, text: Binding<String>, prompt: Text?) { self.init(titleKey, text: text) }
    public init(_ titleKey: LocalizedStringKey, text: Binding<String>, axis: Axis) { self.init(titleKey, text: text) }
    public init<F: ParseableFormatStyle>(_ titleKey: LocalizedStringKey, value: Binding<F.FormatInput>, format: F) where F.FormatOutput == String {
        self.text = Binding(get: { format.format(value.wrappedValue) }, set: { if let v = try? format.parseStrategy.parse($0) { value.wrappedValue = v } })
        prompt = titleKey.spans.map(\.text).joined()
        secure = false
    }
    init(secure titleKey: String, text: Binding<String>) {
        self.text = text
        prompt = titleKey
        secure = true
    }
}

public struct SecureField<Label: View>: View {
    let field: TextField<Text>
    public var body: some View { field }
}

extension SecureField where Label == Text {
    public init(_ titleKey: LocalizedStringKey, text: Binding<String>) {
        field = TextField(secure: titleKey.spans.map(\.text).joined(), text: text)
    }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, text: Binding<String>) { field = TextField(secure: String(title), text: text) }
}

@MainActor
final class _FieldNode: _WrapperNode {
    let setter: Int
    let text: String
    let secure: Bool
    init(child: _Node, setter: Int, text: String, secure: Bool) {
        self.setter = setter
        self.text = text
        self.secure = secure
        super.init(child: child)
    }
    override func emit(_ list: _DisplayList) {
        child.emit(list)
        list.field(frame, setter: setter, text: text, secure: secure)
    }
}

// MARK: - Slider, Stepper, ProgressView, Link

public struct Slider<Label: View, ValueLabel: View>: View, _PrimitiveView {
    let value: Binding<Double>
    let bounds: ClosedRange<Double>
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_SliderNode(path: ctx.path, env: ctx.environment, fraction: (value.wrappedValue - bounds.lowerBound) / max(0.0001, bounds.upperBound - bounds.lowerBound))]
    }
}

extension Slider where Label == EmptyView, ValueLabel == EmptyView {
    public init<V: BinaryFloatingPoint>(value: Binding<V>, in bounds: ClosedRange<V> = 0...1, step: V.Stride = 1, onEditingChanged: @escaping (Bool) -> Void = { _ in }) where V.Stride: BinaryFloatingPoint {
        self.value = Binding(get: { Double(value.wrappedValue) }, set: { value.wrappedValue = V($0) })
        self.bounds = Double(bounds.lowerBound)...Double(bounds.upperBound)
    }
    public init<V: BinaryFloatingPoint>(value: Binding<V>, in bounds: ClosedRange<V> = 0...1, onEditingChanged: @escaping (Bool) -> Void = { _ in }) where V.Stride: BinaryFloatingPoint {
        self.value = Binding(get: { Double(value.wrappedValue) }, set: { value.wrappedValue = V($0) })
        self.bounds = Double(bounds.lowerBound)...Double(bounds.upperBound)
    }
}

@MainActor
final class _SliderNode: _Node {
    let fraction: Double
    init(path: String, env: EnvironmentValues, fraction: Double) {
        self.fraction = min(1, max(0, fraction))
        super.init(path: path, env: env)
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { CGSize(width: proposal.width ?? 120, height: 31) } // calibrated
    override func emit(_ list: _DisplayList) {
        let trackH: CGFloat = 6
        let track = CGRect(x: frame.minX, y: frame.midY - trackH / 2, width: frame.width, height: trackH)
        list.shape(Path(roundedRect: track, cornerRadius: trackH / 2, style: .circular), style: AnyShapeStyle(Color.tertiarySystemFill), stroke: nil, env: env)
        let filled = CGRect(x: track.minX, y: track.minY, width: track.width * fraction, height: trackH)
        list.shape(Path(roundedRect: filled, cornerRadius: trackH / 2, style: .circular), style: AnyShapeStyle(env._tint ?? .accentColor), stroke: nil, env: env)
        let knobW: CGFloat = 38, knobH: CGFloat = 24
        let x = frame.minX + (frame.width - knobW) * fraction
        list.pushShadow(color: _RGBA(0, 0, 0, 0.15), radius: 4, x: 0, y: 1)
        list.shape(Path(roundedRect: CGRect(x: x, y: frame.midY - knobH / 2, width: knobW, height: knobH), cornerRadius: knobH / 2, style: .circular),
                   style: AnyShapeStyle(Color.white), stroke: nil, env: env)
        list.pop()
    }
}

public struct Stepper<Label: View>: View {
    let label: Label
    let increment: () -> Void
    let decrement: () -> Void
    public init(@ViewBuilder label: () -> Label, onIncrement: (() -> Void)?, onDecrement: (() -> Void)?) {
        self.label = label()
        increment = onIncrement ?? {}
        decrement = onDecrement ?? {}
    }
    public var body: some View {
        HStack {
            label
            Spacer()
            HStack(spacing: 0) {
                Text("−").frame(width: 47, height: 32).onTapGesture(perform: decrement)
                Rectangle().fill(Color.separator).frame(width: 1 / _scale, height: 18)
                Text("+").frame(width: 47, height: 32).onTapGesture(perform: increment)
            }
            .background(Capsule().fill(Color.tertiarySystemFill))
        }
    }
}

extension Stepper where Label == Text {
    public init<V: Strideable>(_ titleKey: LocalizedStringKey, value: Binding<V>, in bounds: ClosedRange<V>, step: V.Stride = 1) {
        self.init(label: { Text(titleKey) },
                  onIncrement: { value.wrappedValue = min(bounds.upperBound, value.wrappedValue.advanced(by: step)) },
                  onDecrement: { value.wrappedValue = max(bounds.lowerBound, value.wrappedValue.advanced(by: -step)) })
    }
    public init<V: Strideable>(_ titleKey: LocalizedStringKey, value: Binding<V>, step: V.Stride = 1) {
        self.init(label: { Text(titleKey) },
                  onIncrement: { value.wrappedValue = value.wrappedValue.advanced(by: step) },
                  onDecrement: { value.wrappedValue = value.wrappedValue.advanced(by: -step) })
    }
}

public struct ProgressView<Label: View, CurrentValueLabel: View>: View {
    let fraction: Double?
    public var body: some View {
        if let fraction {
            _ProgressBar(fraction: fraction)
        } else {
            Image(systemName: "progress.indicator").foregroundStyle(Color.gray)
        }
    }
}

extension ProgressView where Label == EmptyView, CurrentValueLabel == EmptyView {
    public init() { fraction = nil }
    public init<V: BinaryFloatingPoint>(value: V?, total: V = 1.0) { fraction = value.map { Double($0 / max(total, 0.0001)) } }
}

extension ProgressView where CurrentValueLabel == EmptyView, Label == Text {
    public init(_ titleKey: LocalizedStringKey) { fraction = nil }
    public init<V: BinaryFloatingPoint>(_ titleKey: LocalizedStringKey, value: V?, total: V = 1.0) {
        fraction = value.map { Double($0 / max(total, 0.0001)) }
    }
}

struct _ProgressBar: View {
    let fraction: Double
    var body: some View {
        Capsule().fill(Color.tertiarySystemFill)
            .frame(height: 4)
            .overlay(alignment: .leading) {
                GeometryReader { proxy in
                    Capsule().fill(Color.accentColor).frame(width: proxy.size.width * min(1, max(0, fraction)))
                }
            }
    }
}

public struct Link<Label: View>: View {
    let destination: URL
    let label: Label
    public init(destination: URL, @ViewBuilder label: () -> Label) {
        self.destination = destination
        self.label = label()
    }
    public var body: some View { Button(action: {}) { label } }
}

extension Link where Label == Text {
    public init(_ titleKey: LocalizedStringKey, destination: URL) { self.init(destination: destination) { Text(titleKey) } }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, destination: URL) { self.init(destination: destination) { Text(title) } }
}

public protocol PickerStyle {}
public struct MenuPickerStyle: PickerStyle { public init() {} }
public struct SegmentedPickerStyle: PickerStyle { public init() {} }
public struct WheelPickerStyle: PickerStyle { public init() {} }
public struct InlinePickerStyle: PickerStyle { public init() {} }
public struct NavigationLinkPickerStyle: PickerStyle { public init() {} }
public struct DefaultPickerStyle: PickerStyle { public init() {} }
extension PickerStyle where Self == MenuPickerStyle { public static var menu: MenuPickerStyle { MenuPickerStyle() } }
extension PickerStyle where Self == SegmentedPickerStyle { public static var segmented: SegmentedPickerStyle { SegmentedPickerStyle() } }
extension PickerStyle where Self == WheelPickerStyle { public static var wheel: WheelPickerStyle { WheelPickerStyle() } }
extension PickerStyle where Self == InlinePickerStyle { public static var inline: InlinePickerStyle { InlinePickerStyle() } }
extension PickerStyle where Self == NavigationLinkPickerStyle { public static var navigationLink: NavigationLinkPickerStyle { NavigationLinkPickerStyle() } }
extension PickerStyle where Self == DefaultPickerStyle { public static var automatic: DefaultPickerStyle { DefaultPickerStyle() } }
