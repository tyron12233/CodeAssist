package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.core.runSync
import dev.ide.core.settings.CodeStyleSamples
import dev.ide.core.settings.CodeStyleSettings
import dev.ide.lang.LanguageId
import dev.ide.model.LanguageLevel
import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.jdt.formatting.JdtFormattingService
import dev.ide.lang.kotlin.KotlinFormatter
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.resolve.StructureItem
import java.nio.file.Path

/**
 * WORKSPACE-scoped engine service: the on-demand editor language features that are thin delegations to a
 * file's language analyzer — folding, formatting, the breadcrumb, the structure/outline, and quick-doc.
 * Carved out of [dev.ide.core.IdeServices]; the completion + diagnostics pipelines stay on the engine (they
 * are not simple delegations). Everything shared is reached through [EngineContext]: the per-(module,
 * language) analyzer ([EngineContext.analyzerFor]), file→language routing, the live overlay push, and the
 * incremental reparse of the live buffer.
 */
internal class LanguageFeatureService(private val ctx: EngineContext) {

    private companion object {
        /** Declaration kinds that form a breadcrumb chain (enclosing types + the enclosing method/constructor). */
        val BREADCRUMB_KINDS = setOf(
            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.RECORD,
            SymbolKind.ANNOTATION_TYPE, SymbolKind.METHOD, SymbolKind.CONSTRUCTOR,
        )
    }

    /** Foldable regions for [file]'s live buffer — imports, type/function bodies, block comments. */
    fun codeFolds(file: Path, text: String): List<FoldRegion> {
        val module = ctx.moduleForEditableFile(file) ?: return emptyList()
        ctx.updateDocument(file, text) // the live buffer feeds the analyzer's overlay
        val analyzer = ctx.analyzerFor(module, ctx.languageFor(file))
        val service = analyzer.folding ?: return emptyList()
        val vf = ctx.store.vfs.fileFor(file)
        // Refresh the analyzer's parse of the live buffer (the folder reads the last parse).
        ctx.refreshParse(analyzer, file, text)
        return runCatching { runSync { service.folds(vf) } }.getOrDefault(emptyList())
    }

    /** Reformat the whole live buffer of [file] to [style]; minimal edits, or empty if the language has no
     *  formatter / the buffer is already formatted / can't be safely formatted. */
    fun formatDocument(file: Path, text: String, style: FormatStyle): List<DocumentEdit> {
        val service = formattingServiceFor(file) ?: return emptyList()
        val vf = ctx.store.vfs.fileFor(file)
        return runCatching { runSync { service.format(vf, text, style) } }.getOrDefault(emptyList())
    }

    /** Reformat only the text overlapping `[start, end)` of [file]'s live buffer. */
    fun formatRange(file: Path, text: String, start: Int, end: Int, style: FormatStyle): List<DocumentEdit> {
        val service = formattingServiceFor(file) ?: return emptyList()
        val vf = ctx.store.vfs.fileFor(file)
        val range = TextRange(start, end)
        return runCatching { runSync { service.formatRange(vf, text, range, style) } }.getOrDefault(emptyList())
    }

    private fun formattingServiceFor(file: Path): FormattingService? {
        val module = ctx.moduleForEditableFile(file) ?: return null
        ctx.analyzerFor(module, ctx.languageFor(file)).formatting?.let { return it }
        // The IntelliJ-PSI Java backend (now the `.java` editor default) ships no formatter; reuse JDT's proven
        // CodeFormatter for `.java` — the same engine the Code Style preview uses — at the module's compliance.
        if (file.fileName.toString().endsWith(".java"))
            return JdtFormattingService(jdtComplianceOf(module.languageLevel))
        return null
    }

    private fun jdtComplianceOf(level: LanguageLevel): String = when (level) {
        LanguageLevel.JAVA_8 -> "1.8"
        LanguageLevel.JAVA_11 -> "11"
        LanguageLevel.JAVA_17 -> "17"
        LanguageLevel.JAVA_21 -> "21"
    }

    /** Format the built-in code sample for [languageId] with [style] and return the result — for the Code
     *  Style screen's live preview. Module-independent: it builds a standalone formatter, so it works with no
     *  file open. Returns the sample unchanged if formatting fails. */
    fun formatStylePreview(languageId: String, style: FormatStyle): String {
        val sample = CodeStyleSamples.forLanguage(languageId)
        val edits = runCatching {
            when (languageId) {
                CodeStyleSettings.LANG_KOTLIN ->
                    KotlinFormatter.reformat("Preview.kt", sample, style, 0, sample.length)
                // A high default compliance so newer Java syntax in the sample parses; not tied to any module.
                else -> JdtFormattingService("17").formatText(sample, style)
            }
        }.getOrDefault(emptyList())
        if (edits.isEmpty()) return sample
        val sb = StringBuilder(sample)
        for (e in edits.sortedByDescending { it.offset }) {
            val s = e.offset.coerceIn(0, sb.length)
            val en = (e.offset + e.oldLength).coerceIn(s, sb.length)
            sb.replace(s, en, e.newText.toString())
        }
        return sb.toString()
    }

    /** Enclosing declarations (type/method names, outer→inner) at [offset] in [file]'s buffer — for the
     *  cursor-tracking breadcrumb. Empty if the file is outside the project or not JDT-backed. */
    fun breadcrumbAt(file: Path, text: String, offset: Int): List<String> {
        val module = ctx.moduleForFile(file) ?: return emptyList()
        ctx.updateDocument(file, text)
        // Backend-neutral: derive the enclosing type/method chain from the SPI `fileStructure` (both the JDT
        // and IntelliJ-PSI Java backends implement it), so this isn't tied to a concrete analyzer type.
        val analyzer = ctx.analyzerFor(module, ctx.languageFor(file))
        return runCatching {
            analyzer.fileStructure(ctx.store.vfs.fileFor(file), text)
                .filter { it.kind in BREADCRUMB_KINDS && offset in it.nameOffset..it.endOffset }
                .sortedBy { it.depth }
                .map { it.name }
        }.getOrDefault(emptyList())
    }

    /** The file's declarations (for the structure/outline view + sticky scroll headers), via the language
     *  analyzer's structure walk. Empty if the file is outside the project or the backend doesn't support it. */
    fun fileStructure(file: Path, text: String): List<StructureItem> {
        val module = ctx.moduleForEditableFile(file) ?: return emptyList()
        val analyzer = ctx.analyzerFor(module, ctx.languageFor(file))
        val vf = ctx.store.vfs.fileFor(file)
        return runCatching { analyzer.fileStructure(vf, text) }.getOrDefault(emptyList())
    }

    /** Quick documentation (signature + doc comment) for the symbol at [offset] in [file]'s buffer, or null.
     *  Resolves through the live overlay so cross-file references reach their declaration. */
    fun quickDocAt(file: Path, text: String, offset: Int): QuickDocInfo? {
        val module = ctx.moduleForEditableFile(file) ?: return null
        ctx.updateDocument(file, text)
        val analyzer = ctx.analyzerFor(module, ctx.languageFor(file))
        val vf = ctx.store.vfs.fileFor(file)
        return runCatching { runSync { analyzer.quickDoc(vf, text, offset) } }.getOrNull()
    }
}
