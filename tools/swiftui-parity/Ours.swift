// Our side of the parity harness, run on the Mac: Cases.swift through CodeAssist's engine, text measured with CoreText,
// the display list painted with CoreGraphics at @3x, and each result compared with the Simulator's screenshot.
//
//   Ours <reference dir> <output dir> [case]
//
// Writes <output>/<case>.png (reference | ours | difference) and prints one line per case with the share of pixels
// that differ. Uses the system font (SF) for text: on the Mac that isolates layout and styling from font differences.

import AppKit
import CoreText
import ImageIO
import UniformTypeIdentifiers

// MARK: - CoreText measuring, shared by layout and painting

final class CoreTextMeasurer: _TextMeasurer {
    static func font(_ span: _ResolvedSpan) -> CTFont {
        let weights: [Int: NSFont.Weight] = [100: .ultraLight, 200: .thin, 300: .light, 400: .regular, 500: .medium,
                                             600: .semibold, 700: .bold, 800: .heavy, 900: .black]
        var font = NSFont.systemFont(ofSize: span.size, weight: weights[span.weight] ?? .regular)
        let design: NSFontDescriptor.SystemDesign? = switch span.design {
        case .rounded: .rounded
        case .serif: .serif
        case .monospaced: .monospaced
        case .default: nil
        }
        if let design, let d = font.fontDescriptor.withDesign(design) { font = NSFont(descriptor: d, size: span.size) ?? font }
        if span.italic { font = NSFontManager.shared.convert(font, toHaveTrait: .italicFontMask) }
        return font as CTFont
    }

    static func attributed(_ spans: [_ResolvedSpan]) -> NSAttributedString {
        let out = NSMutableAttributedString()
        for s in spans {
            var attrs: [NSAttributedString.Key: Any] = [
                .font: font(s),
                .foregroundColor: NSColor(srgbRed: s.color.r, green: s.color.g, blue: s.color.b, alpha: s.color.a),
            ]
            if s.kerning != 0 { attrs[.kern] = s.kerning }
            out.append(NSAttributedString(string: s.text, attributes: attrs))
        }
        return out
    }

    struct Layout {
        var lines: [CTLine]
        var lineHeight: CGFloat
        var ascent: CGFloat
        var width: CGFloat
    }

    static func layout(_ spans: [_ResolvedSpan], width: CGFloat?, maxLines: Int?) -> Layout {
        let string = attributed(spans)
        let typesetter = CTTypesetterCreateWithAttributedString(string)
        let fonts = spans.map(font)
        let ascent = fonts.map { CTFontGetAscent($0) }.max() ?? 0
        let lineHeight = fonts.map { CTFontGetAscent($0) + CTFontGetDescent($0) + CTFontGetLeading($0) }.max() ?? 0
        var lines: [CTLine] = []
        var start = 0
        let length = string.length
        var widest: CGFloat = 0
        while start < length {
            let limit = width ?? .greatestFiniteMagnitude
            var count = CTTypesetterSuggestLineBreak(typesetter, start, Double(max(limit, 0.1)))
            if count <= 0 { count = 1 }
            let last = maxLines.map { lines.count == $0 - 1 } ?? false
            var line = CTTypesetterCreateLine(typesetter, CFRange(location: start, length: last ? length - start : count))
            if last && start + count < length, let width {
                let ellipsis = CTLineCreateWithAttributedString(NSAttributedString(string: "…", attributes: string.attributes(at: start, effectiveRange: nil)))
                line = CTLineCreateTruncatedLine(line, Double(width), .end, ellipsis) ?? line
            }
            // SwiftUI's text width counts a wrapped line's trailing space (capped by the offered width below).
            let w = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
            widest = max(widest, w)
            lines.append(line)
            if last { break }
            start += count
        }
        if lines.isEmpty { lines = [CTLineCreateWithAttributedString(string)] }
        return Layout(lines: lines, lineHeight: lineHeight, ascent: ascent, width: width.map { min(widest, $0) } ?? widest)
    }

    func measure(_ spans: [_ResolvedSpan], width: CGFloat?, maxLines: Int?, alignment: TextAlignment, lineAdvance: CGFloat?) -> _TextMetrics {
        let l = Self.layout(spans, width: width, maxLines: maxLines)
        let advance = lineAdvance ?? l.lineHeight
        let height = l.lineHeight + CGFloat(l.lines.count - 1) * advance
        return _TextMetrics(size: CGSize(width: l.width, height: height), firstBaseline: l.ascent,
                            lastBaseline: CGFloat(l.lines.count - 1) * advance + l.ascent)
    }
}

// MARK: - Painting the display list

final class Painter {
    let ctx: CGContext
    let scale: CGFloat = 3
    var depth: [Int] = []

    init(width: Int, height: Int) {
        ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        // Top-left origin, in points.
        ctx.translateBy(x: 0, y: CGFloat(height))
        ctx.scaleBy(x: scale, y: -scale)
    }

    static func color(_ hex: String) -> CGColor {
        var v: UInt64 = 0
        Scanner(string: String(hex.dropFirst())).scanHexInt64(&v)
        return CGColor(srgbRed: CGFloat((v >> 24) & 0xFF) / 255, green: CGFloat((v >> 16) & 0xFF) / 255,
                       blue: CGFloat((v >> 8) & 0xFF) / 255, alpha: CGFloat(v & 0xFF) / 255)
    }

    /// Apple's continuous ("squircle") corner, as reconstructed from UIBezierPath(roundedRect:cornerRadius:).
    static func continuousRect(_ r: CGRect, radius: CGFloat) -> CGPath {
        let p = CGMutablePath()
        let limit = min(r.width, r.height) / 2 / 1.52866483
        let c = min(radius, limit)
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
        let (minX, minY, maxX, maxY) = (r.minX, r.minY, r.maxX, r.maxY)
        p.move(to: pt(minX + 1.52866483 * c, minY))
        p.addLine(to: pt(maxX - 1.52866483 * c, minY))
        p.addCurve(to: pt(maxX - 0.63149399 * c, minY + 0.07491100 * c), control1: pt(maxX - 1.08849299 * c, minY), control2: pt(maxX - 0.86840701 * c, minY))
        p.addCurve(to: pt(maxX - 0.07491100 * c, minY + 0.63149399 * c), control1: pt(maxX - 0.37282401 * c, minY + 0.16905900 * c), control2: pt(maxX - 0.16905900 * c, minY + 0.37282401 * c))
        p.addCurve(to: pt(maxX, minY + 1.52866483 * c), control1: pt(maxX, minY + 0.86840701 * c), control2: pt(maxX, minY + 1.08849299 * c))
        p.addLine(to: pt(maxX, maxY - 1.52866483 * c))
        p.addCurve(to: pt(maxX - 0.07491100 * c, maxY - 0.63149399 * c), control1: pt(maxX, maxY - 1.08849299 * c), control2: pt(maxX, maxY - 0.86840701 * c))
        p.addCurve(to: pt(maxX - 0.63149399 * c, maxY - 0.07491100 * c), control1: pt(maxX - 0.16905900 * c, maxY - 0.37282401 * c), control2: pt(maxX - 0.37282401 * c, maxY - 0.16905900 * c))
        p.addCurve(to: pt(maxX - 1.52866483 * c, maxY), control1: pt(maxX - 0.86840701 * c, maxY), control2: pt(maxX - 1.08849299 * c, maxY))
        p.addLine(to: pt(minX + 1.52866483 * c, maxY))
        p.addCurve(to: pt(minX + 0.63149399 * c, maxY - 0.07491100 * c), control1: pt(minX + 1.08849299 * c, maxY), control2: pt(minX + 0.86840701 * c, maxY))
        p.addCurve(to: pt(minX + 0.07491100 * c, maxY - 0.63149399 * c), control1: pt(minX + 0.37282401 * c, maxY - 0.16905900 * c), control2: pt(minX + 0.16905900 * c, maxY - 0.37282401 * c))
        p.addCurve(to: pt(minX, maxY - 1.52866483 * c), control1: pt(minX, maxY - 0.86840701 * c), control2: pt(minX, maxY - 1.08849299 * c))
        p.addLine(to: pt(minX, minY + 1.52866483 * c))
        p.addCurve(to: pt(minX + 0.07491100 * c, minY + 0.63149399 * c), control1: pt(minX, minY + 1.08849299 * c), control2: pt(minX, minY + 0.86840701 * c))
        p.addCurve(to: pt(minX + 0.63149399 * c, minY + 0.07491100 * c), control1: pt(minX + 0.16905900 * c, minY + 0.37282401 * c), control2: pt(minX + 0.37282401 * c, minY + 0.16905900 * c))
        p.addCurve(to: pt(minX + 1.52866483 * c, minY), control1: pt(minX + 0.86840701 * c, minY), control2: pt(minX + 1.08849299 * c, minY))
        p.closeSubpath()
        return p
    }

    static func path(_ elements: [[Any]]) -> CGPath {
        let p = CGMutablePath()
        for e in elements {
            let k = e[0] as! String
            let n = e.dropFirst().map { CGFloat(($0 as! NSNumber).doubleValue) }
            switch k {
            case "M": p.move(to: CGPoint(x: n[0], y: n[1]))
            case "L": p.addLine(to: CGPoint(x: n[0], y: n[1]))
            case "Q": p.addQuadCurve(to: CGPoint(x: n[0], y: n[1]), control: CGPoint(x: n[2], y: n[3]))
            case "C": p.addCurve(to: CGPoint(x: n[0], y: n[1]), control1: CGPoint(x: n[2], y: n[3]), control2: CGPoint(x: n[4], y: n[5]))
            case "Z": p.closeSubpath()
            case "R": p.addRect(CGRect(x: n[0], y: n[1], width: n[2], height: n[3]))
            case "E": p.addEllipse(in: CGRect(x: n[0], y: n[1], width: n[2], height: n[3]))
            case "RR":
                let r = CGRect(x: n[0], y: n[1], width: n[2], height: n[3])
                if n[6] == 1 {
                    p.addPath(continuousRect(r, radius: n[4]))
                } else {
                    let rx = min(n[4], r.width / 2), ry = min(n[5], r.height / 2)
                    p.addRoundedRect(in: r, cornerWidth: rx, cornerHeight: ry)
                }
            default: break
            }
        }
        return p
    }

    func paint(_ ops: [[String: Any]]) {
        for op in ops {
            switch op["o"] as! String {
            case "path":
                let p = Self.path(op["d"] as! [[Any]])
                ctx.addPath(p)
                if let sw = op["sw"] as? NSNumber {
                    ctx.setStrokeColor(Self.color(op["c"] as? String ?? "#000000FF"))
                    ctx.setLineWidth(CGFloat(sw.doubleValue))
                    ctx.strokePath()
                } else if let c = op["c"] as? String {
                    ctx.setFillColor(Self.color(c))
                    ctx.fillPath()
                } else {
                    ctx.beginPath()
                }
            case "text":
                drawText(op)
            case "symbol":
                drawSymbol(op)
            case "clip":
                ctx.saveGState()
                ctx.addPath(Self.path(op["d"] as! [[Any]]))
                ctx.clip()
            case "alpha":
                ctx.saveGState()
                ctx.setAlpha(CGFloat((op["a"] as! NSNumber).doubleValue))
                ctx.beginTransparencyLayer(auxiliaryInfo: nil)
                depth.append(1)
                continue
            case "shadow":
                ctx.saveGState()
                ctx.setShadow(offset: CGSize(width: CGFloat((op["x"] as! NSNumber).doubleValue) * scale, height: -CGFloat((op["y"] as! NSNumber).doubleValue) * scale),
                              blur: CGFloat((op["r"] as! NSNumber).doubleValue) * scale, color: Self.color(op["c"] as! String))
            case "pop":
                if depth.last == 1 { ctx.endTransparencyLayer() }
                if !depth.isEmpty { depth.removeLast() }
                ctx.restoreGState()
                continue
            default:
                break
            }
            if ["clip", "shadow"].contains(op["o"] as! String) { depth.append(0) }
        }
    }

    func drawText(_ op: [String: Any]) {
        let r = op["r"] as! [NSNumber]
        let frame = CGRect(x: r[0].doubleValue, y: r[1].doubleValue, width: r[2].doubleValue, height: r[3].doubleValue)
        let spans: [_ResolvedSpan] = (op["sp"] as! [[String: Any]]).map { s in
            let c = Self.color(s["c"] as! String).components!
            return _ResolvedSpan(text: s["t"] as! String, symbol: s["sym"] as? String, size: CGFloat((s["s"] as! NSNumber).doubleValue),
                                 weight: (s["w"] as! NSNumber).intValue,
                                 design: ["rounded": .rounded, "serif": .serif, "monospaced": .monospaced][s["d"] as! String] ?? .default,
                                 italic: s["i"] != nil, color: _RGBA(Double(c[0]), Double(c[1]), Double(c[2]), Double(c[3])),
                                 strikethrough: nil, underline: nil, kerning: CGFloat((s["k"] as? NSNumber)?.doubleValue ?? 0), baselineOffset: 0)
        }
        let layout = CoreTextMeasurer.layout(spans, width: frame.width + 0.01, maxLines: (op["n"] as? NSNumber)?.intValue)
        let align = op["a"] as! String
        ctx.saveGState()
        for (i, line) in layout.lines.enumerated() {
            let w = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil) - CTLineGetTrailingWhitespaceWidth(line))
            let x = align == "center" ? frame.minX + (frame.width - w) / 2 : align == "trailing" ? frame.maxX - w : frame.minX
            let advance = (op["lh"] as? NSNumber).map { CGFloat($0.doubleValue) } ?? layout.lineHeight
            let baseline = frame.minY + layout.ascent + CGFloat(i) * advance
            ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
            ctx.textPosition = CGPoint(x: x, y: baseline)
            CTLineDraw(line, ctx)
        }
        ctx.restoreGState()
    }

    func drawSymbol(_ op: [String: Any]) {
        let r = op["r"] as! [NSNumber]
        let frame = CGRect(x: r[0].doubleValue, y: r[1].doubleValue, width: r[2].doubleValue, height: r[3].doubleValue)
        let size = CGFloat((op["s"] as! NSNumber).doubleValue)
        let config = NSImage.SymbolConfiguration(pointSize: size, weight: .regular)
            .applying(NSImage.SymbolConfiguration(paletteColors: [NSColor(cgColor: Self.color(op["c"] as! String))!]))
        guard let image = NSImage(systemSymbolName: op["n"] as! String, accessibilityDescription: nil)?.withSymbolConfiguration(config),
              let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return }
        let s = image.size
        let rect = CGRect(x: frame.midX - s.width / 2, y: frame.midY - s.height / 2, width: s.width, height: s.height)
        ctx.saveGState()
        ctx.translateBy(x: rect.minX, y: rect.maxY)
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(cg, in: CGRect(origin: .zero, size: rect.size))
        ctx.restoreGState()
    }
}

// MARK: - Images and comparison

func loadImage(_ url: URL) -> CGImage? {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(src, 0, nil)
}

func rgba(_ image: CGImage) -> [UInt8] {
    var data = [UInt8](repeating: 0, count: image.width * image.height * 4)
    let ctx = CGContext(data: &data, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: image.width * 4,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    return data
}

func savePNG(_ image: CGImage, _ url: URL) {
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
}

/// Share of pixels that differ by more than a small tolerance (antialiasing), and a composite: reference, ours, and
/// the difference (red where they disagree, over a faded reference).
func compare(_ ref: CGImage, _ ours: CGImage) -> (Double, CGImage) {
    let w = ref.width, h = ref.height
    let a = rgba(ref), b = rgba(ours)
    var diff = [UInt8](repeating: 255, count: w * h * 4)
    var bad = 0
    for i in stride(from: 0, to: w * h * 4, by: 4) {
        let d = abs(Int(a[i]) - Int(b[i])) + abs(Int(a[i + 1]) - Int(b[i + 1])) + abs(Int(a[i + 2]) - Int(b[i + 2]))
        if d > 60 {
            bad += 1
            diff[i] = 255; diff[i + 1] = 0; diff[i + 2] = 0
        } else {
            let grey = UInt8(200 + Int(a[i]) * 55 / 255)
            diff[i] = grey; diff[i + 1] = grey; diff[i + 2] = grey
        }
    }
    let out = CGContext(data: nil, width: w * 3, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    out.draw(ref, in: CGRect(x: 0, y: 0, width: w, height: h))
    out.draw(ours, in: CGRect(x: w, y: 0, width: w, height: h))
    let diffCtx = CGContext(data: &diff, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    out.draw(diffCtx.makeImage()!, in: CGRect(x: 2 * w, y: 0, width: w, height: h))
    return (Double(bad) / Double(w * h), out.makeImage()!)
}

// MARK: - Main

@main
struct Ours {
    @MainActor static func main() {
        let args = CommandLine.arguments
        let refDir = URL(fileURLWithPath: args[1]), outDir = URL(fileURLWithPath: args[2])
        let only = args.count > 3 ? args[3] : nil
        try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        _Graph.shared.measurer = CoreTextMeasurer()
        var total = 0.0, count = 0
        for (name, view) in parityCases() where only == nil || name == only {
            let preview = _Preview(name) { view }
            _Graph.shared.beginRender()
            let json = _render(preview, index: 0, scheme: .light)
            _Graph.shared.endRender()
            if args.contains("--json") { print(json) }
            let frame = try! JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
            let painter = Painter(width: 1206, height: 2622)
            painter.ctx.setFillColor(Painter.color(frame["bg"] as! String))
            painter.ctx.fill(CGRect(x: 0, y: 0, width: 402, height: 874))
            painter.paint(frame["ops"] as! [[String: Any]])
            let ours = painter.ctx.makeImage()!
            savePNG(ours, outDir.appendingPathComponent("\(name)-ours.png"))
            guard let ref = loadImage(refDir.appendingPathComponent("\(name).png")) else { print("\(name): no reference"); continue }
            let (score, composite) = compare(ref, ours)
            savePNG(composite, outDir.appendingPathComponent("\(name).png"))
            print(String(format: "%-26@ %6.2f%% of pixels differ", name as NSString, score * 100))
            total += score
            count += 1
        }
        if count > 0 { print(String(format: "MEAN %6.2f%%", total / Double(count) * 100)) }
    }
}
