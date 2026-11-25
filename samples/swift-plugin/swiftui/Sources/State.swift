// View state: the graph that keeps @State storage alive between renders (keyed by a view's identity, its path in the
// tree), and the property wrappers that read and write it: State, Binding, StateObject, ObservedObject, Environment,
// EnvironmentObject.

import Foundation

// MARK: - Graph

/// What survives from one render to the next: state storage by identity, and the actions the last render registered
/// (taps, text edits), which the host addresses by number.
@MainActor
final class _Graph {
    static let shared = _Graph()

    var cells: [String: AnyObject] = [:]
    /// Identities seen in the render in progress; storage of identities that disappeared is dropped after it.
    var live: Set<String> = []
    var currentPath = ""
    var needsRender = true

    var actions: [Int: () -> Void] = [:]
    var setters: [Int: (String) -> Void] = [:]
    /// Sheets and covers the preview being rendered presents, in the order they were found.
    var presentations: [_PresentationRequest] = []
    /// How the preview being rendered changed from its last frame, for the host to animate: a screen pushed or popped,
    /// a sheet presented or dismissed (with its frame).
    var transition: (kind: String, rect: CGRect?)?
    private var nextAction = 0

    func beginRender() {
        live = []
        actions = [:]
        setters = [:]
        nextAction = 0
    }

    func endRender() {
        cells = cells.filter { live.contains($0.key) }
        needsRender = false
    }

    func register(_ action: @escaping () -> Void) -> Int {
        nextAction += 1
        actions[nextAction] = action
        return nextAction
    }

    func register(setter: @escaping (String) -> Void) -> Int {
        nextAction += 1
        setters[nextAction] = setter
        return nextAction
    }

    func cell<T>(_ key: String, initial: () -> T) -> _Cell<T> {
        live.insert(key)
        if let existing = cells[key] as? _Cell<T> { return existing }
        let created = _Cell(initial())
        cells[key] = created
        return created
    }

    func invalidate() { needsRender = true }
}

final class _Cell<T> {
    var value: T
    init(_ value: T) { self.value = value }
}

// MARK: - Dynamic properties

public protocol DynamicProperty {
    mutating func update()
}

extension DynamicProperty {
    public mutating func update() {}
}

/// The engine's side of a dynamic property: attached to its storage when the view owning it is about to be evaluated.
@MainActor
protocol _BindableProperty {
    func _bind(key: String, _ ctx: _ResolveContext)
}

/// Bind every dynamic property of [view] (found by reflection, including inside custom DynamicProperty structs) to
/// the storage of the view's identity.
@MainActor
func _bindDynamicProperties(of view: Any, _ ctx: _ResolveContext, prefix: String = "") {
    for (index, child) in Mirror(reflecting: view).children.enumerated() {
        let label = prefix + (child.label ?? "\(index)")
        if let bindable = child.value as? any _BindableProperty {
            bindable._bind(key: ctx.path + "#" + label, ctx)
        } else if child.value is any DynamicProperty {
            _bindDynamicProperties(of: child.value, ctx, prefix: label + ".")
        }
    }
}

// MARK: - State

final class _StateBox<Value> {
    let initial: Value
    var cell: _Cell<Value>?
    init(_ initial: Value) { self.initial = initial }

    var value: Value {
        get { cell?.value ?? initial }
        set {
            if let cell {
                cell.value = newValue
            } else {
                // Not attached yet (a view used outside of a render): keep it here.
                cell = _Cell(newValue)
            }
            MainActor.assumeIsolated { _Graph.shared.invalidate() }
        }
    }
}

@propertyWrapper
public struct State<Value>: DynamicProperty, _BindableProperty {
    let _box: _StateBox<Value>

    public init(wrappedValue value: Value) { _box = _StateBox(value) }
    public init(initialValue value: Value) { _box = _StateBox(value) }

    public var wrappedValue: Value {
        get { _box.value }
        nonmutating set { _box.value = newValue }
    }

    public var projectedValue: Binding<Value> {
        let box = _box
        return Binding(get: { box.value }, set: { box.value = $0 })
    }

    func _bind(key: String, _ ctx: _ResolveContext) {
        let box = _box
        box.cell = ctx.graph.cell(key) { box.cell?.value ?? box.initial }
    }
}

extension State where Value: ExpressibleByNilLiteral {
    public init() { self.init(wrappedValue: nil) }
}

// MARK: - Binding

@propertyWrapper @dynamicMemberLookup
public struct Binding<Value> {
    let _get: () -> Value
    let _set: (Value) -> Void

    public init(get: @escaping () -> Value, set: @escaping (Value) -> Void) {
        _get = get
        _set = set
    }

    public init(get: @escaping () -> Value, set: @escaping (Value, Transaction) -> Void) {
        _get = get
        _set = { set($0, Transaction()) }
    }

    public init(projectedValue: Binding<Value>) { self = projectedValue }
    public init(_ base: Binding<Value>) { self = base }

    public static func constant(_ value: Value) -> Binding<Value> {
        Binding(get: { value }, set: { _ in })
    }

    public var wrappedValue: Value {
        get { _get() }
        nonmutating set { _set(newValue) }
    }

    public var projectedValue: Binding<Value> { self }

    public subscript<Subject>(dynamicMember keyPath: WritableKeyPath<Value, Subject>) -> Binding<Subject> {
        let get = _get, set = _set
        return Binding<Subject>(
            get: { get()[keyPath: keyPath] },
            set: { newValue in
                var value = get()
                value[keyPath: keyPath] = newValue
                set(value)
            }
        )
    }

    public func transaction(_ transaction: Transaction) -> Binding<Value> { self }
    public func animation(_ animation: Animation? = .default) -> Binding<Value> { self }
}

extension Binding: DynamicProperty {}

extension Binding {
    public init<V>(_ base: Binding<V>) where Value == V? {
        self.init(get: { base.wrappedValue }, set: { if let v = $0 { base.wrappedValue = v } })
    }

    public init?(_ base: Binding<Value?>) {
        guard let value = base.wrappedValue else { return nil }
        var last = value
        self.init(get: { base.wrappedValue ?? last }, set: { last = $0; base.wrappedValue = $0 })
    }
}

extension Binding: Identifiable where Value: Identifiable {
    public var id: Value.ID { wrappedValue.id }
}

extension Binding: Sequence where Value: MutableCollection {
    public typealias Element = Binding<Value.Element>
    public typealias Iterator = IndexingIterator<Binding<Value>>
    public typealias SubSequence = Slice<Binding<Value>>
}

extension Binding: Collection where Value: MutableCollection {
    public typealias Index = Value.Index
    public typealias Indices = Value.Indices
    public var startIndex: Value.Index { wrappedValue.startIndex }
    public var endIndex: Value.Index { wrappedValue.endIndex }
    public var indices: Value.Indices { wrappedValue.indices }
    public func index(after i: Value.Index) -> Value.Index { wrappedValue.index(after: i) }
    public subscript(position: Value.Index) -> Binding<Value.Element> {
        let get = _get, set = _set
        return Binding<Value.Element>(
            get: { get()[position] },
            set: { var v = get(); v[position] = $0; set(v) }
        )
    }
}

extension Binding: BidirectionalCollection where Value: BidirectionalCollection, Value: MutableCollection {
    public func index(before i: Value.Index) -> Value.Index { wrappedValue.index(before: i) }
}

extension Binding: RandomAccessCollection where Value: MutableCollection, Value: RandomAccessCollection {}

// MARK: - Transactions and animation (accepted; previews render the end state)

public struct Transaction {
    public init() {}
    public init(animation: Animation?) {}
    public var animation: Animation?
    public var disablesAnimations = false
}

public struct Animation: Equatable, Sendable {
    let name: String
    public static let `default` = Animation(name: "default")
    public static let linear = Animation(name: "linear")
    public static let easeIn = Animation(name: "easeIn")
    public static let easeOut = Animation(name: "easeOut")
    public static let easeInOut = Animation(name: "easeInOut")
    public static let spring = Animation(name: "spring")
    public static let bouncy = Animation(name: "bouncy")
    public static let smooth = Animation(name: "smooth")
    public static let snappy = Animation(name: "snappy")
    public static func linear(duration: Double) -> Animation { .linear }
    public static func easeIn(duration: Double) -> Animation { .easeIn }
    public static func easeOut(duration: Double) -> Animation { .easeOut }
    public static func easeInOut(duration: Double) -> Animation { .easeInOut }
    public static func spring(response: Double = 0.5, dampingFraction: Double = 0.825, blendDuration: Double = 0) -> Animation { .spring }
    public static func spring(duration: Double = 0.5, bounce: Double = 0, blendDuration: Double = 0) -> Animation { .spring }
    public static func interactiveSpring(response: Double = 0.15, dampingFraction: Double = 0.86, blendDuration: Double = 0.25) -> Animation { .spring }
    public func delay(_ delay: Double) -> Animation { self }
    public func speed(_ speed: Double) -> Animation { self }
    public func repeatForever(autoreverses: Bool = true) -> Animation { self }
    public func repeatCount(_ repeatCount: Int, autoreverses: Bool = true) -> Animation { self }
}

@MainActor
public func withAnimation<Result>(_ animation: Animation? = .default, _ body: () throws -> Result) rethrows -> Result {
    try body()
}

@MainActor
public func withTransaction<Result>(_ transaction: Transaction, _ body: () throws -> Result) rethrows -> Result {
    try body()
}

// MARK: - Observable objects

/// A class whose changes re-render the views that use it. Without Combine on this platform, any change that goes
/// through @Published (or `objectWillChange.send()`) schedules a render of the whole preview.
public protocol ObservableObject: AnyObject {
    associatedtype ObjectWillChangePublisher = ObservableObjectPublisher
    var objectWillChange: ObservableObjectPublisher { get }
}

extension ObservableObject {
    public var objectWillChange: ObservableObjectPublisher { ObservableObjectPublisher() }
}

public final class ObservableObjectPublisher {
    public init() {}
    public func send() { MainActor.assumeIsolated { _Graph.shared.invalidate() } }
}

@propertyWrapper
public struct Published<Value> {
    final class Storage { var value: Value; init(_ v: Value) { value = v } }
    let storage: Storage

    public init(wrappedValue: Value) { storage = Storage(wrappedValue) }
    public init(initialValue: Value) { storage = Storage(initialValue) }

    public var wrappedValue: Value {
        get { storage.value }
        nonmutating set {
            storage.value = newValue
            MainActor.assumeIsolated { _Graph.shared.invalidate() }
        }
    }
}

@propertyWrapper
public struct StateObject<ObjectType: ObservableObject>: DynamicProperty, _BindableProperty {
    final class Box {
        let make: () -> ObjectType
        var cell: _Cell<ObjectType>?
        init(_ make: @escaping () -> ObjectType) { self.make = make }
        var object: ObjectType {
            if let cell { return cell.value }
            let created = _Cell(make())
            cell = created
            return created.value
        }
    }
    let box: Box

    public init(wrappedValue thunk: @autoclosure @escaping () -> ObjectType) { box = Box(thunk) }

    public var wrappedValue: ObjectType { box.object }
    public var projectedValue: ObservedObject<ObjectType>.Wrapper { ObservedObject.Wrapper(object: box.object) }

    func _bind(key: String, _ ctx: _ResolveContext) {
        let box = self.box
        box.cell = ctx.graph.cell(key) { box.cell?.value ?? box.make() }
    }
}

@propertyWrapper
public struct ObservedObject<ObjectType: ObservableObject>: DynamicProperty {
    @dynamicMemberLookup
    public struct Wrapper {
        let object: ObjectType
        public subscript<Subject>(dynamicMember keyPath: ReferenceWritableKeyPath<ObjectType, Subject>) -> Binding<Subject> {
            let object = self.object
            return Binding(get: { object[keyPath: keyPath] }, set: { object[keyPath: keyPath] = $0 })
        }
    }

    public var wrappedValue: ObjectType
    public init(wrappedValue: ObjectType) { self.wrappedValue = wrappedValue }
    public init(initialValue: ObjectType) { wrappedValue = initialValue }
    public var projectedValue: Wrapper { Wrapper(object: wrappedValue) }
}

@propertyWrapper
public struct EnvironmentObject<ObjectType: ObservableObject>: DynamicProperty, _BindableProperty {
    final class Box { var object: ObjectType? }
    let box = Box()
    public init() {}

    public var wrappedValue: ObjectType {
        guard let object = box.object else {
            fatalError("No ObservableObject of type \(ObjectType.self) found. A View.environmentObject(_:) for \(ObjectType.self) may be missing as an ancestor of this view.")
        }
        return object
    }
    public var projectedValue: ObservedObject<ObjectType>.Wrapper { ObservedObject.Wrapper(object: wrappedValue) }

    func _bind(key: String, _ ctx: _ResolveContext) {
        box.object = ctx.environment._objects[ObjectIdentifier(ObjectType.self)] as? ObjectType
    }
}

// MARK: - Environment

@propertyWrapper
public struct Environment<Value>: DynamicProperty, _BindableProperty {
    final class Box { var value: Value?; }
    let keyPath: KeyPath<EnvironmentValues, Value>?
    let objectType: Any.Type?
    let box = Box()

    public init(_ keyPath: KeyPath<EnvironmentValues, Value>) {
        self.keyPath = keyPath
        objectType = nil
    }

    public var wrappedValue: Value {
        if let value = box.value { return value }
        if let keyPath { return EnvironmentValues()[keyPath: keyPath] }
        fatalError("@Environment(\(Value.self).self) has no object of that type in the environment")
    }

    func _bind(key: String, _ ctx: _ResolveContext) {
        if let keyPath {
            box.value = ctx.environment[keyPath: keyPath]
        } else if let object = ctx.environment._objects[ObjectIdentifier(Value.self)] as? Value {
            box.value = object
        }
    }
}

extension Environment where Value: AnyObject {
    /// `@Environment(Model.self)`: an object put in the environment with `.environment(model)`.
    public init(_ objectType: Value.Type) {
        keyPath = nil
        self.objectType = objectType
    }
}
