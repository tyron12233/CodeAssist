// Liquid Glass parity cases (iOS 26): glass over high-contrast content, so blur, refraction and highlights can be
// measured and the painters' glass model fitted (`Ours --fit-glass`).
#if canImport(UIKit)
import SwiftUI
#endif

struct Stripes: View {
    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<20, id: \.self) { i in
                (i % 2 == 0 ? Color.black : Color.white).frame(width: 402 / 20)
            }
        }
    }
}

struct Checker: View {
    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<44, id: \.self) { r in
                HStack(spacing: 0) {
                    ForEach(0..<20, id: \.self) { c in
                        ((r + c) % 2 == 0 ? Color.red : Color.blue).frame(width: 402 / 20, height: 874 / 44)
                    }
                }
            }
        }
    }
}

/// The palette behind glass-palette: 6 x 4 cells, each a colour, so glass's colour transform can be fitted.
let glassPalette: [Color] = [
    .red, .orange, .yellow, .green, .mint, .teal,
    .cyan, .blue, .indigo, .purple, .pink, .brown,
    .black, .white, .gray, Color(red: 0.5, green: 0.5, blue: 0.5), Color(red: 0.25, green: 0.25, blue: 0.25), Color(red: 0.75, green: 0.75, blue: 0.75),
    Color(red: 1, green: 0, blue: 0), Color(red: 0, green: 1, blue: 0), Color(red: 0, green: 0, blue: 1),
    Color(red: 1, green: 1, blue: 0), Color(red: 0, green: 1, blue: 1), Color(red: 1, green: 0, blue: 1),
]

struct Palette: View {
    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<6, id: \.self) { r in
                HStack(spacing: 0) {
                    ForEach(0..<4, id: \.self) { c in glassPalette[r * 4 + c].frame(width: 402 / 4, height: 874 / 6) }
                }
            }
        }
    }
}

@MainActor
func glassCases() -> [(String, AnyView)] {
    [
        ("glass-palette", AnyView(ZStack {
            Palette().ignoresSafeArea()
            Color.clear.frame(width: 390, height: 860).glassEffect(.regular, in: .rect(cornerRadius: 50))
        })),
        ("glass-palette-clear", AnyView(ZStack {
            Palette().ignoresSafeArea()
            Color.clear.frame(width: 390, height: 860).glassEffect(.clear, in: .rect(cornerRadius: 50))
        })),
        ("glass-palette-bare", AnyView(Palette().ignoresSafeArea())),
        // Ramps: brightness encodes position, so where the glass sampled can be read back (Glass analysis).
        ("glass-ramp-x", AnyView(ZStack {
            LinearGradient(colors: [.black, .white], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })),
        ("glass-ramp-y", AnyView(ZStack {
            LinearGradient(colors: [.black, .white], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })),
        ("glass-ramp-x-clear", AnyView(ZStack {
            LinearGradient(colors: [.black, .white], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.clear, in: .rect(cornerRadius: 40))
        })),
        ("glass-ramp-x-small", AnyView(ZStack {
            LinearGradient(colors: [.black, .white], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            Color.clear.frame(width: 120, height: 60).glassEffect(.regular, in: .capsule)
        })),
        ("glass-ramp-x-big", AnyView(ZStack {
            LinearGradient(colors: [.black, .white], startPoint: .leading, endPoint: .trailing).ignoresSafeArea()
            Color.clear.frame(width: 380, height: 500).glassEffect(.regular, in: .rect(cornerRadius: 60))
        })),
        ("glass-stripes-capsule", AnyView(ZStack {
            Stripes().ignoresSafeArea()
            Text("Liquid Glass").font(.title).padding(.horizontal, 40).padding(.vertical, 24).glassEffect()
        })),
        ("glass-stripes-rect", AnyView(ZStack {
            Stripes().ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })),
        ("glass-white", AnyView(ZStack {
            Color.white.ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })),
        ("glass-black", AnyView(ZStack {
            Color.black.ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })),
        ("glass-clear-checker", AnyView(ZStack {
            Checker().ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.clear, in: .rect(cornerRadius: 40))
        })),
        ("glass-regular-checker", AnyView(ZStack {
            Checker().ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })),
        ("glass-tint", AnyView(ZStack {
            Stripes().ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular.tint(.blue), in: .rect(cornerRadius: 40))
        })),
        ("glass-buttons", AnyView(ZStack {
            Checker().ignoresSafeArea()
            VStack(spacing: 30) {
                Button("Glass") {}.buttonStyle(.glass)
                Button("Prominent") {}.buttonStyle(.glassProminent)
                Button { } label: { Image(systemName: "heart.fill") }.buttonStyle(.glass)
            }
        })),
        ("glass-container", AnyView(ZStack {
            Stripes().ignoresSafeArea()
            GlassEffectContainer(spacing: 30) {
                HStack(spacing: 20) {
                    Image(systemName: "pencil").frame(width: 70, height: 70).glassEffect()
                    Image(systemName: "eraser").frame(width: 70, height: 70).glassEffect()
                }
            }
        })),
    ]
}

#if canImport(UIKit)
import UIKit

/// A screen-sized image whose colour encodes position along one axis, exactly per pixel (no gradient interpolation):
/// red is a coarse ramp (0...0.4 over the screen), green a fine sawtooth (0...0.6 every 60 pixels), blue 0. Through
/// glass, inverting glass's colour matrix recovers which pixel each glass pixel shows (glassmap analysis).
func positionMap(vertical: Bool, range: Double = 0.4, fine: Double = 0.6, period: Int = 60) -> UIImage {
    let w = 1206, h = 2622
    var data = [UInt8](repeating: 255, count: w * h * 4)
    for y in 0..<h {
        for x in 0..<w {
            let p = vertical ? y : x
            let n = vertical ? h : w
            let o = (y * w + x) * 4
            data[o] = UInt8((Double(p) / Double(n - 1) * range * 255).rounded())
            data[o + 1] = UInt8((Double(p % period) / Double(period - 1) * fine * 255).rounded())
            data[o + 2] = 0
        }
    }
    let ctx = CGContext(data: &data, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    return UIImage(cgImage: ctx.makeImage()!, scale: 3, orientation: .up)
}

/// Reference-only cases (the engine has no UIImage): the position maps under glass, and bare.
@MainActor
func glassMapCases() -> [(String, AnyView)] {
    var cases: [(String, AnyView)] = []
    for vertical in [false, true] {
        let image = Image(uiImage: positionMap(vertical: vertical))
        let axis = vertical ? "y" : "x"
        cases.append(("glassmap-\(axis)-bare", AnyView(image.ignoresSafeArea())))
        cases.append(("glassmap-\(axis)-rect", AnyView(ZStack {
            image.ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })))
        cases.append(("glassmap-\(axis)-capsule", AnyView(ZStack {
            image.ignoresSafeArea()
            Color.clear.frame(width: 200, height: 60).glassEffect(.regular, in: .capsule)
        })))
        cases.append(("glassmap-\(axis)-big", AnyView(ZStack {
            image.ignoresSafeArea()
            Color.clear.frame(width: 380, height: 600).glassEffect(.regular, in: .rect(cornerRadius: 60))
        })))
        // Clear glass barely changes colour (1.03 x + 0.07), so a full-range map reads to ~1 point: the lens, measured.
        let full = Image(uiImage: positionMap(vertical: vertical, range: 0.85, fine: 0.85))
        cases.append(("glassmap-\(axis)-full-bare", AnyView(full.ignoresSafeArea())))
        cases.append(("glassmap-\(axis)-clear-rect", AnyView(ZStack {
            full.ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.clear, in: .rect(cornerRadius: 40))
        })))
        cases.append(("glassmap-\(axis)-clear-capsule", AnyView(ZStack {
            full.ignoresSafeArea()
            Color.clear.frame(width: 200, height: 60).glassEffect(.clear, in: .capsule)
        })))
        cases.append(("glassmap-\(axis)-clear-big", AnyView(ZStack {
            full.ignoresSafeArea()
            Color.clear.frame(width: 380, height: 600).glassEffect(.clear, in: .rect(cornerRadius: 60))
        })))
        // Regular glass blurs a few points and compresses colour to ~0.4: a full-range map with an 80-point fine period
        // keeps the sawtooth's wraps (smeared by the blur) a small share of it.
        let wide = Image(uiImage: positionMap(vertical: vertical, range: 0.85, fine: 0.85, period: 240))
        cases.append(("glassmap-\(axis)-wide-bare", AnyView(wide.ignoresSafeArea())))
        cases.append(("glassmap-\(axis)-wide-rect", AnyView(ZStack {
            wide.ignoresSafeArea()
            Color.clear.frame(width: 300, height: 200).glassEffect(.regular, in: .rect(cornerRadius: 40))
        })))
        cases.append(("glassmap-\(axis)-wide-big", AnyView(ZStack {
            wide.ignoresSafeArea()
            Color.clear.frame(width: 380, height: 600).glassEffect(.regular, in: .rect(cornerRadius: 60))
        })))
        cases.append(("glassmap-\(axis)-wide-capsule", AnyView(ZStack {
            wide.ignoresSafeArea()
            Color.clear.frame(width: 200, height: 60).glassEffect(.regular, in: .capsule)
        })))
    }
    return cases
}
#endif
