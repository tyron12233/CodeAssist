// The display list: what a render produces for the host to paint, in preview coordinates (points, origin at the top
// left of the device screen). One JSON object per operation; groups (clip, opacity, shadow) are pushed and popped.

import Foundation

final class _DisplayList {
    private(set) var ops: [String] = []
    private var depth = 0

    // MARK: Writing

    static func num(_ v: CGFloat) -> String {
        let r = (Double(v) * 1000).rounded() / 1000
        return r == r.rounded() ? String(Int(r)) : String(r)
    }

    static func string(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 { out += String(format: "\\u%04x", scalar.value) } else { out.unicodeScalars.append(scalar) }
            }
        }
        return out + "\""
    }

    static func rect(_ r: CGRect) -> String { "[\(num(r.minX)),\(num(r.minY)),\(num(r.width)),\(num(r.height))]" }

    static func path(_ p: Path) -> String {
        let parts: [String] = p.elements.map { e in
            switch e {
            case .move(let pt): return "[\"M\",\(num(pt.x)),\(num(pt.y))]"
            case .line(let pt): return "[\"L\",\(num(pt.x)),\(num(pt.y))]"
            case .quad(let pt, let c): return "[\"Q\",\(num(pt.x)),\(num(pt.y)),\(num(c.x)),\(num(c.y))]"
            case .curve(let pt, let c1, let c2):
                return "[\"C\",\(num(pt.x)),\(num(pt.y)),\(num(c1.x)),\(num(c1.y)),\(num(c2.x)),\(num(c2.y))]"
            case .close: return "[\"Z\"]"
            case .rect(let r): return "[\"R\",\(num(r.minX)),\(num(r.minY)),\(num(r.width)),\(num(r.height))]"
            case .roundedRect(let r, let s, let continuous):
                return "[\"RR\",\(num(r.minX)),\(num(r.minY)),\(num(r.width)),\(num(r.height)),\(num(s.width)),\(num(s.height)),\(continuous ? 1 : 0)]"
            case .ellipse(let r): return "[\"E\",\(num(r.minX)),\(num(r.minY)),\(num(r.width)),\(num(r.height))]"
            }
        }
        return "[" + parts.joined(separator: ",") + "]"
    }

    static func gradient(_ g: _Gradient, in bounds: CGRect) -> String {
        let stops = g.stops.map { "[\(string($0.color.hex)),\(num(CGFloat($0.location)))]" }.joined(separator: ",")
        switch g.kind {
        case .linear(let s, let e):
            let p0 = CGPoint(x: bounds.minX + s.x * bounds.width, y: bounds.minY + s.y * bounds.height)
            let p1 = CGPoint(x: bounds.minX + e.x * bounds.width, y: bounds.minY + e.y * bounds.height)
            return "{\"k\":\"linear\",\"p\":[\(num(p0.x)),\(num(p0.y)),\(num(p1.x)),\(num(p1.y))],\"s\":[\(stops)]}"
        case .radial(let c, let r0, let r1):
            let p = CGPoint(x: bounds.minX + c.x * bounds.width, y: bounds.minY + c.y * bounds.height)
            return "{\"k\":\"radial\",\"p\":[\(num(p.x)),\(num(p.y)),\(num(r0)),\(num(r1))],\"s\":[\(stops)]}"
        case .angular(let c):
            let p = CGPoint(x: bounds.minX + c.x * bounds.width, y: bounds.minY + c.y * bounds.height)
            return "{\"k\":\"angular\",\"p\":[\(num(p.x)),\(num(p.y))],\"s\":[\(stops)]}"
        }
    }

    // MARK: Operations

    func shape(_ path: Path, style: AnyShapeStyle, stroke: StrokeStyle?, env: EnvironmentValues) {
        let paint: String
        if let g = style._gradient(env) {
            paint = "\"g\":" + Self.gradient(g, in: path.boundingRect)
        } else {
            let color = style._resolvedColor(env).resolve(env.colorScheme)
            if color.a <= 0 { return }
            paint = "\"c\":" + Self.string(color.hex)
        }
        var op = "{\"o\":\"path\",\"d\":\(Self.path(path)),\(paint)"
        if let stroke {
            op += ",\"sw\":\(Self.num(stroke.lineWidth))"
            if !stroke.dash.isEmpty { op += ",\"dash\":[\(stroke.dash.map { Self.num($0) }.joined(separator: ","))]" }
        }
        ops.append(op + "}")
    }

    /// SwiftUI draws a view at its frame's origin rounded to the pixel grid (@3x).
    static func snapped(_ r: CGRect) -> CGRect {
        CGRect(x: (r.minX * _scale).rounded() / _scale, y: (r.minY * _scale).rounded() / _scale, width: r.width, height: r.height)
    }

    func text(_ frame: CGRect, spans: [_ResolvedSpan], alignment: TextAlignment, maxLines: Int?, lineAdvance: CGFloat?) {
        let frame = Self.snapped(frame)
        let encoded = spans.map(Self.span).joined(separator: ",")
        var op = "{\"o\":\"text\",\"r\":\(Self.rect(frame)),\"sp\":[\(encoded)],\"a\":\(Self.string("\(alignment)"))"
        if let maxLines { op += ",\"n\":\(maxLines)" }
        if let lineAdvance { op += ",\"lh\":\(Self.num(lineAdvance))" }
        ops.append(op + "}")
    }

    static func span(_ s: _ResolvedSpan) -> String {
        var out = "{\"t\":\(string(s.text)),\"s\":\(num(s.size)),\"w\":\(s.weight),\"d\":\(string("\(s.design)")),\"c\":\(string(s.color.hex))"
        if s.italic { out += ",\"i\":1" }
        if let sym = s.symbol { out += ",\"sym\":\(string(sym))" }
        if let u = s.underline { out += ",\"u\":\(string(u.hex))" }
        if let st = s.strikethrough { out += ",\"st\":\(string(st.hex))" }
        if s.kerning != 0 { out += ",\"k\":\(num(s.kerning))" }
        if s.baselineOffset != 0 { out += ",\"b\":\(num(s.baselineOffset))" }
        return out + "}"
    }

    func symbol(_ frame: CGRect, name: String, size: CGFloat, weight: Int, color: _RGBA, large: Bool = false) {
        ops.append("{\"o\":\"symbol\",\"r\":\(Self.rect(frame)),\"n\":\(Self.string(name)),\"s\":\(Self.num(size)),\"w\":\(weight),\"c\":\(Self.string(color.hex))\(large ? ",\"sc\":\"l\"" : "")}")
    }

    func pushClip(_ path: Path) {
        depth += 1
        ops.append("{\"o\":\"clip\",\"d\":\(Self.path(path))}")
    }

    func pushOpacity(_ alpha: Double) {
        depth += 1
        ops.append("{\"o\":\"alpha\",\"a\":\(Self.num(CGFloat(alpha)))}")
    }

    func pushShadow(color: _RGBA, radius: CGFloat, x: CGFloat, y: CGFloat) {
        depth += 1
        ops.append("{\"o\":\"shadow\",\"c\":\(Self.string(color.hex)),\"r\":\(Self.num(radius)),\"x\":\(Self.num(x)),\"y\":\(Self.num(y))}")
    }

    func pop() {
        depth -= 1
        ops.append("{\"o\":\"pop\"}")
    }

    /// Liquid Glass over what is already painted: [path] is the glass shape, [bounds] its frame. The painter computes
    /// the glass from the pixels behind it (see Glass.swift).
    func glass(_ path: Path, bounds: CGRect, variant: String, tint: _RGBA?, dark: Bool) {
        var op = "{\"o\":\"glass\",\"d\":\(Self.path(path)),\"r\":\(Self.rect(bounds)),\"k\":\(Self.string(variant))"
        if let tint { op += ",\"tint\":\(Self.string(tint.hex))" }
        if dark { op += ",\"dark\":1" }
        ops.append(op + "}")
    }

    func hit(_ frame: CGRect, action: Int) {
        ops.append("{\"o\":\"hit\",\"r\":\(Self.rect(frame)),\"id\":\(action)}")
    }

    func field(_ frame: CGRect, setter: Int, text: String, secure: Bool) {
        ops.append("{\"o\":\"field\",\"r\":\(Self.rect(frame)),\"id\":\(setter),\"text\":\(Self.string(text)),\"secure\":\(secure ? 1 : 0)}")
    }

    var json: String { "[" + ops.joined(separator: ",") + "]" }
}
