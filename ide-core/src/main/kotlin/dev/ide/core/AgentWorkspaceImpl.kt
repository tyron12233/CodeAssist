package dev.ide.core

import dev.ide.agent.AgentWorkspace
import dev.ide.agent.ArtifactHit
import dev.ide.agent.DiagnosticInfo
import dev.ide.agent.DiagnosticSeverity
import dev.ide.agent.FileDiagnostic
import dev.ide.agent.Location
import dev.ide.agent.ModuleInfo
import dev.ide.agent.ProjectOverview
import dev.ide.agent.QuickFixInfo
import dev.ide.agent.RenameResult
import dev.ide.agent.RenameTargetInfo
import dev.ide.agent.RunResult
import dev.ide.agent.SymbolHit
import dev.ide.agent.TaskInfo
import dev.ide.agent.TaskRunResult
import dev.ide.agent.TextEdit
import dev.ide.agent.TextMatch
import dev.ide.agent.WorkspaceEntry
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.NavKind
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiSearchOptions
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * [AgentWorkspace] over the active engine. Every call resolves the current [IdeServices] via [ctx] and runs
 * on the engine's background lane, so the index, analyzers, and editor overlays stay consistent. Writes
 * assume the permission gate has already cleared (the agent loop gates mutating tools before calling here).
 */
internal class IdeAgentWorkspace(private val ctx: BackendContext) : AgentWorkspace {

    private fun engine(): IdeServices =
        ctx.servicesOrNull ?: throw IllegalStateException("No project is open.")

    /**
     * Resolves an agent-supplied path. Absolute paths are used as-is; a relative path is anchored to the
     * open project's root, never the process working directory. On the Android runtime the working
     * directory is the filesystem root ("/"), which the app sandbox can neither list nor write, so an
     * unanchored relative path lands outside the project and fails with a permission error. Anchoring to
     * [IdeServices.workspaceRoot] keeps the model's workspace-relative paths inside the project folder.
     */
    private fun path(p: String): Path {
        val raw = Paths.get(p)
        if (raw.isAbsolute) return raw.normalize()
        val root = ctx.servicesOrNull?.workspaceRoot ?: return raw.normalize()
        return root.resolve(raw).normalize()
    }

    override fun projectRoot(): String? = ctx.servicesOrNull?.workspaceRoot?.toString()

    override suspend fun readFile(path: String, startLine: Int?, endLine: Int?): String = ctx.background {
        sliceLines(engine().readCurrentText(path(path)), startLine, endLine)
    }

    override suspend fun listDir(path: String): List<WorkspaceEntry> = ctx.background {
        val dir = path(path)
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { stream ->
                stream.map { WorkspaceEntry(it.fileName.toString(), it.toString(), Files.isDirectory(it)) }
                    .collect(Collectors.toList())
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    override suspend fun searchText(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        limit: Int,
    ): List<TextMatch> = ctx.background {
        engine().search.findInFiles(
            query,
            UiSearchOptions(regex = regex, wholeWord = false, caseSensitive = caseSensitive),
            limit,
        ).map { TextMatch(it.filePath, it.line, it.col, it.lineText) }
    }

    override suspend fun findSymbol(query: String, limit: Int): List<SymbolHit> = ctx.background {
        val search = engine().search
        val symbols = search.searchSymbols(query, limit).map {
            SymbolHit(it.name, it.kind, search.symbolFilePath(it.fileId), 0)
        }
        val members = search.searchMembers(query, (limit - symbols.size).coerceAtLeast(0)).map {
            SymbolHit(it.name, it.kind, null, 0)
        }
        (symbols + members).take(limit)
    }

    override suspend fun diagnostics(path: String): List<DiagnosticInfo> = ctx.background {
        val file = path(path)
        val text = engine().readCurrentText(file)
        engine().analyzeDiagnostics(file, text).map { d ->
            val (line, col) = lineColumn(text, d.range.start)
            DiagnosticInfo(line, col, mapSeverity(d.severity), d.message)
        }
    }

    override suspend fun projectOverview(): ProjectOverview = ctx.background {
        val e = engine()
        val modules = e.modules()
        val name = modules.firstOrNull()?.let { e.moduleRoot(it)?.parent?.fileName?.toString() } ?: "project"
        ProjectOverview(
            name = name,
            modules = modules.map { m ->
                ModuleInfo(
                    name = m.name,
                    type = m.type.toString(),
                    languageLevel = m.languageLevel.toString(),
                    sourceRoots = e.sourceRoots(m).map { it.toString() },
                    dependencies = m.dependencies.map { it.toString() },
                )
            },
        )
    }

    override suspend fun createFile(path: String, content: String): String = ctx.background {
        val file = path(path)
        if (Files.exists(file)) throw IllegalStateException("File already exists: $path")
        file.parent?.let { Files.createDirectories(it) }
        engine().save(file, content)
        file.toString()
    }.also { ctx.bumpFileSystemEpoch() }

    override suspend fun writeFile(path: String, content: String) {
        ctx.background {
            val file = path(path)
            file.parent?.let { Files.createDirectories(it) }
            engine().save(file, content)
        }
        ctx.bumpFileSystemEpoch()
    }

    override suspend fun applyEdits(path: String, edits: List<TextEdit>) {
        ctx.background {
            engine().applyDocumentEdits(
                path(path),
                edits.map { DocumentEdit(it.offset, it.oldLength, it.newText) },
            )
        }
        ctx.bumpFileSystemEpoch()
    }

    override suspend fun createDir(path: String): String = ctx.background {
        val dir = path(path)
        Files.createDirectories(dir)
        dir.toString()
    }.also { ctx.bumpFileSystemEpoch() }

    override suspend fun renamePath(path: String, newName: String): String {
        val outcome = ctx.background { engine().renameFile(path(path), newName) }
        ctx.bumpFileSystemEpoch()
        if (!outcome.success) throw IllegalStateException(outcome.message)
        return outcome.newPath ?: path(path).resolveSibling(newName).toString()
    }

    override suspend fun movePath(path: String, destDir: String): String {
        val dest = ctx.background { engine().movePath(path(path), path(destDir)) }
            ?: throw IllegalStateException("Could not move $path into $destDir.")
        ctx.bumpFileSystemEpoch()
        return dest.toString()
    }

    override suspend fun deletePath(path: String): Boolean {
        val ok = ctx.background { engine().deletePath(path(path)) }
        ctx.bumpFileSystemEpoch()
        return ok
    }

    override suspend fun addDependency(module: String, coordinate: String): String {
        ctx.background { engine().dependencies.addDependency(module, coordinate, "implementation") }
        ctx.bumpFileSystemEpoch()
        return "Added $coordinate to $module."
    }

    override suspend fun runProgram(module: String?, stdin: String): RunResult {
        val e = engine()
        // Default to the sole/first module; runAndCapture reports a clear error if it has no runnable main,
        // which tells the model to pick another module.
        val moduleName = module?.takeIf { it.isNotBlank() }
            ?: e.modules().firstOrNull()?.name
            ?: throw IllegalStateException("No modules in the project to run.")
        val capture = e.runAndCapture(moduleName, stdin, timeoutMs = RUN_TIMEOUT_MS)
        return RunResult(
            compiled = capture.compiled,
            finished = capture.ran,
            output = capture.stdout,
            exitCode = capture.exitCode,
            diagnostics = capture.diagnostics,
        )
    }

    // ---- code intelligence ----------------------------------------------------------------------

    override suspend fun goToDefinition(path: String, offset: Int): List<Location> = ctx.background {
        val file = path(path)
        val text = engine().readCurrentText(file)
        val textByPath = HashMap<String, String>()
        engine().navigationTargets(file, text, offset, NavKind.DECLARATION).map { t ->
            val p = t.file.path
            if (p.startsWith("library://")) {
                Location(p, 0, 0, t.label) // a compiled dependency: no source line to point at
            } else {
                val body = textByPath.getOrPut(p) { runCatching { engine().readCurrentText(Paths.get(p)) }.getOrDefault("") }
                val (line, col) = lineColumn(body, t.offset)
                Location(p, line, col, t.label)
            }
        }
    }

    override suspend fun findReferences(path: String, offset: Int): List<Location> = ctx.background {
        val file = path(path)
        val text = engine().readCurrentText(file)
        val textByPath = HashMap<Path, String>()
        engine().findReferences(file, text, offset).map { (p, range) ->
            val body = textByPath.getOrPut(p) { runCatching { engine().readCurrentText(p) }.getOrDefault("") }
            val (line, col) = lineColumn(body, range.start)
            Location(p.toString(), line, col)
        }
    }

    override suspend fun projectDiagnostics(errorsOnly: Boolean): List<FileDiagnostic> = ctx.background {
        val e = engine()
        val files = LinkedHashSet<Path>()
        for (m in e.modules()) {
            for (root in e.sourceRoots(m)) {
                if (!Files.isDirectory(root)) continue
                Files.walk(root).use { stream ->
                    stream.filter { Files.isRegularFile(it) }
                        .filter { val n = it.fileName.toString(); n.endsWith(".java") || n.endsWith(".kt") }
                        .forEach { files.add(it.normalize()) }
                }
            }
            if (files.size >= MAX_DIAGNOSTIC_FILES) break
        }
        val out = ArrayList<FileDiagnostic>()
        for (f in files.take(MAX_DIAGNOSTIC_FILES)) {
            val body = runCatching { e.readCurrentText(f) }.getOrNull() ?: continue
            val diags = runCatching { e.analyzeDiagnostics(f, body) }.getOrNull() ?: continue
            for (d in diags) {
                val sev = mapSeverity(d.severity)
                if (errorsOnly && sev != DiagnosticSeverity.ERROR) continue
                val (line, col) = lineColumn(body, d.range.start)
                out += FileDiagnostic(f.toString(), line, col, sev, d.message)
                if (out.size >= MAX_DIAGNOSTICS) return@background out
            }
        }
        out
    }

    override suspend fun prepareRename(path: String, offset: Int): RenameTargetInfo? = ctx.background {
        val file = path(path)
        engine().prepareRename(file, engine().readCurrentText(file), offset)
            ?.let { RenameTargetInfo(it.oldName, it.kind) }
    }

    override suspend fun renameSymbol(path: String, offset: Int, newName: String): RenameResult {
        val file = path(path)
        val outcome = ctx.background { engine().rename(file, engine().readCurrentText(file), offset, newName) }
        ctx.bumpFileSystemEpoch()
        return RenameResult(outcome.success, outcome.message, outcome.occurrences, outcome.filesChanged)
    }

    override suspend fun quickFixes(path: String, line: Int): List<QuickFixInfo> = ctx.background {
        val file = path(path)
        val text = engine().readCurrentText(file)
        val (start, end) = lineRange(text, line)
        engine().editorActions(file, text, start, end)
            .mapIndexed { i, fix -> QuickFixInfo(i, fix.title, fix.kind.name) }
    }

    override suspend fun applyQuickFix(path: String, line: Int, index: Int): String {
        val file = path(path)
        val applied = ctx.background {
            val text = engine().readCurrentText(file)
            val (start, end) = lineRange(text, line)
            val actions = engine().editorActions(file, text, start, end)
            if (index !in actions.indices) return@background null
            val edits = engine().applyEditorAction(file, text, start, end, index)
            if (edits.isNotEmpty()) engine().applyDocumentEdits(file, edits)
            actions[index].title to edits.size
        } ?: throw IllegalArgumentException("No quick fix at index $index on line $line.")
        ctx.bumpFileSystemEpoch()
        return "Applied \"${applied.first}\" (${applied.second} edit(s))."
    }

    override suspend fun formatFile(path: String): String {
        val file = path(path)
        val count = ctx.background {
            val text = engine().readCurrentText(file)
            val edits = engine().formatDocument(file, text, FormatStyle())
            if (edits.isNotEmpty()) engine().applyDocumentEdits(file, edits)
            edits.size
        }
        ctx.bumpFileSystemEpoch()
        return if (count == 0) "Already formatted (no changes)." else "Formatted $path ($count edit(s))."
    }

    override suspend fun organizeImports(path: String): String {
        val file = path(path)
        val count = ctx.background {
            val text = engine().readCurrentText(file)
            val edits = engine().organizeImports(file, text)
            if (edits.isNotEmpty()) engine().applyDocumentEdits(file, edits)
            edits.size
        }
        ctx.bumpFileSystemEpoch()
        return if (count == 0) "Imports already organized (no changes)." else "Organized imports in $path ($count edit(s))."
    }

    // ---- build & dependencies -------------------------------------------------------------------

    override suspend fun listTasks(): List<TaskInfo> = ctx.background {
        engine().build.runTasks().map { TaskInfo(it.id, it.label, it.group) }
    }

    override suspend fun runTask(id: String): TaskRunResult {
        val build = engine().build
        build.runTask(id)
        // Wait for a terminal status, dropping any stale terminal/idle state left by a previous run first, so
        // we observe the build WE launched. Not on the engine lane: collecting suspends until the build ends.
        val terminal = withTimeoutOrNull(TASK_TIMEOUT_MS) {
            build.buildState
                .dropWhile { it.status == RunStatus.Idle || it.status == RunStatus.Succeeded || it.status == RunStatus.Failed }
                .first { it.status == RunStatus.Succeeded || it.status == RunStatus.Failed }
        }
        ctx.bumpFileSystemEpoch()
        if (terminal == null) {
            return TaskRunResult(false, "timed out", "", listOf("The task did not finish within ${TASK_TIMEOUT_MS / 1000}s."))
        }
        val success = terminal.status == RunStatus.Succeeded
        val log = terminal.log.takeLast(MAX_LOG_LINES).joinToString("\n") { it.message }
        val diagnostics = terminal.diagnostics.map { d ->
            val loc = if (d.file != null && d.line >= 0) " (${d.file}:${d.line})" else ""
            "${d.severity} ${d.message}$loc"
        }
        return TaskRunResult(success, if (success) "succeeded" else "failed", log, diagnostics)
    }

    override suspend fun searchDependency(query: String, module: String?): List<ArtifactHit> = ctx.background {
        val e = engine()
        val moduleName = module?.takeIf { it.isNotBlank() } ?: e.modules().firstOrNull()?.name ?: ""
        e.dependencies.searchArtifacts(query, moduleName)
            .map { ArtifactHit(it.coordinate, it.packaging, it.compatible, it.incompatibleReason) }
    }

    // ---- memory ---------------------------------------------------------------------------------

    override suspend fun readMemory(): String = ctx.background {
        val root = ctx.servicesOrNull?.workspaceRoot
        val sb = StringBuilder()
        if (root != null) {
            for (name in INSTRUCTION_FILES) {
                val f = root.resolve(name)
                if (!Files.isRegularFile(f)) continue
                val body = runCatching { f.readText() }.getOrNull()?.trim().orEmpty()
                if (body.isNotEmpty()) sb.append("# ").append(name).append(" (project instructions)\n\n").append(body).append("\n\n")
            }
        }
        val notes = memoryFile()?.takeIf { Files.isRegularFile(it) }?.let { runCatching { it.readText() }.getOrNull() }?.trim()
        if (!notes.isNullOrEmpty()) sb.append("# Agent notes\n\n").append(notes)
        sb.toString().trim()
    }

    override suspend fun writeMemory(content: String): String = ctx.background {
        val f = memoryFile() ?: throw IllegalStateException("No project is open.")
        f.parent?.let { Files.createDirectories(it) }
        f.writeText(content)
        "Saved project memory (${content.length} chars)."
    }

    private fun memoryFile(): Path? =
        ctx.servicesOrNull?.workspaceRoot?.resolve(".platform")?.resolve("agent")?.resolve("MEMORY.md")

    // ---- web ------------------------------------------------------------------------------------

    override suspend fun fetchUrl(url: String, maxChars: Int): String = ctx.background {
        doHttp("GET", url, emptyList(), null, maxChars, fullResponse = false)
    }

    override suspend fun httpRequest(
        method: String,
        url: String,
        headers: List<String>,
        body: String?,
        maxChars: Int,
    ): String = ctx.background { doHttp(method.uppercase(), url, headers, body, maxChars, fullResponse = true) }

    /** One HTTP(S) call over [HttpURLConnection] (no new dependency). [fullResponse] returns a status line +
     *  content type + raw body (the curl view); otherwise it returns readable text, stripping HTML. */
    private fun doHttp(
        method: String,
        url: String,
        headers: List<String>,
        body: String?,
        maxChars: Int,
        fullResponse: Boolean,
    ): String {
        val u = URL(url)
        require(u.protocol == "http" || u.protocol == "https") { "Only http(s) URLs are supported." }
        val conn = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CodeAssist-Agent")
            headers.forEach { h ->
                val idx = h.indexOf(':')
                if (idx > 0) setRequestProperty(h.substring(0, idx).trim(), h.substring(idx + 1).trim())
            }
            if (body != null && method in HTTP_BODY_METHODS) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val status = conn.responseCode
            val ok = status in 200..399
            val stream = (if (ok) conn.inputStream else conn.errorStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
            val contentType = conn.contentType ?: ""
            val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val rendered = if (!fullResponse && contentType.contains("html", ignoreCase = true)) stripHtml(raw) else raw
            val text = if (rendered.length > maxChars) rendered.take(maxChars) + "\n… (truncated)" else rendered
            when {
                fullResponse -> "HTTP $status ${conn.responseMessage ?: ""}\nContent-Type: $contentType\n\n$text"
                !ok -> "HTTP $status ${conn.responseMessage ?: ""}\n\n$text"
                else -> text
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n")
        .trim()

    private fun lineRange(text: String, line: Int): Pair<Int, Int> {
        var offset = 0
        var current = 1
        while (current < line && offset < text.length) {
            val nl = text.indexOf('\n', offset)
            if (nl < 0) { offset = text.length; break }
            offset = nl + 1
            current++
        }
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return offset to end
    }

    private fun sliceLines(text: String, startLine: Int?, endLine: Int?): String {
        if (startLine == null && endLine == null) return text
        val lines = text.split('\n')
        val from = (startLine ?: 1).coerceAtLeast(1)
        val to = (endLine ?: lines.size).coerceAtMost(lines.size)
        if (from > to) return ""
        return lines.subList(from - 1, to).joinToString("\n")
    }

    private fun lineColumn(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var col = 1
        val end = offset.coerceIn(0, text.length)
        var i = 0
        while (i < end) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
            i++
        }
        return line to col
    }

    private fun mapSeverity(severity: Any): DiagnosticSeverity {
        val s = severity.toString().uppercase()
        return when {
            s.contains("ERROR") -> DiagnosticSeverity.ERROR
            s.contains("WARN") -> DiagnosticSeverity.WARNING
            else -> DiagnosticSeverity.INFO
        }
    }

    private companion object {
        /** Cap an agent-triggered run so a long-running or blocked program can't stall the turn. */
        const val RUN_TIMEOUT_MS = 120_000L

        /** Cap an agent-triggered build task (assemble/dex can be slow on a cold cache). */
        const val TASK_TIMEOUT_MS = 300_000L
        /** Tail of the build log returned to the model, to keep the tool result compact. */
        const val MAX_LOG_LINES = 200
        /** Bound a project-wide diagnostics sweep so a huge project can't stall the turn. */
        const val MAX_DIAGNOSTIC_FILES = 400
        const val MAX_DIAGNOSTICS = 500
        /** Per-request connect/read timeout for the web tools. */
        const val HTTP_TIMEOUT_MS = 20_000

        /** HTTP methods that carry a request body. */
        val HTTP_BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

        /** Project instruction files surfaced to the agent as read-only memory (its own notes are separate). */
        val INSTRUCTION_FILES = listOf("AGENTS.md", "CLAUDE.md")
    }
}
