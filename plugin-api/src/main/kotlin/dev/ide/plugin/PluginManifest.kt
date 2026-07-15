package dev.ide.plugin

import dev.ide.platform.PluginId

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
