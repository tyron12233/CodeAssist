// Text: SwiftUI's construction API (localized keys with interpolation, verbatim strings, attributed strings, dates),
// the modifiers that return Text, concatenation, and the node that sizes text by asking the host to measure it.

import Foundation

// MARK: - Localized string keys

public struct LocalizedStringKey: Equatable, ExpressibleByStringInterpolation, ExpressibleByStringLiteral, Sendable {
    var spans: [_Span]

    public init(_ value: String) { spans = [_Span(text: value)] }
    public init(stringLiteral value: String) { spans = [_Span(text: value)] }
    public init(stringInterpolation: StringInterpolation) { spans = stringInterpolation.spans }

    public static func == (a: LocalizedStringKey, b: LocalizedStringKey) -> Bool { a.spans.map(\.text) == b.spans.map(\.text) }

    public struct StringInterpolation: StringInterpolationProtocol {
        var spans: [_Span] = []

        public init(literalCapacity: Int, interpolationCount: Int) {}

        mutating func append(_ text: String) {
            if let last = spans.last, last.isPlain { spans[spans.count - 1].text += text } else { spans.append(_Span(text: text)) }
        }

        public mutating func appendLiteral(_ literal: String) { append(literal) }
        public mutating func appendInterpolation(_ string: String) { append(string) }
        public mutating func appendInterpolation<Subject: ReferenceConvertible>(_ subject: Subject, formatter: Formatter? = nil) {
            append(_format(formatter, subject))
        }
        public mutating func appendInterpolation<Subject: NSObject>(_ subject: Subject, formatter: Formatter? = nil) {
            append(_format(formatter, subject))
        }
        public mutating func appendInterpolation<T>(_ value: T) { append("\(value)") }
        public mutating func appendInterpolation<T: BinaryInteger>(_ value: T, specifier: String) {
            append(String(format: specifier, Int64(value)))
        }
        public mutating func appendInterpolation<T: BinaryFloatingPoint>(_ value: T, specifier: String) {
            append(String(format: specifier, Double(value)))
        }
        public mutating func appendInterpolation(_ text: Text) { spans += text.spans }
        public mutating func appendInterpolation(_ image: Image) {
            spans.append(_Span(text: "\u{FFFC}", symbol: image.systemName))
        }
        public mutating func appendInterpolation(_ date: Date, style: Text.DateStyle) { append(style.format(date)) }
        public mutating func appendInterpolation<F: FormatStyle>(_ input: F.FormatInput, format: F) where F.FormatOutput == String {
            append(format.format(input))
        }
        public mutating func appendInterpolation(_ attributed: AttributedString) { append(String(attributed.characters)) }
    }
}

/// A value through a Formatter. Not generic on purpose: handing a generic value straight to the Objective-C
/// `string(for:)` crashes the Swift 6.4 compiler on Apple platforms (SILGen, emitNativeToCBridgedValue).
func _format(_ formatter: Formatter?, _ value: Any) -> String {
    formatter?.string(for: value) ?? "\(value)"
}

/// A run of text and the styling Text-level modifiers gave it (nil: inherit from the environment).
struct _Span: Equatable, Sendable {
    var text: String
    var symbol: String? = nil
    var font: Font? = nil
    var weight: Font.Weight? = nil
    var italic: Bool? = nil
    var color: Color? = nil
    var strikethrough: Color?? = nil
    var underline: Color?? = nil
    var kerning: CGFloat? = nil
    var baselineOffset: CGFloat? = nil
    var monospaced: Bool? = nil

    var isPlain: Bool {
        symbol == nil && font == nil && weight == nil && italic == nil && color == nil && strikethrough == nil &&
            underline == nil && kerning == nil && baselineOffset == nil && monospaced == nil
    }
}

// MARK: - Text

public struct Text: Equatable, Sendable {
    var spans: [_Span]
    var textCase: Text.Case? = nil

    public init(verbatim content: String) { spans = [_Span(text: content)] }
    public init<S: StringProtocol>(_ content: S) { spans = [_Span(text: String(content))] }
    public init(_ key: LocalizedStringKey, tableName: String? = nil, bundle: Bundle? = nil, comment: StaticString? = nil) {
        spans = key.spans
    }
    public init(_ attributedContent: AttributedString) { spans = [_Span(text: String(attributedContent.characters))] }
    public init(_ date: Date, style: DateStyle) { spans = [_Span(text: style.format(date))] }
    public init(_ dates: ClosedRange<Date>) {
        spans = [_Span(text: "\(DateStyle.time.format(dates.lowerBound))–\(DateStyle.time.format(dates.upperBound))")]
    }
    public init<F: FormatStyle>(_ input: F.FormatInput, format: F) where F.FormatOutput == String {
        spans = [_Span(text: format.format(input))]
    }
    public init<Subject: ReferenceConvertible>(_ subject: Subject, formatter: Formatter) {
        spans = [_Span(text: _format(formatter, subject))]
    }
    public init(_ image: Image) { spans = [_Span(text: "\u{FFFC}", symbol: image.systemName)] }
    init(spans: [_Span]) { self.spans = spans }

    public enum TruncationMode: Hashable, Sendable { case head, tail, middle }
    public enum Case: Hashable, Sendable { case uppercase, lowercase }

    public struct DateStyle: Sendable {
        let kind: Int
        public static let time = DateStyle(kind: 0)
        public static let date = DateStyle(kind: 1)
        public static let relative = DateStyle(kind: 2)
        public static let offset = DateStyle(kind: 3)
        public static let timer = DateStyle(kind: 4)

        func format(_ date: Date) -> String {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US")
            switch kind {
            case 0: f.dateStyle = .none; f.timeStyle = .short
            case 1: f.dateStyle = .long; f.timeStyle = .none
            default:
                let seconds = Int(abs(date.timeIntervalSinceNow))
                return seconds < 60 ? "\(seconds) sec" : seconds < 3600 ? "\(seconds / 60) min" : "\(seconds / 3600) hr"
            }
            return f.string(from: date)
        }
    }

    private func mapSpans(_ transform: (inout _Span) -> Void) -> Text {
        var copy = self
        for i in copy.spans.indices { transform(&copy.spans[i]) }
        return copy
    }

    public func font(_ font: Font?) -> Text { mapSpans { if $0.font == nil { $0.font = font } } }
    public func fontWeight(_ weight: Font.Weight?) -> Text { mapSpans { if $0.weight == nil { $0.weight = weight } } }
    public func bold() -> Text { fontWeight(.bold) }
    public func bold(_ isActive: Bool) -> Text { isActive ? bold() : self }
    public func italic() -> Text { mapSpans { if $0.italic == nil { $0.italic = true } } }
    public func italic(_ isActive: Bool) -> Text { isActive ? italic() : self }
    public func foregroundColor(_ color: Color?) -> Text { mapSpans { if $0.color == nil { $0.color = color } } }
    public func foregroundStyle<S: ShapeStyle>(_ style: S) -> Text {
        let color = (style as? _ResolvableStyle)?._resolvedColor(EnvironmentValues())
        return foregroundColor(color)
    }
    public func strikethrough(_ isActive: Bool = true, color: Color? = nil) -> Text {
        mapSpans { if isActive { $0.strikethrough = .some(color) } }
    }
    public func strikethrough(_ isActive: Bool = true, pattern: LineStylePattern, color: Color? = nil) -> Text { strikethrough(isActive, color: color) }
    public func underline(_ isActive: Bool = true, color: Color? = nil) -> Text {
        mapSpans { if isActive { $0.underline = .some(color) } }
    }
    public func underline(_ isActive: Bool = true, pattern: LineStylePattern, color: Color? = nil) -> Text { underline(isActive, color: color) }
    public func kerning(_ kerning: CGFloat) -> Text { mapSpans { $0.kerning = kerning } }
    public func tracking(_ tracking: CGFloat) -> Text { kerning(tracking) }
    public func baselineOffset(_ offset: CGFloat) -> Text { mapSpans { $0.baselineOffset = offset } }
    public func monospaced(_ isActive: Bool = true) -> Text { mapSpans { $0.monospaced = isActive } }
    public func monospacedDigit() -> Text { self }
    public func textScale(_ scale: Text.Scale, isEnabled: Bool = true) -> Text { self }

    public struct Scale: Hashable, Sendable {
        public static let `default` = Scale()
        public static let secondary = Scale()
    }
    public struct LineStylePattern: Hashable, Sendable {
        public static let solid = LineStylePattern()
        public static let dot = LineStylePattern()
        public static let dash = LineStylePattern()
        public static let dashDot = LineStylePattern()
        public static let dashDotDot = LineStylePattern()
    }

    public static func + (lhs: Text, rhs: Text) -> Text { Text(spans: lhs.spans + rhs.spans) }
}

extension Text: View, _PrimitiveView {
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_TextNode(path: ctx.path, env: ctx.environment, spans: spans, textCase: ctx.environment[_TextCaseKey.self])]
    }
}

struct _TextCaseKey: EnvironmentKey { static let defaultValue: Text.Case? = nil }

extension View {
    public func textCase(_ textCase: Text.Case?) -> some View {
        _modified(_EnvironmentModifier { $0[_TextCaseKey.self] = textCase })
    }
}

// MARK: - Measuring and the text node

/// A run as the host lays it out: everything resolved, nothing inherited.
struct _ResolvedSpan: Equatable, Hashable {
    var text: String
    var symbol: String?
    var size: CGFloat
    var weight: Int
    var design: Font.Design
    var italic: Bool
    var color: _RGBA
    var strikethrough: _RGBA?
    var underline: _RGBA?
    var kerning: CGFloat
    var baselineOffset: CGFloat
}

struct _TextMetrics: Equatable {
    var size: CGSize
    var firstBaseline: CGFloat
    var lastBaseline: CGFloat
}

/// What measures text: CoreText in the Mac parity harness, the IDE (over the pipe) on the device.
protocol _TextMeasurer: AnyObject {
    /// [lineAdvance]: the distance from one line to the next, nil for the font's natural line height.
    func measure(_ spans: [_ResolvedSpan], width: CGFloat?, maxLines: Int?, alignment: TextAlignment, lineAdvance: CGFloat?) -> _TextMetrics
}

/// Pixel grid: the preview renders at @3x, as an iPhone does, and SwiftUI rounds text up to it.
let _scale: CGFloat = 3
func _ceilToPixel(_ v: CGFloat) -> CGFloat { (v * _scale - 0.001).rounded(.up) / _scale }

@MainActor
final class _TextNode: _Node {
    let resolved: [_ResolvedSpan]
    let alignment: TextAlignment
    let lineLimit: Int?
    let lineAdvance: CGFloat?
    private var cache: [String: _TextMetrics] = [:]

    init(path: String, env: EnvironmentValues, spans: [_Span], textCase: Text.Case?) {
        alignment = env.multilineTextAlignment
        lineLimit = env.lineLimit
        let base = env.font ?? .body
        // A span's own font (Text.font) decides the line height when there is one.
        lineAdvance = ((spans.first?.font ?? base).lineAdvance).map { $0 + env.lineSpacing }
        resolved = spans.map { span in
            let font = span.font ?? base
            var weight = font.weight.value
            if let w = env._weight { weight = w.value }
            if env._bold { weight = max(weight, Font.Weight.bold.value) }
            if let w = span.weight { weight = w.value }
            let color = span.color ?? env._foreground?._resolvedColor(env) ?? .primary
            var text = span.text
            switch textCase {
            case .uppercase: text = text.uppercased()
            case .lowercase: text = text.lowercased()
            case nil: break
            }
            return _ResolvedSpan(
                text: text, symbol: span.symbol, size: font.size, weight: weight,
                design: span.monospaced == true ? .monospaced : font.design,
                italic: span.italic ?? (font.isItalic || env._italic),
                color: color.resolve(env.colorScheme).with(alpha: env.isEnabled ? 1 : 0.5),
                strikethrough: span.strikethrough.map { ($0 ?? color).resolve(env.colorScheme) },
                underline: span.underline.map { ($0 ?? color).resolve(env.colorScheme) },
                kerning: span.kerning ?? 0, baselineOffset: span.baselineOffset ?? 0
            )
        }
        super.init(path: path, env: env)
    }

    override var isTextLike: Bool { true }

    override var textSpacing: _TextSpacing? {
        let font = (env.font ?? .body)
        return _TextSpacing(size: resolved.first?.size ?? font.size, style: font.textStyle)
    }

    func metrics(_ proposal: ProposedViewSize) -> _TextMetrics {
        let width = proposal.width.flatMap { $0.isFinite ? $0 : nil }
        let key = "\(width ?? -1)|\(proposal.height ?? -1)"
        if let cached = cache[key] { return cached }
        let measurer = _Graph.shared.measurer
        var m = measurer.measure(resolved, width: width, maxLines: lineLimit, alignment: alignment, lineAdvance: lineAdvance)
        // Less height than the text needs: as many whole lines as fit, never fewer than one.
        if let h = proposal.height, h.isFinite, m.size.height > h + 0.01 {
            let single = measurer.measure(resolved, width: width, maxLines: 1, alignment: alignment, lineAdvance: lineAdvance)
            let lineHeight = max(1, single.size.height)
            let lines = max(1, Int((h / lineHeight).rounded(.down)))
            if lineLimit == nil || lines < lineLimit! {
                m = measurer.measure(resolved, width: width, maxLines: lines, alignment: alignment, lineAdvance: lineAdvance)
            }
        }
        m.size = CGSize(width: _ceilToPixel(m.size.width), height: _ceilToPixel(m.size.height))
        cache[key] = m
        return m
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let m = metrics(proposal)
        if let w = proposal.width, w.isFinite { return CGSize(width: min(m.size.width, max(0, w)), height: m.size.height) }
        return m.size
    }

    override func baselines(for size: CGSize) -> (first: CGFloat?, last: CGFloat?) {
        let m = metrics(ProposedViewSize(width: size.width, height: nil))
        return (m.firstBaseline, m.lastBaseline)
    }

    override func emit(_ list: _DisplayList) {
        let m = metrics(ProposedViewSize(width: frame.width, height: frame.height))
        list.text(frame, spans: resolved, alignment: alignment, maxLines: lineLimit, lineAdvance: lineAdvance)
    }
}
