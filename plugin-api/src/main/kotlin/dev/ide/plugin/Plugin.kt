// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
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
    /**
     * Identity + load-order metadata. `manifest.id` is the attribution key and the `dependsOn` node id.
     *
     * A plugin **shipped as its own app leaves this alone**. Its identity is the packaged
     * `res/raw/codeassist_plugin.toml`, which the host reads before any of the plugin's code runs and uses in
     * preference to whatever an entry point returns, so a manifest declared here was only ever read by the
     * plugin itself. It was also the one line that broke an already-compiled plugin whenever [PluginManifest]
     * grew a field: Kotlin compiles a call relying on default arguments into a synthetic constructor whose
     * descriptor names every parameter. Defaulting it here moves that call into the artifact the host ships,
     * where it is always in step.
     *
     * A **built-in** has no packaged manifest, so it must override this. [dev.ide.plugin.PluginRegistration]
     * attributes contributions by id, and the loader refuses a plugin whose id is blank rather than let one
     * register unattributed.
     */
    val manifest: PluginManifest get() = PACKAGED

    /** Contribute extension points, extensions, and services. Runs once, after every plugin in `dependsOn`. */
    fun register(reg: PluginRegistration)

    /** Release resources this plugin owns beyond its registry contributions. Optional; most plugins need none. */
    fun dispose() {}
}

/**
 * What [Plugin.manifest] answers for a plugin that does not declare one. Nothing reads it: an installed
 * plugin's identity is its packaged manifest, and the loader replaces this before the plugin is registered.
 */
private val PACKAGED = PluginManifest(id = "", name = "")
