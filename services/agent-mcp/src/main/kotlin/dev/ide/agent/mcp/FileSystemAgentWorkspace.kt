package dev.ide.agent.mcp

import dev.ide.agent.AgentWorkspace
import dev.ide.agent.DiagnosticInfo
import dev.ide.agent.ModuleInfo
import dev.ide.agent.ProjectOverview
import dev.ide.agent.QuickFixInfo
import dev.ide.agent.RenameResult
import dev.ide.agent.SymbolHit
import dev.ide.agent.TextEdit
import dev.ide.agent.TextMatch
import dev.ide.agent.WorkspaceEntry
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A disk-backed [AgentWorkspace] for the standalone stdio server ([Main]), used when no engine is
 * available. File, search, and web operations run directly against the project directory; code
 * intelligence and build/run operations need the engine-backed workspace that ide-core provides, so they
 * report "not supported" (via the [AgentWorkspace] defaults) instead of pretending to work.
 *
 * Paths are resolved relative to the project root unless they are absolute. The project is modelled as a
 * single `app` module whose source roots are the directories that actually exist under the root
 * (`src`, `src/main/java`, `src/main/kotlin`, `src/main/res`, `app/src/main/java`, …).
 */
class FileSystemAgentWorkspace(
    private val root: Path,
    private val maxFileBytes: Long = 4L * 1024 * 1024,
) : AgentWorkspace {

    init {
        require(Files.isDirectory(root)) { "Project root is not a directory: $root" }
    }

    override fun projectRoot(): String = root.toString()

    override suspend fun readFile(path: String, startLine: Int?, endLine: Int?): String {
        val text = Files.readString(resolve(path), StandardCharsets.UTF_8)
        if (startLine == null) return text
        return sliceLines(text, startLine, endLine ?: Int.MAX_VALUE)
    }

    override suspend fun listDir(path: String): List<WorkspaceEntry> {
        val dir = resolve(path)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.sorted().map { entry ->
                val name = entry.fileName.toString()
                WorkspaceEntry(name, root.relativize(entry).toString(), Files.isDirectory(entry))
            }.toList()
        }
    }

    override suspend fun searchText(query: String, regex: Boolean, caseSensitive: Boolean, limit: Int): List<TextMatch> {
        val pattern = if (regex) Regex(query, if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
        else Regex(Regex.escape(query), if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
        val matches = ArrayList<TextMatch>()
        Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { file ->
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                    TEXT_EXTENSIONS.any { file.fileName.toString().endsWith(it) }
            }.forEach { file ->
                if (matches.size >= limit) return@forEach
                if (Files.size(file) > maxFileBytes) return@forEach
                val relative = root.relativize(file).toString()
                Files.readAllLines(file, StandardCharsets.UTF_8).forEachIndexed { index, line ->
                    if (matches.size >= limit) return@forEachIndexed
                    if (pattern.containsMatchIn(line)) {
                        val column = pattern.find(line)?.range?.first?.plus(1) ?: 1
                        matches += TextMatch(relative, index + 1, column, line.trim())
                    }
                }
            }
        }
        return matches
    }

    override suspend fun findSymbol(query: String, limit: Int): List<SymbolHit> = emptyList()

    override suspend fun diagnostics(path: String): List<DiagnosticInfo> = emptyList()

    override suspend fun projectOverview(): ProjectOverview {
        val sourceRoots = SOURCE_ROOTS.filter { Files.isDirectory(root.resolve(it)) }
        return ProjectOverview(
            name = root.fileName?.toString() ?: "project",
            modules = listOf(
                ModuleInfo(
                    name = "app",
                    type = "java",
                    languageLevel = null,
                    sourceRoots = sourceRoots,
                    dependencies = emptyList(),
                ),
            ),
        )
    }

    // --- Writes ---

    override suspend fun createFile(path: String, content: String): String {
        val file = resolve(path)
        require(!Files.exists(file)) { "File already exists: $path" }
        Files.createDirectories(file.parent)
        Files.writeString(file, content, StandardCharsets.UTF_8)
        return path
    }

    override suspend fun writeFile(path: String, content: String) {
        val file = resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content, StandardCharsets.UTF_8)
    }

    override suspend fun applyEdits(path: String, edits: List<TextEdit>) {
        val file = resolve(path)
        var text = if (Files.exists(file)) Files.readString(file, StandardCharsets.UTF_8) else ""
        edits.sortedByDescending { it.offset }.forEach { e ->
            require(e.offset in 0..text.length) { "Edit offset ${e.offset} out of range for $path (length ${text.length})" }
            require(e.offset + e.oldLength <= text.length) { "Edit range out of bounds for $path" }
            text = text.substring(0, e.offset) + e.newText + text.substring(e.offset + e.oldLength)
        }
        Files.writeString(file, text, StandardCharsets.UTF_8)
    }

    override suspend fun createDir(path: String): String {
        Files.createDirectories(resolve(path))
        return path
    }

    override suspend fun renamePath(path: String, newName: String): String {
        val source = resolve(path)
        require(Files.exists(source)) { "Path not found: $path" }
        val target = source.resolveSibling(newName)
        require(!Files.exists(target)) { "Target already exists: $target" }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        return root.relativize(target).toString()
    }

    override suspend fun movePath(path: String, destDir: String): String {
        val source = resolve(path)
        require(Files.exists(source)) { "Path not found: $path" }
        val destination = resolve(destDir).resolve(source.fileName)
        Files.createDirectories(destination.parent)
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        return root.relativize(destination).toString()
    }

    override suspend fun deletePath(path: String): Boolean {
        val target = resolve(path)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        Files.walk(target).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        return true
    }

    override suspend fun addDependency(module: String, coordinate: String): String =
        "Adding dependencies requires the engine-backed workspace (the in-IDE agent)."

    // --- Code intelligence: not available without the engine ---

    override suspend fun quickFixes(path: String, line: Int): List<QuickFixInfo> = emptyList()

    override suspend fun applyQuickFix(path: String, line: Int, index: Int): String =
        "Quick fixes require the engine-backed workspace."

    override suspend fun formatFile(path: String): String = "Formatting requires the engine-backed workspace."

    override suspend fun organizeImports(path: String): String =
        "Organizing imports requires the engine-backed workspace."

    override suspend fun renameSymbol(path: String, offset: Int, newName: String): RenameResult =
        RenameResult(success = false, message = "Semantic rename requires the engine-backed workspace.")

    // --- Memory ---

    override suspend fun readMemory(): String {
        val notes = memoryFile()
        val instruction = instructionFile()
        val parts = mutableListOf<String>()
        instruction?.let {
            if (Files.isRegularFile(it)) parts += "## Instruction file (${root.relativize(it)})\n\n${Files.readString(it, StandardCharsets.UTF_8)}"
        }
        if (Files.isRegularFile(notes)) parts += "## Agent notes\n\n${Files.readString(notes, StandardCharsets.UTF_8)}"
        return parts.joinToString("\n\n")
    }

    override suspend fun writeMemory(content: String): String {
        Files.writeString(memoryFile(), content, StandardCharsets.UTF_8)
        return "Saved agent notes (${memoryFile().fileName})."
    }

    // --- Web ---

    override suspend fun fetchUrl(url: String, maxChars: Int): String = http("GET", url, emptyList(), null, maxChars)

    override suspend fun httpRequest(method: String, url: String, headers: List<String>, body: String?, maxChars: Int): String =
        http(method, url, headers, body, maxChars)

    private fun http(method: String, url: String, headers: List<String>, body: String?, maxChars: Int): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            headers.forEach { header ->
                val colon = header.indexOf(':')
                if (colon > 0) connection.setRequestProperty(header.substring(0, colon).trim(), header.substring(colon + 1).trim())
            }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = if (input == null) "" else input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val truncated = response.take(maxChars) + if (response.length > maxChars) "\n…(truncated)" else ""
            return "HTTP $status ${connection.responseMessage}\n\n$truncated"
        } finally {
            connection.disconnect()
        }
    }

    // --- Helpers ---

    private fun resolve(path: String): Path {
        val p = Paths.get(path)
        return if (p.isAbsolute) p.normalize() else root.resolve(p).normalize()
    }

    private fun memoryFile(): Path = root.resolve(".codeassist-memory.md")

    private fun instructionFile(): Path {
        val candidate = root.resolve("AGENTS.md")
        return candidate.takeIf { Files.isRegularFile(candidate) }
            ?: root.resolve(".agent").resolve("AGENTS.md").takeIf { Files.isRegularFile(it) }
            ?: root.resolve(".codeassist").resolve("AGENTS.md")
    }

    private fun sliceLines(text: String, startLine: Int, endLine: Int): String {
        if (startLine < 1) return text
        val lines = text.lines()
        val first = (startLine - 1).coerceIn(0, lines.size)
        val last = if (endLine == Int.MAX_VALUE) lines.size else endLine.coerceIn(first, lines.size)
        return lines.subList(first, last).joinToString("\n")
    }

    private companion object {
        val SOURCE_ROOTS = listOf(
            "src",
            "src/main/java",
            "src/main/kotlin",
            "src/main/res",
            "src/test/java",
            "app/src/main/java",
            "app/src/main/kotlin",
            "app/src/main/res",
        )

        val TEXT_EXTENSIONS = listOf(
            ".txt", ".kt", ".java", ".xml", ".gradle", ".kts", ".md", ".properties", ".json", ".yml", ".yaml", ".toml",
        )
    }
}
