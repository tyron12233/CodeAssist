package dev.ide.plugin

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.platform.ServiceFactory
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceScopeLevel

/**
 * The registrar handed to [Plugin.register]. Every contribution is attributed to this plugin's [pluginId]
 * automatically and tracked for unload, so a plugin never passes a [PluginId] by hand (the imperative host
 * wiring this replaces threaded one at all ~26 call sites). Contributions land on the application registry —
 * the parent that every per-project registry inherits.
 */
interface PluginRegistration {
    /** This plugin's id (== `manifest.pluginId`), injected by the manager. */
    val pluginId: PluginId

    /** Contribute [impl] to [ep]. Returns a [Disposable] (already tracked) that removes exactly this one. */
    fun <T : Any> register(ep: ExtensionPoint<T>, impl: T): Disposable

    /** Contribute a scoped service: a [dev.ide.platform.ServiceDescriptor] on `SERVICE_EP` at [level], built
     *  by [factory]. Collapses the double-passing of the id the raw `register(SERVICE_EP, ...)` requires. */
    fun <T : Any> service(key: ServiceKey<T>, level: ServiceScopeLevel, factory: ServiceFactory<T>): Disposable

    /** Escape hatch for the existing `(ExtensionRegistry, PluginId) -> Unit` facades (e.g. `AndroidSupport
     *  .register`, `JdtAnalysisSupport.register`, the EP-backed wrapper registries). They discard their
     *  per-registration [Disposable]s, so their unload relies on the bulk `unregisterAll(pluginId)` sweep —
     *  correct because they attribute to this same [pluginId]. */
    fun contributeVia(block: (ExtensionRegistry, PluginId) -> Unit)

    /** Tie an arbitrary [Disposable] to this plugin's unload (LIFO with the rest of its contributions). */
    fun onDispose(d: Disposable)
}
