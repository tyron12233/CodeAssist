package dev.ide.store.impl

import dev.ide.store.StoreResult
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath
import dev.ide.store.impl.platform.nowMillis
import dev.ide.store.impl.platform.openZip
import dev.ide.store.impl.platform.parentPath

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
 *  - **No executables.** A store project is source. A packaged app, dex, jar, native library or compiled
 *    class in the archive is dropped, so a download never puts runnable code on disk; the project builds
 *    its own from the source it ships.
 *
 * Nothing is moved into place until every entry has passed: extraction goes to a staging directory and is
 * renamed at the end, so a rejected archive leaves no half-written project behind.
 */
class PayloadExtractor(
    private val maxUncompressedBytes: Long = MAX_UNCOMPRESSED_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /**
     * Extract the archive at [archivePath] into a fresh directory under [parentPath], named after
     * [preferredName].
     *
     * Returns the created directory's path. A name already in use gets a numeric suffix rather than
     * overwriting someone's existing project.
     */
    fun extract(archivePath: String, parentPath: String, preferredName: String): StoreResult<String> {
        if (!StoreFs.isFile(archivePath)) return StoreResult.Failed("The downloaded archive is missing")
        val staging = joinPath(parentPath, ".store-staging-${nowMillis()}-${counter++}")
        StoreFs.mkdirs(staging)
        val canonicalStaging = StoreFs.canonicalPath(staging)
        val zip = openZip(archivePath) ?: return fail(staging, "The downloaded archive could not be read")
        var entries = 0
        var uncompressed = 0L
        try {
            for (entry in zip.entries()) {
                if (++entries > maxEntries) {
                    return fail(staging, "The project has too many files ($entries, limit $maxEntries)")
                }
                // Reject the escape BEFORE creating anything.
                val target = StoreFs.canonicalPath(joinPath(staging, entry.name))
                if (!target.startsWith("$canonicalStaging/") && target != canonicalStaging) {
                    return fail(staging, "The project contains an unsafe path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    StoreFs.mkdirs(target)
                    continue
                }
                // Host state from the SUBMITTER's device, dropped on the way in as well as on the way
                // out. The packager excludes these now, but every archive published before it did still
                // carries them, and they are actively harmful: `libraries.json`/`sdks.json` name jars
                // under the (never-packaged) `.platform/caches`, and a `.deps-reconciled` whose
                // fingerprint still matches makes the engine SKIP resolving dependencies at all — so the
                // installed project reports every library resolved with nothing on disk, and cannot
                // repair itself. Stripping on extract means an old payload heals on install rather than
                // waiting for its author to republish.
                if (entryName(entry.name) in STRIPPED_ON_INSTALL) continue
                if (isExecutable(entry.name)) continue
                uncompressed += entry.size
                if (uncompressed > maxUncompressedBytes) {
                    return fail(
                        staging,
                        "The project unpacks to more than ${maxUncompressedBytes / 1024 / 1024} MB",
                    )
                }
                StoreFs.mkdirsForFile(target)
                if (zip.extractTo(entry, target) < 0) {
                    return fail(staging, "The project contains a file that could not be unpacked: ${entry.name}")
                }
            }
        } finally {
            zip.close()
        }

        if (entries == 0) return fail(staging, "The downloaded archive is empty")

        val destination = uniqueDirectory(parentPath, preferredName)
        if (!StoreFs.rename(staging, destination)) {
            // A rename can fail across filesystems; fall back to a copy so the install still lands.
            if (!StoreFs.copyRecursively(staging, destination)) {
                return fail(staging, "The project could not be moved into place")
            }
            StoreFs.deleteRecursively(staging)
        }
        return StoreResult.Ok(destination)
    }

    private fun fail(staging: String, message: String): StoreResult<String> {
        StoreFs.deleteRecursively(staging)
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
        /** Host-specific state dropped from any payload on the way in; see the extract loop. Mirrors
         *  `ProjectPackager.EXCLUDED_PATHS`, which keeps it out of new archives. */
        internal val STRIPPED_ON_INSTALL = setOf(
            ".platform/libraries.json",
            ".platform/sdks.json",
            ".platform/.deps-reconciled",
            ".platform/.deps-unresolved",
            ".platform/settings.properties",
            ".platform/open-tabs.txt",
        )

        /** Endings of files that carry runnable code: never extracted (see the class notes). Mirrors the
         *  executable part of `ProjectPackager.EXCLUDED_SUFFIXES`, which keeps them out of new archives. */
        internal val EXECUTABLE_SUFFIXES = listOf(
            ".apk", ".apks", ".aab", ".xapk", ".dex", ".jar", ".aar", ".so", ".class",
        )

        internal fun isExecutable(name: String): Boolean {
            val lower = name.lowercase()
            return EXECUTABLE_SUFFIXES.any { lower.endsWith(it) }
        }

        /** A zip entry name as a `/`-separated relative path, with any leading `./` removed. */
        internal fun entryName(raw: String): String =
            raw.replace('\\', '/').removePrefix("./").trimStart('/')

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
        fun uniqueDirectory(parentPath: String, name: String): String {
            val base = safeName(name)
            var candidate = joinPath(parentPath, base)
            var n = 2
            while (StoreFs.exists(candidate)) {
                candidate = joinPath(parentPath, "$base-$n")
                n++
            }
            return candidate
        }

        /**
         * Distinguishes two staging directories created inside the same millisecond.
         *
         * `System.nanoTime` used to do it, and there is no common-code equivalent with that resolution.
         * A counter is better anyway: it cannot collide at all, where a clock only made it unlikely.
         */
        private var counter = 0
    }
}
