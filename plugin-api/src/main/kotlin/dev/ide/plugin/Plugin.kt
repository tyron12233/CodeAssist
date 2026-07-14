package dev.ide.plugin

/**
 * A unit of contribution to the IDE: extension points, extensions, and scoped services, declared and wired
 * through one SPI. The IDE's own built-ins are plugins (the first consumers of this API); a future
 * separately-packaged plugin implements the same contract.
 *
 * A plugin declares its identity + load order inline via [manifest] (the "manifest + entry point" model:
 * built-ins carry a Kotlin-literal manifest; an external artifact would ship the same shape as a resource the
 * loader parses). [register] runs exactly once, in dependency order (see [manifest] `dependsOn`), and receives
 * a [PluginRegistration] that auto-attributes every contribution to this plugin and tracks it for unload — so
 * the plugin never threads a [dev.ide.platform.PluginId] by hand.
 *
 * Teardown is automatic: the [dev.ide.platform.Disposable]s produced by `register` (plus a bulk
 * unregister-by-id) are the default unload path. [dispose] is only for a plugin that holds extra resources of
 * its own (a background scope, a file watcher) beyond its registry contributions.
 */
interface Plugin {
    /** Identity + load-order metadata. `manifest.id` is the attribution key and the `dependsOn` node id. */
    val manifest: PluginManifest

    /** Contribute extension points, extensions, and services. Runs once, after every plugin in `dependsOn`. */
    fun register(reg: PluginRegistration)

    /** Release resources this plugin owns beyond its registry contributions. Optional; most plugins need none. */
    fun dispose() {}
}
