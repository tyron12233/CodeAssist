package dev.ide.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Local history: a copy of each saved version of a file, kept beside the project (separate from Git), so an edit
 * can be undone after the editor's own undo is gone (the tab closed, the app restarted, a bad Find and Replace
 * saved).
 *
 * Each file gets a directory under [root] named by a hash of its project-relative path, holding one snapshot per
 * saved version as `<epoch millis>.txt`, newest kept, at most [MAX_SNAPSHOTS] each. The first save of a file also
 * keeps the text it replaced, so the version before any edit is there. Writes happen on a background thread: a
 * save stays as fast as before.
 */
class LocalHistory(private val root: Path, private val projectRoot: Path) {
    private val writer: ExecutorService = BackgroundThreads.single("local-history")

    /** One kept version: [id] is its time (epoch millis), unique within a file. */
    data class Snapshot(val id: Long, val sizeChars: Long)

    /**
     * Keep [after], the text [file] was just saved with. [before] is what was on disk before the save; it is kept
     * too when the file has no history yet, so the version before the first edit survives.
     */
    fun recordSave(file: Path, before: String?, after: String) {
        if (after.length > MAX_CHARS) return
        val dir = dirFor(file) ?: return
        val now = System.currentTimeMillis()
        writer.execute {
            runCatching {
                Files.createDirectories(dir)
                val existing = ids(dir)
                if (existing.isEmpty() && before != null && before != after && before.length <= MAX_CHARS) {
                    dir.resolve("${now - 1}.txt").writeText(before)
                }
                val latest = ids(dir).maxOrNull()
                if (latest != null && runCatching { dir.resolve("$latest.txt").readText() }.getOrNull() == after) return@runCatching
                dir.resolve("$now.txt").writeText(after)
                prune(dir)
            }
        }
    }

    /** The kept versions of [file], newest first. */
    fun snapshots(file: Path): List<Snapshot> {
        val dir = dirFor(file) ?: return emptyList()
        if (!dir.isDirectory()) return emptyList()
        return ids(dir).sortedDescending().map { id -> Snapshot(id, runCatching { Files.size(dir.resolve("$id.txt")) }.getOrDefault(0L)) }
    }

    /** The text of [file]'s version [id], or null when it is gone. */
    fun text(file: Path, id: Long): String? {
        val dir = dirFor(file) ?: return null
        return runCatching { dir.resolve("$id.txt").readText() }.getOrNull()
    }

    /** Wait for pending writes (tests, shutdown). */
    fun flush() {
        writer.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun ids(dir: Path): List<Long> =
        runCatching { dir.listDirectoryEntries("*.txt").mapNotNull { it.name.removeSuffix(".txt").toLongOrNull() } }.getOrDefault(emptyList())

    private fun prune(dir: Path) {
        val ids = ids(dir).sorted()
        if (ids.size <= MAX_SNAPSHOTS) return
        ids.take(ids.size - MAX_SNAPSHOTS).forEach { runCatching { Files.deleteIfExists(dir.resolve("$it.txt")) } }
    }

    /** The history directory of [file], keyed by its path relative to the project; null outside the project. */
    private fun dirFor(file: Path): Path? {
        val abs = file.toAbsolutePath().normalize()
        val project = projectRoot.toAbsolutePath().normalize()
        if (!abs.startsWith(project)) return null
        val rel = project.relativize(abs).joinToString("/")
        val digest = MessageDigest.getInstance("SHA-1").digest(rel.toByteArray(Charsets.UTF_8))
        return root.resolve(digest.take(10).joinToString("") { "%02x".format(it) })
    }

    companion object {
        /** Versions kept per file; older ones are dropped. */
        const val MAX_SNAPSHOTS = 50

        /** A file larger than this is not kept (a generated or data file, not something edited by hand). */
        const val MAX_CHARS = 1_000_000
    }
}
