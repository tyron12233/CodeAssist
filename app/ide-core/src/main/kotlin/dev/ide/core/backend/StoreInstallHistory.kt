package dev.ide.core.backend

import java.io.File

/**
 * The device's own record of what it has installed from the store.
 *
 * It exists for two reasons, and both are things only the device can know:
 *
 *  - The recommendation model is anonymous, so the server holds install ids and has no way to know which
 *    of them is this phone. The seed for "Because you installed X" can therefore only come from here.
 *  - An item the device already has must offer **Open**, not a second download, and opening needs the path
 *    the project was unpacked to. The install's progress carries it for the rest of the session; this
 *    carries it across launches.
 *
 * Most-recent first, deduplicated and capped. Nothing here throws: an install must not fail because a
 * recommendation seed could not be written.
 */
internal class StoreInstallHistory(private val file: () -> File?) {

    /** The most recent install, or null on a device that has installed nothing from the store. */
    fun mostRecent(): String? = read().firstOrNull()

    /** Every remembered install id, newest first. */
    fun read(): List<String> = entries().map { it.first }

    /**
     * Item id to the project directory it was unpacked into, for the installs that still exist on disk.
     *
     * A project the user has since deleted is dropped rather than reported: the store would otherwise
     * offer to open a folder that is not there, which is worse than offering to install it again.
     */
    fun installedPaths(): Map<String, String> = entries()
        .mapNotNull { (id, path) -> path?.takeIf { File(it).isDirectory }?.let { id to it } }
        .toMap()

    /**
     * Push an install onto the front, with the directory it landed in when there is one.
     *
     * An id already present MOVES to the front rather than being duplicated, and a re-install replaces the
     * remembered path: the newest unpack is the one on disk. A null [rootPath] means "I do not know where
     * it went", which KEEPS whatever was remembered before: the counting path calls this without one, and
     * must not erase what the unpacking path just recorded.
     */
    fun remember(id: String, rootPath: String? = null) {
        if (id.isBlank()) return
        val target = file() ?: return
        val known = entries()
        val path = rootPath ?: known.firstOrNull { it.first == id }?.second
        val previous = known.filterNot { it.first == id }
        val next = (listOf(id to path) + previous).take(LIMIT)
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(next.joinToString("\n") { (entry, path) -> if (path == null) entry else "$entry\t$path" })
        }
    }

    /**
     * The file's lines as id/path pairs.
     *
     * Tab-separated, and a line with no tab is an id on its own, which is exactly what every line written
     * before installs remembered their path looks like, so an existing device keeps its recommendation
     * seed instead of starting over.
     */
    private fun entries(): List<Pair<String, String?>> =
        file()?.takeIf { it.isFile }
            ?.let { runCatching { it.readLines() }.getOrNull() }
            ?.filter { it.isNotBlank() }
            ?.map { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) line to null else line.substring(0, tab) to line.substring(tab + 1).ifBlank { null }
            }
            ?.filter { it.first.isNotBlank() }
            .orEmpty()

    private companion object {
        /**
         * How many installs to keep.
         *
         * Only the head seeds the recommendation shelf. The rest are kept because they answer "is this one
         * already installed, and where?" for every row the store draws, and because a seed the server no
         * longer publishes yields an empty shelf, where the obvious next step is the one before it.
         */
        const val LIMIT = 50
    }
}
