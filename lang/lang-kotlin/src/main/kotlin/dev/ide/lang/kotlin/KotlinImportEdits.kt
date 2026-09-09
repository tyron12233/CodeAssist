package dev.ide.lang.kotlin

import dev.ide.lang.imports.ImportLayout
import dev.ide.lang.imports.ImportLayout.ImportEntry
import dev.ide.lang.imports.ImportLayout.PositionedImport
import org.jetbrains.kotlin.psi.KtFile

/**
 * Where a new `import` directive is spliced into a Kotlin file, and how the file's imports are read back for
 * the "Optimize Imports" command. Both the analyzer (the unresolved-reference quick-fix) and completion
 * (auto-import when an unimported symbol is accepted) add imports through [planImport], which places the new
 * directive in the correct alphabetical position (Kotlin uses one sorted block) via the language-neutral
 * [ImportLayout] rather than appending it. A first import lands one blank line after the package directive.
 */
object KotlinImportEdits {

    /** Kotlin uses a single sorted block (no static-import concept). */
    val CONFIG = ImportLayout.ImportLayoutConfig.KOTLIN

    /** Render one [ImportEntry] as a Kotlin `import` directive (no trailing newline). */
    fun render(entry: ImportEntry): String {
        val path = if (entry.isWildcard) "${entry.fqn}.*" else entry.fqn
        return if (entry.alias != null) "import $path as ${entry.alias}" else "import $path"
    }

    /** The file's existing imports as positioned entries (directive start → just past its line's newline). */
    fun existingImports(ktFile: KtFile): List<PositionedImport> {
        val text = ktFile.text
        return ktFile.importDirectives.mapNotNull { imp ->
            val fq = imp.importedFqName?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val entry = ImportEntry(fq, isStatic = false, isWildcard = imp.isAllUnder, alias = imp.aliasName)
            PositionedImport(entry, imp.textRange.startOffset, lineEndAfter(text, imp.textRange.endOffset))
        }
    }

    /** Offset just past the newline of the package directive's line, or null when there is no package line. */
    fun packageLineEnd(ktFile: KtFile): Int? {
        val pkg = ktFile.packageDirective?.takeIf { it.textLength > 0 && it.text.isNotBlank() } ?: return null
        return lineEndAfter(ktFile.text, pkg.textRange.endOffset)
    }

    /** Plan splicing `import <fqn>` in sorted position, or null when it is already imported. */
    fun planImport(ktFile: KtFile, fqn: String): ImportLayout.InsertPlan? =
        ImportLayout.planInsert(
            entry = ImportEntry(fqn),
            existing = existingImports(ktFile),
            config = CONFIG,
            text = ktFile.text,
            packageLineEnd = packageLineEnd(ktFile),
            render = ::render,
        )

    /** Advance [from] past the rest of its line and its trailing newline (clamped to the end of [text]). */
    private fun lineEndAfter(text: CharSequence, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        if (i < text.length) i++
        return i
    }
}
