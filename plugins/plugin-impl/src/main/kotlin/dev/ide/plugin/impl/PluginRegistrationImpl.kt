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
import dev.ide.platform.ServiceLookup
import dev.ide.platform.ServiceScopeLevel
import dev.ide.platform.impl.CompositeDisposable
import dev.ide.platform.log.Log
import dev.ide.platform.log.Logger
import dev.ide.plugin.PluginRegistration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
    override val hostVersion: String? = null,
    override val appServices: ServiceLookup = ServiceLookup.Empty,
    /** Where this plugin's [dataDir] goes. See [PluginManager] for what a host supplies and what a
     *  standalone caller gets instead. */
    private val dataRoot: Path = defaultDataRoot(),
) : PluginRegistration {

    override val messageBus: MessageBus get() = bus

    /**
     * Derived from [pluginId], never from anything the plugin says, so a plugin cannot name another's
     * directory. `by lazy` because reading it creates it: a plugin that never stores anything should not
     * leave an empty directory behind, and every plugin's `register` would otherwise make one.
     */
    override val dataDir: Path by lazy {
        // A plugin id is an identifier in practice, but it arrives from a manifest an author wrote, so it is
        // reduced to path-safe characters rather than trusted: `..` or a separator here would escape the root.
        val safe = pluginId.value.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }
            .joinToString("").trim('.').ifEmpty { "plugin" }
        dataRoot.resolve(safe).also { runCatching { Files.createDirectories(it) } }
    }

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

/**
 * Where a plugin's [PluginRegistration.dataDir] goes when no host supplied a root: a per-process temporary
 * directory. A standalone caller (a test, a one-off bootstrap) has no app storage to point at, and a plugin
 * under test that writes a file should still work rather than throw on a path that does not exist. Nothing
 * written there is expected to survive the process.
 */
internal fun defaultDataRoot(): Path =
    // Paths.get, never Path.of: this ships to ART, where Path.of is API 34 and a call below that throws
    // NoSuchMethodError (D8 outlines it, so it dexes clean and fails only when a plugin asks for its dataDir).
    Paths.get(System.getProperty("java.io.tmpdir"), "codeassist-plugin-data")
