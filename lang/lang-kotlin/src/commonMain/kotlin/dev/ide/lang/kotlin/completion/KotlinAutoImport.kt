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
    // An import under another name (`import a.B as C`) does not make `B` visible by its own name.
    private val explicitImports = fileContext.imports
        .filter { !it.isStar && (it.alias == null || it.alias == it.fqn.substringAfterLast('.')) }
        .map { it.fqn }.toHashSet()
    private val planner = KotlinImportEdits.Planner(kt)

    private val ownPackage = fileContext.packageName
    /** The names this file declares at top level: a package scope symbol of one of them is this file's. */
    private val ownTopLevel: Set<String> by lazy { kt.declarations.mapNotNullTo(HashSet()) { (it as? KtNamedDeclaration)?.name } }
    private val starPackages = fileContext.imports.filter { it.isStar }.map { it.packageName }.toHashSet()
    /** The packages something is imported from by name: their other declarations are "sibling imported". */
    private val explicitImportPackages = fileContext.imports.filter { !it.isStar }.map { it.packageName }.toHashSet()
    /** What the file imports under another name (`import a.B as C`): the original name is hidden in this file. */
    private val aliasedAway = fileContext.imports
        .filter { !it.isStar && it.alias != null && it.alias != it.fqn.substringAfterLast('.') }
        .map { it.fqn }.toHashSet()

    /**
     * IntelliJ's `NotImportedWeigher` tiers (its `ImportableFqNameClassifier`) for [fqn], a declaration whose
     * acceptance adds an import: 0 a sibling of something already imported by name (same package), 1 not
     * imported, 2 not to be used by this name (the file imports it under an alias).
     */
    fun notImportedTier(fqn: String): Int = when {
        fqn in aliasedAway -> 2
        fqn.substringBeforeLast('.', "") in explicitImportPackages -> 0
        else -> 1
    }

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
        // A name the file imports under an alias is not visible by its own name, star imports notwithstanding.
        if ((pkg in visiblePackages && fqn !in aliasedAway) || fqn in explicitImports) return emptyList()
        val plan = planner.plan(fqn) ?: return emptyList()
        return listOf(TextEdit(TextRange(plan.offset, plan.offset), plan.text))
    }
}
