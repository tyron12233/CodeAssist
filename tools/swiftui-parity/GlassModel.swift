// The Liquid Glass model the painters use (this file on the Mac; SwiftPreviewPainter's port on the device), with
// parameters fitted to the iOS Simulator (`Ours --fit-glass`): a lens at the rim that samples further inside, a
// light blur, a veil that lifts the content (regular) or barely touches it (clear), a tint, a bright rim, and a wide
// soft shadow outside. Everything works on an RGBA8 bitmap at 3 pixels per point.

import CoreGraphics
import Foundation

struct GlassParams: Codable {
    var veilA = 0.278, veilB = 0.702          // regular, light: out = veilA * in + veilB
    var darkA = 0.30, darkB = 0.10            // regular, dark
    var clearA = 1.0, clearB = 0.06           // clear
    var tintAmount = 0.80                     // tinted glass: share of the tint in the result
    var saturation = 1.0                      // colour boost applied to what is behind, before the veil
    // Glass's colour transform, a 3 x 4 matrix on sRGB (rows r, g, b: coefficients of r, g, b and the constant),
    // fitted by least squares to 24 colours seen through glass in the Simulator (glass-palette; residual 0.008 regular,
    // 0.002 clear): it lifts towards white and boosts saturation, which no single veil does.
    var regularMatrix: [Double] = [0.6685, -0.3660, -0.0282, 0.7160, -0.0883, 0.4001, -0.0300, 0.7125, -0.1196, -0.4078, 0.7839, 0.7315]
    var clearMatrix: [Double] = [1.0324, 0.0112, 0.0005, 0.0723, 0.0057, 1.0385, 0.0024, 0.0711, 0.0003, 0.0097, 1.0283, 0.0754]
    var blur = 1.5                            // blur radius, points (box blur x3)
    var lensDepth = 32.0                      // displacement at the edge, points
    var lensWidth = 12.0                      // band the lens works in, points from the edge
    var lensPower = 1.6                       // falloff exponent
    var rimWidth = 1.0                        // points
    var rimAmount = 0.25                      // added towards white at the very edge
    /// The lens, measured (glassmap analysis of clear glass over a position-encoded image, two sizes agreeing): how far
    /// inward, in points, a pixel at 0, 1, 2, ... points from the edge samples. Linear between entries, 0 past the end.
    var lensTable: [Double] = [30.0, 30.0, 32.0, 26.0, 22.8, 19.2, 16.2, 13.55, 11.3, 9.3, 7.55, 6.0, 4.65, 3.55, 2.55, 1.75, 1.08, 0.65, 0.27, 0.05, 0.0]
    /// Regular glass bends further in and blurs more than clear: its own lens (same form as lensTable, fitted with
    /// `fitlens` against the Simulator's position maps) and blur. Nil falls back to the clear values.
    var regularLensTable: [Double]? = nil
    var regularBlur: Double? = nil
    /// Regular glass's blur radius (points) by distance from the edge (points), linear between entries, the last entry
    /// beyond: it frosts a band along the rim (wide, strongest a few points in) and is nearly sharp inside it.
    var regularBlurTable: [Double]? = nil
    var shadowAlpha = 0.07
    var shadowRadius = 14.0                   // points
    var shadowY = 4.0                         // points

    static func load(_ path: String) -> GlassParams {
        guard let data = FileManager.default.contents(atPath: path), let p = try? JSONDecoder().decode(GlassParams.self, from: data) else { return GlassParams() }
        return p
    }
}

/// An RGBA8 bitmap (premultiplied, opaque content), top-left origin, `scale` pixels per point.
struct Bitmap {
    var w: Int, h: Int
    var px: UnsafeMutablePointer<UInt8>
    let scale: Double
    /// Bytes per row: CoreGraphics pads rows, so never assume w * 4.
    var stride: Int

    @inline(__always) func at(_ x: Int, _ y: Int, _ c: Int) -> Double { Double(px[y * stride + x * 4 + c]) / 255 }

    /// Bilinear sample of channel values in a snapshot.
    static func sample(_ s: [Float], _ w: Int, _ h: Int, _ x: Double, _ y: Double) -> RGB {
        let fx = min(max(x, 0), Double(w - 1)), fy = min(max(y, 0), Double(h - 1))
        let x0 = Int(fx), y0 = Int(fy), x1 = min(x0 + 1, w - 1), y1 = min(y0 + 1, h - 1)
        let tx = fx - Double(x0), ty = fy - Double(y0)
        func ch(_ c: Int) -> Double {
            let a = Double(s[(y0 * w + x0) * 3 + c]), b = Double(s[(y0 * w + x1) * 3 + c])
            let d = Double(s[(y1 * w + x0) * 3 + c]), e = Double(s[(y1 * w + x1) * 3 + c])
            return (a * (1 - tx) + b * tx) * (1 - ty) + (d * (1 - tx) + e * tx) * ty
        }
        return RGB(ch(0), ch(1), ch(2))
    }
}

enum GlassModel {
    /// Applies one glass shape: [mask] is its coverage (0...1) over the bitmap, [bounds] its frame in pixels.
    static func apply(_ bmp: Bitmap, mask: [Float], bounds: CGRect, variant: String, tint: RGB?, dark: Bool, p: GlassParams) {
        let w = bmp.w, h = bmp.h, s = bmp.scale
        let margin = Int((p.shadowRadius * 2 + p.shadowY + 4) * s)
        let x0 = max(0, Int(bounds.minX) - margin), x1 = min(w, Int(bounds.maxX) + margin)
        let y0 = max(0, Int(bounds.minY) - margin), y1 = min(h, Int(bounds.maxY) + margin)
        let rw = x1 - x0, rh = y1 - y0
        guard rw > 2, rh > 2 else { return }

        // Snapshot of what is behind, and blurred copies of it: one radius for clear glass (and regular without a
        // table), or a ladder of radii that a per-pixel radius (by distance from the edge) interpolates between.
        var snap0 = [Float](repeating: 0, count: rw * rh * 3)
        for y in 0..<rh { for x in 0..<rw { for c in 0..<3 { snap0[(y * rw + x) * 3 + c] = Float(bmp.at(x0 + x, y0 + y, c)) } } }
        let original = snap0
        let regular = variant != "clear"
        let table = regular ? (p.regularLensTable ?? p.lensTable) : p.lensTable
        let blurTable = regular ? p.regularBlurTable : nil
        var levels: [(Double, [Float])] = []   // (radius in points, blurred snapshot)
        func blurred(_ radius: Double) -> [Float] {
            var b = original
            let r = Int((radius * s).rounded())
            if r > 0 { for _ in 0..<3 { boxBlur(&b, rw, rh, r) } }
            return b
        }
        if let bt = blurTable {
            let maxR = bt.max() ?? 0
            var radius = 0.0
            levels.append((0, original))
            radius = 1 / s
            while true { levels.append((radius, blurred(radius))); if radius >= maxR { break }; radius *= 2 }
        } else {
            let blur = regular ? (p.regularBlur ?? p.blur) : p.blur
            levels.append((blur, blurred(blur)))
        }

        // Distance to the edge inside the shape, in pixels, and its gradient (the inward normal).
        var inside = [Float](repeating: 0, count: rw * rh)
        for y in 0..<rh { for x in 0..<rw { inside[y * rw + x] = mask[(y0 + y) * w + (x0 + x)] } }
        let dist = distanceInside(inside, rw, rh)
        // The chamfer distance's gradient is noisy where fronts meet (corners); a smoothed copy gives clean normals.
        var smooth = dist
        for _ in 0..<2 { boxBlur1(&smooth, rw, rh, 2) }

        // The shadow first, outside the shape: the shape's coverage, blurred and offset, darkening.
        var shadow = inside
        let sr = max(1, Int((p.shadowRadius * s / 2).rounded()))
        for _ in 0..<3 { boxBlur1(&shadow, rw, rh, sr) }
        let dy = Int((p.shadowY * s).rounded())

        for y in 0..<rh {
            for x in 0..<rw {
                let i = y * rw + x
                let cover = Double(inside[i])
                let gx = x0 + x, gy = y0 + y
                let o = gy * bmp.stride + gx * 4
                var out = RGB(Double(original[i * 3]), Double(original[i * 3 + 1]), Double(original[i * 3 + 2]))
                // Shadow (only where the glass does not cover).
                let sy = y - dy
                if sy >= 0 && sy < rh {
                    let sh = Double(shadow[sy * rw + x]) * p.shadowAlpha * (1 - cover)
                    out = out.map { $0 * (1 - sh) }
                }
                if cover > 0 {
                    let d = Double(dist[i]) / s
                    // Inward normal from the distance field's gradient.
                    let dxp = Double(smooth[y * rw + min(x + 1, rw - 1)]) - Double(smooth[y * rw + max(x - 1, 0)])
                    let dyp = Double(smooth[min(y + 1, rh - 1) * rw + x]) - Double(smooth[max(y - 1, 0) * rw + x])
                    let len = max(1e-6, (dxp * dxp + dyp * dyp).squareRoot())
                    let offset = GlassModel.lens(table, d) * s
                    let qx = Double(x) + dxp / len * offset, qy = Double(y) + dyp / len * offset
                    var c: RGB
                    if let bt = blurTable {
                        let want = GlassModel.lens(bt, d)
                        var k = 0
                        while k < levels.count - 2 && levels[k + 1].0 < want { k += 1 }
                        let (r0, s0) = levels[k], (r1, s1) = levels[min(k + 1, levels.count - 1)]
                        let t = r1 > r0 ? min(1, max(0, (want - r0) / (r1 - r0))) : 0
                        let a = Bitmap.sample(s0, rw, rh, qx, qy)
                        c = t > 0 ? a.mix(Bitmap.sample(s1, rw, rh, qx, qy), t) : a
                    } else {
                        c = Bitmap.sample(levels[0].1, rw, rh, qx, qy)
                    }
                    if variant != "clear" && dark {
                        c = c.map { $0 * p.darkA + p.darkB }
                    } else {
                        let m = variant == "clear" ? p.clearMatrix : p.regularMatrix
                        func row(_ k: Int) -> Double { min(1, max(0, m[k] * c.r + m[k + 1] * c.g + m[k + 2] * c.b + m[k + 3])) }
                        c = RGB(row(0), row(4), row(8))
                    }
                    if let t = tint { c = c.mix(t, p.tintAmount) }
                    // Measured: dark glass has no bright rim.
                    if d < p.rimWidth && !(dark && variant != "clear") {
                        let k = p.rimAmount * (1 - d / p.rimWidth)
                        c = c.map { $0 + (1 - $0) * k }
                    }
                    out = out.mix(c, cover)
                }
                bmp.px[o] = UInt8(max(0, min(255, (out.r * 255).rounded())))
                bmp.px[o + 1] = UInt8(max(0, min(255, (out.g * 255).rounded())))
                bmp.px[o + 2] = UInt8(max(0, min(255, (out.b * 255).rounded())))
            }
        }
    }

    static func lens(_ t: [Double], _ d: Double) -> Double {
        guard d >= 0, !t.isEmpty else { return 0 }
        let i = Int(d)
        if i >= t.count - 1 { return t.last ?? 0 }
        let f = d - Double(i)
        return t[i] * (1 - f) + t[i + 1] * f
    }

    static func boxBlur(_ a: inout [Float], _ w: Int, _ h: Int, _ r: Int) {
        var tmp = a
        let n = Float(2 * r + 1)
        for y in 0..<h {
            for c in 0..<3 {
                var acc: Float = 0
                for k in -r...r { acc += a[(y * w + min(max(k, 0), w - 1)) * 3 + c] }
                for x in 0..<w {
                    tmp[(y * w + x) * 3 + c] = acc / n
                    acc += a[(y * w + min(x + r + 1, w - 1)) * 3 + c] - a[(y * w + max(x - r, 0)) * 3 + c]
                }
            }
        }
        for x in 0..<w {
            for c in 0..<3 {
                var acc: Float = 0
                for k in -r...r { acc += tmp[(min(max(k, 0), h - 1) * w + x) * 3 + c] }
                for y in 0..<h {
                    a[(y * w + x) * 3 + c] = acc / n
                    acc += tmp[(min(y + r + 1, h - 1) * w + x) * 3 + c] - tmp[(max(y - r, 0) * w + x) * 3 + c]
                }
            }
        }
    }

    static func boxBlur1(_ a: inout [Float], _ w: Int, _ h: Int, _ r: Int) {
        var tmp = a
        let n = Float(2 * r + 1)
        for y in 0..<h {
            var acc: Float = 0
            for k in -r...r { acc += a[y * w + min(max(k, 0), w - 1)] }
            for x in 0..<w { tmp[y * w + x] = acc / n; acc += a[y * w + min(x + r + 1, w - 1)] - a[y * w + max(x - r, 0)] }
        }
        for x in 0..<w {
            var acc: Float = 0
            for k in -r...r { acc += tmp[min(max(k, 0), h - 1) * w + x] }
            for y in 0..<h { a[y * w + x] = acc / n; acc += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x] }
        }
    }

    /// Distance (pixels) from each covered pixel to the nearest uncovered one: two-pass chamfer (3-4) on the mask.
    static func distanceInside(_ m: [Float], _ w: Int, _ h: Int) -> [Float] {
        let big: Float = 1e9
        var d = m.map { $0 >= 0.5 ? big : 0 }
        for y in 0..<h {
            for x in 0..<w where d[y * w + x] > 0 {
                var v = d[y * w + x]
                if x > 0 { v = min(v, d[y * w + x - 1] + 1) }
                if y > 0 { v = min(v, d[(y - 1) * w + x] + 1) }
                if x > 0 && y > 0 { v = min(v, d[(y - 1) * w + x - 1] + 1.4142) }
                if x < w - 1 && y > 0 { v = min(v, d[(y - 1) * w + x + 1] + 1.4142) }
                d[y * w + x] = v
            }
        }
        for y in stride(from: h - 1, through: 0, by: -1) {
            for x in stride(from: w - 1, through: 0, by: -1) where d[y * w + x] > 0 {
                var v = d[y * w + x]
                if x < w - 1 { v = min(v, d[y * w + x + 1] + 1) }
                if y < h - 1 { v = min(v, d[(y + 1) * w + x] + 1) }
                if x < w - 1 && y < h - 1 { v = min(v, d[(y + 1) * w + x + 1] + 1.4142) }
                if x > 0 && y < h - 1 { v = min(v, d[(y + 1) * w + x - 1] + 1.4142) }
                d[y * w + x] = v
            }
        }
        // Coverage below one pixel still counts as at the edge.
        for i in d.indices where m[i] > 0 && m[i] < 0.5 { d[i] = 0.5 }
        return d
    }
}

/// Oklab conversions (same constants as the engine's _Oklab and SwiftGlass.kt).
enum OklabF {
    static func lin(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
    static func srgb(_ c: Double) -> Double { c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1 / 2.4) - 0.055 }
    static func lab(_ c: RGB) -> (Double, Double, Double) {
        let r = lin(c.r), g = lin(c.g), b = lin(c.b)
        let l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        let m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        let s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }
    static func rgb(_ v: (Double, Double, Double)) -> RGB {
        let l = pow(v.0 + 0.3963377774 * v.1 + 0.2158037573 * v.2, 3)
        let m = pow(v.0 - 0.1055613458 * v.1 - 0.0638541728 * v.2, 3)
        let s = pow(v.0 - 0.0894841775 * v.1 - 1.2914855480 * v.2, 3)
        func c(_ x: Double) -> Double { min(1, max(0, srgb(x))) }
        return RGB(c(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                   c(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                   c(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s))
    }
}

/// An RGB triple, 0...1.
struct RGB {
    var r: Double, g: Double, b: Double
    init(_ r: Double, _ g: Double, _ b: Double) { self.r = r; self.g = g; self.b = b }
    func map(_ f: (Double) -> Double) -> RGB { RGB(f(r), f(g), f(b)) }
    func mix(_ o: RGB, _ k: Double) -> RGB { RGB(r * (1 - k) + o.r * k, g * (1 - k) + o.g * k, b * (1 - k) + o.b * k) }
}
