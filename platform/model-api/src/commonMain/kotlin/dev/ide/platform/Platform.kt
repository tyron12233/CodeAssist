// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.jvm.JvmInline

/**
 * The half of platform-core's substrate that has no platform behind it: plugin identity, disposal, the
 * extension framework, service keys and progress reporting.
 *
 * Here rather than in `:platform-core` for the reason the rest of this module exists: a language backend that
 * is otherwise portable still cannot be COMPILED for a non-JVM target while the extension point it registers
 * through can only be NAMED from the JVM. The package is unchanged, so no consumer moves.
 *
 * What stayed behind is what genuinely needs a JVM: `Topic` is typed by `java.lang.Class`, so the message bus
 * and everything hanging off it remain in `:platform-core`.
 */

/** Identity of a loaded plugin (bundled or third-party). */
@JvmInline
value class PluginId(val value: String) {
    companion object {
        /** Stands for "this contribution could not be attributed" — never a loaded plugin's id. */
        val UNKNOWN = PluginId("")
    }
}

/** Anything with a deterministic teardown. Registrations return one so callers can unregister. */
fun interface Disposable {
    fun dispose()
}

// ---------------------------------------------------------------------------
// Extension framework
// ---------------------------------------------------------------------------

/**
 * A typed extension point. Plugins contribute implementations of [T]; the platform and other
 * plugins consume them via [ExtensionRegistry.extensions]. This is how module types, build
 * systems, and language backends are pluggable without the core depending on them.
 */
class ExtensionPoint<T : Any>(val id: String)

/** One contribution to an extension point, with the plugin that made it. See [ExtensionRegistry.contributions]. */
data class Contribution<T : Any>(val impl: T, val plugin: PluginId)

interface ExtensionRegistry {
    /** Contribute [impl] to [ep] on behalf of [plugin]. Dispose the returned handle to remove it. */
    fun <T : Any> register(ep: ExtensionPoint<T>, impl: T, plugin: PluginId): Disposable

    /** All current contributions to [ep], in registration order. */
    fun <T : Any> extensions(ep: ExtensionPoint<T>): List<T>

    /**
     * [extensions], but each contribution paired with the plugin that registered it. The host needs the
     * attribution to hold a plugin to account for what it contributed: which analyzer belongs to which
     * plugin, so a slow or failing one can be named (and taken off the pass) rather than anonymously
     * degrading the IDE.
     *
     * Defaulted so a registry implementation that does not track origins still compiles; it then reports
     * every contribution as [PluginId.UNKNOWN], which callers must treat as "not attributable", never as
     * a real plugin.
     */
    fun <T : Any> contributions(ep: ExtensionPoint<T>): List<Contribution<T>> =
        extensions(ep).map { Contribution(it, PluginId.UNKNOWN) }

    /** Remove every contribution made by [plugin]. The bulk-unregister path a plugin unload takes: a plugin
     *  contributing through a facade that discards its per-registration [Disposable] is still fully removed. */
    fun unregisterAll(plugin: PluginId)
}

/** A scoped service locator (workspace- or project-scoped, depending on the key's origin). */
class ServiceKey<T : Any>(val id: String)

// ---------------------------------------------------------------------------
// Progress / cancellation
// ---------------------------------------------------------------------------

/**
 * Reports progress + cancellation for a long-running operation (dependency resolution, a build, indexing).
 * Producers (build-api/deps-api) take one and call [report]/[checkCanceled]; the host supplies an impl that
 * forwards to the UI (or a no-op).
 */
interface ProgressReporter {
    /** [fraction] in 0.0..1.0, or negative for indeterminate. */
    fun report(fraction: Double, message: String? = null)
    fun checkCanceled()
    val isCanceled: Boolean
}
