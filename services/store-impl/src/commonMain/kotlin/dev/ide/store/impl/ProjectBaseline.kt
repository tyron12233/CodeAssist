package dev.ide.store.impl

import dev.ide.platform.ProjectFiles
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath
import dev.ide.store.impl.platform.sha256Hex

/**
 * The files a store install put into a project, by content, and what has happened to them since.
 *
 * Recorded when a project is installed from the store, so that when its publisher ships a new version the
 * app can tell an untouched copy (safe to update in place) from one the user has been working in (which an
 * update must not overwrite). Content hashes rather than timestamps: an editor that saves an unchanged file,
 * or a copy that resets every mtime, is not an edit.
 *
 * The walk keeps what [PayloadExtractor] keeps: build output and tool state are not the project's content,
 * they are never installed, and a build writing them is not the user changing anything.
 */
object ProjectBaseline {

    /** What the user did to a project since its baseline. Paths are project-relative, `/`-separated. */
    data class LocalChanges(
        val modified: List<String>,
        val deleted: List<String>,
        val added: List<String>,
    ) {
        /** Files the store installed that are no longer as installed: what an in-place update would undo. */
        val touched: Int get() = modified.size + deleted.size
    }

    /** Relative path to sha256 of every project file under [root]. */
    fun capture(root: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        walk(root, "") { rel, path ->
            StoreFs.readBytes(path)?.let { out[rel] = sha256Hex(it) }
        }
        return out
    }

    /**
     * How the project at [root] differs from [baseline].
     *
     * `.platform/` is left out of the comparison: the IDE writes its own state there (open tabs, the
     * resolved classpath, agent sessions) the moment a project is opened, and none of it is the user editing
     * the project. An update still brings the new version's `.platform` model files; it just does not count
     * the IDE's writes as reasons to refuse.
     */
    fun localChanges(root: String, baseline: Map<String, String>): LocalChanges {
        val now = capture(root)
        val modified = baseline.filter { (rel, hash) -> !isIdeState(rel) && now[rel] != null && now[rel] != hash }.keys
        val deleted = baseline.keys.filter { !isIdeState(it) && it !in now }
        val added = now.keys.filter { !isIdeState(it) && it !in baseline && !isLocalOnly(it) }
        return LocalChanges(modified.sorted(), deleted.sorted(), added.sorted())
    }

    /** IDE state inside the project, which the IDE rewrites on its own. */
    fun isIdeState(rel: String): Boolean = rel == ".platform" || rel.startsWith(".platform/")

    /** Files a device writes for itself and never ships (an SDK path, signing settings). Not user edits. */
    private fun isLocalOnly(rel: String): Boolean = rel.substringAfterLast('/') in LOCAL_ONLY

    private val LOCAL_ONLY = setOf("local.properties", "keystore.properties", ".DS_Store")

    private fun walk(dir: String, prefix: String, visit: (rel: String, path: String) -> Unit) {
        for (name in StoreFs.list(dir).sorted()) {
            val path = joinPath(dir, name)
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (ProjectFiles.isToolState(rel)) continue
            if (StoreFs.isDirectory(path)) {
                if (ProjectFiles.isBuildOutput(rel, isDirectory = true)) continue
                // The installer's staging directories, should one survive a crash beside the project.
                if (name.startsWith(".store-staging-")) continue
                walk(path, rel, visit)
            } else if (!ProjectFiles.isBuildOutput(rel, isDirectory = false)) {
                visit(rel, path)
            }
        }
    }
}
