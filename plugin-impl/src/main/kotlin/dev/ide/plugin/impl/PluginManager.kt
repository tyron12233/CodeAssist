package dev.ide.plugin.impl

import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.MessageBus
import dev.ide.platform.PluginId
import dev.ide.platform.impl.CompositeDisposable
import dev.ide.platform.impl.MessageBusImpl
import dev.ide.plugin.Plugin

/**
 * Loads and unloads [Plugin]s over one [ExtensionRegistry]. The host builds a manager over its application
 * registry and hands it the built-in plugin set; each plugin's contributions register through a
 * [PluginRegistrationImpl] that attributes them to the plugin and tracks them for unload.
 *
 * Load order is the topological order of `manifest.dependsOn`, so what used to be implicit registration
 * sequencing (e.g. the JDT language backend registering before the others, so it is the resolution fallback)
 * becomes a declared, enforced edge. Unload disposes a plugin's tracked [dev.ide.platform.Disposable]s (LIFO)
 * and then bulk-unregisters anything it contributed through a facade (`unregisterAll`); both paths are
 * list-removals, so running both is idempotent.
 */
class PluginManager(
    private val registry: ExtensionRegistry,
    /** The bus handed to each plugin's [PluginRegistrationImpl] so it can publish/subscribe. Defaults to a
     *  private bus (standalone tests); the host passes its application-wide `PlatformCore.messageBus`. */
    private val bus: MessageBus = MessageBusImpl(),
) {

    private class Loaded(val plugin: Plugin, val teardown: CompositeDisposable)

    // Insertion-ordered so unloadAll() can reverse the actual load order.
    private val loaded = LinkedHashMap<PluginId, Loaded>()

    /** The ids currently loaded, in load order. */
    val loadedIds: List<PluginId> get() = loaded.keys.toList()

    /** Load [plugins] in dependency order. Throws on a duplicate id, a missing dependency, or a cycle. */
    fun loadAll(plugins: List<Plugin>) {
        for (p in topoSort(plugins)) load(p)
    }

    /** Load one plugin now (its `dependsOn` are assumed already loaded). */
    fun load(plugin: Plugin) {
        val id = plugin.manifest.pluginId
        require(id !in loaded) { "plugin '${id.value}' already loaded" }
        val teardown = CompositeDisposable()
        plugin.register(PluginRegistrationImpl(id, registry, teardown, bus))
        loaded[id] = Loaded(plugin, teardown)
    }

    /** Unload one plugin: dispose its tracked contributions, sweep any facade contributions by id, then
     *  dispose the plugin itself. A no-op if [id] is not loaded. */
    fun unload(id: PluginId) {
        val entry = loaded.remove(id) ?: return
        runCatching { entry.teardown.dispose() }
        registry.unregisterAll(id)
        runCatching { entry.plugin.dispose() }
    }

    /** Unload everything in reverse load order. */
    fun unloadAll() {
        for (id in loaded.keys.toList().asReversed()) unload(id)
    }

    /**
     * Topologically sort [plugins] by `manifest.dependsOn` (dependencies first). DFS post-order with the
     * plugins visited as roots in declaration order, so the result is deterministic and independent plugins
     * keep their declared relative order. Throws [IllegalArgumentException] on a duplicate id, a `dependsOn`
     * referencing a plugin not in [plugins], or a dependency cycle.
     */
    private fun topoSort(plugins: List<Plugin>): List<Plugin> {
        val byId = LinkedHashMap<String, Plugin>()
        for (p in plugins) {
            require(byId.put(p.manifest.id, p) == null) { "duplicate plugin id '${p.manifest.id}'" }
        }
        for (p in plugins) {
            for (dep in p.manifest.dependsOn) {
                require(dep in byId) { "plugin '${p.manifest.id}' depends on missing plugin '$dep'" }
            }
        }

        val ordered = ArrayList<Plugin>(plugins.size)
        val placed = HashSet<String>()
        val onStack = HashSet<String>()

        fun visit(p: Plugin) {
            if (p.manifest.id in placed) return
            require(onStack.add(p.manifest.id)) { "plugin dependency cycle at '${p.manifest.id}'" }
            for (dep in p.manifest.dependsOn) visit(byId.getValue(dep))
            onStack.remove(p.manifest.id)
            placed.add(p.manifest.id)
            ordered.add(p)
        }

        for (p in plugins) visit(p)
        return ordered
    }
}
