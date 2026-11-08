package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.lang.imports.ImportLayout
import dev.ide.lang.imports.ImportLayout.ImportEntry
import dev.ide.lang.imports.ImportLayout.PositionedImport

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
    fun existingImports(ktFile: KtFile): List<PositionedImport> = existingImports(ktFile, ktFile.text)

    private fun existingImports(ktFile: KtFile, text: CharSequence): List<PositionedImport> {
        return ktFile.importDirectives.mapNotNull { imp ->
            val fq = imp.importedFqName?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val entry = ImportEntry(fq, isStatic = false, isWildcard = imp.isAllUnder, alias = imp.aliasName)
            PositionedImport(entry, imp.textRange.startOffset, lineEndAfter(text, imp.textRange.endOffset))
        }
    }

    /** Offset just past the newline of the package directive's line, or null when there is no package line. */
    fun packageLineEnd(ktFile: KtFile): Int? = packageLineEnd(ktFile, ktFile.text)

    private fun packageLineEnd(ktFile: KtFile, text: CharSequence): Int? {
        val pkg = ktFile.packageDirective?.takeIf { it.textLength > 0 && it.text.isNotBlank() } ?: return null
        return lineEndAfter(text, pkg.textRange.endOffset)
    }

    /** Plan splicing `import <fqn>` in sorted position, or null when it is already imported. */
    fun planImport(ktFile: KtFile, fqn: String): ImportLayout.InsertPlan? = Planner(ktFile).plan(fqn)

    /**
     * Plans imports into one unchanging [ktFile], reading its text, imports and package line once. Completion
     * plans an import for every unimported candidate on every keystroke; doing that through [planImport]
     * re-read every import directive (and the whole text, three times) per candidate.
     */
    class Planner(private val ktFile: KtFile) {
        private val text: CharSequence by lazy { ktFile.text }
        private val existing: List<PositionedImport> by lazy { existingImports(ktFile, text) }
        private val packageLineEnd: Int? by lazy { packageLineEnd(ktFile, text) }

        fun plan(fqn: String): ImportLayout.InsertPlan? =
            ImportLayout.planInsert(
                entry = ImportEntry(fqn),
                existing = existing,
                config = CONFIG,
                text = text,
                packageLineEnd = packageLineEnd,
                render = ::render,
            )
    }

    /** Advance [from] past the rest of its line and its trailing newline (clamped to the end of [text]). */
    private fun lineEndAfter(text: CharSequence, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        if (i < text.length) i++
        return i
    }
}
