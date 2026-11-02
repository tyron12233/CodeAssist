package dev.ide.android.support.resources

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * A [StdlibResourceModel] that remembers each res XML file's parse, so a rebuild re-parses only the files that
 * changed. The IDE rebuilds the repository whenever an open `res/` buffer changes (an unsaved `<string>` must
 * reach the synthetic `R` before save); without this, every such rebuild re-parsed every values file of the
 * module AND of its dependencies (Material/AndroidX ship hundreds), to pick up a one-line edit.
 *
 * A remembered parse is reused while its file is unchanged: for a file with an open editor buffer, while the
 * buffer text is equal; otherwise while its size and modification time are. One instance per engine; entries
 * under a parsed res dir that the walk no longer finds are dropped, so deleted files don't linger.
 */
class CachingResourceModel : ResourceModel {

    private class Entry(val buffer: String?, val size: Long, val modified: Long, val contribution: StdlibResourceModel.FileContribution)

    private val entries = ConcurrentHashMap<Path, Entry>()

    /** How many file parses the last [parse] actually ran (the rest were reused). For tests and perf logs. */
    @Volatile var lastParsedFiles: Int = 0
        private set

    override fun parse(resDirs: List<Path>, textOverride: (Path) -> String?): ResourceRepository {
        val seen = HashSet<Path>()
        var parsed = 0
        val repo = StdlibResourceModel.parseWith(resDirs) { kind, file, qualifier ->
            seen.add(file)
            val buffer = textOverride(file)
            val size = if (buffer == null) runCatching { Files.size(file) }.getOrDefault(-1L) else -1L
            val modified = if (buffer == null) runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L) else -1L
            val hit = entries[file]?.takeIf {
                if (buffer != null) it.buffer == buffer else it.buffer == null && it.size == size && it.modified == modified && size >= 0
            }
            hit?.contribution ?: StdlibResourceModel.contribution(kind, file, qualifier, textOverride).also {
                parsed++
                entries[file] = Entry(buffer, size, modified, it)
            }
        }
        val roots = resDirs.map { it.toAbsolutePath().normalize() }
        entries.keys.removeIf { p -> p !in seen && roots.any { p.toAbsolutePath().normalize().startsWith(it) } }
        lastParsedFiles = parsed
        return repo
    }

    /** Forget every remembered parse (memory pressure). */
    fun clear() = entries.clear()
}
