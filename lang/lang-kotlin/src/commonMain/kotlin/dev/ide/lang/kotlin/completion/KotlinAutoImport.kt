package dev.ide.lang.kotlin.completion

import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.KotlinImportEdits
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.FileContext

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
    private val planner = KotlinImportEdits.Planner(kt)

    private val ownPackage = fileContext.packageName
    /** The names this file declares at top level: a package scope symbol of one of them is this file's. */
    private val ownTopLevel: Set<String> by lazy { kt.declarations.mapNotNullTo(HashSet()) { (it as? KtNamedDeclaration)?.name } }
    private val starPackages = fileContext.imports.filter { it.isStar }.map { it.packageName }.toHashSet()

    /**
     * The scope a top-level callable `pkg.name` is visible through, nearest first as IntelliJ orders them: 0
     * an explicit import, 1 this file, 2 another file of its package, 3 a star import, 4 the default imports,
     * 5 none (accepting it adds an import).
     */
    fun scopeOf(pkg: String, name: String, fromSource: Boolean): Int = when {
        (if (pkg.isEmpty()) name else "$pkg.$name") in explicitImports -> 0
        pkg == ownPackage -> if (fromSource && name in ownTopLevel) 1 else 2
        pkg in starPackages -> 3
        pkg in DefaultImports.STAR_PACKAGES -> 4
        else -> 5
    }

    /** The `import <fqn>` edit, spliced in sorted position, or no edit when [fqn] is already visible (same
     *  package, a star import, or an explicit import) or is unqualified. */
    fun editForType(fqn: String): List<TextEdit> {
        if ('.' !in fqn) return emptyList()
        val pkg = fqn.substringBeforeLast('.')
        if (pkg in visiblePackages || fqn in explicitImports) return emptyList()
        val plan = planner.plan(fqn) ?: return emptyList()
        return listOf(TextEdit(TextRange(plan.offset, plan.offset), plan.text))
    }
}
