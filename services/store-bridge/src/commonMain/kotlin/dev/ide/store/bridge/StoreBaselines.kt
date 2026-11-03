package dev.ide.store.bridge

import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath

/**
 * What each store install put on this device: which version, and the content hash of every file.
 *
 * One small file per item, beside the install ledger rather than inside the project. Inside, it would ride
 * along into a backup, an export or the user's own publish of the project, and it describes this device's
 * install, not the project.
 *
 * Installs made before this existed have no baseline. They still open; they just cannot be told apart from
 * an edited copy, so they are never updated in place (see [StoreUpdates]).
 */
class StoreBaselines(private val directory: () -> String?) {

    data class Baseline(
        val version: String?,
        /** The catalog's monotonic version code; 0 when the catalog row carried none. */
        val versionCode: Int,
        val files: Map<String, String>,
    )

    fun read(itemId: String): Baseline? {
        val text = pathFor(itemId)?.let(StoreFs::readText) ?: return null
        var version: String? = null
        var code = 0
        val files = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t', limit = 3)
            when (parts.firstOrNull()) {
                "version" -> version = parts.getOrNull(1)?.ifBlank { null }
                "code" -> code = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "f" -> if (parts.size == 3) files[parts[2]] = parts[1]
            }
        }
        return Baseline(version, code, files)
    }

    fun write(itemId: String, baseline: Baseline) {
        val path = pathFor(itemId) ?: return
        StoreFs.mkdirsForFile(path)
        val text = buildString {
            append(FORMAT).append('\n')
            append("version\t").append(baseline.version.orEmpty()).append('\n')
            append("code\t").append(baseline.versionCode).append('\n')
            // A tab cannot appear in a path a zip entry passed the extractor with, so it is a safe separator.
            baseline.files.forEach { (rel, hash) -> append("f\t").append(hash).append('\t').append(rel).append('\n') }
        }
        StoreFs.writeText(path, text)
    }

    fun forget(itemId: String) {
        pathFor(itemId)?.let(StoreFs::delete)
        archivePath(itemId)?.let(StoreFs::delete)
    }

    /**
     * Where the installed version's own archive is kept: the base a merge needs, since a hash says whether a
     * file changed but not what it said. Null for an id that is not a slug.
     */
    fun archivePath(itemId: String): String? {
        if (!SLUG.matches(itemId)) return null
        val dir = directory() ?: return null
        return joinPath(dir, "$itemId.zip")
    }

    /** Whether the installed version's archive is on this device, so a merge is possible. */
    fun hasArchive(itemId: String): Boolean = archivePath(itemId)?.let(StoreFs::isFile) == true

    /** Null for an id that is not a slug: the id names a file, and a store row is not trusted to be safe. */
    private fun pathFor(itemId: String): String? {
        if (!SLUG.matches(itemId)) return null
        val dir = directory() ?: return null
        return joinPath(dir, "$itemId.baseline")
    }

    private companion object {
        const val FORMAT = "ca-store-baseline 1"
        val SLUG = Regex("^[a-z0-9][a-z0-9-]{0,80}$")
    }
}
