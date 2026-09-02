package dev.ide.core.plugins

import dev.ide.model.impl.format.Toml
import dev.ide.plugin.PluginManifest

/**
 * Reads an installed plugin's packaged manifest. Built-ins carry a [PluginManifest] as a Kotlin literal on
 * their entry point; a plugin built outside the IDE ships the same shape as TOML, and this is the only place
 * that text becomes a manifest.
 *
 * ```toml
 * [plugin]
 * id = "com.example.hello"
 * name = "Hello"
 * version = "1.0.0"
 * apiVersion = 1
 * description = "Adds a Hello tool window."
 * entryPoints = ["com.example.hello.HelloPlugin"]
 * uiEntryPoints = ["com.example.hello.HelloUiPlugin"]
 * dependsOn = ["kotlin-language"]
 * capabilities = ["ui.toolWindow"]
 * minHostVersion = "3.11.0"
 * ```
 *
 * Two fields in [PluginManifest] are the host's to decide and are ignored here whatever the file says:
 * `essential` (a plugin cannot make itself undisablable) and `trusted` (which follows from the origin's
 * signature, not from a self-declaration).
 */
object PluginManifestToml {

    /** Parse [text]. Throws [IllegalArgumentException] with a user-facing message on anything malformed. */
    fun parse(text: String): PluginManifest {
        val doc = try {
            Toml.parse(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("not valid TOML: ${e.message}")
        }
        // Accept both a `[plugin]` table and bare top-level keys.
        val table = (doc["plugin"] as? Map<*, *>) ?: doc

        val id = string(table, "id") ?: throw IllegalArgumentException("manifest has no 'id'")
        require(ID.matches(id)) { "plugin id '$id' must be letters, digits, '.', '-' or '_'" }
        val entryPoints = strings(table, "entryPoints")
        val uiEntryPoints = strings(table, "uiEntryPoints")
        // Either list alone is a complete plugin: engine-only, UI-only, or both. Neither is nothing.
        require(entryPoints.isNotEmpty() || uiEntryPoints.isNotEmpty()) {
            "plugin '$id' declares no 'entryPoints' or 'uiEntryPoints'"
        }

        return PluginManifest(
            id = id,
            name = string(table, "name") ?: id,
            version = string(table, "version") ?: "1.0.0",
            apiVersion = (table["apiVersion"] as? Long)?.toInt() ?: 1,
            dependsOn = strings(table, "dependsOn"),
            description = string(table, "description") ?: "",
            essential = false,
            entryPoints = entryPoints,
            uiEntryPoints = uiEntryPoints,
            capabilities = strings(table, "capabilities"),
            minHostVersion = string(table, "minHostVersion"),
            trusted = false,
        )
    }

    /**
     * The shape of a plugin id. Deliberately as permissive as an Android `applicationId` or a Java package,
     * since that is what a plugin id is normally derived from: rejecting the capitals in a name like
     * `com.exampleApp.plugin` would refuse an id the author has every reason to expect works. Case is part of
     * the id, so it must be written the same way wherever another plugin names it in `dependsOn`; two ids
     * that differ only in case are treated as one and the second is rejected, so no two plugins can be
     * distinguished only by capitalisation.
     */
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    private fun string(table: Map<*, *>, key: String): String? =
        (table[key] as? String)?.trim()?.ifEmpty { null }

    private fun strings(table: Map<*, *>, key: String): List<String> = when (val v = table[key]) {
        is List<*> -> v.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
        is String -> v.split(',').mapNotNull { it.trim().ifEmpty { null } }
        else -> emptyList()
    }
}
