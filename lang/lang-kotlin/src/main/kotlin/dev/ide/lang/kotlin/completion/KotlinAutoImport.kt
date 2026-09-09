package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.KotlinImportEdits
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.FileContext
import org.jetbrains.kotlin.psi.KtFile

/**
 * Computes the auto-import [TextEdit] for accepting an unimported type/callable in [kt]. Captures the file's
 * already-visible packages, its explicit imports, and the import insertion anchor once, so each candidate's
 * edit is a cheap lookup. The symbol-to-FQN decision stays in [KotlinCompletion] (it is completion-specific);
 * this only answers "is this FQN already visible, and if not, where does its `import` go?".
 */
internal class KotlinAutoImport(private val kt: KtFile, fileContext: FileContext) {

    private val visiblePackages = (DefaultImports.STAR_PACKAGES + fileContext.packageName +
        fileContext.imports.filter { it.isStar }.map { it.packageName }).toHashSet()
    private val explicitImports = fileContext.imports.filter { !it.isStar }.map { it.fqn }.toHashSet()

    /** The `import <fqn>` edit, spliced in sorted position, or no edit when [fqn] is already visible (same
     *  package, a star import, or an explicit import) or is unqualified. */
    fun editForType(fqn: String): List<TextEdit> {
        if ('.' !in fqn) return emptyList()
        val pkg = fqn.substringBeforeLast('.')
        if (pkg in visiblePackages || fqn in explicitImports) return emptyList()
        val plan = KotlinImportEdits.planImport(kt, fqn) ?: return emptyList()
        return listOf(TextEdit(TextRange(plan.offset, plan.offset), plan.text))
    }
}
