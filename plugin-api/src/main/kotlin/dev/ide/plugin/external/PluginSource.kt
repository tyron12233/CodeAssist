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
 * A plugin a [PluginSource] found but has not loaded: its parsed manifest plus the means to materialise its
 * code. Discovery is deliberately code-free, so the host can build the enable/disable catalogue, honour the
 * user's disabled set, and render the Plugins screen without executing anything a third party wrote.
 * [classLoader] is called only for a plugin that survives that pass.
 */
interface DiscoveredPlugin {
    /** The plugin's declared identity, parsed from its packaged manifest, not from its code. */
    val manifest: PluginManifest

    /** Where this plugin came from. */
    val origin: PluginOrigin

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

    /** Every plugin this source can currently see, in no particular order. */
    fun discover(): List<DiscoveredPlugin>
}
