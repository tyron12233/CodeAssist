// What the engine needs from the platform. On Apple platforms CGFloat, CGPoint, CGSize and CGRect come from
// CoreGraphics, whose Swift additions (`.zero`, Equatable) Foundation no longer re-exports; elsewhere Foundation itself
// defines them.

#if canImport(CoreGraphics)
@_exported import CoreGraphics
#endif
@_exported import Foundation

#if !canImport(CoreGraphics)
/// CoreGraphics' affine transform, which Foundation off Apple's platforms does not have: what SwiftUI API that takes
/// one (GraphicsContext.concatenate, ProjectionTransform) needs to compile.
public struct CGAffineTransform: Equatable, Sendable {
    public var a: CGFloat, b: CGFloat, c: CGFloat, d: CGFloat, tx: CGFloat, ty: CGFloat
    public init(a: CGFloat, b: CGFloat, c: CGFloat, d: CGFloat, tx: CGFloat, ty: CGFloat) {
        self.a = a; self.b = b; self.c = c; self.d = d; self.tx = tx; self.ty = ty
    }
    public init(_ a: CGFloat, _ b: CGFloat, _ c: CGFloat, _ d: CGFloat, _ tx: CGFloat, _ ty: CGFloat) {
        self.init(a: a, b: b, c: c, d: d, tx: tx, ty: ty)
    }
    public static let identity = CGAffineTransform(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)
    public init(translationX x: CGFloat, y: CGFloat) { self.init(a: 1, b: 0, c: 0, d: 1, tx: x, ty: y) }
    public init(scaleX x: CGFloat, y: CGFloat) { self.init(a: x, b: 0, c: 0, d: y, tx: 0, ty: 0) }
    public init(rotationAngle angle: CGFloat) {
        let c = CGFloat(cos(Double(angle))), s = CGFloat(sin(Double(angle)))
        self.init(a: c, b: s, c: -s, d: c, tx: 0, ty: 0)
    }
}
#endif
