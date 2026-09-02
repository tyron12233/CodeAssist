// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin

import dev.ide.platform.PluginId

/**
 * The plugin SPI version this build of the IDE loads. A plugin built outside the IDE declares the version it
 * was compiled against as [PluginManifest.apiVersion]; a mismatch is rejected at load rather than allowed to
 * fail later as a linkage error. Bumped whenever the SPI changes incompatibly.
 */
const val PLUGIN_API_VERSION: Int = 1

/**
 * The version the SPI artifacts are published under, so a project that compiles against them can be
 * scaffolded with a coordinate that resolves:
 *
 * ```
 * compileOnly("io.github.tyron12233:plugin-api:1.0.0")
 * compileOnly("io.github.tyron12233:platform-core:1.0.0")
 * ```
 *
 * Independent of the IDE's own version, because the SPI changes far less often than the app ships.
 * Whether a plugin is *compatible* is decided by [PLUGIN_API_VERSION] and the manifest's
 * [PluginManifest.minHostVersion], never by this coordinate. The publishing configuration reads this
 * constant, so what a scaffolded project asks for and what is actually published cannot drift.
 */
const val PLUGIN_SPI_VERSION: String = "1.0.0"

/**
 * A plugin's identity and load-order metadata. Built-ins construct this as a Kotlin literal on their entry
 * point; the same shape round-trips through TOML for a future externally-packaged plugin, so the loader for
 * that tier parses into this exact type without an SPI change.
 *
 * The internal (one-classpath) tier uses [id]/[name]/[version]/[apiVersion]/[dependsOn]. The remaining fields
 * are carried but inert until the external/dex tier enforces them: [entryPoints] (the class FQCNs a loader
 * instantiates — unused for built-ins, where the class *is* the entry point), [capabilities] (declared,
 * prompted, and enforced only for untrusted code), [minHostVersion], and [trusted] (built-ins are trusted;
 * an external plugin defaults untrusted).
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    /** Host plugin-SPI/ABI compatibility floor. Bumped when this SPI changes incompatibly. */
    val apiVersion: Int = 1,
    /** Ids of plugins that must load before this one. Drives the topological load order. */
    val dependsOn: List<String> = emptyList(),
    /** One-line human-readable summary, shown in the Plugins settings UI. */
    val description: String = "",
    /** An essential plugin cannot be disabled — the IDE cannot function without it (the platform substrate,
     *  the default language backend + resolution fallback, the engine's core scoped services). Essentials and
     *  everything they transitively depend on stay loaded regardless of the user's disabled set. */
    val essential: Boolean = false,

    // Inert until the external/dex tier (parsed + carried now, enforced by that tier's loader):
    val entryPoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val minHostVersion: String? = null,
    val trusted: Boolean = true,
) {
    /** The attribution id every contribution this plugin makes is tagged with. */
    val pluginId: PluginId get() = PluginId(id)
}
