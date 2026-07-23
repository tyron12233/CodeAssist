package dev.ide.plugin.impl

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.MessageBus
import dev.ide.platform.MessageBusConnection
import dev.ide.platform.PluginId
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceDescriptor
import dev.ide.platform.ServiceFactory
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceScopeLevel
import dev.ide.platform.impl.CompositeDisposable
import dev.ide.platform.log.Log
import dev.ide.platform.log.Logger
import dev.ide.plugin.PluginRegistration

/**
 * The [PluginRegistration] a [PluginManager] hands to one plugin's `register`. It attributes every
 * contribution to [pluginId] and adds each returned [Disposable] to [teardown] (the plugin's
 * [CompositeDisposable]), so an unload disposes them LIFO. Facade contributions made through [contributeVia]
 * discard their handles by design; those are swept on unload by [ExtensionRegistry.unregisterAll].
 */
internal class PluginRegistrationImpl(
    override val pluginId: PluginId,
    private val registry: ExtensionRegistry,
    private val teardown: CompositeDisposable,
    private val bus: MessageBus,
) : PluginRegistration {

    override val messageBus: MessageBus get() = bus

    override fun <T : Any> register(ep: ExtensionPoint<T>, impl: T): Disposable =
        teardown.add(registry.register(ep, impl, pluginId))

    override fun <T : Any> service(
        key: ServiceKey<T>,
        level: ServiceScopeLevel,
        factory: ServiceFactory<T>,
    ): Disposable =
        teardown.add(registry.register(SERVICE_EP, ServiceDescriptor(key, level, factory, pluginId), pluginId))

    override fun contributeVia(block: (ExtensionRegistry, PluginId) -> Unit) = block(registry, pluginId)

    override fun onDispose(d: Disposable) {
        teardown.add(d)
    }

    // A MessageBusConnection is a Disposable, so tracking it in teardown auto-unsubscribes on unload.
    override fun busConnection(): MessageBusConnection = bus.connect().also { teardown.add(it) }

    override fun logger(tag: String): Logger = Log.logger(tag, source = pluginId.value)
}
