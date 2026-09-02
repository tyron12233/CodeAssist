package dev.ide.plugin.impl

import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.PluginVersions
import dev.ide.plugin.external.DiscoveredPlugin

/**
 * Turns a [DiscoveredPlugin] into a loadable [Plugin]: checks the plugin against the host's compatibility
 * floors, builds its classloader, and instantiates each declared entry point.
 *
 * Everything here is failure-tolerant by construction. A plugin the host did not write can be built against
 * the wrong SPI version, name a class that is not there, or throw from its constructor, and none of those may
 * stop the IDE from starting; each comes back as [Result.Failed] with a message the Plugins screen shows
 * against that plugin's row.
 *
 * The manifest the host discovered stays authoritative. The instantiated entry point's own `manifest` is
 * ignored, so a plugin cannot claim an id, an essential flag, or a dependency edge other than the one the
 * host already used to build its catalogue and to ask the user.
 */
class ExternalPluginLoader(
    /** The host's plugin-SPI version. A plugin declaring a different [PluginManifest.apiVersion] is rejected. */
    private val hostApiVersion: Int = 1,
    /** The host's own version, compared against [PluginManifest.minHostVersion]. Null skips that check. */
    private val hostVersion: String? = null,
) {

    /** The outcome of loading one discovered plugin. */
    sealed interface Result {
        val manifest: PluginManifest

        /** The plugin instantiated cleanly and is ready for [PluginManager.load]. */
        data class Loaded(override val manifest: PluginManifest, val plugin: Plugin) : Result

        /** The plugin was rejected or threw while being instantiated. [reason] is user-facing. */
        data class Failed(override val manifest: PluginManifest, val reason: String) : Result
    }

    fun load(discovered: DiscoveredPlugin): Result {
        val manifest = discovered.manifest
        if (manifest.apiVersion != hostApiVersion) {
            return Result.Failed(
                manifest,
                "built for plugin API ${manifest.apiVersion}, this version of the IDE loads API $hostApiVersion",
            )
        }
        val min = manifest.minHostVersion
        if (!PluginVersions.satisfies(hostVersion, min)) {
            return Result.Failed(manifest, "requires CodeAssist $min or newer")
        }
        if (manifest.entryPoints.isEmpty()) {
            return Result.Failed(manifest, "declares no entry point")
        }

        return try {
            val loader = discovered.classLoader()
            val entries = manifest.entryPoints.map { instantiate(loader, it) }
            val plugin = if (entries.size == 1) entries[0] else CompositePlugin(entries)
            Result.Loaded(manifest, ExternalPlugin(manifest, plugin, loader))
        } catch (t: Throwable) {
            Result.Failed(manifest, describe(t))
        }
    }

    private fun instantiate(loader: ClassLoader, fqcn: String): Plugin {
        val cls = loader.loadClass(fqcn)
        val instance = cls.getDeclaredConstructor().newInstance()
        return instance as? Plugin
            ?: throw IllegalStateException("$fqcn does not implement ${Plugin::class.java.name}")
    }

    private fun describe(t: Throwable): String {
        // An entry point that fails to link reports the missing type, which is the actionable part: it means
        // the plugin was built against a host API that is not on this classloader's parent.
        val cause = generateSequence(t) { it.cause }.last()
        val message = cause.message?.takeIf { it.isNotBlank() }
        return if (message != null) "${cause::class.java.simpleName}: $message" else cause::class.java.name
    }

    /**
     * The loaded plugin as the manager sees it: the host's manifest over the instantiated delegate, with the
     * plugin's own classloader installed as the thread context loader for the duration of each SPI call, so
     * a plugin that looks its own resources up through the context loader finds them.
     */
    private class ExternalPlugin(
        override val manifest: PluginManifest,
        private val delegate: Plugin,
        private val loader: ClassLoader,
    ) : Plugin {

        override fun register(reg: PluginRegistration) = inContext { delegate.register(reg) }

        override fun dispose() = inContext { delegate.dispose() }

        private fun inContext(body: () -> Unit) {
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            thread.contextClassLoader = loader
            try {
                body()
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    /** A manifest that names several entry points loads them all under its single id. */
    private class CompositePlugin(private val parts: List<Plugin>) : Plugin {
        override val manifest: PluginManifest get() = parts.first().manifest
        override fun register(reg: PluginRegistration) = parts.forEach { it.register(reg) }
        override fun dispose() = parts.asReversed().forEach { runCatching { it.dispose() } }
    }
}
