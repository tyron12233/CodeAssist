package dev.ide.agent

/**
 * The narrow project port the agent's tools act through. The host (ide-core) implements this over the
 * engine, running every call on the engine dispatcher so the index, analyzers, and overlays stay
 * consistent; tests implement it as an in-memory fake. Paths are workspace-relative or absolute strings,
 * as the engine already treats them. Write methods assume the caller has already cleared the permission
 * gate.
 */
interface AgentWorkspace {
    /** The open project's root directory path, or null if no project is open. */
    fun projectRoot(): String?

    // Reads.

    /** File text, overlay-preferred (the live editor buffer if open, else disk). Optional 1-based,
     *  inclusive line window. */
    suspend fun readFile(path: String, startLine: Int? = null, endLine: Int? = null): String

    suspend fun listDir(path: String): List<WorkspaceEntry>

    suspend fun searchText(
        query: String,
        regex: Boolean = false,
        caseSensitive: Boolean = false,
        limit: Int = 100,
    ): List<TextMatch>

    suspend fun findSymbol(query: String, limit: Int = 50): List<SymbolHit>

    /** Compiler and analyzer diagnostics for a single file, over its current text. */
    suspend fun diagnostics(path: String): List<DiagnosticInfo>

    suspend fun projectOverview(): ProjectOverview

    // Writes.

    /** Creates a new file (with intermediate directories), returning its path. Fails if it exists. */
    suspend fun createFile(path: String, content: String): String

    /** Replaces a file's entire content, or creates it if absent. */
    suspend fun writeFile(path: String, content: String)

    /** Applies offset-based edits to an existing file, persisting to disk and the editor overlay. */
    suspend fun applyEdits(path: String, edits: List<TextEdit>)

    suspend fun createDir(path: String): String

    /** Renames a file or directory in place, returning the new path. */
    suspend fun renamePath(path: String, newName: String): String

    /** Moves a file or directory into [destDir], returning the new path. */
    suspend fun movePath(path: String, destDir: String): String

    suspend fun deletePath(path: String): Boolean

    /** Adds a Maven-coordinate dependency to a module, returning a human-readable confirmation. */
    suspend fun addDependency(module: String, coordinate: String): String
}

/** An offset-based text edit: replace [oldLength] characters at [offset] with [newText]. */
data class TextEdit(val offset: Int, val oldLength: Int, val newText: String)

data class WorkspaceEntry(val name: String, val path: String, val isDirectory: Boolean)

data class TextMatch(val path: String, val line: Int, val column: Int, val lineText: String)

data class SymbolHit(val name: String, val kind: String, val path: String?, val line: Int)

enum class DiagnosticSeverity { ERROR, WARNING, INFO }

data class DiagnosticInfo(
    val line: Int,
    val column: Int,
    val severity: DiagnosticSeverity,
    val message: String,
)

data class ProjectOverview(val name: String, val modules: List<ModuleInfo>)

data class ModuleInfo(
    val name: String,
    val type: String,
    val languageLevel: String?,
    val sourceRoots: List<String>,
    val dependencies: List<String>,
)
