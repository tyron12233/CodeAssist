// Views the API audit against Apple's SwiftUI interface found missing: pickers, menus, grids, text editors, date
// pickers and the smaller system views. Their looks are measured on the iPhone 17 Pro Simulator (tools/swiftui-parity
// ApiCases.swift, and `run.sh dump` for the ones UIKit draws).

import Foundation

/// Nodes already resolved, as a view: how a container composes parts it resolved itself into an ordinary layout.
struct _Nodes: View, _PrimitiveView {
    let nodes: [_Node]
    var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] { nodes }
}

// MARK: - Picker

enum _PickerStyleKind { case automatic, menu, segmented, wheel, inline, navigationLink, palette }
struct _PickerStyleKey: EnvironmentKey { static let defaultValue: _PickerStyleKind = .automatic }

public struct PalettePickerStyle: PickerStyle { public init() {} }
extension PickerStyle where Self == PalettePickerStyle { public static var palette: PalettePickerStyle { PalettePickerStyle() } }

func _pickerStyleKind(_ style: Any) -> _PickerStyleKind {
    switch style {
    case is SegmentedPickerStyle: return .segmented
    case is WheelPickerStyle: return .wheel
    case is InlinePickerStyle: return .inline
    case is NavigationLinkPickerStyle: return .navigationLink
    case is PalettePickerStyle: return .palette
    case is MenuPickerStyle: return .menu
    default: return .automatic
    }
}

/// iOS 26's Picker. The menu style (the default): in a List, the label with the selection after it in the secondary
/// colour and chevron.up.chevron.down; elsewhere the selection and the chevrons in the tint. Segmented: a 32-point
/// capsule track the width offered, the selection on a white thumb, 13-point labels (medium when selected), measured
/// from UISegmentedControl. A tap on a menu picker moves to the next option, the preview's stand-in for its menu.
public struct Picker<Label: View, SelectionValue: Hashable, Content: View>: View, _PrimitiveView {
    let label: Label
    let selection: Binding<SelectionValue>
    let content: Content
    public init(selection: Binding<SelectionValue>, @ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label) {
        self.selection = selection
        self.content = content()
        self.label = label()
    }
    public init(selection: Binding<SelectionValue>, @ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label,
                @ViewBuilder currentValueLabel: () -> some View) {
        self.init(selection: selection, content: content, label: label)
    }
    public var body: Never { _neverBody() }

    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let env = ctx.environment
        let style = env[_PickerStyleKey.self]
        let current = AnyHashable(selection.wrappedValue)
        let selection = self.selection
        func options(_ c: _ResolveContext) -> [_Node] { _resolveView(content, c.with { $0._stackAxis = nil }) }
        let probe = options(ctx.child("probe").with { $0._isProbe = true })
        let tags = probe.map(\.tagValue)
        let index = tags.firstIndex { $0 == current }
        let select: (Int) -> Void = { i in
            if tags.indices.contains(i), let value = tags[i]?.base as? SelectionValue { selection.wrappedValue = value }
        }
        let next = { select(((index ?? -1) + 1) % max(1, tags.count)) }

        switch style {
        case .segmented, .palette:
            let labels = (0..<tags.count).map { i -> _Node? in
                let c = ctx.child("segment\(i)").with {
                    $0.font = Font(size: 13, weight: i == index ? .medium : .regular)
                    $0._foreground = AnyShapeStyle(Color.primary)
                    $0._stackAxis = nil
                }
                return options(c).dropFirst(i).first
            }
            return [_SegmentedNode(path: ctx.path, env: env, labels: labels.compactMap { $0 }, selected: index, select: select)]
        case .inline, .wheel:
            // Every option, the selected one checked: as an inline picker in a Form shows them.
            let rows = options(ctx.child("rows")).enumerated().map { i, node -> _Node in
                let row = _resolveView(HStack {
                    _Nodes(nodes: [node])
                    Spacer()
                    if i == index { Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(TintShapeStyle()) }
                }, ctx.child("row\(i)"))
                return row.count == 1 ? _TapNode(child: row[0], action: { select(i) }) : row.first ?? node
            }
            return rows
        default:
            let inList = env[_InListKey.self]
            let valueCtx = ctx.child("value").with {
                $0._foreground = AnyShapeStyle(inList ? Color.secondary : (env._tint ?? .accentColor))
                $0._stackAxis = nil
            }
            let value = index.flatMap { options(valueCtx).dropFirst($0).first }
            let chevron = Image(systemName: "chevron.up.chevron.down").font(.footnote.weight(.medium))
                .foregroundStyle(inList ? AnyShapeStyle(Color.secondary) : AnyShapeStyle(env._tint ?? .accentColor))
            let nodes: [_Node]
            if inList {
                nodes = _resolveView(HStack {
                    label
                    Spacer()
                    HStack(spacing: 4) {
                        _Nodes(nodes: value.map { [$0] } ?? [])
                        chevron
                    }
                }, ctx.child("row"))
            } else {
                nodes = _resolveView(HStack(spacing: 4) {
                    _Nodes(nodes: value.map { [$0] } ?? [])
                    chevron
                }, ctx.child("button"))
            }
            return nodes.map { _TapNode(child: $0, action: next) }
        }
    }
}

extension Picker where Label == Text {
    public init(_ titleKey: LocalizedStringKey, selection: Binding<SelectionValue>, @ViewBuilder content: () -> Content) {
        self.init(selection: selection, content: content) { Text(titleKey) }
    }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, selection: Binding<SelectionValue>, @ViewBuilder content: () -> Content) {
        self.init(selection: selection, content: content) { Text(title) }
    }
    public init(_ titleKey: LocalizedStringKey, systemImage: String, selection: Binding<SelectionValue>, @ViewBuilder content: () -> Content) {
        self.init(selection: selection, content: content) { Text(titleKey) }
    }
}

/// UISegmentedControl as iOS 26 draws it, measured: 32 tall, a capsule track; the selected segment on a white capsule
/// thumb inset 2 points, with a soft shadow.
@MainActor
final class _SegmentedNode: _Node {
    let labels: [_Node]
    let selected: Int?
    let select: (Int) -> Void
    private var segments: [CGRect] = []
    init(path: String, env: EnvironmentValues, labels: [_Node], selected: Int?, select: @escaping (Int) -> Void) {
        self.labels = labels
        self.selected = selected
        self.select = select
        super.init(path: path, env: env)
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let natural = labels.map { $0.sizeThatFits(.unspecified).width + 24 }.reduce(0, +)
        return CGSize(width: proposal.width.map { $0.isFinite ? $0 : natural } ?? natural, height: 32)
    }
    override func place(_ rect: CGRect) {
        frame = rect
        let n = max(1, labels.count)
        let w = rect.width / CGFloat(n)
        segments = (0..<labels.count).map { CGRect(x: rect.minX + CGFloat($0) * w, y: rect.minY, width: w, height: rect.height) }
        for (i, label) in labels.enumerated() {
            let s = label.sizeThatFits(ProposedViewSize(width: w - 8, height: nil))
            label.place(CGRect(x: segments[i].midX - s.width / 2, y: segments[i].midY - s.height / 2, width: s.width, height: s.height))
        }
    }
    override func emit(_ list: _DisplayList) {
        list.shape(Capsule().path(in: frame), style: AnyShapeStyle(Color.tertiarySystemFill), stroke: nil, env: env)
        if let selected, segments.indices.contains(selected) {
            let thumb = segments[selected].insetBy(dx: 2, dy: 2)
            list.shape(Capsule().path(in: thumb), style: AnyShapeStyle(Color(light: _RGBA(1, 1, 1), dark: _RGBA(hex: 0x636366))), stroke: nil, env: env)
        }
        labels.forEach { $0.emit(list) }
        for (i, s) in segments.enumerated() { let i = i; list.hit(s, action: _Graph.shared.register { self.select(i) }) }
    }
}

// MARK: - Menu

/// A menu's button: its label in the tint, as a borderless button. A tap runs the primary action, if any.
public struct Menu<Label: View, Content: View>: View {
    let label: Label
    let content: Content
    let primaryAction: (() -> Void)?
    public init(@ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label) {
        self.content = content()
        self.label = label()
        primaryAction = nil
    }
    public init(@ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label, primaryAction: @escaping () -> Void) {
        self.content = content()
        self.label = label()
        self.primaryAction = primaryAction
    }
    public var body: some View {
        Button(action: primaryAction ?? {}) { label }
    }
}

extension Menu where Label == Text {
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder content: () -> Content) { self.init(content: content) { Text(titleKey) } }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) { self.init(content: content) { Text(title) } }
}

extension Menu where Label == SwiftUILabel<Text, Image> {
    public init(_ titleKey: LocalizedStringKey, systemImage: String, @ViewBuilder content: () -> Content) {
        self.init(content: content) { SwiftUILabel(titleKey, systemImage: systemImage) }
    }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, systemImage: String, @ViewBuilder content: () -> Content) {
        self.init(content: content) { SwiftUILabel(title, systemImage: systemImage) }
    }
}

public protocol MenuStyle {}
public struct DefaultMenuStyle: MenuStyle { public init() {} }
public struct ButtonMenuStyle: MenuStyle { public init() {} }
public struct BorderlessButtonMenuStyle: MenuStyle { public init() {} }
extension MenuStyle where Self == DefaultMenuStyle { public static var automatic: DefaultMenuStyle { DefaultMenuStyle() } }
extension MenuStyle where Self == ButtonMenuStyle { public static var button: ButtonMenuStyle { ButtonMenuStyle() } }
extension MenuStyle where Self == BorderlessButtonMenuStyle { public static var borderlessButton: BorderlessButtonMenuStyle { BorderlessButtonMenuStyle() } }
public enum MenuOrder: Sendable { case automatic, priority, fixed }

// MARK: - Lazy grids

public struct GridItem: Sendable {
    public enum Size: Sendable {
        case fixed(CGFloat)
        case flexible(minimum: CGFloat = 10, maximum: CGFloat = .infinity)
        case adaptive(minimum: CGFloat, maximum: CGFloat = .infinity)
    }
    public var size: Size
    public var spacing: CGFloat?
    public var alignment: Alignment?
    public init(_ size: Size = .flexible(), spacing: CGFloat? = nil, alignment: Alignment? = nil) {
        self.size = size
        self.spacing = spacing
        self.alignment = alignment
    }
}

/// The tracks of a lazy grid across [length]: fixed sizes first, then flexible items share the rest (within their
/// bounds), and an adaptive item becomes as many tracks of at least its minimum as fit. Default spacing 8, as SwiftUI's.
func _gridTracks(_ items: [GridItem], length: CGFloat) -> [(size: CGFloat, spacing: CGFloat, alignment: Alignment?)] {
    var expanded: [(GridItem, CGFloat?)] = []
    for item in items {
        if case .adaptive(let minimum, let maximum) = item.size {
            let sp = item.spacing ?? 8
            let others = items.filter { if case .adaptive = $0.size { return false }; return true }
            let fixed = others.reduce(CGFloat(0)) { acc, i in if case .fixed(let f) = i.size { return acc + f + (i.spacing ?? 8) }; return acc }
            let room = max(0, length - fixed)
            let count = max(1, Int((room + sp) / (minimum + sp)))
            let size = min(maximum, (room - sp * CGFloat(count - 1)) / CGFloat(count))
            for _ in 0..<count { expanded.append((item, size)) }
        } else {
            expanded.append((item, nil))
        }
    }
    let spacings = expanded.enumerated().map { i, e in i == expanded.count - 1 ? 0 : (e.0.spacing ?? 8) }
    var remaining = length - spacings.reduce(0, +)
    var sizes = [CGFloat?](repeating: nil, count: expanded.count)
    for (i, e) in expanded.enumerated() {
        if case .fixed(let f) = e.0.size { sizes[i] = f; remaining -= f }
        if let s = e.1 { sizes[i] = s; remaining -= s }
    }
    let flexible = expanded.indices.filter { sizes[$0] == nil }
    if !flexible.isEmpty {
        let share = max(0, remaining) / CGFloat(flexible.count)
        for i in flexible {
            if case .flexible(let minimum, let maximum) = expanded[i].0.size { sizes[i] = min(max(share, minimum), maximum) } else { sizes[i] = share }
        }
    }
    return expanded.indices.map { (sizes[$0] ?? 0, spacings[$0], expanded[$0].0.alignment) }
}

@MainActor
final class _LazyGridNode: _Node {
    let vertical: Bool
    let items: [GridItem]
    let spacing: CGFloat
    let children: [_Node]
    init(path: String, env: EnvironmentValues, vertical: Bool, items: [GridItem], spacing: CGFloat?, children: [_Node]) {
        self.vertical = vertical
        self.items = items
        self.spacing = spacing ?? 8
        self.children = children.filter { !$0.isSpacer }
        super.init(path: path, env: env)
    }

    private func layout(_ cross: CGFloat) -> (tracks: [(size: CGFloat, spacing: CGFloat, alignment: Alignment?)], lines: [CGFloat]) {
        let tracks = _gridTracks(items, length: cross)
        let n = max(1, tracks.count)
        var lines: [CGFloat] = []
        var i = 0
        while i < children.count {
            var line: CGFloat = 0
            for t in 0..<n where i + t < children.count {
                let s = vertical
                    ? children[i + t].sizeThatFits(ProposedViewSize(width: tracks[t].size, height: nil)).height
                    : children[i + t].sizeThatFits(ProposedViewSize(width: nil, height: tracks[t].size)).width
                line = max(line, s)
            }
            lines.append(line)
            i += n
        }
        return (tracks, lines)
    }

    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        let crossProposal = vertical ? proposal.width : proposal.height
        let natural = items.reduce(CGFloat(0)) { acc, i in
            if case .fixed(let f) = i.size { return acc + f + (i.spacing ?? 8) }
            return acc + 80
        }
        let offered = crossProposal.map { $0.isFinite ? $0 : natural } ?? natural
        let (tracks, lines) = layout(offered)
        // As wide (or tall) as its tracks: all of the offer for flexible ones, just their sizes for fixed ones.
        let cross = min(offered, tracks.reduce(0) { $0 + $1.size + $1.spacing })
        let along = lines.reduce(0, +) + spacing * CGFloat(max(0, lines.count - 1))
        return vertical ? CGSize(width: cross, height: along) : CGSize(width: along, height: cross)
    }

    override func place(_ rect: CGRect) {
        frame = rect
        let (tracks, lines) = layout(vertical ? rect.width : rect.height)
        let n = max(1, tracks.count)
        var along = vertical ? rect.minY : rect.minX
        for (l, line) in lines.enumerated() {
            var cross = vertical ? rect.minX : rect.minY
            for t in 0..<n {
                let index = l * n + t
                guard index < children.count else { break }
                let cell = vertical
                    ? CGRect(x: cross, y: along, width: tracks[t].size, height: line)
                    : CGRect(x: along, y: cross, width: line, height: tracks[t].size)
                let child = children[index]
                let size = child.sizeThatFits(ProposedViewSize(width: vertical ? tracks[t].size : nil, height: vertical ? nil : tracks[t].size))
                child.place(_align(child, size, in: cell, tracks[t].alignment ?? .center))
                cross += tracks[t].size + tracks[t].spacing
            }
            along += line + spacing
        }
    }
    override func emit(_ list: _DisplayList) { children.forEach { $0.emit(list) } }
}

public struct LazyVGrid<Content: View>: View, _PrimitiveView {
    let columns: [GridItem]
    let spacing: CGFloat?
    let content: Content
    public init(columns: [GridItem], alignment: HorizontalAlignment = .center, spacing: CGFloat? = nil,
                pinnedViews: PinnedScrollableViews = .init(), @ViewBuilder content: () -> Content) {
        self.columns = columns
        self.spacing = spacing
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_LazyGridNode(path: ctx.path, env: ctx.environment, vertical: true, items: columns, spacing: spacing,
                       children: _resolveView(content, ctx.with { $0._stackAxis = nil }))]
    }
}

public struct LazyHGrid<Content: View>: View, _PrimitiveView {
    let rows: [GridItem]
    let spacing: CGFloat?
    let content: Content
    public init(rows: [GridItem], alignment: VerticalAlignment = .center, spacing: CGFloat? = nil,
                pinnedViews: PinnedScrollableViews = .init(), @ViewBuilder content: () -> Content) {
        self.rows = rows
        self.spacing = spacing
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_LazyGridNode(path: ctx.path, env: ctx.environment, vertical: false, items: rows, spacing: spacing,
                       children: _resolveView(content, ctx.with { $0._stackAxis = nil }))]
    }
}

// MARK: - Grid

@MainActor
final class _GridRowMarker: _Node {
    let cells: [_Node]
    init(path: String, env: EnvironmentValues, cells: [_Node]) {
        self.cells = cells
        super.init(path: path, env: env)
    }
}

public struct GridRow<Content: View>: View, _PrimitiveView {
    let content: Content
    public init(alignment: VerticalAlignment? = nil, @ViewBuilder content: () -> Content) { self.content = content() }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_GridRowMarker(path: ctx.path, env: ctx.environment, cells: _resolveView(content, ctx.with { $0._stackAxis = nil }))]
    }
}

/// Grid: a table whose columns are as wide as their widest cell; a view between rows (a Divider) spans them all.
@MainActor
final class _GridNode: _Node {
    let rows: [[_Node]]   // a single non-row view is a row of one spanning cell
    let spanning: [Bool]
    let alignment: Alignment
    let hSpacing: CGFloat
    let vSpacing: CGFloat
    private var widths: [CGFloat] = []
    init(path: String, env: EnvironmentValues, rows: [[_Node]], spanning: [Bool], alignment: Alignment, hSpacing: CGFloat, vSpacing: CGFloat) {
        self.rows = rows
        self.spanning = spanning
        self.alignment = alignment
        self.hSpacing = hSpacing
        self.vSpacing = vSpacing
        super.init(path: path, env: env)
    }
    private func columnWidths() -> [CGFloat] {
        let n = rows.enumerated().filter { !spanning[$0.offset] }.map { $0.element.count }.max() ?? 0
        var w = [CGFloat](repeating: 0, count: n)
        for (r, row) in rows.enumerated() where !spanning[r] {
            for (c, cell) in row.enumerated() { w[c] = max(w[c], cell.sizeThatFits(.unspecified).width) }
        }
        return w
    }
    private func rowHeight(_ r: Int, width: CGFloat) -> CGFloat {
        if spanning[r] { return rows[r].first?.sizeThatFits(ProposedViewSize(width: width, height: nil)).height ?? 0 }
        return rows[r].enumerated().map { c, cell in cell.sizeThatFits(ProposedViewSize(width: widths.indices.contains(c) ? widths[c] : nil, height: nil)).height }.max() ?? 0
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        widths = columnWidths()
        let width = widths.reduce(0, +) + hSpacing * CGFloat(max(0, widths.count - 1))
        let height = rows.indices.map { rowHeight($0, width: width) }.reduce(0, +) + vSpacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: width, height: height)
    }
    override func place(_ rect: CGRect) {
        frame = rect
        widths = columnWidths()
        var y = rect.minY
        for (r, row) in rows.enumerated() {
            let h = rowHeight(r, width: rect.width)
            if spanning[r], let only = row.first {
                let s = only.sizeThatFits(ProposedViewSize(width: rect.width, height: nil))
                only.place(CGRect(x: rect.minX, y: y, width: rect.width, height: s.height))
            } else {
                var x = rect.minX
                for (c, cell) in row.enumerated() where widths.indices.contains(c) {
                    let s = cell.sizeThatFits(ProposedViewSize(width: widths[c], height: nil))
                    cell.place(_align(cell, s, in: CGRect(x: x, y: y, width: widths[c], height: h), alignment))
                    x += widths[c] + hSpacing
                }
            }
            y += h + vSpacing
        }
    }
    override func emit(_ list: _DisplayList) { rows.flatMap { $0 }.forEach { $0.emit(list) } }
}

public struct Grid<Content: View>: View, _PrimitiveView {
    let alignment: Alignment
    let horizontalSpacing: CGFloat?
    let verticalSpacing: CGFloat?
    let content: Content
    public init(alignment: Alignment = .center, horizontalSpacing: CGFloat? = nil, verticalSpacing: CGFloat? = nil, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.horizontalSpacing = horizontalSpacing
        self.verticalSpacing = verticalSpacing
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        var rows: [[_Node]] = [], spanning: [Bool] = []
        for node in _resolveView(content, ctx.with { $0._stackAxis = .vertical }) {
            if let row = node as? _GridRowMarker { rows.append(row.cells); spanning.append(false) } else { rows.append([node]); spanning.append(true) }
        }
        return [_GridNode(path: ctx.path, env: ctx.environment, rows: rows, spanning: spanning, alignment: alignment,
                          hSpacing: horizontalSpacing ?? 8, vSpacing: verticalSpacing ?? 8)]
    }
}

extension View {
    public func gridCellColumns(_ count: Int) -> some View { self }
    public func gridCellAnchor(_ anchor: UnitPoint) -> some View { self }
    public func gridCellUnsizedAxes(_ axes: Axis.Set) -> some View { self }
    public func gridColumnAlignment(_ guide: HorizontalAlignment) -> some View { self }
}

// MARK: - TextEditor

/// UITextView as TextEditor shows it, measured: body text inset 5 points from the sides and 8 from the top, filling
/// the space offered. A tap edits it (the IDE's text dialog, as for a TextField).
public struct TextEditor: View, _PrimitiveView {
    let text: Binding<String>
    public init(text: Binding<String>) { self.text = text }
    public init(text: Binding<String>, selection: Binding<Any?>) { self.text = text }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let text = self.text
        let inner = _resolveView(Text(verbatim: text.wrappedValue).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(EdgeInsets(top: 8, leading: 5, bottom: 8, trailing: 5)), ctx.child("text").with { $0._stackAxis = nil })
        let setter = ctx.graph.register(setter: { text.wrappedValue = $0 })
        return inner.map { _FieldNode(child: $0, setter: setter, text: text.wrappedValue, secure: false) }
    }
}

public protocol TextEditorStyle {}
public struct PlainTextEditorStyle: TextEditorStyle { public init() {} }
public struct AutomaticTextEditorStyle: TextEditorStyle { public init() {} }
extension TextEditorStyle where Self == PlainTextEditorStyle { public static var plain: PlainTextEditorStyle { PlainTextEditorStyle() } }
extension TextEditorStyle where Self == AutomaticTextEditorStyle { public static var automatic: AutomaticTextEditorStyle { AutomaticTextEditorStyle() } }

// MARK: - DatePicker

public struct DatePickerComponents: OptionSet, Sendable {
    public let rawValue: UInt
    public init(rawValue: UInt) { self.rawValue = rawValue }
    public static let hourAndMinute = DatePickerComponents(rawValue: 1)
    public static let date = DatePickerComponents(rawValue: 2)
}

/// iOS's compact DatePicker, measured: the date ("Nov 14, 2023") and/or the time ("10:13 PM") at body size, each on a
/// capsule of tertiary fill padded 12 x 7, trailing in the row.
public struct DatePicker<Label: View>: View {
    public typealias Components = DatePickerComponents
    let label: Label
    let selection: Binding<Date>
    let components: DatePickerComponents
    @Environment(\.self) private var environment

    public init(selection: Binding<Date>, displayedComponents: Components = [.hourAndMinute, .date], @ViewBuilder label: () -> Label) {
        self.selection = selection
        components = displayedComponents
        self.label = label()
    }
    public init(selection: Binding<Date>, in range: ClosedRange<Date>, displayedComponents: Components = [.hourAndMinute, .date], @ViewBuilder label: () -> Label) {
        self.init(selection: selection, displayedComponents: displayedComponents, label: label)
    }
    public init(selection: Binding<Date>, in range: PartialRangeFrom<Date>, displayedComponents: Components = [.hourAndMinute, .date], @ViewBuilder label: () -> Label) {
        self.init(selection: selection, displayedComponents: displayedComponents, label: label)
    }
    public init(selection: Binding<Date>, in range: PartialRangeThrough<Date>, displayedComponents: Components = [.hourAndMinute, .date], @ViewBuilder label: () -> Label) {
        self.init(selection: selection, displayedComponents: displayedComponents, label: label)
    }

    private func format(date: Bool) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.timeZone = environment.timeZone
        f.dateStyle = date ? .medium : .none
        f.timeStyle = date ? .none : .short
        return f.string(from: selection.wrappedValue).replacingOccurrences(of: "\u{202F}", with: " ")
    }

    public var body: some View {
        HStack {
            label
            Spacer()
            HStack(spacing: 8) {
                if components.contains(.date) { pill(format(date: true)) }
                if components.contains(.hourAndMinute) { pill(format(date: false)) }
            }
            // In a list the row grows around the pills: rows measured 64.67 tall where a text row is 52.
            .padding(.vertical, environment[_InListKey.self] ? 4 + 1.0 / 6 : 0)
        }
    }
    private func pill(_ text: String) -> some View {
        Text(verbatim: text)
            .foregroundStyle(Color.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Capsule().fill(Color.tertiarySystemFill))
    }
}

extension DatePicker where Label == Text {
    public init(_ titleKey: LocalizedStringKey, selection: Binding<Date>, displayedComponents: Components = [.hourAndMinute, .date]) {
        self.init(selection: selection, displayedComponents: displayedComponents) { Text(titleKey) }
    }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, selection: Binding<Date>, displayedComponents: Components = [.hourAndMinute, .date]) {
        self.init(selection: selection, displayedComponents: displayedComponents) { Text(title) }
    }
    public init(_ titleKey: LocalizedStringKey, selection: Binding<Date>, in range: ClosedRange<Date>, displayedComponents: Components = [.hourAndMinute, .date]) {
        self.init(selection: selection, displayedComponents: displayedComponents) { Text(titleKey) }
    }
    public init(_ titleKey: LocalizedStringKey, selection: Binding<Date>, in range: PartialRangeFrom<Date>, displayedComponents: Components = [.hourAndMinute, .date]) {
        self.init(selection: selection, displayedComponents: displayedComponents) { Text(titleKey) }
    }
    public init(_ titleKey: LocalizedStringKey, selection: Binding<Date>, in range: PartialRangeThrough<Date>, displayedComponents: Components = [.hourAndMinute, .date]) {
        self.init(selection: selection, displayedComponents: displayedComponents) { Text(titleKey) }
    }
}

public protocol DatePickerStyle {}
public struct CompactDatePickerStyle: DatePickerStyle { public init() {} }
public struct GraphicalDatePickerStyle: DatePickerStyle { public init() {} }
public struct WheelDatePickerStyle: DatePickerStyle { public init() {} }
public struct DefaultDatePickerStyle: DatePickerStyle { public init() {} }
extension DatePickerStyle where Self == CompactDatePickerStyle { public static var compact: CompactDatePickerStyle { CompactDatePickerStyle() } }
extension DatePickerStyle where Self == GraphicalDatePickerStyle { public static var graphical: GraphicalDatePickerStyle { GraphicalDatePickerStyle() } }
extension DatePickerStyle where Self == WheelDatePickerStyle { public static var wheel: WheelDatePickerStyle { WheelDatePickerStyle() } }
extension DatePickerStyle where Self == DefaultDatePickerStyle { public static var automatic: DefaultDatePickerStyle { DefaultDatePickerStyle() } }

public struct MultiDatePicker<Label: View>: View {
    let label: Label
    public init(selection: Binding<Set<DateComponents>>, @ViewBuilder label: () -> Label) { self.label = label() }
    public var body: some View { label }
}
extension MultiDatePicker where Label == Text {
    public init(_ titleKey: LocalizedStringKey, selection: Binding<Set<DateComponents>>) { self.init(selection: selection) { Text(titleKey) } }
}

// MARK: - System views

/// iOS's empty-state view, measured: the symbol (secondary), the title in title2 bold, the description in the
/// secondary colour, stacked and centred in the space offered.
public struct ContentUnavailableView<Label: View, Description: View, Actions: View>: View {
    let label: Label
    let description: Description
    let actions: Actions
    public init(@ViewBuilder label: () -> Label, @ViewBuilder description: () -> Description = { EmptyView() }, @ViewBuilder actions: () -> Actions = { EmptyView() }) {
        self.label = label()
        self.description = description()
        self.actions = actions()
    }
    public var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 2 + 2.0 / 3) {
                label.labelStyle(_UnavailableLabelStyle.shared)
                description.font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            actions
        }
        .padding(.horizontal, 32)
        // iOS sets the block 5 points above the centre.
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The label of a ContentUnavailableView: icon above title. A marker the Label body reads.
struct _UnavailableLabelStyle: LabelStyle { static let shared = _UnavailableLabelStyle() }

extension ContentUnavailableView where Label == SwiftUILabel<Text, Image>, Description == Text?, Actions == EmptyView {
    public init(_ titleKey: LocalizedStringKey, systemImage name: String, description: Text? = nil) {
        self.init(label: { SwiftUILabel(titleKey, systemImage: name) }, description: { description })
    }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, systemImage name: String, description: Text? = nil) {
        self.init(label: { SwiftUILabel(title, systemImage: name) }, description: { description })
    }
    public static var search: ContentUnavailableView<SwiftUILabel<Text, Image>, Text?, EmptyView> {
        ContentUnavailableView("No Results", systemImage: "magnifyingglass", description: Text("Check the spelling or try a new search."))
    }
    public static func search(text: String) -> ContentUnavailableView<SwiftUILabel<Text, Image>, Text?, EmptyView> {
        ContentUnavailableView("No Results for \u{201C}\(text)\u{201D}", systemImage: "magnifyingglass", description: Text("Check the spelling or try a new search."))
    }
}

/// A row's label and its value, the value trailing in the secondary colour.
public struct LabeledContent<Label: View, Content: View>: View {
    let label: Label
    let content: Content
    public init(@ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label) {
        self.content = content()
        self.label = label()
    }
    public var body: some View {
        HStack {
            label
            Spacer()
            content.foregroundStyle(.secondary)
        }
    }
}

extension LabeledContent where Label == Text {
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder content: () -> Content) { self.init(content: content) { Text(titleKey) } }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) { self.init(content: content) { Text(title) } }
}
extension LabeledContent where Label == Text, Content == Text {
    @_disfavoredOverload
    public init<S1: StringProtocol, S2: StringProtocol>(_ title: S1, value: S2) { self.init(content: { Text(value) }) { Text(title) } }
    public init<S: StringProtocol>(_ titleKey: LocalizedStringKey, value: S) { self.init(content: { Text(value) }) { Text(titleKey) } }
    public init<F: FormatStyle>(_ titleKey: LocalizedStringKey, value: F.FormatInput, format: F) where F.FormatOutput == String {
        self.init(content: { Text(format.format(value)) }) { Text(titleKey) }
    }
}

/// GroupBox, measured: the label (headline) leading over the content, padded 16 inside a secondary-system-background
/// rounded rectangle (continuous, radius 8 from iOS 26's look).
public struct GroupBox<Label: View, Content: View>: View {
    let label: Label
    let content: Content
    public init(@ViewBuilder content: () -> Content, @ViewBuilder label: () -> Label) {
        self.content = content()
        self.label = label()
    }
    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            label.font(.headline).frame(maxWidth: .infinity, alignment: .leading)
            VStack { content }.frame(maxWidth: .infinity)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondarySystemBackground))
    }
}
extension GroupBox where Label == EmptyView {
    public init(@ViewBuilder content: () -> Content) { self.init(content: content) { EmptyView() } }
}
extension GroupBox where Label == Text {
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder content: () -> Content) { self.init(content: content) { Text(titleKey) } }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) { self.init(content: content) { Text(title) } }
}

/// DisclosureGroup: a row with a chevron (down when open, right when closed) that a tap toggles; in a List its content
/// rows follow it, indented.
public struct DisclosureGroup<Label: View, Content: View>: View, _PrimitiveView {
    let label: Label
    let content: Content
    let isExpanded: Binding<Bool>?
    public init(@ViewBuilder content: @escaping () -> Content, @ViewBuilder label: () -> Label) {
        self.content = content()
        self.label = label()
        isExpanded = nil
    }
    public init(isExpanded: Binding<Bool>, @ViewBuilder content: @escaping () -> Content, @ViewBuilder label: () -> Label) {
        self.content = content()
        self.label = label()
        self.isExpanded = isExpanded
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let cell = ctx.graph.cell(ctx.path + "/expanded") { false }
        let open = isExpanded?.wrappedValue ?? cell.value
        let binding = isExpanded
        let toggle = { if let binding { binding.wrappedValue.toggle() } else { cell.value.toggle() } }
        let header = _resolveView(HStack {
            label
            Spacer()
            Image(systemName: open ? "chevron.down" : "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Color.primary)
        }, ctx.child("header")).map { _TapNode(child: $0, action: toggle) }
        guard open else { return header }
        let rows = _resolveView(content, ctx.child("content")).map {
            _PaddingNode(child: $0, insets: EdgeInsets(top: 0, leading: 12, bottom: 0, trailing: 0)) as _Node
        }
        if ctx.environment[_InListKey.self] { return header + rows }
        return [_StackNode(path: ctx.path, env: ctx.environment, axis: .vertical, alignment: .leading, spacing: nil, children: header + rows)]
    }
}
extension DisclosureGroup where Label == Text {
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder content: @escaping () -> Content) { self.init(content: content) { Text(titleKey) } }
    public init(_ titleKey: LocalizedStringKey, isExpanded: Binding<Bool>, @ViewBuilder content: @escaping () -> Content) {
        self.init(isExpanded: isExpanded, content: content) { Text(titleKey) }
    }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, @ViewBuilder content: @escaping () -> Content) { self.init(content: content) { Text(title) } }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, isExpanded: Binding<Bool>, @ViewBuilder content: @escaping () -> Content) {
        self.init(isExpanded: isExpanded, content: content) { Text(title) }
    }
}

/// ViewThatFits: the first of its views whose ideal size fits what is offered (in the given axes), else the last.
public struct ViewThatFits<Content: View>: View, _PrimitiveView {
    let axes: Axis.Set
    let content: Content
    public init(in axes: Axis.Set = [.horizontal, .vertical], @ViewBuilder content: () -> Content) {
        self.axes = axes
        self.content = content()
    }
    public var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        [_FitsNode(path: ctx.path, env: ctx.environment, axes: axes, children: _resolveView(content, ctx.with { $0._stackAxis = nil }))]
    }
}

@MainActor
final class _FitsNode: _Node {
    let axes: Axis.Set
    let children: [_Node]
    private var chosen: _Node?
    init(path: String, env: EnvironmentValues, axes: Axis.Set, children: [_Node]) {
        self.axes = axes
        self.children = children
        super.init(path: path, env: env)
    }
    private func pick(_ p: ProposedViewSize) -> _Node? {
        for child in children {
            let ideal = child.sizeThatFits(.unspecified)
            let fitsW = !axes.contains(.horizontal) || p.width == nil || ideal.width <= p.width! + 0.01
            let fitsH = !axes.contains(.vertical) || p.height == nil || ideal.height <= p.height! + 0.01
            if fitsW && fitsH { return child }
        }
        return children.last
    }
    override func _sizeThatFits(_ proposal: ProposedViewSize) -> CGSize {
        chosen = pick(proposal)
        return chosen?.sizeThatFits(proposal) ?? .zero
    }
    override func place(_ rect: CGRect) {
        frame = rect
        chosen = pick(ProposedViewSize(rect.size))
        chosen?.place(rect)
    }
    override func emit(_ list: _DisplayList) { chosen?.emit(list) }
}

/// iOS's default (linear capacity) Gauge, measured: the label centred above a capsule track, filled in
/// the tint to the value's fraction; the current value label centred below. Bar 16 tall, 10.67 below the label,
/// the value label 6.67 below it.
public struct Gauge<Label: View, CurrentValueLabel: View, BoundsLabel: View, MarkedValueLabels: View>: View {
    let fraction: Double
    let label: Label
    let current: CurrentValueLabel
    public var body: some View {
        VStack(spacing: 0) {
            label.padding(.bottom, 10 + 2.0 / 3)
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.tertiarySystemFill)
                    Capsule().fill(TintShapeStyle()).frame(width: proxy.size.width * min(1, max(0, fraction)))
                }
            }
            .frame(height: 16)
            current.padding(.top, 6 + 2.0 / 3)
        }
    }
}
extension Gauge where CurrentValueLabel == EmptyView, BoundsLabel == EmptyView, MarkedValueLabels == EmptyView {
    public init<V: BinaryFloatingPoint>(value: V, in bounds: ClosedRange<V> = 0...1, @ViewBuilder label: () -> Label) {
        fraction = Double((value - bounds.lowerBound) / max(bounds.upperBound - bounds.lowerBound, V.ulpOfOne))
        self.label = label()
        current = EmptyView()
    }
}
extension Gauge where BoundsLabel == EmptyView, MarkedValueLabels == EmptyView {
    public init<V: BinaryFloatingPoint>(value: V, in bounds: ClosedRange<V> = 0...1, @ViewBuilder label: () -> Label,
                                        @ViewBuilder currentValueLabel: () -> CurrentValueLabel) {
        fraction = Double((value - bounds.lowerBound) / max(bounds.upperBound - bounds.lowerBound, V.ulpOfOne))
        self.label = label()
        current = currentValueLabel()
    }
}
extension Gauge where MarkedValueLabels == EmptyView {
    public init<V: BinaryFloatingPoint>(value: V, in bounds: ClosedRange<V> = 0...1, @ViewBuilder label: () -> Label,
                                        @ViewBuilder currentValueLabel: () -> CurrentValueLabel,
                                        @ViewBuilder minimumValueLabel: () -> BoundsLabel, @ViewBuilder maximumValueLabel: () -> BoundsLabel) {
        fraction = Double((value - bounds.lowerBound) / max(bounds.upperBound - bounds.lowerBound, V.ulpOfOne))
        self.label = label()
        current = currentValueLabel()
    }
}
public protocol GaugeStyle {}
public struct LinearCapacityGaugeStyle: GaugeStyle { public init() {} }
public struct AccessoryCircularGaugeStyle: GaugeStyle { public init() {} }
public struct AccessoryLinearGaugeStyle: GaugeStyle { public init() {} }
public struct AccessoryCircularCapacityGaugeStyle: GaugeStyle { public init() {} }
extension GaugeStyle where Self == LinearCapacityGaugeStyle { public static var linearCapacity: LinearCapacityGaugeStyle { LinearCapacityGaugeStyle() } }
extension GaugeStyle where Self == AccessoryCircularGaugeStyle { public static var accessoryCircular: AccessoryCircularGaugeStyle { AccessoryCircularGaugeStyle() } }
extension GaugeStyle where Self == AccessoryLinearGaugeStyle { public static var accessoryLinear: AccessoryLinearGaugeStyle { AccessoryLinearGaugeStyle() } }
extension GaugeStyle where Self == AccessoryCircularCapacityGaugeStyle { public static var accessoryCircularCapacity: AccessoryCircularCapacityGaugeStyle { AccessoryCircularCapacityGaugeStyle() } }

/// What ShareLink shares; on iOS, CoreTransferable's protocol.
public protocol Transferable {}
extension URL: Transferable {}
extension String: Transferable {}
extension Data: Transferable {}

/// ShareLink: a borderless button, "Share…" with square.and.arrow.up unless given a label.
public struct ShareLink<Label: View>: View {
    let label: Label
    public init<I: Transferable>(item: I, subject: Text? = nil, message: Text? = nil, @ViewBuilder label: () -> Label) { self.label = label() }
    public init<I: Transferable>(items: [I], subject: Text? = nil, message: Text? = nil, @ViewBuilder label: () -> Label) { self.label = label() }
    public var body: some View { Button(action: {}) { label } }
}
extension ShareLink where Label == SwiftUILabel<Text, Image> {
    public init<I: Transferable>(item: I, subject: Text? = nil, message: Text? = nil) {
        label = SwiftUILabel("Share…", systemImage: "square.and.arrow.up")
    }
    public init<I: Transferable>(_ titleKey: LocalizedStringKey, item: I, subject: Text? = nil, message: Text? = nil) {
        label = SwiftUILabel(titleKey, systemImage: "square.and.arrow.up")
    }
    public init<I: Transferable>(items: [I], subject: Text? = nil, message: Text? = nil) {
        label = SwiftUILabel("Share…", systemImage: "square.and.arrow.up")
    }
}
public struct SharePreview<Image, Icon> {
    public init(_ titleKey: LocalizedStringKey) where Image == Never, Icon == Never {}
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S) where Image == Never, Icon == Never {}
}

/// ColorPicker, measured from UIColorWell: a 28-point well trailing in the row, the colour in an 18-point circle inside
/// a white ring inside a rainbow ring.
public struct ColorPicker<Label: View>: View {
    let label: Label
    let color: Color
    public init(selection: Binding<Color>, supportsOpacity: Bool = true, @ViewBuilder label: () -> Label) {
        self.label = label()
        color = selection.wrappedValue
    }
    public var body: some View {
        HStack {
            label
            Spacer()
            ZStack {
                Circle().fill(AngularGradient(colors: [.red, .yellow, .green, .cyan, .blue, .purple, .red], center: .center))
                Circle().fill(Color.white).frame(width: 22, height: 22)
                Circle().fill(color).frame(width: 18, height: 18)
            }
            .frame(width: 28, height: 28)
        }
    }
}
extension ColorPicker where Label == Text {
    public init(_ titleKey: LocalizedStringKey, selection: Binding<Color>, supportsOpacity: Bool = true) {
        self.init(selection: selection, supportsOpacity: supportsOpacity) { Text(titleKey) }
    }
    @_disfavoredOverload
    public init<S: StringProtocol>(_ title: S, selection: Binding<Color>, supportsOpacity: Bool = true) {
        self.init(selection: selection, supportsOpacity: supportsOpacity) { Text(title) }
    }
}

/// ControlGroup, as iOS draws it in content: its buttons side by side sharing a capsule of tertiary fill, labels in
/// the primary colour at footnote size.
public struct ControlGroup<Content: View>: View {
    let content: Content
    public init(@ViewBuilder content: () -> Content) { self.content = content() }
    public var body: some View {
        _ControlGroupRow(content: content)
            .font(.footnote)
            .buttonStyle(.plain)
            .frame(minHeight: 34)
            .background(Capsule().fill(Color.tertiarySystemFill))
    }
}
struct _ControlGroupRow<Content: View>: View, _PrimitiveView {
    let content: Content
    var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let buttons = _resolveView(content, ctx.with { $0._stackAxis = .horizontal; $0._foreground = AnyShapeStyle(Color.primary) })
        let cells = buttons.map { _FlexFrameNode(child: $0, minWidth: nil, idealWidth: nil, maxWidth: .infinity, minHeight: nil, idealHeight: nil, maxHeight: nil, alignment: .center) as _Node }
        return [_StackNode(path: ctx.path, env: ctx.environment, axis: .horizontal, alignment: .center, spacing: 0, children: cells)]
    }
}
extension ControlGroup {
    public init<C: View, L: View>(@ViewBuilder content: () -> C, @ViewBuilder label: () -> L) where Content == C { self.content = content() }
}
public protocol ControlGroupStyle {}
public struct AutomaticControlGroupStyle: ControlGroupStyle { public init() {} }
public struct NavigationControlGroupStyle: ControlGroupStyle { public init() {} }
public struct MenuControlGroupStyle: ControlGroupStyle { public init() {} }
public struct CompactMenuControlGroupStyle: ControlGroupStyle { public init() {} }
public struct PaletteControlGroupStyle: ControlGroupStyle { public init() {} }
extension ControlGroupStyle where Self == AutomaticControlGroupStyle { public static var automatic: AutomaticControlGroupStyle { AutomaticControlGroupStyle() } }
extension ControlGroupStyle where Self == NavigationControlGroupStyle { public static var navigation: NavigationControlGroupStyle { NavigationControlGroupStyle() } }
extension ControlGroupStyle where Self == MenuControlGroupStyle { public static var menu: MenuControlGroupStyle { MenuControlGroupStyle() } }
extension ControlGroupStyle where Self == CompactMenuControlGroupStyle { public static var compactMenu: CompactMenuControlGroupStyle { CompactMenuControlGroupStyle() } }
extension ControlGroupStyle where Self == PaletteControlGroupStyle { public static var palette: PaletteControlGroupStyle { PaletteControlGroupStyle() } }

/// EditButton: "Edit", toggling the environment's edit mode when it has one ("Done" while editing).
public struct EditButton: View {
    @Environment(\.editMode) private var editMode
    public init() {}
    public var body: some View {
        Button(editMode?.wrappedValue.isEditing == true ? "Done" : "Edit") {
            if let editMode { editMode.wrappedValue = editMode.wrappedValue.isEditing ? .inactive : .active }
        }
    }
}

public struct PasteButton: View {
    public init<T: Transferable>(payloadType: T.Type, onPaste: @escaping ([T]) -> Void) {}
    public var body: some View { Button(action: {}) { SwiftUILabel("Paste", systemImage: "doc.on.clipboard") } }
}

public struct RenameButton<Label: View>: View {
    let label: Label
    public var body: some View { Button(action: {}) { label } }
}
extension RenameButton where Label == SwiftUILabel<Text, Image> {
    public init() { label = SwiftUILabel("Rename", systemImage: "pencil") }
}

// MARK: - AsyncImage

public enum AsyncImagePhase: Sendable {
    case empty
    case success(Image)
    case failure(Error)
    public var image: Image? { if case .success(let i) = self { return i }; return nil }
    public var error: Error? { if case .failure(let e) = self { return e }; return nil }
}

/// AsyncImage: previews do not fetch from the network, so it shows its placeholder (its empty phase), the way iOS
/// shows it until an image arrives.
public struct AsyncImage<Content: View>: View {
    let content: Content
    public init(url: URL?, scale: CGFloat = 1) where Content == _AsyncImagePlaceholder {
        content = _AsyncImagePlaceholder()
    }
    public init<I: View, P: View>(url: URL?, scale: CGFloat = 1, @ViewBuilder content: @escaping (Image) -> I,
                                  @ViewBuilder placeholder: @escaping () -> P) where Content == P {
        self.content = placeholder()
    }
    public init(url: URL?, scale: CGFloat = 1, transaction: Transaction = Transaction(), @ViewBuilder content: @escaping (AsyncImagePhase) -> Content) {
        self.content = content(.empty)
    }
    public var body: some View { content }
}
public struct _AsyncImagePlaceholder: View {
    public var body: some View { Color.secondarySystemFill }
}

// MARK: - NavigationSplitView

public struct NavigationSplitViewVisibility: Equatable, Sendable {
    let id: Int
    public static let automatic = NavigationSplitViewVisibility(id: 0), all = NavigationSplitViewVisibility(id: 1)
    public static let doubleColumn = NavigationSplitViewVisibility(id: 2), detailOnly = NavigationSplitViewVisibility(id: 3)
}
public enum NavigationSplitViewColumn: Hashable, Sendable { case sidebar, content, detail }

/// On an iPhone a split view collapses to a stack that starts at its sidebar.
public struct NavigationSplitView<Sidebar: View, Content: View, Detail: View>: View {
    let sidebar: Sidebar
    public init(@ViewBuilder sidebar: () -> Sidebar, @ViewBuilder content: () -> Content, @ViewBuilder detail: () -> Detail) { self.sidebar = sidebar() }
    public init(columnVisibility: Binding<NavigationSplitViewVisibility>, @ViewBuilder sidebar: () -> Sidebar, @ViewBuilder content: () -> Content,
                @ViewBuilder detail: () -> Detail) { self.sidebar = sidebar() }
    public init(preferredCompactColumn: Binding<NavigationSplitViewColumn>, @ViewBuilder sidebar: () -> Sidebar, @ViewBuilder content: () -> Content,
                @ViewBuilder detail: () -> Detail) { self.sidebar = sidebar() }
    public var body: some View { NavigationStack { sidebar } }
}
extension NavigationSplitView where Content == EmptyView {
    public init(@ViewBuilder sidebar: () -> Sidebar, @ViewBuilder detail: () -> Detail) { self.sidebar = sidebar() }
    public init(columnVisibility: Binding<NavigationSplitViewVisibility>, @ViewBuilder sidebar: () -> Sidebar, @ViewBuilder detail: () -> Detail) {
        self.sidebar = sidebar()
    }
    public init(preferredCompactColumn: Binding<NavigationSplitViewColumn>, @ViewBuilder sidebar: () -> Sidebar, @ViewBuilder detail: () -> Detail) {
        self.sidebar = sidebar()
    }
}
public protocol NavigationSplitViewStyle {}
public struct AutomaticNavigationSplitViewStyle: NavigationSplitViewStyle { public init() {} }
public struct BalancedNavigationSplitViewStyle: NavigationSplitViewStyle { public init() {} }
public struct ProminentDetailNavigationSplitViewStyle: NavigationSplitViewStyle { public init() {} }
extension NavigationSplitViewStyle where Self == AutomaticNavigationSplitViewStyle { public static var automatic: AutomaticNavigationSplitViewStyle { AutomaticNavigationSplitViewStyle() } }
extension NavigationSplitViewStyle where Self == BalancedNavigationSplitViewStyle { public static var balanced: BalancedNavigationSplitViewStyle { BalancedNavigationSplitViewStyle() } }
extension NavigationSplitViewStyle where Self == ProminentDetailNavigationSplitViewStyle { public static var prominentDetail: ProminentDetailNavigationSplitViewStyle { ProminentDetailNavigationSplitViewStyle() } }

// MARK: - Time-driven views

/// TimelineView: drawn once, for now (previews are still pictures between interactions).
public struct TimelineView<Schedule: TimelineSchedule, Content: View>: View {
    public struct Context {
        public enum Cadence: Comparable, Sendable { case live, seconds, minutes }
        public let date: Date
        public let cadence: Cadence
    }
    let content: (Context) -> Content
    public init(_ schedule: Schedule, @ViewBuilder content: @escaping (Context) -> Content) { self.content = content }
    public var body: some View { content(Context(date: Date(), cadence: .live)) }
}
public protocol TimelineSchedule {}
public struct PeriodicTimelineSchedule: TimelineSchedule {}
public struct EveryMinuteTimelineSchedule: TimelineSchedule {}
public struct AnimationTimelineSchedule: TimelineSchedule {}
public struct ExplicitTimelineSchedule<Entries: Sequence>: TimelineSchedule where Entries.Element == Date {}
extension TimelineSchedule where Self == PeriodicTimelineSchedule {
    public static func periodic(from startDate: Date, by interval: TimeInterval) -> PeriodicTimelineSchedule { PeriodicTimelineSchedule() }
}
extension TimelineSchedule where Self == EveryMinuteTimelineSchedule { public static var everyMinute: EveryMinuteTimelineSchedule { EveryMinuteTimelineSchedule() } }
extension TimelineSchedule where Self == AnimationTimelineSchedule {
    public static var animation: AnimationTimelineSchedule { AnimationTimelineSchedule() }
    public static func animation(minimumInterval: Double? = nil, paused: Bool = false) -> AnimationTimelineSchedule { AnimationTimelineSchedule() }
}
extension TimelineSchedule {
    public static func explicit<S: Sequence>(_ dates: S) -> ExplicitTimelineSchedule<S> where Self == ExplicitTimelineSchedule<S> { ExplicitTimelineSchedule() }
}

/// PhaseAnimator: drawn in its first phase.
public struct PhaseAnimator<Phase: Equatable, Content: View>: View {
    let first: Phase?
    let content: (Phase) -> Content
    public init<S: Sequence>(_ phases: S, trigger: some Equatable, @ViewBuilder content: @escaping (Phase) -> Content,
                             animation: @escaping (Phase) -> Animation? = { _ in .default }) where S.Element == Phase {
        first = phases.first(where: { _ in true })
        self.content = content
    }
    public init<S: Sequence>(_ phases: S, @ViewBuilder content: @escaping (Phase) -> Content,
                             animation: @escaping (Phase) -> Animation? = { _ in .default }) where S.Element == Phase {
        first = phases.first(where: { _ in true })
        self.content = content
    }
    public var body: some View { if let first { content(first) } }
}
extension View {
    public func phaseAnimator<Phase: Equatable>(_ phases: some Sequence<Phase>, trigger: some Equatable,
                                                @ViewBuilder content: @escaping (PlaceholderContentView<Self>, Phase) -> some View,
                                                animation: @escaping (Phase) -> Animation? = { _ in .default }) -> some View {
        let first = phases.first(where: { _ in true })
        return Group { if let first { content(PlaceholderContentView(content: self), first) } else { self } }
    }
    public func phaseAnimator<Phase: Equatable>(_ phases: some Sequence<Phase>,
                                                @ViewBuilder content: @escaping (PlaceholderContentView<Self>, Phase) -> some View,
                                                animation: @escaping (Phase) -> Animation? = { _ in .default }) -> some View {
        let first = phases.first(where: { _ in true })
        return Group { if let first { content(PlaceholderContentView(content: self), first) } else { self } }
    }
}
/// The view a phaseAnimator or keyframeAnimator modifier hands its content closure.
public struct PlaceholderContentView<Value: View>: View {
    let content: Value
    public var body: some View { content }
}

/// KeyframeAnimator: drawn at its initial value.
public struct KeyframeAnimator<Value, KeyframePath, Content: View>: View {
    let initial: Value
    let content: (Value) -> Content
    public init(initialValue: Value, trigger: some Equatable, @ViewBuilder content: @escaping (Value) -> Content,
                @_KeyframesBuilder keyframes: @escaping (Value) -> KeyframePath) {
        initial = initialValue
        self.content = content
    }
    public init(initialValue: Value, repeating: Bool = true, @ViewBuilder content: @escaping (Value) -> Content,
                @_KeyframesBuilder keyframes: @escaping (Value) -> KeyframePath) {
        initial = initialValue
        self.content = content
    }
    public var body: some View { content(initial) }
}
@resultBuilder
public struct _KeyframesBuilder {
    public static func buildBlock<each K>(_ k: repeat each K) {}
    public static func buildExpression<K>(_ k: K) -> K { k }
}
extension View {
    public func keyframeAnimator<Value>(initialValue: Value, trigger: some Equatable,
                                        @ViewBuilder content: @escaping (PlaceholderContentView<Self>, Value) -> some View,
                                        @_KeyframesBuilder keyframes: @escaping (Value) -> some Any) -> some View {
        content(PlaceholderContentView(content: self), initialValue)
    }
    public func keyframeAnimator<Value>(initialValue: Value, repeating: Bool = true,
                                        @ViewBuilder content: @escaping (PlaceholderContentView<Self>, Value) -> some View,
                                        @_KeyframesBuilder keyframes: @escaping (Value) -> some Any) -> some View {
        content(PlaceholderContentView(content: self), initialValue)
    }
}
public struct KeyframeTrack<Root, Value, Content> {
    public init(_ keyPath: WritableKeyPath<Root, Value>, @_KeyframesBuilder content: () -> Content) {}
    public init(@_KeyframesBuilder content: () -> Content) where Root == Value {}
}
public struct LinearKeyframe<Value> { public init(_ to: Value, duration: TimeInterval, timingCurve: UnitCurve = .linear) {} }
public struct SpringKeyframe<Value> { public init(_ to: Value, duration: TimeInterval? = nil, spring: Spring = Spring(), startVelocity: Value? = nil) {} }
public struct CubicKeyframe<Value> { public init(_ to: Value, duration: TimeInterval, startVelocity: Value? = nil, endVelocity: Value? = nil) {} }
public struct MoveKeyframe<Value> { public init(_ to: Value) {} }
public struct UnitCurve: Sendable {
    public static let linear = UnitCurve(), easeIn = UnitCurve(), easeOut = UnitCurve(), easeInOut = UnitCurve()
    public static func bezier(startControlPoint: UnitPoint, endControlPoint: UnitPoint) -> UnitCurve { UnitCurve() }
}
public struct Spring: Sendable {
    public init(duration: TimeInterval = 0.5, bounce: Double = 0) {}
    public init(response: Double, dampingRatio: Double) {}
    public init(mass: Double = 1, stiffness: Double, damping: Double, allowOverDamping: Bool = false) {}
    public static let smooth = Spring(), snappy = Spring(), bouncy = Spring()
}
