package dev.ide.platform

/**
 * Scoped service container: an IntelliJ-style service locator with explicit factory-based injection.
 *
 * Services live at one of three scopes ([ServiceScopeLevel]). A container resolves a [ServiceKey] by
 * lazily instantiating the service via its registered [ServiceFactory] the first time it is asked for,
 * caching it, and delegating to its parent container when the key is not defined at this level. So a
 * workspace singleton is built once and shared across every module container under it.
 *
 * platform-core stays domain-free: it knows nothing of "module" or "workspace", only the three abstract
 * levels and the [ServiceScope.scopeObject] each factory receives.
 */

/** The three lifetimes a service can have. */
enum class ServiceScopeLevel { APPLICATION, WORKSPACE, MODULE }

/**
 * The receiver a [ServiceFactory] runs against. It exposes the scope's level, its parent container
 * (for reaching parent-scope services), its own container, and the domain object the scope is bound to
 * ([scopeObject]: the Module at MODULE, the Workspace at WORKSPACE, null at APPLICATION). A factory
 * pulls its dependencies through [getService].
 */
interface ServiceScope {
    val level: ServiceScopeLevel
    val parent: ServiceContainer?
    val container: ServiceContainer
    val scopeObject: Any?

    /** Register extra teardown that runs when this scope's container disposes. */
    fun onDispose(d: Disposable)

    /** Resolve a (possibly parent-scope) service this service depends on. */
    fun <T : Any> getService(key: ServiceKey<T>): T = container.getService(key)
}

/** Builds a service instance against a [ServiceScope]. Explicit lambda, no reflection. */
fun interface ServiceFactory<T : Any> {
    fun ServiceScope.create(): T
}

/**
 * A scoped locator. Lookups are lazy and cached; an unresolved key falls back to [parent] and throws
 * only when no scope up the chain defines it. A constructed service that is [Disposable] is torn down
 * with the container (LIFO).
 */
interface ServiceContainer : Disposable {
    val level: ServiceScopeLevel
    val parent: ServiceContainer?

    /** The service for [key], instantiating it on first use. Throws if no scope defines it. */
    fun <T : Any> getService(key: ServiceKey<T>): T

    /** Like [getService] but returns null instead of throwing when no scope defines [key]. */
    fun <T : Any> getServiceOrNull(key: ServiceKey<T>): T?

    /** Register [factory] for [key] at this level programmatically. Overrides an EP descriptor for the
     *  same key+level. Must be called before the service is first resolved. */
    fun <T : Any> registerService(key: ServiceKey<T>, factory: ServiceFactory<T>)

    /** Register [factory] for [key] only if nothing is registered for it yet (a no-op otherwise). For a
     *  shared container (e.g. the application container several projects register the same service on). */
    fun <T : Any> registerServiceIfAbsent(key: ServiceKey<T>, factory: ServiceFactory<T>)

    /** The already-built instance for [key] at THIS level, or null if it has not been instantiated.
     *  Never constructs and never falls back to the parent — a pure peek, for enumerating live services. */
    fun <T : Any> peekService(key: ServiceKey<T>): T?

    /** Dispose and drop the instance for [key] if it was built at this level (a no-op otherwise). Lets a
     *  caller release a service's resources promptly without tearing down the whole scope. */
    fun evict(key: ServiceKey<*>)
}

/**
 * A declarative service contribution, contributed through [SERVICE_EP] so a plugin registers a service
 * the same way it registers any other extension.
 */
class ServiceDescriptor<T : Any>(
    val key: ServiceKey<T>,
    val level: ServiceScopeLevel,
    val factory: ServiceFactory<T>,
    val plugin: PluginId? = null,
)

/** The extension point plugins contribute [ServiceDescriptor]s to. */
val SERVICE_EP = ExtensionPoint<ServiceDescriptor<*>>("platform.service")

/** Thrown when resolving a service would require constructing a service already under construction. */
class ServiceCycleException(val chain: List<String>) :
    IllegalStateException("service dependency cycle: ${chain.joinToString(" -> ")}")
