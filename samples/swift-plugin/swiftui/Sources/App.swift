// Apps: what `@main struct MyApp: App` declares, and running one full screen. The app's first WindowGroup is its
// screen; the rest of its scenes (Settings, more windows) have nowhere to appear on an iPhone and are ignored, as iOS
// ignores them.

import Foundation

@MainActor @preconcurrency
public protocol Scene {
    associatedtype Body: Scene
    @SceneBuilder @MainActor @preconcurrency var body: Self.Body { get }
}

extension Never: Scene {}

/// A scene the engine knows how to show: the view it puts on screen, if any.
@MainActor
protocol _PrimitiveScene {
    var _rootView: AnyView? { get }
}

extension _PrimitiveScene {
    func _neverBody() -> Never { fatalError("\(Self.self) is a primitive scene and has no body") }
}

/// The view a scene shows: the first WindowGroup's content, through any scene the app composes.
@MainActor
func _sceneRootView<S: Scene>(_ scene: S) -> AnyView? {
    if let primitive = scene as? any _PrimitiveScene { return primitive._rootView }
    return _sceneRootView(scene.body)
}

@resultBuilder
public struct SceneBuilder {
    public static func buildBlock<Content: Scene>(_ content: Content) -> Content { content }
    public static func buildBlock<each Content: Scene>(_ content: repeat each Content) -> _TupleScene {
        var scenes: [any Scene] = []
        repeat scenes.append(each content)
        return _TupleScene(scenes: scenes)
    }
    public static func buildExpression<Content: Scene>(_ content: Content) -> Content { content }
    public static func buildOptional<Content: Scene>(_ content: Content?) -> _TupleScene { _TupleScene(scenes: content.map { [$0] } ?? []) }
    public static func buildEither<T: Scene>(first: T) -> _TupleScene { _TupleScene(scenes: [first]) }
    public static func buildEither<F: Scene>(second: F) -> _TupleScene { _TupleScene(scenes: [second]) }
    public static func buildLimitedAvailability<Content: Scene>(_ content: Content) -> _TupleScene { _TupleScene(scenes: [content]) }
}

public struct _TupleScene: Scene, _PrimitiveScene {
    let scenes: [any Scene]
    public var body: Never { _neverBody() }
    var _rootView: AnyView? {
        for scene in scenes {
            func open<S: Scene>(_ s: S) -> AnyView? { _sceneRootView(s) }
            if let view = open(scene) { return view }
        }
        return nil
    }
}

public struct WindowGroup<Content: View>: Scene, _PrimitiveScene {
    let content: Content
    public init(@ViewBuilder content: () -> Content) { self.content = content() }
    public init(id: String, @ViewBuilder content: () -> Content) { self.content = content() }
    public init(_ title: Text, @ViewBuilder content: () -> Content) { self.content = content() }
    public init(_ titleKey: LocalizedStringKey, @ViewBuilder content: () -> Content) { self.content = content() }
    @_disfavoredOverload public init<S: StringProtocol>(_ title: S, @ViewBuilder content: () -> Content) { self.content = content() }
    public init(_ titleKey: LocalizedStringKey, id: String, @ViewBuilder content: () -> Content) { self.content = content() }
    public var body: Never { _neverBody() }
    var _rootView: AnyView? { AnyView(content) }
}

/// macOS's settings window: nothing on an iPhone.
public struct Settings<Content: View>: Scene, _PrimitiveScene {
    public init(@ViewBuilder content: () -> Content) {}
    public var body: Never { _neverBody() }
    var _rootView: AnyView? { nil }
}

extension Scene {
    public func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping (V, V) -> Void) -> some Scene { self }
    public func onChange<V: Equatable>(of value: V, initial: Bool = false, _ action: @escaping () -> Void) -> some Scene { self }
    public func commands<C>(@_CommandsBuilder content: () -> C) -> some Scene { self }
    public func defaultSize(width: CGFloat, height: CGFloat) -> some Scene { self }
    public func windowResizability(_ resizability: WindowResizability) -> some Scene { self }
    public func modelContainer(for modelType: Any.Type) -> some Scene { self }
}

@resultBuilder
public struct _CommandsBuilder {
    public static func buildBlock<each C>(_ c: repeat each C) {}
}

public struct WindowResizability: Sendable {
    public static let automatic = WindowResizability(), contentSize = WindowResizability(), contentMinSize = WindowResizability()
}

@MainActor @preconcurrency
public protocol App {
    associatedtype Body: Scene
    @SceneBuilder @MainActor @preconcurrency var body: Self.Body { get }
    @MainActor init()
}

extension App {
    /// What `@main` calls: run the app full screen, answering the IDE over stdin/stdout like previews do.
    @MainActor public static func main() { _runApp(Self.self) }
}

/// The app's screen: the app's properties (@State, @StateObject) bound to the graph as a view's are, then its first
/// window's content.
struct _AppRoot<A: App>: View, _PrimitiveView {
    let app: A
    var body: Never { _neverBody() }
    func _resolve(_ ctx: _ResolveContext) -> [_Node] {
        let ctx = ctx.child("app")
        _bindDynamicProperties(of: app, ctx)
        guard let root = _sceneRootView(app.body) else { return [] }
        return _resolveView(root, ctx.child("window"))
    }
}

/// Runs [type] as an app: created once, its first window drawn on the device canvas and kept live.
@MainActor
public func _runApp<A: App>(_ type: A.Type) {
    let app = A()
    _runPreviews([_Preview("\(A.self)") { _AppRoot(app: app) }])
}

/// `@UIApplicationDelegateAdaptor`: accepted so an app that declares one compiles; there is no UIKit to call it, and
/// reading it stops the app with a message saying so (Android's Foundation cannot construct a delegate generically).
@propertyWrapper
public struct UIApplicationDelegateAdaptor<Delegate: AnyObject>: DynamicProperty {
    public init(_ delegateType: Delegate.Type = Delegate.self) {}
    public var wrappedValue: Delegate { fatalError("UIApplicationDelegateAdaptor: there is no UIApplication delegate in CodeAssist's SwiftUI") }
}
