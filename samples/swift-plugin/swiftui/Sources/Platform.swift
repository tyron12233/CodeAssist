// What the engine needs from the platform. On Apple platforms CGFloat, CGPoint, CGSize and CGRect come from
// CoreGraphics, whose Swift additions (`.zero`, Equatable) Foundation no longer re-exports; elsewhere Foundation itself
// defines them.

#if canImport(CoreGraphics)
@_exported import CoreGraphics
#endif
@_exported import Foundation
