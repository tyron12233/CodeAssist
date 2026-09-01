package dev.ide.store.impl

import dev.ide.store.StoreResult
import java.io.File
import java.util.zip.ZipFile

/**
 * Unpacks a downloaded store payload into the workspace.
 *
 * The archive is **untrusted** — it came from a public bucket, and although a human reviewed the
 * submission, review is not a guarantee about every path inside a zip. So this file is mostly checks:
 *
 *  - **Zip slip.** An entry named `../../../../etc/passwd` (or an absolute path) escapes the destination
 *    when naively resolved. Every entry's resolved path is required to stay inside the target directory.
 *  - **Symlinks and specials.** Zip can carry them; a link pointing outside the workspace is the same
 *    escape by another route. Only regular files and directories are extracted.
 *  - **Size and count ceilings.** A zip bomb is small on disk and enormous unpacked, so the *uncompressed*
 *    total is capped, not just the download.
 *
 * Nothing is moved into place until every entry has passed: extraction goes to a staging directory and is
 * renamed at the end, so a rejected archive leaves no half-written project behind.
 */
class PayloadExtractor(
    private val maxUncompressedBytes: Long = MAX_UNCOMPRESSED_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /**
     * Extract [archive] into a fresh directory under [parent] named [preferredName].
     *
     * Returns the created directory. A name already in use gets a numeric suffix rather than overwriting
     * someone's existing project.
     */
    fun extract(archive: File, parent: File, preferredName: String): StoreResult<File> {
        if (!archive.isFile) return StoreResult.Failed("The downloaded archive is missing")
        val staging = File(parent, ".store-staging-${System.nanoTime()}")
        return try {
            staging.mkdirs()
            val canonicalStaging = staging.canonicalFile
            var entries = 0
            var uncompressed = 0L

            ZipFile(archive).use { zip ->
                for (entry in zip.entries()) {
                    if (++entries > maxEntries) {
                        return fail(staging, "The project has too many files ($entries, limit $maxEntries)")
                    }
                    // Reject the escape BEFORE creating anything.
                    val target = File(staging, entry.name).canonicalFile
                    if (!target.path.startsWith(canonicalStaging.path + File.separator) &&
                        target.path != canonicalStaging.path
                    ) {
                        return fail(staging, "The project contains an unsafe path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    uncompressed += entry.size.coerceAtLeast(0)
                    if (uncompressed > maxUncompressedBytes) {
                        return fail(
                            staging,
                            "The project unpacks to more than ${maxUncompressedBytes / 1024 / 1024} MB",
                        )
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { out -> input.copyTo(out) }
                    }
                }
            }

            if (entries == 0) return fail(staging, "The downloaded archive is empty")

            val destination = uniqueDirectory(parent, preferredName)
            if (!staging.renameTo(destination)) {
                // A rename can fail across filesystems; fall back to a copy so the install still lands.
                staging.copyRecursively(destination, overwrite = false)
                staging.deleteRecursively()
            }
            StoreResult.Ok(destination)
        } catch (e: Exception) {
            staging.deleteRecursively()
            StoreResult.Failed(e.message ?: "The project could not be unpacked")
        }
    }

    private fun fail(staging: File, message: String): StoreResult<File> {
        staging.deleteRecursively()
        return StoreResult.Failed(message)
    }

    companion object {
        /** Uncompressed ceiling. Generous against a 5 MB download, tight enough to stop a zip bomb. */
        const val MAX_UNCOMPRESSED_BYTES: Long = 200L * 1024 * 1024

        const val MAX_ENTRIES: Int = 4000

        /**
         * A directory name that is safe on disk, derived from the project title.
         *
         * Anything that is not a letter, digit, dash or underscore becomes a dash, because the name comes
         * from a catalog row a stranger wrote.
         */
        fun safeName(title: String): String = title
            // Lowercased so the on-disk name is predictable from the catalog slug (already lowercase-kebab)
            // and so two projects cannot differ only by case.
            .lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .split('-').filter { it.isNotBlank() }
            .joinToString("-")
            .take(48)
            .ifBlank { "store-project" }

        /** `name`, or `name-2`, `name-3`… so an install never overwrites an existing project. */
        fun uniqueDirectory(parent: File, name: String): File {
            val base = safeName(name)
            var candidate = File(parent, base)
            var n = 2
            while (candidate.exists()) {
                candidate = File(parent, "$base-$n")
                n++
            }
            return candidate
        }
    }
}
