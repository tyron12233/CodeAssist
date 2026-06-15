package dev.ide.platform.impl

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe [ExtensionRegistry]. Contributions are kept in registration order per extension point;
 * reads ([extensions]) are lock-free snapshots over a copy-on-write list. Registering returns a
 * [Disposable] that removes exactly that one contribution, so a plugin can be unloaded without
 * disturbing the others.
 *
 * Extension points are keyed by their [ExtensionPoint.id] string rather than instance identity, so a
 * producer and a consumer that each construct their own `ExtensionPoint("…")` with the same id see
 * the same channel.
 */
class ExtensionRegistryImpl : ExtensionRegistry {
    private class Registration(val impl: Any, val plugin: PluginId)

    private val byPoint = ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>>()

    override fun <T : Any> register(ep: ExtensionPoint<T>, impl: T, plugin: PluginId): Disposable {
        val list = byPoint.getOrPut(ep.id) { CopyOnWriteArrayList() }
        val reg = Registration(impl, plugin)
        list.add(reg)
        return Disposable { list.remove(reg) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> extensions(ep: ExtensionPoint<T>): List<T> {
        val list = byPoint[ep.id] ?: return emptyList()
        // Map to a fresh list so callers get a stable snapshot, independent of later (un)registration.
        return list.map { it.impl as T }
    }

    /** Remove every contribution made by [plugin] (bulk unregister on plugin unload). */
    fun unregisterAll(plugin: PluginId) {
        for (list in byPoint.values) list.removeAll { it.plugin == plugin }
    }
}
