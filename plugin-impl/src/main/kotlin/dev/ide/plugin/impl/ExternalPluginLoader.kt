package dev.ide.plugin.impl

import dev.ide.plugin.PLUGIN_API_VERSION
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
    private val hostApiVersion: Int = PLUGIN_API_VERSION,
    /** The host's own version, compared against [PluginManifest.minHostVersion]. Null skips that check. */
    private val hostVersion: String? = null,
) {

    /** The outcome of loading one discovered plugin. */
    sealed interface Result {
        val manifest: PluginManifest

        /**
         * The plugin instantiated cleanly and is ready for [PluginManager.load].
         *
         * [instances] holds the entry points this load created, carried out so the host can take the plugin's
         * other facets from it. The UI facet (`dev.ide.plugin.ui.UiPlugin`) is loaded that way: its type lives
         * in a Compose module this one cannot see, so the host does the instantiating, through this object
         * rather than off the raw loader. That is what lets a class named in both of the manifest's lists be
         * one object, and what keeps every facet on the loader that lets them share statics.
         */
        data class Loaded(
            override val manifest: PluginManifest,
            val plugin: Plugin,
            val instances: EntryPointInstances,
        ) : Result {
            /** The loader the plugin's classes came off. */
            val classLoader: ClassLoader get() = instances.classLoader
        }

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
        if (manifest.entryPoints.isEmpty() && manifest.uiEntryPoints.isEmpty()) {
            return Result.Failed(manifest, "declares no entry point")
        }

        return try {
            val instances = EntryPointInstances(discovered.classLoader())
            // Distinct, because one class is one entry point: a manifest naming it twice used to register it
            // twice, and now that both names resolve to one object that would be the same object registering
            // twice, which is worse.
            val entries = manifest.entryPoints.distinct().map { asPlugin(it, instances) }
            // A UI-only plugin has no engine facet to run. It still loads, so that it holds a place in the
            // load order (another plugin may depend on it), is attributed and listed like any other, and has
            // a classloader the host can take its UI facet off; register() then has nothing to do.
            val plugin = when (entries.size) {
                0 -> UiOnlyPlugin(manifest)
                1 -> entries[0]
                else -> CompositePlugin(entries)
            }
            Result.Loaded(manifest, ExternalPlugin(manifest, plugin, instances.classLoader), instances)
        } catch (t: Throwable) {
            Result.Failed(manifest, describe(t))
        }
    }

    private fun asPlugin(fqcn: String, instances: EntryPointInstances): Plugin =
        instances.of(fqcn) as? Plugin
            ?: throw IllegalStateException("$fqcn does not implement ${Plugin::class.java.name}")

    // An entry point that fails to link is a plugin built against an SPI this host does not have; see
    // PluginLoadFailure for why that reads as a raw JVM descriptor unless it is spelled out.
    private fun describe(t: Throwable): String = PluginLoadFailure.describe(t)

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

    /** The engine facet of a plugin that declares only `uiEntryPoints`: present, ordered, and inert. */
    private class UiOnlyPlugin(override val manifest: PluginManifest) : Plugin {
        override fun register(reg: PluginRegistration) = Unit
    }

    /** A manifest that names several entry points loads them all under its single id. */
    private class CompositePlugin(private val parts: List<Plugin>) : Plugin {
        override val manifest: PluginManifest get() = parts.first().manifest
        override fun register(reg: PluginRegistration) = parts.forEach { it.register(reg) }
        override fun dispose() = parts.asReversed().forEach { runCatching { it.dispose() } }
    }
}
