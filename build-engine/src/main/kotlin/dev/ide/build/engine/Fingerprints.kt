package dev.ide.build.engine

import dev.ide.build.TaskInputs
import dev.ide.build.TaskOutputs
import dev.ide.model.ClasspathSnapshot
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.file.Paths

internal fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
internal fun MessageDigest.put(s: String) { update(s.toByteArray(Charsets.UTF_8)); update(0) }

private fun MessageDigest.putFileContent(path: Path) {
    if (Files.isRegularFile(path)) {
        put(path.toString())
        update(runCatching { Files.readAllBytes(path) }.getOrDefault(ByteArray(0)))
    }
}

private fun MessageDigest.putDirContent(dir: Path) {
    if (!Files.isDirectory(dir)) return
    val files = runCatching {
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
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
