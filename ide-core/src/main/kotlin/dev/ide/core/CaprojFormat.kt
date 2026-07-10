package dev.ide.core

import dev.ide.model.impl.format.Json

/**
 * A shareable CodeAssist project package (`.caproj`): a deflate zip carrying a project's
 * source-of-truth (declared config + sources, never regenerable caches) plus a [CaprojManifest]
 * describing it and an optional embedded preview icon. The internal layout is:
 *
 * ```
 * manifest.json                     the [CaprojManifest]
 * icon.png                          optional raster launcher icon (for the import preview)
 * project/<...>                     the project tree (workspace.json, module.toml, sources, res, ...)
 * deps/<...>                        optional resolved dependencies (only when bundled at export)
 * ```
 *
 * The reader/writer live in [ProjectPackaging]; this object holds the format constants and the
 * manifest model + its JSON codec (built on the same [Json] serializer the model persistence uses).
 */
object CaprojFormat {
    /** Bumped when the on-disk package layout changes incompatibly. A package with a higher [format]
     *  than this build understands is rejected in the import preview. */
    const val FORMAT_VERSION = 1

    /** The custom file extension (no dot). */
    const val EXTENSION = "caproj"

    /** The MIME type registered for share/open-with on Android. */
    const val MIME = "application/x-caproj"

    /** Product name stamped into [CaprojManifest.createdBy]. */
    const val APP_NAME = "CodeAssist"

    const val KIND_PROJECT = "project"

    const val MANIFEST_ENTRY = "manifest.json"
    const val ICON_ENTRY = "icon.png"
    /** Zip-entry prefix for the project tree. */
    const val PROJECT_PREFIX = "project/"
    /** Zip-entry prefix for bundled resolved dependencies. */
    const val DEPS_PREFIX = "deps/"
    /** Zip-entry prefix for Explore/Store metadata (screenshots); package-only, never extracted on import. */
    const val STORE_PREFIX = "store/"

    /** Serialize [manifest] to the JSON written as `manifest.json`. */
    fun encode(manifest: CaprojManifest): String = Json.write(
        linkedMapOf(
            "format" to manifest.format,
            "kind" to manifest.kind,
            "name" to manifest.name,
            "description" to manifest.description,
            "author" to manifest.author,
            "createdBy" to manifest.createdBy,
            "exportedAt" to manifest.exportedAt,
            "isAndroid" to manifest.isAndroid,
            "packageName" to manifest.packageName,
            "moduleCount" to manifest.moduleCount,
            "modules" to manifest.modules,
            "fileCount" to manifest.fileCount,
            "uncompressedSize" to manifest.uncompressedSize,
            "hasBundledDeps" to manifest.hasBundledDeps,
            "iconEntry" to manifest.iconEntry,
            "store" to manifest.store?.let { storeToJson(it) },
        ),
    )

    private fun storeToJson(store: CaprojStoreInfo): Map<String, Any?> = linkedMapOf(
        "summary" to store.summary,
        "category" to store.category,
        "tags" to store.tags,
        "highlights" to store.highlights,
        "language" to store.language,
        "screenshots" to store.screenshotEntries,
    )

    private fun storeFromJson(map: Map<*, *>): CaprojStoreInfo {
        fun strings(key: String): List<String> = (map[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return CaprojStoreInfo(
            summary = map["summary"] as? String ?: "",
            category = map["category"] as? String ?: "",
            tags = strings("tags"),
            highlights = strings("highlights"),
            language = map["language"] as? String,
            screenshotEntries = strings("screenshots"),
        )
    }

    /** Parse a `manifest.json` string, or null when it isn't a well-formed manifest object. */
    fun decode(text: String): CaprojManifest? {
        val map = runCatching { Json.parse(text) as? Map<*, *> }.getOrNull() ?: return null
        fun str(key: String): String = map[key] as? String ?: ""
        fun long(key: String): Long = (map[key] as? Number)?.toLong() ?: 0L
        fun int(key: String): Int = (map[key] as? Number)?.toInt() ?: 0
        fun bool(key: String): Boolean = map[key] as? Boolean ?: false
        val format = map["format"] as? Number ?: return null
        @Suppress("UNCHECKED_CAST")
        val modules = (map["modules"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return CaprojManifest(
            format = format.toInt(),
            kind = (map["kind"] as? String) ?: KIND_PROJECT,
            name = str("name"),
            description = str("description"),
            author = str("author"),
            createdBy = str("createdBy"),
            exportedAt = long("exportedAt"),
            isAndroid = bool("isAndroid"),
            packageName = map["packageName"] as? String,
            moduleCount = int("moduleCount"),
            modules = modules,
            fileCount = int("fileCount"),
            uncompressedSize = long("uncompressedSize"),
            hasBundledDeps = bool("hasBundledDeps"),
            iconEntry = map["iconEntry"] as? String,
            store = (map["store"] as? Map<*, *>)?.let { storeFromJson(it) },
        )
    }
}

/**
 * Metadata describing a `.caproj` package, read for the import preview before anything is extracted.
 * [description]/[author] are collected at export time (not stored in the project model); the rest is
 * derived from the project being exported.
 */
data class CaprojManifest(
    val format: Int,
    val kind: String,
    val name: String,
    val description: String,
    val author: String,
    /** The tool that produced the package (e.g. `CodeAssist`). */
    val createdBy: String,
    /** Epoch milliseconds when the package was written. */
    val exportedAt: Long,
    val isAndroid: Boolean,
    /** The Android application module's namespace (e.g. `com.example.app`), or null when not Android. */
    val packageName: String?,
    val moduleCount: Int,
    val modules: List<String>,
    /** Number of regular files under `project/`. */
    val fileCount: Int,
    /** Total uncompressed size of the `project/` files, in bytes. */
    val uncompressedSize: Long,
    val hasBundledDeps: Boolean,
    /** The embedded icon entry name (`icon.png`), or null when the package carries no raster icon. */
    val iconEntry: String?,
    /** Optional Explore/Store metadata (screenshots, tags, ...); null for a plain project package. */
    val store: CaprojStoreInfo? = null,
)

/**
 * Explore/Store metadata carried by a `.caproj` for publishing to the community catalog. Maps onto the UI's
 * store-item fields. Screenshots live as `store/screenshots/` image entries, named in [screenshotEntries].
 */
data class CaprojStoreInfo(
    val summary: String,
    val category: String,
    val tags: List<String>,
    val highlights: List<String>,
    val language: String?,
    val screenshotEntries: List<String>,
)
