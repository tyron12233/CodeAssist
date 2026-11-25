// Previews: what `#Preview` and `PreviewProvider` become in a preview build, the device canvas they render on, and the
// loop that renders, answers taps and edits, and re-renders. The IDE talks to it over stdin/stdout, one line each way:
//
//   out  {"t":"frame","previews":[{"name":..,"w":402,"h":874,"bg":"#..","ops":[...]}]}
//   out  {"t":"measure","id":N,"sp":[...],"w":W|null,"n":LINES|null,"lh":ADVANCE|null,"a":"leading"}  -> in  "m N width height first last"
//   in   "tap N" | "set N <json string>" | "scheme light|dark" | "quit"

import Foundation
#if canImport(Android)
import Android
#elseif canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

// MARK: - Declaring previews

public protocol PreviewProvider {
    associatedtype Previews: View
    @ViewBuilder @MainActor static var previews: Previews { get }
}

public struct PreviewTrait<T>: Sendable {
    let name: String
}
public enum Preview {
    public enum ViewTraits {}
}
extension PreviewTrait where T == Preview.ViewTraits {
    public static var sizeThatFitsLayout: PreviewTrait<T> { PreviewTrait(name: "sizeThatFits") }
    public static var portrait: PreviewTrait<T> { PreviewTrait(name: "portrait") }
    public static var landscapeLeft: PreviewTrait<T> { PreviewTrait(name: "landscape") }
    public static var landscapeRight: PreviewTrait<T> { PreviewTrait(name: "landscape") }
    public static func fixedLayout(width: CGFloat, height: CGFloat) -> PreviewTrait<T> { PreviewTrait(name: "fixed:\(width)x\(height)") }
}

/// One preview, as the IDE's generated code declares it in place of a `#Preview` macro.
public final class _Preview {
    let name: String
    let traits: [String]
    let make: @MainActor () -> AnyView
    var root: AnyView?

    public init<V: View>(_ name: String? = nil, traits: PreviewTrait<Preview.ViewTraits>..., @ViewBuilder body: @escaping @MainActor () -> V) {
        self.name = name ?? ""
        self.traits = traits.map(\.name)
        make = { AnyView(body()) }
    }

    public init<P: PreviewProvider>(provider: P.Type) {
        name = String(describing: P.self)
        traits = []
        make = { AnyView(P.previews) }
    }
}

extension View {
    public func previewDisplayName(_ displayName: String?) -> some View { self }
    public func previewLayout(_ value: PreviewLayout) -> some View { self }
    public func previewDevice(_ value: PreviewDevice?) -> some View { self }
    public func previewInterfaceOrientation(_ value: InterfaceOrientation) -> some View { self }
}
public enum PreviewLayout: Sendable {
    case device, sizeThatFits
    case fixed(width: CGFloat, height: CGFloat)
}
public struct PreviewDevice: RawRepresentable, ExpressibleByStringLiteral, Sendable {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public init(stringLiteral: String) { rawValue = stringLiteral }
}
public struct InterfaceOrientation: Sendable {
    public static let portrait = InterfaceOrientation()
    public static let landscapeLeft = InterfaceOrientation()
    public static let landscapeRight = InterfaceOrientation()
    public static let portraitUpsideDown = InterfaceOrientation()
}

// MARK: - The device

/// iPhone 17 Pro, the preview canvas's default device: screen size and safe area in points.
enum _Device {
    static let size = CGSize(width: 402, height: 874)
    static let safeArea = EdgeInsets(top: 62, leading: 0, bottom: 34, trailing: 0)
    static var safeRect: CGRect {
        CGRect(x: safeArea.leading, y: safeArea.top, width: size.width - safeArea.horizontal, height: size.height - safeArea.vertical)
    }
}

// MARK: - Rendering

extension _Graph {
    private static var _measurer: _TextMeasurer = _PipeMeasurer()
    var measurer: _TextMeasurer {
        get { _Graph._measurer }
        set { _Graph._measurer = newValue }
    }
}

/// Renders [preview] onto the device canvas: as SwiftUI places a preview's root view, in the safe area, centred.
@MainActor
func _render(_ preview: _Preview, index: Int, scheme: ColorScheme) -> String {
    let graph = _Graph.shared
    let root = preview.root ?? preview.make()
    preview.root = root
    var env = EnvironmentValues()
    env.colorScheme = scheme
    let ctx = _ResolveContext(graph: graph, path: "p\(index)", environment: env)
    let nodes = _resolveView(root, ctx)
    let list = _DisplayList()
    let background = (nodes.count == 1 ? nodes[0].edgeBackground : nil ?? nil).map { $0.resolve(scheme) }
        ?? Color.systemBackground.resolve(scheme)

    if preview.traits.contains("sizeThatFits") {
        let node = nodes.count == 1 ? nodes[0] : _StackNode(path: ctx.path, env: env, axis: .vertical, alignment: .center, spacing: nil, children: nodes)
        let size = node.sizeThatFits(.unspecified)
        node.place(CGRect(origin: .zero, size: size))
        node.emit(list)
        return "{\"name\":\(_DisplayList.string(preview.name)),\"w\":\(_DisplayList.num(size.width)),\"h\":\(_DisplayList.num(size.height)),\"bg\":\(_DisplayList.string(background.hex)),\"ops\":\(list.json)}"
    }

    let device = _Device.size
    let safe = CGRect(x: _Device.safeArea.leading, y: _Device.safeArea.top,
                      width: device.width - _Device.safeArea.horizontal, height: device.height - _Device.safeArea.vertical)
    // Several top-level views (a #Preview body with more than one) stack vertically, as SwiftUI does.
    let node = nodes.count == 1 ? nodes[0] : _StackNode(path: ctx.path, env: env, axis: .vertical, alignment: .center, spacing: nil, children: nodes)
    let size = node.sizeThatFits(ProposedViewSize(safe.size))
    node.place(_align(node, size, in: safe, .center))
    node.emit(list)
    return "{\"name\":\(_DisplayList.string(preview.name)),\"w\":\(_DisplayList.num(device.width)),\"h\":\(_DisplayList.num(device.height)),\"bg\":\(_DisplayList.string(background.hex)),\"ops\":\(list.json)}"
}

@MainActor
func _renderAll(_ previews: [_Preview], scheme: ColorScheme) -> String {
    let graph = _Graph.shared
    graph.beginRender()
    var frames: [String] = []
    for (i, p) in previews.enumerated() { frames.append(_render(p, index: i, scheme: scheme)) }
    graph.endRender()
    return "{\"t\":\"frame\",\"previews\":[" + frames.joined(separator: ",") + "]}"
}

func _emit(_ line: String) {
    print(line)
    fflush(stdout)
}

/// The previews' event loop: render, then wait for the IDE to say what happened, and render again.
@MainActor
public func _runPreviews(_ previews: [_Preview]) {
    setvbuf(stdout, nil, _IOFBF, 1 << 16)
    var scheme = ColorScheme.light
    let graph = _Graph.shared
    _emit(_renderAll(previews, scheme: scheme))
    while let line = readLine() {
        let parts = line.split(separator: " ", maxSplits: 2).map(String.init)
        guard let command = parts.first else { continue }
        switch command {
        case "tap":
            if let id = parts.count > 1 ? Int(parts[1]) : nil { graph.actions[id]?() }
        case "set":
            if parts.count > 2, let id = Int(parts[1]) {
                let value = (try? JSONDecoder().decode(String.self, from: Data(parts[2].utf8))) ?? parts[2]
                graph.setters[id]?(value)
            }
        case "scheme":
            scheme = parts.count > 1 && parts[1] == "dark" ? .dark : .light
        case "quit":
            return
        default:
            continue
        }
        // Run .onAppear actions the render collected, then render what they and the event changed.
        while !graph.pendingAppear.isEmpty {
            let actions = graph.pendingAppear
            graph.pendingAppear = []
            actions.forEach { $0() }
        }
        _emit(_renderAll(previews, scheme: scheme))
    }
}

// MARK: - Measuring through the IDE

/// Asks the IDE to measure text and waits for its answer: the IDE lays text out with the font it paints with, so the
/// sizes layout uses are exactly what is drawn.
final class _PipeMeasurer: _TextMeasurer {
    private var next = 0
    private var cache: [String: _TextMetrics] = [:]

    func measure(_ spans: [_ResolvedSpan], width: CGFloat?, maxLines: Int?, alignment: TextAlignment, lineAdvance: CGFloat?) -> _TextMetrics {
        let encoded = spans.map(_DisplayList.span).joined(separator: ",")
        let key = "\(encoded)|\(width.map { "\($0)" } ?? "-")|\(maxLines ?? -1)|\(lineAdvance ?? -1)"
        if let cached = cache[key] { return cached }
        next += 1
        let w = width.map { _DisplayList.num($0) } ?? "null"
        let n = maxLines.map(String.init) ?? "null"
        let lh = lineAdvance.map { _DisplayList.num($0) } ?? "null"
        _emit("{\"t\":\"measure\",\"id\":\(next),\"sp\":[\(encoded)],\"w\":\(w),\"n\":\(n),\"lh\":\(lh),\"a\":\"\(alignment)\"}")
        var result = _TextMetrics(size: .zero, firstBaseline: 0, lastBaseline: 0)
        while let line = readLine() {
            let p = line.split(separator: " ")
            guard p.count >= 6, p[0] == "m", Int(p[1]) == next else { continue }
            result = _TextMetrics(
                size: CGSize(width: CGFloat(Double(p[2]) ?? 0), height: CGFloat(Double(p[3]) ?? 0)),
                firstBaseline: CGFloat(Double(p[4]) ?? 0), lastBaseline: CGFloat(Double(p[5]) ?? 0)
            )
            break
        }
        cache[key] = result
        return result
    }
}
