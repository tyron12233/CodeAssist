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

    // Build / run.

    /** Compiles [module] (defaulting to the project's main module when null) and runs its `main` on the
     *  in-process VM, feeding [stdin] then EOF, capturing output + exit code + compile errors. Headless: it
     *  does not touch the interactive run console. Defaults to unsupported so a non-engine host / test fake
     *  need not implement it. */
    suspend fun runProgram(module: String?, stdin: String = ""): RunResult =
        RunResult(compiled = false, finished = false, output = "", exitCode = null, diagnostics = listOf("Running a program is not supported here."))

    // Code intelligence.

    /** Declaration location(s) of the symbol at [offset] in [path], via the engine's semantic navigation.
     *  Supported for Kotlin sources and Android resource references today; empty when nothing resolves. */
    suspend fun goToDefinition(path: String, offset: Int): List<Location> = emptyList()

    /** Every reference to the symbol at [offset] in [path], project-wide (Java/JDT today). */
    suspend fun findReferences(path: String, offset: Int): List<Location> = emptyList()

    /** Compiler and analyzer diagnostics across every source file in the project. When [errorsOnly] is set,
     *  warnings and infos are dropped. */
    suspend fun projectDiagnostics(errorsOnly: Boolean = false): List<FileDiagnostic> = emptyList()

    /** The renameable symbol at [offset] in [path] (its current name + a kind label), or null when the
     *  position is not a renameable symbol. */
    suspend fun prepareRename(path: String, offset: Int): RenameTargetInfo? = null

    /** Semantically rename the symbol at [offset] in [path] to [newName] across the whole project, applying
     *  the multi-file edit to disk and the editor overlays. */
    suspend fun renameSymbol(path: String, offset: Int, newName: String): RenameResult =
        RenameResult(success = false, message = "Rename is not supported here.")

    /** The quick fixes and intentions available on 1-based [line] of [path], addressed by their index. */
    suspend fun quickFixes(path: String, line: Int): List<QuickFixInfo> = emptyList()

    /** Apply the quick fix at [index] (from [quickFixes]) on 1-based [line] of [path], persisting the edits.
     *  Returns a short status. */
    suspend fun applyQuickFix(path: String, line: Int, index: Int): String =
        throw UnsupportedOperationException("Quick fixes are not supported here.")

    /** Reformat the whole file with the active code style, persisting the change. Returns a short status. */
    suspend fun formatFile(path: String): String =
        throw UnsupportedOperationException("Formatting is not supported here.")

    /** Sort and prune the file's imports, persisting the change. Returns a short status. */
    suspend fun organizeImports(path: String): String =
        throw UnsupportedOperationException("Organize imports is not supported here.")

    // Build & dependencies.

    /** The runnable build/run tasks for the open project (run a Java main, assemble an APK, and so on). */
    suspend fun listTasks(): List<TaskInfo> = emptyList()

    /** Run a build/run task by [id] (from [listTasks]), waiting for it to finish, and report the outcome. */
    suspend fun runTask(id: String): TaskRunResult =
        TaskRunResult(success = false, status = "unavailable", log = "Running tasks is not supported here.")

    /** Search Maven Central and Google Maven for a dependency coordinate matching [query]; when [module] is
     *  given, each hit is judged for compatibility with that module. */
    suspend fun searchDependency(query: String, module: String? = null): List<ArtifactHit> = emptyList()

    // Memory.

    /** The agent's project memory: the project's instruction files plus the persisted agent notes. */
    suspend fun readMemory(): String = ""

    /** Replace the persisted agent notes with [content] (the project's own instruction files are read-only).
     *  Returns a short status. */
    suspend fun writeMemory(content: String): String =
        throw UnsupportedOperationException("Memory is not supported here.")

    // Web.

    /** Fetch [url] over HTTP(S) and return its readable text, truncated to about [maxChars] characters. */
    suspend fun fetchUrl(url: String, maxChars: Int = 20_000): String =
        throw UnsupportedOperationException("Web fetch is not supported here.")

    /** Make an arbitrary HTTP(S) request (curl-like) and return a status line, key response headers, and the
     *  body (truncated to [maxChars]). [headers] are "Name: value" strings; [body] is sent for POST/PUT/PATCH. */
    suspend fun httpRequest(
        method: String,
        url: String,
        headers: List<String> = emptyList(),
        body: String? = null,
        maxChars: Int = 20_000,
    ): String = throw UnsupportedOperationException("HTTP requests are not supported here.")
}

/**
 * The outcome of [AgentWorkspace.runProgram]: whether the module [compiled] (its `main` started), [finished]
 * running (vs. timed out), its captured [output], its process [exitCode] (null if it never finished), and any
 * compile-error [diagnostics].
 */
data class RunResult(
    val compiled: Boolean,
    val finished: Boolean,
    val output: String,
    val exitCode: Int?,
    val diagnostics: List<String>,
)

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

/** A resolved code location: a file [path] (or a `library://` synthetic path for a compiled dependency) at
 *  1-based [line]/[column], with an optional human [label]. [line] is 0 when only a synthetic path is known. */
data class Location(val path: String, val line: Int, val column: Int, val label: String = "")

/** A diagnostic tagged with the file it belongs to (the project-wide counterpart of [DiagnosticInfo]). */
data class FileDiagnostic(
    val path: String,
    val line: Int,
    val column: Int,
    val severity: DiagnosticSeverity,
    val message: String,
)

/** A renameable symbol: its current [oldName] and a human [kind] label ("method", "class", …). */
data class RenameTargetInfo(val oldName: String, val kind: String)

/** The outcome of [AgentWorkspace.renameSymbol]. */
data class RenameResult(
    val success: Boolean,
    val message: String,
    val occurrences: Int = 0,
    val filesChanged: Int = 0,
)

/** An available quick fix / intention, addressed by its [index] within the position's action list. */
data class QuickFixInfo(val index: Int, val title: String, val kind: String)

/** A runnable build/run task descriptor. [id] is the dispatch string, [group] one of "run"/"build"/"android". */
data class TaskInfo(val id: String, val label: String, val group: String)

/** The outcome of [AgentWorkspace.runTask]: whether it [success]fully finished, a terminal [status] word, a
 *  trimmed [log] transcript, and any structured [diagnostics]. */
data class TaskRunResult(
    val success: Boolean,
    val status: String,
    val log: String,
    val diagnostics: List<String> = emptyList(),
)

/** A dependency-search hit. [coordinate] is "group:name:version"; [note] explains an incompatibility. */
data class ArtifactHit(
    val coordinate: String,
    val packaging: String,
    val compatible: Boolean = true,
    val note: String? = null,
)
