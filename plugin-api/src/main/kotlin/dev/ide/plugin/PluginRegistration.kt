// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.MessageBus
import dev.ide.platform.MessageBusConnection
import dev.ide.platform.PluginId
import dev.ide.platform.ServiceFactory
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup
import dev.ide.platform.ServiceScopeLevel
import dev.ide.platform.log.Logger

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

    /**
     * Resolution, the counterpart of [service] registration: the APPLICATION-scoped container, read-only. A
     * plugin registers a service to publish a capability, and resolves one to consume a capability the host
     * or another plugin published.
     *
     * Prefer [ServiceLookup.getServiceOrNull]. A key with no service behind it is the normal case, not an
     * error: a host may not supply the capability at all (it is absent on desktop and in tests for most of
     * the host's own ports), and a plugin the user disabled registered nothing. Fall back rather than fail.
     *
     * Two rules follow from [Plugin.register] running once, at startup, before any project is open.
     *
     *  - **Resolve lazily.** Hold this and call it from a callback, not from `register`. Resolving during
     *    load forces the service to be built then, and a WORKSPACE- or MODULE-scoped one cannot be built at
     *    all, because there is no open project to scope it to. Those two scopes are reached from the
     *    `Workspace` or `Module` an extension-point callback is already handed, through its own `service`.
     *  - **Declare the edge.** Resolving what another plugin registers works only once that plugin has run,
     *    so name it in `manifest.dependsOn`. That also makes the user disabling it disable this plugin,
     *    which is what depending on it means.
     *
     * Defaults to [ServiceLookup.Empty], so a host that wires no container answers "nothing registered"
     * instead of failing.
     */
    val appServices: ServiceLookup get() = ServiceLookup.Empty

    /** Escape hatch for the existing `(ExtensionRegistry, PluginId) -> Unit` facades (e.g. `AndroidSupport
     *  .register`, `JdtAnalysisSupport.register`, the EP-backed wrapper registries). They discard their
     *  per-registration [Disposable]s, so their unload relies on the bulk `unregisterAll(pluginId)` sweep —
     *  correct because they attribute to this same [pluginId]. */
    fun contributeVia(block: (ExtensionRegistry, PluginId) -> Unit)

    /** Tie an arbitrary [Disposable] to this plugin's unload (LIFO with the rest of its contributions). */
    fun onDispose(d: Disposable)

    /** The application-wide [MessageBus]. Use it to PUBLISH — the IDE's lifecycle topics (editor/build/run/
     *  analysis/project/indexing events) or a topic this plugin defines itself for plugin-to-plugin messaging
     *  (`messageBus.syncPublisher(myTopic)`). To subscribe, prefer [busConnection]: a raw `messageBus.connect()`
     *  is NOT tracked, so its subscriptions outlive an unload unless the plugin disposes it via [onDispose]. */
    val messageBus: MessageBus

    /** A [MessageBusConnection] already tracked for unload (disposed LIFO with the plugin's other
     *  contributions), so its subscriptions are removed automatically when the plugin unloads. The normal
     *  way for a plugin to listen: `busConnection().subscribe(SomeTopics.CHANGES, listener)`. */
    fun busConnection(): MessageBusConnection

    /** A [Logger] whose records are attributed to this plugin (via [pluginId]) so the in-app Logs viewer can
     *  filter by plugin. The attribution is set by the platform and cannot be forged by the caller. */
    fun logger(tag: String): Logger

    /**
     * The running IDE's version, or null when the host supplied none (the desktop launcher, a standalone
     * test). This is the same value the loader compares against a manifest's `minHostVersion`, so a plugin
     * that branches on it and one that declares a floor agree about what they are running on.
     *
     * Prefer `minHostVersion` for "I need a newer IDE than this": the loader refuses to load the plugin and
     * puts the reason on its row in the Plugins screen, which no runtime check can do. Read this for the
     * softer cases, such as adapting behaviour or reporting the host in a diagnostic.
     */
    val hostVersion: String? get() = null
}
