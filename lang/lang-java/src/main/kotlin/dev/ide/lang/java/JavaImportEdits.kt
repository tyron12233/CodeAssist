package dev.ide.lang.java

import dev.ide.lang.imports.ImportLayout
import dev.ide.lang.imports.ImportLayout.ImportEntry
import dev.ide.lang.imports.ImportLayout.PositionedImport
import com.intellij.psi.PsiJavaFile

/**
 * Where a new `import` is spliced into a Java file, and how the file's imports are read back for
 * "Optimize Imports". Completion (auto-import) and the unresolved-reference quick-fix add imports through
 * [planImport], which places the directive in sorted position (regular block, static block split off) via the
 * language-neutral [ImportLayout] rather than appending it. A first import lands one blank line after the
 * package statement. Works over the IntelliJ-PSI [PsiJavaFile] the editor backend parses.
 */
object JavaImportEdits {

    /** IntelliJ's default Java scheme: regular block, then a blank line, then the static block. */
    val CONFIG = ImportLayout.ImportLayoutConfig.JAVA

    /** Render one [ImportEntry] as a Java `import` statement (no trailing newline). */
    fun render(entry: ImportEntry): String {
        val static = if (entry.isStatic) "static " else ""
        val path = if (entry.isWildcard) "${entry.fqn}.*" else entry.fqn
        return "import $static$path;"
    }

    /** The file's existing imports as positioned entries (statement start → just past its line's newline). */
    fun existingImports(psi: PsiJavaFile): List<PositionedImport> {
        val text = psi.text
        val list = psi.importList ?: return emptyList()
        val out = ArrayList<PositionedImport>()
        list.importStatements.forEach { imp ->
            val fqn = imp.qualifiedName?.takeIf { it.isNotBlank() } ?: return@forEach
            out += PositionedImport(
                ImportEntry(fqn, isStatic = false, isWildcard = imp.isOnDemand),
                imp.textRange.startOffset, lineEndAfter(text, imp.textRange.endOffset),
            )
        }
        list.importStaticStatements.forEach { imp ->
            val fqn = imp.importReference?.qualifiedName?.takeIf { it.isNotBlank() } ?: return@forEach
            out += PositionedImport(
                ImportEntry(fqn, isStatic = true, isWildcard = imp.isOnDemand),
                imp.textRange.startOffset, lineEndAfter(text, imp.textRange.endOffset),
            )
        }
        return out.sortedBy { it.start }
    }

    /** Offset just past the newline of the package statement's line, or null when there is none. */
    fun packageLineEnd(psi: PsiJavaFile): Int? {
        val pkg = psi.packageStatement ?: return null
        return lineEndAfter(psi.text, pkg.textRange.endOffset)
    }

    /** Plan splicing `import <fqn>;` in sorted position, or null when it is already imported (or covered by an
     *  on-demand import of its package). */
    fun planImport(psi: PsiJavaFile, fqn: String): ImportLayout.InsertPlan? {
        // An on-demand import of the package already makes the type visible — nothing to add.
        val pkg = fqn.substringBeforeLast('.', "")
        if (existingImports(psi).any { it.entry.isWildcard && !it.entry.isStatic && it.entry.fqn == pkg }) return null
        return ImportLayout.planInsert(
            entry = ImportEntry(fqn),
            existing = existingImports(psi),
            config = CONFIG,
            text = psi.text,
            packageLineEnd = packageLineEnd(psi),
            render = ::render,
        )
    }

    private fun lineEndAfter(text: CharSequence, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        if (i < text.length) i++
        return i
    }
}
