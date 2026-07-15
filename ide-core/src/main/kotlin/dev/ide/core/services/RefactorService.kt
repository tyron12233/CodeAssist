package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.core.RenameInfo
import dev.ide.core.RenameOutcome
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.lang.jdt.rename.JdtRename
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * WORKSPACE-scoped engine service: the Java rename refactoring, carved out of [dev.ide.core.IdeServices].
 * Resolves the symbol under the caret (a JDT binding key), finds every reference across the project's `.java`
 * files, applies a multi-file edit to disk + the editor overlay, renames the backing file when a public
 * top-level type's name matched it, and fires ONE batched mutation event so the invalidation/reindex runs
 * exactly once. File/package move/copy/delete are a separate concern and stay on the engine.
 */
internal class RefactorService(private val ctx: EngineContext) {

    /** The rename target under [offset] in [file]'s buffer (old name + kind), or null when not renameable. */
    fun prepareRename(file: Path, text: String, offset: Int): RenameInfo? {
        if (!file.toString().endsWith(".java") || ctx.analysisDisabled(file)) return null
        val module = ctx.moduleForFile(file) ?: return null
        val analyzer = ctx.analyzerFor(module, LanguageId("java")) as? JdtSourceAnalyzer ?: return null
        return try {
            JdtRename.targetAt(analyzer.parse(ctx.store.vfs.fileFor(file), text), offset)
                ?.let { RenameInfo(it.oldName, it.kind) }
        } catch (e: LinkageError) {
            ctx.markAnalysisUnavailable(ctx.languageFor(file)); null
        }
    }

    /**
     * Rename the symbol under the caret to [newName] across the whole project: resolve the target, find every
     * reference (binding-key match; a file-local symbol stays in its own file, otherwise project `.java` files
     * are pre-filtered by name then parsed), then apply a multi-file edit to disk + the editor overlay. When a
     * top-level public type whose name matches its file is renamed, the backing `.java` file is renamed too
     * (its new path is returned so the editor can reopen it). Re-indexes and invalidates analyzers.
     */
    suspend fun rename(file: Path, text: String, offset: Int, newName: String): RenameOutcome {
        val name = newName.trim()
        if (!ctx.isValidJavaIdentifier(name)) return RenameOutcome(false, "'$newName' is not a valid Java identifier.")
        if (ctx.analysisDisabled(file)) return RenameOutcome(false, "Java analysis is unavailable.")
        val module = ctx.moduleForFile(file) ?: return RenameOutcome(false, "This file is not in a source module.")
        val analyzer = ctx.analyzerFor(module, LanguageId("java")) as? JdtSourceAnalyzer
            ?: return RenameOutcome(false, "Rename is only supported for Java.")
        val fileAbs = file.toAbsolutePath().normalize()

        val target: dev.ide.lang.jdt.rename.RenameTarget
        val editsByPath = LinkedHashMap<Path, List<DocumentEdit>>()
        var occurrences = 0
        try {
            val parsed = analyzer.parse(ctx.store.vfs.fileFor(file), text)
            target = JdtRename.targetAt(parsed, offset)
                ?: return RenameOutcome(false, "Place the caret on a symbol to rename.")
            if (name == target.oldName) return RenameOutcome(false, "The new name is the same as the current one.")

            fun editsFor(ranges: List<TextRange>) = ranges.map { DocumentEdit(it.start, it.end - it.start, name) }
            if (target.fileLocal) {
                val ranges = JdtRename.referencesIn(parsed, target)
                if (ranges.isNotEmpty()) {
                    editsByPath[fileAbs] = editsFor(ranges); occurrences += ranges.size
                }
            } else {
                for (cand in ctx.projectJavaFiles()) {
                    val candAbs = cand.toAbsolutePath().normalize()
                    val candText = ctx.overlayText(candAbs) ?: runCatching { cand.readText() }.getOrNull() ?: continue
                    if (!candText.contains(target.oldName)) continue // cheap pre-filter before the binding parse
                    val candParsed = if (candAbs == fileAbs) parsed else {
                        val m = ctx.moduleForFile(cand) ?: continue
                        val a = ctx.analyzerFor(m, LanguageId("java")) as? JdtSourceAnalyzer ?: continue
                        a.parse(ctx.store.vfs.fileFor(cand), candText)
                    }
                    val ranges = JdtRename.referencesIn(candParsed, target)
                    if (ranges.isNotEmpty()) {
                        editsByPath[candAbs] = editsFor(ranges); occurrences += ranges.size
                    }
                }
            }
        } catch (e: LinkageError) {
            ctx.markAnalysisUnavailable(ctx.languageFor(file))
            return RenameOutcome(false, "Java analysis is unavailable.")
        }
        if (editsByPath.isEmpty()) return RenameOutcome(false, "No occurrences of '${target.oldName}' found.")

        // Apply each file's edits descending (offsets stay valid), writing disk + the live overlay together.
        for ((path, edits) in editsByPath) {
            val current = ctx.overlayText(path) ?: runCatching { path.readText() }.getOrNull() ?: continue
            val sb = StringBuilder(current)
            for (e in edits.sortedByDescending { it.offset }) {
                val s = e.offset.coerceIn(0, sb.length)
                val en = (e.offset + e.oldLength).coerceIn(s, sb.length)
                sb.replace(s, en, e.newText.toString())
            }
            val updated = sb.toString()
            ctx.updateDocument(path, updated)
            runCatching { path.parent?.let { Files.createDirectories(it) }; path.writeText(updated) }
        }

        // Rename the backing file when a top-level public type's name matched the file name (Java convention).
        var newPath: Path? = null
        if (target.isType && fileAbs.fileName?.toString() == "${target.oldName}.java") {
            val dest = fileAbs.resolveSibling("$name.java")
            if (!Files.exists(dest)) runCatching {
                Files.move(fileAbs, dest)
                ctx.removeOverlay(fileAbs)?.let { ctx.updateDocument(dest, it) }
                newPath = dest
            }
        }

        // One batched mutation event: the hub coalesces the reaction (a multi-file edit / file rename
        // invalidates analyzers + synthetics and re-syncs the index exactly once).
        ctx.events.filesMutated(editsByPath.keys.toList(), newPath?.let { fileAbs to it })
        return RenameOutcome(
            true, "Renamed '${target.oldName}' to '$name'", occurrences, editsByPath.size, newPath?.toString()
        )
    }
}
