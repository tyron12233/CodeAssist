// The environment: values a view passes down to everything inside it (font, foreground style, colour scheme, ...),
// with the public EnvironmentKey / EnvironmentValues API and the modifiers that set them.

import Foundation

public protocol EnvironmentKey {
    associatedtype Value
    static var defaultValue: Value { get }
}

public struct EnvironmentValues: CustomStringConvertible {
    var _values: [ObjectIdentifier: Any] = [:]
    /// Objects from `.environmentObject(_:)` and `.environment(_ object:)`, by type.
    var _objects: [ObjectIdentifier: AnyObject] = [:]

    public init() {}

    public subscript<K: EnvironmentKey>(key: K.Type) -> K.Value {
        get { _values[ObjectIdentifier(key)] as? K.Value ?? K.defaultValue }
        set { _values[ObjectIdentifier(key)] = newValue }
    }

    public var description: String { "EnvironmentValues(\(_values.count) values)" }
}

// MARK: - Built-in keys

struct _FontKey: EnvironmentKey { static let defaultValue: Font? = nil }
struct _ForegroundKey: EnvironmentKey { static let defaultValue: AnyShapeStyle? = nil }
struct _TintKey: EnvironmentKey { static let defaultValue: Color? = nil }
struct _ColorSchemeKey: EnvironmentKey { static let defaultValue: ColorScheme = .light }
struct _TextAlignmentKey: EnvironmentKey { static let defaultValue: TextAlignment = .leading }
struct _LineLimitKey: EnvironmentKey { static let defaultValue: Int? = nil }
struct _EnabledKey: EnvironmentKey { static let defaultValue: Bool = true }
struct _BoldKey: EnvironmentKey { static let defaultValue: Bool = false }
struct _ItalicKey: EnvironmentKey { static let defaultValue: Bool = false }
struct _WeightKey: EnvironmentKey { static let defaultValue: Font.Weight? = nil }
struct _DynamicTypeKey: EnvironmentKey { static let defaultValue: DynamicTypeSize = .large }
struct _LayoutDirectionKey: EnvironmentKey { static let defaultValue: LayoutDirection = .leftToRight }
struct _StackAxisKey: EnvironmentKey { static let defaultValue: Axis? = nil }
struct _ButtonStyleKey: EnvironmentKey { static let defaultValue: _ButtonStyleKind = .automatic }
struct _ControlSizeKey: EnvironmentKey { static let defaultValue: ControlSize = .regular }
struct _ListStyleKey: EnvironmentKey { static let defaultValue: _ListStyleKind = .automatic }
struct _RedactionKey: EnvironmentKey { static let defaultValue: RedactionReasons = [] }
struct _LocaleKey: EnvironmentKey { static let defaultValue: Locale = Locale(identifier: "en_US") }
struct _CalendarKey: EnvironmentKey { static let defaultValue: Calendar = Calendar(identifier: .gregorian) }
struct _TimeZoneKey: EnvironmentKey { static let defaultValue: TimeZone = TimeZone(identifier: "UTC") ?? .current }
struct _DismissKey: EnvironmentKey { static let defaultValue = DismissAction() }
struct _OpenURLKey: EnvironmentKey { static let defaultValue = OpenURLAction(handler: { _ in .handled }) }
struct _EditModeKey: EnvironmentKey { static let defaultValue: Binding<EditMode>? = nil }
struct _HorizontalSizeClassKey: EnvironmentKey { static let defaultValue: UserInterfaceSizeClass? = .compact }
struct _VerticalSizeClassKey: EnvironmentKey { static let defaultValue: UserInterfaceSizeClass? = .regular }
struct _DisplayScaleKey: EnvironmentKey { static let defaultValue: CGFloat = 3 }
struct _IsPresentedKey: EnvironmentKey { static let defaultValue: Bool = false }
struct _ScenePhaseKey: EnvironmentKey { static let defaultValue: ScenePhase = .active }
struct _MinimumScaleKey: EnvironmentKey { static let defaultValue: CGFloat = 1 }
struct _TruncationKey: EnvironmentKey { static let defaultValue: Text.TruncationMode = .tail }
struct _LineSpacingKey: EnvironmentKey { static let defaultValue: CGFloat = 0 }
struct _ImageScaleKey: EnvironmentKey { static let defaultValue: Image.Scale = .medium }

extension EnvironmentValues {
    public var font: Font? { get { self[_FontKey.self] } set { self[_FontKey.self] = newValue } }
    public var colorScheme: ColorScheme { get { self[_ColorSchemeKey.self] } set { self[_ColorSchemeKey.self] = newValue } }
    public var multilineTextAlignment: TextAlignment { get { self[_TextAlignmentKey.self] } set { self[_TextAlignmentKey.self] = newValue } }
    public var lineLimit: Int? { get { self[_LineLimitKey.self] } set { self[_LineLimitKey.self] = newValue } }
    public var isEnabled: Bool { get { self[_EnabledKey.self] } set { self[_EnabledKey.self] = newValue } }
    public var dynamicTypeSize: DynamicTypeSize { get { self[_DynamicTypeKey.self] } set { self[_DynamicTypeKey.self] = newValue } }
    public var layoutDirection: LayoutDirection { get { self[_LayoutDirectionKey.self] } set { self[_LayoutDirectionKey.self] = newValue } }
    public var controlSize: ControlSize { get { self[_ControlSizeKey.self] } set { self[_ControlSizeKey.self] = newValue } }
    public var redactionReasons: RedactionReasons { get { self[_RedactionKey.self] } set { self[_RedactionKey.self] = newValue } }
    public var locale: Locale { get { self[_LocaleKey.self] } set { self[_LocaleKey.self] = newValue } }
    public var calendar: Calendar { get { self[_CalendarKey.self] } set { self[_CalendarKey.self] = newValue } }
    public var timeZone: TimeZone { get { self[_TimeZoneKey.self] } set { self[_TimeZoneKey.self] = newValue } }
    public var dismiss: DismissAction { self[_DismissKey.self] }
    public var openURL: OpenURLAction { get { self[_OpenURLKey.self] } set { self[_OpenURLKey.self] = newValue } }
    public var editMode: Binding<EditMode>? { get { self[_EditModeKey.self] } set { self[_EditModeKey.self] = newValue } }
    public var horizontalSizeClass: UserInterfaceSizeClass? { get { self[_HorizontalSizeClassKey.self] } set { self[_HorizontalSizeClassKey.self] = newValue } }
    public var verticalSizeClass: UserInterfaceSizeClass? { get { self[_VerticalSizeClassKey.self] } set { self[_VerticalSizeClassKey.self] = newValue } }
    public var displayScale: CGFloat { get { self[_DisplayScaleKey.self] } set { self[_DisplayScaleKey.self] = newValue } }
    public var isPresented: Bool { self[_IsPresentedKey.self] }
    public var scenePhase: ScenePhase { get { self[_ScenePhaseKey.self] } set { self[_ScenePhaseKey.self] = newValue } }
    public var minimumScaleFactor: CGFloat { get { self[_MinimumScaleKey.self] } set { self[_MinimumScaleKey.self] = newValue } }
    public var truncationMode: Text.TruncationMode { get { self[_TruncationKey.self] } set { self[_TruncationKey.self] = newValue } }
    public var lineSpacing: CGFloat { get { self[_LineSpacingKey.self] } set { self[_LineSpacingKey.self] = newValue } }
    public var imageScale: Image.Scale { get { self[_ImageScaleKey.self] } set { self[_ImageScaleKey.self] = newValue } }

    var _foreground: AnyShapeStyle? { get { self[_ForegroundKey.self] } set { self[_ForegroundKey.self] = newValue } }
    var _tint: Color? { get { self[_TintKey.self] } set { self[_TintKey.self] = newValue } }
    var _bold: Bool { get { self[_BoldKey.self] } set { self[_BoldKey.self] = newValue } }
    var _italic: Bool { get { self[_ItalicKey.self] } set { self[_ItalicKey.self] = newValue } }
    var _weight: Font.Weight? { get { self[_WeightKey.self] } set { self[_WeightKey.self] = newValue } }
    var _stackAxis: Axis? { get { self[_StackAxisKey.self] } set { self[_StackAxisKey.self] = newValue } }
    var _buttonStyle: _ButtonStyleKind { get { self[_ButtonStyleKey.self] } set { self[_ButtonStyleKey.self] = newValue } }
    var _listStyle: _ListStyleKind { get { self[_ListStyleKey.self] } set { self[_ListStyleKey.self] = newValue } }
}

// MARK: - Value types the environment carries

public enum ColorScheme: CaseIterable, Hashable, Sendable { case light, dark }
public enum LayoutDirection: Hashable, CaseIterable, Sendable { case leftToRight, rightToLeft }
public enum TextAlignment: Hashable, CaseIterable, Sendable { case leading, center, trailing }
public enum UserInterfaceSizeClass: Hashable, Sendable { case compact, regular }
public enum ControlSize: CaseIterable, Hashable, Sendable { case mini, small, regular, large, extraLarge }
public enum ScenePhase: Comparable, Hashable, Sendable { case background, inactive, active }
public enum EditMode: Hashable, Sendable {
    case inactive, transient, active
    public var isEditing: Bool { self != .inactive }
}
public enum DynamicTypeSize: Hashable, Comparable, CaseIterable, Sendable {
    case xSmall, small, medium, large, xLarge, xxLarge, xxxLarge
    case accessibility1, accessibility2, accessibility3, accessibility4, accessibility5
    public var isAccessibilitySize: Bool { self >= .accessibility1 }
}

public struct RedactionReasons: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    public static let placeholder = RedactionReasons(rawValue: 1)
    public static let privacy = RedactionReasons(rawValue: 2)
    public static let invalidated = RedactionReasons(rawValue: 4)
}

public struct DismissAction {
    public func callAsFunction() {}
}

public struct OpenURLAction {
    public struct Result: Sendable {
        public static let handled = Result()
        public static let discarded = Result()
        public static let systemAction = Result()
        public static func systemAction(_ url: URL) -> Result { Result() }
    }
    let handler: (URL) -> Result
    public init(handler: @escaping (URL) -> Result) { self.handler = handler }
    public func callAsFunction(_ url: URL) { _ = handler(url) }
}

public enum Axis: Int8, CaseIterable, Hashable, Sendable {
    case horizontal, vertical
    public struct Set: OptionSet, Sendable {
        public let rawValue: Int8
        public init(rawValue: Int8) { self.rawValue = rawValue }
        public static let horizontal = Set(rawValue: 1)
        public static let vertical = Set(rawValue: 2)
    }
}

// MARK: - Modifiers that set the environment

struct _EnvironmentModifier: _NodeModifier {
    let transform: (inout EnvironmentValues) -> Void
    func _context(_ ctx: _ResolveContext) -> _ResolveContext { ctx.with(transform) }
}

extension View {
    public func environment<V>(_ keyPath: WritableKeyPath<EnvironmentValues, V>, _ value: V) -> some View {
        _modified(_EnvironmentModifier { $0[keyPath: keyPath] = value })
    }

    public func environmentObject<T: ObservableObject>(_ object: T) -> some View {
        _modified(_EnvironmentModifier { $0._objects[ObjectIdentifier(T.self)] = object })
    }

    public func environment<T: AnyObject>(_ object: T?) -> some View {
        _modified(_EnvironmentModifier { env in
            if let object { env._objects[ObjectIdentifier(T.self)] = object }
        })
    }

    public func transformEnvironment<V>(_ keyPath: WritableKeyPath<EnvironmentValues, V>, transform: @escaping (inout V) -> Void) -> some View {
        _modified(_EnvironmentModifier { transform(&$0[keyPath: keyPath]) })
    }

    public func preferredColorScheme(_ colorScheme: ColorScheme?) -> some View {
        _modified(_EnvironmentModifier { env in if let colorScheme { env.colorScheme = colorScheme } })
    }

    public func multilineTextAlignment(_ alignment: TextAlignment) -> some View {
        environment(\.multilineTextAlignment, alignment)
    }

    public func lineLimit(_ number: Int?) -> some View { environment(\.lineLimit, number) }
    public func lineLimit(_ limit: Int, reservesSpace: Bool) -> some View { environment(\.lineLimit, limit) }
    public func lineSpacing(_ lineSpacing: CGFloat) -> some View { environment(\.lineSpacing, lineSpacing) }
    public func truncationMode(_ mode: Text.TruncationMode) -> some View { environment(\.truncationMode, mode) }
    public func minimumScaleFactor(_ factor: CGFloat) -> some View { environment(\.minimumScaleFactor, factor) }
    public func disabled(_ disabled: Bool) -> some View {
        _modified(_EnvironmentModifier { if disabled { $0.isEnabled = false } })
    }
    public func controlSize(_ controlSize: ControlSize) -> some View { environment(\.controlSize, controlSize) }
    public func dynamicTypeSize(_ size: DynamicTypeSize) -> some View { environment(\.dynamicTypeSize, size) }
    public func redacted(reason: RedactionReasons) -> some View { environment(\.redactionReasons, reason) }
    public func unredacted() -> some View { environment(\.redactionReasons, []) }

    public func font(_ font: Font?) -> some View { environment(\.font, font) }
    public func bold(_ isActive: Bool = true) -> some View { _modified(_EnvironmentModifier { $0._bold = isActive }) }
    public func italic(_ isActive: Bool = true) -> some View { _modified(_EnvironmentModifier { $0._italic = isActive }) }
    public func fontWeight(_ weight: Font.Weight?) -> some View { _modified(_EnvironmentModifier { $0._weight = weight }) }

    public func foregroundColor(_ color: Color?) -> some View {
        _modified(_EnvironmentModifier { $0._foreground = color.map { AnyShapeStyle($0) } })
    }
    public func foregroundStyle<S: ShapeStyle>(_ style: S) -> some View {
        _modified(_EnvironmentModifier { $0._foreground = AnyShapeStyle(style) })
    }
    public func foregroundStyle<S1: ShapeStyle, S2: ShapeStyle>(_ primary: S1, _ secondary: S2) -> some View {
        _modified(_EnvironmentModifier { $0._foreground = AnyShapeStyle(primary) })
    }
    public func foregroundStyle<S1: ShapeStyle, S2: ShapeStyle, S3: ShapeStyle>(_ primary: S1, _ secondary: S2, _ tertiary: S3) -> some View {
        _modified(_EnvironmentModifier { $0._foreground = AnyShapeStyle(primary) })
    }
    public func tint(_ tint: Color?) -> some View { _modified(_EnvironmentModifier { $0._tint = tint }) }
    public func tint<S: ShapeStyle>(_ tint: S?) -> some View {
        _modified(_EnvironmentModifier { env in if let tint { env._tint = AnyShapeStyle(tint)._resolvedColor(env) } })
    }
    public func accentColor(_ accentColor: Color?) -> some View { tint(accentColor) }
}
