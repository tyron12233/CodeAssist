package dev.ide.build

import dev.ide.model.ClasspathSnapshot
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/*
 * The implementations of [TaskInputs] and [TaskOutputs] every task uses. They live here, beside the
 * interfaces, rather than in the engine: declaring inputs and outputs is the one thing a task cannot skip,
 * and build-api is the module a contributed build plugin compiles against.
 */

internal fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
internal fun MessageDigest.put(s: String) { update(s.toByteArray(Charsets.UTF_8)); update(0) }

private fun MessageDigest.putFileContent(path: Path) {
    val digest = FileDigests.digestOf(path) ?: return
    put(path.toString())
    update(digest)
}

/**
 * Per-file content digests, reused while a file's size, modification time and file key are unchanged, so
 * an up-to-date check stats each declared file instead of reading it. Jars, android.jar and the external
 * dex folder are declared inputs of several tasks; without this every build read all of them, twice for
 * outputs.
 *
 * A digest is reused only when the file's modification time is at least [RACY_WINDOW_MS] older than the
 * moment it was hashed. A file written within that window (a save just before a build, a task output
 * written moments ago) can change again without its timestamp moving on a coarse-grained filesystem, so it
 * is hashed again until it has been stable for the whole window.
 */
private object FileDigests {
    private const val RACY_WINDOW_MS = 2_000L
    private const val MAX_ENTRIES = 100_000
    private const val BUFFER_SIZE = 64 * 1024

    private class Entry(
        val size: Long,
        val modifiedMillis: Long,
        val fileKey: Any?,
        val hashedAtMillis: Long,
        val digest: ByteArray,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The content digest of the regular file at [path], or null when it is missing or not a regular file. */
    fun digestOf(path: Path): ByteArray? {
        val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()
        if (attrs == null || !attrs.isRegularFile) return null
        val key = path.toAbsolutePath().normalize().toString()
        val size = attrs.size()
        val modified = attrs.lastModifiedTime().toMillis()
        val fileKey = attrs.fileKey()
        entries[key]?.let { e ->
            if (e.size == size && e.modifiedMillis == modified && e.fileKey == fileKey &&
                modified + RACY_WINDOW_MS <= e.hashedAtMillis
            ) return e.digest
        }
        val hashedAt = System.currentTimeMillis()
        val digest = hash(path) ?: return ByteArray(0)
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[key] = Entry(size, modified, fileKey, hashedAt, digest)
        return digest
    }

    private fun hash(path: Path): ByteArray? = runCatching {
        val md = sha256()
        val buf = ByteArray(BUFFER_SIZE)
        Files.newInputStream(path).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest()
    }.getOrNull()
}

private fun MessageDigest.putDirContent(dir: Path) {
    if (!Files.isDirectory(dir)) return
    val files = runCatching {
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.sorted().collect(Collectors.toList()) }
    }.getOrDefault(emptyList())
    for (f in files) putFileContent(f)
}

/**
 * Task inputs hashed from **live content** at [fingerprint] time (not at declaration), so a change to an
 * upstream task's output dir — declared here via [dirs] — flows into this task's fingerprint and forces a
 * re-run. That content sensitivity is what makes "change one input → only the affected subgraph re-runs"
 * correct, where a path-only classpath hash would miss it.
 */
class TaskInputsImpl : TaskInputs {
    private val fileGroups = sortedMapOf<String, List<Path>>()
    private val dirGroups = sortedMapOf<String, List<Path>>()
    private val props = sortedMapOf<String, String>()
    private val cps = sortedMapOf<String, String>()

    override fun files(key: String, files: Iterable<VirtualFile>) { fileGroups[key] = files.map { Paths.get(it.path) } }
    /** Directories whose recursive content is part of the input (e.g. a dependency's compiled output). */
    fun dirs(key: String, dirs: Iterable<VirtualFile>) { dirGroups[key] = dirs.map { Paths.get(it.path) } }
    fun filePaths(key: String, paths: Iterable<Path>) { fileGroups[key] = paths.toList() }
    fun dirPaths(key: String, paths: Iterable<Path>) { dirGroups[key] = paths.toList() }
    override fun property(key: String, value: Any?) { props[key] = value.toString() }
    override fun classpath(key: String, cp: ClasspathSnapshot) { cps[key] = cp.fingerprint().value }

    override fun isEmpty(): Boolean = fileGroups.isEmpty() && dirGroups.isEmpty() && props.isEmpty() && cps.isEmpty()

    override fun declaredPaths(): Set<String> =
        (fileGroups.values.flatten() + dirGroups.values.flatten()).mapTo(LinkedHashSet()) { it.toAbsolutePath().normalize().toString() }

    override fun fingerprint(): ContentHash {
        val md = sha256()
        for ((k, fs) in fileGroups) { md.put("F:$k"); fs.sortedBy { it.toString() }.forEach { md.putFileContent(it) } }
        for ((k, ds) in dirGroups) { md.put("D:$k"); ds.sortedBy { it.toString() }.forEach { md.putDirContent(it) } }
        for ((k, v) in props) { md.put("P:$k"); md.put(v) }
        for ((k, v) in cps) { md.put("C:$k"); md.put(v) }
        return ContentHash(md.hex())
    }
}

/** Task outputs hashed from live content, to detect "outputs unchanged since last run" (and tampering). */
class TaskOutputsImpl : TaskOutputs {
    private val fileGroups = sortedMapOf<String, List<Path>>()
    private val dirGroups = sortedMapOf<String, Path>()

    override fun files(key: String, files: Iterable<VirtualFile>) { fileGroups[key] = files.map { Paths.get(it.path) } }
    override fun dir(key: String, dir: VirtualFile) { dirGroups[key] = Paths.get(dir.path) }
    fun filePath(key: String, path: Path) { fileGroups[key] = listOf(path) }
    fun dirPath(key: String, path: Path) { dirGroups[key] = path }

    override fun declaredPaths(): Set<String> =
        (fileGroups.values.flatten() + dirGroups.values).mapTo(LinkedHashSet()) { it.toAbsolutePath().normalize().toString() }

    override fun fingerprint(): ContentHash {
        val md = sha256()
        for ((k, fs) in fileGroups) { md.put("f:$k"); fs.sortedBy { it.toString() }.forEach { md.putFileContent(it) } }
        for ((k, d) in dirGroups) { md.put("d:$k"); md.putDirContent(d) }
        return ContentHash(md.hex())
    }
}
