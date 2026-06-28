package dev.ide.platform

/**
 * platform-core — the substrate every other module sits on.
 *
 * No domain knowledge lives here: no notion of "project", "Android", "Java", or "Gradle".
 * It provides extensibility (extension points), eventing (message bus), lifecycle
 * (disposers), background work (activities), progress/cancellation, and the value types
 * shared across modules ([ContentHash], ids).
 */

/** Identity of a loaded plugin (bundled or third-party). */
@JvmInline
value class PluginId(val value: String)

/** A stable digest of some content (file bytes, a classpath, a set of task inputs). String-backed (hex/base64). */
@JvmInline
value class ContentHash(val value: String)

/** Anything with a deterministic teardown. Registrations return one so callers can unregister. */
fun interface Disposable {
    fun dispose()
}

// ---------------------------------------------------------------------------
// Extension framework
// ---------------------------------------------------------------------------

/**
 * A typed extension point. Plugins contribute implementations of [T]; the platform and other
 * plugins consume them via [ExtensionRegistry.extensions]. This is how module types, build
 * systems, and language backends are pluggable without the core depending on them.
 */
class ExtensionPoint<T : Any>(val id: String)

interface ExtensionRegistry {
    /** Contribute [impl] to [ep] on behalf of [plugin]. Dispose the returned handle to remove it. */
    fun <T : Any> register(ep: ExtensionPoint<T>, impl: T, plugin: PluginId): Disposable

    /** All current contributions to [ep], in registration order. */
    fun <T : Any> extensions(ep: ExtensionPoint<T>): List<T>
}

/** A scoped service locator (workspace- or project-scoped, depending on the key's origin). */
class ServiceKey<T : Any>(val id: String)

// ---------------------------------------------------------------------------
// Message bus
// ---------------------------------------------------------------------------

/** A typed broadcast channel. Listener interface [L] defines the callbacks publishers invoke. */
class Topic<L : Any>(val name: String, val listenerType: Class<L>)

interface MessageBus {
    fun connect(): MessageBusConnection
    /** Returns a proxy; calling a method on it invokes that method on every subscribed listener. */
    fun <L : Any> syncPublisher(topic: Topic<L>): L
}

interface MessageBusConnection : Disposable {
    fun <L : Any> subscribe(topic: Topic<L>, listener: L)
}

// ---------------------------------------------------------------------------
// Progress / cancellation
// ---------------------------------------------------------------------------

/**
 * Reports progress + cancellation for a long-running operation (dependency resolution, a build, indexing).
 * Producers (build-api/deps-api) take one and call [report]/[checkCanceled]; the host supplies an impl that
 * forwards to the UI (or a no-op).
 */
interface ProgressReporter {
    /** [fraction] in 0.0..1.0, or negative for indeterminate. */
    fun report(fraction: Double, message: String? = null)
    fun checkCanceled()
    val isCanceled: Boolean
}
