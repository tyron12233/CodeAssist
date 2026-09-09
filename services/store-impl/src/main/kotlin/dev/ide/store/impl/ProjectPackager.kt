package dev.ide.store.impl

import dev.ide.store.PackagedFile
import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips a project for submission.
 *
 * This is the code that decides what leaves the user's device, so the exclusion list is the important
 * part of the file, not the zipping. Three kinds of thing are dropped:
 *
 *  - **Secrets.** A keystore, `keystore.properties`, `local.properties`, a `.env`. Uploading a signing
 *    key to a public catalog would be unrecoverable, so these are matched by name and never packaged.
 *  - **Build output.** `build/`, `.gradle/`, `.kotlin/`, `bin/`, `out/`. Large, machine-specific, and
 *    regenerated on the recipient's machine anyway.
 *  - **Local state.** `.git/`, `.idea/`, `.DS_Store`. History and editor state are not part of a template.
 *
 * The excluded paths are *reported*, not silently skipped ([PackagedProject.excluded]), so the submit
 * screen can show that a keystore was found and left out rather than merely promising it would be.
 *
 * Everything is streamed and hashed in one pass, so a 5 MB archive never needs to be held in memory
 * twice — which matters on a phone.
 */
class ProjectPackager(
    private val maxBytes: Long = MAX_ARCHIVE_BYTES,
    private val maxFiles: Int = MAX_FILES,
) {

    fun pack(projectRoot: String, into: File? = null): StoreResult<PackagedProject> {
        val root = File(projectRoot)
        if (!root.isDirectory) return StoreResult.Failed("Not a project directory: $projectRoot")

        val included = ArrayList<Pair<File, String>>()
        val excluded = ArrayList<String>()
        collect(root, root, included, excluded)

        if (included.isEmpty()) return StoreResult.Failed("Nothing to submit: every file was excluded")
        if (included.size > maxFiles) {
            return StoreResult.Failed("Too many files to submit (${included.size}, limit $maxFiles)")
        }

        val archive = into ?: File.createTempFile("ca-submission-", ".zip")
        val digest = MessageDigest.getInstance("SHA-256")
        val files = ArrayList<PackagedFile>(included.size)

        try {
            // Sorted so the same project always produces the same archive — a stable hash is what lets a
            // re-submission be recognised as unchanged.
            val ordered = included.sortedBy { it.second }
            ZipOutputStream(DigestingStream(archive.outputStream().buffered(), digest)).use { zip ->
                for ((file, rel) in ordered) {
                    // A fixed timestamp, for the same reason: the mtime would otherwise change the bytes.
                    zip.putNextEntry(ZipEntry(rel).apply { time = FIXED_ENTRY_TIME })
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files += PackagedFile(rel, file.length())
                }
            }
            val total = archive.length()
            if (total > maxBytes) {
                archive.delete()
                return StoreResult.Failed(
                    "Project is too large to submit (${total / 1024 / 1024} MB, limit ${maxBytes / 1024 / 1024} MB)",
                )
            }
            return StoreResult.Ok(
                PackagedProject(
                    files = files,
                    excluded = excluded.sorted(),
                    totalBytes = total,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    archivePath = archive.absolutePath,
                ),
            )
        } catch (e: Exception) {
            archive.delete()
            return StoreResult.Failed(e.message ?: "Could not package the project")
        }
    }

    private fun collect(
        root: File,
        dir: File,
        included: MutableList<Pair<File, String>>,
        excluded: MutableList<String>,
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val rel = child.relativeTo(root).path.replace(File.separatorChar, '/')
            when {
                isExcluded(child, rel) -> excluded += if (child.isDirectory) "$rel/" else rel
                child.isDirectory -> collect(root, child, included, excluded)
                // A symlink could point anywhere on the device, including outside the project.
                !child.isFile -> excluded += rel
                else -> included += child to rel
            }
        }
    }

    private fun isExcluded(f: File, rel: String): Boolean {
        val name = f.name
        if (rel in EXCLUDED_PATHS) return true
        if (f.isDirectory) return name in EXCLUDED_DIRS
        if (name in EXCLUDED_FILES) return true
        return EXCLUDED_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }
    }

    companion object {
        /** Matches the `size_bytes` CHECK and the bucket limit in the Supabase migrations. */
        const val MAX_ARCHIVE_BYTES: Long = 5L * 1024 * 1024

        /** Matches the `file_count` CHECK on `store_item_versions`. */
        const val MAX_FILES: Int = 2000

        /** Zip entries get a fixed mtime so the archive bytes — and therefore the hash — are stable. */
        private const val FIXED_ENTRY_TIME = 0L

        /**
         * Excluded by their position, not their name.
         *
         * `.platform` itself must travel — it holds the project model, and without it the installed copy
         * is a folder rather than a project — but its caches are the index and build state of the
         * *submitter's* device: megabytes against a 5 MB budget, carrying absolute paths from a machine
         * nobody else has. Matched on the relative path so a source directory that happens to be called
         * `caches` is unaffected.
         */
        private val EXCLUDED_PATHS = setOf(".platform/caches")

        private val EXCLUDED_DIRS = setOf(
            "build", ".gradle", ".kotlin", ".idea", ".git", "bin", "out", "node_modules",
            ".externalNativeBuild", ".cxx", "captures", "keystore",
        )

        /**
         * Names that must never be uploaded. `local.properties` holds an SDK path, `keystore.properties`
         * and any `.jks`/`.keystore` hold signing material, `.env` holds whatever the user put there.
         */
        private val EXCLUDED_FILES = setOf(
            "local.properties", "keystore.properties", ".env", ".env.local", ".DS_Store",
            "google-services.json", "agent.properties",
        )

        private val EXCLUDED_SUFFIXES = listOf(
            ".jks", ".keystore", ".p12", ".pem", ".apk", ".aab", ".dex", ".class", ".jar", ".iml", ".log",
        )
    }
}

/** Tees everything written into a [MessageDigest], so hashing costs no second pass over the archive. */
private class DigestingStream(
    private val delegate: java.io.OutputStream,
    private val digest: MessageDigest,
) : java.io.OutputStream() {
    override fun write(b: Int) {
        digest.update(b.toByte())
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        digest.update(b, off, len)
        delegate.write(b, off, len)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
