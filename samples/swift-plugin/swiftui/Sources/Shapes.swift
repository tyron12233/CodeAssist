// Shapes: the Shape protocol, Path, the built-in shapes, fill and stroke, and the node that draws them.

import Foundation

// MARK: - Path

public struct Path: Equatable, Sendable {
    enum Element: Equatable, Sendable {
        case move(CGPoint)
        case line(CGPoint)
        case quad(CGPoint, control: CGPoint)
        case curve(CGPoint, control1: CGPoint, control2: CGPoint)
        case close
        case rect(CGRect)
        case roundedRect(CGRect, CGSize, continuous: Bool)
        case ellipse(CGRect)
    }
    var elements: [Element] = []

    public init() {}
    public init(_ rect: CGRect) { elements = [.rect(rect)] }
    public init(roundedRect rect: CGRect, cornerRadius: CGFloat, style: RoundedCornerStyle = .continuous) {
        elements = [.roundedRect(rect, CGSize(width: cornerRadius, height: cornerRadius), continuous: style == .continuous)]
    }
    public init(roundedRect rect: CGRect, cornerSize: CGSize, style: RoundedCornerStyle = .continuous) {
        elements = [.roundedRect(rect, cornerSize, continuous: style == .continuous)]
    }
    public init(ellipseIn rect: CGRect) { elements = [.ellipse(rect)] }
    public init(_ callback: (inout Path) -> ()) { callback(&self) }

    public var isEmpty: Bool { elements.isEmpty }
    public var boundingRect: CGRect {
        var points: [CGPoint] = []
        for e in elements {
            switch e {
            case .move(let p), .line(let p): points.append(p)
            case .quad(let p, let c): points += [p, c]
            case .curve(let p, let c1, let c2): points += [p, c1, c2]
            case .rect(let r), .roundedRect(let r, _, _), .ellipse(let r): points += [r.origin, CGPoint(x: r.maxX, y: r.maxY)]
            case .close: break
            }
        }
        guard let first = points.first else { return .zero }
        var lo = first, hi = first
        for p in points { lo = CGPoint(x: min(lo.x, p.x), y: min(lo.y, p.y)); hi = CGPoint(x: max(hi.x, p.x), y: max(hi.y, p.y)) }
        return CGRect(x: lo.x, y: lo.y, width: hi.x - lo.x, height: hi.y - lo.y)
    }

    public mutating func move(to p: CGPoint) { elements.append(.move(p)) }
    public mutating func addLine(to p: CGPoint) { elements.append(.line(p)) }
    public mutating func addLines(_ points: [CGPoint]) {
        guard let first = points.first else { return }
        move(to: first)
        points.dropFirst().forEach { addLine(to: $0) }
    }
    public mutating func addQuadCurve(to p: CGPoint, control cp: CGPoint) { elements.append(.quad(p, control: cp)) }
    public mutating func addCurve(to p: CGPoint, control1 cp1: CGPoint, control2 cp2: CGPoint) {
        elements.append(.curve(p, control1: cp1, control2: cp2))
    }
    public mutating func closeSubpath() { elements.append(.close) }
    public mutating func addRect(_ rect: CGRect) { elements.append(.rect(rect)) }
    public mutating func addRects(_ rects: [CGRect]) { rects.forEach { addRect($0) } }
    public mutating func addRoundedRect(in rect: CGRect, cornerSize: CGSize, style: RoundedCornerStyle = .continuous) {
        elements.append(.roundedRect(rect, cornerSize, continuous: style == .continuous))
    }
    public mutating func addEllipse(in rect: CGRect) { elements.append(.ellipse(rect)) }
    public mutating func addPath(_ path: Path) { elements += path.elements }

    /// An arc as cubic curves (quarter turns at most each), from the current point's line to its start.
    public mutating func addArc(center: CGPoint, radius: CGFloat, startAngle: Angle, endAngle: Angle, clockwise: Bool) {
        var start = startAngle.radians
        var end = endAngle.radians
        // SwiftUI's y axis points down, so "clockwise" sweeps towards decreasing angles in its maths.
        if clockwise { while end > start { end -= 2 * .pi } } else { while end < start { end += 2 * .pi } }
        let first = CGPoint(x: center.x + radius * CGFloat(cos(start)), y: center.y + radius * CGFloat(sin(start)))
        if elements.isEmpty { move(to: first) } else { addLine(to: first) }
        let segments = max(1, Int((abs(end - start) / (.pi / 2)).rounded(.up)))
        let step = (end - start) / Double(segments)
        for _ in 0..<segments {
            let a0 = start, a1 = start + step
            let k = 4.0 / 3.0 * tan((a1 - a0) / 4)
            let p0 = CGPoint(x: cos(a0), y: sin(a0)), p1 = CGPoint(x: cos(a1), y: sin(a1))
            let c1 = CGPoint(x: center.x + radius * (p0.x - CGFloat(k) * p0.y), y: center.y + radius * (p0.y + CGFloat(k) * p0.x))
            let c2 = CGPoint(x: center.x + radius * (p1.x + CGFloat(k) * p1.y), y: center.y + radius * (p1.y - CGFloat(k) * p1.x))
            addCurve(to: CGPoint(x: center.x + radius * p1.x, y: center.y + radius * p1.y), control1: c1, control2: c2)
            start = a1
        }
    }

    public mutating func addArc(center: CGPoint, radius: CGFloat, startAngle: Angle, delta: Angle) {
        addArc(center: center, radius: radius, startAngle: startAngle, endAngle: startAngle + delta, clockwise: delta.radians < 0)
    }

    func offsetBy(_ dx: CGFloat, _ dy: CGFloat) -> Path {
        func o(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x + dx, y: p.y + dy) }
        var copy = Path()
        copy.elements = elements.map {
            switch $0 {
            case .move(let p): return .move(o(p))
            case .line(let p): return .line(o(p))
            case .quad(let p, let c): return .quad(o(p), control: o(c))
            case .curve(let p, let c1, let c2): return .curve(o(p), control1: o(c1), control2: o(c2))
            case .close: return .close
            case .rect(let r): return .rect(r.offsetBy(dx: dx, dy: dy))
            case .roundedRect(let r, let s, let c): return .roundedRect(r.offsetBy(dx: dx, dy: dy), s, continuous: c)
            case .ellipse(let r): return .ellipse(r.offsetBy(dx: dx, dy: dy))
            }
        }
        return copy
    }
}

extension Angle {
    public static func + (a: Angle, b: Angle) -> Angle { Angle(radians: a.radians + b.radians) }
    public static func - (a: Angle, b: Angle) -> Angle { Angle(radians: a.radians - b.radians) }
}

public enum RoundedCornerStyle: Hashable, Sendable { case circular, continuous }

// MARK: - Shape

public protocol Shape: View, Sendable {
    func path(in rect: CGRect) -> Path
    func sizeThatFits(_ proposal: ProposedViewSize) -> CGSize
}

extension Shape {
    public func sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { proposal.replacingUnspecifiedDimensions() }
    public var body: _ShapeView<Self, ForegroundStyle> { _ShapeView(shape: self, style: ForegroundStyle(), stroke: nil) }

    public func fill<S: ShapeStyle>(_ content: S, style: FillStyle = FillStyle()) -> _ShapeView<Self, S> {
        _ShapeView(shape: self, style: content, stroke: nil)
    }
    public func fill(style: FillStyle = FillStyle()) -> _ShapeView<Self, ForegroundStyle> {
        _ShapeView(shape: self, style: ForegroundStyle(), stroke: nil)
    }
    public func stroke<S: ShapeStyle>(_ content: S, style: StrokeStyle) -> _ShapeView<Self, S> {
        _ShapeView(shape: self, style: content, stroke: style)
    }
    public func stroke<S: ShapeStyle>(_ content: S, lineWidth: CGFloat = 1) -> _ShapeView<Self, S> {
        _ShapeView(shape: self, style: content, stroke: StrokeStyle(lineWidth: lineWidth))
    }
    public func stroke(style: StrokeStyle) -> _ShapeView<Self, ForegroundStyle> {
        _ShapeView(shape: self, style: ForegroundStyle(), stroke: style)
    }
    public func stroke(lineWidth: CGFloat = 1) -> _ShapeView<Self, ForegroundStyle> {
        _ShapeView(shape: self, style: ForegroundStyle(), stroke: StrokeStyle(lineWidth: lineWidth))
    }
    public func trim(from startFraction: CGFloat = 0, to endFraction: CGFloat = 1) -> some Shape { self }
    public func rotation(_ angle: Angle, anchor: UnitPoint = .center) -> some Shape { self }
    public func scale(_ scale: CGFloat, anchor: UnitPoint = .center) -> some Shape { self }
    public func offset(_ offset: CGSize) -> some Shape { _OffsetShape(base: self, offset: offset) }
    public func offset(x: CGFloat = 0, y: CGFloat = 0) -> some Shape { offset(CGSize(width: x, height: y)) }
}

public protocol InsettableShape: Shape {
    associatedtype InsetShape: InsettableShape
    func inset(by amount: CGFloat) -> InsetShape
}

extension InsettableShape {
    public func strokeBorder<S: ShapeStyle>(_ content: S, style: StrokeStyle, antialiased: Bool = true) -> some View {
        inset(by: style.lineWidth / 2).stroke(content, style: style)
    }
    public func strokeBorder<S: ShapeStyle>(_ content: S, lineWidth: CGFloat = 1, antialiased: Bool = true) -> some View {
        inset(by: lineWidth / 2).stroke(content, lineWidth: lineWidth)
    }
    public func strokeBorder(style: StrokeStyle, antialiased: Bool = true) -> some View {
        inset(by: style.lineWidth / 2).stroke(style: style)
    }
    public func strokeBorder(lineWidth: CGFloat = 1, antialiased: Bool = true) -> some View {
        inset(by: lineWidth / 2).stroke(lineWidth: lineWidth)
    }
}

public struct FillStyle: Equatable, Sendable {
    public var isEOFilled: Bool
    public var isAntialiased: Bool
    public init(eoFill: Bool = false, antialiased: Bool = true) { isEOFilled = eoFill; isAntialiased = antialiased }
}

public enum CGLineCapCompat: Sendable { case butt, round, square }

public struct StrokeStyle: Equatable, Sendable {
    public var lineWidth: CGFloat
    public var dash: [CGFloat]
    public var dashPhase: CGFloat
    public var miterLimit: CGFloat
    public init(lineWidth: CGFloat = 1, lineCap: CGLineCap = .butt, lineJoin: CGLineJoin = .miter, miterLimit: CGFloat = 10, dash: [CGFloat] = [], dashPhase: CGFloat = 0) {
        self.lineWidth = lineWidth
        self.dash = dash
        self.dashPhase = dashPhase
        self.miterLimit = miterLimit
    }
}

#if !canImport(CoreGraphics)
public enum CGLineCap: Int32, Sendable { case butt, round, square }
public enum CGLineJoin: Int32, Sendable { case miter, round, bevel }
#endif

/// A shape drawn with a style: what `fill`, `stroke` and a bare shape (filled with the foreground) are.
public struct _ShapeView<Content: Shape, Style: ShapeStyle>: View, _PrimitiveView {
    let shape: Content
    let style: Style
    let stroke: StrokeStyle?
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_ShapeNode(path: ctx.path, env: ctx.environment, shape: _AnyShape(shape), fill: AnyShapeStyle(style), stroke: stroke)]
    }
}

struct _AnyShape: Sendable {
    let path: @Sendable (CGRect) -> Path
    let size: @Sendable (ProposedViewSize) -> CGSize
    init<S: Shape>(_ shape: S) {
        path = { shape.path(in: $0) }
        size = { shape.sizeThatFits($0) }
    }
}

public struct AnyShape: Shape {
    let base: _AnyShape
    public init<S: Shape>(_ shape: S) { base = _AnyShape(shape) }
    public func path(in rect: CGRect) -> Path { base.path(rect) }
    public func sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { base.size(proposal) }
}

struct _OffsetShape<Base: Shape>: Shape {
    let base: Base
    let offset: CGSize
    func path(in rect: CGRect) -> Path { base.path(in: rect).offsetBy(offset.width, offset.height) }
}

@MainActor
final class _ShapeNode: _Node {
    let shape: _AnyShape
    let fill: AnyShapeStyle
    let stroke: StrokeStyle?

    init(path: String, env: EnvironmentValues, shape: _AnyShape, fill: AnyShapeStyle, stroke: StrokeStyle?) {
        self.shape = shape
        self.fill = fill
        self.stroke = stroke
        super.init(path: path, env: env)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize { shape.size(proposal) }

    override func emit(_ list: _DisplayList) {
        let path = shape.path(CGRect(origin: .zero, size: frame.size)).offsetBy(frame.minX, frame.minY)
        list.shape(path, style: fill, stroke: stroke, env: env)
    }
}

// MARK: - Built-in shapes

public struct Rectangle: InsettableShape {
    var insetAmount: CGFloat = 0
    public init() {}
    public func path(in rect: CGRect) -> Path { Path(rect.insetBy(dx: insetAmount, dy: insetAmount)) }
    public func inset(by amount: CGFloat) -> Rectangle { var c = self; c.insetAmount += amount; return c }
}

public struct RoundedRectangle: InsettableShape {
    public var cornerSize: CGSize
    public var style: RoundedCornerStyle
    var insetAmount: CGFloat = 0
    public init(cornerSize: CGSize, style: RoundedCornerStyle = .continuous) {
        self.cornerSize = cornerSize
        self.style = style
    }
    public init(cornerRadius: CGFloat, style: RoundedCornerStyle = .continuous) {
        self.init(cornerSize: CGSize(width: cornerRadius, height: cornerRadius), style: style)
    }
    public func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: insetAmount, dy: insetAmount)
        let size = CGSize(width: max(0, cornerSize.width - insetAmount), height: max(0, cornerSize.height - insetAmount))
        return Path(roundedRect: r, cornerSize: size, style: style)
    }
    public func inset(by amount: CGFloat) -> RoundedRectangle { var c = self; c.insetAmount += amount; return c }
}

public struct UnevenRoundedRectangle: Shape {
    public var cornerRadii: RectangleCornerRadii
    public var style: RoundedCornerStyle
    public init(cornerRadii: RectangleCornerRadii, style: RoundedCornerStyle = .continuous) {
        self.cornerRadii = cornerRadii
        self.style = style
    }
    public init(topLeadingRadius: CGFloat = 0, bottomLeadingRadius: CGFloat = 0, bottomTrailingRadius: CGFloat = 0,
                topTrailingRadius: CGFloat = 0, style: RoundedCornerStyle = .continuous) {
        self.init(cornerRadii: RectangleCornerRadii(topLeading: topLeadingRadius, bottomLeading: bottomLeadingRadius,
                                                    bottomTrailing: bottomTrailingRadius, topTrailing: topTrailingRadius), style: style)
    }
    public func path(in rect: CGRect) -> Path {
        let c = cornerRadii
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + c.topLeading, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - c.topTrailing, y: rect.minY))
        p.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + c.topTrailing), control: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - c.bottomTrailing))
        p.addQuadCurve(to: CGPoint(x: rect.maxX - c.bottomTrailing, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + c.bottomLeading, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - c.bottomLeading), control: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + c.topLeading))
        p.addQuadCurve(to: CGPoint(x: rect.minX + c.topLeading, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        p.closeSubpath()
        return p
    }
}

public struct RectangleCornerRadii: Equatable, Sendable {
    public var topLeading: CGFloat
    public var bottomLeading: CGFloat
    public var bottomTrailing: CGFloat
    public var topTrailing: CGFloat
    public init(topLeading: CGFloat = 0, bottomLeading: CGFloat = 0, bottomTrailing: CGFloat = 0, topTrailing: CGFloat = 0) {
        self.topLeading = topLeading
        self.bottomLeading = bottomLeading
        self.bottomTrailing = bottomTrailing
        self.topTrailing = topTrailing
    }
}

public struct Capsule: InsettableShape {
    public var style: RoundedCornerStyle
    var insetAmount: CGFloat = 0
    public init(style: RoundedCornerStyle = .continuous) { self.style = style }
    public func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: insetAmount, dy: insetAmount)
        let radius = min(r.width, r.height) / 2
        return Path(roundedRect: r, cornerRadius: radius, style: .circular)
    }
    public func inset(by amount: CGFloat) -> Capsule { var c = self; c.insetAmount += amount; return c }
}

public struct Circle: InsettableShape {
    var insetAmount: CGFloat = 0
    public init() {}
    public func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height) - 2 * insetAmount
        return Path(ellipseIn: CGRect(x: rect.midX - side / 2, y: rect.midY - side / 2, width: side, height: side))
    }
    public func inset(by amount: CGFloat) -> Circle { var c = self; c.insetAmount += amount; return c }
}

public struct Ellipse: InsettableShape {
    var insetAmount: CGFloat = 0
    public init() {}
    public func path(in rect: CGRect) -> Path { Path(ellipseIn: rect.insetBy(dx: insetAmount, dy: insetAmount)) }
    public func inset(by amount: CGFloat) -> Ellipse { var c = self; c.insetAmount += amount; return c }
}

public struct ContainerRelativeShape: InsettableShape {
    var insetAmount: CGFloat = 0
    public init() {}
    public func path(in rect: CGRect) -> Path { Path(rect.insetBy(dx: insetAmount, dy: insetAmount)) }
    public func inset(by amount: CGFloat) -> ContainerRelativeShape { var c = self; c.insetAmount += amount; return c }
}

extension Path: Shape {
    public func path(in rect: CGRect) -> Path { self }
    // A Path draws where its points are: its frame is whatever it is proposed.
}

extension Shape where Self == Rectangle { public static var rect: Rectangle { Rectangle() } }
extension Shape where Self == Circle { public static var circle: Circle { Circle() } }
extension Shape where Self == Ellipse { public static var ellipse: Ellipse { Ellipse() } }
extension Shape where Self == Capsule {
    public static var capsule: Capsule { Capsule() }
    public static func capsule(style: RoundedCornerStyle) -> Capsule { Capsule(style: style) }
}
extension Shape where Self == RoundedRectangle {
    public static func rect(cornerRadius: CGFloat, style: RoundedCornerStyle = .continuous) -> RoundedRectangle {
        RoundedRectangle(cornerRadius: cornerRadius, style: style)
    }
}
