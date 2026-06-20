package dev.ide.platform.impl

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceContainer
import dev.ide.platform.ServiceCycleException
import dev.ide.platform.ServiceDescriptor
import dev.ide.platform.ServiceFactory
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceScope
import dev.ide.platform.ServiceScopeLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * The [ServiceContainer] implementation. Each service has a [Holder] guarding its lazy, single
 * instantiation; the map of holders is lock-free and there is no container-wide lock, so the container
 * never contends with the model read/write lock and two distinct keys construct concurrently.
 *
 * [descriptors] supplies the EP-contributed [ServiceDescriptor]s (re-read on each miss so a late
 * registration is picked up). Programmatic registrations take precedence over EP descriptors.
 */
class ServiceContainerImpl(
    override val level: ServiceScopeLevel,
    override val parent: ServiceContainer?,
    private val scopeObject: Any?,
    private val descriptors: () -> List<ServiceDescriptor<*>> = { emptyList() },
) : ServiceContainer {

    private class Holder(val factory: ServiceFactory<*>) {
        @Volatile var instance: Any? = null
    }

    private val entries = ConcurrentHashMap<String, Holder>()
    private val programmatic = ConcurrentHashMap<String, ServiceFactory<*>>()
    private val disposer = CompositeDisposable()

    private val scope = object : ServiceScope {
        override val level get() = this@ServiceContainerImpl.level
        override val parent get() = this@ServiceContainerImpl.parent
        override val container get() = this@ServiceContainerImpl
        override val scopeObject get() = this@ServiceContainerImpl.scopeObject
        override fun onDispose(d: Disposable) { disposer.add(d) }
    }

    override fun <T : Any> getService(key: ServiceKey<T>): T =
        getServiceOrNull(key)
            ?: error("no service registered for '${key.id}' at or above scope $level")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getServiceOrNull(key: ServiceKey<T>): T? {
        val id = key.id
        entries[id]?.let { return build(id, it) as T }
        val factory = factoryFor(id)
        if (factory != null) {
            val holder = entries.computeIfAbsent(id) { Holder(factory) }
            return build(id, holder) as T
        }
        // Not defined here; a parent scope may own it. Negative results are never cached.
        return parent?.getServiceOrNull(key)
    }

    override fun <T : Any> registerService(key: ServiceKey<T>, factory: ServiceFactory<T>) {
        check(entries[key.id]?.instance == null) { "service '${key.id}' is already instantiated; register before first use" }
        programmatic[key.id] = factory
    }

    override fun <T : Any> registerServiceIfAbsent(key: ServiceKey<T>, factory: ServiceFactory<T>) {
        if (entries[key.id]?.instance != null) return
        programmatic.putIfAbsent(key.id, factory)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> peekService(key: ServiceKey<T>): T? = entries[key.id]?.instance as T?

    override fun evict(key: ServiceKey<*>) {
        val holder = entries.remove(key.id) ?: return
        (holder.instance as? Disposable)?.let { inst ->
            disposer.remove(inst)
            runCatching { inst.dispose() }
        }
    }

    override fun dispose() {
        try {
            disposer.dispose()
        } finally {
            entries.clear()
            programmatic.clear()
        }
    }

    /** The factory that owns [id] at THIS level: a programmatic registration first, else the single
     *  matching EP descriptor. Two EP descriptors for one key+level is a config error. */
    private fun factoryFor(id: String): ServiceFactory<*>? {
        programmatic[id]?.let { return it }
        val matching = descriptors().filter { it.key.id == id && it.level == level }
        return when (matching.size) {
            0 -> null
            1 -> matching[0].factory
            else -> error(
                "multiple platform.service descriptors for '$id' at $level: " +
                    matching.joinToString { it.plugin?.value ?: "?" },
            )
        }
    }

    private fun build(id: String, holder: Holder): Any {
        holder.instance?.let { return it }
        synchronized(holder) {
            holder.instance?.let { return it }
            val stack = constructionStack.get()
            if (id in stack) throw ServiceCycleException(stack.toList() + id)
            stack.addLast(id)
            try {
                @Suppress("UNCHECKED_CAST")
                val factory = holder.factory as ServiceFactory<Any>
                val instance = with(factory) { scope.create() }
                holder.instance = instance
                (instance as? Disposable)?.let { disposer.add(it) }
                return instance
            } finally {
                stack.removeLast()
            }
        }
    }

    companion object {
        // Per-thread so a cycle is detected even when it crosses scope containers (module -> workspace -> module).
        private val constructionStack = ThreadLocal.withInitial { ArrayDeque<String>() }
    }
}

/**
 * The application-scope container. Process-global (one per host), it carries its OWN extension registry
 * because a [PlatformCore]'s registry is per-workspace: app-scoped [ServiceDescriptor]s and built-ins
 * register here so they survive project switches.
 */
class ApplicationContainer private constructor(
    val extensions: ExtensionRegistry,
    private val impl: ServiceContainerImpl,
) : ServiceContainer by impl {
    constructor(extensions: ExtensionRegistry = ExtensionRegistryImpl()) : this(
        extensions,
        ServiceContainerImpl(ServiceScopeLevel.APPLICATION, parent = null, scopeObject = null) {
            extensions.extensions(SERVICE_EP)
        },
    )
}
