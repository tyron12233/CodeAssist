package dev.ide.core.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Codes
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.FileAnalyzer
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.java.JavaPackageRewrite
import dev.ide.lang.kotlin.KotlinPackageRewrite
import dev.ide.model.ContentRole
import dev.ide.model.Module
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Inspection: a Java/Kotlin source file whose `package` directive does not match its location under the
 * source root (IntelliJ's "package does not correspond to file path"). The expected package is the file's
 * directory relativized against the most-specific SOURCE content root of its module; a file outside every
 * source root is not judged. Ships a "Set package to '…'" quick-fix that rewrites the directive to match —
 * the complement of the move/copy path, which rewrites the package when the file changes directory.
 *
 * SYNTAX tier: it needs only the file's path, the module's source roots, and a cheap read of the declared
 * package (a line regex, not a re-parse), so it runs on every keystroke without the resolver. `.kts` scripts
 * are skipped (they legitimately carry no package). Registered as a first-class analyzer, so it appears in
 * the Analysis settings inspection list with an enable toggle and severity override.
 */
class PackageMismatchAnalyzer : FileAnalyzer {
    override val id = AnalyzerId("packageMismatch")
    override val displayName = "Package does not match file location"
    override val languages = setOf(LanguageId("java"), LanguageId("kotlin"))
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.SYNTAX
    override val interestedIn: Set<NodeKind>? = null // whole-file, invoked once

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        val path = runCatching { Paths.get(target.file.path) }.getOrNull() ?: return
        val dir = path.parent ?: return
        // Only real Java/Kotlin sources — not `.kts` scripts (build/settings files carry no package).
        val isKotlin = when (path.fileName.toString().substringAfterLast('.', "")) {
            "kt" -> true
            "java" -> false
            else -> return
        }
        val expected = expectedPackage(target.module, dir) ?: return // outside every source root → don't judge

        val text = target.parsed.text()
        val match = PACKAGE_LINE.find(text)
        val declared = match?.groupValues?.get(1) ?: ""
        if (declared == expected) return

        val range = match?.let { TextRange(it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1) }
            ?: firstLineRange(text)
        val message = when {
            declared.isEmpty() -> "Missing package directive; the file location expects package '$expected'"
            expected.isEmpty() ->
                "Package directive '$declared' does not match the file location; it should be in the default package"
            else -> "Package directive '$declared' does not match the file location; expected '$expected'"
        }
        sink.report(range, defaultSeverity, message, Codes.PACKAGE_MISMATCH, listOf(SetPackageFix(expected, isKotlin, path.fileName.toString())))
    }

    /**
     * The package the directory [dir] maps to under [module]'s most-specific SOURCE content root: `""` for the
     * root itself (default package), or null when [dir] sits under no source root (so the package can't be
     * derived). Mirrors `IdeServices.packageForDir`, scoped to the file's own module and to SOURCE roots only
     * (generated roots are never nagged).
     */
    private fun expectedPackage(module: Module, dir: Path): String? {
        val d = dir.toAbsolutePath().normalize()
        val root = module.sourceSets.asSequence()
            .flatMap { it.contentRoots.asSequence() }
            .filter { ContentRole.SOURCE in it.roles }
            .map { Paths.get(it.dir.path).toAbsolutePath().normalize() }
            .filter { d == it || d.startsWith(it) }
            .maxByOrNull { it.nameCount } ?: return null
        if (d == root) return ""
        val rel = root.relativize(d)
        return (0 until rel.nameCount).joinToString(".") { rel.getName(it).toString() }
    }

    private fun firstLineRange(text: CharSequence): TextRange {
        if (text.isEmpty()) return TextRange(0, 0)
        val nl = text.indexOf('\n')
        return TextRange(0, if (nl < 0) text.length else nl)
    }

    private companion object {
        // The declared package name on its own line — matches both Java (`package a.b.c;`) and Kotlin
        // (`package a.b.c`); the trailing semicolon is ignored, only the dotted name is captured.
        val PACKAGE_LINE = Regex("""(?m)^[ \t]*package[ \t]+([\w.]+)""")
    }
}

/**
 * Sets the file's package directive to [expected] (`""` = the default package). Recomputes the edit against
 * the live buffer at apply time via the language's package-text rewriter, so a stale snapshot is never
 * applied. A single minimal replace — the changed span only — keeps the caret and undo history intact.
 */
private class SetPackageFix(
    private val expected: String,
    private val isKotlin: Boolean,
    private val fileName: String,
) : QuickFix {
    override val title = if (expected.isEmpty()) "Remove package directive" else "Set package to '$expected'"
    override val kind = CodeActionKind.QUICK_FIX

    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
        val text = ctx.target.parsed.text().toString()
        val updated = (if (isKotlin) KotlinPackageRewrite.rewrite(fileName, text, expected)?.updatedText
        else JavaPackageRewrite.rewrite(text, expected)?.updatedText) ?: return WorkspaceEdit.EMPTY
        val edit = minimalReplace(text, updated) ?: return WorkspaceEdit.EMPTY
        return WorkspaceEdit.of(ctx.target.file, edit)
    }

    /** The single [DocumentEdit] that turns [old] into [new] by replacing only their differing middle span. */
    private fun minimalReplace(old: String, new: String): DocumentEdit? {
        if (old == new) return null
        val min = minOf(old.length, new.length)
        var start = 0
        while (start < min && old[start] == new[start]) start++
        var endOld = old.length
        var endNew = new.length
        while (endOld > start && endNew > start && old[endOld - 1] == new[endNew - 1]) {
            endOld--; endNew--
        }
        return DocumentEdit(start, endOld - start, new.substring(start, endNew))
    }
}
