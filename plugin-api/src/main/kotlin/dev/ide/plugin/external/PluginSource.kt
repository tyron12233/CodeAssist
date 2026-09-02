// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.external

import dev.ide.plugin.PluginManifest

/**
 * Where an installed plugin came from. Carried alongside the manifest so the Plugins settings screen can show
 * provenance ("com.example.caplugin") next to a plugin the user did not get from the IDE itself, and so the
 * host can make trust decisions from something the plugin cannot forge (the [signature]).
 */
data class PluginOrigin(
    /** Id of the [PluginSource] that discovered this plugin. */
    val sourceId: String,
    /** Human-readable provenance: an installed package name, or a file name. */
    val label: String,
    /** Lowercase hex SHA-256 of the signing certificate, when the source can attest one. Null when the
     *  source has no notion of signing (a plain directory drop). */
    val signature: String? = null,
)

/**
 * Something a [PluginSource] found: either a [DiscoveredPlugin] the host can go on to load, or a
 * [RejectedPlugin] it cannot. Both are reported, because a plugin app the user installed and the IDE then
 * dropped in silence is indistinguishable from one the IDE never saw.
 */
sealed interface PluginCandidate {
    /** Where this candidate came from. */
    val origin: PluginOrigin
}

/**
 * A plugin a [PluginSource] found but has not loaded: its parsed manifest plus the means to materialise its
 * code. Discovery is deliberately code-free, so the host can build the enable/disable catalogue, honour the
 * user's disabled set, and render the Plugins screen without executing anything a third party wrote.
 * [classLoader] is called only for a plugin that survives that pass.
 */
interface DiscoveredPlugin : PluginCandidate {
    /** The plugin's declared identity, parsed from its packaged manifest, not from its code. */
    val manifest: PluginManifest

    /** Where this plugin came from. */
    override val origin: PluginOrigin

    /**
     * Build the classloader the plugin's [PluginManifest.entryPoints] are instantiated from. The parent must
     * be the host's own classloader so the plugin's references to the plugin SPI, the Kotlin stdlib, and the
     * Compose runtime resolve to the host's copies rather than to a shadowed second version.
     *
     * Called at most once per load, and only for an enabled plugin. May throw: the caller reports the failure
     * against this plugin rather than failing the IDE's startup.
     */
    fun classLoader(): ClassLoader
}

/**
 * A plugin a source found but cannot offer for loading, with the user-facing [reason]. It has no usable
 * manifest, so there is no id to attribute contributions to or to persist an enable/disable choice against,
 * and it never reaches the plugin catalogue. It is carried only so the Plugins settings screen can show that
 * the IDE saw this plugin and why it was not used.
 */
data class RejectedPlugin(
    override val origin: PluginOrigin,
    /** What is wrong with the plugin, phrased for the user. */
    val reason: String,
    /** The best display name the source could read (an app label), falling back to the package name. */
    val name: String = origin.label,
) : PluginCandidate

/**
 * A place installed plugins come from. The host holds zero or more, queries each at startup, and merges what
 * they return with its built-ins into one catalogue.
 *
 * Implementations are host-specific: on Android a source enumerates installed plugin APKs through the package
 * manager; a desktop source would scan a plugins directory. [discover] may throw or return a partially
 * populated list; the host guards the call, so one broken source cannot stop the IDE from starting.
 */
interface PluginSource {
    /** Stable id of this source, recorded on every [PluginOrigin] it produces. */
    val id: String

    /**
     * Every plugin this source can currently see, in no particular order, including any it found but could
     * not turn into a [DiscoveredPlugin].
     */
    fun discover(): List<PluginCandidate>
}
