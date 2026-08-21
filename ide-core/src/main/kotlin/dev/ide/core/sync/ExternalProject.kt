package dev.ide.core.sync

import dev.ide.model.sync.ExternalRepository
import dev.ide.platform.ProgressReporter
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Progress sink for a sync with no UI attached (a folder import, a background open). */
internal object NoSyncProgress : ProgressReporter {
    override fun report(fraction: Double, message: String?) {}
    override fun checkCanceled() {}
    override val isCanceled: Boolean get() = false
}

/**
 * On-disk state for a project whose model came from a foreign build system: which importer owns it, what the
 * last sync could not read, and whether the build files have changed since. All of it lives under
 * `.platform/`, so it travels with the workspace and never touches the user's sources.
 */
internal object ExternalProjectMarker {

    /** The importer that owns this project, the one-line summary shown in the UI, and the last sync's notes. */
    data class Info(val importerId: String, val summary: String, val notes: List<String>)

    private const val MARKER = "external-project"

    /** The marker written by builds that only knew about the Gradle importer. */
    private const val LEGACY_GRADLE_MARKER = "imported-from-gradle"
    private const val LEGACY_GRADLE_IMPORTER = "gradle-compat"

    private fun markerFile(root: Path): Path = root.resolve(".platform").resolve(MARKER)
    private fun legacyFile(root: Path): Path = root.resolve(".platform").resolve(LEGACY_GRADLE_MARKER)

    /** Record that [importerId] owns the project at [root], with the [summary] and [notes] to surface. */
    fun write(root: Path, importerId: String, summary: String, notes: List<String> = emptyList()) {
        val file = markerFile(root)
        Files.createDirectories(file.parent)
        val body = (listOf("importer=$importerId", summary) + notes.filter { it.isNotBlank() })
        file.writeText(body.joinToString("\n", postfix = "\n"))
    }

    /** The recorded owner of [root], or null when the project is a native one. Reads the legacy Gradle
     *  marker too, so a workspace imported by an earlier build keeps its compatibility surface. */
    fun read(root: Path): Info? {
        readOrNull(markerFile(root))?.let { text ->
            val lines = text.lines().filter { it.isNotBlank() }
            val importer = lines.firstOrNull()?.removePrefix("importer=")?.trim().orEmpty()
            if (importer.isNotEmpty()) {
                return Info(importer, lines.getOrElse(1) { "" }, lines.drop(2))
            }
        }
        return readOrNull(legacyFile(root))?.lines()?.filter { it.isNotBlank() }?.let { lines ->
            Info(LEGACY_GRADLE_IMPORTER, lines.firstOrNull().orEmpty(), lines.drop(1))
        }
    }

    fun exists(root: Path): Boolean = read(root) != null

    /** Drop the marker (converting an imported project to a native one). */
    fun clear(root: Path) {
        runCatching { Files.deleteIfExists(markerFile(root)) }
        runCatching { Files.deleteIfExists(legacyFile(root)) }
    }

    private fun readOrNull(path: Path): String? =
        if (Files.isRegularFile(path)) runCatching { path.readText() }.getOrNull() else null
}

/**
 * Tracks the build files a sync read, so the IDE can tell that a project's model is out of date without
 * re-running the importer. The stamp is a name/size/modified-time line per matched file; a sync writes it,
 * and any difference (a changed, added, or deleted file) marks the model stale.
 */
internal object SyncStamp {

    /** Directories never worth walking when matching an importer's patterns. */
    private val PRUNED = setOf("build", ".gradle", ".platform", ".git", ".idea")

    private fun stampFile(root: Path, importerId: String): Path =
        root.resolve(".platform").resolve("sync").resolve("$importerId.stamp")

    /**
     * The files under [root] matching any of [globs] (project-relative, `/`-separated), sorted for a stable
     * stamp. Prunes derived/tooling directories, so a `**` pattern stays cheap on a large project.
     */
    fun match(root: Path, globs: List<String>): List<Path> {
        if (!Files.isDirectory(root) || globs.isEmpty()) return emptyList()
        val fs = FileSystems.getDefault()
        val matchers = globs.mapNotNull { runCatching { fs.getPathMatcher("glob:$it") }.getOrNull() }
        if (matchers.isEmpty()) return emptyList()
        val out = ArrayList<Path>()
        runCatching {
            Files.walk(root).use { stream ->
                for (path in stream) {
                    if (path == root || !Files.isRegularFile(path)) continue
                    val rel = root.relativize(path)
                    if ((0 until rel.nameCount).any { rel.getName(it).toString() in PRUNED }) continue
                    val relPath = fs.getPath(rel.toString().replace('\\', '/'))
                    if (matchers.any { it.matches(relPath) }) out.add(path)
                }
            }
        }
        return out.sorted()
    }

    /** Record [files] as the state the last sync read. */
    fun write(root: Path, importerId: String, files: List<Path>) {
        val file = stampFile(root, importerId)
        runCatching {
            Files.createDirectories(file.parent)
            file.writeText(files.joinToString("\n") { line(root, it) } + "\n")
        }
    }

    /**
     * True when [files] no longer match the recorded stamp. A project with no stamp is stale (it has never
     * been synced by this importer, or the stamp was cleaned), which is what makes the first open offer a sync.
     */
    fun isStale(root: Path, importerId: String, files: List<Path>): Boolean {
        val file = stampFile(root, importerId)
        if (!Files.isRegularFile(file)) return true
        val recorded = runCatching { file.readText() }.getOrNull() ?: return true
        val current = files.joinToString("\n") { line(root, it) } + "\n"
        return recorded != current
    }

    fun clear(root: Path, importerId: String) {
        runCatching { Files.deleteIfExists(stampFile(root, importerId)) }
    }

    private fun line(root: Path, file: Path): String {
        val rel = runCatching { root.relativize(file).toString().replace('\\', '/') }.getOrDefault(file.toString())
        val size = runCatching { Files.size(file) }.getOrDefault(-1L)
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)
        return "$rel\t$size\t$modified"
    }
}

/**
 * The extra Maven repositories an imported project declares, merged into `.platform/repositories.txt` (the
 * tab-separated list the dependency service resolves against). A merge, never a clobber: a repository the
 * user added by hand survives a re-sync, and the well-known defaults are skipped because they are always
 * consulted.
 */
internal object ExternalRepositories {

    private val DEFAULT_URLS = setOf(
        "https://repo1.maven.org/maven2",
        "https://dl.google.com/android/maven2",
    )

    fun merge(root: Path, repositories: List<ExternalRepository>) {
        val file = root.resolve(".platform").resolve("repositories.txt")
        val merged = LinkedHashMap<String, ExternalRepository>()
        if (Files.isRegularFile(file)) {
            runCatching { file.readText() }.getOrNull()?.lineSequence()?.forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[1].isNotBlank()) {
                    merged[parts[1].trimEnd('/')] = ExternalRepository(parts[0], parts[1])
                }
            }
        }
        for (repository in repositories) {
            val key = repository.url.trimEnd('/')
            if (key in DEFAULT_URLS) continue
            merged.putIfAbsent(key, repository)
        }
        if (merged.isEmpty()) return
        Files.createDirectories(file.parent)
        file.writeText(merged.values.joinToString("") { "${it.name}\t${it.url}\n" })
    }
}
