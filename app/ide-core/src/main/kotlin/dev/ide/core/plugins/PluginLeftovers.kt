package dev.ide.core.plugins

import dev.ide.plugin.impl.pluginDataDirName
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

/**
 * What uninstalled plugins left behind: their `dataDir` and `privateDataDir` (one directory per plugin under
 * each of [roots]), plus any path a plugin is known to have used before `privateDataDir` existed ([legacy]).
 *
 * Android uninstalls a plugin app from its own settings page, so the IDE never gets to ask about the plugin's
 * data at that moment; it finds out on the next launch, when the plugin is no longer discovered. Nothing here
 * deletes on its own. A leftover is offered, with its size, and removed when the user says so: uninstalling to
 * reinstall a build signed with another key is common for plugin authors, and should not cost them their data.
 *
 * A directory counts as left behind only when no installed plugin could own it, compared case-insensitively
 * (shared storage folds case) against every installed id and package name. The caller passes nothing installed
 * it is unsure of: when discovery failed this launch, it offers nothing at all.
 *
 * Plugin names are remembered in a small file beside the data ([NAMES_FILE]), written while the plugins are
 * still installed, so a leftover reads "Dart & Flutter" rather than an id. Sizes and deletes walk without
 * following symbolic links: a plugin's private data may link into its own (read-only) native library
 * directory, and that is neither counted nor touched.
 */
class PluginLeftovers(
    private val roots: List<Path>,
    /** Per plugin id, directories it used before it had a `privateDataDir` (absolute, outside [roots]). */
    private val legacy: Map<String, List<Path>> = emptyMap(),
) {
    data class Leftover(
        /** The directory name under the roots, which is the plugin's id made path-safe. */
        val id: String,
        /** The plugin's name, when it was seen installed before. */
        val name: String?,
        val bytes: Long,
        val paths: List<Path>,
    )

    /**
     * Everything left by plugins that are not installed. [installed] holds every id and package name that may
     * own a directory (installed plugins, built-ins, and packages the IDE could not read a manifest from).
     */
    fun scan(installed: Collection<String>): List<Leftover> {
        val owned = installed.mapTo(HashSet()) { pluginDataDirName(it).lowercase() }
        val byId = LinkedHashMap<String, MutableList<Path>>()
        for (root in roots) {
            val children = runCatching { Files.newDirectoryStream(root).use { it.toList() } }.getOrDefault(emptyList())
            for (dir in children) {
                val name = dir.fileName?.toString() ?: continue
                if (name.startsWith(".") || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
                if (name.lowercase() in owned) continue
                byId.getOrPut(name) { ArrayList() }.add(dir)
            }
        }
        for ((id, paths) in legacy) {
            val name = pluginDataDirName(id)
            if (name.lowercase() in owned) continue
            for (p in paths) if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) byId.getOrPut(name) { ArrayList() }.add(p)
        }
        val names = readNames()
        return byId.map { (id, paths) -> Leftover(id, names[id], paths.sumOf(::treeSize), paths) }
            .sortedByDescending { it.bytes }
    }

    /** Deletes what [id] left (as found by [scan] against [installed]); returns the bytes freed, 0 if nothing. */
    fun delete(id: String, installed: Collection<String>): Long {
        val leftover = scan(installed).firstOrNull { it.id == id } ?: return 0L
        var freed = 0L
        for (p in leftover.paths) freed += deleteTree(p)
        forget(setOf(id))
        return freed
    }

    /** Records [plugins] (id to name) so a plugin uninstalled later is still named. */
    fun remember(plugins: Map<String, String>) {
        if (plugins.isEmpty()) return
        val names = readNames()
        var changed = false
        for ((id, name) in plugins) {
            val key = pluginDataDirName(id)
            if (name.isNotBlank() && names[key] != name) { names[key] = name; changed = true }
        }
        if (changed) writeNames(names)
    }

    private fun forget(ids: Set<String>) {
        val names = readNames()
        if (names.keys.removeAll(ids)) writeNames(names)
    }

    private val namesFile: Path? get() = roots.firstOrNull()?.resolve(NAMES_FILE)

    private fun readNames(): MutableMap<String, String> {
        val file = namesFile ?: return HashMap()
        val props = Properties()
        runCatching { Files.newInputStream(file).use(props::load) }
        return props.stringPropertyNames().associateWithTo(HashMap()) { props.getProperty(it) }
    }

    private fun writeNames(names: Map<String, String>) {
        val file = namesFile ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val props = Properties().apply { names.forEach { (k, v) -> setProperty(k, v) } }
            Files.newOutputStream(file).use { props.store(it, "Names of plugins whose data is here") }
        }
    }

    companion object {
        const val NAMES_FILE = ".plugin-names.properties"

        /** Bytes of the regular files under [path], not following links (a link itself counts as nothing). */
        fun treeSize(path: Path): Long {
            var total = 0L
            runCatching {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            }
            return total
        }

        /** Deletes [path] bottom-up without following links (a link is removed, never its target); bytes freed. */
        fun deleteTree(path: Path): Long {
            var freed = 0L
            runCatching {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val size = if (attrs.isRegularFile) attrs.size() else 0L
                        if (runCatching { Files.delete(file) }.isSuccess) freed += size
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.delete(dir) }
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            return freed
        }
    }
}
