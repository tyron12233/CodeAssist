// Colours, shape styles and fonts, with iOS's own values: the system colours in light and dark, the label hierarchy,
// and Dynamic Type's text styles at the default (Large) size.

import Foundation

// MARK: - Colour

/// An sRGB colour with alpha, components 0...1.
struct _RGBA: Equatable, Hashable {
    var r: Double, g: Double, b: Double, a: Double

    init(_ r: Double, _ g: Double, _ b: Double, _ a: Double = 1) {
        self.r = r; self.g = g; self.b = b; self.a = a
    }
    init(hex: UInt32, alpha: Double = 1) {
        self.init(Double((hex >> 16) & 0xFF) / 255, Double((hex >> 8) & 0xFF) / 255, Double(hex & 0xFF) / 255, alpha)
    }
    func with(alpha: Double) -> _RGBA { _RGBA(r, g, b, a * alpha) }

    var hex: String {
        func c(_ v: Double) -> Int { max(0, min(255, Int((v * 255).rounded()))) }
        return String(format: "#%02X%02X%02X%02X", c(r), c(g), c(b), c(a))
    }
}

public struct Color: Hashable, CustomStringConvertible, Sendable {
    /// Resolves against the colour scheme in effect where it is drawn.
    let light: _RGBA
    let dark: _RGBA
    let name: String?
    /// Dark mode's colour in an elevated context (sheets, popovers), where iOS lifts backgrounds off black.
    var elevated: _RGBA?

    init(light: _RGBA, dark: _RGBA, name: String? = nil) {
        self.light = light
        self.dark = dark
        self.name = name
    }
    init(light: _RGBA, dark: _RGBA, elevated: _RGBA) {
        self.init(light: light, dark: dark)
        self.elevated = elevated
    }
    init(_ both: _RGBA) { self.init(light: both, dark: both) }

    func resolve(_ scheme: ColorScheme) -> _RGBA { scheme == .dark ? dark : light }
    func resolve(_ env: EnvironmentValues) -> _RGBA { env.colorScheme == .dark ? (env._elevated ? elevated ?? dark : dark) : light }

    public enum RGBColorSpace: Hashable, Sendable { case sRGB, sRGBLinear, displayP3 }

    public init(_ colorSpace: RGBColorSpace = .sRGB, red: Double, green: Double, blue: Double, opacity: Double = 1) {
        self.init(_RGBA(red, green, blue, opacity))
    }
    public init(_ colorSpace: RGBColorSpace = .sRGB, white: Double, opacity: Double = 1) {
        self.init(_RGBA(white, white, white, opacity))
    }
    public init(hue: Double, saturation: Double, brightness: Double, opacity: Double = 1) {
        let h = (hue.truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1) * 6
        let c = brightness * saturation
        let x = c * (1 - abs(h.truncatingRemainder(dividingBy: 2) - 1))
        let m = brightness - c
        let (r, g, b): (Double, Double, Double)
        switch Int(h) {
        case 0: (r, g, b) = (c, x, 0)
        case 1: (r, g, b) = (x, c, 0)
        case 2: (r, g, b) = (0, c, x)
        case 3: (r, g, b) = (0, x, c)
        case 4: (r, g, b) = (x, 0, c)
        default: (r, g, b) = (c, 0, x)
        }
        self.init(_RGBA(r + m, g + m, b + m, opacity))
    }
    /// A colour from the app's asset catalog. Previews cannot read the catalog yet: it draws as the accent colour.
    public init(_ name: String, bundle: Bundle? = nil) {
        self.init(light: Color.blue.light, dark: Color.blue.dark, name: name)
    }

    public func opacity(_ opacity: Double) -> Color {
        Color(light: light.with(alpha: opacity), dark: dark.with(alpha: opacity), name: name)
    }

    public var description: String { name ?? light.hex }

    // iOS system colours (light, dark).
    public static let red = Color(light: _RGBA(hex: 0xFF383C), dark: _RGBA(hex: 0xFF4245), name: "red")
    public static let orange = Color(light: _RGBA(hex: 0xFF8D28), dark: _RGBA(hex: 0xFF9230), name: "orange")
    public static let yellow = Color(light: _RGBA(hex: 0xFFCC00), dark: _RGBA(hex: 0xFFD600), name: "yellow")
    public static let green = Color(light: _RGBA(hex: 0x34C759), dark: _RGBA(hex: 0x30D158), name: "green")
    public static let mint = Color(light: _RGBA(hex: 0x00C8B3), dark: _RGBA(hex: 0x00DAC3), name: "mint")
    public static let teal = Color(light: _RGBA(hex: 0x00C3D0), dark: _RGBA(hex: 0x00D2E0), name: "teal")
    public static let cyan = Color(light: _RGBA(hex: 0x00C0E8), dark: _RGBA(hex: 0x3CD3FE), name: "cyan")
    public static let blue = Color(light: _RGBA(hex: 0x0088FF), dark: _RGBA(hex: 0x0091FF), name: "blue")
    public static let indigo = Color(light: _RGBA(hex: 0x6155F5), dark: _RGBA(hex: 0x6D7CFF), name: "indigo")
    public static let purple = Color(light: _RGBA(hex: 0xCB30E0), dark: _RGBA(hex: 0xDB34F2), name: "purple")
    public static let pink = Color(light: _RGBA(hex: 0xFF2D55), dark: _RGBA(hex: 0xFF375F), name: "pink")
    public static let brown = Color(light: _RGBA(hex: 0xAC7F5E), dark: _RGBA(hex: 0xB78A66), name: "brown")
    public static let gray = Color(light: _RGBA(hex: 0x8E8E93), dark: _RGBA(hex: 0x8E8E93), name: "gray")
    public static let black = Color(light: _RGBA(0, 0, 0), dark: _RGBA(0, 0, 0), name: "black")
    public static let white = Color(light: _RGBA(1, 1, 1), dark: _RGBA(1, 1, 1), name: "white")
    public static let clear = Color(light: _RGBA(0, 0, 0, 0), dark: _RGBA(0, 0, 0, 0), name: "clear")
    public static let primary = Color(light: _RGBA(0, 0, 0), dark: _RGBA(1, 1, 1), name: "primary")
    public static let secondary = Color(light: _RGBA(hex: 0x3C3C43, alpha: 0.6), dark: _RGBA(hex: 0xEBEBF5, alpha: 0.6), name: "secondary")
    public static let accentColor = Color.blue

    // UIKit's semantic colours, which SwiftUI code reaches through Color(uiColor:) or Color(.systemBackground).
    static let systemBackground = Color(light: _RGBA(1, 1, 1), dark: _RGBA(0, 0, 0), elevated: _RGBA(hex: 0x1C1C1E))
    static let secondarySystemBackground = Color(light: _RGBA(hex: 0xF2F2F7), dark: _RGBA(hex: 0x1C1C1E), elevated: _RGBA(hex: 0x2C2C2E))
    static let tertiarySystemBackground = Color(light: _RGBA(1, 1, 1), dark: _RGBA(hex: 0x2C2C2E))
    static let systemGroupedBackground = Color(light: _RGBA(hex: 0xF2F2F7), dark: _RGBA(0, 0, 0), elevated: _RGBA(hex: 0x1C1C1E))
    static let secondarySystemGroupedBackground = Color(light: _RGBA(1, 1, 1), dark: _RGBA(hex: 0x1C1C1E), elevated: _RGBA(hex: 0x2C2C2E))
    static let tertiaryLabel = Color(light: _RGBA(hex: 0x3C3C43, alpha: 0.3), dark: _RGBA(hex: 0xEBEBF5, alpha: 0.3))
    static let quaternaryLabel = Color(light: _RGBA(hex: 0x3C3C43, alpha: 0.18), dark: _RGBA(hex: 0xEBEBF5, alpha: 0.16))
    static let separator = Color(light: _RGBA(hex: 0x3C3C43, alpha: 0.29), dark: _RGBA(hex: 0x545458, alpha: 0.6))
    static let systemFill = Color(light: _RGBA(hex: 0x787880, alpha: 0.2), dark: _RGBA(hex: 0x787880, alpha: 0.36))
    static let secondarySystemFill = Color(light: _RGBA(hex: 0x787880, alpha: 0.16), dark: _RGBA(hex: 0x787880, alpha: 0.32))
    static let tertiarySystemFill = Color(light: _RGBA(hex: 0x767680, alpha: 0.12), dark: _RGBA(hex: 0x767680, alpha: 0.24))
    static let quaternarySystemFill = Color(light: _RGBA(hex: 0x747480, alpha: 0.08), dark: _RGBA(hex: 0x767680, alpha: 0.18))
    static let switchOff = Color(light: _RGBA(hex: 0x787880, alpha: 0.16), dark: _RGBA(hex: 0x787880, alpha: 0.32))
}

extension Color: View, _PrimitiveView {
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_ShapeNode(path: ctx.path, env: ctx.environment, shape: _AnyShape(Rectangle()), fill: AnyShapeStyle(self), stroke: nil)]
    }
}

// MARK: - Shape styles

public protocol ShapeStyle: Sendable {}

extension ShapeStyle {
    func _color(_ env: EnvironmentValues) -> Color? { nil }
}

/// A style the engine can paint: a colour, or a hierarchical level resolved against the foreground.
protocol _ResolvableStyle {
    func _resolvedColor(_ env: EnvironmentValues) -> Color
    func _gradient(_ env: EnvironmentValues) -> _Gradient?
}

extension _ResolvableStyle {
    func _gradient(_ env: EnvironmentValues) -> _Gradient? { nil }
}

extension Color: ShapeStyle, _ResolvableStyle {
    func _resolvedColor(_ env: EnvironmentValues) -> Color { self }
}

public struct AnyShapeStyle: ShapeStyle {
    let base: any ShapeStyle
    public init<S: ShapeStyle>(_ style: S) {
        if let any = style as? AnyShapeStyle { base = any.base } else { base = style }
    }

    func _resolvedColor(_ env: EnvironmentValues) -> Color {
        (base as? _ResolvableStyle)?._resolvedColor(env) ?? .primary
    }
    func _gradient(_ env: EnvironmentValues) -> _Gradient? {
        (base as? _ResolvableStyle)?._gradient(env)
    }
}

public struct HierarchicalShapeStyle: ShapeStyle, _ResolvableStyle {
    let level: Int
    public static let primary = HierarchicalShapeStyle(level: 0)
    public static let secondary = HierarchicalShapeStyle(level: 1)
    public static let tertiary = HierarchicalShapeStyle(level: 2)
    public static let quaternary = HierarchicalShapeStyle(level: 3)
    public static let quinary = HierarchicalShapeStyle(level: 4)

    func _resolvedColor(_ env: EnvironmentValues) -> Color {
        // Levels below primary are the foreground at the label hierarchy's opacities.
        let base = env._foreground.map { $0._resolvedColor(env) }
        switch level {
        case 0: return base ?? .primary
        case 1: return base.map { $0.opacity(0.6) } ?? .secondary
        case 2: return base.map { $0.opacity(0.3) } ?? .tertiaryLabel
        case 3: return base.map { $0.opacity(0.18) } ?? .quaternaryLabel
        default: return base.map { $0.opacity(0.1) } ?? Color.quaternaryLabel.opacity(0.5)
        }
    }
}

public struct ForegroundStyle: ShapeStyle, _ResolvableStyle {
    public init() {}
    func _resolvedColor(_ env: EnvironmentValues) -> Color { env._foreground?._resolvedColor(env) ?? .primary }
}

public struct TintShapeStyle: ShapeStyle, _ResolvableStyle {
    public init() {}
    func _resolvedColor(_ env: EnvironmentValues) -> Color { env._tint ?? .accentColor }
}

public struct BackgroundStyle: ShapeStyle, _ResolvableStyle {
    public init() {}
    func _resolvedColor(_ env: EnvironmentValues) -> Color { .systemBackground }
}

public struct SeparatorShapeStyle: ShapeStyle, _ResolvableStyle {
    public init() {}
    func _resolvedColor(_ env: EnvironmentValues) -> Color { .separator }
}

public struct FillShapeStyle: ShapeStyle, _ResolvableStyle {
    let level: Int
    func _resolvedColor(_ env: EnvironmentValues) -> Color {
        [Color.systemFill, .secondarySystemFill, .tertiarySystemFill, .quaternarySystemFill][min(level, 3)]
    }
}

extension ShapeStyle where Self == HierarchicalShapeStyle {
    public static var primary: HierarchicalShapeStyle { .primary }
    public static var secondary: HierarchicalShapeStyle { .secondary }
    public static var tertiary: HierarchicalShapeStyle { .tertiary }
    public static var quaternary: HierarchicalShapeStyle { .quaternary }
    public static var quinary: HierarchicalShapeStyle { .quinary }
}

extension ShapeStyle where Self == ForegroundStyle { public static var foreground: ForegroundStyle { ForegroundStyle() } }
extension ShapeStyle where Self == TintShapeStyle { public static var tint: TintShapeStyle { TintShapeStyle() } }
extension ShapeStyle where Self == BackgroundStyle { public static var background: BackgroundStyle { BackgroundStyle() } }
extension ShapeStyle where Self == SeparatorShapeStyle { public static var separator: SeparatorShapeStyle { SeparatorShapeStyle() } }
extension ShapeStyle where Self == FillShapeStyle { public static var fill: FillShapeStyle { FillShapeStyle(level: 0) } }

extension ShapeStyle where Self == Color {
    public static var red: Color { .red }
    public static var orange: Color { .orange }
    public static var yellow: Color { .yellow }
    public static var green: Color { .green }
    public static var mint: Color { .mint }
    public static var teal: Color { .teal }
    public static var cyan: Color { .cyan }
    public static var blue: Color { .blue }
    public static var indigo: Color { .indigo }
    public static var purple: Color { .purple }
    public static var pink: Color { .pink }
    public static var brown: Color { .brown }
    public static var white: Color { .white }
    public static var gray: Color { .gray }
    public static var black: Color { .black }
    public static var clear: Color { .clear }
}

// MARK: - Gradients

struct _Gradient {
    enum Kind { case linear(start: UnitPoint, end: UnitPoint), radial(center: UnitPoint, start: CGFloat, end: CGFloat), angular(center: UnitPoint) }
    let kind: Kind
    let stops: [(color: _RGBA, location: Double)]
}

public struct UnitPoint: Hashable, Sendable {
    public var x: CGFloat
    public var y: CGFloat
    public init() { x = 0; y = 0 }
    public init(x: CGFloat, y: CGFloat) { self.x = x; self.y = y }
    public static let zero = UnitPoint(x: 0, y: 0)
    public static let center = UnitPoint(x: 0.5, y: 0.5)
    public static let leading = UnitPoint(x: 0, y: 0.5)
    public static let trailing = UnitPoint(x: 1, y: 0.5)
    public static let top = UnitPoint(x: 0.5, y: 0)
    public static let bottom = UnitPoint(x: 0.5, y: 1)
    public static let topLeading = UnitPoint(x: 0, y: 0)
    public static let topTrailing = UnitPoint(x: 1, y: 0)
    public static let bottomLeading = UnitPoint(x: 0, y: 1)
    public static let bottomTrailing = UnitPoint(x: 1, y: 1)
}

public struct Gradient: Hashable, Sendable {
    public struct Stop: Hashable, Sendable {
        public var color: Color
        public var location: CGFloat
        public init(color: Color, location: CGFloat) { self.color = color; self.location = location }
    }
    public var stops: [Stop]
    public init(stops: [Stop]) { self.stops = stops }
    public init(colors: [Color]) {
        stops = colors.enumerated().map { i, c in
            Stop(color: c, location: colors.count > 1 ? CGFloat(i) / CGFloat(colors.count - 1) : 0)
        }
    }
    /// The stops a painter interpolates between in sRGB. iOS 26 blends a gradient's colours perceptually (in Oklab:
    /// black to white is 0.388 grey halfway, measured), so each segment is expanded into 16 steps computed in Oklab.
    func resolved(_ env: EnvironmentValues) -> [(color: _RGBA, location: Double)] {
        let base = stops.map { ($0.color.resolve(env), Double($0.location)) }.sorted { $0.1 < $1.1 }
        guard base.count > 1 else { return base }
        var out: [(color: _RGBA, location: Double)] = []
        for i in 0..<(base.count - 1) {
            let (a, la) = base[i], (b, lb) = base[i + 1]
            let steps = 16
            for k in 0..<steps {
                let t = Double(k) / Double(steps)
                out.append((_Oklab.mix(a, b, t), la + (lb - la) * t))
            }
        }
        out.append(base[base.count - 1])
        return out
    }
}

/// Colour mixing in Oklab (Björn Ottosson's perceptual space), as iOS 26 blends gradients.
enum _Oklab {
    static func toLinear(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
    static func toSRGB(_ c: Double) -> Double { c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1 / 2.4) - 0.055 }

    static func lab(_ c: _RGBA) -> (Double, Double, Double) {
        let r = toLinear(c.r), g = toLinear(c.g), b = toLinear(c.b)
        let l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        let m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        let s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    static func rgb(_ L: Double, _ A: Double, _ B: Double, alpha: Double) -> _RGBA {
        let l = pow(L + 0.3963377774 * A + 0.2158037573 * B, 3)
        let m = pow(L - 0.1055613458 * A - 0.0638541728 * B, 3)
        let s = pow(L - 0.0894841775 * A - 1.2914855480 * B, 3)
        func c(_ v: Double) -> Double { min(1, max(0, toSRGB(v))) }
        return _RGBA(c(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                     c(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                     c(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s), alpha)
    }

    static func mix(_ a: _RGBA, _ b: _RGBA, _ t: Double) -> _RGBA {
        let x = lab(a), y = lab(b)
        return rgb(x.0 + (y.0 - x.0) * t, x.1 + (y.1 - x.1) * t, x.2 + (y.2 - x.2) * t, alpha: a.a + (b.a - a.a) * t)
    }
}

public struct LinearGradient: ShapeStyle, View, _PrimitiveView, _ResolvableStyle {
    let gradient: Gradient
    let startPoint: UnitPoint
    let endPoint: UnitPoint
    public init(gradient: Gradient, startPoint: UnitPoint, endPoint: UnitPoint) {
        self.gradient = gradient; self.startPoint = startPoint; self.endPoint = endPoint
    }
    public init(colors: [Color], startPoint: UnitPoint, endPoint: UnitPoint) {
        self.init(gradient: Gradient(colors: colors), startPoint: startPoint, endPoint: endPoint)
    }
    public init(stops: [Gradient.Stop], startPoint: UnitPoint, endPoint: UnitPoint) {
        self.init(gradient: Gradient(stops: stops), startPoint: startPoint, endPoint: endPoint)
    }
    public var body: Never { _neverBody() }
    func _resolvedColor(_ env: EnvironmentValues) -> Color { gradient.stops.first?.color ?? .clear }
    func _gradient(_ env: EnvironmentValues) -> _Gradient? {
        _Gradient(kind: .linear(start: startPoint, end: endPoint), stops: gradient.resolved(env))
    }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_ShapeNode(path: ctx.path, env: ctx.environment, shape: _AnyShape(Rectangle()), fill: AnyShapeStyle(self), stroke: nil)]
    }
}

public struct RadialGradient: ShapeStyle, View, _PrimitiveView, _ResolvableStyle {
    let gradient: Gradient
    let center: UnitPoint
    let startRadius: CGFloat
    let endRadius: CGFloat
    public init(gradient: Gradient, center: UnitPoint, startRadius: CGFloat, endRadius: CGFloat) {
        self.gradient = gradient; self.center = center; self.startRadius = startRadius; self.endRadius = endRadius
    }
    public init(colors: [Color], center: UnitPoint, startRadius: CGFloat, endRadius: CGFloat) {
        self.init(gradient: Gradient(colors: colors), center: center, startRadius: startRadius, endRadius: endRadius)
    }
    public var body: Never { _neverBody() }
    func _resolvedColor(_ env: EnvironmentValues) -> Color { gradient.stops.first?.color ?? .clear }
    func _gradient(_ env: EnvironmentValues) -> _Gradient? {
        _Gradient(kind: .radial(center: center, start: startRadius, end: endRadius), stops: gradient.resolved(env))
    }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_ShapeNode(path: ctx.path, env: ctx.environment, shape: _AnyShape(Rectangle()), fill: AnyShapeStyle(self), stroke: nil)]
    }
}

public struct AngularGradient: ShapeStyle, View, _PrimitiveView, _ResolvableStyle {
    let gradient: Gradient
    let center: UnitPoint
    public init(gradient: Gradient, center: UnitPoint, startAngle: Angle = .zero, endAngle: Angle = .zero) {
        self.gradient = gradient; self.center = center
    }
    public init(colors: [Color], center: UnitPoint, startAngle: Angle = .zero, endAngle: Angle = .zero) {
        self.init(gradient: Gradient(colors: colors), center: center)
    }
    public var body: Never { _neverBody() }
    func _resolvedColor(_ env: EnvironmentValues) -> Color { gradient.stops.first?.color ?? .clear }
    func _gradient(_ env: EnvironmentValues) -> _Gradient? {
        _Gradient(kind: .angular(center: center), stops: gradient.resolved(env))
    }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_ShapeNode(path: ctx.path, env: ctx.environment, shape: _AnyShape(Rectangle()), fill: AnyShapeStyle(self), stroke: nil)]
    }
}

public struct Angle: Hashable, Comparable, Sendable {
    public var radians: Double
    public var degrees: Double { get { radians * 180 / .pi } set { radians = newValue * .pi / 180 } }
    public init() { radians = 0 }
    public init(radians: Double) { self.radians = radians }
    public init(degrees: Double) { radians = degrees * .pi / 180 }
    public static func radians(_ r: Double) -> Angle { Angle(radians: r) }
    public static func degrees(_ d: Double) -> Angle { Angle(degrees: d) }
    public static let zero = Angle()
    public static func < (a: Angle, b: Angle) -> Bool { a.radians < b.radians }
}

// MARK: - Fonts

public struct Font: Hashable, Sendable {
    public enum TextStyle: CaseIterable, Hashable, Sendable {
        case extraLargeTitle2, extraLargeTitle, largeTitle, title, title2, title3, headline, subheadline, body, callout, footnote, caption, caption2
    }

    public struct Weight: Hashable, Sendable {
        let value: Int
        public static let ultraLight = Weight(value: 100)
        public static let thin = Weight(value: 200)
        public static let light = Weight(value: 300)
        public static let regular = Weight(value: 400)
        public static let medium = Weight(value: 500)
        public static let semibold = Weight(value: 600)
        public static let bold = Weight(value: 700)
        public static let heavy = Weight(value: 800)
        public static let black = Weight(value: 900)
    }

    public enum Design: Hashable, Sendable { case `default`, serif, rounded, monospaced }
    public enum Leading: Hashable, Sendable { case standard, tight, loose }

    var size: CGFloat
    var weight: Weight
    var design: Design
    var isItalic = false
    var hasMonospacedDigits = false
    var textStyle: TextStyle?
    var customName: String?

    init(size: CGFloat, weight: Weight, design: Design = .default, textStyle: TextStyle? = nil) {
        self.size = size
        self.weight = weight
        self.design = design
        self.textStyle = textStyle
    }

    /// Dynamic Type at its default (Large) size: point size and weight of each text style on iOS.
    static func style(_ style: TextStyle, design: Design = .default) -> Font {
        switch style {
        case .extraLargeTitle2: return Font(size: 28, weight: .bold, design: design, textStyle: style)
        case .extraLargeTitle: return Font(size: 36, weight: .bold, design: design, textStyle: style)
        case .largeTitle: return Font(size: 34, weight: .regular, design: design, textStyle: style)
        case .title: return Font(size: 28, weight: .regular, design: design, textStyle: style)
        case .title2: return Font(size: 22, weight: .regular, design: design, textStyle: style)
        case .title3: return Font(size: 20, weight: .regular, design: design, textStyle: style)
        case .headline: return Font(size: 17, weight: .semibold, design: design, textStyle: style)
        case .subheadline: return Font(size: 15, weight: .regular, design: design, textStyle: style)
        case .body: return Font(size: 17, weight: .regular, design: design, textStyle: style)
        case .callout: return Font(size: 16, weight: .regular, design: design, textStyle: style)
        case .footnote: return Font(size: 13, weight: .regular, design: design, textStyle: style)
        case .caption: return Font(size: 12, weight: .regular, design: design, textStyle: style)
        case .caption2: return Font(size: 11, weight: .regular, design: design, textStyle: style)
        }
    }

    /// The line height of a Dynamic Type style at the Large size: the advance from one line to the next in a
    /// multi-line Text (the first line keeps the font's natural height). Calibrated against iOS.
    var lineAdvance: CGFloat? {
        switch textStyle {
        case .largeTitle?: return 41
        case .title?: return 34
        case .title2?: return 28
        case .title3?: return 25
        case .headline?, .body?: return 22
        case .callout?: return 21
        case .subheadline?: return 20
        case .footnote?: return 18
        case .caption?: return 16
        case .caption2?: return 13
        default: return nil
        }
    }

    public static let largeTitle = style(.largeTitle)
    public static let title = style(.title)
    public static let title2 = style(.title2)
    public static let title3 = style(.title3)
    public static let headline = style(.headline)
    public static let subheadline = style(.subheadline)
    public static let body = style(.body)
    public static let callout = style(.callout)
    public static let footnote = style(.footnote)
    public static let caption = style(.caption)
    public static let caption2 = style(.caption2)

    public static func system(size: CGFloat, weight: Weight? = nil, design: Design? = nil) -> Font {
        Font(size: size, weight: weight ?? .regular, design: design ?? .default)
    }
    public static func system(_ style: TextStyle, design: Design? = nil, weight: Weight? = nil) -> Font {
        var font = Font.style(style, design: design ?? .default)
        if let weight { font.weight = weight }
        return font
    }
    public static func custom(_ name: String, size: CGFloat) -> Font {
        var font = Font(size: size, weight: .regular)
        font.customName = name
        return font
    }
    public static func custom(_ name: String, size: CGFloat, relativeTo textStyle: TextStyle) -> Font { custom(name, size: size) }
    public static func custom(_ name: String, fixedSize: CGFloat) -> Font { custom(name, size: fixedSize) }

    public func weight(_ weight: Weight) -> Font { var f = self; f.weight = weight; return f }
    public func bold() -> Font { weight(.bold) }
    public func italic() -> Font { var f = self; f.isItalic = true; return f }
    public func monospaced() -> Font { var f = self; f.design = .monospaced; return f }
    public func monospacedDigit() -> Font { var f = self; f.hasMonospacedDigits = true; return f }
    public func smallCaps() -> Font { self }
    public func lowercaseSmallCaps() -> Font { self }
    public func uppercaseSmallCaps() -> Font { self }
    public func leading(_ leading: Leading) -> Font { self }
    public func width(_ width: Width) -> Font { self }

    public struct Width: Hashable, Sendable {
        let value: CGFloat
        public init(_ value: CGFloat) { self.value = value }
        public static let compressed = Width(-0.3)
        public static let condensed = Width(-0.2)
        public static let standard = Width(0)
        public static let expanded = Width(0.2)
    }
}
