package dev.ide.core.project

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * A project handed over as a `.zip` (a GitHub "Download ZIP", a wizard's output, a folder zipped on another
 * device): unpacked into a staging folder, then searched for the project inside, because the project is rarely
 * the archive's root. A download wraps it in `repo-main/`, and a samples repository holds a dozen projects side by
 * side. The import itself is the folder import's; this only gets the folder to it.
 */
object ProjectArchives {

    /** What an archive held: where it was unpacked, and the project roots found in it, shallowest first. */
    class Contents(val root: Path, val projects: List<Found>)

    /** One project root inside an archive, with [relativePath] for telling several apart ("" for the root). */
    class Found(val path: Path, val relativePath: String, val kind: ImportableKind)

    /** Thrown when an archive's entries would unpack past [MAX_BYTES] or [MAX_ENTRIES]: a zip bomb, or not a project. */
    class TooLarge(message: String) : IOException(message)

    /** Far beyond any project's sources; the cap is there for archives that only claim to be one. */
    const val MAX_BYTES: Long = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRIES: Int = 200_000

    /** How deep to look for a project root: `repo-main/samples/App` is three levels down. */
    private const val SEARCH_DEPTH = 4

    /**
     * Unpack [archive] into [dest] (created; expected empty) and return the project roots [inspect] recognizes in
     * it. Null when [archive] is not a zip at all.
     *
     * Skipped while unpacking: macOS's `__MACOSX` resource forks and `.DS_Store`, build outputs and the Gradle
     * cache (`build/`, `.gradle/`; the import would drop them anyway, and they are most of a zipped project's
     * bytes), and installable packages and raw dex (`.apk`, `.aab`, `.dex`, ...), which are build products,
     * never sources. An entry whose name would land outside [dest] fails the whole archive.
     */
    fun extract(archive: Path, dest: Path, inspect: (Path) -> ImportableKind): Contents? {
        val zip = try {
            ZipFile(archive.toFile())
        } catch (e: ZipException) {
            return null
        }
        val root = dest.toAbsolutePath().normalize()
        zip.use {
            Files.createDirectories(root)
            var written = 0L
            var count = 0
            for (entry in zip.entries()) {
                if (++count > MAX_ENTRIES) throw TooLarge("The archive has more than $MAX_ENTRIES entries")
                val name = entry.name.replace('\\', '/')
                if (skipped(name)) continue
                val target = root.resolve(name).normalize()
                if (!target.startsWith(root) || target == root) {
                    if (target == root) continue
                    throw IOException("The archive entry '$name' points outside the project")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(target).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > MAX_BYTES) throw TooLarge("The archive unpacks to more than ${MAX_BYTES shr 30} GB")
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
        return Contents(root, find(root, inspect))
    }

    /**
     * The project roots under [root], shallowest first, not looking inside one once found (its modules are part
     * of it). A root that is itself a project is the only answer.
     */
    fun find(root: Path, inspect: (Path) -> ImportableKind): List<Found> {
        val found = ArrayList<Found>()
        var level = listOf(root)
        for (depth in 0..SEARCH_DEPTH) {
            val next = ArrayList<Path>()
            for (dir in level) {
                val kind = runCatching { inspect(dir) }.getOrDefault(ImportableKind.NONE)
                if (kind != ImportableKind.NONE) {
                    found += Found(dir, root.relativize(dir).toString().replace('\\', '/'), kind)
                    continue
                }
                runCatching {
                    Files.newDirectoryStream(dir).use { children ->
                        children.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                            .sortedBy { it.fileName.toString().lowercase() }
                            .forEach(next::add)
                    }
                }
            }
            if (next.isEmpty()) break
            level = next
        }
        return found
    }

    private val PACKAGES = listOf(".apk", ".apks", ".aab", ".xapk", ".dex")

    private fun skipped(name: String): Boolean {
        val segments = name.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true
        if (segments.first() == "__MACOSX" || segments.last() == ".DS_Store") return true
        // A directory segment named so; the last segment of a file entry is the file itself.
        val dirs = if (name.endsWith("/")) segments else segments.dropLast(1)
        if (dirs.any { it == "build" || it == ".gradle" }) return true
        return !name.endsWith("/") && PACKAGES.any { segments.last().endsWith(it, ignoreCase = true) }
    }
}
