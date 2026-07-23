package dev.ide.core

import dev.ide.agent.AgentWorkspace
import dev.ide.agent.DiagnosticInfo
import dev.ide.agent.DiagnosticSeverity
import dev.ide.agent.ModuleInfo
import dev.ide.agent.ProjectOverview
import dev.ide.agent.SymbolHit
import dev.ide.agent.TextEdit
import dev.ide.agent.TextMatch
import dev.ide.agent.WorkspaceEntry
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.ui.backend.UiSearchOptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

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
}
