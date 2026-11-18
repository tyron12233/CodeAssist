package dev.codeassist.csharp.project

import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojGraph
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds project entry points using [CsMainScan].
 *
 * Results are cached per `.cs` file using its modified time and size, so the Run picker and editor run marks
 * don't need to rescan unchanged files.
 */
internal object CsMains {

    private class Cached(
        val modified: Long,
        val size: Long,
        val classes: List<String>,
        val topLevel: Boolean,
    )

    private val cache = ConcurrentHashMap<Path, Cached>()

    private fun sourcesOf(dir: Path): List<Path> {
        val project = Csproj.load(dir)
        return CsprojGraph.sources(dir, project)
    }

    /**
     * Whether any source file contains top-level statements.
     *
     * These become the program's entry point. The compiler ignores other `Main` methods (CS7022) and rejects
     */
    fun hasTopLevelStatements(dir: Path): Boolean = topLevelFiles(dir).isNotEmpty()

    /** Returns source files containing top-level statements. See [compilerMain]. */
    fun topLevelFiles(dir: Path): List<Path> = sourcesOf(dir).filter { scanned(it)?.topLevel == true }

    /**
     * Returns each class containing an entry point, once, in file order.
     *
     * Files with top-level statements are excluded because their `Main` methods are ignored.
     */
    fun classes(dir: Path): List<String> =
        sourcesOf(dir)
            .mapNotNull { scanned(it) }
            .filter { !it.topLevel }
            .flatMap { it.classes }
            .distinct()

    /**
     * Whether the Run picker and editor marks need a separate row for each class.
     *
     * This is needed when there are multiple entry-point classes, or one class alongside top-level statements.
     */
    fun classRows(dir: Path): Boolean {
        val count = classes(dir).size
        return count >= 2 || (count == 1 && hasTopLevelStatements(dir))
    }

    /**
     * Returns the class whose `Main` the module's own row runs.
     *
     * Priority: [override] (Module Settings > Run), `<StartupObject>`, then the first discovered class.
     * Returns null when no entry point is available.
     *
     * With top-level statements, the module runs those unless [override] is set. `<StartupObject>` is ignored.
     */
    fun entry(dir: Path, override: String?): String? {
        if (hasTopLevelStatements(dir)) return override
        return override ?: Csproj.load(dir)?.startupObject ?: classes(dir).firstOrNull()
    }

    /**
     * Returns the value for the compiler's `-main:` argument, or null when the compiler can choose automatically.
     *
     * An explicit [override] or `<StartupObject>` takes priority. Otherwise, the first class is selected only when
     * multiple entry-point classes exist.
     *
     * With top-level statements, only [override] applies. The caller excludes those files from compilation because
     * `-main:` cannot be used alongside top-level statements.
     */
    fun compilerMain(dir: Path, override: String?): String? {
        if (hasTopLevelStatements(dir)) return override
        return override
            ?: Csproj.load(dir)?.startupObject
            ?: classes(dir).takeIf { it.size >= 2 }?.first()
    }

    private fun scanned(file: Path): Cached? {
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(file) }.getOrNull() ?: return null

        cache[file]?.let {
            if (it.modified == modified && it.size == size) return it
        }

        val text = runCatching {
            String(Files.readAllBytes(file), Charsets.UTF_8)
        }.getOrDefault("")

        val found = Cached(
            modified = modified,
            size = size,
            classes = runCatching { CsMainScan.hits(text).map { it.className } }.getOrDefault(emptyList()),
            topLevel = runCatching { CsMainScan.topLevel(text) != null }.getOrDefault(false),
        )

        cache[file] = found
        return found
    }
}