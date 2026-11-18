package dev.codeassist.csharp.project

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.PlatformKind
import dev.ide.model.SourceSetTemplate
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Represents a C# project as a module.
 *
 * The project directory is also its source root, using a custom role owned by this plugin.
 */
object CsModuleType : ModuleType {

    /** Module type ID used by the host for Run, Build, and editor module matching. */
    const val ID = "csharp-project"

    override val id = ID
    override val displayName = "C# project"
    override val platform: PlatformKind
        get() = DOTNET_PLATFORM

    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate(
            "main",
            DependencyScope.IMPLEMENTATION,
            mapOf(
                "" to setOf(CS_SOURCE, ContentRole.SOURCE),

                // Build output inside the source root: dimmed in the tree and skipped by host
                // sweeps such as indexing, search, and project-wide analysis.
                "bin" to setOf(ContentRole.EXCLUDED),
                "obj" to setOf(ContentRole.EXCLUDED),
            ),
        ),
    )

    override fun defaultFacets(): List<FacetTemplate> = emptyList()

    override fun supportedBuildSystems(): Set<BuildSystemId> =
        setOf(BuildSystemId.NATIVE)

    /** C# source directory role. This is not a Java source root. */
    val CS_SOURCE = ContentRole("csharp-source")

    /** C# uses .NET reference assemblies, never `android.jar` or a JDK. */
    val DOTNET_PLATFORM = PlatformKind("DOTNET")
}

/** Reads project files and metadata from disk. No .NET processes are started here. */
object CsProject {

    /** Directories containing build output or tooling, not user source files. */
    private val SKIPPED = setOf("obj", "bin")

    /** Returns all project `.cs` files, sorted by path, as one compilation unit. */
    fun sources(dir: Path): List<Path> =
        files(dir, Int.MAX_VALUE, skipHidden = true)
            .filter { isSource(dir, it) }
            .sortedBy { it.toString() }

    /**
     * Returns regular files under [dir] up to [maxDepth].
     *
     * Skips `bin/` and `obj/`, and optionally hidden directories. Since the project directory
     * is also the source root, filtering these directories after walking them could mean
     * traversing thousands of generated files.
     */
    internal fun files(dir: Path, maxDepth: Int, skipHidden: Boolean): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()

        val found = ArrayList<Path>()
        val visitor = object : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(
                folder: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (folder == dir) return FileVisitResult.CONTINUE

                val name = folder.fileName.toString()
                return if (name in SKIPPED || (skipHidden && name.startsWith("."))) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (attrs.isRegularFile) found.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: java.io.IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        }

        Files.walkFileTree(dir, emptySet(), maxDepth, visitor)
        return found
    }

    /**
     * Converts a free-form project name into a valid assembly name.
     *
     * Keeps only letters, digits, and underscores, and prefixes names that start with a digit.
     * For example, `My App` becomes `MyApp`.
     */
    fun assemblyName(raw: String): String {
        val name = raw.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "App" }
        return if (name.first().isDigit()) "App$name" else name
    }

    /**
     * Checks whether [path] is a visible `.cs` file outside the directories in [SKIPPED].
     */
    private fun isSource(dir: Path, path: Path): Boolean {
        val fileName = path.fileName.toString()
        if (!Files.isRegularFile(path) || !fileName.endsWith(".cs") || fileName.startsWith(".")) {
            return false
        }

        return dir.relativize(path).none { part ->
            val folder = part.toString()
            folder.startsWith(".") || folder in SKIPPED
        }
    }
}